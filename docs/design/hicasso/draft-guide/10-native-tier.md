# The native tier

Most apps never need this page. Ordinary Hicasso — hiccup, `h/sub` where you
read, events as data — is the product. This page is for the thin slice that
is not: market-tick rows, display-rate drag, vendor widgets that are hooks
all the way down. You write React directly; frame, app-db, and root stay the
same.

`[...]` is always interpreted Hiccup. `n/$` is always native React. Neither
form changes the other's meaning, and nothing compiles hiccup into native
behind the scenes. Crossing is explicit in source, visible to tests, and
named by Xray. An application that never requires the native namespace ships
zero native-tier code.

## The ladder

Climb one rung at a time, with a measurement at every step.

| Rung | You write | What changes | Climb when |
|---|---|---|---|
| 1. Ordinary Hicasso | Hiccup, `h/sub`, event vectors | Nothing — this is the product | Always start here |
| 2. Tuned topology | The same language | View placement, keys, read shape (fine / coarse / chunked / windowed), virtualization | A named interaction misses its budget — [Lists and collections](06-lists-and-collections.md) |
| 3. Direct native return | A `defview` whose body returns `n/$` | Hiccup interpretation skipped for that result; frame, reads, memo, and lifecycle stay | Attribution names hiccup construction as the cost owner |
| 4. Named native island | `n/defcomponent` (or UIx) with `n/use-sub` / `n/use-frame` | Hooks, vendor widgets, and high-rate mechanics live in a real native component with an explicit crossing | Hooks, reconciliation, vendor behavior, or high-rate local work dominates |
| 5. Native screen | A whole screen in the native namespace or UIx | The view language for that screen — same root, same frames, same app-db | The screen is React-shaped by design |

Rungs 1 and 2 are ordinary product —
[Views and reads](02-views-and-reads.md) and
[Lists and collections](06-lists-and-collections.md). This page owns rungs 3
to 5.

**Escape-benefit rule:** keep an escape only if it recovers at least 20% of
the measured interaction, saves at least 2 ms at p95, or converts a failed
user-visible budget into a pass. Otherwise take it out. Native code that
cannot meet that rule is a permanent cost with no return. Reverting a rung-3
escape is a small diff, so the rule has no exceptions.

## Rung 3 — return native React from a view

Here is a watchlist row after rung 2 is already done. The table is windowed
to the ~40 visible rows, keys are stable, and each row makes one coarse
display read. The subscription returns render-ready strings, so the body does
almost no work of its own.

```clojure
(ns app.watchlist.row
  (:require [re-frame.hicasso :as h]))

(h/defview quote-row [{:keys [sym]}]
  (let [{:keys [px chg pct vol up?]} (h/sub [:quotes/display-row sym])]
    [:tr {:class    (if up? "quote up" "quote down")
          :on-click [:watchlist/select sym]}
     [:td.sym sym]
     [:td.px  px]
     [:td.chg chg]
     [:td.pct pct]
     [:td.vol vol]]))
```

Market ticks update most visible rows several times a second. On this app's
low-tier reference laptop, Xray's attribution for the tick event reads:
bodies 3 ms, hiccup construction 9 ms, React reconcile-and-commit 6 ms, paint
3 ms — 21 ms at p95. Every row did change, so there is no read-topology fix
left. The owner is construction: forty rows of vectors turn into React
elements, several times a second. That is the situation rung 3 exists for.

A `defview` may return an existing React element instead of hiccup. Build it
with `n/$`:

```clojure
(ns app.watchlist.row
  (:require [re-frame.hicasso        :as h]
            [re-frame.hicasso.native :as n]))

(h/defview quote-row [{:keys [sym]}]
  (let [{:keys [px chg pct vol up?]} (h/sub [:quotes/display-row sym])]
    (n/$ :tr {:class    (if up? "quote up" "quote down")
              :on-click (h/event [_] [:watchlist/select sym])}
         (n/$ :td {:class "sym"} sym)
         (n/$ :td {:class "px"}  px)
         (n/$ :td {:class "chg"} chg)
         (n/$ :td {:class "pct"} pct)
         (n/$ :td {:class "vol"} vol))))
```

The view did not change as a unit. The parent still renders
`[quote-row {:key sym :sym sym}]`. The view keeps its React identity,
value-equality memo, frame, `h/sub` reads, lifecycle, and name in Xray. What
changed is the return value: an already-built React element, so hiccup
interpretation is skipped for this result. Re-measured, the tick lands at
13 ms p95 — 38% recovered, 8 ms saved. That passes the benefit rule on two
conditions; the escape stays. Your numbers will differ. The point is that you
have numbers.

Read the diff:

- **`[:td.sym …]` became `(n/$ :td {:class "sym"} …)`.** The native grammar
  has no selector shorthand. Spell class and id as props.
