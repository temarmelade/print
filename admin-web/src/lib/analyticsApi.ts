import { api } from "./apiClient.ts";
import type { OperationType } from "./txApi.ts";

export interface OperationStat {
  operationType: OperationType;
  jobs: number;
  paidJobs: number;
  pages: number;
  revenueSom: number | null;
  sharePercent: number;
  conversionPercent: number;
  avgPages: number;
  avgCopies: number;
}

export type UploadSource = "TELEGRAM" | "WEBSITE" | "SCAN" | "COPY" | "UNKNOWN";

export interface SourceStat {
  source: UploadSource;
  jobs: number;
  paidJobs: number;
  pages: number;
  revenueSom: number | null;
  sharePercent: number;
  conversionPercent: number;
  avgPages: number;
}

export interface FormatStat {
  extension: string;
  jobs: number;
  paidJobs: number;
  pages: number;
  sharePercent: number;
  conversionPercent: number;
}

export interface OperationDaily {
  date: string;
  operationType: OperationType;
  paidJobs: number;
  revenueSom: number | null;
}

export interface HourlyPoint {
  hour: number;
  jobs: number;
  paidJobs: number;
  revenueSom: number | null;
}

export interface WeekdayPoint {
  weekday: number;          // 1 = понедельник … 7 = воскресенье
  paidJobs: number;
  revenueSom: number | null;
}

export interface KioskPerformance {
  kioskId: string;
  jobs: number;
  paidJobs: number;
  pages: number;
  revenueSom: number | null;
  conversionPercent: number;
  avgCheckSom: number | null;
  topOperation: OperationType | null;
}

export interface VolumeBucket {
  label: string;
  jobs: number;
  sharePercent: number;
}

export interface Funnel {
  created: number;
  paymentCreated: number;
  paid: number;
  completed: number;
  failed: number;
  expired: number;
  paymentRatePercent: number;
  paidRatePercent: number;
  completionRatePercent: number;
  lostRevenueSom: number | null;
}

export interface Averages {
  avgPagesPerJob: number;
  avgCopiesPerJob: number;
  avgCheckSom: number | null;
  maxOrderPages: number;
  avgMinutesToPayment: number;
}

export interface Analytics {
  periodDays: number;
  byOperation: OperationStat[];
  bySource: SourceStat[];
  byFormat: FormatStat[];
  dailyByOperation: OperationDaily[];
  hourly: HourlyPoint[];
  weekday: WeekdayPoint[];
  byKiosk: KioskPerformance[];
  volumeBuckets: VolumeBucket[];
  funnel: Funnel;
  averages: Averages;
}

export function fetchAnalytics(days = 30): Promise<Analytics> {
  return api<Analytics>(`/admin/analytics?days=${days}`);
}

export const WEEKDAY_LABEL: Record<number, string> = {
  1: "Пн", 2: "Вт", 3: "Ср", 4: "Чт", 5: "Пт", 6: "Сб", 7: "Вс",
};

export const SOURCE_LABEL: Record<UploadSource, string> = {
  TELEGRAM: "Telegram",
  WEBSITE: "Сайт",
  SCAN: "Сканер киоска",
  COPY: "Ксерокопия",
  UNKNOWN: "Не определён",
};

/**
 * Внешние каналы (пользователь загрузил документ) против внутренних
 * (документ создан на самом киоске). Конверсию канала имеет смысл сравнивать
 * только внутри группы: скан уже стоит у терминала, ему «дойти до оплаты»
 * куда проще, чем тому, кто загрузил файл из дома.
 */
export const SOURCE_IS_EXTERNAL: Record<UploadSource, boolean> = {
  TELEGRAM: true,
  WEBSITE: true,
  SCAN: false,
  COPY: false,
  UNKNOWN: false,
};

export function formatPercent(v: number): string {
  return `${v.toFixed(1).replace(/\.0$/, "")}%`;
}

/** «1,2 стр» — компактные средние без лишних нулей. */
export function formatAvg(v: number): string {
  return v.toFixed(1).replace(/\.0$/, "").replace(".", ",");
}

/** Часы «дедлайна»: топ-3 часа по количеству заданий. */
export function peakHours(hourly: HourlyPoint[]): number[] {
  return [...hourly]
    .filter((h) => h.jobs > 0)
    .sort((a, b) => b.jobs - a.jobs)
    .slice(0, 3)
    .map((h) => h.hour)
    .sort((a, b) => a - b);
}
