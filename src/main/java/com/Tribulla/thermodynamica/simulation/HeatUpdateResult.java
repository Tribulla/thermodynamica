package com.Tribulla.thermodynamica.simulation;

import java.util.List;

/**
 * Result of a chunk simulation pass — a batch of temperature updates
 * to be applied on the main thread.
 */
public class HeatUpdateResult {
    final ChunkHeatKey chunkKey;
    final List<HeatUpdate> updates;

    public HeatUpdateResult(ChunkHeatKey chunkKey, List<HeatUpdate> updates) {
        this.chunkKey = chunkKey;
        this.updates = updates;
    }
}
