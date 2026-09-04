(ns re-frame.story-review-dialog-cljs-test
  "CLJS-side tests for the shared review-then-commit dialog primitive
  (rf2-7jpky).

  Runs under shadow's `:node-test` build (ns-regexp `cljs-test$`).
  The pure-data corpus is identical to the JVM
  `re-frame.story-review-dialog-test` arm; this file adds the CLJS-
  only hiccup-renderer assertions that the recorder + save-variant
  flows both depend on."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.predicates :as rf.story.predicates]
            [re-frame.story.review-dialog :as rf.story.review-dialog]))

;; ---- parse-variant-id-string ---------------------------------------------

(deftest parse-with-leading-colon
  (is (= :foo/bar (rf.story.review-dialog/parse-variant-id-string ":foo/bar")))
  (is (= :plain   (rf.story.review-dialog/parse-variant-id-string ":plain"))))

(deftest parse-without-leading-colon
  (is (= :foo/bar (rf.story.review-dialog/parse-variant-id-string "foo/bar")))
  (is (= :plain   (rf.story.review-dialog/parse-variant-id-string "plain"))))

(deftest parse-nil-for-empty-or-bad-input
  (is (nil? (rf.story.review-dialog/parse-variant-id-string nil)))
  (is (nil? (rf.story.review-dialog/parse-variant-id-string "")))
  (is (nil? (rf.story.review-dialog/parse-variant-id-string "foo/")))
  (is (nil? (rf.story.review-dialog/parse-variant-id-string "/bar"))))

;; ---- default-variant-id-with-prefix --------------------------------------

(deftest default-uses-source-namespace
  (let [k (rf.story.review-dialog/default-variant-id-with-prefix
            :story.counter/happy-path 12345 "saved")]
    (is (qualified-keyword? k))
    (is (= "story.counter" (namespace k)))
    (is (str/starts-with? (name k) "saved-"))))

(deftest default-honors-custom-prefix
  (is (= "recorded-0"
         (name (rf.story.review-dialog/default-variant-id-with-prefix
                 :story.x/y 0 "recorded"))))
  (is (= "saved-0"
         (name (rf.story.review-dialog/default-variant-id-with-prefix
                 :story.x/y 0 "saved")))))

(deftest default-nil-for-unqualified-source
  (is (nil? (rf.story.review-dialog/default-variant-id-with-prefix nil 0 "saved")))
  (is (nil? (rf.story.review-dialog/default-variant-id-with-prefix
              :unqualified 0 "saved"))))

;; ---- dialog state machine ------------------------------------------------

(deftest initial-state-is-idle
  (is (false? (:open?     rf.story.review-dialog/initial-state)))
  (is (nil?   (:draft-id  rf.story.review-dialog/initial-state)))
  (is (nil?   (:source-id rf.story.review-dialog/initial-state))))

(deftest open-flips-open-and-seeds-defaults
  (let [s (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                              :story.x/y
                              {:args {:n 1}}
                              12345
                              "saved")]
    (is (true? (:open? s)))
    (is (= :story.x/y (:source-id s)))
    (is (= {:args {:n 1}} (:context s)))
    (is (qualified-keyword? (:draft-id s)))))

(deftest close-returns-idle
  (let [opened (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                   :story.x/y {} 0 "saved")]
    (is (= rf.story.review-dialog/initial-state (rf.story.review-dialog/close opened)))))

(deftest parse-and-set-draft-id-parses-on-success
  (let [s (-> rf.story.review-dialog/initial-state
              (rf.story.review-dialog/open :story.x/y nil 0 "saved")
              (rf.story.review-dialog/parse-and-set-draft-id ":story.x/edited"))]
    (is (= :story.x/edited (:draft-id s)))))

(deftest parse-and-set-draft-id-keeps-raw-on-failure
  (let [s (-> rf.story.review-dialog/initial-state
              (rf.story.review-dialog/open :story.x/y nil 0 "saved")
              (rf.story.review-dialog/parse-and-set-draft-id "foo/"))]
    (is (= "foo/" (:draft-id s)))))

;; ---- renderer: closed state ----------------------------------------------

(deftest renderer-returns-nil-when-closed
  (testing "the renderer returns nil for the idle state"
    (is (nil? (rf.story.review-dialog/review-dialog
                rf.story.review-dialog/initial-state
                {:title             "Test"
                 :snippet           "(snippet)"
                 :placeholder-id    :story.x/example
                 :placeholder-input ":story.x/sample"
                 :on-edit-id        (fn [_])
                 :on-copy           (fn [])
                 :on-close          (fn [])
                 :data-test-prefix  "test"})))))

;; ---- renderer: opened state ----------------------------------------------

(defn- opened-state []
  (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                      :story.x/source
                      {:args {:n 1}}
                      12345
                      "saved"))

