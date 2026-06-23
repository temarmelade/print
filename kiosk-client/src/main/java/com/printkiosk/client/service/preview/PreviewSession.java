package com.printkiosk.client.service.preview;

import java.awt.image.BufferedImage;

public interface PreviewSession extends AutoCloseable {

    int getPageCount();

    BufferedImage renderPage(int pageIndex) throws Exception;

    @Override
    void close();
}
