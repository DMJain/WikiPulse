package com.wikipulse.worker.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalyticsRollupRepository extends JpaRepository<AnalyticsRollup, Long> {

  Optional<AnalyticsRollup> findByTimeBucketAndLanguageAndNamespace(
      Instant timeBucket, String language, String namespace);

  List<AnalyticsRollup> findByTimeBucket(Instant timeBucket);
}