import { useCallback, useEffect, useMemo, useState } from "react";
import { Check, History, RefreshCw, RotateCcw, Wallet, X } from "lucide-react";
import { listKiosks, type Kiosk } from "../lib/kiosksApi.ts";
import {
  listTariffs, tariffHistory, setDefaultTariff, setKioskTariff, resetKioskTariff,
  type Tariff,
} from "../lib/tariffsApi.ts";
import "./pages.css";
import "./pricing.css";

/** Строка таблицы: киоск + его действующая цена (своя или унаследованная). */
interface Row {
  kioskId: string | null;
  name: string;
  location: string | null;
  bw: number;
  color: number;
  /** true — у киоска своя цена, false — работает по глобальной. */
  own: boolean;
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString("ru-RU", {
    day: "2-digit", month: "2-digit", year: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}

export function PricingPage() {
  const [tariffs, setTariffs] = useState<Tariff[]>([]);
  const [kiosks, setKiosks] = useState<Kiosk[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState<string | null>(null);   // kioskId | "__default__"
  const [draft, setDraft] = useState<{ bw: string; color: string }>({ bw: "", color: "" });
  const [saving, setSaving] = useState(false);

  const [historyFor, setHistoryFor] = useState<Row | null>(null);
  const [historyRows, setHistoryRows] = useState<Tariff[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [t, k] = await Promise.all([listTariffs(), listKiosks()]);
      setTariffs(t);
      setKiosks(k);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить цены");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const defaultTariff = useMemo(
    () => tariffs.find((t) => t.kioskId === null) ?? null,
    [tariffs]
  );

  /**
   * Показываем ВСЕ киоски, а не только те, у кого есть своя строка тарифа:
   * оператору важно видеть фактическую цену на каждой точке, даже если она
   * унаследована. Иначе «пусто в таблице» читается как «цена не задана».
   */
  const rows: Row[] = useMemo(() => {
    const overrides = new Map(
      tariffs.filter((t) => t.kioskId !== null).map((t) => [t.kioskId as string, t])
    );
    return kiosks.map((k) => {
      const own = overrides.get(k.id);
      return {
        kioskId: k.id,
        name: k.name,
        location: k.location,
        bw: own ? own.bwPriceSom : defaultTariff?.bwPriceSom ?? 0,
        color: own ? own.colorPriceSom : defaultTariff?.colorPriceSom ?? 0,
        own: !!own,
      };
    });
  }, [kiosks, tariffs, defaultTariff]);

  function startEdit(key: string, bw: number, color: number) {
    setEditing(key);
    setDraft({ bw: String(bw), color: String(color) });
    setError(null);
  }

  async function save(kioskId: string | null) {
    const bw = Number(draft.bw);
    const color = Number(draft.color);
    if (!Number.isInteger(bw) || !Number.isInteger(color) || bw < 0 || color < 0) {
      setError("Цена должна быть целым неотрицательным числом");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      if (kioskId === null) await setDefaultTariff({ bwPriceSom: bw, colorPriceSom: color });
      else await setKioskTariff(kioskId, { bwPriceSom: bw, colorPriceSom: color });
      setEditing(null);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось сохранить цену");
    } finally {
      setSaving(false);
    }
  }

  async function reset(kioskId: string) {
    setSaving(true);
    try {
      await resetKioskTariff(kioskId);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось сбросить цену");
    } finally {
      setSaving(false);
    }
  }

  async function openHistory(row: Row) {
    setHistoryFor(row);
    setHistoryRows([]);
    try {
      setHistoryRows(await tariffHistory(row.kioskId ?? undefined));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить историю");
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <h2>Цены</h2>
        <p>
          Базовая цена действует на все киоски. Персональная цена киоска
          перекрывает её — например, если точка стоит в аренде подороже.
        </p>
      </div>

      {error && <div className="alert-error">{error}</div>}

      {loading ? (
        <div className="muted">Загрузка…</div>
      ) : (
        <>
          {/* ---- Базовая цена ---- */}
          <section className="pricing-default">
            <header>
              <Wallet size={18} />
              <h3>Базовая цена сети</h3>
              {defaultTariff && (
                <span className="muted">
                  действует с {formatDate(defaultTariff.effectiveFrom)}
                </span>
              )}
            </header>

            {editing === "__default__" ? (
              <div className="pricing-edit">
                <label>
                  Ч/б
                  <input
                    type="number" min={0} value={draft.bw}
                    onChange={(e) => setDraft({ ...draft, bw: e.target.value })}
                  />
                </label>
                <label>
                  Цвет
                  <input
                    type="number" min={0} value={draft.color}
                    onChange={(e) => setDraft({ ...draft, color: e.target.value })}
                  />
                </label>
                <button className="btn-icon ok" disabled={saving} onClick={() => save(null)}>
                  <Check size={16} />
                </button>
                <button className="btn-icon" onClick={() => setEditing(null)}>
                  <X size={16} />
                </button>
              </div>
            ) : (
              <div className="pricing-values">
                <span className="price"><b>{defaultTariff?.bwPriceSom ?? "—"}</b> сом / ч-б</span>
                <span className="price"><b>{defaultTariff?.colorPriceSom ?? "—"}</b> сом / цвет</span>
                <button
                  className="btn-sm"
                  onClick={() => startEdit("__default__",
                    defaultTariff?.bwPriceSom ?? 0, defaultTariff?.colorPriceSom ?? 0)}
                >
                  Изменить
                </button>
                <button
                  className="btn-sm ghost"
                  onClick={() => openHistory({
                    kioskId: null, name: "Базовая цена", location: null,
                    bw: defaultTariff?.bwPriceSom ?? 0,
                    color: defaultTariff?.colorPriceSom ?? 0, own: true,
                  })}
                >
                  <History size={14} /> История
                </button>
              </div>
            )}
          </section>

          {/* ---- Цены по киоскам ---- */}
          <section className="pricing-table">
            <header>
              <h3>Цены по точкам</h3>
              <button className="btn-sm ghost" onClick={() => void load()}>
                <RefreshCw size={14} /> Обновить
              </button>
            </header>

            <table>
              <thead>
                <tr>
                  <th>Киоск</th>
                  <th>Адрес</th>
                  <th>Ч/б</th>
                  <th>Цвет</th>
                  <th>Источник</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => {
                  const isEditing = editing === r.kioskId;
                  return (
                    <tr key={r.kioskId}>
                      <td><b>{r.name}</b><div className="muted small">{r.kioskId}</div></td>
                      <td className="muted">{r.location ?? "—"}</td>

                      {isEditing ? (
                        <>
                          <td>
                            <input
                              type="number" min={0} value={draft.bw}
                              onChange={(e) => setDraft({ ...draft, bw: e.target.value })}
                            />
                          </td>
                          <td>
                            <input
                              type="number" min={0} value={draft.color}
                              onChange={(e) => setDraft({ ...draft, color: e.target.value })}
                            />
                          </td>
                          <td colSpan={2} className="row-actions">
                            <button
                              className="btn-icon ok" disabled={saving}
                              onClick={() => void save(r.kioskId)}
                            >
                              <Check size={16} />
                            </button>
                            <button className="btn-icon" onClick={() => setEditing(null)}>
                              <X size={16} />
                            </button>
                          </td>
                        </>
                      ) : (
                        <>
                          <td>{r.bw} сом</td>
                          <td>{r.color} сом</td>
                          <td>
                            {r.own
                              ? <span className="tag own">Своя цена</span>
                              : <span className="tag inherited">Базовая</span>}
                          </td>
                          <td className="row-actions">
                            <button
                              className="btn-sm"
                              onClick={() => startEdit(r.kioskId as string, r.bw, r.color)}
                            >
                              Изменить
                            </button>
                            {r.own && (
                              <button
                                className="btn-icon" title="Вернуть на базовую цену"
                                disabled={saving}
                                onClick={() => void reset(r.kioskId as string)}
                              >
                                <RotateCcw size={15} />
                              </button>
                            )}
                            <button
                              className="btn-icon" title="История цен"
                              onClick={() => void openHistory(r)}
                            >
                              <History size={15} />
                            </button>
                          </td>
                        </>
                      )}
                    </tr>
                  );
                })}
                {rows.length === 0 && (
                  <tr><td colSpan={6} className="muted">Киоски ещё не заведены</td></tr>
                )}
              </tbody>
            </table>
          </section>
        </>
      )}

      {/* ---- История ---- */}
      {historyFor && (
        <div className="modal-backdrop" onClick={() => setHistoryFor(null)}>
          <div className="modal" onClick={(e) => e.stopPropagation()}>
            <header>
              <h3>История цен — {historyFor.name}</h3>
              <button className="btn-icon" onClick={() => setHistoryFor(null)}>
                <X size={16} />
              </button>
            </header>
            <table>
              <thead>
                <tr><th>Ч/б</th><th>Цвет</th><th>Действовала с</th><th>по</th></tr>
              </thead>
              <tbody>
                {historyRows.map((h) => (
                  <tr key={h.id}>
                    <td>{h.bwPriceSom} сом</td>
                    <td>{h.colorPriceSom} сом</td>
                    <td>{formatDate(h.effectiveFrom)}</td>
                    <td>{h.effectiveTo ? formatDate(h.effectiveTo) : <b>сейчас</b>}</td>
                  </tr>
                ))}
                {historyRows.length === 0 && (
                  <tr><td colSpan={4} className="muted">Записей нет</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
