package com.idea2strategy.trading.strategy.runtime.plan;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ExecutionPlanIntegrity {
    private ExecutionPlanIntegrity() {
    }

    public static String planSha256(
            UUID botId,
            UUID releaseId,
            boolean locked,
            String planSchemaVersion,
            Set<FeatureRequirement> requiredFeatures,
            String planPayload) {
        DigestWriter writer = new DigestWriter()
                .add("execution-plan-v1")
                .add(botId)
                .add(releaseId)
                .add(locked)
                .add(planSchemaVersion)
                .add(requiredFeatures.size());
        requiredFeatures.stream()
                .sorted(Comparator.comparing(FeatureRequirement::featureId)
                        .thenComparing(FeatureRequirement::version))
                .forEach(requirement -> writer.add(requirement.featureId()).add(requirement.version()));
        return writer.add(planPayload).hexDigest();
    }

    public static String runtimeStateSha256(
            UUID botId,
            UUID releaseId,
            String runtimeSchemaVersion,
            long sequence,
            Map<String, String> values) {
        DigestWriter writer = new DigestWriter()
                .add("runtime-state-v1")
                .add(botId)
                .add(releaseId)
                .add(runtimeSchemaVersion)
                .add(sequence)
                .add(values.size());
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> writer.add(entry.getKey()).add(entry.getValue()));
        return writer.hexDigest();
    }

    static boolean planMatches(ReleasedExecutionPlanSnapshot snapshot) {
        return MessageDigest.isEqual(
                snapshot.integritySha256().getBytes(StandardCharsets.US_ASCII),
                planSha256(
                                snapshot.botId(), snapshot.releaseId(), snapshot.locked(),
                                snapshot.planSchemaVersion(), snapshot.requiredFeatures(), snapshot.planPayload())
                        .getBytes(StandardCharsets.US_ASCII));
    }

    static boolean runtimeStateMatches(RuntimeStateSnapshot snapshot) {
        return MessageDigest.isEqual(
                snapshot.integritySha256().getBytes(StandardCharsets.US_ASCII),
                runtimeStateSha256(
                                snapshot.botId(), snapshot.releaseId(), snapshot.runtimeSchemaVersion(),
                                snapshot.sequence(), snapshot.values())
                        .getBytes(StandardCharsets.US_ASCII));
    }

    private static final class DigestWriter {
        private final MessageDigest digest;

        private DigestWriter() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private DigestWriter add(UUID value) {
            return add(value.toString());
        }

        private DigestWriter add(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
            return this;
        }

        private DigestWriter add(boolean value) {
            digest.update(value ? (byte) 1 : (byte) 0);
            return this;
        }

        private DigestWriter add(long value) {
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
            return this;
        }

        private DigestWriter add(int value) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
            return this;
        }

        private String hexDigest() {
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }
}
