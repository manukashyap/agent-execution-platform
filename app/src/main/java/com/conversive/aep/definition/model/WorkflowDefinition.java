package com.conversive.aep.definition.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A workflow definition exactly as submitted (snake_case, the PDF §4 shape). Every field except
 * {@code nodes} may be absent; defaults are applied by freezing, structural checks by the validator.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
        @JsonProperty("workflow_id") @JsonAlias("workflowId") String workflowId,
        Integer version,
        List<NodeSpec> nodes,
        @JsonProperty("max_duration_s") @JsonAlias("maxDurationS") Integer maxDurationS,
        @JsonProperty("max_parallel") @JsonAlias("maxParallel") Integer maxParallel,
        Limits limits) {

    public WorkflowDefinition {
        // Null elements are kept so the validator can report them instead of the parser failing.
        nodes = nodes == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(nodes));
    }
}
