# Pattern — Nine States of UI

> **Type:** Pattern
> A page-level convention for making common UI states explicit: blank, loading, cardinality variants, validation failure, success, and terminal/frozen. Built from a single parallel state machine plus `:fsm/tags`; convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

This is a **named pattern**, not a Spec. It does not introduce a runtime feature. It names a disciplined way to build a page or panel so the common UI states are:

- explicit in data
- enumerable by tooling
- easy to test headlessly
- hard to forget during implementation

The pattern is assembled from primitives already in re-frame2:

- [005-StateMachines.md §Parallel regions](005-StateMachines.md#parallel-regions) — three orthogonal axes (data lifecycle / form validity / mode) modelled as three regions of one machine.
- [005-StateMachines.md §State tags](005-StateMachines.md#state-tags) — per-axis intent (`:data/loading`, `:form/invalid`, `:mode/done`) attached to states so view-level queries can ask tag-shaped questions across the active configuration.
- [Pattern-RemoteData](Pattern-RemoteData.md) — the lifecycle vocabulary folded into the `:data` region (`:nothing → :loading → :resolving → {:empty | :one | :some | :too-many} | :error`).
- [Pattern-Forms](Pattern-Forms.md) — the validation / submission lifecycle folded into the `:form` region (`:neutral → {:correct | :incorrect}`).
- A small selector sub over a **render-priority** table that collapses the tag union into a single render-model keyword.

The pattern is useful because most apps are only designed for the happy path. The empty result, single-item case, too-many-results case, validation failure, and terminal/frozen state all end up visually undefined unless they are treated as first-class.

## The nine states

For a list-or-workflow page, the canonical checklist is:

| # | Name | Meaning | Typical source |
|---|---|---|---|
| 1 | **Nothing** | The user has not yet caused a fetch or the page has been reset. | `:data` region at `:nothing` |
| 2 | **Loading** | First fetch in flight; no prior data yet. | `:data` region at `:loading` |
| 3 | **Empty** | Loaded successfully, but the result is empty. | `:data` region at `:empty` |
| 4 | **One** | Loaded successfully, exactly one result. | `:data` region at `:one` |
| 5 | **Some** | Loaded successfully, a manageable list. | `:data` region at `:some` |
| 6 | **Too Many** | Loaded successfully, but the result volume changes the UI shape. | `:data` region at `:too-many` |
| 7 | **Incorrect** | User input is invalid; the user can fix it. | `:form` region at `:incorrect` |
| 8 | **Correct** | A successful submission or transient success acknowledgement. | `:form` region at `:correct` |
| 9 | **Done / Frozen** | The domain reached a terminal or read-only state. | `:mode` region at `:done` |

These are **page-level render states**, not one shared enum in the runtime. The three axes are independent; the snapshot's `:tags` carries them all simultaneously, and the render-priority table decides which one wins for any given commit.

## What this pattern is not

It is not:

- a replacement for [Pattern-RemoteData](Pattern-RemoteData.md)
- a replacement for [Pattern-Forms](Pattern-Forms.md)
- a claim that every page literally needs all nine states
- a claim that transport / server errors disappear

In particular:

- `Incorrect` means **user-fixable invalid input**, not network failure.
- Transport failures still belong to the `:data` region's `:error` branch.
- `Done` means **terminal / frozen / archived / irreversible**, not merely "loaded" or "completed once."

Treat the nine states as a **design checklist** and a **rendering convention**, not as a universal ontology.

## The shape

One machine. Three regions. Tags on states. One selector sub. One `case` in the root view.

```clojure
(rf/reg-machine :ui/nine-states
  {:type :parallel
   :data {:items [] :error nil}                 ;; shared across regions

   :guards
   {:empty?    (fn [d _] (zero? (count (:items d))))
    :one?      (fn [d _] (= 1 (count (:items d))))
    :too-many? (fn [d _] (> (count (:items d)) too-many-threshold))}

   :actions
   {:set-items (fn [d [_ {:keys [items]}]] {:data (assoc d :items (vec items) :error nil)})
    :set-error (fn [d [_ {:keys [failure]}]] {:data (assoc d :error failure)})}

   :regions
   {:data
    {:initial :nothing
     :states
     {:nothing   {:tags #{:data/nothing}
                  :on   {:fetch-started :loading}}
      :loading   {:tags #{:data/loading :data/transient}
                  :on   {:fetch-succeeded {:target :resolving :action :set-items}
                         :fetch-failed    {:target :error     :action :set-error}}}
      :resolving {:always [{:guard :empty?    :target :empty}
                           {:guard :one?      :target :one}
                           {:guard :too-many? :target :too-many}
                           {:target :some}]}
      :empty     {:tags #{:data/empty}    :on {:fetch-started :loading}}
      :one       {:tags #{:data/one}      :on {:fetch-started :loading}}
      :some      {:tags #{:data/some}     :on {:fetch-started :loading}}
      :too-many  {:tags #{:data/too-many} :on {:fetch-started :loading}}
      :error     {:tags #{:data/error}    :on {:fetch-started :loading}}}}

    :form
    {:initial :neutral
     :states
     {:neutral   {:tags #{:form/neutral}
                  :on   {:submit-valid   :correct
                         :submit-invalid :incorrect
                         :edit           :neutral}}
      :incorrect {:tags #{:form/invalid}
                  :on   {:submit-valid :correct
                         :edit         :neutral}}
      :correct   {:tags #{:form/success :form/transient}
                  :on   {:edit :neutral}}}}

    :mode
    {:initial :active
     :states
     {:active {:tags #{:mode/active}
               :on   {:archive :done}}
      :done   {:tags #{:mode/done :mode/read-only :mode/terminal}}}}}})
```

The render-priority table is plain data:

```clojure
(def render-priority
  [;; mode region — terminal wins outright
   {:tag :mode/done       :render :done}
   ;; form region — transient acknowledgements
   {:tag :form/success    :render :correct}
   {:tag :form/invalid    :render :incorrect}
   ;; data region — lifecycle then cardinality
   {:tag :data/loading    :render :loading}
   {:tag :data/error      :render :error}
   {:tag :data/nothing    :render :nothing}
   {:tag :data/empty      :render :empty}
   {:tag :data/one        :render :one}
   {:tag :data/some       :render :some}
   {:tag :data/too-many   :render :too-many}])

(rf/reg-sub :ui/render
  :<- [:rf/machine :ui/nine-states]
  (fn [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))
```

The root view:

```clojure
(reg-view root-view []
  (case @(subscribe [:ui/render])
    :done      [view-done]
    :correct   [view-correct]
    :incorrect [view-incorrect]
    :nothing   [view-nothing]
    :loading   [view-loading]
    :error     [view-error]
    :empty     [view-empty]
    :one       [view-one]
    :some      [view-some]
    :too-many  [view-too-many]
    [view-fallback]))
```

Views that need a single tag-shaped predicate (typically "is the page read-only?") subscribe directly with `(rf/has-tag? :ui/nine-states :mode/read-only)` — the framework sub `:rf/machine-has-tag?` shipped with [§State tags](005-StateMachines.md#state-tags). The `case` over `:ui/render` is for choosing the **whole** view; individual disabled-attribute toggles read tags directly.

## Canonical rules

Four rules. Read them as a checklist.

### 1. Identify the orthogonal axes

Most pages of this shape have three axes:

- **Data axis** — the request-lifecycle plus cardinality story (states 1-6 plus an error branch).
- **Form axis** — the validation / submission lifecycle (states 7-8); only present on pages that take user input.
- **Mode axis** — the active / read-only / terminal axis (state 9); only present on pages that can become frozen.

Some pages have fewer axes (a static settings panel: just data). Some have more (a per-row permissions axis on a CRUD panel). The pattern scales by adding a region per axis; it does not require all three.

Do **not** flatten the axes into a single page-local enum like `:ui-state :nothing | :loading | :empty | :one | ... | :done`. The cross-product is `~7 × 3 × 2 = 42` named states, half of which never appear in practice; the pattern is designed to **avoid** that explosion.

### 2. Declare one parallel machine with one region per axis

A `:type :parallel` machine (per [§Parallel regions](005-StateMachines.md#parallel-regions)) takes a `:regions` map keyed by region name. Each region is a full state-tree (its own `:initial`, `:states`, optional `:always` / `:after` / `:invoke`). All regions are active simultaneously when the machine is active; the snapshot's `:state` is a **map** of region-name → that region's state value; transitions are **broadcast** across regions; the run-to-completion drain settles every region before commit.

Regions share a single `:data` blob — they coordinate axes of one feature, so they read and write the same underlying data, just sliced differently. If your axes are conceptually separate features (multiple tabs each with their own state, an audio/video player whose two regions share nothing but the play/pause event, encapsulated `:data`), you don't want parallel regions — you want **N separate machines** colocated in app-db. See [005-StateMachines.md §When to reach for parallel regions](005-StateMachines.md#when-to-reach-for-parallel-regions) and [CP-5-MachineGuide §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) for the redirect.

### 3. Tag each state with its axis-level intent

Per [§State tags](005-StateMachines.md#state-tags), every state may declare `:tags <set-of-keywords>`. The runtime maintains a `:tags` slot on the snapshot — the union of every active state's tag set across every active region — and the framework sub `:rf/machine-has-tag?` answers the predicate question.

Use a small canonical tag vocabulary, namespaced per axis:

- `:data/nothing` / `:data/loading` / `:data/empty` / `:data/one` / `:data/some` / `:data/too-many` / `:data/error` / `:data/transient`
- `:form/neutral` / `:form/invalid` / `:form/success` / `:form/transient`
- `:mode/active` / `:mode/done` / `:mode/read-only` / `:mode/terminal`

Tag names are illustrative. Feature code may namespace them differently — the only framework-reserved prefixes are `:rf/*` and `:rf.*/*` (per [Conventions.md §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).

The point of tags isn't to encode every state. The point is to carry the **query-shaped intent** so views and tests can ask "is the page in any loading-ish state?" without enumerating every nested path that counts as loading-ish. New states added later under existing tags pick up the query for free.

### 4. Compute the render selection in one selector sub, with priority in data

The page's render decision is **one selector sub** that consults a render-priority **vector** against the machine's tag union and returns a single keyword. The root view's `case` over that keyword is the only branch site.

This puts the priority in **data** (the `render-priority` vector — printable, testable, comparable) rather than in **control flow** (a priority `cond` in the root view). A test can pretty-print the priority table; a tool can read it; a code review can compare two pages' priorities side by side.

The selector sub re-runs only when the tag union changes; Reagent's equality dedup gates downstream renders against the resolved keyword. The root view re-renders only when `:ui/render` actually changes — not every time the form region advances if the priority winner is still `:done`.

## Why this shape

Compared to the pre-parallel-regions variant (see the appendix), the parallel-machine shape gives:

- **The three axes are visible in the machine declaration.** Anyone reading `:regions {:data ... :form ... :mode ...}` sees the model immediately.
- **No mutual-exclusion lie.** Multiple axes can be true simultaneously; the priority is explicit in *data* (the render-priority vector) rather than buried in *control flow* (a priority `cond`'s clause order). The legacy variant's "correct? AND one?" overlap (post-submit on an empty list, `:form/success` and `:data/one` are both true) is a first-class property of the model now, not a workaround.
- **Tags carry the query intent.** "Is the page in any loading state?" → `(rf/has-tag? :ui/nine-states :data/loading)`. The view doesn't need a separate `:ui.state/loading?` sub.
- **Adding an axis is one region, not a row × column expansion.** A permissions axis becomes a fourth region with two states (`:editable` / `:read-only`); the render priority gains one entry; the view's `case` gains one branch.

## How the states are usually derived

### From the `:data` region

States 1-6 plus the error branch come from the request lifecycle plus a small `:always`-cascade in `:resolving`:

- `:fetch-started` advances the region from any cardinality bucket (or `:nothing` / `:error`) to `:loading`.
- `:fetch-succeeded` lands the items in shared `:data` via the `:set-items` action and advances to `:resolving`.
- `:resolving`'s `:always` cascade picks the cardinality bucket from the count, first-match-wins.
- `:fetch-failed` lands the failure in shared `:data` via `:set-error` and advances to `:error`.

The cardinality threshold is named, not buried in a sub:

```clojure
(def too-many-threshold 7)
```

### From the `:form` region

States 7-8 come from the form lifecycle. The region's events (`:submit-valid` / `:submit-invalid` / `:edit`) are broadcast from the corresponding `:new-todo/*` events that own the slice's `{:draft :errors :touched}` shape (per [Pattern-Forms](Pattern-Forms.md)). The region's state-keyword IS the form's status; the slice's `:errors` and `:touched` carry the per-field detail the view uses to render the inline error.

`Incorrect` is the page state when the user can **see** and act on the invalid state — touched fields, attempted submission, visible form-level errors. The form region's `:incorrect` state is entered only after `:submit-invalid` is broadcast; an in-progress edit that hasn't been submitted does not put the page in `Incorrect`. This composes directly with [Pattern-Forms](Pattern-Forms.md)' visibility rules.

`Correct` is the page state when the UI renders a visible success acknowledgement — "Todo added." / a green confirmation banner / a brief success toast. If success is immediately followed by navigation and there is no visible acknowledgement, the `:form` region can skip the `:correct` state entirely; the form region becomes a two-state `:neutral ↔ :incorrect` machine and the render-priority table simply never produces `:correct`.

### From the `:mode` region

State 9 comes from a terminal transition in the `:mode` region. Use it when the user has reached a state where the UI must become read-only:

- archived
- submitted and immutable
- completed workflow
- expired edit window
- closed incident

The `:done` state carries the `:mode/read-only` tag; the view inspects that tag (`(rf/has-tag? :ui/nine-states :mode/read-only)`) to disable inputs, control buttons, and other affordances. Do not use `:mode/done` as a synonym for "request succeeded" — that's the `:form/success` tag in the form region.

## Worked example

The canonical worked example is:

- [examples/reagent/nine_states/core.cljs](../examples/reagent/nine_states/core.cljs)
- [examples/reagent/nine_states/README.md](../examples/reagent/nine_states/README.md)

It demonstrates the full machine declaration, the render-priority table, the `:ui/render` selector sub, per-state views, and a headless test per state asserting against tags + the resolved render-model keyword.

## When to use this pattern

Use it when:

- a page has a meaningful loaded-data cardinality story
- a page has one or more forms whose validation state matters to the UI
- a workflow can become terminal or read-only
- you want a deliberate, testable rendering contract rather than ad hoc view branching

It is especially useful for:

- search and listing pages
- dashboards
- inbox / queue UIs
- setup flows and wizards
- CRUD panels

## When not to force it

Do not force this pattern onto:

- tiny static panels with no load lifecycle
- pages where `One` vs `Some` vs `Too Many` has no visual consequence
- forms that navigate away immediately on success
- pages whose axes are conceptually separate features (encapsulated `:data`, no shared domain)

The point is not numerology. The point is to make the **important** UI states explicit and testable. If a page only has the data axis, declare a one-region machine (or no machine at all — Pattern-RemoteData with a flat sub graph is sufficient). If the axes don't share data, register N separate machines per the CP-5-MachineGuide substitute pattern.

## Cross-references

- [005-StateMachines.md §Parallel regions](005-StateMachines.md#parallel-regions) — the substrate.
- [005-StateMachines.md §State tags](005-StateMachines.md#state-tags) — the substrate.
- [Pattern-RemoteData.md](Pattern-RemoteData.md) — the lifecycle folded into the `:data` region.
- [Pattern-Forms.md](Pattern-Forms.md) — the lifecycle folded into the `:form` region.
- [CP-5-MachineGuide.md §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) — the N-machines-per-region pattern, the right answer when axes are independent features.
- [004-Views.md §Loading state is explicit](004-Views.md#loading-state-is-explicit-not-implicit) — the explicit-state view philosophy this pattern exemplifies.
- [examples/reagent/nine_states/README.md](../examples/reagent/nine_states/README.md) — worked example.

## Conformance checklist

A page applies this pattern well when:

- The page's render axes are visible as regions of one parallel machine (or as N machines when the axes are independent features).
- States declare `:tags` from a small per-axis canonical vocabulary; new states added later pick up tag-based queries for free.
- The render decision lives in one selector sub over a render-priority **vector**, not in a priority `cond` in the view.
- The root view branches via `case` over the resolved render-model keyword.
- Tag-shaped predicates are read via `(rf/has-tag? machine-id tag)` (the framework sub `:rf/machine-has-tag?`) rather than via per-state boolean discriminator subs.
- The headless test fixture per state drives `app-db` to the state and asserts against the tag union and the resolved `:ui/render` keyword.

---

## Appendix — Pre-parallel-regions variant (deprecated but supported)

Before re-frame2 shipped `:fsm/tags` and `:fsm/parallel-regions`, the pattern was implemented with **nine boolean discriminator subs** and a root-level priority `cond`. That variant still works — neither capability removed an existing primitive — but new code should use the parallel-machine variant above. The legacy variant is documented here so existing pages have a reference.

### Shape

```clojure
;; nine boolean subs, one per state
(rf/reg-sub :ui.state/nothing?
  :<- [:todos/status]
  (fn [status _] (= status :idle)))

(rf/reg-sub :ui.state/loading?
  :<- [:todos/status]
  (fn [status _] (= status :loading)))

(rf/reg-sub :ui.state/empty?
  :<- [:todos/status]
  :<- [:todos/count]
  (fn [[status n] _] (and (= status :loaded) (zero? n))))

;; ... seven more ...

;; root view branches via priority cond
(reg-view root-view []
  (cond
    @(subscribe [:ui.state/done?])      [view-done]
    @(subscribe [:ui.state/incorrect?]) [view-incorrect]
    @(subscribe [:ui.state/correct?])   [view-correct]
    @(subscribe [:ui.state/nothing?])   [view-nothing]
    @(subscribe [:ui.state/loading?])   [view-loading]
    @(subscribe [:ui.state/empty?])     [view-empty]
    @(subscribe [:ui.state/one?])       [view-one]
    @(subscribe [:ui.state/some?])      [view-some]
    @(subscribe [:ui.state/too-many?])  [view-too-many]
    :else                               [view-fallback]))
```

### Why deprecated

The nine boolean subs aren't mutually exclusive (a post-submit on a single-item list is `:ui.state/correct?` AND `:ui.state/one?` simultaneously); the cond's priority order is the only thing stopping double-rendering. That's a priority hack, not a state model.

The three orthogonal axes (data cardinality / form validity / mode) are flattened into a single sub-vocabulary; the view layer recomposes them via priority order, and a tooling consumer that asks "what data state is this page in?" cannot get an answer without re-deriving it from the seven flat boolean subs.

Adding an axis grows the sub population multiplicatively — a permissions axis adds row × column expansion to the priority cond, plus new subs to discriminate every combination the view renders.

### Why still supported

The legacy variant uses no capability beyond Layer-3 `:<-` subs, which ship in every conforming re-frame2 host. Pages that already follow the variant don't have to migrate; they keep working unchanged. The framework's only commitment is that `:rf/machine`, `:rf/machine-has-tag?`, and the regular sub graph all remain first-class so a page can adopt the parallel-machine variant incrementally (e.g. introduce the machine alongside the existing subs, then drop subs one at a time as views switch to tag queries).
