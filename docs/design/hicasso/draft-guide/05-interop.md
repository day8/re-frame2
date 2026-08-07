# Interop

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

You want a date picker from npm. Declare it once, then use it anywhere a view is
legal:

```clojure
;; cf. implementation/freehand/test/re_frame/bench/hicasso/arm1/
;;     host_hatch_dom_cljs_test.cljs — the witness for the declaration and the
;;     callback behaviour this example shows.
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

This page is the **foreign component** door. For the full ladder — still-Hiccup
perf levers, host-edge hooks/refs, and what is not an escape — see
[Performance](11-performance.md).

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
`[:> …]` escape is built and explicitly secondary — [covered below](#the-escape-).

## Defaults

`defhost` with no options:

| Default | Behaviour |
|---|---|
| Prop names | shallow camelCase — `:on-change` → `onChange` |
| Prop values | crossed whole — a keyword stays a keyword. Only `:class`, `:id`, `:role`, `data-*` and `aria-*` stringify, where an HTML attribute is the destination and a string is the only representation there is |
| `:class` values | the class slot's own coercion, exactly as at a native tag — a collection joins (`["btn" nil :on]` → `"btn on"`) and two spellings of the class compose rather than one overwriting the other |
| Children | hiccup → React elements |
| Functions | pass through unconverted |
| SSR | `:client-only` — nothing server-side until the client adopts. Override with `:ssr`: `:client-only`, `{:fallback <hiccup>}` for placeholder markup, or `:render` to assert the component is server-safe and let it render there with its children. Bad policy → `:rf.error/hicasso-host-bad-ssr-policy` at mint. Full story: [Server-side rendering](10-server-side-rendering.md) |

Two options today: `:callbacks` (next section) and `:ssr`. An unknown option is
refused at mint (`:rf.error/hicasso-host-unknown-option`), not ignored. Bad
contracts, contracts on `:key`/`:ref`, duplicate prop spellings, and a component
that resolved to `nil` (classic `:default` against a library with no default
export) also fail at the declaration — where your stack points at the line you
wrote.

**A keyword value crosses as a keyword.** `[picker {:variant :compact}]` hands
the library `:compact`, not `"compact"`, and two keywords that differ only in
namespace stay two distinct values. A native tag is the other way round —
`:type :text` becomes `type="text"` — because a keyword there is always bound for
an HTML attribute, which is the same reason a host's `:class` and `:id`
stringify. If the library wants a string, write one at the call site, or
`(name :compact)`.

**No deep conversion.** Nested option maps are not camelCased for you. Guessing
which nested maps are options and which are data is a support trap; convert those
maps yourself when the library wants camelCase inside them. Values inside a
collection are shallow in the same way: they go through `clj->js`, which takes a
keyword's name and drops any namespace, so `{:opts {:mode :theme/dark}}` reaches
the library holding `"dark"`.

**`:class` is the one slot that does not follow that last sentence**, because a
class list is not data on its way to a library — it is bound for the `class`
attribute, where the only representation is one space-joined string. So a
collection there is *joined* rather than `clj->js`'d, nils are dropped, and two
spellings of the class compose: `{:class ["btn" nil :on] :className "wide"}`
reaches the library as `"btn on wide"`. That is the same answer a native tag
gives for the same hiccup, which is the point — one authored shape, one answer,
whichever side of the crossing it lands on.

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
`renderItem`). The body must be pure, and its return goes into the library's tree
**unconverted**: a string renders, hiccup does not. A returned vector reaches
React as a vector and is refused there, and nothing on the taught `h/` roster
converts one at this position — see [Not settled yet](#not-settled-yet).
Dispatching *while the call runs* raises
`:rf.error/hicasso-dispatch-in-render-position`, naming the position.

Intents still work, which is most of what the position is for: a row built in the
body carries them, and they fire later, on the user's click, under the frame of
the boundary that *supplied* the callback rather than the one that invoked it.

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
(h/defhost themed (.-Provider some-context) {:ssr :render})
```

