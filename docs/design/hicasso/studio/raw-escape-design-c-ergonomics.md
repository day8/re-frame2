# The `[:>]` raw escape — design C: the programmer's experience

**Genre note.** The rest of this tree is the programme's *measured* record, and
[the studio README](README.md) sets four obligations for a page that publishes a
number. **This page publishes no numbers.** It is one of three independent
designs written for `rf2-2rtt6.103`'s five open edges, to be read against two
siblings and three adversarial reviews before anything is built. Where it cites
code it cites what is on disk at `131bb6ecf0`; where it cites a ruling it cites
`decisions.md` by line. Nothing here is a ruling and nothing here is a
measurement.

**Scope.** HD-011's core lowering is *ruled* and is not redesigned here
([`decisions.md`](../decisions.md) ~:412–421): `[:> Component props & children]`
is legal, lowers through the same foreign path as `defhost` with the same
default conversions, is `.cljs`-only at that node, and carries reduced
structural identity. The bead's six clauses stand. This page answers the five
edges the ruling left open, and it answers them from the five use cases the
ruling itself names.

**Lens.** What does a person type; what happens when they get it slightly wrong;
does the diagnostic tell them *what to do*; and what does each decision cost a
reader who has to hold it.

---

## The design in one line

> **The escape is `defhost` minus the declaration — and everything the
> declaration buys is exactly what you lose.**

That sentence is the whole teaching surface. A declaration buys four things, and
each one is a named loss at `[:>]`:

| A `defhost` declaration buys | What `[:>]` has instead |
|---|---|
| A **name** at the crossing — for React DevTools, for a structural matcher, for the census | No name. One shared element type for every raw crossing (edge 3) |
| An **`:ssr` policy** — `:client-only`, or a fallback | The default only: nothing server-side (edge 1) |
| **Contracts that can contradict a prop's spelling** — `onPick` declared `:handler`, `renderRow` declared `:event` | The spelling's own default, exactly as at a native element (edges 2 and 5) |
| A **`.cljc` quarantine** — the JS require lives in one host namespace | The require lands in the view namespace, which stops loading on the JVM (edge 3) |

Four rows, and three of them are what the guide already teaches as **"declare
what you use twice."** The design's job is to make each loss *legible at the
moment it bites*, and to make sure nothing is lost *silently*.

---

## The five use cases, walked

### 1. A component selected at runtime

```clojure
(ns app.views.form                    ;; .cljs — the require lands here
  (:require ["@lib/pickers" :refer [DatePicker ColorPicker TextField]]))

(defn- widget-for [kind]
  (case kind :date DatePicker :color ColorPicker TextField))

(defview field [{:keys [kind value]}]
  [:> (widget-for kind) {:value      value
                         :on-change  (h/fn [v] [:field/set kind v])
                         :aria-label (name kind)}])
```

**What works.** The head is computed. `:on-change` is event-spelled, so the
`h/fn` takes the event contract and its returned intent dispatches into this
boundary's frame. `:aria-label` camelCases to `ariaLabel`… no: `aria-*` is
exempt from the camelCase rule and crosses as `aria-label`, which is what the
library wants.

**What goes wrong, and what they see.** Switching `kind` swaps the component
value. The crossing's element *type* is a shared gate that does not change
(edge 1), so the gate stays mounted and the widget beneath it unmounts and
remounts — which is the correct semantics: a colour picker must not inherit a
date picker's state.

**The trap this use case would hit under a naive implementation** is the one
worth naming: if the crossing minted a wrapper component *per render*, every
render of `field` would be a fresh React element type and React would tear down
and rebuild the widget on every keystroke elsewhere on the page. The codec
already states this law for boundary heads — *"a fresh wrapper per render would
be a fresh React element type every time, and React would unmount and remount
the entire subtree rather than bail out of it"* (`front/codec.cljs`, on
`memoize-boundary!`). Edge 1's decision makes that impossible by construction.

### 2. A `memo` or `lazy` value

```clojure
(def Chart (react/lazy #(js/import "./chart.js")))

(defview panel [_]
  [:> react/Suspense {:fallback (h/as-element [:div.skeleton])}
   [:> Chart {:data (sub [:report/rows])}]])
```

**What works.** `lazy` and `memo` products are JS objects React accepts as
element types; the Component position passes them through (edge 4). `:data` is
a collection, so it crosses via `clj->js`.

