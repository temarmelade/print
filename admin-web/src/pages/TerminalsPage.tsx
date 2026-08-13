import { useCallback, useEffect, useState, lazy, Suspense } from "react";
import {
  RefreshCw, Plus, Wrench, Droplet, FileStack, KeyRound, Trash2, Copy, Check, Info,
  Map as MapIcon, List, Pencil, TrendingDown,
  RotateCw, Power, History as HistoryIcon,
} from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import {
  listKiosks, createKiosk, updateKiosk, rotateKey, markPaperRefilled, markCartridgeChanged,
  setMaintenance, deleteKiosk, lastSeen, fetchForecast, formatDaysLeft,
  HEALTH_TONE, HEALTH_LABEL, SOURCE_HINT,
  type Kiosk, type CreatedKiosk, type SupplySource, type SupplyForecast,
  sendCommand, cancelCommand, commandHistory,
  COMMAND_LABEL, COMMAND_STATUS_LABEL,
  type KioskCommand,
} from "../lib/kiosksApi.ts";
import "./pages.css";
import "./terminals.css";

/**
 * Карта тянет за собой Leaflet (~150 КБ) — грузим её только когда открыли,
 * иначе каждый заход в панель платил бы за карту, которой чаще не пользуются.
 */
const KioskMap = lazy(() =>
  import("./KioskMap.tsx").then((m) => ({ default: m.KioskMap })));

