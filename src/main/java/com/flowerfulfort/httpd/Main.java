package com.flowerfulfort.httpd;

public class Main {
    public static void main(String[] args) {
        int port = 80;
        if (args.length == 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.out.println("Usage: shttpd [port]\n");
                return;
            }
        }
        HttpServer srv = new HttpServer(port);
        try {
            srv.runServer();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Server closed via exception.");
        }
    }
}