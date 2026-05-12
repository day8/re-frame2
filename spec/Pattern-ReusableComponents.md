# Pattern — Reusable Components

> **Type:** Pattern
> The entity-id idiom for parameterised widgets — a `customer-card` that works against any customer, where the caller supplies the id and the card subscribes and dispatches in terms of it. Convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

A **convention**, not a Spec. The canonical content is the **entity-id idiom**: the caller passes an id; the subscriptions are parameterised by that id; events dispatched from the view carry the same id; multiple instances of the same view render different entities side by side without sharing state.

No new framework substrate is required — the idiom is built entirely from `reg-sub`'s parameter-vector, `reg-view`'s function arguments, and the standard `dispatch` / `subscribe` surfaces.

## Why

The recurring question:

> "How do I make a `customer-card` view that works against *any* customer, where the consumer supplies the id and the card subscribes via `(subscribe [:customer id])`?"

A single-instance widget hardcodes its slice path — `(subscribe [:current-customer])`. That widget works exactly once on a page. The moment a list view wants to render twenty customers, or a "compare" page wants to show two customers side by side, the hardcoded path becomes the bottleneck: every instance reads the same slice, so every instance shows the same data.

The fix is **identity-as-argument**: the view accepts an id; subscriptions and dispatches are parameterised by it; the slice lookup happens *inside* the sub handler, not at the call site. The same view function instantiates N times against N different entities, each reading its own slice, each dispatching its own scoped events.

## The pattern

Three rules, each illustrated by one form.

### 1. Subs accept the id in the query vector

`reg-sub`'s handler receives the dispatched query vector as its second argument. Destructure the id out of it:

```clojure
(rf/reg-sub :customer
  (fn [db [_ id]]
    (get-in db [:customers id])))
```

The id is part of the **sub's identity** — `(subscribe [:customer 42])` and `(subscribe [:customer 43])` cache as distinct entries in the sub-cache. Two instances of `customer-card` rendered with different ids run two independent computations against two distinct app-db slices.

For derived data, the same destructure pattern composes with signal-vector subs:

```clojure
(rf/reg-sub :customer/display-name
  (fn [[_ id] _] (rf/subscribe [:customer id]))
  (fn [customer _]
    (str (:first-name customer) " " (:last-name customer))))
```

### 2. Views accept the id as a positional arg

The view is an ordinary function. The id is a positional argument:

```clojure
(rf/reg-view customer-card [id]
  (let [customer @(rf/subscribe [:customer id])]
    [:div.customer-card
     [:h3 (:name customer)]
     [:p  (:email customer)]
     [:button {:on-click #(rf/dispatch [:customer/edit id])}
      "Edit"]]))
```

The caller passes the id into the render tree:

```clojure
[customer-card 42]
[customer-card 43]
```

Both instances render simultaneously. Each subscribes to its own slice; each `:on-click` dispatches an event carrying its own id.

### 3. Dispatches carry the id

Every event the view emits carries the id as its first payload position:

```clojure
(rf/reg-event-db :customer/edit
  (fn [db [_ id]]
    (assoc-in db [:ui :editing-customer] id)))

(rf/reg-event-fx :customer/update
  (fn [{:keys [db]} [_ id new-data]]
    {:db (update-in db [:customers id] merge new-data)
     :fx [[:dispatch [:customer/save id]]]}))
```

The event handler now scopes the same way the sub does — it knows *which* customer the user acted on, not just *that* a customer was edited. Multi-identity views (below) depend on this: without the id-on-dispatch rule, two `customer-card`s firing `:customer/edit` are indistinguishable at the event handler.

### Unit of reuse

The unit of reuse is **the bundle** — the view, the subs it reads, and the events it dispatches. A reusable `customer-card` ships the view plus `:customer` (and any `:customer/*` derived subs) plus `:customer/edit` / `:customer/update` (and any other events it raises). Half-bundles do not compose: a view that hardcodes `[:customer id]` but no `:customer` sub is registered cannot mount.

## Multi-identity

Some views need **more than one id** — a transfer screen showing source and destination accounts, a comparison view showing two customers side by side, a dropdown that shows one list of options and tracks a separate "currently selected" value.

Two positional args, two subs, two dispatch-scope ids:

```clojure
(rf/reg-view account-transfer [source-id dest-id]
  (let [source @(rf/subscribe [:account source-id])
        dest   @(rf/subscribe [:account dest-id])]
    [:div.transfer
     [:div.from [:h3 (:name source)] [:p (:balance source)]]
     [:div.to   [:h3 (:name dest)]   [:p (:balance dest)]]
     [:button {:on-click #(rf/dispatch [:account/transfer source-id dest-id 100])}
      "Transfer $100"]]))
```

Each id is independently substitutable; the same view powers any `(source, dest)` pair. The transfer event handler reads both ids out of its payload and updates both slices in one `:db` step.

For the dropdown case — one id for the option list, one for the current selection — the two subs hold different semantic roles but the destructure pattern is the same: each id is just a query-vector parameter.

## Placefulness

