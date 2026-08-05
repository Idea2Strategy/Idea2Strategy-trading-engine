package com.idea2strategy.trading.worker.corporateaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.trading.application.corporateaction.CorporateActionApplicationResult;
import com.idea2strategy.trading.application.corporateaction.CorporateActionConflictException;
import com.idea2strategy.trading.application.corporateaction.CorporateActionService;
import com.idea2strategy.trading.application.port.BotEventStore;
import com.idea2strategy.trading.domain.corporateaction.ApprovedCorporateAction;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApplication;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionApprovalStatus;
import com.idea2strategy.trading.domain.corporateaction.CorporateActionType;
import com.idea2strategy.trading.domain.event.BotEvent;
import com.idea2strategy.trading.domain.event.BotEventAppend;
import com.idea2strategy.trading.domain.event.BotEventType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F92's initiation path: reads the market data owner's approved corporate actions from canonical
 * and applies each one to every bot holding the instrument, through the F13 application path.
 *
 * <p>The approval evidence is D's, not ours. {@code contract.operations.corporate-action-approval.v1}
 * fixes that only the backend Admin MCP result can approve a researched action, that the pipeline
 * verifies actor, audit, permission, schema version and content hash fail-closed, and that the
 * verified outcome is recorded in the canonical row's {@code terms_document.review}. This poller
 * therefore treats {@code review.state = 'APPROVED'} as the official decision and never re-derives
 * it — the same division of authority the F13 store already applies to the terms themselves.
 *
 * <p>What this poller adds on top of that record is F's own refusal to guess:
 *
 * <ul>
 *   <li>an action is picked up only once its {@code effective_at} has passed and its source
 *       manifest is {@code AVAILABLE} — applying a split before its effective time would
 *       re-denominate today's lots with tomorrow's ratio;
 *   <li>{@code review.decided_content_hash} must equal the row's {@code terms_hash}, so the content
 *       the administrator approved is the content being applied (the F13 store re-checks the same
 *       equality as {@code evidenceDigest});
 *   <li>an action type the execution engine has no arithmetic for is recorded as rejected rather
 *       than silently skipped, because a silently missed split corrupts every valuation after it;
 *   <li>a superseding approval whose superseded predecessor already moved lots is recorded as
 *       rejected — canonical has no compensating-movement path, so converging that state is an
 *       operator decision, not something this worker may improvise.
 * </ul>
 *
 * <p>One action is applied in one transaction across all its bots: the per-bot official event,
 * the lot movements and the completion fact commit together, so a crash leaves either nothing or
 * the completed application, never a half-applied sweep. Redelivery converges: movement ids derive
 * from (action, lot, event), the store's replay path accepts the same official event, and the
 * completion fact inserts {@code on conflict do nothing}.
 *
 * <p>Deliberately out of scope, as F92 records: a withdrawal or supersede arriving <em>after</em>
 * this poller applied the prior approval stands as applied — the durable rejection fact and audit
 * event make the state visible instead of silently reversing official movements.
 */
public final class ApprovedCorporateActionPoller {

    public static final String APPLIED_TYPE = "CORPORATE_ACTION_APPLIED";
    public static final String APPLIED_SCHEMA = "corporate-action-applied.v1";
    public static final String REJECTED_TYPE = "CORPORATE_ACTION_APPLICATION_REJECTED";
    public static final String REJECTED_SCHEMA = "corporate-action-application-rejected.v1";

    private static final UUID SYSTEM_ACTOR = UUID.fromString("f9200000-0000-4000-8000-000000000001");
    private static final String SUPPORTED_ACTION_TYPE = CorporateActionType.SPLIT.name();
    private static final String AVAILABLE = "AVAILABLE";

