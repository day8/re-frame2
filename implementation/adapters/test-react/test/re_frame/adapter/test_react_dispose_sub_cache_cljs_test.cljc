(ns re-frame.adapter.test-react-dispose-sub-cache-cljs-test
  "CLJS-gate companion to the rf2-ghfkkk High finding's coverage in
  `re-frame.adapter.test-react-test`.

  The substantive assertion lives in both: test-react's `dispose-adapter!`
  MUST clear every live frame's per-frame sub-cache (entries + ref-counts),
  the externally-visible counterpart of the React adapters' CLJS-only
  `re-frame.substrate.spine/dispose-frame-sub-caches!` walk — routed here
  through the CLJC-safe shared helper
  `re-frame.subs.cache/clear-all-frame-sub-caches!`. Pre-fix, because
  test-react's `make-derived-value` is a plain IDeref reify whose reaction
  never auto-disposes, the `{:reaction … :ref-count n}` cache slot (held on
  the FRAME, not the reaction) survived a dispose/reinstall cycle, allowing a
  later subscribe to read a stale value and breaking process isolation.

  Why a SEPARATE ns from `test_react_test.cljc`: the sibling's ns
  (`re-frame.adapter.test-react-test`) ends in `-test` but NOT `-cljs-test`,
  so it runs only under the JVM cognitect runner (`clojure -M:test`) — the
  shadow-cljs `:node-test` build's `cljs-test$` ns-regexp does not match it.
  This ns ends in `-cljs-test`, so it ALSO rides `npm run test:cljs`, giving
  the contract CLJS coverage. It uses the standard
  `make-reset-runtime-fixture` (mirroring `plain_atom_dispose_cljs_test`) for
  airtight per-test isolation rather than hand-managing frame/registrar
  state."
  (:require [re-frame.adapter.test-react :as test-react]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            #?(:clj  [clojure.test :as ctest :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :as ctest :refer-macros [deftest is testing use-fixtures]])))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter test-react/adapter}))

(defn- sub-cache-keys []
  (if-let [cache (:sub-cache (frame/frame :rf/default))]
    (set (keys @cache))
    #{}))

(defn- entry-ref-count [query-v]
  (get-in @(:sub-cache (frame/frame :rf/default)) [query-v :ref-count]))

(deftest dispose-adapter-clears-sub-cache-and-recomputes-fresh
  (testing "rf2-ghfkkk — test-react dispose-adapter! disposes + clears every
            live frame's sub-cache; a re-subscribe after reinstall recomputes
            from the current app-db (no stale cross-lifecycle read)"
    (rf/reg-event ::seed (fn [{:keys [db]} [_ v]] {:db {:n v}}))
    (rf/reg-sub ::n (fn [db _] (:n db)))
    (rf/dispatch-sync [::seed 1])

    ;; Materialise a real sub-cache slot (ref-count 1).
    (let [r (subs/subscribe :rf/default [::n])]
      (is (= 1 @r) "sub reads the seeded value")
      (is (contains? (sub-cache-keys) [::n]) "subscribe materialised a cache slot")
      (is (= 1 (entry-ref-count [::n])) "slot ref-count = 1"))

    ;; Dispose the adapter — the cache MUST be cleared (entries + ref-counts).
    (substrate-adapter/dispose-adapter!)
    (is (empty? (sub-cache-keys))
        "dispose-adapter! cleared the frame's sub-cache")
    (is (nil? (entry-ref-count [::n]))
        "no stale ref-count carried across the dispose")

    ;; Reinstall, mutate app-db, re-subscribe — must reflect the NEW value.
    (substrate-adapter/install-adapter! test-react/adapter)
    (rf/dispatch-sync [::seed 99])
    (let [r2 (subs/subscribe :rf/default [::n])]
      (is (= 99 @r2)
          "re-subscribe recomputed from the current app-db, not a stale slot")
      (is (= 1 (entry-ref-count [::n]))
          "rebuilt slot is a fresh cache miss (ref-count 1)")
      (subs/unsubscribe :rf/default [::n]))))

(deftest dispose-adapter-clears-sub-caches-across-multiple-frames
  (testing "rf2-ghfkkk — the walk covers EVERY live frame, not just
            :rf/default: a per-instance frame's materialised slot is disposed
            and cleared by the same dispose-adapter! call"
    (rf/reg-event ::seed (fn [{:keys [db]} [_ v]] {:db {:n v}}))
    (rf/reg-sub ::n (fn [db _] (:n db)))
    (let [other (frame/make-anon-frame-record! {:doc "second frame"})]
      ;; Seed + subscribe in BOTH frames so each carries a live cache slot.
      ;; Frame targeting rides the opts map (`:frame`) via the fn-form
      ;; `dispatch-sync*` — the `dispatch-sync` macro has no frame-positional
      ;; arity.
      (rf/dispatch-sync* [::seed 1] {:frame :rf/default})
      (rf/dispatch-sync* [::seed 2] {:frame other})
      (subs/subscribe :rf/default [::n])
      (subs/subscribe other [::n])
      (is (contains? (set (keys @(:sub-cache (frame/frame :rf/default)))) [::n])
          ":rf/default carries a slot")
      (is (contains? (set (keys @(:sub-cache (frame/frame other)))) [::n])
          "the second frame carries a slot")

      (substrate-adapter/dispose-adapter!)
      (is (empty? (set (keys @(:sub-cache (frame/frame :rf/default)))))
          ":rf/default sub-cache cleared")
      (is (empty? (set (keys @(:sub-cache (frame/frame other)))))
          "the second frame's sub-cache cleared too — walk covers every frame")
      (substrate-adapter/install-adapter! test-react/adapter))))
