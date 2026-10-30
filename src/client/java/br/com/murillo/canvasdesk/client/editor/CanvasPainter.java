package br.com.murillo.canvasdesk.client.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;

/** Real vector rendering shared by editor previews and client-side integrations. */
public final class CanvasPainter {
    private CanvasPainter() {}

    public static void paint(GuiGraphicsExtractor g, Font font, CanvasViewport v, CanvasElementView e,
                             Map<String, CanvasElementView> elements) {
        if (!e.type.equals("CONNECTOR") && (v.screenX(e.x + e.w) < v.left - 16 || v.screenY(e.y + e.h) < v.top - 16
            || v.screenX(e.x) > v.right + 16 || v.screenY(e.y) > v.bottom + 16)) return;
        int ink = 0xff000000 | e.color;
        float scale = (float) v.zoom;
        g.pose().pushMatrix();
        g.pose().translate((float) v.screenX(e.x), (float) v.screenY(e.y)).scale(scale, scale);
        double thickness = Math.max(e.thickness, 0.8 / v.zoom);
        switch (e.type) {
            case "MATERIALS" -> {
                var layout=MaterialsLayout.of(font,e);g.fill(0,0,(int)e.w,(int)layout.height(),0xfff7f5ec);g.fill(0,0,3,(int)layout.height(),ink);
                g.pose().pushMatrix();g.pose().scale((float)layout.scale(),(float)layout.scale());int y=8;
                for(var text:layout.title()){g.text(font,text,8,y,ink,false);y+=12;}g.pose().popMatrix();
                for(var cell:layout.cells()){
                    if(v.screenX(e.x+cell.x()+cell.size())<v.left||v.screenX(e.x+cell.x())>v.right||v.screenY(e.y+cell.quantityY()+9*layout.scale())<v.top||v.screenY(e.y+cell.y())>v.bottom)continue;
                    g.pose().pushMatrix();g.pose().translate((float)cell.x(),(float)cell.y()).scale((float)(cell.size()/16),(float)(cell.size()/16));g.item(MaterialIcons.stack(cell.item().id()),0,0);g.pose().popMatrix();
                    g.pose().pushMatrix();g.pose().translate((float)(cell.x()+cell.size()/2),(float)cell.quantityY()).scale((float)layout.scale(),(float)layout.scale());String count=Integer.toString(cell.item().quantidade());g.text(font,count,-font.width(count)/2,0,ink,false);g.pose().popMatrix();
                }
            }
            case "EMBED" -> {
                g.fill(0,0,(int)e.w,(int)e.h,0xffe7e2d7);g.text(font,font.plainSubstrByWidth(e.text.isBlank()?e.url:e.text,Math.max(1,(int)e.w-16)),8,8,ink,false);
                g.centeredText(font,"▶",(int)e.w/2,(int)e.h/2,ink);
                br.com.murillo.canvasdesk.client.media.BrowserTexture.paint(g,e);
            }
            case "STROKE" -> {
                if (e.points.size() == 1) {
                    CanvasElementView.Point p = e.points.getFirst();
                    line(g, p.x() - e.x, p.y() - e.y, p.x() - e.x + 0.1, p.y() - e.y, thickness, ink);
                }
                CanvasElementView.Point previous = e.points.isEmpty() ? null : e.points.getFirst();
                for (int i = 1; i < e.points.size(); i++) {
                    CanvasElementView.Point p = e.points.get(i);
                    if (i + 1 < e.points.size() && Math.hypot(p.x() - previous.x(), p.y() - previous.y()) * v.zoom < 1.2) continue;
                    line(g, previous.x() - e.x, previous.y() - e.y, p.x() - e.x, p.y() - e.y, thickness, ink);
                    previous = p;
                }
            }
            case "RECTANGLE" -> rectangle(g, 0, 0, e.w, e.h, thickness, ink);
            case "ELLIPSE" -> {
                int segments = Math.clamp((int) ((e.w + e.h) * v.zoom / 5), 16, 96);
                for (int i = 0; i < segments; i++) {
                    double a = i * Math.TAU / segments, b = (i + 1) * Math.TAU / segments;
                    line(g, e.w / 2 + Math.cos(a) * e.w / 2, e.h / 2 + Math.sin(a) * e.h / 2,
                        e.w / 2 + Math.cos(b) * e.w / 2, e.h / 2 + Math.sin(b) * e.h / 2, thickness, ink);
                }
            }
            case "LINE", "ARROW", "CONNECTOR" -> {
                double[] ends = endpoints(e, elements);
                line(g, ends[0] - e.x, ends[1] - e.y, ends[2] - e.x, ends[3] - e.y, thickness, ink);
                if (!e.type.equals("LINE")) {
                    double angle = Math.atan2(ends[3] - ends[1], ends[2] - ends[0]);
                    double length = Math.max(9, e.thickness * 3);
                    for (double offset : new double[]{-0.48, 0.48}) {
                        line(g, ends[2] - e.x, ends[3] - e.y,
                            ends[2] - e.x - Math.cos(angle + offset) * length,
                            ends[3] - e.y - Math.sin(angle + offset) * length, thickness, ink);
                    }
                }
            }
            case "NOTE", "CHECKLIST", "TEXT" -> {
                if (!e.type.equals("TEXT")) {
                    g.fill(0, 0, (int) e.w, (int) e.h, e.type.equals("NOTE") ? tint(e.color, 0.83) : 0xfff7f5ec);
                    g.fill(0, 0, 3, (int) e.h, ink);
                }
                if (v.zoom >= 0.22) {
                    CanvasTextLayout layout=CanvasTextLayout.of(font,e);double s=layout.scale();
                    for(var check:layout.checks()) {
                        double x=layout.padding(),y=check.y(),b=layout.boxSize();
                        rectangle(g,x,y,x+b,y+b,s,ink);
                        if(check.index()<e.checks.size()&&e.checks.get(check.index())) {
                            line(g,x+s,y+4*s,x+4*s,y+7*s,1.4*s,ink);
                            line(g,x+4*s,y+7*s,x+8*s,y+s,1.4*s,ink);
                        }
                    }
                    for(var text:layout.lines()) {
                        if(v.screenY(e.y+text.y()+12*s)<v.top||v.screenY(e.y+text.y())>v.bottom)continue;
                        g.pose().pushMatrix();g.pose().translate((float)layout.textX(),(float)text.y()).scale((float)s,(float)s);
                        g.text(font,text.value(),0,0,ink,false);g.pose().popMatrix();
                    }
                }
            }
            default -> { }
        }
        g.pose().popMatrix();
    }

