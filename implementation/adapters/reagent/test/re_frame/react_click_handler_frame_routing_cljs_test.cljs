(ns re-frame.react-click-handler-frame-routing-cljs-test
  "Regression tests for rf2-tvu99 — React click handlers must route
  dispatches to the surrounding frame.

  This is the 4th instance of the same root-cause pattern (sibling beads
  rf2-7sdja popup, rf2-kcaiz zoom, rf2-p56sk subs-toggle). The framework
  fix nailed down here closes the bug class structurally:

    1. Pin the contract: a synchronous `(rf/with-frame :id (rf/dispatch
       ...))` from a React-shaped callback (a setTimeout, an
       addEventListener click, a requestAnimationFrame callback)
       MUST route the dispatch to `:id` — the binding is synchronous
       around the dispatch call, the dispatch envelope captures
       `:frame` at enqueue time, so the queue drain reads the frame off
       the envelope (never re-resolves the dynamic var, which is
       already popped by drain time).
    2. Pin the same contract for `{:frame :id}` opts envelope.
    3. Pin the same contract for `(:dispatch (rf/capture-frame))` capture-at-call-time.
    4. Pin the internal `re-frame.frame/bind-fn` helper (the relocated
       `frame-bound-fn*` dynamic-rebinding primitive, API-shrink #1
       rf2-csbbwu removed the public `frame-bound-fn` / `frame-bound-fn*`
       facade names): a fn that wraps a callback so any synchronous
       dispatch inside is captured-and-rebound to the call-site's frame.

  All four patterns dispatch off a setTimeout/addEventListener/rAF
  microtask — same shape as a React onClick / onKeyDown firing AFTER
  React's render commit has popped the frame-context.

  Per the bead body, the root cause is documented in the PR body — it
  is NOT 'the dynamic var doesn't survive the microtask' (it doesn't,
  but the envelope-capture path is supposed to make that irrelevant);
  it's whatever subtler thing makes the per-call-site patches
  unreliable in production."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

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
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
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
  (get (frame/frame-app-db-value frame-id) :mode))