(deftest renderer-returns-hiccup-when-open
  (testing "the renderer returns a hiccup tree when :open? is true"
    (let [hiccup (rf.story.review-dialog/review-dialog
                   (opened-state)
                   {:title             "Save"
                    :hint              "the hint"
                    :snippet           "(snippet)"
                    :placeholder-id    :story.x/example
                    :placeholder-input ":story.x/sample"
                    :on-edit-id        (fn [_])
                    :on-copy           (fn [])
                    :on-close          (fn [])
                    :data-test-prefix  "test"})
          flat   (str hiccup)]
      (is (vector? hiccup) "the renderer produces a hiccup vector")
      (is (str/includes? flat "test-dialog"))
      (is (str/includes? flat "test-id-input"))
      (is (str/includes? flat "test-snippet"))
      (is (str/includes? flat "test-copy"))
      (is (str/includes? flat "test-close"))
      (is (str/includes? flat "(snippet)")
          "the rendered snippet string appears in the tree")
      (is (str/includes? flat "Save")
          "the title appears in the tree"))))

(deftest renderer-without-on-discard-omits-discard-button
  (testing "no :on-discard → no 'discard' button is rendered"
    (let [flat (str (rf.story.review-dialog/review-dialog
                      (opened-state)
                      {:title             "Save"
                       :snippet           "(snippet)"
                       :placeholder-id    :story.x/example
                       :placeholder-input ":story.x/sample"
                       :on-edit-id        (fn [_])
                       :on-copy           (fn [])
                       :on-close          (fn [])
                       :data-test-prefix  "test"}))]
      (is (not (str/includes? flat "test-discard"))
          "the discard data-test slot is absent"))))

(deftest renderer-with-on-discard-renders-discard-button
  (testing ":on-discard provided → 'discard' button renders"
    (let [flat (str (rf.story.review-dialog/review-dialog
                     (opened-state)
                     {:title             "Save"
                      :snippet           "(snippet)"
                      :placeholder-id    :story.x/example
                      :placeholder-input ":story.x/sample"
                      :on-edit-id        (fn [_])
                      :on-copy           (fn [])
                      :on-discard        (fn [])
                      :on-close          (fn [])
                      :data-test-prefix  "test"}))]
      (is (str/includes? flat "test-discard")))))

(deftest renderer-uses-placeholder-when-draft-id-nil
  (testing "with no draft-id seeded the input's default-value is the placeholder"
    (let [state (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                    :unqualified-source
                                    nil
                                    0
                                    "saved")
          flat  (str (rf.story.review-dialog/review-dialog
                       state
                       {:title             "Save"
                        :snippet           "(snippet)"
                        :placeholder-id    :story.x/example
                        :placeholder-input ":story.x/sample"
                        :on-edit-id        (fn [_])
                        :on-copy           (fn [])
                        :on-close          (fn [])
                        :data-test-prefix  "test"}))]
      ;; unqualified source produces nil draft-id → renderer falls back
      ;; to placeholder-id (`:story.x/example`).
      (is (str/includes? flat ":story.x/example")))))

(deftest copy-to-clipboard!-safe-on-node
  (testing "the shared copy helper is callable + no-ops without a clipboard API"
    (is (nil? (rf.story.review-dialog/copy-to-clipboard! "anything")))))

;; ---- indent-after (snippet-format helper, rf2-zs0w4) ---------------------

(deftest indent-after-matches-prefix-width
  (testing "indent-after returns \\n + N spaces equal to the prefix length"
    (is (= "\n" (rf.story.predicates/indent-after "")))
    (is (= "\n          " (rf.story.predicates/indent-after "   :name {")))
    (is (= "\n          " (rf.story.predicates/indent-after "   :args {")))
    (is (= (rf.story.predicates/indent-after "   :name {")
           (rf.story.predicates/indent-after "   :args {"))
        "both flows' prefixes collapse to the same indent")))

;; ---- ARIA: modal a11y posture (rf2-p1ai7) -------------------------------

(deftest renderer-stamps-role-dialog-and-aria-modal
  (testing "rf2-p1ai7: the rendered modal carries role=dialog + aria-modal=true"
    (let [flat (str (rf.story.review-dialog/review-dialog
                      (opened-state)
                      {:title             "Save"
                       :snippet           "(snippet)"
                       :placeholder-id    :story.x/example
                       :placeholder-input ":story.x/sample"
                       :on-edit-id        (fn [_])
                       :on-copy           (fn [])
                       :on-close          (fn [])
                       :data-test-prefix  "test"}))]
      (is (str/includes? flat "dialog")
          "role=dialog appears in the rendered tree")
      (is (str/includes? flat "aria-modal")
          "aria-modal flag is stamped on the modal panel")
      (is (str/includes? flat "aria-labelledby")
          "aria-labelledby threads the title id into the modal panel")
      (is (str/includes? flat "test-dialog-title")
          "the title's id matches the data-test-prefix derived id"))))

(deftest renderer-id-input-carries-aria-label
  (testing "rf2-u01y5: the variant-id input has an accessible name"
    (let [flat (str (rf.story.review-dialog/review-dialog
                      (opened-state)
                      {:title             "Save"
                       :snippet           "(snippet)"
                       :placeholder-id    :story.x/example
                       :placeholder-input ":story.x/sample"
                       :on-edit-id        (fn [_])
                       :on-copy           (fn [])
                       :on-close          (fn [])
                       :data-test-prefix  "test"}))]
      (is (str/includes? flat "aria-label")
          "the input carries an aria-label so it's not announced as 'edit, blank'"))))
