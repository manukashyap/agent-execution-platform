package com.conversive.aep.definition.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/** Per-execution limits as submitted; absent values default to the tenant's ceilings when frozen. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Limits(
        @JsonProperty("max_cost_usd") @JsonAlias("maxCostUsd") BigDecimal maxCostUsd,
        @JsonProperty("max_tokens") @JsonAlias("maxTokens") Long maxTokens,
        @JsonProperty("max_node_executions") @JsonAlias("maxNodeExecutions") Integer maxNodeExecutions) {
}
