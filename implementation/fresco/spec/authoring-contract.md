# Fresco authoring contract

What `re-frame.fresco` promises to the code written against it, in the present
tense and one answer per question. **This file governs.** Where a docstring, a
guide chapter or a page under `docs/design/fresco/` says something different,
this file is right and the other text is the defect.

It is the contract of this ClojureScript implementation, not a portable view
contract; the root corpus asserts none. Fresco keeps the root specs it sits on:
[`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md) for
the substrate and the client root, and
[`spec/004C-Roots-and-Mount.md`](../../../spec/004C-Roots-and-Mount.md) for root
identity and the hydrating boot. Its siblings here own three narrower facts:
[`invariants.md`](invariants.md) the fifteen product invariants,
[`server-policy.md`](server-policy.md) every public surface's server policy, and
[`complaints.md`](complaints.md) the index of diagnostic ids. What an id
carries is the Fresco section of
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md). The
guide, [`docs/core/fresco/`](../../../docs/core/fresco/index.md), teaches the
same rules with worked examples, and the design record,
[`docs/design/fresco/`](../../../docs/design/fresco/README.md), holds the
reasoning and the measurements behind them.

Every name below is reached through one alias:

```clojure
(:require [re-frame.fresco :as h])
```

## The boundary ABI

`(h/defview name doc? [props] body)` defines a **view**: a real React function
component. Write it at namespace top level. The var is a Hiccup head.

```clojure
(h/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (h/sub [:todo/by-id id])]
    [:li {:class (when done? :done) :on-click [:todo/toggle id]} title]))

[todo-row {:key id :id id}]
```

**The head set is closed.** A Hiccup head is a native tag keyword, with its
`#id.class` shorthand; a view; a host (`h/defhost`); `[:> Component …]`; a
fragment, `[:<> …]`; or one of the package's own heads, `h/frame-root`,
`h/frame-provider`, `h/portal` and `h/error-boundary`. Anything else in head
position, a plain function included, raises `:rf.error/fresco-bad-head`, and
`[]` raises `:rf.error/fresco-empty-vector`.

**A view takes one props map**, as a ClojureScript value. Trailing children
arrive as `(:children props)`, a vector of forms. A literal `:key` is taken off
before the body sees the props. Every lazy sequence reachable from the props is
forced in the render of the body that wrote them, and an unforced `delay` there
raises `:rf.error/fresco-deferred-read-at-boundary`.

**Equal props skip the body.** A view compares its whole props map with `=`;
when it is equal to the last render's, the body does not run because its parent
did. Functions and JavaScript objects compare by identity. There is no opt-out.
A view's own changed read runs its body whatever its props.

**Children.** `nil` and `false` render nothing; `true` raises
`:rf.error/fresco-true-child`; a string or number is text; a keyword or symbol
renders as its name; a React element passes through; a sequence is realised once
and spliced. Any other value is handed to React as it stands. A body returns
`nil`, one form or a fragment.

**Native attributes.** A key is emitted under one React name in every spelling
— keyword, string, symbol or namespaced keyword: kebab case becomes camel case,
`aria-*`, `data-*` and `--custom` properties pass through, and `class`, `for` and
`charset` become `className`, `htmlFor` and `charSet`. Values convert one level:
a nested map such as `:style` has its keys converted, keywords and symbols become
their names, and functions cross by identity. `:class` takes a string, keyword,
symbol or collection, drops `nil`s and joins the rest, and composes with the
tag's classes; an explicit `:id` wins over the shorthand id. `:key` is React's
and is never emitted as an attribute. `:ref` crosses untouched, so a function
there is React's callback ref.

**A body is a render.** It reads, and it writes nothing: a body that writes
app-db on every run raises `:rf.error/fresco-generation-fence-exhausted` after
four consecutive runs. A body calls no React hook; the
boundary's own two hooks are its whole budget, and React's rules-of-hooks error
is the enforcement. A hook-owning region belongs in a React island mounted
through `h/defhost`.

**Registration.** In a debug build a view is also registered under
`(keyword "<ns>" "<name>")`, so `(rf/view id)` answers it; in a release build it
answers `nil`.

