package com.printkiosk.client.ui.controller;

import com.printkiosk.client.service.i18n.LocalizationService;
import com.printkiosk.client.ui.*;
import com.printkiosk.client.ui.state.Language;
import com.printkiosk.client.config.KioskClientProperties;
import com.printkiosk.client.service.scan.ScanFlow;
import com.printkiosk.client.config.ServerProperties;
import com.printkiosk.client.service.AdPlaylistService;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.concurrent.Task;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.service.PinEntryFlow;
import com.printkiosk.shared.api.dto.VerifyResponse;
import com.printkiosk.shared.api.dto.UploadResponse;
import com.printkiosk.shared.api.UploadSource;
import com.printkiosk.client.service.PreviewFlow;
import javafx.scene.image.Image;
import com.printkiosk.client.service.PrintSettingsFlow;
import com.printkiosk.shared.api.dto.JobPreviewResponse;
import com.printkiosk.shared.api.dto.PrintSettings;
import javafx.scene.Node;
import com.printkiosk.client.service.PaymentSessionFlow;
import com.printkiosk.client.ui.util.QrCodeGenerator;
import com.printkiosk.shared.api.dto.PaymentSessionDto;
import java.util.UUID;
import com.printkiosk.client.service.PrintFlow;
import com.printkiosk.client.printer.PrinterReadinessService;

/**
 * Главный контроллер киоска.
 *
 * <p><b>Локализация (i18n).</b> Схема такая:
 * <ul>
 *   <li>Статические тексты (заголовки, кнопки, подписи) биндятся ОДИН раз в
 *       {@code initI18nBindings()} через {@code loc.bind(key)}. При смене
 *       языка все открытые экраны обновляются автоматически.</li>
 *   <li>Динамические тексты (цена, ошибки, статусы) устанавливаются
 *       императивно через {@code loc.get(key, args)} в момент показа —
 *       они всегда рендерятся на актуальном языке.</li>
 *   <li>Побочные эффекты смены языка (QR-коды, подсветка кнопок, экранная
 *       клавиатура, формат даты) — в одном листенере
 *       {@code loc.languageProperty()}.</li>
 *   <li>Язык живёт в рамках сессии: {@code resetAllAndGoHome()} сбрасывает
 *       его на язык по умолчанию (это же покрывает idle-таймаут через
 *       {@code hideScreensaver} и успешную печать через автовозврат).</li>
 * </ul>
 *
 * <p>ВАЖНО: узел, чей textProperty забинден, НЕЛЬЗЯ трогать setText() —
 * будет RuntimeException. Поэтому лейблы с динамическим контентом
 * (paymentInstructionLabel, printingStatusLabel и т.п.) сознательно
 * не биндятся.
 */
@Slf4j
@Component
@Scope("prototype")
public class MainController {

    // ══════════════════════════════════════════════════════════════════════
    //  SCREENS
    // ══════════════════════════════════════════════════════════════════════

    @FXML private StackPane rootStack;
    @FXML private VBox homeScreen;
    @FXML private VBox uploadScreen;
    @FXML private VBox fileInfoScreen;
    @FXML private VBox settingsScreen;
    @FXML private VBox summaryScreen;
    @FXML private VBox paymentScreen;
    @FXML private VBox printingScreen;
    @FXML private VBox completedScreen;
    @FXML private VBox scanInstructionScreen;
    @FXML private VBox scanProgressScreen;
    @FXML private VBox scanPreviewScreen;
    @FXML private VBox scanDeliveryScreen;
    @FXML private VBox adminScreen;
    @FXML private VBox helpScreen;

    // ══════════════════════════════════════════════════════════════════════
    //  HEADER
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Button langRuBtn;
    /** ВНИМАНИЕ: в FXML должен быть fx:id="langKyBtn" (раньше был langKgBtn —
     *  из-за расхождения имён поле было null и подсветка не работала). */
    @FXML private Button langKyBtn;
    @FXML private Button langEnBtn;
    @FXML private Button headerHelpBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  HOME
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label homeWelcomeLabel;
    @FXML private Label homeSubtitleLabel;
    @FXML private Label printCardTitle;
    @FXML private Label printCardDesc;
    @FXML private Label copyCardTitle;
    @FXML private Label copyCardDesc;
    @FXML private Label scanCardTitle;
    @FXML private Label scanCardDesc;
    @FXML private Label homeTimeLabel;
    @FXML private Label homeDateLabel;

    // ══════════════════════════════════════════════════════════════════════
    //  UPLOAD (ввод PIN)
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label uploadTitleLabel;
    @FXML private Label uploadDescLabel;

    @FXML private Label uploadTgTitle;
    @FXML private Label uploadTgDesc;
    @FXML private Label uploadTgLabel;
    @FXML private Label uploadWebTitle;
    @FXML private Label uploadWebDesc;
    @FXML private Label uploadWebLabel;
    @FXML private Label uploadOrLabel;
    @FXML private Label uploadOrLabelDesc;

    @FXML private ImageView webQrImageView;
    @FXML private ImageView telegramQrImageView;

    @FXML private VBox uploadQrStep;
    @FXML private VBox emojiCodeStep;
    @FXML private Label pinErrorLabel;
    @FXML private Label pinStatusLabel;
    @FXML private Button goToEmojiCodeBtn;
    @FXML private Button backToUploadMethodsBtn;
    @FXML private Button backBtnUpload;
    @FXML private Button backBtnFileInfo;
    @FXML private Button backBtnSettings;
    @FXML private Button backBtnSummary;
    @FXML private Button backBtnPayment;
    @FXML private Button backBtnScanInstruction;
    @FXML private Button backBtnScanPreview;
    @FXML private ScrollPane pageListScroll;
    @FXML private VBox pageListContainer;
    @FXML private Label selectedCountLabel;
    @FXML private Button selectAllBtn;
    @FXML private Button clearSelectionBtn;
    @FXML private Label selectedPinCodeLabel;
    @FXML private Button pinBackspaceBtn;
    @FXML private Button pinSubmitBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  FILE INFO
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label fileFoundTitleLabel;
    @FXML private Label fileInfoLabel;
    @FXML private Button goToSettingsBtn;
    /** Заголовок панели выбора страниц. Требует fx:id в FXML (см. чеклист). */
    @FXML private Label pageSelectTitleLabel;

    @FXML private StackPane previewContainer;
    @FXML private ImageView previewImageView;
    @FXML private VBox previewLoadingBox;
    @FXML private Label previewLoadingLabel;
    @FXML private VBox previewErrorBox;
    @FXML private Label previewErrorLabel;
    @FXML private HBox previewNavBox;
    @FXML private Button printPreviewPrevBtn;
    @FXML private Label previewPageLabel;
    @FXML private Button printPreviewNextBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  SETTINGS
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label settingsTitleLabel;
    @FXML private Label settingsSubtitleLabel;
    @FXML private Label copiesTitleLabel;
    /** Был в FXML, но отсутствовал в контроллере — добавлен для биндинга. */
    @FXML private Label copiesDescLabel;
    @FXML private Label copiesValueLabel;
    @FXML private Label colorTitleLabel;
    @FXML private Button bwBtn;
    @FXML private Button colorBtn;
    @FXML private Label sidesTitleLabel;
    @FXML private Button singleSideBtn;
    @FXML private Button doubleSideBtn;
    @FXML private Label orientationTitleLabel;
    @FXML private Button portraitBtn;
    @FXML private Button landscapeBtn;
    @FXML private Label paperTitleLabel;
    @FXML private Button a4Btn;
    @FXML private Button settingsNextBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  SUMMARY
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label summaryTitleLabel;
    @FXML private Label summaryPagesKeyLabel;
    @FXML private Label summaryCopiesKeyLabel;
    @FXML private Label summaryColorKeyLabel;
    @FXML private Label summarySidesKeyLabel;
    @FXML private Label summaryOrientationKeyLabel;
    @FXML private Label summaryPaperKeyLabel;
    /** Подпись «Стоимость». Требует fx:id в FXML (см. чеклист). */
    @FXML private Label summaryPriceKeyLabel;
    @FXML private Label summaryPagesLabel;
    @FXML private Label summaryCopiesLabel;
    @FXML private Label summaryColorLabel;
    @FXML private Label summarySidesLabel;
    @FXML private Label summaryOrientationLabel;
    @FXML private Label summaryPaperLabel;
    @FXML private Label summaryPriceLabel;
    @FXML private Button proceedToPaymentBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  PAYMENT
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label paymentTitleLabel;
    @FXML private Label paymentAmountLabel;
    @FXML private Label paymentInstructionLabel;
    @FXML private ImageView qrCodeImageView;
    @FXML private VBox paymentReadyBox;
    @FXML private VBox paymentLoadingBox;
    @FXML private Label paymentLoadingLabel;
    @FXML private VBox paymentErrorBox;
    @FXML private Label paymentErrorLabel;
    @FXML private Button paymentRetryBtn;
    @FXML private Button adminBypassPaymentBtn;
    /** Был в FXML, но отсутствовал в контроллере — добавлен для биндинга. */
    @FXML private Button paymentHomeBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  PRINTING
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label printingTitleLabel;
    @FXML private Label printingStatusLabel;
    @FXML private ImageView printingAnimation;
    @FXML private Label printingHintLabel;
    @FXML private Button printingHomeBtn;
    /** Пилюля «Печать в процессе» и чипы. Требуют fx:id в FXML (чеклист). */
    @FXML private Label statusPillLabel;
    @FXML private Label chipPrinterLabel;
    @FXML private Label chipTimeLabel;
    @FXML private VBox  printFailedScreen;
    @FXML private Label printErrorMessageLabel;
    @FXML private Label printErrorPinLabel;
    /** Тексты экрана PRINT_FAILED. Требуют fx:id в FXML (чеклист). */
    @FXML private Label printFailedTitleLabel;
    @FXML private Label printFailedHintLabel;
    @FXML private Label printFailedSupportLabel;
    @FXML private Button printFailedHomeBtn;
    @FXML private VBox outOfServiceScreen;
    /** Тексты экрана OUT_OF_SERVICE. Требуют fx:id в FXML (чеклист). */
    @FXML private Label outOfServiceTitleLabel;
    @FXML private Label outOfServiceDescLabel;
    @FXML private Label outOfServiceSupportHintLabel;
    @FXML private Button outOfServiceHomeBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  COMPLETED
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label completedMessageLabel;
    @FXML private Label completedSubMessageLabel;
    @FXML private Button printAnotherBtn;
    @FXML private Button completedHomeBtn;
    @FXML private ImageView completedMascot;
    @FXML private Label autoReturnCounterLabel;
    /** Подпись «Возврат на главный экран через:». Требует fx:id (чеклист). */
    @FXML private Label autoReturnHintLabel;
    @FXML private ProgressIndicator autoReturnProgress;

    /** Сколько секунд показывать экран COMPLETED до автовозврата на HOME. */
    private static final int AUTO_RETURN_SECONDS = 20;
    /** Активный таймер автовозврата (null — не запущен). */
    private Timeline autoReturnTimeline;

