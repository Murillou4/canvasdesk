package org.cef.browser;

import org.cef.*;
import org.cef.callback.CefDragData;
import org.cef.handler.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import com.google.gson.JsonObject;
import java.util.concurrent.TimeUnit;

/** Custom OSR sink in JCEF's package; avoids a JOGL context or a visible AWT window. */
public final class CanvasBrowser extends CefBrowser_N implements CefRenderHandler {
    public interface Paint {void accept(ByteBuffer pixels,int width,int height);}
    private CanvasDevTools devTools;
    public synchronized CompletableFuture<String> devTools(String method,String params){if(devTools==null)devTools=new CanvasDevTools(this);return devTools.execute(method,params);}
    @Override public void onBeforeClose(){if(devTools!=null)devTools.close();super.onBeforeClose();}
    private final java.util.Set<Integer> pressed=new java.util.HashSet<>();
    private final Canvas canvas=new Canvas();private final Paint paint;private int mouseMask;
    public CanvasBrowser(CefClient client,String url,Paint paint){super(client,url,null,null,null,settings());this.paint=paint;canvas.setSize(1280,720);}
    private static CefBrowserSettings settings(){var s=new CefBrowserSettings();s.windowless_frame_rate=30;return s;}
    public void createImmediately(){createBrowser(getClient(),0,getUrl(),true,false,canvas,getRequestContext());}
    public Component getUIComponent(){return canvas;}
    public CefRenderHandler getRenderHandler(){return this;}
    public Rectangle getViewRect(CefBrowser browser){return new Rectangle(0,0,1280,720);}
    public boolean getScreenInfo(CefBrowser b,CefScreenInfo info){return false;}
    public Point getScreenPoint(CefBrowser b,Point p){return p;}
    public void onPopupShow(CefBrowser b,boolean show){if(!show)invalidate();}
    public void onPopupSize(CefBrowser b,Rectangle r){}
    public void onPaint(CefBrowser b,boolean popup,Rectangle[] dirty,ByteBuffer pixels,int w,int h){if(!popup&&w==1280&&h==720)paint.accept(pixels,w,h);}
    public void addOnPaintListener(Consumer<CefPaintEvent> l){}
    public void setOnPaintListener(Consumer<CefPaintEvent> l){}
    public void removeOnPaintListener(Consumer<CefPaintEvent> l){}
    public boolean onCursorChange(CefBrowser b,int cursor){return false;}
    public boolean startDragging(CefBrowser b,CefDragData d,int mask,int x,int y){return false;}
    public void updateDragCursor(CefBrowser b,int mask){}
    protected CefBrowser_N createDevToolsBrowser(CefClient c,String url,CefRequestContext r,CefBrowser_N parent,Point at){return null;}
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution){return CompletableFuture.failedFuture(new UnsupportedOperationException("Use the shared frame buffer"));}
    public void mouse(int x,int y,int button,boolean down,int wheel){
        long when=System.currentTimeMillis();if(wheel!=0){sendMouseWheelEvent(new MouseWheelEvent(canvas,MouseEvent.MOUSE_WHEEL,when,0,x,y,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,wheel));return;}
        if(button>=0){if(down)mouseMask|=InputEvent.getMaskForButton(button+1);else mouseMask&=~InputEvent.getMaskForButton(button+1);}
        int kind=button<0?(mouseMask==0?MouseEvent.MOUSE_MOVED:MouseEvent.MOUSE_DRAGGED):down?MouseEvent.MOUSE_PRESSED:MouseEvent.MOUSE_RELEASED;
        sendMouseEvent(new MouseEvent(canvas,kind,when,mouseMask,x,y,button<0?0:1,false,button<0?0:button+1));
    }
    public void key(int code,boolean down,int modifiers,String text){
        try{
            JsonObject p=new JsonObject();
            if(text!=null){p.addProperty("text",text);devTools("Input.insertText",asciiJson(p.toString())).get(10,TimeUnit.SECONDS);return;}
            if(down)pressed.add(code);else pressed.remove(code);
            String key=switch(code){case KeyEvent.VK_ENTER->"Enter";case KeyEvent.VK_BACK_SPACE->"Backspace";case KeyEvent.VK_TAB->"Tab";case KeyEvent.VK_SPACE->" ";case KeyEvent.VK_DELETE->"Delete";case KeyEvent.VK_LEFT->"ArrowLeft";case KeyEvent.VK_RIGHT->"ArrowRight";case KeyEvent.VK_UP->"ArrowUp";case KeyEvent.VK_DOWN->"ArrowDown";case KeyEvent.VK_HOME->"Home";case KeyEvent.VK_END->"End";case KeyEvent.VK_SHIFT->"Shift";case KeyEvent.VK_CONTROL->"Control";case KeyEvent.VK_ALT->"Alt";case KeyEvent.VK_ESCAPE->"Escape";case KeyEvent.VK_META->"Meta";case KeyEvent.VK_PAGE_UP->"PageUp";case KeyEvent.VK_PAGE_DOWN->"PageDown";case KeyEvent.VK_INSERT->"Insert";default->code>=65&&code<=90?Character.toString((char)code):"Unidentified";};
            p.addProperty("type",down&&code==KeyEvent.VK_ENTER?"keyDown":down?"rawKeyDown":"keyUp");p.addProperty("key",key);p.addProperty("windowsVirtualKeyCode",switch(code){case KeyEvent.VK_ENTER->13;case KeyEvent.VK_DELETE->46;case KeyEvent.VK_INSERT->45;case KeyEvent.VK_META->91;default->code;});
            p.addProperty("modifiers",((modifiers&InputEvent.ALT_DOWN_MASK)!=0?1:0)|((modifiers&InputEvent.CTRL_DOWN_MASK)!=0?2:0)|((modifiers&InputEvent.META_DOWN_MASK)!=0?4:0)|((modifiers&InputEvent.SHIFT_DOWN_MASK)!=0?8:0));
            if(down&&code==KeyEvent.VK_ENTER){p.addProperty("text","\r");p.addProperty("unmodifiedText","\r");}
            devTools("Input.dispatchKeyEvent",p.toString()).get(10,TimeUnit.SECONDS);
        }catch(Exception error){throw new IllegalStateException("Entrada do navegador não foi confirmada: "+error.getClass().getSimpleName()+": "+error.getMessage(),error);}
    }

    public void resetInput(){for(int code:java.util.Set.copyOf(pressed))key(code,false,0,null);mouseMask=0;setFocus(false);setFocus(true);}
    /** JCEF's JNI uses modified UTF-8. ASCII JSON keeps supplementary code points intact. */
    private static String asciiJson(String json){StringBuilder b=new StringBuilder();for(int i=0;i<json.length();i++){char c=json.charAt(i);if(c>127){b.append('\\').append('u');String hex=Integer.toHexString(c);b.append("0".repeat(4-hex.length())).append(hex);}else b.append(c);}return b.toString();}
}