## Reads

`(h/sub query-v)` returns the subscription's current value. It is legal during
the direct synchronous execution of a view body, including helpers, branches,
loops, and lazy children the same render forces.

**A read is an edge.** The view re-renders when a value it read changes. A
branch that did not run contributes nothing, and a view's dependencies are
exactly the reads of its latest committed render. Subscription identity is
`(query-id, args)` under value equality. A render React abandons or retries
installs no subscription.

**A read outside that extent refuses** with `:rf.error/fresco-sub-outside-render`,
naming the query: a callback, a timer, a promise continuation, a render carrier
invoked by a foreign component, a lazy sequence forced after the render, and a
module-level read alike. Read in the body and close over the value, or move the
work into an event that declares its state with `:rf.cofx/requires`.

**Inside a body, the ambient core doors refuse.** An ambient `rf/subscribe` or
`rf/dispatch` raises core's refusal (spec/002 §The refusal tier); an explicitly
framed call still answers. `rf/current-frame-id` and zero-arity
`rf/capture-frame` answer the body's own frame.

**One limit is declared rather than refused**: a thunk parked in a mutable
reference and forced inside another view's render is recorded as that view's
read. That conduct is undefined.

## Intents

### Event props

A prop spelled `:on-<event>` or `:on<Event>`, as a keyword or a string, is an
**event prop**. There is no roster of event names. What an event prop does
depends on the value written there:

