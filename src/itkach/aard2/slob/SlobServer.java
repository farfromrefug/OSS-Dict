package itkach.aard2.slob;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.Channels;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import itkach.aard2.SlobHelper;
import itkach.slob.Slob;

// With this server, we only serve the resources with authentication
// Format: host:port/auth/slobId/key?blob=<id>#fragment
// Format: host:port/auth/uri/key#fragment
// Returns: Content with designated types and caching info.
//
// Caching:
// For Slob ID, it is `Cache-Control: max-age=31556926`
// For URI, it is `Cache-Control: max-age=600`
//
// For URI, Slob ID is returned as ETag to reuse previous results.
public class SlobServer extends Thread {
    public static final String PAGE_NOT_FOUND = "404";
    public static final String OKAY = "200";
    public static final String CREATED = "201";
    public static final String ACCEPTED = "202";
    public static final String NO_CONTENT = "204";
    public static final String PARTIAL_NO_CONTENT = "206";
    public static final String MULTI_STATUS = "207";
    public static final String MOVED_PERMANENTLY = "301";
    public static final String SEE_OTHER = "303";
    public static final String NOT_MODIFIED = "304";
    public static final String TEMP_REDIRECT = "307";
    public static final String BAD_REQUEST = "400";
    public static final String UNAUTHORIZED_REQUEST = "401";
    public static final String FORBIDDEN = "403";
    public static final String NOT_FOUND = "404";
    public static final String METHOD_NOT_ALLOWED = "405";
    public static final String NOT_ACCEPTABLE = "406";
    public static final String REQUEST_TIMEOUT = "408";
    public static final String CONFLICT = "409";
    public static final String GONE = "410";
    public static final String LENGTH_REQUIRED = "411";
    public static final String PRECONDITION_FAILED = "412";

    public static final String PAYLOAD_TOO_LARGE = "413";
    public static final String UNSUPPORTED_MEDIA_TYPE = "415";
    public static final String RANGE_NOT_SATISFIABLE = "416";
    public static final String EXPECTATION_FAILED = "417";
    public static final String TOO_MANY_REQUESTS = "429";

    public static final String INTERNAL_ERROR = "500";
    public static final String NOT_IMPLEMENTED = "501";
    public static final String SERVICE_UNAVAILABLE = "503";
    public static final String UNSUPPORTED_HTTP_VERSION = "505";

    private static final String SERVER_NAME = "SlobServer/1.0";

    private static final Object sLock = new Object();
    @Nullable
    private static SlobServer sSlobServer;

    @NonNull
    private final ServerSocket serverSocket;
    @NonNull
    private final String authKey;
    private final boolean keepAlive = true;

