import type { ReactNode } from "react";
import "./pages.css";

function PageHead({ title, subtitle, phase }: { title: string; subtitle: string; phase: string }) {
  return (
    <div className="page-head">
      <span className="phase-tag">{phase}</span>
      <h2>{title}</h2>
      <p>{subtitle}</p>
    </div>
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

/* ── Дашборд: демонстрирует role-aware контент ──
   Выручку видит только владелец; техник видит операционные метрики. */
export function AlertsPage() {
  return (
    <>
      <PageHead phase="Фаза 3" title="Инциденты" subtitle="Проблемы, требующие внимания, и тикеты техникам." />
      <Empty>
        Лента инцидентов и тикеты появятся после телеметрии. Сюда же подключим уведомления в
        <strong> Telegram</strong>.
      </Empty>
    </>
  );
}

export function AnalyticsPage() {
  return (
    <>
      <PageHead phase="Фаза 3" title="Аналитика" subtitle="Локации, услуги, нагрузка по времени, прогнозы." />
      <Empty>
        Сравнение точек, разбивка по услугам, тепловая карта нагрузки и предиктивный расход
        расходников. Строится на накопленной истории заданий и телеметрии.
      </Empty>
    </>
  );
}

