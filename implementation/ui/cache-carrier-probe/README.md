# S0 carrier proof — Shadow disk-cache round-trip of analyzer descriptors

This is the `rf2-u53yy.1.1` premise spike for the install/cache-tax
re-architecture (`rf2-u53yy.1`). It is a **standalone, plain** shadow-cljs project,
deliberately outside the main `implementation/` source-paths and carrying neither
`:cache-blockers` nor the `re-frame.ui` build hook, so the disk-cache hit it
measures is genuine.

## The question (exactly one)

On a **real** shadow-cljs disk-cache HIT — daemon restarted, sources untouched,
the consumer namespace verifiably NOT recompiled — does Shadow restore arbitrary
namespaced, EDN-only metadata that macro expansion attached to

- **(A)** a `def`'s `:meta`, and
- **(B)** the namespace's analyzer data

into `[:cljs.analyzer/namespaces]` of the build state, as observable by a build
hook at `compile-finish`?

These are the two carriers the re-architecture needs: it moves whole-build registry
truth (views / elements / roots+plans / descriptors) off macro-expansion side
effects and into per-namespace analyzer descriptors that survive Shadow's disk
cache, so `:cache-blockers #{re-frame.ui}` and the exact `3.4.10` pin can be
deleted from every consumer's install contract. Variant A mirrors re-frame.ui's
`var-meta` stamp (`compiler/emit_cljs.cljc:1027`); variant B mirrors its
`swap! cljs.env/*compiler*` mutation of the analyzer ns map
(`compiler/emit_cljs.cljc:1004-1007`).

## The answer: YES for BOTH variants, across a tested version range

| shadow-cljs | genuine disk-cache hit | variant A (def `:meta`) | variant B (ns-level) |
| ----------- | ---------------------- | ----------------------- | -------------------- |
| 3.4.0       | proven                 | **YES**                 | **YES**              |
| 3.4.10 (pinned minimum) | proven     | **YES**                 | **YES**              |
| 3.4.11 (latest)         | proven     | **YES**                 | **YES**              |

Both carriers round-trip through Shadow's on-disk analyzer cache with **exact EDN
equality** (nested maps, sets, and vectors intact), including variant B's arbitrary
namespaced key on the namespace analyzer map, which one might expect CLJS to strip.

**Consequence (already ruled — see the `rf2-u53yy.1` DECISION note): YES selects
the analyzer-map descriptor carrier for S2. No sidecar files.** Plan-B
(per-namespace sidecar EDN files) is not needed and is not pursued.

Two facts worth carrying into S2:

- The analyzer namespaces live at **`[:compiler-env :cljs.analyzer/namespaces <ns>]`**
  in the build state, not at build-state top level (the bead's shorthand). The
  build hook reads them there at `compile-finish`.
- The mechanism is **not fragile to the exact patch version** — it holds a full ten
  patch releases below the pin (3.4.0) through the current latest (3.4.11) — which
  is the evidence behind the parent acceptance's "tested range, not one exact pin".

## How the proof works (why it can't be fooled)

The macro appends a line to `target/s0-witness/expansions.txt` **at expansion
time**. Two independent one-shot `shadow-cljs compile` runs (each a fresh JVM, no
persistent server) share only the on-disk cache:

1. **Pass 1 (cold):** 45 namespaces compiled, 2 witness lines written, both carriers
   present (baseline).
2. **Pass 2 (warm):** no source byte touched. A genuine disk-cache hit means Shadow
   reports **0 namespaces compiled** and the witness file **does not grow** — the
   macro did not re-run. Yet the carriers are still present in the restored analyzer
   data, so they were **restored from disk**, not re-stamped.

The `compile-finish` hook writes `summary.edn` with two booleans computed in Clojure
by **exact equality** against the values the macro stamped, so a truncated or
key-stripped round-trip fails rather than passing a presence check. The driver
asserts the cold baseline is `YES/YES` (guarding against a probe bug masquerading as
a real NO) before trusting the warm-pass result.

## Reproduce

```bash
# From this directory, using the implementation/ shadow-cljs (3.4.10):
node run.cjs

# Sweep another supported version:
mkdir -p /tmp/shadow-3.4.11 && cd /tmp/shadow-3.4.11
npm install shadow-cljs@3.4.11
RF2_SHADOW_DIR=/tmp/shadow-3.4.11 node <path-to-this-dir>/run.cjs
```
