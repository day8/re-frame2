# Interop

> **Draft ahead of the product artefact.** This page teaches the ruled surface —
> [decisions.md](../decisions.md) (HD-001…HD-028) — and spellings marked
> **[unfrozen]** stay provisional until the API freeze. One honesty note specific
> to this page: `defhost` is ruled (HD-011) but is the one tier-1 door the bench
> arm has not yet exercised, so unlike its siblings this page still describes
> design rather than witnessed behaviour.

You want a date picker from npm. In Reagent you write `[:> DatePicker {...}]` and
move on. Hicasso asks you to write one line first:

```clojure
(ns app.hosts.date-picker
  (:require [re-frame.hicasso :as h]
            ["react-datepicker" :default DatePicker]))

(h/defhost date-picker DatePicker)     ;; defhost is [unfrozen]
```

Then use it anywhere, and it is indistinguishable from a native view:

```clojure
(ns app.views
  (:require [app.hosts.date-picker :refer [date-picker]]))

[date-picker {:selected  due-date
              :on-change [:task/set-due ::h/value]}]
```

## Why a declaration instead of `[:>]`

HD-011's Ruling settles the hierarchy in a sentence: **`defhost` is the door, and the
only form taught**; `[:>]` "survives only as the explicitly secondary raw escape …
for the cases a static declaration cannot express." Both forms are legal. Only one is
taught. This page is written in that order for that reason.

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
*that* is arithmetic: it amortizes to zero over every use site, and a codemod exists
for migration. The expensive half of leaving Reagent was never `[:>]` — it was
`r/atom`.

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
inherits it. The option keys are not in the record; see **Not settled yet**.

## The escape: `[:>]` is legal

`[:> Component props & children]` lowers through the same foreign path with the same
default conversions. It is `.cljs`-only at that node and has reduced structural
identity — exactly the costs listed above, accepted knowingly.

Use it when a static declaration genuinely cannot express the case:

- the component is **selected at runtime** from a map or a prop;
- it is a `memo` or `lazy` value;
- it arrives from a **render prop**;
- it is a **provider an ecosystem library hands you**, not one you named;
- it is a **one-off migration site** you have not got to yet.

**The rule is: declare what you use twice.** First use, take the escape if it's
faster. Second use, write the `defhost`. That is not a moral position; it is where
the amortization crosses over.

One thing stays rejected: **bare-head auto-hosting**. Putting a raw JS component in
head position — `[DatePicker {...}]` — and having the runtime identity-key it is
*not* legal. One sentinel, not two shortcuts. If `[:>]` and a bare head both worked,
every codebase would have both, and the reader would have to know which node is
which by looking somewhere else.

## Migrating from Reagent

A codemod handles the mechanical part: collect the `[:> X …]` sites, emit the
`defhost` block, rewrite the call sites. It runs incrementally, so a large app
converts a namespace at a time rather than in one commit. The codemod's name and
invocation are not in the record.

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

This table names mechanisms; the one minted id on this surface —
`:rf.error/hicasso-ref-vector-reserved` — is covered above.

| Symptom | What went wrong | Fix |
|---|---|---|
| Prop arrives as `on-change` and the library ignores it | Deep conversion expected; the default is shallow | Convert the nested map yourself, or set a policy on the declaration |
| A namespace stops loading on the JVM | A JS require reached a `.cljc` file | Quarantine the require in a `.cljs` host namespace |
| Structural test can't match a node | It's a `[:>]` node — reduced structural identity, by design | Declare it with `defhost`, or assert around it |
| Provider from a library needs to wrap the tree | Hosted like anything else | `defhost` the provider; it is a component |
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
| `defhost`'s option keys | **Not addressed.** "Policy overrides live on the declaration" is as far as the record goes |
| The codemod's name and invocation | **Not addressed** |
| When the reserved `{:ref [id config]}` spelling lands, and what registers an id | **Reserved, not designed.** HD-022 rules the refusal and the value-space; the registry, the timings and the commands roster are explicitly out of v0 |
| Which React version the runtime targets | **Not addressed by the design record.** The cleanup-returning callback ref taught above is a React 19 contract; this repo's implementation currently pins React 19.2, but that is a fact about today's tree, not a Hicasso ruling. If v0 lands on 18, the fallback shape above is the one to teach |
| Whether `::h/value` works across a host crossing | **Not addressed.** The example above assumes it does; a foreign `onChange` may hand you something other than a DOM event |
| The SSR placeholder's shape | Declared policy, inert in v0 |
| Whether a hosted component can be a `defview`'s child via `(:children props)` | The ABI says an existing React element is a legal child anywhere, which implies yes; not stated for the host case specifically |
| Embedding Hicasso *inside* a React-primary app | Named in the charter's use-case roster (item 11); no surface designed |
