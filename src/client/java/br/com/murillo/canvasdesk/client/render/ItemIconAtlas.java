package br.com.murillo.canvasdesk.client.render;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.client.editor.MaterialIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import java.util.*;

/** Native GUI item model renderer, baked incrementally to a reusable atlas for physical boards. */
public final class ItemIconAtlas {
    public record Icon(Identifier texture,float u0,float v0,float u1,float v1) {}
    private static final Identifier ID=CanvasDesk.id("material-items");
    private static final Map<String,Icon> ICONS=new LinkedHashMap<>(32,.75f,true);private static final Map<String,Object> IDENTITIES=new HashMap<>();private static final LinkedHashSet<String> WANTED=new LinkedHashSet<>();
    private static GuiItemAtlas atlas;
    private static net.minecraft.client.renderer.RenderBuffers buffers;
    private static net.minecraft.client.renderer.feature.FeatureRenderDispatcher dispatcher;
    private ItemIconAtlas() {}
    public static Icon get(String id){if(!ICONS.containsKey(id))WANTED.add(id);return ICONS.get(id);}
    public static void renderPending(){
        var c=Minecraft.getInstance();if(c.level==null||WANTED.isEmpty())return;
        if(atlas==null){buffers=new net.minecraft.client.renderer.RenderBuffers(1);dispatcher=new net.minecraft.client.renderer.feature.FeatureRenderDispatcher(buffers,c.getModelManager(),c.getAtlasManager(),c.font,c.gameRenderer.gameRenderState());atlas=new GuiItemAtlas(dispatcher,2048,2);}
        int budget=8;for(var it=WANTED.iterator();it.hasNext()&&budget-->0;){String id=it.next();it.remove();
            var state=new TrackingItemStackRenderState();c.getItemModelResolver().updateForTopItem(state,MaterialIcons.stack(id),ItemDisplayContext.GUI,c.level,c.player,0);
            var live=new HashSet<>(IDENTITIES.values());live.add(state.getModelIdentity());if(!atlas.tryPrepareFor(live))continue;
            var slot=atlas.getOrUpdate(state);if(slot==null)continue;
            if(ICONS.isEmpty())c.getTextureManager().register(ID,new Alias(slot.textureView()));
            ICONS.put(id,new Icon(ID,slot.u0(),slot.v0(),slot.u1(),slot.v1()));
            IDENTITIES.put(id,state.getModelIdentity());while(ICONS.size()>1024){String oldest=ICONS.keySet().iterator().next();ICONS.remove(oldest);IDENTITIES.remove(oldest);}
        }
        atlas.endFrame();
    }
    public static void clear(){if(atlas!=null){Minecraft.getInstance().getTextureManager().release(ID);atlas.close();dispatcher.close();buffers.close();atlas=null;dispatcher=null;buffers=null;}ICONS.clear();IDENTITIES.clear();WANTED.clear();}
    private static final class Alias extends AbstractTexture {
        Alias(com.mojang.renderpearl.api.textures.GpuTextureView view){textureView=view;texture=view.texture();}
        public void close(){} // The atlas owns both texture and view.
    }
}
