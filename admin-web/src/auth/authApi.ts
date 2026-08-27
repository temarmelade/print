import { api, setToken } from "../lib/apiClient.ts";
import type { User } from "../types.ts";

export interface LoginResult {
  token: string;
  user: User;
}

/**
 * Демо-вход без сервера. ВЫКЛЮЧЕН ПО УМОЛЧАНИЮ.
 *
 * <p>Раньше здесь стояло `?? "true"`, то есть сборка без явного флага
 * поднималась с тремя демо-аккаунтами и полными правами владельца.
 * Пароли лежат в публичном репозитории, так что для боевой админки это
 * означало вход для любого желающего.
 *
 * <p>Дополнительно заглушка вырезается в production-сборке: даже если
 * кто-то соберёт прод с VITE_USE_MOCK_AUTH=true, войти не выйдет.
 */
const MOCK_REQUESTED = import.meta.env.VITE_USE_MOCK_AUTH === "true";
const USE_MOCK = MOCK_REQUESTED && import.meta.env.DEV;

if (MOCK_REQUESTED && !import.meta.env.DEV) {
  console.error(
    "VITE_USE_MOCK_AUTH=true игнорируется в production-сборке. " +
    "Вход выполняется через сервер."
  );
}

const MOCK_ACCOUNTS: Record<string, { password: string; user: User }> = {
  owner:   { password: "owner123",   user: { id: "u-owner",   name: "Азамат (владелец)", username: "owner",   role: "OWNER" } },
  tech:    { password: "tech123",    user: { id: "u-tech",    name: "Тимур (техник)",    username: "tech",    role: "TECHNICIAN" } },
  support: { password: "support123", user: { id: "u-support", name: "Айгуль (поддержка)", username: "support", role: "SUPPORT" } },
};

async function mockLogin(username: string, password: string): Promise<LoginResult> {
  await new Promise((r) => setTimeout(r, 350));
  const acc = MOCK_ACCOUNTS[username.trim().toLowerCase()];
  if (!acc || acc.password !== password) {
    throw new Error("Неверный логин или пароль");
  }
  return { token: `mock.${acc.user.role}.${Date.now()}`, user: acc.user };
}

function mockMe(): User {
  const token = localStorage.getItem("pk_admin_token") ?? "";
  const role = token.split(".")[1];
  const acc = Object.values(MOCK_ACCOUNTS).find((a) => a.user.role === role);
  if (!acc) throw new Error("no session");
  return acc.user;
}

/* ─────────────────────────────────────────────────────────────
   Реальные вызовы. Контракт с бэкендом:
     POST /api/admin/auth/login  { username, password } -> { token, user }
     GET  /api/admin/auth/me                            -> user
   ───────────────────────────────────────────────────────────── */

export async function login(username: string, password: string): Promise<LoginResult> {
  const result = USE_MOCK
    ? await mockLogin(username, password)
    : await api<LoginResult>("/admin/auth/login", { method: "POST", body: { username, password } });
  setToken(result.token);
  return result;
}

export async function fetchMe(): Promise<User> {
  if (USE_MOCK) return mockMe();
  return api<User>("/admin/auth/me");
}

export function logout(): void {
  setToken(null);
}
