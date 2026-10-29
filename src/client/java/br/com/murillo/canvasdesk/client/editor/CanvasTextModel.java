package br.com.murillo.canvasdesk.client.editor;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultilineTextField;
import java.util.*;

/** Public value views of the native model's protected StringView type; no reflection or mixin. */
public final class CanvasTextModel extends MultilineTextField {
    public record Span(int start,int end){}
    public CanvasTextModel(Font font,int width){super(font,width);}
    public List<Span> lines(){List<Span> result=new ArrayList<>();for(var line:iterateLines())result.add(new Span(line.beginIndex(),line.endIndex()));return result;}
    public Span selection(){var range=getSelected();return new Span(range.beginIndex(),range.endIndex());}
}
