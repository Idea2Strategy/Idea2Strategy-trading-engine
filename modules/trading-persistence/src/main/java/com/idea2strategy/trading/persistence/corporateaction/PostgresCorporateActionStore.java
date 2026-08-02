package com.idea2strategy.trading.persistence.corporateaction;

import com.idea2strategy.trading.application.corporateaction.CorporateActionApplicationResult;
import com.idea2strategy.trading.application.corporateaction.CorporateActionConflictException;
import com.idea2strategy.trading.application.port.CorporateActionStore;
import com.idea2strategy.trading.domain.corporateaction.ApprovedCorporateAction;
import com.idea2strategy.trading.domain.corporateaction.SplitAdjustment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class PostgresCorporateActionStore implements CorporateActionStore {
    public static final String NO_MONETARY_POSTING="NO_MONETARY_POSTING_COST_BASIS_PRESERVED";
    private final JdbcClient jdbc;private final TransactionTemplate transaction;
    public PostgresCorporateActionStore(JdbcClient jdbc,PlatformTransactionManager manager){this.jdbc=Objects.requireNonNull(jdbc,"jdbc");this.transaction=new TransactionTemplate(Objects.requireNonNull(manager,"manager"));}
    @Override public CorporateActionApplicationResult apply(ApprovedCorporateAction action){Objects.requireNonNull(action,"action");return transaction.execute(status->applyTx(action));}
    private CorporateActionApplicationResult applyTx(ApprovedCorporateAction a){
        String fp=fingerprint(a);Optional<Stored> prior=stored(a.actionId());if(prior.isPresent())return replay(prior.orElseThrow(),fp);
        int inserted=jdbc.sql("""
            insert into trading.execution_corporate_action_application(action_id,instrument_id,action_type,numerator,denominator,
                effective_at,approval_id,approved_by_operator_id,approved_at,evidence_digest,policy_version,request_fingerprint,
                adjusted_lots,adjusted_flow_positions,ledger_effect,applied_at)
            values(:id,:instrument,:type,:numerator,:denominator,:effective,:approval,:operator,:approved,:evidence,:policy,:fp,0,0,:ledger,current_timestamp)
            on conflict do nothing
            """).param("id",a.actionId()).param("instrument",a.instrumentId()).param("type",a.type().name())
            .param("numerator",a.numerator()).param("denominator",a.denominator()).param("effective",a.effectiveAt().atOffset(ZoneOffset.UTC))
            .param("approval",a.approvalId()).param("operator",a.approvedByOperatorId()).param("approved",a.approvedAt().atOffset(ZoneOffset.UTC))
            .param("evidence",a.evidenceDigest()).param("policy",a.policyVersion()).param("fp",fp).param("ledger",NO_MONETARY_POSTING).update();
        if(inserted==0)return replay(stored(a.actionId()).orElseThrow(()->conflict("corporate action identity conflict")),fp);
        List<LotProjection> lots=jdbc.sql("""
            select l.lot_id,p.remaining_quantity,p.remaining_cost_basis,p.version
            from trading.execution_position_lot l join trading.execution_position_lot_projection p on p.lot_id=l.lot_id
            where l.instrument_id=:instrument and p.remaining_quantity>0 order by l.lot_id for update of p
            """).param("instrument",a.instrumentId()).query((rs,row)->new LotProjection(rs.getObject(1,UUID.class),rs.getBigDecimal(2),rs.getBigDecimal(3),rs.getLong(4))).list();
        for(LotProjection lot:lots){
            SplitAdjustment adjustment=SplitAdjustment.exact(lot.quantity,lot.costBasis,a.numerator(),a.denominator());
            int updated=jdbc.sql("update trading.execution_position_lot_projection set remaining_quantity=:q,version=version+1,updated_at=:at where lot_id=:lot and version=:version")
                .param("q",adjustment.afterQuantity()).param("at",a.effectiveAt().atOffset(ZoneOffset.UTC)).param("lot",lot.lotId).param("version",lot.version).update();
            if(updated!=1)throw conflict("lot changed during corporate action");
            jdbc.sql("insert into trading.execution_corporate_action_lot_adjustment values(:action,:lot,:before,:after,:basis,:unit,:version)")
                .param("action",a.actionId()).param("lot",lot.lotId).param("before",adjustment.beforeQuantity()).param("after",adjustment.afterQuantity())
                .param("basis",adjustment.preservedCostBasis()).param("unit",adjustment.adjustedUnitCost()).param("version",lot.version+1).update();
        }
        List<FlowKey> flows=jdbc.sql("select bot_id,partition_id,flow_id,instrument_id,quantity,version from trading.execution_flow_position_projection where instrument_id=:instrument and quantity>0 order by bot_id,partition_id,flow_id for update")
            .param("instrument",a.instrumentId()).query((rs,row)->new FlowKey(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),rs.getBigDecimal(5),rs.getLong(6))).list();
        for(FlowKey flow:flows){BigDecimal after=flow.quantity.multiply(BigDecimal.valueOf(a.numerator())).divide(BigDecimal.valueOf(a.denominator()),18,java.math.RoundingMode.UNNECESSARY).stripTrailingZeros();int updated=jdbc.sql("update trading.execution_flow_position_projection set quantity=:q,version=version+1,updated_at=:at where bot_id=:b and partition_id=:p and flow_id=:f and instrument_id=:i and version=:version").param("q",after).param("at",a.effectiveAt().atOffset(ZoneOffset.UTC)).param("b",flow.bot).param("p",flow.partition).param("f",flow.flow).param("i",flow.instrument).param("version",flow.version).update();if(updated!=1)throw conflict("flow position changed during corporate action");}
        jdbc.sql("update trading.execution_corporate_action_application set adjusted_lots=:lots,adjusted_flow_positions=:flows where action_id=:id")
            .param("lots",lots.size()).param("flows",flows.size()).param("id",a.actionId()).update();
        return new CorporateActionApplicationResult(a.actionId(),lots.size(),flows.size(),NO_MONETARY_POSTING);
    }
    private Optional<Stored> stored(UUID id){return jdbc.sql("select action_id,request_fingerprint,adjusted_lots,adjusted_flow_positions,ledger_effect from trading.execution_corporate_action_application where action_id=:id").param("id",id).query((rs,row)->new Stored(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getString(5))).optional();}
    private CorporateActionApplicationResult replay(Stored s,String fp){if(!s.fingerprint.equals(fp))throw conflict("corporate action identity conflict");return new CorporateActionApplicationResult(s.actionId,s.lots,s.flows,s.ledgerEffect);}
    private static String fingerprint(ApprovedCorporateAction a){String p=String.join("|",a.actionId().toString(),a.instrumentId().toString(),a.type().name(),Long.toString(a.numerator()),Long.toString(a.denominator()),a.effectiveAt().toString(),a.approvalId().toString(),a.approvedByOperatorId().toString(),a.approvedAt().toString(),a.evidenceDigest(),a.policyVersion());try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(p.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static CorporateActionConflictException conflict(String message){return new CorporateActionConflictException(message);}
    private record LotProjection(UUID lotId,BigDecimal quantity,BigDecimal costBasis,long version){}
    private record FlowKey(UUID bot,UUID partition,UUID flow,UUID instrument,BigDecimal quantity,long version){}
    private record Stored(UUID actionId,String fingerprint,int lots,int flows,String ledgerEffect){}
}