    public static double[] endpoints(CanvasElementView e, Map<String, CanvasElementView> elements) {
        double x1 = e.x, y1 = e.y, x2 = e.x + e.w, y2 = e.y + e.h;
        if (e.points.size() >= 2) {
            x1 = e.points.getFirst().x(); y1 = e.points.getFirst().y();
            x2 = e.points.getLast().x(); y2 = e.points.getLast().y();
        }
        CanvasElementView from = elements.get(e.from), to = elements.get(e.to);
        if (from != null) { x1 = from.centerX(); y1 = from.centerY(); }
        if (to != null) { x2 = to.centerX(); y2 = to.centerY(); }
        double cx1 = x1, cy1 = y1;
        if (from != null) {
            double[] edge = edge(from, x2, y2); x1 = edge[0]; y1 = edge[1];
        }
        if (to != null) {
            double[] edge = edge(to, cx1, cy1); x2 = edge[0]; y2 = edge[1];
        }
        return new double[]{x1, y1, x2, y2};
    }

    private static double[] edge(CanvasElementView e, double targetX, double targetY) {
        double dx = targetX - e.centerX(), dy = targetY - e.centerY();
        double divisor = Math.max(Math.abs(dx) / Math.max(1, e.w / 2), Math.abs(dy) / Math.max(1, e.h / 2));
        return divisor > 0 ? new double[]{e.centerX() + dx / divisor, e.centerY() + dy / divisor}
            : new double[]{e.centerX(), e.centerY()};
    }

    public static void line(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, double width, int color) {
        double length = Math.hypot(x2 - x1, y2 - y1);
        g.pose().pushMatrix();
        g.pose().translate((float) x1, (float) y1).rotate((float) Math.atan2(y2 - y1, x2 - x1));
        g.fill(0, -(int) Math.ceil(width / 2), Math.max(1, (int) Math.ceil(length)), Math.max(1, (int) Math.floor(width / 2)), color);
        g.pose().popMatrix();
    }

    public static void rectangle(GuiGraphicsExtractor g, double x1, double y1, double x2, double y2, double width, int color) {
        line(g, x1, y1, x2, y1, width, color); line(g, x2, y1, x2, y2, width, color);
        line(g, x2, y2, x1, y2, width, color); line(g, x1, y2, x1, y1, width, color);
    }

    private static int tint(int color, double white) {
        int r = (int) (((color >> 16) & 255) * (1 - white) + 255 * white);
        int g = (int) (((color >> 8) & 255) * (1 - white) + 255 * white);
        int b = (int) ((color & 255) * (1 - white) + 255 * white);
        return 0xff000000 | r << 16 | g << 8 | b;
    }
}
