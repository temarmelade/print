import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { LogIn } from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import { landingPathFor } from "../lib/permissions.ts";
import "./login.css";

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const me = await login(username, password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from ?? landingPathFor(me.role), { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Не удалось войти");
      setBusy(false);
    }
  }

  return (
    <div className="login">
      {/* Левая панель-подпись: сеть киосков под наблюдением */}
      <aside className="login-brand" aria-hidden>
        <NetworkGraphic />
        <div className="login-brand-copy">
          <div className="eyebrow">PrintKiosk · Control</div>
          <h1>Вся сеть киосков —<br />на одном пульте</h1>
          <p>Статусы, расходники, выручка и инциденты в реальном времени.</p>
        </div>
      </aside>

      {/* Форма входа */}
      <main className="login-form-wrap">
        <form className="login-form" onSubmit={onSubmit}>
          <div className="eyebrow">Доступ к панели</div>
          <h2>Вход в пульт управления</h2>

          <div className="field">
            <label htmlFor="username">Логин</label>
            <input
              id="username" className="input" autoFocus autoComplete="username"
              value={username} onChange={(e) => setUsername(e.target.value)}
              placeholder="например, owner"
            />
          </div>

          <div className="field">
            <label htmlFor="password">Пароль</label>
            <input
              id="password" className="input" type="password" autoComplete="current-password"
              value={password} onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
            />
          </div>

          {error && <div className="login-error" role="alert">{error}</div>}

          <button className="btn btn-primary" type="submit" disabled={busy || !username || !password}>
            <LogIn size={18} />
            {busy ? "Проверяем…" : "Войти в панель"}
          </button>

          <p className="login-hint mono">
            Демо: owner / owner123 · tech / tech123 · support / support123
          </p>
        </form>
      </main>
    </div>
  );
}

/** Подпись экрана: узлы сети с сигнальными статусами (green/amber/red). */
function NetworkGraphic() {
  const nodes = [
    { x: 70, y: 90, s: "ok" },
    { x: 190, y: 60, s: "ok" },
    { x: 300, y: 130, s: "warn" },
    { x: 150, y: 200, s: "ok" },
    { x: 260, y: 240, s: "down" },
    { x: 90, y: 300, s: "ok" },
    { x: 320, y: 320, s: "ok" },
  ] as const;
  const links = [[0, 1], [1, 2], [1, 3], [3, 4], [3, 5], [4, 6], [2, 4]];
  const color = (s: string) => (s === "ok" ? "var(--ok)" : s === "warn" ? "var(--warn)" : "var(--down)");

  return (
    <svg className="net" viewBox="0 0 400 380" role="img" aria-label="Схема сети киосков">
      {links.map(([a, b], i) => (
        <line key={i} x1={nodes[a].x} y1={nodes[a].y} x2={nodes[b].x} y2={nodes[b].y}
          stroke="rgba(134,149,174,0.22)" strokeWidth="1.2" />
      ))}
      {nodes.map((n, i) => (
        <g key={i} style={{ ["--d" as string]: `${i * 0.4}s` }} className="net-node">
          <circle cx={n.x} cy={n.y} r="10" fill={color(n.s)} opacity="0.16" className="net-halo" />
          <circle cx={n.x} cy={n.y} r="4.5" fill={color(n.s)} />
        </g>
      ))}
    </svg>
  );
}
