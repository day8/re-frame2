(ns re-frame.adapter.reagent-slim-surface-parity-cljs-test
  "The published slim artefact ships its adapter at the canonical
  `re-frame.adapter.reagent` ns (IMPL-SPEC §13.1 rename), so a consumer's
  boot namespace must compile unchanged against either coordinate. That
  holds only while the two in-tree adapter namespaces publish the SAME set
  of public vars — the adapter map, the test flush, the SSR seam, and the
  client-root trio (rf2-k5r9t). This reads both surfaces off the CLJS
  analyzer at compile time (the API-manifest probe's `emit-ns-publics`) and
  pins them equal, so a var added to one adapter and not the other reds
  here rather than at a consumer's swap.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require-macros [re-frame.api-manifest.cljs-publics :refer [emit-ns-publics]])
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.reagent]
            [re-frame.adapter.reagent-slim]))

(def ^:private reagent-publics (emit-ns-publics 're-frame.adapter.reagent))
(def ^:private slim-publics    (emit-ns-publics 're-frame.adapter.reagent-slim))

(defn- names [publics] (set (map first publics)))

(deftest full-and-slim-publish-the-same-canonical-surface
  (testing "the two adapter namespaces expose the same public var names"
    (is (seq reagent-publics) "the probe read a non-empty Reagent surface")
    (is (= (names reagent-publics) (names slim-publics))
        (str "full/slim public-var drift — only in full: "
             (pr-str (sort (remove (names slim-publics) (names reagent-publics))))
             "; only in slim: "
             (pr-str (sort (remove (names reagent-publics) (names slim-publics)))))))
  (testing "the client-root trio is on both"
    (doseq [v ["client-root" "render!" "unmount!"]]
      (is (contains? (names reagent-publics) v) (str "re-frame.adapter.reagent/" v))
      (is (contains? (names slim-publics) v) (str "re-frame.adapter.reagent-slim/" v)))))
