import { useCallback, useEffect, useState } from "react";
import {
  RefreshCw, Plus, Wrench, Droplet, FileStack, KeyRound, Trash2, Copy, Check, Info,
} from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import {
  listKiosks, createKiosk, rotateKey, markPaperRefilled, markCartridgeChanged,
  setMaintenance, deleteKiosk, lastSeen,
  HEALTH_TONE, HEALTH_LABEL, SOURCE_HINT,
  type Kiosk, type CreatedKiosk, type SupplySource,
} from "../lib/kiosksApi.ts";
import "./pages.css";
import "./terminals.css";

export function TerminalsPage() {
  const { user } = useAuth();
  const isOwner = user?.role === "OWNER";

  const [kiosks, setKiosks] = useState<Kiosk[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [issuedKey, setIssuedKey] = useState<CreatedKiosk | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setKiosks(await listKiosks());
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить киоски");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  // Живой пульт: обновляем сами, чтобы техник не жал F5.
  useEffect(() => {
    const t = setInterval(() => { void load(); }, 30_000);
    return () => clearInterval(t);
  }, [load]);

  return (
    <>
      <div className="page-head">
        <span className="phase-tag">Фаза 2</span>
        <h2>Терминалы</h2>
        <p>Состояние киосков и расходников. Обновляется каждые 30 секунд.</p>
      </div>

      <div className="list-head">
        <h3>Киоски{kiosks.length > 0 && <span className="count mono">{kiosks.length}</span>}</h3>
        <div className="head-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={15} className={loading ? "spin" : undefined} />
            Обновить
          </button>
          {isOwner && (
            <button className="btn btn-primary btn-sm" onClick={() => setAdding((v) => !v)}>
              <Plus size={15} />
              Добавить киоск
            </button>
          )}
        </div>
      </div>

      {adding && (
        <CreateForm
          onCancel={() => setAdding(false)}
          onCreated={(k) => { setIssuedKey(k); setAdding(false); void load(); }}
        />
      )}

      {issuedKey && <KeyBanner data={issuedKey} onClose={() => setIssuedKey(null)} />}

      {error && <div className="login-error" role="alert">{error}</div>}

      {loading && kiosks.length === 0 ? (
        <div className="empty">Загружаем…</div>
      ) : kiosks.length === 0 ? (
        <div className="empty">
          Киосков пока нет. Добавьте первый — панель выдаст ему API-ключ, который нужно
          прописать в конфиг терминала (<code>KIOSK_API_KEY</code>). После этого киоск
          начнёт слать телеметрию.
        </div>
      ) : (
        <div className="kiosk-grid">
          {kiosks.map((k) => (
            <KioskCard
              key={k.id} k={k} isOwner={isOwner}
              onRefresh={load} onError={setError}
              onKeyIssued={setIssuedKey}
            />
          ))}
        </div>
      )}

      <div className="cache-note" style={{ marginTop: 22 }}>
        <Info size={15} />
        <span>
          Canon MF232w: одна кассета на 250 листов и единый картридж 737 (~2400 стр.).
          Уровень бумаги у этой модели, скорее всего, не измеряется датчиком — тогда он
          считается по счётчику страниц с момента заправки и помечается как «оценка».
        </span>
      </div>
    </>
  );
}

/* ── Карточка киоска ── */

function KioskCard({
  k, isOwner, onRefresh, onError, onKeyIssued,
}: {
  k: Kiosk;
  isOwner: boolean;
  onRefresh: () => Promise<void>;
  onError: (m: string) => void;
  onKeyIssued: (c: CreatedKiosk) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const tone = HEALTH_TONE[k.health];

  async function guard(fn: () => Promise<unknown>) {
    setBusy(true);
    try { await fn(); await onRefresh(); }
    catch (e) { onError(e instanceof Error ? e.message : "Операция не удалась"); }
    finally { setBusy(false); }
  }

  return (
    <div className={`card kiosk-card tone-${tone}`}>
      <div className="kiosk-head">
        <div className="kiosk-id">
          <span className="kiosk-name">
            <span className={`status-dot ${tone === "idle" ? "" : tone}`} />
            {k.name}
          </span>
          <span className="kiosk-loc mono">{k.location ?? k.id}</span>
        </div>
        <span className={`health-badge tone-${tone}`}>{HEALTH_LABEL[k.health]}</span>
      </div>

      <div className={`kiosk-reason tone-${tone}`}>{k.healthReason}</div>

      <div className="supplies">
        <Supply
          icon={<FileStack size={14} />}
          label="Бумага"
          percent={k.paperPercent}
          source={k.paperSource}
          extra={k.paperSheetsLeft != null ? `~${k.paperSheetsLeft} л.` : null}
        />
        <Supply
          icon={<Droplet size={14} />}
          label="Тонер"
          percent={k.tonerPercent}
          source={k.tonerSource}
          extra={null}
        />
      </div>

      <div className="kiosk-meta mono">
        <span>Связь: {lastSeen(k.lastSeenAt)}</span>
        {k.pageCounter != null && <span>Счётчик: {k.pageCounter}</span>}
      </div>

      <div className="kiosk-actions">
        <button className="btn btn-ghost btn-sm" disabled={busy}
                onClick={() => void guard(() => markPaperRefilled(k.id))}>
          <FileStack size={14} /> Заправил бумагу
        </button>
        <button className="btn btn-ghost btn-sm" disabled={busy}
                onClick={() => void guard(() => markCartridgeChanged(k.id))}>
          <Droplet size={14} /> Заменил картридж
        </button>
        <button
          className={"btn btn-sm " + (k.maintenanceMode ? "btn-primary" : "btn-ghost")}
          disabled={busy}
          onClick={() => void guard(() => setMaintenance(k.id, !k.maintenanceMode))}
        >
          <Wrench size={14} />
          {k.maintenanceMode ? "Снять обслуживание" : "На обслуживание"}
        </button>

        {isOwner && (
          <>
            <button className="btn btn-ghost btn-sm" disabled={busy}
                    onClick={() => void guard(async () => onKeyIssued(await rotateKey(k.id)))}>
              <KeyRound size={14} /> Новый ключ
            </button>
            {confirming ? (
              <span className="confirm">
                <button className="btn btn-danger btn-sm" disabled={busy}
                        onClick={() => void guard(() => deleteKiosk(k.id))}>
                  Удалить
                </button>
                <button className="btn btn-ghost btn-sm" onClick={() => setConfirming(false)}>
                  Нет
                </button>
              </span>
            ) : (
              <button className="icon-btn danger" disabled={busy}
                      onClick={() => setConfirming(true)} title="Удалить киоск">
                <Trash2 size={14} />
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

/* ── Индикатор расходника: честен про «неизвестно» ── */

function Supply({
  icon, label, percent, source, extra,
}: {
  icon: React.ReactNode;
  label: string;
  percent: number | null;
  source: SupplySource;
  extra: string | null;
}) {
  const unknown = percent === null;
  const tone = unknown ? "idle" : percent <= 15 ? "down" : percent <= 30 ? "warn" : "ok";

  return (
    <div className="supply">
      <div className="supply-head">
        <span className="supply-label">{icon}{label}</span>
        <span className={"supply-val mono tone-" + tone}>
          {unknown ? "н/д" : `${percent}%`}
          {extra && !unknown && <em>{extra}</em>}
        </span>
      </div>

      <div className="bar" title={SOURCE_HINT[source]}>
        {unknown ? (
          <div className="bar-unknown" />
        ) : (
          <div className={"bar-fill tone-" + tone} style={{ width: `${percent}%` }} />
        )}
      </div>

      <div className="supply-source">
        {source === "ESTIMATE" && <span className="est">оценка</span>}
        {source === "UNKNOWN" && <span className="unk">принтер не сообщает</span>}
        {source === "SENSOR" && <span className="sen">датчик</span>}
      </div>
    </div>
  );
}

/* ── Ключ показывается один раз ── */

function KeyBanner({ data, onClose }: { data: CreatedKiosk; onClose: () => void }) {
  const [copied, setCopied] = useState(false);

  return (
    <div className="key-banner">
      <div className="key-head">
        <strong>Ключ киоска «{data.name}» выдан</strong>
        <button className="icon-btn" onClick={onClose} title="Скрыть"><Check size={15} /></button>
      </div>
      <p>
        Сохраните его сейчас — <b>повторно он не покажется</b> (в базе только хеш).
        Пропишите в конфиг терминала: <code>KIOSK_ID={data.id}</code> и{" "}
        <code>KIOSK_API_KEY=…</code>
      </p>
      <div className="key-row">
        <code className="key-value mono">{data.apiKey}</code>
        <button
          className="btn btn-ghost btn-sm"
          onClick={() => {
            void navigator.clipboard.writeText(data.apiKey);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
          }}
        >
          {copied ? <Check size={15} /> : <Copy size={15} />}
          {copied ? "Скопировано" : "Копировать"}
        </button>
      </div>
    </div>
  );
}

/* ── Регистрация киоска ── */

function CreateForm({
  onCancel, onCreated,
}: {
  onCancel: () => void;
  onCreated: (k: CreatedKiosk) => void;
}) {
  const [id, setId] = useState("");
  const [name, setName] = useState("");
  const [location, setLocation] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setErr(null);
    try {
      onCreated(await createKiosk({
        id: id.trim(), name: name.trim(),
        location: location.trim() || undefined,
      }));
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Не удалось создать киоск");
      setBusy(false);
    }
  }

  return (
    <div className="card create-card">
      <div className="create-grid">
        <div className="field">
          <label htmlFor="k-id">ID киоска</label>
          <input id="k-id" className="input" value={id} onChange={(e) => setId(e.target.value)}
                 placeholder="kgtu-1" />
        </div>
        <div className="field">
          <label htmlFor="k-name">Название</label>
          <input id="k-name" className="input" value={name} onChange={(e) => setName(e.target.value)}
                 placeholder="Киоск №1 (КГТУ)" />
        </div>
        <div className="field">
          <label htmlFor="k-loc">Локация</label>
          <input id="k-loc" className="input" value={location}
                 onChange={(e) => setLocation(e.target.value)}
                 placeholder="КГТУ, 1 корпус, холл" />
        </div>
      </div>

      <p className="role-hint">
        ID должен совпадать с <code>KIOSK_ID</code> в конфиге терминала. Ёмкость кассеты
        (250 л.) и ресурс картриджа (2400 стр.) подставятся под Canon MF232w.
      </p>

      {err && <div className="login-error" role="alert">{err}</div>}

      <div className="create-actions">
        <button className="btn btn-primary" onClick={() => void submit()}
                disabled={busy || !id.trim() || !name.trim()}>
          {busy ? "Создаём…" : "Зарегистрировать"}
        </button>
        <button className="btn btn-ghost" onClick={onCancel} disabled={busy}>Отмена</button>
      </div>
    </div>
  );
}
