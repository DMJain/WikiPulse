import axios from 'axios';
import type { EditUpdate } from '../types/edit';

export interface LanguageCount {
  serverUrl: string | null;
  count: number;
}

export interface NamespaceCount {
  namespace: number | null;
  count: number;
}

export interface BotCount {
  isBot: boolean | null;
  count: number;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
});

export async function fetchRecentEdits(limit = 100): Promise<EditUpdate[]> {
  const response = await http.get<EditUpdate[]>('/api/edits/recent', {
    params: { limit },
  });

  return response.data;
}

export async function fetchTopLanguages(limit = 5): Promise<LanguageCount[]> {
  const response = await http.get<LanguageCount[]>('/api/analytics/languages', {
    params: { limit },
  });

  return response.data;
}

export async function fetchNamespaceDistribution(): Promise<NamespaceCount[]> {
  const response = await http.get<NamespaceCount[]>('/api/analytics/namespaces');
  return response.data;
}

export async function fetchBotDistribution(): Promise<BotCount[]> {
  const response = await http.get<BotCount[]>('/api/analytics/bots');
  return response.data;
}