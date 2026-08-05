package com.printkiosk.shared.api.dto;

import com.printkiosk.shared.api.OperationType;
import com.printkiosk.shared.api.UploadSource;

import java.util.List;

/**
 * Аналитика по услугам за период.
 *
 * <p>Считается по {@code print_jobs} — единственной таблице, которая переживает
 * удаление файлов по TTL (см. V8). Источник загрузки и формат документа тоже
 * берутся из снимка в самой транзакции (V12), а не из {@code files}: иначе
 * статистика исчезала бы вместе с файлом через несколько минут после печати.
 *
 * <p>Денежные поля — {@code Long} и приходят {@code null} для нефинансовых
 * ролей: null ≠ 0, чтобы UI показал «—», а не ноль выручки.
 */
public record AnalyticsDto(
        int periodDays,

        /** Разбивка по типам операций: доля услуг, объём, средние. */
        List<OperationStatDto> byOperation,

        /** Каналы загрузки: Telegram, сайт, сканер киоска. */
        List<SourceStatDto> bySource,

        /** Форматы документов: pdf, docx, jpg… */
        List<FormatStatDto> byFormat,

        /** Суточная динамика по услугам (для стека/линий). */
        List<OperationDailyDto> dailyByOperation,

        /** Нагрузка по часам суток — когда идёт спрос. */
        List<HourlyPointDto> hourly,

        /** Нагрузка по дням недели (1 = понедельник … 7 = воскресенье). */
        List<WeekdayPointDto> weekday,

        /** Рейтинг киосков: объём, конверсия, средний чек. */
        List<KioskPerformanceDto> byKiosk,

        /** Распределение заказов по объёму (1 / 2–5 / 6–10 / 10+ страниц). */
        List<VolumeBucketDto> volumeBuckets,

        /** Воронка: создан → оплата выставлена → оплачен → завершён. */
        FunnelDto funnel,

        /** Сводные средние по периоду. */
        AveragesDto averages
) {

    /** Строка разбивки по услуге. */
    public record OperationStatDto(
            OperationType operationType,
            long jobs,              // всего заданий этого типа
            long paidJobs,          // из них оплачено
            long pages,             // страниц с учётом копий
            Long revenueSom,        // null для нефинансовых ролей
            double sharePercent,    // доля от всех оплаченных заданий
            double conversionPercent, // paidJobs / jobs
            double avgPages,        // среднее страниц на оплаченное задание
            double avgCopies
    ) {}

    /** Точка суточного ряда в разрезе услуги. */
    public record OperationDailyDto(
            String date,            // YYYY-MM-DD
            OperationType operationType,
            long paidJobs,
            Long revenueSom
    ) {}

    /**
     * Канал загрузки документа. Доступен только для заданий, созданных после
     * V12: у более старых записей источник не сохранялся и попадает в UNKNOWN.
     */
    public record SourceStatDto(
            UploadSource source,
            long jobs,
            long paidJobs,
            long pages,
            Long revenueSom,
            double sharePercent,
            double conversionPercent,   // доля дошедших до оплаты
            double avgPages
    ) {}

    /** Формат документа (расширение файла). */
    public record FormatStatDto(
            String extension,           // «pdf», «docx»; null → «без расширения»
            long jobs,
            long paidJobs,
            long pages,
            double sharePercent,
            double conversionPercent
    ) {}

    /** Час суток (0–23) по местному времени киоска. */
    public record HourlyPointDto(
            int hour,
            long jobs,
            long paidJobs,
            Long revenueSom
    ) {}

    /** День недели: 1 = понедельник … 7 = воскресенье. */
    public record WeekdayPointDto(
            int weekday,
            long paidJobs,
            Long revenueSom
    ) {}

    /** Показатели киоска. */
    public record KioskPerformanceDto(
            String kioskId,
            long jobs,
            long paidJobs,
            long pages,
            Long revenueSom,
            double conversionPercent,
            Long avgCheckSom,           // средний чек, null если деньги скрыты
            OperationType topOperation  // самая востребованная услуга на точке
    ) {}

    /** Корзина объёма заказа. */
    public record VolumeBucketDto(
            String label,   // «1», «2–5», «6–10», «10+»
            long jobs,
            double sharePercent
    ) {}

    /**
     * Воронка заказа. Строится по тому, что достоверно есть в print_jobs:
     * задание создано → выставлена оплата (есть payment_id) → оплачено
     * (payment_status = PAID) → завершено (COMPLETED).
     *
     * <p>Шаги «документ загружен» и «PIN введён» сюда не входят: файлы
     * удаляются по TTL, и их история не сохраняется.
     */
    public record FunnelDto(
            long created,
            long paymentCreated,
            long paid,
            long completed,
            long failed,
            long expired,
            double paymentRatePercent,   // paymentCreated / created
            double paidRatePercent,      // paid / paymentCreated
            double completionRatePercent,// completed / paid
            Long lostRevenueSom          // сумма выставленных, но неоплаченных
    ) {}

    /** Средние по всей выборке за период. */
    public record AveragesDto(
            double avgPagesPerJob,
            double avgCopiesPerJob,
            Long avgCheckSom,
            long maxOrderPages,
            double avgMinutesToPayment  // от создания задания до оплаты
    ) {}
}
