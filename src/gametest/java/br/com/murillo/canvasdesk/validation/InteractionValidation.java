package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.editor.CanvasScreen;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/** A small disposable-world smoke of the actual vanilla/Fabric click boundary, never direct open(). */
public final class InteractionValidation {
    private static final int WAIT=200;
    private static final BlockPos VERTICAL=new BlockPos(8,80,8), WALL=new BlockPos(16,81,8), HORIZONTAL=new BlockPos(24,80,8);
    private final Path output=Path.of(System.getProperty("canvasdesk.projectRoot")).resolve("artifacts/1.2.1/smoke");
    private final List<String> checks=new ArrayList<>(),screenshots=new ArrayList<>();
    private final AtomicReference<BlockPos> coldClick=new AtomicReference<>();
    private final AtomicReference<String> serverInterception=new AtomicReference<>();
    private final AtomicInteger loadingScreens=new AtomicInteger();

    public void run(ClientGameTestContext context) {
        if(!Boolean.getBoolean("canvasdesk.selftest"))throw new IllegalStateException("Explicit selftest flag required");
        ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{
            if(screen instanceof CanvasScreen&&Boolean.TRUE.equals(read(screen,"surfaceUpdating")))loadingScreens.incrementAndGet();
        });
        // A cold derived cache is injected at the real server click boundary, not by calling open().
        UseBlockCallback.EVENT.register((player,level,hand,hit)->{
            if(!level.isClientSide()&&VERTICAL.equals(hit.getBlockPos())){
                String interception=serverInterception.getAndSet(null);
                if("cancel".equals(interception))return InteractionResult.FAIL;
                if("replace-anchor".equals(interception))level.setBlock(VERTICAL,Blocks.CHEST.defaultBlockState(),3);
            }
            BlockPos expected=coldClick.get();
            if(!level.isClientSide()&&expected!=null&&expected.equals(hit.getBlockPos())&&coldClick.compareAndSet(expected,null)){
                Map<?,?> cache=(Map<?,?>)read(DeskServer.get(((ServerPlayer)player).level().getServer()),"surfaces");
                cache.remove(level.dimension().identifier()+":"+expected.asLong());
            }
            return InteractionResult.PASS;
        });
        try {
            Files.createDirectories(output);write("RUNNING",null);
            try(var world=context.worldBuilder().create()) {
                context.waitFor(c->c.player!=null&&c.level!=null,WAIT);
                context.runOnClient(c->{c.options.guiScale().set(2);c.options.renderDistance().set(3);c.options.simulationDistance().set(5);c.options.framerateLimit().set(30);c.resizeGui();});
                TestServerContext server=world.getServer();server.runOnServer(this::setup);
                context.waitFor(c->c.level.getBlockEntity(VERTICAL) instanceof DeskBlockEntity&&c.level.getBlockEntity(WALL) instanceof DeskBlockEntity&&c.level.getBlockEntity(HORIZONTAL) instanceof DeskBlockEntity,WAIT);
                for(BlockPos pos:List.of(VERTICAL,WALL,HORIZONTAL))for(boolean stick:new boolean[]{false,true}){
                    equip(context,server,stick?new ItemStack(Items.STICK):ItemStack.EMPTY,ItemStack.EMPTY);
                    Vec3 point=point(context,pos,.5,.5);aim(context,server,pos,point,3);
                    nativeBlockClick(context,pos,point);
                    waitEditor(context);require(context.computeOnClient(c->!((SurfaceLayout)read(c.gui.screen(),"surface")).tiles.isEmpty()),"Opened surface must contain physical modules");
                    pass("native right click opens "+name(pos)+" with "+(stick?"stick":"empty hand"));
                    if(!stick)shot(context,"open-"+name(pos));close(context);
                }
                equip(context,server,ItemStack.EMPTY,ItemStack.EMPTY);Vec3 center=point(context,VERTICAL,.5,.5);aim(context,server,VERTICAL,center,3);
                long epoch=server.computeOnServer(s->((Number)read(DeskServer.get(s),"topologyEpoch")).longValue());
                int loadingBefore=loadingScreens.get();coldClick.set(VERTICAL);
                context.runOnClient(c->{for(int i=0;i<3;i++)c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,new BlockHitResult(center,Direction.SOUTH,VERTICAL,false));});
                waitEditor(context);
                require(loadingScreens.get()>loadingBefore,"Cold native click must open a loading panel before ready state");
                require(server.computeOnServer(s->((Number)read(DeskServer.get(s),"topologyEpoch")).longValue())==epoch,"Repeated opens must not invalidate topology calculation");
                pass("cold cache opens loading panel, reaches ready and repeated clicks preserve topology epoch");close(context);

                placement(context,server,"center",.5,.5,false,false);
                placement(context,server,"edge",.98,.5,false,false);
                placement(context,server,"adjacent-empty-space",1.10,.5,true,false);
                placement(context,server,"air-above",.5,-.10,true,false);
                placement(context,server,"offhand-empty-main",.98,.5,false,true);
                occupied(context,server);
                occluded(context,server);
                outOfRange(context,server);
                sneaking(context,server);
                slotSwap(context,server);
                protectedCallback(context,server,"cancel");
                protectedCallback(context,server,"replace-anchor");
                shot(context,"world-after-interactions");
            }
            context.waitFor(c->c.player==null&&c.level==null,WAIT);
            checks.add("PASS");write("PASS",null);
        }catch(Throwable error){
            checks.add("FAIL "+error);
            try{shot(context,"failure");}catch(Throwable ignored){}
            try{write("FAIL",error.toString());}catch(Exception ignored){}
            throw error instanceof RuntimeException runtime?runtime:new RuntimeException(error);
        }finally{coldClick.set(null);serverInterception.set(null);context.getInput().releaseShift();}
    }

    private void setup(MinecraftServer server) {
        ServerPlayer p=player(server);p.setGameMode(GameType.SURVIVAL);
        p.getAbilities().mayfly=true;p.getAbilities().flying=true;p.onUpdateAbilities();
        for(int x=5;x<=27;x++)for(int z=5;z<=13;z++){
            p.level().setBlock(new BlockPos(x,79,z),Blocks.STONE_BRICKS.defaultBlockState(),3);
            for(int y=80;y<=84;y++)p.level().setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
        }
        fixture(server,VERTICAL,CanvasDesk.DESK.defaultBlockState().setValue(DeskBlock.FACING,Direction.SOUTH));
        p.level().setBlock(WALL.north(),Blocks.STONE_BRICKS.defaultBlockState(),3);
        fixture(server,WALL,CanvasDesk.DESK.defaultBlockState().setValue(DeskBlock.FACING,Direction.SOUTH).setValue(DeskBlock.WALL,true));
        fixture(server,HORIZONTAL,CanvasDesk.DRAWING_DESK.defaultBlockState().setValue(DeskBlock.FACING,Direction.SOUTH));
    }
    private static void fixture(MinecraftServer server,BlockPos pos,BlockState state){
        ServerPlayer p=player(server);p.level().setBlock(pos,state,3);
        DeskBlockEntity desk=(DeskBlockEntity)p.level().getBlockEntity(pos);
        DeskServer.get(server).placed(p.level(),desk,p,new ItemStack(BoardPlane.horizontal(state)?CanvasDesk.DRAWING_DESK_ITEM:CanvasDesk.DESK_ITEM));
    }
    private void placement(ClientGameTestContext context,TestServerContext server,String label,double u,double v,boolean air,boolean offhand)throws Exception{
        equip(context,server,offhand?ItemStack.EMPTY:new ItemStack(CanvasDesk.DESK_ITEM,32),offhand?new ItemStack(CanvasDesk.DESK_ITEM,32):ItemStack.EMPTY);
        Vec3 point=point(context,VERTICAL,u,v);aim(context,server,VERTICAL,point,3);
        DeskPlacement.Result preview=context.computeOnClient(c->DeskPlacement.resolve(c.player,DeskPlacement.chosenHand(c.player)));
        require(preview!=null&&preview.valid(),label+": valid preview required");
        if(air){
            require(u<0||u>1||v<0||v>1,"Air fixture must miss the anchor's actual surface");
            context.runOnClient(c->{c.hitResult=BlockHitResult.miss(point,Direction.SOUTH,BlockPos.containing(point));c.gameMode.useItem(c.player,InteractionHand.MAIN_HAND);});
        }else nativeBlockClick(context,VERTICAL,point);
        server.waitFor(s->player(s).level().getBlockState(preview.pos()).is(CanvasDesk.DESK),WAIT);
        context.waitTicks(5);
        server.runOnServer(s->{ServerPlayer p=player(s);require(p.getItemInHand(offhand?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND).getCount()==31,label+": exactly one item consumed");
            require(p.level().getBlockState(preview.pos()).getValue(DeskBlock.FACING)==preview.state().getValue(DeskBlock.FACING),label+": preview and placed orientation differ");
            require(((DeskBlockEntity)p.level().getBlockEntity(preview.pos())).workspace!=null,label+": placed module has no document");
        });
        require(context.computeOnClient(c->!(c.gui.screen() instanceof CanvasScreen)),label+": expansion must not open the editor");
        pass("assisted "+label+": preview target/orientation match, one block and one consumed item");
        server.runOnServer(s->player(s).level().destroyBlock(preview.pos(),false));
        context.waitFor(c->c.level.getBlockState(preview.pos()).isAir(),WAIT);
    }
    private void occupied(ClientGameTestContext context,TestServerContext server)throws Exception{
        equip(context,server,new ItemStack(CanvasDesk.DESK_ITEM,32),ItemStack.EMPTY);Vec3 point=point(context,VERTICAL,.98,.5);aim(context,server,VERTICAL,point,3);
        DeskPlacement.Result preview=context.computeOnClient(c->DeskPlacement.resolve(c.player,InteractionHand.MAIN_HAND));require(preview!=null,"Occupied fixture needs candidate");
        server.runOnServer(s->player(s).level().setBlock(preview.pos(),Blocks.STONE.defaultBlockState(),3));
        context.waitFor(c->c.level.getBlockState(preview.pos()).is(Blocks.STONE),WAIT);
        require(context.computeOnClient(c->{var p=DeskPlacement.resolve(c.player,InteractionHand.MAIN_HAND);return p!=null&&!p.valid();}),"Occupied candidate must remain visible as invalid");
        int receipts=receiptCount(server);nativeBlockClick(context,VERTICAL,point);server.waitFor(s->receiptCount(s)>receipts,WAIT);context.waitTicks(3);
        server.runOnServer(s->{require(player(s).level().getBlockState(preview.pos()).is(Blocks.STONE),"Occupied target changed");require(player(s).getMainHandItem().getCount()==32,"Invalid target consumed an item");});
        require(context.computeOnClient(c->c.gui.screen()==null),"Invalid placement must consume the click without opening the editor");pass("occupied target stays invalid with no fallback placement or item consumption");
        server.runOnServer(s->player(s).level().setBlock(preview.pos(),Blocks.AIR.defaultBlockState(),3));context.waitTicks(3);
    }
    private void occluded(ClientGameTestContext context,TestServerContext server)throws Exception{
        equip(context,server,new ItemStack(CanvasDesk.DESK_ITEM,32),ItemStack.EMPTY);Vec3 point=point(context,VERTICAL,.5,.5);aim(context,server,VERTICAL,point,3);
        BlockPos obstruction=new BlockPos(8,81,10);server.runOnServer(s->player(s).level().setBlock(obstruction,Blocks.STONE.defaultBlockState(),3));
        context.waitFor(c->c.level.getBlockState(obstruction).is(Blocks.STONE),WAIT);
        require(context.computeOnClient(c->DeskPlacement.resolve(c.player,InteractionHand.MAIN_HAND)==null),"Opaque block must occlude the board ray");
        context.runOnClient(c->c.gameMode.useItem(c.player,InteractionHand.MAIN_HAND));context.waitTicks(5);assertUnconsumed(server,"occluded ray");
        pass("opaque obstruction prevents assistance through a wall");server.runOnServer(s->player(s).level().setBlock(obstruction,Blocks.AIR.defaultBlockState(),3));context.waitTicks(3);
    }
    private void outOfRange(ClientGameTestContext context,TestServerContext server)throws Exception{
        equip(context,server,new ItemStack(CanvasDesk.DESK_ITEM,32),ItemStack.EMPTY);aim(context,server,VERTICAL,point(context,VERTICAL,.5,.5),11);
        require(context.computeOnClient(c->DeskPlacement.resolve(c.player,InteractionHand.MAIN_HAND)==null),"Beyond reach must have no assistance");
        context.runOnClient(c->c.gameMode.useItem(c.player,InteractionHand.MAIN_HAND));context.waitTicks(5);assertUnconsumed(server,"out of range");pass("real interaction range bounds the ray");
    }
    private void sneaking(ClientGameTestContext context,TestServerContext server)throws Exception{
        equip(context,server,new ItemStack(CanvasDesk.DESK_ITEM,32),ItemStack.EMPTY);aim(context,server,VERTICAL,point(context,VERTICAL,.5,.5),3);
        context.getInput().holdShift();context.waitFor(c->c.player.isSecondaryUseActive(),WAIT);
        require(context.computeOnClient(c->DeskPlacement.resolve(c.player,InteractionHand.MAIN_HAND)==null),"Sneak must disable assisted preview");
        context.runOnClient(c->c.gameMode.useItem(c.player,InteractionHand.MAIN_HAND));context.waitTicks(4);assertUnconsumed(server,"sneak air use");
        context.getInput().releaseShift();context.waitFor(c->!c.player.isSecondaryUseActive(),WAIT);pass("sneaking disables assistance and leaves the air-use item untouched");
    }
    private void slotSwap(ClientGameTestContext context,TestServerContext server)throws Exception{
        equip(context,server,new ItemStack(CanvasDesk.DESK_ITEM,32),ItemStack.EMPTY);Vec3 point=point(context,VERTICAL,.98,.5);aim(context,server,VERTICAL,point,3);
        DeskPlacement.Result old=context.computeOnClient(c->DeskPlacement.resolve(c.player,InteractionHand.MAIN_HAND));require(old!=null&&old.valid(),"Slot swap requires a prior preview");
        server.runOnServer(s->{var p=player(s);p.getInventory().setItem(1,new ItemStack(Items.STICK));p.inventoryMenu.broadcastChanges();});
        context.runOnClient(c->{c.player.getInventory().setItem(1,new ItemStack(Items.STICK));c.player.getInventory().setSelectedSlot(1);});
        require(context.computeOnClient(c->DeskPlacement.chosenHand(c.player)==null),"Swapped slot must invalidate chosen preview hand");
        nativeBlockClick(context,VERTICAL,point);waitEditor(context);
        server.runOnServer(s->{var p=player(s);require(p.level().getBlockState(old.pos()).isAir(),"Stale preview placed after slot swap");require(p.getInventory().getItem(0).getCount()==32,"Stale preview consumed old slot");});
        pass("slot swap re-resolves the held item and performs ordinary editor interaction without stale placement");close(context);
    }
    private void protectedCallback(ClientGameTestContext context,TestServerContext server,String mode)throws Exception{
        equip(context,server,new ItemStack(CanvasDesk.DESK_ITEM,32),ItemStack.EMPTY);Vec3 point=point(context,VERTICAL,.98,.5);aim(context,server,VERTICAL,point,3);
        DeskPlacement.Result preview=context.computeOnClient(c->DeskPlacement.resolve(c.player,InteractionHand.MAIN_HAND));require(preview!=null&&preview.valid(),mode+": valid initial candidate required");
        ItemStack reference=server.computeOnServer(s->{ItemStack item=new ItemStack(CanvasDesk.DESK_ITEM);item.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(((DeskBlockEntity)player(s).level().getBlockEntity(VERTICAL)).reference()));return item;});
        int receipts=receiptCount(server);serverInterception.set(mode);nativeBlockClick(context,VERTICAL,point);
        server.waitFor(s->receiptCount(s)>receipts,WAIT);context.waitTicks(3);
        require(serverInterception.get()==null,mode+": server protection callback did not execute");
        server.runOnServer(s->{require(player(s).level().getBlockState(preview.pos()).isAir(),mode+": callback allowed a different placement");require(player(s).getMainHandItem().getCount()==32,mode+": callback consumed an item");});
        require(context.computeOnClient(c->c.gui.screen()==null),mode+": callback opened an unintended editor/container UI");
        if(mode.equals("replace-anchor")){
            server.runOnServer(s->{var p=player(s);require(p.level().getBlockState(VERTICAL).is(Blocks.CHEST),"Mutation callback did not replace anchor");
                p.level().setBlock(VERTICAL,CanvasDesk.DESK.defaultBlockState().setValue(DeskBlock.FACING,Direction.SOUTH),3);
                DeskServer.get(s).placed(p.level(),(DeskBlockEntity)p.level().getBlockEntity(VERTICAL),p,reference);
            });
            context.waitFor(c->c.level.getBlockEntity(VERTICAL) instanceof DeskBlockEntity,WAIT);
        }
        pass("server callback "+mode+": pinned candidate rejected, no consumption and no unintended UI");
    }
    private static int receiptCount(TestServerContext server){return server.computeOnServer(InteractionValidation::receiptCount);}
    private static int receiptCount(MinecraftServer server){Map<?,?> all=(Map<?,?>)read(DeskServer.get(server),"assistReceipts");Map<?,?> receipts=(Map<?,?>)all.get(player(server).getUUID());return receipts==null?0:receipts.size();}
    private static void assertUnconsumed(TestServerContext server,String label){server.runOnServer(s->require(player(s).getMainHandItem().getCount()==32,label+": inventory changed"));}
    private static void equip(ClientGameTestContext context,TestServerContext server,ItemStack main,ItemStack off){
        server.runOnServer(s->{var p=player(s);p.getInventory().setSelectedSlot(0);p.setItemInHand(InteractionHand.MAIN_HAND,main.copy());p.setItemInHand(InteractionHand.OFF_HAND,off.copy());p.inventoryMenu.broadcastChanges();});
        context.runOnClient(c->{c.player.getInventory().setSelectedSlot(0);c.player.setItemInHand(InteractionHand.MAIN_HAND,main.copy());c.player.setItemInHand(InteractionHand.OFF_HAND,off.copy());});
        context.waitTicks(3);
    }
    private static Vec3 point(ClientGameTestContext context,BlockPos pos,double u,double v){return context.computeOnClient(c->BoardPlane.surfacePoint(c.level.getBlockState(pos),u,v).add(Vec3.atLowerCornerOf(pos)));}
    private static void aim(ClientGameTestContext context,TestServerContext server,BlockPos anchor,Vec3 point,double distance){
        double x=anchor.getX()+.5,y=80,z=anchor.getZ()+.5+distance;
        Vec3 delta=point.subtract(x,y+1.62,z);float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        float pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
        server.runOnServer(s->{var p=player(s);p.getAbilities().flying=true;p.onUpdateAbilities();p.teleportTo(p.level(),x,y,z,Set.of(),yaw,pitch,false);});
        context.waitFor(c->c.player.position().distanceToSqr(new Vec3(x,y,z))<.04,WAIT);
        context.runOnClient(c->{c.player.setYRot(yaw);c.player.setXRot(pitch);});context.waitTicks(3);
    }
    private static void nativeBlockClick(ClientGameTestContext context,BlockPos pos,Vec3 point){
        context.runOnClient(c->{var normal=BoardPlane.horizontal(c.level.getBlockState(pos))?Direction.UP:c.level.getBlockState(pos).getValue(DeskBlock.FACING);var hit=new BlockHitResult(point,normal,pos,false);c.hitResult=hit;c.gameMode.useItemOn(c.player,InteractionHand.MAIN_HAND,hit);});
    }
    private static void waitEditor(ClientGameTestContext context){context.waitFor(c->c.gui.screen() instanceof CanvasScreen screen&&!Boolean.TRUE.equals(read(screen,"surfaceUpdating"))&&read(screen,"surface")!=null,WAIT);}
    private static void close(ClientGameTestContext context){context.runOnClient(c->{if(c.gui.screen()!=null)c.gui.screen().onClose();});context.waitFor(c->c.gui.screen()==null,WAIT);context.waitTicks(2);}
    private static ServerPlayer player(MinecraftServer server){return server.getPlayerList().getPlayers().getFirst();}
    private static Object read(Object target,String name){try{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String name(BlockPos pos){return pos.equals(VERTICAL)?"vertical":pos.equals(WALL)?"wall":"horizontal";}
    private void pass(String message)throws Exception{checks.add("PASS "+message);write("RUNNING",null);}
    private void shot(ClientGameTestContext context,String name)throws Exception{Path destination=output.resolve(name+".png");Files.copy(context.takeScreenshot("interaction-"+name),destination,StandardCopyOption.REPLACE_EXISTING);screenshots.add(destination.toString());}
    private void write(String status,String failure)throws Exception{
        Files.write(output.resolve("interaction-selftest.log"),checks,StandardCharsets.UTF_8);
        Map<String,Object> report=new LinkedHashMap<>();report.put("timestamp",Instant.now().toString());report.put("status",status);
        report.put("target","Minecraft 26.3 / Fabric / Java 25");report.put("boundary","Native client.gameMode.useItemOn/useItem and Fabric interaction callbacks, integrated server, disposable fixtures; no direct DeskServer.open");
        report.put("checks",checks);report.put("loadingScreens",loadingScreens.get());report.put("screenshots",screenshots);if(failure!=null)report.put("failure",failure);
        Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(report),StandardCharsets.UTF_8);
    }
}
