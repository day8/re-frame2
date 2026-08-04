# Interop

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

You want a date picker from npm. Declare it once, then use it anywhere a view is
legal:

```clojure
;; cf. implementation/freehand/test/re_frame/bench/hicasso/arm1/
;;     host_hatch_dom_cljs_test.cljs — the witness for every claim on this page.
(ns app.hosts.date-picker
  (:require [re-frame.hicasso :as h]
            ["react-datepicker" :default DatePicker]))

(h/defhost date-picker DatePicker
  {:callbacks {:on-change :event}})     ;; defhost is [unfrozen]
```

```clojure
(ns app.views
  (:require [re-frame.hicasso :as h :refer [defview sub]]
            [app.hosts.date-picker :refer [date-picker]]))

(defview due-field [_]
  [date-picker {:selected  (sub [:task/due-date])
                ;; react-datepicker calls onChange(date) — value first, no DOM
                ;; event at argument one. An intent vector refuses there; h/fn
                ;; takes the library's arguments in order.
                :on-change (h/fn [date & _] [:task/set-due date])}])
```

> **Declare the host once. Use it like any other view head.**

Children cross as ordinary hiccup and lower where they render — including as
another view's child.

## Why declare

Putting a raw JS component in the tree breaks three things at once:

- **JVM / `.cljc`.** A JS require in a shared namespace means the ns no longer loads
  on the JVM, so headless tests die with it.
- **`=` tests.** The node holds an opaque JS object; structural equality stops
  working at that point.
- **Named crossing.** Tools and readers get an identity — a declaration — instead
  of a bare value.

`defhost` quarantines the require in one `.cljs` host namespace and leaves the rest
of the view tree as data. Friction once; free at every use site. The Reagent
`[:> …]` escape is secondary, **not built** yet, and covered briefly below.

## Defaults

`defhost` with no options:

| Default | Behaviour |
|---|---|
| Props | shallow camelCase — `:on-change` → `onChange` |
| Children | hiccup → React elements |
| Functions | pass through unconverted |
| SSR | `:client-only` — nothing server-side until the client adopts. Override with `:ssr`: `:client-only`, or `{:fallback <hiccup>}` for placeholder markup. Bad policy → `:rf.error/hicasso-host-bad-ssr-policy` at mint. Full story: [Server-side rendering](10-server-side-rendering.md) |

Two options today: `:callbacks` (next section) and `:ssr`. An unknown option is
refused at mint (`:rf.error/hicasso-host-unknown-option`), not ignored. Bad
contracts, contracts on `:key`/`:ref`, duplicate prop spellings, and a component
that resolved to `nil` (classic `:default` against a library with no default
export) also fail at the declaration — where your stack points at the line you
wrote.

**No deep conversion.** Nested option maps are not camelCased for you. Guessing
which nested maps are options and which are data is a support trap; convert those
maps yourself when the library wants camelCase inside them.

## Callbacks: `:event`, `:handler`, `:render`

A declared slot carries a contract — never inferred from an `on*` name:

```clojure
(h/defhost picker Widget
  {:callbacks {:on-pick       :event
               :on-imperative :handler
               :on-render-row :render}})
```

