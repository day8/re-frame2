# 24.05 — Reserved namespaces

## TL;DR

re-frame2 reserves a single root keyword namespace for framework-owned ids: `:rf/*`. Every framework runtime id — events, fx, cofx, subs, app-db keys, trace operations, error categories, warnings, machine lifecycle events, route events, navigation fx, SSR advisories, MCP wire markers, **everything** — lives under `:rf/*` or one of its sub-namespaces. This page is the human-readable catalogue. The linter checks this list; the migration agent checks this list; new Spec areas extend it by additive change. User code MUST NOT register handlers, fx, subs, or frames under `:rf/*`.

The normative catalogue lives at [Conventions.md §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned). This page is the guide-side narrative — the same names, in the same order, with one-line "what each one is for" so you can scan rather than read.

## Why one root

Old re-frame used 14 separate top-level prefixes: `:registry/*`, `:machine/*`, `:route/*`, `:nav/*`, `:re-frame/*`, and so on. Each Spec area picked its own prefix. The result was that "is this framework-owned?" became a memorisation question — every reserved name lived in a different mental bucket, and a user registering `:route/checkout` could find it colliding with a route name they wrote half a year ago.

re-frame2 collapses to **one root prefix** and hierarchical sub-namespaces under it. Every framework-owned name starts `:rf` and a slash or a dot — the answer to "is this framework-owned?" is one character: does it start with `:rf`?

That's it. `:rf.frame/...`, `:rf.error/...`, `:rf.http/...`, `:rf.machine/...`, `:rf.size/...`, `:rf.route/...`, `:rf.epoch/...`, `:rf.causa/...` — they're all framework-owned by virtue of the prefix. Pick anything outside the prefix and the framework's stance is "your name, your problem."

## The catalogue

Every reserved framework-owned sub-namespace. **21 entries** in the current set; the set is fixed-and-additive (entries never repurposed; new ones added by Spec change).