    /**
     * Pending work is an approved, effective, unsuperseded action whose source dataset the data
     * owner confirmed and for which this worker holds neither a completion nor a rejection fact.
     * The facts are the durable receipt: without them a bot opening its first post-split lot next
     * week would have last week's ratio applied to a lot already priced after the split.
     */
    private static final String PENDING = """
            select action.id, action.instrument_id, action.action_type, action.effective_at,
                   action.terms_hash,
                   action.terms_document ->> 'actionType' as terms_action_type,
                   action.terms_document #>> '{ratio,to}' as ratio_to,
                   action.terms_document #>> '{ratio,from}' as ratio_from,
                   action.terms_document #>> '{review,state}' as review_state,
                   action.terms_document #>> '{review,decision}' as review_decision,
                   action.terms_document #>> '{review,decided_content_hash}' as decided_content_hash,
                   action.terms_document #>> '{review,actor_id}' as actor_id,
                   action.terms_document #>> '{review,audit_id}' as audit_id,
                   action.terms_document #>> '{review,decided_at}' as decided_at,
                   action.terms_document #>> '{review,request_schema_version}' as request_schema_version,
                   action.supersedes_action_id
            from market_data.corporate_actions action
            join market_data.dataset_manifests manifest
              on manifest.id = action.source_manifest_id
            where action.terms_document #>> '{review,state}' = 'APPROVED'
              and action.effective_at <= :now
              and cast(manifest.status as varchar) = :available
              and not exists (
                  select 1 from market_data.corporate_actions later
                  where later.supersedes_action_id = action.id
                    and later.terms_document #>> '{review,state}' = 'APPROVED')
              and not exists (
                  select 1 from operations.outbox_messages fact
                  where fact.owner_domain = 'trading'
                    and fact.aggregate_id = action.id
                    and fact.event_type in (:appliedType, :rejectedType))
            order by action.effective_at, action.id
            limit :batch
            """;

