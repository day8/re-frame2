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

**Freehand has no `local`, and that is the design.** So the Reagent question
"is this ephemeral enough to keep in the component?" has no yes-branch, and the
real decision is *which owner does this value belong to* — three answers, in
order of how often they are right:

- **Product state → app-db.** Anything another view, an event, a test, a tool or
  a reload cares about — a selected filter, a draft the app acts on, a panel the
  URL should reflect. A `reg-event` writes it, a `reg-sub` reads it, the view
  becomes a pure render. This is a dataflow change the skill *names* for the
  author (cardinal rule 5), not a view rewrite. Do not be squeamish about
  "app-db for a dropdown flag": frame state is cheap, and it is what makes the
  toggle inspectable, replayable and testable.

- **A control that owns a genuine multi-interaction protocol → a semantic
  controller.** A field that drafts and commits on blur-or-Enter, a typeahead
  holding a typed query beside a settled one. A controller is **not a new kind
  of thing**: it is an ordinary `v/defview` plus ordinary `reg-sub` /
  `reg-event`, whose state is ordinary frame data. What the substrate adds is
  three verbs answering *where* the record lives and *when* it is still the one
  the caller means:

  ```clojure
  (v/defview buffered-field [props]
    (let [k        (v/controller-key ::buffered-field props)      ; [kind address]
          revision (v/controller-revision ::buffered-field props)] ; the :reset-key
      …))
  ```

  `v/controller-key` pairs the library's own `kind` keyword with the caller's
  `:control` address — immutable EDN naming the domain thing that owns the state
  (`[:invoice 42 :amount]`), never a DOM id or anything derived from render
  position. An absent `:control` is refused rather than defaulted, and an
  explicit `nil` is refused separately — `nil` is not an address, and the two
  are different mistakes with different fixes. Asking for
  the key is what makes a controller **writable**; asking for the revision is
  what makes it **buffered**, and the two are separable.
  `v/controller-current?` is the generation fence, asked at both boundaries — a
  draft is *displayed* only while current, and only a current record may produce
  the caller's intent. It is total and safe in the missing direction: an absent
  stamp is not current, which is the half a hand-rolled `(= a b)` gets wrong.

  **Reach for this only when the control really owns a protocol.** A view that
  takes a value and emits an intent is props-only, calls none of these verbs,
  and pays nothing.

- **State that is genuinely the DOM's → a registered behavior** (MIG-17). Scroll
  offset a library owns, a chart instance, an editor's internal buffer.

Whichever way it goes, the setter closures disappear: `(reset! open? true)`
becomes an event vector at the `:on-click`, and `@open?` becomes a `v/sub`.

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

### Host / DOM work → a registered behavior

Freehand's one sanctioned imperative boundary is a **behavior**: registered code,
bounded to a single node, addressed by a semantic id.

```clojure
(v/defbehavior autosize
  "Grow the textarea to fit its content."
  {:timing     :layout                                       ; before paint
   :connect    (fn [{:keys [node config]}] (fit! node config))
   :update     (fn [{:keys [node config]}] (fit! node config))
   :disconnect (fn [{:keys [memory]}] (some-> memory .disconnect))})

[v/behavior {:use autosize :target :composer/body :config {:max-rows 8}}
 [:textarea.composer {:value draft :on-input [:composer/typed ::v/value]}]]
```

The mapping from Form-3 is direct, and better-specified than what it replaces:

| Reagent Form-3 | Behavior entry |
|---|---|
| `component-did-mount` | `:connect` — runs once, at the commit that mounts the node; its return is the connection's private memory |
| `component-did-update` | `:update` — runs **only** when the committed `:config` moves by `rf=`, with `:prev-config` alongside |
| `component-will-unmount` | `:disconnect` — exactly once per committed connection, after release |

Two things need a decision rather than a translation, and both are traps
([`gotchas.md`](gotchas.md) §Behaviors): **`:timing`** is a closed pair and the
default (`:passive`, after paint) is *not* where `component-did-mount` ran, so
measure-then-place work has to declare `:layout`; and **`:config` is data at
every depth**, so a function the Form-3 body closed over becomes registered code
or a `:commands` entry rather than a config value. A one-shot operation on a
live connection is an ordinary effect against
`:re-frame.freehand.host/command`, addressed by `:target`.

`:opaque true` declares that the behavior owns the node's descendants, which
makes Freehand children on that node an error rather than content the host would
silently overwrite — use it for editors and charts that render their own DOM.

### Domain work on MOUNT → an ordinary event

