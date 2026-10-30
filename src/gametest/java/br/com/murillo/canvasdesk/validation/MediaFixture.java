package br.com.murillo.canvasdesk.validation;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Local deterministic content; no browser runtime dependency is downloaded during a smoke. */
final class MediaFixture implements AutoCloseable {
    private final HttpServer server;
    private MediaFixture(HttpServer server){this.server=server;}
    static MediaFixture start(Path root)throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);Path local=root.resolve(".codex-temp/media-fixture/long.webm");byte[] video=Files.readAllBytes(Files.exists(local)?local:root.resolve(".codex-temp/media-fixture/clip.webm"));
        server.createContext("/",e->{String path=e.getRequestURI().getPath();byte[] data=(path.endsWith("webm")||path.equals("/stream"))?video:("<!doctype html><meta charset=utf-8><style>body{background:#dde8ee;color:#213244;font:32px sans-serif}section{height:1600px;background:linear-gradient(#dde8ee,#d8e8b0)}</style><h1>Canvas Desk · "+path+"</h1><form action='/form' method='get'><input name='private' placeholder='Digitação local' style='position:absolute;left:20px;top:140px;width:300px;height:36px;font-size:22px'><button style='position:absolute;left:340px;top:140px;height:36px'>Enviar</button></form><a style='position:absolute;left:20px;top:230px' href='/next'>Próxima página</a><textarea id=probe style='position:absolute;left:20px;top:300px;width:500px;height:120px'></textarea><section>Rolagem compartilhada</section><p>Fim</p>").getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type",(path.endsWith("webm")||path.equals("/stream"))?"video/webm":"text/html; charset=utf-8");e.getResponseHeaders().set("Accept-Ranges","bytes");int start=0,end=data.length-1,code=200;String range=e.getRequestHeaders().getFirst("Range");if(range!=null&&range.startsWith("bytes=")){String[] bounds=range.substring(6).split("-",2);try{start=Integer.parseInt(bounds[0]);if(bounds.length>1&&!bounds[1].isEmpty())end=Math.min(end,Integer.parseInt(bounds[1]));if(start<0||start>end){e.sendResponseHeaders(416,-1);e.close();return;}code=206;e.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+data.length);}catch(NumberFormatException bad){e.sendResponseHeaders(416,-1);e.close();return;}}int count=end-start+1;e.getResponseHeaders().set("Content-Length",Integer.toString(count));e.sendResponseHeaders(code,e.getRequestMethod().equals("HEAD")?-1:count);try(var out=e.getResponseBody()){if(!e.getRequestMethod().equals("HEAD"))out.write(data,start,count);}});server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();return new MediaFixture(server);
    }
    String url(String path){return "http://127.0.0.1:"+server.getAddress().getPort()+"/"+path;}
    public void close(){server.stop(0);}
}
