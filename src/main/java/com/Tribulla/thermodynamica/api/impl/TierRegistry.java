package com.Tribulla.thermodynamica.api.impl;

import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.api.TierResolution;
import com.Tribulla.thermodynamica.api.TierResolution.MatchType;
import com.Tribulla.thermodynamica.api.TierResolution.Source;
import com.Tribulla.thermodynamica.config.HeatConfigManager;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TierRegistry {

    public static class Entry implements Comparable<Entry> {
        final ResourceLocation block;
        final HeatTier tier;
        final Source source;
        final MatchType matchType;
        final int priority;
        final long timestamp;
        @Nullable
        final String detail;

        Entry(ResourceLocation block, HeatTier tier, Source source, MatchType matchType,
                int priority, long timestamp, @Nullable String detail) {
            this.block = block;
            this.tier = tier;
            this.source = source;
            this.matchType = matchType;
            this.priority = priority;
            this.timestamp = timestamp;
            this.detail = detail;
        }

        @Override
        public int compareTo(Entry other) {
            int cmp = Integer.compare(other.priority, this.priority);
            if (cmp != 0)
                return cmp;
            cmp = Integer.compare(this.matchType.ordinal(), other.matchType.ordinal());
            if (cmp != 0)
                return cmp;
            return Long.compare(this.timestamp, other.timestamp);
        }
    }

    private final HeatConfigManager configManager;
    private final Map<ResourceLocation, List<Entry>> entries = new ConcurrentHashMap<>();
    private final Map<ResourceLocation, TierResolution> cache = new ConcurrentHashMap<>();
    private long registrationCounter = 0;

    public TierRegistry(HeatConfigManager configManager) {
        this.configManager = configManager;
    }

    public void registerRuntime(ResourceLocation block, HeatTier tier) {
        register(block, tier, Source.RUNTIME_API, MatchType.BLOCK, sourcePriority(Source.RUNTIME_API), "Runtime API");
    }

    public void registerModConfig(ResourceLocation block, HeatTier tier, String modId) {
        register(block, tier, Source.MOD_CONFIG, MatchType.BLOCK, sourcePriority(Source.MOD_CONFIG), "Mod: " + modId);
    }

    public void registerDataPack(ResourceLocation block, HeatTier tier, String packName) {
        register(block, tier, Source.DATA_PACK, MatchType.BLOCK, sourcePriority(Source.DATA_PACK),
                "DataPack: " + packName);
    }

    public void registerDefault(ResourceLocation block, HeatTier tier) {
        register(block, tier, Source.LIBRARY_DEFAULT, MatchType.BLOCK, sourcePriority(Source.LIBRARY_DEFAULT),
                "Library default");
    }

    public void registerTag(ResourceLocation tag, HeatTier tier, Source source, String detail) {
        register(tag, tier, source, MatchType.BLOCK_TAG, sourcePriority(source), detail);
    }

    private synchronized void register(ResourceLocation block, HeatTier tier, Source source,
            MatchType matchType, int priority, @Nullable String detail) {
        Entry entry = new Entry(block, tier, source, matchType, priority, registrationCounter++, detail);
        entries.computeIfAbsent(block, k -> new ArrayList<>()).add(entry);
        entries.get(block).sort(null);
        cache.remove(block);
    }

    @Nullable
    public TierResolution resolve(ResourceLocation block) {
        return cache.computeIfAbsent(block, this::resolveUncached);
    }

    @Nullable
    private TierResolution resolveUncached(ResourceLocation block) {
        List<Entry> blockEntries = entries.get(block);
        if (blockEntries == null || blockEntries.isEmpty()) {
            return null;
        }
        Entry winner = blockEntries.get(0);
        return new TierResolution(
                winner.block, winner.tier, winner.source,
                winner.priority, winner.matchType, winner.detail);
    }

    public void clearSource(Source source) {
        for (Map.Entry<ResourceLocation, List<Entry>> entry : entries.entrySet()) {
            entry.getValue().removeIf(e -> e.source == source);
            if (entry.getValue().isEmpty()) {
                entries.remove(entry.getKey());
            }
        }
        cache.clear();
    }

    private static int sourcePriority(Source source) {
        return switch (source) {
            case RUNTIME_API -> 1000;
            case MOD_CONFIG -> 750;
            case DATA_PACK -> 500;
            case LIBRARY_DEFAULT -> 250;
            case AMBIENT_DEFAULT -> 0;
        };
    }

    public Map<ResourceLocation, List<Entry>> getAllEntries() {
        return Collections.unmodifiableMap(entries);
    }
}
