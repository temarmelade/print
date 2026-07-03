package com.printkiosk.server.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Отдаёт страницу загрузки файлов по чистому URL /upload (и /upload/).
 *
 * Зачем нужен: Spring Boot автоматически подставляет index.html только для
 * корня "/", но НЕ для вложенных папок вроде /upload/. Поэтому запрос
 * /upload/ давал 404, хотя файл static/upload/index.html существует и
 * открывается по прямому пути /upload/index.html. Этот контроллер форвардит
 * оба варианта на реальный статический файл.
 */
@Controller
public class UploadPageController {

    @GetMapping({"/upload", "/upload/"})
    public String uploadPage() {
        // forward (не redirect) — URL в браузере остаётся /upload,
        // а Spring отдаёт статический index.html из static/upload/.
        return "forward:/upload/index.html";
    }
}