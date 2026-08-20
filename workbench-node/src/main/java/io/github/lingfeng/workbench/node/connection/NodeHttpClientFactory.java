package io.github.lingfeng.workbench.node.connection;

import io.github.lingfeng.workbench.node.config.NodeProperties;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class NodeHttpClientFactory {

    private NodeHttpClientFactory() {
    }

    public static HttpClient create(NodeProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(properties.connectTimeout());
        configureProxy(builder, properties);
        configureTrustStore(builder, properties);
        return builder.build();
    }

    private static void configureProxy(HttpClient.Builder builder, NodeProperties properties) {
        if (properties.proxyUri() == null) {
            return;
        }
        int defaultPort = "https".equalsIgnoreCase(properties.proxyUri().getScheme()) ? 443 : 80;
        int port = properties.proxyUri().getPort() < 0 ? defaultPort : properties.proxyUri().getPort();
        builder.proxy(ProxySelector.of(new InetSocketAddress(properties.proxyUri().getHost(), port)));
        String userInfo = properties.proxyUri().getUserInfo();
        if (userInfo == null || userInfo.isBlank()) {
            return;
        }
        String username = userInfo.split(":", 2)[0];
        if (properties.proxyPasswordFile() == null) {
            throw new IllegalArgumentException("proxyPasswordFile is required when proxy user info is configured");
        }
        char[] password = readSecret(properties.proxyPasswordFile());
        builder.authenticator(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
    }

    private static void configureTrustStore(HttpClient.Builder builder, NodeProperties properties) {
        if (properties.trustStore() == null) {
            return;
        }
        if (properties.trustStorePasswordFile() == null) {
            throw new IllegalArgumentException("trustStorePasswordFile is required for a custom truststore");
        }
        char[] password = readSecret(properties.trustStorePasswordFile());
        try (InputStream source = Files.newInputStream(properties.trustStore())) {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(source, password);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trustStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers.getTrustManagers(), null);
            builder.sslContext(sslContext);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("Unable to load custom TLS truststore", exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] readSecret(java.nio.file.Path secretFile) {
        try {
            return Files.readString(secretFile, StandardCharsets.UTF_8).strip().toCharArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to read an external secret file", exception);
        }
    }
}
