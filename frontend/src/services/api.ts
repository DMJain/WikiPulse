import axios from 'axios';
import type { EditUpdate } from '../types/edit';

export interface LanguageCount {
  serverUrl: string | null;
  count: number;
}

export interface NamespaceCountDto {
  namespace: string | null;
  count: number;
}

export type NamespaceCount = NamespaceCountDto;

export interface BotCount {
  isBot: boolean | null;
  count: number;
}

export interface KpiSnapshot {
  totalEdits: number;
  botPercentage: number;
  averageComplexity: number;
}

interface AnalyticsFilters {
  timeframe?: string;
  isBot?: boolean;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

const http = axios.create({
  baseURL: API_BASE_URL,
  timeout: 10000,
});

function buildAnalyticsParams(timeframe?: string, isBot?: boolean): AnalyticsFilters {
  return {
    ...(timeframe ? { timeframe } : {}),
    ...(typeof isBot === 'boolean' ? { isBot } : {}),
  };
}

export async function fetchRecentEdits(limit = 100): Promise<EditUpdate[]> {
  const response = await http.get<EditUpdate[]>('/api/edits/recent', {
    params: { limit },
  });

  return response.data;
}

export async function fetchTopLanguages(
  limit = 5,
  timeframe?: string,
  isBot?: boolean,
): Promise<LanguageCount[]> {
  const response = await http.get<LanguageCount[]>('/api/analytics/languages', {
    params: {
      limit,
      ...buildAnalyticsParams(timeframe, isBot),
    },
  });

  return response.data;
}

export async function fetchNamespaceDistribution(
  timeframe?: string,
  isBot?: boolean,
): Promise<NamespaceCount[]> {
  const response = await http.get<NamespaceCount[]>('/api/analytics/namespaces', {
    params: buildAnalyticsParams(timeframe, isBot),
  });

  return response.data;
}

export async function fetchBotDistribution(timeframe?: string, isBot?: boolean): Promise<BotCount[]> {
  const response = await http.get<BotCount[]>('/api/analytics/bots', {
    params: buildAnalyticsParams(timeframe, isBot),
  });

  return response.data;
}

export async function fetchKpis(timeframe?: string, isBot?: boolean): Promise<KpiSnapshot> {
  const response = await http.get<KpiSnapshot>('/api/analytics/kpis', {
    params: buildAnalyticsParams(timeframe, isBot),
  });

  return response.data;
}