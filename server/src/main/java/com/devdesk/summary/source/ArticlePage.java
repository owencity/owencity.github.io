package com.devdesk.summary.source;

import java.net.URI;

record ArticlePage(URI uri, byte[] bytes, String charset, String mime) {}
