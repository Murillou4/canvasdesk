package br.com.murillo.canvasdesk.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static br.com.murillo.canvasdesk.client.editor.CanvasElementView.number;
import static br.com.murillo.canvasdesk.client.editor.CanvasElementView.string;

/** Native, server-authoritative whiteboard. Every persisted edit is an atomic revisioned operation. */
public final class CanvasScreen extends Screen {
    private enum Tool {
        SELECT("select", "Selecionar", "Select", "V"), PEN("pen", "Desenhar", "Draw", "B"),
        TEXT("text", "Texto", "Text", "T"), NOTE("note", "Anotação", "Note", "N"),
        CHECKLIST("checklist", "Lista", "Checklist", "C"), RECTANGLE("rectangle", "Retângulo", "Rectangle", "R"),
        ELLIPSE("ellipse", "Elipse", "Ellipse", "O"), LINE("line", "Linha", "Line", "L"),
        ARROW("arrow", "Seta", "Arrow", "A"), CONNECTOR("connector", "Conectar elementos", "Connect elements", "K"),
        ERASER("eraser", "Apagar elemento", "Erase element", "E"), PAN("pan", "Mover visão", "Pan", "H");
        final String sprite, pt, en, key;
        Tool(String sprite, String pt, String en, String key) { this.sprite = sprite; this.pt = pt; this.en = en; this.key = key; }
    }
    private enum Drag { NONE, DRAW, MOVE, RESIZE, BOX, PAN, ERASE }
    private enum Dialog { NONE, TEXT, COLOR, RENAME, ACCESS, MERGE, HELP }
    private record Change(CanvasElementView before, JsonObject after) {}
    private record AccessRow(String title, String description, String button, Runnable action) {}

    private static final int PAPER = 0xfff8f5eb, INK = 0xff34342e, MUTED = 0xff827b6d, BORDER = 0xffd8d0bf, ACCENT = 0xffa46e48;
    private static final int[] PALETTE = {0x30302c, 0xa64f41, 0xb97751, 0xc5a34a, 0x5c7956, 0x4e7a87, 0x646c9a, 0x927193};
    private final Consumer<JsonObject> send;
    private final CanvasViewport viewport = new CanvasViewport();
    private final LinkedHashMap<String, CanvasElementView> elements = new LinkedHashMap<>();
    private final Set<String> selection = new LinkedHashSet<>();
    private final Map<String, CanvasElementView> dragBefore = new LinkedHashMap<>();
    private final Map<String, CanvasElementView> dragPreview = new LinkedHashMap<>();
    private final Set<String> erased = new LinkedHashSet<>();
    private JsonObject state = new JsonObject(), draft;
    private Tool tool = Tool.SELECT;
    private Drag drag = Drag.NONE;
    private Dialog dialog = Dialog.NONE;
    private int color = PALETTE[0], accessScroll;
    private double thickness = 3, originX, originY, startX, startY, currentX, currentY;
    private boolean editable, owner, initialized, saving, spaceHeld, closeSent;
    private String message = "", connectorStart = "", dialogTitle = "";
    private String pendingAction = "", pendingOperation = "";
    private long messageUntil, viewAfter;
    private MultiLineEditBox textField;
    private JsonObject textTarget;
    private JsonObject mergeTarget;
    private int dialogX, dialogY, dialogW, dialogH;

    public CanvasScreen(JsonObject state, Consumer<JsonObject> send) {
        super(Component.literal("Canvas Desk"));
        this.send = send;
        acceptState(state);
        originX = number(state, "originX", 0); originY = number(state, "originY", 0);
        viewport.panX = originX - 24; viewport.panY = originY - 24;
    }

    /** Network updates replace the visible region while keeping camera and selection stable. */
    public void acceptState(JsonObject incoming) {
        state = incoming.deepCopy();
        if (initialized) {
            viewport.panX += number(incoming, "viewShiftX", 0);
            viewport.panY += number(incoming, "viewShiftY", 0);
        }
        originX = number(incoming, "originX", originX); originY = number(incoming, "originY", originY);
        editable = incoming.has("editable") && incoming.get("editable").getAsBoolean();
        owner = incoming.has("owner") && incoming.get("owner").getAsBoolean();
        elements.clear();
        if (incoming.has("elements")) for (JsonElement value : incoming.getAsJsonArray("elements")) {
            CanvasElementView e = new CanvasElementView(value.getAsJsonObject()); elements.put(e.id, e);
        }
        selection.retainAll(elements.keySet());
        if (incoming.has("ackAction") && string(incoming, "ackAction", "").equals(pendingAction)
            && (pendingOperation.isEmpty() || string(incoming, "ackOperation", "").equals(pendingOperation))) {
            saving = false; pendingAction = ""; pendingOperation = "";
        }
        if (incoming.has("message") && !incoming.get("message").getAsString().isBlank()) status(incoming.get("message").getAsString());
        if (initialized && dialog == Dialog.ACCESS) rebuildControls();
        if (!editable && drag != Drag.NONE && drag != Drag.PAN && drag != Drag.BOX) cancelGesture();
    }

    @Override protected void init() {
        viewport.left = 0; viewport.top = 80; viewport.right = width; viewport.bottom = height - 19;
        initialized = true;
        rebuildControls();
        queueView();
    }

