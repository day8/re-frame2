# Anti-pattern — Manual loading flags

Boolean flag keys in `app-db` — `:foo/loading?`, `:bar/saving?`, `:baz/in-flight?` — flipped on by a "start" event and flipped off by every "done" / "failed" / "cancelled" terminator. The flag and the data live as separate keys; keeping them coherent is the application's problem.

## Detection rules

Greppable signals:

- `(assoc db :*/loading? true)` paired anywhere in the codebase with `(dissoc db :*/loading?)` or `(assoc db :*/loading? false)`.
- Keys matching `:*/loading?`, `:*/saving?`, `:*/in-flight?`, `:*/pending?`, `:*/fetching?` at the top level or one level deep in `app-db`.
- Failure / cancellation handlers that need to remember to flip the flag off — typically a `dissoc` of the flag in two or more terminator handlers.
- View code that reads the flag and the data **separately**: `@(subscribe [:items])` next to `@(subscribe [:items-loading?])`.

Structural signal: the data path and the flag path are siblings (`{:items [...] :items-loading? true}`), not a single value whose shape encodes the lifecycle.

## Why it's an anti-pattern

A boolean flag is a one-bit lifecycle marker implemented in `assoc` calls. It cannot express the page-level lifecycle the framework's **Nine States** checklist enumerates — see [`nine-states.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/nine-states.md). Two detection-relevant traps follow from the one-bit shape:

- **Implicit lifecycle / missing `dissoc`.** Every code path that can terminate the in-flight operation must remember to flip the flag off. The most common bug is a missing `dissoc` on the failure branch, leaving the UI stuck on a spinner.
- **Flag-vs-data race.** The flag and the data are sibling keys, so rendering must guard against `{:items [] :items-loading? true}` (is this "initial load" or "loaded zero items"?) — and typically grows the boolean-discriminator-sub cluster downstream to disambiguate.

## The canonical fix

**Smallest correction first.** When the concrete bug is a missing terminator cleanup — the classic missing `dissoc` on the failure branch — the immediate correctness repair is one line: clear the flag on that branch too (`(-> db (dissoc :items/loading?) (assoc :items/error err))`). Every path that can terminate the in-flight operation must flip the flag off. Report that one-liner as the immediate fix; it is proportionate to the bug and requires no re-architecture.

**One axis — replace the boolean with a status keyword, not with a machine.** The flag's structural cost is real even when the lifecycle stays one-dimensional: every new terminator re-inherits the cleanup obligation, and the flag-vs-data sibling race breeds a discriminator-sub cluster downstream. Both come from the *one-bit shape*, and both go away by widening that bit into an explicit `:status` keyword — [`skills/re-frame2/patterns/remote-data.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/remote-data.md) §Canonical declaration — slice form, "the dominant shape … the vast majority of cases": `[:enum :idle :loading :fetching :loaded :error]` where the boolean was, plus layered convenience subs (`:*/loading?`, `:*/fetching?`) derived from it rather than stored beside it. A status keyword cannot be simultaneously loading and errored, so there is no coherence invariant left to forget, and the view reads one value instead of racing a flag against its data. This is a slice-for-a-slice swap — no new grammar, no lifecycle to initialise.

**When the canonical redesign pays.** The machine earns its migration when the lifecycle grows past one axis (failure *and* cancellation *and* reload *and* empty-vs-initial disambiguation), or when one of [`slice-or-machine.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/decision-trees/slice-or-machine.md)'s four tells fires — multi-step async phases with phase-distinct transitions, a cancellation cascade, a terminal state, or orthogonal axes. Then [`skills/re-frame2/patterns/nine-states.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/patterns/nine-states.md) models the page-level lifecycle as a parallel `reg-machine` with `:data`, `:form`, and `:mode` regions; tag each state with per-axis intent; resolve render via a priority table in plain data. Offer it as the optional broader redesign — never as the mandatory fix for one missing `dissoc`, and never for a one-axis fetch that a `:status` keyword already models.

