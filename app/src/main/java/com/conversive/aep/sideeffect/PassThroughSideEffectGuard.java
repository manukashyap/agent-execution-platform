package com.conversive.aep.sideeffect;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Placeholder; the P5 ledger-backed guard supersedes it as {@code @Primary}. */
@Component
public class PassThroughSideEffectGuard implements SideEffectGuard {

    @Override
    public JsonNode run(EffectSpec spec, EffectCall call) {
        return call.invoke(spec.key().value());
    }
}
