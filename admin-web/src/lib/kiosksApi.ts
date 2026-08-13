import { api } from "./apiClient.ts";

export type KioskHealth = "OK" | "WARNING" | "DOWN" | "MAINTENANCE";
export type SupplySource = "SENSOR" | "ESTIMATE" | "UNKNOWN";

export interface Kiosk {
  id: string;
  name: string;
  location: string | null;
  latitude: number | null;
  longitude: number | null;

  health: KioskHealth;
  healthReason: string;
  online: boolean;
  maintenanceMode: boolean;
  lastSeenAt: string | null;

  tonerPercent: number | null;
  tonerSource: SupplySource;
  paperPercent: number | null;
  paperSource: SupplySource;
  paperSheetsLeft: number | null;

  paperOut: boolean;
  paperJam: boolean;
  tonerLow: boolean;
  tonerEmpty: boolean;
  doorOpen: boolean;
  printerError: string | null;

  pageCounter: number | null;
  paperRefilledAt: string | null;
  cartridgeChangedAt: string | null;
}

export interface CreatedKiosk {
  id: string;
  name: string;
  apiKey: string;
}

/** Цвет точки: сигнальные цвета зарезервированы под статус киоска. */
export const HEALTH_TONE: Record<KioskHealth, "ok" | "warn" | "down" | "idle"> = {
  OK: "ok",
  WARNING: "warn",
  DOWN: "down",
  MAINTENANCE: "idle",
};

export const HEALTH_LABEL: Record<KioskHealth, string> = {
  OK: "Работает",
  WARNING: "Требует внимания",
  DOWN: "Не работает",
  MAINTENANCE: "Обслуживание",
};

/** Пояснение к цифре: датчик это или наша оценка. Врать нельзя. */
export const SOURCE_HINT: Record<SupplySource, string> = {
  SENSOR: "показание принтера",
  ESTIMATE: "оценка по счётчику страниц",
  UNKNOWN: "принтер не сообщает",
};

export function listKiosks(): Promise<Kiosk[]> {
  return api<Kiosk[]>("/admin/kiosks");
}

export function createKiosk(body: {
  id: string;
  name: string;
  location?: string;
  latitude?: number;
  longitude?: number;
  paperCapacity?: number;
  cartridgeYield?: number;
}): Promise<CreatedKiosk> {
  return api<CreatedKiosk>("/admin/kiosks", { method: "POST", body });
}

/** Частичное обновление: шлём только изменённые поля. */
export function updateKiosk(id: string, body: {
  name?: string;
  location?: string | null;
  latitude?: number | null;
  longitude?: number | null;
  paperCapacity?: number;
  cartridgeYield?: number;
}): Promise<void> {
  return api<void>(`/admin/kiosks/${id}`, { method: "PATCH", body });
}

export interface SupplyForecast {
  kioskId: string;
  pagesPerDay: number | null;
  paperEmptyAt: string | null;
  paperDaysLeft: number | null;
  tonerEmptyAt: string | null;
  tonerDaysLeft: number | null;
  samples: number;
  windowHours: number;
}

export function fetchForecast(id: string): Promise<SupplyForecast> {
  return api<SupplyForecast>(`/admin/kiosks/${id}/forecast`);
}

/**
 * «через 3 дня» / «завтра» — техник планирует выезд днями, а не датами.
 * null означает «данных недостаточно», и это надо показать честно.
 */
export function formatDaysLeft(days: number | null): string {
  if (days === null) return "н/д";
  if (days <= 0) return "уже кончается";
  if (days === 1) return "завтра";
  if (days < 5) return `через ${days} дня`;
  if (days >= 365) return "не скоро";
  return `через ${days} дней`;
}

export function rotateKey(id: string): Promise<CreatedKiosk> {
  return api<CreatedKiosk>(`/admin/kiosks/${id}/rotate-key`, { method: "POST" });
}

export function markPaperRefilled(id: string): Promise<void> {
  return api<void>(`/admin/kiosks/${id}/paper-refilled`, { method: "POST" });
}

export function markCartridgeChanged(id: string): Promise<void> {
  return api<void>(`/admin/kiosks/${id}/cartridge-changed`, { method: "POST" });
}

export function setMaintenance(id: string, enabled: boolean): Promise<void> {
  return api<void>(`/admin/kiosks/${id}/maintenance?enabled=${enabled}`, { method: "PATCH" });
}

export function deleteKiosk(id: string): Promise<void> {
  return api<void>(`/admin/kiosks/${id}`, { method: "DELETE" });
}

export function lastSeen(iso: string | null): string {
  if (!iso) return "никогда";
  const sec = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (sec < 60) return "только что";
  if (sec < 3600) return `${Math.floor(sec / 60)} мин назад`;
  if (sec < 86400) return `${Math.floor(sec / 3600)} ч назад`;
  return `${Math.floor(sec / 86400)} дн назад`;
}

/* ─────────────── Дистанционные команды ─────────────── */

export type KioskCommandType = "RESTART_APP" | "REBOOT_OS";
export type KioskCommandStatus =
  | "PENDING" | "SENT" | "DONE" | "FAILED" | "EXPIRED" | "CANCELLED";

export interface KioskCommand {
  id: string;
  kioskId: string;
  type: KioskCommandType;
  status: KioskCommandStatus;
  createdBy: string | null;
  createdAt: string;
  dispatchedAt: string | null;
  finishedAt: string | null;
  resultMessage: string | null;
}

export const COMMAND_LABEL: Record<KioskCommandType, string> = {
  RESTART_APP: "Перезапуск приложения",
  REBOOT_OS: "Перезагрузка Windows",
};

export const COMMAND_STATUS_LABEL: Record<KioskCommandStatus, string> = {
  PENDING: "В очереди",
  SENT: "Отправлена",
  DONE: "Выполнена",
  FAILED: "Отклонена",
  EXPIRED: "Просрочена",
  CANCELLED: "Отменена",
};

/** Ставит команду в очередь. Киоск заберёт её в течение ~30 секунд. */
export function sendCommand(
  kioskId: string,
  type: KioskCommandType
): Promise<KioskCommand> {
  return api<KioskCommand>(
    `/admin/kiosks/${encodeURIComponent(kioskId)}/commands?type=${type}`,
    { method: "POST" }
  );
}

export function commandHistory(kioskId: string): Promise<KioskCommand[]> {
  return api<KioskCommand[]>(`/admin/kiosks/${encodeURIComponent(kioskId)}/commands`);
}

/** Отзыв команды, которую киоск ещё не забрал. */
export function cancelCommand(commandId: string): Promise<void> {
  return api<void>(`/admin/kiosks/commands/${commandId}`, { method: "DELETE" });
}
