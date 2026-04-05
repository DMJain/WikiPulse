import { useEffect, useState } from 'react';
import { fetchRecentEdits } from '../services/api';
import { createEditStream, type SocketState } from '../services/websocket';
import type { EditUpdate } from '../types/edit';
import './LiveEditFeed.css';

const MAX_ITEMS = 100;

function mergeEdits(primary: EditUpdate[], secondary: EditUpdate[]): EditUpdate[] {
  const seenIds = new Set<number>();
  const merged: EditUpdate[] = [];

  for (const edit of [...primary, ...secondary]) {
    if (seenIds.has(edit.id)) {
      continue;
    }

    seenIds.add(edit.id);
    merged.push(edit);

    if (merged.length >= MAX_ITEMS) {
      break;
    }
  }

  return merged;
}

function formatTimestamp(timestamp: string): string {
  const date = new Date(timestamp);
  if (Number.isNaN(date.getTime())) {
    return timestamp;
  }
  return date.toLocaleString();
}

function statusLabel(status: SocketState): string {
  switch (status) {
    case 'connecting':
      return 'Connecting';
    case 'connected':
      return 'Live';
    case 'disconnected':
      return 'Offline';
    case 'error':
      return 'Error';
    default:
      return 'Offline';
  }
}

export default function LiveFirehoseTab() {
  const [edits, setEdits] = useState<EditUpdate[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [streamError, setStreamError] = useState<string | null>(null);
  const [socketState, setSocketState] = useState<SocketState>('disconnected');

  useEffect(() => {
    let cancelled = false;

    const loadRecent = async () => {
      try {
        const recentEdits = await fetchRecentEdits(MAX_ITEMS);
        if (cancelled) {
          return;
        }

        setEdits((current) => mergeEdits(current, recentEdits));
        setLoadError(null);
      } catch {
        if (!cancelled) {
          setLoadError('Failed to fetch recent edits from API.');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    void loadRecent();

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const stream = createEditStream({
      onEdit: (edit) => {
        setEdits((current) => mergeEdits([edit], current));
      },
      onStatusChange: (nextState) => {
        setSocketState(nextState);
        if (nextState === 'connected') {
          setStreamError(null);
        }
      },
      onError: (message) => {
        setStreamError(message);
      },
    });

    stream.connect();

    return () => {
      void stream.disconnect();
    };
  }, []);

  return (
    <section className="feed-shell">
      <header className="feed-header">
        <div>
          <p className="feed-kicker">Live Firehose</p>
          <h1>Real-Time Wikipedia Edit Feed</h1>
          <p className="feed-subtitle">
            Initial snapshot is loaded from REST, then updates stream in via STOMP over SockJS.
          </p>
        </div>

        <div className="feed-metrics">
          <div className="metric-pill">
            <span>Window</span>
            <strong>
              {edits.length}/{MAX_ITEMS}
            </strong>
          </div>
          <div className={`socket-pill socket-${socketState}`}>{statusLabel(socketState)}</div>
        </div>
      </header>

      {(loadError || streamError) && (
        <section className="feed-errors" aria-live="polite">
          {loadError && <p>{loadError}</p>}
          {streamError && <p>{streamError}</p>}
        </section>
      )}

      {loading && edits.length === 0 ? (
        <div className="feed-loading">Loading recent edits...</div>
      ) : (
        <ul className="edit-list" aria-live="polite">
          {edits.map((edit) => (
            <li key={edit.id} className={`edit-card ${edit.isBot ? 'bot' : 'human'}`}>
              <div className="edit-top-row">
                <span className={`edit-badge ${edit.isBot ? 'bot-badge' : 'human-badge'}`}>
                  {edit.isBot ? 'Bot' : 'Human'}
                </span>
                <span className="event-type">{edit.eventType || 'edit'}</span>
                <time dateTime={edit.editTimestamp}>{formatTimestamp(edit.editTimestamp)}</time>
              </div>

              <h2 className="edit-title">{edit.pageTitle || 'Untitled Page'}</h2>

              <p className="edit-meta">
                <span className="meta-user">{edit.userName || 'unknown-user'}</span>
                <span>ID #{edit.id}</span>
                <span>Complexity {edit.complexityScore ?? 0}</span>
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}