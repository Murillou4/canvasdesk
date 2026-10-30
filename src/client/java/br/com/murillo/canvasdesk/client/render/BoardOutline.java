package br.com.murillo.canvasdesk.client.render;

import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.CanvasDeskClient;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import java.util.*;

/** Cached silhouette of the physical connected component, independent from block selection/collision. */
public final class BoardOutline {
    private record Line(Vec3 a,Vec3 b,Vec3 side) {}
    private record View(String moduleId,long revision,List<Line> lines) {}
    private static final Map<String,View> CACHE=new LinkedHashMap<>();
    private static View current;
    private static boolean deskTarget;
    private static String wanted="";
    private static int lastRequest=-100;
    private BoardOutline() {}
    public static void tick(Minecraft c){
        current=null;deskTarget=false;
        if(c.player==null||c.level==null||c.gui.screen()!=null)return;
        if(!(c.hitResult instanceof BlockHitResult hit)||!(c.level.getBlockEntity(hit.getBlockPos()) instanceof DeskBlockEntity desk))return;
        deskTarget=true;String id=desk.moduleId.toString();current=CACHE.get(id);
        if(!wanted.equals(id)||c.player.tickCount-lastRequest>=20){
            wanted=id;lastRequest=c.player.tickCount;JsonObject r=new JsonObject();r.addProperty("action","worldSurface");r.addProperty("pos",hit.getBlockPos().asLong());r.addProperty("moduleId",id);
            r.addProperty("surfaceRevision",current==null?-1:current.revision);CanvasDeskClient.send(r);
        }
    }
    public static void accept(JsonObject state){
        Minecraft c=Minecraft.getInstance();if(c.level==null)return;
        BlockPos pos=BlockPos.of(state.get("pos").getAsLong());String id=state.get("moduleId").getAsString();
        if(!(c.level.getBlockEntity(pos) instanceof DeskBlockEntity desk)||!desk.moduleId.toString().equals(id))return;
        SurfaceLayout surface=SurfaceLayout.fromJson(state.getAsJsonObject("surface"));var previous=CACHE.get(id);if(previous!=null&&previous.revision==surface.revision){if(wanted.equals(id))current=previous;return;}var block=desk.getBlockState();
        double ox=state.get("originX").getAsDouble(),oy=state.get("originY").getAsDouble();
        Direction normal=BoardPlane.horizontal(block)?Direction.UP:block.getValue(DeskBlock.FACING);
        Vec3 n=new Vec3(normal.getStepX(),normal.getStepY(),normal.getStepZ());List<Line> lines=new ArrayList<>();
        for(var edge:SurfaceContour.of(surface)){
            Vec3 a=BoardPlane.surfacePoint(block,(edge.x1()-ox)/256,(edge.y1()-oy)/256).add(Vec3.atLowerCornerOf(pos)).add(n.scale(.032));
            Vec3 b=BoardPlane.surfacePoint(block,(edge.x2()-ox)/256,(edge.y2()-oy)/256).add(Vec3.atLowerCornerOf(pos)).add(n.scale(.032));
            lines.add(new Line(a,b,b.subtract(a).cross(n).normalize().scale(.002)));
        }
        View view=new View(id,surface.revision,List.copyOf(lines));CACHE.put(id,view);if(wanted.equals(id))current=view;
        while(CACHE.size()>32)CACHE.remove(CACHE.keySet().iterator().next());
    }
    public static void register(){
        LevelRenderEvents.BEFORE_BLOCK_OUTLINE.register((context,state)->!deskTarget);
        LevelRenderEvents.COLLECT_SUBMITS.register(context->{
            View view=current;if(view==null)return;var pose=context.poseStack();Vec3 camera=context.levelState().cameraRenderState.pos;
            pose.pushPose();pose.translate(-camera.x,-camera.y,-camera.z);
            context.submitNodeCollector().submitCustomGeometry(pose,RenderTypes.debugQuads(),(matrix,v)->{
                for(Line line:view.lines){Vec3[] p={line.a.add(line.side),line.a.subtract(line.side),line.b.subtract(line.side),line.b.add(line.side)};
                    for(Vec3 point:p)v.addVertex(matrix,(float)point.x,(float)point.y,(float)point.z).setColor(0xff191816);
                    for(int i=3;i>=0;i--){Vec3 point=p[i];v.addVertex(matrix,(float)point.x,(float)point.y,(float)point.z).setColor(0xff191816);}}
            });pose.popPose();
        });
    }
    public static void clear(){CACHE.clear();current=null;deskTarget=false;wanted="";lastRequest=-100;}
}
