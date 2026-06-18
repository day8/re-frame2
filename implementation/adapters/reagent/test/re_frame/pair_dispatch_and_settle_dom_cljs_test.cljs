(ns re-frame.pair-dispatch-and-settle-dom-cljs-test
  "rf2-vk79g — end-to-end proof of the re-frame2-pair runtime's
  `dispatch-and-settle!`: dispatch → SYNCHRONOUSLY flush renders →
  return the SETTLED epoch INCLUDING its `:rf.view/render` /
  `:rf.view/unmounted` entries. ONE call = dispatch → render → complete
  epoch.

  WHY THIS LIVES IN THE REAGENT ADAPTER DOM SUITE. `dispatch-and-settle!`
  is the re-frame2-pair runtime preload fn (`re-frame2-pair.runtime`,
  on the global `:source-paths` per implementation/shadow-cljs.edn), but
  its CONTRACT — 'the returned epoch carries the view renders/unmounts' —
  only has teeth against the REAL machinery: a live substrate adapter
  (so `flush-render!` resolves a substrate-native flush), the epoch
  artefact's post-settle render/unmount BACK-FILL (Spec 009 — a render
  fires at React commit time, AFTER the cascade settles, and is
  re-attributed into the causing epoch + re-fanned to listeners), and
  real view-render trace emission. The pair-mcp dispatch_test pins the
  WIRE wiring (`:settle` → `dispatch-and-settle!` form); this pins the
  RUNTIME behaviour the wire depends on. Sibling of
  `adapter-flush-render-dom-cljs-test` (rf2-40a84), whose header notes
  'The sibling rf2-vk79g dispatch-and-settle op consumes this.'

  HOW THE PROOF IS RIGOROUS. A parent view renders a child only when a
  `:show?` flag in app-db is true. We mount the parent through a real
  React root, then drive the flag through `dispatch-and-settle!`:

    - `[:show]`  flips the flag true → the child MOUNTS on the flushed
      re-render → a `:rf.view/render` emit fires at commit time and is
      back-filled into the dispatch's epoch. We assert the returned
      epoch's `:render-events` (and `:renders`) carry it.
    - `[:hide]`  flips the flag false → the child UNMOUNTS → a
      `:rf.view/unmounted` emit fires at teardown and is back-filled
      into that dispatch's epoch's `:trace-events`. We assert
      `:render-events` carries the unmount op.

  The single `dispatch-and-settle!` call does ALL of it synchronously —
  no `setTimeout`, no manual `r/flush`, no `requestAnimationFrame`. If
  the flush were rAF-scheduled (the pre-rf2-40a84 behaviour) the emit
  would land a tick later and the just-read epoch would miss it; the
  assertion passing IS the proof the flush was synchronous and the
  back-fill landed before the re-read.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers
  it; the `:node-test` runner also loads it, where the body gates on
  `(browser?)` and no-ops cleanly (no DOM)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views :as views]
            [re-frame2-pair.runtime :as pair]))

;; EP-0002 (rf2-9o48ih): `:ambient-frame nil` opts out of the fixture's default
;; ambient `*current-frame*` :rf/default scope. The parent/child views are
;; reg-views whose `subscribe` resolves the frame from the enclosing
;; `frame-provider` via the React-context tier; the renders run synchronously
;; inside `flushSync` / `dispatch-and-settle!`, inside the test body's dynamic
;; extent, so an ambient :rf/default scope would shadow the React-context tier
;; at tier 1 and the child would never mount. The pure-runtime second test
;; carries explicit `{:frame …}` on its dispatch, so it does not rely on the
;; ambient scope either.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (when (browser?)
    (.createElement js/document "div")))

