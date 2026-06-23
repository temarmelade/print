package com.printkiosk.client.ui.state;


/*
 * Перечисление всех этапов основного сценария (Happy Path) для киоска.
 */
public enum KioskStep {
    IDLE,
    LANGUAGE_SELECTION,
    FILE_UPLOAD,
    PREVIEW,
    PAYMENT,
    PRINTING,
    COMPLETED,
    SCAN_INSTRUCTION,
    SCAN_PROGRESS,
    SCAN_PREVIEW,
    SCAN_DELIVERY
}
