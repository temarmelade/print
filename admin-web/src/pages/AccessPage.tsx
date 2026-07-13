import { useCallback, useEffect, useState } from "react";
import {
  UserPlus, KeyRound, Trash2, Check, X, RefreshCw, ShieldAlert, Power,
} from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import { ROLE_LABEL, type Role } from "../types.ts";
import {
  listUsers, createUser, changeRole, setUserEnabled, resetPassword, deleteUser,
  ROLE_HINT, MIN_PASSWORD_LEN, formatDate,
  type AdminUser,
} from "../lib/usersApi.ts";
import "./pages.css";
import "./access.css";

const ROLES: Role[] = ["OWNER", "TECHNICIAN", "SUPPORT"];

export function AccessPage() {
  const { user: me } = useAuth();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setUsers(await listUsers());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить сотрудников");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const activeOwners = users.filter((u) => u.role === "OWNER" && u.enabled).length;

  return (
    <>
      <div className="page-head">
        <span className="phase-tag">Фаза 1</span>
        <h2>Доступы</h2>
        <p>Сотрудники панели и их права.</p>
      </div>

      {/* Дефолтный пароль владельца — реальная дыра, о ней надо кричать. */}
      <div className="warn-note">
        <ShieldAlert size={16} />
        <span>
          Если у владельца всё ещё стандартный пароль (<code>owner12345</code>) — смените
          его: кнопка <strong>«Пароль»</strong> в своей строке.
        </span>
      </div>

      <div className="list-head">
        <h3>Сотрудники</h3>
        <div className="head-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={15} className={loading ? "spin" : undefined} />
            Обновить
          </button>
          <button className="btn btn-primary btn-sm" onClick={() => setAdding((v) => !v)}>
            <UserPlus size={15} />
            Добавить
          </button>
        </div>
      </div>

      {adding && (
        <CreateForm
          onCancel={() => setAdding(false)}
          onCreated={(u) => { setUsers((p) => [...p, u]); setAdding(false); }}
        />
      )}

      {error && <div className="login-error" role="alert">{error}</div>}

      {loading && users.length === 0 ? (
        <div className="empty">Загружаем…</div>
      ) : (
        <div className="user-list">
          {users.map((u) => (
            <UserRow
              key={u.id}
              u={u}
              isMe={u.id === me?.id}
              isLastOwner={u.role === "OWNER" && u.enabled && activeOwners <= 1}
              onChanged={(next) => setUsers((p) => p.map((x) => (x.id === next.id ? next : x)))}
              onDeleted={() => setUsers((p) => p.filter((x) => x.id !== u.id))}
              onError={setError}
            />
          ))}
        </div>
      )}
    </>
  );
}

/* ───────────────────────── Создание ───────────────────────── */

function CreateForm({
  onCancel, onCreated,
}: {
  onCancel: () => void;
  onCreated: (u: AdminUser) => void;
}) {
  const [name, setName] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<Role>("TECHNICIAN");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const valid = name.trim() && username.trim() && password.length >= MIN_PASSWORD_LEN;

  async function submit() {
    setBusy(true);
    setErr(null);
    try {
      onCreated(await createUser({
        name: name.trim(), username: username.trim(), password, role,
      }));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Не удалось создать сотрудника");
      setBusy(false);
    }
  }

  return (
    <div className="card create-card">
      <div className="create-grid">
        <div className="field">
          <label htmlFor="c-name">Имя</label>
          <input id="c-name" className="input" value={name}
                 onChange={(e) => setName(e.target.value)} placeholder="Тимур" />
        </div>
        <div className="field">
          <label htmlFor="c-user">Логин</label>
          <input id="c-user" className="input" value={username} autoComplete="off"
                 onChange={(e) => setUsername(e.target.value)} placeholder="timur" />
        </div>
        <div className="field">
          <label htmlFor="c-pass">Пароль</label>
          <input id="c-pass" className="input" type="password" value={password} autoComplete="new-password"
                 onChange={(e) => setPassword(e.target.value)}
                 placeholder={`от ${MIN_PASSWORD_LEN} символов`} />
        </div>
        <div className="field">
          <label htmlFor="c-role">Роль</label>
          <select id="c-role" className="input select" value={role}
                  onChange={(e) => setRole(e.target.value as Role)}>
            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
          </select>
        </div>
      </div>

      <p className="role-hint">{ROLE_HINT[role]}</p>

      {err && <div className="login-error" role="alert">{err}</div>}

      <div className="create-actions">
        <button className="btn btn-primary" onClick={() => void submit()} disabled={!valid || busy}>
          {busy ? "Создаём…" : "Создать сотрудника"}
        </button>
        <button className="btn btn-ghost" onClick={onCancel} disabled={busy}>Отмена</button>
      </div>
    </div>
  );
}

