package com.greenharborlabs.paygate.lightning.lnd;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;
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
      warnIfPermissionsAreTooBroad(certPath, "TLS certificate");
    }
    if (config.macaroonPath() != null) {
      Path macaroonPath = Path.of(config.macaroonPath());
      validateFileExists(macaroonPath, "Macaroon");
      validateFileReadable(macaroonPath, "Macaroon");
      warnIfPermissionsAreTooBroad(macaroonPath, "Macaroon");
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

    MacaroonClientInterceptor macaroonInterceptor = null;
    if (config.macaroonPath() != null) {
      macaroonInterceptor = newMacaroonInterceptor(config.macaroonPath());
      builder.intercept(macaroonInterceptor);
    }

    return buildZeroizingChannel(builder, macaroonInterceptor);
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

    MacaroonClientInterceptor macaroonInterceptor = null;
    if (config.macaroonPath() != null) {
      macaroonInterceptor = newMacaroonInterceptor(config.macaroonPath());
      builder.intercept(macaroonInterceptor);
    }

    ManagedChannel channel = buildZeroizingChannel(builder, macaroonInterceptor);

    log.log(
        System.Logger.Level.INFO,
        "Built TLS gRPC channel to {0}:{1}",
        config.host(),
        config.port());

    return channel;
  }

  private static final long MAX_MACAROON_FILE_SIZE = 4096;

  private static MacaroonClientInterceptor newMacaroonInterceptor(String macaroonPath) {
    byte[] macaroonBytes = readMacaroonBytes(macaroonPath);
    try {
      return new MacaroonClientInterceptor(macaroonBytes);
    } finally {
      Arrays.fill(macaroonBytes, (byte) 0);
    }
  }

  private static ManagedChannel buildZeroizingChannel(
      ManagedChannelBuilder<?> builder, MacaroonClientInterceptor macaroonInterceptor) {
    try {
      return buildZeroizingChannel(builder.build(), macaroonInterceptor);
    } catch (RuntimeException e) {
      if (macaroonInterceptor != null) {
        macaroonInterceptor.zeroize();
      }
      throw e;
    }
  }

  private static ManagedChannel buildZeroizingChannel(
      ManagedChannel channel, MacaroonClientInterceptor macaroonInterceptor) {
    if (macaroonInterceptor == null) {
      return channel;
    }
    return new ZeroizingManagedChannel(channel, macaroonInterceptor);
  }

  static boolean isMacaroonZeroized(ManagedChannel channel) {
    if (channel instanceof ZeroizingManagedChannel zeroizingChannel) {
      return zeroizingChannel.isMacaroonZeroized();
    }
    return true;
  }

  private static byte[] readMacaroonBytes(String macaroonPath) {
    Path path = Path.of(macaroonPath);

    try {
      long fileSize = Files.size(path);
      if (fileSize > MAX_MACAROON_FILE_SIZE) {
        throw new LndException(
            "LND macaroon file exceeds maximum size of %d bytes: %d"
                .formatted(MAX_MACAROON_FILE_SIZE, fileSize));
      }
      return Files.readAllBytes(path);
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

  /**
   * Warns when credential material is accessible by a group or other users.
   *
   * <p>The access check deliberately follows symbolic links. Container secret mounts commonly
   * expose credentials through symlinks, and rejecting those mounts would make a secure deployment
   * pattern unusable. The warning contains only a credential type, never a path or file contents.
   */
  private static void warnIfPermissionsAreTooBroad(Path path, String fileDescription) {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
      if (permissions.stream()
          .anyMatch(
              permission ->
                  permission.name().startsWith("GROUP_")
                      || permission.name().startsWith("OTHERS_"))) {
        log.log(
            System.Logger.Level.WARNING,
            "{0} file permissions allow access beyond its owner; restrict credential permissions",
            fileDescription);
      }
    } catch (UnsupportedOperationException | IOException ignored) {
      // POSIX permissions are not universally available. File existence/readability remains
      // checked.
    }
  }

  private static final class ZeroizingManagedChannel extends ManagedChannel {

    private final ManagedChannel delegate;
    private final MacaroonClientInterceptor macaroonInterceptor;

    private ZeroizingManagedChannel(
        ManagedChannel delegate, MacaroonClientInterceptor macaroonInterceptor) {
      this.delegate = delegate;
      this.macaroonInterceptor = macaroonInterceptor;
    }

    @Override
    public ManagedChannel shutdown() {
      macaroonInterceptor.zeroize();
      delegate.shutdown();
      return this;
    }

    @Override
    public ManagedChannel shutdownNow() {
      macaroonInterceptor.zeroize();
      delegate.shutdownNow();
      return this;
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions callOptions) {
      return delegate.newCall(methodDescriptor, callOptions);
    }

    @Override
    public String authority() {
      return delegate.authority();
    }

    private boolean isMacaroonZeroized() {
      return macaroonInterceptor.isZeroized();
    }
  }
}
