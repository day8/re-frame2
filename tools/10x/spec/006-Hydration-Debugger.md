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