(defn- await-mode
  "Poll until the named frame's :mode slot equals `expected`. The router
  drain is async (goog.async.nextTick microtask) so a queued dispatch
  needs one or two macrotasks before its reducer lands."
  [frame-id expected]
  (test-support/poll-until
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
;; The four canonical patterns — each MUST route the dispatch to :rf/xray
;; =========================================================================

;; ---- 1. Envelope-on-call: {:frame :rf/xray} -----------------------------

(deftest envelope-opts-from-set-timeout-routes-to-target-frame
  (testing "rf2-tvu99 — `(rf/dispatch [...] {:frame :rf/xray})` from
            a setTimeout callback (React onClick analogue) routes to
            :rf/xray, never to :rf/default. This is the first per-
            call-site pattern the popup landed on (rf2-7sdja)."
    (async done
      (-> (inside-set-timeout
            (fn []
              (rf/dispatch [:rf-tvu99/set-mode :all] {:frame :rf/xray})))
          (.then (fn [_]
                   (await-mode :rf/xray :all)))
          (.then (fn [_]
                   (is (= :all (mode-of :rf/xray))
                       ":rf/xray :mode flips to :all via the envelope opts")
                   (is (nil? (mode-of :rf/default))
                       ":rf/default is NOT polluted by the dispatch")))
          ;; Reports and releases; it never finishes (rf2-fyba) — `done` runs the
          ;; whole remainder of the run synchronously, so a `.catch` downstream of
          ;; it would claim a later namespace's throw and fire `done` twice.
          (.catch (fn [e] (is false (str "promise rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

;; ---- 2. with-frame lexical wrapper --------------------------------------

(deftest with-frame-wrapper-from-set-timeout-routes-to-target-frame
  (testing "rf2-tvu99 — `(rf/with-frame :rf/xray (rf/dispatch [...]))`
            from a setTimeout callback routes to :rf/xray. The binding
            is synchronous around the dispatch call; `build-envelope`
            must read `*current-frame*` synchronously so the envelope's
            :frame slot is set at enqueue time (not at drain time when
            the binding has already popped). This is the second per-
            call-site pattern (used by core.cljs `set-target-frame!`
            in imperative code) — the bead claims it doesn't work
            from React handlers; this test pins whether it does."
    (async done
      (-> (inside-set-timeout
            (fn []
              (rf/with-frame :rf/xray
                (rf/dispatch [:rf-tvu99/set-mode :all]))))
          (.then (fn [_]
                   (await-mode :rf/xray :all)))
          (.then (fn [_]
                   (is (= :all (mode-of :rf/xray))
                       ":rf/xray :mode flips to :all via the lexical with-frame wrapper")
                   (is (nil? (mode-of :rf/default))
                       ":rf/default is NOT polluted by the dispatch")))
          (.catch (fn [e] (is false (str "promise rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

;; ---- 3. (:dispatch (rf/capture-frame)) capture-at-call-time ----------------------------

(deftest dispatcher-captured-under-with-frame-from-set-timeout-routes
  (testing "rf2-tvu99 — `(:dispatch (rf/capture-frame))` captured inside a
            `with-frame :rf/xray` block produces a dispatch-fn that
            carries :rf/xray onto every envelope regardless of when it
            is called. The capture closes over the frame VALUE — no
            dynamic-var read at call time."
    (async done
      ;; Capture the dispatcher inside :rf/xray (synchronous — like
      ;; building it inside a reg-view body whose render is under the
      ;; :rf/xray frame-provider).
      (let [d (rf/with-frame :rf/xray (:dispatch (rf/capture-frame)))]
        (-> (inside-set-timeout
              (fn [] (d [:rf-tvu99/set-mode :all])))
            (.then (fn [_]
                     (await-mode :rf/xray :all)))
            (.then (fn [_]
                     (is (= :all (mode-of :rf/xray))
                         ":rf/xray :mode flips via the captured dispatcher")
                     (is (nil? (mode-of :rf/default))
                         ":rf/default is NOT polluted")))
            (.catch (fn [e] (is false (str "promise rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---- 4. re-frame.frame/bind-fn — the internal HoF framework helper ------
;;
;; API-shrink #1 (rf2-csbbwu) removed the public `frame-bound-fn` macro /
;; `frame-bound-fn*` fn from the facade — `capture-frame` (pattern 3, above)
;; is the ONE public carry primitive. `bind-fn`'s dynamic-rebinding
;; semantics are genuinely different (it re-establishes `*current-frame*`
;; around an ARBITRARY already-held fn, not a pre-bound op bundle) and
;; survive internally for the framework's own routing correctness — pinned
;; here directly against `re-frame.frame/bind-fn`.

(deftest bind-fn-wraps-callback-routes-to-target-frame
  (testing "rf2-tvu99 / rf2-kkut0 — `(frame/bind-fn frame-id f)` returns a fn
            that re-binds `*current-frame*` (to the given frame-id) for the
            duration of every invocation. This is the structural
            framework helper — survives any React boundary, any async
            escape, any concurrent renderer transition."
    (async done
      ;; Wrap under :rf/xray.
      (let [wrapped (frame/bind-fn :rf/xray
                      (fn [mode] (rf/dispatch [:rf-tvu99/set-mode mode])))]
        (-> (inside-set-timeout
              (fn [] (wrapped :all)))
            (.then (fn [_]
                     (await-mode :rf/xray :all)))
            (.then (fn [_]
                     (is (= :all (mode-of :rf/xray))
                         ":rf/xray :mode flips via the bind-fn wrap")
                     (is (nil? (mode-of :rf/default))
                         ":rf/default is NOT polluted")))
            (.catch (fn [e] (is false (str "promise rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest bind-fn-captured-under-with-frame-routes-to-captured-frame
  (testing "rf2-tvu99 / rf2-kkut0 — `(frame/bind-fn frame-id f)` built inside
            a `with-frame` block (during render, when *current-frame* is
            bound by the surrounding reg-view / with-frame / frame-provider)
            re-binds on every call, so a setTimeout-deferred invocation
            still routes to the captured frame."
    (async done
      (let [wrapped (rf/with-frame :rf/xray
                      (frame/bind-fn :rf/xray
                        (fn [mode] (rf/dispatch [:rf-tvu99/set-mode mode]))))]
        (-> (inside-set-timeout
              (fn [] (wrapped :all)))
            (.then (fn [_]
                     (await-mode :rf/xray :all)))
            (.then (fn [_]
                     (is (= :all (mode-of :rf/xray))
                         ":rf/xray :mode flips via the bind-fn-captured callback")
                     (is (nil? (mode-of :rf/default))
                         ":rf/default is NOT polluted")))
            (.catch (fn [e] (is false (str "promise rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---- regression: dispatch-sync from inside the wrap routes too ---------

(deftest bind-fn-sync-dispatch-routes-too
  (testing "rf2-tvu99 — `dispatch-sync` inside the wrapped callback
            also routes to the captured frame. The binding tier is
            the same; nothing about the sync drain bypasses it."
    (let [wrapped (frame/bind-fn :rf/xray
                    (fn [mode] (rf/dispatch-sync [:rf-tvu99/set-mode mode])))]
      (wrapped :all)
      (is (= :all (mode-of :rf/xray))
          ":rf/xray :mode flips via sync dispatch inside the wrap")
      (is (nil? (mode-of :rf/default))
          ":rf/default is NOT polluted"))))
