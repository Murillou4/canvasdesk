package br.com.murillo.canvasdesk.browser;

import java.util.*;
import org.cef.browser.CefRequestContext;

/** Called exclusively from CEF UI callbacks, before untrusted navigation. */
final class BrowserAudio {
    private final CefRequestContext context;
    private String preference;
    private boolean dictionary;
    private Boolean current;
    BrowserAudio(CefRequestContext context){this.context=context;}
    boolean configure(){
        for(String name:List.of("profile.default_content_setting_values.sound","profile.default_content_setting_values")){
            if(!context.hasPreference(name)||!context.canSetPreference(name))continue;
            preference=name;dictionary=name.endsWith("values");if(apply(false))return true;
        }preference=null;return false;
    }
    boolean apply(boolean allowed){
        if(preference==null)return false;Object value=allowed?1:2;
        if(current!=null&&current==allowed)return true;
        if(dictionary){var copy=new HashMap<String,Object>();Object previous=context.getPreference(preference);if(previous instanceof Map<?,?> m)m.forEach((k,v)->copy.put(k.toString(),v));copy.put("sound",value);value=copy;}
        String error=context.setPreference(preference,value);if(error!=null)return false;
        Object actual=context.getPreference(preference);if(dictionary)actual=actual instanceof Map<?,?> m?m.get("sound"):null;
        boolean ok=actual instanceof Number n&&n.intValue()==(allowed?1:2);if(ok)current=allowed;return ok;
    }
    String preference(){return preference==null?"unavailable":preference;}
}