    // ══════════════════════════════════════════════════════════════════════
    //  SCAN / COPY
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label scanInstructionTitleLabel;
    @FXML private Label scanInstructionDescLabel;
    @FXML private Button startScanPageBtn;
    /** Карточка экрана SCAN_INSTRUCTION — поднимается при открытой клавиатуре. */
    @FXML private VBox scanInstrCard;
    /** Насколько поднимать карточку, чтобы клавиатура не перекрывала поле. */
    private static final double SCAN_CARD_LIFT_Y = -170;
    @FXML private TextField scanFileNameField;   // поле имени файла (добавить в FXML)
    /** Редизайн SCAN_INSTRUCTION: баннер-подсказка, кастомный prompt, статус-бар. */
    @FXML private Label scanNameHintLabel;
    @FXML private HBox  scanNamePromptBox;
    @FXML private Label scanNamePromptLabel;
    @FXML private Label scanStatusConnectedLabel;
    @FXML private Label scanStatusColorLabel;
    @FXML private Label scanStatusFormatLabel;
    private VirtualKeyboard virtualKeyboard;
    @FXML private StackPane scanDeliveryQrBox;
    @FXML private Label scanProgressTitleLabel;
    @FXML private Label scanProgressStatusLabel;
    @FXML private ProgressBar scanProgressBar;
    /** Редизайн SCAN_PROGRESS: круглое окно видео, точки-индикатор, подпись. */
    @FXML private StackPane scanVideoBox;
    @FXML private HBox scanDotsBox;
    @FXML private Label scanProgressAutoCloseLabel;
    /** Плеер зацикленного видео сканирования (null — javafx-media недоступна). */
    private javafx.scene.media.MediaPlayer scanVideoPlayer;
    /** Анимация бегущей точки под видео. */
    private Timeline scanDotsTimeline;

    @FXML private Label scanPreviewTitleLabel;
    @FXML private Label scanPreviewPageLabel;
    @FXML private ImageView scanPreviewImageView;

    @FXML private Button previewPrevBtn;
    @FXML private Button previewNextBtn;
    @FXML private Button addScanPageBtn;
    @FXML private Button rescanPageBtn;
    @FXML private Button deleteScanPageBtn;
    @FXML private Button finishScanBtn;

    @FXML private Label scanDeliveryTitleLabel;
    @FXML private Label scanDeliveryDescLabel;
    @FXML private ImageView scanDeliveryQrImageView;
    @FXML private Label scanDeliveryInfoLabel;
    @FXML private Button scanDeliveryPrintBtn;
    @FXML private Button scanDeliveryWebBtn;
    @FXML private Button scanDeliveryTelegramBtn;
    /** Редизайн: подписи внутри карточек (текст в graphic, а не на кнопке). */
    @FXML private Label scanDeliveryPrintLabel;
    @FXML private Label scanDeliveryWebLabel;
    @FXML private Label scanDeliveryTelegramLabel;
    /** Ряд карточек способов — сжимается в компактный режим при показе QR. */
    @FXML private HBox deliveryChoicesRow;
    @FXML private Button scanDeliveryBackBtn;
    /** Был в FXML, но отсутствовал в контроллере — добавлен для биндинга. */
    @FXML private Button scanDeliveryHomeBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  ADMIN — сознательно НЕ локализуется: экран для оператора, не клиента.
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label adminTitleLabel;
    @FXML private Label adminSubtitleLabel;
    @FXML private Label adminTotalJobsLabel;
    @FXML private Label adminPrintJobsLabel;
    @FXML private Label adminCopyJobsLabel;
    @FXML private Label adminScanJobsLabel;
    @FXML private Label adminRevenueLabel;
    @FXML private Label adminFailedJobsLabel;
    @FXML private Button adminRefreshBtn;
    @FXML private Button adminBackBtn;
    @FXML private Button adminTestPrintBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  HELP
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Button helpBackBtn;
    @FXML private Label helpTitleLabel;
    @FXML private Label helpSubtitleLabel;
    @FXML private Label helpStep1Label;
    @FXML private Label helpStep2Label;
    @FXML private Label helpStep3Label;
    @FXML private Label helpStep4Label;
    @FXML private Label helpFormatsTitleLabel;
    @FXML private Label helpFormatsValueLabel;
    @FXML private Label helpFormatsLimitLabel;
    @FXML private Label helpSupportTitleLabel;
    @FXML private Label helpSupportPhoneLabel;
    @FXML private Label helpSupportTelegramLabel;
    @FXML private Label helpHintLabel;

    // ══════════════════════════════════════════════════════════════════════
    //  STATE
    // ══════════════════════════════════════════════════════════════════════

    /** Шаги работы киоска. Идентичны старому enum'у. */
    public enum KioskStep {
        HOME, UPLOAD, FILE_INFO, SETTINGS, SUMMARY, PAYMENT, PRINTING, COMPLETED,
        SCAN_INSTRUCTION, SCAN_PROGRESS, SCAN_PREVIEW, SCAN_DELIVERY,
        ADMIN, HELP, PRINT_FAILED, OUT_OF_SERVICE
    }

    private VerifyResponse currentFile;
    private String currentPin;
    private JobPreviewResponse currentPreview;
    private KioskStep currentStep = KioskStep.HOME;
    private UUID currentJobId;

    // ══════════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════════
    private final PreviewFlow previewFlow;
    private final PrintSettingsFlow settingsFlow;
    private final PaymentSessionFlow paymentFlow;
    private final PinEntryFlow pinEntryFlow;
    private final PrintFlow printFlow;
    private final PrinterReadinessService printerReadiness;
    private final KioskClientProperties clientProperties;
    private final AdPlaylistService adPlaylistService;
    private final ServerProperties serverProperties;
    private final ScanFlow scanFlow;
    private final KioskServerClient serverClient;
    private final LocalizationService loc;

    private static final String LANG_ACTIVE_CLASS = "lang-btn-active";

    /** Формат времени часов на HOME — от языка не зависит. */
    private static final java.time.format.DateTimeFormatter HOME_TIME_FMT =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm");
    /** Формат даты на HOME — пересобирается при смене языка. */
    private java.time.format.DateTimeFormatter homeDateFmt =
            java.time.format.DateTimeFormatter.ofPattern(
                    "d MMMM, EEEE", new java.util.Locale("ru"));

    /** Откуда вошли в настройки печати — определяет, куда вернёт «Назад». */
    private enum SettingsOrigin { PRINT_UPLOAD, SCAN, COPY }
    private SettingsOrigin settingsOrigin = SettingsOrigin.PRINT_UPLOAD;

    /**
     * Режим, в котором запущен модуль сканирования: обычное сканирование
     * (после «Завершить» — экран выбора действий) или ксерокопия (после
     * «Завершить» — сразу настройки печати). Экраны переиспользуются одни
     * и те же, различается только маршрутизация.
     */
    private enum ScanMode { SCAN, COPY }
    private ScanMode scanMode = ScanMode.SCAN;
    private PageSelectionPanel pageSelection;
    /** Снимок выбранных страниц (1-based) для текущего задания. null = все. */
    private java.util.List<Integer> jobPages = null;
    private NumericKeyboard numericKeyboard;
    /** Рекламная заставка по бездействию. */
    private IdleScreensaver screensaver;
    private IdleWatcher idleWatcher;
    /** Сколько киоск должен простаивать до показа заставки. */
    private static final java.time.Duration IDLE_TIMEOUT = java.time.Duration.ofSeconds(60);

    public MainController(PinEntryFlow pinEntryFlow, PreviewFlow previewFlow,
                          PrintSettingsFlow settingsFlow, PaymentSessionFlow paymentFlow,
                          PrintFlow printFlow, PrinterReadinessService printerReadiness,
                          KioskClientProperties clientProperties, AdPlaylistService adPlaylistService,
                          ServerProperties serverProperties, ScanFlow scanFlow,
                          KioskServerClient serverClient, LocalizationService loc) {
        this.pinEntryFlow = pinEntryFlow;
        this.clientProperties = clientProperties;
        this.adPlaylistService = adPlaylistService;
        this.serverProperties = serverProperties;
        this.scanFlow = scanFlow;
        this.serverClient = serverClient;
        this.previewFlow = previewFlow;
        this.settingsFlow = settingsFlow;
        this.paymentFlow = paymentFlow;
        this.printFlow = printFlow;
        this.printerReadiness = printerReadiness;
        this.loc = loc;
    }


    // ══════════════════════════════════════════════════════════════════════
    //  INITIALIZE
    // ══════════════════════════════════════════════════════════════════════

