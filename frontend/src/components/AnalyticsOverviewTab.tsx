import { useEffect, useMemo, useState } from 'react';
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  fetchBotDistribution,
  fetchKpis,
  fetchNamespaceDistribution,
  fetchTrendData,
  fetchTopLanguages,
  type BotCount,
  type KpiSnapshot,
  type LanguageCount,
  type NamespaceCount,
  type TrendBucket,
} from '../services/api';
import './AnalyticsOverviewTab.css';

const POLL_INTERVAL_MS = 10000;

const LANGUAGE_NAME_BY_CODE: Record<string, string> = {
  ar: 'Arabic',
  de: 'German',
  en: 'English',
  es: 'Spanish',
  fa: 'Persian',
  fr: 'French',
  it: 'Italian',
  ja: 'Japanese',
  pl: 'Polish',
  pt: 'Portuguese',
  ru: 'Russian',
  uk: 'Ukrainian',
  vi: 'Vietnamese',
  zh: 'Chinese',
};

const NAMESPACE_LABELS: Record<string, string> = {
  '-2': 'Media',
  '-1': 'Special',
  '0': 'Main',
  '1': 'Talk',
  '2': 'User',
  '3': 'User talk',
  '4': 'Project',
  '5': 'Project talk',
  '6': 'File',
  '7': 'File talk',
  '8': 'MediaWiki',
  '9': 'MediaWiki talk',
  '10': 'Template',
  '11': 'Template talk',
  '12': 'Help',
  '13': 'Help talk',
  '14': 'Category',
  '15': 'Category talk',
};

// Neo-Brutalist chart accent palette
const BOT_SLICE_COLORS = ['#ff00ff', '#00ff66'];
const NAMESPACE_SLICE_COLORS = ['#fff500', '#00ff66', '#ff00ff', '#00e5ff', '#ff6600', '#0a0a0a'];

interface LanguageChartDatum {
  language: string;
  edits: number;
}

interface PieChartDatum {
  name: string;
  value: number;
}

type TooltipValue = number | string | Array<number | string>;

const TOOLTIP_CONTENT_STYLE = {
  borderRadius: 0,
  border: '2px solid #000000',
  backgroundColor: '#ffffff',
  boxShadow: '4px 4px 0px #000000',
  fontFamily: "'Roboto Mono', monospace",
  fontSize: '0.78rem',
} as const;

function formatTooltipValue(value: TooltipValue): string {
  if (Array.isArray(value)) {
    return value.join(' - ');
  }

  const numericValue = typeof value === 'number' ? value : Number(value);
  if (Number.isFinite(numericValue)) {
    return `${numericValue.toLocaleString()} edits`;
  }

  return String(value);
}

function parseBotFilterValue(value: string): boolean | null {
  if (value === 'bots') {
    return true;
  }

  if (value === 'humans') {
    return false;
  }

  return null;
}

function languageLabelFromServerUrl(serverUrl: string | null): string {
  if (!serverUrl) {
    return 'Unknown';
  }

  try {
    const parsed = new URL(serverUrl);
    const [prefix] = parsed.hostname.split('.');
    if (!prefix || prefix.toLowerCase() === 'www') {
      return parsed.hostname;
    }

    const normalizedPrefix = prefix.toLowerCase();
    return LANGUAGE_NAME_BY_CODE[normalizedPrefix] ?? normalizedPrefix.toUpperCase();
  } catch {
    return serverUrl;
  }
}

function namespaceLabel(namespaceId: string | null): string {
  if (!namespaceId || namespaceId.trim() === '') {
    return 'Unknown';
  }

  const normalizedNamespace = namespaceId.trim();
  const mappedNamespace = NAMESPACE_LABELS[normalizedNamespace];

  if (mappedNamespace) {
    return mappedNamespace;
  }

  return /^-?\d+$/.test(normalizedNamespace)
    ? `NS ${normalizedNamespace}`
    : normalizedNamespace;
}

function formatUpdatedAt(timestamp: string | null): string {
  if (!timestamp) {
    return 'Awaiting first refresh';
  }

  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) {
    return timestamp;
  }

  return date.toLocaleTimeString();
}

function formatTrendBucketTick(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
  });
}

function formatTrendBucketLabel(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return date.toLocaleString();
}

