package br.com.murillo.canvasdesk.core;

/** The fixed OSR viewport keeps its aspect ratio in both document and world coordinates. */
public final class EmbedGeometry {
    public record Rect(double x,double y,double w,double h) {
        public boolean contains(double px,double py){return px>=x&&px<x+w&&py>=y&&py<y+h;}
    }
    private EmbedGeometry(){}
    public static Rect fit(double x,double y,double w,double h){double width=Math.max(0,Math.min(w,Math.max(0,h)*16/9)),height=width*9/16;return new Rect(x+(w-width)/2,y+(h-height)/2,width,height);}
}