**What goes wrong.** `{:fallback [:div.skeleton]}` — hiccup written in a **prop**
— is the sharpest silent failure available at any foreign crossing. Hiccup
becomes elements in **child** position only; in a prop it is a collection, so it
goes through `clj->js` and arrives as the array `["div.skeleton"]`, which React
renders as the text `div.skeleton`. No error, wrong pixels.

This is not new to `[:>]` — it is HD-011's shallow-conversion default and it
bites identically at `defhost`. But `[:>]` is where a person meets an
*unfamiliar* component, so it is where they are most likely to guess at a prop's
shape. **Decision: no new machinery; one troubleshooting row** (§Diagnostics,
row 9) and `h/as-element` named in it. Refusing "a vector at a non-event prop"
is not available — a vector is legal data at a foreign prop and always will be.

### 3. A component supplied through a render prop

```clojure
(defview grid-panel [_]
  [:> DataGrid {:rows       (sub [:report/rows])
                :renderRow  (h/fn [row]
                              (h/as-element
                                [:li {:on-click [:row/pick (:id row)]}
                                 (:title row)]))}])
```

**What works — and this is the design's load-bearing claim.** `renderRow` is not
event-spelled, so an `h/fn` there takes the **render contract**: `render-callback`
captures both `*frame*` and `*dispatch*` at lowering time and rebinds them for
each invocation, so the `:on-click` inside the row dispatches into *the boundary
that supplied the callback*, however much later the grid calls it. That is
`rf2-2rtt6.74`'s repair (PR #7449), and the escape inherits it **for free** —
but only under edge 2's decision. Under the alternative (§Edge 2), an `h/fn`
here would cross as a bare function with no contract at all, and the returned
intent would silently never dispatch.

**What goes wrong.** The library spells its render prop `onRenderRow`. That
matches `^on[A-Z]`, so the position is an *event* position, the `h/fn` takes the
event contract, and a returned **vector** is dispatched — so returning hiccup
dispatches your hiccup as an intent. The symptom is re-frame's own "no handler
registered for `:li`". That is a bad diagnostic for a real ecosystem spelling
(Fluent UI uses `onRenderItem`), and it is the one shape the escape genuinely
gets wrong. **Its recovery is a declaration**, which is the right place for the
unpleasantness: `(h/defhost grid DataGrid {:callbacks {:on-render-row :render}})`
makes the contract explicit and the spelling stops mattering. §Diagnostics row 7
carries it.

### 4. A provider an ecosystem library hands you

```clojure
(defview app [_]
  [:> ThemeContext {:value (sub [:theme/current])}
   [shell {}]])
```

**What works.** A context object is a legal element type in React 19; consumers
below read through it because the node is a real React element.

**What goes wrong, and it needs a ruling.** A provider is a *transparent*
wrapper: it contributes no markup and exists to carry a subtree. Under edge 1's
`:client-only` default the crossing renders **nothing** on the server — and
"nothing" takes `[shell {}]` and the entire application with it. The server
sends an empty document and the whole page appears after hydration.

This is not the escape's fault and the escape cannot fix it. `defhost` has the
same hole: `mint-host-gate!` returns a single pre-walked `placeholder` element
when unadopted and never consults `props.children`, so `:ssr {:fallback …}`
*replaces* the subtree rather than passing it through. **The guide's own
provider example** (`05-interop.md:120`, `(h/defhost themed (.-Provider
some-context))`) loses its subtree server-side today. See §Needs a ruling, R1.

### 5. A one-off migration site

```clojure
;; pasted from a Reagent codebase, unchanged
[:> DatePicker {:selected  due
                :onChange  #(rf/dispatch [:task/set-due %])
                :popperProps {:strategy "fixed"}}]
```

**What works.** The head, the props map, the camelCase spellings, the plain
function by identity, `:key` in the props map. Most Reagent `[:>]` sites compile
and run unchanged, which is the point of keeping the spelling.

**What goes wrong.** `:popperProps {:strategy "fixed"}` is a **nested** map, and
Reagent camelCases nested keys recursively where we convert shallowly. Here the
key is already camelCase so nothing breaks; write `{:popper-props {:some-key 1}}`
and Reagent would have produced `someKey` and we produce `some-key`. Silent.
§Reagent differences carries the full list, and the answer to "how does a
migrator learn" is *the codemod targets `defhost`, not `[:>]`* (§What this means
for `rf2-2rtt6.106`).

---

## Edge 1 — SSR and hydration

### What must be true