| Value | What happens |
| --- | --- |
| a vector — an **intent** | dispatched into the writing view's frame when the event fires |
| a map — a **key map** | the entry for the pressed key, spelled as `KeyboardEvent.key`, runs: a vector is dispatched, a carrier or function is called. Keystrokes during IME composition are ignored |
| a carrier | its own meaning, under [Carriers](#carriers) |
| a plain function | crosses to React by identity |
| anything else | crosses untouched |

At any other prop a vector or map is data. An intent is data in the tree as
well, so a test compares it with `=`.

**The frame is fixed at render.** An intent closes over the frame of the view
whose render lowered it, however much later it fires. Lowering one with no
frame in scope raises `:rf.error/fresco-intent-outside-boundary`.

**Prevention is explicit**, except at `:on-submit`, where an intent or key map
calls `.preventDefault` before it dispatches. No other prop prevents by default,
and a carrier is never prevented for you: whoever holds the event owns it.

**The intent spelling is event-first.** A marker, `::h/prevent`, `:on-submit`'s
prevention and a key map read the DOM event from the invoker's first argument;
an invoker that passes something else first raises
`:rf.error/fresco-intent-needs-the-event`. An intent with none of those never
reads its argument, so it works under any invoker.

### Markers

`::h/value` and `::h/checked`, written at the top level of an intent, are
replaced when it fires by the event target's current value and checked state.
`::h/value` on a `<select multiple>` is the vector of selected option values,
`[]` when none is selected. On a file input it raises
`:rf.error/fresco-file-input-value-marker`; read `.files` through `h/event`
there.

### Reserved heads

Two intent heads are reserved, and the set is closed.

- `[::h/prevent intent]` calls `.preventDefault`, then dispatches `intent`. It
  wraps exactly one non-empty intent vector, which may carry markers and may not
  be another reserved head; anything else raises
  `:rf.error/fresco-malformed-prevent`.
- The navigate head is minted by `h/route-link`, which renders one real anchor
  whose click is routing's decision. No author writes it. A route link's
  `:on-click` takes `nil`, `[::h/prevent intent]`, `h/event` or a plain
  function; anything else raises `:rf.error/fresco-route-link-bad-on-click`.

### Carriers

A **carrier** is an ordinary function marked with its kind. The kind is its
meaning, at every prop of a native tag, a host or a `[:>]` crossing, and in a
key map's entry. The prop's name selects nothing, so a carrier moved between
props keeps its effect.

| Carrier | Meaning |
| --- | --- |
| `(h/event [args] body)` | the body receives every argument the invoker passes, in order; a returned vector is dispatched into the writing view's frame, and any other return is ignored |
| `(h/render-fn [args] body)` | for a callback a foreign component invokes for markup during its own render: the returned Hiccup becomes a React element, intents inside it dispatch into the writing view's frame, and the carrier dispatches nothing itself |

```clojure
[date-picker {:on-change (h/event [date _e] [:todo/set-due id date])}]

[virtual-list {:item-content (h/render-fn [i]
                               [:li {:on-click [:row/pick (nth ids i)]}
                                (nth ids i)])}]
```

- An event carrier that returns a vector with no frame in scope raises
  `:rf.error/fresco-intent-outside-boundary`.
- Inside a render carrier's call, the supplying view's discipline holds:
  `rf/current-frame-id` and zero-arity `rf/capture-frame` answer its frame, and
  an ambient `rf/subscribe` refuses.
- `:ref` is React's, and nothing is lowered there. Where Fresco does not walk at
  all, such as a raw `#js` object, a carrier simply runs as the function it is.
- A carrier at a host prop declared in `:slots` raises
  `:rf.error/fresco-host-unclaimed-callback`.
- A plain function is claimed by no prop, carries no frame, and crosses by
  identity, so `React.memo` and every identity bail-out keep working.

## The controlled-input door

A native `:input`, `:textarea` or `:select` written with `:value`, and a
checkbox or radio written with `:checked`, is **controlled**: the model owns the
displayed value after every commit.

| Control | The view supplies | The event |
| --- | --- | --- |
| text-like `:input` (`text`, `email`, `password`, `search`, `url`, `tel`), `:textarea` | `:value` | `:on-input` with `::h/value` |
| number, date, time and range inputs | `:value` | `:on-input` with `::h/value`, a string the handler parses |
| checkbox, radio option | `:checked` | `:on-change` with `::h/checked`, or the option's value literally |
| `:select` | `:value`; a vector under `:multiple` | `:on-change` with `::h/value` |
| file input | no controlled value | `:on-change` with `h/event`, reading `.files` |

- **Same-turn convergence.** Under a synchronous dispatch the committed value is
  back in the field within the discrete event that edited it. An accepted edit
  stays, a normalised one shows the normalised value, and a rejected one — a
  handler that leaves the value unchanged — shows the model's value.
- **The caret** comes back at the same distance from the end of the committed
  value, a selected range collapses to that caret, and the node is never
  remounted. This holds for `text`, `search`, `url`, `tel` and `password`
  inputs and textareas; `email` and `number` expose no selection, so a rewritten
  edit there leaves the caret at the end.
- **IME composition** is never corrected mid-composition: the field converges
  once, when the composition ends, and a key map ignores composition keystrokes.
- A `nil` `:value` on a text-like input or a textarea shows an empty field that
  stays controlled.
- A write from outside the model — autofill, an extension, a script — is
  replaced at the next commit. An event after unmount is a no-op.
- A contenteditable region is not controlled: a `:value` there reaches React
  untouched.
- **Owned slots win by presence.** In a merged attribute map the later entry
  under the same key wins, `nil` included. The rule is per map key, not per React
  prop: an `:onInput` beside an owned `:on-input` lands on the same React prop
  in map order.

**`::h/revision` is the reset.** Written beside a controlled text `:value`, a
changed revision, compared with `=`, re-baselines the field: the current draft is
discarded, the node and focus are kept, and the caret moves to the end of the
model's value. An equal revision with a new value is an ordinary update; a reset
is never inferred from the value. A revision change during a composition waits
for it to end. `::h/revision` on anything but a controlled text field raises
`:rf.error/fresco-revision-not-controlled`.

## Frames, roots and hydration

### Frame heads

The frame is written in the tree. Two heads write it, into the one React frame
context every view adapter reads.

- **`[h/frame-root {:id kw …} & children]` ensures a frame.** It takes the
  `rf/make-frame` option map whole. An absent frame is created and its
  `:initial-events` run once; a live one is reused, its app-db, runtime-db,
  subscription cache and queue kept, its `:initial-events` recorded and never
  replayed, and its record configuration replaced whole. The ensure is
  commit-owned: the first render emits no children and a discarded render
  creates nothing. Unmounting destroys nothing. `:id` is a required keyword; a
  `:frame` key raises `:rf.error/frame-root-given-frame`; changing a mounted
  head's `:id` or options raises `:rf.error/frame-root-reconfigured` — give the
  head a React `:key` to point it at another frame.
- **`[h/frame-provider {:frame target} & children]` scopes an existing frame.**
  `target` is a frame-id keyword or a live frame value. It creates, refreshes and
  destroys nothing, and renders its children on its first pass. A frame that is
  not live raises `:rf.error/frame-provider-frame-absent`, and an `:id` key raises
  `:rf.error/frame-provider-given-id`.

Everything lowered below a head acts on that head's frame. The calls the
enclosing body makes itself — `h/sub`, `h/route-link`, `rf/capture-frame` —
stay in the body's frame, because they run before the head lowers anything. A
view rendered with no frame above it raises `:rf.error/no-frame-context`.

### The root grammar

Fresco publishes Spec 006 §The client root.

```clojure
(defonce app-root (h/client-root))

(defn ^:dev/after-load mount! []
  (h/render! app-root
             [h/frame-root {:id :app/main :initial-events [[:app/init]]}
              [app {}]]
             (js/document.getElementById "app")))
```

- `(h/client-root)` returns an inert, opaque handle. One handle owns at most one
  React Root at a time.
- `(h/render! handle tree node opts?)` — the first call through a handle creates
  the Root; every later call updates it, so the boot and the hot-reload hook are
  one line. `node` and `opts` take effect on the first call only, and `opts` is
  checked on every call. Every update commits inside `flushSync`. `tree` is
  rooted at a frame head and re-rendered whole, head and options unchanged.
- `opts` carries root options only: `:identifier-prefix` (React's
  `identifierPrefix`), `:on-recoverable-error` (React's `onRecoverableError`)
  and `:hydrate?`. `:frame` or `:initial-events` there raises
  `:rf.error/fresco-frame-config-misplaced`; any other key raises
  `:rf.error/fresco-unknown-root-option`.
- `(h/unmount! handle)` takes that Root down, idempotently, and leaves the
  handle inert for a later `render!`. It destroys no frame. `rf/destroy-adapter!`
  releases a live handle's Root exactly once.

### Hydration

A hydrating boot runs three steps in order: make the frame, install the server's
state with `re-frame.ssr/hydrate!`, then adopt the DOM.

```clojure
(rf/make-frame {:id :app/main})
(ssr/hydrate! {:frame :app/main})
(h/render! app-root
           [h/frame-provider {:frame :app/main} [views/page {}]]
           node
           {:hydrate? true :identifier-prefix "main"})
```

- `{:hydrate? true}` makes the first call adopt the server's DOM rather than
  replace it; on a later call it is ignored.
- The hydrating tree scopes with `h/frame-provider`, because `h/frame-root`'s
  first pass emits no children and the adopting root must render the server's
  shape on its first pass.
- The root takes the same `:identifier-prefix` the server render used.
- A hydrating first call returns before adoption finishes; later calls are
  ordinary `flushSync` updates.
- Each root reports its own recoverable errors. Inside the adoption window a
  recovery is also Spec 011's `:rf.ssr/hydration-mismatch`.

On the server React renders the bytes; there is no second emitter.
`re-frame.fresco.server/render` renders one request in a frame of its own and
raises `:rf.error/ssr-render-failed` when the render recorded an error. An intent
needs no wire form: each side lowers the same vector. What each surface does on
the server is [`server-policy.md`](server-policy.md).

## The interop door

`(h/defhost name doc? component opts?)` declares a foreign React component once,
at namespace top level, and the var is a Hiccup head:

```clojure
(h/defhost date-picker DatePicker)
(h/defhost modal Modal {:slots #{:title :footer}})
(h/defhost chart Chart {:fallback [:div.chart-skeleton]})
(h/defhost themed (.-Provider theme-context) {:server :render})
```

`opts` keys, each optional:

- **`:slots`** — the set of props that take markup. Hiccup at a declared slot
  becomes a React element under the writing view's frame; a string or React
  element passes through; a carrier raises
  `:rf.error/fresco-host-unclaimed-callback`. At an undeclared prop a vector is
  data.
- **`:server`** — `:client-only`, the default, or `:render`. Client-only renders
  nothing on the server and on hydration's first pass, and mounts the component
  once the page is adopted; a fresh mount shows no placeholder. `:render` asserts
  the component renders deterministically on the server, client and hydration
  alike, and is the only policy under which the crossing's children reach the
  server response.
- **`:fallback`** — Client-only's placeholder, inert Hiccup.

Refused when the namespace loads: a `nil` component,
`:rf.error/fresco-host-no-component`; options that are not a map, an unknown
option, a malformed `:slots` set (not a set, an entry naming no prop, `:key` or
`:ref`, one prop spelled twice) or a form after `opts`,
`:rf.error/fresco-bad-host-declaration`; a `:server` value outside the two or a
`:fallback` beside `:render`, `:rf.error/fresco-host-bad-ssr-policy`; and a view or
host head inside a fallback, `:rf.error/fresco-host-fallback-boundary-head`.

**Props cross shallowly.** Top-level names become React names as on a native
tag; names inside a value are left as written. Functions, keywords and
JavaScript objects cross by identity, except that `class`, `id`, `role`,
`data-*` and `aria-*` take the native coercion, and class collections are
joined. A ClojureScript map, vector or set crosses through `clj->js`. `:ref`
crosses untouched and `:key` goes onto the element. Callbacks follow
[Intents](#intents): a carrier keeps its meaning at every prop, an intent or key
map is an event at an `on*` prop, and a plain function crosses by identity; no
declaration names a callback. Hiccup children are converted in the writing
view's render.

A host adds no memo wrapper: it re-enters whenever the view that wrote it
re-renders, so every value that should update it travels on its own props.

**`[:> Component props & children]`** is a host with the declaration erased: the
name tools see is `"[:>]"`, there are no slots, the policy is Client-only with no
fallback, and props and callbacks cross exactly as at a declared host. `nil` or
a view or host head in the component position raises
`:rf.error/fresco-raw-not-a-component`.

**`(h/as-element hiccup)`** is the one explicit Hiccup-to-element conversion,
under the frame of the view currently rendering: for an element at an undeclared
`[:>]` prop, or a Fresco child inside a native React subtree. Converted outside
every render, an intent in it raises `:rf.error/fresco-intent-outside-boundary`.

**`(h/as-component view)`** returns a React component a native parent mounts.
React props arrive as the view's props map under canonical names (`todoId` as
`:todo-id`), values as they cross; the frame comes from React context, and a
view rendered with none raises `:rf.error/no-frame-context`.

**`[h/portal {:target node :fallback hiccup?} & children]`** renders its children
into another DOM container in the same React tree: events bubble through the
React tree, intents keep the writing view's frame, a changed `:target` remounts
the subtree, and the portal is Client-only.

## The incarnation rule

A frame's keyword names an **address**, not an object. Destroying a frame and
creating another under the same id seats a new **incarnation**, and everything a
render minted against the predecessor — a lowered intent, a carrier, a
`capture-frame` bundle, a route link's navigation — belongs to the predecessor
for the rest of its life. Fired after the successor seats, it writes nothing and
raises `:rf.error/frame-destroyed`. **Where a delayed operation lands is fixed
when it is minted, never when it fires.** The successor's next render mints
operations that route to it, and a view mounted across the switch shows the
successor's values before the next paint.

A hot reload is the same rule applied to views: re-evaluating a namespace
re-mints every view it defines, so React remounts each one with everything below
it, and focus, caret, hook state and DOM nodes start over. App-db, the
registered handlers and the frame survive. A reload hook that re-runs
`rf/make-frame` for a live id replaces it in place and keeps every captured
operation addressing it; one that destroys the frame first retires them all
under the rule above.
