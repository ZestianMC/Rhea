package de.erethon.rhea.zlib;

import com.zestian.zlib.menu.MenuManager;
import com.zestian.zlib.menu.render.client.ClientCapabilityService;
import de.erethon.rhea.RheaProvider;
import de.erethon.rhea.RheaTemplate;
import com.zestian.zlib.menu.render.client.PlayerPlatform;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Routes compiled GUIs per viewer; unknown clients or missing packs always use the caller's inventory. */
public final class RheaGuiManager implements Listener, AutoCloseable {
    private final ClientCapabilityService capabilities;
    private final Map<UUID, Set<UUID>> loadedPacks = new HashMap<>();
    private record Active(Player player, RheaRequirements requirements, Consumer<Player> fallback) {}
    private final Map<UUID, Active> active = new HashMap<>();
    private final Consumer<String> warning;
    private final Consumer<UUID> beforeOpen;
    private Supplier<? extends RheaProvider> factory;
    private RheaProvider provider;
    private boolean closed;
    /** A tracked pack acknowledgement belongs to this exact connected player, not just its UUID. */
    public interface PackSource extends AutoCloseable { boolean owns(Player player); }
    private PackSource packSource;

    public RheaGuiManager(JavaPlugin plugin, MenuManager menus) {
        this(plugin, menus.getClientCapabilityService(), menus::cancelRefresh);
    }

    public RheaGuiManager(JavaPlugin plugin, ClientCapabilityService capabilities, Consumer<UUID> beforeOpen) {
        this(capabilities, () -> loadAdapter(plugin), plugin.getLogger()::warning, beforeOpen);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    RheaGuiManager(ClientCapabilityService capabilities, Supplier<? extends RheaProvider> factory,
               Consumer<String> warning, Consumer<UUID> beforeOpen) {
        this.capabilities = Objects.requireNonNull(capabilities);
        this.factory = Objects.requireNonNull(factory);
        this.warning = Objects.requireNonNull(warning);
        this.beforeOpen = Objects.requireNonNull(beforeOpen);
    }

    // No modern Paper types are linked until both server and viewer have passed the gate.
    private static RheaProvider loadAdapter(JavaPlugin plugin) {
        try {
            return (RheaProvider) Class.forName("de.erethon.rhea.Rhea")
                    .getConstructor(JavaPlugin.class).newInstance(plugin);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Compiled GUI adapter unavailable", e);
        }
    }

    public void setProviderFactory(Supplier<? extends RheaProvider> factory) {
        closeViews();
        provider = null;
        this.factory = Objects.requireNonNull(factory);
    }

    /** Optional source for Adventure/configuration packs that do not emit Bukkit pack events. */
    public boolean enablePacketEventsTracking(JavaPlugin plugin) {
        if (closed) return false;
        if (packSource != null) return true;
        try {
            packSource = Class.forName("de.erethon.rhea.zlib.adapter.PacketEventsPackAdapter")
                    .asSubclass(PackSource.class).getConstructor(JavaPlugin.class, RheaGuiManager.class).newInstance(plugin, this);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            warning.accept("Resource-pack packet tracking unavailable; unconfirmed GUIs will use inventory");
            return false;
        }
    }

    public boolean canShow(Player player, RheaRequirements requirements) {
        try { return !closed && player != null && player.isOnline() && requirements != null
                && (packSource == null || packSource.owns(player))
                && !PlayerPlatform.isBedrock(player) && capabilities.serverSupportsDialogs()
                && requirements.supports(capabilities.knownProtocolOf(player))
                && loadedPacks.getOrDefault(player.getUniqueId(), Set.of()).contains(requirements.resourcePackId());
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    /** Binding must only prepare the view; item transfers and other effects belong in action callbacks. */
    public void open(Player player, RheaTemplate template, Component title, Component closeLabel,
                     RheaRequirements requirements, Consumer<RheaProvider.Dialog> bindings,
                     Consumer<Player> inventoryFallback) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(inventoryFallback);
        beforeOpen.accept(player.getUniqueId());
        Active previous = active.get(player.getUniqueId());
        if (previous != null && previous.player() != player) close(previous.player());
        if (template != null && canShow(player, requirements)) {
            try {
                if (provider == null) provider = Objects.requireNonNull(factory.get());
                RheaProvider.Dialog dialog = provider.dialog(template, title, closeLabel);
                bindings.accept(dialog);
                dialog.show(player);
                active.put(player.getUniqueId(), new Active(player, requirements, inventoryFallback));
                return;
            } catch (RuntimeException | LinkageError e) {
                warning.accept("Compiled GUI failed for " + template.id() + "; using inventory: " + e.getClass().getSimpleName());
            }
        }
        close(player);
        // Outside the catch: never retry a business action that the fallback may execute.
        inventoryFallback.accept(player);
    }

    public void close(Player player) {
        Active current = active.get(player.getUniqueId());
        if (current != null && current.player() != player) return;
        active.remove(player.getUniqueId());
        if (provider != null) try { provider.close(player); }
        catch (RuntimeException | LinkageError e) { warning.accept("Could not close compiled GUI: " + e.getClass().getSimpleName()); }
    }
    public void closeViews() { active.clear(); if (provider != null) provider.closeAll(); }

    @EventHandler public void resourcePack(PlayerResourcePackStatusEvent event) {
        // PacketEvents validates the connection/offer; Bukkit can also emit stale or unrequested ACKs.
        if (packSource != null) return;
        resourcePackStatus(event.getPlayer().getUniqueId(), event.getID(), event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED);
    }

    /** Main-thread hook for pack senders and packet adapters, including acknowledgements before PlayerJoin. */
    public void resourcePackStatus(UUID viewer, UUID pack, boolean loadedSuccessfully) {
        if (closed || viewer == null || pack == null) return;
        if (loadedSuccessfully)
            loadedPacks.computeIfAbsent(viewer, ignored -> new HashSet<>()).add(pack);
        else {
            Set<UUID> loaded = loadedPacks.get(viewer);
            if (loaded != null) { loaded.remove(pack); if (loaded.isEmpty()) loadedPacks.remove(viewer); }
            Active current = active.get(viewer);
            if (current != null && current.requirements().resourcePackId().equals(pack)) {
                boolean viewing = provider != null && provider.isViewing(current.player());
                close(current.player());
                if (viewing) current.fallback().accept(current.player());
            }
        }
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        if (packSource != null && !packSource.owns(event.getPlayer())) return;
        loadedPacks.remove(event.getPlayer().getUniqueId());
        close(event.getPlayer());
    }

    /** Also called when a configuration connection disconnects without ever creating a Player. */
    public void forget(UUID viewer) {
        loadedPacks.remove(viewer);
        Active current = active.get(viewer);
        if (current != null) close(current.player());
    }

    @EventHandler public void inventory(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Active current = active.get(player.getUniqueId());
            if (current != null && current.player() == player) close(player);
        }
    }

    @Override public void close() {
        closed = true;
        if (packSource != null) try { packSource.close(); }
        catch (Exception | LinkageError error) { warning.accept("Could not stop GUI resource-pack tracking"); }
        closeViews();
        loadedPacks.clear();
        HandlerList.unregisterAll(this);
    }
}
