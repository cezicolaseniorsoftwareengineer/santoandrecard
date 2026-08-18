package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.application.port.CardRepository;
import com.cezicola.card.domain.Card;
import com.cezicola.card.domain.CardNumber;
import com.cezicola.card.domain.CardPin;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaCardRepository implements CardRepository {
    private final CardEntityRepository entities;

    public JpaCardRepository(CardEntityRepository entities) {
        this.entities = entities;
    }

    @Override
    public Card save(Card card, String idempotencyKey) {
        CardEntity entity = toEntity(card, idempotencyKey);
        entities.persist(entity);
        return toDomain(entity);
    }

    @Override
    public Optional<Card> findById(UUID tenantId, UUID id) {
        return entities.find("tenantId = ?1 and id = ?2", tenantId, id).firstResultOptional().map(JpaCardRepository::toDomain);
    }

    @Override
    public List<Card> findByCustomer(UUID tenantId, UUID customerId) {
        return entities.find("tenantId = ?1 and customerId = ?2 order by createdAt desc", tenantId, customerId)
                .list().stream().map(JpaCardRepository::toDomain).toList();
    }

    /**
     * Applies a PIN change to the stored row. The entity is managed inside the
     * caller's transaction, so the update is flushed with everything else that
     * operation touched rather than on its own.
     */
    @Override
    public void updatePin(UUID tenantId, UUID cardId, CardPin pin, int attempts) {
        entities.find("tenantId = ?1 and id = ?2", tenantId, cardId).firstResultOptional().ifPresent(entity -> {
            entity.pinSalt = pin == null ? null : pin.salt();
            entity.pinHash = pin == null ? null : pin.hash();
            entity.pinAttempts = attempts;
        });
    }

    @Override
    public Optional<Card> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return entities.find("tenantId = ?1 and idempotencyKey = ?2", tenantId, idempotencyKey).firstResultOptional().map(JpaCardRepository::toDomain);
    }

    private static CardEntity toEntity(Card card, String idempotencyKey) {
        CardEntity entity = new CardEntity();
        entity.id = card.id();
        entity.tenantId = card.tenantId();
        entity.customerId = card.customerId();
        entity.creditLimit = card.creditLimit();
        entity.currency = card.currency();
        entity.status = card.status();
        entity.product = card.product();
        entity.cardNumber = card.number().value();
        entity.pinSalt = card.pin() == null ? null : card.pin().salt();
        entity.pinHash = card.pin() == null ? null : card.pin().hash();
        entity.pinAttempts = card.pinAttempts();
        entity.lastFourDigits = card.lastFourDigits();
        entity.idempotencyKey = idempotencyKey;
        entity.createdAt = card.createdAt();
        return entity;
    }

    private static Card toDomain(CardEntity entity) {
        return new Card(entity.id, entity.tenantId, entity.customerId, entity.creditLimit, entity.currency,
                entity.status, entity.product, new CardNumber(entity.cardNumber),
                entity.pinHash == null ? null : new CardPin(entity.pinSalt, entity.pinHash),
                entity.pinAttempts, entity.createdAt);
    }
}
