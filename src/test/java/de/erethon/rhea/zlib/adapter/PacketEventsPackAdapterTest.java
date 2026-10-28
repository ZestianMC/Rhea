package de.erethon.rhea.zlib.adapter;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.protocol.player.User;
import de.erethon.rhea.zlib.RheaGuiManager;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises pre-join ACKs and connection ownership without a Bukkit player or network thread. */
class PacketEventsPackAdapterTest {
    private static final UUID VIEWER = UUID.randomUUID(), PACK = UUID.randomUUID();
    private final RheaGuiManager gui = mock(RheaGuiManager.class);
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final PacketEventsPackAdapter adapter = new PacketEventsPackAdapter(gui, pending::add);

    private User user() { User user = mock(User.class); when(user.getUUID()).thenReturn(VIEWER); return user; }
    private UserLoginEvent login(User user,Object player) { var event=mock(UserLoginEvent.class);when(event.getUser()).thenReturn(user);when(event.getPlayer()).thenReturn(player);return event; }
    private UserConnectEvent connect(User user) { var event=mock(UserConnectEvent.class);when(event.getUser()).thenReturn(user);return event; }
    private UserDisconnectEvent disconnect(User user) { var event=mock(UserDisconnectEvent.class);when(event.getUser()).thenReturn(user);return event; }
    private void drain() { while (!pending.isEmpty()) pending.remove().run(); }
    private void offer(User user) { adapter.request(user, PACK); drain(); clearInvocations(gui); }

    @Test void configurationSuccessSurvivesLoginOfTheSameUser() {
        User user = user();
        adapter.request(user, PACK);
        adapter.status(user, PACK, true);
        adapter.onUserLogin(login(user, null));
        verifyNoInteractions(gui);

        drain();

        var ordered = inOrder(gui);
        ordered.verify(gui).forget(VIEWER);
        ordered.verify(gui).resourcePackStatus(VIEWER, PACK, false);
        ordered.verify(gui).resourcePackStatus(VIEWER, PACK, true);
        verifyNoMoreInteractions(gui);
        clearInvocations(gui);
        adapter.onUserLogin(login(user, null));
        drain();
        verifyNoInteractions(gui);
    }

    @Test void everyTransitionIsDeferredToMain() {
        User user = user();
        offer(user);
        adapter.status(user, PACK, false);
        adapter.status(user, PACK, true);
        adapter.status(user, PACK, false);
        verifyNoInteractions(gui);

        drain();

        var ordered = inOrder(gui);
        ordered.verify(gui).resourcePackStatus(VIEWER, PACK, false);
        ordered.verify(gui).resourcePackStatus(VIEWER, PACK, true);
        ordered.verify(gui).resourcePackStatus(VIEWER, PACK, false);
    }

    @Test void oldQueuedStatusAndOldPacketsCannotOverwriteTheNewOwner() {
        User oldUser = user(), newUser = user();
        offer(oldUser);
        adapter.status(oldUser, PACK, false);
        adapter.onUserLogin(login(newUser, null));
        adapter.request(newUser, PACK);
        adapter.status(newUser, PACK, true);
        adapter.status(oldUser, PACK, false);

        drain();

        verify(gui).forget(VIEWER);
        verify(gui).resourcePackStatus(VIEWER, PACK, true);
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verifyNoMoreInteractions(gui);
    }

    @Test void queuedDisconnectDoesNotClearTheReplacementConnection() {
        User oldUser = user(), newUser = user();
        offer(oldUser);
        adapter.status(oldUser, PACK, false);
        adapter.onUserDisconnect(disconnect(oldUser));
        adapter.onUserConnect(connect(newUser));
        adapter.request(newUser, PACK);
        adapter.status(newUser, PACK, true);

        drain();

        verify(gui).forget(VIEWER);
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verify(gui).resourcePackStatus(VIEWER, PACK, true);
        verifyNoMoreInteractions(gui);
    }

    @Test void oldDisconnectCannotEvictANewOwner() {
        User oldUser = user(), newUser = user();
        offer(oldUser);
        adapter.status(oldUser, PACK, false);
        adapter.onUserLogin(login(newUser, null));
        adapter.onUserDisconnect(disconnect(oldUser));
        adapter.request(newUser, PACK);
        adapter.status(newUser, PACK, true);

        drain();

        verify(gui).forget(VIEWER);
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verify(gui).resourcePackStatus(VIEWER, PACK, true);
        verifyNoMoreInteractions(gui);
    }

    @Test void disconnectDropsItsQueuedAckAndForgetsItsOwnState() {
        User user = user();
        offer(user);
        adapter.status(user, PACK, true);
        adapter.onUserDisconnect(disconnect(user));

        drain();

        verify(gui).forget(VIEWER);
        verifyNoMoreInteractions(gui);
    }

    @Test void closeDropsAllQueuedAndFutureUpdates() {
        User user = user();
        offer(user);
        adapter.status(user, PACK, true);
        adapter.close();
        adapter.close();
        adapter.status(user, PACK, false);
        adapter.onUserLogin(login(user, null));
        adapter.onUserDisconnect(disconnect(user));

        drain();

        assertTrue(pending.isEmpty());
        verifyNoInteractions(gui);
    }

