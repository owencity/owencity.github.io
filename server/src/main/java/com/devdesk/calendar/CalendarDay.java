package com.devdesk.calendar;

import java.util.List;

public record CalendarDay(boolean connected, List<CalendarEvent> events, String calendarUrl,
        String error, boolean writeEnabled, String date) {}
