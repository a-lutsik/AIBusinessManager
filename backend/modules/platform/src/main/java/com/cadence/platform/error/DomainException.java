package com.cadence.platform.error;

public class DomainException extends RuntimeException {

    private final String code;
    private final int status;

    public DomainException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public static DomainException notFound(String code, String message) {
        return new DomainException(code, 404, message);
    }

    public static DomainException conflict(String code, String message) {
        return new DomainException(code, 409, message);
    }

    public static DomainException forbidden(String code, String message) {
        return new DomainException(code, 403, message);
    }

    public static DomainException badRequest(String code, String message) {
        return new DomainException(code, 400, message);
    }

    public static DomainException tooManyRequests(String message) {
        return new DomainException("RATE_LIMITED", 429, message);
    }

    public static DomainException badGateway(String code, String message) {
        return new DomainException(code, 502, message);
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
