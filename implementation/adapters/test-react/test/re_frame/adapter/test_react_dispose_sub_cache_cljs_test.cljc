(ns re-frame.adapter.test-react-dispose-sub-cache-cljs-test
  "Coverage for test-react's `dispose-adapter!` sub-cache contract — this ns
  OWNS it.

  test-react's `dispose-adapter!`
  MUST clear every live frame's per-frame sub-cache (entries + ref-counts),
  the externally-visible counterpart of the React adapters' CLJS-only
  `re-frame.substrate.spine/dispose-frame-sub-caches!` walk — routed here
  through the CLJC-safe shared helper
  `re-frame.subs.cache/clear-all-frame-sub-caches!`. Because test-react's
  `make-derived-value` is a plain IDeref reify whose reaction never
  auto-disposes, the `{:reaction … :ref-count n}` cache slot (held on the
  FRAME, not the reaction) would otherwise survive a dispose/reinstall cycle,
  allowing a later subscribe to read a stale value and breaking process
  isolation.

  `dispose-adapter-clears-sub-caches-across-multiple-frames` pins it: the walk
  covers EVERY live frame, not just `:rf/default`. An emptied cache is what
  makes the next subscribe a fresh cache miss that recomputes from the current
  app-db.

  The ns ends in `-cljs-test`, so it rides `npm run test:cljs` as well as the
  JVM cognitect runner. It uses the standard `make-reset-runtime-fixture`
  (mirroring `plain_atom_dispose_cljs_test`) for airtight per-test isolation
  rather than hand-managing frame/registrar state."
  (:require [re-frame.adapter.test-react :as rf.adapter.test-react]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]
            #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.adapter.test-react/adapter}))

(deftest dispose-adapter-clears-sub-caches-across-multiple-frames
  (testing "the walk covers EVERY live frame, not just
            :rf/default: a per-instance frame's materialised slot is disposed
            and cleared by the same dispose-adapter! call"
    (rf/reg-event ::seed (fn [_ctx [_ v]] {:db {:n v}}))
    (rf/reg-sub ::n (fn [db _] (:n db)))
    (let [other (rf.frame/make-anon-frame-record! {:doc "second frame"})]
      ;; Seed + subscribe in BOTH frames so each carries a live cache slot.
      ;; Frame targeting rides the opts map (`:frame`), the explicit-
      ;; override 2-arity — no frame-positional arity.
      (rf/dispatch-sync [::seed 1] {:frame :rf/default})
      (rf/dispatch-sync [::seed 2] {:frame other})
      (rf.subs/subscribe [::n] {:frame :rf/default})
      (rf.subs/subscribe [::n] {:frame other})
      (is (contains? (set (keys @(:sub-cache (rf.frame/frame :rf/default)))) [::n])
          ":rf/default carries a slot")
      (is (contains? (set (keys @(:sub-cache (rf.frame/frame other)))) [::n])
          "the second frame carries a slot")

      (rf.substrate.adapter/dispose-adapter!)
      (is (empty? (set (keys @(:sub-cache (rf.frame/frame :rf/default)))))
          ":rf/default sub-cache cleared")
      (is (empty? (set (keys @(:sub-cache (rf.frame/frame other)))))
          "the second frame's sub-cache cleared too — walk covers every frame")
      (rf.substrate.adapter/install-adapter! rf.adapter.test-react/adapter))))
