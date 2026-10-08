(ns re-frame.story.ui.save-variant-cljs-test
  "Tests for the save-current-canvas-state-as-variant UI surface.

  Splits into two tiers:

  - **JVM + CLJS** (pure machinery in `save_variant.cljc`) — the dialog
    state's violation / slice stamping and the eight-slice capture report.
    The snippet generator and the trigger are pinned in
    `story_save_variant_test.clj` / `story_save_variant_cljs_test.cljs`.

  - **CLJS-only** (`save_variant.cljs` is CLJS-only — depends on
    Reagent / DOM) — the button's disabled state and the dialog's snippet
    preview, violations hint and slice report.

  Runs on the JVM under `clojure -M:test` and on CLJS under shadow's
  `:node-test` target (ns suffix `-cljs-test`)."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [re-frame.story.save-variant :as rf.story.save-variant]
            #?(:cljs [re-frame.story :as rf.story])
            #?(:cljs [re-frame.story.ui.save-variant :as rf.story.ui.save-variant])))

;; ---- CLJS-only: button hiccup --------------------------------------------

#?(:cljs
   (deftest save-variant-button-disabled-iff-no-variant
     (testing "the button is disabled until a variant is focused, and carries
               its data-test slot either way"
       (is (= [[true "story-save-variant-button"] [false "story-save-variant-button"]]
              (mapv #((juxt :disabled :data-test)
                      (second (rf.story.ui.save-variant/save-variant-button %)))
                    [nil :story.x/y]))))))

;; ---- CLJS-only: dialog hiccup --------------------------------------------

#?(:cljs
   (deftest save-dialog-not-rendered-when-closed
     (testing "the dialog renders nil when the ratom :open? is false"
       (reset! rf.story.ui.save-variant/ui-dialog rf.story.save-variant/initial-dialog-state)
       (is (nil? (rf.story.ui.save-variant/save-dialog))))))

#?(:cljs
   (deftest save-dialog-renders-snippet-when-open
     (testing "the dialog renders a hiccup tree with the snippet preview"
       (reset! rf.story.ui.save-variant/ui-dialog
               (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                                  :story.x/source
                                  {:label "hello" :n 42}
                                  12345))
       (let [flat (str (rf.story.ui.save-variant/save-dialog))]
         (is (str/includes? flat "story-save-variant-dialog"))
         (is (str/includes? flat "story-save-variant-snippet"))
         (is (str/includes? flat ":story.x/source")
             "the source-variant id appears in the rendered preview")
         (is (str/includes? flat ":extends")
             "the snippet pins :extends to the source variant")
         (is (str/includes? flat "hello")
             "the snapshot args appear in the rendered snippet")))))

;; ---- dialog state + save-dialog pre-paste hint ---------------------------

(deftest open-stamps-violations-and-slices-or-defaults-them-to-empty
  (let [vs     [{:key :b :value "oops" :schema :int :explain nil}]
        report (rf.story.save-variant/capture-slices {:n 1} nil {})
        init   rf.story.save-variant/initial-dialog-state]
    (are [s violations slices] (= [violations slices] [(:violations s) (:slices s)])
      ;; the 4-arity defaults both to [], so the dialog's hint paths render nothing
      (rf.story.save-variant/open init :story.x/y {:a 1} 0)
      [] []

      ;; the 5-arity stamps the violations the non-blocking hint renders
      (rf.story.save-variant/open init :story.x/y {:a 1 :b "oops"} 0 vs)
      vs []

      ;; the 6-arity stamps the slice report as well
      (rf.story.save-variant/open init :story.x/y {:n 1} 0 [] report)
      [] report)))

#?(:cljs
   (deftest save-dialog-renders-no-violations-hint-when-empty
     (testing "a conforming snapshot renders the snippet without the
               violations hint"
       (reset! rf.story.ui.save-variant/ui-dialog
               (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                                  :story.x/source
                                  {:label "hi"}
                                  12345
                                  []))
       (is (not (str/includes? (str (rf.story.ui.save-variant/save-dialog))
                               "story-save-variant-violations-hint"))))))

#?(:cljs
   (deftest save-dialog-renders-violations-hint-when-non-empty
     (testing "a snapshot that violates the schema renders a non-blocking hint
               listing the offending keys; the snippet still renders with
               the violating args as captured"
       (reset! rf.story.ui.save-variant/ui-dialog
               (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                                  :story.x/source
                                  {:label "hi" :count "not-an-int"}
                                  12345
                                  [{:key :count :value "not-an-int"
                                    :schema :int :explain nil}]))
       (let [flat (str (rf.story.ui.save-variant/save-dialog))]
         (is (str/includes? flat "story-save-variant-violations-hint")
             "the hint container is present")
         (is (str/includes? flat "story-save-variant-violation-row")
             "the violation list renders rows")
         (is (str/includes? flat ":count")
             "the offending key name appears in the hint")
         (is (str/includes? flat "story-save-variant-snippet")
             "the snippet still renders — the hint is non-blocking")))))

;; ---- eight-slice capture model -----------------------------------------

(deftest capture-slices-args-is-projectable
  (testing "args (+ transient-controls) are the projectable pair; args
            carries the live snapshot"
    (let [by-slice (into {} (map (juxt :slice identity))
                         (rf.story.save-variant/capture-slices {:n 7} nil {}))]
      (is (= [:projectable {:n 7}] ((juxt :status :value) (:args by-slice))))
      (is (= :projectable (-> by-slice :transient-controls :status))))))

