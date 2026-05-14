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

Views that need a single tag-shaped predicate (typically "is the page read-only?") subscribe directly with `(rf/machine-has-tag? :ui/nine-states :mode/read-only)` — the framework sub `:rf/machine-has-tag?` shipped with [§State tags](005-StateMachines.md#state-tags). The `case` over `:ui/render` is for choosing the **whole** view; individual disabled-attribute toggles read tags directly.

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

The parallel-machine shape gives:

- **The three axes are visible in the machine declaration.** Anyone reading `:regions {:data ... :form ... :mode ...}` sees the model immediately.
- **No mutual-exclusion lie.** Multiple axes can be true simultaneously; the priority is explicit in *data* (the render-priority vector) rather than buried in *control flow* (a priority `cond`'s clause order). The "correct? AND one?" overlap (post-submit on an empty list, `:form/success` and `:data/one` are both true) is a first-class property of the model, not a workaround.
- **Tags carry the query intent.** "Is the page in any loading state?" → `(rf/machine-has-tag? :ui/nine-states :data/loading)`. The view doesn't need a separate `:ui.state/loading?` sub.
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

The `:done` state carries the `:mode/read-only` tag; the view inspects that tag (`(rf/machine-has-tag? :ui/nine-states :mode/read-only)`) to disable inputs, control buttons, and other affordances. Do not use `:mode/done` as a synonym for "request succeeded" — that's the `:form/success` tag in the form region.

## Working with managed HTTP

The `:data` region's lifecycle is the page-level shape of a remote-data fetch. The canonical HTTP fx in re-frame2 — `:rf.http/managed`, per [014-HTTPRequests](014-HTTPRequests.md) — is what feeds that lifecycle in practice. This section shows how the two fit together.

The mapping is direct: the events the `:data` region listens for (`:fetch-started`, `:fetch-succeeded`, `:fetch-failed`) line up one-for-one with the dispatch surface a managed-HTTP call presents (dispatch on issue, `:on-success` on reply, `:on-failure` on failure). No new substrate. The fx kicks the region into `:loading`; its `:on-success` reply drives the region's `:fetch-succeeded` action, which lands the items in shared `:data` and steps through `:resolving` to the cardinality bucket; its `:on-failure` reply drives `:fetch-failed`, which lands the failure map at `[:data :error]` and the region in `:error`.

### Mapping — region states ↔ managed-HTTP lifecycle

| `:data` region state | Managed-HTTP lifecycle | Notes |
|---|---|---|
| `:nothing` | No request yet issued. | Initial state; carries `:data/nothing`. |
| `:loading` | Request in flight (any attempt, including retries). | Carries `:data/loading` + `:data/transient`. The transport-retry loop sits *inside* `:loading` — intermediate `:rf.http/transport` / `:rf.http/http-5xx` attempts emit `:rf.http/retry-attempt` traces but do not advance the region (per 014 §Retry × `:on-failure` semantics). |
| `:resolving` | 2xx reply received, decoded, accepted; `:items` has just been written. | Eventless microstep; the cardinality `:always`-cascade fires here. |
| `:empty` / `:one` / `:some` / `:too-many` | Resolution settled into a cardinality bucket. | Guard-selected by `(count (:items data))` against `too-many-threshold`; first match wins. |
| `:error` | One of the eight `:rf.http/*` failure categories surfaced after retries exhausted. | The failure map at `[:data :error]` is the standard 014 shape: `{:kind :rf.http/* ...kind-tags...}`. |

The same shape works whether the fx is dispatched directly (`:fx [[:rf.http/managed ...]]`) or threaded through `:rf.http/managed`'s child-invokable wrapper (`:invoke {:machine-id :rf.http/managed ...}` per [014 §Machine-shape wrapper](014-HTTPRequests.md#machine-shape-wrapper)). The reply lands at an event id, the event id broadcasts `:fetch-succeeded` / `:fetch-failed` into the page machine, and the region picks the bucket.

### Worked transition table

The transitions the page-level event handler drives, for a `:counter/load`-shaped fetch:

| User intent | Event dispatched | `:data` region transition | Notes |
|---|---|---|---|
| First load | `[:counter/load]` | `:nothing → :loading` | The handler broadcasts `:fetch-started` and issues `:rf.http/managed`. |
| Retry from a bucket | `[:counter/load]` | `:empty | :one | :some | :too-many | :error → :loading` | Same handler; the `:on {:fetch-started :loading}` map on each bucket makes the reload uniform. |
| Reply success | `[:counter/loaded {:rf/reply {:kind :success :value items}}]` | `:loading → :resolving → :empty | :one | :some | :too-many` | The `:resolving` `:always`-cascade decides the bucket. |
| Reply failure | `[:counter/loaded {:rf/reply {:kind :failure :failure failure-map}}]` | `:loading → :error` | The `:set-error` action stamps the failure map at `[:data :error]`. |
| User-driven reset | `[:counter/reset]` | `* → :nothing` | Optional, per [Pattern-RemoteData](Pattern-RemoteData.md). |

A minimal co-located handler:

```clojure
(rf/reg-event-fx :counter/load
  (fn [_ [_ {:keys [page] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply path — fold the reply into the machine.
      (case (:kind reply)
        :success
        {:fx [[:dispatch [:ui/nine-states
                          [:fetch-succeeded {:items (:value reply)}]]]]}

        :failure
        {:fx [[:dispatch [:ui/nine-states
                          [:fetch-failed {:failure (:failure reply)}]]]]})

      ;; Initial dispatch — kick the region into :loading and issue the request.
      {:fx [[:dispatch [:ui/nine-states [:fetch-started]]]
            [:rf.http/managed
             {:request    {:method :get
                           :url    "/api/counters"
                           :params {:page (or page 1)}}
              :decode     CounterListResponse
              :retry      {:on           #{:rf.http/transport :rf.http/http-5xx}
                           :max-attempts 3
                           :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}}
              :request-id :counter/load}]]})))
```

One handler covers issue + reply. The machine's `:resolving` cascade decides whether the reply lands at `:empty`, `:one`, `:some`, or `:too-many`; the handler doesn't.

### Cardinality is a region concern, not a handler concern

The above handler treats every successful reply identically — it forwards `:fetch-succeeded {:items value}` and lets the `:data` region pick the cardinality bucket. This is the point of the region's `:resolving` cascade: the count → bucket mapping lives in **data** (the `:guards` + `:always`-vector on `:resolving`), not in the handler. Different `too-many-threshold`s per page just change the guard's named constant; the handler is unchanged.

If the API returns a richer success shape (a struct with `:total-count`, `:items`, `:page`), normalise it inside the handler's reply branch before dispatching `:fetch-succeeded` — the cardinality cascade reads `(count (:items data))` and doesn't care what the wire shape looked like.

### Cancellation cascade

The `:data` region's `:loading` state usually has an `:invoke` of `:rf.http/managed`'s machine wrapper when the request's lifetime should be bound to the region's. With that wiring, navigating away mid-fetch causes the parent's state to exit, which destroys the wrapper actor, which fires the late-bind `:http/abort-on-actor-destroy` hook (per [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy) — rf2-wvkn). The in-flight HTTP request is cancelled with `:reason :actor-destroyed`; the `:rf.http/aborted-on-actor-destroy` trace fires.

```clojure
:loading
{:tags  #{:data/loading :data/transient}
 :invoke {:machine-id :rf.http/managed
          :data       {:request    {:method :get :url "/api/counters"}
                       :decode     CounterListResponse
                       :request-id :counter/load}}
 :on    {:succeeded {:target :resolving :action :set-items}
         :failed    {:target :error     :action :set-error}
         :reset     :nothing}}             ;; user-driven reset cancels the wrapper
```

`:reset` from a bucket — fired by a navigation handler or an explicit user gesture — exits `:loading`, the wrapper is destroyed, and the in-flight request is aborted. No `:rf.http/managed-abort` call needed; the lifetime binding handles it.

Pages that issue managed HTTP from an event handler directly (the `:fx [[:rf.http/managed ...]]` form above, not the `:invoke` form) don't get the actor-destroy cascade — per [014 §Direct dispatches from event handlers — NOT covered](014-HTTPRequests.md#direct-dispatches-from-event-handlers--not-covered), only requests issued from inside a spawned actor are subject to actor-destroy cancellation. Apps that want navigation-mid-fetch cancellation in that case have two options: lift the call into the `:invoke` form above, or issue an explicit `[:rf.http/managed-abort :counter/load]` from the navigation handler against the same `:request-id`.

### Stale-detection

Even with cancellation in place, the network may have already returned by the time the cancellation runs (the bytes are in the page's buffer, the decoder is about to fire). Per [Pattern-StaleDetection](Pattern-StaleDetection.md), correctness lives in stale-detection, not in cancellation: the runtime can deliver a stale reply and the handler suppresses on epoch mismatch.

When the page issues a fresh `:counter/load` while a previous one is still in flight, the previous request's reply must not land — its `:items` would clobber the freshly-`:loading` region's eventual real reply. Two mechanisms compose here:

1. **`:request-id` supersession.** Using a stable `:request-id` (e.g. `:counter/load` above), the second `:rf.http/managed` with the same id supersedes the first — the older request is aborted with `:reason :request-id-superseded` (per [014 §`:request-id` (internal)](014-HTTPRequests.md#request-id-internal)). The first request's `:on-failure` fires with `:kind :rf.http/aborted`, and the reply branch can ignore aborted replies before broadcasting `:fetch-failed`:

   ```clojure
   :failure
   (when-not (= :rf.http/aborted (-> reply :failure :kind))
     {:fx [[:dispatch [:ui/nine-states [:fetch-failed {:failure (:failure reply)}]]]]})
   ```

2. **Connection-epoch on the dispatched reply.** For request shapes where `:request-id` isn't enough (e.g. the same id is reused after the user has navigated away and back, and an outstanding earlier reply may still be in flight), the page-level container carries an epoch and the reply suppresses on mismatch. The epoch advances on every life event of the container (a reset, a route re-enter); the dispatched success/failure event carries the epoch it was issued under; the handler compares on receipt. This is the canonical staleness idiom; see [Pattern-StaleDetection](Pattern-StaleDetection.md).

In practice, `:request-id` supersession handles the common "user typed faster than the server responded" case; the epoch handles "the page was destroyed and recreated" cases. Both are cheap; use both when in doubt.

### Cross-region implications — render priority during mid-flight

The page machine has three regions running in parallel; a managed-HTTP reply landing in the `:data` region is one of them. The render-priority vector (per §4 above) decides what the user actually sees when more than one region's tags are present.

The interesting cases:

- **`:form` region `:correct` lands while `:data` region is `:loading`.** Tags `:form/success` AND `:data/loading` are both in the union. The render-priority table puts `:form/success` above `:data/loading` (see the vector in §The shape), so the success acknowledgement wins. The spinner is suppressed under the toast — which is what the user actually wants: the post-submit success confirmation is the load-bearing piece of feedback in that instant; the spinner can re-appear when the success state ages out (`:form/transient` is the tag that flags this).
- **`:mode` region `:done` lands while `:data` region is `:loading`.** Tags `:mode/done` AND `:data/loading` are both in the union. `:mode/done` is at the top of the priority vector — the archived view wins outright, the spinner never renders. The in-flight HTTP request continues in the background; when it lands, the reply still folds into `:items` (or `:error`), but the user is looking at the archived view. If you want the request cancelled when `:mode` flips to `:done`, wire the wrapper actor's lifetime to the `:active` state of the `:mode` region (not the `:loading` state of `:data`) and the cancellation cascade takes care of it.
- **`:data` region `:error` lands while `:form` region is `:incorrect`.** Both `:data/error` and `:form/invalid` are in the union. The render-priority vector puts `:form/invalid` above `:data/error` — the inline form error wins. This matches what users expect: the field-level error is closer to the user's attention than the page-level fetch error.

The point of the render-priority vector is that these cross-region overlaps are first-class. The reply lands honestly into its own region; the union of tags reflects every truth simultaneously; the priority decides the single render-model keyword; the root view's `case` branches. No cross-region `cond`s in event handlers, no "is the page also loading?" guards in submit logic.

### Cross-references

- [014-HTTPRequests](014-HTTPRequests.md) — the managed-HTTP surface: args map, failure categories, retry semantics, abort, machine wrapper.
- [Pattern-RemoteData](Pattern-RemoteData.md) — the request-lifecycle slice the `:data` region's states fold; `:rf.http/managed` writes through it.
- [Pattern-StaleDetection](Pattern-StaleDetection.md) — the epoch idiom for suppressing stale replies.
- [014 §Abort on actor destroy](014-HTTPRequests.md#abort-on-actor-destroy) — the cancellation cascade contract (rf2-wvkn).

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
- [014-HTTPRequests.md](014-HTTPRequests.md) — the canonical managed-HTTP fx that feeds the `:data` region; see [§Working with managed HTTP](#working-with-managed-http) for the integration.
- [Pattern-StaleDetection.md](Pattern-StaleDetection.md) — the epoch idiom for suppressing stale HTTP replies.
- [CP-5-MachineGuide.md §Substitutes](CP-5-MachineGuide.md#substitutes-for-skipped-features) — the N-machines-per-region pattern, the right answer when axes are independent features.
- [004-Views.md §Loading state is explicit](004-Views.md#loading-state-is-explicit-not-implicit) — the explicit-state view philosophy this pattern exemplifies.
- [examples/reagent/nine_states/README.md](../examples/reagent/nine_states/README.md) — worked example.

## Conformance checklist

A page applies this pattern well when:

- The page's render axes are visible as regions of one parallel machine (or as N machines when the axes are independent features).
- States declare `:tags` from a small per-axis canonical vocabulary; new states added later pick up tag-based queries for free.
- The render decision lives in one selector sub over a render-priority **vector**, not in a priority `cond` in the view.
- The root view branches via `case` over the resolved render-model keyword.
- Tag-shaped predicates are read via `(rf/machine-has-tag? machine-id tag)` (the framework sub `:rf/machine-has-tag?`) rather than via per-state boolean discriminator subs.
- The headless test fixture per state drives `app-db` to the state and asserts against the tag union and the resolved `:ui/render` keyword.
