import { Link } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.tsx";
import { landingPathFor } from "../lib/permissions.ts";
import "./pages.css";

export function ForbiddenPage() {
  const { user } = useAuth();
  const home = user ? landingPathFor(user.role) : "/login";
  return (
    <div className="empty" style={{ marginTop: 40 }}>
      <span className="phase-tag">403</span>
      <h2 style={{ margin: "10px 0 6px", fontSize: 22 }}>Нет доступа к этому модулю</h2>
      <p style={{ color: "var(--text-muted)", margin: "0 0 16px" }}>
        Ваша роль не открывает этот раздел. Если нужен доступ — попросите владельца.
      </p>
      <Link className="btn btn-ghost" to={home} style={{ display: "inline-flex" }}>
        На главную
      </Link>
    </div>
  );
}

export function NotFoundPage() {
  return (
    <div className="empty" style={{ marginTop: 40 }}>
      <span className="phase-tag">404</span>
      <h2 style={{ margin: "10px 0 6px", fontSize: 22 }}>Страница не найдена</h2>
      <p style={{ color: "var(--text-muted)", margin: "0 0 16px" }}>
        Такого раздела нет. Вернитесь в меню слева.
      </p>
      <Link className="btn btn-ghost" to="/" style={{ display: "inline-flex" }}>
        На главную
      </Link>
    </div>
  );
}
