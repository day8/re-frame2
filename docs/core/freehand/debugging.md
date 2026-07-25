# Debugging and performance

A view looks stale, something feels slow, or you cannot tell which control owns a
draft. Freehand answers those questions **as data** — and you climb a performance
ladder **before** you compile.

> **High `:stable-renders` means fix subs (or props), not compile.**

!!! note "Design-target APIs"

    Names such as `v/inspect-boundary` and `v/hot-views` match the design. Shapes
    may refine as implementation lands; the **questions** are the contract.

## Questions and where to look

| Question | Where to look |
|---|---|
| Why didn’t this view re-render? | Boundary inspector: deps, last cause, props `rf=` |
| Why re-render “for nothing”? | High `:stable-renders` / parent churn → fix subs or props |
| What will this button dispatch? | Structural tree / Xray; handlers carry site identity |
| What dispatched this event? | Trace tags (view source + attr) when present |
| Where is this field’s draft? | Controller join — address, not mount path |
| Orphan controller records? | `v/orphans` |
| Live host behaviors? | `v/behaviors` |
| Should I compile? | Only after the ladder below |

Evidence records state how complete they are: **scope**, **basis**,
**completeness**, and **loss**. “Proven / observed / opaque” are labels on that
grid — not separate product modes.

## Performance ladder (before compilation)

Work in this order:

1. **Inspect the cause** — which query or parent churn drove the render?  
2. **Narrow the subscription** — classic bug: every cell reads a global
   “editing id”; fix with per-id predicates so one click dirties two cells, not
   two thousand.  
3. **Choose the boundary** — keyed rows for independent change; helpers for pure
   structure you are happy to re-run with the parent.  
4. **Window large lists** — do not mount what the user cannot see.  
5. **Measure again** with `v/hot-views` (below).  
6. **Only then** compile a remaining hot boundary, or a library leaf that needs
   static proof.  

If a host library or React reconciliation dominates the budget, another Hiccup
compiler will not save you — fix the host boundary or the data size.

## `v/hot-views` — anti-folklore measurement

Dev-only counters on work the interpreter (and compiled cells) already do:

```clojure
(v/hot-views frame)
;; ⇒ [{:view app/person-list
;;     :renders 214
;;     :self-ms-p95 6.2
;;     :nodes 12040
;;     :rows-max 1200
;;     :top-causes [[[:crud/filtered-people] 180]
;;                  [[:rf.view/parent] 30] …]
;;     :stable-renders 0.31
;;     :interp-slots 0}]
```

How to read it:

| Signal | Meaning |
|---|---|
| High `self-ms × renders` and high `nodes` | Interpretation cost may matter — candidate for compile **after** structural fixes |
| High `:stable-renders` | Output often equal — **narrow subs** or stop rebuilding props |
| `:top-causes` with `[:rf.view/parent …]` | Parent re-rendered without a moved sub — unstable props (inline maps, per-render fns) |
| `:interp-slots` | Count of `v/markup` (or similar) interpreted holes under a compiled path |

Promote on interpretation work, not on a percentage of “hot.” After promotion, the
**same** counters should still flow so you can see whether the win was real
(evidence continuity).

## `v/inspect-boundary` — “why is this view wrong?”

The first move in most debug sessions is: show me what this boundary actually
depends on.

```clojure
(v/inspect-boundary occurrence)
;; ⇒ {:occurrence …
;;    :view app/todo-row
;;    :committed [{:query [:todo/by-id 7] :value … :owned? true} …]
;;    :last-render {:epoch 812 :cause [:todo/by-id 7]}
;;    :props {:current … :rf=-prev? true}
;;    :door-sites [{:attr :on-input :door? true :reason :literal-controlled}]
;;    :controller {:kind :fh/buffered-field :address [:invoice 42 :amount]}}
```

Typical uses:

- **Stale view** — compare committed queries to the query you thought you mutated.
  Mismatch is often the bug (wrong id, wrong frame, conditional branch not taken).  
- **Props churn** — `:rf=-prev? false` with no meaningful data change → parent is
  rebuilding maps or functions.  
- **Controlled door** — which sites are synchronous and why.  
- **Controller join** — which semantic address is joined to this occurrence (tool
  plane). Storage is still under the library’s app-db path keyed by that address.  

How you obtain `occurrence` is tooling-dependent (Xray click, mounted-views table,
error summary). The design requires the read; the UI that picks the occurrence may
be Xray or a REPL helper.

## Companions: orphans and behaviors

```clojure
(v/orphans frame {:epochs n})
;; controller records with no mounted occurrence join for n epochs

(v/behaviors frame)
;; active behavior connections per occurrence
```

Orphans matter because controller state is **not** cleared on unmount. After a form
route leave, orphans should go to zero if your owner clear is correct. Behaviors
answer “is this Vega/Mapbox connection still live, and under which occurrence?”

## Traces: “what dispatched this?”

UI handlers stamp development tags (view site + attr such as `:on-click`) so a
dispatch ties back to the tree:

- forward: tree → intent (structural test / Xray)  
- backward: trace → site  

Production keeps evidence bounded; completeness fields say what was lost.

## Evidence surface roster (design target)

Tools **read** one host-neutral schema — they are not a second Freehand. Names may
polish; jobs stay stable:

| Projection (illustrative) | Answers |
|---|---|
| `v/inspect-boundary` | one occurrence: deps, cause, props, door, controller |
| `v/hot-views` | expensive / high-churn views |
| `v/orphans` | controller records without a mounted join |
| `v/behaviors` | live host connections |
| manifests / mounted-views / explain-render | static sites, live occurrences, causes |

Every projection should state **scope**, **basis**, **completeness**, and **loss**.
Detailed evidence is compiled out of production.

## Warnings vs errors

| Kind | When | Author experience |
|---|---|---|
| **Hard error** | illegal tree, bad compiled form, render-phase `sub`, invalid event outcome, … | fails loudly with recovery |
| **Default-on warning** | contract misfire that would surface far away | once per source site + kind |
| **Opt-in quality lint** | predictive / style / “maybe promote” | `v/check` categories you enable |

Freehand does not nag you into compiling. Fix a site once and the warning should
stay quiet until the source changes.

## Demote-to-debug (compiled views)

1. Remove `{:compiled true}`.  
2. Reproduce with ordinary Clojure stacks.  
3. Fix; run `v/check`.  
4. Restore `{:compiled true}` if still needed.  

Call sites, structural tests, and identity stay the same. Demotion **is** the
debug mode — not a second product.

## Xray and related tools

Xray (Story, pair MCP) read the same evidence vocabulary. Use epoch history for
“what ran,” filters when typing is noisy, and controller badges when join data is
available. Per-keystroke events are not hidden by default — filter them.