`rf2-2rtt6.103`'s clause 6 rules that `[:>]` renders nothing server-side. The
design-phase note adds the binding half: **server-absent must also be absent
from the client's hydration first pass**, or X2's zero-React-complaints row
breaks. X2 currently reads *0 complaints across the whole adoption*
([ssr-spike-witness.md](ssr-spike-witness.md)).

### The decision

**`[:>]` renders through one module-level gate component, shared by every raw
crossing in the program, with the foreign component travelling as a prop.**

The gate is the same shape as `defhost`'s: a one-hook component whose
`useSyncExternalStore` answers `false` from its **server** snapshot and `true`
from its **client** one. React reads the server snapshot under `renderToString`
*and again on hydration's first client pass*, then re-renders with the client
snapshot once adoption completes. Server HTML and first client pass therefore
agree **by construction** — there is no mismatch to reconcile and no rule anyone
has to remember. A fresh `createRoot` mount never consults a server snapshot at
all, so it renders the foreign component on its first pass with no placeholder
flash.

The three module-level snapshot functions the codec already defines
(`gate-no-subscribe`, `gate-adopted`, `gate-unadopted`) are reused verbatim.
**Zero new SSR machinery.**

### Why *shared*, and not one gate per crossing

`defhost` mints its gate at the declaration, closing over the component and the
pre-walked fallback. `[:>]` has no declaration site, so the candidates are:

| Candidate | Verdict |
|---|---|
| No gate — `createElement` the component directly | Renders on the server. Breaks clause 6 and drops the `window is not defined` protection that is the whole reason the door does not guess |
| A gate minted **per render** | Fresh element type every render ⇒ React unmounts and remounts the foreign subtree on every parent render. Silent, catastrophic, and the obvious implementation |
| A gate **cached by component identity** (WeakMap, or an expando on the component) | Works, but it is an identity-keyed cache of a raw JS value — *precisely* the mechanism HD-011 rejected when it rejected bare-head auto-hosting. Also needs a third cache HD-004 does not permit, and either mutates foreign values or holds them in a map |
| **One module-level gate; component as a prop** | Chosen |

The chosen shape carries the crossing as two slots — the foreign component and
the converted props — which is the shape `boundary-element` already uses
(`#js {"rfProps" …}`). Children are forwarded explicitly, because
`createElement` puts them on the *gate's* props rather than on the nested
object. The cost is one extra JS object per crossing.

**One fiber and one hook — identical to `defhost`.** HD-020(b)'s ≤2-hook budget
is a statement about Hicasso *boundaries*, and the gate is not one: no frame, no
subscription, no body. Untouched.

**A property worth stating:** the shared gate *is* the reduced structural
identity. Every raw crossing in the program has the same element type and the
same DevTools name, which is exactly "no name at this node" (edge 3). Mechanism
and cost are one fact rather than two.

### There is no `:ssr` option on `[:>]`

A policy key in the props map would mean the map has two kinds of key — ones
that cross and ones that do not — which is a new concept, a new refusal surface,
and a new thing to get wrong. **Decision: the escape has no policy surface.
Policy is what a declaration is for.** The answer to "I need a server fallback"
is `defhost`, which is the paved path and a one-line diff.

### What goes wrong, and what they see

Nothing throws. The symptom is *the node is missing from view-source and appears
after hydration*, which is correct behaviour and identical to `defhost`'s
default. It is documented, not diagnosed — a console note fired per crossing
would be exactly the nag-diagnostic this project rejects. §Diagnostics row 10 is
the troubleshooting entry.

**Do not lean on `:rf/render-hash` for any of this.** `rf2-2rtt6.91` records it
as degenerate for an interpreted root — the dogfood screen and a ~1,200-element
page both hash `83b865f8`. The instrument cannot witness anything here, and the
SSR witness rows are byte-level for that reason.

