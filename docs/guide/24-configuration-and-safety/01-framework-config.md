# 24.01 — Framework configuration

## TL;DR

`(rf/configure key opts)` is the one entry point for process-level framework knobs. The vocabulary is closed-and-additive: four keys today, never renamed, never removed; new keys arrive by Spec change. This page enumerates them, lists their defaults, and gives you the question each one answers.

`set-!` / `install-!` are the *other* entry point — for hooks that need a fn-reference rather than a data value. Per-frame metadata is the third. The chapter index sketched the three-bucket model; this page is the inside of bucket 1, with a glance at bucket 2.

## The `configure` keys

There are four. Each is a plain-data setting that applies to the framework runtime as a whole. The full normative table — vocabulary, opts shape, defaults, status — lives at [API.md §Configure keys](../../../spec/API.md#configure-keys); this page is the guide-side narrative.

### `:epoch-history` — how far back can you rewind?

```clojure
(rf/configure :epoch-history {:depth 50})        ;; the default
(rf/configure :epoch-history {:depth 200})       ;; deeper history; more memory
(rf/configure :epoch-history {:depth 0})         ;; disable
```

Every dispatched event's full cascade is recorded as an *epoch record* — `:db-before`, `:db-after`, `:sub-runs`, `:renders`, `:effects`, `:trace-events` — and stored in a ring buffer. That buffer is what powers [chapter 15](../15-devtools-and-pair-tools.md)'s time-travel debugging, `restore-epoch`, `reset-frame-db!`, and the Tool-Pair surface.

50 epochs is enough for a typical debug session (you almost never want to rewind further than 50 user actions). 200 is reasonable for long-running stress tests. 0 disables history entirely — useful in SSR production where you have no replayer attached and the per-cascade allocation is wasted work.

The setting is **dev-only** by status. Under `:advanced` + `goog.DEBUG=false`, the recording site DCEs and the buffer never allocates, regardless of what you configured.

### `:trace-buffer` — how many trace events sit in memory?

```clojure
(rf/configure :trace-buffer {:depth 200})        ;; the default
(rf/configure :trace-buffer {:depth 1000})       ;; longer trace history
(rf/configure :trace-buffer {:depth 0})          ;; disable the buffer
```

The trace buffer is the ring of `:rf.*/*` trace events that backs your dev tooling — the same stream that [chapter 22](../22-trace-to-datadog.md) ships to observability back-ends, the same stream that pair2-mcp inspects when an agent asks "what happened in the last cascade." Bigger buffer means longer history; smaller buffer means less memory.

The default of 200 is sized to comfortably hold a single complex cascade (one user action that fans out into 30+ machine transitions, an HTTP response, a few sub-runs, the lot). If you're investigating a multi-event saga, bump it. If you're in production and you've registered listeners that ship events as they arrive (rather than reading the buffer), set it to 0 — the listeners get every event live; the buffer is wasted memory.

Like `:epoch-history`, the buffer is dev-only. Production builds elide the allocation regardless.

### `:sub-cache` — how long do unused subscriptions live before disposal?

```clojure
(rf/configure :sub-cache {:grace-period-ms 50})   ;; the default
(rf/configure :sub-cache {:grace-period-ms 200})  ;; tolerate longer route transitions
(rf/configure :sub-cache {:grace-period-ms 0})    ;; synchronous disposal
```

Subscription caching is ref-counted: when the last consumer of a subscription disappears, the cache schedules disposal. The **grace period** is how long the disposal waits — if a new consumer shows up inside the window, the subscription is rescued and the disposal is cancelled.

The reason this matters: route transitions, modal close-and-reopen flows, and "scrub the timeline forward then back" interactions briefly unmount and remount the same subscription tree. With a 50ms grace period, those flows reuse the cached subscription without re-running the computation; the cache is doing its job. Tune up if your app has slow-mounting routes; tune down (or to 0) if you're memory-constrained and want subs to drop the second nobody's reading them.

Setting it to 0 selects synchronous disposal: the subscription tears down on the same tick as the last consumer leaves. Useful for tests that assert on cache cardinality; rarely useful in production.

Unlike the two above, `:sub-cache` is **not** dev-only — the sub-cache exists in production builds, and the grace-period configuration applies there too.

### `:elision` — how big is "big enough to elide?"

```clojure
(rf/configure :elision {:rf.size/threshold-bytes 16384})   ;; the default — 16KB
(rf/configure :elision {:rf.size/threshold-bytes 65536})   ;; tolerate bigger inline values
(rf/configure :elision {:rf.size/threshold-bytes 0})       ;; disable runtime size-based detection
```

The wire-elision walker (per [chapter 23b](../23b-large-blobs.md) and [API.md §`rf/elide-wire-value`](../../../spec/API.md#elide-wire-value-the-wire-boundary-walker)) replaces large values with `:rf.size/large-elided` markers before they egress to off-box consumers. The threshold-bytes value is the **runtime auto-detection threshold** — a value larger than this gets a marker even if the schema didn't pre-declare it as `:large?`.

16KB is the default because it's about the size where pretty-printing the value into a Datadog event starts being a bad idea. Tune up if you've got endpoints that legitimately return larger blobs that fit your back-end; tune down if you're shipping to a back-end with a smaller event limit.

Setting it to 0 disables the runtime walker for size-based detection; only **schema-declared** `:large?` slots elide. That's the right setting for environments where you've fully audited every slot via schemas and want the runtime to honour those declarations and nothing else — no accidental elision of an unexpectedly-large response payload, no false negatives from "small slot containing a large value."

Note that `:sensitive?` elision is **never** size-gated. Sensitive values redact regardless of size; the threshold only governs `:large?`-flavoured size elision.

## When to tune

The defaults are the right answer for almost every app. The cases where you tune are narrow:

- **Bumping `:epoch-history`** for a debug session where you want to scrub a longer cascade.
- **Bumping `:trace-buffer`** when you're hunting a bug that spans multiple user actions and the existing buffer is rotating events out before you can read them.
- **Bumping `:sub-cache` grace period** when route transitions feel laggy because the sub recomputed instead of resurrecting from cache. (This is rare; the default catches most cases.)
- **Bumping `:elision` threshold** when your back-end can handle larger events than 16KB and you want fewer round-trips to refetch elided values.
- **Setting any of the dev-only keys to 0** in long-running JVM SSR processes where dev recording is wasted allocation.

If you find yourself wanting to tune something that isn't on the list, the option doesn't exist — and that's deliberate. The framework's stance is that the per-process knobs are a small fixed set; new knobs land by Spec change, not by adding a "configurable" flag to wallpaper over a design issue.

## The `set-!` / `install-!` neighbours

A few things look like they should be `configure` keys but aren't, because the value the framework needs is a fn or component reference rather than data. These live under separate `set-!` / `install-!` fns; the bang on the end is the framework's way of telling you the surface mutates a process-level slot the framework calls into from arbitrary sites.

```clojure
(rf/install-adapter! reagent/adapter)                ;; install the reactive substrate
(rf/set-schema-validator! malli.core/validate)       ;; swap the schema validator
(rf/set-schema-explainer! malli.core/explain)        ;; swap the schema explainer
```

If you're using the default substrate ([chapter 19](../19-adapters.md)) and Malli for schemas ([chapter 04a](../04a-schemas.md)), you call none of these and the boot wiring is automatic. If you want to drop the Malli dependency and bring your own validator (Plumatic, Specs, hand-rolled), `set-schema-validator!` is the entry point.

These are **not** folded under `configure` because keyword-keyed addressing loses the type information a consumer needs to pass an actual fn/component reference. `configure` is for *data*; `set-!` is for *impls*. That asymmetry is the explicit signal: "the framework is going to hold this reference and call it back from places you don't control."

For the full enumeration of the `set-!` / `install-!` surface, see [API.md §Adapter lifecycle](../../../spec/API.md) and [API.md §Schemas](../../../spec/API.md). Both surfaces are small (five-ish fns each) and follow the same pattern: install / swap / inspect / dispose.

## The per-frame neighbour

The third bucket is per-frame metadata. Anything whose lifetime is "as long as this frame exists" — `:fx-overrides` for one test fixture, `:drain-depth` tightened for one story, `:on-error` for one production frame versus one SSR frame — lives on the frame's metadata, not in `configure`:

```clojure
(rf/reg-frame :auth
  {:on-create  [:auth/initialise]
   :drain-depth 100
   :on-error   {:default :log
                :rf.error/drain-depth-exceeded :halt}})
```

[Chapter 06a](../06a-frames.md) walks the whole frame-metadata grammar. The next page in this chapter, [§04 Drain depth](04-drain-depth-and-error-recovery.md), digs into `:drain-depth` and `:on-error` specifically — both are bucket-3 knobs that the safety story leans on.

## Cross-references

- [API.md §Configure keys](../../../spec/API.md#configure-keys) — the normative key table.
- [Conventions §Configuration surfaces](../../../spec/Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) — the three-bucket model.
- [Chapter 06a — Frames](../06a-frames.md) — the per-frame metadata grammar.
- [Chapter 15 — Tooling](../15-devtools-and-pair-tools.md) — the consumers of `:epoch-history` and `:trace-buffer`.
- [Chapter 19 — Adapters](../19-adapters.md) — `install-adapter!`, `dispose-adapter!`, and friends.
- [Chapter 23b — Large blobs](../23b-large-blobs.md) — the consumer of `:elision`.
