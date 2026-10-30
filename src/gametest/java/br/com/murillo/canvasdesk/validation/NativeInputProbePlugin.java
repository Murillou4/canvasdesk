package br.com.murillo.canvasdesk.validation;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.extensibility.*;

/** Fabric GameTest deliberately drops SDL events. Re-enable them only in the disposable OS-input probe. */
public final class NativeInputProbePlugin implements IMixinConfigPlugin {
    public void onLoad(String pkg){}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){String role=System.getProperty("canvasdesk.testRole","");boolean manual=role.equals("features")&&!System.getProperty("canvasdesk.nativeInputMode","none").equals("none");return mixin.endsWith("HiddenTestWindow")?(role.startsWith("media")||role.equals("worldMedia")||role.equals("party")||role.equals("features")&&!manual):manual;}
    public void acceptTargets(Set<String> mine,Set<String> others){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){
        if(mixin.endsWith("HiddenTestWindow"))return;
        boolean found=false;
        for(var method:node.methods)if(method.name.contains("disableRealInput")){method.instructions.clear();method.instructions.add(new InsnNode(Opcodes.RETURN));method.tryCatchBlocks.clear();found=true;}
        if(!found)throw new IllegalStateException("Fabric SDL input guard changed; cannot claim native keyboard validation.");
    }
}
