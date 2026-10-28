package de.erethon.rhea.zlib;

import com.zestian.zlib.menu.render.client.ClientCapabilityService;
import java.util.Objects;
import java.util.UUID;

/** Exact supported protocol range and the pack containing this GUI; a generic dialog is not enough. */
public record RheaRequirements(int minimumProtocol, int maximumProtocol, UUID resourcePackId) {
    public RheaRequirements {
        Objects.requireNonNull(resourcePackId, "resourcePackId");
        if (minimumProtocol < ClientCapabilityService.PROTOCOL_DIALOGS || maximumProtocol < minimumProtocol)
            throw new IllegalArgumentException("Invalid GUI protocol range");
    }
    public boolean supports(int protocol) { return protocol >= minimumProtocol && protocol <= maximumProtocol; }
}
