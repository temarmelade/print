package com.printkiosk.server.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

class ByteArrayMultipartFile implements MultipartFile {
    private final byte[] content;
    private final String originalFilename;
    private final String contentType;

    ByteArrayMultipartFile(byte[] content, String originalFilename, String contentType) {
        this.content = content;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
    }

    @Override public String getName()              { return "file"; }
    @Override public String getOriginalFilename()  { return originalFilename; }
    @Override public String getContentType()       { return contentType; }
    @Override public boolean isEmpty()             { return content.length == 0; }
    @Override public long getSize()                { return content.length; }
    @Override public byte[] getBytes()             { return content; }
    @Override public InputStream getInputStream()  { return new ByteArrayInputStream(content); }
    @Override public void transferTo(File dest) throws IOException {
        try (var out = new FileOutputStream(dest)) { out.write(content); }
    }
}