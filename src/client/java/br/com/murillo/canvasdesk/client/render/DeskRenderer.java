package br.com.murillo.canvasdesk.client.render;

import br.com.murillo.canvasdesk.block.DeskBlockEntity;
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
    public static final class State extends BlockEntityRenderState { Identifier texture; }
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(DeskBlockEntity desk,State state,float partialTick,Vec3 camera,ModelFeatureRenderer.CrumblingOverlay overlay){
        BlockEntityRenderer.super.extractRenderState(desk,state,partialTick,camera,overlay);state.texture=SurfaceCache.texture(desk.getBlockPos());
    }
    @Override public void submit(State state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
        if(state.texture==null)return;int light=state.lightCoords;
        collector.submitCustomGeometry(pose,RenderTypes.entityTranslucent(state.texture),(matrix,vertices)->{
            vertex(vertices,matrix,0,0,0,0,light);vertex(vertices,matrix,0,1,0,1,light);
            vertex(vertices,matrix,1,1,1,1,light);vertex(vertices,matrix,1,0,1,0,light);
        });
    }
    private static void vertex(VertexConsumer vertices,PoseStack.Pose pose,float x,float z,float u,float v,int light){
        vertices.addVertex(pose,x,1.002F,z).setColor(0xffffffff).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,0,1,0);
    }
    @Override public int getViewDistance(){return 64;}
}
