package com.devdesk.calendar;

import com.devdesk.HttpFetch;
import java.io.IOException;
import java.net.http.HttpRequest;

@FunctionalInterface
interface CalendarTransport {
    HttpFetch.Response exchange(HttpRequest request, int maxBytes) throws IOException;
}
