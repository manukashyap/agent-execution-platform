package com.conversive.aep.sideeffect;

/**
 * Thrown by an {@link EffectCall} when the provider reports that a request with the same idempotency key is
 * still being processed (for transports where that is not an HTTP 409, e.g. a JSON-RPC error).
 */
public class ProviderInFlightException extends RuntimeException {

    public ProviderInFlightException(String message) {
        super(message);
    }
}
