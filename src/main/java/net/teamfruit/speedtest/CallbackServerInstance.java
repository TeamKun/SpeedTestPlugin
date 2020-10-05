package net.teamfruit.speedtest;

import com.google.common.base.Charsets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultBHttpServerConnection;
import org.apache.http.impl.DefaultBHttpServerConnectionFactory;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;

public class CallbackServerInstance implements AutoCloseable {
    private Consumer<SpeedTestData.FeedbackData> consumer;
    private final ExecutorService listenerExecutor;
    private final ExecutorService workerExecutor;
    private ServerSocket serversocket;
    private String allowOrigin;

    public CallbackServerInstance(final Consumer<SpeedTestData.FeedbackData> consumer, String allowOrigin) throws IOException {
        this(consumer, allowOrigin, 0);
    }

    public CallbackServerInstance(final Consumer<SpeedTestData.FeedbackData> consumer, String allowOrigin, final int port) throws IOException {
        this.consumer = consumer;
        this.allowOrigin = allowOrigin;

        this.listenerExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).setNameFormat(SpeedTest.PluginName + "-web-listener-%d").build());
        this.workerExecutor = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setDaemon(true).setNameFormat(SpeedTest.PluginName + "-web-worker-%d").build());

        // Load class
        IOUtils.closeQuietly(null, null);

        // Set up the HTTP protocol processor
        final HttpProcessor httpproc = HttpProcessorBuilder.create()
                .add(new ResponseDate())
                .add(new ResponseServer())
                .add(new ResponseContent())
                .add(new ResponseConnControl())
                .build();

        // Set up request handlers
        final UriHttpRequestHandlerMapper reqistry = new UriHttpRequestHandlerMapper();
        reqistry.register("*", new HttpCallbackHandler());

        // Set up the HTTP service
        final HttpService httpService = new HttpService(httpproc, reqistry);

        final HttpConnectionFactory<DefaultBHttpServerConnection> connFactory = DefaultBHttpServerConnectionFactory.INSTANCE;
        this.serversocket = new ServerSocket(port, 0, InetAddress.getByAddress(new byte[]{0, 0, 0, 0}));

        this.listenerExecutor.submit(() -> {
            Log.log.info(SpeedTest.PluginName + " Web Listener on port " + this.serversocket.getLocalPort());
            try {
                while (!Thread.interrupted())
                    try {
                        // Set up HTTP connection
                        final Socket socket = this.serversocket.accept();
                        final HttpServerConnection conn = connFactory.createConnection(socket);

                        // Start worker thread
                        Log.log.info("Incoming connection from " + socket.getInetAddress());
                        this.workerExecutor.submit(() -> {
                            final HttpContext context = new BasicHttpContext(null);
                            context.setAttribute("ip", socket.getInetAddress().getHostAddress());
                            try {
                                while (!Thread.interrupted() && conn.isOpen())
                                    httpService.handleRequest(conn, context);
                            } catch (final ConnectionClosedException ex) {
                                Log.log.log(Level.INFO, "Client closed connection");
                            } catch (final IOException ex) {
                                Log.log.log(Level.SEVERE, "IO error: " + ex.getMessage());
                            } catch (final HttpException ex) {
                                Log.log.log(Level.SEVERE, "Unrecoverable HTTP protocol violation: " + ex.getMessage());
                            } finally {
                                try {
                                    conn.shutdown();
                                } catch (final IOException ignore) {
                                }
                            }
                        });
                    } catch (final InterruptedIOException ex) {
                        break;
                    } catch (final IOException e) {
                        Log.log.log(Level.SEVERE, "IO error initialising connection thread: ", e);
                        break;
                    }
            } finally {
                IOUtils.closeQuietly(this.serversocket, null);
            }
            Log.log.info(SpeedTest.PluginName + " Web Listener closed");
        });
    }

    public int getPort() {
        return this.serversocket.getLocalPort();
    }

    @Override
    public void close() {
        this.listenerExecutor.shutdownNow();
        this.workerExecutor.shutdownNow();
        IOUtils.closeQuietly(serversocket, null);
    }

    @SuppressWarnings("resource")
    public static void main(final String[] args) throws IOException {
        new CallbackServerInstance(e -> {
        }, "*");
    }

    private class HttpCallbackHandler implements HttpRequestHandler {
        @Override
        public void handle(
                final HttpRequest request, final HttpResponse response,
                final HttpContext context
        ) throws HttpException, IOException {
            response.addHeader(new BasicHeader("Access-Control-Allow-Origin", allowOrigin));
            response.addHeader(new BasicHeader("Access-Control-Allow-Methods", "POST, OPTIONS"));
            response.addHeader(new BasicHeader("Access-Control-Allow-Headers", "Authorization, Content-Type"));
            response.addHeader(new BasicHeader("Access-Control-Max-Age", "86400"));
            response.addHeader(new BasicHeader(HttpHeaders.ACCEPT, "text/plain"));
            response.addHeader(new BasicHeader(HttpHeaders.ACCEPT_CHARSET, "utf-8"));
            //response.addHeader(new BasicHeader(HttpHeaders.CONNECTION, "close"));

            final String method = request.getRequestLine().getMethod().toUpperCase(Locale.ENGLISH);
            if (method.equals("OPTIONS")) {
                response.setStatusCode(HttpStatus.SC_NO_CONTENT);
                Log.log.log(Level.FINE, "OPTIONS");
                return;
            }

            final String target = request.getRequestLine().getUri();
            Log.log.info(String.format("URL: %s, Method: %s", target, method));

            if (!target.equals("/")) {
                response.setStatusCode(HttpStatus.SC_NOT_FOUND);
                response.setEntity(new StringEntity("NG\nNot Found", ContentType.create("text/plain", StandardCharsets.UTF_8)));
                Log.log.log(Level.WARNING, "Invalid Request: Not Found");
                return;
            }

            if (method.equals("GET")) {
                response.setStatusCode(HttpStatus.SC_OK);
                response.setEntity(new StringEntity("OK", ContentType.create("text/plain", StandardCharsets.UTF_8)));
                Log.log.log(Level.FINE, "GET");
                return;
            }

            if (!method.equals("POST")) {
                response.setStatusCode(HttpStatus.SC_METHOD_NOT_ALLOWED);
                response.setEntity(new StringEntity("NG\nMethod Not Allowed", ContentType.create("text/plain", StandardCharsets.UTF_8)));
                Log.log.log(Level.WARNING, "Invalid Request: Method Not Allowed");
                return;
            }

            if (!(request instanceof HttpEntityEnclosingRequest)) {
                response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
                response.setEntity(new StringEntity("NG\nNo Request Data", ContentType.create("text/plain", StandardCharsets.UTF_8)));
                Log.log.log(Level.WARNING, "Invalid Request: No Request Data");
                return;
            }

            final HttpEntityEnclosingRequest eRequest = (HttpEntityEnclosingRequest) request;
            final HttpEntity entity = eRequest.getEntity();
            //if (eRequest.expectContinue()) {}

            String callback = IOUtils.toString(entity.getContent(), Charsets.UTF_8);

            if (callback == null) {
                response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
                response.setEntity(new StringEntity("NG\nNo Data", ContentType.create("text/plain", StandardCharsets.UTF_8)));
                Log.log.log(Level.WARNING, "Invalid Request: No Data");
                return;
            }

            SpeedTestData.FeedbackData data = new SpeedTestData.FeedbackData();
            data.token = callback;
            data.ip = (String) context.getAttribute("ip");

            try {
                CallbackServerInstance.this.consumer.accept(data);
            } catch (Exception e) {
                response.setStatusCode(HttpStatus.SC_BAD_REQUEST);
                response.setEntity(new StringEntity("NG\nInvalid Data", ContentType.create("text/plain", StandardCharsets.UTF_8)));
                Log.log.log(Level.WARNING, "Invalid Request: Invalid Data");
                return;
            }

            response.setStatusCode(HttpStatus.SC_OK);
            response.setEntity(new StringEntity("OK", ContentType.create("text/plain", StandardCharsets.UTF_8)));
        }
    }
}