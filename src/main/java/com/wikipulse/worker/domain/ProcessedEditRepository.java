package com.wikipulse.worker.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

  List<ProcessedEdit> findByOrderByEditTimestampDesc(Pageable pageable);

  @Query(
      """
      select p.serverUrl as serverUrl, count(p) as count
      from ProcessedEdit p
      where p.serverUrl is not null and p.serverUrl <> ''
      group by p.serverUrl
      order by count(p) desc
      """)
  List<LanguageCount> findTopLanguages(Pageable pageable);

  @Query(
      """
      select p.namespace as namespace, count(p) as count
      from ProcessedEdit p
      where p.namespace is not null
      group by p.namespace
      order by count(p) desc
      """)
  List<NamespaceCount> findNamespaceBreakdown();

  @Query(
      """
      select p.isBot as isBot, count(p) as count
      from ProcessedEdit p
      where p.isBot is not null
      group by p.isBot
      order by count(p) desc
      """)
  List<BotCount> findBotVsHumanBreakdown();
}
