package com.cezicola.card.application.port;

import com.cezicola.card.domain.Card;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CardRepository {
    Card save(Card card, String idempotencyKey);

    Optional<Card> findById(UUID tenantId, UUID id);

    List<Card> findByCustomer(UUID tenantId, UUID customerId);

    Optional<Card> findByIdempotencyKey(UUID tenantId, String idempotencyKey);
}
