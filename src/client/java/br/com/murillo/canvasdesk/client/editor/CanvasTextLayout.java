package br.com.murillo.canvasdesk.client.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.gui.components.MultilineTextField;

/** Layout is measured once in document units by all three consumers: GUI, tiles and hit targets. */
public record CanvasTextLayout(double scale, double padding, double boxSize, double textX,
                               double width,double height, List<Line> lines, List<Check> checks) {
    public record Line(FormattedCharSequence value,double y,int start,int end) {}
    public record Check(int index,double y) {}
    private static final Map<CanvasElementView,CanvasTextLayout> CACHE=new WeakHashMap<>();
    public static void clear(){CACHE.clear();}
    public static CanvasTextLayout of(Font font,CanvasElementView e) {
        CanvasTextLayout cached=CACHE.get(e);if(cached!=null)return cached;
        double scale=e.fontSize/9, pad=e.type.equals("TEXT")?0:9*scale;
        boolean checklist=e.type.equals("CHECKLIST");double textX=pad+(checklist?14*scale:0);
        int wrap=Math.max(1,(int)Math.floor((e.w-textX-pad)/scale));
        List<Line> lines=new ArrayList<>();List<Check> checks=new ArrayList<>();double y=pad;
        if(e.type.equals("TEXT")){
            CanvasTextModel model=new CanvasTextModel(font,wrap);model.setValue(e.text);
            double width=8;
            for(var line:model.lines()){
                String text=e.text.substring(line.start(),line.end());
                lines.add(new Line(Component.literal(text).getVisualOrderText(),y,line.start(),line.end()));
                width=Math.max(width,font.width(text)*scale);y+=12*scale;
            }
            CanvasTextLayout layout=new CanvasTextLayout(scale,0,8*scale,0,width,Math.max(12*scale,y),List.copyOf(lines),List.of());CACHE.put(e,layout);return layout;
        }
        String[] rows=e.text.split("\n",-1);
        for(int i=0;i<rows.length;i++) {
            if(checklist)checks.add(new Check(i,y));
            var wrapped=font.split(Component.literal(rows[i]),wrap);
            if(wrapped.isEmpty())y+=12*scale;
            else for(var line:wrapped){lines.add(new Line(line,y,0,0));y+=12*scale;}
        }
        CanvasTextLayout layout=new CanvasTextLayout(scale,pad,8*scale,textX,e.w,y+pad,List.copyOf(lines),List.copyOf(checks));CACHE.put(e,layout);return layout;
    }
    public int checkAt(double x,double y) {
        double hit=Math.max(boxSize,12*scale);
        if(x<padding-2*scale||x>padding+hit)return -1;
        for(Check check:checks)if(y>=check.y-2*scale&&y<=check.y+hit)return check.index;
        return -1;
    }
}
