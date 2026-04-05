import { useEffect, useMemo, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import {
  fetchBotDistribution,
  fetchNamespaceDistribution,
  fetchTopLanguages,
  type BotCount,
  type LanguageCount,
  type NamespaceCount,
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

function namespaceLabel(namespaceId: number | null): string {
  if (namespaceId === null) {
    return 'Unknown';
  }

  return NAMESPACE_LABELS[String(namespaceId)] ?? `NS ${namespaceId}`;
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

export default function AnalyticsOverviewTab() {
  const [languageBreakdown, setLanguageBreakdown] = useState<LanguageCount[]>([]);
  const [namespaceBreakdown, setNamespaceBreakdown] = useState<NamespaceCount[]>([]);
  const [botBreakdown, setBotBreakdown] = useState<BotCount[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const loadAnalytics = async () => {
      try {
        const [languages, namespaces, bots] = await Promise.all([
          fetchTopLanguages(5),
          fetchNamespaceDistribution(),
          fetchBotDistribution(),
        ]);

        if (cancelled) {
          return;
        }

        setLanguageBreakdown(languages);
        setNamespaceBreakdown(namespaces);
        setBotBreakdown(bots);
        setError(null);
        setLastUpdatedAt(new Date().toISOString());
      } catch {
        if (!cancelled) {
          setError('Failed to fetch analytics datasets from API endpoints.');
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
  }, []);

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

  return (
    <section className="analytics-shell">
      <header className="analytics-header">
        <div>
          <p className="analytics-kicker">Analytics Overview</p>
          <h2>Aggregated Insights (Phase 7 Endpoints)</h2>
          <p>
            Charts are hydrated from REST and refreshed every 10 seconds to keep trends current.
          </p>
        </div>
        <div className="analytics-refresh-pill">Last updated: {formatUpdatedAt(lastUpdatedAt)}</div>
      </header>

      {error && (
        <section className="analytics-errors" aria-live="polite">
          <p>{error}</p>
        </section>
      )}

      <div className="analytics-grid">
        <article className="analytics-card analytics-card-wide">
          <div className="analytics-card-head">
            <h3>Top 5 Languages</h3>
            <p>Derived from Wikimedia server origin ({`serverUrl`}).</p>
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
                  <Tooltip />
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
                  <Tooltip />
                  <Legend />
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
                  <Tooltip />
                  <Legend />
                </PieChart>
              </ResponsiveContainer>
            ) : (
              <div className="chart-empty">No namespace data yet.</div>
            )}
          </div>
        </article>
      </div>
    </section>
  );
}