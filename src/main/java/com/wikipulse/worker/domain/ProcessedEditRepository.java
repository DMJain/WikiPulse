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

  List<ProcessedEdit> findByOrderByEditTimestampDesc(Pageable pageable);

  @Query(
      """
      select p.serverUrl as serverUrl, count(p) as count
      from ProcessedEdit p
      where p.serverUrl is not null
        and p.serverUrl <> ''
        and (:since is null or p.editTimestamp >= :since)
        and (:isBot is null or p.isBot = :isBot)
      group by p.serverUrl
      order by count(p) desc
      """)
  List<LanguageCount> findTopLanguages(
      Pageable pageable, @Param("since") Instant since, @Param("isBot") Boolean isBot);

  @Query(
      """
      select p.namespace as namespace, count(p) as count
      from ProcessedEdit p
      where p.namespace is not null
        and (:since is null or p.editTimestamp >= :since)
        and (:isBot is null or p.isBot = :isBot)
      group by p.namespace
      order by count(p) desc
      """)
  List<NamespaceCount> findNamespaceBreakdown(
      @Param("since") Instant since, @Param("isBot") Boolean isBot);

  @Query(
      """
      select p.isBot as isBot, count(p) as count
      from ProcessedEdit p
      where p.isBot is not null
        and (:since is null or p.editTimestamp >= :since)
        and (:isBot is null or p.isBot = :isBot)
      group by p.isBot
      order by count(p) desc
      """)
  List<BotCount> findBotVsHumanBreakdown(
      @Param("since") Instant since, @Param("isBot") Boolean isBot);

  @Query(
      """
      select count(p) as totalEdits,
             coalesce(sum(case when p.isBot = true then 1 else 0 end), 0) as botEdits,
             coalesce(avg(p.complexityScore), 0.0) as averageComplexity
      from ProcessedEdit p
      where (:since is null or p.editTimestamp >= :since)
        and (:isBot is null or p.isBot = :isBot)
      """)
  KpiSnapshot getKpiSnapshot(@Param("since") Instant since, @Param("isBot") Boolean isBot);
}
