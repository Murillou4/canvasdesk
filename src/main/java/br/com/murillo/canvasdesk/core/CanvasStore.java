package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Server-side authority. Its synchronized facade serializes validation and durable commit. */
public final class CanvasStore implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(CanvasStore.class.getName());
    private record Alias(UUID target, double dx, double dy) {}
    private final Map<UUID, DocumentState> documents = new LinkedHashMap<>();
    private final Map<UUID, Alias> aliases = new LinkedHashMap<>();
    private final Map<UUID, MergeRequest> merges = new LinkedHashMap<>();
    private final DurableJournal journal;
    private final ElementObjects objects;
    private boolean closed;

    public CanvasStore(Path directory) throws IOException {
        journal = new DurableJournal(directory);
        try {
            objects = new ElementObjects(directory);
            DurableJournal.Snapshot snapshot = journal.readSnapshot();
            loadSnapshot(snapshot.state()); journal.replay(snapshot.sequence(), this::applyEvent);
        } catch (RuntimeException | IOException error) {
            try { journal.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw new IOException("Could not recover Canvas Desk documents. Existing storage was preserved.", error);
        }
    }

    public synchronized UUID create(UUID owner) {
        ensureOpen(); if (owner == null) throw new IllegalArgumentException("Dono ausente.");
        DocumentState doc = new DocumentState(UUID.randomUUID()); doc.owners.add(owner);
        JsonObject event = event("create"); event.add("document", doc.toJson());
        try { commit(event); } catch (IOException error) { throw new UncheckedIOException(error); }
        return doc.id;
    }

    public synchronized UUID resolve(UUID workspace) {
        ensureOpen(); if (workspace == null) throw new IllegalArgumentException("Documento ausente.");
        Set<UUID> visited = new HashSet<>(); UUID id = workspace;
        while (aliases.containsKey(id)) {
            if (!visited.add(id)) throw new IllegalStateException("Cyclic document aliases.");
            id = aliases.get(id).target;
        }
        if (!documents.containsKey(id)) throw new IllegalArgumentException("Documento desconhecido: " + workspace);
        return id;
    }

    /** Count index entries without loading every element payload during a server diagnostic. */
    public synchronized int elementCount(UUID workspace) { return document(workspace).index.entries.size(); }
    public synchronized boolean canEdit(UUID workspace,UUID actor){return document(workspace).editable(actor);}
    public synchronized JsonObject element(UUID workspace,UUID id){
        var entry=document(workspace).index.entries.get(id);if(entry==null)return null;
        try{return objects.get(entry).toJson();}catch(IOException e){throw new UncheckedIOException(e);}
    }

    /** Offset to add to a module's saved coordinates after resolving its old workspace id. */
    public synchronized double[] projectionOffset(UUID workspace) {
        resolve(workspace); double dx = 0, dy = 0; UUID id = workspace;
        while (aliases.containsKey(id)) { Alias alias = aliases.get(id); dx += alias.dx; dy += alias.dy; id = alias.target; }
        return new double[]{dx, dy};
    }

    public synchronized JsonObject view(UUID workspace, UUID viewer, double minX, double minY, double maxX, double maxY) {
        try {
            DocumentState doc = document(workspace);
            CanvasElement.finite(minX); CanvasElement.finite(minY); CanvasElement.finite(maxX); CanvasElement.finite(maxY);
            if (maxX < minX || maxY < minY) throw new IllegalArgumentException("Área de visualização inválida.");
            JsonObject state = result(true, "", doc.id);
            state.addProperty("name", doc.name); state.addProperty("revision", doc.revision);
            state.addProperty("editable", doc.editable(viewer)); state.addProperty("owner", doc.owners.contains(viewer));
            state.add("owners", DocumentState.ids(doc.owners)); state.add("editors", DocumentState.ids(doc.editors));
            JsonArray elements = new JsonArray();
            for (UUID id : doc.index.query(new CanvasElement.Bounds(minX, minY, maxX, maxY)))
                elements.add(objects.get(doc.index.entries.get(id)).toJson());
            state.add("elements", elements); state.add("bounds", bounds(doc));
            JsonArray pending = new JsonArray();
            for (MergeRequest merge : merges.values()) if (merge.target.equals(doc.id) || merge.source.equals(doc.id)) {
                JsonObject item = merge.toJson(); item.addProperty("targetName", documents.get(merge.target).name);
                item.addProperty("sourceName", documents.get(merge.source).name); pending.add(item);
            }
            state.add("merges", pending); return state;
        } catch (RuntimeException | IOException error) { return failure(workspace, error); }
    }

    public synchronized JsonObject action(UUID workspace, UUID actor, JsonObject request) {
        try {
            DocumentState doc = document(workspace);
            if (actor == null || request == null || !request.has("action")) throw new IllegalArgumentException("Ação inválida.");
            String action = request.get("action").getAsString();
            UUID operationId = CanvasElement.optionalId(request, "operationId");
            if (operationId != null && doc.receipts.containsKey(operationId)) {
                DocumentState.Receipt receipt = doc.receipts.get(operationId);
                if (!receipt.actor().equals(actor)) throw new IllegalArgumentException("Identificador de operação já utilizado.");
                JsonObject duplicate = result(true, receipt.message(), doc.id); duplicate.addProperty("deduplicated", true); return duplicate;
            }
            return switch (action) {
                case "edit" -> edit(doc, actor, request, operationId);
                case "setChecklistItem" -> setChecklistItem(doc,actor,request,operationId);
                case "undo", "redo" -> history(doc, actor, action, operationId);
                case "invite", "revoke" -> permission(doc, actor, request, action, operationId);
                case "rename" -> rename(doc, actor, request, operationId);
                case "approveMerge" -> approve(doc, actor, request);
                default -> throw new IllegalArgumentException("Ação desconhecida: " + action);
            };
        } catch (RuntimeException | IOException error) { return failure(workspace, error); }
    }

    private JsonObject edit(DocumentState doc, UUID actor, JsonObject request, UUID operationId) throws IOException {
        requireEditor(doc, actor);
        if (operationId == null) throw new IllegalArgumentException("Identificador da operação ausente.");
        JsonArray changes = request.getAsJsonArray("changes");
        if (changes == null || changes.isEmpty() || changes.size() > 2048) throw new IllegalArgumentException("Operação deve conter entre 1 e 2048 alterações.");
        Map<UUID, CanvasElement> desired = new LinkedHashMap<>();
        for (JsonElement value : changes) {
            JsonObject item = value.getAsJsonObject(); UUID id = UUID.fromString(item.get("id").getAsString());
            if (desired.containsKey(id)) throw new IllegalArgumentException("Elemento repetido na operação.");
            ElementIndex.Entry current = doc.index.entries.get(id);
            long expected = item.get("expectedRevision").getAsLong();
            if ((current == null ? -1 : current.revision()) != expected)
                throw new IllegalArgumentException("Conflito: outro jogador alterou o elemento. A tela foi atualizada.");
            JsonElement after = item.get("after");
            if (after == null) throw new IllegalArgumentException("Estado final do elemento ausente.");
            CanvasElement element = after.isJsonNull() ? null : CanvasElement.fromJson(after.getAsJsonObject());
            if (element != null && !element.id().equals(id)) throw new IllegalArgumentException("Identidade do elemento inconsistente.");
            if (element == null && current == null) throw new IllegalArgumentException("Elemento já removido.");
            if(element!=null&&element.type()==CanvasElement.Type.EMBED){
                CanvasElement previous=current==null?null:objects.get(current);
                UUID author=previous!=null&&previous.type()==CanvasElement.Type.EMBED&&previous.url().equals(element.url())?previous.sourceAuthor():actor;
                element=element.withSourceAuthor(author);
            }
            desired.put(id, element);
        }
        updateConnectors(doc, desired);
        DocumentState.History history = prepare(doc, desired);
        UUID typingGroup=CanvasElement.optionalId(request,"typingGroup");
        UUID typingElement=null;
        if(typingGroup!=null){
            if(changes.size()!=1)throw new IllegalArgumentException("Intervalo de escrita deve alterar um texto.");
            JsonObject primary=changes.get(0).getAsJsonObject();CanvasElement text=desired.get(UUID.fromString(primary.get("id").getAsString()));
            if(text==null||text.type()!=CanvasElement.Type.TEXT)throw new IllegalArgumentException("Intervalo de escrita inválido.");
            typingElement=text.id();
        }
        commitEdit(doc, actor, "normal", history, operationId, "Salvo.",typingGroup,typingElement);
        return result(true, "Salvo.", doc.id);
    }

    /** Narrow world interaction: never accept replacement text, geometry or links from this request. */
    private JsonObject setChecklistItem(DocumentState doc,UUID actor,JsonObject request,UUID operationId) throws IOException {
        requireEditor(doc,actor);
        if(operationId==null)throw new IllegalArgumentException("Identificador da operação ausente.");
        UUID id=UUID.fromString(request.get("elementId").getAsString());
        ElementIndex.Entry entry=doc.index.entries.get(id);
        if(entry==null||entry.revision()!=request.get("expectedRevision").getAsLong())
            throw new IllegalArgumentException("A lista mudou; confira o quadro e tente novamente.");
        CanvasElement current=objects.get(entry);int row=request.get("row").getAsInt();
        if(current.type()!=CanvasElement.Type.CHECKLIST||row<0||row>=current.text().split("\n",-1).length||row>=16_384)
            throw new IllegalArgumentException("Tarefa inválida.");
        boolean checked=request.get("checked").getAsBoolean();
        if((row<current.checks().size()&&current.checks().get(row))==checked){
            JsonObject event=event("receipt");event.addProperty("workspace",doc.id.toString());
            receipt(event,operationId,actor,"Lista já atualizada.");commit(event);
            return result(true,"Lista já atualizada.",doc.id);
        }
        List<Boolean> checks=new ArrayList<>(current.checks());while(checks.size()<=row)checks.add(false);checks.set(row,checked);
        CanvasElement after=new CanvasElement(current.id(),current.type(),current.x(),current.y(),current.w(),current.h(),
                current.color(),current.thickness(),current.text(),current.points(),current.from(),current.to(),checks,
                current.revision(),current.group(),current.fontSize());
        commitEdit(doc,actor,"normal",prepare(doc,Map.of(id,after)),operationId,"Lista salva.");
        return result(true,"Lista salva.",doc.id);
    }

    private void updateConnectors(DocumentState doc, Map<UUID, CanvasElement> desired) throws IOException {
        Set<UUID> connectors = new LinkedHashSet<>(doc.index.connectors);
        desired.values().stream().filter(e -> e != null && e.type() == CanvasElement.Type.CONNECTOR).forEach(e -> connectors.add(e.id()));
        for (UUID id : connectors) {
            CanvasElement connector = prospective(doc, desired, id);
            if (connector == null || connector.type() != CanvasElement.Type.CONNECTOR) continue;
            boolean changed = desired.containsKey(id) || desired.containsKey(connector.from()) || desired.containsKey(connector.to());
            if (!changed) continue;
            CanvasElement from = connector.from() == null ? null : prospective(doc, desired, connector.from());
            CanvasElement to = connector.to() == null ? null : prospective(doc, desired, connector.to());
            boolean removedFrom = connector.from() != null && from == null;
            boolean removedTo = connector.to() != null && to == null;
            if (desired.containsKey(id) && ((removedFrom && !desired.containsKey(connector.from())) || (removedTo && !desired.containsKey(connector.to()))))
                throw new IllegalArgumentException("Conector aponta para um elemento inexistente.");
            if (connector.from() != null && connector.from().equals(id) || connector.to() != null && connector.to().equals(id))
                throw new IllegalArgumentException("Conector não pode ligar a si mesmo.");
            if ((from != null && from.type() == CanvasElement.Type.CONNECTOR) || (to != null && to.type() == CanvasElement.Type.CONNECTOR))
                throw new IllegalArgumentException("Conecte formas, notas ou textos; conectores não podem encadear outros conectores.");
            desired.put(id, connector.connected(from, to, removedFrom, removedTo));
        }
    }

    private CanvasElement prospective(DocumentState doc, Map<UUID, CanvasElement> desired, UUID id) throws IOException {
        if (desired.containsKey(id)) return desired.get(id);
        ElementIndex.Entry entry = doc.index.entries.get(id); return entry == null ? null : objects.get(entry);
    }

    private DocumentState.History prepare(DocumentState doc, Map<UUID, CanvasElement> desired) throws IOException {
        return prepare(doc, desired, Map.of());
    }
    private DocumentState.History prepare(DocumentState doc, Map<UUID, CanvasElement> desired, Map<UUID, Long> restoredOrders) throws IOException {
        List<DocumentState.Change> changes = new ArrayList<>(); long order = doc.nextOrder;
        for (Map.Entry<UUID, CanvasElement> desiredEntry : desired.entrySet()) {
            UUID id = desiredEntry.getKey(); DocumentState.State before = doc.state(id);
            long version = Math.addExact(before.version(), 1);
            ElementIndex.Entry entry = desiredEntry.getValue() == null ? null : objects.put(desiredEntry.getValue().withRevision(version))
                .withOrder(before.entry() == null ? restoredOrders.getOrDefault(id, Math.addExact(order, 1)) : before.entry().order());
            if (entry != null) order = Math.max(order, entry.order());
            changes.add(new DocumentState.Change(id, before, new DocumentState.State(entry, version)));
        }
        return new DocumentState.History(changes);
    }

    private JsonObject history(DocumentState doc, UUID actor, String mode, UUID operationId) throws IOException {
        requireEditor(doc, actor);
        Deque<DocumentState.History> stack = (mode.equals("undo") ? doc.undo : doc.redo).get(actor);
        if (stack == null || stack.isEmpty()) throw new IllegalArgumentException(mode.equals("undo") ? "Nada para desfazer." : "Nada para refazer.");
        DocumentState.History previous = stack.getLast(); Map<UUID, CanvasElement> desired = new LinkedHashMap<>(); Map<UUID, Long> orders = new LinkedHashMap<>();
        for (DocumentState.Change change : previous.changes()) {
            if (!doc.state(change.id()).equals(change.after()))
                throw new IllegalArgumentException("Conflito: o elemento mudou depois da sua ação; o histórico não substituirá essa alteração.");
            desired.put(change.id(), change.before().entry() == null ? null : objects.get(change.before().entry()));
            if (change.before().entry() != null) orders.put(change.id(), change.before().entry().order());
        }
        // Recompute even connectors created since the original action, so their links remain live.
        updateConnectors(doc, desired);
        DocumentState.History next = prepare(doc, desired, orders);
        String message = mode.equals("undo") ? "Ação desfeita e salva." : "Ação refeita e salva.";
        commitEdit(doc, actor, mode, next, operationId, message); return result(true, message, doc.id);
    }

    private void commitEdit(DocumentState doc, UUID actor, String mode, DocumentState.History history, UUID operationId, String message) throws IOException {
        commitEdit(doc,actor,mode,history,operationId,message,null,null);
    }
    private void commitEdit(DocumentState doc, UUID actor, String mode, DocumentState.History history, UUID operationId, String message,UUID typingGroup,UUID typingElement) throws IOException {
        JsonObject event = event("edit"); event.addProperty("workspace", doc.id.toString()); event.addProperty("actor", actor.toString());
        if(typingGroup!=null){event.addProperty("typingGroup",typingGroup.toString());event.addProperty("typingElement",typingElement.toString());}
        event.addProperty("mode", mode); event.add("changes", history.toJson()); receipt(event, operationId, actor, message); commit(event);
    }

    private JsonObject permission(DocumentState doc, UUID actor, JsonObject request, String action, UUID operationId) throws IOException {
        requireOwner(doc, actor); UUID player = UUID.fromString(request.get("player").getAsString());
        if (doc.owners.contains(player)) throw new IllegalArgumentException("Coproprietários já possuem acesso e não podem ser revogados como convidados.");
        Set<UUID> editors = new LinkedHashSet<>(doc.editors);
        if (action.equals("invite")) editors.add(player); else editors.remove(player);
        String message = action.equals("invite") ? "Editor convidado. Aprovações de união anteriores foram canceladas." : "Acesso removido. Aprovações de união anteriores foram canceladas.";
        JsonObject event = event("acl"); event.addProperty("workspace", doc.id.toString()); event.add("editors", DocumentState.ids(editors));
        receipt(event, operationId, actor, message); commit(event); return result(true, message, doc.id);
    }

    private JsonObject rename(DocumentState doc, UUID actor, JsonObject request, UUID operationId) throws IOException {
        requireOwner(doc, actor); String name = request.get("name").getAsString().strip();
        if (name.isEmpty() || name.length() > 80 || name.chars().anyMatch(c -> Character.isISOControl(c)))
            throw new IllegalArgumentException("Nome deve conter de 1 a 80 caracteres e não pode ter controles.");
        JsonObject event = event("rename"); event.addProperty("workspace", doc.id.toString()); event.addProperty("name", name);
        receipt(event, operationId, actor, "Nome salvo."); commit(event); return result(true, "Nome salvo.", doc.id);
    }

    public synchronized JsonObject requestMerge(UUID target, UUID source, double dx, double dy, UUID actor) {
        try {
            DocumentState targetDoc = document(target), sourceDoc = document(source);
            CanvasElement.finite(dx); CanvasElement.finite(dy);
            if (targetDoc.id.equals(sourceDoc.id)) return result(true, "As mesas já compartilham o documento.", targetDoc.id);
            if (!targetDoc.owners.contains(actor) && !sourceDoc.owners.contains(actor))
                throw new IllegalArgumentException("Um proprietário deve solicitar a união dos documentos.");
            if (targetDoc.owners.size() == 1 && sourceDoc.owners.size() == 1 && targetDoc.owners.contains(actor) && sourceDoc.owners.contains(actor))
                return merge(targetDoc, sourceDoc, dx, dy);
            for (MergeRequest existing : merges.values()) {
                if (existing.target.equals(targetDoc.id) && existing.source.equals(sourceDoc.id) && existing.dx == dx && existing.dy == dy)
                    return mergePending(existing);
            }
            Set<UUID> owners = new LinkedHashSet<>(targetDoc.owners); owners.addAll(sourceDoc.owners);
            Set<UUID> editors = new LinkedHashSet<>(targetDoc.editors); editors.addAll(sourceDoc.editors); editors.removeAll(owners);
            MergeRequest merge = new MergeRequest(UUID.randomUUID(), targetDoc.id, sourceDoc.id, dx, dy, owners, editors);
            // Placing a connecting module requests a union; it is not consent to the resulting ACL.
            JsonObject event = event("mergeRequest"); event.add("request", merge.toJson()); commit(event); return mergePending(merge);
        } catch (RuntimeException | IOException error) { return failure(target, error); }
    }

    private JsonObject approve(DocumentState doc, UUID actor, JsonObject request) throws IOException {
        requireOwner(doc, actor); UUID id = UUID.fromString(request.get("requestId").getAsString()); MergeRequest pending = merges.get(id);
        if (pending == null || !pending.target.equals(doc.id) && !pending.source.equals(doc.id))
            throw new IllegalArgumentException("Solicitação de união expirada ou desconhecida.");
        if (!pending.owners.contains(actor)) throw new IllegalArgumentException("Somente os proprietários envolvidos podem aprovar.");
        Set<UUID> approvals = new LinkedHashSet<>(pending.approved); approvals.add(actor);
        if (approvals.containsAll(pending.owners))
            return merge(documents.get(pending.target), documents.get(pending.source), pending.dx, pending.dy);
        JsonObject event = event("mergeApproval"); event.addProperty("requestId", id.toString()); event.addProperty("actor", actor.toString()); commit(event);
        return result(true, "Aprovação salva; aguardando os demais proprietários.", doc.id);
    }

    private JsonObject merge(DocumentState target, DocumentState source, double dx, double dy) throws IOException {
        for (UUID id : source.index.entries.keySet()) if (target.index.entries.containsKey(id))
            throw new IllegalArgumentException("União bloqueada: os documentos possuem elementos com a mesma identidade. Nenhum dado foi alterado.");
        DocumentState combined = DocumentState.fromJson(target.toJson());
        // A merge changes coordinate systems; old per-document history cannot safely span this boundary.
        combined.undo.clear(); combined.redo.clear();
        for (ElementIndex.Entry entry : new ArrayList<>(combined.index.entries.values())) {
            CanvasElement element = objects.get(entry);
            if (element.group() == null) {
                CanvasElement grouped = element.translated(0, 0, target.id);
                ElementIndex.Entry replacement = objects.put(grouped).withOrder(entry.order()); combined.set(entry.id(), new DocumentState.State(replacement, replacement.revision()));
            }
        }
        for (ElementIndex.Entry entry : source.index.entries.values().stream().sorted(java.util.Comparator.comparingLong(ElementIndex.Entry::order)).toList()) {
            CanvasElement element = objects.get(entry).translated(dx, dy, source.id);
            ElementIndex.Entry replacement = objects.put(element).withOrder(Math.addExact(combined.nextOrder, 1)); combined.set(entry.id(), new DocumentState.State(replacement, replacement.revision()));
        }
        source.versions.forEach((id, version) -> combined.versions.putIfAbsent(id, version));
        source.receipts.forEach(combined.receipts::putIfAbsent);
        combined.owners.addAll(source.owners); combined.editors.addAll(source.editors); combined.editors.removeAll(combined.owners);
        combined.revision = Math.max(target.revision, source.revision) + 1;
        JsonObject event = event("merge"); event.add("document", combined.toJson());
        event.addProperty("source", source.id.toString()); event.addProperty("dx", dx); event.addProperty("dy", dy); commit(event);
        JsonObject result=result(true, "Documentos unidos e salvos. Grupos de origem preservados; histórico reiniciado.", target.id);
        result.addProperty("merged",true);return result;
    }

    public synchronized void invalidateMerges(UUID workspace) {
        DocumentState doc = document(workspace);
        if (merges.values().stream().noneMatch(r -> r.target.equals(doc.id) || r.source.equals(doc.id))) return;
        JsonObject event = event("invalidate"); event.addProperty("workspace", doc.id.toString());
        try { commit(event); } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    public synchronized JsonArray list(UUID actor, boolean operator) {
        ensureOpen(); JsonArray list = new JsonArray();
        for (DocumentState doc : documents.values()) if (operator || doc.owners.contains(actor)) {
            JsonObject item = new JsonObject(); item.addProperty("id", doc.id.toString()); item.addProperty("name", doc.name); list.add(item);
        }
        return list;
    }

    private void commit(JsonObject event) throws IOException {
        journal.append(event); applyEvent(event);
        if (journal.checkpointDue()) {
            try { journal.checkpoint(snapshot()); }
            catch (IOException error) { LOGGER.log(Level.WARNING, "Canvas Desk snapshot failed; the committed journal remains durable.", error); }
        }
    }

    private void applyEvent(JsonObject event) throws IOException {
        String kind = event.get("kind").getAsString(); DocumentState doc = null;
        switch (kind) {
            case "create" -> {
                doc = DocumentState.fromJson(event.getAsJsonObject("document"));
                if (documents.putIfAbsent(doc.id, doc) != null) throw new IOException("Duplicate document creation.");
            }
            case "edit" -> {
                doc = document(id(event, "workspace")); UUID actor = id(event, "actor");
                DocumentState.History history = DocumentState.History.fromJson(event.getAsJsonArray("changes"));
                for (DocumentState.Change change : history.changes()) {
                    if (!doc.state(change.id()).equals(change.before())) throw new IOException("Journal revision mismatch.");
                    doc.set(change.id(), change.after());
                }
                doc.history(actor, event.get("mode").getAsString(), history,CanvasElement.optionalId(event,"typingGroup"),CanvasElement.optionalId(event,"typingElement")); doc.revision++;
            }
            case "acl" -> {
                doc = document(id(event, "workspace")); doc.editors.clear(); doc.editors.addAll(DocumentState.readIds(event.getAsJsonArray("editors")));
                removeMerges(doc.id); doc.revision++;
            }
            case "rename" -> { doc = document(id(event, "workspace")); doc.name = event.get("name").getAsString(); doc.revision++; }
            case "receipt" -> doc = document(id(event,"workspace"));
            case "mergeRequest" -> {
                MergeRequest request = MergeRequest.fromJson(event.getAsJsonObject("request")); merges.put(request.id, request);
            }
            case "mergeApproval" -> {
                MergeRequest request = merges.get(id(event, "requestId"));
                if (request == null) throw new IOException("Unknown journal merge request."); request.approved.add(id(event, "actor"));
            }
            case "invalidate" -> removeMerges(id(event, "workspace"));
            case "merge" -> {
                doc = DocumentState.fromJson(event.getAsJsonObject("document")); UUID source = id(event, "source");
                documents.put(doc.id, doc); documents.remove(source);
                aliases.put(source, new Alias(doc.id, event.get("dx").getAsDouble(), event.get("dy").getAsDouble()));
                removeMerges(source); removeMerges(doc.id);
            }
            default -> throw new IOException("Unknown Canvas Desk journal event: " + kind);
        }
        if (doc != null && event.has("operationId")) {
            doc.receipts.put(id(event, "operationId"), new DocumentState.Receipt(id(event, "receiptActor"), event.get("receiptMessage").getAsString()));
        }
    }

    private JsonObject snapshot() {
        JsonObject out = new JsonObject(); JsonArray docs = new JsonArray(); documents.values().forEach(d -> docs.add(d.toJson())); out.add("documents", docs);
        JsonObject links = new JsonObject(); aliases.forEach((id, alias) -> {
            JsonObject a = new JsonObject(); a.addProperty("target", alias.target.toString()); a.addProperty("dx", alias.dx); a.addProperty("dy", alias.dy); links.add(id.toString(), a);
        }); out.add("aliases", links); JsonArray requests = new JsonArray(); merges.values().forEach(r -> requests.add(r.toJson())); out.add("merges", requests); return out;
    }
    private void loadSnapshot(JsonObject state) {
        if (!state.has("documents")) return;
        for (JsonElement value : state.getAsJsonArray("documents")) { DocumentState doc = DocumentState.fromJson(value.getAsJsonObject()); documents.put(doc.id, doc); }
        state.getAsJsonObject("aliases").entrySet().forEach(entry -> {
            JsonObject a = entry.getValue().getAsJsonObject(); aliases.put(UUID.fromString(entry.getKey()), new Alias(id(a, "target"), a.get("dx").getAsDouble(), a.get("dy").getAsDouble()));
        });
        for (JsonElement value : state.getAsJsonArray("merges")) { MergeRequest merge = MergeRequest.fromJson(value.getAsJsonObject()); merges.put(merge.id, merge); }
    }
    private void removeMerges(UUID workspace) { merges.values().removeIf(r -> r.target.equals(workspace) || r.source.equals(workspace)); }
    private DocumentState document(UUID id) { return documents.get(resolve(id)); }
    private static UUID id(JsonObject value, String field) { return UUID.fromString(value.get(field).getAsString()); }
    private static void requireEditor(DocumentState doc, UUID actor) {
        if (!doc.editable(actor)) throw new IllegalArgumentException("Somente leitura: peça acesso a um proprietário.");
    }
    private static void requireOwner(DocumentState doc, UUID actor) {
        if (!doc.owners.contains(actor)) throw new IllegalArgumentException("Somente um proprietário pode alterar permissões ou aprovar uniões.");
    }
    private static JsonObject event(String kind) { JsonObject event = new JsonObject(); event.addProperty("kind", kind); return event; }
    private static void receipt(JsonObject event, UUID id, UUID actor, String message) {
        if (id == null) return; event.addProperty("operationId", id.toString()); event.addProperty("receiptActor", actor.toString()); event.addProperty("receiptMessage", message);
    }
    private static JsonObject result(boolean ok, String message, UUID workspace) {
        JsonObject out = new JsonObject(); out.addProperty("ok", ok); out.addProperty("message", message);
        if (workspace != null) out.addProperty("workspace", workspace.toString()); return out;
    }
    private static JsonObject mergePending(MergeRequest pending) {
        JsonObject out = result(true, "União aguardando aprovação de todos os proprietários.", pending.target);
        out.addProperty("pending", true); out.addProperty("requestId", pending.id.toString()); return out;
    }
    private JsonObject failure(UUID workspace, Exception error) {
        if (error instanceof IOException) LOGGER.log(Level.SEVERE, "Canvas Desk durable storage error.", error);
        String message = error.getMessage() == null ? "Operação inválida." : error.getMessage();
        return result(false, error instanceof IOException ? "Falha ao salvar/ler documento. Nenhuma confirmação de salvamento foi enviada; consulte o log do servidor." : message, workspace);
    }
    private static JsonObject bounds(DocumentState doc) {
        CanvasElement.Bounds bounds = doc.contentBounds();
        JsonObject out = new JsonObject(); out.addProperty("minX", bounds.minX()); out.addProperty("minY", bounds.minY());
        out.addProperty("maxX", bounds.maxX()); out.addProperty("maxY", bounds.maxY()); return out;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Canvas Desk storage is closed."); }
    @Override public synchronized void close() {
        if (closed) return;
        try { journal.checkpoint(snapshot()); }
        catch (IOException error) { throw new UncheckedIOException(error); }
        finally {
            closed = true;
            try { journal.close(); } catch (IOException error) { throw new UncheckedIOException(error); }
        }
    }
}
