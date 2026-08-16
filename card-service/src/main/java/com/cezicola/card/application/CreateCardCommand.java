package com.cezicola.card.application;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateCardCommand(UUID tenantId, UUID customerId, BigDecimal creditLimit, String idempotencyKey) {
}
