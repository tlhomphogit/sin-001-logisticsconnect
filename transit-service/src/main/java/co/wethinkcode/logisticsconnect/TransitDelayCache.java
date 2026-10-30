package co.wethinkcode.logisticsconnect;

import co.wethinkcode.logisticsconnect.mq.DelayStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class TransitDelayCache {

    private static final Logger log = LoggerFactory.getLogger(TransitDelayCache.class);
    private final Map<String, DelayStageEvent> cache = new ConcurrentHashMap<>();

    public TransitDelayCache() {}

    public TransitDelayCache(Map<String, DelayStageEvent> initial) {
        if (initial != null) {
            initial.forEach((k, v) -> cache.put(k.trim().toUpperCase(Locale.ROOT), v));
        }
    }

    public Optional<DelayStageEvent> get(String hubId) {
        if (hubId == null || hubId.isBlank()) return Optional.empty();
        String canonicalId = hubId.trim().toUpperCase(Locale.ROOT);
        return Optional.ofNullable(cache.get(canonicalId));
    }

    public boolean updateIfNewer(DelayStageEvent event) {
        if (event == null || event.hubId() == null || event.hubId().isBlank()) {
            return false;
        }
        String canonicalId = event.hubId().trim().toUpperCase(Locale.ROOT);
        AtomicBoolean updated = new AtomicBoolean(false);

        cache.compute(canonicalId, (k, existing) -> {
            if (existing == null) {
                updated.set(true);
                log.info("Cached new delay stage for hub {}: stage={}, timestamp={}",
                        canonicalId, event.stage(), event.timestamp());
                return event;
            }
            if (event.timestamp() > existing.timestamp()) {
                updated.set(true);
                log.info("Updated delay stage for hub {}: stage {} -> {}, timestamp {} -> {}",
                        canonicalId, existing.stage(), event.stage(), existing.timestamp(), event.timestamp());
                return event;
            }
            log.warn("Ignored stale or duplicate event for hub {}: incoming ts {} <= existing ts {}",
                    canonicalId, event.timestamp(), existing.timestamp());
            return existing;
        });

        return updated.get();
    }

    public void put(DelayStageEvent event) {
        if (event != null && event.hubId() != null) {
            cache.put(event.hubId().trim().toUpperCase(Locale.ROOT), event);
        }
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }
}