**Addendum, 2026-08-05 — `rf2-2rtt6.91` has closed (PR #7510); the instruction is
unchanged and its reason is now firmer.** Do not lean on `:rf/render-hash` for any of
this — but no longer because the value is degenerate. Because **this tier carries no
such value**: Spec 011 tiers hydration-mismatch detection by render-tree
representation rather than by adapter brand, and a root React adopts (a compiled
`re-frame.ui` root, a native UIx root, or a Freehand root) now emits none at either
end. The figure still reproduces — the witness rows were rewired to take the hash
from `ssr-hash/render-tree-hash` directly — and `83b865f8` was never a fact about
either page: it is the FNV-1a-32 of the canonical EDN `[#fn[] {}]`, so **any**
unresolved `[<fn> {}]` root takes it. The SSR witness rows stay byte-level, and now
there is not even a degenerate instrument to be tempted by.

### Reader cost

**Zero new concepts.** The SSR fact is `defhost`'s default, already taught in
`05-interop.md`'s Defaults table and `10-server-side-rendering.md`. One row
extends to cover the escape.

---

## Edge 2 — intent vectors and `h/fn` in declaration-less props

### The fact that forces the decision

`h/fn` is **an ordinary function**. `intent/callback` sets one own property
(`"hicassoFn"`) on the function and returns *the same function*
(`front/intent.cljs` ~:300–327). So at a prop with no contract it takes
`host-prop-value`'s first branch, `fn?`, and crosses by identity — callable,
no error, **and with no contract at all**. `event-callback` never wraps it, so a
returned intent vector is **not dispatched**. The `"hicassoFn"` marker rides onto
the foreign props object, inert.

That is a silent no-op on the single most common thing a person writes at a
crossing. It cannot stand.

### The decision

**A `[:>]` prop takes its contract from its position, exactly as a native
element's prop does.** Concretely: the crossing runs `intent/lower-prop` — the
declaration-less row of the position table — where `defhost` runs
`lower-declared-prop`. Everything else in `host-element` is unchanged.

| At a `[:>]` prop | Behaviour |
|---|---|
| Event-spelled (`^on-[a-z]` or `^on[A-Z]`), intent vector | Lowers as at a native event position — frame-locked, argument law and `::h/prevent` unchanged |
| Event-spelled, key-map | Same |
| Event-spelled, `h/fn` | Event contract: every library argument reaches the body, a returned vector dispatches |
| Not event-spelled, not `:ref`, `h/fn` | **Render contract** — pure position, frame captured and rebound per invocation |
| Anything, plain unmarked function | Crosses by identity. Unchanged, and this is the Reagent-parity case |
| `:ref` | `check-ref!` — a vector is refused as the reserved spelling |
| Anything else | `host-prop-value` — shallow camelCase, HD-011's default |

### Why this is inside the ruling, not an amendment to it

HD-011's "same default conversions" names three: *shallow camelCase props,
hiccup children → elements, functions pass through*. All three are preserved
verbatim. Carrier lowering is a different axis, and it is HD-024's: **"one
callback form; the position selects the contract."** `lower-prop` is that law's
own implementation at every native element in the language. A `[:>]` prop is a
position with no declaration, so it takes the no-declaration rule. Nothing is
invented and nothing new is written.

`defhost` refuses to infer a contract from an `on*` spelling because it *has* a
declaration, and a spelling that overrode the declaration would make the
declaration a lie. Where there is no declaration, spelling is the only authority
there is.

**The reviewers should test this reading.** A literal reading of "the same
foreign path as `defhost`" gives the alternative: `[:>]` behaves as a `defhost`
with an empty `:callbacks`, so `lower-declared-prop` is never reached, every
event-spelled intent vector is refused by `refuse-undeclared-host-event!`, and
`h/fn` must be refused too (because silently inert is worse). That alternative
makes the escape **unable to attach a handler at all** — its only recovery
message would be "use `defhost`". Three of the five named use cases need
handlers, Reagent's `[:>]` takes handlers, and an escape you cannot attach a
handler to is a read-only widget mount rather than an escape. That is the
argument; if it is wrong, it is wrong here.

### What goes wrong, and what they see

The event-spelled render prop (`onRenderRow`) — walked in use case 3, and the
one shape this decision gets wrong. Recovery: declare it. §Diagnostics row 7.

### Reader cost

**Zero new concepts, and one deleted.** A reader who knows native props knows
`[:>]` props. There is no "at the escape, callbacks work differently" paragraph
to write, because they do not.

---

## Edge 3 — what "reduced structural identity" concretely means

### The decision

Reduced identity is **three named losses at the node, and nothing else.**
Rendering is not reduced; only identity is.

**(a) No name.** A `defhost` crossing's element type carries the declaration's
name as its `displayName`, so React DevTools shows `date-picker` and a structural
matcher can match on a *string* without holding the JS value. Every `[:>]`
crossing shares one element type whose name is constant, so DevTools shows the
escape's own name and then the foreign component's own name beneath it. A
matcher can distinguish two raw crossings only by holding the component values
themselves — which under `:advanced` may have been renamed away.

**(b) No census entry.** Tooling that answers *"what foreign components does this
app use, and what are their contracts?"* enumerates declarations. A `[:>]` is
invisible to it. This is not hypothetical: it is why `front/codec.cljs` can say
*"the census counts none."*

**(c) No `.cljc` quarantine.** `defhost` puts the JS require in one host
namespace and leaves view namespaces as data. A `[:>]` names the component *at
the call site*, so the require is in the view namespace, so that namespace stops
loading on the JVM. **This is the loss a person actually meets first**, and its
symptom is not "my structural test cannot match a node" — it is "my test
namespace will not load." The guide already carries the row.

### What is *not* lost, stated because it is the honest half

**Canonical DOM is identical.** `[:> C props …]` and `[c-host props …]` for the
same `C` and the same props produce byte-identical markup, because they run the
same conversion, the same gate and the same `createElement`. This is a checkable
claim and the design proposes it as the witness that bounds the loss:

> **Canonical-DOM parity between the escape and the declaration is asserted;
> structural identity is where they differ.**

**Hiccup `=` survives at the form level.** `[:> C {:a 1}]` equals `[:> C {:a 1}]`
whenever both sides name the same `C`, because the head compares by identity.
What dies is writing the expected form in a namespace that cannot hold the JS
value — which is (c) again, wearing a different hat.

### What a Freehand or Reagent migration hits first

Not the structural matcher. `08-testing.md`'s headless structural render is a
sketch and is not built, so nobody reaches edge 3 through that door today. What
they reach is (c): the moment a view namespace gains a `[:> X …]`, it is a
`.cljs` namespace, and any JVM-side suite over it stops loading. A Reagent
migration arrives with exactly this shape already — Reagent has no structural
test story either — so the migration does not *regress*; it fails to *gain* the
thing `defhost` was built to give. The guide's framing ("friction once; free at
every use site") is the right one and needs no change.

### Reader cost

**One sentence** — *"`[:>]` renders exactly what the declaration would; what it
does not have is a name"* — plus the `.cljc` fact the guide already teaches
under "Why declare."

---

## Edge 4 — the legal Component-value boundary

### The decision

**Refuse a named list; pass everything else to React.**

An allow-list of React's `$$typeof` roster is a list that goes stale the next
time React adds an element type, and it would refuse values a person legitimately
reached for. So the check is a refusal list, and every entry has a recovery a
person can act on:

| Value in Component position | Refused because | Told to |
|---|---|---|
| `nil` | The classic broken-import symptom (`:default` against a library with no default export). `mint-host!` already refuses it at the declaration for exactly this reason | Check the require's `:refer` / `:default` |
| A string or a keyword | Reagent accepts `[:> "div" …]`; we have a spelling for that | Write the tag directly — `[:div …]`, or `["my-element" …]` for a custom element |
| A number, a boolean, a ClojureScript collection | Not a component under any reading | — |
| A **Hicasso view head** | It is a function, so React would accept it and the body would receive shallow-camelCased props instead of the boundary props protocol. **Silent garbage** | Write `[my-view {…}]` |
| A **`defhost` declaration** | The minted head is a plain JS object with no `$$typeof`; React's own error names nothing the author wrote | Write `[my-host {…}]` |

Everything that survives is a JS function, a JS object or a JS symbol — which is
React's roster plus whatever React adds next. `memo`, `lazy`, `forwardRef`,
class components, context objects, `Suspense`, `Fragment`, `Profiler`: all pass,
none enumerated.

**Where React's own error is accepted.** A JS object React does not recognise
gets React's error, one fiber down. The design takes that trade knowingly: at
that point the value is provably a foreign JS object the author reached for
deliberately, and enumerating the roster to improve the message would cost more
than the message is worth.

The two head refusals (view head, host head) are the ones an adversarial reviewer
should push on: both values *are* things React accepts, so refusing them is a
choice beyond clause 5's letter. The case for them is that each has a correct
spelling one character shorter, and the failure mode of allowing them is silent
rather than loud.

### What goes wrong, and what they see

All five refuse **at the crossing, with the value printed and the alternative
spelling named**. `lazy` without a `Suspense` above it is React's error and a
good one; the `:fallback`-is-hiccup trap that comes with it is §Diagnostics
row 9.

### An honest gap, inherited not introduced

HD-016 rules **callback refs only**, but `check-ref!` refuses only a *vector* —
the reserved data spelling. An object ref (`(react/createRef)`) is not refused
anywhere, and React 19 carries it as an ordinary prop, so it works. A Reagent
migrant's object ref will therefore just work. **Decision: do not add a refusal
at `[:>]` that `defhost` does not have** — conversion parity is the ruling, and a
rule enforced at one crossing and not the other is worse than a rule enforced at
neither. Filed as a `defhost`-level question (§Beads).

### Reader cost

**Zero.** A refusal roster is met only when you are already wrong; it is not
something a reader holds.

---

## Edge 5 — children lowering, dispatch and frame capture

### The decision

**Nothing new — and that is the finding.** `[:>]` reuses `host-element`'s child
path verbatim, and every property it needs is already established and witnessed
for `defhost`.

**Children are lowered eagerly, inside the writing body's frame.**
`make-element` puts every trailing form through `as-element` *now*, while the
boundary's `with-frame` extent is still on the stack (`runtime/run-once` wraps
the entire `codec/as-element` walk). So an intent in a `[:>]` child closes over
the writer's frame-locked dispatch and fires into the right frame however much
later, and wherever, the foreign component renders it — a portal, a virtualized
window, a `Suspense` fallback. This is a *good* property and it is free.

