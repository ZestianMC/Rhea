package de.erethon.rhea;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RheaSessionTest {
    @Test void previousConnectionCannotCloseOrUseTheNewPlayersCallbacks() {
        var plugin=mock(JavaPlugin.class);when(plugin.isEnabled()).thenReturn(true);
        var previous=player();var current=player();var id=current.getUniqueId();when(previous.getUniqueId()).thenReturn(id);
        var renderer=new Rhea(plugin);long old=renderer.begin(previous),fresh=renderer.begin(current);
        renderer.close(previous);
        assertFalse(renderer.current(previous,old));assertFalse(renderer.current(previous,fresh));assertFalse(renderer.isViewing(previous));
        assertTrue(renderer.current(current,fresh));assertTrue(renderer.isViewing(current));verify(previous,never()).closeDialog();
        renderer.closeAll();verify(current).closeDialog();
    }
    @Test void replacingOrClosingAViewInvalidatesItsOldActionsPerPlayer() {
        var plugin=mock(JavaPlugin.class);when(plugin.isEnabled()).thenReturn(true);
        var first=player();var second=player();var adapter=new Rhea(plugin);
        long old=adapter.begin(first),other=adapter.begin(second),current=adapter.begin(first);
        assertFalse(adapter.current(first,old));assertTrue(adapter.current(first,current));assertTrue(adapter.current(second,other));
        adapter.close(first);assertFalse(adapter.current(first,current));assertTrue(adapter.current(second,other));verify(first).closeDialog();
        when(plugin.isEnabled()).thenReturn(false);assertFalse(adapter.current(second,other));
    }
    @Test void reloadInvalidatesEveryViewerBeforeSendingClosePackets() {
        var plugin=mock(JavaPlugin.class);var server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(plugin.isEnabled()).thenReturn(true);
        var first=player();var second=player();var adapter=new Rhea(plugin);
        long a=adapter.begin(first),b=adapter.begin(second);
        when(server.getPlayer(first.getUniqueId())).thenReturn(first);when(server.getPlayer(second.getUniqueId())).thenReturn(second);
        adapter.closeAll();assertFalse(adapter.current(first,a));assertFalse(adapter.current(second,b));verify(first).closeDialog();verify(second).closeDialog();
    }
    private Player player(){var player=mock(Player.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(player.isOnline()).thenReturn(true);return player;}
}
