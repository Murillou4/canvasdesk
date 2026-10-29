package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CanvasStoreTest {
    @Test void typingCheckpointsUndoAsOneActionAcrossRestart() throws Exception {
        UUID doc,element=UUID.randomUUID(),group=UUID.randomUUID();
        try(CanvasStore store=new CanvasStore(directory)){
            doc=store.create(owner);success(store.action(doc,owner,typing(element,-1,"ação",group)));
            success(store.action(doc,owner,typing(element,0,"ação\npróxima",group)));
        }
        try(CanvasStore store=new CanvasStore(directory)){
            success(store.action(doc,owner,typing(element,1,"ação\npróxima revisão",group)));
            success(store.action(doc,owner,action("undo")));assertEquals(0,store.elementCount(doc));
            success(store.action(doc,owner,action("redo")));assertEquals("ação\npróxima revisão",onlyElement(store,doc).get("text").getAsString());
        }
    }
    @Test void distinctTypingBurstsHaveSeparateUndoBoundaries() throws Exception {
        try(CanvasStore store=new CanvasStore(directory)){
            UUID doc=store.create(owner),id=UUID.randomUUID();
            success(store.action(doc,owner,typing(id,-1,"primeiro",UUID.randomUUID())));
            success(store.action(doc,owner,typing(id,0,"primeiro segundo",UUID.randomUUID())));
            success(store.action(doc,owner,action("undo")));assertEquals("primeiro",onlyElement(store,doc).get("text").getAsString());
            success(store.action(doc,owner,action("undo")));assertEquals(0,store.elementCount(doc));
        }
    }
    @Test void collaboratorEditEndsTypingCoalescingWithoutOverwritingTheirElement() throws Exception {
        try(CanvasStore store=new CanvasStore(directory)){
            UUID doc=store.create(owner),id=UUID.randomUUID(),peer=UUID.randomUUID(),group=UUID.randomUUID();
            success(store.action(doc,owner,invite(other)));
            success(store.action(doc,owner,typing(id,-1,"a",group)));
            success(store.action(doc,other,edit(UUID.randomUUID(),change(peer,-1,element(peer,10,10)))));
            success(store.action(doc,owner,typing(id,0,"ab",group)));
            success(store.action(doc,owner,action("undo")));
            assertEquals("a",store.element(doc,id).get("text").getAsString());assertNotNull(store.element(doc,peer));
            assertEquals(2,store.elementCount(doc));
        }
    }
    @Test void typingMetadataCannotBypassAtomicValidation() throws Exception {
        try(CanvasStore store=new CanvasStore(directory)){
            UUID doc=store.create(owner),id=UUID.randomUUID();
            JsonObject request=edit(UUID.randomUUID(),change(id,-1,element(id,0,0)));request.addProperty("typingGroup",UUID.randomUUID().toString());
            failure(store.action(doc,owner,request));assertEquals(0,store.elementCount(doc));
            failure(store.action(doc,other,typing(id,-1,"sem acesso",UUID.randomUUID())));assertEquals(0,store.elementCount(doc));
        }
    }
    private static JsonObject typing(UUID id,long revision,String text,UUID group){
        JsonObject e=element(id,20,30);e.addProperty("type","TEXT");e.addProperty("text",text);
        JsonObject r=edit(UUID.randomUUID(),change(id,revision,e));r.addProperty("typingGroup",group.toString());return r;
    }
    @Test void fontSizeDefaultsValidateAndSurviveRestart() throws Exception {
        JsonObject value=element(UUID.randomUUID(),0,0);assertEquals(9,CanvasElement.fromJson(value).fontSize());
        value.addProperty("fontSize",18);assertEquals(18,CanvasElement.fromJson(value).fontSize());
        for(double invalid:new double[]{7,97,Double.NaN,Double.POSITIVE_INFINITY}) {
            value.addProperty("fontSize",invalid);assertThrows(IllegalArgumentException.class,()->CanvasElement.fromJson(value));
        }
        value.addProperty("fontSize",36);
        assertEquals(36,CanvasElement.fromJson(CanvasElement.fromJson(value).translated(2,3,null).withRevision(4).toJson()).fontSize());
        UUID workspace;
        try(CanvasStore store=new CanvasStore(directory)) {
            workspace=store.create(owner);success(store.action(workspace,owner,edit(UUID.randomUUID(),change(UUID.fromString(value.get("id").getAsString()),-1,value))));
        }
        try(CanvasStore store=new CanvasStore(directory)){assertEquals(36,onlyElement(store,workspace).get("fontSize").getAsDouble());}
    }
    @TempDir Path directory;
    private final UUID owner = UUID.randomUUID(), other = UUID.randomUUID();

    private static JsonObject element(UUID id, double x, double y) {
        JsonObject e = new JsonObject(); e.addProperty("id", id.toString()); e.addProperty("type", "RECTANGLE");
        e.addProperty("x", x); e.addProperty("y", y); e.addProperty("w", 100); e.addProperty("h", 60); return e;
    }
    private static JsonObject change(UUID id, long expected, JsonObject after) {
        JsonObject c = new JsonObject(); c.addProperty("id", id.toString()); c.addProperty("expectedRevision", expected);
        c.add("after", after == null ? JsonNull.INSTANCE : after); return c;
    }
    private static JsonObject edit(UUID operationId, JsonObject... changes) {
        JsonObject r = action("edit"); r.addProperty("operationId", operationId.toString()); JsonArray a = new JsonArray();
        for (JsonObject change : changes) a.add(change); r.add("changes", a); return r;
    }
    private static JsonObject action(String name) { JsonObject r = new JsonObject(); r.addProperty("action", name); return r; }
    private static JsonObject invite(UUID id) { JsonObject r = action("invite"); r.addProperty("player", id.toString()); return r; }
    private static void success(JsonObject response) { assertTrue(response.get("ok").getAsBoolean(), response.toString()); }
    private static void failure(JsonObject response) { assertFalse(response.get("ok").getAsBoolean(), response.toString()); }
    private JsonObject view(CanvasStore store, UUID workspace) { return store.view(workspace, owner, -100000, -100000, 100000, 100000); }
    private JsonObject onlyElement(CanvasStore store, UUID workspace) { return view(store, workspace).getAsJsonArray("elements").get(0).getAsJsonObject(); }

    @Test void durableContentAndDedupSurviveRestart() throws Exception {
        UUID workspace, element = UUID.randomUUID(), operation = UUID.randomUUID();
        JsonObject request = edit(operation, change(element, -1, element(element, 21, 31)));
        try (CanvasStore store = new CanvasStore(directory)) { workspace = store.create(owner); success(store.action(workspace, owner, request)); }
        try (CanvasStore store = new CanvasStore(directory)) {
            JsonObject duplicate = store.action(workspace, owner, request); success(duplicate);
            assertTrue(duplicate.get("deduplicated").getAsBoolean()); assertEquals(0, onlyElement(store, workspace).get("revision").getAsLong());
            assertEquals(21, onlyElement(store, workspace).get("x").getAsDouble());
            assertEquals(1, view(store, workspace).get("revision").getAsLong());
        }
    }

    @Test void atomicBatchRejectsStaleRevisionWithoutChangingOtherElements() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID doc = store.create(owner), a = UUID.randomUUID(), b = UUID.randomUUID();
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 0, 0)), change(b, -1, element(b, 20, 0)))));
            failure(store.action(doc, owner, edit(UUID.randomUUID(), change(a, 0, element(a, 500, 0)), change(b, 7, element(b, 700, 0)))));
            assertEquals(1, view(store, doc).get("revision").getAsLong());
            for (var value : view(store, doc).getAsJsonArray("elements")) assertTrue(value.getAsJsonObject().get("x").getAsDouble() < 500);
        }
    }

    @Test void concurrentEditsHaveOneWinnerAtSameRevision() throws Exception {
        try (CanvasStore store = new CanvasStore(directory); var executor = Executors.newFixedThreadPool(8)) {
            UUID doc = store.create(owner), a = UUID.randomUUID();
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 0, 0)))));
            CountDownLatch start = new CountDownLatch(1); List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final int x = i;
                results.add(executor.submit(() -> { start.await(); return store.action(doc, owner,
                    edit(UUID.randomUUID(), change(a, 0, element(a, x, 0)))).get("ok").getAsBoolean(); }));
            }
            start.countDown(); int successes = 0; for (var result : results) if (result.get()) successes++;
            assertEquals(1, successes); assertEquals(1, onlyElement(store, doc).get("revision").getAsLong());
        }
    }

    @Test void permissionsAreAuthoritativeAndRecoveryListIsOwnerOnly() throws Exception {
        UUID doc;
        try (CanvasStore store = new CanvasStore(directory)) {
            doc = store.create(owner); UUID a = UUID.randomUUID();
            failure(store.action(doc, other, edit(UUID.randomUUID(), change(a, -1, element(a, 0, 0)))));
            success(store.action(doc, owner, invite(other)));
            success(store.action(doc, other, edit(UUID.randomUUID(), change(a, -1, element(a, 0, 0)))));
            failure(store.action(doc, other, invite(UUID.randomUUID())));
            assertEquals(0, store.list(other, false).size()); assertEquals(1, store.list(other, true).size());
            JsonObject revoke = action("revoke"); revoke.addProperty("player", other.toString()); success(store.action(doc, owner, revoke));
            assertFalse(store.view(doc, other, -100, -100, 500, 500).get("editable").getAsBoolean());
        }
        try (CanvasStore store = new CanvasStore(directory)) {
            assertFalse(store.view(doc, other, -100, -100, 500, 500).get("editable").getAsBoolean());
            assertEquals(1, store.list(owner, false).size());
        }
    }

    @Test void undoRedoSurviveRestartAndRefuseOtherPlayersNewerEdit() throws Exception {
        UUID doc, a = UUID.randomUUID();
        try (CanvasStore store = new CanvasStore(directory)) {
            doc = store.create(owner); success(store.action(doc, owner, invite(other)));
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 0, 0)))));
            success(store.action(doc, owner, action("undo"))); assertEquals(0, view(store, doc).getAsJsonArray("elements").size());
        }
        try (CanvasStore store = new CanvasStore(directory)) {
            success(store.action(doc, owner, action("redo"))); assertEquals(2, onlyElement(store, doc).get("revision").getAsLong());
            success(store.action(doc, other, edit(UUID.randomUUID(), change(a, 2, element(a, 300, 0)))));
            failure(store.action(doc, owner, action("undo"))); assertEquals(300, onlyElement(store, doc).get("x").getAsDouble());
            success(store.action(doc, other, action("undo"))); assertEquals(0, onlyElement(store, doc).get("x").getAsDouble());
        }
    }

    @Test void undoDoesNotConflictWithUnrelatedOtherPlayerElement() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID doc = store.create(owner), a = UUID.randomUUID(), b = UUID.randomUUID(); success(store.action(doc, owner, invite(other)));
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 0, 0)))));
            success(store.action(doc, other, edit(UUID.randomUUID(), change(b, -1, element(b, 500, 0)))));
            success(store.action(doc, owner, action("undo")));
            assertEquals(b.toString(), onlyElement(store, doc).get("id").getAsString());
        }
    }

    @Test void severalUndoRedoStepsRebaseRevisionsWithoutLosingConflictProtection() throws Exception {
        UUID doc, a = UUID.randomUUID(), b = UUID.randomUUID();
        try (CanvasStore store = new CanvasStore(directory)) {
            doc = store.create(owner);
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 10, 0)))));
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(b, -1, element(b, 50, 0)))));
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, 0, element(a, 20, 0)))));
            success(store.action(doc, owner, action("undo"))); assertEquals(10, find(view(store, doc), a).get("x").getAsDouble());
            success(store.action(doc, owner, action("undo"))); assertEquals(1, view(store, doc).getAsJsonArray("elements").size());
        }
        try (CanvasStore store = new CanvasStore(directory)) {
            success(store.action(doc, owner, action("undo"))); assertEquals(0, view(store, doc).getAsJsonArray("elements").size());
            success(store.action(doc, owner, action("redo"))); success(store.action(doc, owner, action("redo"))); success(store.action(doc, owner, action("redo")));
            assertEquals(20, find(view(store, doc), a).get("x").getAsDouble()); assertEquals(50, find(view(store, doc), b).get("x").getAsDouble());
        }
    }

    @Test void incompleteJournalTailIsQuarantinedAndLastDurableStateRestored() throws Exception {
        UUID doc;
        try (CanvasStore store = new CanvasStore(directory)) { doc = store.create(owner); }
        Files.writeString(directory.resolve("operations.jsonl"), "{\"sequence\":2,\"event\":", StandardOpenOption.APPEND);
        try (CanvasStore store = new CanvasStore(directory)) { assertEquals(doc, store.resolve(doc)); store.create(owner); }
        try (var files = Files.list(directory)) { assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("operations.corrupt-"))); }
        try (CanvasStore store = new CanvasStore(directory)) { assertEquals(2, store.list(owner, false).size()); }
    }

    @Test void corruptedFinalCompleteJournalRecordIsQuarantined() throws Exception {
        UUID doc;
        try (CanvasStore store = new CanvasStore(directory)) { doc = store.create(owner); }
        Files.writeString(directory.resolve("operations.jsonl"), "{\"sequence\":2,\"sha256\":\"bad\",\"event\":{\"kind\":\"create\"}}\n", StandardOpenOption.APPEND);
        try (CanvasStore store = new CanvasStore(directory)) { assertEquals(doc, store.resolve(doc)); }
    }

    @Test void corruptMiddleJournalFailsWithoutDiscardingLaterRecords() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) { store.create(owner); store.create(owner); }
        Path journal = directory.resolve("operations.jsonl"); List<String> lines = Files.readAllLines(journal);
        Files.writeString(journal, lines.getFirst().replace("\"sha256\":\"", "\"sha256\":\"BROKEN") + "\n" + lines.getLast() + "\n");
        long size = Files.size(journal); assertThrows(IOException.class, () -> new CanvasStore(directory)); assertEquals(size, Files.size(journal));
    }

    @Test void badSnapshotsRecoverFromFullJournal() throws Exception {
        UUID doc, a = UUID.randomUUID();
        try (CanvasStore store = new CanvasStore(directory)) {
            doc = store.create(owner); success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 1250, -1024)))));
        }
        Files.writeString(directory.resolve("snapshot.json"), "damaged"); Files.writeString(directory.resolve("snapshot.previous.json"), "also damaged");
        try (CanvasStore store = new CanvasStore(directory)) { assertEquals(1250, onlyElement(store, doc).get("x").getAsDouble()); }
    }

    @Test void mergeConsentPreservesIdsTranslationsAndPermissionsAcrossRestart() throws Exception {
        UUID target, source, a = UUID.randomUUID(), editor = UUID.randomUUID();
        try (CanvasStore store = new CanvasStore(directory)) {
            target = store.create(owner); source = store.create(other); success(store.action(source, other, invite(editor)));
            success(store.action(source, other, edit(UUID.randomUUID(), change(a, -1, element(a, 17, 23)))));
            JsonObject pending = store.requestMerge(target, source, 256, -256, owner); success(pending); assertTrue(pending.get("pending").getAsBoolean());
            assertEquals(source, store.resolve(source));
            JsonObject state = view(store, target); JsonObject merge = state.getAsJsonArray("merges").get(0).getAsJsonObject();
            assertEquals(2, merge.getAsJsonArray("owners").size()); assertEquals(1, merge.getAsJsonArray("editors").size());
            JsonObject approve = action("approveMerge"); approve.addProperty("requestId", pending.get("requestId").getAsString());
            assertEquals(0,merge.getAsJsonArray("approved").size(),"Physical request must not imply owner consent");
            failure(store.action(target, editor, approve)); success(store.action(source, other, approve));
            assertEquals(source,store.resolve(source),"One of two owners is still pending");
            success(store.action(target,owner,approve));
            assertEquals(target, store.resolve(source)); assertArrayEquals(new double[]{256, -256}, store.projectionOffset(source));
            JsonObject e = onlyElement(store, target); assertEquals(a.toString(), e.get("id").getAsString());
            assertEquals(273, e.get("x").getAsDouble()); assertEquals(-233, e.get("y").getAsDouble());
            assertEquals(source.toString(), e.get("group").getAsString());
        }
        try (CanvasStore store = new CanvasStore(directory)) {
            assertEquals(target, store.resolve(source)); assertArrayEquals(new double[]{256, -256}, store.projectionOffset(source));
            assertTrue(store.view(target, editor, 0, -500, 1000, 500).get("editable").getAsBoolean());
            assertEquals(2, view(store, target).getAsJsonArray("owners").size());
        }
    }

    @Test void aclAndTopologyChangesInvalidateConsent() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID target = store.create(owner), source = store.create(other);
            JsonObject pending = store.requestMerge(target, source, 256, 0, owner); success(pending);
            success(store.action(target, owner, invite(UUID.randomUUID()))); assertEquals(0, view(store, target).getAsJsonArray("merges").size());
            JsonObject approve = action("approveMerge"); approve.addProperty("requestId", pending.get("requestId").getAsString()); failure(store.action(source, other, approve));
            success(store.requestMerge(target, source, 256, 0, owner)); store.invalidateMerges(source);
            assertEquals(0, view(store, target).getAsJsonArray("merges").size()); assertEquals(source, store.resolve(source));
        }
    }

    @Test void sameOwnerMergeIsAutomaticAndAliasOffsetsAccumulate() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID first = store.create(owner), second = store.create(owner), third = store.create(owner), a = UUID.randomUUID();
            success(store.action(first, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 1, 2)))));
            success(store.requestMerge(second, first, 256, 128, owner)); success(store.requestMerge(third, second, -512, 16, owner));
            assertEquals(third, store.resolve(first)); assertArrayEquals(new double[]{-256, 144}, store.projectionOffset(first));
            assertEquals(-255, onlyElement(store, third).get("x").getAsDouble()); assertEquals(146, onlyElement(store, third).get("y").getAsDouble());
            assertEquals(first.toString(), onlyElement(store, third).get("group").getAsString());
        }
    }

    @Test void regionViewsAreClippedByElementBoundsAndIncludeCrossingStrokes() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID doc = store.create(owner), a = UUID.randomUUID(), b = UUID.randomUUID();
            JsonObject stroke = element(a, 0, 0); stroke.addProperty("type", "STROKE"); JsonArray points = new JsonArray();
            JsonObject p1 = new JsonObject(); p1.addProperty("x", -1200); p1.addProperty("y", 0); points.add(p1);
            JsonObject p2 = new JsonObject(); p2.addProperty("x", 1200); p2.addProperty("y", 0); points.add(p2); stroke.add("points", points);
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, stroke), change(b, -1, element(b, 10000, 10000)))));
            JsonObject state = store.view(doc, owner, -1201, -5, -1199, 5); success(state);
            assertEquals(1, state.getAsJsonArray("elements").size()); assertTrue(state.getAsJsonObject("bounds").get("maxX").getAsDouble() > 10000);
        }
    }

    @Test void overlapLayerOrderIsStableAcrossRegionsEditingUndoAndRestart() throws Exception {
        UUID doc, a = UUID.randomUUID(), b = UUID.randomUUID();
        try (CanvasStore store = new CanvasStore(directory)) {
            doc = store.create(owner);
            JsonObject lower = element(a, 1000, 0), upper = element(b, 1000, 0);
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, lower))));
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(b, -1, upper))));
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, 0, element(a, 1001, 0)))));
            assertOrder(store.view(doc, owner, 990, 0, 1020, 100), a, b);
            assertOrder(store.view(doc, owner, 1024, 0, 1100, 100), a, b);
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, 1, null)))); success(store.action(doc, owner, action("undo")));
            assertOrder(store.view(doc, owner, 990, 0, 1100, 100), a, b);
        }
        try (CanvasStore store = new CanvasStore(directory)) { assertOrder(store.view(doc, owner, 990, 0, 1100, 100), a, b); }
    }

    private static void assertOrder(JsonObject state, UUID first, UUID second) {
        success(state); JsonArray elements = state.getAsJsonArray("elements"); assertEquals(2, elements.size());
        assertEquals(first.toString(), elements.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(second.toString(), elements.get(1).getAsJsonObject().get("id").getAsString());
    }

    @Test void liveConnectorsFollowMovesAndDetachSafelyOnDeleteUndo() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID doc = store.create(owner), a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
            JsonObject connector = element(c, 0, 0); connector.addProperty("type", "CONNECTOR");
            connector.addProperty("from", a.toString()); connector.addProperty("to", b.toString());
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 0, 0)), change(b, -1, element(b, 500, 0)), change(c, -1, connector))));
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, 0, element(a, 200, 100)))));
            JsonObject link = find(view(store, doc), c); assertEquals(300, link.getAsJsonArray("points").get(0).getAsJsonObject().get("x").getAsDouble());
            success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, 1, null)))); assertFalse(find(view(store, doc), c).has("from"));
            success(store.action(doc, owner, action("undo"))); assertEquals(a.toString(), find(view(store, doc), c).get("from").getAsString());
        }
    }

    @Test void duplicateElementIdsBlockMergeWithoutDestroyingEitherDocument() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID a = store.create(owner), b = store.create(owner), id = UUID.randomUUID();
            success(store.action(a, owner, edit(UUID.randomUUID(), change(id, -1, element(id, 1, 2)))));
            success(store.action(b, owner, edit(UUID.randomUUID(), change(id, -1, element(id, 3, 4)))));
            failure(store.requestMerge(a, b, 256, 0, owner)); assertEquals(a, store.resolve(a)); assertEquals(b, store.resolve(b));
        }
    }

    @Test void invalidInputFailsSafelyAndUnknownDocumentIsNeverRecreated() throws Exception {
        try (CanvasStore store = new CanvasStore(directory)) {
            UUID doc = store.create(owner), unknown = UUID.randomUUID();
            failure(store.action(doc, owner, action("edit"))); failure(store.action(unknown, owner, action("undo")));
            assertThrows(IllegalArgumentException.class, () -> store.resolve(unknown)); assertEquals(1, store.list(owner, true).size());
            JsonObject bad = element(UUID.randomUUID(), 0, 0); bad.addProperty("x", "NaN");
            failure(store.action(doc, owner, edit(UUID.randomUUID(), change(UUID.fromString(bad.get("id").getAsString()), -1, bad))));
            assertEquals(0, view(store, doc).getAsJsonArray("elements").size());
        }
    }

    @Test void exclusiveLockPreventsSecondWriter() throws Exception {
        try (CanvasStore ignored = new CanvasStore(directory)) { assertThrows(IOException.class, () -> new CanvasStore(directory)); }
        try (CanvasStore store = new CanvasStore(directory)) { store.create(owner); }
    }

    @Test void accentedMultilineTextChecklistAndEveryPrimitiveCodecRoundTrip() {
        for (CanvasElement.Type type : CanvasElement.Type.values()) {
            CanvasElement element = new CanvasElement(UUID.randomUUID(), type, -81, 17, 100, 200, 0xFFFFFF, 4.5f,
                "Ação e organização\nSegunda linha: São Paulo", List.of(new CanvasElement.Point(-81, 17), new CanvasElement.Point(19, 217)),
                UUID.randomUUID(), UUID.randomUUID(), List.of(true, false, true), 73, UUID.randomUUID());
            assertEquals(element, CanvasElement.fromJson(element.toJson()));
        }
    }

    @Test void corruptPayloadIsReportedWithoutReplacingOriginalDocument() throws Exception {
        UUID doc;
        try (CanvasStore store = new CanvasStore(directory)) {
            doc = store.create(owner); UUID a = UUID.randomUUID(); success(store.action(doc, owner, edit(UUID.randomUUID(), change(a, -1, element(a, 1, 2)))));
        }
        try (var paths = Files.walk(directory.resolve("objects"))) {
            Path object = paths.filter(Files::isRegularFile).findFirst().orElseThrow(); Files.writeString(object, "damaged", StandardCharsets.UTF_8);
        }
        try (CanvasStore store = new CanvasStore(directory)) { failure(view(store, doc)); assertEquals(doc, store.resolve(doc)); assertEquals(1, store.list(owner, true).size()); }
    }
    private static JsonObject find(JsonObject state, UUID id) {
        for (var value : state.getAsJsonArray("elements")) if (value.getAsJsonObject().get("id").getAsString().equals(id.toString())) return value.getAsJsonObject();
        fail("Missing element " + id); return null;
    }
}
