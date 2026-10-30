package br.com.murillo.canvasdesk.client.editor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CanvasViewportTest {
    private CanvasViewport viewport(int w,int h){var v=new CanvasViewport();v.left=31;v.top=20;v.right=31+w;v.bottom=20+h;return v;}
    @Test void fitsPhysicalBoundsWithEightUnitMarginsAndSharedScale(){
        for(int[] size:new int[][]{{351,186},{551,297},{702,380},{1750,950}}){
            var v=viewport(size[0],size[1]);v.fitBounds(-256,-512,256,-256);
            assertEquals(Math.min(5,Math.min((size[0]-16)/512.0,(size[1]-16)/256.0)),v.zoom,1e-9);
            assertEquals((v.left+v.right)/2.0,v.screenX(0),1e-9);assertEquals((v.top+v.bottom)/2.0,v.screenY(-384),1e-9);
            assertTrue(v.screenX(-256)>=v.left+8-1e-9);assertTrue(v.screenY(-512)>=v.top+8-1e-9);
            assertEquals(256,v.documentX(v.screenX(256)),1e-9);assertEquals(-256,v.documentY(v.screenY(-256)),1e-9);
        }
    }
    @Test void fitCanExceedFormerTwoTimesCeiling(){var v=viewport(1400,1100);v.fitBounds(0,0,256,256);assertTrue(v.zoom>2);assertTrue(v.zoom<=5);}
    @Test void hugeSurfaceNeverClipsOrJumpsToWheelMinimum(){
        var v=viewport(550,297);v.fitBounds(0,0,256000,256000);double z=v.zoom;
        assertTrue(z<.005);v.zoomAt(200,120,1.15);assertEquals(z*1.15,v.zoom,1e-12);
        v.zoomAt(200,120,1/1.15);assertEquals(z,v.zoom,1e-12);
    }
    @Test void wheelKeepsCursorPointAndManualScaleAfterBoundsShrink(){
        var v=viewport(550,297);v.fitBounds(0,0,8192,8192);double x=v.documentX(120),y=v.documentY(80);
        v.zoomAt(120,80,1.15);assertEquals(x,v.documentX(120),1e-9);assertEquals(y,v.documentY(80),1e-9);
        v.zoom=.001;v.setFitBounds(0,0,256,256);v.zoomAt(120,80,1.15);assertEquals(.00115,v.zoom,1e-12);
    }
    @Test void layoutReservesThinRailsWithoutFixedPanelCap(){
        for(int[] size:new int[][]{{427,240},{640,360},{960,540},{1920,1080}}){
            var l=CanvasEditorLayout.of(size[0],size[1]);assertEquals((int)(size[0]*.94),l.panel().w());assertEquals((int)(size[1]*.92),l.panel().h());
            assertTrue(l.panel().x()>0&&l.panel().y()>0);assertEquals(24,l.leftRail().w());
            assertEquals(l.leftRail().right(),l.canvas().x());assertEquals(l.canvas().right(),l.rightRail().x());
            assertTrue(l.canvas().w()>0&&l.canvas().h()>0);assertEquals(l.canvas().bottom(),l.status().y());
        }
        assertTrue(CanvasEditorLayout.of(1920,1080).panel().w()>800);assertTrue(CanvasEditorLayout.of(1920,1080).panel().h()>500);
    }
}
