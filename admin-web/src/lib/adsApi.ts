import { api, apiUpload } from "./apiClient.ts";

export type AdSlot = "HOME" | "BANNER";
export type AdMediaType = "IMAGE" | "VIDEO";

export interface AdCreative {
  id: string;
  title: string;
  mediaType: AdMediaType;
  slot: AdSlot;
  mediaUrl: string;
  contentType: string;
  fileSize: number;
  durationSec: number | null;
  sortOrder: number;
  enabled: boolean;
  createdAt: string;
}

export const SLOT_LABEL: Record<AdSlot, string> = {
  HOME: "Главный экран",
  BANNER: "Нижний баннер",
};

export const SLOT_HINT: Record<AdSlot, string> = {
  HOME: "Крупный медиа-блок, играет во время простоя киоска.",
  BANNER: "Узкая полоса внизу всех экранов.",
};

/** Что принимает сервер (AdService.ALLOWED_TYPES). */
export const ACCEPT_MIME = "image/jpeg,image/png,image/gif,video/mp4,video/webm";

/** Все креативы слота, включая выключенные (для админки). */
export function listAds(slot: AdSlot): Promise<AdCreative[]> {
  return api<AdCreative[]>(`/ads/admin?slot=${slot}`);
}

export interface UploadParams {
  file: File;
  slot: AdSlot;
  title?: string;
  /** Обязателен для картинок (сервер отклонит IMAGE без длительности). */
  durationSec?: number;
  sortOrder?: number;
}

export function uploadAd(p: UploadParams): Promise<AdCreative> {
  const qs = new URLSearchParams({ slot: p.slot });
  if (p.title) qs.set("title", p.title);
  if (p.durationSec != null) qs.set("durationSec", String(p.durationSec));
  if (p.sortOrder != null) qs.set("sortOrder", String(p.sortOrder));

  const form = new FormData();
  form.append("file", p.file);

  return apiUpload<AdCreative>(`/ads/admin?${qs.toString()}`, form);
}

export function updateAd(
  id: string,
  fields: { title?: string; sortOrder?: number; durationSec?: number }
): Promise<AdCreative> {
  const qs = new URLSearchParams();
  if (fields.title != null) qs.set("title", fields.title);
  if (fields.sortOrder != null) qs.set("sortOrder", String(fields.sortOrder));
  if (fields.durationSec != null) qs.set("durationSec", String(fields.durationSec));
  return api<AdCreative>(`/ads/admin/${id}?${qs.toString()}`, { method: "PATCH" });
}

export function setAdEnabled(id: string, enabled: boolean): Promise<AdCreative> {
  return api<AdCreative>(`/ads/admin/${id}/enabled?enabled=${enabled}`, { method: "PATCH" });
}

export function deleteAd(id: string): Promise<void> {
  return api<void>(`/ads/admin/${id}`, { method: "DELETE" });
}

/** Человекочитаемый размер файла. */
export function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} Б`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} КБ`;
  return `${(bytes / 1024 / 1024).toFixed(1)} МБ`;
}
