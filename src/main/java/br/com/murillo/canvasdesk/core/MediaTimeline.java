package br.com.murillo.canvasdesk.core;

import com.google.gson.JsonObject;

/** Transient monotonic timeline; playback never writes document history or the durable journal. */
public final class MediaTimeline {
    public final String source;
    public final String session=java.util.UUID.randomUUID().toString();
    private String navigationUrl;
    private long revision,baseMillis,navigationRevision;
    private double position,scrollX,scrollY;
    private boolean playing;
    public MediaTimeline(String source,long now){this.source=EmbedSource.validate(source);navigationUrl=source;baseMillis=now;}
    public long revision(){return revision;}
    public double position(long now){return position+(playing?Math.max(0,now-baseMillis)/1000.0:0);}
    public void command(String command,JsonObject request,long expected,long now){
        if(expected!=revision)throw new IllegalArgumentException("Outro jogador alterou a reprodução. Estado atualizado.");
        double at=position(now);
        switch(command){
            case "play"->{if(request.has("position"))at=bounded(request,"position",0,31_536_000);playing=true;}
            case "pause"->{if(request.has("position"))at=bounded(request,"position",0,31_536_000);playing=false;}
            case "toggle"->playing=!playing;
            case "seek"->at=bounded(request,"position",0,31_536_000);
            case "navigate"->{navigationUrl=EmbedSource.validate(request.get("url").getAsString());scrollX=scrollY=0;navigationRevision++;}
            case "scroll"->{double sx=bounded(request,"scrollX",0,1),sy=bounded(request,"scrollY",0,1);scrollX=sx;scrollY=sy;}
            default->throw new IllegalArgumentException("Controle de mídia inválido.");
        }
        position=at;baseMillis=now;revision++;
    }
    private static double bounded(JsonObject r,String field,double min,double max){double n=r.get(field).getAsDouble();if(!Double.isFinite(n)||n<min||n>max)throw new IllegalArgumentException("Valor inválido: "+field);return n;}
    public JsonObject snapshot(long now){JsonObject s=new JsonObject();s.addProperty("mediaSession",session);s.addProperty("source",source);s.addProperty("navigationUrl",navigationUrl);s.addProperty("mediaRevision",revision);s.addProperty("navigationRevision",navigationRevision);s.addProperty("position",position(now));s.addProperty("playing",playing);s.addProperty("scrollX",scrollX);s.addProperty("scrollY",scrollY);s.addProperty("serverTime",now);return s;}
}
