package de.erethon.rhea.zlib.adapter;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.UserConnectEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.configuration.client.WrapperConfigClientResourcePackStatus;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerResourcePackSend;
import com.github.retrooper.packetevents.wrapper.configuration.server.WrapperConfigServerResourcePackRemove;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientResourcePackStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackSend;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResourcePackRemove;
import de.erethon.rhea.zlib.RheaGuiManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Forwards configuration/play pack acknowledgements to the owning GUI manager on the main thread. */
public final class PacketEventsPackAdapter extends PacketListenerAbstract implements RheaGuiManager.PackSource {
    private final RheaGuiManager gui;
    private final Consumer<Runnable> schedule;
    private final ConcurrentHashMap<UUID, User> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<User, Player> players = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<User, ConcurrentHashMap<UUID, Object>> offered = new ConcurrentHashMap<>();
    private EventManager events;
    private volatile boolean closed;

    public PacketEventsPackAdapter(JavaPlugin plugin, RheaGuiManager gui) {
        this(gui, mainThread(plugin));
        events = PacketEvents.getAPI().getEventManager();
        events.registerListener(this);
    }

    PacketEventsPackAdapter(RheaGuiManager gui, Consumer<Runnable> schedule) {
        super(PacketListenerPriority.MONITOR);
        this.gui = Objects.requireNonNull(gui);
        this.schedule = Objects.requireNonNull(schedule);
    }

    private static Consumer<Runnable> mainThread(JavaPlugin plugin) {
        Objects.requireNonNull(plugin);
        BukkitScheduler scheduler = plugin.getServer().getScheduler();
        return task -> scheduler.runTask(plugin, () -> {
            if (plugin.isEnabled()) task.run();
        });
    }

    @Override public void onPacketReceive(PacketReceiveEvent event) {
        if (closed || event.isCancelled()) return;
        if (event.getPacketType() == PacketType.Configuration.Client.RESOURCE_PACK_STATUS) {
            var packet = new WrapperConfigClientResourcePackStatus(event);
            status(event.getUser(), packet.getPackId(),
                    packet.getResult() == WrapperConfigClientResourcePackStatus.Result.SUCCESSFULLY_LOADED);
        } else if (event.getPacketType() == PacketType.Play.Client.RESOURCE_PACK_STATUS) {
            var packet = new WrapperPlayClientResourcePackStatus(event);
            status(event.getUser(), packet.getPackId(),
                    packet.getResult() == WrapperPlayClientResourcePackStatus.Result.SUCCESSFULLY_LOADED);
        }
    }

    @Override public void onPacketSend(PacketSendEvent event) {
        if (closed || event.isCancelled()) return;
        if (event.getPacketType() == PacketType.Configuration.Server.RESOURCE_PACK_SEND) {
            request(event.getUser(), new WrapperConfigServerResourcePackSend(event).getPackId());
        } else if (event.getPacketType() == PacketType.Play.Server.RESOURCE_PACK_SEND) {
            request(event.getUser(), new WrapperPlayServerResourcePackSend(event).getPackId());
        } else if (event.getPacketType() == PacketType.Configuration.Server.RESOURCE_PACK_REMOVE) {
            remove(event.getUser(), new WrapperConfigServerResourcePackRemove(event).getPackId());
        } else if (event.getPacketType() == PacketType.Play.Server.RESOURCE_PACK_REMOVE) {
            remove(event.getUser(), new WrapperPlayServerResourcePackRemove(event).getPackId());
        }
    }

    void request(User user, UUID pack) {
        if (closed || user == null || pack == null || user.getUUID() == null) return;
        connected(user, true);
        UUID viewer = user.getUUID();
        Object offer = new Object();
        offered.compute(user, (ignored, requests) -> {
            if (requests == null) requests = new ConcurrentHashMap<>();
            requests.put(pack, offer);
            return requests;
        });
        main(() -> {
            if (owners.get(viewer) == user && currentOffer(user, pack) == offer)
                gui.resourcePackStatus(viewer, pack, false);
        });
    }

    void status(User user, UUID pack, boolean loaded) {
        if (closed || user == null || pack == null) return;
        UUID viewer = user.getUUID();
        if (viewer == null) return;
        Object offer = currentOffer(user, pack);
        if (owners.get(viewer) != user || offer == null) return;
        main(() -> {
            if (owners.get(viewer) == user && currentOffer(user, pack) == offer)
                gui.resourcePackStatus(viewer, pack, loaded);
        });
    }

    void remove(User user, UUID pack) {
        if (closed || user == null) return;
        UUID viewer = user.getUUID();
        if (viewer == null || owners.get(viewer) != user) return;
        if (pack == null) {
            var requests = offered.remove(user);
            if (requests != null) for (UUID id : List.copyOf(requests.keySet())) removed(user, viewer, id);
        } else {
            var requests = offered.get(user);
            if (requests != null) requests.remove(pack);
            removed(user, viewer, pack);
        }
    }

    private Object currentOffer(User user, UUID pack) {
        var requests = offered.get(user);
        return requests == null ? null : requests.get(pack);
    }

    private void removed(User user, UUID viewer, UUID pack) {
        main(() -> {
            if (owners.get(viewer) == user && currentOffer(user, pack) == null)
                gui.resourcePackStatus(viewer, pack, false);
        });
    }

    @Override public void onUserConnect(UserConnectEvent event) { connected(event.getUser(), true); }
    @Override public void onUserLogin(UserLoginEvent event) {
        User user = event.getUser();
        connected(user, false);
        if (!closed && user != null && user.getUUID() != null && owners.get(user.getUUID()) == user
                && event.getPlayer() instanceof Player player) players.put(user, player);
    }

    @Override public boolean owns(Player player) {
        if (closed || player == null) return false;
        User user = owners.get(player.getUniqueId());
        return user != null && players.get(user) == player;
    }

    private void connected(User user, boolean replace) {
        if (closed || user == null) return;
        UUID viewer = user.getUUID();
        if (viewer == null) return;
        User previous = replace ? owners.put(viewer, user) : owners.putIfAbsent(viewer, user);
        if (!replace && previous != null && previous != user) return;
        if (previous != null && previous != user) { offered.remove(previous); players.remove(previous); }
        // Login can follow a successful configuration ACK from this same connection.
        if (previous != user) main(() -> {
            if (owners.get(viewer) == user) gui.forget(viewer);
        });
    }

    @Override public void onUserDisconnect(UserDisconnectEvent event) {
        User user = event.getUser();
        if (closed || user == null) return;
        UUID viewer = user.getUUID();
        offered.remove(user);
        players.remove(user);
        if (viewer != null && owners.remove(viewer, user)) main(() -> {
            if (!owners.containsKey(viewer)) gui.forget(viewer);
        });
    }

    private void main(Runnable action) {
        if (closed) return;
        try {
            schedule.accept(() -> { if (!closed) action.run(); });
        } catch (RuntimeException unavailable) {
            // A disabled plugin or rejected scheduler must not mutate the main-thread GUI state.
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        owners.clear();
        players.clear();
        offered.clear();
        if (events != null) events.unregisterListener(this);
    }
}
