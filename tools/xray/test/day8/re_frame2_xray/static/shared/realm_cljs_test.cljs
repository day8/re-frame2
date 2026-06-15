(ns day8.re-frame2-xray.static.shared.realm-cljs-test
  "Pure-helper tests for the static-browse realm dimension (rf2-dfaey7,
  a15n62). The registry-touching fns (`realm-ids` / `registrations-in-realm`
  / `multi-realm?`) are exercised against the live default realm through the
  reset fixture; the pure projections (`realm-of` / `cross-realm-ids`) are
  tested directly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.static.shared.realm :as realm]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest realm-ids-always-includes-default
  (testing "rf2-dfaey7 — realm-ids always carries the default realm and is
            fail-soft on a runtime that can't answer"
    (is (contains? (realm/realm-ids) realm/default-realm-id)
        "the default realm is always present")))

(deftest single-realm-is-the-default-case
  (testing "rf2-dfaey7 — a fresh runtime is single-realm (the common case)"
    (is (false? (realm/multi-realm?))
        "no extra realm installed → single-realm browse")))

(deftest realm-of-blanks-the-default
  (testing "rf2-dfaey7 — absence is the default; the default realm displays
            as nothing"
    (is (nil? (realm/realm-of nil)) "nil ⇒ default ⇒ no label")
    (is (nil? (realm/realm-of realm/default-realm-id))
        "the explicit default-realm id also displays as nothing")
    (is (= :tenant/acme (realm/realm-of :tenant/acme))
        "a non-default realm displays its id")))

(deftest realm-qualified-registrations-single-realm-shape
  (testing "rf2-dfaey7 — single-realm yields one [default reg-map] pair"
    (let [pairs (realm/realm-qualified-registrations :event)]
      (is (= 1 (count pairs)) "one pair in a single-realm app")
      (is (= realm/default-realm-id (ffirst pairs))
          "the one pair is the default realm")
      (is (map? (second (first pairs))) "the pair carries the {id meta} map"))))

(deftest cross-realm-ids-detects-id-conflict
  (testing "rf2-dfaey7 — an id present in >1 realm is a cross-realm conflict"
    (let [pairs [[:rf.realm/default {:shared/id {} :only-default/id {}}]
                 [:tenant/acme      {:shared/id {} :only-acme/id {}}]]]
      (is (= #{:shared/id} (realm/cross-realm-ids pairs))
          "only :shared/id appears in both realms")))
  (testing "rf2-dfaey7 — single realm has no cross-realm conflicts"
    (is (empty? (realm/cross-realm-ids
                  [[:rf.realm/default {:a {} :b {}}]])))))

(deftest realm-badge-renders-nothing-in-single-realm
  (testing "rf2-dfaey7 — the chip is suppressed in a single-realm browse so
            the surface renders byte-identical to pre-rf2-dfaey7"
    (is (nil? (realm/realm-badge :tenant/acme "test-id"))
        "single-realm (no second realm installed) → no chip")
    (is (nil? (realm/realm-badge nil "test-id"))
        "the default realm never carries a chip")))