Spec sources: [`spec/Pattern-RemoteData.md`](https://github.com/day8/re-frame2/blob/main/spec/Pattern-RemoteData.md) (the one-axis status slice) and [`spec/Pattern-NineStates.md`](https://github.com/day8/re-frame2/blob/main/spec/Pattern-NineStates.md) (the multi-axis machine).

## Worked example

**Smallest correction** — the failure handler learns the cleanup it forgot:

```clojure
(rf/reg-event :items/load-failure
  (fn [{:keys [db]} [_ err]]
    {:db (-> db (dissoc :items/loading?) (assoc :items/error err))}))
```

**Before the redesign** — manual flag with `dissoc` discipline:

```clojure
(rf/reg-event :items/load-start
  (fn [{:keys [db]} _] {:db (assoc db :items/loading? true :items/error nil)}))

(rf/reg-event :items/load-success
  (fn [{:keys [db]} [_ items]]
    {:db (-> db (dissoc :items/loading?) (assoc :items items))}))

(rf/reg-event :items/load-failure
  (fn [{:keys [db]} [_ err]]
    {:db (-> db (dissoc :items/loading?) (assoc :items/error err))}))  ;; if you forget the dissoc, spinner-forever
```

**After — one axis** (the usual case): the boolean widens into a `:status` keyword on the slice, and the terminators set it instead of remembering to clear a flag.

```clojure
;; `:items` becomes the slice map {:status … :data … :error …}, not a bare vector —
;; the flag and the data stop being siblings, so they cannot disagree.
(rf/reg-event :items/load-start
  (fn [{:keys [db]} _] {:db (update db :items merge {:status :loading :error nil})}))

(rf/reg-event :items/load-success
  (fn [{:keys [db]} [_ items]] {:db (update db :items merge {:status :loaded :data items})}))

(rf/reg-event :items/load-failure
  (fn [{:keys [db]} [_ err]] {:db (update db :items merge {:status :error :error err})}))

;; Convenience booleans are DERIVED from the status, never stored:
(rf/reg-sub :items           (fn [db _] (:items db)))
(rf/reg-sub :items/status    {:inputs [[:items]]} (fn [[items] _] (:status items)))
(rf/reg-sub :items/data      {:inputs [[:items]]} (fn [[items] _] (:data items)))
(rf/reg-sub :items/fetching? {:inputs [[:items/status]]} (fn [[status] _] (contains? #{:loading :fetching} status)))
```

Forgetting a terminator is now *visible* rather than silent: the status simply stays `:loading`, and there is no second key that could disagree with it.

**After the redesign** — machine with tag-based render selection (only once the lifecycle has earned it — see above):

```clojure
(rf/reg-machine :items
  {:initial :nothing
   :data    {:items [] :error nil}
   :actions {:set-items (fn [{d :data [_ items] :event}] {:data (assoc d :items items :error nil)})
             :set-error (fn [{d :data [_ err]   :event}] {:data (assoc d :error err)})}
   :states
   {:nothing {:tags #{:items/nothing} :on {:load :loading}}
    :loading {:tags #{:items/loading :items/transient}
              :on   {:load-success {:target :loaded :action :set-items}
                     :load-failure {:target :error  :action :set-error}}}
    :loaded  {:tags #{:items/loaded} :on {:load :loading}}
    :error   {:tags #{:items/error}  :on {:load :loading}}}})

;; The view reads one tag-question, no flag-and-data race. It is an
;; rf/reg-view, so `subscribe` is the macro's injected frame-bound local: a
;; plain (defn …) view with a bare rf/subscribe raises
;; :rf.error/no-frame-context under EP-0002 (see view-side-hook-state.md).
(rf/reg-view items-panel []
  (if @(subscribe [:rf.machine/has-tag? :items :items/loading])
    [spinner]
    [items-list]))
```

## Edge cases — when a boolean flag is fine

- **Render-local UI state** that never crosses an asynchronous boundary — `(:menu-open? db)`, `(:tooltip-visible? db)`. No retry / failure / cancellation lifecycle exists, so no flag-coherence bug exists.
- **Truly binary flags** that aren't lifecycle markers — `(:auth/authenticated? db)`, `(:user/preferences-loaded? db)` set once at boot. These are facts, not state machines.
- **One-shot transitions** with no error or cancellation paths (rare). Even then, prefer a tiny machine for consistency — but a flag is not *wrong*.
