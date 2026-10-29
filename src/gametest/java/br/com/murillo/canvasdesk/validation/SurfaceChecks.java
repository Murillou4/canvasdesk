package br.com.murillo.canvasdesk.validation;

import br.com.murillo.canvasdesk.client.render.SurfaceCache;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import java.util.Map;

/** Test-only observation of the exact vector payload used by a tile's uploaded GPU texture. */
public final class SurfaceChecks {
    private SurfaceChecks(){}
    public static void waitForContent(ClientGameTestContext context,BlockPos pos,String marker) {
        context.waitFor(client->{
            try{
                var field=SurfaceCache.class.getDeclaredField("TILES");field.setAccessible(true);
                Object tile=((Map<?,?>)field.get(null)).get(pos.asLong());if(tile==null)return false;
                var fingerprint=tile.getClass().getDeclaredField("fingerprint");fingerprint.setAccessible(true);
                return fingerprint.get(tile).toString().contains(marker);
            }catch(ReflectiveOperationException error){throw new RuntimeException(error);}
        },1800);
    }
}
