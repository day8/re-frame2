# Host boundaries

Most Freehand code never needs this page. Ordinary views stay in data: Hiccup,
event vectors, subscriptions, props.

Real apps still embed React widgets, canvas libraries, dialogs, and measurement.
Those are **host facts** — not app-db. Freehand gives a small set of **explicit
boundaries** so the host work is honest and the rest of the tree stays inspectable.

There is no general “neutral hooks / refs / effects” language in ordinary views.

> **Qualify the host edge. Keep application truth in re-frame.**

## Four host shapes

| Shape | Use when |
|---|---|
| **`v/defhost`** | the paved inward door: a React component **declared** as a Freehand host and mounted at a vector head |
| **A React element in a child position** | the weaker escape: you already hold a created element, and can do without the declared door's guarantees |
| **Registered behavior** | one DOM node owned by an imperative library (`connect` / `update` / `disconnect`, optional commands) |
| **`v/->react`** | pointing outward: a library asks for a *component value* and you hand it a declared view |

A bare React **component** at a vector head is not a legal Freehand descriptor.
`v/defhost` mints the descriptor that is, and it is the only thing that does. A
created React **element** in a child position is legal too, and deliberately
weaker — those two are the whole inward boundary.

### Declared hosts (`v/defhost`)

`v/defhost` declares a React component as a Freehand **host**. It is the sole
public inward React boundary, and the only way to mint the third legal vector head.

```clojure
(v/defhost date-picker
  "A third-party date picker."
  DatePicker                                  ; the imported React component
  {:callbacks {:onChange :event}
   :children  :none
   :ssr       :client-only})

(v/defview booking-date [{:keys [date]}]
  [date-picker
   {:selected date
    :onChange (v/event [js-date]
                [:booking/date-picked (from-js-date js-date)])}])
```

The var holds a **non-callable descriptor**: it is mounted, never invoked, and
`(date-picker {…})` reports rather than answering `nil` the way a map's lookup
would. That is the silent failure the boundary exists to remove, at exactly the
place a hand arriving from React is most likely to call by habit.

There is **one** descriptor kind. "Leaf" and "wrapper" describe the registered
React implementation — which may itself use hooks, context, refs, effects,
Suspense or a compound protocol — not two ABIs to keep in step.

| Option | Meaning |
|---|---|
| `:callbacks` | a finite map from **exact** prop names to `:event` or `:handler`; never inferred from an `on*` name |
| `:children` | **required** — `:none` / `:optional` / `:required`, the same roster an internal boundary uses |
| `:ssr` | **required** — `:client-only`, or `{:fallback <markup>}` when there is honest server content |
| `:map-props` | optional: one adapter over the whole ordinary plane, browser-only |
| `:props` | optional props-contract evidence |

`:children` and `:ssr` carry **no default**, and the roster is closed. Freehand
never executes the registered component on the JVM, so a default would be the
substrate choosing a server behaviour silently.

Three planes cross, and they stay disjoint:

- **Ordinary props pass shallowly and exactly.** `:selected` reaches React as
  `selected` — no camelisation, no deep Clojure-to-JavaScript walk, no per-prop
  conversion language. A function in an ordinary slot is refused.
- **Declared callbacks** take the carrier their role names, and get everything a
  roster site gets: stable identity per site, the latest committed body and frame,
  silence for abandoned renders, retirement after unmount. A bare event vector at a
  foreign callback position is refused rather than converted — the library may
  itself want a vector there.
- **Children** become ordinary React children in the registered component's own
  tree, under the declared policy.

A structural render emits an honest marker rather than the React implementation:
`:rf.ui/host`, `:rf.ui/host-ssr`, `:rf.ui/host-children` (a **count** — the
caller's children cross into React's tree and are not walked here), and the
authored `:props`, each carrier recorded as its opaque role marker. `t/attrs`
reads that props map, so a crossing is assertable with no browser in sight.

