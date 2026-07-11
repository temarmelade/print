import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.tsx";
import { canAccess, landingPathFor } from "../lib/permissions.ts";

/** Пускает только авторизованных; иначе — на экран входа (с запоминанием куда шли). */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === "loading") return <FullScreenLoader />;
  if (status === "anon") {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}

/** Пускает в модуль только разрешённые роли; иначе — на «нет доступа». */
export function RequireModule({ moduleId, children }: { moduleId: string; children: ReactNode }) {
  const { user } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (!canAccess(user.role, moduleId)) {
    return <Navigate to="/forbidden" replace />;
  }
  return <>{children}</>;
}

/** Если уже вошёл — с экрана входа сразу на его стартовый модуль. */
export function RedirectIfAuthed({ children }: { children: ReactNode }) {
  const { status, user } = useAuth();
  if (status === "loading") return <FullScreenLoader />;
  if (status === "authed" && user) return <Navigate to={landingPathFor(user.role)} replace />;
  return <>{children}</>;
}

export function FullScreenLoader() {
  return (
    <div style={{
      height: "100vh", display: "grid", placeItems: "center",
      color: "var(--text-dim)", fontFamily: "var(--font-mono)", fontSize: 13,
      letterSpacing: "0.14em", textTransform: "uppercase",
    }}>
      Загрузка панели…
    </div>
  );
}
