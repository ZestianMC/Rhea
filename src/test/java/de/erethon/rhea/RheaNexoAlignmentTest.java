package de.erethon.rhea;

import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Regression fixture records the spacing values found in the player's generated Nexo pack. */
class RheaNexoAlignmentTest {
    private Map<Integer,Double> sentSpacing() {
        try(var reader=new InputStreamReader(getClass().getResourceAsStream("/nexo-truncated-spacing.json"),StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject().entrySet().stream()
                .collect(Collectors.toMap(e->e.getKey().codePointAt(0),e->e.getValue().getAsDouble()));
        } catch(Exception error) {throw new IllegalStateException(error);}
    }
    private double advance(String spacing) {
        var values=sentSpacing();return spacing.codePoints().mapToDouble(values::get).sum();
    }
    private void assertRowWidth(int slots,int slotWidth,int textWidth) {
        double rowWidth=900;
        for(int i=0;i<slots;i++) {
            var slot=new RheaTemplate.Slot("name",648+i*48,0,slotWidth,9,null,"center",0);
            var line=new Rhea.SlotLine(Component.empty(),textWidth);
            double x=slot.x()+Rhea.horizontalOffset(slot,line);
            rowWidth+=advance(Rhea.spaces(-900)+Rhea.spaces(x))+textWidth+advance(Rhea.spaces(900-x-textWidth));
        }
        assertEquals(900,rowWidth,"Each native-text overlay must restore the row cursor even with Nexo's integer spacing");
    }
    @Test void emptyInventoryLabelsDoNotShortenTheirBitmapRow(){assertRowWidth(5,43,0);}
    @Test void emptyMixtureLabelsDoNotShortenTheirBitmapRow(){assertRowWidth(5,48,23);}
    @Test void chanceHeadingDoesNotShiftThePanelBorder(){assertRowWidth(1,189,52);}
    @Test void integerSpacingSurvivesTheSentPackUnchanged(){
        for(int width:new int[]{-900,-512,-1,0,1,512,900})assertEquals(width,advance(Rhea.spaces(width)));
    }
}
