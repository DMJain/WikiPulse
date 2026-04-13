package com.wikipulse.worker.config;

import com.clickhouse.jdbc.ClickHouseDriver;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

@Configuration
public class ClickHouseConfig {

  @Bean(name = "dataSourceProperties")
  @Primary
  @ConfigurationProperties("spring.datasource")
  public DataSourceProperties dataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean(name = "dataSource")
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  public DataSource dataSource(
      @Qualifier("dataSourceProperties") DataSourceProperties dataSourceProperties) {
    return dataSourceProperties.initializeDataSourceBuilder().build();
  }

  @Bean(name = "clickHouseDataSource")
  public DataSource clickHouseDataSource(
      @Value("${wikipulse.clickhouse.url:jdbc:clickhouse://clickhouse:8123/default}") String url,
      @Value("${wikipulse.clickhouse.username:default}") String username,
      @Value("${wikipulse.clickhouse.password:}") String password) {
    SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
    dataSource.setDriverClass(ClickHouseDriver.class);
    dataSource.setUrl(url);
    dataSource.setUsername(username);
    dataSource.setPassword(password);
    return dataSource;
  }

  // FIX: Use JdbcTemplate (not NamedParameterJdbcTemplate).
  // clickhouse-jdbc 0.6.0 uses SqlBasedPreparedStatement which does NOT support JDBC
  // positional '?' placeholders produced by NamedParameterJdbcTemplate — causing
  // BadSqlGrammarException on every /api/v2/analytics/* call.
  @Bean(name = "clickHouseJdbcTemplate")
  public JdbcTemplate clickHouseJdbcTemplate(
      @Qualifier("clickHouseDataSource") DataSource clickHouseDataSource) {
    return new JdbcTemplate(clickHouseDataSource);
  }
}
