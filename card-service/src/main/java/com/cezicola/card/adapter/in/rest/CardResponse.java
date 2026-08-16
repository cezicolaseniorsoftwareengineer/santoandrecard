package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.domain.Card;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CardResponse(UUID id, UUID customerId, BigDecimal creditLimit, String currency,
                           String status, String lastFourDigits, Instant createdAt) {
    static CardResponse from(Card card) {
        return new CardResponse(card.id(), card.customerId(), card.creditLimit(), card.currency(),
                card.status().name(), card.lastFourDigits(), card.createdAt());
    }
}
