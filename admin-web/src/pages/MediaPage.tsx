import  { useCallback, useEffect, useRef, useState } from "react";
import {
  Upload, Trash2, Eye, EyeOff, Info, RefreshCw, Film, Image as ImageIcon, Check, X,
  MapPin, Globe,
} from "lucide-react";
import {
  listAds, uploadAd, deleteAd, setAdEnabled, updateAd, setAdTargets,
  formatSize, ACCEPT_MIME, SLOT_LABEL, SLOT_HINT,
  type AdCreative, type AdSlot,
} from "../lib/adsApi.ts";
import { listKiosks, type Kiosk } from "../lib/kiosksApi.ts";
import "./pages.css";
import "./media.css";

const SLOTS: AdSlot[] = ["HOME", "BANNER"];

export function MediaPage() {
  const [slot, setSlot] = useState<AdSlot>("HOME");
  const [items, setItems] = useState<AdCreative[]>([]);
  const [kiosks, setKiosks] = useState<Kiosk[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** Фильтр «что крутится на этой точке». null — показывать всё. */
  const [filterKiosk, setFilterKiosk] = useState<string | null>(null);

  const load = useCallback(async (s: AdSlot) => {
    setLoading(true);
    setError(null);
    try {
      setItems(await listAds(s));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить список");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(slot); }, [slot, load]);

  // Список киосков нужен и для таргетинга, и для подписей в карточках.
  useEffect(() => {
    listKiosks()
      .then(setKiosks)
      .catch(() => setKiosks([]));   // без киосков просто не покажем таргетинг
  }, []);

  const kioskNames: Record<string, string> = Object.fromEntries(
    kiosks.map((k) => [k.id, k.name])
  );

  /**
   * Что реально увидит выбранный киоск: свои ролики плюс общесетевые.
   * Та же логика, что в findPlaylistForKiosk на сервере — пустой таргетинг
   * означает показ везде.
   */
  const visible = filterKiosk
    ? items.filter((a) => a.kioskIds.length === 0 || a.kioskIds.includes(filterKiosk))
    : items;

  return (
    <>
      <div className="page-head">
        <span className="phase-tag">Фаза 1</span>
        <h2>Реклама</h2>
        <p>Баннеры и видео-заставки для экранов киосков.</p>
      </div>

      {/* Слоты */}
      <div className="slot-tabs" role="tablist">
        {SLOTS.map((s) => (
          <button
            key={s}
            role="tab"
            aria-selected={slot === s}
            className={"slot-tab" + (slot === s ? " active" : "")}
            onClick={() => setSlot(s)}
          >
            {SLOT_LABEL[s]}
          </button>
        ))}
      </div>
      <p className="slot-hint">{SLOT_HINT[slot]}</p>

      <UploadPanel slot={slot} kiosks={kiosks} onUploaded={() => void load(slot)} />

      <div className="cache-note">
        <Info size={15} />
        <span>
          Киоски обновляют плейлист раз в 5 минут — изменения появятся на экранах
          не мгновенно.
        </span>
      </div>

      <div className="list-head">
        <h3>Загруженные материалы</h3>
        <label className="kiosk-filter">
          <MapPin size={14} />
          Показать плейлист киоска
          <select
            className="input"
            value={filterKiosk ?? ""}
            onChange={(e) => setFilterKiosk(e.target.value || null)}
          >
            <option value="">Все материалы</option>
            {kiosks.map((k) => (
              <option key={k.id} value={k.id}>{k.name}</option>
            ))}
          </select>
        </label>
        <button className="btn btn-ghost btn-sm" onClick={() => void load(slot)} disabled={loading}>
          <RefreshCw size={15} className={loading ? "spin" : undefined} />
          Обновить
        </button>
      </div>

      {error && <div className="login-error" role="alert">{error}</div>}

      {loading ? (
        <div className="empty">Загружаем…</div>
      ) : visible.length === 0 ? (
        <div className="empty">
          {filterKiosk
            ? "На этой точке ничего не крутится: нет ни общесетевых материалов, ни адресованных ей."
            : "В этом слоте пока пусто. Загрузите первый ролик или картинку — она появится на киосках после обновления плейлиста."}
        </div>
      ) : (
        <div className="ad-grid">
          {visible.map((ad) => (
            <AdCard
              key={ad.id}
              ad={ad}
              kiosks={kiosks}
              kioskNames={kioskNames}
              onChanged={(next) =>
                setItems((prev) => prev.map((x) => (x.id === next.id ? next : x)))
              }
              onDeleted={() => setItems((prev) => prev.filter((x) => x.id !== ad.id))}
            />
          ))}
        </div>
      )}
    </>
  );
}


/* ───────────────────── Выбор киосков ─────────────────────
   Пустой выбор = «вся сеть». Это не «ничего не выбрано по ошибке», а
   осмысленный режим, поэтому он вынесен отдельным пунктом со своей
   иконкой, а не оставлен как пустое состояние списка. */

function KioskPicker({
  kiosks, value, onChange, disabled,
}: {
  kiosks: Kiosk[];
  value: string[];
  onChange: (next: string[]) => void;
  disabled?: boolean;
}) {
  const all = value.length === 0;

  function toggle(id: string) {
    onChange(value.includes(id) ? value.filter((x) => x !== id) : [...value, id]);
  }

  return (
    <div className="kiosk-picker">
      <button
        type="button"
        className={"kp-option all" + (all ? " on" : "")}
        onClick={() => onChange([])}
        disabled={disabled}
      >
        <Globe size={14} />
        Вся сеть
      </button>

      <div className="kp-list">
        {kiosks.map((k) => (
          <button
            type="button"
            key={k.id}
            className={"kp-option" + (value.includes(k.id) ? " on" : "")}
            onClick={() => toggle(k.id)}
            disabled={disabled}
            title={k.location ?? k.id}
          >
            <MapPin size={13} />
            {k.name}
          </button>
        ))}
        {kiosks.length === 0 && (
          <span className="kp-empty">Киоски ещё не заведены</span>
        )}
      </div>
    </div>
  );
}

/* ───────────────────────── Загрузка ───────────────────────── */

function UploadPanel({
  slot, kiosks, onUploaded,
}: {
  slot: AdSlot;
  kiosks: Kiosk[];
  onUploaded: () => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState("");
  const [duration, setDuration] = useState("10");
  const [targets, setTargets] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);

  const isImage = file?.type.startsWith("image/") ?? false;

  function pick(f: File | null) {
    setErr(null);
    setFile(f);
    if (f && !title) setTitle(f.name.replace(/\.[^.]+$/, ""));
  }

  async function submit() {
    if (!file) return;
    setBusy(true);
    setErr(null);
    try {
      await uploadAd({
        file,
        slot,
        title: title.trim() || undefined,
        // Длительность обязательна только для картинок; видео играет свою.
        durationSec: isImage ? Number(duration) || 10 : undefined,
        kioskIds: targets,
      });
      setFile(null);
      setTitle("");
      setTargets([]);
      if (inputRef.current) inputRef.current.value = "";
      onUploaded();
    } catch (e) {
      setErr(e instanceof Error ? e.message : "Не удалось загрузить");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card upload-card">
      <div
        className={"dropzone" + (dragOver ? " over" : "") + (file ? " filled" : "")}
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          pick(e.dataTransfer.files?.[0] ?? null);
        }}
        onClick={() => inputRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") inputRef.current?.click(); }}
      >
        <Upload size={20} />
        {file ? (
          <span className="dz-file">
            <strong>{file.name}</strong>
            <em className="mono">{formatSize(file.size)}</em>
          </span>
        ) : (
          <span className="dz-copy">
            Перетащите файл сюда или нажмите, чтобы выбрать
            <em>JPG, PNG, GIF, MP4, WebM</em>
          </span>
        )}
        <input
          ref={inputRef}
          type="file"
          accept={ACCEPT_MIME}
          hidden
          onChange={(e) => pick(e.target.files?.[0] ?? null)}
        />
      </div>

      {file && (
        <div className="upload-fields">
          <div className="field">
            <label htmlFor="ad-title">Название</label>
            <input
              id="ad-title" className="input" value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Для админки"
            />
          </div>

          {isImage && (
            <div className="field dur">
              <label htmlFor="ad-dur">Показ, сек</label>
              <input
                id="ad-dur" className="input" type="number" min={1}
                value={duration} onChange={(e) => setDuration(e.target.value)}
              />
            </div>
          )}

          <div className="field targets">
            <label>Где показывать</label>
            <KioskPicker
              kiosks={kiosks} value={targets} onChange={setTargets} disabled={busy}
            />
          </div>

          <div className="upload-actions">
            <button className="btn btn-primary" onClick={() => void submit()} disabled={busy}>
              {busy ? "Загружаем…" : "Загрузить"}
            </button>
            <button
              className="btn btn-ghost"
              onClick={() => { setFile(null); if (inputRef.current) inputRef.current.value = ""; }}
              disabled={busy}
            >
              Отмена
            </button>
          </div>
        </div>
      )}

      {err && <div className="login-error" role="alert">{err}</div>}
    </div>
  );
}

/* ───────────────────────── Карточка ───────────────────────── */

function AdCard({
  ad, kiosks, kioskNames, onChanged, onDeleted,
}: {
  ad: AdCreative;
  kiosks: Kiosk[];
  kioskNames: Record<string, string>;
  onChanged: (next: AdCreative) => void;
  onDeleted: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [editing, setEditing] = useState(false);
  const [editingTargets, setEditingTargets] = useState(false);
  const [draftTargets, setDraftTargets] = useState<string[]>(ad.kioskIds);
  const [title, setTitle] = useState(ad.title);
  const [duration, setDuration] = useState(String(ad.durationSec ?? ""));

  const isVideo = ad.mediaType === "VIDEO";

  async function toggle() {
    setBusy(true);
    try { onChanged(await setAdEnabled(ad.id, !ad.enabled)); }
    finally { setBusy(false); }
  }

  async function remove() {
    setBusy(true);
    try { await deleteAd(ad.id); onDeleted(); }
    finally { setBusy(false); }
  }

  async function save() {
    setBusy(true);
    try {
      onChanged(await updateAd(ad.id, {
        title: title.trim() || undefined,
        durationSec: !isVideo && duration ? Number(duration) : undefined,
      }));
      setEditing(false);
    } finally { setBusy(false); }
  }

  async function saveTargets() {
    setBusy(true);
    try {
      onChanged(await setAdTargets(ad.id, draftTargets));
      setEditingTargets(false);
    } finally { setBusy(false); }
  }

  return (
    <div className={"card ad-card" + (ad.enabled ? "" : " off")}>
      <div className="ad-preview">
        {isVideo ? (
          <video src={ad.mediaUrl} muted loop playsInline
                 onMouseEnter={(e) => void e.currentTarget.play()}
                 onMouseLeave={(e) => e.currentTarget.pause()} />
        ) : (
          <img src={ad.mediaUrl} alt={ad.title} loading="lazy" />
        )}
        <span className="ad-type mono">
          {isVideo ? <Film size={12} /> : <ImageIcon size={12} />}
          {isVideo ? "VIDEO" : "IMAGE"}
        </span>
        {!ad.enabled && <span className="ad-off-badge">Выключено</span>}
      </div>

      <div className="ad-body">
        {editing ? (
          <div className="ad-edit">
            <input className="input" value={title} onChange={(e) => setTitle(e.target.value)} />
            {!isVideo && (
              <input
                className="input" type="number" min={1} value={duration}
                onChange={(e) => setDuration(e.target.value)} placeholder="сек"
              />
            )}
            <div className="ad-edit-actions">
              <button className="icon-btn" onClick={() => void save()} disabled={busy} title="Сохранить">
                <Check size={16} />
              </button>
              <button className="icon-btn" onClick={() => setEditing(false)} disabled={busy} title="Отмена">
                <X size={16} />
              </button>
            </div>
          </div>
        ) : (
          <>
            <button className="ad-title" onClick={() => setEditing(true)} title="Переименовать">
              {ad.title}
            </button>
            <div className="ad-meta mono">
              {formatSize(ad.fileSize)}
              {ad.durationSec ? ` · ${ad.durationSec} сек` : ""}
            </div>
          </>
        )}

        {editingTargets ? (
          <div className="ad-targets-edit">
            <KioskPicker
              kiosks={kiosks} value={draftTargets}
              onChange={setDraftTargets} disabled={busy}
            />
            <div className="ad-edit-actions">
              <button className="icon-btn" onClick={() => void saveTargets()} disabled={busy} title="Сохранить">
                <Check size={16} />
              </button>
              <button
                className="icon-btn"
                onClick={() => { setDraftTargets(ad.kioskIds); setEditingTargets(false); }}
                disabled={busy} title="Отмена"
              >
                <X size={16} />
              </button>
            </div>
          </div>
        ) : (
          <button
            className={"ad-targets" + (ad.kioskIds.length === 0 ? " all" : "")}
            onClick={() => { setDraftTargets(ad.kioskIds); setEditingTargets(true); }}
            title="Изменить киоски показа"
          >
            {ad.kioskIds.length === 0 ? <Globe size={13} /> : <MapPin size={13} />}
            <span>
              {ad.kioskIds.length === 0
                ? "Вся сеть"
                : ad.kioskIds.map((id) => kioskNames[id] ?? id).join(", ")}
            </span>
          </button>
        )}

        <div className="ad-actions">
          <button className="btn btn-ghost btn-sm" onClick={() => void toggle()} disabled={busy}>
            {ad.enabled ? <EyeOff size={15} /> : <Eye size={15} />}
            {ad.enabled ? "Выключить" : "Включить"}
          </button>

          {confirming ? (
            <span className="confirm">
              <button className="btn btn-danger btn-sm" onClick={() => void remove()} disabled={busy}>
                Удалить
              </button>
              <button className="btn btn-ghost btn-sm" onClick={() => setConfirming(false)} disabled={busy}>
                Нет
              </button>
            </span>
          ) : (
            <button
              className="icon-btn danger" onClick={() => setConfirming(true)}
              disabled={busy} title="Удалить"
            >
              <Trash2 size={15} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
