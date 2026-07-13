const BASE = import.meta.env.VITE_API_BASE_URL ?? "/api";
const TOKEN_KEY = "pk_admin_token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}
export function setToken(token: string | null): void {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

type Options = Omit<RequestInit, "body"> & { body?: unknown };

/**
 * Тонкая обёртка над fetch: подставляет Bearer-токен, парсит JSON и на 401
 * чистит сессию и уводит на экран входа. Токен в localStorage — простой
 * вариант для старта; для прода стоит перевести на httpOnly-cookie (см. README).
 */
export async function api<T>(path: string, options: Options = {}): Promise<T> {
  const { body, headers, ...rest } = options;
  const token = getToken();

  const res = await fetch(`${BASE}${path}`, {
    ...rest,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (res.status === 401) {
    setToken(null);
    if (!location.pathname.startsWith("/login")) {
      location.assign("/login");
    }
    throw new ApiError(401, "Сессия истекла. Войдите снова.");
  }

  if (!res.ok) {
    let message = `Ошибка ${res.status}`;
    try {
      const data = await res.json();
      if (data?.message) message = data.message;
    } catch { /* тело не JSON — оставляем дефолт */ }
    throw new ApiError(res.status, message);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

/**
 * Загрузка файла (multipart). Content-Type НЕ выставляем вручную — браузер сам
 * проставит его вместе с boundary, иначе сервер не разберёт тело.
 */
export async function apiUpload<T>(path: string, form: FormData): Promise<T> {
  const token = getToken();

  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: {
      Accept: "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: form,
  });

  if (res.status === 401) {
    setToken(null);
    if (!location.pathname.startsWith("/login")) location.assign("/login");
    throw new ApiError(401, "Сессия истекла. Войдите снова.");
  }

  if (!res.ok) {
    let message = `Ошибка ${res.status}`;
    try {
      const data = await res.json();
      if (data?.message) message = data.message;
    } catch { /* тело не JSON */ }
    throw new ApiError(res.status, message);
  }

  return (await res.json()) as T;
}
