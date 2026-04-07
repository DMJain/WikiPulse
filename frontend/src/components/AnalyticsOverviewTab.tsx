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

const BOT_SLICE_COLORS = ['#ca8a04', '#0f766e'];
const NAMESPACE_SLICE_COLORS = ['#0f766e', '#0f8a64', '#0891b2', '#d97706', '#b45309', '#64748b'];

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
  borderRadius: 12,
  border: '1px solid rgba(15, 118, 110, 0.3)',
  backgroundColor: 'rgba(255, 255, 255, 0.96)',
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
      <header className="analytics-header">
        <div>
          <p className="analytics-kicker">Analytics Overview</p>
          <h2>Aggregated Insights (Dynamic Filters + KPI Cards)</h2>
          <p>
            Metrics and charts stay in sync with timeframe, bot-status, and project filters,
            refreshing every 10 seconds.
          </p>
        </div>
        <div className="analytics-refresh-pill">Last updated: {formatUpdatedAt(lastUpdatedAt)}</div>
      </header>

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
          <p className="analytics-kpi-label">Average Complexity</p>
          <p className="analytics-kpi-value">{averageComplexity.toFixed(1)}</p>
        </article>
      </section>

      <section className="analytics-filter-bar" aria-label="Filter controls">
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
          <label htmlFor="analytics-project-filter">Project Filter</label>
          <select
            id="analytics-project-filter"
            value={project}
            onChange={(event) => setProject(event.target.value)}
          >
            <option value="all">All</option>
            <option value="wikipedia">Wikipedia</option>
            <option value="wikimedia-commons">Wikimedia Commons</option>
            <option value="wikidata">Wikidata</option>
          </select>
        </div>
      </section>

      {error && (
        <section className="analytics-errors" aria-live="polite">
          <p>{error}</p>
        </section>
      )}

      <div className="analytics-grid">
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
                  <CartesianGrid strokeDasharray="4 4" vertical={false} stroke="rgba(70, 98, 91, 0.24)" />
                  <XAxis dataKey="language" tickLine={false} axisLine={false} />
                  <YAxis allowDecimals={false} tickLine={false} axisLine={false} />
                  <Tooltip
                    formatter={(value) => formatTooltipValue(value as TooltipValue)}
                    contentStyle={TOOLTIP_CONTENT_STYLE}
                    cursor={{ fill: 'rgba(15, 138, 100, 0.08)' }}
                  />
                  <Bar dataKey="edits" fill="#0f8a64" radius={[8, 8, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <div className="chart-empty">No language data yet.</div>
            )}
          </div>
        </article>

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
                    paddingAngle={2}
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
                    paddingAngle={2}
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
                      <stop offset="5%" stopColor="#0f8a64" stopOpacity={0.4} />
                      <stop offset="95%" stopColor="#0f8a64" stopOpacity={0.05} />
                    </linearGradient>
                    <linearGradient id="trendBotGradient" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#ca8a04" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="#ca8a04" stopOpacity={0.04} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="4 4" vertical={false} stroke="rgba(70, 98, 91, 0.24)" />
                  <XAxis
                    dataKey="timeBucket"
                    tickFormatter={(value) => formatTrendBucketTick(String(value))}
                    minTickGap={24}
                    tickLine={false}
                    axisLine={false}
                  />
                  <YAxis allowDecimals={false} tickLine={false} axisLine={false} />
                  <Tooltip
                    labelFormatter={(value) => formatTrendBucketLabel(String(value))}
                    formatter={(value) => formatTooltipValue(value as TooltipValue)}
                    contentStyle={TOOLTIP_CONTENT_STYLE}
                  />
                  <Area
                    type="monotone"
                    dataKey="totalEdits"
                    name="Total Edits"
                    stroke="#0f8a64"
                    strokeWidth={2}
                    fill="url(#trendTotalGradient)"
                  />
                  <Area
                    type="monotone"
                    dataKey="botEdits"
                    name="Bot Edits"
                    stroke="#ca8a04"
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