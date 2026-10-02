(ns re-frame.story-review-dialog-cljs-test
  "CLJS-side tests for the shared review-then-commit dialog primitive.

  Runs under shadow's `:node-test` build (ns-regexp `cljs-test$`).
  The pure state machine and id parsing are `.cljc` with no reader
  conditional on their path, and the JVM
  `re-frame.story-review-dialog-test` covers them in full; the few pure
  rows here keep them running under CLJS. The rest cover the CLJS-only
  surface: the hiccup renderer the recorder + save-variant flows both
  depend on, and the clipboard shim."
  (:require [cljs.test :refer [async] :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.predicates :as rf.story.predicates]
            [re-frame.story.review-dialog :as rf.story.review-dialog]))

;; ---- default-variant-id-with-prefix --------------------------------------

(deftest default-uses-source-namespace
  (is (= :story.counter/saved-12345
         (rf.story.review-dialog/default-variant-id-with-prefix
           :story.counter/happy-path 12345 "saved"))))

;; ---- dialog state machine ------------------------------------------------

(deftest initial-state-is-idle
  (is (= {:open? false :draft-id nil :source-id nil :context nil}
         rf.story.review-dialog/initial-state)))

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
  (testing "the shared copy helper is callable without a clipboard
            API and resolves an HONEST false outcome (a nil return
            would read as success to every caller)"
    (async done
      (let [p (rf.story.review-dialog/copy-to-clipboard! "anything")]
        (is (instance? js/Promise p) "the shim exposes a completion result")
        (-> p
            (.then (fn [ok?]
                     (is (false? ok?)
                         "no navigator.clipboard on node → not copied")))
            (.finally done))))))

;; ---- indent-after (snippet-format helper) --------------------------------

(deftest indent-after-matches-prefix-width
  (testing "indent-after returns \\n + N spaces equal to the prefix length"
    (is (= "\n" (rf.story.predicates/indent-after "")))
    (is (= "\n          " (rf.story.predicates/indent-after "   :name {")))
    (is (= "\n          " (rf.story.predicates/indent-after "   :args {")))))

;; ---- ARIA: modal a11y posture --------------------------------------------
;;
;; Read the attributes off the element that carries them: a substring probe
;; of the printed tree is satisfied by other attributes (`"dialog"` by the
;; `data-test` value `"test-dialog"`, `"aria-label"` by `:aria-labelledby`).

(defn- attr-maps
  "Every props map in the hiccup `tree`."
  [tree]
  (->> (tree-seq #(or (vector? %) (seq? %)) seq tree)
       (filter #(and (vector? %) (map? (second %))))
       (map second)))

(deftest renderer-stamps-role-dialog-and-aria-modal
  (testing "the rendered modal carries role=dialog + aria-modal=true"
    (let [maps  (attr-maps (rf.story.review-dialog/review-dialog
                             (opened-state)
                             {:title             "Save"
                              :snippet           "(snippet)"
                              :placeholder-id    :story.x/example
                              :placeholder-input ":story.x/sample"
                              :on-edit-id        (fn [_])
                              :on-copy           (fn [])
                              :on-close          (fn [])
                              :data-test-prefix  "test"}))
          panel (first (filter #(= "dialog" (:role %)) maps))]
      (is (some? panel)
          "an element carries role=dialog")
      (is (= "true" (:aria-modal panel))
          "aria-modal flag is stamped on the modal panel")
      (is (= "test-dialog-title" (:aria-labelledby panel))
          "aria-labelledby threads the title id into the modal panel")
      (is (some #(= "test-dialog-title" (:id %)) maps)
          "the title element carries the id aria-labelledby names"))))

(deftest renderer-id-input-carries-aria-label
  (testing "the variant-id input has an accessible name"
    (let [maps  (attr-maps (rf.story.review-dialog/review-dialog
                             (opened-state)
                             {:title             "Save"
                              :snippet           "(snippet)"
                              :placeholder-id    :story.x/example
                              :placeholder-input ":story.x/sample"
                              :on-edit-id        (fn [_])
                              :on-copy           (fn [])
                              :on-close          (fn [])
                              :data-test-prefix  "test"}))
          input (first (filter #(= "test-id-input" (:data-test %)) maps))]
      (is (some? input) "precondition: the id input is rendered")
      (is (not (str/blank? (:aria-label input)))
          "the input carries an aria-label so it's not announced as 'edit, blank'"))))
