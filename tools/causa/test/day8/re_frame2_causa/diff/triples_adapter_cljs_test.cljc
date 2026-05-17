(ns day8.re-frame2-causa.diff.triples-adapter-cljs-test
  "Pure-data tests for the annotated-tree → flat-triples adapter
  (rf2-gfxmk Phase 1 of rf2-abts7).

  ## Migration-safety property

  `(triples-adapter/annotated-tree->triples (annotated-tree/diff-tree
  before after))` must equal `(app-db-diff-helpers/diff-paths before
  after)` for the corpus below. The legacy flat-triples consumers
  (pin-store, MCP exporter, show-me-when-this-changed walker) depend
  on the triple shape; the adapter projects the annotated-tree's
  richer shape back to the legacy contract.

  ## Known divergence — `=` vs `identical?` short-circuit

  The legacy `diff-paths` walker short-circuits ONLY on `identical?`;
  two equal-but-separately-allocated vectors at the same key surface
  as a spurious `:modified` triple under the old walker. The new
  annotated-tree walker also short-circuits on `=`, eliminating that
  spurious triple — a behavioural *improvement*, not a regression.

  Real-world callers (every consumer that walks PersistentHashMaps
  produced by `assoc-in`) get the `identical?` short-circuit at every
  level, so the divergence is only visible to callers that
  deliberately construct separately-allocated equals. The corpus
  below uses literal shared references across paired inputs where it
  matters, so the property holds — see `mixed-ops` for the explicit
  shared-reference shape.

  ## Corpus

  Pairs exercise added / removed / modified at root + nested, vector
  / set leaves (the diff-paths first-non-map-bottoms-out contract),
  reserved-key paths, sentinels, whole-replacement, and empty-side
  boundary cases."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.diff.triples-adapter :as adapter]
            [day8.re-frame2-causa.panels.app-db-diff-helpers :as h]))

(defn- via-adapter
  "Compute the legacy triples shape by routing through the annotated
  tree + adapter."
  [before after]
  (adapter/annotated-tree->triples (at/diff-tree before after)))

;; ---- equivalence on a corpus -------------------------------------------

(def ^:private shared-items
  "Shared vector reference so `identical?` short-circuits in both
  walkers — exercises the `:items unchanged` carry-through in the
  `mixed-ops` corpus entry."
  [{:id 7 :qty 1}])

(def ^:private shared-totals {:gross 0})

(def ^:private corpus
  "Each entry is `[label before after]`. The adapter equivalence test
  applies `=` between `diff-paths` and `via-adapter` on each pair."
  [["equal maps"
    {:a 1 :b 2} {:a 1 :b 2}]

   ["empty maps"
    {} {}]

   ["single :added"
    {:a 1} {:a 1 :b 2}]

   ["single :removed"
    {:a 1 :b 2} {:a 1}]

   ["single :modified leaf"
    {:a 1} {:a 2}]

   ["nested :modified"
    {:cart {:items [] :totals shared-totals}}
    {:cart {:items [{:id 7}] :totals shared-totals}}]

   ["mixed ops"
    {:cart {:items shared-items} :user/auth :anon}
    {:cart {:items shared-items :totals {:gross 48}} :flash "Welcome"}]

   ["non-map → map at a key"
    {:a 1} {:a {:b 2}}]

   ["sentinel both-sides"
    {:auth :rf/redacted} {:auth :rf/redacted}]

   ["sentinel modified"
    {:auth :rf/redacted} {:auth "alice@example.com"}]

   ["reserved keys"
    {:rf/machines {:m1 :a} :cart {:items []}}
    {:rf/machines {:m1 :b} :cart {:items [1]}}]

   ["whole replacement"
    {:a 1 :b 2 :c 3} {:a 10 :b 20 :c 30}]

   ["empty before"
    {} {:a 1 :b 2}]

   ["empty after"
    {:a 1 :b 2} {}]

   ["set as a leaf"
    {:tags #{:a :b}} {:tags #{:a :c}}]

   ["nested vector leaf change"
    {:cart {:items [1 2]}} {:cart {:items [1 2 3]}}]])

(defn- semantic-triples
  "Drop spurious `:modified` triples where `=` holds — these are
  emitted by the legacy `diff-paths` because it short-circuits only on
  `identical?` (per the ns docstring's 'Known divergence' note). The
  new walker eliminates them via its `=` short-circuit. The adapter
  test compares the *semantically-equal* projections."
  [triples]
  (vec
    (remove (fn [{:keys [op before after]}]
              (and (= op :modified) (= before after)))
            triples)))

(deftest adapter-matches-diff-paths-on-corpus
  (testing "the adapter's flat-triples projection equals the legacy
            `diff-paths` output modulo the `=` vs `identical?`
            short-circuit divergence documented in the ns docstring"
    (doseq [[label before after] corpus]
      (testing label
        (is (= (semantic-triples (h/diff-paths before after))
               (semantic-triples (via-adapter before after)))
            (str "diff-paths and adapter must agree (semantically) for: "
                 label))))))

;; ---- spot-check the basic triple shapes --------------------------------

(deftest single-added-triple-shape
  (let [triples (via-adapter {} {:a 1})]
    (is (= [{:op :added :path [:a] :before nil :after 1}] triples))))

(deftest single-removed-triple-shape
  (let [triples (via-adapter {:a 1} {})]
    (is (= [{:op :removed :path [:a] :before 1 :after nil}] triples))))

(deftest single-modified-triple-shape
  (let [triples (via-adapter {:a 1} {:a 2})]
    (is (= [{:op :modified :path [:a] :before 1 :after 2}] triples))))

(deftest vector-leaf-collapses-to-modified-at-parent
  (testing "the adapter bottoms out at the first non-map level, matching
            diff-paths' contract. A vector that changed internally
            renders as ONE :modified triple at the parent path, not
            multiple slot-level triples."
    (let [triples (via-adapter {:items [1 2]} {:items [1 2 3]})]
      (is (= 1 (count triples)))
      (is (= :modified (:op (first triples))))
      (is (= [:items] (:path (first triples)))))))

;; ---- sort order matches diff-paths -------------------------------------

(deftest adapter-sorts-by-path-as-pr-str
  (testing "triple ordering is stable across re-renders — sorted by
            path-as-pr-str, same as diff-paths"
    (let [triples (via-adapter {:user/auth :anon
                                :cart {:items [{:id 7 :qty 1}]}}
                               {:cart {:items [{:id 7 :qty 1}]
                                       :totals {:gross 48}}
                                :flash "Welcome"})
          paths   (mapv :path triples)]
      (is (= paths (sort-by pr-str paths))))))
