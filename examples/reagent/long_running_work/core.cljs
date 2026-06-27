(ns long-running-work.core
  "Entry point for the long-running-work example.

   The work decomposes into parallel sub-tasks, so it uses the
   `:spawn-all` shape: one parent coordinator spawns N parallel
   workers rather than chunking everything through a single machine.
   See the machines guide's `:spawn` (docs/machines/glossary.md#spawn).

   What this example demonstrates:

   - **Declarative spawn-and-join** — one parent coordinator spawns N
     parallel workers via `:spawn-all` and joins on `:all`. The
     parent's `:data` holds no per-child bookkeeping; the runtime owns
     the join state.
   - **Cooperative cancellation cascade** — leaving the `:working`
     state (by user `:cancel`, by completion, by frame destroy, by a
     timeout) fires one `:rf.machine/destroy` fx that tears down every
     surviving child. Each torn-down child's in-flight timers and HTTP
     requests go with it.
   - **Progress reporting** — each child dispatches a `:progress`
     event back to the parent on every chunk. The parent records it
     in `:data :progress`, the `:work/progress-fraction` sub
     recomputes, and the bar updates.
   - **Parent-unmount cascade** — the component wrapping the
     work-bench dispatches `[:work/flow [:cancel]]` in its
     `r/with-let` cleanup. That dispatch is the only point where the
     UI lifecycle touches the machine; the cascade does the rest.

   Files:

     core.cljs    mount + boot (this file)
     worker.cljs  the :work/processor child machine and the
                  :work/flow parent coordinator (the :spawn-all
                  declaration is here)
     views.cljs   UI components — controls, progress bar, shard
                  breakdown, root, plus the work-bench wrapper whose
                  with-let cleanup triggers the unmount cascade
     schema.cljs  malli schemas for the parent + child snapshots

   Run from `implementation/`:

     shadow-cljs watch examples/long-running-work
                                  (iterate against a live browser)

   Headless tests:

     npm run test:browser        (runs every example's cljs-test)"
  ;; Substrate: stock Reagent (`reagent.dom.client` +
  ;; `re-frame.adapter.reagent`). The load-bearing teaching point is the
  ;; `r/with-let` finally-cleanup in views.cljs that fires the `:cancel`
  ;; cascade on view unmount, built on stock Reagent's
  ;; `reagent.core/with-let`.
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Loading re-frame.machines registers the :spawn-all init /
            ;; spawn / destroy fx handlers and the `:rf/machine` sub.
            ;; worker.cljs and views.cljs both require this transitively;
            ;; it's declared explicitly here too so the ns loads cleanly
            ;; on its own.
            [re-frame.machines]
            [long-running-work.schema]
            [long-running-work.worker]
            [long-running-work.views :as views]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================
;;
;; The :app/initialise boot event fans out to the per-feature initialisers.
;; `:work/initialise` resets the parent flow machine to :idle;
;; `:ui/initialise` seeds the Show/Hide toggle to true.

(rf/reg-event :app/initialise
  {:doc "App boot. Fans out to per-feature initialisers."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:work/initialise]]
          [:dispatch [:ui/initialise]]]}))

;; ============================================================================
;; MOUNT  (client-only)
;; ============================================================================
;;
;; The React root is named `react-root` to avoid colliding with
;; `root-view`. It's held in an atom and created lazily inside `run`,
;; not at ns-load, so requiring this ns has no DOM side effect. That
;; lets the headless fixtures require it without a browser, and keeps
;; co-loaded example namespaces from racing multiple roots onto the
;; shared `#app` element.

(defonce react-root (atom nil))

;; The frame is established by the `frame-provider {:id app-frame …}` at
;; the render root below. On first mount it creates the app frame,
;; applies its config, and runs `:initial-events` (the `[:app/initialise]`
;; boot dispatch) once; on hot reload it reuses the same frame and skips
;; re-seeding. Every in-tree `dispatch`/`subscribe` resolves to that
;; frame — including the work-bench wrapper's `r/with-let` cleanup
;; (views.cljs), which dispatches `[:work/flow [:cancel]]` from render
;; scope. See the frame glossary (docs/guide/glossary.md#frame).
(def app-frame :rf/default)

(defn run []
  ;; Install the Reagent adapter, then mount the provider that
  ;; establishes the frame (see above).
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:app/initialise]]}
                 [views/root-view]])))