    private final JdbcClient jdbc;
    private final BotEventStore events;
    private final CorporateActionService service;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ApprovedCorporateActionPoller(
            JdbcClient jdbc,
            BotEventStore events,
            CorporateActionService service,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.events = Objects.requireNonNull(events, "events");
        this.service = Objects.requireNonNull(service, "service");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    /** Processes up to {@code batchSize} approved actions, returning the number applied. */
    public int pollOnce(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        List<PendingAction> pending = pendingActions(batchSize);
        int applied = 0;
        for (PendingAction action : pending) {
            try {
                ApprovedCorporateAction approved = approved(action);
                rejectSupersedeAfterApplication(action);
                transaction.executeWithoutResult(status -> applyToEveryBot(action, approved));
                applied++;
            } catch (PermanentFailure permanent) {
                recordRejection(action, permanent.reasonCode);
            } catch (CorporateActionConflictException conflict) {
                recordRejection(action, bounded("CANONICAL_EVIDENCE_CONFLICT:"
                        + conflict.getMessage()));
            }
            // Any other RuntimeException aborts this cycle: nothing durable was written for the
            // action, so the next poll retries it, and a persistent fault surfaces in the
            // scheduler's error channel instead of being recorded as a business refusal.
        }
        return applied;
    }

    private List<PendingAction> pendingActions(int batchSize) {
        return jdbc.sql(PENDING)
                .param("now", offset(clock.instant()))
                .param("available", AVAILABLE)
                .param("appliedType", APPLIED_TYPE)
                .param("rejectedType", REJECTED_TYPE)
                .param("batch", batchSize)
                .query((resultSet, rowNumber) -> new PendingAction(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("instrument_id", UUID.class),
                        resultSet.getString("action_type"),
                        resultSet.getObject("effective_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("terms_hash"),
                        resultSet.getString("terms_action_type"),
                        resultSet.getString("ratio_to"),
                        resultSet.getString("ratio_from"),
                        resultSet.getString("review_decision"),
                        resultSet.getString("decided_content_hash"),
                        resultSet.getString("actor_id"),
                        resultSet.getString("audit_id"),
                        resultSet.getString("decided_at"),
                        resultSet.getString("request_schema_version"),
                        resultSet.getObject("supersedes_action_id", UUID.class)))
                .list();
    }

    /**
     * Maps the canonical review record onto the domain's approval object, refusing on anything
     * the record cannot prove. The domain constructor re-validates ratio, hash shape and the
     * approval-precedes-effective rule, so a violation there is recorded under its own reason.
     */
    private ApprovedCorporateAction approved(PendingAction action) {
        if (!SUPPORTED_ACTION_TYPE.equals(action.actionType())
                || !SUPPORTED_ACTION_TYPE.equals(action.termsActionType())) {
            throw permanent("UNSUPPORTED_ACTION_TYPE");
        }
        if (!"APPROVE".equals(action.reviewDecision())) {
            throw permanent("REVIEW_DECISION_NOT_APPROVE");
        }
        if (action.decidedContentHash() == null
                || !action.decidedContentHash().equals(action.termsHash())) {
            throw permanent("STALE_CONTENT_HASH");
        }
        try {
            return new ApprovedCorporateAction(
                    action.actionId(),
                    action.instrumentId(),
                    CorporateActionType.SPLIT,
                    parseRatio(action.ratioTo(), "RATIO_TO"),
                    parseRatio(action.ratioFrom(), "RATIO_FROM"),
                    action.effectiveAt(),
                    CorporateActionApprovalStatus.APPROVED,
                    parseUuid(action.auditId(), "AUDIT_ID"),
                    parseUuid(action.actorId(), "ACTOR_ID"),
                    parseInstant(action.decidedAt()),
                    action.decidedContentHash(),
                    requireText(action.requestSchemaVersion(), "REQUEST_SCHEMA_VERSION"));
        } catch (IllegalArgumentException refused) {
            throw permanent(bounded("DOMAIN_REFUSED:" + refused.getMessage()));
        }
    }

    /**
     * A replacement approval whose predecessor already moved lots cannot be applied on top: the
     * lots carry the old ratio and canonical has no compensating movement to take it back out.
     */
    private void rejectSupersedeAfterApplication(PendingAction action) {
        if (action.supersedesActionId() == null) {
            return;
        }
        Integer priorMovements = jdbc.sql("""
                        select count(*) from trading.lot_movements
                        where corporate_action_id = :priorActionId
                        """)
                .param("priorActionId", action.supersedesActionId())
                .query(Integer.class)
                .single();
        if (priorMovements > 0) {
            throw permanent("SUPERSEDE_AFTER_APPLICATION");
        }
    }

    private void applyToEveryBot(PendingAction action, ApprovedCorporateAction approved) {
        List<UUID> bots = jdbc.sql("""
                        select distinct lot.bot_id
                        from trading.position_lots lot
                        join trading.position_lot_projections projection
                          on projection.position_lot_id = lot.id
                        where lot.instrument_id = :instrumentId
                          and projection.remaining_quantity > 0
                        order by lot.bot_id
                        """)
                .param("instrumentId", action.instrumentId())
                .query(UUID.class)
                .list();

        int adjustedLots = 0;
        for (UUID botId : bots) {
            BotEvent event = events.appendOrLoad(BotEventAppend.of(
                    botId, BotEventType.CORPORATE_ACTION_APPLIED, action.actionId().toString(),
                    parseUuid(action.auditId(), "AUDIT_ID"), action.effectiveAt(),
                    write(eventSummary(action))));
            CorporateActionApplicationResult result = service.apply(
                    new CorporateActionApplication(approved, botId, event.eventId()));
            adjustedLots += result.adjustedLots();
        }
        appendFact(action, APPLIED_TYPE, APPLIED_SCHEMA,
                appliedPayload(action, bots.size(), adjustedLots));
    }

    private ObjectNode eventSummary(PendingAction action) {
        return json.createObjectNode()
                .put("contractVersion", APPLIED_SCHEMA)
                .put("corporateActionId", action.actionId().toString())
                .put("instrumentId", action.instrumentId().toString())
                .put("actionType", action.actionType())
                .put("ratioTo", action.ratioTo())
                .put("ratioFrom", action.ratioFrom())
                .put("effectiveAt", action.effectiveAt().toString())
                .put("termsHash", action.termsHash())
                .put("approvalAuditId", action.auditId())
                .put("approvedByOperatorId", action.actorId())
                .put("decidedAt", action.decidedAt());
    }

    private ObjectNode appliedPayload(PendingAction action, int botCount, int adjustedLots) {
        return eventSummary(action)
                .put("botCount", botCount)
                .put("adjustedLots", adjustedLots)
                .put("appliedAt", clock.instant().toString());
    }

    private void recordRejection(PendingAction action, String reason) {
        transaction.executeWithoutResult(status -> {
            appendFact(action, REJECTED_TYPE, REJECTED_SCHEMA, eventSummary(action)
                    .put("reasonCode", reason)
                    .put("rejectedAt", clock.instant().toString()));
            jdbc.sql("""
                            insert into operations.audit_events (
                                id, actor_type, actor_id, action_type, target_domain, target_id,
                                reason_code, correlation_id, idempotency_key, before_hash,
                                occurred_at)
                            values (:id, 'SYSTEM', :actor, :actionType, 'trading', :target,
                                :reason, :correlation, :key, :termsHash, :at)
                            on conflict (idempotency_key) do nothing
                            """)
                    .param("id", stableId("audit:" + action.actionId() + ":" + reason))
                    .param("actor", SYSTEM_ACTOR)
                    .param("actionType", REJECTED_TYPE)
                    .param("target", action.actionId())
                    .param("reason", bounded(reason))
                    .param("correlation", action.actionId())
                    .param("key", REJECTED_TYPE + ":" + action.actionId())
                    .param("termsHash", action.termsHash())
                    .param("at", offset(clock.instant()))
                    .update();
        });
    }

    private void appendFact(PendingAction action, String eventType, String schema,
            ObjectNode payload) {
        jdbc.sql("""
                        insert into operations.outbox_messages (
                            id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                            event_schema_version, payload_document, idempotency_key,
                            producer_idempotency_key, created_at)
                        values (:id, 'trading', :aggregateId, 1, :eventType, :schemaVersion,
                            cast(:payload as jsonb), :idempotencyKey, :producerKey, :createdAt)
                        on conflict (idempotency_key) do nothing
                        """)
                .param("id", stableId(eventType + ":" + action.actionId()))
                .param("aggregateId", action.actionId())
                .param("eventType", eventType)
                .param("schemaVersion", schema)
                .param("payload", write(payload))
                .param("idempotencyKey", eventType + ":" + action.actionId())
                .param("producerKey", eventType + ":" + action.actionId())
                .param("createdAt", offset(clock.instant()))
                .update();
    }

    private static long parseRatio(String value, String field) {
        if (value == null) {
            throw permanent("MISSING_" + field);
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException notANumber) {
            throw permanent("INVALID_" + field);
        }
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw permanent("MISSING_REVIEW_" + field);
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException invalid) {
            throw permanent("INVALID_REVIEW_" + field);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw permanent("MISSING_REVIEW_DECIDED_AT");
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException invalid) {
            throw permanent("INVALID_REVIEW_DECIDED_AT");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw permanent("MISSING_REVIEW_" + field);
        }
        return value.strip();
    }

    private String write(JsonNode value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new IllegalStateException("unable to serialize corporate action fact",
                    impossible);
        }
    }

    private static String bounded(String reason) {
        return reason.substring(0, Math.min(reason.length(), 80));
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static PermanentFailure permanent(String reason) {
        return new PermanentFailure(reason);
    }

    private record PendingAction(
            UUID actionId, UUID instrumentId, String actionType, Instant effectiveAt,
            String termsHash, String termsActionType, String ratioTo, String ratioFrom,
            String reviewDecision, String decidedContentHash, String actorId, String auditId,
            String decidedAt, String requestSchemaVersion, UUID supersedesActionId) {}

    private static final class PermanentFailure extends RuntimeException {
        private final String reasonCode;

        private PermanentFailure(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }
    }
}
