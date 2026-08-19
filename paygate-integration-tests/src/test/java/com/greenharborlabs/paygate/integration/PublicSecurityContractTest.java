package com.greenharborlabs.paygate.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies that security limitations remain explicit in the public documentation. */
@Tag("integration")
@DisplayName("Public security documentation contracts")
class PublicSecurityContractTest {

  @Test
  @DisplayName("documents bearer, macaroon, IP, cleanup, and body-limit limitations")
  void documentsIntentionalSecurityLimitations() throws IOException {
    var workspace = workspaceRoot();
    var mpp = documentation(workspace, "paygate-protocol-mpp/README.md");
    var macaroons = documentation(workspace, "paygate-core/README.md");
    var caveatGuide = documentation(workspace, "docs/macaroons-deep-dive.md");
    var api = documentation(workspace, "paygate-api/README.md");
    var lnd = documentation(workspace, "paygate-lightning-lnd/README.md");
    var spring = documentation(workspace, "paygate-spring-autoconfigure/README.md");
    var security = documentation(workspace, "SECURITY.md");
    var root = documentation(workspace, "README.md");

    SoftAssertions.assertSoftly(
        assertions -> {
          assertions
              .assertThat(mpp)
              .contains("transferable bearer material")
              .contains("presented repeatedly until expiry")
              .contains("not single-use")
              .contains("does not provide replay prevention")
              .contains("exact raw query");
          assertions
              .assertThat(macaroons)
              .contains("third-party caveats")
              .contains("additional macaroons")
              .contains("rejected");
          assertions
              .assertThat(caveatGuide)
              .contains("literal")
              .contains("exact string")
              .contains("no dns")
              .contains("no cidr");
          assertions
              .assertThat(api)
              .contains("ownership transfer")
              .contains("close")
              .contains("destroy")
              .contains("cleaner");
          assertions.assertThat(lnd).contains("string-backed").contains("cannot be zeroized");
          assertions
              .assertThat(spring)
              .contains("paygate.request-body.max-bytes")
              .contains("1 byte")
              .contains("16 mib");
          assertions
              .assertThat(security)
              .contains("exact-request")
              .contains("short expir")
              .contains("idempot")
              .contains("consumed-state")
              .contains("auto-provision")
              .contains("string");
          assertions
              .assertThat(root)
              .contains("trusted proxy")
              .contains("not a user identity")
              .contains("not an authorization boundary");
        });
  }

  @Test
  @DisplayName("keeps every Kimi Medium finding tied to public compatibility and failure evidence")
  void keepsKimiMediumFindingEvidenceTraceable() throws IOException {
    var workspace = workspaceRoot();
    var ledger = documentation(workspace, "docs/security/KIMI-MEDIUM-FINDING-DISPOSITIONS.md");
    var vector =
        documentation(
            workspace,
            "paygate-protocol-l402/src/test/resources/test-vectors/l402-paid-price-vectors.json");
    var validatorTests =
        documentation(
            workspace,
            "paygate-core/src/test/java/com/greenharborlabs/paygate/core/protocol/L402ValidatorTest.java");
    var macaroonVerifierTests =
        documentation(
            workspace,
            "paygate-core/src/test/java/com/greenharborlabs/paygate/core/macaroon/MacaroonVerifierTest.java");
    var core = documentation(workspace, "paygate-core/README.md");
    var root = documentation(workspace, "README.md");

    SoftAssertions.assertSoftly(
        assertions -> {
          assertions
              .assertThat(ledger)
              .contains("m-1")
              .contains("m-2")
              .contains("m-3")
              .contains("m-4")
              .contains("implemented")
              .contains("approval status");
          assertions
              .assertThat(vector)
              .contains("rootkeyhex")
              .contains("paymentpreimagehex")
              .contains("coveredamountsats")
              .contains("price_sats")
              .contains("signaturehex");
          assertions
              .assertThat(validatorTests)
              .contains("wrong preimage")
              .contains("tampered")
              .contains("signed paid-price enforcement");
          assertions.assertThat(macaroonVerifierTests).contains("wrong root");
          assertions
              .assertThat(core)
              .contains("ascii space")
              .contains("horizontal-tab")
              .contains("price_sats");
          assertions
              .assertThat(root)
              .contains("request_dependent")
              .contains("route_stable")
              .contains("observed");
        });
  }

  private static String documentation(Path workspace, String relativePath) throws IOException {
    return Files.readString(workspace.resolve(relativePath)).toLowerCase().replaceAll("\\s+", " ");
  }

  private static Path workspaceRoot() {
    var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    for (var depth = 0;
        depth < 4 && directory != null;
        depth++, directory = directory.getParent()) {
      if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
        return directory;
      }
    }
    throw new IllegalStateException("Could not locate the Gradle workspace root");
  }
}