**A Hicasso boundary beneath a `[:>]` gets its frame from React context.**
`shell` reads `useContext frame-context`, and React context ignores the JS call
stack, so a foreign component in the middle is transparent. Already witnessed
for `defhost` at `arm1/frame_prop_dom_cljs_test.cljs`
(`a-foreign-component-between-two-boundaries-preserves-the-frame`). The
frame-as-data variant (`rfFrame`) is a measurement variant, not the default, and
it survives the crossing too because the prop is stamped onto the element at
creation time, inside the writing body.

**A callback the foreign component invokes later** carries its frame because
`render-callback` captured `*frame*` and `*dispatch*` at lowering and rebinds
them per invocation, with the arming gate released in a `finally`. That is
`rf2-2rtt6.74`'s repair and the escape inherits it — **conditional on edge 2**.
Under the alternative reading, no render contract exists at `[:>]` at all and
this paragraph is false: an `h/fn` handed to a render prop would cross bare, and
either its rows would carry no dispatch or its intents would raise
`:rf.error/hicasso-intent-outside-boundary` at the library's first call.

**A `[:>]` written outside any boundary** is fine for plain props and raises
`:rf.error/hicasso-intent-outside-boundary` for an intent — the same loud error
`defhost` raises, from the same check (`require-dispatch` reads `*dispatch*` at
lowering and throws when it is nil).