- **`:on-click [:watchlist/select sym]` became
  `(h/event [_] [:watchlist/select sym])`.** Past this fence there is no
  automatic event-vector → callback conversion. An event vector in a native
  prop is an error, not a callback. `h/event` captures the frame while the
  body runs and hands React an ordinary function. Plain `fn` values are fine
  when you do not dispatch.
- **Children are nested `n/$` forms, not vectors.** A hiccup vector as a
  native child is refused. If a native subtree needs one Hicasso-rendered
  child, convert explicitly: `(h/as-element [sparkline {:points pts}])`.

What stays with the view, and what stops at the returned element:

| Stays with the view | Stops at the returned element |
|---|---|
| The frame — and `h/sub` anywhere in the body | Hiccup interpretation — a vector is not markup here |
| The props ABI, value-equality memo, and the parent's `:key` | Event vectors in props — an event vector in a prop is an error |
| Lifecycle, HMR conduct, and the view's name in Xray | Controlled repair — no `::h/value`, `::h/checked`, `::h/prevent`, `::h/revision` |
| Server rendering — intrinsic-headed output produces the same deterministic bytes | Structural assertions and key diagnostics — the element is opaque to the pure test tiers ([Testing](14-testing.md)) |

Form fields stay interpreted. An `<input>` inside `n/$` is a raw React
controlled input: you do all of React's manual work, and you get none of
Hicasso's caret, IME, or same-turn guarantees
([Controlled inputs](04-controlled-inputs.md)). Hooks still do not belong in
the body. A `defview` body is dynamically composed — branches and loops are
legal, which is where hook order breaks. The wish for a hook in a `defview`
body is the signal you are at rung 4, not rung 3.

### The `n/$` grammar

```clojure
(n/$ head)
(n/$ head child*)
(n/$ head literal-props child*)
(n/$ head (n/props dynamic-props) child*)
```

- **Heads.** An unqualified keyword such as `:div` names an intrinsic React
  element. A string names an intrinsic or custom element verbatim (SVG and
  web components work). Any other head expression must evaluate to a native
  React component.
- **The props operand.** The macro treats exactly four forms as props: `nil`,
  a literal ClojureScript map, a literal `#js` object, or the explicit
  `(n/props expression)` marker. **Every other trailing form is a child.**
- **Prop names.** ClojureScript-map keys use the canonical React slot-name
  rule: kebab becomes camelCase (`:on-click` → `onClick`); `:class` and
  `:for` become the React names; `data-*` / `aria-*` stay hyphenated;
  camelCase keywords are fixpoints; string keys pass verbatim. A raw
  JavaScript object is never renamed.
- **Prop values pass by identity.** No event-vector conversion, no
  class-collection merge, no style-map conversion, no keyword-value
  conversion, no controlled-field repair, no deep conversion. Where a native
  API expects a JavaScript object — React style objects included — hand it
  one: `:style #js {:transform "translateX(4px)"}`.
- **Children** are trailing ReactNode values. Nest with `n/$`; collections
  must already be valid React children, normally a JavaScript array
  (`(into-array (map row-el rows))`, each element carrying its `:key`). The
  grammar refuses `:children` inside the props map — there is one child
  channel.
- **`:key` and `:ref`** use the ordinary React slots. Two source keys that
  normalize to the same slot — for example `:class` and `"className"` in one
  map — refuse rather than let map order pick a winner.

!!! warning "Dynamic props must be marked"
    The props rule is syntactic on purpose. A dynamic React element is itself
    a JavaScript object, so the macro never inspects a runtime value to decide
    "props or child".

    ```clojure
    ;; Don't: a dynamic map in second position is a CHILD, not props.
    (let [cell-props {:class "px" :dir "ltr"}]
      (n/$ :td cell-props px))   ;; refuses — recovery names (n/props …)

    ;; Do: mark the operand. n/props emits no wrapper — it only classifies.
    (let [cell-props {:class "px" :dir "ltr"}]
      (n/$ :td (n/props cell-props) px))
    ```

    A dynamic ClojureScript map inside `n/props` converts shallowly under the
    same slot-name rule; a JavaScript object passes by identity.

## Rung 4 — a named native island

When the cost owner is not hiccup construction but *behavior* — hooks, a
retained vendor widget, React reconciliation itself, or work that runs per
animation frame — the answer is a named, top-level native component. The
self-contained route is `n/defcomponent`. UIx is an equally supported mature
route. A JavaScript or TypeScript component enters through the host bridge
([Interop](09-interop.md)). Every route stays inside the same React root, the
same frame context, and the same state owner.

Here is a column-resize handle. Pointer movement is high-rate mechanics —
host-private, not an application fact. It stays in local React state; app-db
receives exactly one write, on release
([Ephemeral state](11-ephemeral-state.md)):

