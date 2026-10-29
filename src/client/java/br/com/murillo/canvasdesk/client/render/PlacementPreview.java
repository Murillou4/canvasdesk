package br.com.murillo.canvasdesk.client.render;

import br.com.murillo.canvasdesk.block.*;
import com.mojang.blaze3d.vertex.*;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.List;

/** All world queries run on the client tick. Rendering consumes an immutable preview. */
public final class PlacementPreview {
    private record Preview(BlockPos pos,BlockState state,VoxelShape shape,List<AABB> boxes,
                           Vec3 start,Vec3 normal,Direction expansion,boolean valid){}
    private static volatile Preview current;
    private PlacementPreview(){}
    public static void clear(){current=null;}
    public static void tick(Minecraft client){
        if(client.level==null||client.player==null||client.gui.screen()!=null){current=null;return;}
        InteractionHand hand=DeskPlacement.chosenHand(client.player);
        DeskPlacement.Result result=hand==null?null:DeskPlacement.resolve(client.player,hand);
        if(result==null){current=null;return;}
        Preview previous=current;
        VoxelShape shape=previous!=null&&previous.state==result.state()?previous.shape:DeskGeometry.shape(result.state());
        List<AABB> boxes=previous!=null&&previous.state==result.state()?previous.boxes:List.copyOf(shape.toAabbs());
        Vec3 start=BoardPlane.surfacePoint(result.state(),.5,.5)
                .add(Vec3.atLowerCornerOf(result.anchor())).add(result.normal().scale(.025));
        current=new Preview(result.pos().immutable(),result.state(),shape,boxes,
                start,result.normal(),result.expansion(),result.valid());
    }
    public static void register(){
        LevelRenderEvents.COLLECT_SUBMITS.register(context->{
            Preview p=current;if(p==null)return;
            var pose=context.poseStack();Vec3 camera=context.levelState().cameraRenderState.pos;
            int rgb=p.valid?0x73b58a:0xcf6455;
            pose.pushPose();pose.translate(p.pos.getX()-camera.x,p.pos.getY()-camera.y,p.pos.getZ()-camera.z);
            context.submitNodeCollector().submitShapeOutline(pose,p.shape,RenderTypes.linesTranslucentNoDepthWrite(),0xdd000000|rgb,2,false);
            context.submitNodeCollector().submitCustomGeometry(pose,RenderTypes.debugQuads(),(matrix,v)->{
                for(AABB b:p.boxes)box(v,matrix,b,0x44000000|rgb);
            });pose.popPose();
            Vec3 direction=new Vec3(p.expansion.getStepX(),p.expansion.getStepY(),p.expansion.getStepZ());
            Vec3 side=direction.cross(p.normal).normalize(),end=p.start.add(direction.scale(.9));
            pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
            context.submitNodeCollector().submitCustomGeometry(pose,RenderTypes.debugQuads(),(matrix,v)->{
                int color=0xdd000000|rgb;ribbon(v,matrix,p.start,end,side,.025,color);
                ribbon(v,matrix,end,end.subtract(direction.scale(.22)).add(side.scale(.15)),p.normal.cross(direction).normalize(),.025,color);
                ribbon(v,matrix,end,end.subtract(direction.scale(.22)).subtract(side.scale(.15)),p.normal.cross(direction).normalize(),.025,color);
                Vec3 center=end.add(direction.scale(.18));
                if(p.valid){ribbon(v,matrix,center.subtract(direction.scale(.08)),center.add(direction.scale(.08)),side,.018,color);ribbon(v,matrix,center.subtract(side.scale(.08)),center.add(side.scale(.08)),direction,.018,color);}
                else {Vec3 a=direction.add(side).normalize(),b=direction.subtract(side).normalize();ribbon(v,matrix,center.subtract(a.scale(.1)),center.add(a.scale(.1)),b,.018,color);ribbon(v,matrix,center.subtract(b.scale(.1)),center.add(b.scale(.1)),a,.018,color);}
            });pose.popPose();
        });
    }
    private static void vertex(VertexConsumer v,PoseStack.Pose m,Vec3 p,int c){v.addVertex(m,(float)p.x,(float)p.y,(float)p.z).setColor(c);}
    private static void ribbon(VertexConsumer v,PoseStack.Pose m,Vec3 a,Vec3 b,Vec3 side,double width,int color){
        Vec3 s=side.scale(width);quad(v,m,a.add(s),a.subtract(s),b.subtract(s),b.add(s),color);
        quad(v,m,b.add(s),b.subtract(s),a.subtract(s),a.add(s),color);
    }
    private static void quad(VertexConsumer v,PoseStack.Pose m,Vec3 a,Vec3 b,Vec3 c,Vec3 d,int color){vertex(v,m,a,color);vertex(v,m,b,color);vertex(v,m,c,color);vertex(v,m,d,color);}
    private static void box(VertexConsumer v,PoseStack.Pose m,AABB b,int c){
        Vec3 a=new Vec3(b.minX,b.minY,b.minZ),d=new Vec3(b.maxX,b.minY,b.minZ),e=new Vec3(b.maxX,b.minY,b.maxZ),f=new Vec3(b.minX,b.minY,b.maxZ);
        Vec3 g=new Vec3(b.minX,b.maxY,b.minZ),h=new Vec3(b.maxX,b.maxY,b.minZ),i=new Vec3(b.maxX,b.maxY,b.maxZ),j=new Vec3(b.minX,b.maxY,b.maxZ);
        quad(v,m,a,d,e,f,c);quad(v,m,g,j,i,h,c);quad(v,m,a,g,h,d,c);quad(v,m,f,e,i,j,c);quad(v,m,a,f,j,g,c);quad(v,m,d,h,i,e,c);
    }
}
