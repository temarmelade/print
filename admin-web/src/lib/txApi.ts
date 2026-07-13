import { api } from "./apiClient.ts";

export type JobStatus =
  | "READY" | "PAYMENT_PENDING" | "PAID" | "PRINTING" | "COMPLETED" | "FAILED" | "EXPIRED";

export interface Transaction {
  id: string;
  pin: string;
  fileName: string;
  pageCount: number;
  copies: number;
  colorMode: string;
  priceSom: number;
  status: JobStatus;
  paymentStatus: string | null;
  paymentId: string | null;
  kioskId: string | null;
  createdAt: string;
  paidAt: string | null;
  completedAt: string | null;
}

export interface TransactionPage {
  items: Transaction[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  paidCount: number;
  revenueSom: number;
}

export const STATUS_LABEL: Record<JobStatus, string> = {
  READY: "Готов к оплате",
  PAYMENT_PENDING: "Ожидает оплаты",
  PAID: "Оплачен",
  PRINTING: "Печатается",
  COMPLETED: "Завершён",
  FAILED: "Ошибка",
  EXPIRED: "Истёк",
};

/** Цвет плашки статуса: сигнальные цвета — только для «здоровья» операции. */
export const STATUS_TONE: Record<JobStatus, "ok" | "warn" | "down" | "idle"> = {
  READY: "idle",
  PAYMENT_PENDING: "warn",
  PAID: "ok",
  PRINTING: "warn",
  COMPLETED: "ok",
  FAILED: "down",
  EXPIRED: "idle",
};

export interface TxFilters {
  from?: string;      // ISO
  to?: string;        // ISO
  status?: JobStatus | "";
  paymentStatus?: string;
  kioskId?: string;
  q?: string;
  page?: number;
  size?: number;
}

export function fetchTransactions(f: TxFilters): Promise<TransactionPage> {
  const qs = new URLSearchParams();
  if (f.from) qs.set("from", f.from);
  if (f.to) qs.set("to", f.to);
  if (f.status) qs.set("status", f.status);
  if (f.paymentStatus) qs.set("paymentStatus", f.paymentStatus);
  if (f.kioskId) qs.set("kioskId", f.kioskId);
  if (f.q?.trim()) qs.set("q", f.q.trim());
  qs.set("page", String(f.page ?? 0));
  qs.set("size", String(f.size ?? 25));
  return api<TransactionPage>(`/admin/transactions?${qs.toString()}`);
}

export function fetchKiosks(): Promise<string[]> {
  return api<string[]>("/admin/transactions/kiosks");
}

export function formatSom(v: number): string {
  return new Intl.NumberFormat("ru-RU").format(v) + " с";
}

export function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("ru-RU", {
    day: "2-digit", month: "2-digit", year: "2-digit",
    hour: "2-digit", minute: "2-digit",
  });
}

/** Границы дня в ISO — для быстрых пресетов периода. */
export function dayRange(daysBack: number): { from: string; to: string } {
  const now = new Date();
  const to = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
  const from = new Date(now.getFullYear(), now.getMonth(), now.getDate() - daysBack);
  return { from: from.toISOString(), to: to.toISOString() };
}
