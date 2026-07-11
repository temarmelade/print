export type Role = "OWNER" | "TECHNICIAN" | "SUPPORT";

export interface User {
  id: string;
  name: string;
  username: string;
  role: Role;
}

export const ROLE_LABEL: Record<Role, string> = {
  OWNER: "Владелец",
  TECHNICIAN: "Техник",
  SUPPORT: "Поддержка",
};
