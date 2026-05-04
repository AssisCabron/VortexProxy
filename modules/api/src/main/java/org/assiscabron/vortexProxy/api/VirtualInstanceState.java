package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.VirtualInstanceState;

public enum VirtualInstanceState {
    CREATED,
    RENDERING_HOME,
    SELECTING_EXPERIENCE,
    RENDERING_EXPERIENCE,
    TRANSFERRING,
    CLOSED,
    FAILED
}
