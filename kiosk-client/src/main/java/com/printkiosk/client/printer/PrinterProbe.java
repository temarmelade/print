package com.printkiosk.client.printer;

import com.printkiosk.client.config.KioskClientProperties;
import com.printkiosk.shared.api.SupplySource;
import lombok.extern.slf4j.Slf4j;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.*;
import org.snmp4j.transport.DefaultUdpTransportMapping;
import org.springframework.stereotype.Component;

import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.attribute.standard.PrinterStateReason;
import javax.print.attribute.standard.PrinterStateReasons;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Снимает состояние принтера: SNMP (по сети) + javax.print (драйвер, в т.ч. USB).
 *
 * <p><b>Почему обход поддерева, а не фиксированные OID.</b> У разных моделей
 * индексы записей различаются (.1.1, .1.2 и т.д.), и угадывать их — источник
 * ошибок: именно так мы прочитали не тот счётчик страниц (pages=1). Поэтому
 * читаем поддерево целиком (GETNEXT) и берём первую осмысленную запись.
 *
 * <p><b>Магические значения Printer MIB.</b> Уровень расходника может быть
 * отрицательным: −1 «прочее», −2 «неизвестно», −3 «есть, сколько — не скажу».
 * Всё это = «неизвестно» (null), а не 0.
 *
 * <p><b>Аномалия Canon MF232w:</b> у кассеты нет датчика уровня, и принтер
 * отдаёт по бумаге 0, хотя лоток полон. Ноль без флага «нет бумаги» —
 * недостоверен, поэтому трактуем его как «неизвестно» (см. sanitizePaper).
 * Иначе киоск решит, что бумага кончилась, и перестанет принимать оплату.
 */
@Slf4j
@Component
public class PrinterProbe {

    // Базы поддеревьев Printer MIB (RFC 3805) — без индексов, обходим целиком.
    private static final OID BASE_TONER_CURRENT = new OID("1.3.6.1.2.1.43.11.1.1.9");
    private static final OID BASE_TONER_MAX     = new OID("1.3.6.1.2.1.43.11.1.1.8");
    private static final OID BASE_PAPER_CURRENT = new OID("1.3.6.1.2.1.43.8.2.1.10");
    private static final OID BASE_PAPER_MAX     = new OID("1.3.6.1.2.1.43.8.2.1.9");
    /** prtMarkerLifeCount — счётчик напечатанных страниц. */
    private static final OID BASE_PAGE_COUNTER  = new OID("1.3.6.1.2.1.43.10.2.1.4");
    /** hrPrinterDetectedErrorState — битовая маска ошибок. */
    private static final OID BASE_ERROR_STATE   = new OID("1.3.6.1.2.1.25.3.5.1.2");

    // Биты hrPrinterDetectedErrorState (байт 0).
    private static final int BIT_NO_PAPER  = 0x40;
    private static final int BIT_LOW_TONER = 0x20;
    private static final int BIT_NO_TONER  = 0x10;
    private static final int BIT_DOOR_OPEN = 0x08;
    private static final int BIT_JAMMED    = 0x04;
    private static final int BIT_OFFLINE   = 0x02;

    private final KioskClientProperties properties;

    public PrinterProbe(KioskClientProperties properties) {
        this.properties = properties;
    }

    /** Состояние принтера. Integer-поля: null = «принтер не сообщает». */
    public record Reading(
            Boolean online,
            Integer tonerPercent,
            Integer paperPercent,
            SupplySource tonerSource,
            SupplySource paperSource,
            boolean paperOut,
            boolean paperJam,
            boolean tonerLow,
            boolean tonerEmpty,
            boolean doorOpen,
            String error,
            Integer pageCounter
    ) {}

    public boolean snmpEnabled() {
        String host = properties.getPrinter().getSnmpHost();
        return host != null && !host.isBlank();
    }

    public Reading probe() {
        Reading local = probeViaJavaPrint();
        if (!snmpEnabled()) return local;
        return merge(local, probeViaSnmp());
    }

    // ════════════════════════════════════════════════════════════════
    //  javax.print (работает и по USB)
    // ════════════════════════════════════════════════════════════════

