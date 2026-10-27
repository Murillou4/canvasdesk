package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Storage/index measurement only; it deliberately makes no claim about Minecraft frame time. */
@Tag("load")
class CanvasLoadTest {
    @TempDir Path directory;

    @Test void tenThousandElementsAcrossThirtyTwoByThirtyTwoModules() throws Exception {
        UUID owner = UUID.randomUUID(), workspace;
        long heapBefore = heap(), start = System.nanoTime(), writeEnd;
        try (CanvasStore store = new CanvasStore(directory)) {
            workspace = store.create(owner);
            for (int batch = 0; batch < 10; batch++) {
                JsonObject request = new JsonObject(); request.addProperty("action", "edit"); request.addProperty("operationId", UUID.randomUUID().toString());
                JsonArray changes = new JsonArray();
                for (int offset = 0; offset < 1000; offset++) {
                    int i = batch * 1000 + offset; UUID id = UUID.nameUUIDFromBytes(("load-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    JsonObject element = new JsonObject(); element.addProperty("id", id.toString()); element.addProperty("type", i % 3 == 0 ? "TEXT" : "RECTANGLE");
                    element.addProperty("x", (i % 100) * 81); element.addProperty("y", (i / 100) * 81);
                    element.addProperty("w", 48); element.addProperty("h", 32); element.addProperty("text", "Escrivaninha — ação " + i);
                    JsonObject change = new JsonObject(); change.addProperty("id", id.toString()); change.addProperty("expectedRevision", -1); change.add("after", element); changes.add(change);
                }
                request.add("changes", changes); JsonObject result = store.action(workspace, owner, request);
                assertTrue(result.get("ok").getAsBoolean(), result.toString());
            }
            writeEnd = System.nanoTime();
        }
        long restartStart = System.nanoTime(), restartEnd, viewStart, viewEnd, heapAfter;
        int visible;
        try (CanvasStore store = new CanvasStore(directory)) {
            restartEnd = System.nanoTime(); viewStart = System.nanoTime();
            JsonObject viewport = store.view(workspace, owner, 0, 0, 256, 256); viewEnd = System.nanoTime();
            assertTrue(viewport.get("ok").getAsBoolean()); visible = viewport.getAsJsonArray("elements").size();
            assertTrue(visible > 0 && visible < 100, "Region request should fetch a small subset: " + visible);
            assertEquals(10000, store.view(workspace, owner, -20, -20, 8192, 8192).getAsJsonArray("elements").size());
            heapAfter = heap();
        }
        JsonObject measurements = new JsonObject(); measurements.addProperty("scope", "core storage and region queries; excludes Minecraft rendering and network");
        measurements.addProperty("elements", 10000); measurements.addProperty("moduleWidth", 32); measurements.addProperty("moduleHeight", 32);
        measurements.addProperty("writeMillis", millis(writeEnd - start)); measurements.addProperty("restartMillis", millis(restartEnd - restartStart));
        measurements.addProperty("tileViewMillis", millis(viewEnd - viewStart)); measurements.addProperty("tileVisibleElements", visible);
        measurements.addProperty("heapBeforeBytes", heapBefore); measurements.addProperty("heapAfterFullViewBytes", heapAfter);
        long diskBytes;
        try (var paths = Files.walk(directory)) { diskBytes = paths.filter(Files::isRegularFile).mapToLong(path -> { try { return Files.size(path); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }).sum(); }
        measurements.addProperty("storageBytes", diskBytes);
        Path report = Path.of("build", "reports", "canvasdesk-core-load.json"); Files.createDirectories(report.getParent()); Files.writeString(report, measurements.toString());
        System.out.println("Canvas Desk core load: " + measurements);
    }
    private static double millis(long nanoseconds) { return nanoseconds / 1_000_000.0; }
    private static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
}
