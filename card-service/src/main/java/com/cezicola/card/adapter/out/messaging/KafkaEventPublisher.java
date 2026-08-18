package com.cezicola.card.adapter.out.messaging;

import com.cezicola.card.application.port.EventPublisher;
import com.cezicola.card.domain.DomainEvent;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Publishes outbox events to Kafka.
 *
 * <p>The producer is configured for correctness before throughput. {@code
 * acks=all} means an acknowledgement reflects every in-sync replica rather than
 * just the leader; {@code enable.idempotence} stops a producer-side retry from
 * writing the record twice; and one in-flight request per connection preserves
 * the order of a partition even while retrying.
 *
 * <p>The send is awaited rather than fired and forgotten. The relay marks an
 * event published when this method returns, so returning before the broker
 * answered would be a claim it cannot take back.
 */
@ApplicationScoped
public class KafkaEventPublisher implements EventPublisher {
    private final String bootstrapServers;
    private final String topic;
    private final Duration sendTimeout;
    private Producer<String, String> producer;

    public KafkaEventPublisher(
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers,
            @ConfigProperty(name = "card.events.topic") String topic,
            @ConfigProperty(name = "card.events.send-timeout-ms") long sendTimeoutMillis) {
        this.bootstrapServers = bootstrapServers;
        this.topic = topic;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMillis);
    }

    void onStart(@Observes StartupEvent ignored) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        // The client refuses a delivery timeout smaller than linger + request
        // timeout, and the default linger is not zero. Both are set explicitly so
        // the budget is stated here rather than inherited and then violated: the
        // relay is a batch drain, so there is nothing to linger for.
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) sendTimeout.toMillis());
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) (sendTimeout.toMillis() / 2));
        this.producer = new KafkaProducer<>(config);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (producer != null) {
            producer.close(Duration.ofSeconds(5));
        }
    }

    @Override
    public void publish(DomainEvent event) {
        // Keyed by aggregate so every event about one customer lands on the same
        // partition, which is the only place Kafka promises ordering.
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, event.aggregateId().toString(), event.payload());
        record.headers()
                .add(new RecordHeader("event-id", event.id().toString().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("event-type", event.type().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader("tenant-id", event.tenantId().toString().getBytes(StandardCharsets.UTF_8)));

        try {
            producer.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EventPublicationException("interrupted while publishing " + event.id(), interrupted);
        } catch (Exception failure) {
            throw new EventPublicationException("could not publish " + event.id(), failure);
        }
    }

    public static class EventPublicationException extends RuntimeException {
        public EventPublicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