    private Reading probeViaJavaPrint() {
        PrintService service = resolvePrinter();
        if (service == null) {
            return new Reading(false, null, null, SupplySource.UNKNOWN, SupplySource.UNKNOWN,
                    false, false, false, false, false, "Принтер не найден в системе", null);
        }

        boolean paperOut = false, jam = false, tonerLow = false, tonerEmpty = false;
        boolean doorOpen = false, offline = false;

        PrinterStateReasons reasons = service.getAttribute(PrinterStateReasons.class);
        if (reasons != null) {
            Set<PrinterStateReason> set = reasons.keySet();
            for (PrinterStateReason r : set) {
                switch (r.toString()) {
                    case "media-empty", "media-needed" -> paperOut = true;
                    case "media-jam"                   -> jam = true;
                    case "toner-low"                   -> tonerLow = true;
                    case "toner-empty"                 -> tonerEmpty = true;
                    case "door-open", "cover-open"     -> doorOpen = true;
                    case "shutdown", "paused"          -> offline = true;
                    default -> { }
                }
            }
        }

        return new Reading(!offline, null, null, SupplySource.UNKNOWN, SupplySource.UNKNOWN,
                paperOut, jam, tonerLow, tonerEmpty, doorOpen, null, null);
    }

    private PrintService resolvePrinter() {
        String name = properties.getPrinter().getName();
        if (name != null && !name.isBlank()) {
            for (PrintService s : PrintServiceLookup.lookupPrintServices(null, null)) {
                if (s.getName().equalsIgnoreCase(name)) return s;
            }
        }
        return PrintServiceLookup.lookupDefaultPrintService();
    }

    // ════════════════════════════════════════════════════════════════
    //  SNMP
    // ════════════════════════════════════════════════════════════════

    private Reading probeViaSnmp() {
        var p = properties.getPrinter();

        try (TransportMapping<?> transport = new DefaultUdpTransportMapping()) {
            Snmp snmp = new Snmp(transport);
            transport.listen();
            CommunityTarget<Address> target = target(p);

            List<VariableBinding> tonerCur = walk(snmp, target, BASE_TONER_CURRENT);
            List<VariableBinding> tonerMax = walk(snmp, target, BASE_TONER_MAX);
            List<VariableBinding> paperCur = walk(snmp, target, BASE_PAPER_CURRENT);
            List<VariableBinding> paperMax = walk(snmp, target, BASE_PAPER_MAX);
            List<VariableBinding> pagesVb  = walk(snmp, target, BASE_PAGE_COUNTER);
            List<VariableBinding> errVb    = walk(snmp, target, BASE_ERROR_STATE);

            if (tonerCur.isEmpty() && pagesVb.isEmpty() && errVb.isEmpty()) {
                log.warn("SNMP: принтер {} не ответил", p.getSnmpHost());
                return new Reading(false, null, null, SupplySource.UNKNOWN, SupplySource.UNKNOWN,
                        false, false, false, false, false, "Принтер не отвечает по сети", null);
            }

            dump(tonerCur, tonerMax, paperCur, paperMax, pagesVb, errVb);

            Integer tonerPct = percent(firstInt(tonerCur), firstInt(tonerMax));
            Integer paperPct = percent(firstInt(paperCur), firstInt(paperMax));
            // Счётчик страниц — берём максимальное значение: у некоторых моделей
            // первая запись поддерева служебная (у нас так и вышло: pages=1).
            Integer pages = maxInt(pagesVb);
            int errBits = firstErrorBits(errVb);

            boolean noPaper = (errBits & BIT_NO_PAPER) != 0;
            paperPct = sanitizePaper(paperPct, noPaper);

            log.debug("SNMP: toner={} paper={} pages={} errBits=0x{}",
                    tonerPct, paperPct, pages, Integer.toHexString(errBits));

            return new Reading(
                    (errBits & BIT_OFFLINE) == 0,
                    tonerPct,
                    paperPct,
                    tonerPct != null ? SupplySource.SENSOR : SupplySource.UNKNOWN,
                    paperPct != null ? SupplySource.SENSOR : SupplySource.UNKNOWN,
                    noPaper,
                    (errBits & BIT_JAMMED) != 0,
                    (errBits & BIT_LOW_TONER) != 0,
                    (errBits & BIT_NO_TONER) != 0,
                    (errBits & BIT_DOOR_OPEN) != 0,
                    null,
                    pages);

        } catch (Exception e) {
            log.warn("SNMP-опрос не удался: {}", e.getMessage());
            return new Reading(null, null, null, SupplySource.UNKNOWN, SupplySource.UNKNOWN,
                    false, false, false, false, false, null, null);
        }
    }

    /**
     * Ноль без флага «нет бумаги» недостоверен: у MF232w нет датчика уровня,
     * и он отдаёт 0 при полной кассете. Считаем это «неизвестно» — иначе киоск
     * решит, что бумага кончилась, и перестанет принимать оплату.
     */
    private static Integer sanitizePaper(Integer pct, boolean noPaperFlag) {
        if (pct != null && pct == 0 && !noPaperFlag) return null;
        return pct;
    }

