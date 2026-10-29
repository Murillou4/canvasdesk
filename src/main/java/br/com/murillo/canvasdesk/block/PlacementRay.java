package br.com.murillo.canvasdesk.block;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry: no world access, client classes or Minecraft bootstrap. */
final class PlacementRay {
    private static final double EPSILON=1.0e-8;
    enum Edge { UP,RIGHT,DOWN,LEFT }
    record Vector(double x,double y,double z) {
        double dot(Vector v){return x*v.x+y*v.y+z*v.z;}
        Vector minus(Vector v){return new Vector(x-v.x,y-v.y,z-v.z);}
        Vector plus(Vector v){return new Vector(x+v.x,y+v.y,z+v.z);}
        Vector times(double amount){return new Vector(x*amount,y*amount,z*amount);}
        boolean finite(){return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z);}
        Vector unit(){double size=Math.sqrt(dot(this));return size>EPSILON&&Double.isFinite(size)?times(1/size):null;}
    }
    record Hit(double distance,double u,double v,Edge edge,boolean face,double edgeDistance) {}
    record Cell(int x,int y,int z) {}
    private PlacementRay() {}

    // A ray lying exactly on a cell boundary also touches the lower cell's exterior neighbor.
    static int haloStart(double coordinate,double direction) {
        return Math.abs(direction)<EPSILON&&Math.abs(coordinate-Math.rint(coordinate))<EPSILON?-2:-1;
    }

    static Hit intersect(Vector eye,Vector direction,Vector center,Vector normal,Vector right,Vector down,double range) {
        if(!eye.finite()||!center.finite()||!direction.finite()||!Double.isFinite(range)||range<=0)return null;
        Vector ray=direction.unit();if(ray==null)return null;
        double denominator=ray.dot(normal);
        if(denominator>=-EPSILON)return null;
        double distance=center.minus(eye).dot(normal)/denominator;
        if(distance<0||distance>range+EPSILON)return null;
        Vector relative=eye.plus(ray.times(distance)).minus(center);
        double u=.5+relative.dot(right),v=.5+relative.dot(down);
        boolean insideU=u>=0&&u<=1,insideV=v>=0&&v<=1;
        if(insideU&&insideV) {
            double[] distances={v,1-u,1-v,u};int nearest=0;
            for(int i=1;i<distances.length;i++)if(distances[i]<distances[nearest]-EPSILON)nearest=i;
            return new Hit(distance,u,v,Edge.values()[nearest],true,distances[nearest]);
        }
        // Full orthogonal neighboring cells; never attach from the diagonal corners.
        if(insideU&&v>=-1&&v<0)return new Hit(distance,u,v,Edge.UP,false,-v);
        if(insideV&&u>1&&u<=2)return new Hit(distance,u,v,Edge.RIGHT,false,u-1);
        if(insideU&&v>1&&v<=2)return new Hit(distance,u,v,Edge.DOWN,false,v-1);
        if(insideV&&u>=-1&&u<0)return new Hit(distance,u,v,Edge.LEFT,false,-u);
        return null;
    }

    /** Bounded Amanatides/Woo traversal, including negative and exactly-on-boundary starts. */
    static List<Cell> cells(Vector start,Vector direction,double range,int budget) {
        List<Cell> cells=new ArrayList<>();
        if(budget<=0||!start.finite()||!direction.finite()||!Double.isFinite(range)||range<0)return cells;
        Vector ray=direction.unit();if(ray==null)return cells;
        int x=(int)Math.floor(start.x),y=(int)Math.floor(start.y),z=(int)Math.floor(start.z);
        int sx=ray.x>0?1:ray.x<0?-1:0,sy=ray.y>0?1:ray.y<0?-1:0,sz=ray.z>0?1:ray.z<0?-1:0;
        double tx=firstBoundary(start.x,x,ray.x,sx),ty=firstBoundary(start.y,y,ray.y,sy),tz=firstBoundary(start.z,z,ray.z,sz);
        double dx=sx==0?Double.POSITIVE_INFINITY:Math.abs(1/ray.x);
        double dy=sy==0?Double.POSITIVE_INFINITY:Math.abs(1/ray.y);
        double dz=sz==0?Double.POSITIVE_INFINITY:Math.abs(1/ray.z);
        cells.add(new Cell(x,y,z));
        while(cells.size()<budget) {
            double next=Math.min(tx,Math.min(ty,tz));if(next>range+EPSILON)break;
            if(tx<=ty&&tx<=tz){x+=sx;tx+=dx;}
            else if(ty<=tz){y+=sy;ty+=dy;}
            else {z+=sz;tz+=dz;}
            cells.add(new Cell(x,y,z));
        }
        return cells;
    }
    private static double firstBoundary(double coordinate,int cell,double direction,int step) {
        return step==0?Double.POSITIVE_INFINITY:(cell+(step>0?1:0)-coordinate)/direction;
    }
}