(defn- render-event-ops
  "The set of `:operation`s on the epoch's `:render-events` slot — the
  view-lifecycle signal `dispatch-and-settle!` surfaces."
  [settled]
  (into #{} (map :operation) (:render-events settled)))

(deftest dispatch-and-settle-returns-epoch-with-render-and-unmount
  (testing "dispatch-and-settle! flushes renders synchronously and returns
            the epoch carrying :rf.view/render + :rf.view/unmounted (rf2-vk79g)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [frame-kw :rf.pair-settle/probe-frame]
        ;; Recording must be ON for the epoch to be assembled + re-read
        ;; (the default depth is 50; pin it explicitly so the test does
        ;; not depend on ambient config).
        (rf/configure! {:epoch-history {:depth 50}})
        (rf/reg-frame frame-kw {:doc "dispatch-and-settle! probe frame"})
        (rf/reg-event ::seed (fn [{:keys [db]} _] {:db {:show? false}}))
        (rf/reg-event ::show (fn [{:keys [db]} _] {:db (assoc db :show? true)}))
        (rf/reg-event ::hide (fn [{:keys [db]} _] {:db (assoc db :show? false)}))
        (rf/reg-sub ::show? (fn [db _] (:show? db)))
        ;; The child whose mount/unmount we drive. Distinct registered id
        ;; so its render/unmount emits carry an identifying :view-id tag.
        (rf/reg-view* :rf.pair-settle/child
                      (fn child [] [:span.child "child"]))
        ;; The parent gates the child on the :show? flag, so toggling the
        ;; flag mounts / unmounts the child on the next flushed render.
        (rf/reg-view* :rf.pair-settle/parent
                      (fn parent []
                        ;; No frame arg on the subscribe — the
                        ;; frame-provider wrapping the mounted tree binds
                        ;; the frame for the view (mirrors the rf2-40a84
                        ;; flush-render sibling test).
                        [:div.parent
                         (when @(rf/subscribe [::show?])
                           [(rf/view :rf.pair-settle/child)])]))
        ;; Pin the runtime's operating frame to ours so the no-arg
        ;; dispatch-and-settle! arity resolves the right frame.
        (pair/select-frame! frame-kw)
        ;; Seed the flag false and mount the parent (child absent). The
        ;; mount itself is wrapped in flushSync so the first commit lands
        ;; before render returns (React 19 root.render is otherwise async).
        (rf/dispatch-sync [::seed] {:frame frame-kw})
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn []
                (rdc/render root [rf/frame-provider {:frame frame-kw}
                                  [(rf/view :rf.pair-settle/parent)]])))
            (is (nil? (.querySelector mount-node ".child"))
                "child absent before :show")

            ;; ---- MOUNT: dispatch-and-settle! [:show] ----
            (let [settled (pair/dispatch-and-settle! [::show] {:frame frame-kw})
                  ops     (render-event-ops settled)]
              (is (:ok? settled) "dispatch-and-settle! succeeded")
              (is (true? (:settled? settled))
                  "the render layer flushed synchronously")
              (is (some? (:epoch settled))
                  "the SETTLED epoch record rides back")
              (is (some? (.querySelector mount-node ".child"))
                  "child MOUNTED — flush-render! committed the re-render
                   synchronously inside the single settle call")
              (is (contains? ops :rf.view/render)
                  "the settled epoch's :render-events carry a :rf.view/render
                   for the just-mounted child (back-filled before the re-read)")
              (is (pos? (get-in settled [:cascade-summary :renders]))
                  ":cascade-summary :renders (a COUNT) reflects the flushed
                   render — not the pre-flush zero"))

            ;; ---- UNMOUNT: dispatch-and-settle! [:hide] ----
            ;; flush-render! synchronously COMMITS the removal — the child
            ;; leaves the DOM before the call returns (asserted below).
            ;;
            ;; The `:rf.view/unmounted` EMIT rides Reagent's per-component
            ;; render-reaction DISPOSAL, a passive teardown React schedules
            ;; AFTER the synchronous commit (passive-effect cleanups are
            ;; async even under flushSync). The contract
            ;; `dispatch-and-settle!` owns is the ATTRIBUTION TARGET:
            ;; whenever that teardown emit fires, the framework back-fills
            ;; it into the frame's most-recently-settled epoch — the
            ;; [:hide] epoch this call just returned — and re-fans the
            ;; record (Spec 009 §post-settle unmount back-fill, rf2-59hx3).
            ;;
            ;; We prove that target is correct deterministically: with the
            ;; removal committed and the [:hide] epoch the
            ;; most-recently-settled one, we fire the teardown emit through
            ;; the SAME public surface React's componentWillUnmount uses
            ;; (`views/emit-view-unmounted!`, the rf2-9hoos teardown
            ;; marker), then RE-READ `dispatch-and-settle!`'s epoch-id and
            ;; assert the unmount landed in it. (Driving the emit directly
            ;; rather than racing Reagent's deferred reaction-disposal
            ;; flush keeps the assertion deterministic — the back-fill
            ;; routing is what this gate pins, not Reagent's dispose
            ;; cadence.)
            (let [settled    (pair/dispatch-and-settle! [::hide] {:frame frame-kw})
                  hide-epoch (:epoch-id settled)]
              (is (:ok? settled) "dispatch-and-settle! succeeded")
              (is (true? (:settled? settled)))
              (is (nil? (.querySelector mount-node ".child"))
                  "child REMOVED from the DOM synchronously on the settle")
              ;; The teardown emit React fires post-commit — same surface,
              ;; fired now that [:hide] is the most-recently-settled epoch.
              (views/emit-view-unmounted! :rf.pair-settle/child
                                          [:rf.pair-settle/child 1]
                                          frame-kw)
              ;; Re-read the SAME epoch the settle returned — the unmount
              ;; back-fills into it + re-fans, so its :trace-events now
              ;; carry the :rf.view/unmounted teardown.
              (let [refreshed (pair/epoch-by-id hide-epoch frame-kw)
                    ops       (into #{} (map :operation) (:trace-events refreshed))]
                (is (contains? ops :rf.view/unmounted)
                    "the [:hide] epoch dispatch-and-settle! returned is the
                     unmount-attribution target — the teardown back-fills
                     into it + re-fans (Spec 009 / rf2-59hx3)")))
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))

(deftest dispatch-and-settle-frame-failure-rides-through
  (testing "an :ok? false dispatch (no recorded epoch) rides through with
            NO flush attempted — the rf2-ldfnx invariant holds (rf2-vk79g)"
    ;; Pure-runtime arm — no DOM needed; runs on :node-test too. With
    ;; recording disabled the frame records no epoch, so
    ;; pair-dispatch-sync! returns the :ok? false envelope;
    ;; dispatch-and-settle! must pass it through verbatim (no :settled?,
    ;; no :render-events) rather than claim a settle.
    ;;
    ;; `:epoch-history {:depth ...}` is a PROCESS-GLOBAL knob. We flip it
    ;; to 0 for the probe and RESTORE it to the framework default (50) in
    ;; a finally — otherwise a leaked depth-0 disables epoch recording for
    ;; every subsequent test in this JS runtime (the cljs.test runner
    ;; shares one process).
    (let [frame-kw :rf.pair-settle/no-record-frame]
      (try
        (rf/configure! {:epoch-history {:depth 0}}) ;; recording OFF (process-global)
        (rf/reg-frame frame-kw {:doc "no-epoch probe frame"})
        (rf/reg-event ::noop (fn [{:keys [db]} _] {:db db}))
        (let [settled (pair/dispatch-and-settle! [::noop] {:frame frame-kw})]
          (is (false? (:ok? settled))
              "no recorded epoch ⇒ :ok? false rides through")
          (is (not (contains? settled :settled?))
              "NO :settled? slot — nothing settled")
          (is (not (contains? settled :render-events))
              "NO :render-events slot on the failure path"))
        (finally
          ;; Restore the framework default so the global knob does not
          ;; leak into sibling tests.
          (rf/configure! {:epoch-history {:depth 50}}))))))
