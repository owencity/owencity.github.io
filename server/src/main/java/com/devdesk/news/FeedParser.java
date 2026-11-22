package com.devdesk.news;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class FeedParser {
    public record Entry(String sourceId, String title, String url, String description, Instant publishedAt) {}
    private FeedParser() {}

    public static List<Entry> parse(byte[] xml) throws Exception {
        if (xml.length > 4_194_304) throw new IllegalArgumentException("Feed exceeds size limit");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        var entries = document.getElementsByTagNameNS("*", "entry");
        boolean atom = entries.getLength() > 0;
        if (!atom) entries = document.getElementsByTagName("item");
        List<Entry> result = new ArrayList<>();
        for (int i=0; i<Math.min(entries.getLength(), 1000); i++) {
            Element entry = (Element) entries.item(i);
            String url = atom ? atomLink(entry) : child(entry,"link");
            if (!safeUrl(url)) continue;
            String id = child(entry, atom ? "id" : "guid");
            if (id.isBlank()) id = url;
            String title = child(entry,"title").strip();
            if (title.isBlank()) continue;
            String summary = child(entry, atom ? "summary" : "description");
            if (summary.isBlank() && atom) summary = child(entry,"content");
            // Never persist executable markup for the UI. The client also renders textContent.
            summary = summary.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>", "")
                .replaceAll("(?s)<[^>]*>", " ").replaceAll("\\s+", " ").strip();
            String published = child(entry, atom ? "published" : "pubDate");
            if (published.isBlank() && atom) published = child(entry,"updated");
            if (id.length()>1000 || url.length()>4000) continue;
            result.add(new Entry(id, clip(title,1500), url, clip(summary,12000), parseDate(published)));
        }
        return result;
    }

    private static String child(Element parent,String name) {
        for (Node n=parent.getFirstChild(); n!=null; n=n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName()==null?e.getTagName():e.getLocalName())) return e.getTextContent();
        }
        return "";
    }
    private static String atomLink(Element parent) {
        for (Node n=parent.getFirstChild(); n!=null; n=n.getNextSibling()) {
            if (n instanceof Element e && "link".equals(e.getLocalName())) {
                String rel=e.getAttribute("rel");
                if (rel.isEmpty() || rel.equals("alternate")) return e.getAttribute("href");
            }
        }
        return "";
    }
    public static boolean safeUrl(String value) {
        try { URI uri=URI.create(value); return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null; }
        catch (RuntimeException invalid) { return false; }
    }
    static Instant parseDate(String text) {
        try { return OffsetDateTime.parse(text.strip()).toInstant(); } catch (RuntimeException ignored) {}
        try { return ZonedDateTime.parse(text.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(); } catch (RuntimeException ignored) {}
        return null; // Keep missing dates missing; collection time is a separate field.
    }
    private static String clip(String value,int limit) { return value.length()>limit?value.substring(0,limit):value; }
}
