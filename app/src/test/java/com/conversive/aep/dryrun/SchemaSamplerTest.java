package com.conversive.aep.dryrun;

import static com.conversive.aep.dryrun.DryRunFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class SchemaSamplerTest {

    @Test
    void sameSeedSameDocumentDifferentSeedDifferentDocument() {
        JsonNode schema = json("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"},"
                + "\"n\":{\"type\":\"integer\"}}}");

        assertThat(SchemaSampler.sample(schema, "a")).isEqualTo(SchemaSampler.sample(schema, "a"));
        assertThat(SchemaSampler.sample(schema, "a")).isNotEqualTo(SchemaSampler.sample(schema, "b"));
    }

    @Test
    void honoursConstNullableTypesNumbersAndBooleans() {
        JsonNode schema = json("""
                {"properties":{"c":{"const":"fixed"},"maybe":{"type":["null","string"]},
                 "price":{"type":"number","minimum":10,"maximum":10},"ok":{"type":"boolean"},
                 "when":{"type":"string","format":"date-time"},"id":{"type":"string","format":"uuid"}}}
                """);

        JsonNode out = SchemaSampler.sample(schema, "seed");

        assertThat(out.path("c").asText()).isEqualTo("fixed");
        assertThat(out.path("maybe").asText()).startsWith("maybe-");
        assertThat(out.path("price").decimalValue()).isEqualByComparingTo("10");
        assertThat(out.path("ok").isBoolean()).isTrue();
        assertThat(out.path("when").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(out.path("id").asText()).hasSize(36);
    }

    @Test
    void stopsAtTheDepthLimitAndHandlesAMissingSchema() {
        String deep = "{\"type\":\"string\"}";
        for (int i = 0; i <= SchemaSampler.MAX_DEPTH + 1; i++) {
            deep = "{\"type\":\"array\",\"items\":" + deep + "}";
        }

        assertThat(SchemaSampler.sample(json(deep), "s").toString()).contains("null");
        assertThat(SchemaSampler.sample(null, "s").isNull()).isTrue();
    }
}
