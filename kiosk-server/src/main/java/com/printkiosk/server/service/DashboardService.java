package com.printkiosk.server.service;

import com.printkiosk.shared.api.dto.DashboardDto;
import com.printkiosk.shared.api.dto.DashboardDto.DailyPointDto;
import com.printkiosk.shared.api.dto.DashboardDto.KioskStatDto;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Агрегаты для дашборда. Считаются по print_jobs — единственному источнику,
 * который переживает удаление файлов (см. V8).
 *
 * <p>Ключевые правила метрик:
 * <ul>
 *   <li>Выручка — только фактически оплаченное ({@code payment_status = 'PAID'}),
 *       и по времени оплаты ({@code paid_at}), а не создания задания.</li>
 *   <li>Страницы — {@code printed_pages * copies}: пользователь мог выбрать
 *       часть файла, и полный page_count завышал бы объём (см. V9).</li>
 *   <li>Дни считаются в часовом поясе киоска, иначе «сегодня» будет съезжать.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Бишкек: сутки считаем по местному времени, а не по UTC. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Bishkek");

    private final EntityManager em;

    @Transactional(readOnly = true)
    public DashboardDto summary(int days, boolean includeRevenue) {
        int period = Math.min(Math.max(days, 1), 90);

        LocalDate today = LocalDate.now(ZONE);
        Instant todayStart = today.atStartOfDay(ZONE).toInstant();
        Instant tomorrow = today.plusDays(1).atStartOfDay(ZONE).toInstant();
        Instant periodStart = today.minusDays(period - 1L).atStartOfDay(ZONE).toInstant();

        Totals todayT = totals(todayStart, tomorrow);
        Totals periodT = totals(periodStart, tomorrow);
        long failed = failedJobs(periodStart, tomorrow);

        return new DashboardDto(
                period,
                money(todayT.revenue(), includeRevenue),
                todayT.paidJobs(),
                todayT.pages(),
                money(periodT.revenue(), includeRevenue),
                periodT.paidJobs(),
                periodT.pages(),
                failed,
                daily(periodStart, tomorrow, period, today, includeRevenue),
                byKiosk(periodStart, tomorrow, includeRevenue));
    }

    // ════════════════════════════════════════════════════════════════
    //  Агрегаты
    // ════════════════════════════════════════════════════════════════

    private record Totals(long revenue, long paidJobs, long pages) {}

    private Totals totals(Instant from, Instant to) {
        Object[] row = (Object[]) em.createNativeQuery("""
                SELECT COALESCE(SUM(price_som), 0),
                       COUNT(*),
                       COALESCE(SUM(printed_pages * copies), 0)
                  FROM print_jobs
                 WHERE payment_status = 'PAID'
                   AND paid_at >= :from AND paid_at < :to
                """)
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .getSingleResult();

        return new Totals(num(row[0]), num(row[1]), num(row[2]));
    }

    /** Неудачные задания за период — «требуют внимания». */
    private long failedJobs(Instant from, Instant to) {
        Object row = em.createNativeQuery("""
                SELECT COUNT(*) FROM print_jobs
                 WHERE status = 'FAILED'
                   AND created_at >= :from AND created_at < :to
                """)
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .getSingleResult();
        return num(row);
    }

    /** Дневной ряд. Дни без оплат заполняем нулями — иначе график «схлопнется». */
    @SuppressWarnings("unchecked")
    private List<DailyPointDto> daily(Instant from, Instant to, int period,
                                      LocalDate today, boolean includeRevenue) {

        List<Object[]> rows = em.createNativeQuery("""
                SELECT CAST(paid_at AT TIME ZONE 'Asia/Bishkek' AS date) AS d,
                       COALESCE(SUM(price_som), 0),
                       COUNT(*)
                  FROM print_jobs
                 WHERE payment_status = 'PAID'
                   AND paid_at >= :from AND paid_at < :to
                 GROUP BY d
                 ORDER BY d
                """)
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .getResultList();

        // Индексируем результат по дате, затем разворачиваем полный ряд дней.
        var byDate = new java.util.HashMap<LocalDate, long[]>();
        for (Object[] r : rows) {
            LocalDate d = ((java.sql.Date) r[0]).toLocalDate();
            byDate.put(d, new long[]{ num(r[1]), num(r[2]) });
        }

        List<DailyPointDto> out = new ArrayList<>(period);
        for (int i = period - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            long[] v = byDate.getOrDefault(d, new long[]{0, 0});
            out.add(new DailyPointDto(d.toString(), money(v[0], includeRevenue), v[1]));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<KioskStatDto> byKiosk(Instant from, Instant to, boolean includeRevenue) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT COALESCE(kiosk_id, 'не указан'),
                       COALESCE(SUM(price_som), 0),
                       COUNT(*),
                       COALESCE(SUM(printed_pages * copies), 0)
                  FROM print_jobs
                 WHERE payment_status = 'PAID'
                   AND paid_at >= :from AND paid_at < :to
                 GROUP BY kiosk_id
                 ORDER BY 2 DESC
                """)
                .setParameter("from", Timestamp.from(from))
                .setParameter("to", Timestamp.from(to))
                .getResultList();

        List<KioskStatDto> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new KioskStatDto((String) r[0], money(num(r[1]), includeRevenue),
                    num(r[2]), num(r[3])));
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутреннее
    // ════════════════════════════════════════════════════════════════

    /** Скрываем деньги от нефинансовых ролей: null ≠ 0. */
    private static Long money(long value, boolean allowed) {
        return allowed ? value : null;
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
