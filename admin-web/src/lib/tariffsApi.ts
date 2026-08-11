import { api } from "./apiClient.ts";

export interface Tariff {
  id: string;
  /** null — глобальный тариф, действует на киоски без своей цены. */
  kioskId: string | null;
  kioskName: string | null;
  bwPriceSom: number;
  colorPriceSom: number;
  effectiveFrom: string;
  /** null у действующей цены, дата — у архивной. */
  effectiveTo: string | null;
}

/** Действующие цены: глобальная + переопределения киосков. */
export function listTariffs(): Promise<Tariff[]> {
  return api<Tariff[]>("/admin/tariffs");
}

/** История изменений. Без kioskId — история глобальной цены. */
export function tariffHistory(kioskId?: string): Promise<Tariff[]> {
  const qs = kioskId ? `?kioskId=${encodeURIComponent(kioskId)}` : "";
  return api<Tariff[]>(`/admin/tariffs/history${qs}`);
}

export interface PriceInput {
  bwPriceSom: number;
  colorPriceSom: number;
}

export function setDefaultTariff(p: PriceInput): Promise<Tariff> {
  return api<Tariff>("/admin/tariffs/default", {
    method: "PUT",
    body: p,
  });
}

export function setKioskTariff(kioskId: string, p: PriceInput): Promise<Tariff> {
  return api<Tariff>(`/admin/tariffs/${encodeURIComponent(kioskId)}`, {
    method: "PUT",
    body: p,
  });
}

/** Снять персональную цену — киоск вернётся на глобальную. */
export function resetKioskTariff(kioskId: string): Promise<void> {
  return api<void>(`/admin/tariffs/${encodeURIComponent(kioskId)}`, {
    method: "DELETE",
  });
}

export function formatSom(v: number): string {
  return `${v} сом`;
}
