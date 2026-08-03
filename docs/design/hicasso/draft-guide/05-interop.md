# Interop

> **Draft ahead of the product artefact.** This page teaches the landed surface —
> ruled in [decisions.md](../decisions.md) (HD-001…HD-028). `defhost`'s door is
> witnessed end-to-end by
> `implementation/freehand/test/re_frame/bench/hicasso/arm1/host_hatch_dom_cljs_test.cljs`:
> one foreign React component with its own state, its own effects, its own context
> read and three different invoker styles on its callbacks, declared once and
> driven from a Hicasso tree. No `implementation/hicasso/` artefact ships yet,
> though, and spellings marked **[unfrozen]** stay provisional until the API
> freeze. Three things this page describes are ruled but **not built** — the `[:>]`
> raw escape, the migration codemod and SSR — and one default, deep prop
> conversion, is deliberately not offered at all. See **Not settled yet**.

You want a date picker from npm. In Reagent you write `[:> DatePicker {...}]` and
move on. Hicasso asks you to write one line first:

```clojure
(ns app.hosts.date-picker
  (:require [re-frame.hicasso :as h]
            ["react-datepicker" :default DatePicker]))

(h/defhost date-picker DatePicker
  {:callbacks {:on-change :event}})     ;; defhost is [unfrozen]
```

Then use it anywhere a view is legal — including as another view's child, since
children cross as ordinary hiccup and are lowered by whoever renders them:

```clojure
(ns app.views
  (:require [re-frame.hicasso :as h :refer [defview sub]]
            [app.hosts.date-picker :refer [date-picker]]))

(defview due-field [_]
  [date-picker {:selected  (sub [:task/due-date])
                ;; react-datepicker calls onChange(date) — the date first, and
                ;; no event at argument one. An intent vector refuses there;
                ;; h/fn takes the library's own arguments, in order.
                :on-change (h/fn [date & _] [:task/set-due date])}])
```

## Why a declaration instead of `[:>]`

HD-011's Ruling settles the hierarchy in a sentence: **`defhost` is the door, and the
only form taught**; `[:>]` "survives only as the explicitly secondary raw escape …
for the cases a static declaration cannot express." Both forms are ruled legal.
Only one is built, and only one is taught. This page is written in that order for
that reason.

`[:> DatePicker {...}]` puts a raw JavaScript value inside your data tree. Three
things break at that node, all of them quietly:

- **`.cljc` purity.** The namespace can no longer load on the JVM, so it can no
  longer be tested headlessly.
- **Structural testing.** The node holds an opaque JS object, so you can't assert
  the tree with `=`.
- **Tooling identity.** There is nothing for a tool to name — the crossing has no
  identity, just a value.

`defhost` gives the crossing a name, keeps the JS require quarantined in one `.cljs`
host namespace, and leaves the rest of your view tree pure data.

The honest counter-argument is that a declaration is friction, and the counter to
*that* is arithmetic: it amortizes to zero over every use site. HD-011's rationale
leans on a migration codemod as well, and that half has not been built, so today
the conversion is by hand. It is a small hand: the expensive part of leaving
Reagent was never `[:>]`, it was `r/atom`.

## The defaults

`defhost` with no options gives you:

| Default | Behaviour |
|---|---|
| Props | shallow camelCase conversion — `:on-change` becomes `onChange` |
| Children | hiccup children are converted to React elements |
| Functions | pass through unconverted |
| SSR | a placeholder (declared policy; inert in v0, since SSR is out of v0 per HD-020) |

Policy overrides live on the declaration rather than at the call site, which is the
whole point: one place decides how this component is crossed, and every use site
inherits it. There is exactly one option today — `:callbacks`, a finite map from
prop name to `:event`, `:handler` or `:render`, and the subject of the next
section.

