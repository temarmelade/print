package com.printkiosk.client.printer;

import com.printkiosk.client.config.KioskClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.*;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Опрашивает принтер по SNMP (Printer MIB v2, RFC 3805).
 * <p>
 * Если в конфиге не задан snmp-host — поллер работает в no-op режиме
 * и считает принтер «локальным USB» (всегда ready). Это нужно для
 * dev-окружения с подключенным по USB Canon.
 * <p>
 * Снимок последнего статуса хранится в {@link AtomicReference}, чтобы
 * {@link PrinterReadinessService} мог его читать без блокировок.
 */
@Slf4j
@Component
public class PrinterMonitor {

    private static final OID OID_TONER_CURRENT = new OID("1.3.6.1.2.1.43.11.1.1.9.1.1");
    private static final OID OID_TONER_MAX     = new OID("1.3.6.1.2.1.43.11.1.1.8.1.1");
    private static final OID OID_PAPER_CURRENT = new OID("1.3.6.1.2.1.43.8.2.1.10.1.1");
    private static final OID OID_PAPER_MAX     = new OID("1.3.6.1.2.1.43.8.2.1.9.1.1");

    /** Минимальный процент тонера, ниже которого считаем «не готов». */
    private static final int MIN_TONER_PCT = 5;
    /** Минимальный процент бумаги, ниже которого считаем «не готов». */
    private static final int MIN_PAPER_PCT = 5;

    private final ApplicationEventPublisher eventPublisher;
    private final KioskClientProperties properties;

    private final AtomicReference<Snapshot> lastSnapshot = new AtomicReference<>();

    public PrinterMonitor(ApplicationEventPublisher eventPublisher,
                          KioskClientProperties properties) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
    }

    /** Снимок состояния, опубликованный SNMP-опросом. -1 = неизвестно. */
    public record Snapshot(int tonerPct, int paperPct, Instant at) {

        public boolean isReady() {
            // Если значения недоступны, не валим печать — принтер может не отдавать
            // эти OID, но физически работать. Только явно низкие уровни блокируют.
            return (tonerPct == -1 || tonerPct >= MIN_TONER_PCT)
                    && (paperPct == -1 || paperPct >= MIN_PAPER_PCT);
        }
    }

    public Snapshot lastSnapshot() {
        return lastSnapshot.get();
    }

    public boolean snmpEnabled() {
        String host = properties.getPrinter().getSnmpHost();
        return host != null && !host.isBlank();
    }

    @Scheduled(fixedRateString = "${kiosk.printer.snmp-poll-interval-ms:30000}")
    public void poll() {
        if (!snmpEnabled()) {
            return;   // USB-принтер, нет смысла опрашивать
        }

        var p = properties.getPrinter();
        String host = p.getSnmpHost();
        int port = p.getSnmpPort();
        String community = p.getSnmpCommunity();

        try (TransportMapping<?> transport = new DefaultUdpTransportMapping()) {
            Snmp snmp = new Snmp(transport);
            transport.listen();

            CommunityTarget<Address> target = buildTarget(host, port, community);
            PDU response = sendGet(snmp, target);

            if (response == null) {
                log.warn("SNMP: no response from {}:{}", host, port);
                publish(new Snapshot(-1, -1, Instant.now()));
                return;
            }

            int tonerCurrent = extractInt(response, OID_TONER_CURRENT);
            int tonerMax     = extractInt(response, OID_TONER_MAX);
            int paperCurrent = extractInt(response, OID_PAPER_CURRENT);
            int paperMax     = extractInt(response, OID_PAPER_MAX);

            int tonerPct = tonerMax > 0 ? (tonerCurrent * 100 / tonerMax) : -1;
            int paperPct = paperMax > 0 ? (paperCurrent * 100 / paperMax) : -1;

            Snapshot s = new Snapshot(tonerPct, paperPct, Instant.now());
            log.debug("Printer SNMP poll: toner={}%, paper={}%", tonerPct, paperPct);
            publish(s);

        } catch (Exception e) {
            log.warn("SNMP poll failed: {}", e.getMessage());
            publish(new Snapshot(-1, -1, Instant.now()));
        }
    }

    private void publish(Snapshot snapshot) {
        lastSnapshot.set(snapshot);
        eventPublisher.publishEvent(snapshot);
    }

    private CommunityTarget<Address> buildTarget(String host, int port, String community) {
        CommunityTarget<Address> target = new CommunityTarget<>();
        target.setAddress(GenericAddress.parse("udp:" + host + "/" + port));
        target.setCommunity(new OctetString(community));
        target.setVersion(SnmpConstants.version2c);
        target.setTimeout(2_000);
        target.setRetries(1);
        return target;
    }

    private PDU sendGet(Snmp snmp, CommunityTarget<Address> target) throws Exception {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(OID_TONER_CURRENT));
        pdu.add(new VariableBinding(OID_TONER_MAX));
        pdu.add(new VariableBinding(OID_PAPER_CURRENT));
        pdu.add(new VariableBinding(OID_PAPER_MAX));
        pdu.setType(PDU.GET);

        ResponseEvent<Address> event = snmp.send(pdu, target);
        return event != null ? event.getResponse() : null;
    }

    private int extractInt(PDU pdu, OID oid) {
        for (int i = 0; i < pdu.size(); i++) {
            VariableBinding binding = pdu.get(i);
            if (binding.getOid().equals(oid)) {
                Variable v = binding.getVariable();
                if (v instanceof Integer32 x)         return x.getValue();
                if (v instanceof UnsignedInteger32 x) return (int) x.getValue();
                return -1;
            }
        }
        return -1;
    }
}