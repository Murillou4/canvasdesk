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
    public static final class State extends BlockEntityRenderState { Identifier texture; BlockState block; }
    @Override public State createRenderState(){return new State();}
    @Override public void extractRenderState(DeskBlockEntity desk,State state,float partialTick,Vec3 camera,ModelFeatureRenderer.CrumblingOverlay overlay){
        BlockEntityRenderer.super.extractRenderState(desk,state,partialTick,camera,overlay);state.texture=SurfaceCache.texture(desk.getBlockPos());state.block=desk.getBlockState();
    }
    @Override public void submit(State state,PoseStack pose,SubmitNodeCollector collector,CameraRenderState camera){
        if(state.texture==null)return;int light=state.lightCoords;
        collector.submitCustomGeometry(pose,RenderTypes.entityTranslucent(state.texture),(matrix,vertices)->{
            float u1=(float)DeskGeometry.minU(state.block),u2=(float)DeskGeometry.maxU(state.block),v1=(float)DeskGeometry.minV(state.block),v2=(float)DeskGeometry.maxV(state.block);
            vertex(vertices,matrix,state.block,u1,v1,light);vertex(vertices,matrix,state.block,u1,v2,light);
            vertex(vertices,matrix,state.block,u2,v2,light);vertex(vertices,matrix,state.block,u2,v1,light);
        });
    }
    private static void vertex(VertexConsumer vertices,PoseStack.Pose pose,BlockState block,float u,float v,int light){
        Vec3 point=BoardPlane.surfacePoint(block,u,v);Direction normal=BoardPlane.horizontal(block)?Direction.UP:block.getValue(DeskBlock.FACING);
        vertices.addVertex(pose,(float)point.x,(float)point.y,(float)point.z).setColor(0xffffffff).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose,normal.getStepX(),normal.getStepY(),normal.getStepZ());
    }
    @Override public int getViewDistance(){return 64;}
}
