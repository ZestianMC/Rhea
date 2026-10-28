package de.erethon.rhea;

import net.kyori.adventure.text.Component;
import de.erethon.rhea.zlib.RheaRequirements;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RheaTemplateTest {
    private static final String JSON = """
        {"id":"test","namespace":"demo","font":"demo:ui","width":9,"height":18,
        "rows":[[{"glyph":"a","action":"click"}],[{"glyph":"b"}]],
        "actions":["click"],"slots":[{"id":"name","x":0,"y":0,"width":9,"height":9,
        "action":"click","align":"left","fontShift":0,"lineHeight":9}]}
        """;
    private RheaTemplate read(String json) { return RheaTemplate.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))); }
    @Test void existingCompilerFormatLoadsAndItsCollectionsAreImmutable() {
        var template = read(JSON); assertEquals(9, template.width()); assertEquals("click", template.slot("name").action());
        assertThrows(UnsupportedOperationException.class, () -> template.rows().getFirst().clear());
        assertThrows(UnsupportedOperationException.class, () -> template.actions().clear());
    }
    @Test void rejectsBadDimensionsDuplicateActionsAndUnknownTargets() {
        for (String invalid : List.of(JSON.replace("\"width\":9", "\"width\":1001"), JSON.replace("\"height\":18", "\"height\":19"),
                JSON.replace("\"actions\":[\"click\"]", "\"actions\":[\"click\",\"click\"]"), JSON.replace("\"action\":\"click\"", "\"action\":\"missing\"")))
            assertThrows(IllegalArgumentException.class, () -> read(invalid));
    }
    @Test void rejectsClippedTextAndUnsafeFontResourcePaths() {
        assertThrows(IllegalArgumentException.class, () -> read(JSON.replace("\"y\":0", "\"y\":18")));
        assertThrows(IllegalArgumentException.class, () -> read(JSON.replace("\"lineHeight\":9", "\"lineHeight\":9,\"fontFile\":\"../other.ttf\"")));
    }
    @Test void measuredLinesAndPackRequirementsRejectInvalidValues() {
        for (double width : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, .1})
            assertThrows(IllegalArgumentException.class, () -> new Rhea.SlotLine(Component.empty(), width));
        assertDoesNotThrow(() -> new Rhea.SlotLine(Component.empty(), .25));
        assertThrows(IllegalArgumentException.class, () -> new RheaRequirements(770, 777, UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new RheaRequirements(777, 776, UUID.randomUUID()));
    }
}
