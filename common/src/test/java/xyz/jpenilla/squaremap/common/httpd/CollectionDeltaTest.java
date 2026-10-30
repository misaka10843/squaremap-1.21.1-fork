package xyz.jpenilla.squaremap.common.httpd;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollectionDeltaTest {
    @Test
    @SuppressWarnings("unchecked")
    void patchesOnlyChangedFieldsAndCanRemoveFieldsAndRows() {
        final var stream = new CollectionDelta("id");
        stream.update(List.of(Map.of("id", "a", "name", "train", "x", 1, "owner", "old"), Map.of("id", "b")), Map.of());
        final var before = stream.snapshot();
        final var delta = stream.update(List.of(Map.of("id", "a", "name", "train", "x", 2)), Map.of());
        assertEquals(before.get("seq"), delta.get("base"));
        final var patches = (List<Map<String, Object>>) delta.get("upsert");
        assertEquals(1, patches.size());
        assertEquals(2, patches.getFirst().get("x"));
        assertFalse(patches.getFirst().containsKey("name"));
        assertEquals(List.of("owner"), patches.getFirst().get("$unset"));
        assertEquals(List.of("b"), delta.get("remove"));
        assertTrue(stream.update(List.of(Map.of("id", "a", "name", "train", "x", 2)), Map.of()).isEmpty());
        assertEquals(true, stream.snapshot().get("reset"));
    }
}
