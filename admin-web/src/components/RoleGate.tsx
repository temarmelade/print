import type { ReactNode } from "react";
import { useAuth } from "../auth/AuthContext.tsx";
import type { Role } from "../types.ts";

/**
 * Показывает содержимое только перечисленным ролям. Для гейтинга блоков
 * внутри страницы — например, KPI выручки видит только владелец.
 */
export function RoleGate({ allow, children }: { allow: Role[]; children: ReactNode }) {
  const { user } = useAuth();
  if (!user || !allow.includes(user.role)) return null;
  return <>{children}</>;
}
