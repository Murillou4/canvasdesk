package br.com.murillo.canvasdesk.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EmbedGeometryTest {
    @Test void wideCardLetterboxesWithoutStretching(){var r=EmbedGeometry.fit(256,16,320,90);assertEquals(336,r.x());assertEquals(16,r.y());assertEquals(160,r.w());assertEquals(90,r.h());}
    @Test void tallCardKeepsCenterAndRatioAcrossAModuleSeam(){var r=EmbedGeometry.fit(200,20,160,180);assertEquals(200,r.x());assertEquals(65,r.y());assertEquals(160,r.w());assertEquals(90,r.h());assertEquals(280,r.x()+r.w()/2);}
}