Consumers below the crossing read context correctly — the node is a real React
element, so React's plumbing runs through.

**Write the `{:ssr :render}`.** A provider is a *transparent wrapper*: it
contributes no markup of its own and exists solely to carry a subtree. Under the
default `:client-only` policy a crossing renders nothing until the client adopts
it — and "nothing" includes the children, so on a server-rendered page the
provider takes the whole subtree beneath it out of the response. Nothing reports
that: the server HTML and hydration's first client pass agree, so there is no
mismatch and no warning, and the page simply appears after hydration.

`:ssr :render` is you asserting that this component is safe to run on a server,
and a context provider is the case where that is trivially true — it is React's
own component, and React's server renderer supports context fully. The
declaration then mints no gate at all: the component *is* the element, so the
server render, hydration's first pass and a fresh mount are all one tree, and
consumers below read the value you declared rather than the context default.

If the provider's *value* is genuinely client-only — derived from `window`, say
— `:render` cannot help you, because that value is computed in the caller's body.
Keep such a provider `:client-only` and accept that everything below it is too.

A fallback is not the way out of this. `{:fallback …}` renders *instead of* the
crossing rather than around it, and it is inert markup: a `defview` or `defhost`
head written into one is refused at the declaration
(`:rf.error/hicasso-host-fallback-boundary-head`).

## Memo and hosted components

