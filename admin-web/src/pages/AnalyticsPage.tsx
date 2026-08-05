import { useCallback, useEffect, useState } from "react";
import { RefreshCw, Info, Clock, Layers, Filter } from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import {
  fetchAnalytics, formatPercent, formatAvg, peakHours, WEEKDAY_LABEL,
  SOURCE_LABEL, SOURCE_IS_EXTERNAL,
  type Analytics, type OperationStat, type HourlyPoint, type Funnel,
  type SourceStat, type FormatStat,
} from "../lib/analyticsApi.ts";
import { OPERATION_LABEL, OPERATION_FAMILY, formatSom } from "../lib/txApi.ts";
import "./pages.css";
import "./dashboard.css";
import "./tx.css";
import "./analytics.css";

const PERIODS = [7, 30, 90];

export function AnalyticsPage() {
  const { user } = useAuth();
  const isOwner = user?.role === "OWNER";

  const [days, setDays] = useState(30);
  const [data, setData] = useState<Analytics | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (d: number) => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchAnalytics(d));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить аналитику");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(days); }, [days, load]);

  const hasData = data && data.byOperation.length > 0;

  return (
    <>
      <div className="page-head">
        <span className="phase-tag">Фаза 3</span>
        <h2>Аналитика</h2>
        <p>Услуги, точки и нагрузка по времени за период.</p>
      </div>

      <div className="filter-row" style={{ marginBottom: 18 }}>
        <div className="preset-tabs">
          {PERIODS.map((d) => (
            <button
              key={d}
              className={"slot-tab" + (days === d ? " active" : "")}
              onClick={() => setDays(d)}
            >
              {d} дней
            </button>
          ))}
        </div>
        <button className="btn btn-ghost btn-sm" onClick={() => void load(days)} disabled={loading}>
          <RefreshCw size={15} className={loading ? "spin" : undefined} />
          Обновить
        </button>
      </div>

      {error && <div className="login-error" role="alert">{error}</div>}

      {loading && !data ? (
        <div className="empty">Загружаем…</div>
      ) : !hasData ? (
        <div className="empty">
          За выбранный период данных нет. Попробуйте расширить период.
        </div>
      ) : (
        <>
          <Averages data={data} isOwner={isOwner} />

          <div className="eyebrow section-label">Услуги</div>
          <ServiceTable rows={data.byOperation} isOwner={isOwner} />

          <div className="eyebrow section-label">Каналы загрузки</div>
          <div className="analytics-2col">
            <SourceTable rows={data.bySource} isOwner={isOwner} />
            <FormatTable rows={data.byFormat} />
          </div>

          <div className="eyebrow section-label">Нагрузка по времени</div>
          <div className="analytics-2col">
            <HourlyChart hourly={data.hourly} />
            <WeekdayChart data={data} />
          </div>

          <div className="eyebrow section-label">Точки</div>
          <KioskTable data={data} isOwner={isOwner} />

          <div className="analytics-2col">
            <div>
              <div className="eyebrow section-label">Воронка заказа</div>
              <FunnelCard funnel={data.funnel} isOwner={isOwner} />
            </div>
            <div>
              <div className="eyebrow section-label">Объём заказов</div>
              <VolumeCard data={data} />
            </div>
          </div>

          <div className="cache-note" style={{ marginTop: 22 }}>
            <Info size={15} />
            <span>
              Канал и формат сохраняются в самом задании, поэтому статистика переживает
              удаление файла. У заданий, созданных до этого изменения, канал восстановлен
              по типу операции, а у части — помечен «не определён».
            </span>
          </div>
        </>
      )}
    </>
  );
}

/* ── Средние ─────────────────────────────────────────────── */

function Averages({ data, isOwner }: { data: Analytics; isOwner: boolean }) {
  const a = data.averages;
  const peaks = peakHours(data.hourly);
  return (
    <div className="kpi-grid">
      <div className="card kpi">
        <div className="kpi-label"><Layers size={15} /> Средний заказ</div>
        <div className="kpi-value">{formatAvg(a.avgPagesPerJob)} стр</div>
        <div className="kpi-sub">копий: {formatAvg(a.avgCopiesPerJob)}</div>
      </div>
      {isOwner && (
        <div className="card kpi">
          <div className="kpi-label">Средний чек</div>
          <div className="kpi-value">{formatSom(a.avgCheckSom ?? 0)}</div>
          <div className="kpi-sub">на оплаченное задание</div>
        </div>
      )}
      <div className="card kpi">
        <div className="kpi-label"><Clock size={15} /> Время до оплаты</div>
        <div className="kpi-value">{formatAvg(a.avgMinutesToPayment)} мин</div>
        <div className="kpi-sub">от создания задания</div>
      </div>
      <div className="card kpi">
        <div className="kpi-label">Часы пик</div>
        <div className="kpi-value">
          {peaks.length ? peaks.map((h) => `${h}:00`).join(" · ") : "—"}
        </div>
        <div className="kpi-sub">максимум заданий</div>
      </div>
    </div>
  );
}

