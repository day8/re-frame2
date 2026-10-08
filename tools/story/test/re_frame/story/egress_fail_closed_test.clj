(ns re-frame.story.egress-fail-closed-test
  "Story's two record-don't-throw redaction catches FAIL CLOSED: any throw
  out of the wire-egress walk yields the `:rf/redacted` sentinel, never the
  raw value. The fault is injected at the walker, so the test does not go
  vacuous when one particular bad opts key becomes valid; each test also
  has a negative control, so a guard that always returned the sentinel fails."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.elision :as rf.elision]
            [re-frame.privacy :as rf.privacy]
            [re-frame.story.assertions]
            [re-frame.story.error]))

(def ^:private redact-at #'re-frame.story.assertions/redact-at)
(def ^:private elide-ex-data #'re-frame.story.error/elide-ex-data)

(defn- throwing-walker [_v _opts]
  (throw (ex-info "unrecognised egress opts key(s) [:include-sensitive?]"
                  {:rf.error/id  :rf.error/bad-egress-opts
                   :unknown-keys [:include-sensitive?]})))

(deftest assertion-redaction-fails-closed-on-a-walker-throw
  (testing "a walker throw at the assertion projection site yields the
            sentinel, NEVER the raw asserted value"
    (with-redefs [rf.elision/elide-wire-value throwing-walker]
      (is (= rf.privacy/redacted-sentinel
             (redact-at :app/main [:auth :password] {:password "s3cret"})))))
  (testing "NEGATIVE CONTROL — with the walker behaving, the projected value comes back"
    (with-redefs [rf.elision/elide-wire-value (fn [v _opts] v)]
      (is (= {:password "s3cret"}
             (redact-at :app/main [:auth :password] {:password "s3cret"}))))))

(deftest ex-data-projection-fails-closed-on-a-walker-throw
  (testing "a walker throw at the error-projection site yields the sentinel,
            NEVER the raw `ex-data`"
    (with-redefs [rf.elision/elide-wire-value throwing-walker]
      (is (= rf.privacy/redacted-sentinel
             (elide-ex-data :app/main {:token "s3cret"})))))
  (testing "NEGATIVE CONTROL — the projected map comes back when the walker behaves"
    (with-redefs [rf.elision/elide-wire-value (fn [v _opts] v)]
      (is (= {:token "s3cret"} (elide-ex-data :app/main {:token "s3cret"})))))
  (testing "frameless and nil `ex-data` pass through before the walk: frameless
            ex-data is structural framework metadata, and a frameless walk
            would fail closed and redact the whole map"
    (with-redefs [rf.elision/elide-wire-value throwing-walker]
      (is (= {:rf.error/id :rf.error/whatever}
             (elide-ex-data nil {:rf.error/id :rf.error/whatever})))
      (is (nil? (elide-ex-data :app/main nil))))))
