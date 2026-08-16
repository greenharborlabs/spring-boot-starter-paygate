# Security Enforcement Rollout

The repository-owned expected GitHub configuration is
`.github/repository-settings.json`. The read-only drift audit is:

```bash
bash scripts/audit-github-settings.sh
```

The audit is expected to report drift until this branch is merged and the new
checks have completed successfully on `main`. Do not activate the required
checks before GitHub has observed their exact identities.

## Ordered rollout

1. Merge the deterministic workflow changes through the existing protected
   branch process.
2. Confirm successful `CI / pr-gate`, `Security / security-gate`, and CodeQL
   analysis on the exact `main` SHA.
3. Replace the broad all-branch ruleset with the `protected-main` state in the
   expected-settings file. Keep zero approvals while there is one maintainer,
   and retain only the organization-administrator `pull_request` bypass.
4. Enable Dependabot alerts/security updates, all declared secret-scanning
   controls, selected Actions, full-SHA pinning, and immutable releases.
5. Create `maven-central` and `maven-snapshots`; restrict each to `main`, move
   publishing secrets into the environments, require one maintainer approval,
   and keep self-review enabled for the current solo-maintainer model.
6. Run the read-only audit until it passes, then exercise Java, dependency-only,
   workflow-only, documentation-only, and fork pull requests.

CODEOWNERS remains advisory. When a second maintainer exists, require one
non-author CODEOWNER approval, dismiss stale approvals, and prevent release
self-review.

Agentic/AI review is deliberately deferred until the deterministic controls
above are stable. It must remain advisory, use GitHub-hosted runners, receive no
publishing secrets, and require explicit maintainer activation.
