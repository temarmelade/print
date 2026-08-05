import { api } from "./apiClient.ts";

export type IncidentType =
  | "OFFLINE" | "PAPER_JAM" | "PAPER_OUT" | "TONER_EMPTY" | "DOOR_OPEN"
  | "PRINTER_OFFLINE" | "PRINTER_ERROR" | "TONER_LOW" | "PAPER_LOW";

export type IncidentSeverity = "DOWN" | "WARNING";

export interface Incident {
  id: number;
  kioskId: string;
  kioskName: string;
  location: string | null;
  incidentType: IncidentType;
  severity: IncidentSeverity;
  title: string;
  reason: string | null;
  startedAt: string;
  resolvedAt: string | null;
  durationMinutes: number;
  occurrences: number;
  acknowledgedAt: string | null;
  acknowledgedBy: string | null;
}

export interface TypeCount {
  incidentType: IncidentType;
  title: string;
  count: number;
  totalMinutes: number;
}

export interface KioskIncidentCount {
  kioskId: string;
  kioskName: string;
  count: number;
  downtimeMinutes: number;
}

export interface IncidentSummary {
  periodDays: number;
  openBlocking: number;
  openWarning: number;
  totalInPeriod: number;
  avgResolutionMinutes: number;
  totalDowntimeMinutes: number;
  topTypes: TypeCount[];
  topKiosks: KioskIncidentCount[];
}

export function fetchOpenIncidents(): Promise<Incident[]> {
  return api<Incident[]>("/admin/incidents");
}

export function fetchIncidentHistory(days = 30, kioskId?: string): Promise<Incident[]> {
  const qs = new URLSearchParams({ days: String(days), size: "50" });
  if (kioskId) qs.set("kioskId", kioskId);
  return api<Incident[]>(`/admin/incidents/history?${qs.toString()}`);
}

export function fetchIncidentSummary(days = 30): Promise<IncidentSummary> {
  return api<IncidentSummary>(`/admin/incidents/summary?days=${days}`);
}

export function acknowledgeIncident(id: number): Promise<void> {
  return api<void>(`/admin/incidents/${id}/acknowledge`, { method: "POST" });
}

export function resolveIncident(id: number): Promise<void> {
  return api<void>(`/admin/incidents/${id}/resolve`, { method: "POST" });
}

/** Иконка типа проблемы — узнаётся быстрее, чем текст. */
export const INCIDENT_ICON: Record<IncidentType, string> = {
  OFFLINE: "📡",
  PAPER_JAM: "📄",
  PAPER_OUT: "📭",
  TONER_EMPTY: "🖨",
  DOOR_OPEN: "🚪",
  PRINTER_OFFLINE: "🔌",
  PRINTER_ERROR: "⚠️",
  TONER_LOW: "🟡",
  PAPER_LOW: "🟡",
};

/**
 * Длительность в человеческом виде. Для дежурного «2 ч 15 мин» читается
 * мгновенно, а «135 мин» требует пересчёта в уме.
 */
export function formatDuration(minutes: number): string {
  if (minutes < 1) return "только что";
  if (minutes < 60) return `${minutes} мин`;
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h < 24) return m > 0 ? `${h} ч ${m} мин` : `${h} ч`;
  const d = Math.floor(h / 24);
  return `${d} д ${h % 24} ч`;
}