    private void rebuildControls() {
        String currentText = textField == null ? null : textField.getValue();
        clearWidgets(); textField = null;
        if (dialog != Dialog.NONE) { buildDialog(currentText); return; }
        int actionX = width - 150;
        icon(actionX, 4, "undo", tr("Desfazer · Ctrl+Z", "Undo · Ctrl+Z"), () -> action("undo"), () -> false, this::canEdit);
        icon(actionX + 24, 4, "redo", tr("Refazer · Ctrl+Y", "Redo · Ctrl+Y"), () -> action("redo"), () -> false, this::canEdit);
        icon(actionX + 48, 4, "fit", tr("Enquadrar conteúdo · F", "Fit content · F"), this::fit, () -> false, () -> true);
        icon(actionX + 72, 4, "invite", tr("Acesso e uniões", "Access and merges"), () -> openDialog(Dialog.ACCESS, tr("Acesso ao documento", "Document access")), () -> false, () -> true);
        button(actionX + 96, 4, 22, 21, "?", null, () -> openDialog(Dialog.HELP, tr("Controles", "Controls")), () -> false, () -> true, null);
        icon(actionX + 120, 4, "close", tr("Fechar · Esc", "Close · Esc"), this::onClose, () -> false, () -> true);
        int step = Math.min(24, Math.max(19, (width - 16) / Tool.values().length));
        for (int i = 0; i < Tool.values().length; i++) {
            Tool candidate = Tool.values()[i];
            button(8 + i * step, 29, step - 1, 21, tr(candidate.pt, candidate.en) + " · " + candidate.key,
                candidate.sprite, () -> selectTool(candidate), () -> tool == candidate,
                () -> editable || candidate == Tool.SELECT || candidate == Tool.PAN, null);
        }
        int paletteX = 8;
        for (int candidate : PALETTE) {
            button(paletteX, 55, 17, 17, String.format("#%06X", candidate), null,
                () -> setColor(candidate), () -> color == candidate, this::canEdit, candidate);
            paletteX += 19;
        }
        button(paletteX + 1, 55, 24, 17, "#", null, () -> openDialog(Dialog.COLOR, tr("Cor hexadecimal", "Hex color")), () -> false, this::canEdit, null);
        int widthX = paletteX + 32;
        for (int candidate : new int[]{1, 3, 6, 10}) {
            button(widthX, 55, 23, 17, Integer.toString(candidate), null,
                () -> setThickness(candidate), () -> thickness == candidate, this::canEdit, null);
            widthX += 24;
        }
        if (width >= widthX + 60) button(widthX + 4, 55, 54, 17, tr("Editar", "Edit"), null, this::editSelected,
            () -> false, () -> canEdit() && selection.size() == 1 && isText(elements.get(selection.iterator().next())), null);
    }