"Mark viewed", "load on mount" is not host work. There is deliberately no
`:on-mount` primitive: it becomes the root's frame `:initial-events` (MIG-15), a
route's entry cascade, or an ordinary event the app already dispatches. Name it
for the author.

### Domain work on UNMOUNT → re-home it out of the view

A Reagent view that dispatched a domain event from `:component-will-unmount`
("release a held resource", "mark the draft abandoned") has **no Freehand
equivalent, by design** — and a behavior's `:disconnect` is not a place to
smuggle it back in. Mount and unmount are host facts, not domain facts.

So re-home the lifetime to whatever **causally** ends the thing: a route's
`:resources` entry released on every route leave, a machine actor, or a semantic
app event that mints an owner and a matching event that ends it. The discipline
is completeness — enumerate **every** exit path and prove each one releases. The
classic leak is an enumeration that covers "save" and "delete" and misses
"navigate away". Scope that owner with the author (cardinal rule 5).

### `:component-did-catch` → `v/error-boundary`

```clojure
[v/error-boundary {:fallback [broken-page {}]
                   :reset-key route-revision
                   :on-error  [:telemetry/ui-render-failed]}
 [workspace-page {:workspace-id id}]]
```

The option roster is closed. It catches render-class failures below it — not
event-handler, asynchronous, or re-frame handler/sub failures, which keep their
own typed owners. Changing `:reset-key` by `rf=` clears the failure and re-mounts
the child; there is no imperative reset handle. If the Reagent boundary's
recovery semantics don't fit that shape, the view stays on Reagent.

### The paired snapshot protocol has no target

`:get-snapshot-before-update` + `:component-did-update` (scroll restoration reading
pre-mutation geometry) has no Freehand door: a behavior's `:update` runs after
React has already mutated the DOM. **Hold the whole view** on Reagent.

## MIG-18 — non-conforming `:on-*` handlers

A DOM `:on-*` handler whose body fails the three clean shapes (MIG-04/05/06) — mixed
local work plus a dispatch, a *guarded* dispatch, a dispatch of a computed vector,
or pure imperative work:

```clojure
;; before — mixed local work + dispatch
{:on-click (fn [e] (.preventDefault e) (when ok? (dispatch [:save])))}
```

**The decision: split local work from app intent, then pick the form.** A
converted `v/defview` has no ambient `dispatch`, so this is not a one-line lift:

- **A guarded or payload-extracting dispatch → `v/event`.** Its body runs
  synchronously with the live callback arguments and **names its outcome**: an
  event vector dispatches, `nil` dispatches nothing. That `nil` *is* the guard —
  `(when ok? [:save])` expressed as data-with-a-filter. It may not `v/sub`.
- **Pure imperative work whose return is irrelevant → `v/handler`.** Stable per
  site, retired with its site, so a listener that outlives its view is inert
  rather than firing into a successor.
- **Browser mechanics → the options map** (`:prevent-default`,
  `:stop-propagation`), not `(.preventDefault e)` inside a closure.
- **Mixed** — the imperative half is a `v/handler` or a behavior; the app intent
  is a vector on the natural element. Prefer splitting to writing one closure
  that does both.

Coupled to MIG-16's state decision (local work often read view-held state), which
is why a MIG-18 hit **gates the view**.

## MIG-19 — derived state (`r/track` / `r/cursor` / `reaction`)

```clojure
(let [x (r/track compute a)] [:div @x])
```

**The decision: name the sub, design its query, rewire the call sites.** The
compute fn copies almost verbatim into `(reg-sub :ns/name …)`; the read becomes
`(v/sub [:ns/name a])`. You gain caching, tool visibility and a JVM-testable
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
reads become subs, the writes become events, and an `add-watch` becomes the event
handler that made the change — the handler carries the consequence. Do **not**
mechanically translate a watcher into post-commit work: a watcher fires
synchronously mid-`swap!`, before render; anything post-commit runs after. That
swap is a behaviour change no diff review catches.

**Compat escape:** the views on this store stay on Reagent until the store is
restructured. This is a dataflow re-model the skill scopes and names; the author
does it.

## MIG-23 — SSR (`render-to-string` / hydrate)

**The decision: which SSR path?**

- **Static-page (non-hydrating) HTML** → `(v/render-static [app-root {}])`. The
  pure `:server`-phase render — Freehand's `renderToStaticMarkup` — folding the
  JVM structural tree to a string. JVM/server only, and the root form is
  **literal** (it is a macro). It refuses to elide silently: a runtime-requiring
  capability anywhere in the server-reachable closure is a loud build error with
  source coordinates, not a capability quietly dropped. Requires
  `day8/re-frame2-ssr` on the classpath at render time.
