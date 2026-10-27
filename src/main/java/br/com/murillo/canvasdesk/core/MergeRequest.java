package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

final class MergeRequest {
    final UUID id, target, source;
    final double dx, dy;
    final Set<UUID> owners, editors;
    final Set<UUID> approved = new LinkedHashSet<>();
    MergeRequest(UUID id, UUID target, UUID source, double dx, double dy, Set<UUID> owners, Set<UUID> editors) {
        this.id = id; this.target = target; this.source = source; this.dx = dx; this.dy = dy;
        this.owners = new LinkedHashSet<>(owners); this.editors = new LinkedHashSet<>(editors);
    }
    JsonObject toJson() {
        JsonObject o = new JsonObject(); o.addProperty("id", id.toString());
        o.addProperty("target", target.toString()); o.addProperty("source", source.toString());
        o.addProperty("dx", dx); o.addProperty("dy", dy);
        o.add("owners", DocumentState.ids(owners)); o.add("editors", DocumentState.ids(editors)); o.add("approved", DocumentState.ids(approved)); return o;
    }
    static MergeRequest fromJson(JsonObject o) {
        MergeRequest r = new MergeRequest(UUID.fromString(o.get("id").getAsString()), UUID.fromString(o.get("target").getAsString()),
            UUID.fromString(o.get("source").getAsString()), o.get("dx").getAsDouble(), o.get("dy").getAsDouble(),
            DocumentState.readIds(o.getAsJsonArray("owners")), DocumentState.readIds(o.getAsJsonArray("editors")));
        r.approved.addAll(DocumentState.readIds(o.getAsJsonArray("approved"))); return r;
    }
}
