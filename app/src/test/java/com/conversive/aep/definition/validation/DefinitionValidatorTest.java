package com.conversive.aep.definition.validation;

import static com.conversive.aep.definition.validation.ValidationCodes.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.definition.DefinitionProperties;
import com.conversive.aep.definition.InMemoryToolCatalog;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.WorkflowDefinition;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.tenancy.TenantLimits;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** One row per validator error and warning of 06 §4.3 (plus structural checks). */
class DefinitionValidatorTest {

    static final TenantLimits CEILINGS = TenantLimits.defaults(new com.conversive.aep.common.TenantId("t_dev"));

    private final DefinitionValidator validator =
            new DefinitionValidator(DefinitionProperties.defaults(), new InMemoryToolCatalog());

    private static String wf(String nodes) {
        return wf(nodes, "");
    }

    private static String wf(String nodes, String extra) {
        return "{\"workflow_id\":\"w\",\"version\":1,\"nodes\":[" + nodes + "]" + extra + "}";
    }

    private static final String HTTP_A = "{\"id\":\"a\",\"type\":\"http\",\"config\":{\"url\":\"http://x\"}}";

    private ValidationReport validate(String json) {
        WorkflowDefinition def = DefinitionCodec.parse(DefinitionCodec.readTree(json));
        return validator.validate(def, CEILINGS);
    }

    private static List<String> codes(List<ValidationReport.Issue> issues) {
        return issues.stream().map(ValidationReport.Issue::code).toList();
    }

