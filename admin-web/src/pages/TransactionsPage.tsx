import { useCallback, useEffect, useState } from "react";
import { Search, RefreshCw, ChevronLeft, ChevronRight, Info } from "lucide-react";
import { RoleGate } from "../components/RoleGate.tsx";
import {
  fetchTransactions, fetchKiosks, formatSom, formatDateTime,
  STATUS_LABEL, STATUS_TONE, dayRange,
  type Transaction, type TransactionPage, type JobStatus, type TxFilters,
} from "../lib/txApi.ts";
import "./pages.css";
import "./tx.css";

const PRESETS: { label: string; days: number | null }[] = [
  { label: "Сегодня", days: 0 },
  { label: "7 дней", days: 6 },
  { label: "30 дней", days: 29 },
  { label: "Всё время", days: null },
];

const STATUSES: JobStatus[] = [
  "COMPLETED", "PAID", "PRINTING", "PAYMENT_PENDING", "READY", "FAILED", "EXPIRED",
];

export function TransactionsPage() {
  const [preset, setPreset] = useState(2);          // по умолчанию 30 дней
  const [status, setStatus] = useState<JobStatus | "">("");
  const [kioskId, setKioskId] = useState("");
  const [q, setQ] = useState("");
  const [page, setPage] = useState(0);

  const [data, setData] = useState<TransactionPage | null>(null);
  const [kiosks, setKiosks] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchKiosks().then(setKiosks).catch(() => setKiosks([]));
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const days = PRESETS[preset].days;
      const range = days === null ? {} : dayRange(days);
      const filters: TxFilters = { ...range, status, kioskId, q, page, size: 25 };
      setData(await fetchTransactions(filters));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить транзакции");
    } finally {
      setLoading(false);
    }
  }, [preset, status, kioskId, q, page]);

  useEffect(() => { void load(); }, [load]);

  // Смена фильтра всегда возвращает на первую страницу.
  function changeFilter(fn: () => void) {
    setPage(0);
    fn();
  }

  return (
    <>
      <div className="page-head">
        <span className="phase-tag">Фаза 1</span>
        <h2>Транзакции</h2>
        <p>Все задания и оплаты с фильтрами.</p>
      </div>

      {/* Сводка — деньги видит только владелец */}
      <div className="kpi-grid">
        <RoleGate allow={["OWNER"]}>
          <div className="card kpi">
            <div className="kpi-label">Выручка за период</div>
            <div className="kpi-value">{data ? formatSom(data.revenueSom) : "—"}</div>
            <div className="kpi-sub">только фактически оплаченные</div>
          </div>
        </RoleGate>
        <div className="card kpi">
          <div className="kpi-label">Оплачено</div>
          <div className="kpi-value">{data ? data.paidCount : "—"}</div>
          <div className="kpi-sub">заданий</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Всего заданий</div>
          <div className="kpi-value">{data ? data.totalItems : "—"}</div>
          <div className="kpi-sub">в выборке</div>
        </div>
      </div>

      {/* Фильтры */}
      <div className="filters">
        <div className="preset-tabs">
          {PRESETS.map((p, i) => (
            <button
              key={p.label}
              className={"slot-tab" + (preset === i ? " active" : "")}
              onClick={() => changeFilter(() => setPreset(i))}
            >
              {p.label}
            </button>
          ))}
        </div>

        <div className="filter-row">
          <div className="search-box">
            <Search size={16} />
            <input
              className="input"
              placeholder="PIN, имя файла или payment ID"
              value={q}
              onChange={(e) => changeFilter(() => setQ(e.target.value))}
            />
          </div>

          <select
            className="input select"
            value={status}
            onChange={(e) => changeFilter(() => setStatus(e.target.value as JobStatus | ""))}
          >
            <option value="">Все статусы</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{STATUS_LABEL[s]}</option>
            ))}
          </select>

          <select
            className="input select"
            value={kioskId}
            onChange={(e) => changeFilter(() => setKioskId(e.target.value))}
          >
            <option value="">Все киоски</option>
            {kiosks.map((k) => <option key={k} value={k}>{k}</option>)}
          </select>

          <button className="btn btn-ghost btn-sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw size={15} className={loading ? "spin" : undefined} />
            Обновить
          </button>
        </div>
      </div>

      {error && <div className="login-error" role="alert">{error}</div>}

      {/* Таблица */}
      {loading && !data ? (
        <div className="empty">Загружаем…</div>
      ) : !data || data.items.length === 0 ? (
        <div className="empty">
          За выбранный период транзакций нет. Попробуйте расширить период или сбросить фильтры.
        </div>
      ) : (
        <>
          <div className="table-wrap card">
            <table className="tx-table">
              <thead>
                <tr>
                  <th>Дата</th>
                  <th>PIN</th>
                  <th>Файл</th>
                  <th className="num">Стр.</th>
                  <th className="num">Копий</th>
                  <th className="num">Сумма</th>
                  <th>Статус</th>
                  <th>Киоск</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((t) => <Row key={t.id} t={t} />)}
              </tbody>
            </table>
          </div>

          <div className="pager">
            <span className="mono pager-info">
              Стр. {data.page + 1} из {Math.max(data.totalPages, 1)} · всего {data.totalItems}
            </span>
            <div className="pager-btns">
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => setPage((p) => Math.max(p - 1, 0))}
                disabled={data.page === 0 || loading}
              >
                <ChevronLeft size={15} /> Назад
              </button>
              <button
                className="btn btn-ghost btn-sm"
                onClick={() => setPage((p) => p + 1)}
                disabled={data.page + 1 >= data.totalPages || loading}
              >
                Вперёд <ChevronRight size={15} />
              </button>
            </div>
          </div>
        </>
      )}

      <div className="cache-note" style={{ marginTop: 22 }}>
        <Info size={15} />
        <span>
          Возврат средств пока недоступен: в API платёжного шлюза не подключён метод
          refund. Как появится — кнопка вернётся сюда.
        </span>
      </div>
    </>
  );
}

function Row({ t }: { t: Transaction }) {
  const tone = STATUS_TONE[t.status];
  return (
    <tr>
      <td className="mono nowrap">{formatDateTime(t.createdAt)}</td>
      <td className="mono pin">{t.pin}</td>
      <td className="file" title={t.fileName}>{t.fileName}</td>
      <td className="num mono">{t.pageCount}</td>
      <td className="num mono">{t.copies}</td>
      <td className="num mono strong">{formatSom(t.priceSom)}</td>
      <td>
        <span className={`tx-status tone-${tone}`}>
          <span className={`status-dot ${tone === "idle" ? "" : tone}`} />
          {STATUS_LABEL[t.status]}
        </span>
      </td>
      <td className="mono dim">{t.kioskId ?? "—"}</td>
    </tr>
  );
}
