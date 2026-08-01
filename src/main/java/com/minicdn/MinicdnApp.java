package com.minicdn;

import com.minicdn.origin.OriginMain;
import com.minicdn.shield.OriginShieldMain;
import com.minicdn.edge.EdgeMain;
import com.minicdn.router.RouterMain;

public class MinicdnApp {
    public static void main(String[] args) throws Exception {
        // Set properties for each service before starting (no race conditions)
        new Thread(() -> {
            System.setProperty("port", "8000");
            System.setProperty("contentRoot", "./origin-files");
            OriginMain.main(args);
        }).start();

        Thread.sleep(2000); // give origin time to start

        new Thread(() -> {
            System.setProperty("port", "9000");
            System.setProperty("originUrl", "http://localhost:8000");
            OriginShieldMain.main(args);
        }).start();

        Thread.sleep(2000);

        new Thread(() -> {
            System.setProperty("port", "9001");
            System.setProperty("upstreamUrl", "http://localhost:9000");
            EdgeMain.main(args);
        }).start();

        new Thread(() -> {
            System.setProperty("port", "9002");
            System.setProperty("upstreamUrl", "http://localhost:9000");
            EdgeMain.main(args);
        }).start();

        Thread.sleep(2000);

        new Thread(() -> {
            System.setProperty("routerPort", "8080");
            try {
                RouterMain.main(args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).start();
    }
}