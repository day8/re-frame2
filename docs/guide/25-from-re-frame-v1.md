# 25 - From re-frame v1

You have a re-frame v1 app and a migration to plan, and the question you actually want answered isn't "what's the API diff" — it's "how big a deal is this, really?" The honest answer is the reassuring one: most of your code is already v2 code and doesn't know it. This chapter is the map of what crosses over unchanged, what changed and *why*, and the handful of places where the v2 architecture tells a genuinely different story than the one you wrote against in v1.

## The good news first, because it's load-bearing

Here's the thing that should reframe the whole project before you start: **the bones are identical.** The six dominoes are the six dominoes. Events are still data describing what happened. Handlers are still pure functions of `(db, event) → db`. Subscriptions are still derivations off a single `app-db`. The whole opinionated stance — one source of truth, data over APIs over syntax, immutable values and stable contracts — is the v1 stance, because v2 *is* v1's architecture with new capabilities grown on top, not a different architecture wearing the same name.

That's why your v1 code reads as v2 code on the first pass, most of the time without you noticing what changed. A `reg-event-db` is a `reg-event-db`. A `reg-sub` is a `reg-sub`. A hiccup view is a hiccup view. The migration is not a rewrite; it's a *sweep* — a bounded set of mechanical renames, a smaller set of judgment calls, and a few genuinely new shapes you'll *choose* to adopt rather than be forced into. The framework's own number for the rule set is "40-plus M- and O-rules," and the reason that number sounds scarier than the work feels is that the vast majority of them are find-and-replace, and a tool does them for you.

## Don't do this by hand

Which brings me to the most important sentence in the chapter: **the migration is automated, and you should not hand-migrate anything larger than a toy.** There's a Claude Code skill that ships in this repo, [`skills/re-frame-migration/`](../../skills/re-frame-migration/), whose entire job is to drive the sweep. It walks six phases — orient, bump, sweep, verify, optional modernisations, report — applies the mechanical rewrites unprompted, and *stops at every judgment call to ask you* before it touches anything risky. The mechanical rewrites are what the skill calls Type A; the judgment calls are Type B, and the cardinal rule the skill obeys is **don't invent migration rules** — if a failure doesn't match a known shape, it surfaces it for human review rather than guessing.

The workflow is four steps:

