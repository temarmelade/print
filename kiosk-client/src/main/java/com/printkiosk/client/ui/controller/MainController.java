package com.printkiosk.client.ui.controller;

import com.printkiosk.client.ui.state.Language;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import com.printkiosk.client.api.KioskServerClient;
import com.printkiosk.client.service.PinEntryFlow;
import com.printkiosk.shared.api.dto.VerifyResponse;
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
 * Главный контроллер киоска. На этом этапе — скелет без логики:
 * все обработчики кликов логируют TODO и не делают ничего.
 * <p>
 * Подключение сервисов будет идти пошагово:
 *  - PinEntryFlow (вызов server.verify)
 *  - PrintSettingsFlow (управление настройками)
 *  - PrintFlowService (создание job, печать)
 *  - PaymentSessionFlow (оплата)
 *  - AdminFlow (статистика)
 */
@Slf4j
@Component
@Scope("prototype")
public class MainController {

    // ══════════════════════════════════════════════════════════════════════
    //  SCREENS
    // ══════════════════════════════════════════════════════════════════════

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
    @FXML private Button langKgBtn;
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

    @FXML private Label selectedPinCodeLabel;
    @FXML private Button pinBackspaceBtn;
    @FXML private Button pinSubmitBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  FILE INFO
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label fileFoundTitleLabel;
    @FXML private Label fileInfoLabel;
    @FXML private Button goToSettingsBtn;

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

    // ══════════════════════════════════════════════════════════════════════
    //  PRINTING
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label printingTitleLabel;
    @FXML private Label printingStatusLabel;
    @FXML private ImageView printingAnimation;
    @FXML private Label printingHintLabel;
    @FXML private Button printingHomeBtn;
    @FXML private VBox  printFailedScreen;
    @FXML private Label printErrorMessageLabel;
    @FXML private Label printErrorPinLabel;
    @FXML private VBox outOfServiceScreen;

    // если ещё нет
    // ══════════════════════════════════════════════════════════════════════
    //  COMPLETED
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label completedMessageLabel;
    @FXML private Label completedSubMessageLabel;
    @FXML private Button printAnotherBtn;
    @FXML private Button completedHomeBtn;
    @FXML private ImageView completedMascot;
    @FXML private Label autoReturnCounterLabel;
    @FXML private Label autoReturnSecondsLabel;
    @FXML private ProgressIndicator autoReturnProgress;

    // ══════════════════════════════════════════════════════════════════════
    //  SCAN / COPY
    // ══════════════════════════════════════════════════════════════════════

    @FXML private Label scanInstructionTitleLabel;
    @FXML private Label scanInstructionDescLabel;
    @FXML private Button startScanPageBtn;
    @FXML private StackPane scanDeliveryQrBox;
    @FXML private Label scanProgressTitleLabel;
    @FXML private Label scanProgressStatusLabel;
    @FXML private ProgressBar scanProgressBar;

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
    @FXML private Button scanDeliveryBackBtn;

    // ══════════════════════════════════════════════════════════════════════
    //  ADMIN
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
    private Language  currentLang = Language.RU;
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

    public MainController(PinEntryFlow pinEntryFlow, PreviewFlow previewFlow,  PrintSettingsFlow settingsFlow, PaymentSessionFlow paymentFlow, PrintFlow printFlow, PrinterReadinessService printerReadiness) {
        this.pinEntryFlow = pinEntryFlow;
        this.previewFlow = previewFlow;
        this.settingsFlow = settingsFlow;
        this.paymentFlow = paymentFlow;
        this.printFlow = printFlow;
        this.printerReadiness = printerReadiness;
    }


    // ══════════════════════════════════════════════════════════════════════
    //  INITIALIZE
    // ══════════════════════════════════════════════════════════════════════

