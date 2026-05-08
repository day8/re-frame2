# Pattern — Nine States of UI

> **Type:** Pattern
> A page-level convention for making common UI states explicit: blank, loading, cardinality variants, validation failure, success, and terminal/frozen. Built from existing re-frame2 primitives; convention, not Spec.

> **Code samples are in ClojureScript** (the CLJS reference). The pattern itself is host-agnostic.

## Role

This is a **named pattern**, not a Spec. It does not introduce a runtime feature. It names a disciplined way to build a page or panel so the common UI states are:

- explicit in data
- enumerable by tooling
- easy to test headlessly
- hard to forget during implementation

The pattern is assembled from three existing pieces:

- [Pattern-RemoteData](Pattern-RemoteData.md) for `Nothing`, `Loading`, `Empty`, `One`, `Some`, `Too Many`
- [Pattern-Forms](Pattern-Forms.md) for `Incorrect` and often `Correct`
- [005-StateMachines.md](005-StateMachines.md) or an explicit domain flag for `Done` / `Frozen`

The pattern is useful because most apps are only designed for the happy path. The empty result, single-item case, too-many-results case, validation failure, and terminal/frozen state all end up visually undefined unless they are treated as first-class.

## The nine states

For a list-or-workflow page, the canonical checklist is:

| # | Name | Meaning | Typical source |
|---|---|---|---|
| 1 | **Nothing** | The user has not yet caused a fetch or the page has been reset. | Remote-data slice `:status :idle` |
| 2 | **Loading** | First fetch in flight; no prior data yet. | Remote-data slice `:status :loading` |
| 3 | **Empty** | Loaded successfully, but the result is empty. | `:status :loaded` + item count `0` |
| 4 | **One** | Loaded successfully, exactly one result. | `:status :loaded` + item count `1` |
| 5 | **Some** | Loaded successfully, a manageable list. | `:status :loaded` + item count in a bounded range |
| 6 | **Too Many** | Loaded successfully, but the result volume changes the UI shape. | `:status :loaded` + item count above a threshold |
| 7 | **Incorrect** | User input is invalid; the user can fix it. | Form slice `:errors` / touched-field visibility |
| 8 | **Correct** | A successful submission or transient success acknowledgement. | Form slice `:status :submitted` or feature-local success flag |
| 9 | **Done / Frozen** | The domain reached a terminal or read-only state. | Terminal machine state or explicit domain flag |

These are **page-level render states**, not one shared enum in the runtime.

## What this pattern is not

It is not:

- a replacement for [Pattern-RemoteData](Pattern-RemoteData.md)
- a replacement for [Pattern-Forms](Pattern-Forms.md)
- a claim that every page literally needs all nine states
- a claim that transport / server errors disappear

In particular:

- `Incorrect` means **user-fixable invalid input**, not network failure.
- Transport failures still belong to the feature's ordinary `:error` branch.
- `Done` means **terminal / frozen / archived / irreversible**, not merely "loaded" or "completed once."

Treat the nine states as a **design checklist** and a **rendering convention**, not as a universal ontology.

## Canonical rules

### 1. Derive the states from existing slices

Do **not** invent a new page-local enum like `:ui-state :loading | :empty | :one | :some ...` when the state is already derivable from:

- a remote-data slice
- a form slice
- a machine snapshot
- a simple domain flag

The point of the pattern is to reuse the explicit state you already have, not to mirror it into another slot.

### 2. Give each state a named discriminator

Expose one named sub per state:

```clojure
(rf/reg-sub :ui.state/nothing? ...)
(rf/reg-sub :ui.state/loading? ...)
(rf/reg-sub :ui.state/empty? ...)
(rf/reg-sub :ui.state/one? ...)
(rf/reg-sub :ui.state/some? ...)
(rf/reg-sub :ui.state/too-many? ...)
(rf/reg-sub :ui.state/incorrect? ...)
(rf/reg-sub :ui.state/correct? ...)
(rf/reg-sub :ui.state/done? ...)
```

This gives:

- inspectability
- easy headless tests
- explicit documentation of intended UI states

The sub ids are illustrative. Feature code may namespace them differently.

### 3. Render by explicit branch, not hidden heuristics

The page's root view should branch explicitly:

