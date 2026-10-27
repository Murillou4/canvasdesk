package br.com.murillo.canvasdesk.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A client projection of the transport format; never a second persistent document. */
public final class CanvasElementView {
    public record Point(double x, double y) {}

    public final JsonObject json;
    public final String id, type, text, from, to, group;
    public final double x, y, w, h, thickness;
    public final int color;
    public final long revision;
    public final List<Point> points;
    public final List<Boolean> checks;

    public CanvasElementView(JsonObject object) {
        json = object.deepCopy();
        id = string(object, "id", "");
        type = string(object, "type", "RECTANGLE");
        text = string(object, "text", "");
        from = string(object, "from", "");
        to = string(object, "to", "");
        group = string(object, "group", "");
        x = number(object, "x", 0); y = number(object, "y", 0);
        w = number(object, "w", 100); h = number(object, "h", 60);
        color = (int) number(object, "color", 0x30302c);
        thickness = number(object, "thickness", 3);
        revision = (long) number(object, "revision", 0);
        points = new ArrayList<>();
        if (object.has("points") && object.get("points").isJsonArray()) {
            for (JsonElement p : object.getAsJsonArray("points")) {
                if (p.isJsonObject()) points.add(new Point(number(p.getAsJsonObject(), "x", 0), number(p.getAsJsonObject(), "y", 0)));
            }
        }
        checks = new ArrayList<>();
        if (object.has("checks") && object.get("checks").isJsonArray()) {
            for (JsonElement p : object.getAsJsonArray("checks")) checks.add(p.getAsBoolean());
        }
    }

    public double centerX() { return x + w / 2; }
    public double centerY() { return y + h / 2; }

    public CanvasElementView translated(double dx, double dy) {
        JsonObject copy = json.deepCopy();
        copy.addProperty("x", x + dx); copy.addProperty("y", y + dy);
        JsonArray shifted = new JsonArray();
        for (Point p : points) shifted.add(point(p.x + dx, p.y + dy));
        copy.add("points", shifted);
        return new CanvasElementView(copy);
    }

    public CanvasElementView resized(double width, double height) {
        JsonObject copy = json.deepCopy();
        width = Math.max(8, width); height = Math.max(8, height);
        copy.addProperty("w", width); copy.addProperty("h", height);
        JsonArray scaled = new JsonArray();
        for (Point p : points) scaled.add(point(x + (p.x - x) * width / Math.max(1, w), y + (p.y - y) * height / Math.max(1, h)));
        copy.add("points", scaled);
        return new CanvasElementView(copy);
    }

    public static JsonObject create(String type, double x, double y, double w, double h, int color, double thickness) {
        JsonObject e = new JsonObject();
        e.addProperty("id", UUID.randomUUID().toString()); e.addProperty("type", type);
        e.addProperty("x", x); e.addProperty("y", y); e.addProperty("w", w); e.addProperty("h", h);
        e.addProperty("color", color); e.addProperty("thickness", thickness); e.addProperty("text", "");
        e.addProperty("revision", 0); e.add("points", new JsonArray()); e.add("checks", new JsonArray());
        return e;
    }

    public static JsonObject point(double x, double y) {
        JsonObject point = new JsonObject(); point.addProperty("x", x); point.addProperty("y", y); return point;
    }

    public static String string(JsonObject object, String name, String fallback) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
    }

    public static double number(JsonObject object, String name, double fallback) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsDouble() : fallback;
    }
}
