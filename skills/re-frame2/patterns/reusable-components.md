# Pattern — Reusable Components

The entity-id idiom for parameterised widgets — a `customer-card` that works against *any* customer, where the caller supplies the id and the card subscribes and dispatches in terms of it. The same view function instantiates N times against N entities, each reading its own slice, each dispatching its own scoped events. **Convention, not Spec** — built entirely from `reg-sub`'s query-vector, `reg-view`'s positional args, and the standard `dispatch` / `subscribe` surfaces; no new substrate.

> **Mental-model anchor:** this is the **React "prop-as-id, not prop-as-data"** discipline — pass `id`, let the component resolve its own data through a (cached) selector, exactly the shape that lets `<CustomerCard id={42} />` and `<CustomerCard id={43} />` render side by side without sharing state. Map that intuition onto the re-frame2 query-vector below.

## When to load

The prompt mentions: a reusable / parameterised widget, a list rendering N of the same card, a "compare two entities side by side" view, a `customer-card` / `user-avatar` / `line-item` component, "make this work against any X", or a library-shipped widget that must drop into an arbitrary host app. Also load when a single-instance widget needs to become multi-instance (the singleton-sub bottleneck below).

## The three rules

The whole pattern is **identity-as-argument** — the slice lookup happens *inside* the sub handler, never at the call site.

1. **Subs accept the id in the query vector.** Destructure it out of the second arg:

```clojure
(rf/reg-sub :customer
  (fn [db [_ id]]
    (get-in db [:customers id])))

;; Derived: the id flows through the input fn. EP-0004 — the input fn takes the
;; query vector and returns a *vector of query vectors*; the runtime resolves
;; each in the outer sub's frame and hands the compute fn the resolved values.
(rf/reg-sub :customer/display-name
  (fn [[_ id]] [[:customer id]])                ;; input fn → vector of query vectors
  (fn [[customer] _]                            ;; compute fn → one-element input vector
    (str (:first-name customer) " " (:last-name customer))))
```

`(subscribe [:customer 42])` and `(subscribe [:customer 43])` cache as **distinct** entries — two instances run two independent computations against two app-db slices.

2. **Views accept the id as a positional arg.** The view is an ordinary function:

```clojure
(rf/reg-view customer-card [id]
  (let [customer @(rf/subscribe [:customer id])]
    [:div.customer-card
     [:h3 (:name customer)]
     [:button {:on-click #(rf/dispatch [:customer/edit id])} "Edit"]]))
```

Callers splice `[customer-card 42]` and `[customer-card 43]` into the render tree; both render simultaneously.

3. **Dispatches carry the id** in the first payload position, so the handler scopes the same way the sub does:

```clojure
(rf/reg-event :customer/edit
  (fn [{:keys [db]} [_ id]] {:db (assoc-in db [:ui :editing-customer] id)}))
```

Without the id-on-dispatch rule, two cards firing `:customer/edit` are indistinguishable at the handler.

## The unit of reuse

The unit is **the bundle** — the view, the subs it reads, and the events it dispatches. A reusable `customer-card` ships the view plus `:customer` (and `:customer/*` derived subs) plus `:customer/edit` / `:customer/update`. Half-bundles do not compose: a view that derefs `[:customer id]` with no `:customer` sub registered cannot mount.

## Multi-identity

Views that need **more than one id** (a transfer screen with source + destination, a comparison view, a dropdown with an options-list id and a separate selection id) take two positional args, two subs, two dispatch-scope ids:

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

Each id is independently substitutable; the transfer handler reads both out of its payload and updates both slices in one `:db` step.

## Placefulness — library-shipped widgets

When a widget is distributed as a *library* across host apps, the slice path may vary (app A stores at `[:customers id]`, app B at `[:entities :crm :customers id]`). Frames change the shape but don't eliminate it:

- Within a frame, document the slice the widget reads from, or accept a `:base-path` argument. The documented-slice convention is shorter than threading a base-path through every call.
- Across frames, per-frame `app-db` isolation removes one collision class: two cards in two frames against the same id render two independent views. The id selects within a frame; the frame selects which `app-db`.
- Host apps that already store the entity elsewhere project into the documented slice via a `reg-flow` (see `references/fundamentals/flows.md`) rather than reaching across a foreign boundary.

## Substrate-agnostic

The idiom ports across adapters with **zero changes to the view body**. The view is an ordinary function of its args; Reagent splices `[customer-card 42]`; UIx / Helix read the parameterised sub through the adapter's `use-subscribe` hook with the id as the first prop. Only the surrounding component wrapper differs by adapter.

## Anti-patterns

- **Hardcoded slice path inside a "reusable" component.** `(subscribe [:current-customer])` is by definition single-instance — the moment two entities must render at once, the singleton sub shows the same data in both.
- **Threading the full entity map through the render tree** (`[customer-card customer-map]`). Defeats the sub-cache: every parent re-render reconstructs the map literal, the cached-sub input-equality check fails, the card re-renders even when its data is unchanged. Pass the **id**; let the card resolve the entity.
- **Asymmetric dispatches** — reading `[:customer id]` but dispatching `[:customer/edit]` without the id. Broken under multi-instance rendering.
- **Storing per-instance UI state in the entity slice.** A card's `:expanded?` flag does not belong at `[:customers id :expanded?]` — that conflates the entity with transient view state. Use a separate `[:ui :customer-cards id]` slice (keyed by the same id), or, when the widget wraps a stateful JS thing, the stateful-component idiom (`patterns/stateful-components.md`).

## Worked example

No standalone example app — the idiom appears wherever a list renders the same card N times. The closest worked shape is the RealWorld article list (`examples/real-apps/realworld_http/articles.cljs`), where each article row is an entity-id-parameterised view over the `[:articles]` slice.

## Pointers

- Spec: [`spec/Pattern-ReusableComponents.md`](../../../spec/Pattern-ReusableComponents.md) — the full idiom, the multi-identity forms, the four placefulness answers, the conformance checklist.
- Substrate: `SKILL-REDIRECT.md` → *EP — Views (004)* (`reg-view` positional args, render-tree splice), *EP — Registration (001)* (`reg-sub` query-vector destructure).
- Compose: `patterns/stateful-components.md` (a `[customer-chart id]` widget is a reusable component that *also* wraps a charting library — entity-id handles the data, the outer/inner shape handles the lifecycle).

---

*Derived from `spec/Pattern-ReusableComponents.md` (Convention, not Spec) @ main. Re-verify if `reg-view` positional-arg or `reg-sub` query-vector semantics change.*
