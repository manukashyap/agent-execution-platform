package com.conversive.aep.sideeffect;

import com.fasterxml.jackson.databind.JsonNode;

/** Wraps every side-effecting call so a retried attempt never repeats a committed effect (P5). */
public interface SideEffectGuard {

    JsonNode run(EffectSpec spec, EffectCall call);
}
