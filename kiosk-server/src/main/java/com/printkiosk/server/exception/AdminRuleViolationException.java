package com.printkiosk.server.exception;

/** Нарушение бизнес-правила админки (занятый логин, последний владелец и т.п.). HTTP 400. */
public class AdminRuleViolationException extends RuntimeException {
    public AdminRuleViolationException(String message) {
        super(message);
    }
}
