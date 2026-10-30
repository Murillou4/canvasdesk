package br.com.murillo.canvasdesk.client.editor;

import br.com.murillo.canvasdesk.client.media.*;
import com.google.gson.JsonObject;
import java.awt.event.InputEvent;
import java.util.function.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.*;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Native page focus and an independent, durable source-address draft. */
final class BrowserPanel implements GuiEventListener,NarratableEntry {
    private BrowserTexture.Entry entry;
    private boolean focused,addressDirty,submitted,settingAddress;
    private String addressDraft,submittedUrl="",error="";
    private int x,y,w,h,left,top,bw,bh,toolbarX,toolbarY;
    private EditBox seek,address;
    private Consumer<String> saveSource;private BooleanSupplier canEdit;
    private Supplier<String> clipboardSource=()->Minecraft.getInstance().keyboardHandler.getClipboard();
    private Consumer<String> clipboardTarget=value->Minecraft.getInstance().keyboardHandler.setClipboard(value);
    BrowserPanel(BrowserTexture.Entry entry){this.entry=entry;addressDraft=entry.url;BrowserTexture.focus(entry);}
    String elementId(){return entry.elementId;}
    String source(){return entry.url;}
    MediaPartyClient.Target target(){return entry.target();}
    boolean matches(String workspace,java.util.Map<String,CanvasElementView> elements){return workspace.equals(entry.workspace)&&elements.containsKey(entry.elementId);}
    void accept(CanvasElementView e,BrowserTexture.Entry replacement,boolean ownAck,JsonObject state){
        if(replacement!=null){entry=replacement;BrowserTexture.focus(entry);}
        if(ownAck&&submitted){submitted=false;if(state.has("ok")&&state.get("ok").getAsBoolean()&&e.url.equals(submittedUrl)){if(addressDraft.equals(submittedUrl)){addressDirty=false;addressDraft=e.url;}error="";}else error=state.has("message")?state.get("message").getAsString():"Não foi possível trocar o endereço.";}
        if(!addressDirty&&!submitted)addressDraft=e.url;
        if(address!=null&&!address.getValue().equals(addressDraft)){settingAddress=true;address.setValue(addressDraft);settingAddress=false;}
    }
    void build(int width,int height,Consumer<AbstractWidget> add,Runnable close,Consumer<String> saveSource,Runnable party,BooleanSupplier canEdit){
        this.saveSource=saveSource;this.canEdit=canEdit;
        x=10;y=8;w=width-20;h=height-16;int rows=(int)Math.ceil(490.0/Math.max(160,w-24));bw=Math.max(32,Math.min(w-24,(h-94-rows*24)*16/9));bh=bw*9/16;left=x+(w-bw)/2;top=y+54;
        var font=Minecraft.getInstance().font;
        address=new EditBox(font,x+12,y+27,Math.max(60,w-76),20,Component.literal("Endereço"));address.setMaxLength(4096);address.setValue(addressDraft);address.setEditable(canEdit.getAsBoolean());address.setResponder(v->{if(!settingAddress){addressDraft=v;addressDirty=true;error="";}});add.accept(address);
        button(add,x+w-58,y+27,46,"Ir",this::submitAddress,()->canEdit.getAsBoolean()&&!submitted);
        toolbarX=x+12;toolbarY=y+h-28-(rows-1)*24;
        flow(add,30,"▶",()->entry.control("play",position(),null,null,null),this::controls);
        flow(add,30,"Ⅱ",()->entry.control("pause",position(),null,null,null),this::controls);
        seek=new EditBox(font,toolbarX,toolbarY,55,20,Component.literal("Tempo em segundos"));toolbarX+=59;seek.setMaxLength(12);seek.setValue(Integer.toString((int)position()));add.accept(seek);
        flow(add,34,"↦",()->{try{entry.control("seek",Double.parseDouble(seek.getValue().replace(',','.')),null,null,null);}catch(NumberFormatException ignored){}},this::controls);
        flow(add,40,"Som",()->entry.volume(entry.localVolume()>0?0:1),()->true);
        flow(add,48,"Party",party,()->true);
        flow(add,40,"Sair",()->MediaPartyClient.leave(entry.workspace),()->MediaPartyClient.member(entry.workspace));
        flow(add,60,"Cancelar",()->BrowserTexture.cancel(entry),()->true);
        flow(add,55,"Reabrir",()->BrowserTexture.reopen(entry),()->true);
        button(add,x+w-68,y+3,56,"Fechar",close,()->true);
    }
    private double position(){var s=entry.projected();return s.has("position")?s.get("position").getAsDouble():0;}
    private boolean controls(){return entry.editable&&entry.ready&&!entry.closed&&!entry.mediaKind.equals("web");}
    private void flow(Consumer<AbstractWidget> add,int width,String label,Runnable action,BooleanSupplier enabled){if(toolbarX+width>x+w-12){toolbarX=x+12;toolbarY+=24;}button(add,toolbarX,toolbarY,width,label,action,enabled);toolbarX+=width+4;}
    private void button(Consumer<AbstractWidget> add,int x,int y,int w,String label,Runnable action,BooleanSupplier enabled){add.accept(new CanvasButton(x,y,w,20,label,null,action,()->false,enabled,null));}
    private void submitAddress(){
        if(!canEdit.getAsBoolean()||submitted)return;
        try{submittedUrl=br.com.murillo.canvasdesk.core.EmbedSource.validate(addressDraft);if(submittedUrl.equals(entry.url)){BrowserTexture.reopen(entry);addressDirty=false;error="Recarregando…";return;}submitted=true;error="Gravando endereço…";saveSource.accept(submittedUrl);}catch(RuntimeException invalid){error=invalid.getMessage();submitted=false;}
    }
    boolean addressEnter(KeyEvent event,GuiEventListener active){if(active==address&&(event.key()==40||event.key()==88)){submitAddress();return true;}return false;}
    void paint(GuiGraphicsExtractor g){
        var fresh=BrowserTexture.get(entry.workspace,entry.elementId);if(fresh!=null&&fresh!=entry&&fresh.url.equals(entry.url)){entry=fresh;BrowserTexture.focus(entry);}
        var client=Minecraft.getInstance();var font=client.font;address.setEditable(canEdit.getAsBoolean());seek.setEditable(controls());
        if(focused)client.textInputManager().setTextInputArea(left+(int)(entry.caretX*bw/1280),top+(int)(entry.caretY*bh/720),left+(int)((entry.caretX+entry.caretW)*bw/1280),top+(int)((entry.caretY+entry.caretH)*bh/720));
        g.fill(0,0,g.guiWidth(),g.guiHeight(),0x990f100e);g.fill(x,y,x+w,y+h,0xfffafaf7);g.outline(x,y,w,h,0xff967a57);
        g.text(font,entry.editable?"Interagir · controles compartilhados":"Assistindo · controles locais",x+12,y+9,0xff34342e,false);g.fill(left,top,left+bw,top+bh,0xff171717);
        if(BrowserTexture.texture(entry.elementId)!=null)g.blit(entry.textureId,left,top,left+bw,top+bh,0,1,0,1);
        String info=!error.isBlank()?error:!entry.status.isBlank()?entry.status:!entry.diagnostic.isBlank()?entry.diagnostic:MediaPartyClient.message.isBlank()?"Esc volta · use Party para ouvir este quadro":MediaPartyClient.message;
        g.text(font,font.plainSubstrByWidth(info,w-24),x+12,top+bh+4,0xff827b6d,false);
    }
    private boolean input(){return entry.ready&&entry.localInteractive&&!entry.closed&&!entry.inputFailed;}
    public boolean isMouseOver(double px,double py){return px>=left&&px<left+bw&&py>=top&&py<top+bh;}
    public boolean mouseClicked(MouseButtonEvent e,boolean twice){if(!isMouseOver(e.x(),e.y()))return false;if(input())mouse(e,true);return true;}
    public boolean mouseReleased(MouseButtonEvent e){if(focused&&input())mouse(e,false);return focused;}
    public void mouseMoved(double px,double py){if(focused&&input()&&isMouseOver(px,py))entry.send(mouseCommand(px,py,-1,false,0));}
    public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){if(focused&&input()){entry.send(mouseCommand(e.x(),e.y(),-1,true,0));return true;}return false;}
    public boolean mouseScrolled(double px,double py,double horizontal,double vertical){if(isMouseOver(px,py)&&input()){entry.send(mouseCommand(px,py,-1,false,(int)-vertical));return true;}return false;}
    private void mouse(MouseButtonEvent e,boolean down){entry.send(mouseCommand(e.x(),e.y(),Math.max(0,e.button()-1),down,0));}
    private JsonObject mouseCommand(double px,double py,int button,boolean down,int wheel){JsonObject c=new JsonObject();c.addProperty("cmd","mouse");c.addProperty("x",(int)Math.clamp((px-left)*1280/bw,0,1279));c.addProperty("y",(int)Math.clamp((py-top)*720/bh,0,719));c.addProperty("button",button);c.addProperty("down",down);c.addProperty("wheel",wheel);return c;}
    public boolean keyPressed(KeyEvent e){return key(e,true);}
    public boolean keyReleased(KeyEvent e){return key(e,false);}
    private boolean key(KeyEvent e,boolean down){
        if(!input())return true;int scan=e.key(),code;
        if(scan>=4&&scan<=29)code='A'+scan-4;else if(scan>=30&&scan<=38)code='1'+scan-30;else code=switch(scan){case 39->48;case 40,88->10;case 41->27;case 42->8;case 43->9;case 44->32;case 73->155;case 74->36;case 75->33;case 76->127;case 77->35;case 78->34;case 79->39;case 80->37;case 81->40;case 82->38;case 224,228->17;case 225,229->16;case 226,230->18;case 227,231->157;default->0;};
        if(down&&e.hasControlDown()&&(code==86||code==67||code==88)){
            if(code==86){String value=clipboardSource.get();if(value.length()>262144){error="Texto grande demais para colar de uma vez.";return true;}for(int i=0;i<value.length();){int end=Math.min(value.length(),i+4096);if(end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;JsonObject paste=new JsonObject();paste.addProperty("cmd","key");paste.addProperty("code",0);paste.addProperty("down",true);paste.addProperty("modifiers",0);paste.addProperty("text",value.substring(i,end));entry.send(paste);i=end;}}
            else{entry.clipboardReceiver=clipboardTarget;JsonObject copy=new JsonObject();copy.addProperty("cmd",code==88?"cut":"copy");entry.send(copy);}return true;
        }
        if(code==0)return true;JsonObject c=new JsonObject();c.addProperty("cmd","key");c.addProperty("code",code);c.addProperty("down",down);c.addProperty("modifiers",(e.hasControlDown()?InputEvent.CTRL_DOWN_MASK:0)|(e.hasShiftDown()?InputEvent.SHIFT_DOWN_MASK:0)|(e.hasAltDown()?InputEvent.ALT_DOWN_MASK:0));entry.send(c);return true;
    }
    public boolean charTyped(CharacterEvent e){if(input()&&e.isAllowedChatCharacter()){JsonObject c=new JsonObject();c.addProperty("cmd","key");c.addProperty("code",0);c.addProperty("down",true);c.addProperty("modifiers",0);c.addProperty("text",e.codepointAsString());entry.send(c);}return true;}
    public void setFocused(boolean value){if(focused!=value){focused=value;Minecraft.getInstance().onTextInputFocusChange(this,value);if(!value&&entry.ready&&!entry.closed){JsonObject c=new JsonObject();c.addProperty("cmd","resetInput");entry.send(c);}}}
    public boolean isFocused(){return focused;}
    public boolean capturesInput(){return focused;}
    public NarrationPriority narrationPriority(){return focused?NarrationPriority.FOCUSED:NarrationPriority.NONE;}
    public void updateNarration(NarrationElementOutput out){out.add(NarratedElementType.TITLE,Component.literal("Navegador do Canvas Desk"));}
    void close(){setFocused(false);BrowserTexture.focus(null);}
}