### What goes wrong, and what they see

A function child — `[:> Ctx.Consumer (fn [v] …)]`, the React context-consumer and
headless-UI shape — passes through `as-element`'s `:else` branch and reaches the
library as a function, which is correct. But the function's **return** is not
walked: it must be an element, so the body writes `(h/as-element …)`. Getting
that wrong hands React a ClojureScript vector as a child and React says
*"Objects are not valid as a React child"*. §Diagnostics row 8.

### Reader cost

**Zero.** Nothing about children changes at the escape, so nothing about children
is written about the escape.

---

## Diagnostics

Every entry follows the house shape exactly: prose first, the error id as a
bracketed suffix, `:recovery` an imperative kebab keyword, `:where` the
attributed position. New ids are marked **new**; the rest are inherited unchanged
and are listed so a reviewer can see the escape adds three ids, not fifteen.

| # | Trigger | Id | What it tells them to do |
|---|---|---|---|
| 1 | `nil` in Component position | `:rf.error/hicasso-raw-no-component` **new** | Name the broken-import cause and say to check `:refer` / `:default` — the same sentence `mint-host!` already uses |
| 2 | String or keyword in Component position | `:rf.error/hicasso-raw-not-a-component` **new** | "Write the tag directly: `[:div …]`" — and for a string, `["my-element" …]` |
| 3 | Number, boolean or CLJS collection in Component position | `:rf.error/hicasso-raw-not-a-component` **new** | Print the value; say what the position takes |
| 4 | A Hicasso view head in Component position | `:rf.error/hicasso-raw-hicasso-head` **new** | "`X` is a Hicasso view — write `[X {…}]`. `[:>]` is for foreign React values" |
| 5 | A `defhost` declaration in Component position | `:rf.error/hicasso-raw-hicasso-head` **new** | "`X` is a declaration — write `[X {…}]`" |
| 6 | Empty `[:>]` — no component at all | `:rf.error/hicasso-raw-no-component` **new** | "`[:> Component props & children]` — the component is the second element" |
| 7 | `h/fn` at an event-spelled prop the library treats as a render prop | *no new id* | Troubleshooting row: "a returned vector dispatches at an event position; `onRenderRow` is event-spelled. Declare the prop `:render` with `defhost`" |
| 8 | A function child returning hiccup | React's own | Troubleshooting row: wrap with `h/as-element` |
| 9 | Hiccup written in a **prop** | *none — silent* | Troubleshooting row: hiccup becomes elements in child position only; wrap the prop's value with `h/as-element` |
| 10 | Node missing from server HTML | *none — by design* | Troubleshooting row: foreign regions are `:client-only`; `[:>]` has no declaration to say otherwise, so declare with `defhost` and set `:ssr` |
| — | Intent vector outside a boundary | `:rf.error/hicasso-intent-outside-boundary` | inherited |
| — | Vector at `:ref` | `:rf.error/hicasso-ref-vector-reserved` | inherited |
| — | Non-map at `:&` | `:rf.error/hicasso-merge-not-a-map` | inherited |
| — | Unforced `delay` reachable from props | `:rf.error/hicasso-deferred-read-at-boundary` | inherited |

