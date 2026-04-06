package com.wikipulse.worker.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class WikiMetadataNormalizer {

  private static final Map<String, String> WELL_KNOWN_HOST_LABELS =
      Map.of(
          "en.wikipedia.org", "English Wikipedia",
          "www.wikidata.org", "Wikidata",
          "commons.wikimedia.org", "Wikimedia Commons");

  public String normalizeServerUrl(String serverUrl) {
    if (serverUrl == null || serverUrl.isBlank()) {
      return "Unknown";
    }

    String host = extractHost(serverUrl.trim());
    if (host == null || host.isBlank()) {
      return stripHttpPrefix(serverUrl.trim());
    }

    String normalizedHost = host.toLowerCase(Locale.ROOT);
    return WELL_KNOWN_HOST_LABELS.getOrDefault(normalizedHost, normalizedHost);
  }

  public String normalizeNamespace(Integer namespace) {
    if (namespace == null) {
      return "Other";
    }

    return switch (namespace) {
      case 0 -> "Article";
      case 1 -> "Article Talk";
      case 2 -> "User";
      case 3 -> "User Talk";
      case 4 -> "Project";
      default -> "Other";
    };
  }

  private String extractHost(String rawServerUrl) {
    try {
      URI uri = new URI(rawServerUrl);
      if (uri.getHost() != null) {
        return uri.getHost();
      }
    } catch (URISyntaxException ignored) {
      // Fall through to prefix stripping for malformed URLs.
    }

    String stripped = stripHttpPrefix(rawServerUrl);
    int slashIndex = stripped.indexOf('/');
    return slashIndex >= 0 ? stripped.substring(0, slashIndex) : stripped;
  }

  private String stripHttpPrefix(String value) {
    if (value.startsWith("https://")) {
      return value.substring("https://".length());
    }

    if (value.startsWith("http://")) {
      return value.substring("http://".length());
    }

    return value;
  }
}