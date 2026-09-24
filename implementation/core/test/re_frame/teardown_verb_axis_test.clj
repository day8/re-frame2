(ns re-frame.teardown-verb-axis-test
  "Pin the tear-down verb axis naming.

   Public API (per spec/Conventions.md §Tear-down verb axis):

   - the adapter tear-down is `rf/destroy-adapter!` (destroy- cluster);
     there is no `rf/dispose-adapter!`

   Alpha posture: no back-compat shims, no deprecation aliases. The
   v1→v2 rename is recorded in migration/from-re-frame-v1/README.md
   M-53.

   Carve-out (per Conventions §Tear-down verb axis — Carve-out):
   `rf/unsubscribe` is **not** on the clear- axis. `(rf/clear :sub id)` is
   the symmetric inverse of `reg-sub` (the registrar decrement — distinct
   semantics from the cache ref-count decrement that `unsubscribe`
   performs). This test pins the carve-out so the two are never
   collapsed into one name.

   This test pins:

   1. `destroy-adapter!` resolves.
   2. `dispose-adapter!` does NOT resolve (no alias).
   3. The `unsubscribe` carve-out is intact: `unsubscribe` resolves,
      `rf/clear` resolves to a DIFFERENT fn value (registrar decrement
      vs cache decrement), and there is no `clear-sub`.

   Per Spec/Conventions §Tear-down verb axis; per
   migration/from-re-frame-v1/README.md M-53."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]))

(deftest new-name-resolves
  (testing "rf/destroy-adapter! resolves to a Var"
    (is (some? (find-var 're-frame.core/destroy-adapter!))
        "rf/destroy-adapter! must be a public surface (M-53)")))

(deftest old-name-does-not-resolve
  (testing "rf/dispose-adapter! does not exist (alpha — no back-compat aliases)"
    (is (nil? (find-var 're-frame.core/dispose-adapter!))
        "rf/dispose-adapter! does not exist; use destroy-adapter! per Conventions §Tear-down verb axis")))

(deftest canonical-name-not-marked-deprecated
  (testing "rf/destroy-adapter! is NOT marked :deprecated"
    (is (nil? (:deprecated (meta (find-var 're-frame.core/destroy-adapter!))))
        "the canonical name carries no deprecation flag")))

(deftest unsubscribe-carve-out-preserved
  (testing "rf/unsubscribe resolves to a Var (carve-out: NOT renamed)"
    (is (some? (find-var 're-frame.core/unsubscribe))
        "rf/unsubscribe is the singular un- surface (carve-out)"))
  (testing "rf/clear also resolves (the kind-keyed registrar decrement)"
    (is (some? (find-var 're-frame.core/clear))
        "(rf/clear :sub id) is the symmetric inverse of reg-sub — registrar
         decrement, through the one kind-keyed door; there is no `clear-sub`.")
    (is (nil? (find-var 're-frame.core/clear-sub))
        "there is no rf/clear-sub (no back-compat alias)"))
  (testing "rf/unsubscribe and rf/clear are DIFFERENT fns (different semantics)"
    (is (not (identical? @(find-var 're-frame.core/unsubscribe)
                         @(find-var 're-frame.core/clear)))
        "unsubscribe decrements the sub CACHE ref-count; (clear :sub id)
         decrements the REGISTRAR — distinct operations, which is why
         `clear-sub-cache!` keeps a name of its own"))
  (testing "rf/unsubscribe carries NO :deprecated meta (it's a carve-out, not deprecated)"
    (is (nil? (:deprecated (meta (find-var 're-frame.core/unsubscribe))))
        "unsubscribe is a load-bearing surface kept as-is, not a deprecated alias")))
