import type { Role } from "../types.ts";
import {
  LayoutDashboard,
  MonitorSmartphone,
  BellRing,
  BarChart3,
  Receipt,
  Megaphone,
  Wallet,
  Users,
  type LucideIcon,
} from "lucide-react";

export interface ModuleDef {
  id: string;
  label: string;
  path: string;
  icon: LucideIcon;
  roles: Role[]; // кто видит модуль в меню и может открыть его роут
}

/**
 * Единый источник правды по доступам. Меню, роут-гарды и «403» читают отсюда.
 * Раскладка ролей — из ТЗ:
 *  • Владелец  — всё, включая финансы и аналитику.
 *  • Техник    — карта, статусы киосков, расходники, перезагрузка. Без выручки.
 *  • Поддержка — транзакции (для возвратов) и статус киосков.
 */
export const MODULES: ModuleDef[] = [
  { id: "dashboard",    label: "Дашборд",    path: "/dashboard",    icon: LayoutDashboard,   roles: ["OWNER", "TECHNICIAN", "SUPPORT"] },
  { id: "terminals",    label: "Терминалы",  path: "/terminals",    icon: MonitorSmartphone, roles: ["OWNER", "TECHNICIAN", "SUPPORT"] },
  { id: "alerts",       label: "Инциденты",  path: "/alerts",       icon: BellRing,          roles: ["OWNER", "TECHNICIAN"] },
  { id: "analytics",    label: "Аналитика",  path: "/analytics",    icon: BarChart3,         roles: ["OWNER"] },
  { id: "transactions", label: "Транзакции", path: "/transactions", icon: Receipt,           roles: ["OWNER", "SUPPORT"] },
  { id: "pricing",      label: "Цены",       path: "/pricing",      icon: Wallet,            roles: ["OWNER"] },
  { id: "media",        label: "Реклама",    path: "/media",        icon: Megaphone,         roles: ["OWNER"] },
  { id: "access",       label: "Доступы",    path: "/access",       icon: Users,             roles: ["OWNER"] },
];

export function modulesForRole(role: Role): ModuleDef[] {
  return MODULES.filter((m) => m.roles.includes(role));
}

export function canAccess(role: Role, moduleId: string): boolean {
  const m = MODULES.find((x) => x.id === moduleId);
  return !!m && m.roles.includes(role);
}

/** Первый доступный роль модуль — куда отправлять после входа. */
export function landingPathFor(role: Role): string {
  return modulesForRole(role)[0]?.path ?? "/dashboard";
}