(deftest capture-slices-unwired-slices-warn-not-fabricate
  (testing "with a bare source body, the not-yet-wired slices are :not-wired
            — captured nothing, honest warning, never a fabricated value"
    (let [report   (rf.story.save-variant/capture-slices {:n 1} nil {:viewport :tablet})
          by-slice (into {} (map (juxt :slice identity)) report)]
      (doseq [s [:sub-overrides :db-seed :route :network :fx-overrides :viewport]]
        (is (= :not-wired (-> by-slice s :status))
            (str s " is honestly not-wired with no declared source value"))
        (is (string? (-> by-slice s :note)) (str s " carries an honest note")))
      (is (not-any? #(str/includes? (:note %) "rf2-") report)
          "no note cites a bead id")
      (is (str/includes? (-> by-slice :viewport :note) ":tablet")
          "viewport note names the live chrome-wide selection it is NOT projecting"))))

(deftest capture-slices-declared-source-slots-are-captured-as-declared
  (testing "when the source variant body declares the unwired slices, they are
            captured-as-declared (carried forward via :extends), warned, honest"
    (let [body     {:sub-overrides {[:s] :v}
                    :network       {[:get "/u"] {:reply {}}}
                    :fx-overrides  {:my/fx 1}
                    :db-seed       {:count 1}
                    :setup         [[:e]]
                    :viewport      :tablet}
          report   (rf.story.save-variant/capture-slices {:n 1} body {})
          by-slice (into {} (map (juxt :slice identity)) report)]
      (doseq [[s v] {:sub-overrides {[:s] :v}
                     :network       {[:get "/u"] {:reply {}}}
                     :fx-overrides  {:my/fx 1}
                     :db-seed       {:count 1}
                     :viewport      :tablet}]
        (is (= :captured-as-declared (-> by-slice s :status))
            (str s " carries the declared source value forward"))
        (is (= v (-> by-slice s :value)) (str s " value is the declared slot")))
      (is (str/includes? (-> by-slice :db-seed :note) ":setup events re-run")
          "a declared :setup is reported beside the seed, not as the seed")
      (is (not-any? #(str/includes? (:note %) "rf2-") report)
          "no note cites a bead id")
      (is (= :not-wired (-> by-slice :route :status))
          "route has no declared source slot — not-wired"))))

(deftest capture-slices-empty-setup-declares-no-events-to-re-run
  (let [db-seed (first (filter #(= :db-seed (:slice %))
                               (rf.story.save-variant/capture-slices {:n 1} {:setup []} {})))]
    (is (not (str/includes? (:note db-seed) ":setup")))))

(deftest slice-warnings-filters-projectable
  (testing "slice-warnings keeps exactly the rows the user must see — every
            slice that is NOT a clean live projection, in slice-order"
    (is (= (remove #{:args :transient-controls} rf.story.save-variant/slice-order)
           (map :slice (rf.story.save-variant/slice-warnings
                         (rf.story.save-variant/capture-slices {:n 1} nil {})))))))

#?(:cljs
   (deftest slice-report-renders-warnings-when-present
     (testing "the slice-report component renders a row per
               non-projectable slice with its status + honest note"
       (let [report (rf.story.save-variant/capture-slices {:n 1} nil {:viewport :tablet})
             flat   (str (rf.story.ui.save-variant/slice-report report))]
         (is (str/includes? flat "story-save-variant-slice-report")
             "the report container is present")
         (is (str/includes? flat "story-save-variant-slice-row")
             "individual slice rows render")
         (is (str/includes? flat "not yet projectable")
             "not-wired slices carry the 'not yet projectable' label")
         (is (str/includes? flat "FORK")
             "the viewport fork is surfaced to the user")))))

#?(:cljs
   (deftest slice-report-nil-when-all-projectable
     (testing "when every slice projects cleanly there is
               nothing to warn about and the report renders nil"
       (let [all-clean [{:slice :args :status :projectable}
                        {:slice :transient-controls :status :projectable}]]
         (is (nil? (rf.story.ui.save-variant/slice-report all-clean)))))))

#?(:cljs
   (deftest save-dialog-renders-slice-report-when-open
     (testing "the open dialog renders the slice report so the save is honest
               about what it captures"
       (reset! rf.story.ui.save-variant/ui-dialog
               (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                                  :story.x/source
                                  {:n 1}
                                  12345
                                  []
                                  (rf.story.save-variant/capture-slices {:n 1} nil {})))
       (is (str/includes? (str (rf.story.ui.save-variant/save-dialog))
                          "story-save-variant-slice-report")))))

#?(:cljs
   (deftest save-dialog-snippet-carries-the-source-compose
     (testing "the dialog prints the saved body, so a source's :compose ids
               ride into the snippet the user pastes"
       (rf.story/clear-all!)
       (try
         (rf.story/reg-fragment :fragment.svui/cart {:db-seed {[:count] 7}})
         (rf.story/reg-variant :story.svui/source {:args {:n 1} :compose [:fragment.svui/cart]})
         (reset! rf.story.ui.save-variant/ui-dialog
                 (rf.story.save-variant/open rf.story.save-variant/initial-dialog-state
                                             :story.svui/source {:n 1} 12345))
         (let [flat (str (rf.story.ui.save-variant/save-dialog))]
           (is (str/includes? flat ":compose [:fragment.svui/cart]")))
         (finally
           (reset! rf.story.ui.save-variant/ui-dialog rf.story.save-variant/initial-dialog-state)
           (rf.story/clear-all!))))))
