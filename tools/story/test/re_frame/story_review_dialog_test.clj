(ns re-frame.story-review-dialog-test
  "JVM tests for the shared review-then-commit dialog primitive: the
  variant-id string parser the id input pipes through on every keystroke,
  the default-id derivation, and the pure dialog transitions the CLJS
  adapter swaps a Reagent ratom around. The cljs-test arm is
  `story_review_dialog_cljs_test.cljs`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.story.predicates :as rf.story.predicates]
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
  (testing "the suffix is always in [0, 999999] so it stays a short slug"
    (doseq [now-ms [0 1 1000 1000000 1700000000000 (* 1000000 99999)]]
      (let [k     (rf.story.review-dialog/default-variant-id-with-prefix
                    :story.x/y now-ms "saved")
            n-str (subs (name k) (count "saved-"))
            n     (Long/parseLong n-str)]
        (is (and (>= n 0) (< n 1000000))
            (str "now-ms " now-ms " produced suffix " n))))))

;; ---- dialog state machine ------------------------------------------------

(deftest open-flips-open-and-seeds-defaults
  (testing "open builds the opened state with the source, context and default
            id; close returns to the idle state"
    (let [opened (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                              :story.x/y {:args {:n 1}} 12345 "saved")]
      (is (= {:open? true :source-id :story.x/y :context {:args {:n 1}} :draft-id :story.x/saved-12345}
             opened))
      (is (= {:open? false :draft-id nil :source-id nil :context nil}
             rf.story.review-dialog/initial-state
             (rf.story.review-dialog/close opened))))))

(deftest parse-and-set-parses-on-success
  (testing "parse-and-set-draft-id stores the parsed keyword, or the raw string
            when it does not parse"
    (let [opened (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                              :story.x/y nil 0 "saved")]
      (is (= [:story.x/edited "foo/" ""]
             (map #(:draft-id (rf.story.review-dialog/parse-and-set-draft-id opened %))
                  [":story.x/edited" "foo/" ""]))))))

;; ---- indent-after (snippet-format helper) --------------------------------

(deftest indent-after-shape
  (testing "indent-after returns a newline followed by N spaces matching prefix width"
    (is (= "\n" (rf.story.predicates/indent-after "")))
    (is (= "\n " (rf.story.predicates/indent-after "x")))
    (is (= "\n     " (rf.story.predicates/indent-after "12345")))))
