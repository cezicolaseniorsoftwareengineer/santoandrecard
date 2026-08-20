package com.cezicola.card.domain;

/**
 * A statement was asked to do something its state forbids.
 *
 * <p>Separate from a validation failure: the request was well formed and the
 * statement simply cannot honour it — paid twice, closed twice, or paid beyond
 * what it billed. The distinction decides the status code the caller sees and
 * whether retrying could ever help.
 */
public class StatementStateException extends RuntimeException {
    public StatementStateException(String message) {
        super(message);
    }
}
