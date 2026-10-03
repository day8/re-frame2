# Fan-out and join

<a id="9-fan-out-and-join"></a>

After sign-in, session startup may need both a profile and preferences before
opening the application. Use `:spawn-all` when those tasks should run
together, and leaving the startup state should cancel both.

This is a process with a joint completion rule. For independent server-data
reads that need caching and invalidation, use [resources](../resources/index.md).

## Wait for every child

First register a reusable request child. It uses the same self-addressed
HTTP reply and final-state pattern as the [login request actor](actors.md#state-bound-spawn):

```clojure
(ns app.session-startup
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.http.managed]))

(rf/defmachine load-json
  {:initial :running
   :data {}
   :actions
   {:fetch
    (fn [{:keys [data]}]
      {:fx [[:rf.http/managed
             {:request {:method :get :url (:url data)}
              :decode :json
              :on-success [(:rf/self-id data) [:loaded]]
              :on-failure [(:rf/self-id data) [:failed]]}]]})
    :keep-value (fn [{[_ reply] :event}]
                  {:data {:value (:value reply)}})
    :keep-error (fn [{[_ reply] :event}]
                  {:data {:reason (:error reply)}})}
   :states
   {:running {:entry :fetch
              :on {:loaded {:target :done :action :keep-value}
                   :failed {:target :error :action :keep-error}}}
    :done  {:final? true :output-key :value}
    :error {:final? true :error? true :output-key :reason}}})

(rf/reg-machine :auth/load-json load-json)
```

The parent starts two instances of that type with different URLs. Each child
has a unique logical `:id`, and its `:on-done` fold stores its result in the
parent's working data:

```clojure
(rf/defmachine session-startup
  {:initial :loading-session
   :data {:profile nil :preferences nil}
   :states
   {:loading-session
    {:entry (fn [_] {:data {:profile nil :preferences nil}})
     :spawn-all
     {:children
      [{:id :profile :machine-id :auth/load-json
        :data {:url "/api/me"}
        :on-done (fn [{:keys [data result]}]
                   (assoc data :profile result))}
       {:id :preferences :machine-id :auth/load-json
        :data {:url "/api/preferences"}
        :on-done (fn [{:keys [data result]}]
                   (assoc data :preferences result))}]
      :join :all
      :on-all-complete [:auth/session-loaded]
      :on-any-failed [:auth/session-failed]}
     :after {8000 :failed}
     :on {:auth/session-loaded :ready
          :auth/session-failed :failed
          :auth/logout :signed-out}}
    :ready {:tags #{:auth/ready}
            :on {:auth/logout :signed-out}}
    :failed {:on {:auth/retry :loading-session
                  :auth/logout :signed-out}}
    :signed-out {}}})

(rf/reg-machine :auth.session/startup session-startup)
(rf/dispatch [:auth.session/startup [:rf.machine/start]])
```

`:join :all` waits for every child to succeed. The parent's ordinary `:on`
handles the resolution event and moves to `:ready`. Any failure or the
eight-second deadline moves to `:failed`; logout moves to `:signed-out`.
Every exit destroys the children that are still running.

The cell below registers both machines and mounts the startup flow in two
frames. In the first, both endpoints answer: **Start** dispatches
`[:rf.machine/start]`, which spawns the two children; each child's `:on-done`
fold stores its result, and the join moves the parent to `:ready`. In the
second, every request fails, so the first failure fires `:on-any-failed` and
the parent enters `:failed` with no results. **Retry** re-enters
`:loading-session` and fails again.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.http.test-support :as http-test-support])

;; The first frame's server: both endpoints answer.
(http-test-support/install-managed-request-stubs!
  {[:get "/api/me"]          {:reply {:ok {:name "Ada"}}}
   [:get "/api/preferences"] {:reply {:ok {:theme "dark"}}}})

(rf/reg-machine :auth/load-json
  {:initial :running
   :data {}
   :actions
   {:fetch
    (fn [{:keys [data]}]
      {:fx [[:rf.http/managed
             {:request {:method :get :url (:url data)}
              :decode :json
              :on-success [(:rf/self-id data) [:loaded]]
              :on-failure [(:rf/self-id data) [:failed]]}]]})
    :keep-value (fn [{[_ reply] :event}]
                  {:data {:value (:value reply)}})
    :keep-error (fn [{[_ reply] :event}]
                  {:data {:reason (:error reply)}})}
   :states
   {:running {:entry :fetch
              :on {:loaded {:target :done :action :keep-value}
                   :failed {:target :error :action :keep-error}}}
    :done  {:final? true :output-key :value}
    :error {:final? true :error? true :output-key :reason}}})

