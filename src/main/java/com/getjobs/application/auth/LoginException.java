package com.getjobs.application.auth;

public class LoginException extends RuntimeException {
    private final int status;
    private final int retryAfter;

    public LoginException(int status, String message) {
        this(status, message, 0);
    }

    public LoginException(int status, String message, int retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    public int retryAfter() {
        return retryAfter;
    }
}
