package br.com.murillo.canvasdesk.client.render;

import br.com.murillo.canvasdesk.block.DeskBlockEntity;
import br.com.murillo.canvasdesk.block.DeskBlock;
import br.com.murillo.canvasdesk.block.BoardPlane;
import br.com.murillo.canvasdesk.block.DeskGeometry;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

public final class DeskRenderer implements BlockEntityRenderer<DeskBlockEntity,DeskRenderer.State> {
    public DeskRenderer(BlockEntityRendererProvider.Context context){}
    public static final class State extends BlockEntityRenderState { Identifier texture; BlockState block; java.util.List<SurfaceCache.Layer> layers;double x,y; }
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(DeskBlockEntity desk,State state,float partialTick,Vec3 camera,ModelFeatureRenderer.CrumblingOverlay overlay){
        BlockEntityRenderer.super.extractRenderState(desk,state,partialTick,camera,overlay);state.texture=SurfaceCache.texture(desk.getBlockPos());state.block=desk.getBlockState();state.layers=SurfaceCache.layers(desk.getBlockPos());var tile=SurfaceCache.interaction(desk.getBlockPos());state.x=tile==null?0:tile.x();state.y=tile==null?0:tile.y();
    }
    @Override public void submit(State state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
        if(state.texture==null)return;int light=state.lightCoords;
        int stage=0;for(var layer:state.layers){
            boolean vector=layer.item()==null&&layer.embed()==null;
            double depth=(vector?stage:stage-1)*.00005;if(vector)stage+=2;
            Identifier texture=layer.texture();float tu0=0,tv0=0,tu1=1,tv1=1;
            if(layer.item()!=null){var icon=ItemIconAtlas.get(layer.item());if(icon==null)continue;texture=icon.texture();tu0=icon.u0();tv0=icon.v0();tu1=icon.u1();tv1=icon.v1();}
            if(layer.embed()!=null)texture=br.com.murillo.canvasdesk.client.media.BrowserTexture.texture(layer.embed());if(texture==null)continue;
            double x1=Math.max(state.x+256*DeskGeometry.minU(state.block),layer.x()),y1=Math.max(state.y+256*DeskGeometry.minV(state.block),layer.y());
            double x2=Math.min(state.x+256*DeskGeometry.maxU(state.block),layer.x()+layer.w()),y2=Math.min(state.y+256*DeskGeometry.maxV(state.block),layer.y()+layer.h());if(x2<=x1||y2<=y1)continue;
            float u0=tu0+(float)((x1-layer.x())/layer.w())*(tu1-tu0),v0=tv0+(float)((y1-layer.y())/layer.h())*(tv1-tv0),u1=tu0+(float)((x2-layer.x())/layer.w())*(tu1-tu0),v1=tv0+(float)((y2-layer.y())/layer.h())*(tv1-tv0);
            Identifier id=texture;
            collector.submitCustomGeometry(pose,RenderTypes.entityTranslucent(id),(matrix,vertices)->{
                layerVertex(vertices,matrix,state.block,(x1-state.x)/256,(y1-state.y)/256,u0,v0,light,depth);layerVertex(vertices,matrix,state.block,(x1-state.x)/256,(y2-state.y)/256,u0,v1,light,depth);
                layerVertex(vertices,matrix,state.block,(x2-state.x)/256,(y2-state.y)/256,u1,v1,light,depth);layerVertex(vertices,matrix,state.block,(x2-state.x)/256,(y1-state.y)/256,u1,v0,light,depth);
            });
        }
    }
    private static void layerVertex(VertexConsumer vertices,PoseStack.Pose pose,BlockState block,double x,double y,float u,float v,int light,double depth){Vec3 p=BoardPlane.surfacePoint(block,x,y);Direction n=BoardPlane.horizontal(block)?Direction.UP:block.getValue(DeskBlock.FACING);p=p.add(n.getStepX()*depth,n.getStepY()*depth,n.getStepZ()*depth);vertices.addVertex(pose,(float)p.x,(float)p.y,(float)p.z).setColor(0xffffffff).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,n.getStepX(),n.getStepY(),n.getStepZ());}
    private static void vertex(VertexConsumer vertices,PoseStack.Pose pose,BlockState block,float u,float v,int light){
        Vec3 point=BoardPlane.surfacePoint(block,u,v);Direction normal=BoardPlane.horizontal(block)?Direction.UP:block.getValue(DeskBlock.FACING);
        vertices.addVertex(pose,(float)point.x,(float)point.y,(float)point.z).setColor(0xffffffff).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,normal.getStepX(),normal.getStepY(),normal.getStepZ());
    }
    @Override public int getViewDistance(){return 64;}
}