```clojure
(rf/reg-view root-view []
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

The exact ordering depends on the feature, but the branch structure should be visible in one place.

### 4. Make the `Some` / `Too Many` threshold explicit

`Too Many` is domain-specific. It is not a fixed framework constant.

Use a named threshold:

```clojure
(def too-many-threshold 7)
```

and make the discriminator sub read from that named constant. Do not bury the threshold in a view body.

### 5. Treat `Incorrect` as validation-visible, not merely invalid-under-the-hood

The page is in `Incorrect` when the user can **see** and act on the invalid state. In practice that means:

- field is touched, or
- submit has been attempted, or
- form-level errors are visible

This pattern therefore composes directly with [Pattern-Forms](Pattern-Forms.md)' visibility rules.

### 6. Treat `Correct` as a real UI state only when the success is visible

Sometimes success is immediately followed by navigation and there is no visible `Correct` state. In that case, do not force one.

Use `Correct` when the UI actually renders a success acknowledgement:

- "Todo added."
- green confirmation banner
- brief success toast
- field reset plus success checkmark

If success is not visually distinct, omit this state from the page's explicit branch set.

### 7. `Done` comes from terminal domain semantics

Use `Done` / `Frozen` when the user has reached a state where the UI must become read-only or terminal:

- archived
- submitted and immutable
- completed workflow
- expired edit window
- closed incident

This typically comes from:

- a terminal machine state, or
- an explicit domain flag like `:archived? true`

Do not use `Done` as a synonym for "request succeeded."

## How the states are usually derived

### From a remote-data slice

The first six states usually come from the request-lifecycle slice plus result count:

```clojure
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

(rf/reg-sub :ui.state/one?
  :<- [:todos/status]
  :<- [:todos/count]
  (fn [[status n] _] (and (= status :loaded) (= 1 n))))

(rf/reg-sub :ui.state/some?
  :<- [:todos/status]
  :<- [:todos/count]
  (fn [[status n] _] (and (= status :loaded) (<= 2 n too-many-threshold))))

(rf/reg-sub :ui.state/too-many?
  :<- [:todos/status]
  :<- [:todos/count]
  (fn [[status n] _] (and (= status :loaded) (> n too-many-threshold))))
```

This is why `Pattern-RemoteData` distinguishes `:idle`, `:loading`, and `:loaded`: the page can render `Nothing`, `Loading`, and the cardinality states separately.

### From a form slice

`Incorrect` and often `Correct` come from the form lifecycle:

```clojure
(rf/reg-sub :ui.state/incorrect?
  :<- [:new-todo/errors]
  :<- [:new-todo/touched]
  (fn [[errs touched] _]
    (boolean (some touched (keys errs)))))

(rf/reg-sub :ui.state/correct?
  :<- [:new-todo/status]
  (fn [status _]
    (= status :submitted)))
```

### From a machine or domain flag

`Done` / `Frozen` often comes from a machine snapshot:

```clojure
(rf/reg-sub :ui.state/done?
  (fn [db _]
    (= :archived (get-in db [:rf/machines :todos/editor :state]))))
```

or from an explicit domain slot:

```clojure
(rf/reg-sub :ui.state/done?
  (fn [db _]
    (true? (get-in db [:invoice :paid?]))))
```

## Worked example

The canonical worked example is:

- [examples/nine_states/core.cljs](../../examples/nine_states/core.cljs)
- [examples/nine_states/README.md](../../examples/nine_states/README.md)

It demonstrates:

- `Nothing`, `Loading`, `Empty`, `One`, `Some`, `Too Many` from a todos remote-data slice
- `Incorrect` / `Correct` from a new-todo form
- `Done` from a two-state editor machine (`:editing -> :archived`)

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

Do not force all nine states onto:

- tiny static panels
- pages with no load lifecycle
- pages where `One` vs `Some` vs `Too Many` has no visual consequence
- forms that navigate away immediately on success

The point is not numerology. The point is to make the **important** UI states explicit and testable.

## Cross-references

- [Pattern-RemoteData.md](Pattern-RemoteData.md) — request lifecycle behind states 1-6
- [Pattern-Forms.md](Pattern-Forms.md) — validation and submission lifecycle behind states 7-8
- [005-StateMachines.md](005-StateMachines.md) — terminal/frozen state behind state 9
- [004-Views.md](004-Views.md#loading-state-is-explicit-not-implicit) — explicit-state view philosophy this pattern exemplifies
- [examples/nine_states/README.md](../../examples/nine_states/README.md) — worked example

## Conformance checklist

A page applies this pattern well when:

- Its important UI states are named explicitly rather than implied.
- Remote-data cardinality states (`Nothing`, `Loading`, `Empty`, `One`, `Some`, `Too Many`) are derived from the data lifecycle rather than hand-waved into one generic loaded branch.
- Validation failure is rendered explicitly as `Incorrect`, not hidden behind an undifferentiated error message.
- Success is rendered explicitly as `Correct` when the page actually has a visible success acknowledgement.
- Terminal/read-only behaviour is rendered explicitly as `Done` / `Frozen` when the domain needs it.
- The root view branches explicitly across the applicable states.
- The discriminator subs are easy to drive in headless tests.
