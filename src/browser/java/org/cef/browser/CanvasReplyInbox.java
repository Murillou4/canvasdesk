package org.cef.browser;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** A CDP reply may arrive before the native future supplies its request id. */
final class CanvasReplyInbox implements AutoCloseable {
    private record Reply(boolean ok,String value) {}
    private final Map<Integer,CompletableFuture<String>> pending=new HashMap<>();
    private final LinkedHashMap<Integer,Reply> early=new LinkedHashMap<>();
    private final Set<CompletableFuture<String>> unassigned=new HashSet<>();
    private boolean closed;
    CompletableFuture<String> expect(CompletableFuture<Integer> issued){
        CompletableFuture<String> answer=new CompletableFuture<>();
        synchronized(this){if(closed)return CompletableFuture.failedFuture(new IllegalStateException("Browser closed"));unassigned.add(answer);}
        issued.whenComplete((id,error)->{
            Reply reply;
            synchronized(this){
                unassigned.remove(answer);
                if(error!=null){answer.completeExceptionally(error);return;}
                if(closed||id==null||id<=0){answer.completeExceptionally(new IllegalStateException("Browser closed or request rejected"));return;}
                reply=early.remove(id);if(reply==null){pending.put(id,answer);return;}
            }
            complete(answer,reply);
        });return answer;
    }
    void received(int id,boolean ok,String value){
        CompletableFuture<String> answer;Reply reply=new Reply(ok,value);
        synchronized(this){if(closed)return;answer=pending.remove(id);if(answer==null){early.put(id,reply);while(early.size()>256)early.remove(early.keySet().iterator().next());return;}}
        complete(answer,reply);
    }
    private static void complete(CompletableFuture<String> answer,Reply reply){if(reply.ok)answer.complete(reply.value);else answer.completeExceptionally(new IllegalStateException("DevTools command failed: "+reply.value));}
    public synchronized void close(){closed=true;var error=new IllegalStateException("Browser closed");pending.values().forEach(f->f.completeExceptionally(error));unassigned.forEach(f->f.completeExceptionally(error));pending.clear();unassigned.clear();early.clear();}
}
