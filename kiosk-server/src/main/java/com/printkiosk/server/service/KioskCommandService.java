package com.printkiosk.server.service;

import com.github.f4b6a3.uuid.UuidCreator;
import com.printkiosk.server.domain.KioskCommandEntity;
import com.printkiosk.server.domain.KioskCommandRepository;
import com.printkiosk.server.domain.KioskRepository;
import com.printkiosk.server.exception.AdminRuleViolationException;
import com.printkiosk.shared.api.KioskCommandStatus;
import com.printkiosk.shared.api.KioskCommandType;
import com.printkiosk.shared.api.dto.KioskCommandDto;
import com.printkiosk.shared.api.dto.TelemetryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Очередь команд киоскам.
 *
 * <p>Доставка — pull, а не push: киоск за NAT, входящее соединение до него
 * не пробить. Команда цепляется к ответу на heartbeat, который и так ходит
 * раз в 30 секунд, так что отдельного канала не нужно и задержка невелика.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KioskCommandService {

    /**
     * Сколько ждём, пока киоск заберёт команду. Дальше она протухает.
     *
     * <p>Это не косметика: без срока годности киоск, пролежавший офлайн
     * сутки, поднялся бы и ушёл в перезагрузку в произвольный момент —
     * возможно, прямо посреди чужой оплаты.
     */
    private static final Duration PICKUP_TTL = Duration.ofMinutes(10);

    /**
     * Сколько ждём подтверждения после выдачи. Молчание означает, что киоск
     * ушёл в перезагрузку и подтвердить уже не мог — считаем выполненной.
     */
    private static final Duration ACK_TTL = Duration.ofMinutes(5);

    private final KioskCommandRepository commands;
    private final KioskRepository kiosks;

    // ══════════════════════════════════════════════════════════════════
    //  Админка
    // ══════════════════════════════════════════════════════════════════

    @Transactional
    public KioskCommandDto enqueue(String kioskId, KioskCommandType type, String operator) {
        if (!kiosks.existsById(kioskId)) {
            throw new AdminRuleViolationException("Неизвестный киоск: " + kioskId);
        }

        // Повторное нажатие не создаёт вторую команду, а возвращает текущую.
        // Иначе оператор, не дождавшись реакции за 30 секунд, накликал бы
        // очередь перезагрузок.
        Optional<KioskCommandEntity> pending =
                commands.findByKioskIdAndStatus(kioskId, KioskCommandStatus.PENDING);
        if (pending.isPresent()) {
            throw new AdminRuleViolationException(
                    "Для этого киоска уже есть команда в очереди — дождитесь выполнения или отмените её");
        }

        KioskCommandEntity cmd = KioskCommandEntity.builder()
                .id(UuidCreator.getTimeOrderedEpoch())
                .kioskId(kioskId)
                .type(type)
                .status(KioskCommandStatus.PENDING)
                .createdBy(truncate(operator, 64))
                .createdAt(Instant.now())
                .build();

        commands.save(cmd);
        log.info("Command queued: kiosk={} type={} by={}", kioskId, type, operator);
        return toDto(cmd);
    }

    @Transactional
    public void cancel(UUID commandId) {
        KioskCommandEntity cmd = commands.findById(commandId)
                .orElseThrow(() -> new AdminRuleViolationException("Команда не найдена"));

        if (cmd.getStatus() != KioskCommandStatus.PENDING) {
            // Забранную команду отменять поздно — киоск уже перезагружается.
            throw new AdminRuleViolationException(
                    "Команду уже забрал киоск, отменить нельзя");
        }
        cmd.setStatus(KioskCommandStatus.CANCELLED);
        cmd.setFinishedAt(Instant.now());
        log.info("Command cancelled: {} kiosk={}", commandId, cmd.getKioskId());
    }

    @Transactional(readOnly = true)
    public List<KioskCommandDto> history(String kioskId) {
        return commands.findTop50ByKioskIdOrderByCreatedAtDesc(kioskId)
                .stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Optional<KioskCommandDto> latest(String kioskId) {
        return commands.findFirstByKioskIdOrderByCreatedAtDesc(kioskId).map(this::toDto);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Киоск
    // ══════════════════════════════════════════════════════════════════

    /** Выдаёт киоску ожидающую команду и переводит её в SENT. */
    @Transactional
    public TelemetryResponse pullFor(String kioskId) {
        return commands.findByKioskIdAndStatus(kioskId, KioskCommandStatus.PENDING)
                .map(cmd -> {
                    cmd.setStatus(KioskCommandStatus.SENT);
                    cmd.setDispatchedAt(Instant.now());
                    log.info("Command dispatched: {} type={} kiosk={}",
                            cmd.getId(), cmd.getType(), kioskId);
                    return new TelemetryResponse(cmd.getId(), cmd.getType());
                })
                .orElseGet(TelemetryResponse::nothing);
    }

    /**
     * Подтверждение от киоска.
     *
     * <p>kioskId сверяется с владельцем команды: без этой проверки один
     * киоск мог бы закрыть команду, адресованную другому.
     */
    @Transactional
    public void ack(String kioskId, UUID commandId, boolean accepted, String message) {
        KioskCommandEntity cmd = commands.findById(commandId).orElse(null);
        if (cmd == null || !cmd.getKioskId().equals(kioskId)) {
            log.warn("Ack for unknown/foreign command {} from kiosk {}", commandId, kioskId);
            return;
        }
        cmd.setStatus(accepted ? KioskCommandStatus.DONE : KioskCommandStatus.FAILED);
        cmd.setFinishedAt(Instant.now());
        cmd.setResultMessage(message);
        log.info("Command {} acked by {}: accepted={} msg={}",
                commandId, kioskId, accepted, message);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Уборка
    // ══════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelayString = "${kiosk.commands.sweep-interval-ms:60000}")
    @Transactional
    public void sweepStale() {
        Instant now = Instant.now();

        commands.findStale(List.of(KioskCommandStatus.PENDING), now.minus(PICKUP_TTL))
                .forEach(cmd -> {
                    cmd.setStatus(KioskCommandStatus.EXPIRED);
                    cmd.setFinishedAt(now);
                    cmd.setResultMessage("Киоск не забрал команду за "
                            + PICKUP_TTL.toMinutes() + " мин — вероятно, был офлайн");
                    log.info("Command expired: {} kiosk={}", cmd.getId(), cmd.getKioskId());
                });

        commands.findStale(List.of(KioskCommandStatus.SENT), now.minus(ACK_TTL))
                .forEach(cmd -> {
                    // Подтверждения нет, потому что процесс уже убит перезагрузкой.
                    cmd.setStatus(KioskCommandStatus.DONE);
                    cmd.setFinishedAt(now);
                    cmd.setResultMessage("Подтверждение не получено — считаем выполненной");
                    log.info("Command assumed done: {} kiosk={}", cmd.getId(), cmd.getKioskId());
                });
    }

    /**
     * Страховка на длину: created_by — VARCHAR(64). Обрезаем здесь, а не
     * полагаемся на вызывающий код, чтобы любой будущий источник логина
     * не ронял вставку.
     */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private KioskCommandDto toDto(KioskCommandEntity e) {
        return new KioskCommandDto(
                e.getId(), e.getKioskId(), e.getType(), e.getStatus(),
                e.getCreatedBy(), e.getCreatedAt(),
                e.getDispatchedAt(), e.getFinishedAt(), e.getResultMessage());
    }
}