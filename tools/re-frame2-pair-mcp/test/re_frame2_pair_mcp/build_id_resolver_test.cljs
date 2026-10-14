(ns re-frame2-pair-mcp.build-id-resolver-test
  "Forgiving suffix-to-canonical build-id resolution.

  A bare tail such as `machine-epochs` resolves to the unique running
  build ending in it (`:examples/machine-epochs`) through one shared
  resolver (`probe/canonicalize-build!` + `probe/match-running-build`),
  which records the alias on the conn for `wire/arg-build` to apply on
  every op. An ambiguous or unmatched id falls through unchanged to the
  diagnostic ladder, whose paste-back running-build list is pinned in
  probe-test. Colon tolerance is pinned in id-injection-test; the alias
  applied to a suffix `:build`, and its sticky transfer to the next op,
  in sticky-build-invoke-test."
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.probe :as probe]))

(deftest match-running-build-resolution-rule
  ;; The suffix rule applies only to a bare requested id: a namespaced id
  ;; that is not an exact match is never redirected to another namespace.
  (doseq [[requested running expected note]
          [[:examples/machine-epochs [:examples/machine-epochs :testbeds/panel-gallery]
            :examples/machine-epochs
            "an exact keyword match resolves to itself"]
           [:machine-epochs [:testbeds/panel-gallery :examples/machine-epochs]
            :examples/machine-epochs
            "a unique name-suffix resolves to the canonical running id"]
           [:machine-epochs [:examples/machine-epochs :other/machine-epochs]
            :machine-epochs
            "two builds sharing the tail stay ambiguous"]
           [:typo [:examples/machine-epochs]
            :typo
            "no match returns the requested id so the diagnostic ladder fires"]
           [:examples/step-deck [:other/step-deck]
            :examples/step-deck
            "a namespaced request with no exact match falls through unchanged"]]]
    (is (= expected (probe/match-running-build requested running)) note)))

(deftest canonicalize-suffix-resolves-and-caches
  ;; The second resolution of the same id is served from the conn's alias
  ;; cache, with no second active-builds round-trip.
  (async done
    (let [conn  (nrepl/make-conn 0 "127.0.0.1")
          calls (atom 0)
          orig  probe/running-builds]
      (set! probe/running-builds
            (fn [_] (swap! calls inc) (js/Promise.resolve [:examples/machine-epochs])))
      (-> (probe/canonicalize-build! conn :machine-epochs)
          (.then (fn [c1]
                   (.then (probe/canonicalize-build! conn :machine-epochs)
                          (fn [c2] [c1 c2]))))
          (.then (fn [resolved]
                   (is (= [[:examples/machine-epochs :examples/machine-epochs] 1]
                          [resolved @calls]))))
          (.finally (fn [] (set! probe/running-builds orig)))
          (.then (fn [_] (done)))))))
