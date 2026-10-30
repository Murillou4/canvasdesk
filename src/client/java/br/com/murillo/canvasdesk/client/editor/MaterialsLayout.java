package br.com.murillo.canvasdesk.client.editor;

import br.com.murillo.canvasdesk.core.MaterialItem;
import java.util.*;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Identical document-space grid in GUI, raster text and world item layers. */
public record MaterialsLayout(double height,double scale,List<FormattedCharSequence> title,List<Cell> cells) {
    public record Cell(MaterialItem item,double x,double y,double size,double quantityY) {}
    private static final Map<CanvasElementView,MaterialsLayout> CACHE=new WeakHashMap<>();
    public static void clear(){CACHE.clear();}
    public static MaterialsLayout of(Font font,CanvasElementView e){
        var cached=CACHE.get(e);if(cached!=null)return cached;
        double s=e.fontSize/9,pad=8*s,cellW=32*s,cellH=36*s;
        for(var i:e.items)cellW=Math.max(cellW,(font.width(Integer.toString(i.quantidade()))+8)*s);
        var title=font.split(Component.literal(e.text.isBlank()?"Materiais":e.text),Math.max(1,(int)((e.w-2*pad)/s)));
        double top=pad+Math.max(1,title.size())*12*s+4*s;int columns=Math.max(1,(int)((e.w-2*pad)/cellW));
        List<Cell> cells=new ArrayList<>();
        for(int i=0;i<e.items.size();i++){double x=pad+(i%columns)*cellW+(cellW-20*s)/2,y=top+(i/columns)*cellH;cells.add(new Cell(e.items.get(i),x,y,20*s,y+23*s));}
        var result=new MaterialsLayout(top+Math.max(1,(e.items.size()+columns-1)/columns)*cellH+pad,s,List.copyOf(title),List.copyOf(cells));CACHE.put(e,result);return result;
    }
}