/* ───────────────────────── Строка ───────────────────────── */

function UserRow({
  u, isMe, isLastOwner, onChanged, onDeleted, onError,
}: {
  u: AdminUser;
  isMe: boolean;
  isLastOwner: boolean;
  onChanged: (u: AdminUser) => void;
  onDeleted: () => void;
  onError: (m: string) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [pwMode, setPwMode] = useState(false);
  const [pw, setPw] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [done, setDone] = useState(false);

  // Сервер тоже это защищает — тут просто не даём нажать заведомо провальное.
  const protectedOwner = isLastOwner;

  async function guard<T>(fn: () => Promise<T>) {
    setBusy(true);
    try { return await fn(); }
    catch (e) { onError(e instanceof Error ? e.message : "Операция не удалась"); }
    finally { setBusy(false); }
  }

  return (
    <div className={"card user-row" + (u.enabled ? "" : " off")}>
      <div className="user-main">
        <div className="user-id">
          <span className="u-name">
            {u.name}
            {isMe && <span className="u-me">это вы</span>}
          </span>
          <span className="u-login mono">@{u.username}</span>
        </div>

        <div className="user-role">
          <select
            className="input select role-select"
            value={u.role}
            disabled={busy || protectedOwner}
            title={protectedOwner ? "Нельзя понизить последнего владельца" : undefined}
            onChange={(e) =>
              void guard(async () => {
                const next = await changeRole(u.id, e.target.value as Role);
                onChanged(next);
              })
            }
          >
            {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
          </select>
        </div>

        <span className="u-created mono">{formatDate(u.createdAt)}</span>
      </div>

      {pwMode ? (
        <div className="pw-row">
          <input
            className="input" type="password" autoComplete="new-password"
            placeholder={`Новый пароль (от ${MIN_PASSWORD_LEN})`}
            value={pw} onChange={(e) => setPw(e.target.value)}
          />
          <button
            className="icon-btn" title="Сохранить"
            disabled={busy || pw.length < MIN_PASSWORD_LEN}
            onClick={() =>
              void guard(async () => {
                await resetPassword(u.id, pw);
                setPw(""); setPwMode(false);
                setDone(true);
                setTimeout(() => setDone(false), 2500);
              })
            }
          >
            <Check size={16} />
          </button>
          <button className="icon-btn" title="Отмена"
                  onClick={() => { setPwMode(false); setPw(""); }} disabled={busy}>
            <X size={16} />
          </button>
        </div>
      ) : (
        <div className="user-actions">
          {done && <span className="saved mono">Пароль обновлён</span>}

          <button className="btn btn-ghost btn-sm" onClick={() => setPwMode(true)} disabled={busy}>
            <KeyRound size={15} />
            Пароль
          </button>

          <button
            className="btn btn-ghost btn-sm"
            disabled={busy || protectedOwner}
            title={protectedOwner ? "Нельзя выключить последнего владельца" : undefined}
            onClick={() =>
              void guard(async () => onChanged(await setUserEnabled(u.id, !u.enabled)))
            }
          >
            <Power size={15} />
            {u.enabled ? "Выключить" : "Включить"}
          </button>

          {confirming ? (
            <span className="confirm">
              <button
                className="btn btn-danger btn-sm" disabled={busy}
                onClick={() => void guard(async () => { await deleteUser(u.id); onDeleted(); })}
              >
                Удалить
              </button>
              <button className="btn btn-ghost btn-sm" onClick={() => setConfirming(false)} disabled={busy}>
                Нет
              </button>
            </span>
          ) : (
            <button
              className="icon-btn danger"
              disabled={busy || protectedOwner}
              title={protectedOwner ? "Нельзя удалить последнего владельца" : "Удалить"}
              onClick={() => setConfirming(true)}
            >
              <Trash2 size={15} />
            </button>
          )}
        </div>
      )}
    </div>
  );
}