(rf/reg-machine :auth.session/startup
  {:initial :loading-session
   :data {:profile nil :preferences nil}
   :states
   {:loading-session
    {:entry (fn [_] {:data {:profile nil :preferences nil}})
     :spawn-all
     {:children
      [{:id :profile :machine-id :auth/load-json
        :data {:url "/api/me"}
        :on-done (fn [{:keys [data result]}]
                   (assoc data :profile result))}
       {:id :preferences :machine-id :auth/load-json
        :data {:url "/api/preferences"}
        :on-done (fn [{:keys [data result]}]
                   (assoc data :preferences result))}]
      :join :all
      :on-all-complete [:auth/session-loaded]
      :on-any-failed [:auth/session-failed]}
     :after {8000 :failed}
     :on {:auth/session-loaded :ready
          :auth/session-failed :failed
          :auth/logout :signed-out}}
    :ready {:tags #{:auth/ready}
            :on {:auth/logout :signed-out}}
    :failed {:on {:auth/retry :loading-session
                  :auth/logout :signed-out}}
    :signed-out {}}})

(rf/reg-view startup-view [server]
  (let [{:keys [state data]} @(subscribe [:rf/machine :auth.session/startup])]
    [:div
     [:p [:strong server] " · state: " (pr-str state)]
     [:p "profile: " (pr-str (:profile data))
      " · preferences: " (pr-str (:preferences data))]
     [:button {:on-click #(dispatch [:auth.session/startup [:rf.machine/start]])} "Start"]
     [:button {:on-click #(dispatch [:auth.session/startup [:auth/retry]])} "Retry"]
     [:button {:on-click #(dispatch [:auth.session/startup [:auth/logout]])} "Log out"]]))

;; :fx-overrides answers each frame's requests without a network.
;; A real app leaves it out.
[:div
 [rf/frame-root {:id :auth.session/up
                 :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
  [startup-view "Both endpoints answer"]]
 [rf/frame-root {:id :auth.session/down
                 :fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}}
  [startup-view "Every request fails"]]]
```

The entry action resets working data for a retry. Progress updates should
use a targetless transition so they preserve those children:

```clojure
:on {:auth/progress {:action :record-progress}}
```

## How a child reports

A child reports by reaching a root-level `:final?` leaf and naming its
result with `:output-key`. It sends no application event to the parent.
The same child definition works under a single `:spawn` and a `:spawn-all`.

A child `:on-done` fold runs before the join checks completion. It takes
`{:data ... :result ...}` and returns the parent's whole next data map. Use
one to collect each result, as the two folds above do.

## The resolution event

The join emits its configured parent trigger with the decisive child's
logical `:id` and result appended:

```clojure
[:auth/session-loaded :preferences {:theme "dark"}]
```

That is one child's result, not a map of all results. Read the parent's
`:data` to use everything collected by the child folds. The logical id
`:preferences` is the id in `:children`, rather than the actor's allocated id.
When the join resolves, any children still running are destroyed.

## Wait for the first success

Use `:any` when several children offer alternatives and one successful result
is enough. This variant asks two profile endpoints using the same registered
`:auth/load-json` child:

```clojure
:finding-profile
{:spawn-all
 {:children [{:id :primary :machine-id :auth/load-json
              :data {:url "/api/me"}}
             {:id :backup :machine-id :auth/load-json
              :data {:url "/api/profile-backup"}}]
  :join :any
  :on-some-complete [:auth/profile-found]}
 :after {5000 :failed}
 :on {:auth/profile-found
      {:target :ready
       :action (fn [{[_ _ profile] :event}]
                 {:data {:profile profile}})}}}
```

Here one failure leaves the other child running. The first success cancels
any survivor. If both fail, the deadline provides a way out. Add
`:on-any-failed` only when any failure should end the whole attempt
immediately, even when another child could still succeed.

## When not to use `:spawn-all`

- **The list is known only at run time.** Emit one `[:rf.machine/spawn …]`
  per item from an action
  ([Imperative spawn and destroy](actors.md#imperative-spawn-and-destroy)).
  Those children have no parent, so pass each an address to report to in its
  `:data`.
- **The children are independently valuable** — fire-and-forget, with no
  cancel-the-rest. Use separate `:spawn`s on active states in parallel
  regions, or hand-emit spawns and destroy them explicitly when finished.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Registration throws `:rf.error/machine-spawn-all-bad-shape` on `:join` | `:join` was `{:n n}`, a predicate, or another non-enum | `:join` is only `:all` or `:any`. For quorum, count in each child's `:on-done` and decide in a guarded `:after` |
| Registration throws `:rf.error/machine-spawn-all-bad-shape` naming a child-event key | the `:spawn-all` block names an event for its children to dispatch | Delete it. The child completes by reaching a `:final?` leaf; read the result off the resolution event or a child `:on-done` |
| Registration throws `:rf.error/machine-unknown-spawn-key` on a `:spawn-all` child | the child spec declared `:on-error` | Route failure through the block's `:on-any-failed` — a join has no per-child error transition |
| `:join :all` rejected | missing `:on-all-complete` | Give `:on-all-complete` an event vector |
| `:join :any` rejected | missing `:on-some-complete` | Give `:on-some-complete` an event vector |
| The parent never leaves the state; `:rf.warning/spawn-all-join-unsatisfiable` | a child failed and the block has no `:on-any-failed` | Declare `:on-any-failed`, or give the state an `:after` deadline |
| Children torn down or respawned on a progress event | The transition exited their state, targeted a compound that reset its descendants, or set `:reenter? true` | Use a targetless action for progress. A leaf self-target without re-entry also preserves its children |
## Advanced

### Rules

- **Each child needs a unique `:id`** (the join key) on top of the usual
  spawn keys. Duplicates are `:rf.error/machine-spawn-all-duplicate-id`.
- **The non-empty `:children` vector is part of the definition**, so the number of
  children is fixed there; a fn in its place is refused
  (`:rf.error/machine-spawn-all-bad-shape`).
- **There are no child-vocabulary keys.** The block declares only how results
  combine: `:children`, `:join`, `:on-all-complete`, `:on-some-complete`,
  `:on-any-failed`. Any other bare key is
  `:rf.error/machine-spawn-all-bad-shape`.
- **A child spec may declare `:on-done`** — a `:data` fold on the parent at
  that child's successful finality, run before the join fold. It must be a fn:
  registration refuses any other value (`:rf.error/machine-bad-on-done-clause`),
  because the join's events own control flow. It may **not** declare
  `:on-error` (`:rf.error/machine-unknown-spawn-key`): failure control flow
  under a join is the block's `:on-any-failed`, which decides for the whole
  fan-out.
- **`:join` is `:all` (the default) or `:any`.** There is no `{:n n}` and no
  predicate. Quorum ("N of M") is the idiom below, not a `:join` mode.
- **`:on-all-complete` is required for `:all`.** **`:on-some-complete` is
  required for `:any`.** Missing either is
  `:rf.error/machine-spawn-all-bad-shape`.
- **`:on-any-failed` is optional, but without it a failure can leave the join
  waiting for ever.** Under `:all`, one failed child means the join can never
  complete; under `:any`, the join waits once every child has failed. The
  parent stays in the state, and the runtime warns
  `:rf.warning/spawn-all-join-unsatisfiable`. Declare `:on-any-failed`, or
  give the state an `:after` deadline.
- **An unregistered child type rejects the whole invoke**
  (`:rf.error/machine-spawn-unregistered-type`). No children start and
  `:on-any-failed` is not fired; the parent stays in its state. Register every
  child type before entering the state.
- **A state takes `:spawn` or `:spawn-all`, never both**
  (`:rf.error/machine-spawn-all-with-spawn`).
- A wall-clock bound on the join is the same as single `:spawn`: `:after`
  or `:timeout` / `:on-timeout` on the spawn-all-bearing state.

### Quorum

The [long-running-work example](../../examples/patterns/long_running_work/)
shows a larger worker fan-out. For a quorum, count successes in the parent's `:data` and decides at a deadline.
Each child's `:on-done` bumps the count, and the state's `:after` is a guarded
candidate vector that reads it:

```clojure
(defn count-done [{:keys [data]}]
  (update data :done-count (fnil inc 0)))

:guards {:quorum? (fn [{:keys [data]}] (>= (:done-count data 0) 2))}

:working
{:entry     (fn [_] {:data {:done-count 0}})
 :spawn-all {:children        [{:id :a :machine-id :work/fetch :on-done count-done}
                               {:id :b :machine-id :work/fetch :on-done count-done}
                               {:id :c :machine-id :work/fetch :on-done count-done}]
             :join            :all
             :on-all-complete [:work/all-done]}
 :after     {5000 [{:guard :quorum? :target :degraded}   ;; 2 of 3 by the deadline
                   {:target :failed}]}
 :on        {:work/all-done :complete}}
```

An `:always` guard cannot make this decision: a join child's completion folds
into the join without a parent macrostep, so `:always` is not re-checked as
each child finishes. The count lives in `:data`, which outlives the state, so `:entry`
resets it. Leaving the state destroys any child still running.
