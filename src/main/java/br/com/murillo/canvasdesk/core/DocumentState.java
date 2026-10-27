package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DocumentState {
    record State(ElementIndex.Entry entry, long version) {
        JsonObject toJson() {
            JsonObject out = new JsonObject(); out.addProperty("version", version);
            out.add("entry", entry == null ? JsonNull.INSTANCE : entry.toJson()); return out;
        }
        static State fromJson(JsonObject o) {
            return new State(o.get("entry").isJsonNull() ? null : ElementIndex.Entry.fromJson(o.getAsJsonObject("entry")), o.get("version").getAsLong());
        }
    }
    record Change(UUID id, State before, State after) {
        JsonObject toJson() {
            JsonObject out = new JsonObject(); out.addProperty("id", id.toString());
            out.add("before", before.toJson()); out.add("after", after.toJson()); return out;
        }
        static Change fromJson(JsonObject o) {
            return new Change(UUID.fromString(o.get("id").getAsString()), State.fromJson(o.getAsJsonObject("before")), State.fromJson(o.getAsJsonObject("after")));
        }
    }
    record History(List<Change> changes) {
        History { changes = List.copyOf(changes); }
        JsonArray toJson() { JsonArray out = new JsonArray(); changes.forEach(c -> out.add(c.toJson())); return out; }
        static History fromJson(JsonArray values) {
            List<Change> changes = new ArrayList<>(); values.forEach(e -> changes.add(Change.fromJson(e.getAsJsonObject()))); return new History(changes);
        }
    }
    record Receipt(UUID actor, String message) {}
    final UUID id;
    String name = "Canvas Desk";
    long revision;
    long nextOrder;
    final Set<UUID> owners = new LinkedHashSet<>();
    final Set<UUID> editors = new LinkedHashSet<>();
    final ElementIndex index = new ElementIndex();
    final Map<UUID, Long> versions = new LinkedHashMap<>();
    final Map<UUID, Deque<History>> undo = new LinkedHashMap<>();
    final Map<UUID, Deque<History>> redo = new LinkedHashMap<>();
    final Map<UUID, Receipt> receipts = new LinkedHashMap<>();
    private CanvasElement.Bounds cachedBounds;

    DocumentState(UUID id) { this.id = id; }
    State state(UUID element) { return new State(index.entries.get(element), versions.getOrDefault(element, -1L)); }
    boolean editable(UUID player) { return owners.contains(player) || editors.contains(player); }
    void set(UUID element, State state) {
        cachedBounds = null;
        if (state.entry == null) index.remove(element); else { index.put(state.entry); nextOrder = Math.max(nextOrder, state.entry.order()); }
        versions.put(element, state.version);
    }
    CanvasElement.Bounds contentBounds() {
        if (cachedBounds != null) return cachedBounds;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (ElementIndex.Entry e : index.entries.values()) {
            minX = Math.min(minX, e.bounds().minX()); minY = Math.min(minY, e.bounds().minY());
            maxX = Math.max(maxX, e.bounds().maxX()); maxY = Math.max(maxY, e.bounds().maxY());
        }
        if (index.entries.isEmpty()) return cachedBounds = new CanvasElement.Bounds(0, 0, 256, 256);
        return cachedBounds = new CanvasElement.Bounds(minX, minY, maxX, maxY);
    }
    void history(UUID actor, String mode, History history) {
        if (mode.equals("normal")) {
            push(undo, actor, history); redo.remove(actor);
        } else if (mode.equals("undo")) {
            History previous = undo.get(actor).removeLast();
            rebase(undo.get(actor), previous, history); push(redo, actor, history);
        } else if (mode.equals("redo")) {
            History previous = redo.get(actor).removeLast();
            rebase(redo.get(actor), previous, history); push(undo, actor, history);
        } else throw new IllegalArgumentException("Unknown history mode.");
    }
    /** Restoring an older value gives it a fresh revision; advance the actor's next expected baseline. */
    private static void rebase(Deque<History> stack, History previous, History applied) {
        if (stack.isEmpty()) return;
        Map<UUID, State> expected = new LinkedHashMap<>(), restored = new LinkedHashMap<>();
        previous.changes.forEach(change -> expected.put(change.id, change.before));
        applied.changes.forEach(change -> restored.put(change.id, change.after));
        List<History> entries = new ArrayList<>(stack);
        Set<UUID> remaining = new LinkedHashSet<>(expected.keySet());
        for (int i = entries.size() - 1; i >= 0 && !remaining.isEmpty(); i--) {
            History old = entries.get(i); List<Change> changes = new ArrayList<>(old.changes);
            for (int j = 0; j < changes.size(); j++) {
                Change change = changes.get(j);
                if (!remaining.remove(change.id)) continue;
                // An unmatched baseline indicates an intervening edit and must keep its conflict.
                if (change.after.equals(expected.get(change.id)))
                    changes.set(j, new Change(change.id, change.before, restored.get(change.id)));
            }
            entries.set(i, new History(changes));
        }
        stack.clear(); stack.addAll(entries);
    }
    private static void push(Map<UUID, Deque<History>> target, UUID actor, History history) {
        Deque<History> list = target.computeIfAbsent(actor, unused -> new ArrayDeque<>());
        list.addLast(history); if (list.size() > 100) list.removeFirst();
    }
    JsonObject toJson() {
        JsonObject out = new JsonObject(); out.addProperty("id", id.toString()); out.addProperty("name", name); out.addProperty("revision", revision); out.addProperty("nextOrder", nextOrder);
        out.add("owners", ids(owners)); out.add("editors", ids(editors));
        JsonArray elements = new JsonArray(); index.entries.values().forEach(e -> elements.add(e.toJson())); out.add("elements", elements);
        JsonObject v = new JsonObject(); versions.forEach((id, version) -> v.addProperty(id.toString(), version)); out.add("versions", v);
        out.add("undo", historyJson(undo)); out.add("redo", historyJson(redo));
        JsonObject r = new JsonObject(); receipts.forEach((id, receipt) -> {
            JsonObject item = new JsonObject(); item.addProperty("actor", receipt.actor.toString()); item.addProperty("message", receipt.message); r.add(id.toString(), item);
        }); out.add("receipts", r); return out;
    }
    static DocumentState fromJson(JsonObject o) {
        DocumentState doc = new DocumentState(UUID.fromString(o.get("id").getAsString()));
        doc.name = o.get("name").getAsString(); doc.revision = o.get("revision").getAsLong();
        doc.owners.addAll(readIds(o.getAsJsonArray("owners"))); doc.editors.addAll(readIds(o.getAsJsonArray("editors")));
        for (JsonElement element : o.getAsJsonArray("elements")) {
            ElementIndex.Entry entry = ElementIndex.Entry.fromJson(element.getAsJsonObject());
            if (entry.order() == 0) entry = entry.withOrder(doc.nextOrder + 1);
            doc.index.put(entry); doc.nextOrder = Math.max(doc.nextOrder, entry.order());
        }
        if (o.has("nextOrder")) doc.nextOrder = Math.max(doc.nextOrder, o.get("nextOrder").getAsLong());
        o.getAsJsonObject("versions").entrySet().forEach(e -> doc.versions.put(UUID.fromString(e.getKey()), e.getValue().getAsLong()));
        readHistory(o.getAsJsonObject("undo"), doc.undo); readHistory(o.getAsJsonObject("redo"), doc.redo);
        o.getAsJsonObject("receipts").entrySet().forEach(e -> {
            JsonObject receipt = e.getValue().getAsJsonObject(); doc.receipts.put(UUID.fromString(e.getKey()),
                new Receipt(UUID.fromString(receipt.get("actor").getAsString()), receipt.get("message").getAsString()));
        }); return doc;
    }
    static JsonArray ids(Iterable<UUID> ids) { JsonArray out = new JsonArray(); ids.forEach(id -> out.add(id.toString())); return out; }
    static Set<UUID> readIds(JsonArray values) {
        Set<UUID> out = new LinkedHashSet<>(); values.forEach(e -> out.add(UUID.fromString(e.getAsString()))); return out;
    }
    private static JsonObject historyJson(Map<UUID, Deque<History>> map) {
        JsonObject out = new JsonObject(); map.forEach((actor, history) -> {
            JsonArray list = new JsonArray(); history.forEach(h -> list.add(h.toJson())); out.add(actor.toString(), list);
        }); return out;
    }
    private static void readHistory(JsonObject o, Map<UUID, Deque<History>> target) {
        o.entrySet().forEach(e -> {
            Deque<History> list = new ArrayDeque<>(); e.getValue().getAsJsonArray().forEach(h -> list.add(History.fromJson(h.getAsJsonArray())));
            target.put(UUID.fromString(e.getKey()), list);
        });
    }
}