| Sub-namespace | What for | Owning spec |
|---|---|---|
| `:rf/*` | Pattern-level events / fx / app-db keys / subs; the universal default frame id `:rf/default` | 002 / 011 / 012 |
| `:rf.frame/<gensym>` | Anonymous frame ids minted by `make-frame` | 002 |
| `:rf.frame/<operation>` | Frame-lifecycle trace operations (`drain-interrupted`, `destroyed`, …) | 002 / 009 |
| `:rf.registry/*` | Registrar-mutation trace operations (`handler-registered`, `handler-cleared`, `handler-replaced`) | 001 / 009 |
| `:rf.fx/*` | Effect-resolution advisories (`:rf.fx/skipped-on-platform`, `:rf.fx/override-applied`) and reserved fx args (`:rf.fx/spawn-args`) | 002 / 009 |
| `:rf.cofx/*` | Cofx-resolution advisories (e.g. `:rf.cofx/skipped-on-platform`) | 009 / 011 |
| `:rf.error/*` | Error trace operations — the closed `:rf.error/<category>` taxonomy ([ch.14](../14-errors.md)) | 009 |
| `:rf.warning/*` | Warning trace operations — same shape as errors, severity `:warning` | 009 |
| `:rf.machine/*` | Machine lifecycle + transition trace operations; framework machine subs (`[:rf/machine <id>]`) | 005 |
| `:rf.machine.lifecycle/*`, `:rf.machine.timer/*`, `:rf.machine.event/*`, `:rf.machine.microstep/*` | Sub-areas of machine traces (further hierarchy under `:rf.machine`) | 005 |
| `:rf.route/*` | Framework routing events + subs + trace operations ([ch.17a](../17a-routing-reference.md)) | 012 |
| `:rf.nav/*` | Navigation fx ids (`:rf.nav/push-url`, `:rf.nav/replace-url`, `:rf.nav/scroll`, `:rf.nav/external`) | 012 |
| `:rf.ssr/*` | SSR-specific advisories (hydration mismatch, head mismatch, …) | 011 |
| `:rf.server/*` | Server-side response-shape fx (`:rf.server/set-status`, `-set-cookie`, `-redirect`, `-error-projection`, `-set-header`, `-append-header`) | 011 |
| `:rf.epoch/*` | Tool-Pair epoch operations (`restore-epoch`, version-mismatch, schema-mismatch, …) | Tool-Pair |
| `:rf.causa/*` | Canonical-devtools namespace for Causa — events, subs, fxs, app-db keys, traces | Tool-Pair |
| `:rf.assert/*` | Assertion-event vocabulary used by the post-v1 stories library's play functions and test runner | 007 |
| `:rf.test/*` | Test-runner-internal events and fx-stub ids | 008 |
| `:rf.http/*` | Managed-HTTP fx ids and failure taxonomy keys (`:rf.http/managed`, `-managed-abort`, `:rf.http/timeout`, `:rf.http/decode-failure`, …); security args slot `:rf.http/max-decoded-keys` | 014 |
| `:rf.http.interceptor/*` | Per-frame request-side interceptor chain lifecycle operations | 014 |
| `:rf.size/*` | Size-elision wire markers + policy keys (`:rf.size/large-elided`, `-threshold-bytes`, `-include-sensitive?`, `-include-large?`, …) | 009 |
| `:rf.elision/*` | Sentinel-handle namespace for the `:rf.elision/at` shape used by `get-path` to re-fetch an elided value | 009 |
| `:rf.mcp/*` | Cross-MCP wire-vocabulary markers (`:rf.mcp/overflow`, `:rf.mcp/summary`, `:rf.mcp/diff-from`, `:rf.mcp/dedup-table`, `:rf.mcp/ref`, `:rf.mcp/cache-hit`, `:rf.mcp/cursor-stale`) | Tool-Pair |
| `:rf.trace/*` | Trace-channel control slots — closed set of three: `:rf.trace/no-emit?`, `:rf.trace/trigger-handler`, `:rf.trace/call-site` | 009 |
| `:rf.route.nav-token/*` | Navigation-token lifecycle trace operations (`allocated`, `stale-suppressed`) | 012 / 009 |
| `:rf.adapter/*` | Substrate-adapter `:kind` discriminator values (`:rf.adapter/reagent`, `:rf.adapter/reagent-slim`, `:rf.adapter/uix`, `:rf.adapter/helix`, `:rf.adapter/plain-atom`, `:rf.adapter/ssr`) | 006 |

Add it up: 25 rows above; the audit listed 21 framework-owned prefixes because `:rf.frame/<gensym>` and `:rf.frame/<operation>` count together, `:rf.machine.*/*` sub-areas count under `:rf.machine`, and the canonical-namespace `:rf/*` itself is the root. Either way, the answer to "what's reserved?" is "anything that starts with `:rf.` and a slash."

## How the rules apply

Three rules. They cover every collision case.

### 1. User-registered ids must not collide

```clojure
;; FORBIDDEN — :rf/hydrate is a framework-pattern-level event
(rf/reg-event-fx :rf/hydrate ...)

;; FORBIDDEN — :rf.http/my-thing sits under a framework-reserved prefix
(rf/reg-fx :rf.http/my-thing ...)

;; FORBIDDEN — any segment under :rf.frame/* is reserved
(rf/reg-event-fx :rf.frame/my-custom-init ...)
```

The linter flags the registration. The migration agent flags the registration. The runtime registrar's `handler-replaced` trace fires loudly. If you must override a framework event for legitimate reasons (test fixtures replacing `:rf/hydrate` in-test, for example), use the documented extension points — `:on-create` on a test frame, `:fx-overrides` on a per-dispatch basis — not raw registration over a reserved id.

### 2. Library authors choose their own prefixes

Third-party libraries pick a top-level segment of their own. The convention is "library name, no slash":

```clojure
:reagent/*           ;; the Reagent integration
:re-pressed/*        ;; the re-pressed library
:re-frisk/*          ;; the re-frisk library
:my-app/*            ;; your app's feature prefix
```

