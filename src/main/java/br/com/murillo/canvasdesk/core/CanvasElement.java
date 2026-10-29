package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Immutable document primitive. JSON is deliberately confined to this boundary. */
public record CanvasElement(UUID id, Type type, double x, double y, double w, double h,
                            int color, float thickness, String text, List<Point> points,
                            UUID from, UUID to, List<Boolean> checks, long revision, UUID group, double fontSize) {
    public enum Type { STROKE, TEXT, RECTANGLE, ELLIPSE, LINE, ARROW, CONNECTOR, NOTE, CHECKLIST }
    public CanvasElement(UUID id, Type type, double x, double y, double w, double h, int color, float thickness,
                         String text, List<Point> points, UUID from, UUID to, List<Boolean> checks, long revision, UUID group) {
        this(id,type,x,y,w,h,color,thickness,text,points,from,to,checks,revision,group,9);
    }
    public record Point(double x, double y) {}
    public record Bounds(double minX, double minY, double maxX, double maxY) {
        public boolean intersects(Bounds other) {
            return maxX >= other.minX && minX <= other.maxX && maxY >= other.minY && minY <= other.maxY;
        }
    }

    public CanvasElement {
        if (id == null || type == null || text == null) throw new IllegalArgumentException("Elemento incompleto.");
        finite(x); finite(y); finite(w); finite(h);
        if (w < 0 || h < 0) throw new IllegalArgumentException("Dimensões negativas.");
        if (!Double.isFinite(fontSize) || fontSize < 8 || fontSize > 96) throw new IllegalArgumentException("Fonte deve estar entre 8 e 96.");
        if (!Float.isFinite(thickness) || thickness < 0.25f || thickness > 128f)
            throw new IllegalArgumentException("Espessura deve estar entre 0,25 e 128.");
        if (text.length() > 65_536 || points.size() > 65_536 || checks.size() > 16_384)
            throw new IllegalArgumentException("Elemento excede o tamanho permitido por operação.");
        points = List.copyOf(points);
        checks = List.copyOf(checks);
        for (Point p : points) { finite(p.x); finite(p.y); }
        finite(x + w); finite(y + h);
        color &= 0xFFFFFF;
    }

    static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Coordenadas devem ser finitas.");
    }

    public Bounds bounds() {
        double minX = x, minY = y, maxX = x + w, maxY = y + h;
        for (Point p : points) {
            minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
            maxX = Math.max(maxX, p.x); maxY = Math.max(maxY, p.y);
        }
        double padding = thickness / 2.0 + 2;
        return new Bounds(minX - padding, minY - padding, maxX + padding, maxY + padding);
    }

    public CanvasElement withRevision(long next) {
        return new CanvasElement(id, type, x, y, w, h, color, thickness, text, points, from, to, checks, next, group, fontSize);
    }

    public CanvasElement translated(double dx, double dy, UUID defaultGroup) {
        return new CanvasElement(id, type, x + dx, y + dy, w, h, color, thickness, text,
            points.stream().map(p -> new Point(p.x + dx, p.y + dy)).toList(), from, to, checks,
            revision + 1, group == null ? defaultGroup : group, fontSize);
    }

    CanvasElement connected(CanvasElement start, CanvasElement end, boolean removeStart, boolean removeEnd) {
        Point a = start == null ? new Point(x, y) : new Point(start.x + start.w / 2, start.y + start.h / 2);
        Point b = end == null ? new Point(x + w, y + h) : new Point(end.x + end.w / 2, end.y + end.h / 2);
        if (!points.isEmpty()) {
            if (start == null) a = points.getFirst();
            if (end == null) b = points.getLast();
        }
        Point centerA = a;
        if (start != null) a = edge(start, b);
        if (end != null) b = edge(end, centerA);
        return new CanvasElement(id, type, Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x),
            Math.abs(b.y - a.y), color, thickness, text, List.of(a, b), removeStart ? null : from,
            removeEnd ? null : to, checks, revision, group, fontSize);
    }

    private static Point edge(CanvasElement element, Point toward) {
        double cx = element.x + element.w / 2, cy = element.y + element.h / 2;
        double dx = toward.x - cx, dy = toward.y - cy;
        double divisor = Math.max(Math.abs(dx) / Math.max(1, element.w / 2), Math.abs(dy) / Math.max(1, element.h / 2));
        return divisor > 0 ? new Point(cx + dx / divisor, cy + dy / divisor) : new Point(cx, cy);
    }

    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id.toString()); out.addProperty("type", type.name());
        out.addProperty("x", x); out.addProperty("y", y); out.addProperty("w", w); out.addProperty("h", h);
        out.addProperty("color", color); out.addProperty("thickness", thickness); out.addProperty("text", text);
        out.addProperty("revision", revision);
        out.addProperty("fontSize", fontSize);
        if (from != null) out.addProperty("from", from.toString());
        if (to != null) out.addProperty("to", to.toString());
        if (group != null) out.addProperty("group", group.toString());
        JsonArray p = new JsonArray();
        for (Point point : points) {
            JsonObject item = new JsonObject(); item.addProperty("x", point.x); item.addProperty("y", point.y); p.add(item);
        }
        out.add("points", p);
        JsonArray c = new JsonArray(); checks.forEach(c::add); out.add("checks", c);
        return out;
    }

    public static CanvasElement fromJson(JsonObject input) {
        List<Point> points = new ArrayList<>();
        if (input.has("points")) for (JsonElement p : input.getAsJsonArray("points")) {
            JsonObject o = p.getAsJsonObject(); points.add(new Point(number(o, "x", 0), number(o, "y", 0)));
        }
        List<Boolean> checks = new ArrayList<>();
        if (input.has("checks")) for (JsonElement value : input.getAsJsonArray("checks")) checks.add(value.getAsBoolean());
        return new CanvasElement(UUID.fromString(input.get("id").getAsString()),
            Type.valueOf(input.get("type").getAsString()), number(input, "x", 0), number(input, "y", 0),
            number(input, "w", 100), number(input, "h", 60), (int) number(input, "color", 0x30302c),
            (float) number(input, "thickness", 3), input.has("text") ? input.get("text").getAsString() : "",
            points, optionalId(input, "from"), optionalId(input, "to"), checks,
            input.has("revision") ? input.get("revision").getAsLong() : 0, optionalId(input, "group"), number(input, "fontSize", 9));
    }

    private static double number(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }
    static UUID optionalId(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? UUID.fromString(o.get(key).getAsString()) : null;
    }
}
