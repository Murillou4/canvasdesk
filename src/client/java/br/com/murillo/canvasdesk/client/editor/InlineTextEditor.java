package br.com.murillo.canvasdesk.client.editor;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Style;
import java.util.UUID;

/** Native text model, rendered in document coordinates; no second persistent document. */
public final class InlineTextEditor implements net.minecraft.client.gui.components.events.GuiEventListener, net.minecraft.client.gui.narration.NarratableEntry {
    public final String id;
    public final CanvasTextModel input;
    public final JsonObject template;
    public boolean active=true,conflict;
    public String group="",queuedValue;
    public long lastInput,checkpoint;
    private final Font font;
    private CanvasElementView draft;
    private IMEPreeditOverlay preedit;
    private boolean focused;
    @Override public boolean isFocused(){return focused;}
    @Override public void setFocused(boolean value){
        if(focused==value)return;focused=value;
        net.minecraft.client.Minecraft.getInstance().onTextInputFocusChange(this,value);
    }
    @Override public boolean capturesInput(){return focused&&active;}
    @Override public boolean charTyped(CharacterEvent event){return character(event);}
    @Override public boolean preeditUpdated(PreeditEvent event){return preedit(event);}
    @Override public net.minecraft.client.gui.narration.NarratableEntry.NarrationPriority narrationPriority(){return focused?net.minecraft.client.gui.narration.NarratableEntry.NarrationPriority.FOCUSED:net.minecraft.client.gui.narration.NarratableEntry.NarrationPriority.NONE;}
    @Override public void updateNarration(net.minecraft.client.gui.narration.NarrationElementOutput out){out.add(net.minecraft.client.gui.narration.NarratedElementType.TITLE,net.minecraft.network.chat.Component.literal(input.value()));}
    public InlineTextEditor(Font font,JsonObject value){
        this.font=font;template=value.deepCopy();id=value.get("id").getAsString();
        CanvasElementView e=new CanvasElementView(template);
        input=new CanvasTextModel(font,Math.max(1,(int)Math.floor(e.w/(e.fontSize/9))));input.setCharacterLimit(32768);
        input.setValue(e.text);queuedValue=e.text;input.seekCursor(Whence.END,0);updateDraft();
        input.setValueListener(text->{long now=System.currentTimeMillis();if(group.isEmpty()){group=UUID.randomUUID().toString();checkpoint=now;}
            lastInput=now;updateDraft();EditorPreferences.feedback(true);});
    }
    private void updateDraft(){
        template.addProperty("text",input.value());
        CanvasElementView e=new CanvasElementView(template);template.addProperty("h",CanvasTextLayout.of(font,e).height());draft=new CanvasElementView(template);
    }
    public CanvasElementView view(){return draft;}
    public boolean dirty(){return !input.value().equals(queuedValue);}
    public JsonObject snapshot(){return template.deepCopy();}
    public void point(double x,double y,boolean selecting,boolean word){
        double scale=draft.fontSize/9;
        input.setSelecting(selecting);
        // Native text model uses font.lineHeight; the shared canvas layout uses a 12-unit line step.
        input.seekCursorToPoint((x-draft.x)/scale,Math.max(0,Math.floor((y-draft.y)/(12*scale)))*font.lineHeight+.1);
        if(word)input.selectWordAtCursor();
    }
    public boolean key(KeyEvent event){return input.keyPressed(event);}
    public boolean character(CharacterEvent event){if(event.isAllowedChatCharacter())input.insertText(event.codepointAsString());preedit=null;return true;}
    public boolean preedit(PreeditEvent event){preedit=event==null?null:new IMEPreeditOverlay(event,font,font.lineHeight+1);return true;}
    public void paint(GuiGraphicsExtractor g,CanvasViewport viewport){
        if(!active)return;
        CanvasTextLayout layout=CanvasTextLayout.of(font,draft);double scale=layout.scale();
        var selected=input.selection();int cursor=input.cursor();
        int cursorLine=Math.clamp(input.getLineAtCursor(),0,Math.max(0,layout.lines().size()-1));
        var caretLine=layout.lines().isEmpty()?null:layout.lines().get(cursorLine);
        double cursorX=draft.x+(caretLine==null?0:font.width(input.value().substring(caretLine.start(),Math.clamp(cursor,caretLine.start(),caretLine.end())))*scale);
        double cursorY=draft.y+(caretLine==null?0:caretLine.y());
        if(focused)net.minecraft.client.Minecraft.getInstance().textInputManager().setTextInputArea((int)viewport.screenX(cursorX),(int)viewport.screenY(cursorY),(int)viewport.screenX(cursorX)+1,(int)viewport.screenY(cursorY+12*scale));
        int first=Math.max(0,(int)Math.floor((viewport.documentY(viewport.top)-draft.y)/(12*scale)));
        int last=Math.min(layout.lines().size(),1+(int)Math.ceil((viewport.documentY(viewport.bottom)-draft.y)/(12*scale)));
        for(int i=first;i<last;i++){
            var line=layout.lines().get(i);
            int begin=Math.max(line.start(),selected.start()),end=Math.min(line.end(),selected.end());
            if(begin<end){
                double x1=draft.x+font.width(input.value().substring(line.start(),begin))*scale;
                double x2=draft.x+font.width(input.value().substring(line.start(),end))*scale;
                fill(g,viewport,x1,draft.y+line.y(),x2,draft.y+line.y()+10*scale,0x665b8098);
            }
            if(i==cursorLine){
                if((System.currentTimeMillis()/500)%2==0)fill(g,viewport,cursorX,cursorY,cursorX+Math.max(.7,1/viewport.zoom),cursorY+9*scale,0xff000000|draft.color);
            }
        }
        if(preedit!=null){preedit.updateInputPosition((int)viewport.screenX(cursorX),(int)viewport.screenY(cursorY+12*scale));g.setPreeditOverlay(preedit);}
    }
    private static void fill(GuiGraphicsExtractor g,CanvasViewport v,double x1,double y1,double x2,double y2,int color){g.fill((int)v.screenX(x1),(int)v.screenY(y1),(int)Math.ceil(v.screenX(x2)),(int)Math.ceil(v.screenY(y2)),color);}
}
