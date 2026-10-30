package br.com.murillo.canvasdesk.client.editor;

import br.com.murillo.canvasdesk.client.media.MediaPartyClient;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

final class PartyMenu {
    private final MediaPartyClient.Target target;private int x,y,w,h;
    PartyMenu(MediaPartyClient.Target target){this.target=target;}
    void build(int width,int height,Consumer<AbstractWidget> add,Runnable close,Runnable editor){
        w=Math.min(330,width-20);h=Math.min(184,height-16);x=(width-w)/2;y=(height-h)/2;
        button(add,x+12,y+36,w-24,"Ouvir por proximidade",()->MediaPartyClient.join(target,false));
        button(add,x+12,y+62,w-24,"Ouvir continuamente",()->MediaPartyClient.join(target,true));
        button(add,x+12,y+88,w-24,"Sair / silenciar",()->MediaPartyClient.leave(target.workspace()));
        button(add,x+12,y+h-28,(w-28)/2,"Abrir editor",editor);
        button(add,x+16+(w-28)/2,y+h-28,(w-28)/2,"Voltar",close);
    }
    private void button(Consumer<AbstractWidget> add,int x,int y,int w,String label,Runnable action){add.accept(new CanvasButton(x,y,w,22,label,null,action,()->false,()->true,null));}
    void paint(GuiGraphicsExtractor g){var font=Minecraft.getInstance().font;g.fill(0,0,g.guiWidth(),g.guiHeight(),0x660f100e);g.fill(x,y,x+w,y+h,0xfffafaf7);g.outline(x,y,w,h,0xff967a57);g.text(font,"Party · som somente para quem entra",x+12,y+12,0xff34342e,false);g.text(font,font.plainSubstrByWidth(MediaPartyClient.message,w-24),x+12,y+h-44,0xff827b6d,false);}
}
