package com.printkiosk.server.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileRepository extends JpaRepository<FileEntity, UUID> {

    /** Используется генератором PIN для предварительной проверки. */
    @Query("""
           SELECT (COUNT(f) > 0)
             FROM FileEntity f
            WHERE f.code = :code
              AND f.expiresAt > :now
           """)
    boolean existsActiveByCode(@Param("code") String code, @Param("now") Instant now);

    /** Используется киоском через GET /api/files/verify. */
    @Query("""
           SELECT f
             FROM FileEntity f
            WHERE f.code = :code
              AND f.expiresAt > :now
              AND f.consumedAt IS NULL
           """)
    Optional<FileEntity> findActiveByCode(@Param("code") String code,
                                          @Param("now")  Instant now);

    /** Снимок просроченных записей для cleanup-джоба. */
    @Query("SELECT f FROM FileEntity f WHERE f.expiresAt < :threshold")
    List<FileEntity> findExpiredBefore(@Param("threshold") Instant threshold);

    @Modifying
    @Query("DELETE FROM FileEntity f WHERE f.expiresAt < :threshold")
    int deleteExpiredBefore(@Param("threshold") Instant threshold);

    /** Атомарный mark-as-consumed: возвращает 1, если успели первыми. */
    @Modifying
    @Query("""
           UPDATE FileEntity f
              SET f.consumedAt = :now
            WHERE f.id = :id
              AND f.consumedAt IS NULL
           """)
    int markConsumed(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Атомарно закрепляет PIN за киоском (hold). Берёт hold, ТОЛЬКО если PIN:
     *   • никем не удерживается (holderKioskId IS NULL), ИЛИ
     *   • держался кем-то, но hold истёк (holderExpiresAt <= now).
     * <p>
     * Намеренно НЕ продлеваем hold тому же киоску: после первого успешного
     * verify PIN считается «использованным» и недоступен до конца TTL даже
     * тому терминалу, который его ввёл (юзер не должен повторно зайти по
     * тому же коду, даже если печать не состоялась).
     * <p>
     * Условия активности файла (не истёк, не consumed) проверяются здесь же,
     * чтобы hold нельзя было взять на мёртвую запись.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE FileEntity f
              SET f.holderKioskId   = :kioskId,
                  f.holderExpiresAt = :holdUntil
            WHERE f.code = :code
              AND f.expiresAt > :now
              AND f.consumedAt IS NULL
              AND (
                    f.holderKioskId IS NULL
                 OR f.holderExpiresAt <= :now
              )
           """)
    int acquireHold(@Param("code")      String  code,
                    @Param("kioskId")   String  kioskId,
                    @Param("now")       Instant now,
                    @Param("holdUntil") Instant holdUntil);
}