package com.Tribulla.thermodynamica.api;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public class TierResolution {
    public enum Source {
        RUNTIME_API,
        MOD_CONFIG,
        DATA_PACK,
        LIBRARY_DEFAULT,
        AMBIENT_DEFAULT
    }

    public enum MatchType {
        BLOCK_STATE,
        BLOCK,
        BLOCK_NAME,
        BLOCK_TAG
    }

    private final ResourceLocation block;
    private final HeatTier tier;
    private final Source source;
    private final int priority;
    private final MatchType matchType;
    @Nullable
    private final String detail;

    public TierResolution(ResourceLocation block, HeatTier tier, Source source, int priority,
            MatchType matchType, @Nullable String detail) {
        this.block = block;
        this.tier = tier;
        this.source = source;
        this.priority = priority;
        this.matchType = matchType;
        this.detail = detail;
    }

    public ResourceLocation getBlock() {
        return block;
    }

    public HeatTier getTier() {
        return tier;
    }

    public Source getSource() {
        return source;
    }

    public int getPriority() {
        return priority;
    }

    public MatchType getMatchType() {
        return matchType;
    }

    @Nullable
    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "TierResolution{block=" + block + ", tier=" + tier + ", source=" + source +
                ", priority=" + priority + ", match=" + matchType +
                (detail != null ? ", detail=" + detail : "") + "}";
    }
}
