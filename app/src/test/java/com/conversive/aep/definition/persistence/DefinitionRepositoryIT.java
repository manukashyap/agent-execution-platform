package com.conversive.aep.definition.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.StoredDefinition;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.persistence.DefinitionRepository.PublishOutcome;
import com.conversive.aep.support.Fixtures;
import com.conversive.aep.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DefinitionRepositoryIT extends PostgresIntegrationTest {

    private static final TenantId DEV = TenantId.of("t_dev");
    private static final TenantId OTHER = TenantId.of("t_other");

    @Autowired
    DefinitionRepository repository;

    @Test
    void publishIsImmutablePerVersionAndIdempotentForTheSameHash() {
        String workflowId = "repo_it_" + UUID.randomUUID();
        JsonNode spec = Fixtures.pdfExample();
        StoredDefinition v1 = stored(DEV, workflowId, 1, spec);
        ObjectNode changed = spec.deepCopy();
        changed.put("extra", true);

        assertThat(repository.publish(v1)).isEqualTo(PublishOutcome.CREATED);
        assertThat(repository.publish(v1)).isEqualTo(PublishOutcome.ALREADY_PUBLISHED);
        assertThat(repository.publish(stored(DEV, workflowId, 1, changed))).isEqualTo(PublishOutcome.VERSION_EXISTS);
        assertThat(repository.find(DEV, workflowId, 1).orElseThrow().spec()).isEqualTo(spec);
    }

    @Test
    void findIsTenantScopedAndLatestPicksTheHighestVersion() {
        String workflowId = "repo_it_" + UUID.randomUUID();
        repository.publish(stored(DEV, workflowId, 1, Fixtures.pdfExample()));
        repository.publish(stored(DEV, workflowId, 3, Fixtures.pdfExample()));
        repository.publish(stored(OTHER, workflowId, 7, Fixtures.pdfExample()));

        assertThat(repository.find(OTHER, workflowId, 1)).isEmpty();
        assertThat(repository.findLatest(DEV, workflowId).orElseThrow().version()).isEqualTo(3);
        assertThat(repository.findLatest(OTHER, workflowId).orElseThrow().version()).isEqualTo(7);
        assertThat(repository.findLatest(DEV, "absent")).isEmpty();
    }

    private static StoredDefinition stored(TenantId tenant, String workflowId, int version, JsonNode spec) {
        return new StoredDefinition(tenant, workflowId, version, spec, DefinitionCodec.sha256(spec), null);
    }
}
