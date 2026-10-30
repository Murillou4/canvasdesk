package br.com.murillo.canvasdesk.client.media;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.client.CanvasDeskClient;
import br.com.murillo.canvasdesk.client.editor.CanvasElementView;
import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import br.com.murillo.canvasdesk.core.EmbedSource;
import br.com.murillo.canvasdesk.core.BrowserCommandQueue;
import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** At most two OSR helpers. Only texture uploads happen on the render thread; no network/PNG work. */
public final class BrowserTexture {
    private static final int WIDTH=1280,HEIGHT=720,SIZE=WIDTH*HEIGHT*4;
    private static final Map<String,Entry> ENTRIES=new LinkedHashMap<>();
    private static final LinkedHashMap<String,JsonObject> RECEIPTS=new LinkedHashMap<>();
    private static final ExecutorService IO=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"CanvasDesk-browser-io");t.setDaemon(true);return t;});
    private static final Path ROOT=FabricLoader.getInstance().getGameDir().resolve("canvasdesk/browser");
    private static String focus="";private static long lastPoll;
    private static final Map<String,JsonObject> LAST_STATES=new LinkedHashMap<>();
    private static final Map<String,Double> VOLUMES=new LinkedHashMap<>();
    private static final Set<String> PROCESS_GATES=ConcurrentHashMap.newKeySet();
    private static final Map<String,Wanted> WANTED=new HashMap<>();
    private record Wanted(String workspace,CanvasElementView element,BlockPos pos,String module,long time) {}
    private BrowserTexture() {}
    public static final class Entry {
        public final String key,workspace,elementId,url,sourceAuthor;public final Path profile;public final Identifier textureId;
        public volatile String status="Preparando navegador…",mediaKind="web";public volatile JsonObject state;
        public volatile boolean ready,editable,closed,nativeAudio,audioBlocked=true,inputFailed,localInteractive;
        public java.util.function.Consumer<String> clipboardReceiver;public volatile JsonObject inputProbe;public volatile String diagnostic="";public volatile long inputAck;public volatile double caretX,caretY,caretW=1280,caretH=720;public volatile double observedPosition;public volatile boolean observedReady;
        public volatile int loadedPages;
        public volatile Process process;private volatile BufferedWriter input;private volatile MappedByteBuffer frame;
        private volatile List<ProcessHandle> children=List.of();
        private DynamicTexture texture;private int sequence;private long lastVisible,lastSubscribe,receivedAt,started,lastDrawn;
        private BlockPos pos;private String moduleId;private double localVolume=1;
        private final BrowserCommandQueue commands=new BrowserCommandQueue(1024*1024);
        private final CountDownLatch inputReady=new CountDownLatch(1);
        private long inputSequence;private boolean initialAudioAllowed;private long muteDeadline;
        private Entry(String workspace,CanvasElementView e,BlockPos pos,String moduleId){
            this.workspace=workspace;elementId=e.id;url=e.url;sourceAuthor=e.sourceAuthor;this.pos=pos;this.moduleId=moduleId;key=workspace+"/"+e.id;textureId=CanvasDesk.id("embed/"+e.id);
            var player=Minecraft.getInstance().player;profile=ROOT.resolve("profiles").resolve(UUID.nameUUIDFromBytes(((player==null?"offline":player.getUUID().toString())+"/"+key).getBytes(StandardCharsets.UTF_8)).toString());
            localVolume=VOLUMES.getOrDefault(key,1.0);JsonObject previous=LAST_STATES.get(key);if(previous!=null&&previous.get("source").getAsString().equals(url)){state=previous.deepCopy();editable=state.get("editable").getAsBoolean();receivedAt=System.nanoTime()/1_000_000;}
        }
        public MediaPartyClient.Target target(){return new MediaPartyClient.Target(workspace,elementId,pos,moduleId);}
        public JsonObject projected(){
            JsonObject s=state==null?new JsonObject():state.deepCopy();if(s.has("playing")&&s.get("playing").getAsBoolean())s.addProperty("position",s.get("position").getAsDouble()+Math.max(0,System.nanoTime()/1_000_000-receivedAt)/1000.0);
            double volume=Minecraft.getInstance().options.getFinalSoundSourceVolume(SoundSource.MASTER)*localVolume*MediaPartyClient.gain(this);
            boolean allowed=state!=null&&state.has("audioAllowed")&&state.get("audioAllowed").getAsBoolean()&&System.nanoTime()/1_000_000-receivedAt<5000;
            s.addProperty("volume",allowed?volume:0);s.addProperty("audioAllowed",allowed&&volume>0);return s;
        }
        public void send(JsonObject message){
            if(closed)return;String kind=message.get("cmd").getAsString();
            if(kind.equals("mouse")&&message.get("button").getAsInt()<0&&(!message.has("wheel")||message.get("wheel").getAsInt()==0))kind="motion";
            if(!Set.of("motion","state","audio").contains(kind)){message=message.deepCopy();message.addProperty("seq",++inputSequence);}
            if(!commands.offer(kind,message.toString())){inputFailed=true;status="Navegador lento: entrada não enviada. Use Reabrir.";}
        }
        public long sentInput(){return inputSequence;}
        public double localVolume(){return localVolume;}
        private void audio(){var p=projected();JsonObject c=new JsonObject();c.addProperty("cmd","audio");c.addProperty("allowed",p.get("audioAllowed").getAsBoolean());c.add("volume",p.get("volume"));send(c);if(!c.get("allowed").getAsBoolean()&&!audioBlocked&&muteDeadline==0)muteDeadline=System.currentTimeMillis()+250;}
        public void control(String command,Double at,String navigation,Double scrollX,Double scrollY){
            if(closed||state==null||!editable)return;JsonObject r=base(this,"mediaControl");r.addProperty("nonce",UUID.randomUUID().toString());r.addProperty("source",url);r.addProperty("command",command);r.add("mediaRevision",state.get("mediaRevision"));r.add("mediaSession",state.get("mediaSession"));if(at!=null)r.addProperty("position",at);if(navigation!=null)r.addProperty("url",navigation);if(scrollX!=null)r.addProperty("scrollX",scrollX);if(scrollY!=null)r.addProperty("scrollY",scrollY);CanvasDeskClient.send(r);
        }
        public void volume(double value){localVolume=Math.clamp(value,0,1);VOLUMES.put(key,localVolume);while(VOLUMES.size()>128)VOLUMES.remove(VOLUMES.keySet().iterator().next());audio();}
    }
    private static void drain(Entry e){
        try{if(!e.inputReady.await(180,TimeUnit.SECONDS))throw new IOException("Preparação excedeu o limite.");while(!e.closed){String command=e.commands.take();if(command==null)return;BufferedWriter writer=e.input;if(writer==null)throw new IOException("Navegador desconectado.");synchronized(writer){writer.write(command);writer.newLine();writer.flush();}}}
        catch(Exception error){if(!e.closed)Minecraft.getInstance().execute(()->{e.inputFailed=true;e.status="Entrada não confirmada pelo navegador. Use Reabrir.";});}
    }
    public static void pin(String workspace,CanvasElementView element,BlockPos pos,String module){
        String key=key(workspace,element.id);WANTED.put(key,new Wanted(workspace,element,pos,module,System.currentTimeMillis()));Entry e=ENTRIES.get(key);if(e!=null&&!e.url.equals(element.url)){stop(e);ENTRIES.remove(key);return;}if(e!=null){e.pos=pos;e.moduleId=module;e.lastVisible=System.currentTimeMillis();}
    }
    public static void silence(){for(Entry e:List.copyOf(ENTRIES.values())){e.audio();if(!e.nativeAudio&&e.ready){PROCESS_GATES.add(e.key);restart(e);}}}
    private static void restart(Entry e){stop(e);ENTRIES.remove(e.key,e);}
    private static String key(String workspace,String id){return workspace+"/"+id;}
    public static Entry get(String workspace,String id){return ENTRIES.get(key(workspace,id));}
    public static Entry byId(String id){for(var e:ENTRIES.values())if(e.elementId.equals(id)&&!e.closed)return e;return null;}
    public static Entry visible(String workspace,CanvasElementView element,BlockPos pos,String moduleId){
        if(element.url.isBlank())return null;String key=key(workspace,element.id);WANTED.put(key,new Wanted(workspace,element,pos,moduleId,System.currentTimeMillis()));Entry e=ENTRIES.get(key);
        if(e!=null&&!e.url.equals(element.url)){stop(e);ENTRIES.remove(key);e=null;}
        if(e!=null){e.pos=pos;e.moduleId=moduleId;e.lastVisible=System.currentTimeMillis();e.lastDrawn=e.lastVisible;}return e;
    }
    public static Entry activate(String workspace,CanvasElementView element,BlockPos pos,String moduleId){focus=key(workspace,element.id);visible(workspace,element,pos,moduleId);return open(WANTED.get(focus));}
    private static Entry open(Wanted wanted){String workspace=wanted.workspace,moduleId=wanted.module;CanvasElementView element=wanted.element;BlockPos pos=wanted.pos;String key=key(workspace,element.id);Entry e=ENTRIES.get(key);
        if(e==null){
            if(ENTRIES.size()>=2){Entry oldest=ENTRIES.values().stream().filter(v->!v.key.equals(focus)).min(Comparator.<Entry,Boolean>comparing(v->MediaPartyClient.keepAlive(v.workspace,v.elementId)).thenComparingLong(v->v.lastVisible)).orElse(null);if(oldest==null)return null;stop(oldest);ENTRIES.remove(oldest.key);}
            e=new Entry(workspace,element,pos,moduleId);ENTRIES.put(key,e);e.initialAudioAllowed=e.projected().get("audioAllowed").getAsBoolean();Entry started=e;IO.execute(()->drain(started));IO.execute(()->launch(started));
        }
        e.pos=pos;e.moduleId=moduleId;e.lastVisible=System.currentTimeMillis();return e;
    }
    public static void focus(Entry e){focus=e==null?"":e.key;}
    public static void tick(Minecraft client){
        if(client.level==null||client.player==null)return;long time=System.currentTimeMillis();
        WANTED.entrySet().removeIf(w->time-w.getValue().time>500&&!w.getKey().equals(focus)&&!MediaPartyClient.keepAlive(w.getValue().workspace,w.getValue().element.id));
        var desired=WANTED.entrySet().stream().sorted(Comparator.<Map.Entry<String,Wanted>,Boolean>comparing(w->!w.getKey().equals(focus)).thenComparing(w->!MediaPartyClient.keepAlive(w.getValue().workspace,w.getValue().element.id)).thenComparingDouble(w->client.player.distanceToSqr(w.getValue().pos.getX()+.5,w.getValue().pos.getY()+.5,w.getValue().pos.getZ()+.5))).limit(2).toList();
        Set<String> active=new HashSet<>();desired.forEach(w->active.add(w.getKey()));
        for(var it=ENTRIES.values().iterator();it.hasNext();){Entry e=it.next();if(!active.contains(e.key)&&time-e.lastVisible>1000){stop(e);it.remove();}}
        for(var wanted:desired)if(!ENTRIES.containsKey(wanted.getKey()))open(wanted.getValue());
        if(time-lastPoll>=100){lastPoll=time;for(var it=ENTRIES.values().iterator();it.hasNext();){Entry e=it.next();
            if(!e.key.equals(focus)&&!MediaPartyClient.keepAlive(e.workspace,e.elementId)&&time-e.lastVisible>2500){stop(e);it.remove();continue;}
            if(time-e.lastSubscribe>=1000&&client.player.distanceToSqr(e.pos.getX()+.5,e.pos.getY()+.5,e.pos.getZ()+.5)<=4096){CanvasDeskClient.send(base(e,"mediaSubscribe"));e.lastSubscribe=time;}
            if(e.process!=null&&!e.process.isAlive()&&!e.closed){e.status="Navegador encerrou. Use Reabrir.";stop(e);}
            if(!e.ready&&e.started>0&&time-e.started>180_000){e.status="Tempo de preparação esgotado. Use Reabrir.";stop(e);}
            if(e.muteDeadline>0&&time>e.muteDeadline&&!e.audioBlocked){PROCESS_GATES.add(e.key);stop(e);it.remove();continue;}
            e.localInteractive=e.editable||MediaPartyClient.participates(e.workspace,e.sourceAuthor);
            if(e.ready&&e.state!=null){e.audio();JsonObject command=new JsonObject();command.addProperty("cmd","state");command.add("state",e.projected());command.addProperty("editable",e.editable);command.addProperty("localInteractive",e.localInteractive);command.addProperty("visible",e.key.equals(focus)||time-e.lastDrawn<500);e.send(command);}
        }}
    }
    public static JsonObject receipt(String nonce){return RECEIPTS.get(nonce);}
    public static void ended(JsonObject state){Entry e=get(state.get("workspace").getAsString(),state.get("elementId").getAsString());if(e==null||!e.url.equals(state.get("source").getAsString())||e.state!=null&&!state.get("mediaSession").equals(e.state.get("mediaSession")))return;e.status=state.get("message").getAsString();stop(e);}
    public static void accept(JsonObject state){if(state.has("nonce")){RECEIPTS.put(state.get("nonce").getAsString(),state.deepCopy());while(RECEIPTS.size()>64)RECEIPTS.remove(RECEIPTS.keySet().iterator().next());}if(!state.has("workspace")||!state.has("elementId"))return;if(state.has("mediaRevision")){LAST_STATES.put(key(state.get("workspace").getAsString(),state.get("elementId").getAsString()),state.deepCopy());while(LAST_STATES.size()>128)LAST_STATES.remove(LAST_STATES.keySet().iterator().next());}Entry e=get(state.get("workspace").getAsString(),state.get("elementId").getAsString());if(e==null)return;
        if(state.has("source")&&!state.get("source").getAsString().equals(e.url))return;
        if(state.has("mediaRevision")){long revision=state.get("mediaRevision").getAsLong();if(e.state!=null&&state.get("mediaSession").equals(e.state.get("mediaSession"))&&revision<e.state.get("mediaRevision").getAsLong())return;e.state=state.deepCopy();e.editable=state.get("editable").getAsBoolean();e.receivedAt=System.nanoTime()/1_000_000;}
        if(state.has("ok")&&!state.get("ok").getAsBoolean())e.status=state.get("message").getAsString();
    }
    private static JsonObject base(Entry e,String action){JsonObject r=new JsonObject();r.addProperty("action",action);r.addProperty("workspace",e.workspace);r.addProperty("elementId",e.elementId);r.addProperty("pos",e.pos.asLong());r.addProperty("moduleId",e.moduleId);return r;}
    public static void uploadFrames(){
        for(Entry e:ENTRIES.values()){if(!e.key.equals(focus)&&System.currentTimeMillis()-e.lastDrawn>500)continue;MappedByteBuffer frame=e.frame;if(frame==null||e.closed)continue;int sequence=frame.getInt(8);if(sequence==0||(sequence&1)!=0||sequence==e.sequence)continue;
            if(e.texture==null){e.texture=new DynamicTexture(()->"Canvas Desk embed",new NativeImage(WIDTH,HEIGHT,true));Minecraft.getInstance().getTextureManager().register(e.textureId,e.texture);}
            ByteBuffer bytes=frame.duplicate();bytes.position(32).limit(32+SIZE);e.texture.getPixels().getPixelBytes().clear().put(bytes);if(frame.getInt(8)!=sequence)continue;e.texture.upload();e.sequence=sequence;
        }
    }
    public static Identifier texture(String elementId){Entry e=byId(elementId);return e!=null&&e.texture!=null?e.textureId:null;}
    public static void paint(GuiGraphicsExtractor g,CanvasElementView element){
        Identifier id=texture(element.id);if(id!=null){var r=br.com.murillo.canvasdesk.core.EmbedGeometry.fit(0,0,element.w,element.h);g.blit(id,(int)r.x(),(int)r.y(),(int)(r.x()+r.w()),(int)(r.y()+r.h()),0,1,0,1);}
        Entry e=null;for(var known:ENTRIES.values())if(known.elementId.equals(element.id)){e=known;break;}
        if(e!=null&&!e.status.isBlank()){var font=Minecraft.getInstance().font;g.fill(0,(int)element.h-16,(int)element.w,(int)element.h,0xddf5f0e5);g.text(font,font.plainSubstrByWidth(e.status,Math.max(1,(int)element.w-8)),4,(int)element.h-12,0xff34342e,false);}
    }
    public static void reopen(Entry e){stop(e);ENTRIES.remove(e.key,e);}
    public static void cancel(Entry e){e.status="Cancelado. Use Reabrir para tentar novamente.";stop(e);}
    public static void clear(){for(Entry e:ENTRIES.values())stop(e);ENTRIES.clear();WANTED.clear();RECEIPTS.clear();LAST_STATES.clear();PROCESS_GATES.clear();focus="";}
    private static void stop(Entry e){if(e.closed)return;e.closed=true;Process p=e.process;if(p!=null)IO.execute(()->{Set<ProcessHandle> children=new HashSet<>(e.children);if(p.isAlive())children.addAll(p.descendants().toList());try{if(e.input!=null){synchronized(e.input){e.input.write("{\"cmd\":\"close\"}\n");e.input.flush();}}if(!p.waitFor(3,TimeUnit.SECONDS)){children.forEach(ProcessHandle::destroy);p.destroy();if(!p.waitFor(1,TimeUnit.SECONDS))p.destroyForcibly();}}catch(Exception ignored){p.destroyForcibly();}finally{children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);}});
        e.commands.close();e.inputReady.countDown();if(e.texture!=null)Minecraft.getInstance().getTextureManager().release(e.textureId);e.frame=null;
    }
    private static void launch(Entry e){
        e.started=System.currentTimeMillis();try{
            if(e.closed)return;
            Files.createDirectories(ROOT);Path worker;
            synchronized(BrowserTexture.class){try(var in=BrowserTexture.class.getResourceAsStream("/canvasdesk-browser/worker.jar")){if(in==null)throw new IOException("Runtime do navegador ausente.");byte[] bytes=in.readAllBytes();String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0,16);worker=ROOT.resolve("worker-"+hash+".jar");if(!Files.exists(worker))Files.write(worker,bytes);}}
            Path cache=Path.of(System.getProperty("canvasdesk.browserCache",ROOT.resolve("152.0.6").resolve(System.getProperty("os.name").replaceAll("[^a-zA-Z]","")+"-"+System.getProperty("os.arch")).toString()));
            Path session=ROOT.resolve("sessions").resolve(UUID.randomUUID().toString());Files.createDirectories(session);
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"javaw.exe":"java").toString();
            String type=EmbedSource.youtubeId(e.url).isEmpty()?(EmbedSource.directVideo(e.url)?"video":"web"):"youtube";
            ProcessBuilder builder=new ProcessBuilder(java,"--enable-native-access=ALL-UNNAMED","--add-modules=jdk.httpserver","-jar",worker.toString(),cache.toString(),session.toString(),e.url,type);
            builder.command().add(1,"-Dcanvasdesk.browserProfile="+e.profile);
            if(PROCESS_GATES.contains(e.key))builder.command().add(1,"-Dcanvasdesk.audioProcessGate="+(e.initialAudioAllowed?"allowed":"blocked"));
            if(Boolean.getBoolean("canvasdesk.browserSilent"))builder.command().add(1,"-Dcanvasdesk.browserSilent=true");
            if(Boolean.getBoolean("canvasdesk.browserProbe"))builder.command().add(1,"-Dcanvasdesk.browserProbe=true");
            builder.redirectErrorStream(true);builder.redirectOutput(session.resolve("worker.log").toFile());if(e.closed)return;e.process=builder.start();if(e.closed){e.process.destroyForcibly();return;}e.input=new BufferedWriter(new OutputStreamWriter(e.process.getOutputStream(),StandardCharsets.UTF_8));e.inputReady.countDown();
            long childPoll=0;try(var events=new RandomAccessFile(session.resolve("events.jsonl").toFile(),"rw")){
                while(!e.closed&&e.process.isAlive()){
                    long time=System.currentTimeMillis();if(time-childPoll>1000){e.children=e.process.descendants().toList();childPoll=time;}
                    String line;long offset=events.getFilePointer();while((line=events.readLine())!=null){if(line.length()>65536){offset=events.getFilePointer();continue;}try{JsonObject event=JsonParser.parseString(new String(line.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8)).getAsJsonObject();Minecraft.getInstance().execute(()->{if(!e.closed)event(e,event);});}catch(RuntimeException partial){events.seek(offset);break;}offset=events.getFilePointer();}
                    if(e.frame==null&&Files.exists(session.resolve("frame.rgba"))){try(FileChannel channel=FileChannel.open(session.resolve("frame.rgba"),StandardOpenOption.READ)){if(channel.size()>=32+SIZE){e.frame=channel.map(FileChannel.MapMode.READ_ONLY,0,32+SIZE);e.frame.order(ByteOrder.LITTLE_ENDIAN);}}}
                    Thread.sleep(25);
                }
            }
        }catch(Exception error){e.status="Falha no navegador: "+error.getMessage();CanvasDesk.LOGGER.warn("Browser helper failed",error);}
    }
    private static void event(Entry e,JsonObject event){
        String kind=event.get("kind").getAsString();
        switch(kind){
            case "status","error"->e.status=event.has("message")?event.get("message").getAsString():"Falha do provedor.";
            case "audioFallback"->{PROCESS_GATES.add(e.key);restart(e);}
            case "audioRestart"->restart(e);
            case "audioGate"->{e.audioBlocked=event.get("blocked").getAsBoolean()||event.get("silentTest").getAsBoolean();if(e.audioBlocked)e.muteDeadline=0;}
            case "clipboard"->{var receiver=e.clipboardReceiver;e.clipboardReceiver=null;if(receiver!=null)receiver.accept(event.get("text").getAsString());}
            case "clipboardError"->e.status=event.get("message").getAsString();
            case "inputProbe"->{if(Boolean.getBoolean("canvasdesk.browserProbe"))e.inputProbe=event.deepCopy();}
            case "inputAck"->e.inputAck=Math.max(e.inputAck,event.get("seq").getAsLong());
            case "inputError"->{e.inputFailed=true;e.status=event.get("message").getAsString();}
            case "textArea"->{e.caretX=event.get("x").getAsDouble();e.caretY=event.get("y").getAsDouble();e.caretW=event.get("w").getAsDouble();e.caretH=event.get("h").getAsDouble();}
            case "diagnostics"->{e.diagnostic=e.url.contains("netflix.com")&&(!event.get("widevine").getAsBoolean()||!event.get("mp4").getAsBoolean())?"Netflix: DRM/codecs indisponíveis neste navegador JCEF.":"";}
            case "ready"->{e.nativeAudio=event.has("nativeAudio")&&event.get("nativeAudio").getAsBoolean();e.ready=true;e.mediaKind=event.has("mediaKind")?event.get("mediaKind").getAsString():"web";e.status="";}
            case "loaded"->e.loadedPages++;
            case "telemetry"->{e.observedPosition=event.get("position").getAsDouble();e.observedReady=event.has("ready")&&event.get("ready").getAsBoolean();}
            case "navigate"->e.control("navigate",null,event.get("url").getAsString(),null,null);
            case "scroll"->e.control("scroll",null,null,event.get("scrollX").getAsDouble(),event.get("scrollY").getAsDouble());
            case "play","pause","seek"->e.control(kind,event.get("position").getAsDouble(),null,null,null);
        }
    }
}
