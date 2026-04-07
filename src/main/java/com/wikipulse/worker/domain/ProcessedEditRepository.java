package com.wikipulse.worker.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedEditRepository extends JpaRepository<ProcessedEdit, Long> {

  interface LanguageCount {
    String getServerUrl();

    Long getCount();
  }

  interface NamespaceCount {
    Integer getNamespace();

    Long getCount();
  }

  interface BotCount {
    Boolean getIsBot();

    Long getCount();
  }

  interface KpiSnapshot {
    Long getTotalEdits();

    Long getBotEdits();

    Double getAverageComplexity();
  }

  interface ComplexityByServerUrl {
    String getServerUrl();

    Long getCount();

    Double getAverageComplexity();
  }

  List<ProcessedEdit> findByOrderByEditTimestampDesc(Pageable pageable);

  List<ProcessedEdit> findByEditTimestampGreaterThanEqualAndEditTimestampLessThan(
      Instant fromInclusive, Instant toExclusive);

  default List<LanguageCount> findTopLanguages(Pageable pageable, Instant since, Boolean isBot) {
    return findTopLanguagesInternal(
        pageable,
        since != null,
        since != null ? since : Instant.EPOCH,
        isBot != null,
        Boolean.TRUE.equals(isBot));
  }

  @Query(
      """
      select p.serverUrl as serverUrl, count(p) as count
      from ProcessedEdit p
      where p.serverUrl is not null
        and p.serverUrl <> ''
        and (:applySince = false or p.editTimestamp >= :since)
        and (:applyIsBot = false or p.isBot = :isBotValue)
      group by p.serverUrl
      order by count(p) desc
      """)
  List<LanguageCount> findTopLanguagesInternal(
      Pageable pageable,
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyIsBot") boolean applyIsBot,
      @Param("isBotValue") boolean isBotValue);

  default List<NamespaceCount> findNamespaceBreakdown(Instant since, Boolean isBot) {
    return findNamespaceBreakdownInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        isBot != null,
        Boolean.TRUE.equals(isBot));
  }

  @Query(
      """
      select p.namespace as namespace, count(p) as count
      from ProcessedEdit p
      where p.namespace is not null
        and (:applySince = false or p.editTimestamp >= :since)
        and (:applyIsBot = false or p.isBot = :isBotValue)
      group by p.namespace
      order by count(p) desc
      """)
  List<NamespaceCount> findNamespaceBreakdownInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyIsBot") boolean applyIsBot,
      @Param("isBotValue") boolean isBotValue);

  default List<BotCount> findBotVsHumanBreakdown(Instant since, Boolean isBot) {
    return findBotVsHumanBreakdownInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        isBot != null,
        Boolean.TRUE.equals(isBot));
  }

  @Query(
      """
      select p.isBot as isBot, count(p) as count
      from ProcessedEdit p
      where p.isBot is not null
        and (:applySince = false or p.editTimestamp >= :since)
        and (:applyIsBot = false or p.isBot = :isBotValue)
      group by p.isBot
      order by count(p) desc
      """)
  List<BotCount> findBotVsHumanBreakdownInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyIsBot") boolean applyIsBot,
      @Param("isBotValue") boolean isBotValue);

  default KpiSnapshot getKpiSnapshot(Instant since, Boolean isBot) {
    return getKpiSnapshotInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        isBot != null,
        Boolean.TRUE.equals(isBot));
  }

  @Query(
      """
      select count(p) as totalEdits,
             coalesce(sum(case when p.isBot = true then 1 else 0 end), 0) as botEdits,
             coalesce(avg(p.complexityScore), 0.0) as averageComplexity
      from ProcessedEdit p
      where (:applySince = false or p.editTimestamp >= :since)
        and (:applyIsBot = false or p.isBot = :isBotValue)
      """)
  KpiSnapshot getKpiSnapshotInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyIsBot") boolean applyIsBot,
      @Param("isBotValue") boolean isBotValue);

  default List<ComplexityByServerUrl> findComplexityByServerUrl(Instant since, Boolean isBot) {
    return findComplexityByServerUrlInternal(
        since != null,
        since != null ? since : Instant.EPOCH,
        isBot != null,
        Boolean.TRUE.equals(isBot));
  }

  @Query(
      """
      select p.serverUrl as serverUrl,
             count(p) as count,
             coalesce(avg(p.complexityScore), 0.0) as averageComplexity
      from ProcessedEdit p
      where p.serverUrl is not null
        and p.serverUrl <> ''
        and (:applySince = false or p.editTimestamp >= :since)
        and (:applyIsBot = false or p.isBot = :isBotValue)
      group by p.serverUrl
      """)
  List<ComplexityByServerUrl> findComplexityByServerUrlInternal(
      @Param("applySince") boolean applySince,
      @Param("since") Instant since,
      @Param("applyIsBot") boolean applyIsBot,
      @Param("isBotValue") boolean isBotValue);
}
