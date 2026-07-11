import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import type { User } from "../types.ts";
import { getToken, setToken } from "../lib/apiClient.ts";
import * as authApi from "./authApi.ts";

interface AuthState {
  user: User | null;
  status: "loading" | "authed" | "anon";
  login: (username: string, password: string) => Promise<User>;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<AuthState["status"]>("loading");

  // Восстанавливаем сессию по сохранённому токену при загрузке приложения.
  useEffect(() => {
    if (!getToken()) {
      setStatus("anon");
      return;
    }
    authApi
      .fetchMe()
      .then((me) => {
        setUser(me);
        setStatus("authed");
      })
      .catch(() => {
        setToken(null);
        setStatus("anon");
      });
  }, []);

  async function login(username: string, password: string) {
    const { user } = await authApi.login(username, password);
    setUser(user);
    setStatus("authed");
    return user;
  }

  function logout() {
    authApi.logout();
    setUser(null);
    setStatus("anon");
  }

  return (
    <AuthContext.Provider value={{ user, status, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within <AuthProvider>");
  return ctx;
}
