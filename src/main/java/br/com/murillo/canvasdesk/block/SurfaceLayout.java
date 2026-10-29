package br.com.murillo.canvasdesk.block;

import com.google.gson.*;
import java.util.*;

/** A revisioned, disposable physical mask. Document objects never belong to this mask. */
public final class SurfaceLayout {
    public record Rect(double minX,double minY,double maxX,double maxY) {
        public boolean contains(double x,double y){return x>=minX&&x<maxX&&y>=minY&&y<maxY;}
        public boolean intersects(Rect r){return minX<=r.maxX&&maxX>=r.minX&&minY<=r.maxY&&maxY>=r.minY;}
    }
    public record Tile(String id,double x,double y,int color,boolean left,boolean right,boolean up,boolean down) {
        public Rect paper(){return new Rect(x+(left?0:16),y+(up?0:16),x+256-(right?0:16),y+256-(down?0:16));}
        public Rect bounds(){return new Rect(x,y,x+256,y+256);}
    }
    private record Cell(long x,long y) {}
    public final long revision;
    public final String format;
    public final List<Tile> tiles;
    public final Rect bounds;
    private final Map<Cell,List<Tile>> index=new HashMap<>();
    private final JsonObject json;
    public SurfaceLayout(long revision,String format,List<Tile> tiles){
        this.revision=revision;this.format=format;this.tiles=List.copyOf(tiles);
        double x1=Double.POSITIVE_INFINITY,y1=x1,x2=Double.NEGATIVE_INFINITY,y2=x2;
        for(Tile t:tiles){x1=Math.min(x1,t.x);y1=Math.min(y1,t.y);x2=Math.max(x2,t.x+256);y2=Math.max(y2,t.y+256);
            for(long x=cell(t.x);x<=cell(t.x+255.999);x++)for(long y=cell(t.y);y<=cell(t.y+255.999);y++)index.computeIfAbsent(new Cell(x,y),k->new ArrayList<>()).add(t);}
        bounds=tiles.isEmpty()?new Rect(0,0,256,256):new Rect(x1,y1,x2,y2);
        json=new JsonObject();json.addProperty("revision",revision);json.addProperty("format",format);
        JsonObject b=new JsonObject();b.addProperty("minX",bounds.minX);b.addProperty("minY",bounds.minY);b.addProperty("maxX",bounds.maxX);b.addProperty("maxY",bounds.maxY);json.add("bounds",b);
        JsonArray a=new JsonArray();for(Tile t:tiles){JsonObject o=new JsonObject();o.addProperty("id",t.id);o.addProperty("x",t.x);o.addProperty("y",t.y);o.addProperty("color",t.color);o.addProperty("left",t.left);o.addProperty("right",t.right);o.addProperty("up",t.up);o.addProperty("down",t.down);a.add(o);}json.add("modules",a);
    }
    private static long cell(double v){return (long)Math.floor(v/256);}
    public JsonObject toJson(){return json;}
    public static SurfaceLayout fromJson(JsonObject o){
        List<Tile> tiles=new ArrayList<>();for(JsonElement e:o.getAsJsonArray("modules")){JsonObject t=e.getAsJsonObject();tiles.add(new Tile(t.get("id").getAsString(),t.get("x").getAsDouble(),t.get("y").getAsDouble(),t.get("color").getAsInt(),t.get("left").getAsBoolean(),t.get("right").getAsBoolean(),t.get("up").getAsBoolean(),t.get("down").getAsBoolean()));}
        return new SurfaceLayout(o.get("revision").getAsLong(),o.get("format").getAsString(),tiles);
    }
    public boolean contains(double x,double y){for(Tile t:index.getOrDefault(new Cell(cell(x),cell(y)),List.of()))if(t.paper().contains(x,y))return true;return false;}
    public List<Tile> query(Rect r){
        if(!bounds.intersects(r))return List.of();
        long x1=cell(r.minX),x2=cell(r.maxX),y1=cell(r.minY),y2=cell(r.maxY);
        Collection<Tile> candidates;
        if((double)(x2-x1+1)*(y2-y1+1)>Math.max(4,tiles.size()*2))candidates=tiles;
        else{Set<Tile> result=new LinkedHashSet<>();for(long x=x1;x<=x2;x++)for(long y=y1;y<=y2;y++)result.addAll(index.getOrDefault(new Cell(x,y),List.of()));candidates=result;}
        List<Tile> result=new ArrayList<>();for(Tile t:candidates)if(t.bounds().intersects(r))result.add(t);return result;
    }
    public boolean intersects(Rect r){for(Tile t:query(r))if(t.paper().intersects(r))return true;return false;}
    public boolean covers(Rect r){
        double area=Math.max(0,r.maxX-r.minX)*Math.max(0,r.maxY-r.minY),covered=0;
        if(area==0)return contains(r.minX,r.minY);
        for(Tile t:query(r)){Rect p=t.paper();covered+=Math.max(0,Math.min(r.maxX,p.maxX)-Math.max(r.minX,p.minX))*Math.max(0,Math.min(r.maxY,p.maxY)-Math.max(r.minY,p.minY));}
        return covered>=area-.001;
    }
}
