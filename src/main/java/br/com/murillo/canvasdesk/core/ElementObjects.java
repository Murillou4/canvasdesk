package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Content-addressed immutable payloads, loaded lazily with a bounded LRU. */
final class ElementObjects {
    private static final long MAX_CACHE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CACHE_ELEMENTS = 2048;
    private record Cached(CanvasElement element, long bytes) {}
    private final Path directory;
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(32, 0.75f, true);
    private long cacheBytes;
    ElementObjects(Path root) throws IOException { directory = root.resolve("objects"); Files.createDirectories(directory); }

    ElementIndex.Entry put(CanvasElement element) throws IOException {
        byte[] bytes = element.toJson().toString().getBytes(StandardCharsets.UTF_8);
        String hash = DurableJournal.hash(bytes); Path path = objectPath(hash);
        if (!Files.exists(path)) DurableJournal.atomicWrite(path, bytes);
        remember(hash, element, bytes.length);
        return new ElementIndex.Entry(element.id(), element.revision(), element.type(), element.bounds(), hash, 0);
    }
    CanvasElement get(ElementIndex.Entry entry) throws IOException {
        Cached cached = cache.get(entry.object()); if (cached != null) return cached.element;
        byte[] bytes = Files.readAllBytes(objectPath(entry.object()));
        if (!DurableJournal.hash(bytes).equals(entry.object())) throw new IOException("Canvas element checksum mismatch: " + entry.id());
        try {
            CanvasElement element = CanvasElement.fromJson(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject());
            if (!element.id().equals(entry.id()) || element.revision() != entry.revision()) throw new IOException("Canvas element metadata mismatch.");
            remember(entry.object(), element, bytes.length); return element;
        } catch (RuntimeException error) { throw new IOException("Invalid Canvas element " + entry.id(), error); }
    }
    private Path objectPath(String hash) throws IOException {
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid object hash.");
        return directory.resolve(hash.substring(0, 2)).resolve(hash + ".json");
    }
    private void remember(String hash, CanvasElement element, long bytes) {
        Cached previous = cache.put(hash, new Cached(element, bytes)); if (previous != null) cacheBytes -= previous.bytes;
        cacheBytes += bytes;
        while (cache.size() > MAX_CACHE_ELEMENTS || cacheBytes > MAX_CACHE_BYTES) {
            Map.Entry<String, Cached> oldest = cache.entrySet().iterator().next(); cacheBytes -= oldest.getValue().bytes;
            cache.remove(oldest.getKey());
        }
    }
}
