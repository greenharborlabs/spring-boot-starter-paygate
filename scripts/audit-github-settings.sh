#!/usr/bin/env bash
# Read-only drift audit for the GitHub controls declared in
# .github/repository-settings.json. This script never changes repository state.
set -euo pipefail

readonly SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPOSITORY_ROOT="$(cd -P "$SCRIPT_DIR/.." && pwd)"
readonly EXPECTED="$REPOSITORY_ROOT/.github/repository-settings.json"

fail() {
  printf 'GitHub settings audit failed: %s\n' "$*" >&2
  exit 1
}

validate_expected_configuration() {
  jq -e '
    .schemaVersion == 1
    and (.repository | type == "string" and length > 0)
    and .defaultBranch == "main"
    and .securityAndAnalysis.vulnerabilityAlerts == true
    and .securityAndAnalysis.dependabotSecurityUpdates == true
    and .securityAndAnalysis.secretScanning == true
    and .securityAndAnalysis.secretScanningPushProtection == true
    and .securityAndAnalysis.secretScanningValidityChecks == true
    and .securityAndAnalysis.secretScanningNonProviderPatterns == true
    and .securityAndAnalysis.immutableReleases == true
    and .actions.allowedActions == "selected"
    and .actions.shaPinningRequired == true
    and (.actions.patternsAllowed | length > 0)
    and .ruleset.includeRefs == ["refs/heads/main"]
    and .ruleset.requiredStatusChecks == ["CI / pr-gate", "Security / security-gate"]
    and .ruleset.requiredApprovals == 0
    and .ruleset.requireConversationResolution == true
    and .ruleset.blockDeletion == true
    and .ruleset.blockForcePush == true
    and .ruleset.codeScanning.tool == "CodeQL"
    and .ruleset.codeScanning.securityAlertsThreshold == "high_or_higher"
    and .ruleset.bypassActors == [{"actorType":"OrganizationAdmin","bypassMode":"pull_request"}]
    and (.environments | keys | sort == ["maven-central", "maven-snapshots"])
    and all(.environments[]; .requiredReviewers == 1 and (.reviewers | length == 1))
  ' "$EXPECTED" >/dev/null || fail 'expected settings file is invalid or incomplete'
}

record_drift() {
  printf 'DRIFT: %s\n' "$*" >&2
  DRIFT_COUNT=$((DRIFT_COUNT + 1))
}

