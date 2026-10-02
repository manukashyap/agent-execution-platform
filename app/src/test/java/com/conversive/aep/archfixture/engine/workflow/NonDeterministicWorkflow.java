package com.conversive.aep.archfixture.engine.workflow;

import java.time.Instant;
import java.util.UUID;
import org.springframework.util.StringUtils;

/** Deliberately breaks every determinism rule so the ArchUnit test proves the rules bite. */
class NonDeterministicWorkflow {

    String run() {
        new Thread(() -> { }).start();
        Object impl = com.conversive.aep.engine.activity.NodeActivityImpl.class;
        java.util.Collections.shuffle(new java.util.ArrayList<String>());
        String env = System.getenv("HOME") + System.getProperty("user.dir") + new java.util.Date() + impl;
        return StringUtils.capitalize(env + Instant.now() + UUID.randomUUID().toString());
    }
}
