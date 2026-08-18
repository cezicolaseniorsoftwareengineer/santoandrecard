package com.cezicola.card.application;

import com.cezicola.card.adapter.out.persistence.IdempotencyRecordEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs an operation at most once per key, and replays its original outcome.
 *
 * <p>A client that times out cannot tell a lost request from a lost response, so
 * it retries. For a payment the two cases could not be more different, and the
 * server is the only side that knows which happened. This turns the second call
 * into a read of the first call's result.
 *
 * <p>Checking for the key and then inserting is not atomic on its own — two
 * concurrent replays both find nothing and both proceed. The primary key is what
 * actually enforces the guarantee, so losing that race is an expected outcome:
 * the loser reads the winner's stored response, which is precisely what it would
 * have received had the calls not overlapped. This is the same shape already
 * proven for card issuance.
 */
@ApplicationScoped
public class IdempotentOperation {

    /** SQL state class for an integrity constraint violation. */
    private static final String INTEGRITY_VIOLATION = "23";

    private final EntityManager entityManager;
    private final ObjectMapper json;
    private final Clock clock;

    @jakarta.inject.Inject
    public IdempotentOperation(EntityManager entityManager, ObjectMapper json) {
        this(entityManager, json, Clock.systemUTC());
    }

    IdempotentOperation(EntityManager entityManager, ObjectMapper json, Clock clock) {
        this.entityManager = entityManager;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param request the caller's payload, digested so a key replayed with a
     *                different body can be refused instead of being answered
     *                with the first request's outcome
     */
    public <T> T execute(UUID tenantId, String operation, String key, Object request,
                         Class<T> resultType, Supplier<T> work) {
        String digest = digestOf(request);

        Optional<T> replayed = replay(tenantId, operation, key, digest, resultType);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                T result = work.get();
                store(tenantId, operation, key, digest, result);
                return result;
            });
        } catch (RuntimeException failure) {
            if (!isDuplicateKey(failure)) {
                throw failure;
            }
            // Another request holding the same key committed first. Its stored
            // response is the answer this one owes the caller.
            return replay(tenantId, operation, key, digest, resultType)
                    .orElseThrow(() -> failure);
        }
    }

    private <T> Optional<T> replay(UUID tenantId, String operation, String key, String digest, Class<T> type) {
        IdempotencyRecordEntity record = QuarkusTransaction.requiringNew().call(() ->
                entityManager.find(IdempotencyRecordEntity.class,
                        new IdempotencyRecordEntity.Key(tenantId, operation, key)));
        if (record == null) {
            return Optional.empty();
        }
        if (!record.requestDigest.equals(digest)) {
            throw new IdempotencyConflictException();
        }
        try {
            return Optional.of(json.readValue(record.responseBody, type));
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException("stored idempotent response could not be read", unreadable);
        }
    }

    private void store(UUID tenantId, String operation, String key, String digest, Object result) {
        IdempotencyRecordEntity record = new IdempotencyRecordEntity();
        record.tenantId = tenantId;
        record.operation = operation;
        record.idempotencyKey = key;
        record.requestDigest = digest;
        record.responseStatus = 201;
        record.responseBody = serialise(result);
        record.createdAt = clock.instant();
        entityManager.persist(record);
        // Flushed here so a duplicate key surfaces as this operation's failure
        // rather than at commit, where it would be harder to attribute.
        entityManager.flush();
    }

    private String serialise(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unwritable) {
            throw new IllegalStateException("idempotent response could not be stored", unwritable);
        }
    }

    private String digestOf(Object request) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(serialise(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static boolean isDuplicateKey(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith(INTEGRITY_VIOLATION)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
