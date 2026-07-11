import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { LogOut, Radio } from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import { modulesForRole } from "../lib/permissions.ts";
import { ROLE_LABEL } from "../types.ts";
import "./layout.css";

export function AppLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  if (!user) return null;

  const modules = modulesForRole(user.role);

  function onLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  return (
    <div className="shell">
      {/* Приборный рейл */}
      <aside className="rail">
        <div className="rail-brand">
          <span className="rail-mark"><Radio size={18} strokeWidth={2.4} /></span>
          <span className="rail-brand-text">
            PrintKiosk
            <em>Пульт управления</em>
          </span>
        </div>

        <nav className="rail-nav" aria-label="Модули">
          {modules.map((m) => (
            <NavLink
              key={m.id}
              to={m.path}
              className={({ isActive }) => "rail-link" + (isActive ? " active" : "")}
            >
              <m.icon size={18} strokeWidth={2} />
              <span>{m.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="rail-foot mono">Смена: {ROLE_LABEL[user.role]}</div>
      </aside>

      {/* Правая часть */}
      <div className="main">
        <header className="topbar">
          <div className="topbar-net">
            <span className="status-dot ok" aria-hidden />
            <span className="mono">СЕТЬ · В ЭФИРЕ</span>
          </div>

          <div className="topbar-user">
            <div className="user-meta">
              <span className="user-name">{user.name}</span>
              <span className={`role-badge role-${user.role.toLowerCase()}`}>{ROLE_LABEL[user.role]}</span>
            </div>
            <button className="icon-btn" onClick={onLogout} title="Выйти" aria-label="Выйти">
              <LogOut size={18} />
            </button>
          </div>
        </header>

        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
