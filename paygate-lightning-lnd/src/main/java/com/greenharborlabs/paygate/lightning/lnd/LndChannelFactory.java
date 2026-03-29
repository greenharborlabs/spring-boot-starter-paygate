package com.greenharborlabs.paygate.lightning.lnd;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Factory for creating configured gRPC {@link ManagedChannel} instances for connecting to an LND
 * node.
 */
public final class LndChannelFactory {

  private static final System.Logger log = System.getLogger(LndChannelFactory.class.getName());

  private LndChannelFactory() {
    // utility class
  }

  /**
   * Creates a {@link ManagedChannel} configured according to the given {@link LndConfig}.
   *
   * @param config LND connection configuration
   * @return a configured, ready-to-use gRPC channel
   * @throws LndException if TLS cert or macaroon files are missing/unreadable, or if channel
   *     construction fails due to I/O errors
   * @throws IllegalStateException if {@code tlsCertPath} is null and {@code allowPlaintext} is
   *     false
   */
  public static ManagedChannel create(LndConfig config) {
    // Defense-in-depth: LndConfig constructor also enforces this invariant.
    // This guard is unreachable via normal construction but exists for safety
    // in case LndConfig is later relaxed or constructed via reflection/deserialization.
    if (config.tlsCertPath() == null && !config.allowPlaintext()) {
      throw new IllegalStateException(
          "TLS certificate path is required when plaintext is not allowed");
    }

    // Validate all file paths upfront before attempting channel construction
    if (config.tlsCertPath() != null) {
      Path certPath = Path.of(config.tlsCertPath());
      validateFileExists(certPath, "TLS certificate");
      validateFileReadable(certPath, "TLS certificate");
    }
    if (config.macaroonPath() != null) {
      Path macaroonPath = Path.of(config.macaroonPath());
      validateFileExists(macaroonPath, "Macaroon");
      validateFileReadable(macaroonPath, "Macaroon");
    }

    try {
      if (config.tlsCertPath() == null) {
        return buildPlaintextChannel(config);
      } else {
        return buildTlsChannel(config);
      }
    } catch (LndException e) {
      throw e;
    } catch (Exception e) {
      throw new LndException("Failed to build LND gRPC channel: " + e.getMessage(), e);
    }
  }

  private static ManagedChannel buildPlaintextChannel(LndConfig config) {
    log.log(
        System.Logger.Level.WARNING,
        "Building plaintext (unencrypted) gRPC channel to {0}:{1} — use TLS in production",
        config.host(),
        config.port());

    var builder =
        ManagedChannelBuilder.forAddress(config.host(), config.port())
            .usePlaintext()
            .keepAliveTime(config.keepAliveTimeSeconds(), TimeUnit.SECONDS)
            .keepAliveTimeout(config.keepAliveTimeoutSeconds(), TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .idleTimeout(config.idleTimeoutMinutes(), TimeUnit.MINUTES)
            .maxInboundMessageSize(config.maxInboundMessageSize());

    if (config.macaroonPath() != null) {
      String macaroonHex = readMacaroonHex(config.macaroonPath());
      builder.intercept(new MacaroonClientInterceptor(macaroonHex));
    }

    return builder.build();
  }

  private static ManagedChannel buildTlsChannel(LndConfig config) throws IOException {
    Path certPath = Path.of(config.tlsCertPath());

    SslContext sslContext;
    try (InputStream certStream = Files.newInputStream(certPath)) {
      sslContext = GrpcSslContexts.forClient().trustManager(certStream).build();
    }

    var builder =
        NettyChannelBuilder.forAddress(config.host(), config.port())
            .sslContext(sslContext)
            .keepAliveTime(config.keepAliveTimeSeconds(), TimeUnit.SECONDS)
            .keepAliveTimeout(config.keepAliveTimeoutSeconds(), TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .idleTimeout(config.idleTimeoutMinutes(), TimeUnit.MINUTES)
            .maxInboundMessageSize(config.maxInboundMessageSize());

    if (config.macaroonPath() != null) {
      String macaroonHex = readMacaroonHex(config.macaroonPath());
      builder.intercept(new MacaroonClientInterceptor(macaroonHex));
    }

    ManagedChannel channel = builder.build();

    log.log(
        System.Logger.Level.INFO,
        "Built TLS gRPC channel to {0}:{1}",
        config.host(),
        config.port());

    return channel;
  }

  private static final long MAX_MACAROON_FILE_SIZE = 4096;

  private static String readMacaroonHex(String macaroonPath) {
    Path path = Path.of(macaroonPath);

    try {
      long fileSize = Files.size(path);
      if (fileSize > MAX_MACAROON_FILE_SIZE) {
        throw new LndException(
            "LND macaroon file exceeds maximum size of %d bytes: %d"
                .formatted(MAX_MACAROON_FILE_SIZE, fileSize));
      }
      byte[] macaroonBytes = Files.readAllBytes(path);
      return HexFormat.of().formatHex(macaroonBytes);
    } catch (IOException e) {
      throw new LndException("Failed to build LND gRPC channel: " + e.getMessage(), e);
    }
  }

  private static void validateFileExists(Path path, String fileDescription) {
    if (!Files.exists(path)) {
      throw new LndException(fileDescription + " file not found: " + path);
    }
  }

  private static void validateFileReadable(Path path, String fileDescription) {
    if (!Files.isReadable(path)) {
      throw new LndException(fileDescription + " file not readable: " + path);
    }
  }
}
