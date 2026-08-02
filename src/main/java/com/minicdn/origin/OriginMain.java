package com.minicdn.origin;

import io.javalin.Javalin;
import io.javalin.http.Header;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class OriginMain {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("port", "8000"));
        String contentRoot = System.getProperty("contentRoot", "./origin-files");

        Javalin app = Javalin.create(config -> {
            // No fancy config needed – origin is simple
        }).start(port);

        // Serve any file under /content/*
        app.get("/content/{path}", ctx -> {
            String path = ctx.pathParam("path");
            File file = new File(contentRoot, path);

            // 1. Basic security: ensure file is inside contentRoot (no ../ escapes)
            if (!file.getCanonicalPath().startsWith(new File(contentRoot).getCanonicalPath())) {
                ctx.status(403).result("Forbidden");
                return;
            }

            // 2. File not found
            if (!file.exists() || !file.isFile()) {
                ctx.status(404).result("Not found");
                return;
            }

            // 3. Conditional request (If-None-Match) using file modification time as ETag
            String etag = "\"" + file.lastModified() + "\"";
            String ifNoneMatch = ctx.header("If-None-Match");
            if (etag.equals(ifNoneMatch)) {
                ctx.status(304);  // Not Modified – no body needed
                return;
            }

            // 4. Set response headers
            ctx.header(Header.ETAG, etag);
            ctx.header(Header.CONTENT_TYPE, Files.probeContentType(file.toPath()));
            ctx.header("Cache-Control", "public, max-age=60"); // optional hint

            // 5. Stream the file content
            try (FileInputStream fis = new FileInputStream(file)) {
                ctx.result(fis.readAllBytes());
            }
        });

        System.out.println("Origin server started on http://localhost:" + port);
        System.out.println("Serving files from: " + new File(contentRoot).getAbsolutePath());
    }
}