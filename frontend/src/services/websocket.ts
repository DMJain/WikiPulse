import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { EditUpdate } from '../types/edit';

const WEBSOCKET_URL = import.meta.env.VITE_WS_URL ?? 'http://localhost:8080/ws-wikipulse';
const EDIT_TOPIC = '/topic/edits';

export type SocketState = 'connecting' | 'connected' | 'disconnected' | 'error';

export interface EditStreamHandlers {
  onEdit: (edit: EditUpdate) => void;
  onStatusChange?: (state: SocketState) => void;
  onError?: (message: string) => void;
}

export interface EditStreamController {
  connect: () => void;
  disconnect: () => Promise<void>;
}

function parseEditPayload(message: IMessage): EditUpdate | null {
  try {
    return JSON.parse(message.body) as EditUpdate;
  } catch {
    return null;
  }
}

export function createEditStream(handlers: EditStreamHandlers): EditStreamController {
  let subscription: StompSubscription | null = null;

  const client = new Client({
    webSocketFactory: () => new SockJS(WEBSOCKET_URL),
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
  });

  client.onConnect = () => {
    handlers.onStatusChange?.('connected');

    subscription = client.subscribe(EDIT_TOPIC, (message) => {
      const payload = parseEditPayload(message);
      if (!payload) {
        handlers.onError?.('Received malformed edit payload from stream.');
        return;
      }
      handlers.onEdit(payload);
    });
  };

  client.onStompError = (frame) => {
    handlers.onStatusChange?.('error');
    handlers.onError?.(frame.headers.message ?? 'STOMP broker error.');
  };

  client.onWebSocketError = () => {
    handlers.onStatusChange?.('error');
    handlers.onError?.('WebSocket transport error.');
  };

  client.onWebSocketClose = () => {
    handlers.onStatusChange?.('disconnected');
  };

  return {
    connect: () => {
      if (client.active) {
        return;
      }

      handlers.onStatusChange?.('connecting');
      client.activate();
    },
    disconnect: async () => {
      if (subscription) {
        subscription.unsubscribe();
        subscription = null;
      }

      handlers.onStatusChange?.('disconnected');
      await client.deactivate();
    },
  };
}