package com.conversive.aep.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PromptRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode input() throws Exception {
        return MAPPER.readTree("""
                {"lead":{"name":"Ada","tags":["vip","eu"],"score":7,"meta":{"a":1}},"empty":null}
                """);
    }

    @Test
    void replacesDottedPathsAndArrayIndexes() throws Exception {
        assertThat(PromptRenderer.render("Hi {{lead.name}} ({{ lead.tags.1 }}), score {{lead.score}}", input()))
                .isEqualTo("Hi Ada (eu), score 7");
    }

    @Test
    void insertsNonTextValuesAsCompactJson() throws Exception {
        assertThat(PromptRenderer.render("{{lead.meta}} {{lead.tags}}", input())).isEqualTo("{\"a\":1} [\"vip\",\"eu\"]");
    }

    @Test
    void missingAndNullPathsBecomeEmpty() throws Exception {
        assertThat(PromptRenderer.render("[{{lead.nope}}][{{empty}}][{{empty.x}}][{{lead.tags.9}}]", input()))
                .isEqualTo("[][][][]");
    }

    @Test
    void replacementTextIsLiteral() throws Exception {
        JsonNode in = MAPPER.readTree("{\"v\":\"$1 \\\\ {{x}}\"}");
        assertThat(PromptRenderer.render("<{{v}}>", in)).isEqualTo("<$1 \\ {{x}}>");
    }

    @Test
    void templatesWithoutPlaceholdersAreUnchanged() throws Exception {
        assertThat(PromptRenderer.render("plain {text}", input())).isEqualTo("plain {text}");
        assertThat(PromptRenderer.render(null, input())).isNull();
    }
}
