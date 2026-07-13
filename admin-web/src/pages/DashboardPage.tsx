import { useCallback, useEffect, useMemo, useState } from "react";
import { RefreshCw, TrendingUp, FileText, CreditCard, AlertTriangle, Info } from "lucide-react";
import { useAuth } from "../auth/AuthContext.tsx";
import {
  fetchDashboard, formatSom, formatNum, shortDate,
  type Dashboard, type DailyPoint,
} from "../lib/dashboardApi.ts";
import "./pages.css";
import "./dashboard.css";

const PERIODS = [7, 30, 90];

export function DashboardPage() {
  const { user } = useAuth();
  const isOwner = user?.role === "OWNER";

  const [days, setDays] = useState(30);
  const [data, setData] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (d: number) => {
    setLoading(true);
    setError(null);
    try {
      setData(await fetchDashboard(d));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить сводку");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(days); }, [days, load]);

  return (
    <>
      <div className="page-head">
        <span className="phase-tag">Фаза 1</span>
        <h2>Дашборд</h2>
        <p>Сводка по сети за сегодня и за период.</p>
      </div>

      {/* Сегодня */}
      <div className="eyebrow section-label">Сегодня</div>
      <div className="kpi-grid">
        {isOwner && (
          <Kpi
            icon={<TrendingUp size={15} />}
            label="Выручка"
            value={data ? formatSom(data.todayRevenueSom) : "—"}
            sub="только оплаченное"
          />
        )}
        <Kpi
          icon={<CreditCard size={15} />}
          label="Оплачено заданий"
          value={data ? formatNum(data.todayPaidJobs) : "—"}
          sub="за сегодня"
        />
        <Kpi
          icon={<FileText size={15} />}
          label="Напечатано страниц"
          value={data ? formatNum(data.todayPages) : "—"}
          sub="с учётом копий"
        />
      </div>

      {/* Период */}
      <div className="section-head">
        <div className="eyebrow section-label">За период</div>
        <div className="period-tabs">
          {PERIODS.map((d) => (
            <button
              key={d}
              className={"slot-tab" + (days === d ? " active" : "")}
              onClick={() => setDays(d)}
            >
              {d} дней
            </button>
          ))}
          <button className="btn btn-ghost btn-sm" onClick={() => void load(days)} disabled={loading}>
            <RefreshCw size={15} className={loading ? "spin" : undefined} />
          </button>
        </div>
      </div>

      <div className="kpi-grid">
        {isOwner && (
          <Kpi
            icon={<TrendingUp size={15} />}
            label="Выручка"
            value={data ? formatSom(data.periodRevenueSom) : "—"}
            sub={`за ${days} дней`}
          />
        )}
        <Kpi
          icon={<CreditCard size={15} />}
          label="Оплачено заданий"
          value={data ? formatNum(data.periodPaidJobs) : "—"}
          sub={`за ${days} дней`}
        />
        <Kpi
          icon={<FileText size={15} />}
          label="Напечатано страниц"
          value={data ? formatNum(data.periodPages) : "—"}
          sub="с учётом копий"
        />
        <Kpi
          icon={<AlertTriangle size={15} />}
          label="Неудачных заданий"
          value={data ? formatNum(data.periodFailedJobs) : "—"}
          sub="требуют внимания"
          tone={data && data.periodFailedJobs > 0 ? "down" : undefined}
        />
      </div>

      {error && <div className="login-error" role="alert">{error}</div>}

      {/* График */}
      {data && data.daily.length > 0 && (
        <div className="card chart-card">
          <div className="chart-head">
            <h3>{isOwner ? "Выручка по дням" : "Оплаченные задания по дням"}</h3>
          </div>
          <Chart points={data.daily} metric={isOwner ? "revenue" : "jobs"} />
        </div>
      )}

      {/* Киоски */}
      <div className="list-head" style={{ marginTop: 24 }}>
        <h3>По киоскам</h3>
      </div>
      {!data || data.byKiosk.length === 0 ? (
        <div className="empty">
          За период нет оплаченных заданий — разбивке по киоскам пока не из чего строиться.
        </div>
      ) : (
        <div className="table-wrap card">
          <table className="tx-table">
            <thead>
              <tr>
                <th>Киоск</th>
                <th className="num">Заданий</th>
                <th className="num">Страниц</th>
                {isOwner && <th className="num">Выручка</th>}
              </tr>
            </thead>
            <tbody>
              {data.byKiosk.map((k) => (
                <tr key={k.kioskId}>
                  <td className="mono">{k.kioskId}</td>
                  <td className="num mono">{formatNum(k.paidJobs)}</td>
                  <td className="num mono">{formatNum(k.pages)}</td>
                  {isOwner && <td className="num mono strong">{formatSom(k.revenueSom)}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="cache-note" style={{ marginTop: 22 }}>
        <Info size={15} />
        <span>
          Статусы киосков (онлайн, бумага, тонер) появятся в Фазе 2 — для них нужна
          телеметрия с терминалов. Сейчас видно только то, что даёт история заданий.
        </span>
      </div>
    </>
  );
}

function Kpi({
  icon, label, value, sub, tone,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  sub: string;
  tone?: "down";
}) {
  return (
    <div className="card kpi">
      <div className="kpi-label kpi-label-row">
        {icon}
        {label}
      </div>
      <div className={"kpi-value" + (tone === "down" ? " tone-down" : "")}>{value}</div>
      <div className="kpi-sub">{sub}</div>
    </div>
  );
}

/* ── Лёгкий SVG-график: без внешних библиотек ── */
function Chart({ points, metric }: { points: DailyPoint[]; metric: "revenue" | "jobs" }) {
  const values = useMemo(
    () => points.map((p) => (metric === "revenue" ? p.revenueSom ?? 0 : p.paidJobs)),
    [points, metric]
  );
  const max = Math.max(...values, 1);
  const [hover, setHover] = useState<number | null>(null);

  const W = 720, H = 180, PAD = 8;
  const barW = (W - PAD * 2) / points.length;

  return (
    <div className="chart">
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" role="img"
           aria-label="График по дням">
        {[0.25, 0.5, 0.75, 1].map((f) => (
          <line key={f} x1={0} x2={W} y1={H - f * (H - 20)} y2={H - f * (H - 20)}
                stroke="var(--border)" strokeWidth="1" />
        ))}
        {points.map((p, i) => {
          const v = values[i];
          const h = (v / max) * (H - 28);
          const x = PAD + i * barW;
          const y = H - h;
          return (
            <rect
              key={p.date}
              x={x + 1} y={y} width={Math.max(barW - 2, 1)} height={Math.max(h, v > 0 ? 2 : 0)}
              rx="2"
              fill={hover === i ? "var(--accent-2)" : "var(--accent)"}
              opacity={v === 0 ? 0.18 : 1}
              onMouseEnter={() => setHover(i)}
              onMouseLeave={() => setHover(null)}
            />
          );
        })}
      </svg>

      <div className="chart-legend">
        <span className="mono">{shortDate(points[0].date)}</span>
        {hover !== null && (
          <span className="chart-tip mono">
            {shortDate(points[hover].date)} ·{" "}
            {metric === "revenue"
              ? formatSom(points[hover].revenueSom)
              : `${formatNum(points[hover].paidJobs)} заданий`}
          </span>
        )}
        <span className="mono">{shortDate(points[points.length - 1].date)}</span>
      </div>
    </div>
  );
}