**`:event`** — like a native `:on-*`: intent vector, or `h/fn` when you need the
library's arguments. Foreign invokers pass *their* arguments (`onPick(value,
event)`, `onDraft(event)`), and **every argument reaches the `h/fn` body** in
library order.

**`:handler`** — function by identity, no wrap. The library gets exactly the
function you wrote (memoisation on callback identity still works), and the
library's return goes back to that caller. Use this for imperative APIs
(`open()`, `scrollTo()`): Hicasso does not invent meaning for those calls.

**`:render`** — library calls you *during its own render* (`renderRow`,
`renderItem`). The body must be pure: its return is what the library puts in its
tree. Dispatching *while that call runs* raises
`:rf.error/hicasso-dispatch-in-render-position`, naming the position. The row you
build may still carry ordinary intents — `[:li {:on-click [:row/pick id]} title]`
is fine; those fire later, on the user's click, under the frame of the boundary
that supplied the callback.

### The declaration wins

The contract governs **every** carrier at that slot, not only `h/fn`. An intent
vector or key-map is accepted at `:event` and **refused** at `:handler` and
`:render` with `:rf.error/hicasso-intent-at-a-non-event-contract`. An ordinary
unmarked function crosses untouched at all three.

### Intent vectors are event-first

`::h/prevent`, `::h/value`, `::h/checked`, and key-map lookups all read the DOM
event from **argument one**. Value-first invokers — `onPick(value, event)`,
`onChange(date)` — have no event there, so you get
`:rf.error/hicasso-intent-needs-the-event` (naming the position and what arrived)
instead of `value.preventDefault is not a function`. **`h/fn` is the spelling for
those slots.**

An intent with neither a marker nor `::h/prevent` never touches its arguments, so
`{:on-pick [:city/picked "paris"]}` is fine under any invoker shape.

## Providers

A library provider is just a component:

```clojure
(h/defhost themed (.-Provider some-context))
```

Consumers below the crossing read context correctly — the node is a real React
element, so React's plumbing runs through.

## Memo and hosted components

Every Hicasso boundary is a `React.memo` that compares props by value. That
bail-out reaches a hosted component the same way it reaches a native one:

```clojure
(defview chrome-page [_]
  [:div
   [:span.chrome (str (sub [:hatch/label]))]   ;; re-renders on every write
   [hosted-row {:label "fixed"}]])             ;; equal props → bail out
```

A write that only moves `:hatch/label` re-renders `.chrome` and stops.
`hosted-row` never re-enters the foreign component. If a host should react to a
subscription, that value has to reach **its own** props — reading one boundary up
and stopping looks identical, from the host's side, to the value never changing.

## The escape: `[:>]` (unbuilt)

`[:> Component props & children]` is the secondary form for cases a static
declaration cannot express. It is **not built** — there is nothing to call today.
When it exists, it will use the same foreign path and accept the costs in
[Why declare](#why-declare).

Reach for it (once it exists) when the component is selected at runtime, is a
`memo`/`lazy` value, arrives from a render prop, or is a one-off migration site.
**Declare what you use twice.** First use, escape if faster; second use, `defhost`.

**Bare-head auto-hosting stays illegal.** `[DatePicker {…}]` with a raw JS
component in head position is not a shortcut. One sentinel when the escape ships —
not two ways to smuggle JS into the tree.

A hand migration from Reagent is three moves per namespace: collect `[:> X …]`,
emit `defhost`, rewrite call sites. A codemod is planned and not built.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Prop arrives as `on-change` and the library ignores it | Nested keys expected camelCase; deep conversion is not offered | Convert the nested map yourself before it crosses |
| `:rf.error/hicasso-intent-at-a-non-event-contract` | Slot is `:handler` or `:render`; neither dispatches | Declare `:event`, or write an `h/fn` for the real work |
| `:rf.error/hicasso-intent-needs-the-event` | Vector spelling needs the DOM event at arg one; library put something else there | Use `h/fn` — full argument list, library order |
| Namespace won't load on the JVM | A JS require reached a `.cljc` file | Quarantine the require in a `.cljs` host ns |
| Structural test can't match a node | A `[:>]` node (once built) has reduced structural identity | Prefer `defhost`, or assert around the node |
| Hosted component doesn't update when the page did | Boundary above bailed out on equal props — host never re-entered | Put the changing value on the host's *own* props |
| SDK mounts twice in dev | StrictMode double-invoke plus a handle stored outside the attach closure | One attach, one handle, one cleanup, all in one closure |
| SDK rebuilt on every unrelated render | Ref function has a fresh identity each render | `useCallback` with `#js []` |
| `nil` branch in a ref never runs | Callback returns a cleanup; React calls that instead of re-invoking with `nil` | Pick one contract (React 19: prefer the return) |
| Listener/observer survives unmount | Cleanup tears down less than attach built | Cleanup restores the world attach found |

## When not to host

If the library is a thin wrapper over DOM you can write in twenty lines of hiccup,
write the twenty lines. A hosted component is a node your tools see less of and
your tests assert less about. Most apps need this rarely — usually two or three
genuinely hard widgets.