1. Open a fresh Claude Code session at the root of your v1 project.
2. Paste the kickoff prompt from [`skills/re-frame-migration/references/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/references/kickoff-prompt.md). The session loads the skill and walks the phases autonomously.
3. Answer questions at the Type B checkpoints — the agent explains the risk and waits for your call before rewriting.
4. Run your test suite. The agent re-verifies and produces a migration report.

The rest of this chapter is *orientation* — the mental model of what the skill is doing, so that when you're reading a diff at step 3 and asking "what kind of thing am I looking at?", you have a category to slot it into. The exhaustive rule list lives in the skill; this is the why behind it.

## The deps change: pay-as-you-go

re-frame2 is **pay-as-you-go** — capabilities ship as separate artefacts so unused ones never bundle. That single fact shapes the whole deps migration:

1. **Swap the core coord.** Remove `re-frame/re-frame`. Add `day8/re-frame2`.
2. **Add a substrate adapter** for your view library — `day8/re-frame2-reagent` if you're on Reagent (and bump Reagent to v2, which the reference targets), or the matching UIx/Helix adapter if you've already moved off Reagent. ([Chapter 22 — Adapters](22-adapters.md) is the substrate story in full.)
3. **Add per-feature artefacts only for features you actually use.** Don't add them all "to be safe" — the skill tells you which ones the codebase trips. The split is `day8/re-frame2-{machines, flows, routing, http, ssr, schemas, epoch}`, and an app that doesn't use flows doesn't carry flow code.
4. **Don't bump anything else in the same change.** Keep React, shadow-cljs, and the rest on their current versions until the migration settles. Separate failure modes are far easier to debug separately, and a migration that's also a dependency upgrade is two bugs wearing one diff.

The skill handles every part of this; the list is here so you know what's coming.

## What changed, and why — the categories

These are the broad shapes of breakage. The skill identifies and resolves them; this is the taxonomy so you can read a diff with comprehension instead of alarm.

**Registrar imports.** Code that requires `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`, or `re-frame.alpha` directly was reaching past the front door, and v2 closes it. The single-import contract is `(:require [re-frame.core :as rf])`. Direct access to `re-frame.db/app-db` — always off-contract — is now firmly so; the accessor is `(rf/app-db-value :rf/default)`, returning a plain map. The reason for the tightening is the same reason chapter 18's frames work at all: when there can be N isolated app-db instances, "the global app-db atom" stops being a coherent thing to reach for, and the contract has to be a function call that names which frame you mean.

**Removed surfaces.** A handful of v1 affordances are gone, each with a defined replacement: `dispatch-with` / `dispatch-sync-with` fold into a two-arg `dispatch` with an opts map; `reg-global-interceptor` is gone because interceptors are frame-scoped in v2; `reg-sub-raw` gives way to `reg-sub` or the substrate adapter; the `^:flush-dom` event metadata becomes `:dispatch-later {:ms 0}`. None of these is a capability *loss* — they're consolidations, the same job done through one shape instead of several.

**Removed interceptors.** This is the category that surprises people, so it's worth dwelling on. Five v1 interceptors are gone — `debug`, `trim-v`, `on-changes`, `enrich`, and `after` — and the reason each one left is that v2 grew a *better-shaped* answer to the problem it solved. `debug` is subsumed by the trace bus ([chapter 16](16-observability.md)), which sees everything `debug` saw and a great deal more. `trim-v` is unnecessary because the canonical event shape is consistent now. `enrich` and `after` are replaced by flows and schemas — declarative, registered, tooling-visible versions of "compute a derived thing after the handler" and "assert something about the result." And `on-changes` becomes *flows*, which deserve their own section below. The retained interceptor set is deliberately tiny: `inject-cofx`, `path`, `unwrap`, and the `->interceptor` primitive for rolling your own.

**Effect-map shape.** Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` shorthands fold into the `:fx` vector. `:db` is unchanged. If you've internalised "effects are a vector of `[id arg]` pairs" from [chapter 07](07-effects-and-coeffects.md), this is just that shape arriving where the shorthands used to be.

**Test harness rename.** `re-frame-test` becomes `re-frame.test-support`. The namespace moves; the test *bodies* usually don't change.

**View-rendering boundary.** Plain Reagent fns keep working, but they earn a runtime warning if rendered under a non-default frame's subtree (single-frame apps never see it). `reg-view` adoption is opt-in modernisation, not a migration requirement.

When a failure matches none of the above, the skill surfaces it for human review rather than guessing. That's the cardinal rule again, and it's what keeps an automated migration trustworthy: it does the things it's sure of and asks about the rest.

## The two changes worth understanding in depth

Most of the categories above are mechanical and the skill handles them while you watch. Two are different enough that understanding them pays off — because they're places where the v2 architecture isn't just renaming a thing, it's offering a genuinely better shape.

### HTTP folds onto `:rf.http/managed`

A v1 codebase that registered its own `:http` fx — or leaned on `re-frame-http-fx`, `re-frame-fetch-fx`, or a cousin — migrates onto `:rf.http/managed` (the subject of [chapter 10 — HTTP](10-http.md)). The skill recognises the shape and the rewrite is mostly mechanical:

1. Add the `day8/re-frame2-http` artefact and require it from the namespaces that issue requests.
2. Replace `[:http {:url ... :on-success ... :on-error ...}]` with `[:rf.http/managed {:request {:url ...} :on-success ... :on-failure ...}]`. Wire-shape keys (`:method`, `:url`, `:body`, `:headers`, `:params`) move *inside* `:request`.
3. Rename `:on-error` → `:on-failure`. The reply payload appends as the last argument; destructure `{:keys [value]}` for success, `{:keys [failure]}` for failure.
4. Adopt the closed `:rf.http/*` failure category set — code that branched on `(:status err)` becomes branching on `(:kind failure)`.

The skill applies 1–4 unprompted and stops at an optional step 5 (collapsing per-call success handlers into default reply addressing) for review. The reason this is more than a rename is that `:rf.http/managed` is a *managed* effect — it owns retries, aborts, double-submit suppression, the slow-loris timeout from [chapter 24](24-config-and-safety.md), and the eight-category failure taxonomy. Migrating onto it isn't just renaming your old fx; it's deleting a pile of hand-rolled request-lifecycle code that the framework now does correctly for you.

### `on-changes` becomes flows

This is the one v1 concept that maps onto something with a genuinely new name and a slightly different shape, so it earns the most ink. v1's `on-changes` interceptor said "when these in-paths change, compute and write to that out-path." v2's **flows** say the same thing — same compute-on-input-change semantics — but lifted *out* of the per-event interceptor chain and *into* the runtime: registered once, evaluated automatically right after the event handler (as the outermost `:after`, transforming the pending `:db` effect before it installs), and toggleable at runtime.

```clojure
(rf/reg-flow
  {:id     :rectangle/area
   :inputs [[:width] [:height]]      ;; vector of app-db paths
   :output (fn [w h] (* w h))         ;; pure: (in-1, in-2, ...) → output
   :path   [:area]                    ;; where the result is written
   :doc    "Rectangle area computed from :width and :height."})
```

Two things to internalise before you reach for them, because the easy mistake is to treat flows as a sub replacement and end up with a smell:

**Flows are a niche convenience, not a sub replacement.** They're for derived values that are part of the application's *state* — visible to other event handlers, surviving SSR hydration, covered by registered schemas, queryable from the app-db inspector. If the derived value is consumed *only by views*, the right tool is a subscription: lighter, sub-cache-native, no `app-db` write. A healthy re-frame2 app has dozens of subs and a *handful* of flows. Tens of flows is the framework telling you that you reached for the wrong tool.

**Flows can reach what `on-changes` couldn't.** `on-changes` was statically wired into specific events at registration time, so a derivation that should run *conditionally* — only while a wizard step is active, only when a feature gate is engaged, only in advanced mode — had no clean shape. Flows are runtime-registered and runtime-clearable via `:rf.fx/reg-flow` / `:rf.fx/clear-flow`; toggling one is an ordinary fx. So the migration sometimes *improves* the code it touches: a thing that was awkwardly always-on becomes cleanly conditional.

The rewrite itself is Type B — mechanically `(rf/on-changes f out-path & in-paths)` → `(rf/reg-flow {:id ... :inputs in-paths :output f :path out-path})`, but the agent stops to ask about the `:id` (it suggests `:legacy/<event-id>` as a default) and whether the flow should be conditional rather than always-on. An app with no `on-changes` sees no migration here at all.

## The devtools moved house

One last orientation point that trips people who lived in v1's tooling. `re-frame-10x` — the v1 devtools panel — has been renamed and *reimplemented* as **Xray** (`day8/re-frame2-xray`). The word to weight is *reimplemented*: Xray is not 10x ported to v2, it's a from-scratch build against re-frame2's own trace bus and epoch-history surfaces. The mental model carries over completely — events, subs, app-db diff, time-travel are all there — but the wiring underneath is new. If your v1 project depended on 10x during development, the v2 equivalent is Xray, and [the Xray tutorial](../xray/index.md) is where you meet it.

That's the migration in one read: the architecture is the same architecture, the sweep is automated, the handful of genuinely-new shapes (managed HTTP, flows) are improvements you'll be glad to adopt rather than taxes you'll resent paying, and the one rule that keeps all of it honest is *don't invent migration rules* — the tool does what it's sure of and asks about the rest. When the migration settles, [chapter 26](26-operating-well.md) points at how to operate from there.
