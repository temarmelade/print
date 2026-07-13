package com.printkiosk.server.service;

import com.printkiosk.server.domain.PrintJobEntity;
import com.printkiosk.server.domain.PrintJobRepository;
import com.printkiosk.shared.api.PrintJobStatus;
import com.printkiosk.shared.api.dto.TransactionDto;
import com.printkiosk.shared.api.dto.TransactionPageDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение транзакций для админ-панели: фильтры, страницы, сводка.
 *
 * <p>Фильтры собираются через Criteria API, а НЕ через JPQL вида
 * «:param IS NULL OR ...». Причина: при null-параметре драйвер отправляет
 * нетипизированный NULL, и PostgreSQL падает с «could not determine data type
 * of parameter $1». Criteria просто не добавляет предикат, если фильтр пуст.
 */
@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final String PAID = "PAID";

    private final PrintJobRepository jobs;
    private final EntityManager em;

    /** Нормализованный набор фильтров. */
    private record Filters(Instant from, Instant to, PrintJobStatus status,
                           String paymentStatus, String kioskId, String q) {}

    @Transactional(readOnly = true)
    public TransactionPageDto search(Instant from,
                                     Instant to,
                                     PrintJobStatus status,
                                     String paymentStatus,
                                     String kioskId,
                                     String q,
                                     int page,
                                     int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        Filters f = new Filters(from, to, status,
                blankToNull(paymentStatus), blankToNull(kioskId), blankToNull(q));

        Page<PrintJobEntity> result =
                jobs.findAll(specFor(f), PageRequest.of(safePage, safeSize));

        Summary summary = summarize(f);

        List<TransactionDto> items = result.getContent().stream()
                .map(TransactionService::toDto)
                .toList();

        return new TransactionPageDto(
                items,
                safePage,
                safeSize,
                result.getTotalElements(),
                result.getTotalPages(),
                summary.paidCount(),
                summary.revenueSom());
    }

    /** Киоски, встречавшиеся в заданиях — для выпадающего фильтра. */
    @Transactional(readOnly = true)
    public List<String> kiosks() {
        return jobs.findDistinctKioskIds();
    }

    // ════════════════════════════════════════════════════════════════
    //  Criteria
    // ════════════════════════════════════════════════════════════════

    private Specification<PrintJobEntity> specFor(Filters f) {
        return (root, query, cb) -> {
            // К files НЕ джойнимся: файл к этому моменту мог быть удалён по TTL.
            // Всё, что нужно для истории, лежит снимком в самой транзакции.
            Class<?> resultType = (query == null) ? null : query.getResultType();
            boolean isCount = (resultType == Long.class || resultType == long.class);
            if (!isCount && query != null) {
                query.orderBy(cb.desc(root.get("createdAt")));
            }
            return cb.and(predicates(f, cb, root).toArray(new Predicate[0]));
        };
    }

    private List<Predicate> predicates(Filters f, CriteriaBuilder cb,
                                       Root<PrintJobEntity> root) {
        List<Predicate> ps = new ArrayList<>();

        if (f.from() != null) {
            ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.from()));
        }
        if (f.to() != null) {
            ps.add(cb.lessThan(root.get("createdAt"), f.to()));
        }
        if (f.status() != null) {
            ps.add(cb.equal(root.get("status"), f.status()));
        }
        if (f.paymentStatus() != null) {
            ps.add(cb.equal(root.get("paymentStatus"), f.paymentStatus()));
        }
        if (f.kioskId() != null) {
            ps.add(cb.equal(root.get("kioskId"), f.kioskId()));
        }
        if (f.q() != null) {
            String like = "%" + f.q().toLowerCase() + "%";
            ps.add(cb.or(
                    cb.equal(root.get("pin"), f.q()),
                    cb.like(cb.lower(root.get("fileName")), like),
                    cb.equal(root.get("paymentId"), f.q())));
        }
        return ps;
    }

    private record Summary(long paidCount, long revenueSom) {}

    /** Сводка по ВСЕЙ выборке (не по странице), иначе «Итого» врало бы при листании. */
    private Summary summarize(Filters f) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<PrintJobEntity> root = cq.from(PrintJobEntity.class);

        // Выручка — только фактически оплаченное, а не выставленные счета.
        Expression<Integer> paidPrice = cb.<Integer>selectCase()
                .when(cb.equal(root.get("paymentStatus"), PAID), root.<Integer>get("priceSom"))
                .otherwise(0);
        Expression<Integer> paidOne = cb.<Integer>selectCase()
                .when(cb.equal(root.get("paymentStatus"), PAID), 1)
                .otherwise(0);

        cq.multiselect(cb.coalesce(cb.sum(paidOne), 0), cb.coalesce(cb.sum(paidPrice), 0))
          .where(cb.and(predicates(f, cb, root).toArray(new Predicate[0])));

        Object[] row = em.createQuery(cq).getSingleResult();
        long paidCount = ((Number) row[0]).longValue();
        long revenue = ((Number) row[1]).longValue();
        return new Summary(paidCount, revenue);
    }

    // ════════════════════════════════════════════════════════════════
    //  Внутреннее
    // ════════════════════════════════════════════════════════════════

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static TransactionDto toDto(PrintJobEntity j) {
        // Читаем снимок, а не j.getFile(): файла уже может не быть.
        return new TransactionDto(
                j.getId(),
                j.getPin(),
                j.getFileName(),
                j.getPageCount(),
                j.getCopies(),
                j.getColorMode(),
                j.getPriceSom(),
                j.getStatus(),
                j.getPaymentStatus(),
                j.getPaymentId(),
                j.getKioskId(),
                j.getCreatedAt(),
                j.getPaidAt(),
                j.getCompletedAt());
    }
}
