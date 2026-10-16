# 006-Hydration-Debugger

> **The live surface.** There is no Hydration panel or tab. A hydrating
> root reports each divergence React recovers from — text content, or a
> missing / extra / wrong-type element — as `:rf.ssr/hydration-mismatch`
> ([Spec 011 §Hydration-mismatch detection](../../../spec/011-SSR.md#hydration-mismatch-detection)).
> The trace fires from React's `onRecoverableError`, outside any event, so
> Xray lists it in the `:ungrouped` row of the L2 event timeline and in the
> always-on issues ribbon, and the Trace tab shows its raw trace: React's
> message (`:error`), the hydrating door (`:where`) and
> `:recovery :warned-and-replaced`. The `runHydration` feature-matrix
> scenario (`tools/xray/testbeds/feature_matrix/scenarios.cjs`) pins that
> surface against a testbed whose server markup diverges in text. An
> attribute-only divergence draws React's development `console.error` and
> no trace.
>
> **Treat the body below as a design record, not normative spec.** It
> describes a mismatch-bisector panel that does not ship, over a substrate
> — the server and client subtrees at the divergent node — that neither
> React's report nor the runtime supplies. The panel's former subs and
> events are recorded at
> [`014-Registry-Catalogue.md` §Hydration debugger](./014-Registry-Catalogue.md#hydration-debugger),
> and the panel list at
> [`API.md` §Panel reg-views](./API.md#panel-reg-views).

## Bug class

**Body / head hydration mismatch.** The server-rendered tree and the
client first-render tree disagree at a node. The
`:rf.ssr/hydration-mismatch` trace fires with `:first-diff-path`. The
console error is one line; the divergent node is rarely the one the
error points to. SSR debugging is **uniformly opaque** in every framework
— React's "hydration error" message tells you a node mismatched but
gives you nothing about WHY the upstream state diverged.

## Example bug

You run the page; SSR returned `<span class="user-name">Guest</span>`;
the client first-render produced `<span class="user-name">Alice</span>`.
The browser console says "hydration mismatch at `[:div.app :header
:span.user-name]`." You don't know why — was the server missing the
session? Was the sub computing `nil`? Was the `:user/display-name` sub
watching the wrong slice?

## Insight Xray provides

The **hydration mismatch bisector** — a depth-first walk over the
canonical EDN serialisation that finds the first divergent node,
surfaces both trees side-by-side, names the **sub that produced the
divergent value on each side**, names the **upstream `app-db` slice**
each side observed, and emits a **heuristic "likely cause" hypothesis**
that links to the source.

This is the **hero SSR feature**. Xray is the only re-frame2-shaped tool
that can do this — the framework already emits the structured data; this
spec turns it into the picture.

## Affordance

Issues tab — hydration mismatch bisector with sub-attribution. Per
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.3 the
canonical bug-class catalogue. The bisector replaces a generic Issues
row with a dedicated drill-into surface:

```
┌─ Hydration mismatch  ·  cascade #1  :rf/hydrate ───────────────────────┐
│ Failing-id:    :rf/hydrate (body mismatch)                              │
│ React:         Hydration failed because the server rendered text …      │
│                                                                          │
│ Bisector (depth-first over canonical EDN):                              │
│   ✓ root <div id="app">                                                  │
│   ✓ <div.layout>                                                         │
│   ✓ <header>                                                             │
│   ✗ FIRST DIVERGENCE: <span.user-name>                                   │
│                                                                          │
│ Side-by-side:                                                            │
│ ┌─ Server ─────────────────────┬─ Client ──────────────────────┐        │
│ │  [:span.user-name "Guest"]    │  [:span.user-name "Alice"]    │        │
│ │  Subbed to: :user/display-name│  Subbed to: :user/display-name│        │
│ │  Server sub returned: "Guest" │  Client sub returned: "Alice" │        │
│ │  Server :user value: nil      │  Client :user value: {:id 1 …}│        │
│ │  Server :auth/session: nil    │  Client :auth state: :authed  │        │
│ └────────────────────────────────┴────────────────────────────────┘     │
│                                                                          │
│ Likely cause: server-side session cofx not injected (request cookie     │
│ was present but `:auth/server-init` event was not in `:initial-events`) │
│ ↗ Open server-side init: src/server/init.cljs:42                        │
└─────────────────────────────────────────────────────────────────────────┘
```

The "Likely cause" line is heuristic — Xray suggests "session not
injected" when the divergent sub depends on `:auth/*` and the server's
`:auth/*` slice is empty. It is **a hint, not an answer**; it links to a
panel pivot that lets the user verify.

---

SSR hydration mismatches are structurally hard to debug: the failing
client render and the failing server render disagree on a tree, the
console error is one line, and the divergent node is rarely the one
the error points to. No other JS devtool surfaces this; re-frame2's
Spec 011 emits structured data, and Xray renders it.

## Visibility

The Hydration panel is **dormant** until at least one
`:rf.ssr/hydration-mismatch` trace lands. Until then, the sidebar entry
shows `◌` (dormant marker) and clicking it surfaces an empty state
("This app does not appear to use SSR hydration").

On first `:rf.ssr/hydration-mismatch`:

- The Hydration sidebar entry's icon flashes red for 600ms.
- The Issues badge increments.

## Substrate

Per Spec 011 §Hydration-mismatch detection, the runtime emits:

```clojure
{:op-type   :warning
 :operation :rf.ssr/hydration-mismatch
 :tags      {:error <React's recoverable-error message>
             :where <the hydrating door>}
 :recovery  :warned-and-replaced}
```

The bisector this record designs needs more than that:

```clojure
{:path        <hiccup-path-to-divergent-node>
 :server-tree <hiccup of server's subtree at this path>
 :client-tree <hiccup of client's subtree at this path>
 :frame       <frame-id>
 :view-id     <the view registration that owns this subtree>}
```

Neither React's report nor the runtime supplies the two subtrees, so
the design is unbuilt.

## Layout

Side-by-side render-tree view:

```
┌─ server render ────────────────╮  ┌─ client render ────────────────╮
│ ▾ [:div.app]                   │  │ ▾ [:div.app]                   │
│   ▾ [:header]                  │  │   ▾ [:header]                  │
│     · [:h1 "Welcome, Alice"]   │  │     · [:h1 "Welcome, Alice"]   │
│   ▾ [:main]                    │  │   ▾ [:main]                    │
│     ▾ [:section.cart]          │  │     ▾ [:section.cart]          │
│       · [:p "3 items"]         │  │       · [:p "0 items"]    ← ⚠ │
│       · [:button "Checkout"]   │  │       · [:button "Checkout"]   │
╰────────────────────────────────╯  ╰────────────────────────────────╯
```

The divergent node (`[:p "3 items"]` vs `[:p "0 items"]`) pulses red
on entry (600ms expand-fade ring) and is flagged with `⚠`.

## Divergence path

A subtree containing a divergence is itself unequal, so equal subtrees
sit off the divergence path and every node on it differs down to the
first differing descendant — this is the bisection property.

Xray marks each parent on the path:

```
▾ [:div.app]              (diverges)
  ▾ [:header]             (match)
  ▾ [:main]               (diverges)
    ▾ [:section.cart]     (diverges)
      · [:p "3 items"]    ← diverges here
```

The divergence path is highlighted from root to the first differing
node. Click any marked parent → the panel re-roots at that node (zooms
into the subtree).

## Multi-mismatch case

Multiple mismatches in a single hydration produce a list at the panel
top:

```
3 mismatches in this hydration:
▸ [:div.app :main :section.cart :p]          server "3 items" vs client "0 items"
▸ [:div.app :main :section.cart :button]    server "Checkout" vs client "Sign in to checkout"
▸ [:div.app :footer :p]                      server "© 2026" vs client "© 2025"
```

Click an entry → side-by-side rebases to that path. Arrow keys walk
the list.

## Algorithm

The Hydration Debugger's projection is a pure data → data pipeline:
hydration-mismatch trace events → per-mismatch detail record →
bisector path → rendered hiccup. The pipeline is
JVM-runnable so it can be tested without a DOM (the helpers ns is
`.cljc`, exercised under `clojure -M:test`); the view is hiccup-only.
Nothing in the algorithm reaches into the substrate or mutates runtime
state.

### Input model

The projection consumes one upstream source plus three Xray-local
indices, indexed once per call:

- **`:rf.ssr/hydration-mismatch` trace events** — emitted by the
  runtime per
  [Spec 011 §Hydration-mismatch detection](../../../spec/011-SSR.md#hydration-mismatch-detection).
  Each event's `:tags` MUST carry `:path` (the hiccup-path the runtime
  flagged), `:server-tree` and `:client-tree` (the subtrees at that
  path), `:frame` (the affected frame), `:view-id` (the divergent
  view's registration), and `:failing-id`. The optional
  `:first-diff-path` slot carries the runtime's own bisection path
  when the SSR pipeline computed one upstream; the projection
  prefers it over its own walk (see §Bisector walk below).
- **`target-frame`** — the active frame the user has selected in the
  picker. The projection filters mismatches to that frame per
  §Frame awareness; passing `nil` returns mismatches for all frames.
- **`selected-mismatch-id`** — the trace event `:id` the user clicked
  in the mismatch list, or `nil` (the composite picks the latest as
  default).
- **`re-root-path`** — the hiccup-path the user has zoomed into via a
  marked-parent click, or `nil` for the full subtree.

### Bisector walk

The walk is **parallel pre-order over `server-tree` and `client-tree`,
descending into matching slots until the first divergence**. Pre-order
— not leaf-first — because **every ancestor of the first differing
descendant is itself unequal, and every subtree off that path is
equal** (§Divergence path); pre-order walks that bisection property
top-down and stops at the shallowest divergence. The walk is
parallel — not true binary bisection over a balanced tree — because
hiccup trees are rarely balanced (a long `<div>` chain wrapping one
divergent leaf is the canonical shape) and the per-level child
comparison is O(min(server-fanout, client-fanout)) per step.

The host-agnostic contract:

```
Walk server-tree S, client-tree C:
  if nodes-equal?(S, C):
    return nil                              ;; trees are identical
  if NOT (hiccup-vector?(S) AND hiccup-vector?(C)
          AND tag-of(S) = tag-of(C)):
    return []                               ;; divergence at root
  skids ← children-of S; ckids ← children-of C
  n     ← min(count skids, count ckids)
  for i in 0..n-1:
    if NOT nodes-equal?(skids[i], ckids[i]):
      sub ← Walk(skids[i], ckids[i])
      return (sub = nil ? [i] : [i] ++ sub)
  return [n]                                ;; child-count mismatch at slot n
```

The walker returns a **vector of integer child-indices** from the
root of the subtree the runtime flagged down to the first divergent
node — the path the side-by-side highlights and the parent markers
annotate.

The **child-count-mismatch rule** is MUST: when all `n` matching
children matched, the longer side's slot `n` is the divergence. A
walker that returned `nil` (or the empty path) would mis-attribute
the divergence to the parent and the side-by-side would highlight no
node.

The **tag-mismatch root case** is MUST: when the two roots differ in
tag (or one is a hiccup vector and the other a text leaf), the
walker returns `[]` — the empty path means "the divergence is at
the root of the runtime-flagged subtree." The view renders the root
flagged and no marked parent below it.

### Tree representation

Both trees are **hiccup**: `[tag (attrs?) & children]` where `tag` is
a keyword, `attrs` is an optional map at slot 1, and `children` are
hiccup vectors or text leaves (strings / numbers / nil). The walker's
helpers (`hiccup-vector?`, `tag-of`, `attrs-of`, `children-of`,
`text-node?`) implement the structural predicates; `children-of`
skips the attribute map when present so the integer child-indices in
the returned path are stable regardless of whether a node carries
attributes. Other-language ports MUST preserve the
attrs-skipped-from-child-index convention so paths produced by their
walker line up with paths produced by ports running against the same
trees.

### Node equality

The walker runs against the **trees themselves**, because the panel
re-walks subtrees after the user zooms (the re-root affordance under
§Divergence path). It compares nodes by **content equality**: a
canonical `pr-str` over each node, treated as the structural
fingerprint. Two structurally-equal nodes produce the same string; two
different nodes produce different strings.

### Runtime path override

When a trace event's `:tags` carry `:first-diff-path`, the projection
**prefers it over its own walk**. The local walk is the fallback when
the slot is absent — as it is from React's adoption report, which names
no path.

This is a MUST: the runtime is the source of truth (per
[`Principles.md`](./Principles.md) §Observation only — no new runtime
surfaces). Xray's walker exists so the panel works against the
runtime's contract — not so it overrides the runtime when it speaks.

### Cycle handling

Hiccup is a **tree by construction**: a hiccup vector's children are
fresh values produced by the view's render fn; cycles cannot form in
well-formed render output. The walker is nonetheless defensive — the
recursion descends at most `min(server-depth, client-depth)` levels,
and the `nodes-equal?` short-circuit at the top of each recursive
call catches the loop case (a malformed input whose subtree compares
equal to itself terminates the walk at that level). Xray MUST NOT
project a separate `:rf.xray/error` for malformed hiccup — the
runtime is the source of truth; surfacing one would contradict the
read-only-by-default contract (per
[`Principles.md`](./Principles.md) §Observation only — no new runtime
surfaces).

### Divergence-kind classification

A second pass over the divergent subtree's root classifies the
divergence into one of five kinds, used by §Cause hypothesis below to
pick the one-line hint. The classifier is pure data → keyword:

| Kind | Condition |
|---|---|
| `:different-text` | Both nodes are text leaves (string / number / nil), or both are hiccup vectors with the same tag, equal attrs, and a single text child whose value differs. |
| `:tag-differs` | Both nodes are hiccup vectors with different tags, OR one is a hiccup vector and the other a text leaf. |
| `:attr-differs` | Both nodes are hiccup vectors with the same tag but different attribute maps. |
| `:children-missing-client` | Both nodes are hiccup vectors with the same tag and equal attrs; the server has children, the client has none. |
| `:children-missing-server` | Symmetric: the client has children, the server has none. |
| `:unknown` | None of the above — a shape-difference the classifier does not recognise. Safe-default for the view. |

The decision order is the order in the table — earlier checks
dominate later ones. The classifier MUST be evaluated at the
**divergent subtree's root**, not at the runtime-flagged path's root,
so the hypothesis-text aligns with the node the user sees flagged.

### Source-coord resolution

Per §Source-coord drilldown, the panel surfaces the divergent view's
registration coord. The projection resolves it in priority order:

1. The `:view-id`'s **registration coord** when the runtime stamped a
   `:source-coord` map on the trace event's `:tags` — the exact case.
2. The dispatch / `:rf/hydrate` cofx's **handler-coord** when the
   view-coord is absent — the fallback case, surfaced with the
   `(?)` annotation per lock #11 in
   [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md).
3. `nil` when neither is available — the panel renders no coord row.

The fallback path is MUST: a mismatch with no view-coord MUST NOT
fall back silently to nothing — the `(?)` annotation signals to the
user that the coord shown is the handler that produced the divergent
state, not the view that rendered it. The distinction matters for
attribution: clicking through opens the handler in the editor, which
may be several files removed from the divergent view.

### Performance bound

- **Bisector walk** is O(depth × min-fanout) over the divergent
  subtree; the runtime-flagged subtree's depth is bounded by the
  rendered tree's depth, which is in turn bounded by Xray's
  per-panel layer cap (per
  [`007-UX-IA.md`](./007-UX-IA.md) §Performance budget — virtualised
  lists never DOM-mount more than ~200 rows; the hydration debugger
  inherits this).
- **Divergence-kind classification** is O(1) — one decision over the
  divergent subtree's root.
- **Re-walk on re-root** runs the bisector walk again against the
  re-rooted subtree; cost is the same shape as the initial walk and
  bounded by the same depth cap.

The panel's render budget MUST NOT change observable INP on a
typical app (per
[`007-UX-IA.md`](./007-UX-IA.md) §Performance budget). The projection
caches per `(mismatch-id, re-root-path)`; a second render of the same
detail for the same selection is O(1).

### Rendering contract

The projection hands the panel view a plain map:
`{:has-mismatch? :mismatch-summary :selected-mismatch-id :detail
:source-coord :re-root-path :target-frame}` (per
[`014-Registry-Catalogue.md` §Hydration debugger](./014-Registry-Catalogue.md#hydration-debugger)).
The `:detail` slot carries the per-mismatch projection record:
`{:id :path :server-tree :client-tree :frame :view-id :failing-id
:divergence-kind :hypothesis :bisector-path}`. The view consumes those
data and emits hiccup; visual encoding (tree pane, parent marker,
divergent marker, hypothesis row, source-coord row) is computed
per-record off the slots per §Layout, §Divergence path, and
§Cause hypothesis. The pipeline MUST NOT emit SVG, React, or
substrate-specific output: keeping projection pure data preserves
the JVM-test surface and lets every surface that names a hydration
mismatch (the panel, the Issues ribbon's hydration-mismatch entries,
the MCP server's programmatic export — per
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)) consume
the same projection without re-implementing it.

## Source-coord drilldown

Each mismatch surfaces source coords for the divergent view's
registration:

```
Divergent view: cart-summary-view
  Registered at:  src/cart/views.cljs:42
  [Open source]
```

Click `Open source` → editor opens at line 42 via the source-coord URL
handler (per [Spec 006 §Source-coord annotation](../../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory)).

When the view registration itself has no obvious cause, the panel
falls back to the dispatch (or `:rf/hydrate` cofx) that produced the
client-side state — using the same handler-coord fallback as the rest
of Xray (lock #11 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md));
surfaced with `(?)` annotation.

## Cause hypothesis

Below the side-by-side, Xray surfaces a one-line hypothesis based on
the divergence kind:

| Pattern | Hypothesis |
|---|---|
| Different text content under the same tag | "App-db state differs between server and client. Check the slice that feeds this view." |
| Tag differs | "View structure differs. Check conditional rendering — the server may have rendered a different branch." |
| Attribute differs | "Attribute computed from differing inputs. Check the sub feeding the attribute." |
| Children missing on client | "View's children render conditionally; the condition differs. Check the gating sub." |
| Children missing on server | "View bails out on server (e.g., uses `js/window`). Move the dependency into a client-only effect." |

The hypothesis is **a hint, not an answer**. It links to a panel pivot
that lets the user verify (the relevant sub graph, the relevant
app-db slice, the relevant cofx trace).

## Frame awareness

Per Spec 002, hydration is per-frame. The Hydration panel shows
mismatches for the active frame; switching the frame picker
re-filters. Cross-frame SSR (a server-rendered frame nested inside a
client-rendered shell) renders both swimlanes; the panel's title bar
indicates "showing mismatches for `<frame-id>`."

## Production posture

Hydration panel surfaces are dev-only. Per Spec 009, the entire trace
surface elides in production; the `:rf.ssr/hydration-mismatch` trace
compiles out of a production build, where the always-on error record
is the report. Xray's panel renders an empty state in prod-like
builds.

In dev, the panel's first-paint cost is paid only when a mismatch
fires — the panel's render code is lazy-loaded on first activity
(per [`007-UX-IA.md`](./007-UX-IA.md) §Bundle splitting).

## Empty states

**No SSR in this app:**

```
   This app does not appear to use SSR hydration.
   The Hydration panel surfaces when :rf.ssr/hydration-mismatch
   events fire — see Spec 011.
```

**SSR present, no mismatches:**

```
   Hydration ran cleanly.
   No mismatches detected on the last hydration.
```

(Second message is again deliberately a positive result — a clean
hydration is the goal, not the absence of a result to display.)

## Vision — beyond v1's bisector

The bisector is the v1 hero. The next-step features push Xray from
"shows you the mismatch" to "tells you the runtime story behind every
SSR contract":

### Sub-attribution at hydration time

The bisector knows which subs ran on the server and which ran on the
client. It can show, per divergent node, the **input chain** for the sub
on each side — which slice the sub watched, which value it returned, and
why the cache state differed.

Phase 3 per [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md)
§6. Needs sub-recompute attribution at hydration time (a new projection
over the trace bus).

### Schema digest mismatch — per-schema diff

When `:rf.ssr/schema-digest-mismatch` fires, the author wants to see
**which schemas diverged** (server's registered set vs client's). Xray
surfaces a per-schema diff: schemas only on server (greyed); schemas
only on client (highlighted); shared schemas whose digests don't match
(side-by-side digest comparison + the registration source-coord on each
side).

### Server-side error projection trace

When a server-side handler errored, the public-error projection
sanitises the internal trace. In dev mode, `:dev-error-detail? true`
includes `:details`. Xray surfaces the projector's work:

```
┌─ Server error projection  ·  cascade #1 :initial-events ───────────────┐
│ Internal trace:                                                          │
│   :rf.error/handler-exception                                            │
│   :handler-id [:dispatch :user/load-profile]                             │
│   :exception ArgumentError: user-id missing                              │
│   :stack    at user.events.load-profile (src/user/events.cljs:42)       │
│ Projector: :myapp/public-error  src/server/projector.cljs:18 ↗          │
│ Public projection:                                                       │
│   { :status 500, :code :internal-error, :message "Something went wrong",│
│     :retryable? false, :details <absent in prod; …> }                   │
│ Rendered to client:  HTTP 500 + error-page view rendered with public    │
│                      projection. No internal detail crossed the wire.    │
└─────────────────────────────────────────────────────────────────────────┘
```

The security boundary is invisible to authors today; this surface makes
it legible. "What did the client actually see?" gets a direct answer.

### Payload-policy verdict

When the hydration payload landed, surface the policy used + which keys
shipped + which keys were dropped. Catches the
`:payload-policy :rf.ssr.payload/whole-app-db` security mis-configuration
loudly.

### Head model inspector

When the focused cascade involved a `reg-head` resolution, surface
inputs (db slice + route) → head model output → rendered HTML head, in
three columns.

### Per-request frame teardown auditor (dev-only summary)

At per-request frame destroy time, Xray verifies each slot was released.
Surfaces a one-line "frame destroyed cleanly" + slot tally, or a leak
warning if any slot persists. Summary only; deep audit lives in CI.

### Side-by-side SSR replay (post-v1 dream)

Xray offers a "replay SSR" affordance: given the live runtime, spin up
a JVM SSR pipeline (if dev environment has it wired), render the page
server-side from scratch, show the rendered HTML side-by-side with what
the live browser is showing. Diff the two. Author sees exactly what's
different. Way too ambitious for v1; documented as the eventual "SSR
development is interactive" North Star.

### Placement question — Mike's iteration surface

The bisector deserves more than an Issues row. Three options (see
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §7):

- **(a)** Conditional 7th tab — visible only when SSR is detected for
  the session.
- **(b)** Inline panel inside the Issues tab (the current v1 plan).
- **(c)** `h`-keyboard popover (consistent with Nav-token `r`).

**Lean: (c).** Keeps the chrome at 7 tabs; joins the popover pattern.
But "always visible when relevant" is a strong (a) argument for SSR-heavy
apps.