A [boundary](02-views-and-reads.md#boundaries-memoize-by-default) carries a
`React.memo` wrapper that compares props by value. A `defhost` crossing carries
none, so it re-renders whenever the boundary that *wrote* it re-renders, and the
foreign component runs again — unless the library exported a `memo`, which is
its choice and not something the declaration arranges.

```clojure
(defview chrome-page [_]
  [:div
   [:span.chrome (str (sub [:hatch/label]))]
   [hosted-row {:label "fixed"}]])   ;; re-entered anyway — props are never compared
```

A write that moves `:hatch/label` invalidates `chrome-page`, so its body runs
again and rebuilds `hosted-row` with a fresh props object. Those props are equal
by value; nothing on that head compares them.

The bail-out you want is the enclosing boundary's — put the crossing behind a
`defview` of its own and it is not re-entered while that view's props hold still:

```clojure
(defview labelled-row [{:keys [label]}]
  [hosted-row {:label label}])
```

If a host should react to a subscription, that value has to reach **its own**
props — reading one boundary up and stopping looks identical, from the host's
side, to the value never changing.

## The escape: `[:>]`

`[:> Component props & children]` is the secondary form, for the cases a static
declaration cannot express: the component is **selected at runtime**, it is a
`memo` or `lazy` value, it **arrives from a render prop**, an **ecosystem
provider** hands it to you, or the site is a **one-off migration** you have no
intention of keeping. It takes the same foreign path as `defhost` with the same
default conversions, and it accepts the costs in [Why declare](#why-declare).

**Declare what you use twice.** First use, escape if that is faster; second use,
`defhost`. That is not a style preference. The escape is strictly weaker than the
door by construction, and every remedy in this section is the same word.

```clojure
;; the component is chosen by data, so there is nothing to declare
(def widgets {:chart Chart :table Table :map MapView})

(defview panel [{:keys [kind]}]
  [:> (get widgets kind) {:series (sub [:panel/series kind])}
   [:span.legend "revenue"]])
```

One gate serves every `[:>]` on the page and its type is constant, so moving
`kind` keeps that gate's own fiber and remounts only the subtree beneath it —
the right grain when the component is the thing that changed.

**It is `defhost` with the declaration erased, and what erasing the declaration
costs you is exactly what the declaration carried.**

| What a declaration carries | `defhost` | `[:>]` |
|---|---|---|
| A crossing name for your tools | authored, on the crossing's `displayName` | one constant, `"[:>]"` |
| `:callbacks` contracts | exact, per slot | none — every prop is unclaimed |
| An `:ssr` policy | `:client-only`, `{:fallback …}` or `:render` | fixed `:client-only`, and unspellable |
| An early site for refusals | the component, the options and the contracts, checked once at the declaration | nothing to check early — every refusal fires at the crossing |
| A `.cljc` quarantine for the JS require | one host namespace | the require lands in the view namespace |

Every remedy for every row is `defhost`. That is the design rather than a
coincidence, and the rest of this section is those rows in detail.

### Every prop is unclaimed

`:callbacks` is what turns a prop into a *position*. The escape has none, so
every prop at a `[:>]` is unclaimed — and the conduct is exactly the door's for
an undeclared slot, through the same code, not a second rule:

| Written at a `[:>]` prop | What happens |
|---|---|
| An intent vector or key-map at an `on*`-spelled slot | **Refused** — `:rf.error/hicasso-host-undeclared-callback`. A contract is never inferred from a name, and the alternative is `clj->js` shipping your intent to the library as an inert array |
| An intent vector or key-map anywhere else | Data, through the position's own conversion, like any other collection |
| An `h/fn`, at any slot | **Refused** — `:rf.error/hicasso-host-unclaimed-callback`. The marked form *asks* the position for a contract, and there is no position here to answer |
| A plain function | Crosses by identity and runs. It never asked for anything, so nothing is left unanswered — memoisation on callback identity keeps working |
| `:ref` | The crossing's own check: a callback ref crosses untouched, the reserved vector is refused |
| `:key` | React's, on the crossing's element, exactly as at a `defhost` |

Both refusals name `defhost`'s `:callbacks` as the recovery, and at the escape
that means writing the declaration you do not have. **The refusals are the
point.** An `h/fn` that crossed unclaimed would be called by the library, would
return an intent, and the library would discard the return — a handler that does
nothing, in production, with no diagnostic anywhere.

### On the server, nothing

A `[:>]` is hard `:client-only` and there is no spelling for anything else. The
server emits nothing at the crossing, hydration's first client pass emits
nothing, and the component appears once adoption completes. Those are not two
facts kept in step: React reads the same snapshot for the server render and for
hydration's first pass, so server-absent and first-pass-absent cannot disagree
and no mismatch is possible. A fresh client-only mount never consults the server
snapshot at all, so it renders the component immediately with no placeholder
flash.

**"Nothing" includes the children.** If the crossing is a transparent wrapper —
an ecosystem provider is the case you will meet — every descendant leaves the
response with it, silently, because the server HTML and the first client pass
agree by construction. Answering that is `{:ssr :render}`'s job at the door
([Server-side rendering](10-server-side-rendering.md#render--when-the-region-has-to-be-in-the-response)),
and the escape has nothing to assert it with.

A per-site *placeholder* is still reachable with no new escape surface, by
putting the policy back on a declaration:

```clojure
(h/defhost skeleton-slot (fn [p] (.-children p)) {:ssr {:fallback [:div.skeleton]}})

;; then at any site, dynamic head included:
[skeleton-slot {} [:> (get widgets kind) {:series data}]]
```

Say precisely what that buys: a placeholder, not server-rendered content. The
wrapper cannot borrow `{:ssr :render}`, because the thing it wraps is a `[:>]`,
which carries no declaration to assert server-safety with.

### The Component position

The slot takes what React accepts as an element type — a function or class
component, one of React's built-in wrappers (`Fragment`, `Suspense`,
`StrictMode`, `Profiler`, …), and a `memo` / `lazy` / `forwardRef` / context
value. Everything else is refused **at the crossing, in the owner's render and
on the server too**, with your stack pointing at the line you wrote.

That refusal is Hicasso's rather than React's on purpose. React's own *Element
type is invalid* is minted at fiber creation, which behind the adoption gate is
post-adoption and client-only — never on the server, never at first paint — and
it names `typeof type`, so a keyword, a map, a vector and a record all read
*"got: object"*, naming nothing you wrote.

| What you wrote | Why it is refused |
|---|---|
| `nil`, or `[:>]` with nothing after it | Its own id, `:rf.error/hicasso-raw-no-component`, and the door's diagnosis: an import that resolved nothing — `:default` against a library with no default export |
| A string or a keyword | The grammar owns tags. Write the tag, or a computed **keyword** head for a runtime one, which keeps the parse and the controlled-input door that `[:> "input" …]` would silently drop |
| A `defview` head | A head in its own right: write `[my-view …]`. Mounted raw it would read its props slot, get `undefined`, and receive `nil` props — silently |
| A `defhost` head | Also a head: `[my-host …]`. The escape is for what a declaration cannot express, and this one already is a declaration |
| A React **element** | An element is a legal child, never a type. Put it in child position, or hand `[:>]` the component it was built from |

Each carries its own recovery sentence; everything but the `nil` case shares
`:rf.error/hicasso-raw-not-a-component`, with the shape in `ex-data`.

**Bare-head auto-hosting stays illegal.** `[DatePicker {…}]` with a raw JS
component in head position is not a shortcut, and it is not going to become one.
One sentinel, not two ways to smuggle JS into the tree.

### Reduced structural identity, exactly

Four statements, and the useful one is first.

**The DOM is not reduced at all.** A `[:>]` and a `defhost` on the same component
with the same props produce the same DOM in every phase — nothing before
adoption, the component's own markup after — because the gate contributes no node
of its own. Anything asserting on rendered markup sees no difference between the
two forms.

**The hiccup lane is reduced at exactly one slot.** `[:> C {…}]` is an ordinary
vector: `:>` is a plain keyword and `=` compares it structurally. The one
reduction is that slot 1 holds a JS value compared by *identity*, so
`(= [:> C {:a 1}] [:> C {:a 1}])` is true and the same props under a different
component are not equal. Everything else in the vector is total. That is the
whole of the phrase.

**The fiber carries a constant name.** `displayName` is the literal `"[:>]"`, and
no name is derived from the component — deliberately. React resolves a type's
name as `displayName || name || null`, Closure renames `.name` under `:advanced`,
and foreign production bundles routinely ship without a `displayName`, so a
derived name would be *build-dependent*: it would look authored and would not be.
`defhost` has no such problem because its name is data you wrote. Little is lost
in the tree anyway, because the component's own fiber sits directly beneath the
gate and React names it there: a `defhost` reads `<my.ns/date-picker>` →
`<DatePicker>`, and the escape reads `<[:>]>` → `<DatePicker>`.

**And the loss you meet first is none of the above.** It is the `.cljc`
quarantine. `defhost` puts the JS require in one host namespace; `[:>]` names the
component at the call site, so the require lands in the *view* namespace and that
namespace stops loading on the JVM. The symptom is not "my structural test cannot
match a node" — it is "my test namespace will not load." Today that is close to
the whole cost, because the
[headless structural render](08-testing.md#full-headless-render-not-built) does
not exist yet, and the foreign region is out of its scope for both forms in any
case. When it lands, a declared crossing has a name to project and a `[:>]` node
has none.

### Children lower where you wrote them

Children of a `[:>]` lower **eagerly, inside the render window of the boundary
that wrote the crossing**, along the same path a `defhost`'s children take. That
is what makes intents in them work: a child's intent closure captures the owner's
frame-locked dispatch at lowering time, so it fires into the right frame however
much later the foreign component renders it — inside a portal, in a virtualised
window, in a `Suspense` fallback. A Hicasso boundary *beneath* a crossing gets its
frame from React context, which ignores the JS call stack, so a foreign component
in the middle is transparent.

A **function prop on the crossing itself** is the other case, and the one worth
knowing. It gets no wrapper, because no position claimed it, so a body that lowers
an intent there runs with no ambient frame and raises
`:rf.error/hicasso-intent-outside-boundary` at the library's call — loud and late,
never silently inert. The message offers both readings, because from where you sit
you *are* inside a boundary's render (you wrote the crossing in a body), and it
names the repair: `defhost` with `:callbacks {<the prop> :render}` is the position
that owns the frame. A `[:>]` written inside a *declared* `:render` body inherits
that position's frame, which is the composition intended — but a `:render` return
still crosses unconverted, so today the subtree has to be written where children
lower ([Not settled yet](#not-settled-yet)).

### Migrating off it

A hand migration from Reagent is three moves per namespace: collect `[:> X …]`,
emit `defhost`, rewrite call sites. `[:> X props]` → `(defhost x X {})` is
behaviour-preserving by construction, because the escape's prop walk *is* the
door's with an empty roster — whatever is ruled at the door, the escape does the
same thing. A codemod is planned and not built.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Prop arrives as `on-change` and the library ignores it | Nested keys expected camelCase; deep conversion is not offered | Convert the nested map yourself before it crosses |
| The library ignored a keyword prop value | A keyword crosses a host prop unchanged; only HTML-attribute names stringify | Write the string at the call site — `"compact"`, or `(name :compact)` |
| Hiccup passed *in a prop* renders as its own tag name and text | Only children lower; a prop value crosses as data, so `[:h2 "Tasks"]` arrives as the array `["h2" "Tasks"]` — silently, with no error | Pass the subtree as a child where the library takes one; at a prop there is nothing to reach for yet |
| React refuses an object as a child, from a `:render` body | The body returned hiccup; a `:render` return crosses unconverted | Return a string, or keep the subtree on the Hicasso side of the crossing |
| `:rf.error/hicasso-intent-at-a-non-event-contract` | Slot is `:handler` or `:render`; neither dispatches | Declare `:event`, or write an `h/fn` for the real work |
| `:rf.error/hicasso-intent-needs-the-event` | Vector spelling needs the DOM event at arg one; library put something else there | Use `h/fn` — full argument list, library order |
| Namespace won't load on the JVM | A JS require reached a `.cljc` file | Quarantine the require in a `.cljs` host ns |
| Structural test can't match a node | A `[:>]` node has reduced structural identity — slot 1 holds a JS value compared by identity, and the crossing's fiber is named `[:>]` rather than by the component | Prefer `defhost`, or assert around the node |
| A `[:>]` region is missing from the server HTML | `[:>]` is hard `:client-only` — no declaration, so no `:ssr` policy and no spelling for one | Declare it with `defhost` and choose `:ssr`, or wrap the escape in a host that carries a `{:fallback …}` |
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

**An object ref works too, and is simply untaught.** `(react/createRef)` crosses
by identity at a native tag and at a `defhost` crossing alike — React 19 carries
`ref` as an ordinary prop — so a Reagent habit you bring with you will not break.
The callback form is taught instead because it makes attach and teardown one
thing, which is the property the four rules above are about. The *one* value
`:ref` refuses is a **vector**: that spelling is reserved for a later data form,
and writing it today raises `:rf.error/hicasso-ref-vector-reserved` rather than
handing React an array it would ignore in silence.

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
| Turning hiccup into an element at a prop or a `:render` return | **Open, and a real gap.** The mechanism exists inside the codec; nothing on the taught `h/` roster reaches it, so today the answer is to write the subtree where children lower — or to write it twice |
| Declarable conversion defaults on `defhost` | Open — `:callbacks` and `:ssr` are the two options today |
| A provider whose *value* is client-only | Open, and named rather than solved. `:ssr :render` renders the component server-side, but the value it carries is computed in the caller's body — so a `window`-derived value has no server story and the host stays `:client-only` |
| Migration codemod | Planned, unbuilt |
| When `{:ref [id config]}` lands, and what registers an id | Reserved, not designed |
| Which React version the product pins | Not pinned by the product; cleanup-returning refs need React 19; this repo currently pins 19.2 |
| Embedding Hicasso inside a React-primary app | Named as a use case; no API designed |
