package com.devdesk.news;

import java.nio.charset.StandardCharsets;

/** Dependency-free smoke check for environments without Maven. */
public final class ParserSmoke {
    public static void main(String[] args) throws Exception {
        var items=FeedParser.parse(("<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>one</id><title>Test</title>"
            +"<link href='https://example.com/news'/><published>2026-11-22T09:00:00+09:00</published>"
            +"<summary>&lt;p&gt;Hello&lt;/p&gt;&lt;script&gt;bad()&lt;/script&gt;</summary></entry></feed>").getBytes(StandardCharsets.UTF_8));
        if(items.size()!=1||!items.getFirst().description().equals("Hello"))throw new AssertionError("Atom parsing failed");
        if(FeedParser.safeUrl("javascript:alert(1)"))throw new AssertionError("Unsafe URL accepted");
        boolean rejected=false;
        try { FeedParser.parse("<!DOCTYPE rss [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><rss>&x;</rss>".getBytes(StandardCharsets.UTF_8)); }
        catch(Exception expected){rejected=true;}
        if(!rejected)throw new AssertionError("External entity accepted");
        System.out.println("Parser smoke passed: Atom, sanitization, safe URL, XXE rejection");
    }
}
