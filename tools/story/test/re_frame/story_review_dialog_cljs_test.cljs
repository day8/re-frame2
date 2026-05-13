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
            [re-frame.story.review-dialog :as review-dialog]))

;; ---- parse-variant-id-string ---------------------------------------------

(deftest parse-with-leading-colon
  (is (= :foo/bar (review-dialog/parse-variant-id-string ":foo/bar")))
  (is (= :plain   (review-dialog/parse-variant-id-string ":plain"))))

(deftest parse-without-leading-colon
  (is (= :foo/bar (review-dialog/parse-variant-id-string "foo/bar")))
  (is (= :plain   (review-dialog/parse-variant-id-string "plain"))))

(deftest parse-nil-for-empty-or-bad-input
  (is (nil? (review-dialog/parse-variant-id-string nil)))
  (is (nil? (review-dialog/parse-variant-id-string "")))
  (is (nil? (review-dialog/parse-variant-id-string "foo/")))
  (is (nil? (review-dialog/parse-variant-id-string "/bar"))))

;; ---- default-variant-id-with-prefix --------------------------------------

(deftest default-uses-source-namespace
  (let [k (review-dialog/default-variant-id-with-prefix
            :story.counter/happy-path 12345 "saved")]
    (is (qualified-keyword? k))
    (is (= "story.counter" (namespace k)))
    (is (str/starts-with? (name k) "saved-"))))

(deftest default-honors-custom-prefix
  (is (= "recorded-0"
         (name (review-dialog/default-variant-id-with-prefix
                 :story.x/y 0 "recorded"))))
  (is (= "saved-0"
         (name (review-dialog/default-variant-id-with-prefix
                 :story.x/y 0 "saved")))))

(deftest default-nil-for-unqualified-source
  (is (nil? (review-dialog/default-variant-id-with-prefix nil 0 "saved")))
  (is (nil? (review-dialog/default-variant-id-with-prefix
              :unqualified 0 "saved"))))

;; ---- dialog state machine ------------------------------------------------

(deftest initial-state-is-idle
  (is (false? (:open?     review-dialog/initial-state)))
  (is (nil?   (:draft-id  review-dialog/initial-state)))
  (is (nil?   (:source-id review-dialog/initial-state))))

(deftest open-flips-open-and-seeds-defaults
  (let [s (review-dialog/open review-dialog/initial-state
                              :story.x/y
                              {:args {:n 1}}
                              12345
                              "saved")]
    (is (true? (:open? s)))
    (is (= :story.x/y (:source-id s)))
    (is (= {:args {:n 1}} (:context s)))
    (is (qualified-keyword? (:draft-id s)))))

(deftest close-returns-idle
  (let [opened (review-dialog/open review-dialog/initial-state
                                   :story.x/y {} 0 "saved")]
    (is (= review-dialog/initial-state (review-dialog/close opened)))))

(deftest parse-and-set-draft-id-parses-on-success
  (let [s (-> review-dialog/initial-state
              (review-dialog/open :story.x/y nil 0 "saved")
              (review-dialog/parse-and-set-draft-id ":story.x/edited"))]
    (is (= :story.x/edited (:draft-id s)))))

(deftest parse-and-set-draft-id-keeps-raw-on-failure
  (let [s (-> review-dialog/initial-state
              (review-dialog/open :story.x/y nil 0 "saved")
              (review-dialog/parse-and-set-draft-id "foo/"))]
    (is (= "foo/" (:draft-id s)))))

;; ---- renderer: closed state ----------------------------------------------

(deftest renderer-returns-nil-when-closed
  (testing "the renderer returns nil for the idle state"
    (is (nil? (review-dialog/review-dialog
                review-dialog/initial-state
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
  (review-dialog/open review-dialog/initial-state
                      :story.x/source
                      {:args {:n 1}}
                      12345
                      "saved"))

(deftest renderer-returns-hiccup-when-open
  (testing "the renderer returns a hiccup tree when :open? is true"
    (let [hiccup (review-dialog/review-dialog
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
    (let [flat (str (review-dialog/review-dialog
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
    (let [flat (str (review-dialog/review-dialog
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
    (let [state (review-dialog/open review-dialog/initial-state
                                    :unqualified-source
                                    nil
                                    0
                                    "saved")
          flat  (str (review-dialog/review-dialog
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
    (is (nil? (review-dialog/copy-to-clipboard! "anything")))))