Everything a declaration can get wrong, it gets wrong *at the declaration*, where
your stack trace points at the line you wrote: a contract that is not one of the
three, a contract on `:key` or `:ref` (React's, not positions), two spellings of
one prop declared twice, and a component that resolved to `nil` — the classic
`:default` against a library that has no default export, which React would
otherwise report from somewhere else entirely.

Deep conversion — camelCasing keys *inside* a nested option map — is deliberately
not offered at any level. Guessing which nested maps are options and which are data
is the support burden the shallow default exists to delete; convert the map yourself
when a library wants camelCase inside it.

## Callbacks: `:event`, `:handler` and `:render`

A declared slot in `:callbacks` carries a contract, never inferred from an `on*`
spelling:

```clojure
(h/defhost picker Widget
  {:callbacks {:on-pick       :event
               :on-imperative :handler
               :on-render-row :render}})
```

**`:event`** is the door's version of an ordinary `:on-*` position — an intent
vector, or an `h/fn` when you need to read what the library handed you. The proof
pins the detail a native position doesn't have to: a foreign invoker calls with
*its own* arguments, not a lone DOM event — `onPick(value, event)`,
`onDraft(event)` — and **every argument the invoker passed reaches the `h/fn`
body**, in the order the library sent them.

**`:handler`** crosses the function by identity rather than wrapping it: the
library gets exactly the function you wrote — so a library that memoises on
callback identity is not defeated — and whatever the library's own call returns
goes back to *that caller*; Hicasso never sees the return and never dispatches
from it. This is the shape for a library's imperative surface (`open()`,
`scrollTo()`): `defhost` doesn't know what an arbitrary imperative call means, so
`:handler` stays out of its way entirely.

**`:render`** is the third contract, for a library that calls you back *during its
own render* — `renderRow`, `renderItem`, a render prop. The body must be pure: its
return is what the library puts in its tree, and dispatching from inside it raises
`:rf.error/hicasso-dispatch-in-render-position`, naming the position.

Read "from inside it" exactly. **The row you build may carry ordinary intents.** A
`renderRow` that returns `[:li {:on-click [:row/pick id]} title]` is the whole
point of a render prop, and that handler fires later, at the user's click, into
the frame of the boundary that supplied the callback — the only frame that could
own it, since the foreign component has none. What is forbidden is dispatching
*while the call is running*, whether directly or by lowering a handler and firing
it synchronously in the same body. The distinction is the law's own: pure while
you are building the row, ordinary once you have handed it over.

### The declaration decides; the value never overrules it

The contract you declared governs **every** carrier at that position, not just the
`h/fn`. An intent vector and a key-map are each a dispatch and nothing else, so
they are accepted at `:event` — where dispatching is what the contract means — and
**refused** at `:handler` and `:render` with
`:rf.error/hicasso-intent-at-a-non-event-contract`. There is no reading of a bare
vector that a `:handler` could honour, and a `:render` position that dispatched
would be dispatching mid-render. If what happens at that slot is an event, declare
it `:event`; otherwise write an `h/fn` that does the work. An ordinary unmarked
function crosses untouched at all three.

### The vector spelling is event-first

`::h/prevent`, `::h/value`, `::h/checked` and a key-map's key lookup all read the
DOM event, and they all read it from **argument one**. That is what every native
position hands them, and what an event-first foreign contract (`onDraft(event)`)
hands them too.

A value-first invoker — `onPick(value, event)`, or the date picker's
`onChange(date)` — has no event at argument one, and nothing can guess which of a
library's arguments is one. Hicasso says so rather than letting the engine say it:
you get `:rf.error/hicasso-intent-needs-the-event`, naming the position and the
argument that actually arrived, instead of `value.preventDefault is not a
function`. **`h/fn` is the spelling for those positions** — it receives every
argument the invoker passed, in order, which is exactly what the opening example
does.

Both halves are witnessed at a real crossing, which is what settles the question
this page used to hedge. The host-hatch suite drives `::h/value` through a widget's
event-first `onChange` and the model updates; it then drives `::h/prevent`,
`::h/value` and a key-map through the *same* widget's value-first `onPick` and gets
the refusal instead, naming `:on-pick` and the string that actually arrived.

An intent carrying neither a marker nor `::h/prevent` never touches its argument,
so `{:on-pick [:city/picked "paris"]}` is correct under any invoker contract at
all.

## Providers cross the door too

A provider an ecosystem library hands you is hosted like anything else — it's a
component, not a special case:

```clojure
(h/defhost themed (.-Provider some-context))
```

A consumer reading the same context below the crossing reads it correctly: the
crossing is a real React element, so React's own context plumbing runs through
the Hicasso tree exactly as it would through any other one.

## When a boundary bails out, the host inside it does too

