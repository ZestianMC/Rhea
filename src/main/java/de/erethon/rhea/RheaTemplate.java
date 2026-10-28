package de.erethon.rhea;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

/** A page descriptor produced by the Rhea compiler.
 * @param id page ID
 * @param namespace resource-pack namespace
 * @param font bitmap font key
 * @param width canvas width in pixels
 * @param height canvas height in pixels
 * @param rows bitmap rows
 * @param slots live-text regions
 * @param actions authored action IDs
 * @param resourceBase location of generated font files in the plugin JAR
 */
public record RheaTemplate(String id, String namespace, String font, int width, int height,
                           List<List<Segment>> rows, List<Slot> slots, List<String> actions, String resourceBase) {
    private static final Gson GSON = new Gson();

    public RheaTemplate {
        if (id == null || !id.matches("[a-z0-9_-]+") || namespace == null || !namespace.matches("[a-z0-9_.-]+")
                || font == null || !font.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || width < 1 || width > 1000 || height < 9 || height > 720 || height % 9 != 0
                || rows == null || rows.size() * 9 != height || slots == null || actions == null)
            throw new IllegalArgumentException("Invalid compiled GUI template");
        actions = List.copyOf(actions);
        if (new java.util.HashSet<>(actions).size() != actions.size()
                || actions.stream().anyMatch(action -> !action.matches("[a-z0-9:_-]+")))
            throw new IllegalArgumentException("Invalid or duplicate GUI action");
        rows = rows.stream().map(List::copyOf).toList();
        for (var row : rows) for (var segment : row)
            if (segment.glyph() == null || segment.glyph().codePointCount(0, segment.glyph().length()) != 1
                    || segment.action() != null && !actions.contains(segment.action()))
                throw new IllegalArgumentException("Invalid GUI glyph/action");
        slots = List.copyOf(slots);
        var ids = new java.util.HashSet<String>();
        for (var slot : slots) {
            if (slot.id() == null || !slot.id().matches("[a-z0-9:_-]+") || !ids.add(slot.id())
                    || slot.x() < 0 || slot.y() < 0 || slot.width() < 1 || slot.height() < 1
                    || slot.x() > width || slot.width() > width - slot.x() || slot.y() > height || slot.height() > height - slot.y()
                    || slot.fontShift() < -height || slot.fontShift() > height
                    || !java.util.Set.of("left", "center", "right").contains(slot.align() == null ? "left" : slot.align())
                    || slot.action() != null && !actions.contains(slot.action())
                    || slot.font() != null && !slot.font().matches("[a-z0-9_.-]+")
                    || slot.fontFile() != null && !slot.fontFile().matches("[a-z0-9_-]+\\.ttf")
                    || slot.fontFile() != null && (!Double.isFinite(slot.fontSize()) || slot.fontSize() < 1
                    || slot.fontOversample() != 2 && slot.fontOversample() != 4))
                throw new IllegalArgumentException("Invalid GUI slot");
            int lines = slot.height() / slot.lineAdvance();
            int last = (slot.y() + 7 + (lines - 1) * slot.lineAdvance()) / 9 + Math.max(0, Math.ceilDiv(slot.fontShift(), 9));
            if (lines < 1 || last >= rows.size()) throw new IllegalArgumentException("GUI text outside canvas");
        }
    }

    public RheaTemplate(String id, String namespace, String font, int width, int height,
                        List<List<Segment>> rows, List<Slot> slots, List<String> actions) {
        this(id, namespace, font, width, height, rows, slots, actions, null);
    }

    /** One bitmap glyph and its optional action ID.
     * @param glyph bitmap character
     * @param action action ID, or null
     */
    public record Segment(String glyph, String action) { }
    /** A bounded live-text region. {@code align} is left, center, or right.
     * @param id slot ID
     * @param x left position in pixels
     * @param y top position in pixels
     * @param width region width in pixels
     * @param height region height in pixels
     * @param action containing action ID, or null
     * @param align text alignment
     * @param fontShift measured vertical text shift in pixels
     * @param font compiled resource-pack font name
     * @param fontFile local font file bundled beside the template
     * @param fontSize CSS font size in pixels
     * @param fontOversample Minecraft font sampling factor
     * @param lineHeight CSS line height in pixels
     */
    public record Slot(String id, int x, int y, int width, int height, String action, String align, int fontShift,
                       String font, String fontFile, double fontSize, int fontOversample, int lineHeight) {
        public Slot(String id, int x, int y, int width, int height, String action, String align, int fontShift) {
            this(id, x, y, width, height, action, align, fontShift, null, null, 0, 0, 0);
        }
        /** Pixels between live text lines. */
        public int lineAdvance() { return lineHeight > 0 ? lineHeight : 18; }
    }

    /** Loads a template and remembers where its generated font files sit in the plugin JAR. */
    public static RheaTemplate read(JavaPlugin plugin, String path) {
        if (path == null || !path.endsWith(".json") || path.contains("..") || path.contains("\\") || path.startsWith("/"))
            throw new IllegalArgumentException("Expected a relative template JSON path");
        RheaTemplate page = read(plugin.getResource(path));
        return new RheaTemplate(page.id, page.namespace, page.font, page.width, page.height,
                page.rows, page.slots, page.actions, path.substring(0, path.lastIndexOf('/') + 1));
    }

    /** Reads a descriptor bundled in the plugin JAR.
     * @param stream descriptor input
     * @return compiled page
     */
    public static RheaTemplate read(InputStream stream) {
        if (stream == null) throw new IllegalArgumentException("Missing Rhea template");
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RheaTemplate template = GSON.fromJson(reader, RheaTemplate.class);
            if (template == null || template.id == null || template.namespace == null || template.font == null ||
                    template.rows == null || template.slots == null || template.actions == null ||
                    template.width < 1 || template.width > 1000 || template.height != template.rows.size() * 9) {
                throw new IllegalArgumentException("Invalid Rhea template");
            }
            return template;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot read compiled GUI template", ex);
        }
    }

    /** Finds a live-text region by its authored ID.
     * @param id slot ID
     * @return matching slot
     */
    public Slot slot(String id) {
        return slots.stream().filter(slot -> slot.id.equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Rhea slot: " + id));
    }
}
