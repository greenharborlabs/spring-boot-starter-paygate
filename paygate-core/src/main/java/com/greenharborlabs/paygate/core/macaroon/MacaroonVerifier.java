package com.greenharborlabs.paygate.core.macaroon;

import com.greenharborlabs.paygate.core.protocol.L402Validator;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic HMAC-chain and configured-caveat verification utilities.
 *
 * <p>This class does not verify payment preimages, require an identifier version, or apply
 * credential-cache policy. When callers register the complete first-party L402 verifier profile, it
 * requires the issuer's service, route, method, capability, and expiry boundaries. Direct
 * first-party L402 integrations must use {@link L402Validator}. Intentional generic-verifier users
 * may register their own schema; unregistered caveat keys are skipped for delegation compatibility.
 */
public final class MacaroonVerifier {

  private static final String SERVICES_CAVEAT_KEY = "services";
  private static final String ROUTE_CAVEAT_KEY = "route";
  private static final String METHOD_CAVEAT_KEY = "method";

  private MacaroonVerifier() {}

  /**
   * Verifies a macaroon's HMAC chain signature and evaluates registered caveat verifiers.
   *
   * <p>This is generic macaroon verification, not complete first-party L402 credential validation.
   * See the class documentation for the policy checks intentionally outside this method.
   *
   * <p>Unknown caveats are skipped per the L402 macaroons.md spec to support cross-application
   * delegation. Only caveats with a registered verifier are evaluated. The HMAC chain always
   * includes all caveats regardless of whether a verifier exists.
   *
   * <p>For caveats whose key appears more than once, each subsequent occurrence must be at least as
   * restrictive as the previous one (monotonic restriction). If a verifier's {@link
   * CaveatVerifier#isMoreRestrictive} returns {@code false}, verification fails with a caveat
   * escalation error.
   *
   * @param macaroon the macaroon to verify
   * @param rootKey the root key used to derive the signing key
   * @param caveatVerifiers registered verifiers for known caveat keys
   * @param context verification context (service name, current time, etc.)
   * @throws MacaroonVerificationException if the signature is invalid or a caveat fails
   */
  public static void verify(
      Macaroon macaroon,
      byte[] rootKey,
      List<CaveatVerifier> caveatVerifiers,
      L402VerificationContext context) {
    byte[] derivedKey = MacaroonCrypto.deriveKey(rootKey);
    byte[] sig = null;
    try {
      sig = MacaroonCrypto.hmac(derivedKey, macaroon.identifier());

      for (Caveat caveat : macaroon.caveats()) {
        byte[] oldSig = sig;
        sig = MacaroonCrypto.hmac(oldSig, caveat.toString().getBytes(StandardCharsets.UTF_8));
        KeyMaterial.zeroize(oldSig);
      }

      if (!MacaroonCrypto.constantTimeEquals(sig, macaroon.signature())) {
        throw new MacaroonVerificationException("signature verification failed");
      }

      Map<String, CaveatVerifier> verifiersByKey = buildVerifierMap(caveatVerifiers);
      verifyCaveats(macaroon.caveats(), verifiersByKey, context);
    } finally {
      KeyMaterial.zeroize(derivedKey, sig);
    }
  }

  /**
   * Verifies a list of caveats against registered verifiers and a verification context.
   *
   * <p>Unknown caveats (no registered verifier for the key) are silently skipped. For caveats whose
   * key appears more than once, each subsequent occurrence must be at least as restrictive as the
   * previous one (monotonic restriction).
   *
   * <p>This overload builds the verifier lookup map on each call. For repeated invocations with the
   * same verifier list, prefer {@link #verifyCaveats(List, Map, L402VerificationContext)} with a
   * pre-built map from {@link #buildVerifierMap(List)}.
   *
   * @param caveats the caveats to verify
   * @param caveatVerifiers registered verifiers for known caveat keys
   * @param context verification context (service name, current time, etc.)
   * @throws MacaroonVerificationException if a caveat escalation is detected or a verifier rejects
   */
  public static void verifyCaveats(
      List<Caveat> caveats, List<CaveatVerifier> caveatVerifiers, L402VerificationContext context) {
    verifyCaveats(caveats, buildVerifierMap(caveatVerifiers), context);
  }