Every Hicasso boundary is a `React.memo` comparing its props by value (HD-028), and
that bail-out reaches a hosted component exactly as it reaches a native one:

```clojure
(defview chrome-page [_]
  [:div
   [:span.chrome (str (sub [:hatch/label]))]   ;; re-renders on every write
   [hosted-row {:label "fixed"}]])             ;; value-equal props — bails out
```

A write that only moves `:hatch/label` re-renders `.chrome` and stops there.
`hosted-row`'s boundary receives the same `{:label "fixed"}` it always does, the
comparator holds, the boundary bails out, and the foreign component inside it is
not re-rendered at all — not once, not with stale props, simply never re-entered.
That is the memo working exactly as designed: nothing in `hosted-row`'s own props
moved. It is also exactly the shape that sends someone hunting a bug in the
third-party library they are hosting, when the library never got a render to be
wrong in. If a host should react to a subscription, that subscription's value has
to reach *its own* boundary's props — reading it one component further up and
stopping there is indistinguishable, from the host's side, from the value never
having changed.

## The escape: `[:>]` is legal

`[:> Component props & children]` is HD-011's explicitly secondary form — ruled
legal, not merely tolerated, for the cases a static declaration cannot express.
It stays **unbuilt in the bench arm**, deliberately: the census found zero
raw-escape sites to build against, so the door's proof took the v0 budget instead.
When it lands, the design is that it lowers through the same foreign path with the
same default conversions, `.cljs`-only at that node, with reduced structural
identity — exactly the costs listed above, accepted knowingly.

Reach for it, once it exists, when a static declaration genuinely cannot express
the case:

- the component is **selected at runtime** from a map or a prop;
- it is a `memo` or `lazy` value;
- it arrives from a **render prop**;
- it is a **one-off migration site** you have not got to yet.

**The rule, once it exists, is: declare what you use twice.** First use, take the
escape if it's faster. Second use, write the `defhost`. That is not a moral
position; it is where the amortization crosses over.

One thing stays rejected: **bare-head auto-hosting**. Putting a raw JS component in
head position — `[DatePicker {...}]` — and having the runtime identity-key it is
*not* legal. One sentinel, not two shortcuts. If `[:>]` and a bare head both worked,
every codebase would have both, and the reader would have to know which node is
which by looking somewhere else.

## Migrating from Reagent

HD-011 rules a codemod for the mechanical part — collect the `[:> X …]` sites, emit
the `defhost` block, rewrite the call sites — but nothing has been built, and the
record names neither a tool nor an invocation. By hand it is the same three moves,
and it goes a namespace at a time, so a large app never needs one big commit.

## Imperative SDKs

A mapping SDK, a chart library that wants a DOM node, anything that hands you a
handle and expects you to feed it — that is **not** an interop-door problem. It is
ordinary host-edge React (HD-003): a callback ref, written in a `.cljs` namespace at
the edge of your app.

There is no Hicasso concept for this, deliberately. "Behaviors" — a predecessor
product concept for imperative host ownership — is **not v0 vocabulary**. A richer
host-ownership pattern is a product-phase question; the v0 answer is React, used
honestly, at the edge.

Used honestly means **the attach and the teardown are written as one thing.** An SDK
that mounts and never tears down is the most common bug on this seam, and it is not
subtle — you get two maps, two resize observers, and a leak per remount. React 19's
callback ref makes the pairing structural: **whatever the ref function returns is its
cleanup**, so you cannot write the attach without deciding what undoes it.

```clojure
;; Sketch only — host-edge React inside a defview body, in a .cljs namespace.
(ns app.hosts.map-panel
  (:require [re-frame.hicasso :as h :refer [defview]]
            ["react" :as react]
            ["some-map-sdk" :as sdk]))

(defview map-panel [_]
  (let [attach (react/useCallback
                 (fn [node]
                   ;; One attach, one handle, one teardown. The handle lives in
                   ;; this closure and nowhere else.
                   (let [handle (sdk/mount node)
                         done?  (volatile! false)]
                     (fn cleanup []
                       (when-not @done?
                         (vreset! done? true)
                         (sdk/destroy handle)))))
                 #js [])]                    ;; stable identity — see below
    [:div.map {:ref attach}]))
```

Four properties, and each one is load-bearing.

