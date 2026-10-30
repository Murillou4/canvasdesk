package br.com.murillo.canvasdesk.client.render;

import br.com.murillo.canvasdesk.CanvasDesk;
import br.com.murillo.canvasdesk.client.CanvasDeskClient;
import br.com.murillo.canvasdesk.client.editor.CanvasElementView;
import br.com.murillo.canvasdesk.client.editor.CanvasPainter;
import br.com.murillo.canvasdesk.client.editor.CanvasTextLayout;
import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** Raster tiles are disposable views of the vector document, never the saved source. */
public final class SurfaceCache {
    private static final int LIMIT=32*1024*1024;
    private static final LinkedHashMap<Long,Tile> TILES=new LinkedHashMap<>(32,.75F,true);
    private static final LinkedHashMap<Long,Long> WANTED=new LinkedHashMap<>();
    private static final Map<Long,Integer> SEEN=new HashMap<>();
    private static int cacheBytes;
    private static final Map<Integer,Glyph> GLYPHS=new HashMap<>();
    private static boolean fontLoaded;
    private static int tick;
    public record Checkbox(CanvasElementView element,int row,double x,double y,double size) {}
    public record InteractionTile(String moduleId,String workspace,double x,double y,boolean editable,
                                  List<CanvasElementView> elements,Map<String,CanvasElementView> byId) {}
    public record Layer(Identifier texture,String item,String embed,double x,double y,double w,double h) {}
    public static List<Layer> layers(BlockPos pos){Tile t=TILES.get(pos.asLong());if(t==null)return List.of();
        for(var layer:t.layers)if(layer.embed!=null){var e=t.interaction.byId.get(layer.embed);if(e!=null)br.com.murillo.canvasdesk.client.media.BrowserTexture.visible(t.interaction.workspace,e,pos,t.interaction.moduleId);}
        return t.layers;
    }
    public static InteractionTile interaction(BlockPos pos){Tile t=TILES.get(pos.asLong());return t==null?null:t.interaction;}
    public static void request(BlockPos pos){WANTED.put(pos.asLong(),0L);SEEN.put(pos.asLong(),tick);}
    /** Same font layout as rasterisation; cached by immutable element, never split text while rendering. */
    public static Checkbox checkbox(InteractionTile tile,double x,double y){
        var e=topElement(tile,x,y);if(e==null||!e.type.equals("CHECKLIST"))return null;
        var l=CanvasTextLayout.of(Minecraft.getInstance().font,e);int row=l.checkAt(x-e.x,y-e.y);
        for(var c:l.checks())if(c.index()==row)return new Checkbox(e,row,e.x+l.padding(),e.y+c.y(),l.boxSize());return null;
    }
    public static CanvasElementView topElement(InteractionTile tile,double x,double y){
        for(int i=tile.elements.size()-1;i>=0;i--){var e=tile.elements.get(i);if(inkAt(e,tile.byId,x,y))return e;}return null;
    }
    private static boolean inkAt(CanvasElementView e,Map<String,CanvasElementView> byId,double x,double y){
        double dx=x-e.x,dy=y-e.y,t=e.thickness/2+1;
        if(Set.of("NOTE","CHECKLIST","EMBED","MATERIALS").contains(e.type))return dx>=0&&dx<=e.w&&dy>=0&&dy<=e.h;
        if(e.type.equals("TEXT")){var l=CanvasTextLayout.of(Minecraft.getInstance().font,e);return dx>=0&&dx<=l.width()&&dy>=0&&dy<=l.height();}
        if(e.type.equals("RECTANGLE"))return dx>=-t&&dx<=e.w+t&&dy>=-t&&dy<=e.h+t&&(Math.abs(dx)<=t||Math.abs(dx-e.w)<=t||Math.abs(dy)<=t||Math.abs(dy-e.h)<=t);
        if(e.type.equals("ELLIPSE")){double rx=Math.max(.5,e.w/2),ry=Math.max(.5,e.h/2);return Math.abs(Math.hypot((dx-rx)/rx,(dy-ry)/ry)-1)*Math.min(rx,ry)<=t;}
        if(e.type.equals("STROKE")){for(int j=0;j<e.points.size();j++){var a=e.points.get(Math.max(0,j-1));var b=e.points.get(j);if(segment(x,y,a.x(),a.y(),b.x(),b.y())<=t)return true;}return false;}
        double[] p=CanvasPainter.endpoints(e,byId);if(segment(x,y,p[0],p[1],p[2],p[3])<=t)return true;
        if(!e.type.equals("LINE")){double angle=Math.atan2(p[3]-p[1],p[2]-p[0]),len=Math.max(9,e.thickness*3);for(double a:new double[]{angle-.48,angle+.48})if(segment(x,y,p[2],p[3],p[2]-len*Math.cos(a),p[3]-len*Math.sin(a))<=t)return true;}return false;
    }
    private static double segment(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay,t=Math.clamp(((x-ax)*dx+(y-ay)*dy)/Math.max(1e-12,dx*dx+dy*dy),0,1);return Math.hypot(x-ax-t*dx,y-ay-t*dy);}
    private SurfaceCache(){}
    public static Identifier texture(BlockPos pos){
        long key=pos.asLong();WANTED.putIfAbsent(key,0L);SEEN.put(key,tick);Tile tile=TILES.get(key);return tile==null?null:tile.id;
    }
    public static void tick(Minecraft client){
        if(client.level==null||client.player==null)return;
        if(++tick%5!=0)return;
        long now=System.currentTimeMillis();JsonArray positions=new JsonArray(),missing=new JsonArray();
        Iterator<Map.Entry<Long,Long>> iterator=WANTED.entrySet().iterator();
        while(iterator.hasNext()){
            var entry=iterator.next();BlockPos pos=BlockPos.of(entry.getKey());
            if(tick-SEEN.getOrDefault(entry.getKey(),0)>40||client.player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>80*80){iterator.remove();SEEN.remove(entry.getKey());}
        }
        // Oldest first prevents the first 128 visible panels from starving a larger board.
        var requests=new ArrayList<>(WANTED.entrySet());requests.sort(Map.Entry.comparingByValue());
        for(var entry:requests)if(now-entry.getValue()>=1000&&positions.size()<32){
            positions.add(entry.getKey());entry.setValue(now);Tile tile=TILES.get(entry.getKey());
            if(tile==null||tile.size!=resolution(client,BlockPos.of(entry.getKey())))missing.add(entry.getKey());
        }
        if(!positions.isEmpty()){JsonObject request=new JsonObject();request.addProperty("action","tiles");request.add("positions",positions);request.add("missing",missing);CanvasDeskClient.send(request);}
    }
    private static int resolution(Minecraft client,BlockPos pos){return client.player!=null&&client.player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>16*16?64:256;}
    public static void accept(JsonObject state){
        long pos=state.get("pos").getAsLong();Minecraft client=Minecraft.getInstance();int size=resolution(client,BlockPos.of(pos));
        String fingerprint=state.getAsJsonArray("elements").toString()+state.get("tileX")+state.get("tileY")+state.get("background")+"@"+size;
        Tile old=TILES.get(pos);boolean unchanged=old!=null&&old.fingerprint.equals(fingerprint);
        List<CanvasElementView> elements;
        Map<String,CanvasElementView> byId;
        if(unchanged){elements=old.interaction.elements;byId=old.interaction.byId;}
        else{
            var list=new ArrayList<CanvasElementView>();var map=new LinkedHashMap<String,CanvasElementView>();
            for(JsonElement v:state.getAsJsonArray("elements")){var e=new CanvasElementView(v.getAsJsonObject());list.add(e);map.put(e.id,e);if(e.type.equals("CHECKLIST")||e.type.equals("TEXT"))CanvasTextLayout.of(client.font,e);}
            elements=List.copyOf(list);byId=Collections.unmodifiableMap(map);
        }
        InteractionTile interaction=new InteractionTile(state.has("moduleId")?state.get("moduleId").getAsString():"",state.get("workspace").getAsString(),state.get("tileX").getAsDouble(),state.get("tileY").getAsDouble(),state.get("editable").getAsBoolean(),elements,byId);
        if(unchanged){TILES.put(pos,new Tile(old.id,fingerprint,size,interaction,old.layers,old.textures));return;}
        if(old!=null){release(old);cacheBytes-=old.textures.size()*old.size*old.size*4;}
        List<Layer> layers=new ArrayList<>();List<Identifier> textures=new ArrayList<>();JsonArray pending=new JsonArray();
        for(var e:elements){pending.add(e.json);
            if(e.type.equals("MATERIALS")||e.type.equals("EMBED")){
                rasterLayer(state,pending,pos,size,layers,textures);pending=new JsonArray();
                if(e.type.equals("EMBED")){var r=br.com.murillo.canvasdesk.core.EmbedGeometry.fit(e.x,e.y,e.w,e.h);layers.add(new Layer(null,null,e.id,r.x(),r.y(),r.w(),r.h()));}
                else for(var cell:br.com.murillo.canvasdesk.client.editor.MaterialsLayout.of(client.font,e).cells())layers.add(new Layer(null,cell.item().id(),null,e.x+cell.x(),e.y+cell.y(),cell.size(),cell.size()));
            }
        }
        if(!pending.isEmpty()||textures.isEmpty())rasterLayer(state,pending,pos,size,layers,textures);
        Tile tile=new Tile(textures.getFirst(),fingerprint,size,interaction,List.copyOf(layers),List.copyOf(textures));TILES.put(pos,tile);cacheBytes+=textures.size()*size*size*4;
        while(cacheBytes>LIMIT||TILES.size()>2048){Long first=TILES.keySet().iterator().next();Tile removed=TILES.remove(first);cacheBytes-=removed.textures.size()*removed.size*removed.size*4;release(removed);}
    }
    private static void release(Tile tile){for(var id:tile.textures)Minecraft.getInstance().getTextureManager().release(id);}
    private static void rasterLayer(JsonObject state,JsonArray elements,long pos,int size,List<Layer> layers,List<Identifier> textures){
        BufferedImage bitmap=raster(state,elements);NativeImage image=new NativeImage(size,size,true);for(int y=0;y<size;y++)for(int x=0;x<size;x++)image.setPixel(x,y,bitmap.getRGB(x*256/size,y*256/size));
        Identifier id=CanvasDesk.id("surface/"+Long.toUnsignedString(pos)+"/"+textures.size());Minecraft.getInstance().getTextureManager().register(id,new DynamicTexture(()->"Canvas Desk vectors",image));textures.add(id);
        layers.add(new Layer(id,null,null,state.get("tileX").getAsDouble(),state.get("tileY").getAsDouble(),256,256));
    }
    public static void clear(){for(Tile tile:TILES.values())release(tile);TILES.clear();WANTED.clear();SEEN.clear();cacheBytes=0;GLYPHS.clear();fontLoaded=false;}

