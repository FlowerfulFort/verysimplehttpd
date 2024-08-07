package com.flowerfulfort.httpd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class HttpServer {
    private static final Logger log = LogManager.getLogger("root");
    private BufferedInputStream indexIn;
    private String indexHTMLFormat;

    private static final String DIV_FILE_FORMAT = "<div><a href=\"%s/%s\" class=\"%s\">%s</a></div>\n";
    private static final String CLASS_DIRECTORY = "directory";
    private static final String CLASS_FILE = "file";
    private static final String RESPONSE_DATA_FORMAT = "HTTP/1.1 %d %s\r\nDate: %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nSerevr: Simple-httpd/1.0.0\r\n\r\n";
    private static final String RESPONSE_SIMPLE_FORMAT = "HTTP/1.1 %d %s\r\nDate: %s\r\nServer: Simple-httpd/1.0.0\r\n\r\n";
    private static final String RESPONSE_STATUS_BODY = "<title>%d %s</title><h1>%d %s</h1>";

    private static final int BUF_SIZE = 64000;
    private static final DateTimeFormatter timeFormat = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));
    private static final HashMap<Integer, String> statusCodeMap;

    // [[IP]] [location] [statusCode] [status]
    private static final String LOG_FORMAT = "Thread#{}\t[{}] {} {} {}";

    static {
        statusCodeMap = new HashMap<>();
        statusCodeMap.put(200, "OK");
        statusCodeMap.put(404, "Not Found");
        statusCodeMap.put(403, "Forbidden");
        statusCodeMap.put(409, "Conflict");
        statusCodeMap.put(405, "Method Not Allowed");
        statusCodeMap.put(204, "No Content");
    }

    private int port;
    private ArrayList<String> directories;
    private ArrayList<String> files;

    HttpServer() {
        this(80);
    }

    HttpServer(int port) {
        this.port = port;

        // html 템플릿을 읽어들임.
        indexIn = new BufferedInputStream(this.getClass().getClassLoader().getResourceAsStream("template.html"));
        try {
            StringBuilder sb = new StringBuilder();
            int ch;
            while ((ch = indexIn.read()) != -1)
                sb.append((char) ch);

            indexHTMLFormat = sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void logging(InetAddress address, String location, int code) {
        if (statusCodeMap.containsKey(code))
            log.info(LOG_FORMAT, Thread.currentThread().getId(), address, location, code, statusCodeMap.get(code));
        else {
            log.error("Log code error");
            throw new IllegalArgumentException();
        }
    }

    private boolean checkIsDirectory(String path) {
        if (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        File directoryPath = new File(path);
        return directoryPath.isDirectory();
    }

    private boolean setFileList(String path) {
        // 끝이 "/"로 끝나는 경우 지움.
        if (path.endsWith("/"))
            path = path.substring(0, path.length() - 1);
        File directoryPath = new File(path);

        if (!directoryPath.exists()) {
            return false;
        }
        // 역방향으로 가는 경우
        if (path == null || path.contains("/..") || !directoryPath.isDirectory()) {
            throw new IllegalArgumentException();
        }
        String[] patharr = directoryPath.list();

        directories = new ArrayList<>();
        files = new ArrayList<>();

        for (String p : patharr) {
            File f = new File(String.format("%s/%s", path, p));
            if (f.isDirectory()) {
                directories.add(f.getName());
            } else if (f.isFile()) {
                files.add(f.getName());
            }
        }
        Collections.sort(directories);
        Collections.sort(files);
        return true;
    }

    static Map<String, String> parseHeader(String header) {
        String[] headers = header.split("\n");
        HashMap<String, String> h = new HashMap<>();

        String[] httpRequest = headers[0].split(" ");
        h.put("Method", httpRequest[0]);
        h.put("Location", httpRequest[1]);
        h.put("HTTPVersion", httpRequest[2]);

        for (int i = 1; i < headers.length; i++) {
            StringTokenizer tok = new StringTokenizer(headers[i], ":");
            String key = tok.nextToken().strip();
            String value = tok.nextToken().strip();
            h.put(key, value);
        }
        return h;
    }

    // location을 입력받음.
    private String formatIndex(String loc) {
        if (indexHTMLFormat == null || indexHTMLFormat.isEmpty()) {
            System.err.println("read error index.html\n");
            System.exit(1);
        }

        StringBuilder fileList = new StringBuilder();
        if (!loc.equals("")) { // 상위 폴더로 가기를 만들기
            String[] locSplit = loc.split("/");
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < locSplit.length - 1; i++) {
                sb.append(locSplit[i]).append('/');
            }
            String upperLocation = sb.toString();
            // 끝에 오는 "/" 제거
            if (!upperLocation.equals("")) {
                upperLocation = upperLocation.substring(0, upperLocation.length() - 1);
            }
            fileList.append(String.format(DIV_FILE_FORMAT, "", upperLocation, CLASS_DIRECTORY, ".."));
        }
        // 파일과 디렉토리 리스트로 template.html을 수정하여 리턴.
        // 디렉토리가 먼저 옴.
        directories.stream()
                .forEach(dir -> fileList.append(String.format(DIV_FILE_FORMAT, loc, dir, CLASS_DIRECTORY, dir)));
        files.stream().forEach(file -> fileList.append(String.format(DIV_FILE_FORMAT, loc, file, CLASS_FILE, file)));
        return String.format(indexHTMLFormat, fileList.toString());
    }

    // 현재 시간을 포매팅함.
    private String getDateForm() {
        return LocalDateTime.now().format(timeFormat);
    }

    private void responseNotFound(BufferedOutputStream out) throws IOException {
        responseStatus(out, 404);
    }

    private int sendFileToBufferedStream(String filepath, BufferedOutputStream out) throws IOException {
        File f = new File(filepath);
        if (!f.exists()) { // 파일이 존재하지 않으면 404 NOT FOUND
            responseNotFound(out);
            return -1;
        } else {
            String[] extSplit = f.getName().split("\\.");
            String ext = extSplit[extSplit.length - 1];

            BufferedInputStream fin = new BufferedInputStream(new FileInputStream(f));
            byte[] buf = new byte[BUF_SIZE]; // 64000 byte buffer.
            int length = fin.read(buf);
            fin.close();
            // 지금은 64kb 이하의 파일만 전송 가능..
            // int length = 0;l
            // int len = 0;
            // while ((len = fin.read(buf)) != -1) {
            // out.write(buf);
            // length += len;
            // }
            String ctype = switch (ext) {
                case "txt" -> "text/plain";
                case "htm", "html" -> "text/html";
                case "css" -> "text/css";
                case "js" -> "text/javascript";
                case "png", "jpg", "bmp" -> String.format("image/%s", ext);
                case "json" -> "application/json";
                case "zip" -> "application/zip";
                default -> String.format("application/%s", ext);
            };
            String response = String.format(RESPONSE_DATA_FORMAT, 200, "OK", getDateForm(), ctype, length);
            out.write(response.getBytes());
            out.write(buf, 0, length);
            out.flush();

            return length;
        }
    }

    // 스트림 예외는 던지기.
    private void responseGET(Socket sc, String location) throws IOException {
        BufferedOutputStream ostream = new BufferedOutputStream(sc.getOutputStream());

        String path = "." + Objects.requireNonNull(location);
        String html = "";
        String response = "";
        try { // 만약 디렉토리라면..
            if (checkIsDirectory(path)) {
                if (setFileList(path)) {
                    if (location.equals("/"))
                        html = formatIndex("");
                    else
                        html = formatIndex(location);

                    response = String.format(RESPONSE_DATA_FORMAT, 200, "OK", getDateForm(), "text/html",
                            html.length());

                    ostream.write(response.getBytes());
                    ostream.write(html.getBytes());
                    ostream.flush();
                    logging(sc.getInetAddress(), location, 200);
                } else {
                    responseNotFound(ostream);
                }
            } else { // 만약 파일이라면...
                int len = sendFileToBufferedStream(path, ostream);
                if (len < 0) {
                    logging(sc.getInetAddress(), path, 404);
                } else {
                    logging(sc.getInetAddress(), path, 200);
                }
                ostream.close();
            }
        } catch (IOException exp) { // 파일 읽기에 관한 예외만 catch.
            responseStatus(ostream, 403);
            logging(sc.getInetAddress(), path, 403);
        } finally {
            ostream.close();
        }

    }

    // body와 statuscode를 함께 보냄.
    private void responseStatus(BufferedOutputStream out, int statusCode) throws IOException {
        String status = Objects.requireNonNull(statusCodeMap.get(statusCode));
        String body = String.format(RESPONSE_STATUS_BODY, statusCode, status, statusCode, status);
        String response = String.format(RESPONSE_DATA_FORMAT, statusCode, status, getDateForm(), "text/html",
                body.length());
        // out.write(body.getBytes());
        out.write(response.getBytes());
        out.write(body.getBytes());
        out.flush();
    }

    // body없이 Status code만 전송할 때 사용함.
    private void responseSimple(BufferedOutputStream out, int statusCode) throws IOException {
        String response = String.format(RESPONSE_SIMPLE_FORMAT, statusCode, statusCodeMap.get(statusCode),
                getDateForm());
        out.write(response.getBytes());
        out.flush();
    }

    private void responseDELETE(Socket sc, String location) throws IOException {
        BufferedOutputStream ostream = new BufferedOutputStream(sc.getOutputStream());

        String path = "." + Objects.requireNonNull(location);
        File f = new File(path);
        if (f.exists() && f.isFile()) {
            if (!f.delete()) {
                responseStatus(ostream, 403);
                logging(sc.getInetAddress(), location, 403);
            }
        }
        responseSimple(ostream, 204);
        logging(sc.getInetAddress(), location, 204);
    }

    private void responsePOST(Socket sc, Map<String, String> header, BufferedReader istream) throws IOException {
        BufferedOutputStream ostream = new BufferedOutputStream(sc.getOutputStream());

        String ctype = header.get("Content-Type");
        String[] ctypes = ctype.split(";");
        int statusCode = 200;

        // multipart 이외의 post 요청은 405 응답.
        if (!ctypes[0].strip().equals("multipart/form-data")) {
            responseSimple(ostream, 405);
            logging(sc.getInetAddress(), header.get("Location"), 405);
            // return;
        } else {
            String boundary = ctype.split("(boundary=)")[1].strip();
            String input = istream.readLine();
            String filename = null;
            // boundary를 읽고 "--\r\n"이 올때까지 반복.

            while (!input.endsWith("--")) {
                // 파일의 서브헤더 읽기.
                while (!(input = istream.readLine()).isEmpty()) {
                    if (input.contains("Content-Disposition")) {
                        String[] tok = input.split("(filename=)");
                        filename = tok[tok.length - 1].strip();
                        // \"filename\" 큰따옴표 시퀀스 제거.
                        filename = filename.substring(1, filename.length() - 1);
                    }
                }
                // 서브헤더 읽고 난 후..
                File newFile = new File(Objects.requireNonNull(filename));
                BufferedWriter fin = null;

                if (newFile.exists()) {
                    // 같은 이름의 파일이 있다고 하더라도 나머지 파일은 받아들일 수 있게됨.
                    // 같은 이름의 파일의 경우, nullStream으로 흘러보냄.
                    fin = new BufferedWriter(new OutputStreamWriter(OutputStream.nullOutputStream()));
                    statusCode = 409; // 같은 이름의 파일이 있었음..
                    // responseSimple(ostream, 409);
                    // logging(sc.getInetAddress(), header.get("Location"), 409);
                    // ostream.close();
                    // return;
                } else {
                    fin = new BufferedWriter(new FileWriter(newFile));
                }

                // 데이터를 읽어 파일로 저장.
                input = istream.readLine();
                int fileLength = 0;
                while (true) {
                    // 파일에서 개행문자가 여러번 오는 상황을 체크해야함.
                    if (input.isEmpty()) {
                        String next = istream.readLine();
                        // 다음 라인이 boundary가 아니면
                        if (!next.contains(boundary)) {
                            fin.write('\n');
                            fileLength++;
                            fin.flush();
                            input = next;
                        } else { // 다음이 boundary라면.. 파일이 끝났음.(boundary까지 읽힘.)
                            input = next; // boundary string을 참조시키고 break.
                            break;
                        }
                    } else {
                        fin.write(input);
                        fin.write('\n');
                        fileLength += (input.getBytes().length + 1);
                        fin.flush();
                        input = istream.readLine();
                    }
                }
                fin.close();
                log.info("File saved at {}, size: {}", filename, fileLength);
            }

            // 잘 받았음 or 중복이 있었음!
            responseSimple(ostream, statusCode);
            logging(sc.getInetAddress(), header.get("Location"), statusCode);
            ostream.close();
        }
    }

    // 멀티스레드 버전
    public void runServer() throws IOException {
        ExecutorService executorService = Executors.newFixedThreadPool(10);
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            log.info("Server socket listen at http://localhost:{}", port);

            while (true) {
                Socket sc = serverSocket.accept();
                executorService.submit(() -> {
                    try {
                        log.info("Connection established with {}", sc.getInetAddress());

                        BufferedReader istream = new BufferedReader(new InputStreamReader(sc.getInputStream()));
                        String get = null;
                        StringBuilder sb = new StringBuilder();
                        while ((get = istream.readLine()) != null) {
                            if (get.isEmpty())
                                break;
                            sb.append(get).append('\n');
                        } // header 받기
                        Map<String, String> header = parseHeader(sb.toString());
                        String location = header.get("Location");
                        log.info("HTTP {} Request at {}", header.get("Method"), header.get("Location"));
                        long start = System.currentTimeMillis();
                        switch (header.get("Method")) {
                            case "GET" -> responseGET(sc, location);
                            case "POST" -> responsePOST(sc, header, istream);
                            case "DELETE" -> responseDELETE(sc, location);
                            default -> throw new IllegalArgumentException(); // 400 bad request.
                        }
                        long end = System.currentTimeMillis();
                        log.info("Response Time: {} secs", ((double) end - start) / 1000);
                        log.info("Connection close {}", sc.getInetAddress());
                        istream.close();
                        sc.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                        log.error("Socket Error during thread processing");
                    }
                });

            }
        }
    }
    // 싱글스레드 버전
    // public void runServer() throws IOException {
    // // ExecutorService executorService = Executors.newFixedThreadPool(10);
    // try (ServerSocket serverSocket = new ServerSocket(port)) {

    // log.info("Server socket listen at http://localhost:{}", port);
    // while (true) {
    // try (Socket sc = serverSocket.accept()) {

    // log.info("Connection established with {}", sc.getInetAddress());

    // BufferedReader istream = new BufferedReader(new
    // InputStreamReader(sc.getInputStream()));
    // String get = null;
    // StringBuilder sb = new StringBuilder();
    // while ((get = istream.readLine()) != null) {
    // if (get.isEmpty())
    // break;
    // sb.append(get).append('\n');
    // } // header 받기
    // Map<String, String> header = parseHeader(sb.toString());
    // String location = header.get("Location");
    // log.info("HTTP {} Request at {}", header.get("Method"),
    // header.get("Location"));
    // long start = System.currentTimeMillis();
    // switch (header.get("Method")) {
    // case "GET" -> responseGET(sc, location);
    // case "POST" -> responsePOST(sc, header, istream);
    // case "DELETE" -> responseDELETE(sc, location);
    // default -> throw new IllegalArgumentException(); // 400 bad request.
    // }
    // long end = System.currentTimeMillis();
    // log.info("Response Time: {} secs", ((double) end - start) / 1000);
    // log.info("Connection close {}", sc.getInetAddress());
    // istream.close();
    // }
    // }
    // }
    // }
}