    private void button(int x, int y, int w, int h, String text, String sprite, Runnable action,
                        java.util.function.BooleanSupplier selected, java.util.function.BooleanSupplier enabled, Integer swatch) {
        addRenderableWidget(new CanvasButton(x, y, w, h, text, sprite, action, selected, enabled, swatch));
    }
    private void icon(int x, int y, String sprite, String text, Runnable action,
                      java.util.function.BooleanSupplier selected, java.util.function.BooleanSupplier enabled) {
        button(x, y, 22, 21, text, sprite, action, selected, enabled, null);
    }
    private boolean canEdit() { return editable && !saving; }
    private String tr(String pt, String en) { return minecraft.getLanguageManager().getSelected().startsWith("pt") ? pt : en; }
    private void status(String text) { message = text; messageUntil = System.currentTimeMillis() + 6500; }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        g.fill(0, 0, width, height, PAPER);
        drawCanvas(g, mouseX, mouseY);
        g.fill(0, 0, width, 79, 0xffeae4d6);
        g.fill(0, 78, width, 80, BORDER);
        String name = string(state, "name", "Canvas Desk");
        g.text(font, font.plainSubstrByWidth(name, Math.max(35, width - 169)), 9, 10, INK, false);
        if (mouseY < 26 && mouseX < width - 156) g.setTooltipForNextFrame(Component.literal(owner ? tr("Clique para renomear", "Click to rename") : name), mouseX, mouseY);
        g.fill(0, height - 19, width, height, 0xffeae4d6);
        g.fill(0, height - 20, width, height - 19, BORDER);
        String status = saving ? tr("Gravando…", "Saving…") : System.currentTimeMillis() < messageUntil ? message
            : !editable ? tr("Somente leitura · peça acesso ao dono", "Read only · ask an owner for access")
            : tr(tool.pt, tool.en) + " · " + tr("Espaço+arrastar: navegar", "Space+drag: pan");
        g.text(font, font.plainSubstrByWidth(status, Math.max(40, width - 80)), 8, height - 13,
            !editable ? 0xff865a42 : saving ? ACCENT : MUTED, false);
        g.text(font, (int) Math.round(viewport.zoom * 100) + "%", width - 39, height - 13, MUTED, false);
        if (dialog != Dialog.NONE) drawDialog(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, delta);
    }

    private void drawCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (viewport.bottom <= viewport.top) return;
        g.enableScissor(viewport.left, viewport.top, viewport.right, viewport.bottom);
        double grid = 32;
        while (grid * viewport.zoom < 18) grid *= 2;
        for (double x = Math.floor(viewport.panX / grid) * grid; viewport.screenX(x) < width; x += grid) {
            for (double y = Math.floor(viewport.panY / grid) * grid; viewport.screenY(y) < viewport.bottom; y += grid)
                g.fill((int) viewport.screenX(x), (int) viewport.screenY(y), (int) viewport.screenX(x) + 1, (int) viewport.screenY(y) + 1, 0xffddd7c9);
        }
        // Opened module provides a subtle spatial anchor, independent from the infinite editor.
        g.outline((int) viewport.screenX(originX), (int) viewport.screenY(originY),
            Math.max(1, (int) (256 * viewport.zoom)), Math.max(1, (int) (256 * viewport.zoom)), 0xffe1d6bf);
        Map<String, CanvasElementView> shown = shownElements();
        for (CanvasElementView element : shown.values()) CanvasPainter.paint(g, font, viewport, element, shown);
        if (draft != null) CanvasPainter.paint(g, font, viewport, new CanvasElementView(draft), shown);
        for (String id : selection) {
            CanvasElementView e = shown.get(id); if (e == null) continue;
            int x = (int) viewport.screenX(e.x) - 3, y = (int) viewport.screenY(e.y) - 3;
            int w = Math.max(6, (int) (e.w * viewport.zoom)) + 6, h = Math.max(6, (int) (e.h * viewport.zoom)) + 6;
            g.outline(x, y, w, h, ACCENT);
            if (selection.size() == 1 && editable && !e.type.equals("CONNECTOR"))
                g.fill(x + w - 4, y + h - 4, x + w + 3, y + h + 3, ACCENT);
        }
        if (drag == Drag.BOX) {
            int x1 = (int) viewport.screenX(Math.min(startX, currentX)), y1 = (int) viewport.screenY(Math.min(startY, currentY));
            int x2 = (int) viewport.screenX(Math.max(startX, currentX)), y2 = (int) viewport.screenY(Math.max(startY, currentY));
            g.fill(x1, y1, x2, y2, 0x22a46e48); g.outline(x1, y1, x2 - x1, y2 - y1, ACCENT);
        }
        if (!connectorStart.isEmpty() && shown.containsKey(connectorStart)) {
            CanvasElementView e = shown.get(connectorStart);
            CanvasPainter.line(g, viewport.screenX(e.centerX()), viewport.screenY(e.centerY()), mouseX, mouseY, 2, ACCENT);
        }
        g.disableScissor();
    }

    private Map<String, CanvasElementView> shownElements() {
        if (dragPreview.isEmpty() && erased.isEmpty()) return elements;
        Map<String, CanvasElementView> result = new LinkedHashMap<>(elements);
        result.putAll(dragPreview); for (String id : erased) result.remove(id);
        return result;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (dialog != Dialog.NONE) return super.mouseClicked(event, doubleClick);
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.y() < 26 && event.x() < width - 156 && owner) {
            openDialog(Dialog.RENAME, tr("Nome do documento", "Document name")); return true;
        }
        if (!viewport.contains(event.x(), event.y())) return false;
        clearFocus();
        startX = currentX = viewport.documentX(event.x()); startY = currentY = viewport.documentY(event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE || event.button() == InputConstants.MOUSE_BUTTON_LEFT && (spaceHeld || tool == Tool.PAN)) { drag = Drag.PAN; return true; }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        CanvasElementView hit = hit(currentX, currentY);
        if (tool == Tool.SELECT) { startSelection(event, doubleClick, hit); return true; }
        if (!canEdit()) { status(tr("Aguarde a confirmação do servidor", "Wait for server confirmation")); return true; }
        if (tool == Tool.CONNECTOR) { connect(hit); return true; }
        if (tool == Tool.ERASER) { drag = Drag.ERASE; erased.clear(); if (hit != null) erased.add(hit.id); return true; }
        if (tool == Tool.TEXT || tool == Tool.NOTE || tool == Tool.CHECKLIST) {
            textTarget = CanvasElementView.create(tool.name(), currentX, currentY, tool == Tool.TEXT ? 160 : 190, 120, color, thickness);
            openDialog(Dialog.TEXT, tr(tool.pt, tool.en)); return true;
        }
        String type = tool == Tool.PEN ? "STROKE" : tool.name();
        draft = CanvasElementView.create(type, startX, startY, 1, 1, color, thickness);
        draft.getAsJsonArray("points").add(CanvasElementView.point(startX, startY));
        if (tool != Tool.PEN) draft.getAsJsonArray("points").add(CanvasElementView.point(startX, startY));
        selection.clear(); drag = Drag.DRAW; return true;
    }

    private void startSelection(MouseButtonEvent event, boolean doubleClick, CanvasElementView hit) {
        if (selection.size() == 1 && canEdit()) {
            CanvasElementView selected = elements.get(selection.iterator().next());
            if (selected != null && !selected.type.equals("CONNECTOR") && Math.abs(event.x() - viewport.screenX(selected.x + selected.w)) < 8
                && Math.abs(event.y() - viewport.screenY(selected.y + selected.h)) < 8) {
                beginTransform(Drag.RESIZE); return;
            }
        }
        if (hit == null) { if (!event.hasShiftDown()) selection.clear(); drag = Drag.BOX; return; }
        if (doubleClick && isText(hit) && canEdit()) { selection.clear(); selection.add(hit.id); editSelected(); return; }
        if (canEdit() && hit.type.equals("CHECKLIST") && currentX >= hit.x + 7 && currentX <= hit.x + 22) {
            int row = checklistRow(hit, currentY);
            if (row >= 0) {
                JsonObject after = hit.json.deepCopy(); JsonArray checks = after.getAsJsonArray("checks");
                while (checks.size() <= row) checks.add(false);
                checks.set(row, new com.google.gson.JsonPrimitive(!checks.get(row).getAsBoolean()));
                submit(List.of(new Change(hit, after))); return;
            }
        }
        if (event.hasAltDown() && !hit.group.isEmpty()) {
            if (!event.hasShiftDown()) selection.clear();
            for (CanvasElementView e : elements.values()) if (e.group.equals(hit.group)) selection.add(e.id);
            status(tr("Grupo selecionado · ", "Group selected · ") + selection.size() + tr(" elementos visíveis", " visible elements"));
        } else if (event.hasShiftDown()) { if (!selection.add(hit.id)) selection.remove(hit.id); }
        else if (!selection.contains(hit.id)) {
            selection.clear(); selection.add(hit.id);
        }
        color = hit.color; thickness = hit.thickness;
        if (canEdit() && !selection.isEmpty()) beginTransform(Drag.MOVE);
    }

    private int checklistRow(CanvasElementView e, double y) {
        double rowY = e.y + 9;
        String[] rows = e.text.split("\n", -1);
        for (int i = 0; i < rows.length; i++) {
            int lineCount = Math.max(1, font.split(Component.literal(rows[i]), Math.max(8, (int) e.w - 32)).size());
            if (y >= rowY && y <= rowY + 9) return i;
            rowY += lineCount * 12;
        }
        return -1;
    }

    private void beginTransform(Drag kind) {
        drag = kind; dragBefore.clear(); dragPreview.clear();
        for (String id : selection) if (elements.containsKey(id)) dragBefore.put(id, elements.get(id));
    }

    private void connect(CanvasElementView hit) {
        if (hit == null || hit.type.equals("CONNECTOR")) { status(tr("Escolha um elemento para ligar", "Choose an element to connect")); return; }
        if (connectorStart.isEmpty()) { connectorStart = hit.id; status(tr("Agora clique no destino", "Now click the destination")); return; }
        CanvasElementView from = elements.get(connectorStart);
        if (from != null && !from.id.equals(hit.id)) {
            JsonObject connector = CanvasElementView.create("CONNECTOR", Math.min(from.centerX(), hit.centerX()),
                Math.min(from.centerY(), hit.centerY()), Math.abs(hit.centerX() - from.centerX()), Math.abs(hit.centerY() - from.centerY()), color, thickness);
            connector.addProperty("from", from.id); connector.addProperty("to", hit.id);
            connector.getAsJsonArray("points").add(CanvasElementView.point(from.centerX(), from.centerY()));
            connector.getAsJsonArray("points").add(CanvasElementView.point(hit.centerX(), hit.centerY()));
            submit(List.of(new Change(null, connector)));
        }
        connectorStart = "";
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dialog != Dialog.NONE) return super.mouseDragged(event, dx, dy);
        currentX = viewport.documentX(event.x()); currentY = viewport.documentY(event.y());
        switch (drag) {
            case PAN -> { viewport.panX -= dx / viewport.zoom; viewport.panY -= dy / viewport.zoom; queueView(); }
            case DRAW -> updateDraft();
            case MOVE -> {
                dragPreview.clear();
                for (CanvasElementView e : dragBefore.values()) dragPreview.put(e.id, e.translated(currentX - startX, currentY - startY));
            }
            case RESIZE -> {
                for (CanvasElementView e : dragBefore.values()) dragPreview.put(e.id, e.resized(currentX - e.x, currentY - e.y));
            }
            case ERASE -> { CanvasElementView hit = hit(currentX, currentY); if (hit != null) erased.add(hit.id); }
            case BOX -> { }
            default -> { return super.mouseDragged(event, dx, dy); }
        }
        return true;
    }

    private void updateDraft() {
        if (draft == null) return;
        if (tool == Tool.PEN) {
            JsonArray points = draft.getAsJsonArray("points");
            JsonObject last = points.get(points.size() - 1).getAsJsonObject();
            if (Math.hypot(currentX - number(last, "x", 0), currentY - number(last, "y", 0)) * viewport.zoom < 0.8) return;
            points.add(CanvasElementView.point(currentX, currentY));
            double oldX = number(draft, "x", 0), oldY = number(draft, "y", 0);
            double maxX = Math.max(oldX + number(draft, "w", 0), currentX), maxY = Math.max(oldY + number(draft, "h", 0), currentY);
            double minX = Math.min(oldX, currentX), minY = Math.min(oldY, currentY);
            draft.addProperty("x", minX); draft.addProperty("y", minY);
            draft.addProperty("w", Math.max(1, maxX - minX)); draft.addProperty("h", Math.max(1, maxY - minY));
        } else {
            draft.addProperty("x", Math.min(startX, currentX)); draft.addProperty("y", Math.min(startY, currentY));
            draft.addProperty("w", Math.max(1, Math.abs(currentX - startX))); draft.addProperty("h", Math.max(1, Math.abs(currentY - startY)));
            draft.getAsJsonArray("points").set(1, CanvasElementView.point(currentX, currentY));
        }
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (dialog != Dialog.NONE) return super.mouseReleased(event);
        if (drag == Drag.NONE) return super.mouseReleased(event);
        if (drag == Drag.DRAW && draft != null && canEdit()) {
            if (tool != Tool.PEN && Math.hypot(currentX - startX, currentY - startY) < 3 / viewport.zoom) {
                draft.addProperty("w", 120); draft.addProperty("h", tool == Tool.LINE || tool == Tool.ARROW ? 1 : 72);
                draft.getAsJsonArray("points").set(1, CanvasElementView.point(startX + 120, startY + (tool == Tool.LINE || tool == Tool.ARROW ? 0 : 72)));
            }
            selection.clear(); selection.add(string(draft, "id", "")); submit(List.of(new Change(null, draft)));
        } else if ((drag == Drag.MOVE || drag == Drag.RESIZE) && !dragPreview.isEmpty() && canEdit()) {
            List<Change> changes = new ArrayList<>();
            for (CanvasElementView after : dragPreview.values()) changes.add(new Change(dragBefore.get(after.id), after.json));
            submit(changes);
        } else if (drag == Drag.ERASE && !erased.isEmpty() && canEdit()) {
            List<Change> changes = new ArrayList<>();
            for (String id : erased) if (elements.containsKey(id)) changes.add(new Change(elements.get(id), null));
            submit(changes);
        } else if (drag == Drag.BOX) {
            double x1 = Math.min(startX, currentX), y1 = Math.min(startY, currentY), x2 = Math.max(startX, currentX), y2 = Math.max(startY, currentY);
            for (CanvasElementView e : elements.values()) if (e.x <= x2 && e.y <= y2 && e.x + e.w >= x1 && e.y + e.h >= y1) selection.add(e.id);
        }
        cancelGesture(); return true;
    }

    private void cancelGesture() { drag = Drag.NONE; draft = null; dragPreview.clear(); dragBefore.clear(); erased.clear(); }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (dialog == Dialog.ACCESS || dialog == Dialog.MERGE) {
            accessScroll = Math.max(0, accessScroll - (int) Math.signum(vertical)); rebuildControls(); return true;
        }
        if (dialog != Dialog.NONE) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        if (!viewport.contains(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        viewport.panX -= horizontal * 32 / viewport.zoom;
        viewport.zoomAt(mouseX, mouseY, Math.pow(1.15, vertical)); queueView(); return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (dialog != Dialog.NONE) {
            if (event.isEscape()) { closeDialog(); return true; }
            if (textField != null && event.hasControlDown() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) { saveDialog(); return true; }
            return super.keyPressed(event);
        }
        if (event.isEscape()) {
            if (drag != Drag.NONE || !connectorStart.isEmpty()) { cancelGesture(); connectorStart = ""; return true; }
            onClose(); return true;
        }
        if (getFocused() != null && (event.isConfirmation() || event.isCycleFocus())) return super.keyPressed(event);
        if (event.key() == InputConstants.KEY_SPACE) { spaceHeld = true; return true; }
        if (event.hasControlDown()) {
            if (event.key() == InputConstants.KEY_Z && canEdit()) { action(event.hasShiftDown() ? "redo" : "undo"); return true; }
            if (event.key() == InputConstants.KEY_Y && canEdit()) { action("redo"); return true; }
            if (event.isSelectAll()) { selection.clear(); selection.addAll(elements.keySet()); selectTool(Tool.SELECT); return true; }
        }
        if (event.key() == InputConstants.KEY_DELETE || event.key() == InputConstants.KEY_BACKSPACE) { deleteSelection(); return true; }
        if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) { editSelected(); return true; }
        if (event.key() == InputConstants.KEY_F) { fit(); return true; }
        if (event.key() == InputConstants.KEY_F1) { openDialog(Dialog.HELP, tr("Controles", "Controls")); return true; }
        if (!event.hasControlDown() && !event.hasAltDown()) {
            for (Tool candidate : Tool.values()) if (event.key() == InputConstants.KEY_A + candidate.key.charAt(0) - 'A') { selectTool(candidate); return true; }
            if ((event.isLeft() || event.isRight() || event.isUp() || event.isDown()) && !selection.isEmpty() && canEdit()) {
                double distance = event.hasShiftDown() ? 10 : 1;
                double dx = event.isLeft() ? -distance : event.isRight() ? distance : 0;
                double dy = event.isUp() ? -distance : event.isDown() ? distance : 0;
                List<Change> changes = new ArrayList<>();
                for (String id : selection) { CanvasElementView e = elements.get(id); if (e != null) changes.add(new Change(e, e.translated(dx, dy).json)); }
                submit(changes); return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override public boolean keyReleased(KeyEvent event) {
        if (event.key() == InputConstants.KEY_SPACE) { spaceHeld = false; return true; }
        return super.keyReleased(event);
    }

    private void selectTool(Tool next) {
        if (!editable && next != Tool.SELECT && next != Tool.PAN) return;
        cancelGesture(); connectorStart = ""; tool = next; clearFocus();
    }

    private void setColor(int next) {
        color = next;
        updateSelectedProperty("color", next);
    }
    private void setThickness(int next) { thickness = next; updateSelectedProperty("thickness", next); }
    private void updateSelectedProperty(String property, Number next) {
        if (!canEdit() || selection.isEmpty()) return;
        List<Change> changes = new ArrayList<>();
        for (String id : selection) {
            CanvasElementView e = elements.get(id); if (e == null) continue;
            JsonObject after = e.json.deepCopy(); after.addProperty(property, next); changes.add(new Change(e, after));
        }
        submit(changes);
    }
    private void deleteSelection() {
        if (!canEdit()) return;
        List<Change> changes = new ArrayList<>();
        for (String id : selection) if (elements.containsKey(id)) changes.add(new Change(elements.get(id), null));
        submit(changes); selection.clear();
    }

    private void editSelected() {
        if (!canEdit() || selection.size() != 1) return;
        CanvasElementView e = elements.get(selection.iterator().next());
        if (!isText(e)) return;
        textTarget = e.json.deepCopy(); openDialog(Dialog.TEXT, tr("Editar texto", "Edit text"));
    }
    private static boolean isText(CanvasElementView e) { return e != null && (e.type.equals("TEXT") || e.type.equals("NOTE") || e.type.equals("CHECKLIST")); }

    private void submit(Collection<Change> changes) {
        if (!canEdit() || changes.isEmpty()) return;
        JsonObject request = new JsonObject(); request.addProperty("action", "edit");
        pendingAction = "edit"; pendingOperation = UUID.randomUUID().toString(); request.addProperty("operationId", pendingOperation);
        JsonArray operations = new JsonArray();
        for (Change change : changes) {
            String id = change.before == null ? string(change.after, "id", "") : change.before.id;
            JsonObject item = new JsonObject(); item.addProperty("id", id); item.addProperty("expectedRevision", change.before == null ? -1 : change.before.revision);
            item.add("after", change.after == null ? JsonNull.INSTANCE : change.after.deepCopy()); operations.add(item);
            if (change.after == null) elements.remove(id); else elements.put(id, new CanvasElementView(change.after));
        }
        request.add("changes", operations); saving = true; send.accept(request);
    }

    private void action(String action) {
        if (!canEdit()) return;
        JsonObject request = new JsonObject(); request.addProperty("action", action);
        pendingAction = action; pendingOperation = UUID.randomUUID().toString(); request.addProperty("operationId", pendingOperation);
        saving = true; send.accept(request);
    }
    private void request(String action, String key, String value) {
        JsonObject request = new JsonObject(); request.addProperty("action", action); request.addProperty(key, value);
        request.addProperty("operationId", UUID.randomUUID().toString()); send.accept(request);
    }

    private void queueView() { viewAfter = System.currentTimeMillis() + 160; }
    @Override public void tick() {
        if (viewAfter != 0 && System.currentTimeMillis() >= viewAfter && drag != Drag.DRAW && drag != Drag.MOVE && drag != Drag.RESIZE) {
            viewAfter = 0;
            JsonObject request = new JsonObject(); request.addProperty("action", "view");
            double margin = 128;
            request.addProperty("minX", viewport.documentX(viewport.left) - margin); request.addProperty("minY", viewport.documentY(viewport.top) - margin);
            request.addProperty("maxX", viewport.documentX(viewport.right) + margin); request.addProperty("maxY", viewport.documentY(viewport.bottom) + margin);
            send.accept(request);
        }
    }

    private void fit() {
        if (state.has("bounds") && state.get("bounds").isJsonObject()) {
            JsonObject bounds = state.getAsJsonObject("bounds");
            double x1 = number(bounds, "minX", originX), y1 = number(bounds, "minY", originY);
            double x2 = number(bounds, "maxX", x1 + 256), y2 = number(bounds, "maxY", y1 + 256);
            viewport.zoom = Math.clamp(Math.min((width - 40) / Math.max(64, x2 - x1), (viewport.bottom - viewport.top - 40) / Math.max(64, y2 - y1)), 0.12, 2);
            viewport.panX = (x1 + x2) / 2 - width / (2 * viewport.zoom);
            viewport.panY = (y1 + y2) / 2 - (viewport.bottom - viewport.top) / (2 * viewport.zoom);
        } else viewport.fit(elements.values(), originX, originY);
        queueView();
    }

    private CanvasElementView hit(double x, double y) {
        List<CanvasElementView> candidates = new ArrayList<>(elements.values());
        double tolerance = 6 / viewport.zoom;
        for (int i = candidates.size() - 1; i >= 0; i--) {
            CanvasElementView e = candidates.get(i); if (erased.contains(e.id)) continue;
            if (e.type.equals("LINE") || e.type.equals("ARROW") || e.type.equals("CONNECTOR")) {
                double[] p = CanvasPainter.endpoints(e, elements);
                if (distanceToSegment(x, y, p[0], p[1], p[2], p[3]) <= tolerance + e.thickness / 2) return e;
            } else if (e.type.equals("STROKE")) {
                for (int j = 0; j < e.points.size(); j++) {
                    CanvasElementView.Point a = e.points.get(Math.max(0, j - 1)), b = e.points.get(j);
                    if (distanceToSegment(x, y, a.x(), a.y(), b.x(), b.y()) <= tolerance + e.thickness / 2) return e;
                }
            } else if (x >= e.x - tolerance && x <= e.x + e.w + tolerance && y >= e.y - tolerance && y <= e.y + e.h + tolerance) return e;
        }
        return null;
    }

    private static double distanceToSegment(double x, double y, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double factor = dx == 0 && dy == 0 ? 0 : Math.clamp(((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy), 0, 1);
        return Math.hypot(x - x1 - factor * dx, y - y1 - factor * dy);
    }

    private void openDialog(Dialog next, String title) {
        cancelGesture(); dialog = next; dialogTitle = title; accessScroll = 0; textField = null; rebuildControls();
    }
    private void closeDialog() { dialog = Dialog.NONE; textField = null; textTarget = null; mergeTarget = null; rebuildControls(); }

    private void buildDialog(String previousText) {
        dialogW = Math.min(width - 20, dialog == Dialog.ACCESS || dialog == Dialog.MERGE ? 470 : 390);
        dialogH = Math.min(height - 16, dialog == Dialog.ACCESS || dialog == Dialog.MERGE ? 320 : dialog == Dialog.HELP ? 235 : dialog == Dialog.TEXT ? 230 : 120);
        dialogX = (width - dialogW) / 2; dialogY = (height - dialogH) / 2;
        if (dialog == Dialog.TEXT || dialog == Dialog.COLOR || dialog == Dialog.RENAME) {
            int fieldHeight = dialogH - 75;
            textField = addRenderableWidget(MultiLineEditBox.builder().setX(dialogX + 12).setY(dialogY + 31)
                .setShowBackground(true).setShowDecorations(true).setTextShadow(false).setTextColor(0xffe9e5db)
                .setPlaceholder(Component.literal(dialog == Dialog.TEXT ? tr("Escreva aqui…", "Write here…") : ""))
                .build(font, dialogW - 24, fieldHeight, Component.literal(dialogTitle)));
            // Large enough for real documents, bounded per element to keep network operations manageable.
            textField.setCharacterLimit(dialog == Dialog.TEXT ? 32768 : dialog == Dialog.RENAME ? 80 : 7);
            if (dialog != Dialog.TEXT) textField.setLineLimit(1);
            String initial = previousText != null ? previousText : dialog == Dialog.TEXT ? string(textTarget, "text", "")
                : dialog == Dialog.COLOR ? String.format("#%06X", color) : string(state, "name", "Canvas Desk");
            textField.setValue(initial);
            setInitialFocus(textField);
            button(dialogX + dialogW - 151, dialogY + dialogH - 30, 68, 20, tr("Cancelar", "Cancel"), null, this::closeDialog, () -> false, () -> true, null);
            button(dialogX + dialogW - 78, dialogY + dialogH - 30, 66, 20, tr("Salvar", "Save"), null, this::saveDialog, () -> false, () -> editable && !saving, null);
        } else if (dialog == Dialog.ACCESS) {
            List<AccessRow> rows = accessRows();
            int count = Math.max(1, (dialogH - 73) / 40);
            accessScroll = Math.clamp(accessScroll, 0, Math.max(0, rows.size() - count));
            for (int i = accessScroll; i < Math.min(rows.size(), accessScroll + count); i++) {
                AccessRow row = rows.get(i); int y = dialogY + 34 + (i - accessScroll) * 40;
                if (row.action != null) button(dialogX + dialogW - 80, y + 3, 66, 23, row.button, null, row.action,
                    () -> false, () -> owner, null);
            }
            button(dialogX + dialogW - 79, dialogY + dialogH - 29, 66, 20, tr("Fechar", "Close"), null, this::closeDialog, () -> false, () -> true, null);
        } else if (dialog == Dialog.MERGE) {
            button(dialogX + dialogW - 151, dialogY + dialogH - 30, 68, 20, tr("Cancelar", "Cancel"), null, this::closeDialog, () -> false, () -> true, null);
            button(dialogX + dialogW - 78, dialogY + dialogH - 30, 66, 20, tr("Aprovar", "Approve"), null, () -> {
                if (mergeTarget != null && owner) { request("approveMerge", "requestId", string(mergeTarget, "id", "")); closeDialog(); }
            }, () -> false, () -> owner, null);
        } else {
            button(dialogX + dialogW - 79, dialogY + dialogH - 29, 66, 20, tr("Fechar", "Close"), null, this::closeDialog, () -> false, () -> true, null);
        }
    }

    private void saveDialog() {
        if (textField == null || !canEdit()) return;
        String value = textField.getValue();
        if (dialog == Dialog.COLOR) {
            try {
                String hex = value.strip().replaceFirst("^#", "");
                if (!hex.matches("[a-fA-F0-9]{6}")) throw new NumberFormatException();
                setColor(Integer.parseInt(hex, 16)); closeDialog();
            }
            catch (NumberFormatException exception) { dialogTitle = tr("Use #RRGGBB, por exemplo #B97751", "Use #RRGGBB, for example #B97751"); }
            return;
        }
        if (dialog == Dialog.RENAME) {
            if (owner && !value.isBlank()) { request("rename", "name", value.strip()); closeDialog(); }
            return;
        }
        if (dialog == Dialog.TEXT && textTarget != null && !value.isBlank()) {
            JsonObject after = textTarget.deepCopy(); after.addProperty("text", value);
            if (string(after, "type", "").equals("CHECKLIST")) {
                JsonArray checks = after.getAsJsonArray("checks"); int lines = value.split("\n", -1).length;
                while (checks.size() < lines) checks.add(false); while (checks.size() > lines) checks.remove(checks.size() - 1);
            }
            CanvasElementView before = elements.get(string(after, "id", ""));
            if (before == null) {
                int pad = string(after, "type", "").equals("TEXT") ? 0 : 18;
                int rows = 0;
                for (String line : value.split("\n", -1)) rows += Math.max(1, font.split(Component.literal(line), (int) number(after, "w", 190) - pad - 14).size());
                after.addProperty("h", Math.max(number(after, "h", 120), rows * 12 + pad + 4));
            }
            selection.clear(); selection.add(string(after, "id", ""));
            submit(List.of(new Change(before, after))); closeDialog(); tool = Tool.SELECT;
        }
    }

    private void drawDialog(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, width, height, 0x990f100e);
        g.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, PAPER);
        g.outline(dialogX, dialogY, dialogW, dialogH, 0xff967a57);
        g.text(font, font.plainSubstrByWidth(dialogTitle, dialogW - 24), dialogX + 12, dialogY + 12, INK, false);
        if (dialog == Dialog.TEXT) g.text(font, tr("Ctrl+Enter salva · uma linha por item na lista", "Ctrl+Enter saves · one line per checklist item"), dialogX + 12, dialogY + dialogH - 44, MUTED, false);
        if (dialog == Dialog.ACCESS) {
            List<AccessRow> rows = accessRows(); int count = Math.max(1, (dialogH - 73) / 40);
            for (int i = accessScroll; i < Math.min(rows.size(), accessScroll + count); i++) {
                AccessRow row = rows.get(i); int y = dialogY + 34 + (i - accessScroll) * 40;
                g.fill(dialogX + 10, y - 2, dialogX + dialogW - 10, y + 35, 0xffeee9dd);
                g.text(font, font.plainSubstrByWidth(row.title, dialogW - 104), dialogX + 15, y + 3, INK, false);
                g.text(font, font.plainSubstrByWidth(row.description, dialogW - 104), dialogX + 15, y + 17, MUTED, false);
                if (mouseX >= dialogX + 10 && mouseX < dialogX + dialogW - 83 && mouseY >= y && mouseY < y + 35)
                    g.setTooltipForNextFrame(Component.literal(row.title + "\n" + row.description), mouseX, mouseY);
            }
            g.text(font, tr("Roda do mouse: mais pessoas e pedidos", "Mouse wheel: more players and requests"), dialogX + 12, dialogY + dialogH - 21, MUTED, false);
        }
        if (dialog == Dialog.MERGE && mergeTarget != null) {
            String details = tr("Esta união reúne todos os desenhos e concede acesso ao documento resultante às pessoas abaixo. Todos os donos precisam aprovar.",
                "This merge combines all drawings and grants access to the resulting document to the people below. Every owner must approve.")
                + "\n\n" + tr("Donos: ", "Owners: ") + names(ids(mergeTarget, "owners"))
                + "\n\n" + tr("Editores convidados: ", "Invited editors: ") + names(ids(mergeTarget, "editors"));
            var lines = font.split(Component.literal(details), dialogW - 28);
            int count = Math.max(1, (dialogH - 78) / 12);
            accessScroll = Math.clamp(accessScroll, 0, Math.max(0, lines.size() - count));
            for (int i = accessScroll; i < Math.min(lines.size(), accessScroll + count); i++)
                g.text(font, lines.get(i), dialogX + 14, dialogY + 35 + (i - accessScroll) * 12, INK, false);
        }
        if (dialog == Dialog.HELP) {
            String[] help = {
                tr("Arraste para desenhar, criar formas e mover.", "Drag to draw, create shapes and move."),
                tr("V seleciona · Shift+clique adiciona à seleção.", "V selects · Shift+click adds to selection."),
                tr("Alt+clique seleciona o grupo visível da união.", "Alt+click selects the visible merge group."),
                tr("Duplo clique ou Enter edita textos e listas.", "Double click or Enter edits text and lists."),
                tr("Clique na caixa de uma lista para marcar.", "Click a checklist box to toggle its item."),
                tr("Canto inferior direito redimensiona a seleção.", "Bottom right corner resizes the selection."),
                tr("K conecta: clique na origem e no destino.", "K connects: click the source and destination."),
                tr("Roda aproxima · Espaço/central arrasta a visão.", "Wheel zooms · Space/middle button pans."),
                tr("F enquadra · Delete apaga · setas movem.", "F fits · Delete removes · arrow keys move."),
                tr("Ctrl+Z desfaz · Ctrl+Y refaz suas alterações.", "Ctrl+Z undoes · Ctrl+Y redoes your edits."),
                tr("Um módulo revela 256 × 256 unidades do canvas.", "One module reveals 256 × 256 canvas units."),
                tr("Alterações são confirmadas após salvar no servidor.", "Edits are confirmed after the server saves them.")
            };
            int y = dialogY + 34;
            for (String line : help) { g.text(font, font.plainSubstrByWidth(line, dialogW - 24), dialogX + 12, y, INK, false); y += 14; }
        }
    }

    private List<AccessRow> accessRows() {
        List<AccessRow> rows = new ArrayList<>();
        Set<String> owners = ids(state, "owners"), editors = ids(state, "editors");
        if (state.has("merges")) for (JsonElement element : state.getAsJsonArray("merges")) {
            JsonObject merge = element.getAsJsonObject(); String id = string(merge, "id", "");
            String own = names(ids(merge, "owners")), invited = names(ids(merge, "editors"));
            boolean approved = minecraft.player != null && ids(merge, "approved").contains(minecraft.player.getUUID().toString());
            rows.add(new AccessRow(tr("União · donos: ", "Merge · owners: ") + own,
                tr("Editores após unir: ", "Editors after merging: ") + (invited.isEmpty() ? tr("nenhum", "none") : invited),
                approved ? tr("Aprovado", "Approved") : tr("Aprovar", "Approve"),
                owner && !approved ? () -> { mergeTarget = merge.deepCopy(); openDialog(Dialog.MERGE, tr("Confirmar união de documentos", "Confirm document merge")); } : null));
        }
        for (String id : owners) rows.add(new AccessRow(playerName(id), tr("Dono · acesso completo", "Owner · full access"), "", null));
        Set<String> shown = new LinkedHashSet<>(owners);
        if (state.has("players")) for (JsonElement element : state.getAsJsonArray("players")) {
            JsonObject player = element.getAsJsonObject(); String id = string(player, "id", ""); if (!shown.add(id)) continue;
            boolean invited = editors.contains(id);
            rows.add(new AccessRow(string(player, "name", id), invited ? tr("Editor convidado", "Invited editor") : tr("Online · somente leitura", "Online · read only"),
                invited ? tr("Remover", "Remove") : tr("Convidar", "Invite"), owner ? () -> request(invited ? "revoke" : "invite", "player", id) : null));
        }
        for (String id : editors) if (shown.add(id)) rows.add(new AccessRow(playerName(id), tr("Editor · offline", "Editor · offline"), tr("Remover", "Remove"),
            owner ? () -> request("revoke", "player", id) : null));
        if (rows.isEmpty()) rows.add(new AccessRow(tr("Nenhum pedido pendente", "No pending request"), tr("Somente donos podem convidar editores", "Only owners can invite editors"), "", null));
        return rows;
    }

    private static Set<String> ids(JsonObject object, String key) {
        Set<String> values = new LinkedHashSet<>();
        if (object.has(key) && object.get(key).isJsonArray()) for (JsonElement value : object.getAsJsonArray(key)) values.add(value.getAsString());
        return values;
    }
    private String names(Set<String> values) { return String.join(", ", values.stream().map(this::playerName).toList()); }
    private String playerName(String id) {
        if (state.has("players")) for (JsonElement element : state.getAsJsonArray("players")) {
            JsonObject player = element.getAsJsonObject(); if (string(player, "id", "").equals(id)) return string(player, "name", id);
        }
        return id;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override protected Component getUsageNarration() { return Component.literal(tr("Canvas Desk. F1 abre os controles. Tab percorre as ferramentas.", "Canvas Desk. F1 opens controls. Tab navigates tools.")); }
    @Override public void onClose() {
        if (dialog != Dialog.NONE) { closeDialog(); return; }
        if (saving) { status(tr("Aguardando confirmação do servidor…", "Waiting for server confirmation…")); return; }
        sendClose(); minecraft.setScreenAndShow(null);
    }
    @Override public void removed() { sendClose(); }
    private void sendClose() {
        if (closeSent) return;
        closeSent = true; JsonObject request = new JsonObject(); request.addProperty("action", "close"); send.accept(request);
    }
}
