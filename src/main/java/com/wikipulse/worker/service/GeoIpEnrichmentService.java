package com.wikipulse.worker.service;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class GeoIpEnrichmentService {

  private static final Logger log = LoggerFactory.getLogger(GeoIpEnrichmentService.class);
  private static final GeoLocation UNKNOWN_LOCATION = new GeoLocation("Unknown", "Unknown");

  private final DatabaseReader databaseReader;

  public GeoIpEnrichmentService(
      @Value("${wikipulse.geoip.mmdb-path:GeoLite2-City.mmdb}") String mmdbPath) {
    this.databaseReader = initializeReaderSafely(mmdbPath);
  }

  public GeoLocation enrichIp(String ipAddress) {
    if (ipAddress == null || ipAddress.isBlank()) {
      return UNKNOWN_LOCATION;
    }
    if (databaseReader == null) {
      return UNKNOWN_LOCATION;
    }

    try {
      InetAddress inetAddress = InetAddress.getByName(ipAddress);
      CityResponse response = databaseReader.city(inetAddress);

      String country = response.getCountry() != null ? response.getCountry().getName() : null;
      String city = response.getCity() != null ? response.getCity().getName() : null;

      return new GeoLocation(country != null ? country : "Unknown", city != null ? city : "Unknown");
    } catch (IOException | GeoIp2Exception ex) {
      log.debug("GeoIP lookup failed for '{}': {}", ipAddress, ex.getMessage());
      return UNKNOWN_LOCATION;
    }
  }

  private DatabaseReader initializeReaderSafely(String mmdbPath) {
    try {
      return initializeReader(mmdbPath);
    } catch (Exception ex) {
      log.warn(
          "GeoIP database unavailable ({}). Falling back to Unknown for geo lookups.",
          ex.getMessage());
      return null;
    }
  }

  private DatabaseReader initializeReader(String mmdbPath) throws IOException {
    ClassPathResource classPathResource = new ClassPathResource("GeoLite2-City.mmdb");
    if (classPathResource.exists()) {
      return new DatabaseReader.Builder(classPathResource.getInputStream())
          .withCache(new CHMCache())
          .build();
    }

    Path path = Path.of(mmdbPath);
    if (Files.exists(path)) {
      return new DatabaseReader.Builder(path.toFile()).withCache(new CHMCache()).build();
    }

    throw new IOException("GeoLite2-City.mmdb not found on classpath or filesystem");
  }

  public record GeoLocation(String country, String city) {}
}
