package com.printkiosk.client.ui;

import com.printkiosk.client.config.ServerProperties;
import com.printkiosk.shared.api.AdMediaType;
import com.printkiosk.shared.api.dto.AdCreativeDto;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class IdleScreensaver extends StackPane {

    private final String baseUrl;

    private final ImageView imageView = new ImageView();
    private final MediaView mediaView = new MediaView();

    private List<AdCreativeDto> playlist = List.of();
    private int index = 0;

    private MediaPlayer currentPlayer;
    private PauseTransition imageTimer;

    private static final int DEFAULT_IMAGE_SECONDS = 8;

    public IdleScreensaver(ServerProperties serverProperties) {
        this.baseUrl = serverProperties.getBaseUrl();

        getStyleClass().add("idle-screensaver");
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        mediaView.setPreserveRatio(true);

        imageView.fitWidthProperty().bind(widthProperty());
        imageView.fitHeightProperty().bind(heightProperty());
        mediaView.fitWidthProperty().bind(widthProperty());
        mediaView.fitHeightProperty().bind(heightProperty());

        getChildren().addAll(imageView, mediaView);
        setVisible(false);
        setManaged(false);
    }

    public void start(List<AdCreativeDto> items) {
        this.playlist = (items != null) ? items : List.of();
        this.index = 0;
        if (playlist.isEmpty()) {
            log.debug("Screensaver start skipped: empty playlist");
            return;
        }
        setVisible(true);
        setManaged(true);
        playCurrent();
    }

    public void stop() {
        disposeCurrent();
        setVisible(false);
        setManaged(false);
    }

    public boolean hasContent() {
        return !playlist.isEmpty();
    }

    private void playCurrent() {
        disposeCurrent();
        if (playlist.isEmpty()) return;

        AdCreativeDto ad = playlist.get(index);
        String url = resolveUrl(ad.mediaUrl());

        if (ad.mediaType() == AdMediaType.VIDEO) {
            playVideo(url);
        } else {
            playImage(ad, url);
        }
    }

    private void playImage(AdCreativeDto ad, String url) {
        imageView.setVisible(true);
        mediaView.setVisible(false);

        imageView.setImage(new Image(url, true));

        int seconds = (ad.durationSec() != null && ad.durationSec() > 0)
                ? ad.durationSec()
                : DEFAULT_IMAGE_SECONDS;

        imageTimer = new PauseTransition(Duration.seconds(seconds));
        imageTimer.setOnFinished(e -> next());
        imageTimer.play();
    }

    private void playVideo(String url) {
        imageView.setVisible(false);
        mediaView.setVisible(true);

        try {
            Media media = new Media(url);
            currentPlayer = new MediaPlayer(media);
            currentPlayer.setOnEndOfMedia(this::next);
            currentPlayer.setOnError(() -> {
                log.warn("Screensaver video error for {}: {}", url,
                        currentPlayer.getError() != null ? currentPlayer.getError().getMessage() : "unknown");
                next();
            });
            mediaView.setMediaPlayer(currentPlayer);
            currentPlayer.play();
        } catch (Exception e) {
            log.warn("Failed to play video {}: {}", url, e.getMessage());
            next();
        }
    }

    private void next() {
        if (playlist.isEmpty()) return;
        index = (index + 1) % playlist.size();
        Platform.runLater(this::playCurrent);
    }

    private void disposeCurrent() {
        if (imageTimer != null) {
            imageTimer.stop();
            imageTimer = null;
        }
        if (currentPlayer != null) {
            currentPlayer.stop();
            currentPlayer.dispose();
            currentPlayer = null;
        }
    }

    private String resolveUrl(String mediaUrl) {
        if (mediaUrl == null) return "";
        if (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) {
            return mediaUrl;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + (mediaUrl.startsWith("/") ? mediaUrl : "/" + mediaUrl);
    }
}
