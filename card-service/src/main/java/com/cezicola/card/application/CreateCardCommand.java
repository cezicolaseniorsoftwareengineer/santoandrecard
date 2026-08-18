package com.cezicola.card.application;

import com.cezicola.card.domain.CardProduct;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateCardCommand(UUID tenantId, UUID customerId, BigDecimal creditLimit, CardProduct product,
                                String idempotencyKey) {
}
