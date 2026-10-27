package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Append, force, then apply. A snapshot never replaces the recoverable journal. */
final class DurableJournal implements AutoCloseable {
    interface EventReader { void apply(JsonObject event) throws IOException; }
    record Snapshot(long sequence, JsonObject state) {}
    private final Path directory;
    private final Path journalPath;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private FileChannel journal;
    private long sequence;
    private long eventsSinceSnapshot;
    private boolean writeFailed;

    DurableJournal(Path directory) throws IOException {
        this.directory = directory; Files.createDirectories(directory);
        journalPath = directory.resolve("operations.jsonl");
        lockChannel = FileChannel.open(directory.resolve("store.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Canvas Desk storage is already open: " + directory);
        } catch (RuntimeException | IOException error) { lockChannel.close(); throw new IOException("Could not lock Canvas Desk storage.", error); }
    }

    Snapshot readSnapshot() throws IOException {
        Path current = directory.resolve("snapshot.json"), previous = directory.resolve("snapshot.previous.json");
        IOException first = null;
        for (Path candidate : new Path[]{current, previous}) {
            if (!Files.exists(candidate)) continue;
            try {
                JsonObject envelope = JsonParser.parseString(Files.readString(candidate)).getAsJsonObject();
                if (envelope.get("version").getAsInt() != 1) throw new IOException("Unsupported Canvas Desk snapshot version.");
                JsonObject state = envelope.getAsJsonObject("state");
                if (!hash(state.toString()).equals(envelope.get("sha256").getAsString())) throw new IOException("Snapshot checksum mismatch.");
                sequence = envelope.get("sequence").getAsLong(); return new Snapshot(sequence, state);
            } catch (RuntimeException | IOException error) {
                if (first == null) first = new IOException("Invalid Canvas Desk snapshot: " + candidate, error);
            }
        }
        // The append-only journal is sufficient even if both snapshots were damaged.
        if (Files.exists(journalPath) && Files.size(journalPath) > 0) return new Snapshot(0, new JsonObject());
        if (first != null) throw first;
        return new Snapshot(0, new JsonObject());
    }

    void replay(long snapshotSequence, EventReader consumer) throws IOException {
        long goodBytes = 0, recordSequence = 0;
        if (Files.exists(journalPath)) {
            try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(journalPath))) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int b;
                while ((b = input.read()) != -1) {
                    if (b != '\n') { line.write(b); continue; }
                    byte[] bytes = line.toByteArray(); line.reset();
                    try {
                        JsonObject envelope = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                        long next = envelope.get("sequence").getAsLong();
                        JsonObject event = envelope.getAsJsonObject("event");
                        if (next != recordSequence + 1 || !hash(event.toString()).equals(envelope.get("sha256").getAsString()))
                            throw new IllegalArgumentException("Journal checksum or sequence mismatch.");
                        if (next > snapshotSequence) consumer.apply(event);
                        recordSequence = next; sequence = Math.max(sequence, next); goodBytes += bytes.length + 1;
                    } catch (RuntimeException error) {
                        if (hasNonWhitespace(input)) throw new IOException("Corrupt Canvas Desk journal before its final record. Storage preserved for recovery.", error);
                        quarantineTail(goodBytes); break;
                    }
                }
                if (line.size() > 0) quarantineTail(goodBytes);
            }
        }
        if (snapshotSequence > recordSequence)
            throw new IOException("Canvas Desk journal is older than the snapshot; storage may have been incompletely copied.");
        journal = FileChannel.open(journalPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        journal.position(journal.size());
    }

    private static boolean hasNonWhitespace(BufferedInputStream input) throws IOException {
        int b; while ((b = input.read()) != -1) if (b != ' ' && b != '\t' && b != '\r' && b != '\n') return true;
        return false;
    }

    private void quarantineTail(long goodBytes) throws IOException {
        Path copy = directory.resolve("operations.corrupt-" + System.currentTimeMillis() + ".jsonl");
        try (FileChannel source = FileChannel.open(journalPath, StandardOpenOption.READ);
             FileChannel target = FileChannel.open(copy, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long position = goodBytes;
            while (position < source.size()) {
                long count = source.transferTo(position, source.size() - position, target);
                if (count <= 0) throw new IOException("Could not preserve incomplete journal tail.");
                position += count;
            }
            target.force(true);
        }
        try (FileChannel target = FileChannel.open(journalPath, StandardOpenOption.WRITE)) { target.truncate(goodBytes); target.force(true); }
    }

    void append(JsonObject event) throws IOException {
        if (writeFailed) throw new IOException("Journal is unavailable after an I/O failure; repair storage and restart before editing.");
        JsonObject envelope = new JsonObject(); envelope.addProperty("sequence", sequence + 1);
        envelope.addProperty("sha256", hash(event.toString())); envelope.add("event", event);
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(envelope + "\n");
        long start = journal.size();
        try {
            while (bytes.hasRemaining()) journal.write(bytes);
            journal.force(true); sequence++; eventsSinceSnapshot++;
        } catch (IOException error) {
            writeFailed = true;
            // A rejected operation must not leave a parseable record for the next restart.
            try { journal.truncate(start); journal.position(start); journal.force(true); }
            catch (IOException recovery) { error.addSuppressed(recovery); }
            throw error;
        }
    }

    boolean checkpointDue() { return eventsSinceSnapshot >= 128; }
    void checkpoint(JsonObject state) throws IOException {
        JsonObject envelope = new JsonObject(); envelope.addProperty("version", 1); envelope.addProperty("sequence", sequence);
        envelope.addProperty("sha256", hash(state.toString())); envelope.add("state", state);
        Path current = directory.resolve("snapshot.json");
        if (Files.exists(current)) {
            Path previous = directory.resolve("snapshot.previous.json");
            atomicWrite(previous, Files.readAllBytes(current));
        }
        atomicWrite(current, envelope.toString().getBytes(StandardCharsets.UTF_8)); eventsSinceSnapshot = 0;
    }

    static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + java.util.UUID.randomUUID());
        try {
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) out.write(buffer); out.force(true);
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    static String hash(String value) { return hash(value.getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    @Override public void close() throws IOException {
        try { if (journal != null) journal.close(); } finally { try { lock.release(); } finally { lockChannel.close(); } }
    }
}
