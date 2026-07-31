package com.idea2strategy.trading.messaging.contract.v1;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class OrderCandidateContractV1 {
    private OrderCandidateContractV1() {
    }

    public record Candidate(
        UUID candidateId,
        UUID instrumentId,
        OrderExecutionContractV1.Side side,
        DecimalValueV1 requestedWeight,
        String reasonCode
    ) {
        public Candidate {
            ContractValidationV1.required(candidateId, "candidateId");
            ContractValidationV1.required(instrumentId, "instrumentId");
            ContractValidationV1.required(side, "side");
            ContractValidationV1.required(requestedWeight, "requestedWeight");
            ContractValidationV1.requiredText(reasonCode, "reasonCode");
        }
    }

    public record CandidateBatch(
        UUID batchId,
        UUID botId,
        UUID strategyVersionId,
        UUID evaluationId,
        String partitionKey,
        List<Candidate> candidates
    ) {
        public CandidateBatch {
            ContractValidationV1.required(batchId, "batchId");
            ContractValidationV1.required(botId, "botId");
            ContractValidationV1.required(strategyVersionId, "strategyVersionId");
            ContractValidationV1.required(evaluationId, "evaluationId");
            ContractValidationV1.requiredText(partitionKey, "partitionKey");
            candidates = ContractValidationV1.required(candidates, "candidates");
            for (Candidate candidate : candidates) {
                ContractValidationV1.required(candidate, "candidate");
            }
            candidates = List.copyOf(candidates);

            var candidateIds = new HashSet<UUID>();
            for (Candidate candidate : candidates) {
                if (!candidateIds.add(candidate.candidateId())) {
                    throw new IllegalArgumentException("duplicate candidateId");
                }
            }
        }
    }
}
