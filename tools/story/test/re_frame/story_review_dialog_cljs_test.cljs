(ns re-frame.story-review-dialog-cljs-test
  "CLJS tests for the review-then-commit dialog primitive's CLJS-only
  surface: the hiccup renderer the recorder and save-variant flows share,
  and the clipboard shim. The `.cljc` state machine and id parsing are
  covered on the JVM by `re-frame.story-review-dialog-test`."
  (:require [cljs.test :refer [async] :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.review-dialog :as rf.story.review-dialog]))

;; ---- renderer: closed state ----------------------------------------------

(def ^:private base-opts
  {:title             "Save"
   :snippet           "(snippet)"
   :placeholder-id    :story.x/example
   :placeholder-input ":story.x/sample"
   :on-edit-id        (fn [_])
   :on-copy           (fn [])
   :on-close          (fn [])
   :data-test-prefix  "test"})

(deftest renderer-returns-nil-when-closed
  (is (nil? (rf.story.review-dialog/review-dialog rf.story.review-dialog/initial-state base-opts))))

;; ---- renderer: opened state ----------------------------------------------

(defn- opened-state []
  (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                      :story.x/source
                      {:args {:n 1}}
                      12345
                      "saved"))

(deftest renderer-returns-hiccup-when-open
  (testing "the open renderer emits every data-test slot, the snippet and the title"
    (let [hiccup (rf.story.review-dialog/review-dialog (opened-state) (assoc base-opts :hint "the hint"))
          flat   (str hiccup)]
      (is (vector? hiccup))
      (doseq [s ["test-dialog" "test-id-input" "test-snippet" "test-copy" "test-close"
                 "(snippet)" "Save"]]
        (is (str/includes? flat s) s)))))

(deftest renderer-without-on-discard-omits-discard-button
  (testing "the discard button renders only when :on-discard is given"
    (is (= [false true]
           (map #(str/includes? (str (rf.story.review-dialog/review-dialog (opened-state) %))
                                "test-discard")
                [base-opts (assoc base-opts :on-discard (fn []))])))))

(deftest renderer-uses-placeholder-when-draft-id-nil
  (testing "an unqualified source seeds no draft id, so the input falls back to
            the placeholder id"
    (let [state (rf.story.review-dialog/open rf.story.review-dialog/initial-state
                                             :unqualified-source nil 0 "saved")]
      (is (str/includes? (str (rf.story.review-dialog/review-dialog state base-opts))
                         ":story.x/example")))))

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
  (testing "the modal panel carries role=dialog, aria-modal=true and an
            aria-labelledby naming the title element's id"
    (let [maps  (attr-maps (rf.story.review-dialog/review-dialog (opened-state) base-opts))
          panel (first (filter #(= "dialog" (:role %)) maps))]
      (is (= ["true" "test-dialog-title"] ((juxt :aria-modal :aria-labelledby) panel)))
      (is (some #(= "test-dialog-title" (:id %)) maps)))))

(deftest renderer-id-input-carries-aria-label
  (testing "the variant-id input has an accessible name, not 'edit, blank'"
    (let [input (first (filter #(= "test-id-input" (:data-test %))
                               (attr-maps (rf.story.review-dialog/review-dialog (opened-state) base-opts))))]
      (is (some? input) "precondition: the id input is rendered")
      (is (not (str/blank? (:aria-label input)))))))
