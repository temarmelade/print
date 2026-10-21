import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { LogOut, Menu, X } from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import { modulesForRole } from "../lib/permissions.ts";
import { ROLE_LABEL } from "../types.ts";
import { BrandMark } from "./Brand.tsx";
import "./layout.css";

export function AppLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  // Мобильное меню: открыто/закрыто. На десктопе не используется — там
  // боковая панель видна всегда.
  const [menuOpen, setMenuOpen] = useState(false);

  // Закрываем меню при переходе на другую страницу.
  useEffect(() => { setMenuOpen(false); }, [location.pathname]);

  // Пока меню открыто: Esc закрывает, страница под ним не прокручивается.
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setMenuOpen(false); };
    document.addEventListener("keydown", onKey);
    document.body.classList.add("no-scroll");
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.classList.remove("no-scroll");
    };
  }, [menuOpen]);

  if (!user) return null;

  const modules = modulesForRole(user.role);

  function onLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  return (
    <div className={"shell" + (menuOpen ? " menu-open" : "")}>
      {/* Шапка */}
      <header className="appbar">
        <button
          className="appbar-burger"
          onClick={() => setMenuOpen((v) => !v)}
          aria-label={menuOpen ? "Закрыть меню" : "Открыть меню"}
          aria-expanded={menuOpen}
          aria-controls="app-nav"
        >
          {menuOpen ? <X size={22} /> : <Menu size={22} />}
        </button>

        <NavLink to="/" className="appbar-brand" aria-label="BasmaPrint — на главную">
          <BrandMark variant="white" size={34} />
          <span className="brand-word">
            <b>BASMA</b>
            <em>PRINT</em>
          </span>
          <span className="appbar-sub">Пульт управления</span>
        </NavLink>

        <div className="appbar-right">
          <div className="appbar-net" title="Пульт подключён к серверу">
            <span className="status-dot ok" aria-hidden />
            <span>Сеть в эфире</span>
          </div>

          <div className="appbar-user">
            <span className="user-name">{user.name}</span>
            <span className={`role-badge role-${user.role.toLowerCase()}`}>{ROLE_LABEL[user.role]}</span>
          </div>

          <button className="appbar-logout" onClick={onLogout} title="Выйти" aria-label="Выйти">
            <LogOut size={18} />
          </button>
        </div>
      </header>

      <div className="frame">
        {/* Затемнение под мобильным меню */}
        <div className="rail-backdrop" onClick={() => setMenuOpen(false)} aria-hidden />

        {/* Боковое меню */}
        <aside className="rail" id="app-nav">
          <div className="rail-user">
            <span className="user-name">{user.name}</span>
            <span className={`role-badge role-${user.role.toLowerCase()}`}>{ROLE_LABEL[user.role]}</span>
          </div>

          <nav className="rail-nav" aria-label="Модули">
            {modules.map((m) => (
              <NavLink
                key={m.id}
                to={m.path}
                className={({ isActive }) => "rail-link" + (isActive ? " active" : "")}
              >
                <m.icon size={19} strokeWidth={2} />
                <span>{m.label}</span>
              </NavLink>
            ))}
          </nav>

          <button className="rail-logout" onClick={onLogout}>
            <LogOut size={18} />
            Выйти из пульта
          </button>
        </aside>

        <main className="content">
          <Outlet />
        </main>
      </div>

      {/* Футер */}
      <footer className="appfoot">
        <span className="appfoot-brand">
          <BrandMark variant="white" size={20} />
          BasmaPrint
        </span>
        <span>© {new Date().getFullYear()} · Сеть киосков печати и сканирования</span>
      </footer>
    </div>
  );
}
