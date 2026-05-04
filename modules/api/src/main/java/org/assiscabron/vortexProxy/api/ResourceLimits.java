package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ResourceLimits;

public record ResourceLimits(
        int maxPlayers,
        int maxEntities,
        int luaInstructionBudgetPerTick,
        int memoryMb
) {
    public ResourceLimits {
        if (maxPlayers < 1) {
            throw new IllegalArgumentException("maxPlayers must be positive");
        }
        if (maxEntities < 0) {
            throw new IllegalArgumentException("maxEntities cannot be negative");
        }
        if (luaInstructionBudgetPerTick < 1) {
            throw new IllegalArgumentException("luaInstructionBudgetPerTick must be positive");
        }
        if (memoryMb < 16) {
            throw new IllegalArgumentException("memoryMb must be at least 16");
        }
    }
}