    static BufferedImage raster(JsonObject state){
        return raster(state,state.getAsJsonArray("elements"));
    }
    private static BufferedImage raster(JsonObject state,JsonArray drawing){
        BufferedImage image=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        double tileX=state.get("tileX").getAsDouble(),tileY=state.get("tileY").getAsDouble();g.translate(-tileX,-tileY);g.setClip(new Rectangle2D.Double(tileX,tileY,256,256));
        Map<String,CanvasElementView> elements=new LinkedHashMap<>();
        for(JsonElement value:state.getAsJsonArray("elements")){CanvasElementView e=new CanvasElementView(value.getAsJsonObject());elements.put(e.id,e);}
        for(JsonElement value:drawing)draw(g,value.getAsJsonObject(),elements);g.dispose();return image;
    }
    private static void draw(Graphics2D g,JsonObject e,Map<String,CanvasElementView> elements){
        double x=num(e,"x",0),y=num(e,"y",0),w=num(e,"w",100),h=num(e,"h",60);int color=(int)num(e,"color",0x30302c);
        g.setColor(new Color(color|0xff000000,true));g.setStroke(new BasicStroke((float)Math.max(.5,num(e,"thickness",3)),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        String type=e.get("type").getAsString();
        switch(type){
            case "MATERIALS"->{
                var element=new CanvasElementView(e);var layout=br.com.murillo.canvasdesk.client.editor.MaterialsLayout.of(Minecraft.getInstance().font,element);g.setColor(new Color(0xfff7f5ec,true));g.fill(new Rectangle2D.Double(x,y,w,layout.height()));g.setColor(new Color(color|0xff000000,true));g.fill(new Rectangle2D.Double(x,y,3,layout.height()));
                double s=layout.scale(),yy=8*s;for(var line:layout.title()){StringBuilder value=new StringBuilder();line.accept((i,style,cp)->{value.appendCodePoint(cp);return true;});Graphics2D text=(Graphics2D)g.create();text.translate(x+8*s,y+yy);text.scale(s,s);text(text,value.toString(),0,0);text.dispose();yy+=12*s;}
                for(var cell:layout.cells()){String value=Integer.toString(cell.item().quantidade());Graphics2D text=(Graphics2D)g.create();text.translate(x+cell.x()+cell.size()/2,y+cell.quantityY());text.scale(s,s);text(text,value,-Minecraft.getInstance().font.width(value)/2.0,0);text.dispose();}
            }
            case "EMBED"->{g.setColor(new Color(0xffe7e2d7,true));g.fill(new Rectangle2D.Double(x,y,w,h));g.setColor(new Color(color|0xff000000,true));text(g,str(e,"text"),x+8,y+8);Path2D play=new Path2D.Double();play.moveTo(x+w/2-6,y+h/2-9);play.lineTo(x+w/2+9,y+h/2);play.lineTo(x+w/2-6,y+h/2+9);play.closePath();g.fill(play);}
            case "STROKE"->{Path2D path=new Path2D.Double();boolean start=true;for(JsonElement point:e.getAsJsonArray("points")){JsonObject p=point.getAsJsonObject();double px=num(p,"x",0),py=num(p,"y",0);if(start){path.moveTo(px,py);start=false;}else path.lineTo(px,py);}g.draw(path);if(e.getAsJsonArray("points").size()==1){JsonObject p=e.getAsJsonArray("points").get(0).getAsJsonObject();double t=num(e,"thickness",3);g.fill(new Ellipse2D.Double(num(p,"x",0)-t/2,num(p,"y",0)-t/2,t,t));}}
            case "RECTANGLE"->g.draw(new Rectangle2D.Double(x,y,w,h));
            case "ELLIPSE"->g.draw(new Ellipse2D.Double(x,y,w,h));
            case "LINE","ARROW","CONNECTOR"->{double[] ends=CanvasPainter.endpoints(new CanvasElementView(e),elements);g.draw(new Line2D.Double(ends[0],ends[1],ends[2],ends[3]));if(!type.equals("LINE")){double a=Math.atan2(ends[3]-ends[1],ends[2]-ends[0]),len=Math.max(9,num(e,"thickness",3)*3);Path2D arrow=new Path2D.Double();arrow.moveTo(ends[2]-len*Math.cos(a-.48),ends[3]-len*Math.sin(a-.48));arrow.lineTo(ends[2],ends[3]);arrow.lineTo(ends[2]-len*Math.cos(a+.48),ends[3]-len*Math.sin(a+.48));g.draw(arrow);}}
            case "NOTE","CHECKLIST","TEXT"->textBox(g,e,type,x,y,w,h,color);
            default->{}
        }
    }
    private static String str(JsonObject e,String key){return e.has(key)?e.get(key).getAsString():"";}
    private static double num(JsonObject e,String key,double fallback){return e.has(key)?e.get(key).getAsDouble():fallback;}
    private static void textBox(Graphics2D g,JsonObject e,String type,double x,double y,double w,double h,int color){
        if(!type.equals("TEXT")){
            int r=(color>>16)&255,green=(color>>8)&255,b=color&255;
            int tinted=0xff000000|((int)(r*.17+255*.83)<<16)|((int)(green*.17+255*.83)<<8)|(int)(b*.17+255*.83);
            g.setColor(new Color(type.equals("NOTE")?tinted:0xfff7f5ec,true));g.fill(new Rectangle2D.Double(x,y,w,h));
            g.setColor(new Color(color|0xff000000,true));g.fill(new Rectangle2D.Double(x,y,3,h));
        }
        g.setColor(new Color(color|0xff000000,true));
        CanvasElementView element=new CanvasElementView(e);CanvasTextLayout layout=CanvasTextLayout.of(Minecraft.getInstance().font,element);
        double s=layout.scale();
        for(var check:layout.checks()) {
            double xx=x+layout.padding(),yy=y+check.y(),b=layout.boxSize();g.setStroke(new BasicStroke((float)s));g.draw(new Rectangle2D.Double(xx,yy,b,b));
            if(check.index()<element.checks.size()&&element.checks.get(check.index())){g.setStroke(new BasicStroke((float)(1.4*s)));g.draw(new Line2D.Double(xx+s,yy+4*s,xx+4*s,yy+7*s));g.draw(new Line2D.Double(xx+4*s,yy+7*s,xx+8*s,yy+s));}
        }
        for(var visual:layout.lines()) {
            var clip=g.getClipBounds();if(clip!=null&&(y+visual.y()+12*s<clip.getMinY()||y+visual.y()>clip.getMaxY()))continue;
            StringBuilder line=new StringBuilder();visual.value().accept((index,style,cp)->{line.appendCodePoint(cp);return true;});
            Graphics2D scaled=(Graphics2D)g.create();scaled.translate(x+layout.textX(),y+visual.y());scaled.scale(s,s);text(scaled,line.toString(),0,0);scaled.dispose();
        }
    }
    private static void text(Graphics2D g,String text,double x,double y){
        loadFont();double xx=x,yy=y;int rgba=g.getColor().getRGB();
        for(int offset=0;offset<text.length();){int cp=text.codePointAt(offset);offset+=Character.charCount(cp);
            Glyph glyph=GLYPHS.getOrDefault(cp,GLYPHS.get((int)'?'));if(glyph==null)continue;
            for(int py=0;py<glyph.image.getHeight();py++)for(int px=0;px<glyph.image.getWidth();px++)if((glyph.image.getRGB(px,py)>>>24)>64){g.setColor(new Color(rgba,true));g.fill(new Rectangle2D.Double(xx+px*glyph.scale,yy+(7-glyph.ascent)+py*glyph.scale,glyph.scale,glyph.scale));}
            xx+=Minecraft.getInstance().font.width(new String(Character.toChars(cp)));
        }
    }
    /** Read vanilla/resource-pack bitmap providers so world text uses Minecraft glyphs. */
    private static void loadFont(){if(fontLoaded)return;fontLoaded=true;loadFontDefinition(Identifier.withDefaultNamespace("font/default.json"),new HashSet<>());}
    private static void loadFontDefinition(Identifier file,Set<Identifier> seen){
        if(!seen.add(file))return;
        try(var reader=Minecraft.getInstance().getResourceManager().openAsReader(file)){
            JsonObject def=JsonParser.parseReader(reader).getAsJsonObject();
            for(JsonElement provider:def.getAsJsonArray("providers")){
                JsonObject p=provider.getAsJsonObject();String type=p.get("type").getAsString();
                if(type.endsWith("reference")){Identifier id=Identifier.parse(p.get("id").getAsString());loadFontDefinition(Identifier.fromNamespaceAndPath(id.getNamespace(),"font/"+id.getPath()+".json"),seen);}
                else if(type.endsWith("space")){for(var advance:p.getAsJsonObject("advances").entrySet())GLYPHS.putIfAbsent(advance.getKey().codePointAt(0),new Glyph(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),1,7,advance.getValue().getAsFloat()));}
                else if(type.endsWith("bitmap")){
                    Identifier id=Identifier.parse(p.get("file").getAsString());BufferedImage atlas;
                    try(var input=Minecraft.getInstance().getResourceManager().open(Identifier.fromNamespaceAndPath(id.getNamespace(),"textures/"+id.getPath()))){atlas=ImageIO.read(input);}
                    JsonArray rows=p.getAsJsonArray("chars");int columns=rows.get(0).getAsString().codePointCount(0,rows.get(0).getAsString().length());int cw=atlas.getWidth()/columns,ch=atlas.getHeight()/rows.size();float scale=(float)num(p,"height",8)/ch;
                    for(int row=0;row<rows.size();row++){int[] chars=rows.get(row).getAsString().codePoints().toArray();for(int col=0;col<chars.length;col++){if(chars[col]==0)continue;BufferedImage glyph=atlas.getSubimage(col*cw,row*ch,cw,ch);int right=0;for(int xx=0;xx<cw;xx++)for(int yy=0;yy<ch;yy++)if((glyph.getRGB(xx,yy)>>>24)>64)right=Math.max(right,xx+1);GLYPHS.putIfAbsent(chars[col],new Glyph(glyph,scale,(float)num(p,"ascent",7),right*scale+1));}}
                }
            }
        }catch(Exception e){CanvasDesk.LOGGER.debug("Font provider unavailable {}: {}",file,e.toString());}
    }
    private record Glyph(BufferedImage image,float scale,float ascent,float advance){}
    private record Tile(Identifier id,String fingerprint,int size,InteractionTile interaction,List<Layer> layers,List<Identifier> textures){}
}
