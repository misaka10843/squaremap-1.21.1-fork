package xyz.jpenilla.squaremap.common.httpd;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A revisioned collection. The SSE hub serializes updates and subscription snapshots. */
public final class CollectionDelta {
    private final String key;
    private final String epoch = UUID.randomUUID().toString();
    private Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
    private Map<String, Object> metadata = Map.of();
    private long sequence;

    public CollectionDelta(final String key) {
        this.key = key;
    }

    public Map<String, Object> update(final List<? extends Map<String, Object>> values, final Map<String, Object> metadata) {
        final Map<String, Map<String, Object>> next = new LinkedHashMap<>();
        final List<Map<String, Object>> upsert = new ArrayList<>();
        for (final Map<String, Object> value : values) {
            final String id = String.valueOf(value.get(this.key));
            final Map<String, Object> copy = new LinkedHashMap<>(value);
            next.put(id, copy);
            final Map<String, Object> old = this.rows.get(id);
            if (old == null) {
                upsert.add(copy);
            } else if (!old.equals(copy)) {
                final Map<String, Object> patch = new LinkedHashMap<>();
                patch.put(this.key, value.get(this.key));
                copy.forEach((field, item) -> {
                    if (!old.containsKey(field) || !java.util.Objects.equals(old.get(field), item)) patch.put(field, item);
                });
                final List<String> unset = old.keySet().stream().filter(field -> !copy.containsKey(field)).toList();
                if (!unset.isEmpty()) patch.put("$unset", unset);
                upsert.add(patch);
            }
        }
        final List<String> removed = this.rows.keySet().stream().filter(id -> !next.containsKey(id)).toList();
        if (upsert.isEmpty() && removed.isEmpty() && this.metadata.equals(metadata)) return Map.of();
        final Map<String, Object> message = this.header();
        message.put("base", this.sequence);
        message.put("seq", ++this.sequence);
        message.put("upsert", upsert);
        message.put("remove", removed);
        message.put("meta", metadata);
        this.rows = next;
        this.metadata = new LinkedHashMap<>(metadata);
        return message;
    }

    public Map<String, Object> snapshot() {
        final Map<String, Object> message = this.header();
        message.put("reset", true);
        message.put("items", List.copyOf(this.rows.values()));
        message.put("meta", this.metadata);
        return message;
    }

    private Map<String, Object> header() {
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("epoch", this.epoch);
        message.put("seq", this.sequence);
        return message;
    }
}
