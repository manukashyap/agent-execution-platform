package com.conversive.aep.nodes;

/**
 * @param mockLlm        replace live LLM calls with canned output in DRY_RUN
 * @param allowReadOnly  let READ_ONLY tools and GET-style HTTP nodes run live in DRY_RUN
 */
public record DryRunOptions(boolean mockLlm, boolean allowReadOnly) {

    public static DryRunOptions defaults() {
        return new DryRunOptions(false, true);
    }
}
