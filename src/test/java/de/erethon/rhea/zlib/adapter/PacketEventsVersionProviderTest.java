package de.erethon.rhea.zlib.adapter;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.zestian.zlib.menu.render.client.ClientVersionProvider;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketEventsVersionProviderTest {
    private final UUID id=UUID.randomUUID();
    private final Player player=mock(Player.class);
    private final PlayerManager players=mock(PlayerManager.class);

    // PacketEvents' Spigot implementation is likewise a non-public anonymous subclass.
    private PacketEventsAPI<Object> api() {
        return new PacketEventsAPI<>() {
            public void load(){}
            public boolean isLoaded(){return true;}
            public void init(){}
            public void terminate(){}
            public boolean isInitialized(){return true;}
            public boolean isTerminated(){return false;}
            public Object getPlugin(){return null;}
            public ServerManager getServerManager(){return null;}
            public ProtocolManager getProtocolManager(){return null;}
            public PlayerManager getPlayerManager(){return players;}
            public NettyManager getNettyManager(){return null;}
            public ChannelInjector getInjector(){return null;}
        };
    }

    @Test void readsProtocolFromANonPublicPacketEventsImplementation() {
        try(var packets=mockStatic(PacketEvents.class);var bukkit=mockStatic(Bukkit.class)) {
            var api=api();packets.when(PacketEvents::getAPI).thenReturn(api);
            bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);
            when(players.getClientVersion(player)).thenReturn(ClientVersion.V_1_21_6);
            ClientVersionProvider provider=new PacketEventsVersionProvider();
            assertEquals(771,provider.getProtocol(id));
        }
    }
    @Test void unavailableApiPlayerAndVersionRemainUnknown() {
        try(var packets=mockStatic(PacketEvents.class);var bukkit=mockStatic(Bukkit.class)) {
            var provider=new PacketEventsVersionProvider();assertEquals(-1,provider.getProtocol(null));
            assertEquals(-1,provider.getProtocol(id));
            packets.when(PacketEvents::getAPI).thenReturn(api());assertEquals(-1,provider.getProtocol(id));
            bukkit.when(()->Bukkit.getPlayer(id)).thenReturn(player);assertEquals(-1,provider.getProtocol(id));
            when(players.getClientVersion(player)).thenThrow(new IllegalStateException("initializing"));
            assertEquals(-1,provider.getProtocol(id));
        }
    }
}