**Three new ids across six triggers** — `hicasso-raw-no-component`,
`hicasso-raw-not-a-component`, `hicasso-raw-hicasso-head` — plus four
troubleshooting rows that need no id because nothing throws. Every id names the
value that arrived and the spelling to use instead; none names an internal var
and none prints a schema.

One inherited message wants a sentence added, and it is cheap: `merge-caller`'s
`:rf.error/hicasso-merge-not-a-map` says *"Forward a map, or drop the key"*, and
the commonest way to reach it at a raw crossing is forwarding a foreign props
**object** (`{:& rest-props}` from a wrapper). Adding *"a JS object is not a map
— `(js->clj rest :keywordize-keys true)`"* costs nine words and converts a dead
end into an instruction.

---

## Where our `[:>]` differs from Reagent's

Verified against stock Reagent's `reagent/impl/template.cljs` and
`reagent/impl/util.cljs` at `master` (fetched 2026-08-04), not from memory, and
cross-read against this repo's `implementation/adapters/reagent-slim/`, which
records where it deliberately narrowed stock.

| # | Case | Stock Reagent | Hicasso `[:>]` | Loud? |
|---|---|---|---|---|
| 1 | Nested map keys | Recursively camelCased (`kv-conv` → `cached-prop-name` at every depth) | **Shallow** — the top key only; nested maps keep the spelling you wrote | **Silent** |
| 2 | Plain function props | Pass by identity (`js-val?` is true for a function, first `cond` branch) | Pass by identity | — no difference |
| 3 | Non-fn `IFn` values (a keyword or map used as a function) | Wrapped in a variadic shim | Keyword → `name`; collection → `clj->js` | Silent, and vanishingly rare |
| 4 | Keyword **values** | `(name x)` | `(name x)` | — no difference (both drop a namespace; see §Beads) |
| 5 | `^{:key k}` **metadata** | Read — `(-> (meta argv) get-react-key)` | **Ignored.** HD-016 deletes the folklore; `:key` lives in the props map | React's own dev warning |
| 6 | A string in Component position | Accepted, passed straight to `createElement` | **Refused** with the tag spelling named | **Loud** |
| 7 | Server rendering | Renders the component | **Renders nothing** until the client adopts | **Silent** |
| 8 | Contract on a callback | None — a function is a function | Position selects the contract; a returned vector dispatches at an event position | Loud where it matters |

**Rows 1, 5 and 7 are the migration risks**, and 7 is the one that will surprise
hardest because it only appears in production. All three are silent, which is
precisely the argument that they must be *written down* rather than diagnosed.

### What this means for `rf2-2rtt6.106` (the codemod)

The single biggest ergonomic lever in this whole design is not in the escape at
all: **the codemod's target is `defhost`, not `[:>]`.** The guide already says
so — *"three moves per namespace: collect `[:> X …]`, emit `defhost`, rewrite
call sites"* — and if the codemod does that, a migrator's `[:>]` sites become
declarations and **rows 1, 5 and 7 never reach them**: the declaration gets an
`:ssr` policy, the call sites get `:key` in the props map, and the nested-map
conversion is the same either way but is now attached to a named, greppable
crossing.

Three things the codemod must therefore handle, which fall out of this table:

1. **`^{:key k}` metadata** must be rewritten into the props map (row 5) — this
   is not `[:>]`-specific, it applies to every rewritten element.
2. **Nested prop maps** cannot be converted mechanically (row 1). The codemod
   should leave them and flag the site, because deciding which nested map is
   options and which is data is exactly the guess HD-011's shallow default
   refuses to make.