**The handle is per-attach, not per-namespace.** It is created by the attach and
captured by the cleanup that attach returned. Two attaches produce two handles and
two cleanups, correctly paired. The moment you hoist that handle into a `defonce`
atom or a module-level var, a second attach overwrites the first and the first
instance leaks — which is the actual mechanism behind every "it mounted twice" report
on this seam.

**The teardown is idempotent.** The `done?` latch makes a second call a no-op. React
calls each cleanup exactly once, so strictly this is belt and braces — but SDK
`destroy` methods that throw on a dead handle are common enough that the three lines
are worth it, and the alternative is finding out in production. This is the same
idempotence the root teardown has ([Getting started](01-getting-started.md)); the
discipline does not change because the object is foreign.

**Returning a cleanup and handling a `nil` node are exclusive.** When a ref callback
returns a function, React calls that function on detach *instead of* invoking the
callback again with `nil`. Write both and you will have written a `nil` branch that
never runs — a dead path that reads like a teardown and is not one. Pick the return.

**`useCallback` with an empty dependency array is what makes any of this
deterministic.** A ref function with a fresh identity every render is detached and
re-attached on every render, so the SDK is destroyed and rebuilt on every keystroke
elsewhere in the tree. The stable identity is the difference between one mount and
one mount per render.

### Under StrictMode

StrictMode double-invokes the mount in development: attach, cleanup, attach. The
shape above survives it, and the reason is the first property, not the second. The
first attach's handle is destroyed by the cleanup that attach returned, and the
second attach builds a fresh one. You end with exactly one live handle, which is what
you would have had in production.

What does *not* survive is a handle stored outside the closure, or a cleanup that
tears down less than the attach built — a listener left on `window`, an observer left
connected. Then the second attach genuinely doubles, and the symptom is two of
everything in dev and one leak per remount in production. **The rule is that the
cleanup returns the world to the state the attach found it in**; there is no runtime
that can check that for you.

### If the runtime does not target React 19

The cleanup-returning ref is a React 19 contract. The record does not pin a React
version (see **Not settled yet**), so the older shape is worth knowing: the callback
is invoked with the node on attach and with `nil` on detach, and the handle needs a
home that outlives one invocation.

```clojure
(let [handle (react/useRef nil)
      attach (react/useCallback
               (fn [node]
                 (if node
                   (set! (.-current handle) (sdk/mount node))
                   (when-let [h (.-current handle)]
                     (set! (.-current handle) nil)   ;; clear first — idempotent
                     (sdk/destroy h))))
               #js [])]
  [:div.map {:ref attach}])
```

Same discipline, one more moving part. That the handle now needs a home outside the
closure is exactly what the React 19 contract removes, which is why it is the shape
taught above.

Note the two consequences of putting hooks in a body at all: it is outside the
headless testing scope ([Testing](08-testing.md)), and you have taken on React's hook
rules yourself. Both are fine. Both are on you.

### The reserved vector, and the gap it does not close

One thing about `:ref` is worth knowing before you write your first one, because it
changes what you should put in the closure.

**A vector at `:ref` is refused.** `{:ref [::autosize {:max-rows 8}]}` raises
`:rf.error/hicasso-ref-vector-reserved`. That value-space is reserved for a later
data spelling of exactly the pattern above — a registered id and a config map,
with the imperative code in a registry instead of in your view — and v0 claims it
now so that landing it later is not a breaking change. Today, write the function.

The refusal is on the ref **slot**, so it holds however you spell the key —
`{"ref" […]}` and `{:x/ref […]}` are refused too, and name the spelling you wrote.
An unrefused one would have been the worst of both: React ignores an array at
`ref`, in silence, so you would be debugging a ref that never fires.

**And the honest limit on what that later spelling could ever be.** A React ref
callback fires on **attach** and on **detach**. It does **not** fire on **config
change**. There is no third call, and no amount of design gets one: passing a
*different* callback is the only way to make React invoke it again, and doing that
detaches and re-attaches the node — which destroys and rebuilds your map instance,
which is precisely the thing you were trying to avoid.

So the shape to write, now and later, is:

- **attach and detach only**, in one closure, as above;
- **config immutable for the connection's life** — whatever the SDK needs at
  `mount` time is decided once;