V1's `reusable-components.md` raised a real concern: when a re-frame component is packaged as a *library* — distributed for use across different applications — the `app-db` path where its entity lives may vary from one host app to another. App A stores customers at `[:customers id]`; app B stores them at `[:entities :crm :customers id]`. A library-shipped `:customer` sub that hardcodes `[:customers id]` works in A and breaks in B.

V1 named four answers (constrain to one app; standardise paths; parameterise base-path on the component; use DataScript-style indexed storage).

In re-frame2, **frames change the shape of this concern but do not eliminate it**:

- Within a single frame, the path-as-convention rule still applies. The library-author decision is the same — pick a slice and document it, or accept a base-path argument the host app threads through.
- Across frames, the per-frame `app-db` isolation removes one class of collision: a widget rendered inside two different frames sees two different `app-db`s. The entity-id idiom composes naturally — the id selects within a frame; the frame selects which `app-db`. Two customer-cards in two frames against the same id render two independent views of two independent customers.
- For library-shipped widgets meant to drop into arbitrary host apps, the placefulness problem remains: the library's subs must read from a path the host app has agreed to. The recommended convention is to document the path the library reads from and let the host adapt — `[:customers id]` is shorter than threading a `:base-path` argument through every call. Host apps that already store customers elsewhere project into the documented slice via a flow (per [013-Flows.md](013-Flows.md)) rather than reaching across an arbitrary boundary.

The id idiom solves *parameterisation across instances*; per-frame isolation solves *isolation across runtime boundaries*; placefulness remains an author-host coordination concern at the slice-shape level.

## Adapter integration

The idiom is **substrate-agnostic**. The view is an ordinary function of its arguments; the subs are ordinary `reg-sub` registrations; the dispatches are ordinary events. Every reactive-substrate adapter (per [006-ReactiveSubstrate.md](006-ReactiveSubstrate.md)) supports it without special handling:

- **Reagent** — render-tree splice `[customer-card 42]` works as-is; the registered view is a Form-1 fn that closes over its args.
- **Reagent-slim** — same surface.
- **UIx / Helix** — the adapter's `use-subscribe` hook reads the parameterised sub identically; the view is a hooks-style function whose first prop carries the id.

The id idiom predates substrate choice. A view that follows it ports across adapters with zero changes to the view body — only the surrounding component wrapper differs by adapter.

## Anti-patterns

- **Hardcoded slice paths inside a "reusable" component.** A view that calls `(subscribe [:current-customer])` is by definition single-instance. If the same view ever needs to render two entities simultaneously, the singleton sub becomes the bottleneck.
- **Threading the full entity map through the render tree instead of the id.** `[customer-card customer-map]` defeats the sub-cache — every parent re-render reconstructs the map literal, the equality check on the cached sub input fails, and the card re-renders even when its data hasn't changed. Pass the **id**; let the card resolve the entity through its own sub.
- **Asymmetric dispatches.** A view that reads `[:customer id]` but dispatches `[:customer/edit]` without the id is broken under multi-instance rendering — the event handler can't tell which card the user clicked.
- **Storing per-instance UI state in the entity slice.** A `customer-card`'s "expanded?" flag does not belong at `[:customers id :expanded?]` — that conflates the entity with the view's transient UI. Per-instance UI state goes either in a separate `[:ui :customer-cards id]` slice (keyed by the same id) or, when the widget wraps a stateful JS thing, via the stateful-component idiom — see Cross-references.

## Cross-references

- [001-Registration.md](001-Registration.md) — `reg-sub` and `reg-view` registration grammar; the query-vector destructure is standard `reg-sub` mechanics.
- [004-Views.md](004-Views.md) — `reg-view` positional args; render-tree splice semantics; Form-1 canonical.
- [002-Frames.md](002-Frames.md) — per-frame `app-db` isolation; how frames change the multi-instance story for views rendered in different frames.
- [006-ReactiveSubstrate.md](006-ReactiveSubstrate.md) — the adapter contract; why the idiom ports across Reagent / UIx / Helix unchanged.
- [013-Flows.md](013-Flows.md) — when a host app's existing storage shape doesn't match a library's expected slice path, project into the expected shape via a registered flow.
- **Pattern — Stateful Components** — companion pattern for the case where the parameterised widget also wraps a stateful JS library (a chart, a map, a code editor). The entity-id idiom handles the data flow; the stateful-component idiom handles the imperative-handle lifecycle. The two compose: a `[customer-chart id]` widget is a reusable component (entity-id) that is also a stateful component (wraps a charting library).

## Conformance checklist

A reusable component conforms to this convention when:

- The view function takes the entity id (or ids) as positional arg(s) — never reaches into a hardcoded slice path.
- Every sub the view reads is parameterised on the id via the query-vector destructure pattern (`(fn [db [_ id]] ...)`).
- Every event the view dispatches carries the id in the first payload position.
- The reuse bundle — view + subs + events — ships together; consumers don't have to re-register half of it.
- Multiple instances of the view render side-by-side without sharing transient state.
- Library-shipped widgets document the `app-db` slice they expect; host apps that store entities elsewhere project into the documented shape via a flow rather than reaching into a foreign path.