    /** Обход поддерева через GETNEXT — не зависим от индексов конкретной модели. */
    private List<VariableBinding> walk(Snmp snmp, CommunityTarget<Address> target, OID base)
            throws Exception {
        List<VariableBinding> out = new ArrayList<>();
        OID current = new OID(base);

        for (int guard = 0; guard < 32; guard++) {
            PDU pdu = new PDU();
            pdu.setType(PDU.GETNEXT);
            pdu.add(new VariableBinding(current));

            ResponseEvent<Address> event = snmp.send(pdu, target);
            PDU response = (event != null) ? event.getResponse() : null;
            if (response == null || response.size() == 0) break;

            VariableBinding vb = response.get(0);
            if (vb == null || vb.isException()) break;
            if (!vb.getOid().startsWith(base)) break;   // вышли за поддерево

            out.add(vb);
            current = vb.getOid();
        }
        return out;
    }

    /** Полный дамп — заменяет snmpwalk при диагностике новой модели принтера. */
    private void dump(List<VariableBinding> tonerCur, List<VariableBinding> tonerMax,
                      List<VariableBinding> paperCur, List<VariableBinding> paperMax,
                      List<VariableBinding> pages, List<VariableBinding> err) {
        if (!log.isDebugEnabled()) return;
        log.debug("── SNMP RAW DUMP ─────────────────────────────");
        logAll("toner.current", tonerCur);
        logAll("toner.max    ", tonerMax);
        logAll("paper.current", paperCur);
        logAll("paper.max    ", paperMax);
        logAll("pageCounter  ", pages);
        logAll("errorState   ", err);
        log.debug("──────────────────────────────────────────────");
    }

    private void logAll(String label, List<VariableBinding> list) {
        if (list.isEmpty()) {
            log.debug("{} : <пусто — модель не поддерживает>", label);
            return;
        }
        for (VariableBinding vb : list) {
            log.debug("{} : {} = {}", label, vb.getOid(), vb.getVariable());
        }
    }

    private CommunityTarget<Address> target(KioskClientProperties.Printer p) {
        CommunityTarget<Address> t = new CommunityTarget<>();
        t.setAddress(GenericAddress.parse("udp:" + p.getSnmpHost() + "/" + p.getSnmpPort()));
        t.setCommunity(new OctetString(p.getSnmpCommunity()));
        t.setVersion("v2c".equalsIgnoreCase(p.getSnmpVersion())
                ? SnmpConstants.version2c
                : SnmpConstants.version1);
        t.setTimeout(2_000);
        t.setRetries(1);
        return t;
    }

    private static Integer percent(Integer current, Integer max) {
        if (current == null || max == null) return null;
        if (current < 0 || max <= 0) return null;   // −1/−2/−3 = «неизвестно»
        return Math.min(current * 100 / max, 100);
    }

    private static Integer firstInt(List<VariableBinding> list) {
        for (VariableBinding vb : list) {
            Integer v = toInt(vb.getVariable());
            if (v != null) return v;
        }
        return null;
    }

    private static Integer maxInt(List<VariableBinding> list) {
        Integer best = null;
        for (VariableBinding vb : list) {
            Integer v = toInt(vb.getVariable());
            if (v != null && (best == null || v > best)) best = v;
        }
        return best;
    }

    private static Integer toInt(Variable v) {
        if (v instanceof Integer32 x)         return x.getValue();
        if (v instanceof UnsignedInteger32 x) return (int) x.getValue();
        if (v instanceof Counter32 x)         return (int) x.getValue();
        if (v instanceof Counter64 x)         return (int) x.getValue();
        return null;
    }

    private static int firstErrorBits(List<VariableBinding> list) {
        for (VariableBinding vb : list) {
            if (vb.getVariable() instanceof OctetString os && os.length() > 0) {
                return os.getValue()[0] & 0xFF;
            }
        }
        return 0;
    }

    private Reading merge(Reading local, Reading snmp) {
        return new Reading(
                snmp.online() != null ? snmp.online() : local.online(),
                snmp.tonerPercent(),
                snmp.paperPercent(),
                snmp.tonerSource(),
                snmp.paperSource(),
                local.paperOut()   || snmp.paperOut(),
                local.paperJam()   || snmp.paperJam(),
                local.tonerLow()   || snmp.tonerLow(),
                local.tonerEmpty() || snmp.tonerEmpty(),
                local.doorOpen()   || snmp.doorOpen(),
                local.error() != null ? local.error() : snmp.error(),
                snmp.pageCounter());
    }
}
