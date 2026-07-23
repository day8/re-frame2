(ns re-frame.freehand.presence-reconcile-cljs-test
  "FH-PRESENCE-002 — the keyed retention state machine, proven pure and on
  both hosts.

  Retention, re-entry, the terminal-timeout ordering and first-appearance
  order are not two implementations that agree; they are ONE. Both execution
  modes lower a `(v/presence …)` to `re-frame.freehand.presence-runtime`, so
  `reconcile` and `claim-identities` are the retention contract, and a fixture
  run against them on the JVM and in ClojureScript is what makes 'identical in
  both modes' a fact about shared code rather than a hope about two.

  These are pure functions: no React, no clock, no host. The clock and the
  real browser are FH-PRESENCE-003's; this row pins the transition algebra
  underneath them."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.presence-runtime :as presence]))

(def presence-002 (conf/fixture :FH-PRESENCE-002))

(deftest fh-presence-002-reconcile-derives-the-retention-plan
  (testing "Per FH-PRESENCE-002: reconcile derives the three-phase plan —
            new keys mount, present keys hold, a departed key is retained
            `:unmounting`, a re-entered key flips back to `:present`, and the
            committed order is frozen against an incoming reorder."
    (is (seq (:reconcile presence-002)) "the reconcile case table loaded")
    (doseq [{:keys [note committed incoming expected]} (:reconcile presence-002)]
      (is (= expected (presence/reconcile committed incoming)) note))))

(deftest fh-presence-002-claim-identities-keeps-one-pair-per-key
  (testing "Per FH-PRESENCE-002: claim-identities reduces the flattened
            children to one pair per key — a key IS a retained identity — with
            the FIRST claimant winning and the duplicates reported for
            diagnosis."
    (is (seq (:claim presence-002)) "the claim case table loaded")
    (doseq [{:keys [note pairs distinct dups]} (:claim presence-002)]
      (let [[got-distinct got-dups] (presence/claim-identities pairs)]
        (is (= distinct got-distinct) (str note " — distinct pairs"))
        (is (= dups got-dups) (str note " — duplicate keys"))))))

(deftest the-reconcile-proof-is-not-vacuous
  (testing "reconcile must actually MOVE phases — a table where every
            expected value equalled its committed input would pass against an
            identity function. So at least one case must change a phase, and
            the three transitions the contract names must each appear."
    (let [cases  (:reconcile presence-002)
          phases (fn [entries] (set (map :phase entries)))
          out    (map (fn [{:keys [committed incoming]}]
                        (presence/reconcile committed incoming))
                      cases)
          seen   (reduce into #{} (map phases out))]
      (is (contains? seen :mounting) "some case derives a :mounting entry")
      (is (contains? seen :present) "some case derives a :present entry")
      (is (contains? seen :unmounting) "some case derives an :unmounting entry")
      (is (some (fn [{:keys [committed incoming]}]
                  (not= committed (presence/reconcile committed incoming)))
                cases)
          "at least one case changes the committed entries — the table is not identity"))))
