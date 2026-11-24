package com.devdesk.calendar;

/** Times are RFC3339 with offset; all-day end dates are exclusive. */
public record CalendarWriteRequest(String requestId, String title, String description, String location,
        Boolean allDay, String start, String end, String reminderMode, Integer reminderMinutes,
        String repeat, Integer repeatCount) {}
