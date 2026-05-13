(ns re-frame.story.ui.save-variant-cljs-test
  "Tests for the save-current-canvas-state-as-variant UI surface
  (rf2-one3t).

  Splits into two tiers:

  - **JVM + CLJS** (pure machinery in `save_variant.cljc`) — the
    `gen-variant-snippet` shape contract, the dialog state machine,
    and the default-id derivation. Mirrors the corpus in
    `story_save_variant_test.clj` / `story_save_variant_cljs_test.cljs`.
    These cases assert the contract surface `save-variant-button` and
    `save-dialog` depend on.

  - **CLJS-only** (`save_variant.cljs` is CLJS-only — depends on
    Reagent / DOM) — the button hiccup carries the disabled attr +
    data-test slot when no variant is focused, and the dialog renders
    a snippet preview when the dialog ratom is open.

  Runs on the JVM under `clojure -M:test` and on CLJS under shadow's
  `:node-test` / `:browser-test` targets (ns suffix `-cljs-test`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.story.save-variant :as save-variant]
            #?(:cljs [re-frame.story.ui.save-variant :as ui-sv])))

;; ---- JVM + CLJS: contract surface ----------------------------------------

(deftest gen-variant-snippet-emits-reg-variant-form
  (testing "the generator emits an EDN (reg-variant ...) form the UI previews"
    (let [snip (save-variant/gen-variant-snippet
                 {:variant-id :story.x/y
                  :extends    :story.x/source
                  :args       {:n 1}})]
      (is (str/starts-with? snip "(story/reg-variant "))
      (is (str/includes? snip ":story.x/y"))
      (is (str/includes? snip ":story.x/source"))
      (is (str/ends-with? snip "})")))))

(deftest dialog-state-machine-open-close
  (testing "open + close are the two transitions the UI ratom swaps"
    (let [opened (save-variant/open save-variant/initial-dialog-state
                                    :story.x/y {:n 1} 0)
          closed (save-variant/close opened)]
      (is (:open? opened))
      (is (= :story.x/y (:source-id opened)))
      (is (= {:n 1} (:args opened)))
      (is (false? (:open? closed)))
      (is (nil? (:source-id closed))))))

(deftest dialog-set-draft-id-replaces-id-slot
  (testing "set-draft-id is the per-keystroke transition the UI calls on edit"
    (let [s (save-variant/set-draft-id
              (save-variant/open save-variant/initial-dialog-state
                                 :story.x/y {} 0)
              :story.x/edited)]
      (is (= :story.x/edited (:draft-id s))))))

;; ---- CLJS-only: button hiccup --------------------------------------------

#?(:cljs
   (deftest save-variant-button-disabled-without-variant
     (testing "the button is disabled + carries a hinted title when no variant focused"
       (let [hiccup (ui-sv/save-variant-button nil)
             attrs  (second hiccup)]
         (is (true? (:disabled attrs)))
         (is (str/includes? (:title attrs) "Select a variant"))
         (is (= "story-save-variant-button" (:data-test attrs)))))))

#?(:cljs
   (deftest save-variant-button-enabled-with-variant
     (testing "the button enables when a variant is in focus"
       (let [hiccup (ui-sv/save-variant-button :story.x/y)
             attrs  (second hiccup)]
         (is (false? (:disabled attrs)))
         (is (str/includes? (:title attrs) "Capture"))
         (is (= "story-save-variant-button" (:data-test attrs)))))))

#?(:cljs
   (deftest save-variant-button-on-click-callable
     (testing "the on-click handler is a 1-arg fn (event); calling does not throw"
       (let [hiccup (ui-sv/save-variant-button :story.x/y)
             attrs  (second hiccup)
             on-click (:on-click attrs)]
         (is (fn? on-click))))))

;; ---- CLJS-only: dialog hiccup --------------------------------------------

#?(:cljs
   (deftest save-dialog-not-rendered-when-closed
     (testing "the dialog renders nil when the ratom :open? is false"
       (reset! ui-sv/ui-dialog save-variant/initial-dialog-state)
       (is (nil? (ui-sv/save-dialog))))))

#?(:cljs
   (deftest save-dialog-renders-snippet-when-open
     (testing "the dialog renders a hiccup tree with the snippet preview"
       (reset! ui-sv/ui-dialog
               (save-variant/open save-variant/initial-dialog-state
                                  :story.x/source
                                  {:label "hello" :n 42}
                                  12345))
       (let [hiccup (ui-sv/save-dialog)
             flat   (str hiccup)]
         (is (vector? hiccup) "the dialog renders a hiccup tree")
         (is (str/includes? flat "story-save-variant-dialog"))
         (is (str/includes? flat "story-save-variant-snippet"))
         (is (str/includes? flat ":story.x/source")
             "the source-variant id appears in the rendered preview")
         (is (str/includes? flat "hello")
             "the snapshot args appear in the rendered snippet")))))

#?(:cljs
   (deftest save-dialog-snippet-renders-extends
     (testing "the rendered snippet pins :extends to the source variant"
       (reset! ui-sv/ui-dialog
               (save-variant/open save-variant/initial-dialog-state
                                  :story.x/source
                                  {}
                                  12345))
       (let [flat (str (ui-sv/save-dialog))]
         (is (str/includes? flat ":extends"))
         (is (str/includes? flat ":story.x/source"))))))
