import { api } from "./apiClient.ts";

export interface DailyPoint {
  date: string;            // YYYY-MM-DD
  revenueSom: number | null;
  paidJobs: number;
}

export interface KioskStat {
  kioskId: string;
  revenueSom: number | null;
  paidJobs: number;
  pages: number;
}

export interface Dashboard {
  periodDays: number;

  todayRevenueSom: number | null;
  todayPaidJobs: number;
  todayPages: number;

  periodRevenueSom: number | null;
  periodPaidJobs: number;
  periodPages: number;
  periodFailedJobs: number;

  daily: DailyPoint[];
  byKiosk: KioskStat[];
}

export function fetchDashboard(days = 30): Promise<Dashboard> {
  return api<Dashboard>(`/admin/dashboard?days=${days}`);
}

export function formatSom(v: number | null): string {
  if (v === null) return "—";
  return new Intl.NumberFormat("ru-RU").format(v) + " с";
}

export function formatNum(v: number): string {
  return new Intl.NumberFormat("ru-RU").format(v);
}

/** «12 июл» — короткая подпись для оси графика. */
export function shortDate(iso: string): string {
  return new Date(iso + "T00:00:00").toLocaleDateString("ru-RU", {
    day: "numeric", month: "short",
  });
}
