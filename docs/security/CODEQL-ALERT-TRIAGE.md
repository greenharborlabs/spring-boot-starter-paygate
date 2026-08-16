# CodeQL Alert Triage

Triage performed against the hardened release branch before enabling the
CodeQL ruleset gate:

| Alert | Severity | Rule | Disposition |
|---|---:|---|---|
| 37 | High | `java/spring-disabled-csrf-protection` | Fixed on this branch. The example security chain no longer disables CSRF. |
| 39, 40 | High | `java/sensitive-log` | Fixed by removing token identifiers from capability-resolution log messages. |
| 41, 42 | Medium | `java/unreleased-lock` | Fixed by replacing branch-dependent dual-lock acquisition with globally ordered, nested `try/finally` scopes. |

No alert is dismissed. After merge, the unfiltered CodeQL workflow must analyze
the exact `main` SHA and close these alerts before the severity-based ruleset is
activated. Any alert that remains open must be fixed or recorded here with a
narrow, evidence-backed dismissal before activation.
