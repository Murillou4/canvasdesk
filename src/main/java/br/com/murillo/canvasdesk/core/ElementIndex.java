package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Metadata stays resident; immutable element payloads are fetched only when used. */
final class ElementIndex {
    static final int REGION_SIZE = 1024;
    private static final long MAX_BUCKETS_PER_ELEMENT = 4096;
    record Region(long x, long y) {}
    record Entry(UUID id, long revision, CanvasElement.Type type, CanvasElement.Bounds bounds, String object, long order) {
        Entry withOrder(long value) { return new Entry(id, revision, type, bounds, object, value); }
        JsonObject toJson() {
            JsonObject o = new JsonObject(); o.addProperty("id", id.toString()); o.addProperty("revision", revision);
            o.addProperty("type", type.name()); o.addProperty("object", object);
            o.addProperty("order", order);
            o.addProperty("minX", bounds.minX()); o.addProperty("minY", bounds.minY());
            o.addProperty("maxX", bounds.maxX()); o.addProperty("maxY", bounds.maxY()); return o;
        }
        static Entry fromJson(JsonObject o) {
            return new Entry(UUID.fromString(o.get("id").getAsString()), o.get("revision").getAsLong(),
                CanvasElement.Type.valueOf(o.get("type").getAsString()), new CanvasElement.Bounds(
                o.get("minX").getAsDouble(), o.get("minY").getAsDouble(), o.get("maxX").getAsDouble(),
                o.get("maxY").getAsDouble()), o.get("object").getAsString(), o.has("order") ? o.get("order").getAsLong() : 0);
        }
    }
    final Map<UUID, Entry> entries = new LinkedHashMap<>();
    final Set<UUID> connectors = new LinkedHashSet<>();
    private final Map<Region, Set<UUID>> regions = new HashMap<>();
    private final Set<UUID> large = new HashSet<>();

    void put(Entry entry) {
        remove(entry.id); entries.put(entry.id, entry);
        if (entry.type == CanvasElement.Type.CONNECTOR) connectors.add(entry.id);
        if (tooLarge(entry.bounds)) large.add(entry.id);
        else visit(entry.bounds, r -> regions.computeIfAbsent(r, unused -> new HashSet<>()).add(entry.id));
    }
    void remove(UUID id) {
        Entry old = entries.remove(id); if (old == null) return;
        connectors.remove(id);
        if (large.remove(id)) return;
        visit(old.bounds, r -> {
            Set<UUID> ids = regions.get(r);
            if (ids != null) { ids.remove(id); if (ids.isEmpty()) regions.remove(r); }
        });
    }
    Set<UUID> query(CanvasElement.Bounds viewport) {
        Set<UUID> found = new LinkedHashSet<>(large);
        if (tooLarge(viewport)) found.addAll(entries.keySet());
        else visit(viewport, r -> { Set<UUID> ids = regions.get(r); if (ids != null) found.addAll(ids); });
        found.removeIf(id -> !entries.get(id).bounds.intersects(viewport));
        Set<UUID> ordered = new LinkedHashSet<>();
        found.stream().sorted(java.util.Comparator.comparingLong((UUID id) -> entries.get(id).order)
            .thenComparing(UUID::toString)).forEach(ordered::add);
        return ordered;
    }
    private static long region(double coordinate) { return (long) Math.floor(coordinate / REGION_SIZE); }
    private static boolean tooLarge(CanvasElement.Bounds b) {
        double width = (double) region(b.maxX()) - region(b.minX()) + 1;
        double height = (double) region(b.maxY()) - region(b.minY()) + 1;
        return width * height > MAX_BUCKETS_PER_ELEMENT;
    }
    private static void visit(CanvasElement.Bounds b, java.util.function.Consumer<Region> visit) {
        long minX = region(b.minX()), minY = region(b.minY()), maxX = region(b.maxX()), maxY = region(b.maxY());
        for (long x = minX;; x++) {
            for (long y = minY;; y++) { visit.accept(new Region(x, y)); if (y == maxY) break; }
            if (x == maxX) break;
        }
    }
}
