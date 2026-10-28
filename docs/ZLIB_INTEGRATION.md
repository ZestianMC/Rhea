# zLib integration

This fork owns the HTML compiler, templates, renderer, pack tracking and graphical/inventory routing. zLib owns generic menu utilities and client capabilities. Dependency direction is **consumer → Rhea fork → zLib**; zLib does not import or bundle Rhea. The existing direct `Rhea`, `RheaTemplate`, `RheaText` and `Rhea.SlotLine` APIs remain available without zLib or PacketEvents at runtime.

`RheaGuiManager` is opt-in and requires the host to provide zLib with the `knownProtocolOf` capability API ([zLib PR #5](https://github.com/ZestianMC/zLib/pull/5)). It reuses the host's `MenuManager` and protocol cache, cancels stale menu refreshes, then chooses a presentation per viewer:

```java
MenuManager menus = new MenuManager(plugin);
// Only when PacketEvents is already initialized by the host.
menus.setClientVersionProvider(new PacketEventsVersionProvider());
RheaGuiManager guis = new RheaGuiManager(plugin, menus);
guis.enablePacketEventsTracking(plugin);
RheaTemplate template = RheaTemplate.read(plugin, "rhea/shop.json");
RheaRequirements requirements = new RheaRequirements(minimumProtocol, maximumProtocol, actualPackId);

guis.open(player, template, Component.empty(), Component.text("Close"), requirements,
    dialog -> dialog.slot("balance", balanceLabel).action("purchase", this::purchase),
    viewer -> menus.openMenu("shop-inventory", viewer, replacements));
```

The server must expose Paper dialogs. The Java client must have a known protocol within the compiled pack's interval and report successful loading of its exact UUID. Old/unknown clients, Bedrock, absent packs and initial render failures use the consumer's inventory fallback once. A generic dialog-capable protocol alone is not enough for a version-specific pack. The renderer is loaded lazily behind `RheaProvider`.

`de.erethon.rhea.zlib.adapter.PacketEventsVersionProvider` calls the public PacketEvents API directly: its Spigot API object is a non-public anonymous implementation, so reflecting on that implementation can silently report an unknown version. Installing a resource pack manually does not send a server pack acknowledgement; the server must offer the pack and receive its successful load status.

The host owns native layouts, commands, gameplay, item custody and persistence. Bindings prepare presentation only; business effects belong in action callbacks and existing services. Input inventories keep using zLib's `openInputMenu` and `InputSlots` contract. Virtual selections must close before opening their inventory fallback.

## Pack status and lifecycle

Bukkit status events are used by default. Adventure pack senders, including configuration-phase senders before a `Player` exists, may bypass those events. The optional PacketEvents source observes server pack offers/removals and configuration/play acknowledgements without modifying packets. It accepts only the same connection's offered IDs, retains pre-join confirmations, and checks the exact logged-in player identity. Old connections, removed packs and callbacks from replaced offers cannot enable another session. Duplicate Bukkit events are ignored while packet tracking is active.

Reoffering the required pack invalidates its old confirmation. Revocation transitions an active view to its native fallback; unrelated packs and already-closed views do not reopen it. The host can alternatively forward trusted sender callbacks through `resourcePackStatus(viewerUuid, packUuid, successfullyLoaded)` on the main thread and call `forget` on a configuration disconnect. A missing source fails safely to inventory.

Call `guis.close(player)` when a custom action leaves the view, `guis.closeViews()` before reloading menus, and `guis.close()` on shutdown. Native Close/Escape and replacement views invalidate old action tokens. Opening views and mutating Bukkit/gameplay state belong on the main thread; network callbacks are dispatched there and revalidated.

## Build and packaging

Use Java 25 and build zLib PR #5 with Java 21 first. Until its capability API is released, pass the locally built JAR explicitly:

```sh
./gradlew -PzlibJar=/path/to/zLib/target/zlib-1.9.0.jar build
```

The regular Rhea JAR includes the core and optional integration classes. It does not bundle zLib, PacketEvents, Gson or Paper. Consumers using the integration bundle one compatible zLib and this fork in their plugin JAR; PacketEvents is optional and its lifecycle remains the host's responsibility. Relocate both library namespaces consistently if the consumer shades them. No separate Rhea server plugin is needed.

The compiler stays under `compiler/`; existing JSON and pack assets remain compatible. Node/Chromium are build tools only. Source publication is not a package release. Unit tests cover routing, exact pack status, reconnection, stale callbacks, template validation and upstream text wrapping; real clients, proxy translation and Nexo delivery still need server validation.