  /**
   * Verifies a list of caveats using a pre-built verifier lookup map.
   *
   * <p>Unknown caveats (no registered verifier for the key) are silently skipped. For caveats whose
   * key appears more than once, each subsequent occurrence must be at least as restrictive as the
   * previous one (monotonic restriction).
   *
   * @param caveats the caveats to verify
   * @param verifiersByKey pre-built map from caveat key to verifier (see {@link
   *     #buildVerifierMap(List)})
   * @param context verification context (service name, current time, etc.)
   * @throws MacaroonVerificationException if a caveat escalation is detected or a verifier rejects
   */
  public static void verifyCaveats(
      List<Caveat> caveats,
      Map<String, CaveatVerifier> verifiersByKey,
      L402VerificationContext context) {
    requireMandatoryL402Boundaries(caveats, verifiersByKey, context);

    Map<String, Caveat> lastSeenByKey = new HashMap<>();
    Map<String, Caveat> finalEvaluationByKey = new HashMap<>();
    for (Caveat caveat : caveats) {
      CaveatVerifier verifier = verifiersByKey.get(caveat.key());
      if (verifier == null) {
        // Unknown caveats are skipped per the L402 spec
        continue;
      }

      Caveat previous = lastSeenByKey.get(caveat.key());
      if (previous != null && !verifier.isMoreRestrictive(previous, caveat)) {
        throw new MacaroonVerificationException(
            VerificationFailureReason.CAVEAT_ESCALATION,
            "caveat escalation detected for key: " + caveat.key());
      }
      lastSeenByKey.put(caveat.key(), caveat);

      // Capability satisfaction is meaningful only for the final, monotonically narrowed value.
      // isMoreRestrictive validates the grammar and bounds of every repeated occurrence.
      if (verifier instanceof CapabilitiesCaveatVerifier) {
        finalEvaluationByKey.put(caveat.key(), caveat);
      } else {
        verifier.verify(caveat, context);
      }
    }

    for (Map.Entry<String, Caveat> entry : finalEvaluationByKey.entrySet()) {
      verifiersByKey.get(entry.getKey()).verify(entry.getValue(), context);
    }

    // Post-loop enforcement: if a capability is required, the macaroon MUST contain
    // a capabilities caveat. Without this check, a macaroon lacking the caveat
    // entirely would bypass capability enforcement.
    String requestedCapability =
        context.getRequestMetadata().get(VerificationContextKeys.REQUESTED_CAPABILITY);
    if (requestedCapability != null && context.getServiceName() != null) {
      String capabilitiesKey = context.getServiceName() + "_capabilities";
      if (!lastSeenByKey.containsKey(capabilitiesKey)) {
        throw new MacaroonVerificationException(
            VerificationFailureReason.CAVEAT_NOT_MET,
            "Macaroon missing required capabilities caveat for capability '"
                + requestedCapability
                + "'");
      }
    }
  }

  /**
   * Requires every issuer boundary when the caller has configured the complete first-party L402
   * verifier profile. Generic macaroon consumers remain free to register only their own caveat
   * schema, while L402 callers cannot turn an omitted caveat into an unconstrained grant.
   */
  private static void requireMandatoryL402Boundaries(
      List<Caveat> caveats,
      Map<String, CaveatVerifier> verifiersByKey,
      L402VerificationContext context) {
    String serviceName = context.getServiceName();
    if (serviceName == null || serviceName.isBlank()) {
      return;
    }

    String capabilitiesKey = serviceName + "_capabilities";
    String validUntilKey = serviceName + "_valid_until";
    if (!hasCompleteFirstPartyL402Profile(verifiersByKey, capabilitiesKey, validUntilKey)) {
      return;
    }

    boolean hasServices = false;
    boolean hasRoute = false;
    boolean hasMethod = false;
    boolean hasCapabilities = false;
    boolean hasValidUntil = false;
    for (Caveat caveat : caveats) {
      hasServices |= SERVICES_CAVEAT_KEY.equals(caveat.key());
      hasRoute |= ROUTE_CAVEAT_KEY.equals(caveat.key());
      hasMethod |= METHOD_CAVEAT_KEY.equals(caveat.key());
      hasCapabilities |= capabilitiesKey.equals(caveat.key());
      hasValidUntil |= validUntilKey.equals(caveat.key());
    }

    if (!hasServices || !hasRoute || !hasMethod || !hasCapabilities || !hasValidUntil) {
      throw new MacaroonVerificationException(
          VerificationFailureReason.CAVEAT_NOT_MET,
          "Credential is missing a required issuer boundary caveat");
    }
  }

  private static boolean hasCompleteFirstPartyL402Profile(
      Map<String, CaveatVerifier> verifiersByKey, String capabilitiesKey, String validUntilKey) {
    return verifiersByKey
        .keySet()
        .containsAll(
            List.of(
                SERVICES_CAVEAT_KEY,
                ROUTE_CAVEAT_KEY,
                METHOD_CAVEAT_KEY,
                capabilitiesKey,
                validUntilKey));
  }

  /**
   * Builds a lookup map from caveat key to verifier. The resulting map can be reused across
   * multiple calls to {@link #verifyCaveats(List, Map, L402VerificationContext)} to avoid
   * rebuilding it per request.
   *
   * @param caveatVerifiers the verifier list to index
   * @return unmodifiable map from caveat key to verifier
   */
  public static Map<String, CaveatVerifier> buildVerifierMap(List<CaveatVerifier> caveatVerifiers) {
    Map<String, CaveatVerifier> map = new HashMap<>(caveatVerifiers.size());
    for (CaveatVerifier cv : caveatVerifiers) {
      map.put(cv.getKey(), cv);
    }
    return Map.copyOf(map);
  }
}