export function TerminalsPage() {
  const { user } = useAuth();
  const isOwner = user?.role === "OWNER";
  // Перезагрузка — обычная работа выездного инженера, поэтому доступна и
  // технику. Поддержке она не нужна: у неё нет физического доступа к точке.
  const canReboot = user?.role === "OWNER" || user?.role === "TECHNICIAN";

  const [kiosks, setKiosks] = useState<Kiosk[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [issuedKey, setIssuedKey] = useState<CreatedKiosk | null>(null);
  const [showMap, setShowMap] = useState(false);
  const [editing, setEditing] = useState<Kiosk | null>(null);

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
          <button className="btn btn-ghost btn-sm" onClick={() => setShowMap((v) => !v)}>
            {showMap ? <List size={15} /> : <MapIcon size={15} />}
            {showMap ? "Скрыть карту" : "Карта сети"}
          </button>
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

      {showMap && kiosks.length > 0 && (
        <Suspense fallback={<div className="empty">Загружаем карту…</div>}>
          <KioskMap kiosks={kiosks} />
        </Suspense>
      )}

      {adding && (
        <CreateForm
          onCancel={() => setAdding(false)}
          onCreated={(k) => { setIssuedKey(k); setAdding(false); void load(); }}
        />
      )}

      {editing && (
        <EditForm
          kiosk={editing}
          onCancel={() => setEditing(null)}
          onSaved={() => { setEditing(null); void load(); }}
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
              key={k.id} k={k} isOwner={isOwner} canReboot={canReboot}
              onRefresh={load} onError={setError}
              onKeyIssued={setIssuedKey}
              onEdit={() => setEditing(k)}
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
  k, isOwner, canReboot, onRefresh, onError, onKeyIssued, onEdit,
}: {
  k: Kiosk;
  isOwner: boolean;
  canReboot: boolean;
  onRefresh: () => Promise<void>;
  onError: (m: string) => void;
  onKeyIssued: (c: CreatedKiosk) => void;
  onEdit: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [forecast, setForecast] = useState<SupplyForecast | null>(null);
  /** Какую перезагрузку подтверждаем: null — ничего не спрашиваем. */
  const [rebootAsk, setRebootAsk] = useState<"RESTART_APP" | "REBOOT_OS" | null>(null);
  const [commands, setCommands] = useState<KioskCommand[] | null>(null);
  const pending = commands?.find((c) => c.status === "PENDING") ?? null;
  const tone = HEALTH_TONE[k.health];

  // Прогноз грузим отдельно от списка: он тяжелее (читает историю) и не
  // должен задерживать отрисовку карточек.
  useEffect(() => {
    let cancelled = false;
    fetchForecast(k.id)
      .then((f) => { if (!cancelled) setForecast(f); })
      .catch(() => { /* прогноз необязателен — молча пропускаем */ });
    return () => { cancelled = true; };
  }, [k.id]);

  async function guard(fn: () => Promise<unknown>) {
    setBusy(true);
    try { await fn(); await onRefresh(); }
    catch (e) { onError(e instanceof Error ? e.message : "Операция не удалась"); }
    finally { setBusy(false); }
  }

  async function loadCommands() {
    try {
      setCommands(await commandHistory(k.id));
    } catch {
      setCommands([]);
    }
  }

  async function fireCommand(type: "RESTART_APP" | "REBOOT_OS") {
    setBusy(true);
    try {
      await sendCommand(k.id, type);
      setRebootAsk(null);
      await loadCommands();
    } catch (e) {
      onError(e instanceof Error ? e.message : "Не удалось отправить команду");
    } finally {
      setBusy(false);
    }
  }

  async function dropPending() {
    if (!pending) return;
    setBusy(true);
    try {
      await cancelCommand(pending.id);
      await loadCommands();
    } catch (e) {
      onError(e instanceof Error ? e.message : "Не удалось отменить команду");
    } finally {
      setBusy(false);
    }
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

      {forecast?.pagesPerDay != null && (
        <div className="forecast">
          <span className="forecast-head">
            <TrendingDown size={13} /> Прогноз · {forecast.pagesPerDay} стр/сут
          </span>
          <span className="forecast-row">
            бумага: <b>{formatDaysLeft(forecast.paperDaysLeft)}</b>
            {" · "}
            тонер: <b>{formatDaysLeft(forecast.tonerDaysLeft)}</b>
          </span>
        </div>
      )}

      {/* Выбор типа перезагрузки. Мягкая идёт первой и оформлена основной
          кнопкой: она чинит большинство зависаний и стоит секунды, тогда
          как полный ребут занимает минуты и рискует не подняться. */}
      {rebootAsk && (
        <div className="reboot-panel">
          <p className="reboot-q">Что перезагружаем?</p>
          <div className="reboot-opts">
            <button className="btn btn-primary btn-sm" disabled={busy}
                    onClick={() => void fireCommand("RESTART_APP")}>
              <RotateCw size={14} /> Только приложение
            </button>
            <button className="btn btn-danger btn-sm" disabled={busy}
                    onClick={() => void fireCommand("REBOOT_OS")}>
              <Power size={14} /> Windows целиком
            </button>
            <button className="btn btn-ghost btn-sm" disabled={busy}
                    onClick={() => setRebootAsk(null)}>
              Отмена
            </button>
          </div>
          <p className="reboot-note">
            Команда уйдёт на киоск в течение 30 секунд. Если он занят клиентом,
            перезагрузка будет отклонена — деньги уже приняты, документ ещё нет.
          </p>
        </div>
      )}

      {/* Что происходит с последней командой. */}
      {commands && commands.length > 0 && (
        <div className="cmd-status">
          <HistoryIcon size={13} />
          <span>
            {COMMAND_LABEL[commands[0].type]} — {COMMAND_STATUS_LABEL[commands[0].status]}
            {commands[0].createdBy ? `, ${commands[0].createdBy}` : ""}
          </span>
          {pending && (
            <button className="btn btn-ghost btn-sm" disabled={busy}
                    onClick={() => void dropPending()}>
              Отменить
            </button>
          )}
        </div>
      )}

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

        {canReboot && (
          <button
            className="btn btn-ghost btn-sm"
            disabled={busy || !!pending}
            title={k.online ? "Перезагрузить киоск" : "Киоск офлайн — команда протухнет через 10 минут"}
            onClick={() => void loadCommands().then(() => setRebootAsk("RESTART_APP"))}
          >
            <RotateCw size={14} /> Перезагрузить
          </button>
        )}

        {isOwner && (
          <>
            <button className="btn btn-ghost btn-sm" disabled={busy} onClick={onEdit}>
              <Pencil size={14} /> Изменить
            </button>
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
  const [lat, setLat] = useState("");
  const [lng, setLng] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setErr(null);
    try {
      onCreated(await createKiosk({
        id: id.trim(), name: name.trim(),
        location: location.trim() || undefined,
        latitude: parseCoord(lat),
        longitude: parseCoord(lng),
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
        <div className="field">
          <label htmlFor="k-lat">Широта</label>
          <input id="k-lat" className="input" value={lat} onChange={(e) => setLat(e.target.value)}
                 placeholder="42.8746" inputMode="decimal" />
        </div>
        <div className="field">
          <label htmlFor="k-lng">Долгота</label>
          <input id="k-lng" className="input" value={lng} onChange={(e) => setLng(e.target.value)}
                 placeholder="74.5698" inputMode="decimal" />
        </div>
      </div>

      <p className="role-hint">
        ID должен совпадать с <code>KIOSK_ID</code> в конфиге терминала. Ёмкость кассеты
        (250 л.) и ресурс картриджа (2400 стр.) подставятся под Canon MF232w.
        Координаты нужны для карты сети — их можно добавить и позже.
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

/* ── Редактирование киоска ── */

function EditForm({
  kiosk, onCancel, onSaved,
}: {
  kiosk: Kiosk;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [name, setName] = useState(kiosk.name);
  const [location, setLocation] = useState(kiosk.location ?? "");
  const [lat, setLat] = useState(kiosk.latitude?.toString() ?? "");
  const [lng, setLng] = useState(kiosk.longitude?.toString() ?? "");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setErr(null);
    try {
      await updateKiosk(kiosk.id, {
        name: name.trim(),
        location: location.trim() || null,
        latitude: parseCoord(lat) ?? null,
        longitude: parseCoord(lng) ?? null,
      });
      onSaved();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Не удалось сохранить");
      setBusy(false);
    }
  }

  return (
    <div className="card create-card">
      <div className="list-head" style={{ marginTop: 0 }}>
        <h3>Изменить «{kiosk.name}»<span className="count mono">{kiosk.id}</span></h3>
      </div>

      <div className="create-grid">
        <div className="field">
          <label htmlFor="e-name">Название</label>
          <input id="e-name" className="input" value={name}
                 onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="e-loc">Локация</label>
          <input id="e-loc" className="input" value={location}
                 onChange={(e) => setLocation(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="e-lat">Широта</label>
          <input id="e-lat" className="input" value={lat} inputMode="decimal"
                 onChange={(e) => setLat(e.target.value)} placeholder="42.8746" />
        </div>
        <div className="field">
          <label htmlFor="e-lng">Долгота</label>
          <input id="e-lng" className="input" value={lng} inputMode="decimal"
                 onChange={(e) => setLng(e.target.value)} placeholder="74.5698" />
        </div>
      </div>

      <p className="role-hint">
        ID и API-ключ здесь не меняются: ID уже прописан в конфиге терминала, а ключ
        обновляется только кнопкой «Новый ключ». Координаты проще всего скопировать
        из Google Maps или 2ГИС — правый клик по точке даёт пару «широта, долгота».
      </p>

      {err && <div className="login-error" role="alert">{err}</div>}

      <div className="create-actions">
        <button className="btn btn-primary" onClick={() => void submit()}
                disabled={busy || !name.trim()}>
          {busy ? "Сохраняем…" : "Сохранить"}
        </button>
        <button className="btn btn-ghost" onClick={onCancel} disabled={busy}>Отмена</button>
      </div>
    </div>
  );
}

/**
 * Координата из поля ввода. Пустая строка и мусор дают undefined, а не 0:
 * ноль — это реальная точка в Атлантике, и киоск уехал бы туда на карте.
 */
function parseCoord(raw: string): number | undefined {
  const trimmed = raw.trim().replace(",", ".");
  if (!trimmed) return undefined;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : undefined;
}
