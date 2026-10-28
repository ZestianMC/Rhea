package de.erethon.rhea.zlib;

import de.erethon.rhea.RheaProvider;
import de.erethon.rhea.RheaTemplate;

import com.zestian.zlib.menu.render.client.ClientCapabilityService;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RheaGuiManagerTest {
    private final UUID pack = UUID.randomUUID();
    private final RheaRequirements requirements = new RheaRequirements(777, 777, pack);
    private final ClientCapabilityService capabilities = mock(ClientCapabilityService.class);
    private final RheaProvider provider = mock(RheaProvider.class);
    private final RheaProvider.Dialog dialog = mock(RheaProvider.Dialog.class);
    private final AtomicInteger factories = new AtomicInteger(), fallbacks = new AtomicInteger(), bindings = new AtomicInteger(), refreshStops = new AtomicInteger();
    private final RheaGuiManager manager = new RheaGuiManager(capabilities, () -> { factories.incrementAndGet(); return provider; }, ignored -> {}, ignored -> refreshStops.incrementAndGet());
    private final RheaTemplate template = new RheaTemplate("test", "demo", "demo:ui", 9, 18,
            List.of(List.of(new RheaTemplate.Segment("a", null)), List.of(new RheaTemplate.Segment("b", null))), List.of(), List.of());

    private Player player(int protocol) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(capabilities.serverSupportsDialogs()).thenReturn(true);
        when(capabilities.knownProtocolOf(player)).thenReturn(protocol);
        when(provider.dialog(any(), any(), any())).thenReturn(dialog);
        when(provider.isViewing(player)).thenReturn(true);
        return player;
    }
    private void status(Player player, UUID id, PlayerResourcePackStatusEvent.Status status) {
        var event = mock(PlayerResourcePackStatusEvent.class);
        when(event.getPlayer()).thenReturn(player); when(event.getID()).thenReturn(id); when(event.getStatus()).thenReturn(status);
        manager.resourcePack(event);
    }
    private void open(Player player) { manager.open(player, template, Component.empty(), Component.text("Close"), requirements, ignored -> bindings.incrementAndGet(), ignored -> fallbacks.incrementAndGet()); }

    @Test void modernViewerNeedsTheExactSuccessfullyLoadedPack() {
        var player = player(777);
        status(player, UUID.randomUUID(), PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        open(player); assertEquals(1, fallbacks.get()); assertEquals(0, factories.get());
        status(player, pack, PlayerResourcePackStatusEvent.Status.ACCEPTED);
        assertFalse(manager.canShow(player, requirements));
        status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        open(player); verify(dialog).show(player);
        assertEquals(1, fallbacks.get()); assertEquals(1, factories.get()); assertEquals(1, bindings.get()); assertEquals(2, refreshStops.get());
    }
    @Test void oldUnknownAndUntestedProtocolsNeverLoadTheModernAdapter() {
        for (int protocol : new int[]{47, 769, 771, 776, -1, 778}) { var player = player(protocol); status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED); open(player); }
        assertEquals(6, fallbacks.get()); assertEquals(0, factories.get()); assertEquals(0, bindings.get());
    }
    @Test void oldServerAndBedrockUseInventoryEvenWithAPack() {
        var player = player(777); status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        when(capabilities.serverSupportsDialogs()).thenReturn(false); open(player);
        when(capabilities.serverSupportsDialogs()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(new UUID(0, 123)); status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED); open(player);
        assertEquals(2, fallbacks.get()); assertEquals(0, factories.get());
    }
    @Test void twoPlayersChooseDifferentViewsWithoutChangingEachOther() {
        var modern = player(777); var old = player(769);
        status(modern, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        status(old, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        open(modern); open(old);
        verify(dialog).show(modern); verify(dialog, never()).show(old); verify(provider, never()).close(modern);
        assertTrue(manager.canShow(modern, requirements)); assertEquals(1, fallbacks.get());
    }
    @Test void unrelatedPackDoesNotCloseTheActiveGuiAndRevokingItsOwnPackDoes() {
        var player = player(777); status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED); open(player);
        status(player, UUID.randomUUID(), PlayerResourcePackStatusEvent.Status.DECLINED); verify(provider, never()).close(player);
        status(player, pack, PlayerResourcePackStatusEvent.Status.DECLINED); verify(provider).close(player);
        assertFalse(manager.canShow(player, requirements));
    }
    @Test void pendingFailedAndDiscardedStatusesCannotReuseAnOldAcknowledgement() {
        var player = player(777);
        for (var status : PlayerResourcePackStatusEvent.Status.values()) {
            if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) continue;
            status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
            status(player, pack, status);
            assertFalse(manager.canShow(player, requirements), status.name());
        }
    }
    @Test void revokingAPackDoesNotReopenAMenuThePlayerAlreadyClosed() {
        var player=player(777);status(player,pack,PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);open(player);
        when(provider.isViewing(player)).thenReturn(false);
        status(player,pack,PlayerResourcePackStatusEvent.Status.DECLINED);assertEquals(0,fallbacks.get());
    }
    @Test void renderFailureClosesThePartialViewAndFallsBackOnce() {
        var player = player(777); status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        doThrow(new IllegalStateException("render")).when(dialog).show(player);
        doThrow(new IllegalStateException("close")).when(provider).close(player);
        open(player); assertEquals(1, fallbacks.get()); assertEquals(1, bindings.get()); verify(provider).close(player);
    }
    @Test void fallbackExceptionsPropagateWithoutRetryingBusinessEffects() {
        var player = player(47);
        Consumer<Player> fallback = ignored -> { fallbacks.incrementAndGet(); throw new IllegalStateException("business effect"); };
        assertThrows(IllegalStateException.class, () -> manager.open(player, template, Component.empty(), Component.empty(), requirements, ignored -> {}, fallback));
        assertEquals(1, fallbacks.get()); assertEquals(0, factories.get());
    }
    @Test void logoutAndReloadInvalidateViewsButReloadKeepsLoadedPacks() {
        var player = player(777); status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED); open(player);
        manager.closeViews(); verify(provider).closeAll(); assertTrue(manager.canShow(player, requirements));
        var quit = mock(PlayerQuitEvent.class); when(quit.getPlayer()).thenReturn(player); manager.quit(quit);
        assertFalse(manager.canShow(player, requirements)); verify(provider).close(player);
    }
    @Test void unidentifiedPackAndOfflineViewerFailSafe() {
        var player = player(777); assertFalse(manager.canShow(player, null));
        status(player, pack, PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED); when(player.isOnline()).thenReturn(false);
        assertFalse(manager.canShow(player, requirements));
    }
    @Test void configurationAcknowledgementBeforeJoinEnablesTheViewerAndDisconnectForgetsIt() {
        var player=player(777);
        manager.resourcePackStatus(player.getUniqueId(),pack,true);
        assertTrue(manager.canShow(player,requirements));
        manager.forget(player.getUniqueId());
        assertFalse(manager.canShow(player,requirements));
        manager.resourcePackStatus(player.getUniqueId(),pack,true);open(player);
        manager.forget(player.getUniqueId());verify(provider).close(player);
    }
    @Test void bukkitCannotBypassThePacketSourcesConnectionAndOfferValidation() throws Exception {
        var tracked=mock(RheaGuiManager.PackSource.class);when(tracked.owns(any())).thenReturn(true);
        var source=RheaGuiManager.class.getDeclaredField("packSource");source.setAccessible(true);source.set(manager,tracked);
        var player=player(777);
        status(player,pack,PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        assertFalse(manager.canShow(player,requirements));
        manager.resourcePackStatus(player.getUniqueId(),pack,true);
        assertTrue(manager.canShow(player,requirements));
        manager.resourcePackStatus(player.getUniqueId(),pack,false);
        status(player,pack,PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        assertFalse(manager.canShow(player,requirements));
    }
    @Test void overlappingPlayersCannotBorrowAnotherConnectionsAckOrClearItOnQuit() throws Exception {
        var tracked=mock(RheaGuiManager.PackSource.class);
        var source=RheaGuiManager.class.getDeclaredField("packSource");source.setAccessible(true);source.set(manager,tracked);
        var old=player(777);var current=player(777);var id=current.getUniqueId();when(old.getUniqueId()).thenReturn(id);
        when(tracked.owns(current)).thenReturn(true);
        manager.resourcePackStatus(current.getUniqueId(),pack,true);
        assertFalse(manager.canShow(old,requirements));assertTrue(manager.canShow(current,requirements));
        var quit=mock(PlayerQuitEvent.class);when(quit.getPlayer()).thenReturn(old);manager.quit(quit);
        assertTrue(manager.canShow(current,requirements));verify(provider,never()).close(any());
    }
    @Test void staleInventoryAndCloseCannotInvalidateTheNewConnectionsGui() {
        var previous=player(777);var current=player(777);var id=current.getUniqueId();when(previous.getUniqueId()).thenReturn(id);
        manager.resourcePackStatus(id,pack,true);open(current);
        var event=mock(org.bukkit.event.inventory.InventoryOpenEvent.class);when(event.getPlayer()).thenReturn(previous);
        manager.inventory(event);manager.close(previous);verify(provider,never()).close(any());
        when(event.getPlayer()).thenReturn(current);manager.inventory(event);verify(provider).close(current);
    }
    @Test void replacementPlayerRenderFailureClosesBothTheOldViewAndNewPartialView() {
        var previous=player(777);var current=player(777);var id=current.getUniqueId();when(previous.getUniqueId()).thenReturn(id);
        manager.resourcePackStatus(id,pack,true);open(previous);
        doThrow(new IllegalStateException("render")).when(dialog).show(current);
        open(current);verify(provider).close(previous);verify(provider).close(current);assertEquals(1,fallbacks.get());
    }
    @Test void unavailableClientVersionSourceFallsBackBeforeTheRendererLoads() {
        var player=player(777);status(player,pack,PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
        when(capabilities.knownProtocolOf(player)).thenThrow(new NoClassDefFoundError("optional source"));
        open(player);assertEquals(1,fallbacks.get());assertEquals(0,factories.get());
    }
}
