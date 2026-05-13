(ns re-frame.story-review-dialog-test
  "JVM tests for the shared review-then-commit dialog primitive
  (rf2-7jpky).

  Pure-data coverage: the dialog state machine (`initial-state` /
  `open` / `close` / `set-draft-id` / `parse-and-set-draft-id`), the
  default-id derivation (`default-variant-id-with-prefix`), and the
  variant-id string parser (`parse-variant-id-string`). Mirrors the
  cljs-test arm in `story_review_dialog_cljs_test.cljs`.

  ## Coverage layers

  - `parse-variant-id-string` — best-effort string → keyword parser
    the UI's id-input pipes through on every keystroke. Handles
    leading `:` and embedded `/`; returns nil on parse failure so
    callers stash the raw string.
  - `default-variant-id-with-prefix` — keyword derivation from a
    source variant id + wall-clock millis + a per-flow prefix
    (`\"recorded\"` for the recorder; `\"saved\"` for save-variant).
  - Pure transitions (`open` / `close` / `set-draft-id` /
    `parse-and-set-draft-id`) — JVM-testable in isolation; the CLJS
    adapter swaps a Reagent ratom around them."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.review-dialog :as review-dialog]))

;; ---- parse-variant-id-string ---------------------------------------------

(deftest parse-with-leading-colon
  (testing "input with leading `:` strips it and parses"
    (is (= :foo/bar (review-dialog/parse-variant-id-string ":foo/bar")))
    (is (= :plain   (review-dialog/parse-variant-id-string ":plain")))))

(deftest parse-without-leading-colon
  (testing "input without leading `:` parses directly"
    (is (= :foo/bar (review-dialog/parse-variant-id-string "foo/bar")))
    (is (= :plain   (review-dialog/parse-variant-id-string "plain")))))

(deftest parse-qualified-keyword
  (testing "qualified id parses into a qualified keyword"
    (let [k (review-dialog/parse-variant-id-string ":story.counter/saved-1")]
      (is (qualified-keyword? k))
      (is (= "story.counter" (namespace k)))
      (is (= "saved-1"       (name k))))))

(deftest parse-nil-for-empty-or-bad-input
  (testing "nil / empty / non-string returns nil"
    (is (nil? (review-dialog/parse-variant-id-string nil)))
    (is (nil? (review-dialog/parse-variant-id-string "")))
    (is (nil? (review-dialog/parse-variant-id-string ":"))
        "bare colon strips to empty → nil")
    (is (nil? (review-dialog/parse-variant-id-string "foo/")))
    (is (nil? (review-dialog/parse-variant-id-string "/bar")))))

(deftest parse-handles-keywords-only-strings
  (testing "an input that is a printed keyword form (with leading colon) round-trips"
    (is (= :a (review-dialog/parse-variant-id-string ":a")))
    (is (= :ns/name
           (review-dialog/parse-variant-id-string
             (pr-str :ns/name))))))

;; ---- default-variant-id-with-prefix --------------------------------------

(deftest default-uses-source-namespace
  (testing "the derived id inherits the source's namespace + prefix-N suffix"
    (let [k (review-dialog/default-variant-id-with-prefix
              :story.counter/happy-path 12345 "saved")]
      (is (qualified-keyword? k))
      (is (= "story.counter" (namespace k)))
      (is (re-matches #"saved-\d+" (name k))))))

(deftest default-honors-custom-prefix
  (testing "the prefix arg drives the name's leading token"
    (let [k (review-dialog/default-variant-id-with-prefix
              :story.counter/x 0 "recorded")]
      (is (= "recorded-0" (name k))))
    (let [k (review-dialog/default-variant-id-with-prefix
              :story.counter/x 0 "snapshot")]
      (is (= "snapshot-0" (name k))))))

(deftest default-nil-for-unqualified-source
  (testing "an unqualified or nil source returns nil"
    (is (nil? (review-dialog/default-variant-id-with-prefix nil 0 "saved")))
    (is (nil? (review-dialog/default-variant-id-with-prefix
                :unqualified 0 "saved")))))

(deftest default-suffix-bounded-by-million
  (testing "the suffix is always in [0, 999999] so it stays a short slug"
    (doseq [now-ms [0 1 1000 1000000 1700000000000 (* 1000000 99999)]]
      (let [k     (review-dialog/default-variant-id-with-prefix
                    :story.x/y now-ms "saved")
            n-str (subs (name k) (count "saved-"))
            n     (Long/parseLong n-str)]
        (is (and (>= n 0) (< n 1000000))
            (str "now-ms " now-ms " produced suffix " n))))))

;; ---- dialog state machine ------------------------------------------------

(deftest initial-state-is-idle
  (testing "the idle state map has the expected slots"
    (is (false? (:open?     review-dialog/initial-state)))
    (is (nil?   (:draft-id  review-dialog/initial-state)))
    (is (nil?   (:source-id review-dialog/initial-state)))
    (is (nil?   (:context   review-dialog/initial-state)))))

(deftest open-flips-open-and-seeds-defaults
  (testing "open builds the opened state with the source + context + default id"
    (let [s (review-dialog/open review-dialog/initial-state
                                :story.x/y
                                {:args {:n 1}}
                                12345
                                "saved")]
      (is (true? (:open? s)))
      (is (= :story.x/y (:source-id s)))
      (is (= {:args {:n 1}} (:context s)))
      (is (qualified-keyword? (:draft-id s)))
      (is (= "story.x" (namespace (:draft-id s)))))))

(deftest open-with-unqualified-source-still-opens
  (testing "an unqualified source-id leaves :draft-id nil but the dialog opens"
    (let [s (review-dialog/open review-dialog/initial-state
                                :unqualified
                                nil
                                0
                                "saved")]
      (is (true? (:open? s)))
      (is (nil? (:draft-id s)))
      (is (= :unqualified (:source-id s))))))

(deftest close-returns-idle
  (testing "close returns the idle state regardless of prior state"
    (let [opened (review-dialog/open review-dialog/initial-state
                                     :story.x/y {:args {:n 1}} 0 "saved")
          closed (review-dialog/close opened)]
      (is (= review-dialog/initial-state closed)))))

(deftest set-draft-id-replaces-keyword
  (testing "set-draft-id stores a keyword as-is"
    (let [s (-> review-dialog/initial-state
                (review-dialog/open :story.x/y nil 0 "saved")
                (review-dialog/set-draft-id :story.x/edited))]
      (is (= :story.x/edited (:draft-id s))))))

(deftest set-draft-id-stores-raw-string
  (testing "set-draft-id stores a raw string when caller passes one"
    (let [s (-> review-dialog/initial-state
                (review-dialog/open :story.x/y nil 0 "saved")
                (review-dialog/set-draft-id "partial-input"))]
      (is (= "partial-input" (:draft-id s))))))

(deftest parse-and-set-parses-on-success
  (testing "parse-and-set-draft-id parses a clean keyword string into a keyword"
    (let [s (-> review-dialog/initial-state
                (review-dialog/open :story.x/y nil 0 "saved")
                (review-dialog/parse-and-set-draft-id ":story.x/edited"))]
      (is (= :story.x/edited (:draft-id s))))))

(deftest parse-and-set-keeps-raw-on-failure
  (testing "parse-and-set-draft-id keeps the raw string on parse failure"
    (let [s (-> review-dialog/initial-state
                (review-dialog/open :story.x/y nil 0 "saved")
                (review-dialog/parse-and-set-draft-id "foo/"))]
      (is (= "foo/" (:draft-id s))
          "the trailing-slash input doesn't parse — raw string preserved"))))

(deftest parse-and-set-handles-empty-string
  (testing "empty-string input leaves the draft-id slot at the empty string"
    (let [s (-> review-dialog/initial-state
                (review-dialog/open :story.x/y nil 0 "saved")
                (review-dialog/parse-and-set-draft-id ""))]
      (is (= "" (:draft-id s))))))

;; ---- composition ---------------------------------------------------------

(deftest open-then-edit-then-close-cycle
  (testing "the full open → edit → close cycle returns the dialog to idle"
    (let [s0 review-dialog/initial-state
          s1 (review-dialog/open s0 :story.x/y {:args {:n 1}} 1000 "saved")
          s2 (review-dialog/parse-and-set-draft-id s1 ":story.x/edited")
          s3 (review-dialog/parse-and-set-draft-id s2 ":story.x/edited-again")
          s4 (review-dialog/close s3)]
      (is (false? (:open? s0)))
      (is (true?  (:open? s1)))
      (is (= :story.x/edited       (:draft-id s2)))
      (is (= :story.x/edited-again (:draft-id s3)))
      (is (= review-dialog/initial-state s4)))))