/* ── Услуги ──────────────────────────────────────────────── */

function ServiceTable({ rows, isOwner }: { rows: OperationStat[]; isOwner: boolean }) {
  const maxShare = Math.max(...rows.map((r) => r.sharePercent), 1);
  return (
    <div className="table-wrap card">
      <table className="tx-table">
        <thead>
          <tr>
            <th>Услуга</th>
            <th className="num">Заданий</th>
            <th className="num">Оплачено</th>
            <th className="num">Страниц</th>
            {isOwner && <th className="num">Выручка</th>}
            <th className="num">Ср. стр.</th>
            <th className="num">Конверсия</th>
            <th>Доля</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.operationType}>
              <td>
                <span className={`tx-op ${OPERATION_FAMILY[r.operationType] ?? ""}`}>
                  {OPERATION_LABEL[r.operationType] ?? r.operationType}
                </span>
              </td>
              <td className="num mono">{r.jobs}</td>
              <td className="num mono">{r.paidJobs}</td>
              <td className="num mono">{r.pages}</td>
              {isOwner && <td className="num mono strong">{formatSom(r.revenueSom ?? 0)}</td>}
              <td className="num mono">{formatAvg(r.avgPages)}</td>
              <td className="num mono">{formatPercent(r.conversionPercent)}</td>
              <td className="share-cell">
                <div className="share-bar">
                  <span style={{ width: `${(r.sharePercent / maxShare) * 100}%` }} />
                </div>
                <span className="mono dim share-num">{formatPercent(r.sharePercent)}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ── Каналы и форматы ────────────────────────────────────── */

function SourceTable({ rows, isOwner }: { rows: SourceStat[]; isOwner: boolean }) {
  if (rows.length === 0) return <div className="empty">Нет данных по каналам.</div>;
  return (
    <div className="table-wrap card">
      <table className="tx-table">
        <thead>
          <tr>
            <th>Канал</th>
            <th className="num">Заданий</th>
            <th className="num">Оплачено</th>
            {isOwner && <th className="num">Выручка</th>}
            <th className="num">Конверсия</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.source}>
              <td>
                <span className={`tx-op ${SOURCE_IS_EXTERNAL[r.source] ? "digital" : ""}`}>
                  {SOURCE_LABEL[r.source] ?? r.source}
                </span>
              </td>
              <td className="num mono">{r.jobs}</td>
              <td className="num mono">{r.paidJobs}</td>
              {isOwner && <td className="num mono strong">{formatSom(r.revenueSom ?? 0)}</td>}
              <td className="num mono">{formatPercent(r.conversionPercent)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="chart-tip mono" style={{ padding: "10px 14px" }}>
        подсвечены внешние каналы — их конверсию сравнивайте между собой
      </div>
    </div>
  );
}

function FormatTable({ rows }: { rows: FormatStat[] }) {
  if (rows.length === 0) {
    return (
      <div className="empty">
        За период не было документов, загруженных из Telegram или с сайта.
      </div>
    );
  }
  const max = Math.max(...rows.map((r) => r.jobs), 1);
  return (
    <div className="card chart-card">
      <div className="chart-head">
        <h3>Форматы документов</h3>
        <span className="chart-tip mono">только загруженные</span>
      </div>
      <div className="weekday-rows">
        {rows.map((r) => (
          <div className="weekday-row" key={r.extension}>
            <span className="weekday-label mono">{r.extension}</span>
            <div className="weekday-bar">
              <span style={{ width: `${(r.jobs / max) * 100}%` }} />
            </div>
            <span className="weekday-num mono">{r.jobs}</span>
          </div>
        ))}
      </div>
      <div className="chart-tip mono" style={{ marginTop: 10 }}>
        конверсия в печать: {rows.map((r) => `${r.extension} ${formatPercent(r.conversionPercent)}`).join(" · ")}
      </div>
    </div>
  );
}

/* ── Часы ────────────────────────────────────────────────── */

function HourlyChart({ hourly }: { hourly: HourlyPoint[] }) {
  const W = 560, H = 150, PAD = 8;
  const max = Math.max(...hourly.map((h) => h.jobs), 1);
  const barW = (W - PAD * 2) / 24;

  return (
    <div className="card chart-card">
      <div className="chart-head">
        <h3>Задания по часам</h3>
        <span className="chart-tip mono">максимум {max}</span>
      </div>
      <div className="chart">
        <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img"
             aria-label="Распределение заданий по часам суток">
          {hourly.map((h, i) => {
            const barH = (h.jobs / max) * (H - 26);
            const x = PAD + i * barW;
            const y = H - 18 - barH;
            return (
              <rect
                key={h.hour}
                x={x + 1} y={y}
                width={Math.max(barW - 2, 1)}
                height={Math.max(barH, h.jobs > 0 ? 2 : 0)}
                rx="2"
                className="hour-bar"
              >
                <title>{`${h.hour}:00 — ${h.jobs} заданий`}</title>
              </rect>
            );
          })}
        </svg>
      </div>
      <div className="hour-axis mono">
        <span>0</span><span>6</span><span>12</span><span>18</span><span>23</span>
      </div>
    </div>
  );
}

function WeekdayChart({ data }: { data: Analytics }) {
  const max = Math.max(...data.weekday.map((w) => w.paidJobs), 1);
  return (
    <div className="card chart-card">
      <div className="chart-head">
        <h3>По дням недели</h3>
        <span className="chart-tip mono">оплаченные</span>
      </div>
      <div className="weekday-rows">
        {data.weekday.map((w) => (
          <div className="weekday-row" key={w.weekday}>
            <span className="weekday-label mono">{WEEKDAY_LABEL[w.weekday]}</span>
            <div className="weekday-bar">
              <span style={{ width: `${(w.paidJobs / max) * 100}%` }} />
            </div>
            <span className="weekday-num mono">{w.paidJobs}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ── Киоски ──────────────────────────────────────────────── */

function KioskTable({ data, isOwner }: { data: Analytics; isOwner: boolean }) {
  return (
    <div className="table-wrap card">
      <table className="tx-table">
        <thead>
          <tr>
            <th>Киоск</th>
            <th className="num">Заданий</th>
            <th className="num">Оплачено</th>
            <th className="num">Страниц</th>
            {isOwner && <th className="num">Выручка</th>}
            {isOwner && <th className="num">Ср. чек</th>}
            <th className="num">Конверсия</th>
            <th>Топ-услуга</th>
          </tr>
        </thead>
        <tbody>
          {data.byKiosk.map((k) => (
            <tr key={k.kioskId}>
              <td className="mono strong">{k.kioskId}</td>
              <td className="num mono">{k.jobs}</td>
              <td className="num mono">{k.paidJobs}</td>
              <td className="num mono">{k.pages}</td>
              {isOwner && <td className="num mono strong">{formatSom(k.revenueSom ?? 0)}</td>}
              {isOwner && <td className="num mono">{formatSom(k.avgCheckSom ?? 0)}</td>}
              <td className="num mono">{formatPercent(k.conversionPercent)}</td>
              <td>
                {k.topOperation ? (
                  <span className={`tx-op ${OPERATION_FAMILY[k.topOperation] ?? ""}`}>
                    {OPERATION_LABEL[k.topOperation]}
                  </span>
                ) : <span className="dim">—</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ── Воронка ─────────────────────────────────────────────── */

function FunnelCard({ funnel, isOwner }: { funnel: Funnel; isOwner: boolean }) {
  const steps = [
    { label: "Задание создано", value: funnel.created, hint: "" },
    { label: "Оплата выставлена", value: funnel.paymentCreated, hint: formatPercent(funnel.paymentRatePercent) },
    { label: "Оплачено", value: funnel.paid, hint: formatPercent(funnel.paidRatePercent) },
    { label: "Завершено", value: funnel.completed, hint: formatPercent(funnel.completionRatePercent) },
  ];
  const max = Math.max(funnel.created, 1);

  return (
    <div className="card funnel-card">
      {steps.map((s) => (
        <div className="funnel-step" key={s.label}>
          <div className="funnel-top">
            <span className="funnel-label">{s.label}</span>
            <span className="mono funnel-value">
              {s.value}
              {s.hint && <span className="funnel-rate"> · {s.hint}</span>}
            </span>
          </div>
          <div className="funnel-bar">
            <span style={{ width: `${(s.value / max) * 100}%` }} />
          </div>
        </div>
      ))}

      <div className="funnel-foot">
        <span><Filter size={13} /> Ошибок: <b className="mono">{funnel.failed}</b></span>
        <span>Истекло: <b className="mono">{funnel.expired}</b></span>
        {isOwner && (
          <span>Не дошло до оплаты: <b className="mono">{formatSom(funnel.lostRevenueSom ?? 0)}</b></span>
        )}
      </div>
    </div>
  );
}

function VolumeCard({ data }: { data: Analytics }) {
  const max = Math.max(...data.volumeBuckets.map((b) => b.jobs), 1);
  return (
    <div className="card chart-card">
      <div className="weekday-rows">
        {data.volumeBuckets.map((b) => (
          <div className="weekday-row" key={b.label}>
            <span className="weekday-label mono">{b.label}</span>
            <div className="weekday-bar">
              <span style={{ width: `${(b.jobs / max) * 100}%` }} />
            </div>
            <span className="weekday-num mono">{b.jobs}</span>
          </div>
        ))}
      </div>
      <div className="chart-tip mono" style={{ marginTop: 10 }}>
        страниц в заказе · всего {data.volumeBuckets.reduce((s, b) => s + b.jobs, 0)}
      </div>
    </div>
  );
}
