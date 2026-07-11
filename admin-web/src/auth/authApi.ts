import { api, setToken } from "../lib/apiClient.ts";
import type { Role, User } from "../types.ts";

export interface LoginResult {
  token: string;
  user: User;
}

const USE_MOCK = (import.meta.env.VITE_USE_MOCK_AUTH ?? "true") === "true";

/* ─────────────────────────────────────────────────────────────
   Заглушка на время, пока не поднят серверный вход. Даёт три
   демо-аккаунта, чтобы прокликать все роли уже сейчас. Убирается
   переключателем VITE_USE_MOCK_AUTH=false — код ниже не трогаем.
   ───────────────────────────────────────────────────────────── */
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
  const role = token.split(".")[1] as Role | undefined;
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