## Advanced

### Imperative SDKs

A map SDK, a chart that wants a DOM node, anything that hands you a handle — that
is ordinary host-edge React, not a second interop API. Write a callback ref in a
`.cljs` namespace at the edge of the app.

**Attach and teardown are one thing.** React 19's callback ref makes the pairing
structural: whatever the ref function returns is its cleanup.

```clojure
;; Sketch — host-edge React inside a defview body, in a .cljs namespace.
(ns app.hosts.map-panel
  (:require [re-frame.hicasso :as h :refer [defview]]
            ["react" :as react]
            ["some-map-sdk" :as sdk]))

(defview map-panel [_]
  (let [attach (react/useCallback
                 (fn [node]
                   (let [handle (sdk/mount node)
                         done?  (volatile! false)]
                     (fn cleanup []
                       (when-not @done?
                         (vreset! done? true)
                         (sdk/destroy handle)))))
                 #js [])]
    [:div.map {:ref attach}]))
```

Four properties that each break something specific if dropped:

1. **Handle is per-attach**, captured by the cleanup that attach returned — not a
   module-level `defonce`. A hoisted handle lets the second attach overwrite the
   first and leak it (the usual "it mounted twice" mechanism).
2. **Teardown is idempotent.** The `done?` latch is cheap insurance against SDK
   `destroy` methods that throw on a dead handle.
3. **Cleanup return and `nil`-node handling are exclusive.** If the ref returns a
   function, React calls that on detach *instead of* invoking the callback with
   `nil`. Write both and the `nil` branch is dead.
4. **`useCallback` with `#js []` keeps the ref identity stable.** A fresh function
   every render detaches and re-attaches every time, so the SDK rebuilds on every
   keystroke elsewhere in the tree.

Under StrictMode (dev), React does attach → cleanup → attach. The shape above
survives because the first handle dies with the cleanup that created it. A handle
stored outside the closure, or a cleanup that leaves a listener on `window`, is
what actually doubles.

Hooks in a body take you outside headless testing scope ([Testing](08-testing.md))
and put React's hook rules on you. Both are fine; both are yours.

#### If you are not on React 19

Cleanup-returning refs are a React 19 contract. Older shape: callback with the
node on attach and `nil` on detach, handle in a ref that outlives one invocation:

```clojure
(let [handle (react/useRef nil)
      attach (react/useCallback
               (fn [node]
                 (if node
                   (set! (.-current handle) (sdk/mount node))
                   (when-let [h (.-current handle)]
                     (set! (.-current handle) nil)
                     (sdk/destroy h))))
               #js [])]
  [:div.map {:ref attach}])
```

### The reserved vector, and the gap it does not close

**A vector at `:ref` is refused.** `{:ref [::autosize {:max-rows 8}]}` raises
`:rf.error/hicasso-ref-vector-reserved`. That value-space is reserved for a later
data spelling (registered id + config); claiming it now keeps the later landing
non-breaking. Today, write the function. The refusal is on the **slot**, so
`{"ref" […]}` and `{:x/ref […]}` are refused too. An unrefused array would have
been worse: React ignores an array at `ref` in silence.

**Limit that later spelling cannot erase.** A ref callback fires on **attach** and
**detach** only — not on config change. Passing a different callback is the only
way to re-invoke it, and that detaches and re-attaches the node (rebuilds your
map). So:

- attach and detach only, in one closure;
- config fixed for the connection's life;
- steady-state change through an ordinary event / effect
  (`{:map/fly-to {:instance id :center [lat lng]}}`).

If you want the ref to "notice" a prop change, move the change onto the effect
path. Nothing in the reserved vector will rescue that later.

## Not settled yet

| Question | Status |
|---|---|
| Declarable conversion defaults on `defhost` | Open — `:callbacks` and `:ssr` are the two options today |
| Migration codemod | Planned, unbuilt |
| When `{:ref [id config]}` lands, and what registers an id | Reserved, not designed |
| Which React version the product pins | Not pinned by the product; cleanup-returning refs need React 19; this repo currently pins 19.2 |
| Embedding Hicasso inside a React-primary app | Named as a use case; no API designed |
