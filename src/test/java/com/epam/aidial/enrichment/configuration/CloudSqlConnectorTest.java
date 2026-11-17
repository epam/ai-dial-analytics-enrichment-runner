package com.epam.aidial.enrichment.configuration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The runtime-state database reached through the Cloud SQL Java Connector with IAM database authentication: a JDBC
 * URL in the shape {@code spring.datasource.url} composes, carrying the connector's parameters in the query string,
 * is handled by the connector shipped on the runtime classpath, which signs in with a service-account key and calls
 * the Cloud SQL Admin API.
 *
 * <p>Hermetic: the token endpoint and the Admin API are a loopback server, and the Admin API refuses the call, so no
 * database is opened. What is proven is that the connector is present, loads with the dependency exclusions and
 * gRPC alignment in {@code build.gradle}, honours its URL parameters, calls the Admin API with the key's token, and
 * sends a token with the IAM login scope in its certificate request. The connection then fails with an ordinary
 * {@link SQLException}, never a linkage error.
 */
class CloudSqlConnectorTest {

    private static final String INSTANCE = "example-project:example-region:example-instance";
    private static final String LOGIN_SCOPE = "https://www.googleapis.com/auth/sqlservice.login";
    private static final String ADMIN_TOKEN = "ya29.loopback-admin";
    private static final String LOGIN_TOKEN = "ya29.loopback-login";

    @TempDir
    Path dir;

    private HttpServer server;
    private ExecutorService handlers;
    private final List<String> tokenClaims = new CopyOnWriteArrayList<>();
    private final List<String> adminCalls = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> ephemeralCertBody = new AtomicReference<>();
    private final CountDownLatch ephemeralCertRequested = new CountDownLatch(1);

    /**
     * Token endpoint plus a refusing Admin API. The connector fetches the instance's connect settings and its
     * ephemeral certificate in parallel, and only the certificate request carries the IAM login token. So the
     * connect-settings call is held until the certificate request has arrived, else a fast refusal could cancel it
     * first.
     */
    @BeforeEach
    void startLoopbackGoogle() throws IOException {
        handlers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/token", exchange -> {
            String claims = jwtBearerClaims(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            tokenClaims.add(claims);
            respondJson(exchange, 200, tokenJson(claims.contains(LOGIN_SCOPE) ? LOGIN_TOKEN : ADMIN_TOKEN));
        });
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            adminCalls.add(exchange.getRequestMethod() + " " + path + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            if (path.endsWith(":generateEphemeralCert")) {
                ephemeralCertBody.set(requestBody(exchange));
                ephemeralCertRequested.countDown();
            } else {
                awaitEphemeralCertRequest();
            }
            respondJson(exchange, 403, "{\"error\":{\"code\":403,\"message\":\"loopback refuses\",\"status\":\"PERMISSION_DENIED\"}}");
        });
        server.start();
    }

    /**
     * The connector keeps one process-wide registry whose background refresh would otherwise keep retrying the
     * stopped loopback server for the rest of the test JVM. Reached reflectively: the connector is runtime-only,
     * which is the classpath this test means to prove.
     */
    @AfterEach
    void stopLoopbackGoogle() throws ReflectiveOperationException {
        Class.forName("com.google.cloud.sql.core.InternalConnectorRegistry").getMethod("resetInstance").invoke(null);
        server.stop(0);
        handlers.shutdownNow();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void iamAuthenticatedUrlIsHandledByTheConnector() throws Exception {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Path keyFile = writeServiceAccountKey(base + "/token");
        // The shape application.yml builds: jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DATABASE}?${POSTGRES_OPS}.
        // The host and port are ignored by the connector; only cloudSqlInstance names the target.
        String url = "jdbc:postgresql://localhost:5432/runner?cloudSqlInstance=" + INSTANCE
                + "&enableIamAuth=true&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
                + "&cloudSqlGoogleCredentialsPath=" + URLEncoder.encode(keyFile.toString(), StandardCharsets.UTF_8)
                + "&cloudSqlAdminRootUrl=" + URLEncoder.encode(base + "/", StandardCharsets.UTF_8)
                + "&sslmode=disable";
        Properties credentials = new Properties();
        credentials.setProperty("user", "runner@example-project.iam");
        // Ignored under IAM authentication, but the driver refuses an empty one; docs/cloud-sql-access.md says so too.
        credentials.setProperty("password", "unused");

        assertThatThrownBy(() -> DriverManager.getConnection(url, credentials).close())
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                        assertThat(cause).isNotInstanceOf(LinkageError.class);
                    }
                });

        assertThat(adminCalls)
                .anySatisfy(call -> assertThat(call).contains("/connectSettings"))
                .anySatisfy(call -> assertThat(call).contains(":generateEphemeralCert"))
                .allSatisfy(call -> assertThat(call)
                        .contains("/projects/example-project/instances/example-instance")
                        .endsWith("Bearer " + ADMIN_TOKEN));
        // IAM database authentication: a login-scoped token rides in the certificate request.
        assertThat(tokenClaims).anySatisfy(claims -> assertThat(claims).contains(LOGIN_SCOPE));
        assertThat(ephemeralCertBody.get()).contains(LOGIN_TOKEN);
    }

    private void awaitEphemeralCertRequest() {
        try {
            ephemeralCertRequested.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A service-account key file with a fresh RSA key, whose token requests go to {@code tokenUri}. */
    private Path writeServiceAccountKey(String tokenUri) throws IOException, NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
        String key = """
                {
                  "type": "service_account",
                  "project_id": "example-project",
                  "private_key_id": "0000000000000000000000000000000000000001",
                  "private_key": "%s",
                  "client_email": "runner@example-project.iam.gserviceaccount.com",
                  "client_id": "000000000000000000001",
                  "token_uri": "%s"
                }
                """.formatted(pem.replace("\n", "\\n"), tokenUri);
        Path keyFile = dir.resolve("key.json");
        Files.writeString(keyFile, key);
        return keyFile;
    }

    /** The claims JSON of a JWT-bearer token request, from its URL-encoded form body. */
    private static String jwtBearerClaims(String form) {
        String decoded = URLDecoder.decode(form, StandardCharsets.UTF_8);
        if (!decoded.contains("grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer") || !decoded.contains("assertion=")) {
            throw new AssertionError("Not a JWT-bearer token request: " + decoded);
        }
        return new String(Base64.getUrlDecoder().decode(decoded.split("assertion=")[1].split("\\.")[1]), StandardCharsets.UTF_8);
    }

    /** A token reply carrying {@code token}, valid for an hour. */
    private static String tokenJson(String token) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** The request body, inflated when google-http-client sent it gzip-encoded. */
    private static String requestBody(HttpExchange exchange) throws IOException {
        InputStream body = exchange.getRequestBody();
        if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
            body = new GZIPInputStream(body);
        }
        return new String(body.readAllBytes(), StandardCharsets.UTF_8);
    }
}
