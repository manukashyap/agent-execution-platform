package com.conversive.aep.archfixture.engine.workflow;

import java.time.Instant;
import java.util.UUID;
import org.springframework.util.StringUtils;

/** Deliberately breaks every determinism rule so the ArchUnit test proves the rules bite. */
class NonDeterministicWorkflow {

    String run() {
        new Thread(() -> { }).start();
        return StringUtils.capitalize(Instant.now() + UUID.randomUUID().toString());
    }
}
