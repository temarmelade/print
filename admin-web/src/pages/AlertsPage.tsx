import { useCallback, useEffect, useState } from "react";
import { RefreshCw, Check, CheckCircle2, AlertTriangle, Clock } from "lucide-react";
import {
  fetchOpenIncidents, fetchIncidentHistory, fetchIncidentSummary,
  acknowledgeIncident, resolveIncident, formatDuration, INCIDENT_ICON,
  type Incident, type IncidentSummary,
} from "../lib/incidentsApi.ts";
import { formatDateTime } from "../lib/txApi.ts";
import "./pages.css";
import "./dashboard.css";
import "./tx.css";
import "./incidents.css";

type Tab = "open" | "history";

export function AlertsPage() {
  const [tab, setTab] = useState<Tab>("open");
  const [open, setOpen] = useState<Incident[]>([]);
  const [history, setHistory] = useState<Incident[]>([]);
  const [summary, setSummary] = useState<IncidentSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [o, s, h] = await Promise.all([
        fetchOpenIncidents(),
        fetchIncidentSummary(30),
        fetchIncidentHistory(30),
      ]);
      setOpen(o);
      setSummary(s);
      setHistory(h);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Не удалось загрузить инциденты");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  // Инциденты — оперативный экран: обновляем сами, чтобы дежурный не жал F5.
  useEffect(() => {
    const t = setInterval(() => { void load(); }, 30_000);
    return () => clearInterval(t);
  }, [load]);

  async function act(id: number, fn: (id: number) => Promise<void>) {
    setBusyId(id);
    try {
      await fn(id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Действие не выполнено");
    } finally {
      setBusyId(null);
    }
  }

  return (
    <>
      <div className="page-head">
        <span className="phase-tag">Фаза 3</span>
        <h2>Инциденты</h2>
        <p>Проблемы киосков, время простоя и история устранения.</p>
      </div>

      {summary && <SummaryCards summary={summary} />}

      <div className="filter-row" style={{ marginBottom: 16 }}>
        <div className="preset-tabs">
          <button className={"slot-tab" + (tab === "open" ? " active" : "")}
                  onClick={() => setTab("open")}>
            Открытые {open.length > 0 && <span className="tab-count">{open.length}</span>}
          </button>
          <button className={"slot-tab" + (tab === "history" ? " active" : "")}
                  onClick={() => setTab("history")}>
            История
          </button>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={() => void load()} disabled={loading}>
          <RefreshCw size={15} className={loading ? "spin" : undefined} />
          Обновить
        </button>
      </div>

      {error && <div className="login-error" role="alert">{error}</div>}

      {loading && open.length === 0 && !summary ? (
        <div className="empty">Загружаем…</div>
      ) : tab === "open" ? (
        open.length === 0 ? (
          <div className="empty all-clear">
            <CheckCircle2 size={22} />
            <span>Открытых инцидентов нет — все киоски работают.</span>
          </div>
        ) : (
          <div className="incident-list">
            {open.map((i) => (
              <IncidentCard
                key={i.id}
                incident={i}
                busy={busyId === i.id}
                onAcknowledge={() => void act(i.id, acknowledgeIncident)}
                onResolve={() => void act(i.id, resolveIncident)}
              />
            ))}
          </div>
        )
      ) : (
        <HistoryTable rows={history} />
      )}

      {summary && summary.topTypes.length > 0 && (
        <>
          <div className="eyebrow section-label">Частые причины за 30 дней</div>
          <TopTypes summary={summary} />
        </>
      )}
    </>
  );
}

/* ── Сводка ──────────────────────────────────────────────── */

function SummaryCards({ summary }: { summary: IncidentSummary }) {
  return (
    <div className="kpi-grid">
      <div className={"card kpi" + (summary.openBlocking > 0 ? " kpi-alert" : "")}>
        <div className="kpi-label"><AlertTriangle size={15} /> Не работают</div>
        <div className="kpi-value">{summary.openBlocking}</div>
        <div className="kpi-sub">печать невозможна</div>
      </div>
      <div className="card kpi">
        <div className="kpi-label">Предупреждения</div>
        <div className="kpi-value">{summary.openWarning}</div>
        <div className="kpi-sub">работают, но скоро встанут</div>
      </div>
      <div className="card kpi">
        <div className="kpi-label"><Clock size={15} /> Среднее устранение</div>
        <div className="kpi-value">{formatDuration(summary.avgResolutionMinutes)}</div>
        <div className="kpi-sub">за 30 дней</div>
      </div>
      <div className="card kpi">
        <div className="kpi-label">Суммарный простой</div>
        <div className="kpi-value">{formatDuration(summary.totalDowntimeMinutes)}</div>
        <div className="kpi-sub">инцидентов: {summary.totalInPeriod}</div>
      </div>
    </div>
  );
}

/* ── Карточка открытого инцидента ────────────────────────── */

function IncidentCard({
  incident, busy, onAcknowledge, onResolve,
}: {
  incident: Incident;
  busy: boolean;
  onAcknowledge: () => void;
  onResolve: () => void;
}) {
  const blocking = incident.severity === "DOWN";
  return (
    <div className={"card incident" + (blocking ? " blocking" : " warning")}>
      <div className="incident-icon" aria-hidden="true">
        {INCIDENT_ICON[incident.incidentType] ?? "⚠️"}
      </div>

      <div className="incident-body">
        <div className="incident-title">
          {incident.title}
          <span className={"sev-chip " + (blocking ? "down" : "warn")}>
            {blocking ? "не работает" : "предупреждение"}
          </span>
        </div>
        <div className="incident-meta">
          <b>{incident.kioskName}</b>
          {incident.location && <span className="dim"> · {incident.location}</span>}
        </div>
        {incident.reason && <div className="incident-reason">{incident.reason}</div>}
        <div className="incident-time mono">
          длится {formatDuration(incident.durationMinutes)} · с {formatDateTime(incident.startedAt)}
          {incident.occurrences > 1 && <> · подтверждений: {incident.occurrences}</>}
        </div>
        {incident.acknowledgedAt && (
          <div className="incident-ack">
            <Check size={13} /> принято в работу
            {incident.acknowledgedBy && <> · {incident.acknowledgedBy}</>}
          </div>
        )}
      </div>

      <div className="incident-actions">
        {!incident.acknowledgedAt && (
          <button className="btn btn-ghost btn-sm" onClick={onAcknowledge} disabled={busy}>
            Принять
          </button>
        )}
        <button className="btn btn-sm" onClick={onResolve} disabled={busy}>
          Устранено
        </button>
      </div>
    </div>
  );
}

/* ── История ─────────────────────────────────────────────── */

function HistoryTable({ rows }: { rows: Incident[] }) {
  if (rows.length === 0) {
    return <div className="empty">За последние 30 дней инцидентов не было.</div>;
  }
  return (
    <div className="table-wrap card">
      <table className="tx-table">
        <thead>
          <tr>
            <th>Начало</th>
            <th>Киоск</th>
            <th>Проблема</th>
            <th className="num">Длительность</th>
            <th>Статус</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((i) => (
            <tr key={i.id}>
              <td className="mono dim">{formatDateTime(i.startedAt)}</td>
              <td><b>{i.kioskName}</b></td>
              <td>
                <span className={"sev-chip " + (i.severity === "DOWN" ? "down" : "warn")}>
                  {i.title}
                </span>
              </td>
              <td className="num mono">{formatDuration(i.durationMinutes)}</td>
              <td className={i.resolvedAt ? "dim" : "strong"}>
                {i.resolvedAt ? "устранён" : "открыт"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function TopTypes({ summary }: { summary: IncidentSummary }) {
  const max = Math.max(...summary.topTypes.map((t) => t.count), 1);
  return (
    <div className="card chart-card">
      <div className="weekday-rows">
        {summary.topTypes.map((t) => (
          <div className="weekday-row" key={t.incidentType}>
            <span className="type-label">
              {INCIDENT_ICON[t.incidentType]} {t.title}
            </span>
            <div className="weekday-bar">
              <span style={{ width: `${(t.count / max) * 100}%` }} />
            </div>
            <span className="weekday-num mono">{t.count}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
