package br.com.murillo.canvasdesk.client.editor;

/** GUI-space rectangles shared by painting, widgets, hit testing and camera framing. */
public record CanvasEditorLayout(Rect panel, Rect header, Rect leftRail, Rect rightRail,
                                 Rect canvas, Rect status, Rect title) {
    public record Rect(int x, int y, int w, int h) {
        public int right() { return x + w; }
        public int bottom() { return y + h; }
        public boolean contains(double px, double py) { return px >= x && px < right() && py >= y && py < bottom(); }
    }
    public static CanvasEditorLayout of(int width, int height) {
        int w = Math.max(1, Math.min(width - 8, (int)(width * .94)));
        int h = Math.max(1, Math.min(height - 8, (int)(height * .92)));
        int x = (width - w) / 2, y = (height - h) / 2;
        int body = Math.max(1, h - 34), rail = 24;
        return new CanvasEditorLayout(new Rect(x,y,w,h), new Rect(x,y,w,20),
                new Rect(x+1,y+20,rail,body), new Rect(x+w-rail-1,y+20,rail,body),
                new Rect(x+rail+1,y+20,Math.max(1,w-2*rail-2),body),
                new Rect(x+1,y+h-14,w-2,13), new Rect(x+6,y+1,Math.max(1,w-12),19));
    }
}
