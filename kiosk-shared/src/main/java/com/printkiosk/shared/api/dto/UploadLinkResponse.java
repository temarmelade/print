package com.printkiosk.shared.api.dto;

/**
 * Ссылка на веб-страницу загрузки для QR-кода на экране киоска.
 *
 * @param url готовый адрес вида {@code https://домен/upload?k=<kioskId>}.
 *            Параметр языка ({@code lang}) киоск добавляет сам — язык
 *            меняется мгновенно, без запроса к серверу.
 */
public record UploadLinkResponse(String url) {}
