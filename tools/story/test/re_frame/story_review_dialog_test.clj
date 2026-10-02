(ns re-frame.story-review-dialog-test
  "JVM tests for the shared review-then-commit dialog primitive.

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
            [re-frame.story.predicates :as rf.story.predicates]
            [re-frame.story.review-dialog :as rf.story.review-dialog]))

;; ---- parse-variant-id-string ---------------------------------------------

(deftest parse-with-leading-colon
  (testing "input with leading `:` strips it and parses"
    (is (= :foo/bar (rf.story.review-dialog/parse-variant-id-string ":foo/bar")))
    (is (= :plain   (rf.story.review-dialog/parse-variant-id-string ":plain")))))

(deftest parse-without-leading-colon
  (testing "input without leading `:` parses directly"
    (is (= :foo/bar (rf.story.review-dialog/parse-variant-id-string "foo/bar")))
    (is (= :plain   (rf.story.review-dialog/parse-variant-id-string "plain")))))

(deftest parse-nil-for-empty-or-bad-input
  (testing "nil / empty / non-string returns nil"
    (is (nil? (rf.story.review-dialog/parse-variant-id-string nil)))
    (is (nil? (rf.story.review-dialog/parse-variant-id-string "")))
    (is (nil? (rf.story.review-dialog/parse-variant-id-string ":"))
        "bare colon strips to empty → nil")
    (is (nil? (rf.story.review-dialog/parse-variant-id-string "foo/")))
    (is (nil? (rf.story.review-dialog/parse-variant-id-string "/bar")))))

;; ---- default-variant-id-with-prefix --------------------------------------

(deftest default-uses-source-namespace
  (testing "the derived id inherits the source's namespace and is named
            prefix-N"
    (is (= :story.counter/saved-12345
           (rf.story.review-dialog/default-variant-id-with-prefix
             :story.counter/happy-path 12345 "saved")))))

(deftest default-honors-custom-prefix
  (testing "the prefix arg drives the name's leading token"
    (let [k (rf.story.review-dialog/default-variant-id-with-prefix
              :story.counter/x 0 "recorded")]
      (is (= "recorded-0" (name k))))
    (let [k (rf.story.review-dialog/default-variant-id-with-prefix
              :story.counter/x 0 "snapshot")]
      (is (= "snapshot-0" (name k))))))

(deftest default-nil-for-unqualified-source
  (testing "an unqualified or nil source returns nil"
    (is (nil? (rf.story.review-dialog/default-variant-id-with-prefix nil 0 "saved")))
    (is (nil? (rf.story.review-dialog/default-variant-id-with-prefix
                :unqualified 0 "saved")))))

(deftest default-suffix-bounded-by-million
  (testing "the suffix is always in [0, 999999] so it stays a short slug"
    (doseq [now-ms [0 1 1000 1000000 1700000000000 (* 1000000 99999)]]
      (let [k     (rf.story.review-dialog/default-variant-id-with-prefix
                    :story.x/y now-ms "saved")
            n-str (subs (name k) (count "saved-"))
            n     (Long/parseLong n-str)]
        (is (and (>= n 0) (< n 1000000))
            (str "now-ms " now-ms " produced suffix " n))))))

;; ---- dialog state machine ------------------------------------------------

(deftest initial-state-is-idle
  (testing "the idle state map has the expected slots"
    (is (= {:open? false :draft-id nil :source-id nil :context nil}
           rf.story.review-dialog/initial-state))))

(deftest open-flips-open-and-seeds-defaults
  (testing "open builds the opened state with the source + context + default id"
    (is (= {:open?     true
            :source-id :story.x/y
            :context   {:args {:n 1}}
            :draft-id  :story.x/saved-12345}
           (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                :story.x/y
                                {:args {:n 1}}
                                12345
                                "saved")))))

(deftest close-returns-idle
  (testing "close returns the idle state regardless of prior state"
    (let [opened (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                     :story.x/y {:args {:n 1}} 0 "saved")
          closed (rf.story.review-dialog/close opened)]
      (is (= rf.story.review-dialog/initial-state closed)))))

(deftest parse-and-set-parses-on-success
  (testing "parse-and-set-draft-id parses a clean keyword string into a keyword"
    (let [s (-> rf.story.review-dialog/initial-state
                (rf.story.review-dialog/open :story.x/y nil 0 "saved")
                (rf.story.review-dialog/parse-and-set-draft-id ":story.x/edited"))]
      (is (= :story.x/edited (:draft-id s))))))

(deftest parse-and-set-keeps-raw-on-failure
  (testing "parse-and-set-draft-id keeps the raw string on parse failure"
    (let [s (-> rf.story.review-dialog/initial-state
                (rf.story.review-dialog/open :story.x/y nil 0 "saved")
                (rf.story.review-dialog/parse-and-set-draft-id "foo/"))]
      (is (= "foo/" (:draft-id s))
          "the trailing-slash input doesn't parse — raw string preserved"))))

(deftest parse-and-set-handles-empty-string
  (testing "empty-string input leaves the draft-id slot at the empty string"
    (let [s (-> rf.story.review-dialog/initial-state
                (rf.story.review-dialog/open :story.x/y nil 0 "saved")
                (rf.story.review-dialog/parse-and-set-draft-id ""))]
      (is (= "" (:draft-id s))))))

;; ---- indent-after (snippet-format helper) --------------------------------

(deftest indent-after-shape
  (testing "indent-after returns a newline followed by N spaces matching prefix width"
    (is (= "\n" (rf.story.predicates/indent-after "")))
    (is (= "\n " (rf.story.predicates/indent-after "x")))
    (is (= "\n     " (rf.story.predicates/indent-after "12345")))))
