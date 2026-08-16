package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.application.port.CardRepository;
import com.cezicola.card.domain.Card;
import jakarta.enterprise.context.ApplicationScoped;

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
        entity.lastFourDigits = card.lastFourDigits();
        entity.idempotencyKey = idempotencyKey;
        entity.createdAt = card.createdAt();
        return entity;
    }

    private static Card toDomain(CardEntity entity) {
        return new Card(entity.id, entity.tenantId, entity.customerId, entity.creditLimit, entity.currency,
                entity.status, entity.lastFourDigits, entity.createdAt);
    }
}
