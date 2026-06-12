(ns re-frame.story.realm-test
  "Pure unit tests for the shared Story realm-stamp util (rf2-c6armm.12).

  `re-frame.story.realm` is the ONE place the EP-0013 absent-key rule (\"carry a
  realm key ONLY for a non-default realm\") lives, so recorder / play-export /
  replay-target make the SAME decision in one place. These pin the predicate +
  the keyed assoc around the two cases the rule turns on: DEFAULT-realm omission
  and NON-default-realm emission. Pure data → data; CLJC (JVM + node-test)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [re-frame.realm :as realm]
            [re-frame.story.realm :as story-realm]))

;; ---- the predicate: non-default-realm ------------------------------------

(deftest non-default-realm-drops-nil-and-default
  (testing "non-default-realm carries NOTHING for nil (unknown / destroyed
            frame) or the default realm — absence IS the default (the
            absent-key rule)"
    (is (nil? (story-realm/non-default-realm nil))
        "a nil realm carries nothing")
    (is (nil? (story-realm/non-default-realm realm/default-realm-id))
        "the default realm carries nothing")
    (is (nil? (story-realm/non-default-realm :rf.realm/default))
        "the literal default id carries nothing"))
  (testing "a constructed (non-default) realm is carried verbatim"
    (is (= :tenant/acme (story-realm/non-default-realm :tenant/acme))
        "a constructed realm id rides through unchanged")))

;; ---- the keyed assoc: assoc-realm (low-level, nil-guard only) -------------

(deftest assoc-realm-is-the-low-level-nil-guard-stamper
  (testing "assoc-realm guards ONLY nil — it assocs any non-nil value verbatim
            (the default reduction is the caller's job, via non-default-realm)"
    (is (= {:frame :v} (story-realm/assoc-realm {:frame :v} nil))
        "a nil value leaves the map untouched")
    (is (= {:frame :v :rf.realm/id :tenant/acme}
           (story-realm/assoc-realm {:frame :v} :tenant/acme))
        "a non-nil value stamps under the default :rf.realm/id key")
    (is (= {:frame :v :realm :tenant/acme}
           (story-realm/assoc-realm {:frame :v} :realm :tenant/acme))
        "a caller-supplied key (the replay-target :realm address key) is honoured")
    ;; The deliberate low-level semantics the recorder's `start` layer relies on:
    ;; a RAW default is assoc'd verbatim here (start-recording! reduces first).
    (is (= {:rf.realm/id :rf.realm/default}
           (story-realm/assoc-realm {} realm/default-realm-id))
        "a raw default is stamped verbatim at the low level (no reduction here)")))

;; ---- the combined form: stamp-realm (reduce + assoc) ----------------------

(deftest stamp-realm-applies-the-absent-key-rule-end-to-end
  (testing "default-realm OMISSION: a nil / default realm carries no key —
            the map round-trips unchanged (zero ceremony)"
    (is (= {:frame :v} (story-realm/stamp-realm {:frame :v} nil))
        "nil realm → no :rf.realm/id key")
    (is (= {:frame :v} (story-realm/stamp-realm {:frame :v} realm/default-realm-id))
        "default realm → no :rf.realm/id key")
    (is (not (contains? (story-realm/stamp-realm {:frame :v} :rf.realm/default)
                        :rf.realm/id))
        "the absent-key invariant: default-realm maps never grow a realm key"))
  (testing "non-default-realm EMISSION: a constructed realm stamps its id"
    (is (= {:frame :v :rf.realm/id :tenant/acme}
           (story-realm/stamp-realm {:frame :v} :tenant/acme))
        "a constructed realm stamps :rf.realm/id (the recording-stamp key)")
    (is (= {:frame :v :realm :tenant/acme}
           (story-realm/stamp-realm {:frame :v} :realm :tenant/acme))
        "the replay-target form stamps under :realm (the address-map key)")))
