package com.devdesk.calendar;
import java.util.List;
public record CalendarToday(boolean connected,List<CalendarEvent> events,String calendarUrl,String error) {}
