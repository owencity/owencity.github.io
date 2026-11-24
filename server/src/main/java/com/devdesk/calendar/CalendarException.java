package com.devdesk.calendar;

/** Safe user-facing error. Never include Google response bodies or OAuth values. */
public final class CalendarException extends RuntimeException {
    private final int status;
    private final String code;
    public CalendarException(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
