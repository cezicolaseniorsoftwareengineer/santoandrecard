package com.cezicola.card.adapter.out.persistence;

import com.cezicola.card.application.port.AuthorizationRepository;
import com.cezicola.card.domain.Authorization;
import com.cezicola.card.domain.AuthorizationStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class JpaAuthorizationRepository implements AuthorizationRepository {
    private final EntityManager entityManager;

    public JpaAuthorizationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Authorization save(Authorization authorization) {
        AuthorizationEntity entity = entityManager.find(AuthorizationEntity.class, authorization.id());
        boolean isNew = entity == null;
        if (isNew) {
            entity = new AuthorizationEntity();
            entity.id = authorization.id();
            entity.tenantId = authorization.tenantId();
            entity.customerId = authorization.customerId();
            entity.cardId = authorization.cardId();
            entity.merchantCategory = authorization.merchantCategory();
            entity.amount = authorization.amount();
            entity.createdAt = authorization.createdAt();
            entity.expiresAt = authorization.expiresAt();
        }
        // Only the outcome changes after approval; the terms of the hold do not.
        // Set before persisting, because the insert must carry the whole row —
        // populating a non-nullable column after persist left it null at flush.
        entity.capturedAmount = authorization.capturedAmount();
        entity.status = authorization.status();
        entity.settledAt = authorization.settledAt();
        if (isNew) {
            entityManager.persist(entity);
        }
        return toDomain(entity);
    }

    @Override
    public Optional<Authorization> findById(UUID tenantId, UUID id) {
        return entityManager.createQuery("""
                        select a from AuthorizationEntity a where a.tenantId = :tenantId and a.id = :id
                        """, AuthorizationEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("id", id)
                .getResultStream().findFirst()
                .map(JpaAuthorizationRepository::toDomain);
    }

    @Override
    public List<Authorization> findByCustomer(UUID tenantId, UUID customerId, int limit) {
        return entityManager.createQuery("""
                        select a from AuthorizationEntity a
                        where a.tenantId = :tenantId and a.customerId = :customerId
                        order by a.createdAt desc
                        """, AuthorizationEntity.class)
                .setParameter("tenantId", tenantId)
                .setParameter("customerId", customerId)
                .setMaxResults(Math.min(Math.max(limit, 1), 200))
                .getResultList().stream().map(JpaAuthorizationRepository::toDomain).toList();
    }

    @Override
    public List<UUID> findExpired(Instant now, int limit) {
        return entityManager.createQuery("""
                        select a.id from AuthorizationEntity a
                        where a.status = :open and a.expiresAt <= :now
                        order by a.expiresAt asc
                        """, UUID.class)
                .setParameter("open", AuthorizationStatus.APPROVED)
                .setParameter("now", now)
                .setMaxResults(limit)
                .getResultList();
    }

    private static Authorization toDomain(AuthorizationEntity entity) {
        return new Authorization(entity.id, entity.tenantId, entity.customerId, entity.cardId,
                entity.merchantCategory, entity.amount, entity.capturedAmount, entity.status,
                entity.createdAt, entity.expiresAt, entity.settledAt);
    }
}
