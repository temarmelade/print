package com.printkiosk.server.service;

import com.printkiosk.shared.api.OperationType;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.shared.api.dto.AnalyticsDto;
import com.printkiosk.shared.api.dto.AnalyticsDto.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Аналитика по услугам. Как и {@link DashboardService}, считается ТОЛЬКО по
 * {@code print_jobs}: это единственная таблица, переживающая удаление файлов
 * по TTL (V8). Источник загрузки и формат документа берутся из снимка в самой
 * транзакции (V12) — у заданий, созданных до этой миграции, источник
 * восстановлен по типу операции либо помечен UNKNOWN.
 *
 * <p>Правила метрик наследуются от дашборда, чтобы цифры сходились:
 * <ul>
 *   <li>выручка — только {@code payment_status = 'PAID'}, по {@code paid_at};</li>
 *   <li>страницы — {@code printed_pages * copies} (пользователь мог выбрать
 *       часть документа, см. V9);</li>
 *   <li>сутки и часы — в часовом поясе киоска, а не в UTC.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Bishkek");
    private static final String TZ = "Asia/Bishkek";
    private static final int MAX_DAYS = 365;

    private final EntityManager em;

    @Transactional(readOnly = true)
    public AnalyticsDto analytics(int days, boolean includeRevenue) {
        int period = Math.min(Math.max(days, 1), MAX_DAYS);

        LocalDate today = LocalDate.now(ZONE);
        Instant to = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        Instant from = today.minusDays(period - 1L).atStartOfDay(ZONE).toInstant();

        return new AnalyticsDto(
                period,
                byOperation(from, to, includeRevenue),
                bySource(from, to, includeRevenue),
                byFormat(from, to),
                dailyByOperation(from, to, includeRevenue),
                hourly(from, to, includeRevenue),
                weekday(from, to, includeRevenue),
                byKiosk(from, to, includeRevenue),
                volumeBuckets(from, to),
                funnel(from, to, includeRevenue),
                averages(from, to, includeRevenue));
    }

    // ════════════════════════════════════════════════════════════════
    //  Услуги
    // ════════════════════════════════════════════════════════════════

    /**
     * Разбивка по типу операции. Заметьте разные окна времени: количество
     * созданных заданий считаем по created_at (интересует спрос), а оплаты и
     * выручку — по paid_at (интересуют деньги). Поэтому два подзапроса, а не
     * одна группировка.
     */
    @SuppressWarnings("unchecked")
    private List<OperationStatDto> byOperation(Instant from, Instant to, boolean money) {
        List<Object[]> rows = nativeQuery("""
                SELECT o.operation_type,
                       COALESCE(c.jobs, 0),
                       COALESCE(p.paid_jobs, 0),
                       COALESCE(p.pages, 0),
                       COALESCE(p.revenue, 0),
                       COALESCE(p.copies_sum, 0)
                  FROM (SELECT DISTINCT operation_type FROM print_jobs
                         WHERE (created_at >= :from AND created_at < :to)
                            OR (paid_at   >= :from AND paid_at   < :to)) o
                  LEFT JOIN (SELECT operation_type, COUNT(*) AS jobs
                               FROM print_jobs
                              WHERE created_at >= :from AND created_at < :to
                              GROUP BY operation_type) c
                         ON c.operation_type = o.operation_type
                  LEFT JOIN (SELECT operation_type,
                                    COUNT(*) AS paid_jobs,
                                    SUM(printed_pages * copies) AS pages,
                                    SUM(price_som) AS revenue,
                                    SUM(copies) AS copies_sum
                               FROM print_jobs
                              WHERE payment_status = 'PAID'
                                AND paid_at >= :from AND paid_at < :to
                              GROUP BY operation_type) p
                         ON p.operation_type = o.operation_type
                """, from, to).getResultList();

        long totalPaid = rows.stream().mapToLong(r -> num(r[2])).sum();

        List<OperationStatDto> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            OperationType type = parseOperation(r[0]);
            if (type == null) continue;

            long jobs = num(r[1]);
            long paidJobs = num(r[2]);
            long pages = num(r[3]);
            long revenue = num(r[4]);
            long copiesSum = num(r[5]);

            out.add(new OperationStatDto(
                    type, jobs, paidJobs, pages, money(revenue, money),
                    percent(paidJobs, totalPaid),
                    percent(paidJobs, jobs),
                    ratio(pages, paidJobs),
                    ratio(copiesSum, paidJobs)));
        }
        // Самая массовая услуга — первой.
        out.sort((a, b) -> Long.compare(b.paidJobs(), a.paidJobs()));
        return out;
    }

    /** Суточная динамика по услугам — для наложенных рядов на графике. */
    @SuppressWarnings("unchecked")
    private List<OperationDailyDto> dailyByOperation(Instant from, Instant to, boolean money) {
        List<Object[]> rows = nativeQuery("""
                SELECT CAST(paid_at AT TIME ZONE :tz AS date) AS d,
                       operation_type,
                       COUNT(*),
                       COALESCE(SUM(price_som), 0)
                  FROM print_jobs
                 WHERE payment_status = 'PAID'
                   AND paid_at >= :from AND paid_at < :to
                 GROUP BY d, operation_type
                 ORDER BY d
                """, from, to).setParameter("tz", TZ).getResultList();

        List<OperationDailyDto> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            OperationType type = parseOperation(r[1]);
            if (type == null) continue;
            out.add(new OperationDailyDto(
                    ((java.sql.Date) r[0]).toLocalDate().toString(),
                    type, num(r[2]), money(num(r[3]), money)));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    //  Каналы и форматы
    // ════════════════════════════════════════════════════════════════

    /**
     * Разбивка по каналу загрузки. Задания создаются по created_at, оплаты —
     * по paid_at, поэтому здесь одна группировка с FILTER, а не два окна:
     * конверсия канала должна считаться по одной и той же когорте заданий.
     */
    @SuppressWarnings("unchecked")
    private List<SourceStatDto> bySource(Instant from, Instant to, boolean money) {
        List<Object[]> rows = nativeQuery("""
                SELECT upload_source,
                       COUNT(*),
                       COUNT(*) FILTER (WHERE payment_status = 'PAID'),
                       COALESCE(SUM(printed_pages * copies)
                                FILTER (WHERE payment_status = 'PAID'), 0),
                       COALESCE(SUM(price_som) FILTER (WHERE payment_status = 'PAID'), 0)
                  FROM print_jobs
                 WHERE created_at >= :from AND created_at < :to
                 GROUP BY upload_source
                 ORDER BY 2 DESC
                """, from, to).getResultList();

        long totalJobs = rows.stream().mapToLong(r -> num(r[1])).sum();

        List<SourceStatDto> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            UploadSource source = parseSource(r[0]);
            if (source == null) continue;

            long jobs = num(r[1]);
            long paidJobs = num(r[2]);
            long pages = num(r[3]);

            out.add(new SourceStatDto(
                    source, jobs, paidJobs, pages, money(num(r[4]), money),
                    percent(jobs, totalJobs),
                    percent(paidJobs, jobs),
                    ratio(pages, paidJobs)));
        }
        return out;
    }

    /**
     * Разбивка по формату документа. Сканы собственного производства сюда не
     * попадают: у них формат всегда PDF и он ничего не говорит о поведении
     * пользователей — интересны именно загруженные документы.
     */
    @SuppressWarnings("unchecked")
    private List<FormatStatDto> byFormat(Instant from, Instant to) {
        List<Object[]> rows = nativeQuery("""
                SELECT COALESCE(file_extension, 'без расширения'),
                       COUNT(*),
                       COUNT(*) FILTER (WHERE payment_status = 'PAID'),
                       COALESCE(SUM(printed_pages * copies)
                                FILTER (WHERE payment_status = 'PAID'), 0)
                  FROM print_jobs
                 WHERE created_at >= :from AND created_at < :to
                   AND upload_source IN ('TELEGRAM', 'WEBSITE')
                 GROUP BY 1
                 ORDER BY 2 DESC
                """, from, to).getResultList();

        long totalJobs = rows.stream().mapToLong(r -> num(r[1])).sum();

        List<FormatStatDto> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            long jobs = num(r[1]);
            long paidJobs = num(r[2]);
            out.add(new FormatStatDto(
                    (String) r[0], jobs, paidJobs, num(r[3]),
                    percent(jobs, totalJobs),
                    percent(paidJobs, jobs)));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    //  Время
    // ════════════════════════════════════════════════════════════════

    /** Нагрузка по часам. Ряд разворачиваем на все 24 часа — иначе провалы «схлопнутся». */
    @SuppressWarnings("unchecked")
    private List<HourlyPointDto> hourly(Instant from, Instant to, boolean money) {
        List<Object[]> rows = nativeQuery("""
                SELECT CAST(EXTRACT(HOUR FROM created_at AT TIME ZONE :tz) AS int) AS h,
                       COUNT(*),
                       COUNT(*) FILTER (WHERE payment_status = 'PAID'),
                       COALESCE(SUM(price_som) FILTER (WHERE payment_status = 'PAID'), 0)
                  FROM print_jobs
                 WHERE created_at >= :from AND created_at < :to
                 GROUP BY h
                """, from, to).setParameter("tz", TZ).getResultList();

        Map<Integer, long[]> byHour = new LinkedHashMap<>();
        for (Object[] r : rows) {
            byHour.put(((Number) r[0]).intValue(),
                    new long[]{ num(r[1]), num(r[2]), num(r[3]) });
        }

        List<HourlyPointDto> out = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            long[] v = byHour.getOrDefault(h, new long[]{0, 0, 0});
            out.add(new HourlyPointDto(h, v[0], v[1], money(v[2], money)));
        }
        return out;
    }

    /** Дни недели. ISODOW: 1 = понедельник … 7 = воскресенье. */
    @SuppressWarnings("unchecked")
    private List<WeekdayPointDto> weekday(Instant from, Instant to, boolean money) {
        List<Object[]> rows = nativeQuery("""
                SELECT CAST(EXTRACT(ISODOW FROM paid_at AT TIME ZONE :tz) AS int) AS dow,
                       COUNT(*),
                       COALESCE(SUM(price_som), 0)
                  FROM print_jobs
                 WHERE payment_status = 'PAID'
                   AND paid_at >= :from AND paid_at < :to
                 GROUP BY dow
                """, from, to).setParameter("tz", TZ).getResultList();

        Map<Integer, long[]> byDow = new LinkedHashMap<>();
        for (Object[] r : rows) {
            byDow.put(((Number) r[0]).intValue(), new long[]{ num(r[1]), num(r[2]) });
        }

        List<WeekdayPointDto> out = new ArrayList<>(7);
        for (int d = 1; d <= 7; d++) {
            long[] v = byDow.getOrDefault(d, new long[]{0, 0});
            out.add(new WeekdayPointDto(d, v[0], money(v[1], money)));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    //  Киоски
    // ════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<KioskPerformanceDto> byKiosk(Instant from, Instant to, boolean money) {
        List<Object[]> rows = nativeQuery("""
                SELECT COALESCE(kiosk_id, 'не указан') AS k,
                       COUNT(*),
                       COUNT(*) FILTER (WHERE payment_status = 'PAID'),
                       COALESCE(SUM((printed_pages * copies))
                                FILTER (WHERE payment_status = 'PAID'), 0),
                       COALESCE(SUM(price_som) FILTER (WHERE payment_status = 'PAID'), 0)
                  FROM print_jobs
                 WHERE created_at >= :from AND created_at < :to
                 GROUP BY k
                 ORDER BY 3 DESC
                """, from, to).getResultList();

        Map<String, OperationType> top = topOperationPerKiosk(from, to);

        List<KioskPerformanceDto> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String kioskId = (String) r[0];
            long jobs = num(r[1]);
            long paidJobs = num(r[2]);
            long pages = num(r[3]);
            long revenue = num(r[4]);

            Long avgCheck = money && paidJobs > 0 ? Math.round((double) revenue / paidJobs) : null;

            out.add(new KioskPerformanceDto(
                    kioskId, jobs, paidJobs, pages, money(revenue, money),
                    percent(paidJobs, jobs), avgCheck, top.get(kioskId)));
        }
        return out;
    }

    /** Самая частая услуга на каждой точке — DISTINCT ON берёт лидера группы. */
    @SuppressWarnings("unchecked")
    private Map<String, OperationType> topOperationPerKiosk(Instant from, Instant to) {
        List<Object[]> rows = nativeQuery("""
                SELECT DISTINCT ON (k) k, operation_type
                  FROM (SELECT COALESCE(kiosk_id, 'не указан') AS k,
                               operation_type,
                               COUNT(*) AS n
                          FROM print_jobs
                         WHERE payment_status = 'PAID'
                           AND paid_at >= :from AND paid_at < :to
                         GROUP BY k, operation_type) t
                 ORDER BY k, n DESC
                """, from, to).getResultList();

        Map<String, OperationType> out = new LinkedHashMap<>();
        for (Object[] r : rows) {
            OperationType type = parseOperation(r[1]);
            if (type != null) out.put((String) r[0], type);
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    //  Объём заказов
    // ════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private List<VolumeBucketDto> volumeBuckets(Instant from, Instant to) {
        List<Object[]> rows = nativeQuery("""
                SELECT CASE WHEN printed_pages <= 1 THEN '1'
                            WHEN printed_pages <= 5 THEN '2-5'
                            WHEN printed_pages <= 10 THEN '6-10'
                            ELSE '10+' END AS bucket,
                       COUNT(*)
                  FROM print_jobs
                 WHERE payment_status = 'PAID'
                   AND paid_at >= :from AND paid_at < :to
                 GROUP BY bucket
                """, from, to).getResultList();

        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] r : rows) counts.put((String) r[0], num(r[1]));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();

        // Порядок фиксируем сами: SQL вернул бы корзины как попало.
        List<VolumeBucketDto> out = new ArrayList<>(4);
        for (String label : List.of("1", "2-5", "6-10", "10+")) {
            long n = counts.getOrDefault(label, 0L);
            out.add(new VolumeBucketDto(label, n, percent(n, total)));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    //  Воронка и средние
    // ════════════════════════════════════════════════════════════════

    private FunnelDto funnel(Instant from, Instant to, boolean money) {
        Object[] r = (Object[]) nativeQuery("""
                SELECT COUNT(*),
                       COUNT(*) FILTER (WHERE payment_id IS NOT NULL),
                       COUNT(*) FILTER (WHERE payment_status = 'PAID'),
                       COUNT(*) FILTER (WHERE status = 'COMPLETED'),
                       COUNT(*) FILTER (WHERE status = 'FAILED'),
                       COUNT(*) FILTER (WHERE status = 'EXPIRED'),
                       COALESCE(SUM(price_som) FILTER (
                           WHERE payment_id IS NOT NULL
                             AND (payment_status IS NULL OR payment_status <> 'PAID')), 0)
                  FROM print_jobs
                 WHERE created_at >= :from AND created_at < :to
                """, from, to).getSingleResult();

        long created = num(r[0]);
        long paymentCreated = num(r[1]);
        long paid = num(r[2]);
        long completed = num(r[3]);

        return new FunnelDto(
                created, paymentCreated, paid, completed, num(r[4]), num(r[5]),
                percent(paymentCreated, created),
                percent(paid, paymentCreated),
                percent(completed, paid),
                money(num(r[6]), money));
    }

    private AveragesDto averages(Instant from, Instant to, boolean money) {
        Object[] r = (Object[]) nativeQuery("""
                SELECT COALESCE(AVG(printed_pages), 0),
                       COALESCE(AVG(copies), 0),
                       COALESCE(AVG(price_som), 0),
                       COALESCE(MAX(printed_pages * copies), 0),
                       COALESCE(AVG(EXTRACT(EPOCH FROM (paid_at - created_at)) / 60.0), 0)
                  FROM print_jobs
                 WHERE payment_status = 'PAID'
                   AND paid_at >= :from AND paid_at < :to
                """, from, to).getSingleResult();

        Long avgCheck = money ? Math.round(dbl(r[2])) : null;
        return new AveragesDto(
                round1(dbl(r[0])), round1(dbl(r[1])), avgCheck,
                num(r[3]), round1(dbl(r[4])));
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутреннее
    // ════════════════════════════════════════════════════════════════

    private Query nativeQuery(String sql, Instant from, Instant to) {
        return em.createNativeQuery(sql)
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to));
    }

    /**
     * Тип операции из БД. Значение может быть неизвестно текущему коду
     * (например, после отката версии) — такие строки пропускаем, а не роняем
     * весь отчёт.
     */
    private static OperationType parseOperation(Object raw) {
        if (raw == null) return null;
        try {
            return OperationType.valueOf(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Канал загрузки из БД; неизвестное значение не роняет отчёт. */
    private static UploadSource parseSource(Object raw) {
        if (raw == null) return null;
        try {
            return UploadSource.valueOf(raw.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Скрываем деньги от нефинансовых ролей: null ≠ 0. */
    private static Long money(long value, boolean allowed) {
        return allowed ? value : null;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static double dbl(Object o) {
        return o == null ? 0d : ((Number) o).doubleValue();
    }

    private static double percent(long part, long total) {
        return total <= 0 ? 0d : round1(part * 100d / total);
    }

    private static double ratio(long part, long total) {
        return total <= 0 ? 0d : round1((double) part / total);
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }
}
