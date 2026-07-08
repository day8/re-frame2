(ns long-running-work.core
  "Run a big job by splitting it across several workers at once.

   The job here is embarrassingly parallel: three independent shards,
   each chewed through on its own. So instead of one machine grinding
   the whole thing in sequence, we use the `:spawn-all` shape — a
   parent coordinator spawns N children, one per shard, and waits for
   them all. See the machines guide's `:spawn`
   (docs/machines/glossary.md#spawn).

   What you'll see here:

   - **Spawn, then join** — the parent spawns N children with one
     `:spawn-all` declaration and joins on `:all`. It keeps no tally
     of who's finished; the runtime owns the join state, so the
     parent's `:data` stays clean.
   - **Cancellation that always lands** — whenever the parent leaves
     `:working` (you hit Cancel, the job finishes, the frame is
     destroyed, a timeout fires), one `:rf.machine/destroy` fx tears
     down every child still standing. Their in-flight timers and HTTP
     requests go down with them, so nothing keeps ticking after the
     curtain falls.
   - **Live progress** — each child fires a `:progress` event at the
     parent after every chunk. The parent stashes it in
     `:data :progress`, the `:work/progress-fraction` sub recomputes,
     and the bar inches forward.
   - **Unmount = cancel** — the component wrapping the work-bench
     dispatches `[:work/flow [:cancel]]` from its `r/with-let`
     cleanup. That single dispatch is the *only* place the UI touches
     the machine; the cascade handles everything downstream.

   The files, and what each owns:

     core.cljs    mount + boot (you are here)
     worker.cljs  the two machines — the :work/processor child and the
                  :work/flow parent coordinator, where the :spawn-all
                  declaration lives
     views.cljs   the UI — controls, progress bar, shard breakdown,
                  root, and the work-bench wrapper whose with-let
                  cleanup fires the unmount cascade
     schema.cljs  malli schemas for the parent and child snapshots

   Run it live, from `implementation/`:

     shadow-cljs watch examples/long-running-work

   The example tree is test-free; the coverage lives in the framework test
   tree at ns `re-frame.long-running-work-cljs-test`
   (implementation/adapters/reagent/test/re_frame/long_running_work_cljs_test.cljs),
   which rides `npm run test:cljs`."
  ;; Plain Reagent here (`reagent.dom.client` + `re-frame.adapter.reagent`).
  ;; The trick worth watching is in views.cljs: a `r/with-let`
  ;; finally-clause that fires the `:cancel` cascade when the view
  ;; unmounts, riding on Reagent's own `reagent.core/with-let`.
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Requiring re-frame.machines is what installs the machine
            ;; runtime: the :spawn-all init / spawn / destroy fx handlers
            ;; and the `:rf/machine` sub. worker.cljs and views.cljs pull
            ;; it in transitively, but we name it here too so this ns
            ;; stands up on its own.
            [re-frame.machines]
            [long-running-work.schema]
            [long-running-work.worker]
            [long-running-work.views :as views]))

;; ============================================================================
;; INITIALISATION
;; ============================================================================
;;
;; One boot event, two jobs to hand out. `:app/initialise` just fans out
;; to the feature initialisers: `:work/initialise` parks the parent
;; machine in :idle, and `:ui/initialise` flips the Show/Hide toggle on.

(rf/reg-event :app/initialise
  {:doc "The boot event. Doesn't do much itself — just hands off to
         each feature's own initialiser."}
  (fn handler-app-initialise [_ _]
    {:fx [[:dispatch [:work/initialise]]
          [:dispatch [:ui/initialise]]]}))

;; ============================================================================
;; MOUNT  (client-only)
;; ============================================================================
;;
;; We call the React root `react-root` so it doesn't get confused with
;; `root-view`. It lives in an atom and is created lazily inside `run`,
;; never at ns-load — so merely requiring this ns touches no DOM. That
;; buys two things: the headless fixtures can require it without a
;; browser, and two co-loaded examples won't race to plant rival roots
;; on the shared `#app` element.

(defonce react-root (atom nil))

;; The frame comes from the `frame-provider {:id app-frame …}` at the
;; render root below. First mount creates the frame, applies its config,
;; and runs `:initial-events` (our `[:app/initialise]` boot dispatch)
;; exactly once; a hot reload reuses the same frame and skips the
;; re-seed. Every `dispatch`/`subscribe` inside the tree finds that
;; frame — including the work-bench wrapper's `r/with-let` cleanup over
;; in views.cljs, which dispatches `[:work/flow [:cancel]]` from render
;; scope. See the frame glossary (docs/core/glossary.md#frame).
(def app-frame :rf/default)

(defn run []
  ;; Install the Reagent adapter, then mount the provider that stands
  ;; the frame up (see above).
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:app/initialise]]}
                 [views/root-view]])))
