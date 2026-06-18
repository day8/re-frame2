(ns re-frame.api-manifest.facade-hygiene-check-test
  "Regression tests for the public-facade manifest-hygiene gate (rf2-gqa7yv —
  the enforcement half of the rf2-ebbpia pre-alpha facade-REMOVAL discipline).

  The pure reconcilers (`superseded-facade-names`, `reconcile`,
  `floor-violation?`) are driven with synthetic inputs so the join contract is
  locked without depending on the live migration-map / manifest shape:

    * a `:rf`-namespaced superseded disposition names a facade var by the
      keyword's NAME; a `:rf.realm/…`-keyed entry names no single var and is
      skipped;
    * a `:facade? true` row whose bare name has a superseded disposition FAILS
      unless its (var, status) pair is allowlisted;
    * an allowlist entry that matches no current violation is reported STALE;
    * a collapsed disposition join trips the non-vacuous floor.

  A live smoke confirms the committed manifest + migration-map reconcile clean
  (the realm-family facade removals are done, so the gate is GREEN)."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.api-manifest.facade-hygiene-check :as fh]))

;; A minimal synthetic disposition map mirroring re-frame.migration's shape:
;; `:rf`-namespaced superseded entries (name a facade var), a `:rf`-namespaced
;; non-superseded sentinel (must be ignored), and an other-namespaced entry
;; (names a conceptual surface, no single var → skipped).
(def ^:private synthetic-migration
  {:rf/install!            {:status :retained-internal}
   :rf/app                 {:status :publicly-replaced}
   :rf/module              {:status :re-expressed}
   ;; non-superseded :rf entry — never contributes a candidate.
   :rf/healthy-export      {:status :live}
   ;; namespaced surface key — no single facade var, must be skipped.
   :rf.realm/frame-address {:status :publicly-replaced}})

(deftest superseded-facade-names-maps-rf-keyed-superseded-only
  (let [m (fh/superseded-facade-names synthetic-migration fh/superseded-statuses)]
    (testing "every superseded :rf-keyed entry contributes its keyword NAME"
      (is (= {"install!" :retained-internal
              "app"      :publicly-replaced
              "module"   :re-expressed}
             m)))
    (testing "a non-superseded :rf entry is excluded"
      (is (not (contains? m "healthy-export"))))
    (testing "a non-:rf-namespaced surface key names no var and is skipped"
      (is (not (contains? m "frame-address"))))))

(def ^:private name->status
  {"install!" :retained-internal
   "app"      :publicly-replaced})

(defn- reconcile-with
  [{:keys [rows allow] :or {allow #{}}}]
  (fh/reconcile {:rows rows :name->status name->status :allow allow}))

(deftest facade-row-with-superseded-disposition-fails
  (let [rows [{:namespace "re-frame.core" :var "install!"
               :tier :advanced :facade? true}
              ;; a non-facade row with the same bare name must NOT fire — the
              ;; retained-internal substrate that correctly LEFT the facade.
              {:namespace "re-frame.realm" :var "install!"
               :tier :implementation :facade? false}
              ;; a facade row with no superseded disposition is clean.
              {:namespace "re-frame.core" :var "make-frame"
               :tier :advanced :facade? true}]
        [problems stale] (reconcile-with {:rows rows})]
    (testing "the :facade? true superseded export is flagged"
      (is (= [{:var "install!" :status :retained-internal
               :namespace "re-frame.core" :tier :advanced}]
             problems)))
    (testing "the :facade? false same-name substrate row does not fire"
      (is (= 1 (count problems))))
    (testing "no stale allowlist entries when the allowlist is empty"
      (is (empty? stale)))))

(deftest allowlisted-violation-is-suppressed
  (let [rows [{:namespace "re-frame.core" :var "install!"
               :tier :advanced :facade? true}]
        [problems stale] (reconcile-with
                           {:rows rows
                            :allow #{["install!" :retained-internal]}})]
    (testing "an allowlisted (var, status) pair suppresses the violation"
      (is (empty? problems)))
    (testing "an allowlist entry that DOES match a present pair is not stale"
      (is (empty? stale)))))

(deftest stale-allowlist-entry-reported
  (let [rows [{:namespace "re-frame.core" :var "make-frame"
               :tier :advanced :facade? true}]
        ;; the allowlist names install!, but no facade row presents that pair.
        [problems stale] (reconcile-with
                           {:rows rows
                            :allow #{["install!" :retained-internal]}})]
    (testing "a cleared transitional exception is flagged stale"
      (is (= [["install!" :retained-internal]] (vec stale))))
    (testing "no live violation in this fixture"
      (is (empty? problems)))))

(deftest floor-trips-on-collapsed-join
  (testing "an empty disposition map trips the non-vacuous floor"
    (is (fh/floor-violation? {})))
  (testing "a populated disposition map does not"
    (is (not (fh/floor-violation? name->status)))))

(deftest live-committed-tree-reconciles-clean
  (testing "the committed manifest + live migration-map carry NO superseded
            :facade? true row (realm-family removals are done)"
    (is (true? (fh/check!)))))