    @Test void unknownUsersAndPackIdsDoNotProduceUpdates() {
        adapter.status(null, PACK, true);
        adapter.status(user(), null, true);
        adapter.status(mock(User.class), PACK, true);
        assertTrue(pending.isEmpty());
        verifyNoInteractions(gui);
    }

    @Test void unrequestedAcknowledgementsNeverReachTheGui() {
        User user = user();
        adapter.status(user, PACK, true);
        assertTrue(pending.isEmpty());
        offer(user);
        adapter.status(user, UUID.randomUUID(), true);
        assertTrue(pending.isEmpty());
        verifyNoInteractions(gui);
    }

    @Test void offerForANewConfigurationConnectionSupersedesThePreviousUser() {
        User oldUser = user(), newUser = user();
        offer(oldUser);
        adapter.status(oldUser, PACK, false);
        adapter.request(newUser, PACK);
        adapter.status(newUser, PACK, true);
        adapter.onUserLogin(login(newUser, null));
        adapter.onUserDisconnect(disconnect(oldUser));
        drain();

        verify(gui).forget(VIEWER);
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verify(gui).resourcePackStatus(VIEWER, PACK, true);
        verifyNoMoreInteractions(gui);
    }

    @Test void reofferInvalidatesTheLoadedPackBeforeTheNextClientResponse() {
        User user = user();
        offer(user);
        adapter.request(user, PACK);
        verifyNoInteractions(gui);
        drain();
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verifyNoMoreInteractions(gui);
    }

    @Test void lateLoginCannotTakeOwnershipFromANewerConfigurationOffer() {
        User oldUser = user(), newUser = user();
        offer(oldUser);
        adapter.request(newUser, PACK);
        adapter.status(newUser, PACK, true);
        adapter.onUserLogin(login(oldUser, null));
        drain();
        verify(gui).forget(VIEWER);
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verify(gui).resourcePackStatus(VIEWER, PACK, true);
        verifyNoMoreInteractions(gui);
    }
    @Test void configurationAckIsOnlyUsableByThePlayerOfItsConnection() {
        User old=user(),current=user();Player previous=mock(Player.class),next=mock(Player.class);
        when(previous.getUniqueId()).thenReturn(VIEWER);when(next.getUniqueId()).thenReturn(VIEWER);
        offer(old);adapter.onUserLogin(login(old,previous));assertTrue(adapter.owns(previous));
        adapter.request(current,PACK);adapter.status(current,PACK,true);
        assertFalse(adapter.owns(previous));assertFalse(adapter.owns(next));
        adapter.onUserLogin(login(old,previous));assertFalse(adapter.owns(previous));
        adapter.onUserLogin(login(current,next));assertTrue(adapter.owns(next));
        adapter.onUserDisconnect(disconnect(old));drain();assertTrue(adapter.owns(next));
        adapter.close();assertFalse(adapter.owns(next));
    }

    @Test void removingAPackRejectsQueuedAndLateAcknowledgements() {
        User user = user();
        offer(user);
        adapter.status(user, PACK, true);
        adapter.remove(user, PACK);
        adapter.status(user, PACK, true);
        drain();
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verifyNoMoreInteractions(gui);
    }

    @Test void removingAllPacksInvalidatesEveryOfferedId() {
        User user = user();UUID other = UUID.randomUUID();
        offer(user);
        adapter.request(user, other);
        drain();clearInvocations(gui);
        adapter.status(user, PACK, true);
        adapter.status(user, other, true);
        adapter.remove(user, null);
        adapter.status(user, PACK, true);
        adapter.status(user, other, true);
        drain();
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verify(gui).resourcePackStatus(VIEWER, other, false);
        verifyNoMoreInteractions(gui);
    }

    @Test void reofferAfterRemovalCannotReactivateAnOldQueuedAck() {
        User user = user();
        offer(user);
        adapter.status(user, PACK, true);
        adapter.remove(user, PACK);
        adapter.request(user, PACK);
        drain();
        verify(gui).resourcePackStatus(VIEWER, PACK, false);
        verifyNoMoreInteractions(gui);
        adapter.status(user, PACK, true);
        drain();
        verify(gui).resourcePackStatus(VIEWER, PACK, true);
    }

    @Test void cancelledPacketsAreNotDecodedOrForwarded() {
        PacketReceiveEvent event = mock(PacketReceiveEvent.class);
        when(event.isCancelled()).thenReturn(true);
        adapter.onPacketReceive(event);
        verify(event, never()).getUser();
        verify(event, never()).getPacketType();
        verifyNoInteractions(gui);
    }

    @Test void cancelledOffersAreNotDecodedOrTracked() {
        PacketSendEvent event = mock(PacketSendEvent.class);
        when(event.isCancelled()).thenReturn(true);
        adapter.onPacketSend(event);
        verify(event, never()).getUser();
        verify(event, never()).getPacketType();
        verifyNoInteractions(gui);
    }

    @Test void schedulerRejectionLeavesTheGuiStateUntouched() {
        var unavailable = new PacketEventsPackAdapter(gui, action -> { throw new IllegalStateException("disabled"); });
        User user = user();
        assertDoesNotThrow(() -> unavailable.request(user, PACK));
        assertDoesNotThrow(() -> unavailable.status(user, PACK, true));
        verifyNoInteractions(gui);
    }
}
