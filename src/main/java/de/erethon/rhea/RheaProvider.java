package de.erethon.rhea;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.List;
import de.erethon.rhea.Rhea.SlotLine;
import java.util.function.Consumer;

/** Compiled-page backend. Plugins bind data and actions without depending on its implementation. */
public interface RheaProvider {
    Dialog dialog(RheaTemplate template, Component title, Component closeLabel);
    void close(Player player);
    boolean isViewing(Player player);
    void closeAll();

    interface Dialog {
        Dialog slot(String id, Component value);
        Dialog lines(String id, List<SlotLine> value);
        Dialog action(String id, Consumer<Player> callback);
        Dialog hover(String id, Component value);
        void show(Player player);
    }
}
