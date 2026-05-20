# 24 — Configuration and safety primitives

## TL;DR

re-frame2 ships with a small set of knobs you can turn — and a smaller set of guardrails you can't. This chapter is about both. The knobs live behind `configure`, `set-!` / `install-!`, and per-frame metadata. The guardrails — CRLF rejection, scheme allowlists, JSON keyword caps, slow-loris timeouts, drain-depth ceilings — sit between your code and a class of failure mode you don't want to be the person who discovered.

You don't have to read this chapter to write a re-frame2 app. The defaults are sensible. But the day you wonder *why* your handler stopped at a header value containing `\r\n`, or *what number* the trace ring buffer actually holds, or *which prefix* the framework is going to flinch at when you try to register a handler under it — this is the chapter.

## The shape of the thing

The framework has three orthogonal places configuration can live, and each one exists because the **lifetime** of the thing being configured is different. Once you see the three buckets, the API stops feeling scattered:

| Lifetime | Surface | Examples |
|---|---|---|
| Process — apply to the runtime as a whole | `(rf/configure :key opts)` | trace-buffer depth, sub-cache grace period, elision threshold |
| Process — but the value is a fn or component the framework has to call | `(rf/set-x!)` / `(rf/install-x!)` | schema validator, schema explainer, substrate adapter |
| Per-frame — apply only to one frame's existence | `reg-frame` / `make-frame` metadata, or `dispatch` opts | `:drain-depth`, `:on-error`, `:fx-overrides`, `:interceptors`, `:on-create` |

The first two are global. The third is local. If you ever feel like you want to configure the same thing in two places, the option is doing two things and should be split. The framework's stance is one option, one bucket; that constraint is what makes the configuration story small enough to hold in your head.

For the canonical normative description of the three buckets see [Conventions §Configuration surfaces](../../../spec/Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata).

## The shape of the safety story

Safety primitives in re-frame2 work the same way the rest of the framework does: **the architecture forbids the thing**, rather than the docs asking you politely. Each safety primitive in this chapter has the same shape:

1. A specific input or condition that, if it reached the system, would cause a class of failure (header injection, DoS-by-keyword, runaway recursion, XSS via clickable IDE link).
2. A check site, deep in the framework, that detects the condition.
3. A structured failure that surfaces to your `:on-error` policy (or your trace listener, or your error projector) so you see the bug the way you'd see any other.

The framework does not strip-and-warn. It does not silently normalise. It does not "do its best with what you gave it." It rejects, raises, and tells you exactly what was wrong. The bet — same bet [chapter 12](../12-the-dynamic-model.md) makes about the dynamic model — is that surfacing the bug at its source is cheaper than letting it bake itself into the system's observable behaviour.

> **Where the contracts live.** This chapter is the *guide-side* tour. The normative descriptions of every safety primitive live in [`spec/Security.md`](../../../spec/Security.md) (threat model + defense-in-depth catalogue). That doc is, as of pre-alpha, slated to split into *pattern* and *implementation* halves (per rf2-1g6cj) — when that lands, the cross-refs in this chapter may need rewriting. The information is what matters; the path will move.

## What this chapter covers

Eight pages. Each page answers one concrete question:

- [01 — Framework configuration](01-framework-config.md). What `configure` lets you tune (and when to bother). The four keys, what they default to, and how to know your value is doing anything.
- [02 — HTTP safety primitives](02-http-safety.md). The CRLF fail-fast on server-side responses, the JSON keyword-interning cap, and the slow-loris timeout that makes the network give up on unresponsive partners before they exhaust your pool.
- [03 — Redirect and editor URIs](03-redirect-and-editor-uris.md). What schemes the framework rejects on click-to-source IDE templates, and the related sanity gates on `:rf.server/redirect`.
- [04 — Drain depth and error recovery](04-drain-depth-and-error-recovery.md). What the run-to-completion drain's depth ceiling protects you from, why the rollback is atomic, and how to tune it per frame.
- [05 — Reserved namespaces](05-reserved-namespaces.md). The single catalogue of every `:rf.*/*` prefix the framework owns. Tools check; the linter checks; this is the human-readable copy.
- [06 — Machine substrate features](06-state-machine-substrate-features.md). The four advanced state-node keys ([chapter 09](../09-state-machines.md) names them in passing) — `:always`, `:after`, `:spawn`, `:spawn-all` — with worked examples and the rules they enforce. Plus `:final?` / `:on-done` / `:output-key` and how parallel regions compose.
- [07 — Privacy and elision in practice](07-privacy-and-elision.md). The tutorial layered on top of [ch.23a](../23a-privacy-secrets.md) and [ch.23b](../23b-large-blobs.md): a single running example (payments, GDPR export, photo upload, server-side imports) walking through the four progressive tiers of declaration that keep sensitive and oversized values off the wire.
- [08 — Exceptions under `:sensitive?`](08-exceptions-under-sensitive.md). The residual author surface that path-marked redaction can't reach: exception messages assembled from sensitive paths, and `ex-data` maps with author-supplied keys. Three patterns and a tiny helper that closes the gap.

Read in order if you're new. Skim individual pages when something specific bites.