```clojure
(ns app.watchlist.resizer
  (:require ["react" :as react]
            [re-frame.hicasso.native :as n]))

(n/defcomponent col-resizer
  ;; No declaration map: the server policy defaults to Client-only.
  [^js props]
  (let [col                (.-col props)
        {:keys [dispatch]} (n/use-frame)
        committed          (n/use-sub [:watchlist/col-width col])
        [live set-live]    (react/useState nil)]   ; px while dragging, else nil
    (n/$ :div
         {:class            "col-resizer"
          :role             "separator"
          :aria-orientation "vertical"
          :on-pointer-down  (fn [e]
                              (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                              (set-live committed))
          :on-pointer-move  (fn [e]
                              (set-live (fn [w] (some-> w (+ (.-movementX e))))))
          :on-pointer-up    (fn [_]
                              (when live
                                (dispatch [:watchlist/set-col-width col live]))
                              (set-live nil))
          :on-lost-pointer-capture (fn [_] (set-live nil))}
         (when live
           (n/$ :div {:class "col-resizer__guide"
                      :style #js {:transform (str "translateX(" (- live committed) "px)")}})))))
```

Piece by piece:

- **The ABI is one raw JavaScript props object** — read it with `.-col`;
  children arrive at `.-children`. There is no hidden map allocation.
- **A declaration map before the argument vector carries the server
  policy** — `{:server :render}` or `{:server :client-only}`. Omit it and
  the policy is Client-only, which is right for a pointer-driven widget.
- **Ordinary React hooks are used directly.** `react/useState`,
  `react/useEffect`, refs — React's surface, React's rules.
- **`n/use-sub` and `n/use-frame` join the frame you were already in.** The
  first reads a subscription under the current frame and re-renders the
  island when the value changes. The second is frame capture in hook
  position. It returns the frame-locked ops map —
  `{:frame :dispatch :dispatch-sync :subscribe}` — and the map is
  reference-stable across re-renders of the same frame *incarnation*. A frame
  keyword is an address, not an identity: destroying that frame and creating
  another under the same id retargets the map. Within an incarnation it is
  safe to pull `:dispatch` off it and close over it in callbacks; a bundle
  held across a same-id reincarnation is silently inert.
- **High-rate work never touches app-db.** The drag guide tracks the pointer
  through local React state; the component dispatches the single committed
  fact — the final width — once, on release.

The two read doors look similar but obey different laws:

| Door | Lives in | Rules |
|---|---|---|
| `h/sub` | Hicasso bodies | Direct synchronous body only; branches, loops, and plain helpers are fine; not a hook |
| `n/use-sub` | Native components | A real React hook; the rules of hooks apply — top level of the component, unconditional |

### Mounting the island

From interpreted hiccup, mount an island behind a named host. Declare once,
use as an ordinary head:

```clojure
(h/defhost resize-handle col-resizer)

;; in the header view:
[:th {:class "px"} "Price" [resize-handle {:col :px}]]
```

From native code, a component is a head as-is: `(n/$ col-resizer {:col :px})`.
Both directions cross under the same root and frame. The one-off raw element
escape also exists ([Interop](09-interop.md)), but it is for migration and
true one-off interop. Repeated or hot crossings deserve a name, a
declaration, and tests — which is what `defhost` gives you.

!!! note "Islands and the server"
    An intrinsic `n/$` return renders on the server like the hiccup it
    replaced. A component-headed island defaults to Client-only — leaving its
    declared fallback in the server bytes or, bare, nothing at all — until
    its declaration selects `{:server :render}` and proves matching hydration.
    [SSR and hydration](17-ssr-and-hydration.md) owns the full contract.

??? info "If your team already writes UIx"
    Everything above has a UIx spelling. A `defview` at rung 3 may return a
    `uix.core/$` element, and an island at rung 4 may be a `defui`. The native
    hooks are substrate-neutral, so `n/use-sub` and `n/use-frame` work inside
    a `defui` exactly as inside `n/defcomponent`. The Hicasso-native surface
    is held to parity against both handwritten React and UIx, so the choice is
    taste and scale, not speed. Two corrections: `n/*` is not UIx-lite and
    never imports UIx — a Hicasso app without UIx islands ships zero UIx
    bytes. When a native region grows into substantial React-first work, UIx
    is the designed answer, not a defeat — the native namespace is deliberately
    too small to be a component framework.

## Keeping the marker: the ABI helpers

`n/defcomponent` stamps its component with a display name and a tier marker
carrying that name and the declared server policy. The marker lets Xray name
the view, and it is how ABI helpers and embedding directions recognize a
native head. It does not carry the component across a hot reload. Defining a
component is allocation, never a lookup by name: a save re-evaluates the
module, the component is allocated afresh, the element type at that position
is a new object, and React replaces the subtree. **A clean remount across a
save is designed conduct, not a fault** — a component's name is an address,
not an identity, the same as `defview`. Raw React wrappers erase the marker:

