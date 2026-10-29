package br.com.murillo.canvasdesk.block;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceLayoutTest {
    @Test void expansionRevealsTheSeamWithoutChangingDocumentCoordinates(){
        var single=new SurfaceLayout(1,"vertical",List.of(tile("a",0,0,false,false,false,false)));
        var expanded=new SurfaceLayout(2,"vertical",List.of(tile("a",0,0,false,true,false,false),tile("b",256,0,true,false,false,false)));
        assertFalse(single.contains(250,100));assertTrue(expanded.contains(250,100));assertTrue(expanded.contains(260,100));
        assertTrue(single.contains(100,100));assertTrue(expanded.contains(100,100));assertEquals(0,expanded.tiles.getFirst().x());
        assertEquals(512,expanded.bounds.maxX());assertFalse(expanded.contains(505,100));
    }
    @Test void holesAndExteriorStayOutsideTheEditableMask(){
        var surface=new SurfaceLayout(7,"vertical",List.of(tile("a",0,0,false,true,false,true),tile("b",256,0,true,false,false,false),tile("c",0,256,false,false,true,false)));
        assertTrue(surface.contains(250,100));assertTrue(surface.contains(100,250));assertFalse(surface.contains(350,350));
        assertFalse(surface.contains(-1,100));assertFalse(surface.contains(2,100));
        assertTrue(surface.intersects(new SurfaceLayout.Rect(200,200,400,400)));
        assertFalse(surface.intersects(new SurfaceLayout.Rect(300,300,400,400)));
    }
    @Test void transportPreservesColorsFormatAndNegativeCoordinates(){
        var surface=new SurfaceLayout(3,"horizontal",List.of(new SurfaceLayout.Tile("a",-256,-512,0x123456,false,false,false,false)));
        var restored=SurfaceLayout.fromJson(surface.toJson());assertEquals(surface.tiles,restored.tiles);assertEquals("horizontal",restored.format);
        assertEquals(3,restored.revision);assertTrue(restored.contains(-200,-450));
    }
    @Test @Timeout(2) void zoomedOutQueriesStayBoundedForDistantCoordinates(){
        var surface=new SurfaceLayout(1,"vertical",List.of(tile("a",0,0,false,false,false,false)));
        assertEquals(1,surface.query(new SurfaceLayout.Rect(-1e15,-1e15,1e15,1e15)).size());
    }
    @Test void invisibleOutlinesDoNotBecomeSelectableThroughTheirEmptyInterior(){
        var surface=new SurfaceLayout(1,"vertical",List.of(tile("a",0,0,false,false,false,false)));
        var shape=new com.google.gson.JsonObject();shape.addProperty("type","RECTANGLE");shape.addProperty("x",-100);shape.addProperty("y",-100);shape.addProperty("w",456);shape.addProperty("h",456);
        assertFalse(ElementFootprint.visible(surface,shape));shape.addProperty("type","ELLIPSE");assertFalse(ElementFootprint.visible(surface,shape));
        shape.addProperty("type","LINE");assertTrue(ElementFootprint.visible(surface,shape));
        assertTrue(surface.covers(new SurfaceLayout.Rect(30,30,200,200)));assertFalse(surface.covers(new SurfaceLayout.Rect(0,0,200,200)));
    }
    private static SurfaceLayout.Tile tile(String id,double x,double y,boolean left,boolean right,boolean up,boolean down){return new SurfaceLayout.Tile(id,x,y,0xfafaf7,left,right,up,down);}
}
