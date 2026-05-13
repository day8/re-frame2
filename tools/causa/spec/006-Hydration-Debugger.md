# 006-Hydration-Debugger

SSR hydration mismatches are structurally hard to debug: the failing
client render and the failing server render disagree on a tree, the
console error is one line, and the divergent node is rarely the one
the error points to. No other JS devtool surfaces this; re-frame2's
Spec 011 emits structured data, and Causa renders it.

## Visibility

The Hydration panel is **dormant** until at least one
`:rf.ssr/hydration-mismatch` trace lands. Until then, the sidebar entry
shows `◌` (dormant marker) and clicking it surfaces an empty state
("This app does not appear to use SSR hydration").

On first `:rf.ssr/hydration-mismatch`:

- The Hydration sidebar entry's icon flashes red for 600ms.
- The Issues badge increments.
- The co-pilot, if open, surfaces a contextual suggestion: "I see a
  hydration mismatch. Open the Hydration panel?" (no auto-open; user
  is in control of their canvas).

## Substrate

Per Spec 011 §Hydration equivalence rule, the runtime emits:

```clojure
{:op-type   :error
 :operation :rf.ssr/hydration-mismatch
 :tags      {:path           <hiccup-path-to-divergent-node>
             :server-tree    <hiccup of server's subtree at this path>
             :client-tree    <hiccup of client's subtree at this path>
             :server-hash    <render-tree hash at this path on the server>
             :client-hash    <render-tree hash at this path on the client>
             :frame          <frame-id>
             :view-id        <the view registration that owns this subtree>}}
```

Plus the SSR payload itself: the runtime stamps the full server
render-tree onto the document at hydration time (per Spec 011); Causa
reads it from there. The first client render-tree is captured by
Causa's preload hook before any user dispatch lands.

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
on entry (600ms expand-fade ring) and is flagged with `⚠`. Hovering
the divergent node shows a tooltip with the render-tree hash at that
node (bisectable).

## Render-tree hash bisector

