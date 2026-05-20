(ns re-frame.teardown-verb-axis-test
  "Pin the rf2-cmabc tear-down verb axis rename + deprecation alias.

   Public API rename (per spec/Conventions.md §Tear-down verb axis):

   - `rf/dispose-adapter!` → `rf/destroy-adapter!` (destroy- cluster)

   The old name is retained for one deprecation cycle as an alias
   pointing at the same underlying Var. Tooling reads `:deprecated`
   metadata to flag stale call sites.

   Carve-out (per Conventions §Tear-down verb axis — Carve-out):
   `rf/unsubscribe` is **not** renamed. The natural target name
   `clear-sub` is already taken by the symmetric inverse of `reg-sub`
   (the registrar decrement — distinct semantics from the cache
   ref-count decrement that `unsubscribe` performs). This test pins
   the carve-out so a future agent does not re-attempt the collision.

   This test pins:

   1. The new name `destroy-adapter!` resolves.
   2. The old name `dispose-adapter!` still resolves (deprecation guarantee).
   3. The alias points at the same underlying implementation.
   4. The deprecated alias carries `:deprecated` metadata.
   5. The canonical name carries no deprecation flag.
   6. The `unsubscribe` carve-out is intact: `unsubscribe` resolves
      and `clear-sub` resolves to a DIFFERENT fn value (registrar
      decrement vs cache decrement).

   Per Spec/Conventions §Tear-down verb axis; per
   migration/from-re-frame-v1/README.md M-53."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]))

(deftest new-name-resolves
  (testing "rf/destroy-adapter! resolves to a Var"
    (is (some? (find-var 're-frame.core/destroy-adapter!))
        "rf/destroy-adapter! must be a public surface (rf2-cmabc M-53)")))

(deftest deprecated-alias-resolves
  (testing "rf/dispose-adapter! still resolves (one-cycle deprecation alias)"
    (is (some? (find-var 're-frame.core/dispose-adapter!))
        "rf/dispose-adapter! retained as deprecated alias for destroy-adapter!")))

(deftest alias-points-at-same-implementation
  (testing "rf/destroy-adapter! and rf/dispose-adapter! point at the same fn value"
    (is (identical? @(find-var 're-frame.core/destroy-adapter!)
                    @(find-var 're-frame.core/dispose-adapter!))
        "the deprecated alias shares the canonical fn value")))

(deftest deprecated-alias-carries-deprecation-metadata
  (testing "rf/dispose-adapter! is marked :deprecated"
    (is (some? (:deprecated (meta (find-var 're-frame.core/dispose-adapter!))))
        "tooling reading :deprecated meta will flag stale call sites")))

(deftest canonical-name-not-marked-deprecated
  (testing "rf/destroy-adapter! is NOT marked :deprecated"
    (is (nil? (:deprecated (meta (find-var 're-frame.core/destroy-adapter!))))
        "the canonical name carries no deprecation flag")))

(deftest unsubscribe-carve-out-preserved
  (testing "rf/unsubscribe resolves to a Var (carve-out: NOT renamed)"
    (is (some? (find-var 're-frame.core/unsubscribe))
        "rf/unsubscribe is retained as the singular un- surface (rf2-cmabc carve-out)"))
  (testing "rf/clear-sub also resolves (the existing registrar decrement)"
    (is (some? (find-var 're-frame.core/clear-sub))
        "rf/clear-sub is the symmetric inverse of reg-sub — registrar decrement"))
  (testing "rf/unsubscribe and rf/clear-sub are DIFFERENT fns (different semantics)"
    (is (not (identical? @(find-var 're-frame.core/unsubscribe)
                         @(find-var 're-frame.core/clear-sub)))
        "unsubscribe decrements the sub CACHE ref-count; clear-sub decrements the REGISTRAR — distinct operations"))
  (testing "rf/unsubscribe carries NO :deprecated meta (it's a carve-out, not deprecated)"
    (is (nil? (:deprecated (meta (find-var 're-frame.core/unsubscribe))))
        "unsubscribe is a load-bearing surface kept as-is, not a deprecated alias")))
