(ns long-running-work.core
  "Entry point for the long-running-work example.

   What this example demonstrates (per Pattern-LongRunningWork's
   :invoke-all shape — the spec/Pattern-LongRunningWork.md guidance
   when the work decomposes into parallel sub-tasks rather than a
   single chunked machine):

   - **Declarative spawn-and-join** — one parent coordinator spawns N
     parallel workers via `:invoke-all` and joins on `:all`. No
     per-child bookkeeping in the parent's `:data` — the runtime
     owns the join state at `[:rf/spawned :work/flow [:working]]`.
   - **Cooperative cancellation cascade** — exiting the `:working`
     state (by user `:cancel`, by `:on-all-complete`, by frame
     destroy, by `:after`) fires one `:rf.machine/destroy` fx whose
     handler tears down every surviving child. Each torn-down child's
     in-flight `:after` timers / HTTP requests go with it (per Spec
     005 §Cancellation cascade).
   - **Progress reporting** — each child dispatches a `:progress`
     event back to the parent on every chunk; the parent's internal
     self-transition updates `:data :progress`; the view's
     `:work/progress-fraction` sub recomputes and the bar updates.
   - **Parent-unmount cascade** — the React component wrapping the
     work-bench dispatches `[:work/flow [:cancel]]` in its
     `r/with-let` cleanup. The dispatch is the only point where the
     UI lifecycle touches the machine; the cascade does the rest.

   Files:

     core.cljs    mount + boot (this file)
     worker.cljs  the :work/processor child machine and the
                  :work/flow parent coordinator (the :invoke-all
                  declaration is here)
     views.cljs   UI components — controls, progress bar, shard
                  breakdown, root, plus the work-bench wrapper whose
                  with-let cleanup triggers the unmount cascade
     schema.cljs  malli schemas for the parent + child snapshots

   Run from `implementation/`:

     npm run test:examples       (Playwright smoke + headless tests)
     shadow-cljs watch examples/long-running-work
                                  (iterate against a live browser)

   Headless tests:

     npm run test:browser        (runs every example's cljs-test;
                                  includes long-running-work-cljs-test
                                  under implementation/adapters/reagent/test/
                                  re_frame/long_running_work_cljs_test.cljs)"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; re-frame.machines ships in day8/re-frame2-machines.
            ;; Loading the ns registers the :invoke-all init / spawn /
            ;; destroy fx handlers and the framework `:rf/machine` sub.
            ;; Both worker.cljs (the :work/flow + :work/processor
            ;; registrations) and views.cljs (the work-bench wrapper)
            ;; transitively require this — declared explicitly here
            ;; for the smoke-load story.
            [re-frame.machines]
            [long-running-work.schema]
            [long-running-work.worker]
            [long-running-work.views :as views])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================
;;
;; The :on-create event fans out to the per-feature initialisers.
;; `:work/initialise` resets the parent flow machine to :idle;
;; `:ui/initialise` seeds the Show/Hide toggle to true.

(rf/reg-event-fx :app/initialise
  {:doc "App boot. Fans out to per-feature initialisers."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:work/initialise]]
          [:dispatch [:ui/initialise]]]}))

;; ============================================================================
;; MOUNT  (CLJS reference; client-only)
;; ============================================================================
;;
;; React root is `react-root` (avoid colliding with `root-view`).
;; Gated on (exists? js/document) so the ns is safe to require under
;; :node-test / JVM smoke / headless cljs-test contexts.

(defonce react-root
  (when (exists? js/document)
    (rdc/create-root (js/document.getElementById "app"))))

(defn ^:export run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/dispatch-sync [:app/initialise])
  (when react-root
    (rdc/render react-root [views/root-view])))
