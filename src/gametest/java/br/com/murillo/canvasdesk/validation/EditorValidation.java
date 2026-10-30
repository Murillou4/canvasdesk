package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.block.*;
import br.com.murillo.canvasdesk.client.*;
import br.com.murillo.canvasdesk.client.editor.*;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.server.DeskServer;
import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.input.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded real-client regression: native block clicks, live Screen input, server receipts and screenshots. */
public final class EditorValidation {
    static final int WAIT=200;
    static final BlockPos V=new BlockPos(8,80,8),H=new BlockPos(16,80,8),R=new BlockPos(24,80,8);
    private final UUID checklist=UUID.randomUUID(),horizontal=UUID.randomUUID(),reader=UUID.randomUUID(),readerOwner=UUID.randomUUID();
    private final Path output=Path.of(System.getProperty("canvasdesk.projectRoot")).resolve("artifacts/1.3.0/smoke");
    private final List<String> checks=new ArrayList<>(),shots=new ArrayList<>();
    private final AtomicReference<String> protection=new AtomicReference<>();

    public void run(ClientGameTestContext context){
        if(!Boolean.getBoolean("canvasdesk.selftest"))throw new IllegalStateException("Explicit selftest flag required");
        UseBlockCallback.EVENT.register((p,level,hand,hit)->{
            if(!level.isClientSide()&&hit.getBlockPos().equals(V)){
                String mode=protection.getAndSet(null);if("cancel".equals(mode))return InteractionResult.FAIL;
                if("replace".equals(mode))level.setBlock(V,Blocks.CHEST.defaultBlockState(),3);
            }return InteractionResult.PASS;
        });
        try{
            Files.createDirectories(output);write("RUNNING",null);
            try(var world=context.worldBuilder().create()){
                context.waitFor(c->c.player!=null&&c.level!=null,WAIT);
                context.runOnClient(c->{c.options.guiScale().set(2);c.options.renderDistance().set(3);c.options.framerateLimit().set(30);c.resizeGui();});
                TestServerContext server=world.getServer();server.runOnServer(this::setup);
                context.waitFor(c->c.level.getBlockEntity(V) instanceof DeskBlockEntity&&c.level.getBlockEntity(H) instanceof DeskBlockEntity,WAIT);
                aim(context,server,V,128,128,3);click(context,V,128,128);waitEditor(context);
                checkLayout(context);shot(context,"editor-1280x720-gui2-pair");
                context.getInput().resizeWindow(854,480);context.waitTicks(4);checkLayout(context);shot(context,"editor-854x480-gui2-overflow");
                widget(context,"…");require(context.computeOnClient(c->read(c.gui.screen(),"dialog").toString().equals("TOOLS")),"Tool overflow must open");key(context,InputConstants.KEY_ESCAPE,0);
                context.getInput().resizeWindow(1920,1080);context.waitTicks(4);checkLayout(context);shot(context,"editor-1920x1080-gui2");
                context.getInput().resizeWindow(1280,720);context.runOnClient(c->{c.options.guiScale().set(3);c.resizeGui();});context.waitTicks(4);checkLayout(context);shot(context,"editor-1280x720-gui3");
                context.runOnClient(c->{c.options.guiScale().set(2);c.resizeGui();});context.waitTicks(3);
                pass("native opening, 854x480 / 1280x720 / 1920x1080 and GUI 2/3; rails, margins, overflow and fit geometry");

                key(context,InputConstants.KEY_B,0);drag(context,310,190,350,195,390,205);waitType(context,server,"STROKE");
                key(context,InputConstants.KEY_R,0);drag(context,310,110,355,120,410,135);waitType(context,server,"RECTANGLE");
                JsonObject rectangle=type(server,"RECTANGLE");String rectId=rectangle.get("id").getAsString();
                key(context,InputConstants.KEY_V,0);documentClick(context,340,125);key(context,InputConstants.KEY_RIGHT,0);
                waitServer(context,()->element(server,rectId).get("x").getAsDouble()==311);
                key(context,InputConstants.KEY_Z,InputConstants.MOD_CONTROL);waitServer(context,()->element(server,rectId).get("x").getAsDouble()==310);
                key(context,InputConstants.KEY_Y,InputConstants.MOD_CONTROL);waitServer(context,()->element(server,rectId).get("x").getAsDouble()==311);
                key(context,InputConstants.KEY_T,0);documentClick(context,300,155);
                context.runOnClient(c->{for(int cp:"Ação V B T ç\nConexão".codePoints().toArray()){if(cp=='\n')c.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_RETURN,13,0));else c.gui.screen().charTyped(new CharacterEvent(cp));}});
                key(context,InputConstants.KEY_RETURN,InputConstants.MOD_CONTROL);
                waitServer(context,()->{JsonObject t=typeOrNull(server,"TEXT");return t!=null&&t.get("text").getAsString().equals("Ação V B T ç\nConexão");});
                key(context,InputConstants.KEY_F1,0);shot(context,"editor-help");key(context,InputConstants.KEY_ESCAPE,0);
                widgetContaining(context,"stroke and font","espessura e fonte");shot(context,"editor-properties");key(context,InputConstants.KEY_ESCAPE,0);
                pass("live screen drawing, selection movement, undo/redo, inline accents and protected tool shortcut characters; help/properties");

                server.runOnServer(s->fixture(s,V.above(),false,ItemStack.EMPTY));waitModules(context,3);checkLayout(context);shot(context,"editor-L");
                context.runOnClient(c->{CanvasScreen screen=(CanvasScreen)c.gui.screen();CanvasViewport v=viewport(c);screen.mouseScrolled((v.left+v.right)/2.0,(v.top+v.bottom)/2.0,0,1);});
                double[] camera=context.computeOnClient(c->new double[]{viewport(c).zoom,viewport(c).panX,viewport(c).panY});
                server.runOnServer(s->player(s).level().setBlock(V.above(),Blocks.AIR.defaultBlockState(),3));waitModules(context,2);
                context.runOnClient(c->{CanvasViewport v=viewport(c);require(v.zoom==camera[0]&&v.panX==camera[1]&&v.panY==camera[2],"Manual camera moved with topology");});
                key(context,InputConstants.KEY_F,0);checkLayout(context);
                server.runOnServer(s->{for(BlockPos pos:List.of(V.east(2),V.east(2).above(),V.east(2).above(2),V.east().above(2),V.above(2),V.above()))fixture(s,pos,false,ItemStack.EMPTY);});
                waitModules(context,8);checkLayout(context);shot(context,"editor-ring-gap");
                server.runOnServer(s->{for(BlockPos pos:List.of(V.east(2),V.east(2).above(),V.east(2).above(2),V.east().above(2),V.above(2),V.above()))player(s).level().setBlock(pos,Blocks.AIR.defaultBlockState(),3);});
                waitModules(context,2);key(context,InputConstants.KEY_F,0);
                pass("live L and ring with gap; automatic fit follows topology, manual zoom survives removal, F restores follow");
                close(context);

                for(BlockPos pos:List.of(V,H)){
                    UUID id=pos.equals(V)?checklist:horizontal;
                    double[] box=checkboxPoint(context,server,pos,id,1);aim(context,server,pos,box[0],box[1],3);shot(context,pos.equals(V)?"world-vertical-before-checkbox":"world-horizontal-before-checkbox");
                    context.waitFor(c->SurfaceCache.interaction(pos)!=null,WAIT);
                    context.waitFor(c->read(WorldChecklistInput.class,"current")!=null,WAIT);
                    long before=revision(server,pos,id);
                    context.runOnClient(c->c.options.keyUse.setDown(true));click(context,pos,box[0],box[1]);waitChecked(context,server,pos,id,1,true);
                    for(int i=0;i<3;i++){click(context,pos,box[0],box[1]);context.waitTicks(2);}
                    require(revision(server,pos,id)==before+1,"Held click repeated checkbox mutation");require(context.computeOnClient(c->c.gui.screen()==null),"Checkbox opened editor");
                    shot(context,pos.equals(V)?"world-vertical-checkbox":"world-horizontal-checkbox");
                    context.runOnClient(c->c.options.keyUse.setDown(false));context.waitTicks(2);click(context,pos,box[0],box[1]);waitChecked(context,server,pos,id,1,false);
                    context.waitTicks(2);aim(context,server,pos,box[0]+45,box[1],3);click(context,pos,box[0]+45,box[1]);waitEditor(context);
                    key(context,InputConstants.KEY_Z,InputConstants.MOD_CONTROL);waitChecked(context,server,pos,id,1,true);
                    key(context,InputConstants.KEY_Y,InputConstants.MOD_CONTROL);waitChecked(context,server,pos,id,1,false);close(context);
                    pass("native "+(pos.equals(V)?"vertical/wrapped":"horizontal")+" checkbox mark/unmark; one held press, label opens editor and shared undo/redo");
                }

                double[] box=checkboxPoint(context,server,V,checklist,0);aim(context,server,V,box[0],box[1],3);
                context.runOnClient(c->c.options.keyShift.setDown(true));server.runOnServer(s->player(s).setShiftKeyDown(true));context.waitTicks(2);
                click(context,V,box[0],box[1]);waitEditor(context);close(context);
                context.runOnClient(c->c.options.keyShift.setDown(false));server.runOnServer(s->player(s).setShiftKeyDown(false));context.waitTicks(2);
                pass("sneaking on checkbox opens editor");

                JsonObject note=primitive("NOTE","Sobreposto",box[0]-8,box[1]-8,30,30,9);server.runOnServer(s->edit(s,V,note,-1));
                context.runOnClient(c->SurfaceCache.request(V));context.waitFor(c->SurfaceCache.interaction(V).byId().containsKey(note.get("id").getAsString()),WAIT);
                click(context,V,box[0],box[1]);waitEditor(context);close(context);require(!isChecked(server,V,checklist,0),"Click passed through note");
                server.runOnServer(s->remove(s,V,note.get("id").getAsString()));context.runOnClient(c->SurfaceCache.request(V));
                context.waitFor(c->!SurfaceCache.interaction(V).byId().containsKey(note.get("id").getAsString()),WAIT);
                pass("topmost opaque note blocks checkbox hit");

                aim(context,server,V,box[0],box[1],3);context.waitFor(c->SurfaceCache.interaction(V)!=null,WAIT);
                JsonObject valid=worldRequest(context,V,checklist,0);long expected=revision(server,V,checklist);
                JsonObject stale=valid.deepCopy();stale.addProperty("expectedRevision",expected-1);rejected(context,server,stale);
                JsonObject projection=valid.deepCopy();projection.addProperty("tileX",valid.get("tileX").getAsDouble()+256);rejected(context,server,projection);
                JsonObject replaced=valid.deepCopy();replaced.addProperty("moduleId",UUID.randomUUID().toString());rejected(context,server,replaced);
                server.runOnServer(s->player(s).level().setBlock(V.south(),Blocks.STONE_BRICKS.defaultBlockState(),3));context.waitTicks(3);
                rejected(context,server,valid);server.runOnServer(s->player(s).level().setBlock(V.south(),Blocks.AIR.defaultBlockState(),3));context.waitTicks(3);
                aim(context,server,V,box[0],box[1],8);rejected(context,server,worldRequest(context,V,checklist,0));
                require(revision(server,V,checklist)==expected,"Rejected request changed checklist");require(context.computeOnClient(c->c.gui.screen()==null),"Rejected request opened editor");
                pass("stale revision, shifted projection, changed identity, foreground obstacle and out-of-reach requests rejected");

                for(String mode:List.of("cancel","replace")){
                    aim(context,server,V,box[0],box[1],3);context.waitFor(c->read(WorldChecklistInput.class,"current")!=null,WAIT);
                    protection.set(mode);click(context,V,box[0],box[1]);context.waitFor(c->((String)read(WorldChecklistInput.class,"pending")).isEmpty(),WAIT);
                    require(!isChecked(server,V,checklist,0),"Protection callback allowed mutation");require(context.computeOnClient(c->c.gui.screen()==null),"Protection callback opened replacement UI");
                    if(mode.equals("replace"))server.runOnServer(s->fixture(s,V,false,ItemStack.EMPTY));
                    context.waitTicks(2);pass("world checkbox honors server callback "+mode+" without unintended UI");
                }

                aim(context,server,R,60,68,3);context.runOnClient(c->SurfaceCache.request(R));context.waitFor(c->SurfaceCache.interaction(R)!=null,WAIT);
                click(context,R,60,68);waitEditor(context);require(context.computeOnClient(c->!((JsonObject)read(c.gui.screen(),"state")).get("editable").getAsBoolean()),"Reader gained editing access");close(context);
                server.runOnServer(s->{JsonObject request=new JsonObject();request.addProperty("action","invite");request.addProperty("player",player(s).getUUID().toString());var desk=(DeskBlockEntity)player(s).level().getBlockEntity(R);require(DeskServer.get(s).store.action(desk.workspace,readerOwner,request).get("ok").getAsBoolean(),"Invite failed");});
                context.runOnClient(c->SurfaceCache.request(R));context.waitFor(c->SurfaceCache.interaction(R).editable(),WAIT);
                require(context.computeOnClient(c->SurfaceCache.checkbox(SurfaceCache.interaction(R),60,68)!=null),"Metadata-only permission update lost hit areas");
                click(context,R,60,68);waitChecked(context,server,R,reader,0,true);require(context.computeOnClient(c->c.gui.screen()==null),"Invited editor checkbox opened screen");
                pass("reader opens read-only; ACL update with same pixels enables invited editor world interaction");
                shot(context,"world-final");
            }
            context.waitFor(c->c.level==null&&c.player==null,WAIT);checks.add("PASS");write("PASS",null);
        }catch(Throwable error){checks.add("FAIL "+error);try{shot(context,"failure");write("FAIL",error.toString());}catch(Exception ignored){}throw error instanceof RuntimeException r?r:new RuntimeException(error);}
        finally{protection.set(null);context.runOnClient(c->{c.options.keyUse.setDown(false);c.options.keyShift.setDown(false);});}
    }

    private void setup(MinecraftServer s){
        var p=player(s);p.setGameMode(GameType.SURVIVAL);p.getAbilities().mayfly=true;p.getAbilities().flying=true;p.onUpdateAbilities();
        for(int x=5;x<=27;x++)for(int z=5;z<=12;z++)for(int y=79;y<=84;y++)p.level().setBlock(new BlockPos(x,y,z),y==79?Blocks.STONE_BRICKS.defaultBlockState():Blocks.AIR.defaultBlockState(),3);
        fixture(s,V,false,ItemStack.EMPTY);fixture(s,V.east(),false,ItemStack.EMPTY);fixture(s,H,true,ItemStack.EMPTY);
        CompoundTag tag=new CompoundTag();tag.putString("workspace",DeskServer.get(s).store.create(readerOwner).toString());ItemStack linked=new ItemStack(CanvasDesk.DESK_ITEM);linked.set(DataComponents.CUSTOM_DATA,CustomData.of(tag));fixture(s,R,false,linked);
        JsonObject list=primitive("CHECKLIST","Conferir a acentuação e as quebras de linha\nSalvar alterações\nCompartilhar",32,40,192,180,18);list.addProperty("id",checklist.toString());edit(s,V,list,-1);
        JsonObject flat=list.deepCopy();flat.addProperty("id",horizontal.toString());edit(s,H,flat,-1);
        JsonObject read=list.deepCopy();read.addProperty("id",reader.toString());var desk=(DeskBlockEntity)p.level().getBlockEntity(R);
        JsonObject request=editRequest(read,-1);require(DeskServer.get(s).store.action(desk.workspace,readerOwner,request).get("ok").getAsBoolean(),"Reader seed failed");
        edit(s,V,primitive("NOTE","Ideias da bancada",290,25,150,70,9),-1);
    }
    static void fixture(MinecraftServer s,BlockPos pos,boolean horizontal,ItemStack reference){var p=player(s);var state=(horizontal?CanvasDesk.DRAWING_DESK:CanvasDesk.DESK).defaultBlockState().setValue(DeskBlock.FACING,Direction.SOUTH);p.level().setBlock(pos,state,3);DeskServer.get(s).placed(p.level(),(DeskBlockEntity)p.level().getBlockEntity(pos),p,reference);}
    static ServerPlayer player(MinecraftServer s){return s.getPlayerList().getPlayers().getFirst();}
    static JsonObject primitive(String type,String text,double x,double y,double w,double h,double size){JsonObject e=new JsonObject();e.addProperty("id",UUID.randomUUID().toString());e.addProperty("type",type);e.addProperty("text",text);e.addProperty("x",x);e.addProperty("y",y);e.addProperty("w",w);e.addProperty("h",h);e.addProperty("fontSize",size);return e;}
    static JsonObject worldRequest(ClientGameTestContext c,BlockPos pos,UUID id,int row){return c.computeOnClient(client->{var tile=SurfaceCache.interaction(pos);var e=tile.byId().get(id.toString());JsonObject r=new JsonObject();r.addProperty("action","worldChecklist");r.addProperty("operationId",UUID.randomUUID().toString());r.addProperty("pos",pos.asLong());r.addProperty("moduleId",tile.moduleId());r.addProperty("workspace",tile.workspace());r.addProperty("tileX",tile.x());r.addProperty("tileY",tile.y());r.addProperty("elementId",id.toString());r.addProperty("expectedRevision",e.revision);r.addProperty("row",row);r.addProperty("checked",true);r.addProperty("yaw",client.player.getYRot());r.addProperty("pitch",client.player.getXRot());return r;});}
    static void rejected(ClientGameTestContext c,TestServerContext server,JsonObject template){JsonObject r=template.deepCopy();UUID op=UUID.randomUUID();r.addProperty("operationId",op.toString());c.runOnClient(client->CanvasDeskClient.send(r));waitServer(c,()->server.computeOnServer(s->{Map<?,?> all=(Map<?,?>)read(DeskServer.get(s),"checklistReceipts");Map<?,?> receipts=(Map<?,?>)all.get(player(s).getUUID());return receipts!=null&&receipts.containsKey(op);}));server.runOnServer(s->{Map<?,?> all=(Map<?,?>)read(DeskServer.get(s),"checklistReceipts");JsonObject reply=(JsonObject)((Map<?,?>)all.get(player(s).getUUID())).get(op);require(!reply.get("ok").getAsBoolean(),"Invalid world request accepted: "+reply);});c.waitTicks(1);}
    static JsonObject editRequest(JsonObject e,long revision){JsonObject r=new JsonObject();r.addProperty("action","edit");r.addProperty("operationId",UUID.randomUUID().toString());JsonObject change=new JsonObject();change.addProperty("id",e.get("id").getAsString());change.addProperty("expectedRevision",revision);change.add("after",e);JsonArray a=new JsonArray();a.add(change);r.add("changes",a);return r;}
    static void edit(MinecraftServer s,BlockPos pos,JsonObject e,long revision){var p=player(s);var desk=(DeskBlockEntity)p.level().getBlockEntity(pos);var result=DeskServer.get(s).store.action(desk.workspace,p.getUUID(),editRequest(e,revision));require(result.get("ok").getAsBoolean(),result.toString());}
    static void remove(MinecraftServer s,BlockPos pos,String id){var p=player(s);var desk=(DeskBlockEntity)p.level().getBlockEntity(pos);var store=DeskServer.get(s).store;JsonObject before=store.element(desk.workspace,UUID.fromString(id));JsonObject request=editRequest(before,before.get("revision").getAsLong());request.getAsJsonArray("changes").get(0).getAsJsonObject().add("after",JsonNull.INSTANCE);require(store.action(desk.workspace,p.getUUID(),request).get("ok").getAsBoolean(),"Remove overlay failed");}
    static JsonObject element(TestServerContext server,BlockPos pos,UUID id){return server.computeOnServer(s->{var desk=(DeskBlockEntity)player(s).level().getBlockEntity(pos.equals(V)?V.east():pos);return DeskServer.get(s).store.element(desk.workspace,id);});}
    static JsonObject element(TestServerContext server,String id){return element(server,V,UUID.fromString(id));}
    static JsonObject typeOrNull(TestServerContext server,String type){return server.computeOnServer(s->{var desk=(DeskBlockEntity)player(s).level().getBlockEntity(V);for(var e:DeskServer.get(s).store.view(desk.workspace,player(s).getUUID(),-1000,-1000,2000,2000).getAsJsonArray("elements"))if(e.getAsJsonObject().get("type").getAsString().equals(type))return e.getAsJsonObject();return null;});}
    static JsonObject type(TestServerContext server,String type){return Objects.requireNonNull(typeOrNull(server,type));}
    static void waitType(ClientGameTestContext c,TestServerContext server,String type){waitServer(c,()->typeOrNull(server,type)!=null);}
    static void waitServer(ClientGameTestContext c,java.util.function.BooleanSupplier condition){for(int i=0;i<WAIT;i++){if(condition.getAsBoolean())return;c.waitTicks(1);}throw new AssertionError("Timed out waiting for authoritative server state");}
    static boolean isChecked(TestServerContext server,BlockPos pos,UUID id,int row){var e=element(server,pos,id);var a=e.getAsJsonArray("checks");return a.size()>row&&a.get(row).getAsBoolean();}
    static long revision(TestServerContext server,BlockPos pos,UUID id){return element(server,pos,id).get("revision").getAsLong();}
    static void waitChecked(ClientGameTestContext c,TestServerContext server,BlockPos pos,UUID id,int row,boolean checked){waitServer(c,()->isChecked(server,pos,id,row)==checked);c.waitTicks(2);}
    static double[] checkboxPoint(ClientGameTestContext c,TestServerContext server,BlockPos pos,UUID id,int row){JsonObject e=element(server,pos,id);return c.computeOnClient(client->{var v=new CanvasElementView(e);var l=CanvasTextLayout.of(client.font,v);var check=l.checks().stream().filter(t->t.index()==row).findFirst().orElseThrow();return new double[]{v.x+l.padding()+l.boxSize()/2,v.y+check.y()+l.boxSize()/2};});}
    static void aim(ClientGameTestContext c,TestServerContext server,BlockPos pos,double x,double y,double distance){Vec3 point=c.computeOnClient(client->BoardPlane.surfacePoint(client.level.getBlockState(pos),x/256,y/256).add(Vec3.atLowerCornerOf(pos)));boolean flat=c.computeOnClient(client->BoardPlane.horizontal(client.level.getBlockState(pos)));Vec3 feet=flat?point.add(0,distance-1.62,0):new Vec3(point.x,pos.getY(),point.z+distance);Vec3 eye=feet.add(0,1.62,0),delta=point.subtract(eye);float yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),pitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));server.runOnServer(s->{var p=player(s);p.getAbilities().flying=true;p.onUpdateAbilities();p.teleportTo(p.level(),feet.x,feet.y,feet.z,Set.of(),yaw,pitch,false);});c.waitFor(client->client.player.position().distanceToSqr(feet)<.04,WAIT);c.runOnClient(client->{client.player.setYRot(yaw);client.player.setXRot(pitch);client.player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);SurfaceCache.request(pos);});c.waitTicks(3);}
    static void click(ClientGameTestContext c,BlockPos pos,double x,double y){c.runOnClient(client->{var state=client.level.getBlockState(pos);Vec3 point=BoardPlane.surfacePoint(state,x/256,y/256).add(Vec3.atLowerCornerOf(pos));var hit=new BlockHitResult(point,BoardPlane.horizontal(state)?Direction.UP:state.getValue(DeskBlock.FACING),pos,false);client.hitResult=hit;client.gameMode.useItemOn(client.player,InteractionHand.MAIN_HAND,hit);});}
    static void waitEditor(ClientGameTestContext c){c.waitFor(client->client.gui.screen() instanceof CanvasScreen screen&&read(screen,"surface")!=null&&!((Boolean)read(screen,"surfaceUpdating")),WAIT);}
    static void waitModules(ClientGameTestContext c,int count){c.waitFor(client->client.gui.screen() instanceof CanvasScreen screen&&((SurfaceLayout)read(screen,"surface")).tiles.size()==count&&!((Boolean)read(screen,"surfaceUpdating")),WAIT);}
    static void close(ClientGameTestContext c){c.runOnClient(client->client.gui.screen().onClose());c.waitFor(client->client.gui.screen()==null,WAIT);c.waitTicks(2);}
    static CanvasViewport viewport(Minecraft c){return (CanvasViewport)read(c.gui.screen(),"viewport");}
    static void key(ClientGameTestContext c,int key,int mods){c.runOnClient(client->client.gui.screen().keyPressed(new KeyEvent(key,keycode(key),mods)));c.waitTicks(2);}
    static int keycode(int scan){if(scan>=InputConstants.KEY_A&&scan<=InputConstants.KEY_Z)return 'a'+scan-InputConstants.KEY_A;return switch(scan){case InputConstants.KEY_RETURN->13;case InputConstants.KEY_ESCAPE->27;case InputConstants.KEY_BACKSPACE->8;case InputConstants.KEY_TAB->9;case InputConstants.KEY_SPACE->32;case InputConstants.KEY_DELETE->127;default->scan|(1<<30);};}
    static MouseButtonEvent mouse(double x,double y){return new MouseButtonEvent(x,y,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));}
    static void documentClick(ClientGameTestContext c,double x,double y){c.runOnClient(client->{var v=viewport(client);var e=mouse(v.screenX(x),v.screenY(y));client.gui.screen().mouseClicked(e,false);client.gui.screen().mouseReleased(e);});}
    static void drag(ClientGameTestContext c,double x1,double y1,double x2,double y2,double x3,double y3){c.runOnClient(client->{var v=viewport(client);var screen=client.gui.screen();screen.mouseClicked(mouse(v.screenX(x1),v.screenY(y1)),false);screen.mouseDragged(mouse(v.screenX(x2),v.screenY(y2)),(x2-x1)*v.zoom,(y2-y1)*v.zoom);screen.mouseDragged(mouse(v.screenX(x3),v.screenY(y3)),(x3-x2)*v.zoom,(y3-y2)*v.zoom);screen.mouseReleased(mouse(v.screenX(x3),v.screenY(y3)));});c.waitTicks(2);}
    static void widget(ClientGameTestContext c,String label){widgetContaining(c,label,label);}
    static void widgetContaining(ClientGameTestContext c,String a,String b){c.runOnClient(client->{for(var child:client.gui.screen().children())if(child instanceof AbstractWidget w&&(w.getMessage().getString().contains(a)||w.getMessage().getString().contains(b))){var screen=client.gui.screen();var event=mouse(w.getX()+w.getWidth()/2.0,w.getY()+w.getHeight()/2.0);screen.mouseClicked(event,false);screen.mouseReleased(event);return;}throw new AssertionError("Missing widget "+a);});c.waitTicks(2);}
    static void checkLayout(ClientGameTestContext c){c.runOnClient(client->{var screen=(CanvasScreen)client.gui.screen();var layout=(CanvasEditorLayout)read(screen,"layout");var v=viewport(client);require(layout.panel().x()>0&&layout.panel().y()>0,"World margins missing");for(var child:screen.children())if(child instanceof AbstractWidget w){require(w.getWidth()>=20&&w.getHeight()>=20,"Small control");require(w.getRight()<=v.left||w.getX()>=v.right,"Control overlaps canvas");require(w.getY()>=layout.header().bottom()&&w.getBottom()<=layout.status().y(),"Control outside body");}if((Boolean)read(screen,"autoFit")){var b=((SurfaceLayout)read(screen,"surface")).bounds;double expected=Math.min(5,Math.min((v.right-v.left-16)/(b.maxX()-b.minX()),(v.bottom-v.top-16)/(b.maxY()-b.minY())));require(Math.abs(v.zoom-expected)<1e-8,"Incorrect fit scale");require(Math.abs(v.screenX((b.minX()+b.maxX())/2)-(v.left+v.right)/2.0)<1e-7,"Wrong horizontal center");require(Math.abs(v.screenY((b.minY()+b.maxY())/2)-(v.top+v.bottom)/2.0)<1e-7,"Wrong vertical center");}});}
    static Object read(Object target,String name){try{Class<?> type=target instanceof Class<?> c?c:target.getClass();Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target instanceof Class<?>?null:target);}catch(ReflectiveOperationException e){throw new IllegalStateException(name,e);}}
    static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private void pass(String s){checks.add("PASS "+s);CanvasDesk.LOGGER.info("Editor smoke PASS: {}",s);}
    private void shot(ClientGameTestContext c,String name)throws Exception{Path target=output.resolve(name+".png");Files.copy(c.takeScreenshot(name),target,StandardCopyOption.REPLACE_EXISTING);shots.add(target.toAbsolutePath().toString());}
    private void write(String status,String failure)throws Exception{Files.writeString(output.resolve("editor-selftest.log"),String.join("\n",checks)+"\n",StandardCharsets.UTF_8);Map<String,Object> r=new LinkedHashMap<>();r.put("status",status);r.put("time",Instant.now().toString());r.put("boundary","One real client, integrated server, disposable fixtures; native block clicks and live Screen events");r.put("checks",checks);r.put("screenshots",shots);if(failure!=null)r.put("failure",failure);Files.writeString(output.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(r),StandardCharsets.UTF_8);}
}
