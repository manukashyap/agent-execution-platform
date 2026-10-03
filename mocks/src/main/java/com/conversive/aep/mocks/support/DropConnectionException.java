package com.conversive.aep.mocks.support;

/** Signals that the socket must be closed without writing any response. */
public class DropConnectionException extends RuntimeException {

    public DropConnectionException(String route) {
        super("connection dropped on " + route);
    }
}
