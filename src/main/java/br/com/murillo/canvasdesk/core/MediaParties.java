package br.com.murillo.canvasdesk.core;

import java.util.*;

/** Online consent, independent from document ACLs and durable edits. */
public final class MediaParties {
    public enum Mode { PROXIMITY, CONTINUOUS }
    public record Membership(UUID workspace,Mode mode,boolean explicit) {}
    private final Map<UUID,Membership> members=new HashMap<>();
    private final Map<UUID,Set<UUID>> declined=new HashMap<>();
    private final Map<UUID,UUID> active=new HashMap<>();
    public Membership membership(UUID player){return members.get(player);}
    public UUID active(UUID workspace){return active.get(workspace);}
    public void ensureActive(UUID workspace,UUID element){active.putIfAbsent(workspace,element);}
    public void activate(UUID workspace,UUID element){active.put(workspace,element);}
    public void removed(UUID workspace,UUID element){active.remove(workspace,element);}
    public void observe(UUID player,UUID workspace,UUID sourceAuthor){
        if(player.equals(sourceAuthor)&&!members.containsKey(player)&&!declined.getOrDefault(player,Set.of()).contains(workspace))members.put(player,new Membership(workspace,Mode.PROXIMITY,false));
    }
    public void join(UUID player,UUID workspace,Mode mode){members.put(player,new Membership(workspace,mode,true));var d=declined.get(player);if(d!=null)d.remove(workspace);}
    public void leave(UUID player,UUID workspace){var m=members.get(player);if(m!=null&&m.workspace.equals(workspace))members.remove(player);var d=declined.computeIfAbsent(player,k->new LinkedHashSet<>());d.add(workspace);while(d.size()>32)d.remove(d.iterator().next());}
    public boolean member(UUID player,UUID workspace){var m=members.get(player);return m!=null&&m.workspace.equals(workspace);}
    public boolean allowed(UUID player,UUID workspace,UUID element,UUID author){var m=members.get(player);return m!=null&&m.workspace.equals(workspace)&&element.equals(active.get(workspace))&&(m.explicit||player.equals(author));}
    public Set<UUID> players(){return Set.copyOf(members.keySet());}
    public Set<UUID> trackedPlayers(){Set<UUID> ids=new HashSet<>(members.keySet());ids.addAll(declined.keySet());return Set.copyOf(ids);}
    public Set<UUID> workspaces(){return Set.copyOf(active.keySet());}
    public void end(UUID workspace){active.remove(workspace);members.entrySet().removeIf(e->e.getValue().workspace.equals(workspace));declined.values().forEach(s->s.remove(workspace));}
    public void disconnect(UUID player){members.remove(player);declined.remove(player);}
    public static double gain(Mode mode,double distance){if(!Double.isFinite(distance)||distance<0)return 0;return mode==Mode.CONTINUOUS?1:Math.clamp((16-distance)/12,0,1);}
}
