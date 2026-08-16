package com.cezicola.card.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class CardEntityRepository implements PanacheRepositoryBase<CardEntity, UUID> {
}
