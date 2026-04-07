package com.wikipulse.worker.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class WikiMetadataNormalizer {

  private static final Pattern HOST_PATTERN =
      Pattern.compile("^(?:https?://)?([^/:?#]+)", Pattern.CASE_INSENSITIVE);

  private static final Map<String, String> LANGUAGE_NAME_BY_CODE =
      Map.ofEntries(
        Map.entry("en", "English"),
        Map.entry("fr", "French"),
        Map.entry("ru", "Russian"),
        Map.entry("de", "German"),
        Map.entry("ja", "Japanese"),
        Map.entry("es", "Spanish"),
        Map.entry("zh", "Chinese"),
        Map.entry("it", "Italian"),
        Map.entry("pt", "Portuguese"),
        Map.entry("ar", "Arabic"),
        Map.entry("commons", "Wikimedia Commons"),
        Map.entry("www", "Wikidata"));

  public String normalizeServerUrl(String serverUrl) {
    if (serverUrl == null || serverUrl.isBlank()) {
      return "Unknown";
    }

    String host = extractHost(serverUrl.trim());
    if (host == null || host.isBlank()) {
      return "Unknown";
    }

    String[] hostParts = host.toLowerCase(Locale.ROOT).split("\\.");
    if (hostParts.length == 0 || hostParts[0].isBlank()) {
      return "Unknown";
    }

    String subdomain = hostParts[0];
    String mappedLanguage = LANGUAGE_NAME_BY_CODE.get(subdomain);
    if (mappedLanguage != null) {
      return mappedLanguage;
    }

    return capitalizeFirstLetter(subdomain);
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

      if (uri.getScheme() == null && uri.getPath() != null) {
        URI withFallbackScheme = new URI("https://" + rawServerUrl);
        if (withFallbackScheme.getHost() != null) {
          return withFallbackScheme.getHost();
        }
      }
    } catch (URISyntaxException ignored) {
      // Fall through to regex extraction for malformed URLs.
    }

    Matcher matcher = HOST_PATTERN.matcher(rawServerUrl);
    if (!matcher.find()) {
      return null;
    }

    return matcher.group(1);
  }

  private static String capitalizeFirstLetter(String value) {
    if (value == null || value.isBlank()) {
      return "Unknown";
    }

    String normalized = value.toLowerCase(Locale.ROOT);
    return normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
  }
}