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
        Tile old=TILES.get(pos);if(old!=null&&old.fingerprint.equals(fingerprint))return;
        BufferedImage bitmap=raster(state);NativeImage image=new NativeImage(size,size,true);
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)image.setPixel(x,y,bitmap.getRGB(x*256/size,y*256/size));
        Identifier id=old==null?CanvasDesk.id("surface/"+Long.toUnsignedString(pos)):old.id;
        if(old!=null){client.getTextureManager().release(old.id);cacheBytes-=old.size*old.size*4;}
        DynamicTexture texture=new DynamicTexture(()->"Canvas Desk surface",image);client.getTextureManager().register(id,texture);
        TILES.put(pos,new Tile(id,fingerprint,size));cacheBytes+=size*size*4;
        while(cacheBytes>LIMIT||TILES.size()>2048){Long first=TILES.keySet().iterator().next();Tile removed=TILES.remove(first);cacheBytes-=removed.size*removed.size*4;client.getTextureManager().release(removed.id);}
    }
    public static void clear(){Minecraft client=Minecraft.getInstance();for(Tile tile:TILES.values())client.getTextureManager().release(tile.id);TILES.clear();WANTED.clear();SEEN.clear();cacheBytes=0;GLYPHS.clear();fontLoaded=false;}

    static BufferedImage raster(JsonObject state){
        BufferedImage image=new BufferedImage(256,256,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        double tileX=state.get("tileX").getAsDouble(),tileY=state.get("tileY").getAsDouble();g.translate(-tileX,-tileY);g.setClip(new Rectangle2D.Double(tileX,tileY,256,256));
        Map<String,CanvasElementView> elements=new LinkedHashMap<>();
        for(JsonElement value:state.getAsJsonArray("elements")){CanvasElementView e=new CanvasElementView(value.getAsJsonObject());elements.put(e.id,e);}
        for(JsonElement value:state.getAsJsonArray("elements"))draw(g,value.getAsJsonObject(),elements);g.dispose();return image;
    }
    private static void draw(Graphics2D g,JsonObject e,Map<String,CanvasElementView> elements){
        double x=num(e,"x",0),y=num(e,"y",0),w=num(e,"w",100),h=num(e,"h",60);int color=(int)num(e,"color",0x30302c);
        g.setColor(new Color(color|0xff000000,true));g.setStroke(new BasicStroke((float)Math.max(.5,num(e,"thickness",3)),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        String type=e.get("type").getAsString();
        switch(type){
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
    private record Tile(Identifier id,String fingerprint,int size){}
}
