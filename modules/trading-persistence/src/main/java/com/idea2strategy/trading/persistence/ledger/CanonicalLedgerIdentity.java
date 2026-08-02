package com.idea2strategy.trading.persistence.ledger;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The canonical identifiers and hashes the ledger tables require but a posting does not carry.
 *
 * <p>Everything here is derived from content, never allocated. That is what lets a redelivery
 * converge: the second attempt re-derives the same account id and the same entry hash, so it loses
 * an insert race instead of creating a second record of one fact.
 */
final class CanonicalLedgerIdentity {

    /** Namespace for the version 5 account ids this service derives. Fixed forever. */
    private static final UUID ACCOUNT_NAMESPACE = UUID.fromString("8f4d0b6a-2c31-5a7e-9b10-4f2c6d8e1a30");

    private static final String BOT_WIDE_SCOPE = "BOT";
    private static final String PARTITION_SCOPE = "PARTITION";

    private CanonicalLedgerIdentity() {}

    /**
     * The canonical {@code account_key}: scope, account type and unit, normalised and null free,
     * exactly as {@code trading.ledger_accounts} documents it.
     *
     * <p>These accounts are always denominated in a currency, never in an instrument, because a
     * posting from this service names a currency and never a security. The canonical
     * {@code ledger_account_exactly_one_unit} CHECK is satisfied by leaving {@code instrument_id}
     * null.
     */
    static String accountKey(UUID partitionId, String accountType, String currencyCode) {
        String scope = partitionId == null ? BOT_WIDE_SCOPE : PARTITION_SCOPE + ":" + partitionId;
        return scope + ":" + accountType + ":" + currencyCode;
    }

    /** A stable id for the account under {@code accountKey}, so concurrent creators converge. */
    static UUID accountId(UUID botId, String accountKey) {
        return uuidVersion5(ACCOUNT_NAMESPACE, botId + "|" + accountKey);
    }

    /**
     * The canonical {@code entry_hash}: a digest over everything the entry asserts.
     *
     * <p>The amount goes in at the canonical scale so that a value written as {@code 1.5} and one
     * written as {@code 1.50000000} hash the same, which is the same normalisation the column
     * itself performs.
     */
    static String entryHash(
            UUID transactionId, int sequence, String accountKey, String direction,
            String currencyCode, BigDecimal amount) {
        return sha256Hex("LEDGER_ENTRY|1|" + transactionId + '|' + sequence + '|' + accountKey + '|'
                + direction + '|' + currencyCode + '|' + amount.toPlainString());
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(digest("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID uuidVersion5(UUID namespace, String name) {
        MessageDigest sha1 = digest("SHA-1");
        sha1.update(ByteBuffer.allocate(16)
                .putLong(namespace.getMostSignificantBits())
                .putLong(namespace.getLeastSignificantBits())
                .array());
        byte[] hash = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
        hash[6] &= 0x0f;
        hash[6] |= 0x50;
        hash[8] &= 0x3f;
        hash[8] |= 0x80;
        ByteBuffer bytes = ByteBuffer.wrap(hash);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(algorithm + " is required", unavailable);
        }
    }
}
