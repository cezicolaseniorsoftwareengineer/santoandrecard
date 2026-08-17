package com.cezicola.card.application;

import com.cezicola.card.application.port.CardRepository;
import com.cezicola.card.domain.Card;
import com.cezicola.card.domain.CardStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CardService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CardRepository repository;
    private final Clock clock;

    @Inject
    public CardService(CardRepository repository) {
        this(repository, Clock.systemUTC());
    }

    CardService(CardRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Card create(CreateCardCommand command) {
        var existing = repository.findByIdempotencyKey(command.tenantId(), command.idempotencyKey());
        if (existing.isPresent()) {
            Card card = existing.orElseThrow();
            if (!card.customerId().equals(command.customerId())
                    || card.creditLimit().compareTo(command.creditLimit()) != 0) {
                throw new IdempotencyConflictException();
            }
            return card;
        }
        return repository.save(new Card(
                        UUID.randomUUID(),
                        command.tenantId(),
                        command.customerId(),
                        command.creditLimit(),
                        "BRL",
                        CardStatus.ACTIVE,
                        "%04d".formatted(RANDOM.nextInt(10_000)),
                        clock.instant()), command.idempotencyKey());
    }

    public Card get(UUID tenantId, UUID id) {
        return repository.findById(tenantId, id).orElseThrow(() -> new CardNotFoundException(id));
    }

    public List<Card> listForCustomer(UUID tenantId, UUID customerId) {
        return repository.findByCustomer(tenantId, customerId);
    }
}