    public SlobServer(@NonNull String ip, int port) throws IOException {
        serverSocket = new ServerSocket(port, 100, InetAddress.getByName(ip));
//        serverSocket.setSoTimeout(5000);
        authKey = generateAuthKey();
        setDaemon(true);
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            try {
                // Wait for new connection
                Socket newSocket = serverSocket.accept();
                Thread newClient = new EchoThread(newSocket);
                newClient.start();
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Loop continues as long as the server runs
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public class EchoThread extends Thread {
        public static final int BUFFER_SIZE = 1500;

        protected Socket socket;
        protected boolean nbOpen;

        public EchoThread(Socket clientSocket) {
            this.socket = clientSocket;
            this.nbOpen = true;
        }

        @Override
        public void run() {
            if (socket.isClosed() || !socket.isConnected()) {
                return;
            }
            try {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                byte[] data = new byte[BUFFER_SIZE];
                int size;

                while ((size = in.read(data)) != -1) {
                    String recData = new String(data, 0, size);
                    // System.out.println("Received Data: \n" + recData);
                    String[] headers = recData.split("\\r?\\n");
                    String[] requestLine = headers[0].split(" ");
                    if (requestLine.length != 3) {
                        // Invalid request line
                        continue;
                    }
                    Request request = new Request(requestLine[0], requestLine[1], requestLine[2]);

                    // Read header until a blank line
                    for (int i = 0; i < headers.length; ++i) {
                        String s = headers[i];
                        if (s.isEmpty()) {
                            // End of headers
                            StringBuilder sb = new StringBuilder();
                            for (int j = i; j < headers.length; ++j) {
                                sb.append(headers[i]);
                                if (j != (headers.length - 1)) {
                                    sb.append("\r\n");
                                }
                            }
                            if (size == BUFFER_SIZE) {
                                // TODO: 9/4/23 There may be more data
                            }
                            request.body = sb.toString();
                            break;
                        }
                        int colon = s.indexOf(':');
                        if (colon == -1) {
                            // Invalid header
                            continue;
                        }
                        String name = s.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                        String value = s.substring(colon + 1).trim();
                        request.headers.put(name, value);
                    }

                    processRequest(out, request);
                }
            } catch (Exception er) {
                er.printStackTrace();
            }

        }

    }

    public void processRequest(@NonNull DataOutputStream out, @NonNull Request request) {
        Uri uri = Uri.parse("http://localhost" + request.uri);
        List<String> pathSegments = uri.getPathSegments();

        // Index - Meaning
        // 0     - auth (fixed value: slob)
        // 1     - slob ID or URI
        // 2-n   - key
        if (pathSegments.size() < 3) {
            respondWithNotFound(out);
            return;
        }
        String auth = pathSegments.get(0);
        if (!authKey.equals(auth)) {
            // Invalid auth
            respondWithBadRequest(out);
            return;
        }
        SlobHelper slobHelper = SlobHelper.getInstance();
        String slobIdOrUri = pathSegments.get(1);
        String ifNoneMatch = request.headers.get("if-none-match");
        StringBuilder key = new StringBuilder();
        for (int i = 2; i < pathSegments.size(); i++) {
            if (key.length() > 0) {
                key.append("/");
            }
            key.append(pathSegments.get(i));
        }
        // Slob ID or URI check
        Slob slob = slobHelper.getSlob(slobIdOrUri);
        if (slob != null) {
            // Slob ID
            @Nullable
            String blobId = uri.getQueryParameter("blob");
            if (blobId != null) {
                try {
                    Slob.Content content = slob.getContent(blobId);
                    respondWithBlobContent(out, content, "max-age=31556926", null);
                    return;
                } catch (AssertionError e) {
                    // AssertionError occurs if the blobId is invalid
                }
            }
            if (getEtag(slob.getId()).equals(ifNoneMatch)) {
                respondWithNotModified(out);
                return;
            }
            Slob.Blob blob = getBlobByUri(slobHelper, slob, key.toString());
            if (blob == null) {
                respondWithNotFound(out);
                return;
            }
            respondWithBlobContent(out, blob.getContent(), "max-age=31556926", null);
            return;
        }
        // Might be URI
        slob = slobHelper.findSlob(slobIdOrUri);
        if (slob == null) {
            respondWithNotFound(out);
            return;
        }
        if (getEtag(slob.getId()).equals(ifNoneMatch)) {
            respondWithNotModified(out);
            return;
        }
        Slob.Blob blob = getBlobByUri(slobHelper, slob, key.toString());
        if (blob == null) {
            respondWithNotFound(out);
            return;
        }
        respondWithBlobContent(out, blob.getContent(), "max-age=600", getEtag(slob.getId()));
    }

    @NonNull
    public String getEtag(UUID slobId) {
        return String.format(Locale.ROOT, "\"%s\"", slobId);
    }

    @Nullable
    public Slob.Blob getBlobByUri(@NonNull SlobHelper slobHelper, @NonNull Slob slob, @NonNull String key) {
        List<Slob> candidates = slobHelper.findSlobsByUri(slob.getURI());

        Collections.sort(candidates, (o1, o2) -> {
            String createTime1 = o1.getTags().get("created.at");
            String createTime2 = o2.getTags().get("created.at");
            if (createTime2 == null) createTime2 = "";
            if (createTime1 == null) createTime1 = "";
            return createTime2.compareTo(createTime1);
        });

        Iterator<Slob.Blob> result = Slob.find(key, candidates.toArray(new Slob[0]),
                slob, Slob.Strength.SECONDARY);
        return result.hasNext() ? result.next() : null;
    }

    public void respondWithNotModified(@NonNull DataOutputStream out) {
        PrintWriter writer = getWriter(out);
        printHeader(writer, NOT_MODIFIED, keepAlive, null, 0, null);
        writer.append("\r\n");
        writer.flush();
    }

    public void respondWithBlobContent(@NonNull DataOutputStream out, @NonNull Slob.Content content,
                                       @NonNull String cacheControl, @Nullable String eTag) {
        PrintWriter writer = getWriter(out);
        printHeader(writer, OKAY, keepAlive, content.type, content.data.remaining(), cacheControl);
        if (eTag != null) {
            printHeader(writer, "ETag", eTag);
        }
        writer.append("\r\n");
        writer.flush();
        try {
            Channels.newChannel(out).write(content.data);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void respondWithNotFound(@NonNull DataOutputStream out) {
        String content = "<!DOCTYPE html>" +
                "<html>" +
                "<head><title>404 Not Found</title></head>" +
                "<body><h3>Not found.</h3></body>" +
                "</html>";
        PrintWriter writer = getWriter(out);
        printHeader(writer, NOT_FOUND, keepAlive, "text/html", content.length(), "no-cache");
        writer.append("\r\n");
        writer.print(content);
        writer.flush();
    }

    public void respondWithBadRequest(@NonNull DataOutputStream out) {
        String content = "<!DOCTYPE html>" +
                "<html>" +
                "<head><title>400 Bad Request</title></head>" +
                "<body><h3>Bad request.</h3></body>" +
                "</html>";
        PrintWriter writer = getWriter(out);
        printHeader(writer, BAD_REQUEST, keepAlive, "text/html", content.length(), "no-cache");
        writer.append("\r\n");
        writer.print(content);
        writer.flush();
    }

    private static void printHeader(@NonNull PrintWriter pw, @NonNull String status, boolean keepAlive,
                                    @Nullable String contentType, int contentLength,
                                    @Nullable String cacheControl) {
        SimpleDateFormat gmtFmt = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        gmtFmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        pw.append("HTTP/1.1 ")
                .append(status)
                .append(" \r\n");
        printHeader(pw, "Server", SERVER_NAME);
        printHeader(pw, "Date", gmtFmt.format(new Date()));
        printHeader(pw, "Connection", (keepAlive ? "keep-alive" : "close"));
        if (contentType != null) {
            printHeader(pw, "Content-Type", contentType);
        }
        printHeader(pw, "Content-Length", String.valueOf(contentLength));
        if (cacheControl != null) {
            printHeader(pw, "Cache-Control", cacheControl);
        }
    }

    private static void printHeader(@NonNull PrintWriter pw, @NonNull String key, @NonNull String value) {
        pw.append(key).append(": ").append(value).append("\r\n");
    }

    @NonNull
    private static PrintWriter getWriter(@NonNull DataOutputStream output) {
        return new PrintWriter(new BufferedWriter(new OutputStreamWriter(output)), false);
    }

    public static void startServer(String ip, int port) throws IOException {
        synchronized (sLock) {
            if (sSlobServer != null && sSlobServer.isAlive()) {
                // Server active
                return;
            }
            sSlobServer = new SlobServer(ip, port);
            sSlobServer.start();
            System.out.println("Server Started");
        }
    }

    public static void stopServer() {
        synchronized (sLock) {
            if (sSlobServer != null && sSlobServer.isAlive()) {
                sSlobServer.interrupt();
            }
            System.out.println("Server stopped");
        }
    }

    @Nullable
    public static String getAuthKey() {
        synchronized (sLock) {
            if (sSlobServer != null) {
                return sSlobServer.authKey;
            }
        }
        return null;
    }

    /**
     * Test if the app has permission to use ServerSocket.
     * This is used to detect if network access has been disabled in app settings.
     * 
     * @param ip The IP address to test (typically "127.0.0.1" for localhost)
     * @param port The port to test (typically 0 for any available port)
     * @return true if ServerSocket can be created, false if network access is blocked
     */
    public static boolean canUseServerSocket(@NonNull String ip, int port) {
        ServerSocket testSocket = null;
        try {
            // Try to create a ServerSocket on localhost
            testSocket = new ServerSocket(port, 1, InetAddress.getByName(ip));
            // If we got here, we can create ServerSocket
            return true;
        } catch (SecurityException e) {
            // Network access is blocked by security policy (app settings)
            return false;
        } catch (IOException e) {
            // Other IO errors (port in use, etc.) - but we CAN create sockets
            // This means network access is allowed, just this particular operation failed
            return true;
        } finally {
            if (testSocket != null) {
                try {
                    testSocket.close();
                } catch (IOException ignored) {
                    // Ignore errors when closing test socket
                }
            }
        }
    }

    @NonNull
    private static String generateAuthKey() {
        SecureRandom rand = new SecureRandom();
        byte[] authBytes = new byte[rand.nextInt(16) + 5];
        rand.nextBytes(authBytes);
        return new String(encodeToHex(authBytes, 0, authBytes.length));
    }

    private static final char[] DIGITS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    @NonNull
    private static char[] encodeToHex(@NonNull byte[] data, int offset, int len) {
        char[] result = new char[len * 2];
        for (int i = 0; i < len; i++) {
            byte b = data[offset + i];
            int resultIndex = 2 * i;
            result[resultIndex] = (DIGITS[(b >> 4) & 0x0f]);
            result[resultIndex + 1] = (DIGITS[b & 0x0f]);
        }

        return result;
    }

    private static class Request {
        @NonNull
        public final String method;
        @NonNull
        public final String uri;
        @NonNull
        public final String httpVersion;
        @NonNull
        public Map<String, String> headers = new HashMap<>();
        @Nullable
        public String body;

        public Request(@NonNull String method, @NonNull String requestUri, @NonNull String httpVersion) {
            this.method = method;
            this.uri = requestUri;
            this.httpVersion = httpVersion;
        }
    }
}
