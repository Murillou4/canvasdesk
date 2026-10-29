package br.com.murillo.canvasdesk.block;

import com.google.gson.*;

/** Ink visibility, rather than only a bounding box, determines whether a shape can be interacted with. */
public final class ElementFootprint {
    private ElementFootprint(){}
    public static boolean visible(SurfaceLayout surface,JsonObject e){
        double x=n(e,"x",0),y=n(e,"y",0),w=Math.max(1,n(e,"w",1)),h=Math.max(1,n(e,"h",1)),radius=Math.max(.5,n(e,"thickness",3)/2);
        String type=e.get("type").getAsString();
        for(var tile:surface.query(new SurfaceLayout.Rect(x-radius,y-radius,x+w+radius,y+h+radius))){
            var p=tile.paper();var r=new SurfaceLayout.Rect(p.minX()-radius,p.minY()-radius,p.maxX()+radius,p.maxY()+radius);
            if(type.equals("STROKE")||type.equals("LINE")||type.equals("ARROW")||type.equals("CONNECTOR")){
                JsonArray points=e.getAsJsonArray("points");
                if(points!=null&&!points.isEmpty()){
                    JsonObject previous=points.get(0).getAsJsonObject();
                    for(JsonElement point:points){JsonObject next=point.getAsJsonObject();if(segment(r,n(previous,"x",0),n(previous,"y",0),n(next,"x",0),n(next,"y",0)))return true;previous=next;}
                }else if(segment(r,x,y,x+w,y+h))return true;
            }else if(type.equals("RECTANGLE")){
                if(segment(r,x,y,x+w,y)||segment(r,x+w,y,x+w,y+h)||segment(r,x+w,y+h,x,y+h)||segment(r,x,y+h,x,y))return true;
            }else if(type.equals("ELLIPSE")){
                double cx=x+w/2,cy=y+h/2;
                double nearX=Math.clamp(cx,r.minX(),r.maxX()),nearY=Math.clamp(cy,r.minY(),r.maxY());
                double farX=Math.max(Math.abs(cx-r.minX()),Math.abs(cx-r.maxX())),farY=Math.max(Math.abs(cy-r.minY()),Math.abs(cy-r.maxY()));
                if(sq((nearX-cx)/(w/2))+sq((nearY-cy)/(h/2))<=1&&sq(farX/(w/2))+sq(farY/(h/2))>=1)return true;
            }else if(p.intersects(new SurfaceLayout.Rect(x,y,x+w,y+h)))return true;
        }return false;
    }
    private static double n(JsonObject e,String k,double fallback){return e.has(k)?e.get(k).getAsDouble():fallback;}
    private static double sq(double x){return x*x;}
    private static boolean segment(SurfaceLayout.Rect r,double x1,double y1,double x2,double y2){
        double t0=0,t1=1,dx=x2-x1,dy=y2-y1;
        double[] p={-dx,dx,-dy,dy},q={x1-r.minX(),r.maxX()-x1,y1-r.minY(),r.maxY()-y1};
        for(int i=0;i<4;i++){if(p[i]==0){if(q[i]<0)return false;}else{double t=q[i]/p[i];if(p[i]<0)t0=Math.max(t0,t);else t1=Math.min(t1,t);if(t0>t1)return false;}}
        return true;
    }
}
