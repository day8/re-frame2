# 09 — Debugging

Dev builds answer causal questions directly, in the tools you already run (Xray,
Story, the Pair, React DevTools). Everything on this page is dev-only and provably
absent from production builds.

## "Why did this view just render?"

Xray's existing Views rows gain a cause vector for each committed render *(lands S3)*.
A row names the view and frame, then shows cause chips for the subscription, prop,
local, or foreign boundary that moved.

Every commit carries its **causes** (a vector — one commit can have several): which
subscription changed (from→to), which top-level prop slots differed, which local was
set, or — honestly — `:foreign-or-react` when a raw React boundary caused it. Restore
an epoch and the repaints carry an `:epoch-restore` cause on those same rows.

"Something renders too much" stops being archaeology: inspect the view's existing row
and read its cause vector. S3 adds no render-timeline lane.

A heatmap is not an S3 surface. It remains conditional on a post-S3
information-architecture review; the ruled S3 delivery is cause vectors and cause
chips on the existing Views surface.

*(Causes, manifests, interaction surface, DOM source coordinates, and Xray
consumption of them land S3. Compile-time failures with file:line are Stage 1 —
shipped.)*

## "What does this button do?"

Hover any element in Xray's inspector: its handler is right there —
`:on-click [:cart/add 42]` — plus the registered handler's source and schema, because
handlers are data. Buttons wired to unregistered event ids already warned at render
with the element's file:line. Sites written with `ui/event` / `ui/handler` show as
stable-but-opaque; bare fns as `ƒ opaque` — if you cannot see what your UI does,
that is the nudge to push intent back into vectors.

## "What does this view depend on?"

The inspector's Dependencies panel:

- **Static** — every subscription the view *can* read (the compiler knows without
  executing).
- **Live** — what this *instance* is reading right now.

Reverse works too: from a subscription, every view that can read it; from an app-db
path, the full chain path → subs → views → elements — and backwards from a clicked
element.

## "Where is this defined?"

Click any DOM node in Xray → the `defview` and the exact template line. Source
coordinates are stamped at compile time on every compiler-owned element; there is
nothing to configure.

## Loud, early, didactic failures

Errors fire at the earliest moment the mistake exists, and every message names the
fix:

| When | Examples |
|---|---|
| Build time | dynamic tag heads; `sub`/`lease`/vector-handlers inside loops; missing list keys; markup-returning `map`; unknown literal DOM props — full catalogue in [14](14-compile-time-limits.md) |
| First dev render | props failing a view's schema; unknown event ids; dynamic child that is a keyword |
| When they run | `set!` during render; dispatch during render; `dispatch-fn` after disconnect |

Runtime ids are greppable (no bespoke console prose):
`:rf.error/no-frame-context`, `:rf.error/dispatch-disconnected`,
`:rf.warning/unregistered-event-id`, `:rf.warning/placeholder-in-dynamic-vector`,
`:rf.warning/render-phase-dispatch`.

## Programmatic surface: `re-frame.ui.tool` *(lands S3 — dev-only)*

Everything Xray shows rides a public, dev-only namespace your tooling — or an agent —
can call: `view-manifest`, `mounted-views`, `explain-render`, `view-dependencies`,
`view-event-sites`. Provably absent from production bundles.

```clojure
;; guide:no-fixture — schematic; the ruled fields, lands S3 with the tool namespace
(require '[re-frame.ui.tool :as tool])
(tool/view-manifest my.app/counter)
;; => its subs, event sites, and leases — straight from the compiler
```

## Pairing with an AI on the live app

The Pair tooling (`re-frame2-pair` and its MCP server) attaches to your running app
and consumes the same surfaces this page describes:

- "Why did that tile just re-render?" → the pair reads the causes.
- Hand it a bug → it dispatches events, reads epoch history, hot-swaps the fix,
  scrubs back, and replays.

A session against [06](06-worked-app.md)'s dashboard is the fastest way to *feel*
why intent-as-data matters — the AI is only that useful because your buttons say what
they do.

## React DevTools and profiler

Views appear under real names (`shop.views/product-card`), props as readable CLJS
data, memo boundaries visible. Fast Refresh keys on view identity: edit, save, and
the component updates against live frame state. Host scheduling questions go to
React's tools; causal questions go to Xray — neither patches the other.

## In production

None of this exists — not flagged off; *absent*, verified by a bundle scan in CI.
What remains is re-frame2's always-on error contract: structured, bounded, redacted.
