package com.wikipulse.worker.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AnalyticsRollupRepository extends JpaRepository<AnalyticsRollup, Long> {

  interface LanguageRollupAggregate {
    String getLanguage();

    Long getTotalEdits();

    Long getBotEdits();
  }

  interface NamespaceRollupAggregate {
    String getNamespace();

    Long getTotalEdits();

    Long getBotEdits();
  }

  interface RollupTotals {
    Long getTotalEdits();

    Long getBotEdits();
  }

  interface TrendBucketAggregate {
    Instant getTimeBucket();

    Long getTotalEdits();

    Long getBotEdits();
  }

  Optional<AnalyticsRollup> findByTimeBucketAndLanguageAndNamespace(
      Instant timeBucket, String language, String namespace);

  List<AnalyticsRollup> findByTimeBucket(Instant timeBucket);

  default List<LanguageRollupAggregate> findLanguageRollups(
      Instant since, boolean applyExactLanguage, String exactLanguage, boolean applyWikipedia) {
    return findLanguageRollupsInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        applyExactLanguage,
        applyExactLanguage ? exactLanguage : "",
        applyWikipedia);
  }

  @Query(
      """
      select r.language as language,
             coalesce(sum(r.totalEdits), 0) as totalEdits,
             coalesce(sum(r.botEdits), 0) as botEdits
      from AnalyticsRollup r
      where (:applySince = false or r.timeBucket >= :since)
        and (:applyExactLanguage = false or r.language = :exactLanguage)
        and (:applyWikipedia = false
             or (r.language <> 'Wikidata'
                 and r.language <> 'Wikimedia Commons'
                 and r.language <> 'Unknown'))
      group by r.language
      order by coalesce(sum(r.totalEdits), 0) desc, r.language asc
      """)
  List<LanguageRollupAggregate> findLanguageRollupsInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyExactLanguage") boolean applyExactLanguage,
      @Param("exactLanguage") String exactLanguage,
      @Param("applyWikipedia") boolean applyWikipedia);

  default List<NamespaceRollupAggregate> findNamespaceRollups(
      Instant since, boolean applyExactLanguage, String exactLanguage, boolean applyWikipedia) {
    return findNamespaceRollupsInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        applyExactLanguage,
        applyExactLanguage ? exactLanguage : "",
        applyWikipedia);
  }

  @Query(
      """
      select r.namespace as namespace,
             coalesce(sum(r.totalEdits), 0) as totalEdits,
             coalesce(sum(r.botEdits), 0) as botEdits
      from AnalyticsRollup r
      where (:applySince = false or r.timeBucket >= :since)
        and (:applyExactLanguage = false or r.language = :exactLanguage)
        and (:applyWikipedia = false
             or (r.language <> 'Wikidata'
                 and r.language <> 'Wikimedia Commons'
                 and r.language <> 'Unknown'))
      group by r.namespace
      order by coalesce(sum(r.totalEdits), 0) desc, r.namespace asc
      """)
  List<NamespaceRollupAggregate> findNamespaceRollupsInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyExactLanguage") boolean applyExactLanguage,
      @Param("exactLanguage") String exactLanguage,
      @Param("applyWikipedia") boolean applyWikipedia);

  default RollupTotals getRollupTotals(
      Instant since, boolean applyExactLanguage, String exactLanguage, boolean applyWikipedia) {
    return getRollupTotalsInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        applyExactLanguage,
        applyExactLanguage ? exactLanguage : "",
        applyWikipedia);
  }

  @Query(
      """
      select coalesce(sum(r.totalEdits), 0) as totalEdits,
             coalesce(sum(r.botEdits), 0) as botEdits
      from AnalyticsRollup r
      where (:applySince = false or r.timeBucket >= :since)
        and (:applyExactLanguage = false or r.language = :exactLanguage)
        and (:applyWikipedia = false
             or (r.language <> 'Wikidata'
                 and r.language <> 'Wikimedia Commons'
                 and r.language <> 'Unknown'))
      """)
  RollupTotals getRollupTotalsInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyExactLanguage") boolean applyExactLanguage,
      @Param("exactLanguage") String exactLanguage,
      @Param("applyWikipedia") boolean applyWikipedia);

  default List<TrendBucketAggregate> findTrendRollups(
      Instant since, boolean applyExactLanguage, String exactLanguage, boolean applyWikipedia) {
    return findTrendRollupsInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        applyExactLanguage,
        applyExactLanguage ? exactLanguage : "",
        applyWikipedia);
  }

  @Query(
      """
      select r.timeBucket as timeBucket,
             coalesce(sum(r.totalEdits), 0) as totalEdits,
             coalesce(sum(r.botEdits), 0) as botEdits
      from AnalyticsRollup r
      where (:applySince = false or r.timeBucket >= :since)
        and (:applyExactLanguage = false or r.language = :exactLanguage)
        and (:applyWikipedia = false
             or (r.language <> 'Wikidata'
                 and r.language <> 'Wikimedia Commons'
                 and r.language <> 'Unknown'))
      group by r.timeBucket
      order by r.timeBucket asc
      """)
  List<TrendBucketAggregate> findTrendRollupsInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyExactLanguage") boolean applyExactLanguage,
      @Param("exactLanguage") String exactLanguage,
      @Param("applyWikipedia") boolean applyWikipedia);
}