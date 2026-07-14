# 06 — Debugging

*Part II, step 8 · [← 09](09-testing.md) · [Next: 07 →](07-performance.md)*

Dev builds answer causal questions directly, in the tools you already run (Xray, Story,
the Pair, React DevTools). Everything on this page is dev-only and provably absent from
production builds.

*(Stage note: compile-time failures — with file:line coordinates — are Stage 1,
shipped. The causal surfaces — causes, manifests, the interaction surface, DOM
source-coordinate stamping, Xray consumption — land S3, where debugging is the stage's
first consumer.)*

## "Why did this view just render?"

Xray's render timeline, grouped by epoch:

```
epoch 912  ::message-arrived                     handler 0.2ms · 2 subs Δ · 3 commits
  message-list (:app)   ← sub Δ [::messages 42]   v18→v19
  unread-badge (:app)   ← sub Δ [::unread-count]  3→4
  compose-box  (:app)   ← local Δ                 —
```

Every commit carries its **causes** (a vector — one commit can have several): which
subscription changed (from→to), which **top-level prop slots** differed
(`{:kind :prop :changes [:product]}` — drill into the value diff on demand in the
inspector; nested paths aren't tracked by default), which local was set, or — honestly —
`:foreign-or-react` when a raw React boundary caused it. Restore an epoch and the
repaints carry an `:epoch-restore` cause naming the restore operation and its target
epoch — the timeline stays truthful through time travel without rewriting history.
"Something renders too much" stops being archaeology: sort by view, read causes.

The **heatmap overlay** (an Xray addition staged behind its integration review) tints
views by render count since your last interaction, with honest loss accounting when the
trace buffer dropped records. Cold UI should look cold; a hot corner is an
identity-compared fn prop, freshly-rebuilt children, or a too-broad subscription — and
its timeline rows say which.

## "What does this button do?"

Hover any element in Xray's inspector: its handler is *right there* —
`:on-click [:cart/add 42]` — plus the registered handler's own source and schema, because
handlers are data. Buttons wired to unregistered event ids warned at render already, with
the element's file:line. Sites written with `ui/event`/`ui/handler` show as
stable-but-opaque; bare fns as `ƒ opaque` — if you can't see what your UI does, that's the
nudge to push intent back into vectors.

## "What does this view depend on?"

The inspector's Dependencies panel: statically, every subscription the view *can* read
(the compiler knows without executing); live, what this *instance* is reading right now
(`[::item 42]`, node version, owned or last-committed). Reverse works too: from a
subscription, every view that can read it; from an app-db path, the full chain
path → subs → views → elements — and backwards from a clicked element.

## "Where is this defined?"

Click any DOM node in Xray → the `defview` and the exact template line. Source
coordinates are stamped at compile time on every compiler-owned element; there is nothing
to configure.

## Loud, early, didactic failures

Errors fire at the earliest moment the mistake exists, and every message names the fix:

- **Build time:** dynamic tag heads; `sub`/`lease`/vector-handlers inside loops; missing
  list keys; markup-returning `map`; literal keywords in child position; unknown literal
  DOM props; missing required props; hooks in branches; render-time dispatch.
- **First dev render:** props failing a view's schema (element coords attached); unknown
  event ids; a dynamic child that turns out to be a keyword.
- **When they run:** `set!` during render; dispatch during render; `dispatch-fn` after
  disconnect (your leaked listener, found).

The runtime ids are catalogued in the spec — Spec 004 owns the view-layer ids,
Spec 009 the runtime-wide ones — and greppable, no bespoke console prose:
`:rf.error/no-frame-context`, `:rf.error/dispatch-disconnected`,
`:rf.warning/unregistered-event-id`, `:rf.warning/placeholder-in-dynamic-vector`,
`:rf.warning/render-phase-dispatch` / `-set!`. One catalogue for the whole library;
each id ships with its feature's stage (the `dispatch-fn` and registrar-check ids
arrive with S3).

## The programmatic surface: `re-frame.ui.tool` *(lands S3 — dev-only)*

Everything Xray shows rides a public, dev-only namespace that your own tooling — or an
agent — can call directly: `view-manifest` (what a view *can* do: its subs, event
sites, leases, straight from the compiler, before any mount), `mounted-views` (what is
live right now), `explain-render` (the causes behind a commit), and
`view-dependencies` / `view-event-sites` (the two halves of the inspector's panels).
Tool tier: provably absent from production bundles, like the rest of this page.

It's REPL-shaped on purpose — ask a view what it can do before anything is mounted:

```clojure
;; guide:no-fixture — schematic; the ruled fields, lands S3 with the tool namespace
(require '[re-frame.ui.tool :as tool])
(tool/view-manifest my.app/counter)
;; => its subs, event sites, and leases — straight from the compiler
```

## Pairing with an AI on the live app

Everything above is machine-readable on purpose. The repo's Pair tooling
(`re-frame2-pair` and its MCP server) attaches to your running app and consumes the
same surfaces this page describes: ask *"why did that tile just re-render?"* and the
pair reads the causes; hand it a bug and it dispatches the events, reads the epoch
history, hot-swaps the fix, scrubs back, and replays. A session against
[10](10-worked-app.md)'s dashboard is the fastest way to *feel* why intent-as-data
matters — the AI is only that useful because your buttons say what they do. (The Pair
rides the core Tool-Pair contract, live today; the UI-substrate surfaces it reads —
causes, manifests — deepen with S3.)

## React DevTools & profiler

Views appear under real names (`shop.views/product-card`), props as readable CLJS data,
memo boundaries visible. Fast Refresh keys on view identity: edit, save, and the component
updates against live frame state (`frame-root`'s reuse doing its job). React Performance
Tracks correlate with Xray's epochs via shared render keys — host scheduling questions go
to React's tools, causal questions to Xray, and neither patches the other.

## In production

None of this exists — not flagged off; *absent*, verified by a bundle scan in CI. What
remains is re-frame2's always-on error contract: structured, bounded, redacted per
Spec 009.

---

**Next:** [07 — Performance](07-performance.md) — what the compiler already optimizes for you.
