# Security Policy

## Reporting a vulnerability

If you believe you have found a security issue in re-frame2 — the
specification, the reference implementation, or any of the tools shipped from
this repository — please email **security@day8.com.au** rather than opening a
public GitHub issue or pull request. Include enough detail for us to reproduce
the problem (a minimal example, expected vs. observed behaviour, and the
affected file or surface).

We will acknowledge receipt within seven days and keep you informed as we
investigate and resolve. We ask that you give us a reasonable window to ship a
fix before discussing the issue publicly; we will coordinate the disclosure
timeline with you.

## Supported versions

re-frame2 is currently **pre-alpha**. No artefacts are published to Clojars or
npm yet, and there is no formal support SLA. Fixes land on `main`; consumers
pinning to a `:git/sha` coordinate are expected to update to pick them up.

Once re-frame2 reaches 1.0, the supported-versions matrix will be published
here. The expected baseline is: security fixes for the latest minor release.

## Scope

In scope:

- the specification ([`spec/`](spec/)) where wording would mislead an
  implementer into a security-relevant behaviour;
- the CLJS reference implementation ([`implementation/`](implementation/));
- the tools ([`tools/`](tools/)) — Causa, Story, the MCP servers, the
  template, and supporting libraries.

The implementation-specific security posture (named functions, numeric
defaults, decisions log) is catalogued in
[`implementation/SECURITY.md`](implementation/SECURITY.md), with the
pattern-level companion in [`spec/Security.md`](spec/Security.md). Both are
useful background when filing a report.
