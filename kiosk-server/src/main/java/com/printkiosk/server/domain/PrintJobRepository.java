package com.printkiosk.server.domain;

import com.printkiosk.shared.api.PrintJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrintJobRepository
        extends JpaRepository<PrintJobEntity, UUID>, JpaSpecificationExecutor<PrintJobEntity> {

    // ────────────────────────────────────────────────────────────────
    //  Поиск
    // ────────────────────────────────────────────────────────────────

    /**
     * Найти job вместе с файлом — нужно киоску при отображении
     * статуса (имя файла, ссылка на скачивание).
     */
    @Query("""
           SELECT j
             FROM PrintJobEntity j
             JOIN FETCH j.file
            WHERE j.id = :id
           """)
    Optional<PrintJobEntity> findByIdWithFile(@Param("id") UUID id);

    /**
     * Поиск по платёжному идентификатору — точка входа из Finik webhook.
     * Уникальный индекс делает это O(1) lookup.
     */
    Optional<PrintJobEntity> findByPaymentId(String paymentId);

    /**
     * Активный (не завершённый) job по PIN файла.
     * Используется для GET /api/payments/{pin}/status.
     */
    @Query("""
           SELECT j
             FROM PrintJobEntity j
             JOIN j.file f
            WHERE f.code   = :pin
              AND f.expiresAt > :now
              AND j.status NOT IN (
                  com.printkiosk.shared.api.PrintJobStatus.COMPLETED,
                  com.printkiosk.shared.api.PrintJobStatus.FAILED)
            ORDER BY j.createdAt DESC
           """)
    List<PrintJobEntity> findActiveByPin(@Param("pin") String pin,
                                         @Param("now") Instant now);

    default Optional<PrintJobEntity> findLatestActiveByPin(String pin, Instant now) {
        return findActiveByPin(pin, now).stream().findFirst();
    }

    // ────────────────────────────────────────────────────────────────
    //  Атомарные переходы состояний
    // ────────────────────────────────────────────────────────────────

    /**
     * Перевести job в PAYMENT_PENDING только если он был READY.
     * Защита от двойного вызова startPayment().
     */
    @Modifying
    @Query("""
           UPDATE PrintJobEntity j
              SET j.status        = com.printkiosk.shared.api.PrintJobStatus.PAYMENT_PENDING,
                  j.paymentId     = :paymentId,
                  j.paymentUrl    = :paymentUrl,
                  j.paymentStatus = 'PENDING'
            WHERE j.id     = :id
              AND j.status = com.printkiosk.shared.api.PrintJobStatus.READY
           """)
    int markPaymentPending(@Param("id") UUID id,
                           @Param("paymentId")  String paymentId,
                           @Param("paymentUrl") String paymentUrl);

    /**
     * Перевести в PAID атомарно по paymentId. Webhook от Finik может
     * прийти дважды (ретраи) — UPDATE с условием на статус делает
     * операцию идемпотентной: вторая обработка вернёт 0 изменённых строк.
     */
    @Modifying
    @Query("""
           UPDATE PrintJobEntity j
              SET j.status        = com.printkiosk.shared.api.PrintJobStatus.PAID,
                  j.paymentStatus = 'PAID',
                  j.paidAt        = :now
            WHERE j.paymentId = :paymentId
              AND j.status    = com.printkiosk.shared.api.PrintJobStatus.PAYMENT_PENDING
           """)
    int markPaidByPaymentId(@Param("paymentId") String paymentId,
                            @Param("now")       Instant now);

    /**
     * Универсальный переход состояния. Возвращает 1 при успехе,
     * 0 если текущий статус не соответствует ожиданию.
     */
    @Modifying
    @Query("""
           UPDATE PrintJobEntity j
              SET j.status = :to
            WHERE j.id     = :id
              AND j.status = :from
           """)
    int transition(@Param("id") UUID id,
                   @Param("from") PrintJobStatus from,
                   @Param("to")   PrintJobStatus to);

    @Modifying
    @Query("""
           UPDATE PrintJobEntity j
              SET j.status      = com.printkiosk.shared.api.PrintJobStatus.COMPLETED,
                  j.completedAt = :now
            WHERE j.id     = :id
              AND j.status = com.printkiosk.shared.api.PrintJobStatus.PRINTING
           """)
    int markCompleted(@Param("id") UUID id, @Param("now") Instant now);

    // ────────────────────────────────────────────────────────────────
    //  Cleanup
    // ────────────────────────────────────────────────────────────────

    /**
     * Job'ы, которые так и не оплатили в течение TTL файла.
     * Файл cleanup-джоб удалит сам (CASCADE снесёт строку),
     * но если файл по какой-то причине ещё жив (например,
     * пользователь повторно зашёл и подвис) — пометить FAILED.
     */
    @Modifying
    @Query("""
           UPDATE PrintJobEntity j
              SET j.status = com.printkiosk.shared.api.PrintJobStatus.FAILED
            WHERE j.status IN (
                  com.printkiosk.shared.api.PrintJobStatus.READY,
                  com.printkiosk.shared.api.PrintJobStatus.PAYMENT_PENDING)
              AND j.createdAt < :threshold
           """)
    int failStaleUnpaidJobs(@Param("threshold") Instant threshold);

    /**
     * Атомарно помечает PAYMENT_PENDING job (найденный по коду файла) как PAID.
     * Идемпотентно: повторный webhook не меняет ничего.
     */
    @Modifying
    @Query("""
       UPDATE PrintJobEntity j
          SET j.status        = com.printkiosk.shared.api.PrintJobStatus.PAID,
              j.paymentStatus = 'PAID',
              j.paidAt        = :now
        WHERE j.id IN (
              SELECT j2.id FROM PrintJobEntity j2
               WHERE j2.file.code = :pin
                 AND j2.status    = com.printkiosk.shared.api.PrintJobStatus.PAYMENT_PENDING
        )
       """)
    int markPaidByPin(@Param("pin") String pin, @Param("now") Instant now);

    @Modifying
    @Query("""
       UPDATE PrintJobEntity j
          SET j.status        = com.printkiosk.shared.api.PrintJobStatus.FAILED,
              j.paymentStatus = 'FAILED'
        WHERE j.id IN (
              SELECT j2.id FROM PrintJobEntity j2
               WHERE j2.file.code = :pin
                 AND j2.status    = com.printkiosk.shared.api.PrintJobStatus.PAYMENT_PENDING
        )
       """)
    int markFailedByPin(@Param("pin") String pin);

    // ────────────────────────────────────────────────────────────────
    //  Админ-панель: транзакции
    // ────────────────────────────────────────────────────────────────
    //
    // Выборка с необязательными фильтрами построена на Criteria API
    // (JpaSpecificationExecutor), а НЕ на JPQL вида ":x IS NULL OR ...".
    // Причина: при null-параметре драйвер шлёт нетипизированный NULL, и
    // PostgreSQL падает с «could not determine data type of parameter».
    // Criteria просто не добавляет предикат, если фильтр не задан.

    /** Список киосков, встречавшихся в заданиях — для выпадающего фильтра. */
    @Query("SELECT DISTINCT j.kioskId FROM PrintJobEntity j WHERE j.kioskId IS NOT NULL ORDER BY j.kioskId")
    List<String> findDistinctKioskIds();
}