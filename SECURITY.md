# Security Policy

## Reporting a Vulnerability

**Do not report security vulnerabilities through public issues, discussions, or
pull requests.**

Report them privately via
[GitHub private vulnerability reporting](https://github.com/line/centraldogma/security/advisories/new).

Please include the affected version, a minimal reproducer, and the impact —
what an attacker gains and what privileges they need to start with.

We are a small group of maintainers and cannot promise a fixed response time,
but we take these reports seriously and will get back to you as soon as we can.
If you haven't heard anything for a while, please feel free to ping us on the
advisory thread.

We ask that you keep the report confidential and give us a reasonable chance to
ship a fix before disclosing publicly. If our pace becomes a problem for you,
tell us — we would much rather agree on a disclosure date together than have it
come as a surprise.

Reporters are credited in the published advisory unless you ask otherwise. We do
not operate a bug bounty program.

## Supported Versions

Security fixes are released only for the latest minor version. Older versions are
not patched — please upgrade.

## Scope

Access control bypass, authentication and token flaws, credential exposure
(including mirroring credentials), and web console vulnerabilities are in scope.

The following are not, unless you can show otherwise:

- A server running without an authentication provider configured
- An administrator configuring a mirror against an arbitrary remote
- Exposing the replication port or ZooKeeper ensemble to an untrusted network
- Dependency CVEs with no reachable call path
- Automated scanner output with no demonstrated impact