    @FXML
    public void initialize() {
        log.info("MainController initialized");
        initLocalization();
        pinEntryFlow.setListener(buildPinEntryListener());
        previewFlow.setListener(buildPreviewListener());
        settingsFlow.setListener(buildSettingsListener());
        paymentFlow.setListener(buildPaymentListener());
        printFlow.setListener(buildPrintListener());
        showUploadQrStep();        // стартовое состояние upload — подэкран QR-кодов
        showOnly(homeScreen);
        setupIdleScreensaver();
        setupScan();
        numericKeyboard = new NumericKeyboard();
        rootStack.getChildren().add(numericKeyboard);
        StackPane.setAlignment(numericKeyboard, javafx.geometry.Pos.BOTTOM_CENTER);
        StackPane.setMargin(numericKeyboard, new javafx.geometry.Insets(0, 0, 28, 0));

        pageSelection = new PageSelectionPanel(
                pageListContainer, selectedCountLabel,
                previewFlow::renderThumbnail,
                this::onPageSelectionChanged,
                (sel, total) -> loc.get("pages.selected.count",
                        String.valueOf(sel), String.valueOf(total)),
                n -> loc.get("pages.page.n", String.valueOf(n)));
        startHomeClock();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOCALIZATION
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Точка входа локализации: биндит статические тексты, вешает единый
     * листенер на смену языка и приводит UI к начальному языку (подсветка,
     * QR-коды, формат даты).
     */
    private void initLocalization() {
        initI18nBindings();
        loc.languageProperty().addListener((obs, oldLang, newLang) -> onLanguageChanged(newLang));
        onLanguageChanged(loc.getLanguage());
    }

    /**
     * Все побочные эффекты смены языка в одном месте. Вызывается и при
     * старте (начальное состояние), и при каждом переключении, и при
     * сбросе сессии на язык по умолчанию.
     */
    private void onLanguageChanged(Language lang) {
        // 1. Подсветка активной языковой кнопки
        setLangActive(langRuBtn, lang == Language.RU);
        setLangActive(langKyBtn, lang == Language.KY);
        setLangActive(langEnBtn, lang == Language.EN);

        // 2. QR-коды загрузки (Telegram + веб) — payload зависит от языка.
        //    QR эквайринга (qrCodeImageView) НЕ трогаем: там paymentUrl от шлюза.
        refreshUploadQrCodes();

        // 3. Экранная клавиатура — раскладка под язык
        if (virtualKeyboard != null) virtualKeyboard.rebuild(lang);

        // 4. Формат даты на часах HOME + мгновенное обновление
        homeDateFmt = java.time.format.DateTimeFormatter.ofPattern(
                "d MMMM, EEEE", java.util.Locale.forLanguageTag(langCode(lang)));
        refreshHomeClockNow();

        // 5. Императивные тексты, которые могут быть на экране прямо сейчас.
        //    (Сегодня языковые кнопки видны только на HOME, так что это
        //    страховка на будущее — например, если кнопки переедут в хедер.)
        if (currentPreview != null) populateSummary(currentPreview);
        if (currentFile != null) showFileInfo(currentFile);
        // Панель выбора страниц: счётчик и подписи «Страница N».
        // null-guard обязателен: первый вызов onLanguageChanged происходит из
        // initLocalization(), когда pageSelection ещё не создана.
        if (pageSelection != null) pageSelection.refreshTexts();
    }

    /**
     * Биндинги всех статических текстов. Один раз — навсегда.
     * Хелпер bindText null-безопасен: узлы, для которых fx:id ещё не
     * добавлен в FXML, просто пропускаются (и начнут работать сразу после
     * добавления fx:id, без правок этого кода).
     */
    private void initI18nBindings() {
        // ---- HOME ----
        bindText(homeWelcomeLabel, "home.welcome");
        bindText(homeSubtitleLabel, "home.subtitle");
        bindText(headerHelpBtn, "home.help.btn");
        bindText(printCardTitle, "home.card.print.title");
        bindText(printCardDesc, "home.card.print.desc");
        bindText(copyCardTitle, "home.card.copy.title");
        bindText(copyCardDesc, "home.card.copy.desc");
        bindText(scanCardTitle, "home.card.scan.title");
        bindText(scanCardDesc, "home.card.scan.desc");
        // Языковые кнопки «Рус/Кыр/Eng» не переводим — эндонимы.

        // ---- Кнопки «Назад» / «На главный экран» ----
        bindText(backBtnUpload, "btn.back");
        bindText(backBtnFileInfo, "btn.back");
        bindText(backBtnSettings, "btn.back");
        bindText(backBtnSummary, "btn.back");
        bindText(backBtnPayment, "btn.back");
        bindText(backBtnScanInstruction, "btn.back");
        bindText(backBtnScanPreview, "btn.back");
        bindText(scanDeliveryBackBtn, "btn.back");
        bindText(helpBackBtn, "btn.back");
        bindText(paymentHomeBtn, "btn.home");
        bindText(scanDeliveryHomeBtn, "btn.home");
        bindText(printingHomeBtn, "btn.home");
        bindText(completedHomeBtn, "btn.home");
        bindText(printFailedHomeBtn, "btn.home");
        bindText(outOfServiceHomeBtn, "btn.home");

        // ---- UPLOAD ----
        bindText(uploadTitleLabel, "upload.title");
        bindText(uploadDescLabel, "upload.desc");
        bindText(uploadTgTitle, "upload.tg.title");
        bindText(uploadTgDesc, "upload.tg.desc");
        bindText(uploadTgLabel, "upload.tg.note");
        bindText(uploadWebTitle, "upload.web.title");
        bindText(uploadWebDesc, "upload.web.desc");
        bindText(uploadWebLabel, "upload.web.note");
        bindText(goToEmojiCodeBtn, "upload.have.code.btn");
        bindText(backToUploadMethodsBtn, "upload.back.to.qr.btn");

        // ---- PIN ----
        bindText(uploadOrLabel, "pin.title");
        bindText(uploadOrLabelDesc, "pin.desc");
        // pinStatusLabel / pinErrorLabel / selectedPinCodeLabel — динамические.

        // ---- FILE INFO ----
        bindText(fileFoundTitleLabel, "fileinfo.found");
        bindText(goToSettingsBtn, "fileinfo.configure.btn");
        bindText(previewLoadingLabel, "preview.loading");
        bindText(pageSelectTitleLabel, "pages.panel.title");
        bindText(selectAllBtn, "pages.select.all");
        bindText(clearSelectionBtn, "pages.clear");
        // fileInfoLabel, previewErrorLabel, previewPageLabel — динамические.

        // ---- SETTINGS ----
        bindText(settingsTitleLabel, "settings.title");
        bindText(settingsSubtitleLabel, "settings.subtitle");
        bindText(copiesTitleLabel, "settings.copies.title");
        bindText(copiesDescLabel, "settings.copies.desc");
        bindText(colorTitleLabel, "settings.color.title");
        bindText(bwBtn, "settings.color.bw");
        bindText(colorBtn, "settings.color.color");
        bindText(sidesTitleLabel, "settings.sides.title");
        bindText(singleSideBtn, "settings.sides.single");
        bindText(doubleSideBtn, "settings.sides.double");
        bindText(orientationTitleLabel, "settings.orientation.title");
        bindText(portraitBtn, "settings.orientation.portrait");
        bindText(landscapeBtn, "settings.orientation.landscape");
        bindText(paperTitleLabel, "settings.paper.title");
        bindText(settingsNextBtn, "settings.next.btn");
        // copiesValueLabel — динамический. a4Btn ("A4") — нейтральный.

        // ---- SUMMARY ----
        bindText(summaryTitleLabel, "summary.title");
        bindText(summaryCopiesKeyLabel, "summary.copies");
        bindText(summaryColorKeyLabel, "summary.color");
        bindText(summarySidesKeyLabel, "summary.sides");
        bindText(summaryOrientationKeyLabel, "summary.orientation");
        bindText(summaryPaperKeyLabel, "summary.paper");
        bindText(summaryPagesKeyLabel, "summary.pages");
        bindText(summaryPriceKeyLabel, "summary.price");
        bindText(proceedToPaymentBtn, "summary.confirm.btn");
        // summary*Label (значения) заполняются в populateSummary через loc.get.

        // ---- PAYMENT ----
        bindText(paymentTitleLabel, "payment.title");
        bindText(paymentRetryBtn, "btn.retry");
        // paymentInstructionLabel НЕ биндим: его перезаписывает countdown.
        // paymentLoadingLabel / paymentErrorLabel / paymentAmountLabel — динамические.
        // adminBypassPaymentBtn — dev-кнопка, не переводим.

        // ---- PRINTING ----
        bindText(printingTitleLabel, "printing.title");
        bindText(printingHintLabel, "printing.hint");
        bindText(statusPillLabel, "printing.pill");
        bindText(chipPrinterLabel, "printing.chip.printer");
        bindText(chipTimeLabel, "printing.chip.time");
        // printingStatusLabel — динамический (PrintFlow).

        // ---- PRINT FAILED / OUT OF SERVICE ----
        bindText(printFailedTitleLabel, "printfail.title");
        bindText(printFailedHintLabel, "printfail.save.pin");
        bindText(printFailedSupportLabel, "printfail.support");
        bindText(outOfServiceTitleLabel, "oos.title");
        bindText(outOfServiceDescLabel, "oos.desc");
        bindText(outOfServiceSupportHintLabel, "oos.support");
        // printErrorMessageLabel / printErrorPinLabel — динамические.

        // ---- COMPLETED ----
        bindText(completedMessageLabel, "completed.title");
        bindText(completedSubMessageLabel, "completed.desc");
        bindText(printAnotherBtn, "completed.print.another");
        bindText(autoReturnHintLabel, "completed.autoreturn.hint");
        // autoReturnCounterLabel — динамический (счётчик).

        // ---- SCAN ----
        bindText(scanInstructionTitleLabel, "scan.instruction.title");
        bindText(scanInstructionDescLabel, "scan.instruction.desc");
        bindText(scanNameHintLabel, "scan.name.hint");
        // Prompt поля имени: в новом дизайне это оверлей «иконка + текст» по
        // центру поля. Если оверлея в FXML нет (старый экран) — обычный
        // promptText. Оба сразу нельзя: текст задвоится.
        if (scanNamePromptLabel != null) {
            bindText(scanNamePromptLabel, "scan.name.prompt");
        } else {
            bindPrompt(scanFileNameField, "scan.name.prompt");
        }
        bindText(startScanPageBtn, "scan.start.btn");
        // Статус-бар внизу: модель сканера, «300 DPI» и «A4» — нейтральные.
        bindText(scanStatusConnectedLabel, "scan.status.connected");
        bindText(scanStatusColorLabel, "scan.status.color.mode");
        bindText(scanStatusFormatLabel, "scan.status.format");
        bindText(scanProgressTitleLabel, "scan.progress.title");
        bindText(scanProgressStatusLabel, "scan.progress.status");
        bindText(scanProgressAutoCloseLabel, "scan.progress.autoclose");
        bindText(deleteScanPageBtn, "scan.delete");
        bindText(rescanPageBtn, "scan.rescan");
        bindText(addScanPageBtn, "scan.add.page");
        bindText(finishScanBtn, "scan.finish");
        bindText(scanDeliveryTitleLabel, "scan.delivery.title");
        bindText(scanDeliveryDescLabel, "scan.delivery.desc");
        // Подписи способов — в graphic-лейблах карточек (над ними круг с
        // иконкой), поэтому биндим лейблы, а не сами кнопки.
        bindText(scanDeliveryPrintLabel, "scan.delivery.print");
        bindText(scanDeliveryWebLabel, "scan.delivery.web");
        bindText(scanDeliveryTelegramLabel, "scan.delivery.telegram");
        // scanPreviewTitleLabel — перезаписывается именем файла, НЕ биндим.
        // scanDeliveryInfoLabel — динамический.

        // ---- HELP ----
        bindText(helpTitleLabel, "help.title");
        bindText(helpSubtitleLabel, "help.subtitle");
        bindText(helpStep1Label, "help.step1");
        bindText(helpStep2Label, "help.step2");
        bindText(helpStep3Label, "help.step3");
        bindText(helpStep4Label, "help.step4");
        bindText(helpFormatsTitleLabel, "help.formats.title");
        bindText(helpFormatsValueLabel, "help.formats.value");
        bindText(helpFormatsLimitLabel, "help.formats.limit");
        bindText(helpSupportTitleLabel, "help.support.title");
        bindText(helpSupportPhoneLabel, "help.support.phone");
        bindText(helpSupportTelegramLabel, "help.support.telegram");
        bindText(helpHintLabel, "help.hint");

        // ADMIN-экран сознательно не биндим — он для оператора.
    }

    /** Null-безопасный биндинг текста: пропускает не-инжектированные узлы. */
    private void bindText(Labeled node, String key) {
        if (node != null) {
            node.textProperty().bind(loc.bind(key));
        }
    }

    /** Null-безопасный биндинг prompt-текста для полей ввода. */
    private void bindPrompt(TextInputControl field, String key) {
        if (field != null) {
            field.promptTextProperty().bind(loc.bind(key));
        }
    }

    /**
     * Живые часы на главном экране: раз в секунду обновляют время и дату.
     * Timeline-колбэки выполняются на JavaFX Application Thread — Platform.
     * runLater не нужен. Формат даты (homeDateFmt) зависит от языка и
     * пересобирается в onLanguageChanged.
     */
    private void startHomeClock() {
        Timeline clock = new Timeline(new KeyFrame(Duration.seconds(1),
                e -> refreshHomeClockNow()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        // Первое обновление сразу, не дожидаясь первой секунды.
        refreshHomeClockNow();
    }

    /** Немедленно перерисовывает время и дату по текущему формату. */
    private void refreshHomeClockNow() {
        var now = java.time.LocalDateTime.now();
        if (homeTimeLabel != null) homeTimeLabel.setText(now.format(HOME_TIME_FMT));
        if (homeDateLabel != null) homeDateLabel.setText(now.format(homeDateFmt));
    }

    /** Подключает слушатель сессии сканирования и экранную клавиатуру. */
    private void setupScan() {
        setupScanProgressVisuals();
        scanFlow.setListener(new ScanFlow.Listener() {
            @Override public void onScanStarted() {
                changeStep(KioskStep.SCAN_PROGRESS);
            }
            @Override public void onScanReady() {
                refreshScanPreview();
                changeStep(KioskStep.SCAN_PREVIEW);
            }
            @Override public void onScanError(String message) {
                log.warn("Scan error: {}", message);
                changeStep(KioskStep.SCAN_INSTRUCTION);
            }
            @Override public void onPageChanged() {
                refreshScanPreview();
            }
        });

        // Экранная клавиатура для поля имени файла (если поле есть в FXML).
        if (scanFileNameField != null) {
            virtualKeyboard = new VirtualKeyboard();
            rootStack.getChildren().add(virtualKeyboard);
            StackPane.setAlignment(virtualKeyboard, javafx.geometry.Pos.BOTTOM_CENTER);
            StackPane.setMargin(virtualKeyboard, new javafx.geometry.Insets(0, 0, 28, 0));
            virtualKeyboard.attachTo(scanFileNameField, loc.getLanguage());

            // Ширина поля = ширине кнопки «Начать сканирование» (точное совпадение,
            // не зависит от текста кнопки). maxWidth тоже фиксируем, иначе TextField
            // растянулся бы на всю ширину VBox.
            if (startScanPageBtn != null) {
                scanFileNameField.prefWidthProperty().bind(startScanPageBtn.widthProperty());
                scanFileNameField.maxWidthProperty().bind(startScanPageBtn.widthProperty());
            }

            // Кастомный prompt-оверлей (иконка + текст по центру поля):
            // виден, только пока поле пустое. mouseTransparent задан в FXML,
            // клики проходят сквозь него в TextField.
            if (scanNamePromptBox != null) {
                scanNamePromptBox.visibleProperty().bind(
                        scanFileNameField.textProperty().isEmpty());
            }

            // Подъём карточки, пока клавиатура открыта: после добавления
            // иллюстрации сверху выехавшая клавиатура перекрывала поле ввода.
            // Едем синхронно с анимацией клавиатуры (180 мс) и возвращаемся
            // на место при её закрытии.
            if (scanInstrCard != null) {
                TranslateTransition cardLift =
                        new TranslateTransition(Duration.millis(180), scanInstrCard);
                virtualKeyboard.showingProperty().addListener((obs, was, nowShown) -> {
                    cardLift.stop();
                    cardLift.setToY(nowShown ? SCAN_CARD_LIFT_Y : 0);
                    cardLift.play();
                });
            }
        }
    }

    /**
     * Готовит круглое окно видео на экране SCAN_PROGRESS: круглая маска,
     * подгонка под размер контейнера и зацикленный беззвучный плеер из
     * ресурса /videos/scan_loop.mp4. Если модуля javafx-media нет в сборке
     * или файл отсутствует — тихо откатываемся на статичную иллюстрацию,
     * экран остаётся рабочим.
     */
    private void setupScanProgressVisuals() {
        if (scanVideoBox == null) return;

        // Круглая маска по размеру контейнера (следит за изменением размера).
        javafx.scene.shape.Circle mask = new javafx.scene.shape.Circle();
        mask.radiusProperty().bind(javafx.beans.binding.Bindings
                .min(scanVideoBox.widthProperty(), scanVideoBox.heightProperty())
                .divide(2));
        mask.centerXProperty().bind(scanVideoBox.widthProperty().divide(2));
        mask.centerYProperty().bind(scanVideoBox.heightProperty().divide(2));
        scanVideoBox.setClip(mask);

        try {
            var url = getClass().getResource("/videos/scan_loop.mp4");
            if (url != null) {
                var media  = new javafx.scene.media.Media(url.toExternalForm());
                scanVideoPlayer = new javafx.scene.media.MediaPlayer(media);
                scanVideoPlayer.setCycleCount(javafx.scene.media.MediaPlayer.INDEFINITE);
                scanVideoPlayer.setMute(true);
                var view = new javafx.scene.media.MediaView(scanVideoPlayer);
                // Заполняем круг: fit по большей стороне контейнера.
                view.fitWidthProperty().bind(scanVideoBox.widthProperty());
                view.fitHeightProperty().bind(scanVideoBox.heightProperty());
                view.setPreserveRatio(true);
                scanVideoBox.getChildren().add(view);
                return;
            }
            log.warn("scan_loop.mp4 not found in /videos — using fallback illustration");
        } catch (Throwable t) {
            // NoClassDefFoundError, если javafx-media не подключён в pom.
            log.warn("JavaFX media unavailable ({}) — using fallback illustration",
                    t.toString());
        }
        var fallbackUrl = getClass().getResource("/images/scan/scanner_illustration.png");
        if (fallbackUrl != null) {
            ImageView iv = new ImageView(new Image(fallbackUrl.toExternalForm()));
            iv.fitWidthProperty().bind(scanVideoBox.widthProperty());
            iv.fitHeightProperty().bind(scanVideoBox.heightProperty());
            iv.setPreserveRatio(true);
            scanVideoBox.getChildren().add(iv);
        }
    }

    /** Запуск анимаций экрана SCAN_PROGRESS: видео + бегущая точка. */
    private void startScanProgressAnim() {
        if (scanVideoPlayer != null) scanVideoPlayer.play();
        if (scanDotsBox != null && !scanDotsBox.getChildren().isEmpty()) {
            stopDotsOnly();
            final int count = scanDotsBox.getChildren().size();
            final int[] idx = { 0 };
            applyActiveDot(0);
            scanDotsTimeline = new Timeline(new KeyFrame(Duration.millis(420), e -> {
                idx[0] = (idx[0] + 1) % count;
                applyActiveDot(idx[0]);
            }));
            scanDotsTimeline.setCycleCount(Timeline.INDEFINITE);
            scanDotsTimeline.play();
        }
    }

    /** Остановка анимаций экрана SCAN_PROGRESS (при уходе с экрана). */
    private void stopScanProgressAnim() {
        if (scanVideoPlayer != null) scanVideoPlayer.pause();
        stopDotsOnly();
    }

    private void stopDotsOnly() {
        if (scanDotsTimeline != null) {
            scanDotsTimeline.stop();
            scanDotsTimeline = null;
        }
    }

    /** Подсвечивает точку с индексом i, гасит остальные. */
    private void applyActiveDot(int i) {
        var dots = scanDotsBox.getChildren();
        for (int d = 0; d < dots.size(); d++) {
            dots.get(d).getStyleClass().remove("scan-dot-active");
            if (d == i) dots.get(d).getStyleClass().add("scan-dot-active");
        }
    }

    /**
     * Компактный режим ряда способов: карточки сжимаются и прячут подписи,
     * оставляя только иконки, — освобождая место под QR-код. Управляется
     * одним style-классом на ряду (delivery-choices-compact), от которого
     * каскадом сжимаются дочерние карточки в CSS; подписи гасим через
     * visible+managed, чтобы они не занимали место.
     */
    private void setDeliveryCompact(boolean compact) {
        if (deliveryChoicesRow != null) {
            deliveryChoicesRow.getStyleClass().remove("delivery-choices-compact");
            if (compact) deliveryChoicesRow.getStyleClass().add("delivery-choices-compact");
        }
        for (Label lbl : new Label[]{scanDeliveryPrintLabel,
                scanDeliveryWebLabel, scanDeliveryTelegramLabel}) {
            if (lbl != null) {
                lbl.setVisible(!compact);
                lbl.setManaged(!compact);
            }
        }
    }

    /** Обновляет предпросмотр текущей отсканированной страницы. */
    private void refreshScanPreview() {
        if (scanPreviewImageView != null) {
            scanPreviewImageView.setImage(scanFlow.currentPreviewImage());
        }
        if (scanPreviewPageLabel != null) {
            scanPreviewPageLabel.setText(
                    scanFlow.currentPageNumber() + " / " + scanFlow.pageCount());
        }
        if (scanPreviewTitleLabel != null) {
            scanPreviewTitleLabel.setText(scanFlow.fileName() != null
                    ? scanFlow.fileName()
                    : loc.get("scan.preview.title"));
        }
    }

    /**
     * Создаёт рекламную заставку, монтирует её поверх всего интерфейса и
     * подключает слежение за бездействием, когда появится Scene.
     */
    private void setupIdleScreensaver() {
        screensaver = new IdleScreensaver(serverProperties);
        rootStack.getChildren().add(screensaver);   // верхний слой поверх экранов

        idleWatcher = new IdleWatcher(
                javafx.util.Duration.millis(IDLE_TIMEOUT.toMillis()),
                this::showScreensaver,
                this::hideScreensaver);

        // Scene появляется не сразу — цепляемся, когда станет доступна.
        if (rootStack.getScene() != null) {
            idleWatcher.attach(rootStack.getScene());
        } else {
            rootStack.sceneProperty().addListener((obs, oldScene, newScene) -> {
                if (newScene != null) idleWatcher.attach(newScene);
            });
        }
    }

    /** Показать заставку с актуальным плейлистом (если он непустой). */
    private void showScreensaver() {
        var playlist = adPlaylistService.currentPlaylist();
        log.info("Idle timeout reached. Ad playlist size = {}", playlist.size());
        if (playlist.isEmpty()) {
            // Нечего показывать — выходим из режима заставки и считаем заново.
            log.info("Screensaver not shown: playlist is empty (нет загруженной рекламы для слота HOME)");
            idleWatcher.cancelIdle();
            return;
        }
        log.info("Showing screensaver with {} item(s)", playlist.size());
        screensaver.start(playlist);
        screensaver.toFront();
    }

    /**
     * Скрыть заставку и вернуть киоск на главный экран.
     * resetAllAndGoHome внутри сбросит и язык на дефолтный — заставка
     * появляется по idle-таймауту, т.е. сессия считается завершённой.
     */
    private void hideScreensaver() {
        screensaver.stop();
        resetAllAndGoHome();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  NAVIGATION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Показывает один экран, прячет все остальные.
     * Пока тупо переключает visible/managed; на следующих шагах добавим
     * анимации/таймеры/проверки.
     */
    private void showOnly(VBox screen) {
        VBox[] all = {
                homeScreen, uploadScreen, fileInfoScreen, settingsScreen,
                summaryScreen, paymentScreen, printingScreen, completedScreen,
                scanInstructionScreen, scanProgressScreen, scanPreviewScreen,
                scanDeliveryScreen, adminScreen, helpScreen
        };
        for (VBox s : all) {
            if (s == null) continue;
            boolean visible = (s == screen);
            s.setVisible(visible);
            s.setManaged(visible);
        }
    }

    private void changeStep(KioskStep step) {
        this.currentStep = step;
        // Уход с экрана ввода имени = клавиатура больше не нужна. Без этого
        // она осталась бы висеть поверх следующего экрана, если пользователь
        // ушёл, не закрыв её (showingProperty вернёт карточку на место).
        if (virtualKeyboard != null && step != KioskStep.SCAN_INSTRUCTION) {
            virtualKeyboard.hideKeyboard();
        }
        // Видео и точки крутятся только на экране прогресса сканирования.
        if (step == KioskStep.SCAN_PROGRESS) {
            startScanProgressAnim();
        } else {
            stopScanProgressAnim();
        }
        switch (step) {
            case HOME             -> showOnly(homeScreen);
            case UPLOAD           -> { showOnly(uploadScreen); showUploadQrStep(); }
            case FILE_INFO        -> showOnly(fileInfoScreen);
            case SETTINGS         -> showOnly(settingsScreen);
            case SUMMARY          -> showOnly(summaryScreen);
            case PAYMENT          -> showOnly(paymentScreen);
            case PRINTING         -> {
                // До первого события PrintFlow в лейбле висел бы русский
                // дефолт из FXML — ставим переведённый текст сразу.
                if (printingStatusLabel != null) {
                    printingStatusLabel.setText(loc.get("printing.wait"));
                }
                showOnly(printingScreen);
            }
            case COMPLETED        -> showOnly(completedScreen);
            case SCAN_INSTRUCTION -> {
                // Очищаем поле имени файла при каждом входе на экран старта
                // сканирования (например, при повторном заходе с HOME).
                if (scanFileNameField != null) scanFileNameField.clear();
                showOnly(scanInstructionScreen);
            }
            case SCAN_PROGRESS    -> showOnly(scanProgressScreen);
            case SCAN_PREVIEW     -> showOnly(scanPreviewScreen);
            case SCAN_DELIVERY    -> {
                // Возврат экрана в исходное состояние: QR ещё не строился,
                // подпись-разделитель показывает приглашение выбрать способ.
                // (info-лейбл динамический — после выбора web/telegram его
                // текст меняется в deliverScans на scan.delivery.scan.qr.)
                if (scanDeliveryQrBox != null) {
                    scanDeliveryQrBox.setVisible(false);
                    scanDeliveryQrBox.setManaged(false);
                }
                if (scanDeliveryInfoLabel != null) {
                    scanDeliveryInfoLabel.setText(loc.get("scan.delivery.choose"));
                }
                setDeliveryCompact(false);   // полные карточки с подписями
                showOnly(scanDeliveryScreen);
            }
            case ADMIN            -> showOnly(adminScreen);
            case HELP             -> showOnly(helpScreen);
            case PRINT_FAILED   -> showOnly(printFailedScreen);
            case OUT_OF_SERVICE -> showOnly(outOfServiceScreen);
        }
        log.info("Step → {}", step);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EVENT HANDLERS
    // ══════════════════════════════════════════════════════════════════════

    // ---- HOME ----
    @FXML public void onPrintOperationSelected()  { changeStep(KioskStep.UPLOAD); }
    @FXML public void onCopyOperationSelected()   { scanMode = ScanMode.COPY; changeStep(KioskStep.SCAN_INSTRUCTION); }
    @FXML public void onScanOperationSelected()   { scanMode = ScanMode.SCAN; changeStep(KioskStep.SCAN_INSTRUCTION); }
    @FXML public void onHelpClicked()             { changeStep(KioskStep.HELP); }
    @FXML public void onHelpBackClicked()         { changeStep(KioskStep.HOME); }

    // ---- LANGUAGE ----
    // Смена языка централизована в LocalizationService: setLanguage триггерит
    // languageProperty, от которого пересчитываются все биндинги, а
    // onLanguageChanged делает побочные эффекты (QR, подсветка, клавиатура).
    @FXML public void onSelectRuTop() { loc.setLanguage(Language.RU); }
    @FXML public void onSelectKgTop() { loc.setLanguage(Language.KY); }
    @FXML public void onSelectEnTop() { loc.setLanguage(Language.EN); }

    /** Подсветка активной языковой кнопки (класс lang-btn-active). */
    private static void setLangActive(Node btn, boolean active) {
        if (btn == null) return;
        btn.getStyleClass().remove(LANG_ACTIVE_CLASS);
        if (active) {
            btn.getStyleClass().add(LANG_ACTIVE_CLASS);
        }
    }

    /**
     * Генерирует QR-коды для Telegram-бота и веб-портала с учётом текущего
     * языка и помещает их в карточки экрана UPLOAD.
     * QR оплаты эквайринга сюда не входит — его payload задаёт платёжный шлюз.
     */
    private void refreshUploadQrCodes() {
        Language lang = loc.getLanguage();
        if (telegramQrImageView != null) {
            telegramQrImageView.setImage(
                    QrCodeGenerator.generate(buildTelegramUrl(lang), 185));
        }
        if (webQrImageView != null) {
            webQrImageView.setImage(
                    QrCodeGenerator.generate(buildWebUrl(lang), 185));
        }
    }

    /** Deep-link бота с параметром языка: ...?start=lang_ru */
    private String buildTelegramUrl(Language lang) {
        String base = clientProperties.getUpload().getTelegramBotUrl();
        return base + "?start=lang_" + langCode(lang);
    }

    /** URL сайта с query-параметром локали: ...?lang=ru */
    private String buildWebUrl(Language lang) {
        String base = clientProperties.getUpload().getWebUrl();
        String sep = base.contains("?") ? "&" : "?";
        return base + sep + "lang=" + langCode(lang);
    }

    /** Код языка для ссылок (ISO-639-1; кыргызский — ky). */
    private static String langCode(Language lang) {
        return switch (lang) {
            case RU -> "ru";
            case KY -> "ky";
            case EN -> "en";
        };
    }

    // ---- UPLOAD / PIN ----

    /** Какой подэкран UPLOAD сейчас открыт: QR-коды или ввод PIN. */
    private boolean uploadPinStepShown = false;

    /** Показать подэкран QR-кодов (методы загрузки). */
    private void showUploadQrStep() {
        if (emojiCodeStep != null) { emojiCodeStep.setVisible(false); emojiCodeStep.setManaged(false); }
        if (uploadQrStep  != null) { uploadQrStep.setVisible(true);   uploadQrStep.setManaged(true); }
        uploadPinStepShown = false;
        pinEntryFlow.reset();
    }

    /** Показать подэкран ввода PIN. */
    private void showUploadPinStep() {
        if (uploadQrStep  != null) { uploadQrStep.setVisible(false);  uploadQrStep.setManaged(false); }
        if (emojiCodeStep != null) { emojiCodeStep.setVisible(true);  emojiCodeStep.setManaged(true); }
        uploadPinStepShown = true;
        pinEntryFlow.reset();
        if (selectedPinCodeLabel != null) selectedPinCodeLabel.setText("");
    }

    @FXML
    public void onGoToEmojiCodeClicked() {
        showUploadPinStep();
    }

    @FXML
    public void onBackToUploadMethodsClicked() {
        showUploadQrStep();
    }

    @FXML
    public void onDigitButtonClicked(ActionEvent e) {
        Object src = e.getSource();
        if (src instanceof Button btn) {
            // У кнопок numpad'а text — это сама цифра ("0".."9").
            pinEntryFlow.pressDigit(btn.getText().trim());
        }
    }

    @FXML public void onSelectAllPagesClicked()   { pageSelection.selectAll(); }
    @FXML public void onClearPageSelectionClicked() { pageSelection.clearSelection(); }

    /** Блокируем «Настроить печать», пока не выбрана ни одна страница. */
    private void onPageSelectionChanged() {
        if (goToSettingsBtn != null) {
            goToSettingsBtn.setDisable(pageSelection.selectedCount() == 0);
        }
    }


    @FXML
    public void onPinBackspaceClicked() {
        pinEntryFlow.pressBackspace();
    }

    @FXML
    public void onPinSubmitClicked() {
        pinEntryFlow.submit();
    }

    // ---- FILE INFO ----
    @FXML
    public void onGoToSettingsClicked() {
        if (currentFile == null) {
            log.warn("Tried to enter settings without a verified file");
            return;
        }
        settingsOrigin = SettingsOrigin.PRINT_UPLOAD;   // вошли из печати загруженного файла
        jobPages = pageSelection.getPagesToPrint();     // фиксируем выбор страниц
        if (jobPages.isEmpty()) {                        // страховка: пусто = печатать все
            jobPages = null;
        }
        settingsFlow.start(currentPin, jobPages);
        changeStep(KioskStep.SETTINGS);
    }

    @FXML public void onPrintPreviewPrevClicked() { previewFlow.prev(); }
    @FXML public void onPrintPreviewNextClicked() { previewFlow.next(); }

    // ---- SETTINGS ----
    @FXML public void onCopiesMinusClicked()  { settingsFlow.decrementCopies(); }
    @FXML public void onCopiesPlusClicked()   { settingsFlow.incrementCopies(); }

    @FXML public void onBlackWhiteSelected()  { settingsFlow.setColorMode(PrintSettingsFlow.COLOR_BW); }
    @FXML public void onColorSelected()       { settingsFlow.setColorMode(PrintSettingsFlow.COLOR_COLOR); }

    @FXML public void onSingleSideSelected()  { settingsFlow.setDoubleSided(false); }
    @FXML public void onDoubleSideSelected()  { settingsFlow.setDoubleSided(true); }

    @FXML public void onPortraitSelected()    { settingsFlow.setOrientation(PrintSettingsFlow.ORIENTATION_PORTRAIT); }
    @FXML public void onLandscapeSelected()   { settingsFlow.setOrientation(PrintSettingsFlow.ORIENTATION_LANDSCAPE); }

    @FXML public void onA4Selected()          { settingsFlow.setPaperSize(PrintSettingsFlow.PAPER_A4); }

    @FXML
    public void onProceedToSummaryClicked() {
        if (currentPreview == null) {
            log.warn("Tried to proceed to summary without a valid preview");
            return;
        }
        populateSummary(currentPreview);
        changeStep(KioskStep.SUMMARY);
    }

    /**
     * Значения summary — динамические, поэтому не биндятся, а рендерятся
     * через loc.get(...) в момент показа. При смене языка (если summary
     * уже заполнен) метод повторно вызывается из onLanguageChanged.
     */
    private void populateSummary(JobPreviewResponse preview) {
        var price = preview.price();

        summaryPagesLabel      .setText(String.valueOf(price.pageCount()));
        summaryCopiesLabel     .setText(String.valueOf(price.copies()));
        summaryColorLabel      .setText(loc.get("COLOR".equals(price.colorMode())
                ? "settings.color.color" : "settings.color.bw"));
        summarySidesLabel      .setText(loc.get(price.doubleSided()
                ? "settings.sides.double" : "settings.sides.single"));
        summaryOrientationLabel.setText(loc.get(
                "PORTRAIT".equals(settingsFlow.orientation())
                        ? "settings.orientation.portrait" : "settings.orientation.landscape"));
        summaryPaperLabel      .setText(settingsFlow.paperSize());
        summaryPriceLabel      .setText(loc.get("price.som", String.valueOf(price.totalSom())));
    }

    // ---- SUMMARY ----
    @FXML
    public void onProceedToPaymentClicked() {
        if (currentPin == null || currentPreview == null) {
            log.warn("Cannot proceed to payment: missing state");
            return;
        }

        if (!printerReadiness.isReady()) {
            log.warn("Printer not ready — blocking payment");
            showOutOfService();
            return;
        }

        changeStep(KioskStep.PAYMENT);
        paymentFlow.start(currentPin, settingsFlow.currentSettings(), jobPages);
    }

    private void showOutOfService() {
        changeStep(KioskStep.OUT_OF_SERVICE);
    }

    // ---- PAYMENT ----
    @FXML
    public void onPaymentRetryClicked() {
        if (currentPin == null) {
            changeStep(KioskStep.HOME);
            return;
        }
        paymentFlow.start(currentPin, settingsFlow.currentSettings(), jobPages);
    }
    @FXML public void onAdminBypassPayment()         {changeStep(KioskStep.PRINTING);}

    @FXML
    public void onPaymentHomeClicked() {
        confirmAbandonAndGoHome();
    }

    /**
     * Показывает кастомный диалог подтверждения на экранах с уже активной
     * сессией (после FILE_INFO). Затемняет весь интерфейс полупрозрачной
     * подложкой и выводит по центру карточку с двумя крупными кнопками.
     * При подтверждении сбрасывает всё и уходит на HOME. PIN при этом НЕ
     * освобождается — он остаётся закреплён за киоском до конца TTL.
     */
    private void confirmAbandonAndGoHome() {
        showConfirmOverlay(
                loc.get("confirm.abandon.title"),
                loc.get("confirm.abandon.message"),
                loc.get("confirm.abandon.yes"),
                loc.get("confirm.stay"),
                this::resetAllAndGoHome);
    }

    /**
     * Подтверждение ухода из сессии сканирования на главный экран. В отличие
     * от печатного варианта, здесь предупреждаем о безвозвратной потере
     * отсканированного документа: resetAllAndGoHome вызывает scanFlow.clear(),
     * который удаляет временные файлы страниц. «Назад» с этого экрана ведёт
     * на превью сканов (сессия жива), поэтому подтверждение нужно только на
     * кнопке «На главный экран».
     */
    /**
     * Подтверждение ухода с экрана превью сканов назад на инструкцию.
     * Экран инструкции — начало новой сессии сканирования, поэтому уже
     * сделанные снимки теряются: при подтверждении явно чистим scanFlow
     * (changeStep сам страницы не удаляет — без clear они подмешались бы к
     * новому скану). PIN/оплаты здесь ещё нет, поэтому используем тот же
     * текст про безвозвратную потерю документа, что и на экране доставки.
     */
    private void confirmDiscardScansAndRestart() {
        showConfirmOverlay(
                loc.get("confirm.scan.restart.title"),
                loc.get("confirm.scan.abandon.message"),
                loc.get("confirm.scan.abandon.yes"),
                loc.get("confirm.stay"),
                () -> {
                    scanFlow.clear();
                    changeStep(KioskStep.SCAN_INSTRUCTION);
                });
    }

    private void confirmAbandonScanAndGoHome() {
        showConfirmOverlay(
                loc.get("confirm.scan.abandon.title"),
                loc.get("confirm.scan.abandon.message"),
                loc.get("confirm.scan.abandon.yes"),
                loc.get("confirm.stay"),
                this::resetAllAndGoHome);
    }

    /**
     * Универсальный модальный оверлей подтверждения поверх всего интерфейса.
     * Фон затемняется; клик по затемнению = «остаться» (безопасный выбор).
     * Тексты приходят уже локализованными от вызывающего кода.
     *
     * @param onConfirm действие при нажатии подтверждающей (красной) кнопки
     */
    private void showConfirmOverlay(String title, String message,
                                    String confirmText, String cancelText,
                                    Runnable onConfirm) {
        // Иконка-предупреждение в кружке.
        FontIcon icon = new FontIcon("fas-exclamation-triangle");
        StackPane iconCircle = new StackPane(icon);
        iconCircle.getStyleClass().add("confirm-icon-circle");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("confirm-title");

        Label messageLabel = new Label(message);
        messageLabel.getStyleClass().add("confirm-message");

        Button stayBtn = new Button(cancelText);
        stayBtn.getStyleClass().addAll("confirm-btn", "confirm-btn-stay");

        Button leaveBtn = new Button(confirmText);
        leaveBtn.getStyleClass().addAll("confirm-btn", "confirm-btn-leave");

        HBox actions = new HBox(stayBtn, leaveBtn);
        actions.getStyleClass().add("confirm-actions");

        VBox dialog = new VBox(iconCircle, titleLabel, messageLabel, actions);
        dialog.getStyleClass().add("confirm-dialog");
        // Карточка должна занимать высоту/ширину строго по содержимому, иначе
        // StackPane растянет её на весь экран по вертикали. USE_PREF_SIZE
        // фиксирует размер по preferred, центрирование оставляем StackPane'у.
        dialog.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

        StackPane overlay = new StackPane(dialog);
        overlay.getStyleClass().add("confirm-overlay");
        StackPane.setAlignment(dialog, Pos.CENTER);

        // Закрытие с плавным затуханием.
        Runnable dismiss = () -> {
            FadeTransition out = new FadeTransition(Duration.millis(120), overlay);
            out.setFromValue(1.0);
            out.setToValue(0.0);
            out.setOnFinished(e -> rootStack.getChildren().remove(overlay));
            out.play();
        };

        stayBtn.setOnAction(e -> dismiss.run());
        // Клик по затемнённому фону (вне карточки) = остаться.
        overlay.setOnMouseClicked(e -> {
            if (e.getTarget() == overlay) {
                dismiss.run();
            }
        });
        leaveBtn.setOnAction(e -> {
            rootStack.getChildren().remove(overlay);
            onConfirm.run();
        });

        rootStack.getChildren().add(overlay);

        // Плавное появление.
        FadeTransition in = new FadeTransition(Duration.millis(140), overlay);
        in.setFromValue(0.0);
        in.setToValue(1.0);
        in.play();
    }

    /**
     * Полный сброс сессии и возврат на HOME. Это ЕДИНСТВЕННАЯ точка
     * завершения сессии — сюда сходятся idle-таймаут (через hideScreensaver),
     * автовозврат после успешной печати, все кнопки «На главный экран» и
     * подтверждённый уход из активного заказа. Поэтому именно здесь язык
     * сбрасывается на дефолтный: биндинги вернут тексты, а листенер —
     * QR-коды, подсветку кнопок и клавиатуру. Никакого отдельного кода
     * отката не требуется.
     */
    private void resetAllAndGoHome() {
        stopAutoReturnCountdown();
        paymentFlow.stop();
        settingsFlow.stop();
        previewFlow.close();
        pageSelection.clear();
        pinEntryFlow.reset();
        scanFlow.clear();                              // чистим временные файлы сканов
        settingsOrigin = SettingsOrigin.PRINT_UPLOAD;  // сброс источника настроек
        scanMode = ScanMode.SCAN;                      // сброс режима сканирования
        currentFile = null;
        currentPin  = null;
        currentPreview = null;
        currentJobId = null;
        loc.resetToDefault();   // язык живёт в рамках сессии
        changeStep(KioskStep.HOME);
    }

    /**
     * Запускает обратный отсчёт автовозврата на экране COMPLETED. Каждую
     * секунду обновляет счётчик и круговой прогресс; по достижении нуля
     * автоматически возвращает на HOME. Любое ручное действие (кнопки
     * экрана ведут через resetAllAndGoHome) останавливает таймер.
     */
    private void startAutoReturnCountdown() {
        stopAutoReturnCountdown();   // на всякий случай, если остался прошлый

        final int total = AUTO_RETURN_SECONDS;
        // Используем массив-обёртку, чтобы менять значение из лямбды.
        final int[] remaining = { total };

        updateAutoReturnUi(remaining[0], total);

        autoReturnTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remaining[0]--;
            updateAutoReturnUi(remaining[0], total);
            if (remaining[0] <= 0) {
                resetAllAndGoHome();   // внутри сам остановит таймер
            }
        }));
        autoReturnTimeline.setCycleCount(total);
        autoReturnTimeline.play();
    }

    /** Останавливает и сбрасывает таймер автовозврата, если он активен. */
    private void stopAutoReturnCountdown() {
        if (autoReturnTimeline != null) {
            autoReturnTimeline.stop();
            autoReturnTimeline = null;
        }
    }

    /** Обновляет текст счётчика и круговой прогресс автовозврата. */
    private void updateAutoReturnUi(int secondsLeft, int total) {
        int shown = Math.max(secondsLeft, 0);
        if (autoReturnCounterLabel != null) {
            autoReturnCounterLabel.setText(shown + " " + secondsWord(shown));
        }
        if (autoReturnProgress != null) {
            autoReturnProgress.setProgress((double) shown / total);
        }
    }

    /**
     * Слово «секунда» в правильной форме для текущего языка.
     * Русский — три формы по правилам склонения; английский — one/other;
     * кыргызский — после числительного форма не меняется.
     */
    private String secondsWord(int n) {
        return switch (loc.getLanguage()) {
            case RU -> {
                int mod100 = n % 100;
                int mod10  = n % 10;
                if (mod100 >= 11 && mod100 <= 14) yield loc.get("time.sec.many");
                if (mod10 == 1)                   yield loc.get("time.sec.one");
                if (mod10 >= 2 && mod10 <= 4)     yield loc.get("time.sec.few");
                yield loc.get("time.sec.many");
            }
            case EN -> loc.get(n == 1 ? "time.sec.one" : "time.sec.many");
            case KY -> loc.get("time.sec.one");
        };
    }

    // ---- PRINTING / COMPLETED ----
    @FXML public void onPrintingHomeClicked()        { resetAllAndGoHome(); }
    @FXML public void onPrintReceiptClicked()        { log.info("TODO: print receipt"); }
    @FXML
    public void onPrintFailedHomeClicked() {
        resetAllAndGoHome();
    }

    @FXML
    public void onOutOfServiceHomeClicked() {
        resetAllAndGoHome();
    }

    @FXML public void onPrintAnotherClicked() {
        resetAllAndGoHome();
    }

    @FXML
    public void onCompletedHomeClicked() {
        resetAllAndGoHome();
    }

    /** Завершение = конец сессии: полный сброс, включая язык. */
    @FXML public void onFinishClicked()              { resetAllAndGoHome(); }

    // ---- BACK BUTTONS ----
    @FXML public void onBackClicked() {
        // Простой откат на один шаг назад; на следующем шаге заменим
        // на нормальную историю переходов.
        switch (currentStep) {
            case SETTINGS -> {
                settingsFlow.stop();
                // «Назад» зависит от того, откуда вошли в настройки:
                // скан → экран действий; ксерокопия → превью сканов; печать → превью файла.
                changeStep(switch (settingsOrigin) {
                    case SCAN -> KioskStep.SCAN_DELIVERY;
                    case COPY -> KioskStep.SCAN_PREVIEW;
                    default   -> KioskStep.FILE_INFO;
                });
            }
            case SCAN_DELIVERY -> {
                // «Назад» с экрана действий — к превью сканов (сессия жива).
                changeStep(KioskStep.SCAN_PREVIEW);
            }
            case SUMMARY -> {
                changeStep(KioskStep.SETTINGS);
            }
            case PAYMENT -> {
                // Шаг назад к подтверждению заказа; сессия и hold сохраняются.
                paymentFlow.stop();
                changeStep(KioskStep.SUMMARY);
            }
            case SCAN_PREVIEW             -> confirmDiscardScansAndRestart();
            case UPLOAD -> {
                if (uploadPinStepShown) {
                    // С подэкрана ввода PIN «назад» ведёт на подэкран QR-кодов,
                    // а не сразу домой.
                    showUploadQrStep();
                } else {
                    // С подэкрана QR-кодов «назад» — на главный экран.
                    // verify ещё не было → hold не взят, сессии нет.
                    pinEntryFlow.reset();
                    changeStep(KioskStep.HOME);
                }
            }
            case SCAN_INSTRUCTION -> {
                pinEntryFlow.reset();
                changeStep(KioskStep.HOME);
            }
            case FILE_INFO -> {
                // Сессия уже активна (PIN held). Уход назад = прерывание → подтверждение.
                confirmAbandonAndGoHome();
            }
            default                       -> changeStep(KioskStep.HOME);
        }
    }

    // ---- SCAN ----
    @FXML public void onStartScanPageClicked() {
        // Шаг 1 → запуск сессии с именем файла (из поля; пустое → случайное),
        // затем первый скан. Имя берём из TextField, если он есть в FXML.
        String name = (scanFileNameField != null) ? scanFileNameField.getText() : null;
        scanFlow.startSession(name);
        scanFlow.scanNextPage();
    }

    @FXML public void onAddScanPageClicked() {
        // «Добавить страницу»: текущие сканы остаются, идём сканировать новую.
        scanFlow.scanNextPage();
    }

    @FXML public void onRescanPageClicked() {
        // «Пересканировать»: удалить текущую и сканировать заново.
        scanFlow.rescanCurrent();
    }

    @FXML public void onDeleteCurrentScanPageClicked() {
        // «Удалить»: если осталась одна страница и её удалили — ведём себя как
        // «Пересканировать» (удалить и сразу запустить новый скан), иначе
        // показываем соседнюю страницу.
        boolean empty = scanFlow.deleteCurrent();
        if (empty) {
            scanFlow.scanNextPage();   // запускает скан → onScanStarted → SCAN_PROGRESS → превью
        }
        // если не пусто — onPageChanged обновит предпросмотр, остаёмся на экране
    }
    @FXML public void onPreviewPrevClicked()           { scanFlow.previous(); }
    @FXML public void onPreviewNextClicked()           { scanFlow.next(); }
    @FXML public void onFinishScanClicked() {
        if (scanMode == ScanMode.COPY) {
            // Ксерокопия: минуя экран выбора действий — сразу в настройки печати.
            uploadScansAndOpenPrintSettings(SettingsOrigin.COPY, null);
        } else {
            changeStep(KioskStep.SCAN_DELIVERY);
        }
    }

    /** «На главный экран» с экрана действий сканирования. */
    @FXML public void onScanDeliveryHomeClicked() {
        // Уход домой сотрёт отсканированный документ — спрашиваем подтверждение.
        confirmAbandonScanAndGoHome();
    }
    @FXML public void onScanDeliveryPrintClicked() {
        uploadScansAndOpenPrintSettings(SettingsOrigin.SCAN, scanDeliveryPrintBtn);
    }

    /**
     * Общий тракт «сканы → печать» для обычного сканирования («Распечатать»
     * на экране действий) и ксерокопии («Завершить» в превью): собирает PDF,
     * заливает на сервер как файл печати (source=COPY), получает PIN и
     * открывает стандартные настройки печати. origin определяет, куда
     * вернёт «Назад» из настроек.
     */
    private void uploadScansAndOpenPrintSettings(SettingsOrigin origin, Button triggerBtn) {
        if (!scanFlow.hasPages()) {
            log.warn("Print requested with no scanned pages");
            return;
        }
        settingsOrigin = origin;
        if (triggerBtn != null) triggerBtn.setDisable(true);

        Task<UploadResponse> task = new Task<>() {
            @Override protected UploadResponse call() throws Exception {
                java.io.File pdf = scanFlow.buildPdf();
                return serverClient.uploadFile(pdf, UploadSource.COPY);
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            if (triggerBtn != null) triggerBtn.setDisable(false);
            UploadResponse resp = task.getValue();
            currentPin = resp.pin();                 // теперь сканы = обычный файл печати
            jobPages = null;                         // скан печатаем целиком
            settingsFlow.start(currentPin);          // запускаем стандартные настройки
            changeStep(KioskStep.SETTINGS);
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            if (triggerBtn != null) triggerBtn.setDisable(false);
            Throwable cause = task.getException();
            log.error("Scan upload for printing failed", cause);
            String detail = (cause != null)
                    ? (cause.getClass().getSimpleName() + ": " + cause.getMessage())
                    : loc.get("error.unknown");
            showConfirmOverlay(loc.get("scanupload.print.failed"), detail,
                    loc.get("dialog.ok"), loc.get("dialog.close"), () -> {});
        }));

        Thread t = new Thread(task, "scan-print-upload");
        t.setDaemon(true);
        t.start();
    }

    @FXML public void onScanDeliveryWebClicked() {
        // Веб-доставка: сканы → PDF → заливаем на сервер → получаем PIN →
        // QR ведёт на прямое скачивание файла по этому PIN. Адрес — публичный
        // (сетевой), чтобы телефон пользователя мог открыть ссылку.
        deliverScans(pin -> serverProperties.getPublicBaseUrl() + "/api/files/" + pin + "/download",
                scanDeliveryWebBtn);
    }

    @FXML public void onScanDeliveryTelegramClicked() {
        // Телеграм-доставка (реализуем следующей): пока используем заглушку.
        deliverScans(pin -> clientProperties.getUpload().getTelegramBotUrl()
                + "?start=get_" + pin, scanDeliveryTelegramBtn);
    }

    /**
     * Общий флоу доставки сканов: собирает PDF, заливает на сервер, получает
     * PIN и по нему строит ссылку (linkBuilder), которую показывает QR-кодом.
     * QR доставки сканов ведёт на конкретный файл по PIN — язык в payload
     * не нужен (страница скачивания контента не имеет).
     */
    private void deliverScans(java.util.function.Function<String, String> linkBuilder,
                              Button triggerBtn) {
        if (!scanFlow.hasPages()) {
            log.warn("Delivery requested with no scanned pages");
            return;
        }
        if (triggerBtn != null) triggerBtn.setDisable(true);

        Task<UploadResponse> task = new Task<>() {
            @Override protected UploadResponse call() throws Exception {
                java.io.File pdf = scanFlow.buildPdf();
                return serverClient.uploadFile(pdf, UploadSource.SCAN);
            }
        };
        task.setOnSucceeded(e -> Platform.runLater(() -> {
            if (triggerBtn != null) triggerBtn.setDisable(false);
            String url = linkBuilder.apply(task.getValue().pin());
            if (scanDeliveryQrImageView != null) {
                scanDeliveryQrImageView.setImage(QrCodeGenerator.generate(url, 220));
            }
            // Место под QR освобождаем, сжав карточки способов в ряд иконок.
            setDeliveryCompact(true);
            // Контейнер QR по умолчанию скрыт — показываем его.
            if (scanDeliveryQrBox != null) {
                scanDeliveryQrBox.setVisible(true);
                scanDeliveryQrBox.setManaged(true);
            }
            if (scanDeliveryInfoLabel != null) {
                scanDeliveryInfoLabel.setText(loc.get("scan.delivery.scan.qr"));
                scanDeliveryInfoLabel.setVisible(true);
                scanDeliveryInfoLabel.setManaged(true);
            }
        }));
        task.setOnFailed(e -> Platform.runLater(() -> {
            if (triggerBtn != null) triggerBtn.setDisable(false);
            Throwable cause = task.getException();
            log.error("Scan delivery upload failed", cause);
            String detail = (cause != null)
                    ? (cause.getClass().getSimpleName() + ": " + cause.getMessage())
                    : loc.get("error.unknown");
            showConfirmOverlay(loc.get("scanupload.delivery.failed"), detail,
                    loc.get("dialog.ok"), loc.get("dialog.close"), () -> {});
        }));

        Thread t = new Thread(task, "scan-delivery-upload");
        t.setDaemon(true);
        t.start();
    }

    // ---- ADMIN ----
    @FXML public void onLogoClicked()             { log.info("TODO: logo click counter / admin"); }
    @FXML public void onAdminRefreshClicked()     { log.info("TODO: refresh admin stats"); }
    @FXML public void onAdminBackClicked()        { changeStep(KioskStep.HOME); }
    @FXML public void onAdminTestPrintClicked()   { log.info("TODO: test print"); }

    private PinEntryFlow.Listener buildPinEntryListener() {
        return new PinEntryFlow.Listener() {

            @Override
            public void onBufferChanged(String buffer) {
                // Заполняем индикатор PIN (например, точками или цифрами).
                // Сейчас просто показываем сами цифры — позже сделаем красивее.
                selectedPinCodeLabel.setText(buffer);
                hideStatus();
                hideError();
            }

            @Override
            public void onShortPin() {
                showError(loc.get("pin.error.short"));
            }

            @Override
            public void onLoading() {
                hideError();
                showStatus(loc.get("pin.checking"));
                pinSubmitBtn.setDisable(true);
            }

            @Override
            public void onSuccess(String pin, VerifyResponse response) {
                currentFile = response;
                currentPin  = pin;
                hideStatus();
                hideError();
                pinSubmitBtn.setDisable(false);
                selectedPinCodeLabel.setText("");

                // Заполняем экран "файл найден"
                showFileInfo(response);
                changeStep(KioskStep.FILE_INFO);
                previewFlow.start(response);
            }

            @Override
            public void onPinNotFound() {
                pinSubmitBtn.setDisable(false);
                hideStatus();
                selectedPinCodeLabel.setText("");
                showError(loc.get("pin.error.notfound"));
            }

            @Override
            public void onPinLocked() {
                pinSubmitBtn.setDisable(false);
                hideStatus();
                selectedPinCodeLabel.setText("");
                showError(loc.get("pin.error.locked"));
            }

            @Override
            public void onServerUnavailable() {
                pinSubmitBtn.setDisable(false);
                hideStatus();
                selectedPinCodeLabel.setText("");
                showError(loc.get("pin.error.server"));
            }
        };
    }

    private void showStatus(String text) {
        pinStatusLabel.setText(text);
        pinStatusLabel.setVisible(true);
        pinStatusLabel.setManaged(true);
    }

    private void hideStatus() {
        pinStatusLabel.setVisible(false);
        pinStatusLabel.setManaged(false);
    }

    private void showError(String text) {
        pinErrorLabel.setText(text);
        pinErrorLabel.setVisible(true);
        pinErrorLabel.setManaged(true);
    }

    private void hideError() {
        pinErrorLabel.setVisible(false);
        pinErrorLabel.setManaged(false);
    }

    private PreviewFlow.Listener buildPreviewListener() {
        return new PreviewFlow.Listener() {

            @Override
            public void onLoading() {
                // Текст previewLoadingLabel забинден на ключ preview.loading —
                // setText здесь больше не нужен (и запрещён для bound-свойства).
                showPreviewLoading();
                pageSelection.clear();
            }

            @Override
            public void onDocumentReady(int totalPages) {
                // Строим список страниц с чекбоксами; миниатюры подтянутся асинхронно.
                pageSelection.setPages(totalPages);
            }

            @Override
            public void onPageRendered(Image image, int pageIndex, int totalPages) {
                previewImageView.setImage(image);
                showPreviewImage();
                updatePreviewNav(pageIndex, totalPages);
            }

            @Override
            public void onError(String message) {
                // message приходит из PreviewFlow — переведём на уровне flow
                // (следующая итерация: flow отдаёт ключ, контроллер — loc.get).
                previewErrorLabel.setText(message);
                showPreviewError();
            }
        };
    }

    private void updatePreviewNav(int pageIndex, int totalPages) {
        if (totalPages <= 1) {
            previewNavBox.setVisible(false);
            previewNavBox.setManaged(false);
            return;
        }
        previewNavBox.setVisible(true);
        previewNavBox.setManaged(true);
        previewPageLabel.setText((pageIndex + 1) + " / " + totalPages);
        printPreviewPrevBtn.setDisable(pageIndex <= 0);
        printPreviewNextBtn.setDisable(pageIndex >= totalPages - 1);
    }

    private void showPreviewLoading() {
        previewLoadingBox.setVisible(true);   previewLoadingBox.setManaged(true);
        previewErrorBox  .setVisible(false);  previewErrorBox  .setManaged(false);
        previewImageView .setVisible(false);
        previewNavBox    .setVisible(false);  previewNavBox    .setManaged(false);
    }

    private void showPreviewImage() {
        previewLoadingBox.setVisible(false);  previewLoadingBox.setManaged(false);
        previewErrorBox  .setVisible(false);  previewErrorBox  .setManaged(false);
        previewImageView .setVisible(true);
    }

    private void showPreviewError() {
        previewLoadingBox.setVisible(false);  previewLoadingBox.setManaged(false);
        previewErrorBox  .setVisible(true);   previewErrorBox  .setManaged(true);
        previewImageView .setVisible(false);
        previewNavBox    .setVisible(false);  previewNavBox    .setManaged(false);
    }

    /**
     * Имя файла и размер — динамические. Заголовок «Файл найден» забинден
     * на ключ fileinfo.found, поэтому здесь его больше не трогаем.
     */
    private void showFileInfo(VerifyResponse response) {
        fileInfoLabel.setText(
                response.originalFilename() + "\n" +
                        formatSize(response.fileSize())
        );
    }

    /** Человекочитаемый размер файла с локализованными единицами. */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return loc.get("size.b", String.valueOf(bytes));
        }
        if (bytes < 1024 * 1024) {
            return loc.get("size.kb", String.valueOf(bytes / 1024));
        }
        return loc.get("size.mb", String.format("%.1f", bytes / 1024.0 / 1024.0));
    }

    private PrintSettingsFlow.Listener buildSettingsListener() {
        return new PrintSettingsFlow.Listener() {

            @Override
            public void onSettingsChanged(PrintSettings settings) {
                // Лейблы значений
                copiesValueLabel.setText(String.valueOf(settings.copies()));

                // Подсветка активных кнопок
                setActive(bwBtn,           PrintSettingsFlow.COLOR_BW.equals(settings.colorMode()));
                setActive(colorBtn,        PrintSettingsFlow.COLOR_COLOR.equals(settings.colorMode()));
                setActive(singleSideBtn,   !settings.doubleSided());
                setActive(doubleSideBtn,    settings.doubleSided());
                setActive(portraitBtn,     PrintSettingsFlow.ORIENTATION_PORTRAIT.equals(settings.orientation()));
                setActive(landscapeBtn,    PrintSettingsFlow.ORIENTATION_LANDSCAPE.equals(settings.orientation()));
                setActive(a4Btn,           PrintSettingsFlow.PAPER_A4.equals(settings.paperSize()));
            }

            @Override
            public void onPriceLoading() {
                // На экране SETTINGS цены нет, но кнопку "Далее" блокируем,
                // чтобы юзер не успел уйти со стейлой ценой.
                settingsNextBtn.setDisable(true);
            }

            @Override
            public void onPriceReady(JobPreviewResponse response) {
                currentPreview = response;
                settingsNextBtn.setDisable(false);
                applyDuplexAvailability(response.price().pageCount());
            }

            @Override
            public void onPriceError(String message) {
                currentPreview = null;
                settingsNextBtn.setDisable(true);
                log.warn("Preview error shown: {}", message);
                // На текущем экране кнопка просто остаётся disabled.
                // На SUMMARY мы покажем то же сообщение явно (см. ниже).
            }
        };
    }

    /**
     * Двусторонняя печать недоступна для одностраничного документа.
     * При pageCount == 1 кнопку «Двусторонняя» гасим (disabled + серый стиль)
     * и принудительно переключаем настройку на одностороннюю, чтобы не уйти
     * на оплату с бессмысленным duplex. При большем числе страниц — включаем.
     */
    private void applyDuplexAvailability(int pageCount) {
        boolean duplexAllowed = pageCount > 1;
        if (doubleSideBtn != null) {
            doubleSideBtn.setDisable(!duplexAllowed);
        }
        if (!duplexAllowed && settingsFlow.doubleSided()) {
            // Сбрасываем на одностороннюю, только если двусторонняя реально была
            // выбрана. Проверка значения обязательна: setDoubleSided всегда
            // триггерит пересчёт цены → onPriceReady → сюда же, и без этого
            // guard'а на одностраничном файле получился бы бесконечный цикл.
            settingsFlow.setDoubleSided(false);
        }
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        if (active) {
            if (!node.getStyleClass().contains("option-card-active"))
                node.getStyleClass().add("option-card-active");
        } else {
            node.getStyleClass().remove("option-card-active");
            node.getStyleClass().add("option-card");
        }
    }

    private PaymentSessionFlow.Listener buildPaymentListener() {
        return new PaymentSessionFlow.Listener() {

            @Override
            public void onLoading() {
                paymentLoadingLabel.setText(loc.get("payment.creating"));
                // Инструкция видна и во время создания сессии — переводим сразу,
                // иначе до onSessionReady висит русский текст-дефолт из FXML.
                paymentInstructionLabel.setText(loc.get("payment.instruction"));
                showPaymentLoading();
            }

            @Override
            public void onSessionReady(PaymentSessionDto session) {
                paymentAmountLabel.setText(
                        loc.get("price.som", String.valueOf(session.priceSom())));
                paymentInstructionLabel.setText(loc.get("payment.instruction"));

                // Генерируем QR из paymentUrl. Payload оплаты приходит от
                // платёжного шлюза и от языка киоска НЕ зависит — не трогаем.
                try {
                    Image qrImage = QrCodeGenerator.generate(session.paymentUrl(), 280);
                    qrCodeImageView.setImage(qrImage);
                } catch (Exception e) {
                    log.error("Failed to generate QR code", e);
                    paymentErrorLabel.setText(loc.get("payment.qr.failed"));
                    showPaymentError();
                    return;
                }

                currentJobId = session.jobId();
                showPaymentReady();
            }

            @Override
            public void onCountdownTick(int secondsLeft) {
                int min = secondsLeft / 60;
                int sec = secondsLeft % 60;
                paymentInstructionLabel.setText(loc.get("payment.countdown",
                        String.valueOf(min), String.format("%02d", sec)));
            }

            @Override
            public void onPaid(UUID jobId) {
                log.info("Payment confirmed for job={}", jobId);
                currentJobId = jobId;
                changeStep(KioskStep.PRINTING);
                printFlow.start(jobId, currentFile, settingsFlow.currentSettings(), jobPages);
            }

            @Override
            public void onExpired() {
                log.info("Payment session expired");
                paymentFlow.stop();
                paymentErrorLabel.setText(loc.get("payment.expired"));
                showPaymentError();
            }

            @Override
            public void onError(String message) {
                // message приходит из PaymentSessionFlow — переведём на уровне
                // flow (следующая итерация: flow отдаёт ключ вместо текста).
                paymentErrorLabel.setText(message);
                showPaymentError();
            }
        };
    }

    private void showPaymentLoading() {
        paymentLoadingBox.setVisible(true);   paymentLoadingBox.setManaged(true);
        paymentReadyBox  .setVisible(false);  paymentReadyBox  .setManaged(false);
        paymentErrorBox  .setVisible(false);  paymentErrorBox  .setManaged(false);
    }

    private void showPaymentReady() {
        paymentLoadingBox.setVisible(false);  paymentLoadingBox.setManaged(false);
        paymentReadyBox  .setVisible(true);   paymentReadyBox  .setManaged(true);
        paymentErrorBox  .setVisible(false);  paymentErrorBox  .setManaged(false);
    }

    private void showPaymentError() {
        paymentLoadingBox.setVisible(false);  paymentLoadingBox.setManaged(false);
        paymentReadyBox  .setVisible(false);  paymentReadyBox  .setManaged(false);
        paymentErrorBox  .setVisible(true);   paymentErrorBox  .setManaged(true);
    }

    private PrintFlow.Listener buildPrintListener() {
        return new PrintFlow.Listener() {

            @Override
            public void onStarted() {
                printingStatusLabel.setText(loc.get("printing.starting"));
            }

            @Override
            public void onStatus(String message) {
                // message приходит из PrintFlow — переведём на уровне flow
                // (следующая итерация: flow отдаёт ключ вместо текста).
                printingStatusLabel.setText(message);
            }

            @Override
            public void onCompleted() {
                log.info("Print completed, transitioning to COMPLETED screen");
                changeStep(KioskStep.COMPLETED);
                startAutoReturnCountdown();
            }

            @Override
            public void onFailed(String message) {
                log.warn("Print failed: {}", message);
                printErrorMessageLabel.setText(message);
                printErrorPinLabel.setText(loc.get("printfail.pin", currentPin));  // для ручного refund'а
                changeStep(KioskStep.PRINT_FAILED);
            }
        };
    }
}