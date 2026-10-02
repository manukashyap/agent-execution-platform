package com.conversive.aep.engine;

import com.conversive.aep.execution.ExecutionStatus;

/**
 * A start found the execution's engine run already closed (it was accepted earlier, while the caller saw a
 * failure, and has since ended). Nothing will run again under that id; {@link #outcome()} is the terminal
 * status the execution row should record.
 */
public class RunAlreadyClosedException extends RuntimeException {

    private final ExecutionStatus outcome;

    public RunAlreadyClosedException(ExecutionStatus outcome, String message) {
        super(message);
        this.outcome = outcome;
    }

    public ExecutionStatus outcome() {
        return outcome;
    }
}
