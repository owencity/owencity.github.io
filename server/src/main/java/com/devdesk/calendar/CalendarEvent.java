package com.devdesk.calendar;
public record CalendarEvent(String id, String title, String start, String end, boolean allDay, String url,
        String description, String location, String etag, String recurringEventId, boolean editable,
        String reminderMode, Integer reminderMinutes, String taskPlanKey) {
    public CalendarEvent(String id, String title, String start, String end, boolean allDay, String url) {
        this(id, title, start, end, allDay, url, "", "", "", "", false, "DEFAULT", null, "");
    }
}
