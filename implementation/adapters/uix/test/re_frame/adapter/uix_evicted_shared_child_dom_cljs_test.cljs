(ns re-frame.adapter.uix-evicted-shared-child-dom-cljs-test
  "rf2-1frc (residual) — TWO MOUNTED UIx parents over ONE shared child survive a
  framework-owned cache eviction still sharing that child.

  ## What this pins that the unit lane cannot

  `re-frame.subs-evicted-input-release-cljs-test` (core, `.cljc`) drives the
  same seam with a hand-rolled eager-reacquisition holder and asserts the
  sub-cache ref-count directly. This is the MOUNTED counterpart the acceptance
  asks for: two real `use-sub` consumers under the real React-hook spine, so
  the reacquisition under test is the spine's own `on-committed-disposed`
  callback rather than a stand-in, and the consequence is read off COMMITTED
  DOM rather than off a ref-count alone.

  ## The defect

  Both eviction primitives (`rf.subs.cache/invalidate-frame-subs!` and
  `clear-sub-cache!`) remove the whole condemned batch from the cache atom
  BEFORE disposing any member of it, and since PR #9373 a mounted hook whose
  reaction is disposed REACQUIRES from inside that walk. So a later member's
  teardown ran against a cache an earlier member had already repopulated — and
  `re-frame.subs`' input release was address-only where the cache-dissoc
  beside it was `identical?`-guarded. Two parents P1/P2 over one child C went
  `{C 2, P1 1, P2 1}` → `{C 1, P1 1, P2 1}`, with P1 left holding the
  intermediate child that P2's release had disposed, and a third C built for
  P2.

  The repair routes the release through `re-frame.subs/unsubscribe-if-reaction`
  carrying the reaction the build actually acquired.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` (ns-regexp
  `-dom-cljs-test$`) discovers it for the real DOM assertions; `:node-test`'s
  `cljs-test$` regex also matches, where the test self-gates on `(browser?)`
  and no-ops cleanly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]))

;; MAP-FORM fixture with `:async? true`: the assertions below cross the spine's
;; provisional-acquisition horizon (`settle-past-the-horizon!`), so they are
;; `(async done …)` tests and cljs.test refuses those under a plain-fn `:each`
;; fixture.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true}))

(def ^:private ec-frame :rf.uix-evicted-shared-child/frame)

(def ^:private observed-a (atom []))
(def ^:private observed-b (atom []))

(defui ParentA []
  (let [v (rf.adapter.uix/use-sub [::p1] {:frame ec-frame})]
    (swap! observed-a conj v)
    ($ :div {:id "ec-a"} (str "a=" v))))

(defui ParentB []
  (let [v (rf.adapter.uix/use-sub [::p2] {:frame ec-frame})]
    (swap! observed-b conj v)
    ($ :div {:id "ec-b"} (str "b=" v))))

(defui BothParents []
  ($ :div
     ($ ParentA)
     ($ ParentB)))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (when (exists? (.-act React)) (.-act React)))

(def ^:private horizon-settle-ms
  "Comfortably past the spine's provisional reap horizon
  (`rf.substrate.spine/provisional-horizon-ms`, ruled 4 by rf2-2rtt6.71): these
  assertions read the state the reaper LEFT, so a settle that races it proves
  nothing."
  24)

(defn- settle! [k] (js/setTimeout k horizon-settle-ms))

(defn- ref-count-of
  [query-v]
  (or (get-in @(:sub-cache (rf.frame/frame ec-frame)) [query-v :ref-count]) 0))

(deftest two-mounted-parents-keep-sharing-one-child-across-a-cache-clear
  (testing "UIx — two mounted use-sub consumers over ONE shared declared input
            survive clear-sub-cache! still sharing that input (rf2-1frc)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (async done
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (reset! observed-a [])
            (reset! observed-b [])
            (rf/make-frame {:id  ec-frame
                            :doc "rf2-1frc two-parent / shared-child probe frame"})
            (rf/reg-event ::seed (fn [_ctx _e] {:db {:n 1}}))
            (rf/reg-event ::inc  (fn [{:keys [db]} _e] {:db (update db :n inc)}))
            (rf/dispatch-sync [::seed] {:frame ec-frame})
            (rf/reg-sub ::child (fn [db _q] (:n db)))
            (rf/reg-sub ::p1 {:inputs [[::child]]} (fn [[c] _q] (* 10 c)))
            (rf/reg-sub ::p2 {:inputs [[::child]]} (fn [[c] _q] (* 100 c)))

            (let [mount-node (.createElement js/document "div")
                  root       (react-dom-client/createRoot mount-node)]
              (act-fn (fn [] (.render root ($ BothParents))))
              (settle!
                (fn []
                  (is (= 2 (ref-count-of [::child]))
                      "baseline: the shared child carries one ref per mounted parent")
                  (is (= "a=10b=100" (.-textContent mount-node))
                      "baseline: both parents committed their derived values")

                  ;; THE FRAMEWORK-OWNED EVICTION. Every slot leaves the cache
                  ;; before any of them is disposed, so the second parent's
                  ;; teardown runs against a cache the first parent's eager
                  ;; reacquisition has already repopulated.
                  (act-fn (fn [] (rf.subs.cache/clear-sub-cache! ec-frame)))

                  (settle!
                    (fn []
                      (is (= 2 (ref-count-of [::child]))
                          "THE BUG (rf2-1frc residual): pre-fix this read 1 — the
                           second parent's address-only input release decremented
                           and disposed the SUCCESSOR child the first parent had
                           just built, and its own reacquisition built a third")
                      (is (= 1 (ref-count-of [::p1]))
                          "P1 holds exactly one reference to its rebuilt reaction")
                      (is (= 1 (ref-count-of [::p2]))
                          "P2 holds exactly one reference to its rebuilt reaction")

                      ;; The point of the ref-count: a parent left holding the
                      ;; disposed intermediate child stops re-committing.
                      (act-fn (fn [] (rf/dispatch-sync [::inc] {:frame ec-frame})))
                      (settle!
                        (fn []
                          (is (= "a=20b=200" (.-textContent mount-node))
                              "BOTH mounted parents re-committed after the
                               eviction — neither is watching a child that was
                               disposed out from under it")
                          (act-fn (fn [] (.unmount root)))
                          (settle!
                            (fn []
                              (is (zero? (ref-count-of [::child]))
                                  "unmounting both parents releases the shared
                                   child exactly — no leak, no double-release")
                              (done))))))))))))))))
