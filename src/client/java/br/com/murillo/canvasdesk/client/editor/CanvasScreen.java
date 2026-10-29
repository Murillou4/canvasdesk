package br.com.murillo.canvasdesk.client.editor;

import br.com.murillo.canvasdesk.block.SurfaceLayout;
import br.com.murillo.canvasdesk.block.ElementFootprint;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.PreeditEvent;
import java.util.ArrayDeque;
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
    private enum Dialog { NONE, TEXT, COLOR, FONT, RENAME, ACCESS, MERGE, HELP, TOOLS, PROPERTIES, CONFLICT }
    private record Change(CanvasElementView before, JsonObject after) {}
    private record AccessRow(String title, String description, String button, Runnable action) {}

    private static final int PAPER = 0xfffafaf7, INK = 0xff34342e, MUTED = 0xff827b6d, BORDER = 0xffd8d0bf, ACCENT = 0xffa46e48;
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
    private double thickness = 3, fontSize = 18, originX, originY, startX, startY, currentX, currentY;
    private boolean editable, owner, initialized, saving, spaceHeld, closeSent;
    private String message = "", connectorStart = "", dialogTitle = "";
    private String pendingAction = "", pendingOperation = "";
    private long messageUntil, viewAfter;
    private MultiLineEditBox textField;
    private JsonObject textTarget;
    private JsonObject mergeTarget;
    private int dialogX, dialogY, dialogW, dialogH;
    private int panelX,panelY,panelW,panelH;
    private boolean autoFit=true,surfaceUpdating,closing,textSelecting;
    private SurfaceLayout surface;
    private final Map<String,List<String>> paintTiles=new LinkedHashMap<>();
    private InlineTextEditor inline;
    private CanvasElementView textBase;
    private record TextCommit(JsonObject value,String group) {}
    private final ArrayDeque<TextCommit> textQueue=new ArrayDeque<>();
    private TextCommit inFlightText;
    private String actionAfterText="";
    private CanvasElementView lastCoverageDraft;
    private long lastCoverageRevision=-1;
    private boolean textOverflow;

    public CanvasScreen(JsonObject state, Consumer<JsonObject> send) {
        super(Component.literal("Canvas Desk"));
        this.send = send;
        acceptState(state);
        originX = number(state, "originX", 0); originY = number(state, "originY", 0);
        viewport.panX = originX - 24; viewport.panY = originY - 24;
    }

    /** Network updates replace the visible region while keeping camera and selection stable. */
    public void acceptState(JsonObject incoming) {
        boolean ownAck=incoming.has("ackAction")&&string(incoming,"ackAction","").equals(pendingAction)
            &&(pendingOperation.isEmpty()||string(incoming,"ackOperation","").equals(pendingOperation));
        boolean surfaceChanged=false;
        if(incoming.has("surface")&&(surface==null||number(incoming.getAsJsonObject("surface"),"revision",-1)!=surface.revision)){
            surface=SurfaceLayout.fromJson(incoming.getAsJsonObject("surface"));surfaceChanged=true;
        }
        surfaceUpdating=incoming.has("surfaceUpdating")&&incoming.get("surfaceUpdating").getAsBoolean();
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
        selection.retainAll(elements.keySet());selection.removeIf(id->!visible(elements.get(id)));
        if (incoming.has("ackAction") && string(incoming, "ackAction", "").equals(pendingAction)
            && (pendingOperation.isEmpty() || string(incoming, "ackOperation", "").equals(pendingOperation))) {
            saving = false; pendingAction = ""; pendingOperation = "";
        }
        if (incoming.has("message") && !incoming.get("message").getAsString().isBlank()) status(incoming.get("message").getAsString());
        acceptInline(incoming,ownAck);
        if(inline!=null&&inline.active)selection.add(inline.id);
        if(initialized){
            rebuildPaintIndex();
            if(surfaceChanged){cancelGesture();if(autoFit)fit();else queueView();}
            if(dialog==Dialog.ACCESS)rebuildControls();
        }
        if (!editable && drag != Drag.NONE && drag != Drag.PAN && drag != Drag.BOX) cancelGesture();
    }

    @Override protected void init() {
        double centerX=viewport.documentX((viewport.left+viewport.right)/2.0),centerY=viewport.documentY((viewport.top+viewport.bottom)/2.0);
        panelW=Math.min(800,Math.max(160,(int)(width*.86)));panelH=Math.min(500,Math.max(120,(int)(height*.84)));
        panelW=Math.min(width-8,panelW);panelH=Math.min(height-8,panelH);panelX=(width-panelW)/2;panelY=(height-panelH)/2;
        viewport.left=panelX+1;viewport.top=panelY+46;viewport.right=panelX+panelW-1;viewport.bottom=panelY+panelH-15;
        if(initialized&&!autoFit){viewport.panX=centerX-(viewport.right-viewport.left)/(2*viewport.zoom);viewport.panY=centerY-(viewport.bottom-viewport.top)/(2*viewport.zoom);}
        initialized=true;rebuildControls();rebuildPaintIndex();if(autoFit)fit();else queueView();
    }
    private void rebuildControls() {
        String currentText=textField==null?null:textField.getValue();clearWidgets();textField=null;
        if(dialog!=Dialog.NONE){buildDialog(currentText);return;}
        int y=panelY+22,x=panelX+4;
        icon(x,y,"select",tr("Selecionar · V","Select · V"),()->selectTool(Tool.SELECT),()->tool==Tool.SELECT,()->true);x+=24;
        icon(x,y,"undo",tr("Desfazer · Ctrl+Z","Undo · Ctrl+Z"),()->action("undo"),()->false,this::canEdit);x+=24;
        icon(x,y,"redo",tr("Refazer · Ctrl+Y","Redo · Ctrl+Y"),()->action("redo"),()->false,this::canEdit);x+=24;
        icon(x,y,"fit",tr("Enquadrar superfície · F","Fit surface · F"),this::fit,()->false,()->true);x+=24;
        int count=Math.min(Tool.values().length-1,Math.max(0,(panelW-8-96-48)/24));
        for(int i=1;i<=count;i++){
            Tool candidate=Tool.values()[i];icon(x,y,candidate.sprite,tr(candidate.pt,candidate.en)+" · "+candidate.key,()->selectTool(candidate),()->tool==candidate,()->editable||candidate==Tool.PAN);x+=24;
        }
        icon(x,y,"palette",tr("Cor, espessura e fonte","Color, stroke and font"),()->openDialog(Dialog.PROPERTIES,tr("Propriedades","Properties")),()->false,()->editable);x+=24;
        button(x,y,22,21,"…",null,()->openDialog(Dialog.TOOLS,tr("Ferramentas","Tools")),()->false,()->true,null);
        int right=panelX+panelW;
        icon(right-74,panelY+1,"invite",tr("Acesso e uniões","Access and merges"),()->openDialog(Dialog.ACCESS,tr("Acesso ao documento","Document access")),()->false,()->true);
        button(right-50,panelY+1,22,20,"?",null,()->openDialog(Dialog.HELP,tr("Controles","Controls")),()->false,()->true,null);
        icon(right-26,panelY+1,"close",tr("Fechar · Esc","Close · Esc"),this::onClose,()->false,()->true);
    }
    private void button(int x, int y, int w, int h, String text, String sprite, Runnable action,
                        java.util.function.BooleanSupplier selected, java.util.function.BooleanSupplier enabled, Integer swatch) {
        addRenderableWidget(new CanvasButton(x, y, Math.max(20,w), Math.max(20,h), text, sprite, ()->{if(inline!=null&&inline.active)finishInline();EditorPreferences.feedback(false);action.run();}, selected, enabled, swatch));
    }
    private void icon(int x, int y, String sprite, String text, Runnable action,
                      java.util.function.BooleanSupplier selected, java.util.function.BooleanSupplier enabled) {
        button(x, y, 22, 21, text, sprite, action, selected, enabled, null);
    }
    private boolean canEdit() { return editable && !saving && !surfaceUpdating && (inline==null||!inline.conflict); }
    private String tr(String pt, String en) { return minecraft.getLanguageManager().getSelected().startsWith("pt") ? pt : en; }
    private void status(String text) { message = text; messageUntil = System.currentTimeMillis() + 6500; }

    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta){
        g.fill(panelX,panelY,panelX+panelW,panelY+panelH,PAPER);drawCanvas(g,mouseX,mouseY);
        g.fill(panelX,panelY,panelX+panelW,panelY+45,0xffeae4d6);g.outline(panelX,panelY,panelW,panelH,BORDER);
        String name=string(state,"name","Canvas Desk");g.text(font,font.plainSubstrByWidth(name,Math.max(20,panelW-85)),panelX+7,panelY+7,INK,false);
        if(mouseY>=panelY&&mouseY<panelY+21&&mouseX>=panelX&&mouseX<panelX+panelW-78)g.setTooltipForNextFrame(Component.literal(owner?tr("Clique para renomear","Click to rename"):name),mouseX,mouseY);
        g.fill(panelX+1,viewport.bottom,panelX+panelW-1,panelY+panelH-1,0xffeae4d6);
        boolean pending=saving||!textQueue.isEmpty()||inline!=null&&inline.dirty();
        String info=surfaceUpdating?tr("Atualizando superfície…","Updating surface…"):pending?tr("Gravando…","Saving…"):textOverflow?tr("Parte do texto aguarda superfície","Part of the text needs more surface"):System.currentTimeMillis()<messageUntil?message:
            !editable?tr("Somente leitura","Read only"):tr(tool.pt,tool.en)+" · "+tr("F enquadra","F fits")+(surface==null?"":" · "+surface.tiles.size()+tr(" módulos"," modules"));
        g.text(font,font.plainSubstrByWidth(info,Math.max(20,panelW-58)),panelX+6,viewport.bottom+4,pending?ACCENT:MUTED,false);
        g.text(font,(int)Math.round(viewport.zoom*100)+"%",panelX+panelW-42,viewport.bottom+4,MUTED,false);
        if(dialog!=Dialog.NONE)drawDialog(g,mouseX,mouseY);super.extractRenderState(g,mouseX,mouseY,delta);
    }
    private SurfaceLayout.Rect elementRect(CanvasElementView e){
        if(e.type.equals("TEXT")&&font!=null){CanvasTextLayout l=CanvasTextLayout.of(font,e);return new SurfaceLayout.Rect(e.x,e.y,e.x+l.width(),e.y+l.height());}
        return new SurfaceLayout.Rect(e.x,e.y,e.x+Math.max(1,e.w),e.y+Math.max(1,e.h));
    }
    private boolean visible(CanvasElementView e){
        if(e==null)return false;if(surface==null)return true;
        return e.type.equals("TEXT")?surface.intersects(elementRect(e)):ElementFootprint.visible(surface,e.json);
    }
    private boolean onSurface(double x,double y){return surface!=null&&!surfaceUpdating&&surface.contains(x,y);}
    private void rebuildPaintIndex(){
        paintTiles.clear();if(surface==null)return;
        for(CanvasElementView e:elements.values())for(var tile:surface.query(elementRect(e)))paintTiles.computeIfAbsent(tile.id(),k->new ArrayList<>()).add(e.id);
    }
    private void drawCanvas(GuiGraphicsExtractor g,int mouseX,int mouseY){
        if(viewport.bottom<=viewport.top)return;
        g.enableScissor(viewport.left,viewport.top,viewport.right,viewport.bottom);
        g.fill(viewport.left,viewport.top,viewport.right,viewport.bottom,0xffd7d1c6);
        for(int x=viewport.left-(viewport.bottom-viewport.top);x<viewport.right;x+=12)CanvasPainter.line(g,x,viewport.bottom,x+viewport.bottom-viewport.top,viewport.top,1,0xffc9c2b5);
        Map<String,CanvasElementView> shown=shownElements();
        if(surface!=null){
            var area=new SurfaceLayout.Rect(viewport.documentX(viewport.left),viewport.documentY(viewport.top),viewport.documentX(viewport.right),viewport.documentY(viewport.bottom));
            for(var tile:surface.query(area)){
                var bounds=tile.bounds();var paper=tile.paper();
                fillDocument(g,bounds,0xff775f4b);fillDocument(g,paper,0xff000000|tile.color());
                int left=Math.max(viewport.left,(int)Math.ceil(viewport.screenX(paper.minX()))),top=Math.max(viewport.top,(int)Math.ceil(viewport.screenY(paper.minY())));
                int right=Math.min(viewport.right,(int)Math.ceil(viewport.screenX(paper.maxX()))),bottom=Math.min(viewport.bottom,(int)Math.ceil(viewport.screenY(paper.maxY())));
                if(right<=left||bottom<=top)continue;g.enableScissor(left,top,right,bottom);
                double grid=32;while(grid*viewport.zoom<18)grid*=2;
                int gridColor=((tile.color()>>16&255)+(tile.color()>>8&255)+(tile.color()&255)>350)?0x44796f5e:0x44ffffff;
                for(double x=Math.floor(viewport.documentX(left)/grid)*grid;viewport.screenX(x)<right;x+=grid)
                    for(double y=Math.floor(viewport.documentY(top)/grid)*grid;viewport.screenY(y)<bottom;y+=grid)g.fill((int)viewport.screenX(x),(int)viewport.screenY(y),(int)viewport.screenX(x)+1,(int)viewport.screenY(y)+1,gridColor);
                for(String id:paintTiles.getOrDefault(tile.id(),List.of())){
                    if(dragPreview.containsKey(id)||erased.contains(id)||inline!=null&&inline.id.equals(id))continue;
                    CanvasElementView e=shown.get(id);if(e!=null)CanvasPainter.paint(g,font,viewport,e,shown);
                }
                for(var e:dragPreview.values())if(elementRect(e).intersects(paper))CanvasPainter.paint(g,font,viewport,e,shown);
                if(draft!=null)CanvasPainter.paint(g,font,viewport,new CanvasElementView(draft),shown);
                if(inline!=null){CanvasPainter.paint(g,font,viewport,inline.view(),shown);inline.paint(g,viewport);}
                g.disableScissor();
            }
        }
        for(String id:selection){CanvasElementView e=shown.get(id);if(!visible(e))continue;var r=elementRect(e);
            int x=(int)viewport.screenX(r.minX())-2,y=(int)viewport.screenY(r.minY())-2,w=Math.max(6,(int)((r.maxX()-r.minX())*viewport.zoom))+4,h=Math.max(6,(int)((r.maxY()-r.minY())*viewport.zoom))+4;
            g.outline(x,y,w,h,ACCENT);if(selection.size()==1&&editable&&!e.type.equals("CONNECTOR")&&(inline==null||!inline.active))g.fill(x+w-3,y+h-3,x+w+3,y+h+3,ACCENT);
        }
        if(drag==Drag.BOX){int x1=(int)viewport.screenX(Math.min(startX,currentX)),y1=(int)viewport.screenY(Math.min(startY,currentY)),x2=(int)viewport.screenX(Math.max(startX,currentX)),y2=(int)viewport.screenY(Math.max(startY,currentY));g.outline(x1,y1,x2-x1,y2-y1,ACCENT);}
        if(!connectorStart.isEmpty()&&shown.containsKey(connectorStart)){var e=shown.get(connectorStart);CanvasPainter.line(g,viewport.screenX(e.centerX()),viewport.screenY(e.centerY()),mouseX,mouseY,2,ACCENT);}
        g.disableScissor();
    }
    private void fillDocument(GuiGraphicsExtractor g,SurfaceLayout.Rect r,int color){g.fill((int)Math.floor(viewport.screenX(r.minX())),(int)Math.floor(viewport.screenY(r.minY())),(int)Math.ceil(viewport.screenX(r.maxX())),(int)Math.ceil(viewport.screenY(r.maxY())),color);}
    private Map<String, CanvasElementView> shownElements() {
        if (dragPreview.isEmpty() && erased.isEmpty() && inline==null) return elements;
        Map<String, CanvasElementView> result = new LinkedHashMap<>(elements);
        result.putAll(dragPreview);if(inline!=null)result.put(inline.id,inline.view()); for (String id : erased) result.remove(id);
        return result;
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (dialog != Dialog.NONE) return super.mouseClicked(event, doubleClick);
        if(inline!=null&&inline.active&&event.button()==InputConstants.MOUSE_BUTTON_LEFT){
            double x=viewport.documentX(event.x()),y=viewport.documentY(event.y());
            if(viewport.contains(event.x(),event.y())&&onSurface(x,y)&&elementRect(inline.view()).contains(x,y)){
                inline.point(x,y,event.hasShiftDown(),doubleClick);textSelecting=true;return true;
            }
            finishInline();
        }
        if (super.mouseClicked(event, doubleClick)) {
            // A button can replace all widgets. Restore focus after the native click dispatcher returns.
            if (textField != null) setFocused(textField);
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.y()>=panelY && event.y()<panelY+21 && event.x()>=panelX && event.x()<panelX+panelW-78 && owner) {
            openDialog(Dialog.RENAME, tr("Nome do documento", "Document name")); return true;
        }
        if (!viewport.contains(event.x(), event.y())) return true;
        clearFocus();
        startX = currentX = viewport.documentX(event.x()); startY = currentY = viewport.documentY(event.y());
        if (event.button() == InputConstants.MOUSE_BUTTON_MIDDLE || event.button() == InputConstants.MOUSE_BUTTON_LEFT && (spaceHeld || tool == Tool.PAN)) { autoFit=false;drag = Drag.PAN; return true; }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        CanvasElementView hit = hit(currentX, currentY);
        if (tool == Tool.SELECT) { startSelection(event, doubleClick, hit); return true; }
        if(!onSurface(currentX,currentY)){status(tr("Área sem superfície disponível","No board surface here"));return true;}
        if (!canEdit()) { status(tr("Aguarde a confirmação do servidor", "Wait for server confirmation")); return true; }
        if (tool == Tool.CONNECTOR) { connect(hit); return true; }
        if (tool == Tool.ERASER) { drag = Drag.ERASE; erased.clear(); if (hit != null) erased.add(hit.id); return true; }
        if (tool == Tool.TEXT || tool == Tool.NOTE || tool == Tool.CHECKLIST) {
            textTarget = CanvasElementView.create(tool.name(), currentX, currentY, tool == Tool.CHECKLIST ? 256 : tool == Tool.TEXT ? 160 : 190, 120, color, thickness);
            textTarget.addProperty("fontSize",fontSize);
            if(tool==Tool.TEXT)startInline(textTarget);else openDialog(Dialog.TEXT,tr(tool.pt,tool.en));return true;
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
            if (selected != null && onSurface(currentX,currentY) && !selected.type.equals("CONNECTOR") && Math.abs(event.x() - viewport.screenX(elementRect(selected).maxX())) < 8
                && Math.abs(event.y() - viewport.screenY(elementRect(selected).maxY())) < 8) {
                beginTransform(Drag.RESIZE); return;
            }
        }
        if (hit == null) { if (!event.hasShiftDown()) selection.clear(); drag = Drag.BOX; return; }
        if (doubleClick && isText(hit) && canEdit()) { selection.clear(); selection.add(hit.id); editSelected(); return; }
        if (canEdit() && hit.type.equals("CHECKLIST")) {
            int row = CanvasTextLayout.of(font,hit).checkAt(currentX-hit.x,currentY-hit.y);
            if (row >= 0) {
                JsonObject after = hit.json.deepCopy(); JsonArray checks = after.getAsJsonArray("checks");
                while (checks.size() <= row) checks.add(false);
                checks.set(row, new com.google.gson.JsonPrimitive(!checks.get(row).getAsBoolean()));
                submit(List.of(new Change(hit, after))); return;
            }
        }
        if (event.hasAltDown() && !hit.group.isEmpty()) {
            if (!event.hasShiftDown()) selection.clear();
            for (CanvasElementView e : elements.values()) if (e.group.equals(hit.group)&&visible(e)) selection.add(e.id);
            status(tr("Grupo selecionado · ", "Group selected · ") + selection.size() + tr(" elementos visíveis", " visible elements"));
        } else if (event.hasShiftDown()) { if (!selection.add(hit.id)) selection.remove(hit.id); }
        else if (!selection.contains(hit.id)) {
            selection.clear(); selection.add(hit.id);
        }
        color = hit.color; thickness = hit.thickness;if(isText(hit))fontSize=hit.fontSize;
        if (canEdit() && !selection.isEmpty()) beginTransform(Drag.MOVE);
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
        if(inline!=null&&inline.active&&textSelecting){inline.point(viewport.documentX(event.x()),viewport.documentY(event.y()),true,false);return true;}
        currentX = viewport.documentX(event.x()); currentY = viewport.documentY(event.y());
        switch (drag) {
            case PAN -> { autoFit=false;viewport.panX -= dx / viewport.zoom; viewport.panY -= dy / viewport.zoom; queueView(); }
            case DRAW -> updateDraft();
            case MOVE -> {
                dragPreview.clear();
                for (CanvasElementView e : dragBefore.values()) dragPreview.put(e.id, e.translated(currentX - startX, currentY - startY));
            }
            case RESIZE -> {
                for (CanvasElementView e : dragBefore.values()){
                    if(e.type.equals("TEXT")){var r=elementRect(e);double w=r.maxX()-r.minX(),h=r.maxY()-r.minY();double factor=((currentX-e.x)*w+(currentY-e.y)*h)/Math.max(1,w*w+h*h);dragPreview.put(e.id,e.resized(e.w*factor,e.h*factor));}
                    else dragPreview.put(e.id,e.resized(currentX-e.x,currentY-e.y));
                }
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
        textSelecting=false;
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
            for (CanvasElementView e : elements.values()) if (visible(e)&&elementRect(e).intersects(new SurfaceLayout.Rect(x1,y1,x2,y2))&&surface.query(elementRect(e)).stream().anyMatch(t->t.paper().intersects(new SurfaceLayout.Rect(x1,y1,x2,y2)))) selection.add(e.id);
        }
        cancelGesture(); return true;
    }

    private void cancelGesture() { drag = Drag.NONE; draft = null; dragPreview.clear(); dragBefore.clear(); erased.clear(); }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (dialog == Dialog.ACCESS || dialog == Dialog.MERGE || dialog==Dialog.HELP) {
            accessScroll = Math.max(0, accessScroll - (int) Math.signum(vertical)); rebuildControls(); return true;
        }
        if (dialog != Dialog.NONE) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        if (!viewport.contains(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        autoFit=false;viewport.panX -= horizontal * 32 / viewport.zoom;
        viewport.zoomAt(mouseX, mouseY, Math.pow(1.15, vertical)); queueView(); return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (dialog != Dialog.NONE) {
            if (event.isEscape()) { closeDialog(); return true; }
            if (textField != null && event.hasControlDown() && (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER)) { saveDialog(); return true; }
            return super.keyPressed(event);
        }
        if(inline!=null&&inline.active){
            if(event.isEscape()||event.hasControlDown()&&(event.key()==InputConstants.KEY_RETURN||event.key()==InputConstants.KEY_NUMPADENTER)){finishInline();return true;}
            if(event.hasControlDown()&&(event.key()==InputConstants.KEY_Z||event.key()==InputConstants.KEY_Y)){action(event.key()==InputConstants.KEY_Y||event.hasShiftDown()?"redo":"undo");return true;}
            if(editable&&!inline.conflict){beforeInlineInput();inline.key(event);}return true;
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
            if (event.isSelectAll()) { selection.clear(); for(var e:elements.values())if(visible(e))selection.add(e.id); selectTool(Tool.SELECT); return true; }
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
        finishInline();cancelGesture(); connectorStart = ""; tool = next; clearFocus();
        if(next==Tool.CHECKLIST)fontSize=18;else if(next==Tool.TEXT||next==Tool.NOTE)fontSize=9;
    }

    private void setColor(int next) {
        color = next;
        updateSelectedProperty("color", next);
    }
    private void setThickness(int next) { thickness = next; updateSelectedProperty("thickness", next); }
    private void fitText(JsonObject value) {
        CanvasElementView e=new CanvasElementView(value);
        value.addProperty("h",e.type.equals("TEXT")?CanvasTextLayout.of(font,e).height():Math.max(e.h,CanvasTextLayout.of(font,e).height()));
    }
    private void setFontSize(double next) {
        fontSize=next;List<Change> changes=new ArrayList<>();
        for(String id:selection){CanvasElementView e=elements.get(id);if(!isText(e))continue;
            JsonObject after=e.json.deepCopy();after.addProperty("fontSize",next);fitText(after);changes.add(new Change(e,after));}
        submit(changes);
    }
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
        textTarget = e.json.deepCopy();if(e.type.equals("TEXT"))startInline(textTarget);else openDialog(Dialog.TEXT,tr("Editar texto","Edit text"));
    }
    private static boolean isText(CanvasElementView e) { return e != null && (e.type.equals("TEXT") || e.type.equals("NOTE") || e.type.equals("CHECKLIST")); }

    private void startInline(JsonObject value){
        if(inline!=null&&(inline.dirty()||!textQueue.isEmpty()||saving)){status(tr("Aguarde a gravação do texto anterior","Wait for the previous text to save"));return;}
        inline=new InlineTextEditor(font,value);textBase=elements.get(inline.id);selection.clear();selection.add(inline.id);clearFocus();
    }
    private void beforeInlineInput(){if(inline!=null&&inline.dirty()&&System.currentTimeMillis()-inline.lastInput>=750)flushInline(true);}
    private void flushInline(boolean endBurst){
        if(inline==null||inline.conflict||!editable)return;
        if(inline.dirty()){
            String group=inline.group.isEmpty()?UUID.randomUUID().toString():inline.group;
            TextCommit commit=new TextCommit(inline.snapshot(),group);
            if(!textQueue.isEmpty()&&textQueue.getLast().group.equals(group))textQueue.removeLast();
            textQueue.add(commit);inline.queuedValue=inline.input.value();inline.checkpoint=System.currentTimeMillis();
        }
        if(endBurst)inline.group="";
        sendNextText();
    }
    private void finishInline(){if(inline==null)return;inline.active=false;textSelecting=false;flushInline(true);}
    private void sendNextText(){
        if(saving||surfaceUpdating||surface==null||inline==null||inline.conflict||!editable||textQueue.isEmpty())return;
        inFlightText=textQueue.removeFirst();JsonObject value=inFlightText.value;
        JsonObject request=new JsonObject();request.addProperty("action","edit");request.addProperty("surfaceRevision",surface.revision);
        request.addProperty("typingGroup",inFlightText.group);pendingAction="edit";pendingOperation=UUID.randomUUID().toString();request.addProperty("operationId",pendingOperation);
        JsonObject change=new JsonObject();change.addProperty("id",inline.id);change.addProperty("expectedRevision",textBase==null?-1:textBase.revision);change.add("after",value.deepCopy());
        JsonArray changes=new JsonArray();changes.add(change);request.add("changes",changes);saving=true;send.accept(request);
    }
    private void acceptInline(JsonObject incoming,boolean ownAck){
        if(inline==null)return;
        CanvasElementView remote=elements.get(inline.id);
        boolean pending=inline.dirty()||!textQueue.isEmpty()||inFlightText!=null;
        boolean changed=remote!=null&&(textBase==null||remote.revision!=textBase.revision);
        boolean acknowledged=ownAck&&inFlightText!=null;
        if(acknowledged){
            if(!incoming.has("ok")||incoming.get("ok").getAsBoolean()){
                textBase=remote;inFlightText=null;
            }else {inFlightText=null;inline.conflict=true;}
        }else if(changed&&pending){
            if(inFlightText==null||!remote.text.equals(string(inFlightText.value,"text","")))inline.conflict=true;
        }else if(!pending&&changed){
            if(inline.active){inline.conflict=true;}else textBase=remote;
        }
        if(!editable&&pending)inline.conflict=true;
        if(inline.conflict&&initialized&&dialog!=Dialog.CONFLICT){
            closing=false;EditorPreferences.recover(string(state,"workspace",""),inline.snapshot());
            openDialog(Dialog.CONFLICT,tr("Texto preservado · conflito de gravação","Text preserved · save conflict"));
        }
    }
    @Override public boolean charTyped(CharacterEvent event){
        if(dialog==Dialog.NONE&&inline!=null&&inline.active){if(editable&&!inline.conflict){beforeInlineInput();inline.character(event);}return true;}
        return super.charTyped(event);
    }
    @Override public boolean preeditUpdated(PreeditEvent event){
        if(dialog==Dialog.NONE&&inline!=null&&inline.active)return inline.preedit(event);return super.preeditUpdated(event);
    }
    @Override public boolean capturesInput(){return inline!=null&&inline.active||super.capturesInput();}
    public void serverClosed(String reason){
        if(inline!=null&&(inline.dirty()||saving||!textQueue.isEmpty()))EditorPreferences.recover(string(state,"workspace",""),inline.snapshot());
        minecraft.gui.chatListener().handleSystemMessage(Component.literal(reason),false);
        closeSent=true;minecraft.setScreenAndShow(null);
    }

    private void submit(Collection<Change> changes) {
        if (!canEdit() || changes.isEmpty()) return;
        for(Change c:changes)if(c.after!=null&&!visible(new CanvasElementView(c.after))){status(tr("Mantenha parte do elemento sobre o quadro","Keep part of the element on the board"));return;}
        JsonObject request = new JsonObject(); request.addProperty("action", "edit");request.addProperty("surfaceRevision",surface.revision);
        pendingAction = "edit"; pendingOperation = UUID.randomUUID().toString(); request.addProperty("operationId", pendingOperation);
        JsonArray operations = new JsonArray();
        for (Change change : changes) {
            String id = change.before == null ? string(change.after, "id", "") : change.before.id;
            JsonObject item = new JsonObject(); item.addProperty("id", id); item.addProperty("expectedRevision", change.before == null ? -1 : change.before.revision);
            item.add("after", change.after == null ? JsonNull.INSTANCE : change.after.deepCopy()); operations.add(item);
            if (change.after == null) elements.remove(id); else elements.put(id, new CanvasElementView(change.after));
        }
        request.add("changes", operations);rebuildPaintIndex(); saving = true; send.accept(request);
    }

    private void action(String action) {
        if(inline!=null&&(inline.active||inline.dirty()||inFlightText!=null||!textQueue.isEmpty())){actionAfterText=action;finishInline();return;}
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
        long now=System.currentTimeMillis();
        if(inline!=null&&surface!=null&&(lastCoverageDraft!=inline.view()||lastCoverageRevision!=surface.revision)){
            lastCoverageDraft=inline.view();lastCoverageRevision=surface.revision;textOverflow=!surface.covers(elementRect(inline.view()));
        }else if(inline==null)textOverflow=false;
        if(inline!=null&&!inline.conflict&&inline.active){
            if(inline.dirty()&&now-inline.lastInput>=750)flushInline(true);
            else if(inline.dirty()&&now-inline.checkpoint>=5000)flushInline(false);
        }
        sendNextText();
        if(!saving&&textQueue.isEmpty()&&(inline==null||!inline.dirty())){
            if(inline!=null&&!inline.active)inline=null;
            if(!actionAfterText.isEmpty()){String next=actionAfterText;actionAfterText="";action(next);}
            else if(closing){sendClose();minecraft.setScreenAndShow(null);return;}
        }
        if (viewAfter != 0 && System.currentTimeMillis() >= viewAfter && drag != Drag.DRAW && drag != Drag.MOVE && drag != Drag.RESIZE) {
            viewAfter = 0;
            JsonObject request = new JsonObject(); request.addProperty("action", "view");
            double margin = 128;
            request.addProperty("minX", viewport.documentX(viewport.left) - margin); request.addProperty("minY", viewport.documentY(viewport.top) - margin);
            request.addProperty("maxX", viewport.documentX(viewport.right) + margin); request.addProperty("maxY", viewport.documentY(viewport.bottom) + margin);
            send.accept(request);
        }
    }

    private void fit(){
        autoFit=true;
        if(surface!=null){var b=surface.bounds;viewport.fitBounds(b.minX(),b.minY(),b.maxX(),b.maxY());}
        else viewport.fitBounds(originX,originY,originX+256,originY+256);queueView();
    }
    private CanvasElementView hit(double x, double y) {
        if(!onSurface(x,y))return null;
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
            } else {var r=elementRect(e);if(x>=r.minX()-tolerance&&x<=r.maxX()+tolerance&&y>=r.minY()-tolerance&&y<=r.maxY()+tolerance)return e;}
        }
        return null;
    }

    private static double distanceToSegment(double x, double y, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double factor = dx == 0 && dy == 0 ? 0 : Math.clamp(((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy), 0, 1);
        return Math.hypot(x - x1 - factor * dx, y - y1 - factor * dy);
    }

    private void openDialog(Dialog next, String title) {
        if(next!=Dialog.CONFLICT)finishInline();cancelGesture(); dialog = next; dialogTitle = title; accessScroll = 0; textField = null; rebuildControls();
    }
    private void closeDialog() { if(dialog==Dialog.CONFLICT)return;dialog = Dialog.NONE; textField = null; textTarget = null; mergeTarget = null; rebuildControls(); }

    private void buildDialog(String previousText) {
        dialogW = Math.min(width - 20, dialog == Dialog.ACCESS || dialog == Dialog.MERGE || dialog==Dialog.HELP ? 470 : 390);
        dialogH = Math.min(height - 16, dialog == Dialog.ACCESS || dialog == Dialog.MERGE || dialog==Dialog.HELP ? 320 : dialog == Dialog.HELP ? 235 : dialog == Dialog.TEXT ? 230 : 120);
        dialogX = (width - dialogW) / 2; dialogY = (height - dialogH) / 2;
        if(dialog==Dialog.TOOLS||dialog==Dialog.PROPERTIES||dialog==Dialog.CONFLICT){dialogH=Math.min(height-16,dialog==Dialog.CONFLICT?170:190);dialogY=(height-dialogH)/2;}
        if(dialog==Dialog.TOOLS){
            int columns=Math.max(1,(dialogW-24)/85),cell=(dialogW-24)/columns;
            for(int i=0;i<Tool.values().length;i++){Tool candidate=Tool.values()[i];button(dialogX+12+(i%columns)*cell,dialogY+32+(i/columns)*25,cell-3,22,tr(candidate.pt,candidate.en)+" · "+candidate.key,null,()->{selectTool(candidate);closeDialog();},()->tool==candidate,()->editable||candidate==Tool.SELECT||candidate==Tool.PAN,null);}
            button(dialogX+dialogW-79,dialogY+dialogH-27,66,21,tr("Fechar","Close"),null,this::closeDialog,()->false,()->true,null);return;
        }
        if(dialog==Dialog.PROPERTIES){
            int x=dialogX+12,y=dialogY+35;for(int candidate:PALETTE){button(x,y,22,22,String.format("#%06X",candidate),null,()->setColor(candidate),()->color==candidate,this::canEdit,candidate);x+=25;}
            button(x,y,24,22,"#",null,()->openDialog(Dialog.COLOR,tr("Cor hexadecimal","Hex color")),()->false,this::canEdit,null);
            x=dialogX+12;y+=32;for(int candidate:new int[]{1,3,6,10}){button(x,y,30,22,Integer.toString(candidate),null,()->setThickness(candidate),()->thickness==candidate,this::canEdit,null);x+=34;}
            button(dialogX+12,y+32,90,22,tr("Fonte 8–96","Font 8–96"),null,()->openDialog(Dialog.FONT,tr("Tamanho da fonte","Font size")),()->false,this::canEdit,null);
            button(dialogX+108,y+32,90,22,tr("Editar texto","Edit text"),null,()->{dialog=Dialog.NONE;rebuildControls();editSelected();},()->false,()->canEdit()&&selection.size()==1&&isText(elements.get(selection.iterator().next())),null);
            button(dialogX+dialogW-79,dialogY+dialogH-27,66,21,tr("Fechar","Close"),null,this::closeDialog,()->false,()->true,null);return;
        }
        if(dialog==Dialog.CONFLICT){
            button(dialogX+12,dialogY+dialogH-31,Math.min(150,(dialogW-30)/2),22,tr("Copiar rascunho","Copy draft"),null,()->{if(inline!=null)minecraft.keyboardHandler.setClipboard(inline.input.value());status(tr("Rascunho copiado","Draft copied"));},()->false,()->true,null);
            button(dialogX+dialogW/2,dialogY+dialogH-31,dialogW/2-12,22,tr("Recarregar","Reload"),null,()->{inline=null;textQueue.clear();inFlightText=null;actionAfterText="";closing=false;dialog=Dialog.NONE;rebuildControls();},()->false,()->!saving,null);return;
        }
        if (dialog == Dialog.TEXT || dialog == Dialog.COLOR || dialog == Dialog.FONT || dialog == Dialog.RENAME) {
            int fieldHeight = dialogH - 75;
            textField = addRenderableWidget(MultiLineEditBox.builder().setX(dialogX + 12).setY(dialogY + 31)
                .setShowBackground(true).setShowDecorations(true).setTextShadow(false).setTextColor(0xffe9e5db)
                .setPlaceholder(Component.literal(dialog == Dialog.TEXT ? tr("Escreva aqui…", "Write here…") : ""))
                .build(font, dialogW - 24, fieldHeight, Component.literal(dialogTitle)));
            // Large enough for real documents, bounded per element to keep network operations manageable.
            textField.setCharacterLimit(dialog == Dialog.TEXT ? 32768 : dialog == Dialog.RENAME ? 80 : 7);
            if (dialog != Dialog.TEXT) textField.setLineLimit(1);
            String initial = previousText != null ? previousText : dialog == Dialog.TEXT ? string(textTarget, "text", "")
                : dialog == Dialog.COLOR ? String.format("#%06X", color) : dialog==Dialog.FONT?Double.toString(fontSize):string(state, "name", "Canvas Desk");
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
            if(dialog==Dialog.HELP)button(dialogX+12,dialogY+dialogH-29,Math.min(250,dialogW-100),20,tr("Sons do editor: ","Editor sounds: ")+(EditorPreferences.sounds()?tr("ligados","on"):tr("desligados","off")),null,()->{EditorPreferences.toggleSounds();rebuildControls();},()->false,()->true,null);
            button(dialogX + dialogW - 79, dialogY + dialogH - 29, 66, 20, tr("Fechar", "Close"), null, this::closeDialog, () -> false, () -> true, null);
        }
    }

    private void saveDialog() {
        if (textField == null || !canEdit()) return;
        String value = textField.getValue();
        if(dialog==Dialog.FONT) {
            try{double next=Double.parseDouble(value.strip().replace(',','.'));if(!Double.isFinite(next)||next<8||next>96)throw new NumberFormatException();
                setFontSize(next);closeDialog();}
            catch(NumberFormatException error){dialogTitle=tr("Use um tamanho entre 8 e 96", "Use a size between 8 and 96");}
            return;
        }
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
            fitText(after);
            selection.clear(); selection.add(string(after, "id", ""));
            submit(List.of(new Change(before, after))); closeDialog(); tool = Tool.SELECT;
        }
    }

    private void drawDialog(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.fill(0, 0, width, height, 0x990f100e);
        g.fill(dialogX, dialogY, dialogX + dialogW, dialogY + dialogH, PAPER);
        g.outline(dialogX, dialogY, dialogW, dialogH, 0xff967a57);
        g.text(font, font.plainSubstrByWidth(dialogTitle, dialogW - 24), dialogX + 12, dialogY + 12, INK, false);
        if(dialog==Dialog.PROPERTIES){g.text(font,tr("Cor do elemento","Element color"),dialogX+12,dialogY+25,MUTED,false);}
        if(dialog==Dialog.CONFLICT){
            String details=tr("Seu rascunho foi preservado. Copie o texto antes de recarregar a versão confirmada pelo servidor. Uma cópia também fica em config/canvasdesk-unsaved-text.json.","Your draft was preserved. Copy it before reloading the server version. A copy is also saved in config/canvasdesk-unsaved-text.json.");
            g.textWithWordWrap(font,Component.literal(details),dialogX+12,dialogY+34,dialogW-24,INK);return;
        }
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
            int count=Math.max(1,(dialogH-72)/14);accessScroll=Math.clamp(accessScroll,0,Math.max(0,help.length-count));
            int y=dialogY+34;for(int i=accessScroll;i<Math.min(help.length,accessScroll+count);i++){g.text(font,font.plainSubstrByWidth(help[i],dialogW-24),dialogX+12,y,INK,false);y+=14;}
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
        closing=true;finishInline();
        if(saving||!textQueue.isEmpty()||inline!=null&&inline.dirty()){status(tr("Aguardando confirmação do servidor…","Waiting for server confirmation…"));return;}
        sendClose(); minecraft.setScreenAndShow(null);
    }
    @Override public void removed() {
        if(inline!=null&&(inline.dirty()||inFlightText!=null||!textQueue.isEmpty()))EditorPreferences.recover(string(state,"workspace",""),inline.snapshot());sendClose();
    }
    private void sendClose() {
        if (closeSent) return;
        closeSent = true; JsonObject request = new JsonObject(); request.addProperty("action", "close"); send.accept(request);
    }
}
