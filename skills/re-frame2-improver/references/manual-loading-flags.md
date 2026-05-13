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

A boolean flag is a one-bit state machine implemented in `assoc` calls. It cannot represent the **nine** legal page-level states the framework's canonical pattern enumerates: `:nothing`, `:loading`, `:empty`, `:one`, `:some`, `:too-many`, `:error`, `:reloading`, `:stale`. Worse, the flag's lifecycle is implicit — every code path that can terminate the in-flight operation must remember to flip it. The most common bug is a missing `dissoc` on the failure branch, leaving the UI stuck on a spinner. The data and the flag drift: rendering needs both, must guard against the `{:items [] :items-loading? true}` race (is this "initial load" or "loaded zero items"?), and ends up with the boolean-discriminator-sub cluster downstream.

## The canonical fix

[`skills/re-frame2/patterns/nine-states.md`](../../re-frame2/patterns/nine-states.md) — model the page-level lifecycle as a parallel `reg-machine` with `:data`, `:form`, and `:mode` regions; tag each state with per-axis intent; resolve render via a priority table in plain data.

For the simpler one-axis case (just data lifecycle), the same `reg-machine` shape with a single region replaces the flag.

Spec source: [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md).

## Worked example

**Before** — manual flag with `dissoc` discipline:

```clojure
(rf/reg-event-db :items/load-start
  (fn [db _] (assoc db :items/loading? true :items/error nil)))

(rf/reg-event-db :items/load-success
  (fn [db [_ items]]
    (-> db (dissoc :items/loading?) (assoc :items items))))

(rf/reg-event-db :items/load-failure
  (fn [db [_ err]]
    (-> db (dissoc :items/loading?) (assoc :items/error err))))   ;; if you forget the dissoc, spinner-forever
```

**After** — machine with tag-based render selection:

```clojure
(rf/reg-machine :items
  {:initial :nothing
   :data    {:items [] :error nil}
   :actions {:set-items (fn [d [_ items]] {:data (assoc d :items items :error nil)})
             :set-error (fn [d [_ err]]   {:data (assoc d :error err)})}
   :states
   {:nothing {:tags #{:items/nothing} :on {:load :loading}}
    :loading {:tags #{:items/loading :items/transient}
              :on   {:load-success {:target :loaded :action :set-items}
                     :load-failure {:target :error  :action :set-error}}}
    :loaded  {:tags #{:items/loaded} :on {:load :loading}}
    :error   {:tags #{:items/error}  :on {:load :loading}}}})

;; View reads one tag-question, no flag-and-data race:
@(rf/has-tag? :items :items/loading)
```

## Edge cases — when a boolean flag is fine

- **Render-local UI state** that never crosses an asynchronous boundary — `(:menu-open? db)`, `(:tooltip-visible? db)`. No retry / failure / cancellation lifecycle exists, so no flag-coherence bug exists.
- **Truly binary flags** that aren't lifecycle markers — `(:auth/authenticated? db)`, `(:user/preferences-loaded? db)` set once at boot. These are facts, not state machines.
- **One-shot transitions** with no error or cancellation paths (rare). Even then, prefer a tiny machine for consistency — but a flag is not *wrong*.
