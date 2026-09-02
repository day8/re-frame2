# D-tier — "here's how to DECIDE" (judgment)

> This is where the skill earns its keep. A codemod could only *flag* these;
> an AI **reasons** about the right shape. For each rule: the decision the
> source cannot answer, and how to make it. A view that trips any D rule is
> **held whole** — decide it with the author, then convert the whole view, or
> leave the whole view on Reagent (cardinal rule 2).

## MIG-16 — Form-2 / `with-let` view-local state

```clojure
;; before
(defn dropdown []
  (let [open? (r/atom false)]
    (fn [] [:div {:on-click #(reset! open? true)} (when @open? …)])))
```

**Hicasso has no view-local state tier, and that is the design.** There is no
`local`, no `use-state`, and no cell of any kind; an atom allocated in a
`defview` body is re-allocated every render because the body is an anonymous fn
React re-invokes. So the Reagent question "is this ephemeral enough to keep in
the component?" has no yes-branch, and the real decision is *which owner does
this value belong to* — three answers, in order of how often they are right:

- **Product state, or any UI flag → app-db, and `h/reg-state` is the sugar.**
  Anything another view, an event, a test, a tool or a reload cares about — a
  selected filter, an open panel, a draft the app acts on.

  ```clojure
  ;; once, beside the views
  (def tags-open? (h/reg-state ::tags-open? {:default false}))

  ;; in the view
  (h/defview disclosure [{:keys [ikey title]}]
    (let [shown? (h/sub [tags-open? ikey])]
      [:section
       [:button {:on-click [tags-open? ikey (not shown?)]} title]
       (when shown? [:div.body …])]))
  ```

  `reg-state` mints exactly three things — a parametric sub, a setter event
  `[::concern ikey v]`, and participation in the shared clear event
  `[::h/clear ::concern ikey]` — and stores under `[:ui ::concern ikey]` in
  app-db. The concern must be a namespace-qualified keyword; the instance key is
  a keyword, string, number, or a vector of those (`nil` is refused at both read
  and write). **Do not be squeamish about "app-db for a dropdown flag":** frame
  state is cheap, and it is what makes the toggle inspectable and testable.

- **A control that drafts and commits → `re-frame.hicasso.forms/buffered-field`.**
  A field that edits and commits on blur-or-Enter, a typeahead holding a typed
  query beside a settled one. The module is the sanctioned home, and it is
  honest about the bargain: the draft lives in **app-db in front of the
  committed value**, fenced by `::h/revision` — it does not make the draft
  DOM-local. Require it explicitly (`[re-frame.hicasso.forms :as forms]`); it is
  an optional module and absent when unused.

- **State that is genuinely the DOM's → a callback ref, or a React island.**
  Scroll offset a library owns, a chart instance, an editor's internal buffer.
  See MIG-17.

Whichever way it goes, the setter closures disappear: `(reset! open? true)`
becomes an event vector at the `:on-click`, and `@open?` becomes an `h/sub`.

## MIG-17 — Form-3 / `r/create-class` lifecycle

```clojure
;; before
(r/create-class {:component-did-mount     #(focus! …)
                 :component-will-unmount   #(dispatch [:resource/release])
                 :should-component-update  …
                 :reagent-render           (fn [] [:div …])})
```

**The decision: route each lifecycle body by what it actually does.** Mechanical
parts first — delete `:should-component-update` (memoisation makes it dead) and
extract `:reagent-render` as the view body. Then, per body:

### Host / DOM work on mount and unmount → a callback ref

Hicasso's ref is **React's own callback ref**: a function at `:ref`, called with
the node at commit, and **its return value is the detach cleanup**. That one
mechanism answers both `component-did-mount` and `component-will-unmount` for
DOM work.

```clojure
(defn- focus-on-mount [node]          ; top level — see below
  (when node (.focus node)))

(h/defview composer [_]
  [:textarea {:ref focus-on-mount}])
```

