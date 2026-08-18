package com.cezicola.card.adapter.in.rest;

import com.cezicola.card.domain.Card;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a card looks like to its holder. The full number is deliberately absent:
 * it is returned only by the reveal endpoint, and only against a PIN.
 */
public record CardResponse(UUID id, UUID customerId, BigDecimal creditLimit, String currency,
                           String status, String product, String productName, String lastFourDigits,
                           boolean pinDefined, Instant createdAt) {
    static CardResponse from(Card card) {
        return new CardResponse(card.id(), card.customerId(), card.creditLimit(), card.currency(),
                card.status().name(), card.product().name(), card.product().displayName(),
                card.lastFourDigits(), card.hasPin(), card.createdAt());
    }
}
