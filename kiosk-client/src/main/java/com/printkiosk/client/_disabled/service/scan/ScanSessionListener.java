package com.printkiosk.client.service.scan;

public interface ScanSessionListener {

    void onScanPageCompleted();

    void onScanFailed(Throwable cause);

    void onScanSessionFinished();
}
