package br.com.murillo.canvasdesk.block;

import java.util.List;
import org.junit.jupiter.api.Test;
import static br.com.murillo.canvasdesk.block.PlacementRay.*;
import static org.junit.jupiter.api.Assertions.*;

class PlacementRayTest {
    private static final Vector CENTER=new Vector(0,0,0),NORMAL=new Vector(0,0,1),RIGHT=new Vector(1,0,0),DOWN=new Vector(0,-1,0);

    @Test void wholeFaceChoosesNearestEdgeWithStableClockwiseTies() {
        assertEquals(Edge.UP,hit(.5,.5).edge());
        assertEquals(Edge.UP,hit(.2,.2).edge());
        assertEquals(Edge.RIGHT,hit(.8,.8).edge());
        assertEquals(Edge.DOWN,hit(.2,.8).edge());
        assertEquals(Edge.LEFT,hit(.1,.5).edge());
        assertTrue(hit(.5,.5).face());
    }
    @Test void acceptsEveryOrthogonalNeighborUpToItsOuterBoundary() {
        assertEquals(Edge.UP,hit(.5,-1).edge());
        assertEquals(Edge.RIGHT,hit(2,.5).edge());
        assertEquals(Edge.DOWN,hit(.5,2).edge());
        assertEquals(Edge.LEFT,hit(-1,.5).edge());
        assertFalse(hit(1.1,.5).face());
        assertEquals(Edge.UP,hit(.5,-.1).edge());
        assertNull(hit(2.001,.5));assertNull(hit(-1.001,.5));
    }
    @Test void rejectsAllDiagonalCornerRegions() {
        for(double u:new double[]{-.1,1.1})for(double v:new double[]{-.1,1.1})assertNull(hit(u,v));
    }
    @Test void equivalentLocalPointWorksOnAllVerticalAndHorizontalOrientations() {
        Vector[] normals={new Vector(0,0,1),new Vector(1,0,0),new Vector(0,0,-1),new Vector(-1,0,0)};
        Vector[] rights={new Vector(1,0,0),new Vector(0,0,-1),new Vector(-1,0,0),new Vector(0,0,1)};
        Vector center=new Vector(18.5,82.1,-7.8);
        for(int i=0;i<normals.length;i++) {
            Vector eye=center.plus(normals[i].times(3)).plus(rights[i].times(.6));
            assertEquals(Edge.RIGHT,intersect(eye,normals[i].times(-1),center,normals[i],rights[i],DOWN,4).edge());
            Vector up=new Vector(0,1,0),tableEye=center.plus(up.times(3)).plus(normals[i].times(-.6));
            assertEquals(Edge.UP,intersect(tableEye,up.times(-1),center,up,rights[i],normals[i],4).edge());
        }
    }
    @Test void honorsPhysicalPlaneTranslationAndExactEyeReach() {
        Vector center=new Vector(10,80,8.84575),eye=new Vector(10,80,11.84575);
        assertNotNull(intersect(eye,new Vector(0,0,-1),center,NORMAL,RIGHT,DOWN,3));
        assertNull(intersect(eye,new Vector(0,0,-1),center,NORMAL,RIGHT,DOWN,2.99));
        Vector diagonalEye=new Vector(.6,0,3),aim=new Vector(.1,0,0),direction=aim.minus(diagonalEye);
        double distance=Math.sqrt(direction.dot(direction));
        assertEquals(distance,intersect(diagonalEye,direction,CENTER,NORMAL,RIGHT,DOWN,distance).distance(),1e-9);
    }
    @Test void rejectsBackFacesParallelAndInvalidRays() {
        assertNull(intersect(new Vector(0,0,-3),NORMAL,CENTER,NORMAL,RIGHT,DOWN,4));
        assertNull(intersect(new Vector(0,0,3),RIGHT,CENTER,NORMAL,RIGHT,DOWN,4));
        assertNull(intersect(new Vector(0,0,3),CENTER,CENTER,NORMAL,RIGHT,DOWN,4));
        assertNull(intersect(new Vector(0,0,3),new Vector(Double.NaN,0,-1),CENTER,NORMAL,RIGHT,DOWN,4));
    }
    @Test void traversalCoversNegativeCoordinatesAndBoundaryStartsWithoutDuplicates() {
        assertEquals(List.of(new Cell(0,0,0),new Cell(1,0,0),new Cell(2,0,0)),
            cells(new Vector(.5,.5,.5),RIGHT,2,20));
        assertEquals(List.of(new Cell(0,0,0),new Cell(-1,0,0),new Cell(-2,0,0)),
            cells(CENTER,new Vector(-1,0,0),1.5,20));
        var diagonal=cells(new Vector(.5,.5,.5),new Vector(1,1,1),2,20);
        assertEquals(List.of(new Cell(0,0,0),new Cell(1,0,0),new Cell(1,1,0),new Cell(1,1,1)),diagonal);
        assertEquals(diagonal.size(),diagonal.stream().distinct().count());
    }
    @Test void traversalHasFiniteBudgetAndHandlesInvalidInputs() {
        assertEquals(7,cells(CENTER,RIGHT,10000,7).size());
        assertTrue(cells(CENTER,RIGHT,Double.POSITIVE_INFINITY,7).isEmpty());
        assertTrue(cells(CENTER,RIGHT,10,0).isEmpty());
        assertTrue(cells(CENTER,CENTER,10,7).isEmpty());
    }
    @Test void haloIncludesOuterBoundaryEvenWhenTheRayNeverCrossesThatAxis() {
        assertEquals(-2,haloStart(2,0));assertEquals(-2,haloStart(-2,0));
        assertEquals(-1,haloStart(2,.1));assertEquals(-1,haloStart(2.5,0));
    }
    private static Hit hit(double u,double v) {
        return intersect(new Vector(u-.5,.5-v,3),new Vector(0,0,-1),CENTER,NORMAL,RIGHT,DOWN,4);
    }
}
