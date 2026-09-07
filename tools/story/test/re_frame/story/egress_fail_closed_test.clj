(ns re-frame.story.egress-fail-closed-test
  "rf2-kuky.6 — Story's two record-don't-throw redaction catches FAIL CLOSED.

  ## The hazard

  Story projects two author-facing payloads through the framework's
  wire-egress walker before they land in a record a tool can read:

    - `re-frame.story.assertions/redact-at` — the asserted value, keyed on
      the asserted path and the variant frame;
    - `re-frame.story.error/elide-ex-data` — a captured `ex-data` map.

  Both wrap the walk in a catch, because a redaction failure must never
  break an assertion or lose an error record. Both catches USED TO RETURN
  THE RAW VALUE. That was harmless while the walker could not reject its
  opts map at all — the only way in was a genuine walk error.

  rf2-kuky.6 CLOSES the walker's opts map, which turns a stale or
  misspelled policy key at either site into a THROW. A throw that returns
  the raw value converts a spelling mistake into a leak of exactly the
  payload the projection exists to protect — and silently, because the
  catch is there precisely so nothing surfaces.

  ## What is pinned

  That each catch yields the `:rf/redacted` sentinel, never the input.
  The fault is injected at the walker itself rather than by planting a bad
  key, because the KEY is not the point: any throw out of the walk must
  land on the sentinel, and a test keyed to one particular bad key would
  go quietly vacuous the day that key became valid.

  Both tests carry a NEGATIVE CONTROL — the same call with the walker
  behaving normally — so a guard that returned the sentinel unconditionally
  (which would satisfy the fail-closed assertion while destroying every
  ordinary projection) fails here."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.elision :as rf.elision]
            [re-frame.privacy :as rf.privacy]
            [re-frame.story.assertions]
            [re-frame.story.error]))

(def ^:private redact-at #'re-frame.story.assertions/redact-at)
(def ^:private elide-ex-data #'re-frame.story.error/elide-ex-data)

(defn- throwing-walker
  "Stand in for `elide-wire-value` rejecting its opts map, which is what a
  stale key at these sites now produces."
  [_v _opts]
  (throw (ex-info "unrecognised egress opts key(s) [:include-sensitive?]"
                  {:rf.error/id  :rf.error/bad-egress-opts
                   :unknown-keys [:include-sensitive?]})))

(deftest assertion-redaction-fails-closed-on-a-walker-throw
  (testing "rf2-kuky.6 — a rejected opts key at the assertion projection site
            yields the sentinel, NEVER the raw asserted value."
    (with-redefs [rf.elision/elide-wire-value throwing-walker]
      (is (= rf.privacy/redacted-sentinel
             (redact-at :app/main [:auth :password] {:password "s3cret"}))))
    (with-redefs [rf.elision/elide-wire-value throwing-walker]
      (is (= rf.privacy/redacted-sentinel
             (redact-at nil [:auth :password] "s3cret"))
          "and with no frame in hand too — the catch is the same catch")))
  (testing "NEGATIVE CONTROL — with the walker behaving, the projected value
            still comes back. A guard that returned the sentinel
            unconditionally would fail here."
    (with-redefs [rf.elision/elide-wire-value (fn [v _opts] v)]
      (is (= {:password "s3cret"}
             (redact-at :app/main [:auth :password] {:password "s3cret"}))))))

(deftest ex-data-projection-fails-closed-on-a-walker-throw
  (testing "rf2-kuky.6 — a rejected opts key at the error-projection site
            yields the sentinel, NEVER the raw `ex-data`."
    (with-redefs [rf.elision/elide-wire-value throwing-walker]
      (is (= rf.privacy/redacted-sentinel
             (elide-ex-data :app/main {:token "s3cret"})))))
  (testing "NEGATIVE CONTROL — the projected map still comes back when the
            walker behaves."
    (with-redefs [rf.elision/elide-wire-value (fn [v _opts] v)]
      (is (= {:token "s3cret"} (elide-ex-data :app/main {:token "s3cret"})))))
  (testing "the pre-existing frameless / nil-data pass-throughs are UNCHANGED:
            they short-circuit BEFORE the walk, so they were never the
            leak path this closes. `ex-data` with no frame in hand is
            structural framework metadata (`:rf.error/id`, `:where`), not
            app-db-sourced, and walking it frameless would fail closed and
            wrongly redact the whole map."
    (with-redefs [rf.elision/elide-wire-value throwing-walker]
      (is (= {:rf.error/id :rf.error/whatever}
             (elide-ex-data nil {:rf.error/id :rf.error/whatever})))
      (is (nil? (elide-ex-data :app/main nil))))))