Ordinary props pass **exactly**, so a caller's remainder is forwarded onto a
host head with an ordinary `merge` — never `v/spread` or `v/spread-safe`, which
are element forms and would rewrite `:className` into the `:class` slot on the
way through. Where the head goes, and the shape of that merge, is worked through
in [Composition](composition.md#worked-sketch--foreign-widget).

### A React element as a child

A finished React element is already an ordinary browser child value, so one can
enter a Freehand tree through the existing child fold without a declaration. This
is deliberately **weaker** than a declared host — reach for it when the element is
what you already hold:

```clojure
(ns app.booking
  (:require ["react" :as react]
            ["some-date-picker" :refer [DatePicker]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]))

(v/defview booking-date [{:keys [date]}]
  ;; The frame, held across the render scope. `rf/capture-frame` binds the ops
  ;; to the frame this view rendered in, so a callback the library calls later
  ;; dispatches into the right application.
  (let [{:keys [dispatch]} (rf/capture-frame)]
    (v/client-only
     {:fallback [:input {:type :date :value date :read-only true}]}
     [:div.booking-date
      (react/createElement
        DatePicker
        #js {:selected date
             :onChange (fn [d]
                         (dispatch [:booking/date-picked (from-js-date d)]))})])))
```

One shared React tree, with context propagation, `v/->react` content interleaved
back through it, and synchronous teardown.

- **The `#js` props are the library's own ABI, and Freehand never walks them.**
  A callback there is an ordinary closure — the escape roster
  (`v/event` / `v/handler` / …) belongs at positions Freehand OWNS: a native
  `:on-*` prop, a declared view's props, a declared host's `:callbacks`
  position, a `v/slot`. Hand a roster carrier to a `createElement` prop and the
  library receives a non-callable marker object, because nothing on this path
  materializes it.
- **Close over `rf/capture-frame`, not over `rf/dispatch`.** The render scope has
  unwound by the time the library calls back, so an ambient `rf/dispatch` raises
  `:rf.error/no-frame-context`. The captured bundle is fenced to the exact frame
  incarnation it was taken from, so a callback outliving its frame reports rather
  than dispatching into a same-id successor.
- **What you give up** relative to a roster site: Freehand promises nothing about
  the closure's identity (it is fresh per render, so a library that memoises on
  callback identity sees a changed prop), the closure is not retired with the
  view, and the crossing has no structural marker for a test or a tool to read.
  A declared host gives back all three. When identity is the problem specifically,
  own the node with a behavior and mint the callback once in `:connect` — see
  [Registered behaviors](#registered-behaviors) below.
- The JVM structural renderer accepts **no** React elements. The child path is
  browser-only, like the mount verbs, so wrap it in `v/client-only` if the root is
  ever server-rendered.
- A React component does not gain server semantics merely because it can create
  browser DOM.

### `v/client-only`

Marks a subtree that must not pretend to run on the server:

```clojure
(v/client-only
 {:fallback [:div.chart-placeholder "Chart loads in the browser"]}
 [chart-host {:spec spec}])
```

| Side | Behaviour |
|---|---|
| JVM / SSR | render the **fallback** (or inert policy) |
| Browser | mount the real host subtree after hydrate |
| Hydration | mismatch without a truthful fallback is an error |

Use for canvas charts, maps, and libraries with no honest server output.

### Registered behaviors

This is the one sanctioned way to own DOM or opaque host state, and it is bounded
to a single node. Use it for imperative libraries — Vega, Mapbox, a canvas editor —
not for “I wanted `useEffect`.”

Registration and use site are two halves:

```clojure
(v/defbehavior autosize
  "Grow the textarea to fit its content."
  {:timing     :layout
   ;; :connect ESTABLISHES the private memory — the observer it built. Nothing
   ;; else writes it: :update, :refit and :disconnect all just receive it.
   :connect    (fn [{:keys [node config]}] (observe! node config))
   :update     (fn [{:keys [node config]}] (fit! node config))
   :disconnect (fn [{:keys [memory]}] (some-> memory .disconnect))
   :commands   {:refit (fn [{:keys [node config]}] (fit! node config))}})

(v/defview composer [{:keys [draft]}]
  [v/behavior {:use    autosize
               :target :composer/body
               :config {:max-rows 8}}
   [:textarea.composer {:value draft :on-input [:composer/typed ::v/value]}]])
```

The var `autosize` holds the **registered id** — the qualified keyword
`:app.composer/autosize` — not the implementation. That split is what keeps a use
site data: the tree records an id, the registry holds the code, and nothing
serializable ever carries a function.

`v/behavior` is a **declared descriptor mounted in a vector head**, exactly like
`v/error-boundary` and `v/markup`. It is not an attribute on the element.

| Option | Meaning |
|---|---|
| `:use` | **required** — the registered behavior |
| `:target` | the caller-authored semantic id a command addresses; unique among live connections |
| `:config` | the public configuration, and **data at every depth** |

`:target` is derived from nothing — not render position, not a key path, not the
DOM — so a sort, a rename, a parent extraction or a virtualized remount does not
move it. `:config` refuses a callback, a node, a ref or a preconstructed host
instance on both hosts, because a configuration the structural tree cannot record
is a use site a test and a tool cannot read.

The child is exactly **one element**. A behavior owns one node, so a declared view,
a fragment, a presence boundary or bare text is refused rather than guessed at. The
behavior addresses the node the host hands it and can reach no other: there is no
selector, no document query, and no ref an application can hold.

The definition roster is closed:

| Entry | Meaning |
|---|---|
| `:timing` | closed at `:layout` (before paint — the only honest home for measure-then-place) and `:passive` (the default, after paint) |
| `:connect` | once, at the commit that mounts the node; its return **establishes** the connection's private memory |
| `:update` | only when the committed `:config` moves by `rf=`, receiving `:prev-config` alongside; return ignored |
| `:disconnect` | exactly once per committed connection, after release, so its context is inert |
| `:commands` | a finite roster of named operations |
| `:opaque` | the behavior owns the node's descendants, making Freehand children on that node an error |

**`:connect` establishes the memory; nothing else writes it.** `:update`, a
command and `:disconnect` receive the memory and their return values are
discarded — which is what makes the ordinary integration safe, because the
ordinary host mutator answers nothing at all (`map.setOptions(…)`,
`chart.update(spec)`, `addEventListener`) and a return that replaced the memory
would erase the instance `:disconnect` has to release. When host state genuinely
evolves, return a **mutable cell** from `:connect` — an atom, a volatile, a JS
object — and mutate it in place.

Every entry takes **one context map** — `:node`, `:config`, `:memory`,
`:behavior`, `:target`, `:generation`, and `:dispatch`, a generation-fenced
outward dispatch into the frame the connection committed under. There is no frame
*query* function, so a host can never read application state at a moment nobody
chose.

Connection is **commit-only**: the lifecycle rides a ref and an effect, both of
which React runs only for a render it selected, so a candidate the host abandons
performs no host work at all. Teardown is total — after the last unmount the
substrate holds no connection record, no target claim, no node and no memory.

On the JVM this is an inert marker: the boundary node records `:use`, `:target`
and `:config` with the decorated element as its child, and nothing connects.

A `{:compiled true}` view may attach a behavior and stay compiled — the analyzer
recognises `[v/behavior …]` as the framework boundary it is and lowers it to the
grammar's own node.

This is **not** a general `on-mount` / `on-unmount` callback API.

### Commands (one-shot host ops)

A behavior may register a finite roster of named operations — export, print,
focus-cell. Reaching one is an ordinary effect, so the *request* stays data and
the imperative call stays inside the behavior:

```clojure
(rf/reg-event :composer/refit-requested
  (fn [_ _]
    {:fx [[:re-frame.freehand.host/command
           {:target :composer/body :op :refit}]]}))
```

| Rule | Meaning |
|---|---|
| `:target` | the caller-authored semantic id from the use site — not a DOM path or “last mounted” |
| Timing | only the **currently committed** connection; never queued for a future mount |
| Outcome | the channel records `:delivered` or `:refused` in [`v/command-log`](debugging.md#the-behavior-tool-plane) |
| Return | **ignored** — a command returns no handle and does not replace the memory; a result the domain needs comes back as an event the behavior dispatches |
| Steady state | still flows through `:config` and `:update`; commands are the narrow one-shot escape |

### Reaching for React's own protocols

When the integration needs React's real protocol surface — hooks, context,
portals, ref merging, `asChild`, Suspense — write that component in React-world
and bring it in at one of the [inward shapes](#four-host-shapes) above. Freehand
does not emulate those in neutral Hiccup and does not intend to, and a component
that uses them internally is still an ordinary registered implementation behind a
declaration: that is exactly why there is one descriptor kind and not two.

Keep the component's **public props and outward intents** ordinary Freehand
values, so the structural tree still shows what crosses the boundary, and treat
its interior as opaque.

## Outward React bridge (`v/->react`)

Some React libraries demand a **component value** as a prop — a grid's
`cellRenderer`, a drag overlay, a virtual list's row component, a plugin slot.
Every other verb on the door points inward; this one points out.

```clojure
;; value props already suit the view's ABI
(def person-cell-react (v/->react person-cell))

;; a foreign parameter object gets one named projection
(defn cell-props [params]
  {:person-id (.. params -data -id)
   :column-id (.. params -column getColId)})

(def person-cell-mapped (v/->react person-cell {:map-props cell-props}))
```

What comes back mounts the descriptor exactly as an ordinary Freehand parent
would, so events, subscriptions, error identity and commit fencing inside the
exported subtree are the ones the view already had.

| Rule | Meaning |
|---|---|
| Input | a declared view **descriptor** only — a fn, a hiccup vector, an id keyword or a rendered form is refused |
| Options | closed at one key, `:map-props`; an unknown option is refused rather than ignored |
| Default props | every own enumerable property becomes a props entry **by exact name**, value untouched |
| `:map-props` | one explicit adapter: raw foreign object in, one props map out |
| `frame` | selects an already-live frame; **never creates** one |
| `children` | React's content slot, arriving as the boundary's trailing children |
| `ref` | **refused** — Freehand has no ref protocol |
| Identity | stable, keyed on the view id, so a hot reload republishes rather than remounting the library's subtree |
| JVM | absent, like the mount verbs — a component value has no meaning in a structural render |

**One shallow prop rule, or one explicit adapter.** Without `:map-props`,
`"person-id"` is `:person-id` and `"acme/id"` is `:acme/id`: no camelisation and no
deep walk. A library that hands over a large mutable parameter object supplies the
adapter instead — deliberately ordinary top-level code at the host edge, a named
and testable projection rather than a conversion rule the substrate would have to
pretend was general.

**Three names belong to the bridge**, and the view sees none of them: `frame`,
`children` and `ref`. Because `frame` is the bridge's name, a props map carrying
`:frame` is refused too.

**A frame is selected, never created.** Own-property *presence* decides, not
truthiness, so an explicit `frame={null}` is a stated target that fails rather than
falling through. With no `frame` prop the exported view resolves ambiently, exactly
as a view mounted anywhere else does.

## DOM top layer

Desired **open** state for platform popover/dialog — not presence, not portals:

```clojure
[:div {:popover :auto
       ::web/popover-open? open?
       :on-toggle
       (v/event [e]
         (conj on-open-change (= "open" (.-newState e))))}]

[:dialog {::web/modal-open? open?
          :on-cancel [:dialog/cancelled]}]
```

| Intrinsic | Constraint |
|---|---|
| `::web/popover-open?` | only with a valid `:popover` mode |
| `::web/modal-open?` | only on `<dialog>` → `showModal()` / `close()` |
| Non-modal dialog | platform `:open` attribute |

Browser dismissal never silently mutates app-db. Reconcile through intents.
Development reports a controlled top-layer node with no reconciliation handler.
**No neutral portal** in v1; React portals stay in wrappers. Timed exit after close
pairs with [Presence](presence.md). Positioning is CSS anchors or a behavior.

## Error boundaries

Render failures happen: nil assumptions, bad data, a foreign component throw,
malformed Hiccup. Freehand already guarantees that a **thrown render owns
nothing** — no half-published subscriptions or handlers. What the **user** sees
next and what **telemetry** may learn is the D019 contract below.

```clojure
[v/error-boundary
 {:reset-key route-revision
  :fallback  [broken-page {}]
  :on-error  [:telemetry/ui-render-failed]}
 [workspace-page {:workspace-id workspace-id}]]
```

| Prop | Role |
|---|---|
| **child region** | one region of UI protected by the boundary |
| **`:fallback`** | what to show instead — static structure, a declared view, or a pure `v/render-fn` of the safe summary |
| **`:reset-key`** | when this value changes, remount/retry the child (no imperative `reset!` ref) |
| **`:on-error`** | optional intent prefix; fires **once per failure generation** after the fallback commits |

There is no public “boundary handle” API. Recovery is data: change `:reset-key`
(often a route revision or a user “Try again” counter in app-db).

### What a boundary catches — and what it does not

| Failure | Boundary shows fallback? | Who owns reporting |
|---|---|---|
| Freehand child render throws | yes | boundary + frame error egress |
| Hiccup normalization / common validation throws | yes | same |
| Descendant React throw during render/lifecycle (where React boundaries apply) | yes (browser) | same Freehand boundary |
| **Fallback itself** throws | no at this boundary — propagates outward | parent boundary / frame |
| **re-frame event / sub / resource handler** throws | **no** | re-frame error path |
| **Async** timer / promise / DOM callback after the fact | **no** | browser / owning wrapper |
| Behavior `connect` / command after commit | not as render fallback by default | behavior diagnostics + frame egress |
| SSR transport outside view evaluation | no | server / root host |

**Critical:** a Freehand error boundary is a **render** safety net. It will not
save you from a bad event handler or a failed HTTP effect. Those stay causal-layer
errors. Do not wrap the whole app and assume “all failures become fallback UI.”

### Safe summary vs host detail (two channels)

**Application intent (`:on-error`).** When you supply `:on-error`, Freehand
dispatches **at most one** event per captured failure generation. The payload is
a **safe summary**: stable ids, view ids, phase, correlation facts, evidence
completeness — **not** raw props, full app-db, exception objects, or host nodes.
Use that event for a toast, a redacted product log, or serializable analytics.

**Frame error egress (production detail).** In parallel, the host may promote a
**private** record onto the existing Spec 009 / frame error path: safe summary
plus capped host/React stack for the configured observer. Redaction, transport,
and vendor integration live there.

Defaults:

- Freehand does **not** capture all of app-db or recent event payloads.  
- Snapshots are **opt-in** and allow-listed if you need them.  
- Development evidence is richer; production may be thinner — completeness fields
  must say so.  
- Epoch identity in production reporting stays careful (ordinal / correlation, not
  “dump the world”).  

### Lifecycle of a failure

1. Child render throws (or React reports a catchable render/lifecycle error).  
2. Candidate render publishes **nothing**.  
3. Boundary selects fallback UI.  
4. After fallback commits, optional `:on-error` fires **once** for this failure
   generation.  
5. User or app changes `:reset-key` → child remounts and retries.  
6. StrictMode / HMR must not spam the same generation as many reports.  

When reporting from production, the useful pair is often **where** (view id /
occurrence / correlation from the safe summary) and **what** (redacted snapshot
or release id you opted into). Do not rely on full event history in production
builds.

On the server, do not pretend a client recovery commit happened mid-stream. Use
the Root Descriptor’s **server error projection** — see [SSR](ssr.md).

## Focus, autofocus, and measurement (no neutral refs)

Freehand has **no** `v/ref`, `useEffect`, or “run this on mount” form in ordinary
views. Host ephemera stay at explicit boundaries.

| Need | Prefer first | Escalate to |
|---|---|---|
| Focus a field when it appears | native `:auto-focus true` on the input | — |
| Focus after a **semantic** open (dialog, rename) | top-layer / open state + CSS or browser dialog focus | small **behavior** that `.focus()`s on connect |
| Measure layout / position before paint | CSS anchors where possible | registered behavior with `:layout` timing |
| Scroll lock, trap, restore focus | native dialog patterns | a React focus library, entered as a child |
| Third-party “needs a ref callback” | the registered component behind a declared host owns the ref | never a bare ref prop on a Freehand view as app state |

```clojure
;; Often enough — platform autofocus when the node mounts with the tree
[:input {:value (v/sub [:rename/draft])
         :auto-focus true
         :on-input [:rename/drafted ::v/value]}]

;; When you must call .focus() after connect — an ordinary registered behavior
(v/defbehavior focus-on-connect
  {:connect (fn [{:keys [node]}] (.focus node) nil)})

(v/defview rename-field [{:keys [draft]}]
  [v/behavior {:use focus-on-connect}
   [:input {:value draft
            :on-input [:rename/drafted ::v/value]}]])
```

`:timing` defaults to `:passive`; reach for `:layout` only when the work is
measure-then-place and must land before the browser paints.

Do not store DOM nodes in app-db. Do not invent mount-domain events for “I
focused.” If focus is product-visible (which field is active), that is a re-frame
fact driven by events — the DOM call is still host-side. Behaviors are **not** a
general on-mount API; prefer `:auto-focus` unless you need an imperative call.

## Choosing a shape

| Need | Shape |
|---|---|
| DatePicker value + callback | a declared host (`v/defhost`), with the callback at a position the declaration names |
| A React element you already hold | a child position; the callback is a closure over `rf/capture-frame` |
| Vega/Mapbox owns a DOM node | registered behavior |
| Radix / hooks / portals | a React component of your own, entered at an inward shape above |
| Grid wants a React component prop | `v/->react` |
| Render failure UI | `v/error-boundary` (above) |
| Exit animation after close | presence (+ top-layer for open) |
| Framer / GSAP / other JS libs | a React child or a registered behavior — see [JS libraries](js-libraries.md) |
| Autofocus / measure / `.focus()` | the native attribute first; a registered behavior when you truly need the call |
| Structure, spreads, theming | not host shapes — composition plane |

## Troubleshooting

| Symptom | Fix |
|---|---|
| Bare React component at a vector head | declare it with `v/defhost` and mount the descriptor — or create the element and put it in a **child** position |
| Instance / DOM node in app-db | keep private memory in the behavior; config is data only |
| Command “for later when it mounts” | commands hit the **live** connection only — no queue |
| Error boundary never resets | change `:reset-key` — no imperative reset handle |
| Telemetry spam on StrictMode | once-per-failure-generation for `:on-error` |