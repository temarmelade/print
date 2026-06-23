package com.printkiosk.client.service.preview;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

@Slf4j
class ImagePreviewSession implements PreviewSession {

    private final BufferedImage image;

    ImagePreviewSession(Path file) throws Exception {
        BufferedImage loaded = ImageIO.read(file.toFile());
        if (loaded == null) {
            throw new IllegalArgumentException(
                    "Failed to decode image (unsupported or corrupted file): " + file);
        }
        this.image = loaded;
    }

    @Override
    public int getPageCount() {
        return 1;
    }

    @Override
    public BufferedImage renderPage(int pageIndex) {
        if (pageIndex != 0) {
            throw new IndexOutOfBoundsException(
                    "Image preview has only one page; requested " + pageIndex);
        }
        return image;
    }

    @Override
    public void close() {

    }
}
