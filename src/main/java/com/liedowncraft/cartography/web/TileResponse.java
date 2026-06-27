package com.liedowncraft.cartography.web;

import java.util.Map;

public record TileResponse(int statusCode, byte[] body, String mimeType, Map<String, String> headers) {
}
