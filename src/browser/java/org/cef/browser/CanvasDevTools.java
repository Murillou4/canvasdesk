package org.cef.browser;

import java.util.concurrent.CompletableFuture;

/** Uses the existing JCEF native API without dropping replies that beat the id callback. */
final class CanvasDevTools implements AutoCloseable {
    private final CefBrowser_N browser;
    private final CanvasReplyInbox replies=new CanvasReplyInbox();
    private final CefRegistration registration;
    CanvasDevTools(CefBrowser_N browser){
        this.browser=browser;
        registration=browser.addDevToolsMessageObserver(new CefDevToolsMessageObserver(){
            public void onDevToolsMethodResult(CefBrowser b,int id,boolean ok,String result){replies.received(id,ok,result);}
            public void onDevToolsEvent(CefBrowser b,String method,String parameters){}
        });
    }
    CompletableFuture<String> execute(String method,String parameters){return replies.expect(browser.executeDevToolsMethod(method,parameters));}
    public void close(){replies.close();registration.dispose();}
}