Three things are load-bearing:

- **The ref fn must be a stable top-level `def`.** An inline `(fn [n] …)` is a
  fresh identity every render, so React detaches and reattaches on every commit —
  running your mount work repeatedly and your cleanup repeatedly.
- **A VECTOR at `:ref` is not a ref.** It crosses to React as data and the ref
  never fires; the callback function is the only spelling.
- **`:ref` on a `defview` head is not a ref at all.** The boundary path lifts
  only `:key`; a `:ref` written there stays in the props map as ordinary data
  and nothing checks it. Put the ref on the element you actually want.

If the work is more than "touch this node" — a chart, an editor, anything with
its own hook-shaped lifecycle — that is a React island: a UIx `defui` or a raw
React function component, mounted through `h/defhost`, where ordinary React
hooks are legal because you control the source and its call order. `n/use-sub`
(a read joined to the island's frame) and `n/use-frame` (a dispatch pinned to
that frame's incarnation) are the two Hicasso adds, for when the island needs
Hicasso state.

### Domain work on MOUNT → an ordinary event

"Mark viewed", "load on mount" is not host work. There is no `:on-mount`
primitive: it becomes the frame's `:initial-events` at `make-frame` (MIG-15), a
route's entry cascade, or an ordinary event the app already dispatches. Name it
for the author.

### Domain work on UNMOUNT → re-home it out of the view

A Reagent view that dispatched a domain event from `:component-will-unmount`
("release a held resource", "mark the draft abandoned") should not be smuggled
into a ref's cleanup. Mount and unmount are host facts, not domain facts, and a
cleanup that fires on every StrictMode double-invoke or every ref-identity churn
is not the lifetime you meant.

Re-home the lifetime to whatever **causally** ends the thing: a route's leave, a
machine actor, or a semantic app event that mints an owner and a matching event
that ends it. The discipline is completeness — enumerate **every** exit path and
prove each one releases. The classic leak is an enumeration that covers "save"
and "delete" and misses "navigate away". Scope that owner with the author
(cardinal rule 5).

### `:component-did-catch` → `h/error-boundary`

```clojure
[h/error-boundary {:fallback [broken-page {}]
                   :reset-key route-revision
                   :on-error  [:telemetry/ui-render-failed]}
 [workspace-page {:workspace-id id}]]
```

The prop roster is closed — `:fallback`, `:reset-key`, `:on-error`, `:children`
— and anything outside it is refused. `:fallback` is hiccup **or** a
`(fn [error] hiccup)`. `:reset-key` is compared with `=`; a change clears the
caught failure and re-mounts the children, and **the retry is the caller's to
schedule** — there is no imperative reset handle. `:on-error` is an intent
vector (dispatched with the error appended) or a plain function, fires once per
caught failure, and a bare keyword there is refused at *first render* rather
than at first failure.

It catches render-class failures below it — not event-handler, asynchronous, or
re-frame handler/sub failures, which keep their own owners.

### `:component-did-update` has no answer here → R-tier

See [`catalog-reject.md`](catalog-reject.md) MIG-36.

## MIG-18 — non-conforming `:on-*` handlers

A DOM `:on-*` handler whose body fails the clean shapes (MIG-04/05/06/33) —
mixed local work plus a dispatch, a *guarded* dispatch, a dispatch of a computed
vector, or pure imperative work:

```clojure
;; before — mixed browser mechanic + guard + dispatch
{:on-click (fn [e] (.preventDefault e) (when ok? (dispatch [:save])))}
```

**The decision: split local work from app intent, then pick the form.** A
converted `h/defview` has no ambient `dispatch`, so this is not a one-line lift.
Hicasso gives you exactly one escape and it is deliberately plain:

- **A guarded or payload-shaping dispatch → `h/event`.** Its body runs with the
  live callback arguments, and **only a returned vector is dispatched** — any
  other return, `nil` included, is ignored. That *is* the guard, expressed as
  data-with-a-filter:

  ```clojure
  {:on-click (h/event [e] (.preventDefault e) (when ok? [:save]))}
  ```

- **Pure imperative work whose return is irrelevant → a plain function.** It
  crosses to React by identity and Hicasso does not touch it. This is legal and
  supported — the only rule is that it must not try to `dispatch` ambiently.
- **Prefer splitting to writing one closure that does both.** The imperative
  half goes in the callback; the app intent goes on the natural element as a
  vector, where a test and a tool can read it.

`h/event` receives **every argument the invoker passed, in order** — it does not
assume an event at position one, which is what makes it the right answer at a
value-first foreign callback too (MIG-10).

One failure mode to know: an `h/event` that returns a vector but captured no
frame raises `:rf.error/hicasso-intent-outside-boundary` at *fire* time, not at
render. A `:render`-contract callback is pure by contract — its return is the
render output — and nothing polices a dispatch from inside it beyond React's
own render-phase warnings.

Coupled to MIG-16's state decision (local work often read view-held state),
which is why a MIG-18 hit **gates the view**.

## MIG-19 — derived state (`r/track` / `r/cursor` / `reaction`)

```clojure
(let [x (r/track compute a)] [:div @x])
```

**The decision: name the sub, design its query, rewire the call sites.** The
compute fn copies almost verbatim into `(reg-sub :ns/name …)`; the read becomes
`(h/sub [:ns/name a])`. You gain caching, tool visibility and a JVM-testable
value. Two things need real thought:

- **An `r/cursor` has a WRITE side** the read-rewrite must not leave behind.
  `(reset! the-cursor v)` is silent mutation from anywhere; each write site needs
  a named event. Enumerating them is the point of the move.
- **A `track` capturing a component-local value** can't become a global sub
  verbatim — parameterise the captured value into the query vector.

## MIG-20 — ratom-as-store (a second state model)

```clojure
(def app-state (r/atom {}))         ; top-level, read by many views
(add-watch app-state :k (fn [_ _ _ v] …))
```

**The decision: this is a second state model — restructure it into app-db.** The
reads become subs, the writes become events, and an `add-watch` becomes the
event handler that made the change — the handler carries the consequence. Do
**not** mechanically translate a watcher into post-commit work: a watcher fires
synchronously mid-`swap!`, before render; anything post-commit runs after. That
swap is a behaviour change no diff review catches.

**Compat escape:** the views on this store stay on Reagent until the store is
restructured. This is a dataflow re-model the skill scopes and names; the author
does it.

## MIG-09 / 10 / 22 — foreign React, and the props that cross it

`[:> Component props & children]` is **legal in Hicasso and still works**, so a
foreign React component never forces a whole view onto Reagent. The decision is
*which door*, and it turns on one question: **does this crossing repeat?**

- **A one-off crossing → keep `[:> Component …]`.** It is `defhost` with the
  declaration erased: no `:slots`, no `:callbacks` override, no server policy.
  Callbacks are inferred from the spelling on both, so an intent or `h/event`
  at an `on*` prop of the escape dispatches. Hiccup written at one of its props
  is **data**, not markup — cross a single element with
  `(h/as-element [:h2 "Tasks"])` when you need one.
- **A repeated crossing → declare it once with `h/defhost`**, and the var is a
  legal hiccup head indistinguishable from a view:

  ```clojure
  (h/defhost date-picker DatePicker)

  [date-picker {:selected due-date :on-change (h/event [date & _] [:task/set-due date])}]
  ```

  **Callback contracts are inferred from the prop's spelling, exactly as on a
  native tag**: an `on*` prop is an event position, any other prop that takes
  `h/event` is a render position, and a plain function crosses untouched
  anywhere. `:callbacks` is an **override** — `{prop :event|:render}` — written
  only where the spelling is wrong. `:slots` names the ReactNode positions, so
  hiccup written there is lowered under the writing boundary's frame. `:server`
  is `:client-only` (default) or `:render`, and `:fallback` is Client-only's
  placeholder.

**The decision you must not get wrong is the on*-named render prop**, and it is
the one the source cannot answer. Some vendors name render props `on*` —
Fluent's `onRender*` family, Ant's `onRow`, `onCell` and `onFilter` — and the
inferred `:event` wrapper replaces such a callback's return with `nil`, which
blanks the UI with no useful error. **Read the library's documentation for
every `on*` prop's return value**, and where the library consumes it, declare
the override once on the host:

```clojure
(h/defhost details-list DetailsList {:callbacks {:on-render-item :render}})
```

Infer the usual case; override the vendor exception. There is no `:handler`
contract to declare — a plain function is that contract.

Two migration behaviours are worth pre-empting:

- **An intent vector at an `on*` prop of a former Reagent crossing now
  dispatches.** Under Reagent that vector crossed as an inert JavaScript array
  and never produced a working handler; the migration turns a dead handler
  live. Decide whether it was ever meant to run, and whether the prop is an
  event position at all.
- **EVENT-FIRST vs VALUE-FIRST.** The vector's markers and `::h/prevent` read
  the DOM event from argument one. A library that calls `onChange(date, event)`
  is value-first, so `[:task/set-due ::h/value]` raises
  `:rf.error/hicasso-intent-needs-the-event` naming the position, and `h/event` —
  which sees the library's own arguments in order — is the spelling. At an
  event-first foreign callback the vector is legal and shorter.

**MIG-22 — a third-party Reagent wrapper (re-com et al.) is a Reagent
component, not a React one.** `r/reactify-component` makes it crossable, and the
census reports every such site. That is two renderers in one tree: a judgment
call, worth measuring. The cleaner move is often the **outward** bridge — keep
the wrapper subtree on Reagent and hand a converted Hicasso view up to it with
`(def card* (h/as-component card))`, declared once at top level beside the view.
The parent's props arrive as the view's ordinary props map, children at
`:children`, and the frame comes from React context.

## MIG-26 — ambient `subscribe`/`dispatch` in a plain `defn`

Reagent let a plain fn reach an ambient global frame. Under Hicasso an `h/sub`
from an ordinary `defn` **is legal** — the collector is ambient, and the render
owns the read wherever the call lexically sits. What fails is a read or a
dispatch with **no active render**: a timer, a callback the browser invokes
later, a foreign listener.

**The decision is therefore about *when*, not *where*.** Three different
leftovers fail at three different times under three different ids, and telling
them apart is most of the debugging:

- **A leftover ambient `rf/subscribe` or `rf/dispatch` fails at RENDER**, and
  this is the one the helper case makes easy to miss. A boundary body runs
  inside an extent that *refuses* ambient frame resolution, so the first render
  raises `:rf.error/ambient-frame-refused` — and the extent reaches **the helper
  too**, because a parens-called `defn` runs inside whoever called it. So
  MIG-02's deref-drop applies to the helper as well: an `h/sub` there is legal,
  a surviving `@(rf/subscribe …)` there is not. The refusal is deliberately
  **not** `:rf.error/no-frame-context` — a refused ambient read and a genuinely
  frameless one are different mistakes — so it says in as many words that a
  frame IS in scope and that another boundary will not fix it. Its `:reason`
  names both recoveries: the collector for a read, an intent at a handler
  position for a dispatch.
- **An ambient `rf/dispatch` from a callback fails at CLICK**, raising
  `:rf.error/no-frame-context` — **core's id, not a `hicasso-*` one**. Nothing
  refuses it at render, which is what makes it the nastiest one in the
  migration.
- **An `h/sub` hoisted too far fails at FIRE.** Moved *out* of the render and
  into the callback it was meant to serve, it raises
  `:rf.error/hicasso-sub-outside-render`: hoist the READ to render time and
  close over the VALUE, not the read. `rf/subscribe-once` is the sanctioned
  snapshot for handler and utility code.
- Preference order: (1) if it runs during render, leave it in the helper —
  deref-dropped, that is the supported shape; (2) if it runs later, **hoist the
  read to render time and pass the value into the callback**; (3) if the
  callback genuinely needs to act, it returns an intent vector from an
  `h/event`, which is the frame-carrying spelling.

Grep these out rather than discovering them by clicking — `gotchas.md`
§Three leftovers, three ids carries the table and how to read the complaint.

## MIG-23 — SSR-then-hydrate

**The pipeline ships, so this is a decision rather than a hold** — and the
decision is infrastructure, not spelling: React renders the server output and
there is no parallel JVM string emitter, so the renderer runs on Node. The full
recipe — the cold two-process boot condition, the server half, the three ordered
client calls, the `:identifier-prefix` contract and what stays out of scope —
is [`ssr-hydrate.md`](ssr-hydrate.md). A client-only migration never opens it.

## The other judgment calls (decide, then hold-or-convert whole)

| MIG | Construct | The decision |
|---|---|---|
| **MIG-08** | unkeyed `for`; per-row reads; loop-capturing handlers | Hicasso allows a per-row `h/sub` and a capturing handler, so this is a **shaping** call rather than a forced extraction: extract a keyed child view when the row has its own reads and intents (it also gives you the per-row memo boundary), keep the inline `for` when the row is presentational. Keys are React's list identity either way — and MIG-07 means an unkeyed `for` is a real defect, not a warning to silence. |
| **MIG-13** | markup-returning `(map (fn …) xs)` in child position | Rewrite to a keyed `for` — `(for [t ts] [item {:key (:id t) :t t}])`. Mechanical only when the fn is a literal with a keyed hiccup body; confirm the candidate. |
| **MIG-27** | fn-valued prop on an **internal-view** call site | A plain fn prop is an opaque identity-compared value. *Recommend*, don't force: forward a **data vector** where you want tool visibility (`:on-commit [:commit]`, and the child places it at its own DOM `:on-*` site). Hicasso has no declared render-slot mechanism for internal views — `:slots` is `defhost`'s, for foreign components — so parameterised content is an ordinary hiccup-valued prop the child places. |
| **MIG-28** | computed / dynamic DOM props (`(merge attrs {…})`) | **A plain `merge`, with the owned keys last, is the one spelling** — what the guide teaches (ch02 §Forward attributes, ch04 §Forward caller attributes) and what ships; there is no reserved merge key and no spread form. Write the caller's map first and the owned literals after it: `[:input (merge (dissoc attrs :key) {:type "text" :value draft :on-change …})]`. The owned keys win by presence because they are merged last; `dissoc` `:key` and `::h/revision` from the forwarded map, since both belong to the wrapper's element. The case where a caller override *should* win is spelled by not writing the owned literal. Forward maps in the same kebab-keyword spelling as the literals — an alternate spelling of the same React slot is a different map key, and which one lands is then map order, not law. |
| **MIG-30** | runtime-built markup (`(md/render …)` walking an AST) | **Converts directly.** A helper returning hiccup is ordinary content and Hicasso walks it; there is no finite grammar to satisfy and no compiled tier to opt into. This is no longer a decision — it is MIG-14 pass-through. |
| **MIG-31** | `capture-frame` in a render body | **The spelling is unchanged.** Zero-arity `(rf/capture-frame)` is legal inside a Hicasso body and captures the rendering boundary's frame, exactly as it did in the Reagent view; `(rf/current-frame-id)` answers the id. Decide whether the async work belongs in the view at all: usually it re-homes to an event, which already runs against the committed frame. If it genuinely needs a carried frame in the view, keep the capture — but check the re-home first. |

Every row above is a view the skill leaves whole until the decision is made.
Decide it, then convert the whole view or hold the whole view — never a partial
body.
