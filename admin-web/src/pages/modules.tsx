import type { ReactNode } from "react";
import { RoleGate } from "../components/RoleGate.tsx";
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
export function DashboardPage() {
  return (
    <>
      <PageHead phase="Фаза 1" title="Дашборд" subtitle="Сводка по сети за сегодня." />
      <div className="kpi-grid">
        <RoleGate allow={["OWNER"]}>
          <div className="card kpi">
            <div className="kpi-label">Выручка сегодня</div>
            <div className="kpi-value">— <small>сом</small></div>
            <div className="kpi-sub">подключим к транзакциям</div>
          </div>
        </RoleGate>
        <div className="card kpi">
          <div className="kpi-label">Напечатано страниц</div>
          <div className="kpi-value">—</div>
          <div className="kpi-sub">за сегодня</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Киосков в сети</div>
          <div className="kpi-value">— <small>/ —</small></div>
          <div className="kpi-sub">онлайн / всего</div>
        </div>
        <div className="card kpi">
          <div className="kpi-label">Требуют внимания</div>
          <div className="kpi-value">—</div>
          <div className="kpi-sub">инциденты</div>
        </div>
      </div>
      <Empty>
        Плитки заполнятся данными на следующем шаге <strong>Фазы 1</strong>: выручка и страницы
        приедут из истории заданий, статус киосков — из телеметрии (Фаза 2). Карта сети добавится
        сюда же.
      </Empty>
    </>
  );
}

export function TerminalsPage() {
  return (
    <>
      <PageHead phase="Фаза 2" title="Терминалы" subtitle="Карточки киосков: статус, расходники, управление." />
      <Empty>
        Здесь появятся карточки киосков с прогресс-барами бумаги и тонера и кнопками удалённого
        управления. Нужна <strong>телеметрия</strong>: киоски должны слать heartbeat и уровни
        расходников — это первый шаг Фазы 2.
      </Empty>
    </>
  );
}

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

export function TransactionsPage() {
  return (
    <>
      <PageHead phase="Фаза 1" title="Транзакции" subtitle="Все оплаты с фильтрами и возвратами." />
      <Empty>
        Таблица оплат (дата, сумма, статус, метод) поднимется из заданий — это ближайший шаг
        <strong> Фазы 1</strong>. Кнопка возврата подключится, когда добавим refund в API
        эквайринга.
      </Empty>
    </>
  );
}

export function MediaPage() {
  return (
    <>
      <PageHead phase="Фаза 1" title="Реклама" subtitle="Баннеры и видео-заставки для экранов киосков." />
      <Empty>
        Загрузка креативов и назначение на всю сеть или конкретный ВУЗ. Частично уже есть на
        сервере (плейлист рекламы) — доделаем управление.
      </Empty>
    </>
  );
}

export function AccessPage() {
  return (
    <>
      <PageHead phase="Фаза 1" title="Доступы" subtitle="Сотрудники и их роли." />
      <Empty>
        Управление аккаунтами: владелец, техник, поддержка. Появится сразу после серверной
        авторизации — это оборотная сторона того же фундамента.
      </Empty>
    </>
  );
}
