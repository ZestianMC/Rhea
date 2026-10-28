package de.erethon.rhea.zlib.adapter;

import com.github.retrooper.packetevents.PacketEvents;
import com.zestian.zlib.menu.render.client.ClientVersionProvider;
import org.bukkit.Bukkit;
import java.util.UUID;

/** Reads the initialized PacketEvents public API without reflecting on its non-public implementation. */
public final class PacketEventsVersionProvider implements ClientVersionProvider {
    @Override public int getProtocol(UUID viewer) {
        if (viewer == null) return PROTOCOL_UNKNOWN;
        try {
            var api=PacketEvents.getAPI();var player=Bukkit.getPlayer(viewer);
            if(api==null||player==null)return PROTOCOL_UNKNOWN;
            var version=api.getPlayerManager().getClientVersion(player);
            return version==null?PROTOCOL_UNKNOWN:version.getProtocolVersion();
        } catch(RuntimeException|LinkageError unavailable) { return PROTOCOL_UNKNOWN; }
    }
}
