CREATE TABLE wiki_edits_queue (
    id Int64,
    title String,
    user String,
    timestamp String,
    type String,
    bot UInt8,
    comment Nullable(String),
    serverUrl String,
    namespace Int32,
    country Nullable(String),
    city Nullable(String),
    byteDiff Int32,
    isRevert UInt8,
    isAnonymous UInt8,
    meta Tuple(domain String, stream String, uri String, dt String)
) ENGINE = Kafka('kafka:29092', 'wiki-edits', 'clickhouse_reader', 'JSONEachRow')
SETTINGS kafka_thread_per_consumer = 0, kafka_num_consumers = 1;

CREATE TABLE wiki_edits (
    id Int64,
    title String,
    user String,
    timestamp DateTime64(3),
    type String,
    bot UInt8,
    comment String,
    serverUrl String,
    namespace Int32,
    country Nullable(String),
    city Nullable(String),
    byteDiff Int32,
    isRevert UInt8,
    isAnonymous UInt8,
    meta Tuple(domain String, stream String, uri String, dt String)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(timestamp)
ORDER BY (serverUrl, id);

CREATE MATERIALIZED VIEW wiki_edits_mv TO wiki_edits AS
SELECT
    id,
    title,
    user,
    parseDateTimeBestEffort(timestamp) AS timestamp,
    type,
    bot,
    ifNull(comment, '') AS comment,
    serverUrl,
    namespace,
    country,
    city,
    byteDiff,
    isRevert,
    isAnonymous,
    meta
FROM wiki_edits_queue;
