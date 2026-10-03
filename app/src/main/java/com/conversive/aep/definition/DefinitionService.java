package com.conversive.aep.definition;

import com.conversive.aep.common.ErrorCodes;
import com.conversive.aep.common.NonRetryableError;
import com.conversive.aep.common.TenantId;
import com.conversive.aep.definition.model.DefinitionCodec;
import com.conversive.aep.definition.model.WorkflowDefinition;
import com.conversive.aep.definition.persistence.DefinitionRepository;
import com.conversive.aep.definition.persistence.DefinitionRepository.PublishOutcome;
import com.conversive.aep.definition.validation.DefinitionValidator;
import com.conversive.aep.definition.validation.ValidationReport;
import com.conversive.aep.definition.validation.ValidationReport.Issue;
import com.conversive.aep.tenancy.TenantLimits;
import com.conversive.aep.tenancy.persistence.TenantLimitsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Publish (parse → validate against tenant ceilings → hash → immutable insert) and lookup of definitions. */
@Service
public class DefinitionService {

    private final DefinitionValidator validator;
    private final DefinitionRepository repository;
    private final TenantLimitsRepository limits;
    private final Clock clock;

    public DefinitionService(DefinitionValidator validator, DefinitionRepository repository,
                             TenantLimitsRepository limits, Clock clock) {
        this.validator = validator;
        this.repository = repository;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * @param created  false for an idempotent re-publish of an identical spec
     * @param warnings validator warnings (saga shape); never block publishing
     */
    public record PublishResult(StoredDefinition definition, boolean created, List<Issue> warnings) {
    }

    /**
     * @throws DefinitionRejectedException with every validator error
     * @throws NonRetryableError {@code VERSION_EXISTS} when the version exists with a different spec
     */
    public PublishResult publish(TenantId tenantId, JsonNode spec) {
        WorkflowDefinition def = parse(spec);
        ValidationReport report = validator.validate(def, ceilings(tenantId));
        if (!report.valid()) {
            throw new DefinitionRejectedException("definition is invalid", report.errors());
        }
        StoredDefinition stored = new StoredDefinition(tenantId, def.workflowId(), def.version(), spec,
                DefinitionCodec.sha256(spec), clock.instant());
        PublishOutcome outcome = repository.publish(stored);
        if (outcome == PublishOutcome.VERSION_EXISTS) {
            throw new NonRetryableError(ErrorCodes.VERSION_EXISTS, "version " + def.version() + " of "
                    + def.workflowId() + " already exists with a different definition; publish a new version");
        }
        StoredDefinition saved = get(tenantId, def.workflowId(), def.version());
        return new PublishResult(saved, outcome == PublishOutcome.CREATED, report.warnings());
    }

    /** @throws NonRetryableError {@code NOT_FOUND} */
    public StoredDefinition get(TenantId tenantId, String workflowId, int version) {
        return repository.find(tenantId, workflowId, version).orElseThrow(() -> notFound(workflowId));
    }

    /** The given version, or the latest when {@code version} is null. */
    public StoredDefinition resolve(TenantId tenantId, String workflowId, Integer version) {
        return version == null
                ? repository.findLatest(tenantId, workflowId).orElseThrow(() -> notFound(workflowId))
                : get(tenantId, workflowId, version);
    }

    public TenantLimits ceilings(TenantId tenantId) {
        return limits.find(tenantId).orElseGet(() -> TenantLimits.defaults(tenantId));
    }

    private static WorkflowDefinition parse(JsonNode spec) {
        try {
            return DefinitionCodec.parse(spec);
        } catch (IllegalArgumentException e) {
            throw new DefinitionRejectedException("definition is invalid",
                    List.of(new Issue(ErrorCodes.VALIDATION_FAILED, null, e.getMessage())));
        }
    }

    private static NonRetryableError notFound(String workflowId) {
        return new NonRetryableError(ErrorCodes.NOT_FOUND, "workflow " + workflowId + " not found");
    }
}