export default function AnalyticsOverviewTab() {
  const [kpis, setKpis] = useState<KpiSnapshot | null>(null);
  const [languageBreakdown, setLanguageBreakdown] = useState<LanguageCount[]>([]);
  const [namespaceBreakdown, setNamespaceBreakdown] = useState<NamespaceCount[]>([]);
  const [botBreakdown, setBotBreakdown] = useState<BotCount[]>([]);
  const [trendData, setTrendData] = useState<TrendBucket[]>([]);
  const [timeframe, setTimeframe] = useState('');
  const [isBot, setIsBot] = useState<boolean | null>(null);
  const [project, setProject] = useState('all');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const selectedTimeframe = timeframe || undefined;
    const selectedBotFilter = isBot ?? undefined;
    const selectedProject = project === 'all' ? undefined : project;

    const loadAnalytics = async () => {
      if (!cancelled) {
        setLoading(true);
      }

      try {
        const [kpiSnapshot, languages, namespaces, bots, trendBuckets] = await Promise.all([
          fetchKpis(selectedTimeframe, selectedBotFilter, selectedProject),
          fetchTopLanguages(5, selectedTimeframe, selectedBotFilter, selectedProject),
          fetchNamespaceDistribution(selectedTimeframe, selectedBotFilter, selectedProject),
          fetchBotDistribution(selectedTimeframe, selectedBotFilter, selectedProject),
          fetchTrendData(selectedTimeframe, selectedBotFilter, selectedProject),
        ]);

        if (cancelled) {
          return;
        }

        setKpis(kpiSnapshot);
        setLanguageBreakdown(languages);
        setNamespaceBreakdown(namespaces);
        setBotBreakdown(bots);
        setTrendData(trendBuckets);
        setError(null);
        setLastUpdatedAt(new Date().toISOString());
      } catch {
        if (!cancelled) {
          setError('Failed to fetch analytics datasets with the active filters.');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void loadAnalytics();

    const intervalId = window.setInterval(() => {
      void loadAnalytics();
    }, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [timeframe, isBot, project]);

  const topLanguageData = useMemo<LanguageChartDatum[]>(
    () =>
      languageBreakdown.map((entry) => ({
        language: languageLabelFromServerUrl(entry.serverUrl),
        edits: entry.count,
      })),
    [languageBreakdown],
  );

  const botDistributionData = useMemo<PieChartDatum[]>(() => {
    let botCount = 0;
    let humanCount = 0;

    for (const entry of botBreakdown) {
      if (entry.isBot === true) {
        botCount += entry.count;
      } else {
        humanCount += entry.count;
      }
    }

    return [
      { name: 'Bot', value: botCount },
      { name: 'Human', value: humanCount },
    ];
  }, [botBreakdown]);

  const namespaceDistributionData = useMemo<PieChartDatum[]>(
    () =>
      namespaceBreakdown
        .map((entry) => ({
          name: namespaceLabel(entry.namespace),
          value: entry.count,
        }))
        .filter((entry) => entry.value > 0),
    [namespaceBreakdown],
  );

  const hasLanguageData = topLanguageData.length > 0;
  const hasBotData = botDistributionData.some((entry) => entry.value > 0);
  const hasNamespaceData = namespaceDistributionData.length > 0;
  const hasTrendData = trendData.length > 0;
  const totalEdits = kpis?.totalEdits ?? 0;
  const botPercentage = kpis?.botPercentage ?? 0;
  const averageComplexity = kpis?.averageComplexity ?? 0;
  const botFilterValue = isBot === null ? 'all' : isBot ? 'bots' : 'humans';

  return (
    <section className="analytics-shell">

      {/* ── Header ─────────────────────────────────────── */}
      <header className="analytics-header">
        <div>
          <p className="analytics-kicker">// WikiPulse :: Command Center</p>
          <h2>Analytics Overview</h2>
          <p>
            Metrics sync with timeframe, bot-status, and project filters — refreshing every 10s.
          </p>
        </div>
        <div className="analytics-refresh-pill" aria-live="polite">
          ⟳ {formatUpdatedAt(lastUpdatedAt)}
        </div>
      </header>

      {/* ── Error Banner ────────────────────────────────── */}
      {error && (
        <section className="analytics-errors" aria-live="polite">
          <p>⚠ {error}</p>
        </section>
      )}

      {/* ══════════════════════════════════════════════════
          TOP ROW: Filter Controls + 3 KPI Cards
         ══════════════════════════════════════════════════ */}
      <div className="analytics-top-row">

        {/* Filter sidebar */}
        <section className="analytics-filter-bar" aria-label="Filter controls">
          <span className="analytics-filter-bar-label">// Filters</span>

          <div className="analytics-filter-control">
            <label htmlFor="analytics-timeframe">Timeframe</label>
            <select
              id="analytics-timeframe"
              value={timeframe}
              onChange={(event) => setTimeframe(event.target.value)}
            >
              <option value="">All Time</option>
              <option value="1h">Last 1 Hour</option>
              <option value="24h">Last 24 Hours</option>
              <option value="7d">Last 7 Days</option>
            </select>
          </div>

          <div className="analytics-filter-control">
            <label htmlFor="analytics-bot-filter">Bot Status</label>
            <select
              id="analytics-bot-filter"
              value={botFilterValue}
              onChange={(event) => setIsBot(parseBotFilterValue(event.target.value))}
            >
              <option value="all">All</option>
              <option value="bots">Bots Only</option>
              <option value="humans">Humans Only</option>
            </select>
          </div>

          <div className="analytics-filter-control">
            <label htmlFor="analytics-project-filter">Project</label>
            <select
              id="analytics-project-filter"
              value={project}
              onChange={(event) => setProject(event.target.value)}
            >
              <option value="all">All</option>
              <option value="wikipedia">Wikipedia</option>
              <option value="wikimedia-commons">Commons</option>
              <option value="wikidata">Wikidata</option>
            </select>
          </div>
        </section>

        {/* KPI Cards */}
        <section className="analytics-kpi-row" aria-label="KPI metrics">
          <article className="analytics-kpi-card">
            <p className="analytics-kpi-label">Total Edits</p>
            <p className="analytics-kpi-value">{totalEdits.toLocaleString()}</p>
          </article>
          <article className="analytics-kpi-card">
            <p className="analytics-kpi-label">Bot Percentage</p>
            <p className="analytics-kpi-value">{botPercentage.toFixed(1)}%</p>
          </article>
          <article className="analytics-kpi-card">
            <p className="analytics-kpi-label">Avg Complexity</p>
            <p className="analytics-kpi-value">{averageComplexity.toFixed(1)}</p>
          </article>
        </section>

      </div>

      {/* ══════════════════════════════════════════════════
          MIDDLE ROW: Phase-25 Placeholder Panels
         ══════════════════════════════════════════════════ */}
      <div className="analytics-middle-row">

        {/* Left: Live Event Ticker & Anomalies */}
        <div className="analytics-placeholder-panel" aria-label="Live Event Ticker placeholder">
          <div className="analytics-placeholder-head">
            <h3>Live Event Ticker &amp; Anomalies</h3>
            <span className="analytics-placeholder-badge analytics-placeholder-badge--coming">Phase 25</span>
          </div>
          <div className="analytics-placeholder-body">
            <div className="analytics-placeholder-inner">
              <span className="analytics-placeholder-icon" aria-hidden="true">⚡</span>
              <p>Real-time anomaly stream — coming in Phase 25</p>
            </div>
          </div>
        </div>

        {/* Right: Geographic Distribution Map */}
        <div className="analytics-placeholder-panel" aria-label="Geographic Distribution Map placeholder">
          <div className="analytics-placeholder-head">
            <h3>Geographic Distribution Map</h3>
            <span className="analytics-placeholder-badge analytics-placeholder-badge--phase">Phase 25</span>
          </div>
          <div className="analytics-placeholder-body">
            <div className="analytics-placeholder-inner">
              <span className="analytics-placeholder-icon" aria-hidden="true">🌍</span>
              <p>Global edit distribution map — coming in Phase 25</p>
            </div>
          </div>
        </div>

      </div>

      {/* ══════════════════════════════════════════════════
          BOTTOM ROW: Recharts Grid
         ══════════════════════════════════════════════════ */}
      <div className="analytics-grid">

        {/* Top Languages Bar Chart */}
        <article className="analytics-card analytics-card-wide">
          <div className="analytics-card-head">
            <h3>Top 5 Languages</h3>
            <p>Derived from Wikimedia server origin (serverUrl).</p>
          </div>
          <div className="chart-frame chart-frame-bar">
            {loading && !hasLanguageData ? (
              <div className="chart-empty">Loading chart...</div>
            ) : hasLanguageData ? (
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={topLanguageData} margin={{ top: 8, right: 16, left: -16, bottom: 8 }}>
                  <CartesianGrid strokeDasharray="none" vertical={false} stroke="rgba(0,0,0,0.08)" />
                  <XAxis
                    dataKey="language"
                    tickLine={false}
                    axisLine={{ stroke: '#000000', strokeWidth: 1 }}
                    tick={{ fontFamily: "'Roboto Mono', monospace", fontSize: 11 }}
                  />
                  <YAxis
                    allowDecimals={false}
                    tickLine={false}
                    axisLine={false}
                    tick={{ fontFamily: "'Roboto Mono', monospace", fontSize: 11 }}
                  />
                  <Tooltip
                    formatter={(value) => formatTooltipValue(value as TooltipValue)}
                    contentStyle={TOOLTIP_CONTENT_STYLE}
                    cursor={{ fill: 'rgba(0,0,0,0.05)' }}
                  />
                  <Bar dataKey="edits" fill="#0a0a0a" radius={[0, 0, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <div className="chart-empty">No language data yet.</div>
            )}
          </div>
        </article>

        {/* Bot vs Human Pie Chart */}
        <article className="analytics-card">
          <div className="analytics-card-head">
            <h3>Bot vs Human</h3>
            <p>Distribution from /api/analytics/bots.</p>
          </div>
          <div className="chart-frame">
            {loading && !hasBotData ? (
              <div className="chart-empty">Loading chart...</div>
            ) : hasBotData ? (
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={botDistributionData}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={56}
                    outerRadius={88}
                    paddingAngle={0}
                    strokeWidth={2}
                    stroke="#000000"
                  >
                    {botDistributionData.map((entry, index) => (
                      <Cell key={entry.name} fill={BOT_SLICE_COLORS[index % BOT_SLICE_COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip
                    formatter={(value) => formatTooltipValue(value as TooltipValue)}
                    contentStyle={TOOLTIP_CONTENT_STYLE}
                  />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <div className="chart-empty">No bot/human data yet.</div>
            )}
          </div>
        </article>

        {/* Namespace Distribution Pie Chart */}
        <article className="analytics-card">
          <div className="analytics-card-head">
            <h3>Namespace Distribution</h3>
            <p>Content class segmentation from /api/analytics/namespaces.</p>
          </div>
          <div className="chart-frame">
            {loading && !hasNamespaceData ? (
              <div className="chart-empty">Loading chart...</div>
            ) : hasNamespaceData ? (
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={namespaceDistributionData}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={56}
                    outerRadius={88}
                    paddingAngle={0}
                    strokeWidth={2}
                    stroke="#000000"
                  >
                    {namespaceDistributionData.map((entry, index) => (
                      <Cell
                        key={entry.name}
                        fill={NAMESPACE_SLICE_COLORS[index % NAMESPACE_SLICE_COLORS.length]}
                      />
                    ))}
                  </Pie>
                  <Tooltip
                    formatter={(value) => formatTooltipValue(value as TooltipValue)}
                    contentStyle={TOOLTIP_CONTENT_STYLE}
                  />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <div className="chart-empty">No namespace data yet.</div>
            )}
          </div>
        </article>

        {/* Edit Volume Trend Area Chart */}
        <article className="analytics-card analytics-card-wide">
          <div className="analytics-card-head">
            <h3>Edit Volume over Time</h3>
            <p>Trend rollups from /api/analytics/trend.</p>
          </div>
          <div className="chart-frame chart-frame-area">
            {loading && !hasTrendData ? (
              <div className="chart-empty">Loading chart...</div>
            ) : hasTrendData ? (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={trendData} margin={{ top: 8, right: 16, left: -16, bottom: 8 }}>
                  <defs>
                    <linearGradient id="trendTotalGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#00ff66" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="#00ff66" stopOpacity={0.04} />
                    </linearGradient>
                    <linearGradient id="trendBotGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#ff00ff" stopOpacity={0.3} />
                      <stop offset="95%" stopColor="#ff00ff" stopOpacity={0.04} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="none" vertical={false} stroke="rgba(0,0,0,0.08)" />
                  <XAxis
                    dataKey="timeBucket"
                    tickFormatter={(value) => formatTrendBucketTick(String(value))}
                    minTickGap={24}
                    tickLine={false}
                    axisLine={{ stroke: '#000000', strokeWidth: 1 }}
                    tick={{ fontFamily: "'Roboto Mono', monospace", fontSize: 11 }}
                  />
                  <YAxis
                    allowDecimals={false}
                    tickLine={false}
                    axisLine={false}
                    tick={{ fontFamily: "'Roboto Mono', monospace", fontSize: 11 }}
                  />
                  <Tooltip
                    labelFormatter={(value) => formatTrendBucketLabel(String(value))}
                    formatter={(value) => formatTooltipValue(value as TooltipValue)}
                    contentStyle={TOOLTIP_CONTENT_STYLE}
                  />
                  <Area
                    type="monotone"
                    dataKey="totalEdits"
                    name="Total Edits"
                    stroke="#00ff66"
                    strokeWidth={2}
                    fill="url(#trendTotalGradient)"
                  />
                  <Area
                    type="monotone"
                    dataKey="botEdits"
                    name="Bot Edits"
                    stroke="#ff00ff"
                    strokeWidth={2}
                    fill="url(#trendBotGradient)"
                  />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <div className="chart-empty">No trend data yet.</div>
            )}
          </div>
        </article>

      </div>
    </section>
  );
}