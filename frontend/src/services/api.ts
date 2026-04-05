import axios from 'axios';
import type { EditUpdate } from '../types/edit';

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