3. **Every emitted `defhost` needs an `:ssr` decision** (row 7). `:client-only`
   is the safe default and the codemod should emit nothing, but the *report*
   should list every crossing so a person can pick the fallbacks.

And the guide needs a short **"If you know Reagent's `[:>]`"** table — rows 1, 5,
6, 7 and 8 — paid for only by readers who are migrating.

---

## What a reader must learn

The stance says every concept is one a reader must hold, and the guide just came
down from 2,633 to 1,875 lines. This design's whole additional teaching surface:

| Added | Cost |
|---|---|
| The spelling `[:> Component props & children]` | One line. Reagent users already have it |
| *"Declaration-less, so the prop's spelling picks the contract — like a native element"* | One sentence, and it **deletes** the alternative's paragraph explaining why callbacks are different here |
| *"Nothing server-side, like every foreign region"* | One row extending a table that exists |
| *"It renders exactly what the declaration would; what it lacks is a name"* | One sentence |
| Refusal roster (5 ids) | Zero — met only when already wrong |
| "If you know Reagent's `[:>]`" table | 5 rows, paid only by migrants |

**One spelling, three sentences, one table for migrants.** Everything else is
`defhost`'s concept reused, which is what "the escape is `defhost` minus the
declaration" is *for*: it is a compression claim, not a slogan.

The existing guide sites need only tense flips plus these, and one of them
already says the right thing: `05-interop.md:174`'s troubleshooting row
(*"A `[:>]` node (once built) has reduced structural identity | Prefer `defhost`,
or assert around the node"*) is correct as written and needs the parenthetical
removed, nothing more.

---

## Needs a ruling

**R1 — `defhost` has no `:ssr` value that renders children.**
`mint-host-gate!` returns a single pre-walked `placeholder` and never consults
`props.children`, so an unadopted crossing drops its subtree. For a leaf widget
that is right; for a **provider** — one of HD-011's five named use cases, and the
guide's own example at `05-interop.md:120` — it deletes the application from the
server response. The `:ssr` ruling says *"there is no third value."* Either a
third value exists (`:ssr :children` / `:transparent`, meaning "render the
children in place of the component"), or providers are ruled out of SSR and the
guide says so. **Recommendation: the third value**, because it is the honest
reading of what a transparent wrapper *is*, it costs one row in a table that
exists, and without it the escape's provider use case has no recovery at all.
This is `defhost`'s question; the escape only inherits the answer.

**R2 — does the escape read the carrier, or only the declaration?**
§Edge 2 recommends `lower-prop` (position selects the contract) and gives the
argument. The literal reading of "the same foreign path as `defhost`" gives the
opposite. The two readings differ in whether the escape can attach a handler at
all, so this is the design's single most consequential open question and the
adversarial passes should attack it directly.

**R3 — refusing a Hicasso view head or a `defhost` declaration in Component
position.** Both are values React accepts, so refusing them goes beyond clause
5's letter. §Edge 4 argues for it on silent-failure grounds. Cheap to overturn.

---

## Beads this design filed

1. **`rf2-l0wfx` (P1) — `defhost` `:ssr` drops children.** R1 above. P1 because
   it makes the guide's provider example wrong under SSR, and SSR is now
   required scope.
2. **`rf2-vrvv9` (P2) — `host-prop-value` drops a namespaced keyword's namespace.**
   `{:value :theme/dark}` crosses as `"dark"`. This matches stock Reagent, but
   `reagent-slim` deliberately narrowed it after an audit (its own docstring
   names the case: *"a keyword like `:rf/foo` on a React-context Provider's
   `:value` is preserved"*). Hicasso's broad `(name v)` re-opens the seam that
   audit closed, and it lands hardest on providers. A `defhost` question, not the
   escape's.
3. **`rf2-d03av` (P3) — HD-016's "callback refs only" is not enforced against
   object refs.** `check-ref!` refuses a vector only. Either the rule is narrower
   than it reads, or the check is. §Edge 4.

---

## What this design does not decide

- The head **spelling** — `:>` is ruled, and the case for a different spelling
  (a silently-different meaning for a familiar one being the worst outcome
  available) is answered here by making the differences loud where they can be
  and written down where they cannot.
- Anything about `:r>`, the class-component `__rfArgv` crossing, or bare-head
  auto-hosting. All three stay absent; the last stays rejected.
- The framework's non-Hicasso SSR emitter already throws on `[:> …]`
  (`implementation/ssr/test/re_frame/ssr_emit_test.clj`). That is a different
  lane — Hicasso's server walk is React's `renderToString`, not that emitter —
  and nothing here changes it.
