package com.yiwei.midplat.platform;

public record RuntimeSettings(
        String searchMode,
        int searchTopK,
        int ragTopK,
        int maxContextLength,
        int statsListingLimit,
        int dataQueryDefaultLimit,
        int dataQueryMaxLimit,
        boolean rewriteEnabled,
        int rewriteMaxQueries,
        boolean rerankEnabled,
        boolean searchEnableRerank,
        boolean ragEnableRerank,
        int rerankEvidenceThreshold) {

    public static RuntimeSettings defaults() {
        return new RuntimeSettings("HYBRID", 5, 20, 8000, 50, 10, 50, false, 0, false, false, false, 35);
    }

    public RuntimeSettings apply(RuntimeSettingsPatch patch) {
        if (patch == null) {
            return this;
        }
        return new RuntimeSettings(
                patch.searchMode() == null || patch.searchMode().isBlank() ? searchMode : patch.searchMode(),
                patch.searchTopK() == null ? searchTopK : patch.searchTopK(),
                patch.ragTopK() == null ? ragTopK : patch.ragTopK(),
                patch.maxContextLength() == null ? maxContextLength : patch.maxContextLength(),
                patch.statsListingLimit() == null ? statsListingLimit : patch.statsListingLimit(),
                patch.dataQueryDefaultLimit() == null ? dataQueryDefaultLimit : patch.dataQueryDefaultLimit(),
                patch.dataQueryMaxLimit() == null ? dataQueryMaxLimit : patch.dataQueryMaxLimit(),
                patch.rewriteEnabled() == null ? rewriteEnabled : patch.rewriteEnabled(),
                patch.rewriteMaxQueries() == null ? rewriteMaxQueries : patch.rewriteMaxQueries(),
                patch.rerankEnabled() == null ? rerankEnabled : patch.rerankEnabled(),
                patch.searchEnableRerank() == null ? searchEnableRerank : patch.searchEnableRerank(),
                patch.ragEnableRerank() == null ? ragEnableRerank : patch.ragEnableRerank(),
                patch.rerankEvidenceThreshold() == null ? rerankEvidenceThreshold : patch.rerankEvidenceThreshold());
    }
}

record RuntimeSettingsPatch(
        String searchMode,
        Integer searchTopK,
        Integer ragTopK,
        Integer maxContextLength,
        Integer statsListingLimit,
        Integer dataQueryDefaultLimit,
        Integer dataQueryMaxLimit,
        Boolean rewriteEnabled,
        Integer rewriteMaxQueries,
        Boolean rerankEnabled,
        Boolean searchEnableRerank,
        Boolean ragEnableRerank,
        Integer rerankEvidenceThreshold) {}