- **steady-state change routed through an effect**, dispatched as an ordinary
  event: `{:map/fly-to {:instance id :center [lat lng]}}`. That is already data,
  it is already in the event log, and it is already testable.

If you find yourself wanting the ref to notice that a prop changed, that is the
signal to move the change onto the effect path. Nothing in the reserved vector
will rescue it later.

## Troubleshooting

This table names mechanisms; the minted ids on this surface —
`:rf.error/hicasso-ref-vector-reserved`,
`:rf.error/hicasso-intent-at-a-non-event-contract` and
`:rf.error/hicasso-intent-needs-the-event` — are covered above.

| Symptom | What went wrong | Fix |
|---|---|---|
| Prop arrives as `on-change` and the library ignores it | Nested keys expected in camelCase; deep conversion is deliberately not offered | Convert the nested map yourself before it crosses |
| An intent vector or key-map at a declared slot raises `:rf.error/hicasso-intent-at-a-non-event-contract` | The slot is declared `:handler` or `:render`, and neither contract dispatches — the declaration governs the carrier, so the value cannot overrule it | Declare the slot `:event` if what happens there is an event, or write an `h/fn` that does the `:handler`/`:render` work |
| `:rf.error/hicasso-intent-needs-the-event`, naming a slot whose library hands a value first | The vector spelling reads the DOM event from argument one, and this invoker put something else there | Write an `h/fn` at that slot — it gets every argument the library passed, in order |
| A namespace stops loading on the JVM | A JS require reached a `.cljc` file | Quarantine the require in a `.cljs` host namespace |
| Structural test can't match a node | A `[:>]` node, once the escape is built — reduced structural identity, by design | Declare it with `defhost`, or assert around it |
| Provider from a library needs to wrap the tree | Hosted like anything else | `defhost` the provider; it is a component |
| A hosted component doesn't update when the page clearly changed | The boundary above it bailed out on value-equal props (HD-028) — it was never re-entered, so the host was never asked to render | Trace the value to the host's *own* boundary's props, not a parent's |
| The SDK mounts twice in dev and you end with two live handles | StrictMode double-invoke, plus a handle stored outside the attach's closure — so the second attach overwrote the first instead of replacing it | One attach, one handle, one cleanup, all in one closure; return the cleanup from the ref |
| The SDK is destroyed and rebuilt on every unrelated render | The ref function has a fresh identity each render, so React detaches and re-attaches | `useCallback` with `#js []` — a stable ref identity |
| A `nil`-node branch in a ref never runs | The callback returns a cleanup, so React calls that instead of re-invoking with `nil` | Pick one contract; with React 19, pick the return |
| A listener or observer survives unmount | The cleanup tears down less than the attach built | The cleanup returns the world to the state the attach found it in — nothing checks this for you |

## When not to reach for the door

If the library is a thin wrapper over DOM you could write yourself in twenty lines
of hiccup, write the twenty lines. Every hosted component is a node your tools can
see less of and your tests can assert less about. The census found **zero** foreign
components across 85 idiomatic files, which is why this is an escape hatch and not
tier-1 syntax — most applications never need it, and the ones that do need it for
two or three genuinely hard widgets.

## Not settled yet

| Question | Status |
|---|---|
| Whether `defhost` grows a second option beyond `:callbacks` | **Not addressed.** HD-011 names strong defaults and "policy overrides on the declaration", and `:callbacks` is the only override the landed door carries. Whether the SSR placeholder or the conversion defaults ever become declarable is unstated |
| The migration codemod | **Ruled, unbuilt.** HD-011's rationale leans on it, but nothing has been built and the record names neither a tool nor an invocation |
| When the reserved `{:ref [id config]}` spelling lands, and what registers an id | **Reserved, not designed.** HD-022 rules the refusal and the value-space; the registry, the timings and the commands roster are explicitly out of v0 |
| Which React version the runtime targets | **Not addressed by the design record.** The cleanup-returning callback ref taught above is a React 19 contract; this repo's implementation currently pins React 19.2, but that is a fact about today's tree, not a Hicasso ruling. If v0 lands on 18, the fallback shape above is the one to teach |
| The SSR placeholder's shape | Declared policy, inert in v0 |
| Embedding Hicasso *inside* a React-primary app | Named in the charter's use-case roster (item 11); no surface designed |
