package br.com.murillo.canvasdesk.block;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceContourTest {
    @Test void pairHasFourOuterEdgesAndNoSeam(){var edges=SurfaceContour.of(surface(new int[][]{{0,0},{1,0}}));assertEquals(4,edges.size());assertEquals(1536,length(edges));assertFalse(edges.stream().anyMatch(e->e.x1()==256&&e.x2()==256));}
    @Test void lShapeKeepsConcavity(){var edges=SurfaceContour.of(surface(new int[][]{{0,0},{1,0},{0,1}}));assertEquals(6,edges.size());assertEquals(2048,length(edges));assertTrue(edges.contains(new SurfaceContour.Segment(256,256,512,256)));}
    @Test void ringKeepsHoleAndOmitsInternalSeams(){List<int[]> cells=new ArrayList<>();for(int x=0;x<3;x++)for(int y=0;y<3;y++)if(x!=1||y!=1)cells.add(new int[]{x,y});var edges=SurfaceContour.of(surface(cells.toArray(int[][]::new)));assertEquals(8,edges.size());assertEquals(4096,length(edges));}
    private static double length(List<SurfaceContour.Segment> edges){return edges.stream().mapToDouble(e->Math.hypot(e.x2()-e.x1(),e.y2()-e.y1())).sum();}
    private static SurfaceLayout surface(int[][] cells){Set<String> occupied=new HashSet<>();for(int[] p:cells)occupied.add(p[0]+","+p[1]);List<SurfaceLayout.Tile> tiles=new ArrayList<>();for(int[] p:cells){int x=p[0],y=p[1];tiles.add(new SurfaceLayout.Tile(x+","+y,x*256,y*256,0xffffff,occupied.contains((x-1)+","+y),occupied.contains((x+1)+","+y),occupied.contains(x+","+(y-1)),occupied.contains(x+","+(y+1))));}return new SurfaceLayout(1,"vertical",tiles);}
}
