(ns re-frame.react-click-handler-frame-routing-cljs-test
  "React click handlers must route dispatches to the surrounding frame.

  The framework contract nailed down here closes the bug class
  structurally:

    1. Pin the contract: a synchronous `(rf/with-frame :id (rf/dispatch
       ...))` from a React-shaped callback (a setTimeout, an
       addEventListener click, a requestAnimationFrame callback)
       MUST route the dispatch to `:id` — the binding is synchronous
       around the dispatch call, the dispatch envelope captures
       `:frame` at enqueue time, so the queue drain reads the frame off
       the envelope (never re-resolves the dynamic var, which is
       already popped by drain time).
    2. Pin the same contract for `{:frame :id}` opts envelope.

  `(:dispatch (rf/capture-frame))` capture-at-call-time is pinned from a
  setTimeout by `re-frame.dispatch-frame-capture-cljs-test`. The internal
  `re-frame.frame/bind-fn` helper rebinds the same `*current-frame*` the
  `with-frame` pattern does, and is pinned by `re-frame.capture-frame-test`.

  Both patterns dispatch off a setTimeout microtask — same shape as a React
  onClick / onKeyDown firing AFTER React's render commit has popped the
  frame-context."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; Async map-form fixture (a fn-form fixture's teardown would restore the
;; registrar before an `(async done)` body completes). `make-reset-runtime-
;; fixture` performs the snapshot/restore + frames-reset + adapter
;; dispose/install this suite hand-rolled; `:ambient-frame nil` preserves the
;; suite's no-ambient-scope behaviour (each test drives an explicit
;; `with-frame` / `{:frame …}`). The `:init-fn` registers the `:rf/xray`
;; surrogate frame + a reducer/sub pivoting on a per-frame slot so a routing
;; leak shows as 'reducer ran on the wrong frame's db'; those registrations
;; roll back with the per-test registrar snapshot.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :async?        true
     :ambient-frame nil
     :init-fn       (fn []
                      (rf/make-frame {:id :rf/xray :doc "Xray surrogate for the routing tests"})
                      (rf/reg-event :rf-tvu99/set-mode
                                    (fn [{:keys [db]} [_ mode]] {:db (assoc db :mode mode)}))
                      (rf/reg-sub :rf-tvu99/mode
                                  (fn [db _] (get db :mode :diff))))}))

;; ---- helpers --------------------------------------------------------------

(defn- mode-of
  "Return the :mode slot of a frame's app-db. nil if the frame doesn't
  carry the slot."
  [frame-id]
  (get (rf.frame/frame-app-db-value frame-id) :mode))

(defn- await-mode
  "Poll until the named frame's :mode slot equals `expected`. The router
  drain is async (goog.async.nextTick microtask) so a queued dispatch
  needs one or two macrotasks before its reducer lands."
  [frame-id expected]
  (rf.test-support/poll-until
    #(= expected (mode-of frame-id))
    {:label      (str frame-id " :mode = " expected)
     :timeout-ms 1000}))

(defn- inside-set-timeout
  "Drop `f` onto a fresh JS macrotask via setTimeout(0). Models the React
  synthetic-event timing — the click closure fires on a fresh stack with
  no surrounding `*current-frame*` binding from the render that built
  it. Returns a Promise that resolves once `f` has been invoked."
  [f]
  (js/Promise.
    (fn [resolve _reject]
      (js/setTimeout
        (fn []
          (f)
          (resolve nil))
        0))))

;; =========================================================================
;; The canonical patterns — each MUST route the dispatch to :rf/xray
;; =========================================================================

;; ---- 1. Envelope-on-call: {:frame :rf/xray} -----------------------------

(deftest envelope-opts-from-set-timeout-routes-to-target-frame
  (testing "`(rf/dispatch [...] {:frame :rf/xray})` from
            a setTimeout callback (React onClick analogue) routes to
            :rf/xray, never to :rf/default."
    (async done
      (-> (inside-set-timeout
            (fn []
              (rf/dispatch [:rf-tvu99/set-mode :all] {:frame :rf/xray})))
          (.then (fn [_]
                   (await-mode :rf/xray :all)))
          (.then (fn [_]
                   ;; await-mode resolving is the :rf/xray check.
                   (is (nil? (mode-of :rf/default))
                       ":rf/xray :mode flipped via the envelope opts, and :rf/default is NOT polluted")))
          ;; Reports and releases; it never finishes — `done` runs the
          ;; whole remainder of the run synchronously, so a `.catch` downstream of
          ;; it would claim a later namespace's throw and fire `done` twice.
          (.catch (fn [e] (is false (str "promise rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

;; ---- 2. with-frame lexical wrapper --------------------------------------

(deftest with-frame-wrapper-from-set-timeout-routes-to-target-frame
  (testing "`(rf/with-frame :rf/xray (rf/dispatch [...]))`
            from a setTimeout callback routes to :rf/xray. The binding
            is synchronous around the dispatch call; `build-envelope`
            must read `*current-frame*` synchronously so the envelope's
            :frame slot is set at enqueue time (not at drain time when
            the binding has already popped). This is the second per-
            call-site pattern (used by core.cljs `set-target-frame!`
            in imperative code)."
    (async done
      (-> (inside-set-timeout
            (fn []
              (rf/with-frame :rf/xray
                (rf/dispatch [:rf-tvu99/set-mode :all]))))
          (.then (fn [_]
                   (await-mode :rf/xray :all)))
          (.then (fn [_]
                   (is (nil? (mode-of :rf/default))
                       ":rf/xray :mode flipped via the lexical with-frame wrapper, and :rf/default is NOT polluted")))
          (.catch (fn [e] (is false (str "promise rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))
