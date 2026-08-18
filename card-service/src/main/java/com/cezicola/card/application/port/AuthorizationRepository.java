package com.cezicola.card.application.port;

import com.cezicola.card.domain.Authorization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationRepository {

    Authorization save(Authorization authorization);

    Optional<Authorization> findById(UUID tenantId, UUID id);

    List<Authorization> findByCustomer(UUID tenantId, UUID customerId, int limit);

    /** Open holds whose deadline has passed, oldest first. */
    List<UUID> findExpired(Instant now, int limit);
}