    @FXML
    public void initialize() {
        log.info("MainController initialized");
        pinEntryFlow.setListener(buildPinEntryListener());
        previewFlow.setListener(buildPreviewListener());
        settingsFlow.setListener(buildSettingsListener());
        paymentFlow.setListener(buildPaymentListener());
        printFlow.setListener(buildPrintListener());
        if (uploadQrStep != null)  { uploadQrStep.setVisible(true);   uploadQrStep.setManaged(true); }
        if (emojiCodeStep != null) { emojiCodeStep.setVisible(false); emojiCodeStep.setManaged(false); }
        showOnly(homeScreen);
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
        switch (step) {
            case HOME             -> showOnly(homeScreen);
            case UPLOAD           -> showOnly(uploadScreen);
            case FILE_INFO        -> showOnly(fileInfoScreen);
            case SETTINGS         -> showOnly(settingsScreen);
            case SUMMARY          -> showOnly(summaryScreen);
            case PAYMENT          -> showOnly(paymentScreen);
            case PRINTING         -> showOnly(printingScreen);
            case COMPLETED        -> showOnly(completedScreen);
            case SCAN_INSTRUCTION -> showOnly(scanInstructionScreen);
            case SCAN_PROGRESS    -> showOnly(scanProgressScreen);
            case SCAN_PREVIEW     -> showOnly(scanPreviewScreen);
            case SCAN_DELIVERY    -> showOnly(scanDeliveryScreen);
            case ADMIN            -> showOnly(adminScreen);
            case HELP             -> showOnly(helpScreen);
            case PRINT_FAILED   -> showOnly(printFailedScreen);
            case OUT_OF_SERVICE -> showOnly(outOfServiceScreen);
        }
        log.info("Step → {}", step);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EVENT HANDLERS — все TODO, на следующих шагах будем заполнять
    // ══════════════════════════════════════════════════════════════════════

    // ---- HOME ----
    @FXML public void onPrintOperationSelected()  { changeStep(KioskStep.UPLOAD); }
    @FXML public void onCopyOperationSelected()   { changeStep(KioskStep.SCAN_INSTRUCTION); }
    @FXML public void onScanOperationSelected()   { changeStep(KioskStep.SCAN_INSTRUCTION); }
    @FXML public void onHelpClicked()             { changeStep(KioskStep.HELP); }
    @FXML public void onHelpBackClicked()         { changeStep(KioskStep.HOME); }

    // ---- LANGUAGE ----
    @FXML public void onSelectRuTop() { log.info("TODO: switch to RU"); }
    @FXML public void onSelectKgTop() { log.info("TODO: switch to KG"); }
    @FXML public void onSelectEnTop() { log.info("TODO: switch to EN"); }

    // ---- UPLOAD / PIN ----
    @FXML
    public void onGoToEmojiCodeClicked() {
        if (uploadQrStep != null)    { uploadQrStep.setVisible(false);    uploadQrStep.setManaged(false); }
        if (emojiCodeStep != null)   { emojiCodeStep.setVisible(true);    emojiCodeStep.setManaged(true); }
        pinEntryFlow.reset();
        if (selectedPinCodeLabel != null) selectedPinCodeLabel.setText("");
    }

    @FXML
    public void onBackToUploadMethodsClicked() {
        if (emojiCodeStep != null)   { emojiCodeStep.setVisible(false);   emojiCodeStep.setManaged(false); }
        if (uploadQrStep != null)    { uploadQrStep.setVisible(true);     uploadQrStep.setManaged(true); }
        pinEntryFlow.reset();
    }

    @FXML
    public void onDigitButtonClicked(ActionEvent e) {
        Object src = e.getSource();
        if (src instanceof Button btn) {
            // У кнопок numpad'а text — это сама цифра ("0".."9").
            pinEntryFlow.pressDigit(btn.getText().trim());
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
        // Запускаем preview-цикл с текущим PIN из buffer'а уже нет —
        // PIN не сохранён в clientside. Сохраняем его в state в onSuccess.
        // (см. ниже)
        settingsFlow.start(currentPin);
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

    private void populateSummary(JobPreviewResponse preview) {
        var price = preview.price();

        summaryPagesLabel      .setText(String.valueOf(price.pageCount()));
        summaryCopiesLabel     .setText(String.valueOf(price.copies()));
        summaryColorLabel      .setText("COLOR".equals(price.colorMode()) ? "Цветная" : "Чёрно-белая");
        summarySidesLabel      .setText(price.doubleSided() ? "Двусторонняя" : "Односторонняя");
        summaryOrientationLabel.setText("PORTRAIT".equals(settingsFlow.orientation()) ? "Книжная" : "Альбомная");
        summaryPaperLabel      .setText(settingsFlow.paperSize());
        summaryPriceLabel      .setText(price.totalSom() + " сом");
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
        paymentFlow.start(currentPin, settingsFlow.currentSettings());
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
        paymentFlow.start(currentPin, settingsFlow.currentSettings());
    }
    @FXML public void onAdminBypassPayment()         {changeStep(KioskStep.PRINTING);}

    @FXML
    public void onPaymentHomeClicked() {
        confirmAbandonAndGoHome();
    }

    /**
     * Показывает диалог подтверждения на экранах с уже активной сессией
     * (после FILE_INFO). При подтверждении сбрасывает всё и уходит на HOME.
     * PIN при этом НЕ освобождается — он остаётся закреплён за этим киоском
     * до истечения 10-минутного TTL, даже если печать не состоялась.
     */
    private void confirmAbandonAndGoHome() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Вернуться на главный экран?");
        alert.setHeaderText(null);
        alert.setContentText("Текущий заказ будет отменён. Продолжить?");

        ButtonType yes = new ButtonType("Да, вернуться", ButtonBar.ButtonData.OK_DONE);
        ButtonType no  = new ButtonType("Остаться",       ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(yes, no);

        alert.showAndWait().ifPresent(choice -> {
            if (choice == yes) {
                resetAllAndGoHome();
            }
        });
    }

    private void resetAllAndGoHome() {
        paymentFlow.stop();
        settingsFlow.stop();
        previewFlow.close();
        pinEntryFlow.reset();
        currentFile = null;
        currentPin  = null;
        currentPreview = null;
        currentJobId = null;
        changeStep(KioskStep.HOME);
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

    @FXML public void onFinishClicked()              { changeStep(KioskStep.HOME); }

    // ---- BACK BUTTONS ----
    @FXML public void onBackClicked() {
        // Простой откат на один шаг назад; на следующем шаге заменим
        // на нормальную историю переходов.
        switch (currentStep) {
            case SETTINGS -> {
                settingsFlow.stop();
                changeStep(KioskStep.FILE_INFO);
            }
            case SUMMARY -> {
                changeStep(KioskStep.SETTINGS);
            }
            case PAYMENT -> {
                // Шаг назад к подтверждению заказа; сессия и hold сохраняются.
                paymentFlow.stop();
                changeStep(KioskStep.SUMMARY);
            }
            case SCAN_PREVIEW             -> changeStep(KioskStep.SCAN_INSTRUCTION);
            case UPLOAD, SCAN_INSTRUCTION -> {
                // На UPLOAD ещё нет verify → hold не взят, сессии нет. Просто домой.
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
    @FXML public void onStartScanPageClicked()         { log.info("TODO: start scan"); }
    @FXML public void onAddScanPageClicked()           { log.info("TODO: add scan page"); }
    @FXML public void onRescanPageClicked()            { log.info("TODO: rescan page"); }
    @FXML public void onDeleteCurrentScanPageClicked() { log.info("TODO: delete scan page"); }
    @FXML public void onPreviewPrevClicked()           { log.info("TODO: scan preview prev"); }
    @FXML public void onPreviewNextClicked()           { log.info("TODO: scan preview next"); }
    @FXML public void onFinishScanClicked()            { changeStep(KioskStep.SCAN_DELIVERY); }
    @FXML public void onScanDeliveryPrintClicked()     { log.info("TODO: scan delivery → print"); }
    @FXML public void onScanDeliveryWebClicked()       { log.info("TODO: scan delivery → web"); }
    @FXML public void onScanDeliveryTelegramClicked()  { log.info("TODO: scan delivery → telegram"); }

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
                showError("Введите все 4 цифры");
            }

            @Override
            public void onLoading() {
                hideError();
                showStatus("Проверяем код...");
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
                showError("Код не найден или истёк. Проверьте, что код актуален.");
            }

            @Override
            public void onPinLocked() {
                pinSubmitBtn.setDisable(false);
                hideStatus();
                selectedPinCodeLabel.setText("");
                showError("Этот код сейчас используется на другом терминале.");
            }

            @Override
            public void onServerUnavailable() {
                pinSubmitBtn.setDisable(false);
                hideStatus();
                selectedPinCodeLabel.setText("");
                showError("Сервер недоступен. Попробуйте ещё раз через минуту.");
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
                previewLoadingLabel.setText("Загружаем документ...");
                showPreviewLoading();
            }

            @Override
            public void onPageRendered(Image image, int pageIndex, int totalPages) {
                previewImageView.setImage(image);
                showPreviewImage();
                updatePreviewNav(pageIndex, totalPages);
            }

            @Override
            public void onError(String message) {
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

    private void showFileInfo(VerifyResponse response) {
        fileFoundTitleLabel.setText("Файл найден");
        fileInfoLabel.setText(
                response.originalFilename() + "\n" +
                        formatSize(response.fileSize())
        );
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024)         return bytes + " Б";
        if (bytes < 1024 * 1024)  return (bytes / 1024) + " КБ";
        return String.format("%.1f МБ", bytes / 1024.0 / 1024.0);
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
                paymentLoadingLabel.setText("Создаём платёжную сессию...");
                showPaymentLoading();
            }

            @Override
            public void onSessionReady(PaymentSessionDto session) {
                paymentAmountLabel.setText(session.priceSom() + " сом");
                paymentInstructionLabel.setText("Отсканируйте QR-код в банковском приложении");

                // Генерируем QR из paymentUrl
                try {
                    Image qrImage = QrCodeGenerator.generate(session.paymentUrl(), 280);
                    qrCodeImageView.setImage(qrImage);
                } catch (Exception e) {
                    log.error("Failed to generate QR code", e);
                    paymentErrorLabel.setText("Не удалось сгенерировать QR-код");
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
                paymentInstructionLabel.setText(String.format(
                        "Отсканируйте QR-код в банковском приложении • Осталось %d:%02d",
                        min, sec));
            }

            @Override
            public void onPaid(UUID jobId) {
                log.info("Payment confirmed for job={}", jobId);
                currentJobId = jobId;
                changeStep(KioskStep.PRINTING);
                printFlow.start(jobId, currentFile, settingsFlow.currentSettings());
            }

            @Override
            public void onExpired() {
                log.info("Payment session expired");
                paymentFlow.stop();
                paymentErrorLabel.setText("Время оплаты истекло. Вернитесь на главную и попробуйте снова.");
                showPaymentError();
            }

            @Override
            public void onError(String message) {
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
                printingStatusLabel.setText("Запускаем печать...");
            }

            @Override
            public void onStatus(String message) {
                printingStatusLabel.setText(message);
            }

            @Override
            public void onCompleted() {
                log.info("Print completed, transitioning to COMPLETED screen");
                changeStep(KioskStep.COMPLETED);
                // Автовозврат на HOME через N секунд (если у тебя уже есть таймер
                // в onCompleted-экране — он сработает; если нет — можно
                // запустить здесь Timeline на 15 сек).
            }

            @Override
            public void onFailed(String message) {
                log.warn("Print failed: {}", message);
                printErrorMessageLabel.setText(message);
                printErrorPinLabel.setText("PIN: " + currentPin);    // для ручного refund'а
                changeStep(KioskStep.PRINT_FAILED);
            }
        };
    }
}