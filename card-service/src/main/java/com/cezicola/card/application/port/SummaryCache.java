package com.cezicola.card.application.port;

import java.util.Optional;

/**
 * A read-through cache for figures that are expensive to aggregate.
 *
 * <p>Never a source of truth. Everything here is a copy of something the ledger
 * can recompute, held only long enough to spare the database a repeated scan,
 * and always with an expiry — a cached figure that cannot go stale is a figure
 * that will eventually be wrong with no way back.
 */
public interface SummaryCache {

    Optional<String> get(String key);

    void put(String key, String value);
}
