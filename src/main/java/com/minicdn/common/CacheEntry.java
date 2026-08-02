package com.minicdn.common;

import java.util.Map;

public record CacheEntry(int statusCode, String contentType, byte[] body, Map<String, String> headers) {}