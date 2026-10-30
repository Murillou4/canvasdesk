package br.com.murillo.canvasdesk.client.editor;

import java.util.Collection;

/** The editor and table use the same document units: 256 units per physical module. */
public final class CanvasViewport {
    public double panX, panY, zoom = 1;
    public int left, top, right, bottom;
    private double minimumZoom = .005, fitZoom = 1;

    public double documentX(double screenX) { return (screenX - left) / zoom + panX; }
    public double documentY(double screenY) { return (screenY - top) / zoom + panY; }
    public double screenX(double documentX) { return left + (documentX - panX) * zoom; }
    public double screenY(double documentY) { return top + (documentY - panY) * zoom; }
    public boolean contains(double x, double y) { return x >= left && x < right && y >= top && y < bottom; }

    public void zoomAt(double mouseX, double mouseY, double factor) {
        double beforeX = documentX(mouseX), beforeY = documentY(mouseY);
        zoom = Math.clamp(zoom * factor, Math.min(minimumZoom, zoom), 5);
        panX = beforeX - (mouseX - left) / zoom;
        panY = beforeY - (mouseY - top) / zoom;
    }

    public void fit(Collection<CanvasElementView> elements, double fallbackX, double fallbackY) {
        double x1 = fallbackX, y1 = fallbackY, x2 = fallbackX + 256, y2 = fallbackY + 256;
        if (!elements.isEmpty()) {
            x1 = Double.POSITIVE_INFINITY; y1 = x1; x2 = Double.NEGATIVE_INFINITY; y2 = x2;
            for (CanvasElementView e : elements) {
                x1 = Math.min(x1, e.x); y1 = Math.min(y1, e.y);
                x2 = Math.max(x2, e.x + e.w); y2 = Math.max(y2, e.y + e.h);
            }
        }
        fitBounds(x1,y1,x2,y2);
    }
    public void setFitBounds(double x1,double y1,double x2,double y2){
        fitZoom=Math.min(Math.max(1,right-left-16)/Math.max(1,x2-x1),Math.max(1,bottom-top-16)/Math.max(1,y2-y1));
        minimumZoom=Math.min(.005,fitZoom);
    }
    public void fitBounds(double x1,double y1,double x2,double y2){
        setFitBounds(x1,y1,x2,y2);zoom=Math.min(5,fitZoom);
        panX=(x1+x2)/2-(right-left)/(2*zoom);panY=(y1+y2)/2-(bottom-top)/(2*zoom);
    }
}