- **SSR-then-hydrate** → `re-frame.ssr/hydrate!` seeds the state, then
  `v/hydrate-root` adopts the server's DOM. Identity comes **from the server**,
  so identity opts (`:root-id`, `:disambiguator`, `:identifier-prefix`) are
  refused client-side; `:frame` and the host error callbacks are accepted exactly
  as at `v/mount`. A container with nothing to adopt falls back to an ordinary
  client mount.

Verify the server half of the author's pipeline against `re-frame.ssr`'s actual
exports before assuming a helper exists; if the page-assembly they need isn't
there, hold the hydrating root on Reagent and keep the static arm.

## MIG-26 — ambient `subscribe`/`dispatch` in a plain `defn`

Reagent let a plain fn reach an ambient global frame. In Freehand a `v/sub` from
an ordinary `defn` **is legal** — the render owns the read wherever the call
lexically sits, on the same thread. What fails is a read with **no active
render**: a timer, a callback, a `v/event` body, the REPL, all raise
`:rf.error/view-read-outside-render` (a read conveyed to another thread raises
`:rf.error/view-forked-capture`).

**The decision is therefore about *when*, not *where*.** Grep these out rather
than discovering them by clicking. Preference order: (1) if it runs during
render, leave it in the helper — that is the supported shape; (2) if it runs
later, hoist the read to render time and pass the value into the callback; (3) if
it genuinely must read at an arbitrary moment, that is `rf/subscribe-once`, a
`re-frame.core` verb, not a view one.

## MIG-30 — runtime-built markup (`(md/render …)` walking an AST)

**Interpreted Freehand takes this directly** — a helper returning hiccup data is
ordinary content, so most of these views convert unchanged. The decision only
appears if the *calling* view is later promoted with `{:compiled true}`: the
compiled tier lowers a finite grammar and cannot lower a runtime value, so the
crossing is spelled explicitly:

```clojure
(v/defview editor {:compiled true} [{:keys [error hint]}]
  [:section
   [v/markup {:value (field-help error hint)}]])
```

`v/markup` is an ordinary declared view whose `:value` is anything a body may
return. The compiled parent sees one descriptor boundary; the child owns the
walk. There is no automatic dynamic-markup crossing and no `v/interp`.

## The other judgment calls (decide, then hold-or-convert whole)

| MIG | Construct | The decision |
|---|---|---|
| **MIG-08** | unkeyed `for`; per-row reads; loop-capturing handlers | Interpreted Freehand allows a per-row `v/sub` and a capturing handler, so this is a **shaping** call rather than a forced extraction: extract a keyed child view when the row has its own reads and intents (it also gives you the per-row memo boundary), keep the inline `for` when the row is presentational. Keys are React's list identity either way. |
| **MIG-13** | markup-returning `(map (fn …) xs)` in child position | Rewrite to a keyed `for` — `(for [t ts] [item {:key (:id t) :t t}])`. Mechanical only when the fn is a literal with a keyed hiccup body; confirm the candidate. |
| **MIG-27** | fn-valued prop on an **internal-view** call site | A plain fn prop is an opaque identity-compared value. *Recommend*, don't force: forward a **data vector** where you want tool visibility (`:on-commit [:commit]`, and the child places it at its own DOM `:on-*` site), or a `v/render-fn` where the prop is genuinely parameterized content the child renders through `(v/slot …)`. |
| **MIG-28** | computed / dynamic DOM props | Two forwards, and the grammar makes you pick. `(v/spread base overrides)` is the **visible-cost** forward: whatever the map carries lands, and the author said so at the site. `(v/spread-safe owned caller)` is the **bounded** one a component library wants — `:key`, `:ref`, `:value`, `:checked` and the component's own `on-*` families may not appear in `caller`, in every build; survivors fold under the owned props, with `:class` composing. A control that must stay controlled uses `spread-safe`. |
| **MIG-31** | `capture-frame` in a render body | Freehand exposes no view-tier frame handle. Decide where the async work belongs: usually it re-homes to an event (the handler already runs against the committed frame), or the callback becomes a `v/event`/`v/handler` at the site that owns it. If the work genuinely needs a carried frame, hold the view. |

Every row above is a view the skill leaves whole until the decision is made.
Decide it, then convert the whole view or hold the whole view — never a partial
body.
