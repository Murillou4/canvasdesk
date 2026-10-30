package br.com.murillo.canvasdesk.block;

import java.util.*;

/** Exposed module edges only, merged once per topology revision. Document units, no world queries. */
public final class SurfaceContour {
    public record Segment(double x1,double y1,double x2,double y2) {}
    private record Axis(boolean horizontal,double fixed) {}
    private SurfaceContour() {}
    public static List<Segment> of(SurfaceLayout surface){
        Map<Axis,List<double[]>> edges=new LinkedHashMap<>();
        for(var t:surface.tiles){
            if(!t.up())edge(edges,true,t.y(),t.x(),t.x()+256);
            if(!t.down())edge(edges,true,t.y()+256,t.x(),t.x()+256);
            if(!t.left())edge(edges,false,t.x(),t.y(),t.y()+256);
            if(!t.right())edge(edges,false,t.x()+256,t.y(),t.y()+256);
        }
        List<Segment> result=new ArrayList<>();
        edges.forEach((axis,ranges)->{
            ranges.sort(Comparator.comparingDouble(a->a[0]));double start=Double.NaN,end=0;
            for(double[] r:ranges){if(Double.isNaN(start)){start=r[0];end=r[1];}else if(r[0]<=end+1e-6)end=Math.max(end,r[1]);else{result.add(segment(axis,start,end));start=r[0];end=r[1];}}
            if(!Double.isNaN(start))result.add(segment(axis,start,end));
        });return List.copyOf(result);
    }
    private static void edge(Map<Axis,List<double[]>> edges,boolean horizontal,double fixed,double a,double b){edges.computeIfAbsent(new Axis(horizontal,fixed),k->new ArrayList<>()).add(new double[]{a,b});}
    private static Segment segment(Axis a,double start,double end){return a.horizontal?new Segment(start,a.fixed,end,a.fixed):new Segment(a.fixed,start,a.fixed,end);}
}