main() {
  [[ -f "$EXPECTED" && ! -L "$EXPECTED" ]] || fail 'missing expected settings file'
  command -v jq >/dev/null || fail 'jq is required'
  validate_expected_configuration

  if [[ "${1:-}" == --config-only ]]; then
    printf 'Expected GitHub settings configuration is valid.\n'
    return
  fi
  [[ $# -eq 0 ]] || fail 'usage: audit-github-settings.sh [--config-only]'
  command -v gh >/dev/null || fail 'gh is required for live audit'

  local repository repository_json actions_json selected_actions_json immutable_json
  local ruleset_name ruleset_id ruleset_json environment environment_json branch_policies
  repository="$(jq -r .repository "$EXPECTED")"
  DRIFT_COUNT=0

  repository_json="$(gh api "repos/$repository")" || fail 'cannot read repository settings'
  [[ "$(jq -r .default_branch <<<"$repository_json")" == "$(jq -r .defaultBranch "$EXPECTED")" ]] \
    || record_drift 'default branch does not match'

  if ! gh api "repos/$repository/vulnerability-alerts" >/dev/null 2>&1; then
    record_drift 'Dependabot vulnerability alerts are disabled'
  fi

  while IFS=$'\t' read -r actual_path label; do
    [[ "$(jq -r "$actual_path // \"disabled\"" <<<"$repository_json")" == enabled ]] \
      || record_drift "$label is disabled"
  done <<'SETTINGS'
.security_and_analysis.dependabot_security_updates.status	Dependabot security updates
.security_and_analysis.secret_scanning.status	secret scanning
.security_and_analysis.secret_scanning_push_protection.status	secret scanning push protection
.security_and_analysis.secret_scanning_validity_checks.status	secret scanning validity checks
.security_and_analysis.secret_scanning_non_provider_patterns.status	secret scanning non-provider patterns
SETTINGS

  immutable_json="$(gh api "repos/$repository/immutable-releases")" || fail 'cannot read immutable release setting'
  [[ "$(jq -r .enabled <<<"$immutable_json")" == true ]] || record_drift 'immutable releases are disabled'

  actions_json="$(gh api "repos/$repository/actions/permissions")" || fail 'cannot read Actions policy'
  [[ "$(jq -r .enabled <<<"$actions_json")" == true ]] || record_drift 'GitHub Actions are disabled'
  [[ "$(jq -r .allowed_actions <<<"$actions_json")" == selected ]] || record_drift 'allowed Actions policy is not selected-only'
  [[ "$(jq -r .sha_pinning_required <<<"$actions_json")" == true ]] || record_drift 'full-SHA Action pinning is not required'

  if selected_actions_json="$(gh api "repos/$repository/actions/permissions/selected-actions" 2>/dev/null)"; then
    jq -e --argjson expected "$(jq .actions "$EXPECTED")" '
      .github_owned_allowed == $expected.githubOwnedAllowed
      and .verified_allowed == $expected.verifiedAllowed
      and ((.patterns_allowed // []) | sort == ($expected.patternsAllowed | sort))
    ' <<<"$selected_actions_json" >/dev/null || record_drift 'selected Action allowlist does not match'
  else
    record_drift 'selected Action allowlist is unavailable'
  fi

  ruleset_name="$(jq -r .ruleset.name "$EXPECTED")"
  ruleset_id="$(gh api "repos/$repository/rulesets" --jq ".[] | select(.name == \"$ruleset_name\") | .id" | head -n 1)"
  if [[ -z "$ruleset_id" ]]; then
    record_drift "ruleset $ruleset_name is missing"
  else
    ruleset_json="$(gh api "repos/$repository/rulesets/$ruleset_id")"
    jq -e --argjson expected "$(jq .ruleset "$EXPECTED")" '
      .enforcement == $expected.enforcement
      and (.conditions.ref_name.include | sort == ($expected.includeRefs | sort))
      and ([.rules[] | select(.type == "deletion")] | length == 1)
      and ([.rules[] | select(.type == "non_fast_forward")] | length == 1)
      and ([.rules[] | select(.type == "pull_request")
        | select(.parameters.required_approving_review_count == $expected.requiredApprovals)
        | select(.parameters.required_review_thread_resolution == $expected.requireConversationResolution)] | length == 1)
      and ([.rules[] | select(.type == "required_status_checks")
        | select(.parameters.strict_required_status_checks_policy == $expected.strictStatusChecks)
        | .parameters.required_status_checks[].context] | sort == ($expected.requiredStatusChecks | sort))
      and ([.rules[] | select(.type == "code_scanning")
        | .parameters.code_scanning_tools[]
        | select(.tool == $expected.codeScanning.tool)
        | select(.security_alerts_threshold == $expected.codeScanning.securityAlertsThreshold)
        | select(.alerts_threshold == $expected.codeScanning.alertsThreshold)] | length == 1)
      and ([.bypass_actors[] | {actorType: .actor_type, bypassMode: .bypass_mode}]
        == $expected.bypassActors)
    ' <<<"$ruleset_json" >/dev/null || record_drift "ruleset $ruleset_name does not match"
  fi

  while IFS= read -r environment; do
    if ! environment_json="$(gh api "repos/$repository/environments/$environment" 2>/dev/null)"; then
      record_drift "environment $environment is missing"
      continue
    fi
    jq -e --argjson expected "$(jq --arg name "$environment" '.environments[$name]' "$EXPECTED")" '
      ([.protection_rules[]? | select(.type == "required_reviewers") | .reviewers[]?] | length >= $expected.requiredReviewers)
      and ([.protection_rules[]? | select(.type == "required_reviewers") | .reviewers[]?.reviewer.login]
        | sort == ($expected.reviewers | sort))
      and ([.protection_rules[]? | select(.type == "required_reviewers") | .prevent_self_review] | first == $expected.preventSelfReview)
      and .deployment_branch_policy.custom_branch_policies == true
    ' <<<"$environment_json" >/dev/null || record_drift "environment $environment protection does not match"

    branch_policies="$(gh api "repos/$repository/environments/$environment/deployment-branch-policies")" \
      || fail "cannot read $environment branch policies"
    jq -e --arg branch "$(jq -r --arg name "$environment" '.environments[$name].branch' "$EXPECTED")" \
      '.branch_policies | any(.name == $branch and .type == "branch")' \
      <<<"$branch_policies" >/dev/null || record_drift "environment $environment is not restricted to main"
  done < <(jq -r '.environments | keys[]' "$EXPECTED")

  if ((DRIFT_COUNT > 0)); then
    fail "$DRIFT_COUNT live setting(s) differ from the expected configuration"
  fi
  printf 'GitHub settings match %s.\n' "$EXPECTED"
}

main "$@"
