package br.com.murillo.canvasdesk.block;

import com.google.gson.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Editable art/geometry.json also generates the block models. Shapes never approximate a full cube. */
public final class DeskGeometry {
    public static final double RIM=16;
    private static final JsonObject PARTS=load();
    private static final Map<String,VoxelShape> SHAPES=new java.util.concurrent.ConcurrentHashMap<>();
    private DeskGeometry() {}
    private static JsonObject load() {
        try(var input=DeskGeometry.class.getResourceAsStream("/assets/canvasdesk/geometry.json")) {
            if(input==null)throw new IllegalStateException("Missing Canvas Desk geometry");
            return JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    public static String mode(BlockState s){return BoardPlane.horizontal(s)?"horizontal":s.getValue(DeskBlock.WALL)?"wall":"vertical";}
    public static int color(String name){var c=PARTS.getAsJsonObject("colors").get(name==null?"white":name);return c==null?0xfafaf7:c.getAsInt();}
    public static double minU(BlockState s){return s.getValue(DeskBlock.LEFT)?0:1/16.0;}
    public static double maxU(BlockState s){return s.getValue(DeskBlock.RIGHT)?1:15/16.0;}
    public static double minV(BlockState s){return s.getValue(DeskBlock.UP)?0:1/16.0;}
    public static double maxV(BlockState s){return s.getValue(DeskBlock.DOWN)?1:15/16.0;}
    public static VoxelShape shape(BlockState s) {
        String key=mode(s)+s.getValue(DeskBlock.FACING)+s.getValue(DeskBlock.LEFT)+s.getValue(DeskBlock.RIGHT)+s.getValue(DeskBlock.UP)+s.getValue(DeskBlock.DOWN)+s.getValue(DeskBlock.BASE);
        return SHAPES.computeIfAbsent(key,k->{
            VoxelShape result=Shapes.empty();
            for(JsonElement item:PARTS.getAsJsonArray(mode(s))) {
                JsonObject part=item.getAsJsonObject();boolean matches=true;
                for(var when:part.getAsJsonObject("when").entrySet()) {
                    var property=s.getBlock().getStateDefinition().getProperty(when.getKey());
                    if(property==null||!s.getValue(property).toString().equals(when.getValue().getAsString())){matches=false;break;}
                }
                if(!matches)continue;
                for(JsonElement box:part.getAsJsonArray("boxes")) {
                    JsonArray a=box.getAsJsonObject().getAsJsonArray("from"),b=box.getAsJsonObject().getAsJsonArray("to");
                    Vec3 p=BoardPlane.modelPoint(s,a.get(0).getAsDouble(),a.get(1).getAsDouble(),a.get(2).getAsDouble());
                    Vec3 q=BoardPlane.modelPoint(s,b.get(0).getAsDouble(),b.get(1).getAsDouble(),b.get(2).getAsDouble());
                    result=Shapes.or(result,Block.box(16*Math.min(p.x,q.x),16*Math.min(p.y,q.y),16*Math.min(p.z,q.z),16*Math.max(p.x,q.x),16*Math.max(p.y,q.y),16*Math.max(p.z,q.z)));
                }
            }return result.optimize();
        });
    }
}
