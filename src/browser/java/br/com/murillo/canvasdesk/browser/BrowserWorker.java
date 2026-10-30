package br.com.murillo.canvasdesk.browser;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import me.friwi.jcefmaven.*;
import org.cef.*;
import org.cef.browser.*;
import org.cef.callback.*;
import org.cef.handler.*;
import org.cef.network.CefRequest;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Client-only subprocess. Its stdout is a log; events and raw RGBA frames use separate channels. */
public final class BrowserWorker {
    private static final Gson JSON=new Gson();
    private static final int W=1280,H=720,BYTES=W*H*4,HEADER=32;
    private static BufferedWriter events;private static MappedByteBuffer memory;private static int sequence;
    private static long lastPaint;private static final int[] pixels=new int[W*H];
    private static CanvasBrowser browser;private static CefApp app;private static HttpServer wrapper;
    private static volatile JsonObject state=new JsonObject();private static volatile boolean editable,closing,privateForm;private static long navigationRevision=-1;private static int frameRate=30;
    private static java.nio.channels.FileLock profileLock;private static FileChannel profileLease;
    private static String source,kind,initial;
    private static BrowserAudio audio;private static boolean nativeAudio,remoteLoaded;
    private static volatile boolean audioAllowed,localInteractive;
    private static final String PROCESS_GATE=System.getProperty("canvasdesk.audioProcessGate","");
    private static final boolean SILENT=Boolean.getBoolean("canvasdesk.browserSilent");
    public static void main(String[] args)throws Exception{
        Path cache=Path.of(args[0]),directory=Path.of(args[1]);Files.createDirectories(directory);Files.createDirectories(cache);
        events=Files.newBufferedWriter(directory.resolve("events.jsonl"),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        source=args.length>2?args[2]:"about:blank";kind=args.length>3?args[3]:"web";
        if(kind.equals("web")){event("status","Identificando conteúdo…");try(var http=java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build()){var request=java.net.http.HttpRequest.newBuilder(URI.create(source)).timeout(java.time.Duration.ofSeconds(3)).method("HEAD",java.net.http.HttpRequest.BodyPublishers.noBody()).build();var response=http.send(request,java.net.http.HttpResponse.BodyHandlers.discarding());if(response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT).startsWith("video/"))kind="video";}catch(Exception ignored){/* A normal navigation still handles sites that reject HEAD. */}}
        Runtime.getRuntime().addShutdownHook(new Thread(()->{closing=true;try{if(browser!=null)browser.close(true);if(app!=null)app.dispose();if(wrapper!=null)wrapper.stop(0);}catch(Throwable ignored){}}));
        CefAppBuilder builder=new CefAppBuilder();builder.setInstallDir(cache.toFile());builder.setProgressHandler((progress,percent)->event("status",progress+" "+Math.round(percent)+"%"));
        builder.getCefSettings().windowless_rendering_enabled=true;builder.getCefSettings().persist_session_cookies=true;
        builder.getCefSettings().cache_path=Path.of(System.getProperty("canvasdesk.browserProfile",directory.resolve("profile").toString())).toAbsolutePath().toString();builder.getCefSettings().root_cache_path=builder.getCefSettings().cache_path;
        Path profile=Path.of(builder.getCefSettings().cache_path);Files.createDirectories(profile);
        profileLease=FileChannel.open(profile.resolveSibling(profile.getFileName()+".lease"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        long leaseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while((profileLock=profileLease.tryLock())==null){if(System.nanoTime()>leaseDeadline)throw new IOException("O perfil ainda está encerrando. Use Reabrir.");Thread.sleep(50);}
        builder.getCefSettings().log_file=directory.resolve("chromium.log").toString();
        builder.addJcefArgs("--disable-gpu","--disable-gpu-compositing","--disable-extensions","--autoplay-policy=no-user-gesture-required","--disable-background-timer-throttling");
        if(SILENT||PROCESS_GATE.equals("blocked"))builder.addJcefArgs("--mute-audio");
        builder.setAppHandler(new MavenCefAppHandlerAdapter(){});
        NativeRuntime.prepare(cache,builder,s->event("status",s));
        if(kind.equals("install")){event("ready","Bibliotecas preparadas e verificadas.");events.close();return;}
        try(FileChannel channel=FileChannel.open(directory.resolve("frame.rgba"),StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE)){
            channel.truncate(HEADER+BYTES);memory=channel.map(FileChannel.MapMode.READ_WRITE,0,HEADER+BYTES);memory.order(ByteOrder.LITTLE_ENDIAN);memory.putInt(0,W);memory.putInt(4,H);
            app=builder.build();CefClient client=app.createClient();
            CefMessageRouter router=CefMessageRouter.create(new CefMessageRouterHandlerAdapter(){public boolean onQuery(CefBrowser b,CefFrame frame,long id,String request,boolean persistent,CefQueryCallback callback){
                try{JsonObject e=JsonParser.parseString(request).getAsJsonObject();String type=e.get("kind").getAsString();
                    if(type.equals("applyAudio")){applyAudio();callback.success("");return true;}
                    if(type.equals("textArea")||type.equals("diagnostics")){if(request.length()<8192)emit(e);callback.success("");return true;}
                    if(type.equals("privateForm")){privateForm=true;callback.success("");return true;}
                    if(Set.of("scroll","play","pause","seek","telemetry","error").contains(type)&&request.length()<8192){if(editable||type.equals("telemetry")||type.equals("error"))emit(e);callback.success("");return true;}
                }catch(RuntimeException ignored){}callback.failure(1,"Unsupported message");return true;
            }});client.addMessageRouter(router);
            client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter(){public boolean onBeforePopup(CefBrowser b,CefFrame f,String url,String frame){if(editable)navigate(url);return true;}public void onAfterCreated(CefBrowser b){
                audio=new BrowserAudio(CefRequestContext.getGlobalContext());nativeAudio=audio.configure();
                if(!nativeAudio&&PROCESS_GATE.isEmpty()&&!SILENT){event("audioFallback","Bloqueio de áudio por processo necessário.");closing=true;return;}
                JsonObject ready=new JsonObject();ready.addProperty("kind","ready");ready.addProperty("mediaKind",kind);ready.addProperty("nativeAudio",nativeAudio);ready.addProperty("audioPreference",audio.preference());emit(ready);b.setFocus(true);
                browser.devTools("Emulation.setFocusEmulationEnabled","{\"enabled\":true}");
                applyAudio();
            }});
            client.addLoadHandler(new CefLoadHandlerAdapter(){
                public void onLoadEnd(CefBrowser b,CefFrame frame,int status){if(frame.isMain()){
                    if(frame.getURL().equals("about:blank")&&!remoteLoaded){if(!closing){remoteLoaded=true;b.loadURL(initial);}return;}
                    b.executeJavaScript(resource("input.js"),frame.getURL(),0);
                    if(kind.equals("web"))b.executeJavaScript(resource("web.js"),frame.getURL(),0);apply();event("loaded","");
                }}
                public void onLoadError(CefBrowser b,CefFrame f,ErrorCode code,String text,String url){if(f.isMain()&&code!=ErrorCode.ERR_ABORTED)event("error",code+": "+text);}
            });
            client.addRequestHandler(new CefRequestHandlerAdapter(){
                public boolean onBeforeBrowse(CefBrowser b,CefFrame f,CefRequest request,boolean gesture,boolean redirect){
                    String url=request.getURL();if(f.isMain()&&audio!=null)applyAudio();if(f.isMain()&&!url.startsWith("http://127.0.0.1:")&&!url.startsWith("https://")&&!url.startsWith("http://")&&!url.equals("about:blank"))return true;
                    if(request.getTransitionType()==CefRequest.TransitionType.TT_FORM_SUBMIT){privateForm=true;return false;}
                    if(kind.equals("web")&&f.isMain()&&gesture&&!redirect&&request.getMethod().equals("GET")){if(privateForm||!editable)return false;navigate(url);return true;}return false;
                }
                public void onRenderProcessTerminated(CefBrowser b,TerminationStatus status,int code,String text){event("error","Processo do navegador: "+status);}
            });
            initial=kind.equals("web")?source:startWrapper();browser=new CanvasBrowser(client,"about:blank",BrowserWorker::paint);browser.createImmediately();
            Thread input=new Thread(()->{try(var in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){String line;while((line=in.readLine())!=null&&!closing){if(line.length()>65536)continue;JsonObject c=null;try{c=JsonParser.parseString(line).getAsJsonObject();command(c);if(c.has("seq")){JsonObject ack=new JsonObject();ack.addProperty("kind","inputAck");ack.add("seq",c.get("seq"));emit(ack);}}catch(Throwable error){if(Boolean.getBoolean("canvasdesk.browserProbe")){JsonObject detail=new JsonObject();detail.addProperty("kind","testInputError");detail.addProperty("error",error.toString());if(c!=null){detail.add("command",c.get("cmd"));if(c.has("code"))detail.add("code",c.get("code"));if(c.has("text"))detail.addProperty("textLength",c.get("text").getAsString().length());if(c.has("seq"))detail.add("seq",c.get("seq"));}emit(detail);}event("inputError","Entrada não confirmada; use Reabrir.");}}}catch(IOException ignored){}finally{closing=true;}} ,"Canvas Desk browser input");input.setDaemon(true);input.start();
            while(!closing)Thread.sleep(50);
            browser.close(true);client.dispose();app.dispose();if(wrapper!=null)wrapper.stop(0);events.close();profileLock.release();profileLease.close();
        }
    }
    private static void navigate(String url){try{URI u=URI.create(url);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null)return;JsonObject e=new JsonObject();e.addProperty("kind","navigate");e.addProperty("url",url);emit(e);}catch(RuntimeException ignored){}}
    private static void command(JsonObject c)throws Exception{String cmd=c.get("cmd").getAsString();
        switch(cmd){
            case "close"->closing=true;
            case "state"->{int rate=!c.has("visible")||c.get("visible").getAsBoolean()?30:1;if(rate!=frameRate){frameRate=rate;browser.setWindowlessFrameRate(rate);}state=c.getAsJsonObject("state").deepCopy();editable=c.has("editable")&&c.get("editable").getAsBoolean();localInteractive=c.has("localInteractive")&&c.get("localInteractive").getAsBoolean();audioAllowed=state.has("audioAllowed")&&state.get("audioAllowed").getAsBoolean();long nav=state.has("navigationRevision")?state.get("navigationRevision").getAsLong():0;if(kind.equals("web")&&state.has("navigationUrl")&&(!state.get("navigationUrl").getAsString().equals(source)||privateForm&&nav!=navigationRevision)){source=state.get("navigationUrl").getAsString();privateForm=false;browser.loadURL(source);}navigationRevision=nav;apply();}
            case "mouse"->{if(!localInteractive)break;if(c.get("down").getAsBoolean())browser.executeJavaScript("window.canvasGesture=Date.now();",browser.getURL(),0);browser.mouse(c.get("x").getAsInt(),c.get("y").getAsInt(),c.get("button").getAsInt(),c.get("down").getAsBoolean(),c.has("wheel")?c.get("wheel").getAsInt():0);}
            case "key"->{if(localInteractive)browser.key(c.get("code").getAsInt(),c.get("down").getAsBoolean(),c.get("modifiers").getAsInt(),c.has("text")?c.get("text").getAsString():null);}
            case "resetInput"->browser.resetInput();
            case "copy","cut"->{
                if(!localInteractive)break;
                // Chromium owns focused-frame selection, including cross-origin inputs. The
                // isolated probe uses a memory clipboard so it cannot replace the user's clipboard.
                if(!Boolean.getBoolean("canvasdesk.browserProbe")){
                    CefFrame focused=browser.getFocusedFrame();
                    if(focused!=null){if(cmd.equals("cut"))focused.cut();else focused.copy();}
                    break;
                }
                JsonObject p=new JsonObject();p.addProperty("expression","(()=>{const e=document.activeElement;if(e?.type==='password')return [];let t=(e&&typeof e.selectionStart==='number')?e.value.slice(e.selectionStart,e.selectionEnd):getSelection().toString();if(t.length>16384)return null;"+(cmd.equals("cut")?"if(e&&typeof e.selectionStart==='number'){e.setRangeText('',e.selectionStart,e.selectionEnd,'start');e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'deleteByCut'}));}else document.execCommand('delete');":"")+"return Array.from(t,c=>c.codePointAt(0));})()");p.addProperty("returnByValue",true);
                var result=JsonParser.parseString(browser.devTools("Runtime.evaluate",p.toString()).get(3,TimeUnit.SECONDS)).getAsJsonObject().getAsJsonObject("result");
                if(!result.has("value")||result.get("value").isJsonNull())event("clipboardError","Seleção grande demais para copiar de uma vez.");else{JsonObject e=new JsonObject();e.addProperty("kind","clipboard");e.addProperty("text",codepoints(result.getAsJsonArray("value")));emit(e);}
            }
            case "probeInput"->{if(Boolean.getBoolean("canvasdesk.browserProbe")){JsonObject p=new JsonObject();p.addProperty("expression","Array.from(document.getElementById('probe')?.value ?? '',c=>c.codePointAt(0))");p.addProperty("returnByValue",true);String result=browser.devTools("Runtime.evaluate",p.toString()).get(3,TimeUnit.SECONDS);JsonObject e=new JsonObject();e.addProperty("kind","inputProbe");e.addProperty("value",codepoints(JsonParser.parseString(result).getAsJsonObject().getAsJsonObject("result").getAsJsonArray("value")));emit(e);}}
            case "audio"->{audioAllowed=c.get("allowed").getAsBoolean();JsonObject updated=state.deepCopy();updated.addProperty("volume",c.get("volume").getAsDouble());state=updated;apply();}
            case "volume"->{JsonObject updated=state.deepCopy();updated.addProperty("volume",c.get("value").getAsDouble());state=updated;apply();}
        }
    }
    private static String codepoints(JsonArray values){StringBuilder b=new StringBuilder();for(JsonElement v:values)b.appendCodePoint(v.getAsInt());return b.toString();}
    private static void apply(){if(browser==null)return;JsonObject copy=state.deepCopy();copy.addProperty("editable",editable);copy.addProperty("volume",audioAllowed&&copy.has("volume")?copy.get("volume").getAsDouble():0);browser.executeJavaScript("window.cefQuery&&cefQuery({request:\"{\\\"kind\\\":\\\"applyAudio\\\"}\",onSuccess:()=>{},onFailure:()=>{}});",browser.getURL(),0);browser.executeJavaScript("window.canvasApply&&window.canvasApply("+JSON.toJson(copy)+");",browser.getURL(),0);}
    private static void applyAudio(){
        boolean ok=nativeAudio&&audio.apply(audioAllowed);JsonObject e=new JsonObject();e.addProperty("kind","audioGate");e.addProperty("allowed",audioAllowed);e.addProperty("nativeAudio",nativeAudio);e.addProperty("blocked",nativeAudio?!audioAllowed:SILENT||PROCESS_GATE.equals("blocked"));e.addProperty("silentTest",SILENT);emit(e);
        if(nativeAudio&&!ok){event("audioFallback","A política nativa mudou; reiniciando com bloqueio por processo.");closing=true;}
        else if(!nativeAudio&&!SILENT&&!PROCESS_GATE.isEmpty()&&audioAllowed==PROCESS_GATE.equals("blocked")){event("audioRestart","Atualizando a autorização de áudio.");closing=true;}
    }
    private static String startWrapper()throws IOException{
        wrapper=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);String token="/"+UUID.randomUUID();
        String html=resource("player.html").replace("__SOURCE__",JSON.toJson(source)).replace("__KIND__",JSON.toJson(kind));byte[] bytes=html.getBytes(StandardCharsets.UTF_8);
        wrapper.createContext(token,exchange->{exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.sendResponseHeaders(200,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}});wrapper.setExecutor(Executors.newVirtualThreadPerTaskExecutor());wrapper.start();return "http://127.0.0.1:"+wrapper.getAddress().getPort()+token;
    }
    private static String resource(String name){try(var in=BrowserWorker.class.getResourceAsStream("/browser/"+name)){return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){throw new UncheckedIOException(e);}}
    private static synchronized void paint(ByteBuffer buffer,int w,int h){long now=System.nanoTime();if(now-lastPaint<33_000_000L)return;lastPaint=now;
        buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels);
        for(int i=0;i<pixels.length;i++){int p=pixels[i];pixels[i]=(p&0xff00ff00)|((p>>>16)&255)|((p&255)<<16);}
        memory.putInt(8,++sequence);var out=memory.duplicate().order(ByteOrder.LITTLE_ENDIAN);out.position(HEADER);out.asIntBuffer().put(pixels);memory.putInt(8,++sequence);
    }
    private static void event(String kind,String message){JsonObject e=new JsonObject();e.addProperty("kind",kind);e.addProperty("message",message);emit(e);}
    private static synchronized void emit(JsonObject e){try{events.write(JSON.toJson(e));events.newLine();events.flush();}catch(IOException ignored){}}
}
