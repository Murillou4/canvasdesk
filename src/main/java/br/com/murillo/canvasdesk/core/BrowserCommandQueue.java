package br.com.murillo.canvasdesk.core;

import java.util.*;

/** Reliable input is FIFO. Only snapshots and pointer motion are replaceable. */
public final class BrowserCommandQueue {
    private final ArrayDeque<String> reliable=new ArrayDeque<>();
    private final int maxBytes;
    private int bytes;
    private String state,motion,audio;
    private boolean closed;
    public BrowserCommandQueue(int maxBytes){this.maxBytes=maxBytes;}
    public synchronized boolean offer(String kind,String value){
        if(closed)return false;
        if(kind.equals("audio")){audio=value;notifyAll();return true;}
        if(kind.equals("state")){state=value;notifyAll();return true;}
        if(kind.equals("motion")){motion=value;notifyAll();return true;}
        int size=value.length()*2;
        if(bytes+size>maxBytes)return false;
        // A pointer button/key must see the most recent cursor position first.
        if(motion!=null){int n=motion.length()*2;if(bytes+size+n>maxBytes)return false;reliable.add(motion);bytes+=n;motion=null;}
        reliable.add(value);bytes+=size;notifyAll();return true;
    }
    public synchronized String take()throws InterruptedException{
        while(!closed&&reliable.isEmpty()&&state==null&&motion==null&&audio==null)wait();
        if(audio!=null){String r=audio;audio=null;return r;}
        // Apply the latest focus/permission snapshot before dispatching input.
        if(state!=null){String r=state;state=null;return r;}
        if(!reliable.isEmpty()){String r=reliable.remove();bytes-=r.length()*2;return r;}
        String r=motion;motion=null;return r;
    }
    public synchronized void close(){closed=true;reliable.clear();state=motion=audio=null;bytes=0;notifyAll();}
}
