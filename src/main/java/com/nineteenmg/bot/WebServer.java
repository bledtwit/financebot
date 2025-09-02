

package com.nineteenmg.bot;
public class WebServer {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        System.out.println("Server listening on port " + port);
        Thread.currentThread().join(); // держим контейнер живым
    }
}
