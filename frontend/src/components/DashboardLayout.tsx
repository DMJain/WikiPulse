import { useState } from 'react';
import AnalyticsOverviewTab from './AnalyticsOverviewTab.tsx';
import LiveFirehoseTab from './LiveFirehoseTab.tsx';
import './DashboardLayout.css';

type DashboardTab = 'analytics' | 'firehose';

interface TabDefinition {
  id: DashboardTab;
  label: string;
  subtitle: string;
}

const TAB_DEFINITIONS: TabDefinition[] = [
  {
    id: 'analytics',
    label: 'Analytics Overview',
    subtitle: 'REST-powered aggregate metrics and chart snapshots',
  },
  {
    id: 'firehose',
    label: 'Live Firehose',
    subtitle: 'Real-time STOMP stream for event-by-event monitoring',
  },
];

export default function DashboardLayout() {
  const [activeTab, setActiveTab] = useState<DashboardTab>('analytics');

  return (
    <section className="dashboard-shell">
      <header className="dashboard-header">
        <p className="dashboard-kicker">WikiPulse</p>
        <h1>Phase 8 Analytics Console</h1>
        <p className="dashboard-subtitle">
          Explore high-level analytics in one tab and keep live operational visibility in the other.
        </p>
      </header>

      <nav className="dashboard-tabs" aria-label="Dashboard sections" role="tablist">
        {TAB_DEFINITIONS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`tab-${tab.id}`}
            aria-controls={`panel-${tab.id}`}
            aria-selected={activeTab === tab.id}
            className={`dashboard-tab-button ${activeTab === tab.id ? 'active' : ''}`}
            onClick={() => {
              setActiveTab(tab.id);
            }}
          >
            <span className="tab-label">{tab.label}</span>
            <span className="tab-subtitle">{tab.subtitle}</span>
          </button>
        ))}
      </nav>

      <section
        id={`panel-${activeTab}`}
        role="tabpanel"
        aria-labelledby={`tab-${activeTab}`}
        className="dashboard-panel"
      >
        {activeTab === 'analytics' ? <AnalyticsOverviewTab /> : <LiveFirehoseTab />}
      </section>
    </section>
  );
}