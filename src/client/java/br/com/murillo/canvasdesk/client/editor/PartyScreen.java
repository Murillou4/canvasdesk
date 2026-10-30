package br.com.murillo.canvasdesk.client.editor;

import br.com.murillo.canvasdesk.client.WorldEmbedInput;
import br.com.murillo.canvasdesk.client.media.MediaPartyClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A small world popup; it creates no editor session and grants no editing permission. */
public final class PartyScreen extends Screen {
    private final PartyMenu menu;private final MediaPartyClient.Target target;
    public PartyScreen(MediaPartyClient.Target target){super(Component.literal("Party do quadro"));this.target=target;menu=new PartyMenu(target);}
    protected void init(){menu.build(width,height,w->addRenderableWidget(w),this::onClose,()->WorldEmbedInput.openEditor(target));}
    public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float delta){menu.paint(g);super.extractRenderState(g,mx,my,delta);}
    public boolean isPauseScreen(){return false;}
    public boolean isInGameUi(){return true;}
    public void onClose(){minecraft.setScreenAndShow(null);}
}
