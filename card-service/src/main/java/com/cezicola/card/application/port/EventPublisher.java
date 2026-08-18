package com.cezicola.card.application.port;

import com.cezicola.card.domain.DomainEvent;

/**
 * Delivery to whatever carries events out of this service.
 *
 * <p>A port rather than a Kafka call in the middle of the reader, so the outbox
 * logic can be tested for its delivery guarantees without a broker, and so the
 * broker can be replaced without touching them.
 */
public interface EventPublisher {

    /**
     * Delivers the event, or throws.
     *
     * <p>Returning normally must mean the broker acknowledged it. A publisher
     * that returns before the acknowledgement would let the reader mark an event
     * published that nobody ever received.
     */
    void publish(DomainEvent event);
}
