package com.cezicola.card.domain;

import java.math.BigDecimal;

public record PurchasePlan(BigDecimal principal, BigDecimal interest, BigDecimal total,
                           int installments, BigDecimal installmentAmount) {
}