Avoid `:rf*` for library names — both `:rf` (the framework root) and `:rf.<x>` (any sub-prefix) are off-limits. The framework can grow new sub-namespaces (`:rf.queue/*`, `:rf.someday/*`) and a third-party library claiming one would silently collide.

The two **library-owned** prefixes that live *adjacent to* the framework's `:rf.*` root are:

| Library-owned prefix | Library | Used for |
|---|---|---|
| `:story.<...>` | post-v1 stories library | Story ids (`:story.auth.login-form`) and variant ids |
| `:Workspace.<...>` | post-v1 stories library | Workspace ids |

These prefixes are **library-owned, not framework-reserved** — they're canonical when the library is loaded; they don't violate the single-root invariant. Their normative catalogue lives at [Conventions.md §Library-owned prefixes](../../../spec/Conventions.md#library-owned-prefixes).

### 3. Trace-event `:operation` vocabulary is open

A library can register its own trace operations under its own prefix — `:my-lib.error/something-broke`, `:my-lib.fx/store-write`, whatever. The framework's reserved set is closed (additive only by Spec change); your library's set is open.

This matters because a tooling consumer (a Datadog shipper, an MCP server) should be able to filter by your library's prefix without worrying about colliding with the framework's set. Pick your prefix once, stamp every trace event with it, and downstream filtering is mechanical.

## How to check yourself

Three quick paths to verify a name is free:

1. **The linter.** Run your normal CLJ/CLJS lint pass; the framework ships a rule that flags any registration under `:rf*`. If your lint pass is clean, your names are clean.

2. **The migration agent.** If you're coming from re-frame v1, the migration agent's first pass renames every `:re-frame/*` to `:rf/*` and flags every user-defined name colliding with the new reserved set. The output points at every collision.

3. **`(rf/handler-ids)`.** At the REPL, ask the framework what's registered:

   ```clojure
   (filter #(re-find #"^:rf" (str %)) (rf/handler-ids))
   ```

   Anything that comes back is framework-owned (or a stamped trace operation, which uses the same prefix space). If your name shows up here when you didn't intend it to, you've collided.

## When the framework wants to add a name

The framework adds names by **additive change to the Conventions.md table**. New sub-namespaces ship under `:rf.<spec-area>/*` rather than inventing a top-level prefix. The fixed-and-additive contract means existing entries can't be repurposed and can't be removed — your code under, say, `:my-app.http/*` keeps working; a brand-new `:rf.deferred/*` namespace appears (because Spec 015 introduced it) without disturbing anything.

If you've registered a handler under a name that looks like a future framework reservation but isn't yet, the migration agent will flag it when the framework eventually claims that name. The fix is mechanical: rename your handler. The framework's commitment is that this case is rare; the catalogue above is the practical upper bound on what you should consider off-limits forever.

## Cross-references

- [Conventions.md §Reserved namespaces](../../../spec/Conventions.md#reserved-namespaces-framework-owned) — the normative catalogue.
- [Conventions.md §Reserved fx-ids](../../../spec/Conventions.md#reserved-fx-ids) — the unqualified reserved fx-id set (`:dispatch`, `:dispatch-later`, `:raise`, `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`).
- [Conventions.md §Reserved app-db keys](../../../spec/Conventions.md#reserved-app-db-keys) — `:rf/machines`, `:rf/system-ids`, `:rf/spawned`, `:rf/route`, `:rf/pending-navigation`, `:rf/elision`.
- [Conventions.md §Reserved state-node keys](../../../spec/Conventions.md#reserved-state-node-keys-machine-transition-tables) — the machine transition-table state-node keys (`:on`, `:entry`, `:exit`, `:meta`, `:states`, `:always`, `:after`, `:invoke`, `:invoke-all`).
- [Chapter 14 — Errors](../14-errors.md) — the `:rf.error/*` taxonomy in narrative form.
- [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md) — the complete `:rf.error/*` + `:rf.warning/*` enumeration.
