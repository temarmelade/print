import { api } from "./apiClient.ts";
import type { Role } from "../types.ts";

export interface AdminUser {
  id: string;
  name: string;
  username: string;
  role: Role;
  enabled: boolean;
  createdAt: string | null;
}

export const ROLE_HINT: Record<Role, string> = {
  OWNER: "Полный доступ: финансы, аналитика, реклама, сотрудники.",
  TECHNICIAN: "Киоски, расходники, инциденты. Выручку не видит.",
  SUPPORT: "Транзакции и статусы киосков. Без финансовой аналитики.",
};

export function listUsers(): Promise<AdminUser[]> {
  return api<AdminUser[]>("/admin/users");
}

export function createUser(body: {
  name: string;
  username: string;
  password: string;
  role: Role;
}): Promise<AdminUser> {
  return api<AdminUser>("/admin/users", { method: "POST", body });
}

export function changeRole(id: string, role: Role): Promise<AdminUser> {
  return api<AdminUser>(`/admin/users/${id}/role`, { method: "PATCH", body: { role } });
}

export function setUserEnabled(id: string, enabled: boolean): Promise<AdminUser> {
  return api<AdminUser>(`/admin/users/${id}/enabled?enabled=${enabled}`, { method: "PATCH" });
}

export function resetPassword(id: string, password: string): Promise<void> {
  return api<void>(`/admin/users/${id}/reset-password`, {
    method: "POST",
    body: { password },
  });
}

export function deleteUser(id: string): Promise<void> {
  return api<void>(`/admin/users/${id}`, { method: "DELETE" });
}

/** Сервер требует минимум 8 символов (AdminUserService.MIN_PASSWORD_LEN). */
export const MIN_PASSWORD_LEN = 8;

/** Пароль сид-владельца по умолчанию — на него ругаемся, пока не сменят. */
export const DEFAULT_OWNER_PASSWORD = "owner12345";

export function formatDate(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("ru-RU", {
    day: "2-digit", month: "2-digit", year: "numeric",
  });
}
