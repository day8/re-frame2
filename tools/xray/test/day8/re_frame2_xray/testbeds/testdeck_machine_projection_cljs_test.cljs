(ns day8.re-frame2-xray.testbeds.testdeck-machine-projection-cljs-test
  "Pure-data regression guard for rf2-21rkg — the testdeck `:ws/connection`
  machine's projection must resolve every edge `:to` to a path that exists
  in the projected `:nodes`.

  The bug: `tools/xray/testbeds/testdeck/machine.cljs` used the keyword
  form `:target :failed` from `[:active :authenticating]`. Per Spec 005
  §Target resolution, a keyword target resolves to a sibling of the
  declaring state — i.e. `[:active :failed]`, which does not exist
  (the `:failed` state lives at top-level `[:failed]`). The
  machines-viz projector minted a `[:active :failed]` edge `:to`,
  ELK rejected the graph with `Referenced shape does not exist:
  active__failed`, and the Machine tab couldn't render.

  The fix used the vector form `:target [:failed]` (Spec 005 §1029:
  vector targets are unambiguous and the recommended form for
  cross-level transitions).

  This test pins the projection invariant: for the testdeck machine,
  the set of edge `:to` paths is a SUBSET of the set of node paths.
  A regression to the keyword form would re-mint `[:active :failed]`
  in `:to` while `:nodes` still has `[:failed]`, and the subset
  assertion would fail."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.set :as set]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [testdeck.machine :as testdeck]))

(deftest every-edge-to-resolves-to-a-projected-node
  (testing "rf2-21rkg — every :to path in the projected edges exists in :nodes
            (no edge points at a non-existent state). Pins the spec-compliant
            target form for cross-hierarchy transitions."
    (let [{:keys [nodes edges]} (layout/parse-definition testdeck/connection-machine)
          node-paths            (into #{} (map :path) nodes)
          edge-to-paths         (into #{} (map :to)   edges)
          dangling              (set/difference edge-to-paths node-paths)]
      (is (empty? dangling)
          (str "edge :to paths must all exist in :nodes; dangling: "
               (pr-str dangling))))))

(deftest every-edge-from-resolves-to-a-projected-node
  (testing "symmetric guard — every :from path must also exist (would
            catch a different shape of misuse where a substate is renamed
            but a transition still references the old name)."
    (let [{:keys [nodes edges]} (layout/parse-definition testdeck/connection-machine)
          node-paths            (into #{} (map :path) nodes)
          edge-from-paths       (into #{} (map :from) edges)
          dangling              (set/difference edge-from-paths node-paths)]
      (is (empty? dangling)
          (str "edge :from paths must all exist in :nodes; dangling: "
               (pr-str dangling))))))

(deftest authenticating-failure-edge-points-at-top-level-failed
  (testing "rf2-21rkg — the `:after`-driven failure branch from
            [:active :authenticating] must resolve to top-level [:failed]
            (cross-hierarchy vector target), NOT [:active :failed]
            (the broken keyword-form resolution)."
    (let [{:keys [edges]} (layout/parse-definition testdeck/connection-machine)
          failure-edge    (some (fn [{:keys [from action] :as e}]
                                  (when (and (= [:active :authenticating] from)
                                             (= :record-error action))
                                    e))
                                edges)]
      (is (some? failure-edge)
          "the authenticating→failed `:after` edge with :record-error must project")
      (is (= [:failed] (:to failure-edge))
          "the failure edge resolves to top-level [:failed], not [:active :failed]"))))
