# The native tier

Most apps never need this page. Ordinary [Hicasso](glossary.md#hicasso) —
hiccup, [`h/sub`](glossary.md#hsub) where you read, events as data — is the
product. This page is the exit for the thin slice that is not: market-tick
rows, display-rate drag, vendor widgets that are hooks all the way down. You
write React directly; frame, app-db, and root stay the same.

> **`[...]` is always interpreted Hiccup. [`n/$`](glossary.md#n-dollar) is always native React. Neither form ever changes the other's meaning — and nothing, anywhere, compiles hiccup.**

That sentence is the whole architecture. There is no compiler tier, no `:fast` flag, no mode switch. Crossing is explicit in your source, visible to your tests, and named by Xray. The tier also costs nothing when you do not use it: an application that never requires the native namespace ships zero native-tier code.

## The ladder

The [performance ladder](glossary.md#performance-ladder) is a gradient of
explicit choices. You climb it one rung at a time, with a measurement at every step.

| Rung | You write | What changes | Climb when |
|---|---|---|---|
| 1. Ordinary [Hicasso](glossary.md#hicasso) | Hiccup, [`h/sub`](glossary.md#hsub), event vectors | Nothing — this is the product | Always start here |
| 2. Tuned topology | The same language | Boundary placement, keys, read shape (fine / coarse / chunked / windowed), virtualization | A named interaction misses its budget — [Lists and collections](06-lists-and-collections.md) |
| 3. Direct native return | A [`defview`](glossary.md#defview) whose body returns [`n/$`](glossary.md#n-dollar) | Hiccup [lowering](glossary.md#lowering) is skipped for that result; frame, reads, memo, and lifecycle stay | Attribution names lowering as the cost owner |
| 4. Named [native island](glossary.md#native-island) | [`n/defcomponent`](glossary.md#ndefcomponent) (or UIx) with [`n/use-sub`](glossary.md#nuse-sub) / [`n/use-frame`](glossary.md#nuseframe) | Hooks, vendor widgets, and high-rate mechanics live in a real native component with an explicit crossing | Hooks, reconciliation, vendor behavior, or high-rate local work dominates |
| 5. Native screen | A whole screen in the native namespace or UIx | The view language for that screen — same root, same frames, same app-db | The screen is React-shaped by design |

Rungs 1 and 2 are the ordinary product — [Views and reads](02-views-and-reads.md) and [Lists and collections](06-lists-and-collections.md) own them. This page owns rungs 3 to 5, and the rule that disciplines all three:

**[Escape-benefit rule](glossary.md#escape-benefit-rule):** an escape stays
only if it recovers at least 20% of the measured interaction, saves at least
2 ms at p95, or converts a failed [user-visible budget](glossary.md#user-visible-budget)
into a pass — otherwise it comes out. Native code that cannot meet that rule
is a permanent cost with no return. Reverting a rung-3 escape is a five-minute
diff, so the rule has no exceptions.

## Rung 3 — return native React from a boundary

Here is a watchlist row after rung 2 is already done properly. The table is windowed to the ~40 visible rows, keys are stable, and each row makes one coarse display read. The subscription returns render-ready strings, so the body does almost no work of its own.

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

Market ticks update most visible rows four times a second. On this app's low-tier reference laptop, Xray's attribution for the tick event reads: bodies 3 ms, hiccup [lowering](glossary.md#lowering) 9 ms, React reconcile-and-commit 6 ms, paint 3 ms — 21 ms at p95. Every row did change, so there is no read-topology fix left to make. The owner is lowering: forty rows of vectors turn into React elements, four times a second. That is the one situation rung 3 exists for.

A [`defview`](glossary.md#defview) may return an existing React element instead of hiccup. Build it with [`n/$`](glossary.md#n-dollar):

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

The [boundary](glossary.md#boundary) did not change. The parent still renders `[quote-row {:key sym :sym sym}]`. The view keeps its React identity, its value-equality memo, its frame, its [`h/sub`](glossary.md#hsub) reads, its lifecycle, and its name in Xray. What changed is the return value: an already-constructed React element, so hiccup [lowering](glossary.md#lowering) is skipped for this result entirely. Re-measured, the tick lands at 13 ms p95 — 38% recovered, 8 ms saved. That passes the benefit rule on two of its conditions; the escape stays. Your numbers will differ. The point is that you have numbers.

Read the diff closely. Every changed line marks the semantic fence:

- **`[:td.sym …]` became `(n/$ :td {:class "sym"} …)`.** The native grammar has no selector shorthand. You spell class and id as props.
- **`:on-click [:watchlist/select sym]` became `(h/event [_] [:watchlist/select sym])`.** Past the fence there is no [intent](glossary.md#intent) lowering. An event vector in a native prop is an error, not a callback. [`h/event`](glossary.md#hevent) is the same explicit form you use everywhere else. It captures the frame while the body runs, and it hands React an ordinary function that dispatches its result. Plain `fn` values are equally legal when you do not dispatch.
- **Children are nested [`n/$`](glossary.md#n-dollar) forms, not vectors.** A hiccup vector as a native child refuses at its source. If a native subtree needs one Hicasso-rendered child, convert it explicitly: `(h/as-element [sparkline {:points pts}])` lowers the hiccup under the captured frame and yields a legal ReactNode.

The whole fence fits in one table. The left column is why rung 3 is cheap to adopt and cheap to revert. The right column is the price of native semantics, paid at the crossing:

| Stays with the boundary | Stops at the returned element |
|---|---|
| The frame — and [`h/sub`](glossary.md#hsub) anywhere in the body | Hiccup interpretation — a vector is not markup here |
| The props ABI, value-equality memo, and the parent's `:key` | Intent lowering — an event vector in a prop is an error |
| Lifecycle, HMR conduct, and the view's name in Xray | Controlled repair — no [`::h/value`](glossary.md#hvalue), [`::h/checked`](glossary.md#hchecked), [`::h/prevent`](glossary.md#hprevent), [`::h/revision`](glossary.md#hrevision) |
| Server rendering — intrinsic-headed output produces the same deterministic bytes | Structural assertions and key diagnostics — the element is opaque to the pure test tiers, which refuse with a pointer to the mounted tier ([Testing](14-testing.md)) |

Two consequences deserve their own sentences. First, form fields stay interpreted. An `<input>` inside [`n/$`](glossary.md#n-dollar) is a raw React [controlled input](glossary.md#controlled-field): you do all of React's manual controlled-input work, and you get none of [Hicasso](glossary.md#hicasso)'s caret, IME, or same-turn guarantees ([Controlled inputs](04-controlled-inputs.md)). Second, hooks still do not belong in the body. A [`defview`](glossary.md#defview) body is dynamically composed — branches and loops are legal, and that is exactly the environment where hook order breaks. The wish for a hook in a `defview` body is the signal that you are at rung 4, not rung 3.

### The `n/$` grammar

The authoring shape is deliberately small:

```clojure
(n/$ head)
(n/$ head child*)
(n/$ head literal-props child*)
(n/$ head (n/props dynamic-props) child*)
```

- **Heads.** An unqualified keyword such as `:div` names an intrinsic React element. A string names an intrinsic or custom element verbatim (SVG and web components work). Any other head expression must evaluate to a native React component.
- **The props operand.** The macro treats exactly four forms as props: `nil`, a literal ClojureScript map, a literal `#js` object, or the explicit `(n/props expression)` marker. **Every other trailing form is a child.**
- **Prop names.** ClojureScript-map keys use the canonical React slot-name rule: kebab spellings become camelCase (`:on-click` → `onClick`); `:class` and `:for` become the React names; `data-*` / `aria-*` stay hyphenated; camelCase keywords are fixpoints; string keys pass verbatim. A raw JavaScript object is never renamed — it already uses React's names.
- **Prop values pass by identity.** No [intent](glossary.md#intent) [lowering](glossary.md#lowering), no class-collection merge, no style-map conversion, no keyword-value conversion, no controlled-field repair, no deep conversion. Where a native API expects a JavaScript object — React style objects included — hand it one: `:style #js {:transform "translateX(4px)"}`.
- **Children** are trailing ReactNode values. Nest with [`n/$`](glossary.md#n-dollar); collections must already be valid React children, normally a JavaScript array (`(into-array (map row-el rows))`, each element carrying its `:key`). The grammar refuses `:children` inside the props map — there is one child channel.
- **`:key` and `:ref`** use the ordinary React slots. Two source keys that normalize to the same slot — for example `:class` and `"className"` in one map — refuse rather than let map order pick a winner.

!!! warning "Dynamic props must be marked"
    The props rule is syntactic on purpose. A dynamic React element is itself a JavaScript object, so the macro never inspects a runtime value to decide "props or child" — a guess would misclassify elements. The cost is one rule you must know:

    ```clojure
    ;; Don't: a dynamic map in second position is a CHILD, not props.
    (let [cell-props {:class "px" :dir "ltr"}]
      (n/$ :td cell-props px))   ;; refuses — a map is not a valid React child,
                                 ;; and the recovery names (n/props …)

    ;; Do: mark the operand. n/props emits no wrapper — it only classifies.
    (let [cell-props {:class "px" :dir "ltr"}]
      (n/$ :td (n/props cell-props) px))
    ```

    A dynamic ClojureScript map inside [`n/props`](glossary.md#nprops) converts shallowly under the same slot-name rule; a JavaScript object passes by identity.

## Rung 4 — a named native island

When the cost owner is not [lowering](glossary.md#lowering) but *behavior* — hooks, a retained vendor widget, React reconciliation itself, or work that runs per animation frame — the answer is a named, top-level native component. The self-contained route is [`n/defcomponent`](glossary.md#ndefcomponent). UIx is an equally supported, mature route. A JavaScript or TypeScript component enters through the host bridge ([Interop](09-interop.md)). Every route stays inside the same React root, the same frame context, and the same state owner. An island is a place, not a second architecture.

Here is a column-resize handle. Pointer movement is 120 Hz mechanics — host-private, not an application fact. It stays in local React state, and app-db receives exactly one write, on release ([Ephemeral state](11-ephemeral-state.md)):

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

What this teaches, piece by piece:

- **The ABI is one raw JavaScript props object** — read it with `.-col`; children arrive at `.-children`. There is no hidden map allocation, and no second "fast" ABI anywhere in the tier.
- **A declaration map before the argument vector carries the [server policy](glossary.md#server-policy)** — `{:server :render}` or `{:server :client-only}`. Omit it and the policy is Client-only, which is exactly right for a pointer-driven widget.
- **Ordinary React hooks are used directly.** `react/useState`, `react/useEffect`, refs — React's surface, React's rules. [Hicasso](glossary.md#hicasso) adds no hook wrappers here.
- **[`n/use-sub`](glossary.md#nuse-sub) and [`n/use-frame`](glossary.md#nuseframe) join the frame you were already in.** The first reads a subscription under the current frame and re-renders the island when the value changes. The second is frame capture in hook position. It returns the frame-locked ops map — `{:frame :dispatch :dispatch-sync :subscribe}` — and the map is reference-stable across re-renders. It is therefore safe to pull `:dispatch` off it and close over it in callbacks.
- **High-rate work never touches app-db.** The drag guide tracks the pointer through local React state; the component dispatches the single committed fact — the final width — once, on release.

The two read doors deserve one table, because they look similar but obey different laws:

| Door | Lives in | Rules |
|---|---|---|
| [`h/sub`](glossary.md#hsub) | Hicasso bodies | Direct synchronous body only; branches, loops, and plain helpers are fine; not a hook |
| [`n/use-sub`](glossary.md#nuse-sub) | Native components | A real React hook; the rules of hooks apply — top level of the component, unconditional |

### Mounting the island

From interpreted hiccup, you mount an island behind the named host seam. Declare it once, and use it as an ordinary head:

```clojure
(h/defhost resize-handle col-resizer)

;; in the header view:
[:th {:class "px"} "Price" [resize-handle {:col :px}]]
```

From native code, a component is a head as-is: `(n/$ col-resizer {:col :px})`. Both directions cross under the same root and frame. The one-off raw element escape also exists ([Interop](09-interop.md)), but it is for migration and for true one-off interop. Repeated or hot crossings deserve a name, a declaration, and tests — which is what [`defhost`](glossary.md#defhost) gives you.

!!! note "Islands and the server"
    An intrinsic [`n/$`](glossary.md#n-dollar) return renders on the server like the hiccup it replaced. A component-headed island defaults to Client-only — a source-located refusal, leaving its declared fallback in the server bytes or, bare, nothing at all — until its declaration selects `{:server :render}` and proves matching hydration. [SSR and hydration](17-ssr-and-hydration.md) owns the full contract.

??? info "If your team already writes UIx"
    Everything above has a UIx spelling, and it is not a second-class one. A [`defview`](glossary.md#defview) at rung 3 may return a `uix.core/$` element, and an island at rung 4 may be a `defui`. The native hooks are substrate-neutral, so [`n/use-sub`](glossary.md#nuse-sub) and [`n/use-frame`](glossary.md#nuseframe) work inside a `defui` exactly as inside [`n/defcomponent`](glossary.md#ndefcomponent). The Hicasso-native surface is held to parity against both handwritten React and UIx, so the choice is taste and scale, not speed. Two instincts need correction. First, `n/*` is not UIx-lite and never imports UIx: a [Hicasso](glossary.md#hicasso) app without UIx islands ships zero UIx bytes. Second, when a native region grows into substantial React-first work, UIx is the designed answer, not a defeat — the native namespace is deliberately too small to be a component framework.

## Keeping the marker: the ABI helpers

[`n/defcomponent`](glossary.md#ndefcomponent) stamps its component with a display name and a tier marker carrying that name and the declared server policy. The marker lets Xray name the [boundary](glossary.md#boundary), and it is the seam every ABI helper and every embedding direction reads to recognize a native head. What it does not do is carry the component across a hot reload, and it is not meant to. Minting a component is allocation, never a lookup by name: a save re-evaluates the module, the component is allocated afresh, the element type at that position is a new object, and React replaces the subtree. **A clean remount across a save is the designed conduct, not a fault** — a component's name is an address, not an identity, exactly as [`defview`](glossary.md#defview)'s is. Raw React wrappers erase the marker:

```clojure
;; given (n/defcomponent quote-cell* …) — a pure display cell worth memoizing:

;; Don't: works at runtime, but the marker is gone — Xray shows an anonymous
;; boundary, and the embedding seams no longer recognize the head.
(def quote-cell (react/memo quote-cell*))

;; Do: same React.memo semantics, marker intact.
(def quote-cell (n/memo quote-cell*))
```

[`n/memo`](glossary.md#nmemo) and [`n/lazy`](glossary.md#nlazy) carry the one props/children ABI through memoization and code-split loading. Refs need no helper: `:ref` uses React's ordinary slot, and a function component receives it through props. The same preservation applies to the two embedding directions: hiccup rendering an island (above), and a native parent rendering a minted [Hicasso](glossary.md#hicasso) view through the [outward bridge](glossary.md#outward-bridge) ([Interop](09-interop.md)).

## Rung 5 — a native screen

Some screens are React-shaped from their first commit — a canvas editor, a diagramming surface, a screen that is mostly one enormous vendor grid. Implement that screen's view tree natively — with the native namespace, with UIx, or in JavaScript behind hosts — under the same installed adapter, the same root, and the same frames. This is a local view-implementation choice, not a second adapter, and never a second state owner. An independent React root remains an isolation choice, not a speed choice. After shipping, the native share of your source is a number you can report. Nothing enforces it, because 0% and 2% are both fine answers.

## Every crossing runs the loop

The numbered working loop — reproduce, attribute with Xray, tune topology first, then compare an escape and keep it only if it passes the benefit rule — is [Performance](18-performance.md)'s method. Every rung on this page is entered through it: rung 3 when attribution names [lowering](glossary.md#lowering), rung 4 when it names hooks, vendor behavior, reconciliation, or high-rate local work. What this page adds is the follow-through a crossing owes. Re-run the contracts you can no longer see — DOM and [intent](glossary.md#intent) parity, focus and selection, frame routing, SSR and hydration, cleanup, and the performance script — before you call the escape done.

Xray stays honest on both sides of the fence. It names and times the native [boundary](glossary.md#boundary), it shows the reads made through [`n/use-sub`](glossary.md#nuse-sub), and it labels the inner React tree *opaque* — a real answer, never a silently empty one. The advisor may recommend a boundary and scaffold a comparison. It never rewrites your code, and nothing ever switches semantics at runtime.

## When not to go native

- **You have no named measurement.** "The app feels slow" is almost always a rung-1 or rung-2 problem underneath. No profile, no escape.
- **The owner is [read topology](glossary.md#read-topology).** On the worst mounts we have measured, most of the deficit was read shape — too many fine per-row subscriptions — not the hiccup walk. That fix is [Lists and collections](06-lists-and-collections.md), and it keeps every convenience the fence would cost you.
- **The problem is typing latency.** Keystroke echo is an event-volume and controlled-field question ([Controlled inputs](04-controlled-inputs.md), [Performance](18-performance.md)); native construction does not move it.
- **A [controlled field](glossary.md#controlled-field) is involved.** The [native tier](glossary.md#native-tier) has no controlled repair — same-turn convergence, caret and IME preservation, and [`::h/revision`](glossary.md#hrevision) live on the hiccup side. Fields stay interpreted.
- **You want rung 4 "for consistency".** One island is an escape; islands everywhere is a rewrite of the product you chose. The 2% is a set of local islands, not a general style.

## Troubleshooting

| Symptom | What is happening | Fix |
|---|---|---|
| Refusal: a ClojureScript map appeared as a React child | A dynamic map in the props position is classified as a child — the grammar only recognizes `nil`, literal maps, `#js` literals, and `(n/props …)` as props | Mark it: `(n/$ :td (n/props m) …)` |
| Refusal on a vector child inside [`n/$`](glossary.md#n-dollar) | Hiccup is not interpreted past the fence — square brackets have no meaning in native children | Convert explicitly with [`h/as-element`](glossary.md#as-element), or keep that subtree interpreted |
| Refusal: event vector in a native prop (`:on-click [:x/select id]`) | No [intent](glossary.md#intent) [lowering](glossary.md#lowering) past the fence — native callbacks are functions | `(h/event [_] [:x/select id])` in a rung-3 body; `:dispatch` from [`n/use-frame`](glossary.md#nuseframe) inside an island |
| Refusal: `:children` in the props map | There is one child channel — trailing forms | Pass children after the props operand |
| Refusal: two keys normalize to one slot (`:class` and `"className"`) | Canonical-slot collision — the grammar refuses rather than letting map order pick a winner | Keep one spelling per slot |
| `:rf.error/no-frame-context` from [`n/use-sub`](glossary.md#nuse-sub) or [`n/use-frame`](glossary.md#nuseframe) | The island rendered outside any frame provider — a separate root, a [portal](glossary.md#portal) outside the app, or a test without the harness | Mount under the app root, or use the [test kit](glossary.md#test-kit)'s provider ([Testing](14-testing.md)) |
| Xray shows an anonymous [boundary](glossary.md#boundary) where a named island should be | The component marker was erased by a raw wrapper (`react/memo`, `React.lazy`) — the display name and the declared server policy went with it | Use [`n/memo`](glossary.md#nmemo) / [`n/lazy`](glossary.md#nlazy) — same semantics, marker intact |
| Local state inside an island resets whenever you save | Nothing is wrong. A reload allocates a fresh component, so the element type changes and React remounts the subtree — the designed HMR conduct, and [`defview`](glossary.md#defview)'s too | Nothing to fix. State that must outlive a save belongs in `app-db`, read back through [`n/use-sub`](glossary.md#nuse-sub) |
| Went native, numbers did not move | The cost owner was never construction — usually reads or event volume | Back to loop step 2; expect the fix at rung 2, and take the unearned escape out |
