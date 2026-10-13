(ns re-frame.story-review-dialog-test
  "JVM tests for the shared review-then-commit dialog primitive: the
  variant-id string parser the id input pipes through on every keystroke,
  the default-id derivation, and the pure dialog transitions the CLJS
  adapter swaps a Reagent ratom around. The cljs-test arm is
  `story_review_dialog_cljs_test.cljs`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.review-dialog :as rf.story.review-dialog]))

;; ---- parse-variant-id-string ---------------------------------------------

(deftest parse-with-leading-colon
  (testing "a leading `:` is optional; nil, empty, bare-colon and half-qualified
            input parse to nil so the caller keeps the raw string"
    (is (= [:foo/bar :plain :foo/bar :plain nil nil nil nil nil]
           (map rf.story.review-dialog/parse-variant-id-string
                [":foo/bar" ":plain" "foo/bar" "plain" nil "" ":" "foo/" "/bar"])))))

;; ---- default-variant-id-with-prefix --------------------------------------

(deftest default-honors-custom-prefix
  (testing "the prefix drives the name's leading token; an unqualified or nil
            source has no default"
    (is (= ["recorded-0" "snapshot-0"]
           (map #(name (rf.story.review-dialog/default-variant-id-with-prefix :story.counter/x 0 %))
                ["recorded" "snapshot"])))
    (is (= [nil nil]
           (map #(rf.story.review-dialog/default-variant-id-with-prefix % 0 "saved")
                [nil :unqualified])))))

(deftest default-suffix-bounded-by-million
  (testing "the suffix is now-ms mod 1000000, so it stays a short slug"
    (is (= [:story.x/saved-0 :story.x/saved-123456]
           (map #(rf.story.review-dialog/default-variant-id-with-prefix :story.x/y % "saved")
                [1000000 1700000123456])))))

;; ---- dialog state machine ------------------------------------------------

(deftest open-flips-open-and-seeds-defaults
  (testing "open builds the opened state with the source, context and default
            id; close returns to the idle state"
    (let [opened (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                              :story.x/y {:args {:n 1}} 12345 "saved")]
      (is (= {:open? true :source-id :story.x/y :context {:args {:n 1}} :draft-id :story.x/saved-12345}
             opened))
      (is (= rf.story.review-dialog/initial-state (rf.story.review-dialog/close opened))))))

(deftest parse-and-set-parses-on-success
  (testing "parse-and-set-draft-id stores the parsed keyword, or the raw string
            when it does not parse"
    (let [opened (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                              :story.x/y nil 0 "saved")]
      (is (= [:story.x/edited "foo/" ""]
             (map #(:draft-id (rf.story.review-dialog/parse-and-set-draft-id opened %))
                  [":story.x/edited" "foo/" ""]))))))
