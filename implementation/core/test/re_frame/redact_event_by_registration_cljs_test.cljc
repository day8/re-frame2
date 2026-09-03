(ns re-frame.redact-event-by-registration-cljs-test
  "rf2-i5su5y — pins the PAYLOAD-ROOTED addressing contract of
  `re-frame.classification/redact-event-by-registration` (Spec 015
  §Registration-owned transient classification): a `reg-event` `:sensitive`
  path indexes into the event PAYLOAD — the SECOND element of the event
  vector, canonically the arg-map — never the outer event vector. Spec 015's
  worked example is `{:sensitive [[:password]]}` for
  `[:auth/login {:password …}]`; the outer-rooted `[[1 :password]]` spelling
  was documentation drift (it looks up key `1` INSIDE the arg-map and
  silently no-ops).

  Three legs:

    1. `[[:password]]` redacts the payload's `:password` while the event id
       and a non-secret sibling payload field ride through.
    2. `[[]]` (the whole-shape mark) redacts the ENTIRE payload while the
       event id is preserved — index 0 / the event-id is never addressable.
    3. A trailing POSITIONAL arg (outer position 2+) rides RAW — the
       documented positional fail-open (Conventions §Canonical event-vector
       shape: prefer the map payload form for secrets, then classify the
       path).

  Seam-level counterparts (same walker, observed through the egress seams):
  the conformance fixture `data-classification-event-arg-sensitive-path-
  redacts.edn` and `observability_routing_cljs_test` pin leg 1 end-to-end;
  this ns pins all three legs at the unit seam.

  Dual-runtime `*_cljs_test.cljc`: the shadow `:node-test` build
  (`npm run test:cljs`, `cljs-test$` ns-regexp) AND the JVM `clojure -M:test`
  runner both run it. Plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private eid :rf.i5su5y/login)

(deftest payload-field-path-redacts
  (testing "a [[:password]] registration path redacts the payload's :password —
            payload-rooted, not outer-event-rooted"
    (rf.registrar/register! :event eid {:sensitive [[:password]]})
    (let [out (rf.classification/redact-event-by-registration
                [eid {:password "hunter2" :user "ann"}])]
      (is (= eid (first out)) "the event id is untouched")
      (is (= rf.privacy/redacted-sentinel (get-in out [1 :password]))
          "the declared payload path redacts")
      (is (= "ann" (get-in out [1 :user]))
          "an undeclared sibling payload field rides raw"))))

(deftest whole-payload-mark-preserves-event-id
  (testing "the [[]] whole-shape mark redacts the ENTIRE payload while the
            event id is preserved — index 0 is never addressable"
    (rf.registrar/register! :event eid {:sensitive [[]]})
    (let [out (rf.classification/redact-event-by-registration
                [eid {:password "hunter2" :user "ann"}])]
      (is (= [eid rf.privacy/redacted-sentinel] out)
          "the whole payload redacts to the sentinel; the id survives"))))

(deftest trailing-positional-arg-rides-raw
  (testing "outer positions 2+ pass through RAW — the documented positional
            fail-open; only the payload (second element) is path-addressable"
    (rf.registrar/register! :event eid {:sensitive [[:password]]})
    (let [out (rf.classification/redact-event-by-registration
                [eid {:password "hunter2"} "positional-secret"])]
      (is (= rf.privacy/redacted-sentinel (get-in out [1 :password]))
          "the payload path still redacts")
      (is (= "positional-secret" (nth out 2))
          "the trailing positional arg rides raw (fail-open, per Spec 015)"))))