Per [Spec 011 §Hydration equivalence rule](../../../spec/011-SSR.md#hydration-equivalence-rule-canonical),
every parent node has a render-tree hash that summarises its subtree.
Hashes match at common ancestors and diverge at the first differing
descendant — this is the bisection property.

Causa renders hashes inline as faint chips on every parent:

```
▾ [:div.app]              #a12b
  ▾ [:header]             #c34d  (match)
  ▾ [:main]               #ef56  (diverges)
    ▾ [:section.cart]     #78ab  (diverges)
      · [:p "3 items"]    #cd ← diverges here
```

The divergence path is highlighted from root to the first differing
node. Click any hash chip → the panel re-roots at that node (zooms
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
hydration-mismatch trace events → per-mismatch detail record → per-pane
chip lists + bisector path → rendered hiccup. The pipeline is
JVM-runnable so it can be tested without a DOM (the helpers ns is
`.cljc`, exercised under `clojure -M:test`); the view is hiccup-only.
Nothing in the algorithm reaches into the substrate or mutates runtime
state.

### Input model

The projection consumes one upstream source plus three Causa-local
indices, indexed once per call:

- **`:rf.ssr/hydration-mismatch` trace events** — emitted by the
  runtime per
  [Spec 011 §Hydration-mismatch detection](../../../spec/011-SSR.md#hydration-mismatch-detection).
  Each event's `:tags` MUST carry `:path` (the hiccup-path the runtime
  flagged), `:server-tree` and `:client-tree` (the subtrees at that
  path), `:server-hash` and `:client-hash` (the FNV-1a render-tree
  hashes per Spec 011), `:frame` (the affected frame), `:view-id` (the
  divergent view's registration), and `:failing-id` (the body /
  head discriminator — `:rf/hydrate` for body, `:rf.ssr/head-mismatch`
  for head, treated uniformly by this projection). The optional
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
  hash chip click, or `nil` for the full subtree.

### Bisector walk

The walk is **parallel pre-order over `server-tree` and `client-tree`,
descending into matching slots until the first divergence**. Pre-order
— not leaf-first — because the runtime's structural-hash invariant
(per [Spec 011 §Hydration-mismatch detection](../../../spec/011-SSR.md#hydration-mismatch-detection))
guarantees that **hashes match at every common ancestor and diverge
at the first differing descendant**; pre-order walks that bisection
property top-down and stops at the shallowest divergence. The walk is
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
node — the path the side-by-side highlights and the hash chips
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
flagged + no chips below.

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

### Hash stand-in

Spec 011 §Hydration-mismatch detection commits the runtime to an
FNV-1a 32-bit hash over a canonical EDN serialisation; the
runtime stamps `:server-hash` and `:client-hash` on the trace event.
The Causa walker, however, runs against the **trees themselves** —
not the upstream hashes — because the panel re-walks subtrees
after the user zooms (the re-root affordance under
§Render-tree hash bisector), and the upstream hashes annotate only
the root of each subtree. The walker therefore uses a
**content-equality stand-in**: a canonical `pr-str` over each node,
treated as the structural fingerprint. Two structurally-equal nodes
produce the same string; two different nodes produce different
strings. This mirrors the bisection property of the FNV-1a hash
without depending on the SSR artefact's hash module.

The stand-in MUST behave like the runtime hash on the bisection
question — "are these two subtrees structurally equal?" — but it is
**not** the runtime hash. The view renders the runtime's hashes on
the hash chips (faint pills) when available; the stand-in's role is
internal to the bisector walk. Ports that integrate a host-native
hash module SHOULD swap the stand-in for the host hash provided the
canonical-EDN traversal in
[Spec 011 §Hydration-mismatch detection](../../../spec/011-SSR.md#hydration-mismatch-detection)
is preserved — the substitution is value-equality-preserving by the
spec's traversal contract.

### Runtime path override

When the runtime supplies `:first-diff-path` on the trace event's
`:tags`, the projection **prefers it over its own walk**. The
upstream computation has the canonical FNV-1a hashes per parent and
can compute the bisection path during emission; recomputing in Causa
would be redundant. The local walk is the fallback when the runtime
omits the slot (older SSR builds, ports that have not yet shipped
upstream bisection, or hand-constructed test events).

This is a MUST: the runtime is the source of truth (per
[`Principles.md`](./Principles.md) §Observation only — no new runtime
surfaces). Causa's walker exists so the panel works against the
runtime's contract — not so it overrides the runtime when it speaks.

### Cycle handling

Hiccup is a **tree by construction**: a hiccup vector's children are
fresh values produced by the view's render fn; cycles cannot form in
well-formed render output. The walker is nonetheless defensive — the
recursion descends at most `min(server-depth, client-depth)` levels,
and the `nodes-equal?` short-circuit at the top of each recursive
call catches the loop case (a malformed input whose subtree compares
equal to itself terminates the walk at that level). Causa MUST NOT
project a separate `:rf.causa/error` for malformed hiccup — the
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

### Chip-list derivation

For each side (server / client), the projection walks the tree from
the root of the runtime-flagged subtree down the bisector path,
emitting one `{:path [...] :hash <string>}` entry per parent. The
root carries the empty path; the chip at the bisector path's final
slot is the **divergent chip** the view paints red (per §Render-tree
hash bisector). The walk runs in lock-step on both sides; chip lists
on the two sides have the same length and the same `:path` slots,
differing only in their `:hash` values where the trees diverge.

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
  rendered tree's depth, which is in turn bounded by Causa's
  per-panel layer cap (per
  [`007-UX-IA.md`](./007-UX-IA.md) §Performance budget — virtualised
  lists never DOM-mount more than ~200 rows; the hydration debugger
  inherits this).
- **Chip-list derivation** is O(bisector-depth) per side — typically
  small (single-digit) for real apps.
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
`{:id :path :server-tree :client-tree :server-hash :client-hash
:frame :view-id :failing-id :divergence-kind :hypothesis
:bisector-path :server-chips :client-chips}`. The view consumes those
data and emits hiccup; visual encoding (tree pane, hash chip,
divergent marker, hypothesis row, source-coord row) is computed
per-record off the slots per §Layout, §Render-tree hash bisector, and
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
handler (per [Spec 006 §Source-coord annotation](../../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1)).

When the view registration itself has no obvious cause, the panel
falls back to the dispatch (or `:rf/hydrate` cofx) that produced the
client-side state — using the same handler-coord fallback as the rest
of Causa (lock #11 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md));
surfaced with `(?)` annotation.

## Cause hypothesis

Below the side-by-side, Causa surfaces a one-line hypothesis based on
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
surface elides in production; the `:rf.ssr/hydration-mismatch` event
is never emitted in a production build. Causa's panel renders an
empty state in prod-like builds.

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