```clojure
;; given (n/defcomponent quote-cell* …) — a pure display cell worth memoizing:

;; Don't: works at runtime, but the marker is gone — Xray shows an anonymous
;; view, and the embedding seams no longer recognize the head.
(def quote-cell (react/memo quote-cell*))

;; Do: same React.memo semantics, marker intact.
(def quote-cell (n/memo quote-cell*))
```

`n/memo` and `n/lazy` carry the one props/children ABI through memoization
and code-split loading. Refs need no helper: `:ref` uses React's ordinary
slot, and a function component receives it through props. The same
preservation applies to both embedding directions: hiccup rendering an island
(above), and a native parent rendering a Hicasso view through the outward bridge
([Interop](09-interop.md)).

## Rung 5 — a native screen

Some screens are React-shaped from the first commit — a canvas editor, a
diagramming surface, a screen that is mostly one large vendor grid. Implement
that screen's view tree natively — with the native namespace, with UIx, or in
JavaScript behind hosts — under the same installed adapter, the same root,
and the same frames. This is a local view-implementation choice, not a second
adapter, and never a second state owner. An independent React root remains an
isolation choice, not a speed choice.

## Every crossing runs the loop

The numbered working loop — reproduce, attribute with Xray, tune topology
first, then compare an escape and keep it only if it passes the benefit
rule — is [Performance](18-performance.md)'s method. Every rung on this page
is entered through it: rung 3 when attribution names hiccup construction,
rung 4 when it names hooks, vendor behavior, reconciliation, or high-rate
local work. What this page adds is the follow-through a crossing owes.
Re-run the contracts you can no longer see — DOM and event-vector parity,
focus and selection, frame routing, SSR and hydration, cleanup, and the
performance script — before you call the escape done.

Xray stays honest on both sides of the fence. It names and times the native
view, shows reads made through `n/use-sub`, and labels the inner React tree
*opaque* — a real answer, never a silently empty one. The advisor may
recommend a view split and scaffold a comparison. It never rewrites your
code, and nothing switches semantics at runtime.

## When not to go native

- **You have no named measurement.** "The app feels slow" is almost always a
  rung-1 or rung-2 problem underneath. No profile, no escape.
- **The owner is read placement.** On the worst mounts we have measured, most
  of the deficit was read shape — too many fine per-row subscriptions — not
  the hiccup walk. That fix is [Lists and collections](06-lists-and-collections.md),
  and it keeps every convenience the fence would cost you.
- **The problem is typing latency.** Keystroke echo is an event-volume and
  controlled-field question ([Controlled inputs](04-controlled-inputs.md),
  [Performance](18-performance.md)); native construction does not move it.
- **A controlled field is involved.** The native tier has no controlled
  repair — same-turn convergence, caret and IME preservation, and
  `::h/revision` live on the hiccup side. Fields stay interpreted.
- **You want rung 4 "for consistency".** One island is an escape; islands
  everywhere is a rewrite of the product you chose.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Refusal: a ClojureScript map appeared as a React child | A dynamic map in the props position is classified as a child — the grammar only recognizes `nil`, literal maps, `#js` literals, and `(n/props …)` as props | Mark it: `(n/$ :td (n/props m) …)` |
| Refusal on a vector child inside `n/$` | Hiccup is not interpreted past the fence | Convert with `h/as-element`, or keep that subtree interpreted |
| Refusal: event vector in a native prop (`:on-click [:x/select id]`) | No event-vector conversion past the fence — native callbacks are functions | `(h/event [_] [:x/select id])` in a rung-3 body; `:dispatch` from `n/use-frame` inside an island |
| Refusal: `:children` in the props map | One child channel — trailing forms | Pass children after the props operand |
| Refusal: two keys normalize to one slot (`:class` and `"className"`) | Canonical-slot collision | Keep one spelling per slot |
| `:rf.error/no-frame-context` from `n/use-sub` or `n/use-frame` | Island rendered outside any frame provider — separate root, portal outside the app, or test without the harness | Mount under the app root, or use the test kit's provider ([Testing](14-testing.md)) |
| Xray shows an anonymous view where a named island should be | Component marker erased by a raw wrapper (`react/memo`, `React.lazy`) | Use `n/memo` / `n/lazy` — same semantics, marker intact |
| Local state inside an island resets whenever you save | Designed HMR conduct: reload allocates a fresh component, React remounts | Nothing to fix. State that must outlive a save belongs in `app-db`, read back through `n/use-sub` |
| Went native, numbers did not move | Cost owner was never construction — usually reads or event volume | Back to loop step 2; expect the fix at rung 2, and take the unearned escape out |
