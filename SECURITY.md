# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| `0.1` | Yes |
| older pre-release builds | No |

## Reporting a vulnerability

Please do not publish sensitive security issues together with:

- private source URLs
- access tokens
- cookies
- credentials
- exploit details that expose users directly

If GitHub private vulnerability reporting is available for this repository, use that path first.

If it is not available, open a minimal public issue that only states:

- the affected version
- the rough impact area
- that you need a private follow-up channel

Do not include secrets or exploit material in that public issue.

## What counts as a security issue here

Examples:

- credential or token leakage
- unsafe handling of subscription URLs or cookies
- remote code execution or script injection paths
- unsafe file access
- vulnerable native dependency usage
- exported components that allow unauthorized access

## What does not need a security report

These should usually go through the normal issue templates instead:

- playback incompatibility
- HDR not activating on a device
- subtitle rendering problems
- UI layout or focus bugs
- device-specific decode limitations without a security impact

## Response goals

When a valid security report is received, the maintenance goal is:

- confirm impact
- reproduce if possible
- reduce public exposure
- ship a fix or mitigation in a follow-up release

Response time is best-effort and depends on the severity and reproducibility of the report.
