package com.conversive.aep.engine.workflow.condition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ConditionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static final Map<String, JsonNode> OUTPUTS = Map.of(
            "a", json("{\"status\":200,\"score\":0.75,\"tier\":\"gold\",\"items\":[{\"id\":\"x\"}],\"none\":null}"),
            "input", json("{\"threshold\":0.5}"));

    private static boolean eval(String left, String op, String right) {
        String config = "{\"left\":\"" + left + "\",\"op\":\"" + op + "\""
                + (right == null ? "" : ",\"right\":" + right) + "}";
        return ConditionEvaluator.evaluate(Condition.parse(json(config)), OUTPUTS::get);
    }

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            "$.a.status       | eq     | 200        | true",
            "$.a.status       | eq     | 200.0      | true",
            "$.a.status       | ne     | 201        | true",
            "$.a.tier         | eq     | \"gold\"   | true",
            "$.a.score        | gt     | 0.5        | true",
            "$.a.score        | gte    | 0.75       | true",
            "$.a.score        | lt     | 0.5        | false",
            "$.a.score        | lte    | 0.75       | true",
            "$.a.tier         | gt     | \"bronze\" | true",
            "$.a.tier         | gt     | 1          | false",
            "$.a.items[0].id  | eq     | \"x\"      | true",
            "$.a.items[3].id  | exists |            | false",
            "$.a.none         | exists |            | false",
            "$.a.missing      | ne     | 1          | true",
            "$.zz.status      | exists |            | false",
            "$.input.threshold| lt     | 1          | true"})
    void evaluates(String left, String op, String right, boolean expected) {
        assertThat(eval(left.trim(), op.trim(), right == null ? null : right.trim())).isEqualTo(expected);
    }

    @Test
    void parsesBranches() {
        Condition c = Condition.parse(json(
                "{\"left\":\"$.a.status\",\"op\":\"EQ\",\"right\":1,\"then\":[\"x\"],\"else\":[\"y\",\"z\"]}"));

        assertThat(c.op()).isEqualTo(Condition.Op.EQ);
        assertThat(c.thenNodes()).containsExactly("x");
        assertThat(c.elseNodes()).containsExactly("y", "z");
        assertThat(c.left()).hasToString("$.a.status");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "{\"op\":\"eq\",\"right\":1}",
            "{\"left\":\"a.b\",\"op\":\"eq\",\"right\":1}",
            "{\"left\":\"$.a..b\",\"op\":\"eq\",\"right\":1}",
            "{\"left\":\"$.a[x]\",\"op\":\"eq\",\"right\":1}",
            "{\"left\":\"$.a\",\"op\":\"like\",\"right\":1}",
            "{\"left\":\"$.a\",\"op\":\"eq\"}",
            "{\"left\":\"$.a\",\"op\":\"exists\",\"then\":\"x\"}",
            "{\"left\":\"$.a\",\"op\":\"exists\",\"then\":[1]}"})
    void rejectsMalformedConditions(String config) {
        assertThatThrownBy(() -> Condition.parse(json(config))).isInstanceOf(ConditionSyntaxException.class);
    }
}
