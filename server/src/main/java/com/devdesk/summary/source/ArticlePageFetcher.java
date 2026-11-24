package com.devdesk.summary.source;

import java.io.IOException;
import java.net.URI;

@FunctionalInterface
interface ArticlePageFetcher {
    ArticlePage fetch(URI uri) throws IOException;
}