    @Test
    void pdfExampleIsValidWithoutWarnings() {
        ValidationReport report = validator.validate(Fixtures.pdfExampleDefinition(), CEILINGS);

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void sideEffectDefaultsAndExactFloorAreValid() {
        ValidationReport report = validate(wf("{\"id\":\"a\",\"type\":\"http\",\"side_effecting\":true,"
                + "\"timeout_s\":10,\"schedule_to_close_s\":25,\"retry\":{\"max_attempts\":3},"
                + "\"config\":{\"url\":\"u\"}},"
                + "{\"id\":\"b\",\"type\":\"http\",\"depends_on\":[\"a\"],\"side_effecting\":true,"
                + "\"config\":{\"url\":\"u\"}},"
                + "{\"id\":\"c\",\"type\":\"http\",\"depends_on\":[\"b\"],\"retry\":{\"max_attempts\":1},"
                + "\"config\":{\"url\":\"u\"}}"));

        assertThat(report.errors()).isEmpty();
    }

    @Test
    void conditionWithBranchesAndFanOutIsValid() {
        ValidationReport report = validate(wf(HTTP_A + ","
                + "{\"id\":\"c\",\"type\":\"condition\",\"config\":{\"left\":\"$.a.status\",\"op\":\"eq\",\"right\":200,"
                + "\"then\":[\"ok\"],\"else\":[\"ko\"]}},"
                + "{\"id\":\"ok\",\"type\":\"llm\",\"depends_on\":[\"c\"],\"for_each\":{\"items\":\"$.a.items\","
                + "\"max_concurrency\":10},\"config\":{\"prompt\":\"p\",\"tools\":[\"crm.get\"]}},"
                + "{\"id\":\"ko\",\"type\":\"mcp\",\"depends_on\":[\"c\"],\"config\":{\"tool\":\"leads.fetch\"}}"));

        assertThat(report.errors()).isEmpty();
        assertThat(report.warnings()).isEmpty();
    }

    static Stream<Arguments> errorRows() {
        String fiftyOne = IntStream.range(0, 51)
                .mapToObj(i -> "{\"id\":\"n" + i + "\",\"type\":\"http\",\"config\":{\"url\":\"http://x\"}}")
                .collect(Collectors.joining(","));
        String wideRoots = IntStream.range(0, 3)
                .mapToObj(i -> "{\"id\":\"r" + i + "\",\"type\":\"http\",\"depends_on\":[],\"config\":{\"url\":\"u\"},"
                        + "\"for_each\":{\"items\":\"$.input.items\",\"max_concurrency\":40}}")
                .collect(Collectors.joining(","));
        return Stream.of(
                row("missing workflow_id", "{\"version\":1,\"nodes\":[" + HTTP_A + "]}", MISSING_FIELD),
                row("no nodes", wf(""), MISSING_FIELD),
                row("duplicate node id", wf(HTTP_A + "," + HTTP_A), DUPLICATE_NODE_ID),
                row("reserved node id 'input'", wf("{\"id\":\"input\",\"type\":\"http\",\"config\":{\"url\":\"u\"}}"),
                        INVALID_NODE_ID),
                row("cycle", wf("{\"id\":\"a\",\"type\":\"http\",\"depends_on\":[\"b\"],\"config\":{\"url\":\"u\"}},"
                        + "{\"id\":\"b\",\"type\":\"http\",\"depends_on\":[\"a\"],\"config\":{\"url\":\"u\"}}"), CYCLE),
                row("self dependency", wf("{\"id\":\"a\",\"type\":\"http\",\"depends_on\":[\"a\"],"
                        + "\"config\":{\"url\":\"u\"}}"), CYCLE),
                row("missing dependency", wf("{\"id\":\"a\",\"type\":\"http\",\"depends_on\":[\"nope\"],"
                        + "\"config\":{\"url\":\"u\"}}"), MISSING_DEPENDENCY),
                row("more than 50 nodes", wf(fiftyOne), TOO_MANY_NODES),
                row("static width over 100", wf(wideRoots), WIDTH_EXCEEDED),
                row("max_parallel over 100", wf(HTTP_A, ",\"max_parallel\":101"), WIDTH_EXCEEDED),
                row("forEach.maxConcurrency over 100", wf("{\"id\":\"a\",\"type\":\"http\",\"config\":{\"url\":\"u\"},"
                        + "\"for_each\":{\"items\":\"$.input.x\",\"max_concurrency\":101}}"), FANOUT_CONCURRENCY_EXCEEDED),
                row("forEach items not a path", wf("{\"id\":\"a\",\"type\":\"http\",\"config\":{\"url\":\"u\"},"
                        + "\"for_each\":{\"items\":\"items\"}}"), FOR_EACH_INVALID),
                row("unknown node type", wf("{\"id\":\"a\",\"type\":\"shell\",\"config\":{}}"), UNKNOWN_NODE_TYPE),
                row("unknown mcp tool", wf("{\"id\":\"a\",\"type\":\"mcp\",\"config\":{\"tool\":\"fs.delete\"}}"),
                        UNKNOWN_TOOL),
                row("unknown llm tool", wf("{\"id\":\"a\",\"type\":\"llm\",\"config\":{\"prompt\":\"p\","
                        + "\"tools\":[\"fs.delete\"]}}"), UNKNOWN_TOOL),
                row("llm tool not read-only", wf("{\"id\":\"a\",\"type\":\"llm\",\"config\":{\"prompt\":\"p\","
                        + "\"tools\":[\"payments.charge\"]}}"), AI_TOOL_NOT_READ_ONLY),
                row("http without url", wf("{\"id\":\"a\",\"type\":\"http\",\"config\":{}}"), INVALID_CONFIG),
                row("mcp without tool", wf("{\"id\":\"a\",\"type\":\"mcp\",\"config\":{}}"), INVALID_CONFIG),
                row("condition does not parse", wf(HTTP_A + ",{\"id\":\"c\",\"type\":\"condition\","
                        + "\"config\":{\"left\":\"a.status\",\"op\":\"eq\",\"right\":1}}"), CONDITION_INVALID),
                row("condition unknown op", wf(HTTP_A + ",{\"id\":\"c\",\"type\":\"condition\","
                        + "\"config\":{\"left\":\"$.a.status\",\"op\":\"like\",\"right\":1}}"), CONDITION_INVALID),
                row("condition refers to unknown node", wf(HTTP_A + ",{\"id\":\"c\",\"type\":\"condition\","
                        + "\"config\":{\"left\":\"$.zz.status\",\"op\":\"exists\"}}"), CONDITION_INVALID),
                row("condition branch not downstream", wf(HTTP_A + ",{\"id\":\"c\",\"type\":\"condition\","
                        + "\"config\":{\"left\":\"$.a.s\",\"op\":\"exists\",\"then\":[\"a\"]}}"), CONDITION_BRANCH_INVALID),
                row("timeout below cap", wf("{\"id\":\"a\",\"type\":\"http\",\"timeout_s\":0,\"config\":{\"url\":\"u\"}}"),
                        TIMEOUT_OUT_OF_RANGE),
                row("timeout above cap", wf("{\"id\":\"a\",\"type\":\"http\",\"timeout_s\":301,"
                        + "\"config\":{\"url\":\"u\"}}"), TIMEOUT_OUT_OF_RANGE),
                row("retry.maxAttempts over 5", wf("{\"id\":\"a\",\"type\":\"http\",\"retry\":{\"max_attempts\":6},"
                        + "\"config\":{\"url\":\"u\"}}"), RETRY_OUT_OF_RANGE),
                row("ScheduleToClose < StartToClose + lease grace", wf("{\"id\":\"a\",\"type\":\"http\",\"timeout_s\":30,"
                        + "\"schedule_to_close_s\":34,\"config\":{\"url\":\"u\"}}"), SCHEDULE_TO_CLOSE_TOO_SHORT),
                row("ScheduleToClose below the TimingContract floor (StartToClose + lease)",
                        wf("{\"id\":\"a\",\"type\":\"http\",\"timeout_s\":30,\"schedule_to_close_s\":60,"
                                + "\"config\":{\"url\":\"u\"}}"), SCHEDULE_TO_CLOSE_TOO_SHORT),
                row("side-effecting http with max_attempts 2",
                        wf("{\"id\":\"a\",\"type\":\"http\",\"side_effecting\":true,"
                                + "\"retry\":{\"max_attempts\":2},\"config\":{\"url\":\"u\"}}"),
                        SIDE_EFFECT_NEEDS_THREE_ATTEMPTS),
                row("side-effecting mcp tool with max_attempts 1",
                        wf("{\"id\":\"a\",\"type\":\"mcp\",\"retry\":{\"max_attempts\":1},"
                                + "\"config\":{\"tool\":\"payments.charge\"}}"),
                        SIDE_EFFECT_NEEDS_THREE_ATTEMPTS),
                row("maxCostUsd above tenant ceiling", wf(HTTP_A, ",\"limits\":{\"max_cost_usd\":5.01}"),
                        LIMIT_ABOVE_CEILING),
                row("maxTokens above tenant ceiling", wf(HTTP_A, ",\"limits\":{\"max_tokens\":200001}"),
                        LIMIT_ABOVE_CEILING),
                row("maxNodeExecutions above tenant ceiling", wf(HTTP_A, ",\"limits\":{\"max_node_executions\":501}"),
                        LIMIT_ABOVE_CEILING),
                row("maxDurationS above tenant ceiling", wf(HTTP_A, ",\"max_duration_s\":3601"), LIMIT_ABOVE_CEILING),
                row("negative limit", wf(HTTP_A, ",\"limits\":{\"max_tokens\":-1}"), INVALID_LIMIT),
                row("compensate on a node with no side effect", wf("{\"id\":\"a\",\"type\":\"mcp\","
                        + "\"config\":{\"tool\":\"crm.get\"},\"compensate\":{\"tool\":\"crm.upsert\"}}"),
                        COMPENSATE_WITHOUT_SIDE_EFFECT));
    }

    static Stream<Arguments> warningRows() {
        String charge = "{\"id\":\"%s\",\"type\":\"mcp\",\"config\":{\"tool\":\"payments.charge\"}}";
        String send = "{\"id\":\"%s\",\"type\":\"mcp\",\"config\":{\"tool\":\"messaging.send\"}}";
        return Stream.of(
                row("compensatable node after a pivot", wf(send.formatted("s") + "," + charge.formatted("c")),
                        COMPENSATABLE_AFTER_PIVOT),
                row("more than one pivot on a path", wf(send.formatted("s1") + "," + send.formatted("s2")),
                        MULTIPLE_PIVOTS_ON_PATH),
                row("side-effecting node with neither compensate nor pivot", wf("{\"id\":\"a\",\"type\":\"http\","
                        + "\"side_effecting\":true,\"config\":{\"url\":\"u\",\"method\":\"POST\"}}"),
                        SIDE_EFFECT_WITHOUT_COMPENSATION));
    }

    private static Arguments row(String name, String json, String code) {
        return Arguments.of(name, json, code);
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("errorRows")
    void reportsError(String name, String json, String code) {
        ValidationReport report = validate(json);

        assertThat(codes(report.errors())).as(name).contains(code);
        assertThat(report.valid()).isFalse();
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("warningRows")
    void reportsWarning(String name, String json, String code) {
        ValidationReport report = validate(json);

        assertThat(report.errors()).as(name).isEmpty();
        assertThat(codes(report.warnings())).as(name).contains(code);
    }

    @Test
    void parallelBranchesWithOnePivotEachDoNotWarnAboutMultiplePivots() {
        String send = "{\"id\":\"%s\",\"type\":\"mcp\",\"depends_on\":[],\"config\":{\"tool\":\"messaging.send\"}}";
        ValidationReport report = validate(wf(send.formatted("s1") + "," + send.formatted("s2")));

        assertThat(codes(report.warnings())).doesNotContain(MULTIPLE_PIVOTS_ON_PATH);
    }

    @Test
    void reportsAllErrorsAtOnce() {
        ValidationReport report = validate(wf("{\"id\":\"a\",\"type\":\"shell\",\"timeout_s\":999,"
                + "\"retry\":{\"max_attempts\":9},\"config\":{}}"));

        assertThat(codes(report.errors()))
                .contains(UNKNOWN_NODE_TYPE, TIMEOUT_OUT_OF_RANGE, RETRY_OUT_OF_RANGE);
        assertThat(report.errors()).allSatisfy(issue -> assertThat(issue.nodeId()).isEqualTo("a"));
    }
}
