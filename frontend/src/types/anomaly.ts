export interface AnomalyAlert {
  id: string;
  pageTitle: string;
  anomalyType: string;
  eventCount: number;
  windowStart: string;
  windowEnd: string;
}
