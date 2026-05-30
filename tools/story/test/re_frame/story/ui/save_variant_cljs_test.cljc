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

;; ---- rf2-lancu: snapshot-violations + save-dialog pre-paste hint --------

(deftest snapshot-violations-soft-passes-when-no-validator
  (testing "rf2-lancu — snapshot-violations is a thin pass-through to
            schema-validation/args-violations; soft-passes per Spec 010
            when no validator / no schema (returns empty vec)"
    (is (= [] (save-variant/snapshot-violations {:a 1} nil nil)))
    (is (= [] (save-variant/snapshot-violations
                {:a 1}
                [:map [:a :int]]
                nil))
        "no validator-fns → soft-pass per Spec 010")
    (is (= [] (save-variant/snapshot-violations
                {:a 1}
                [:map [:a :int]]
                {:validate (fn [_ _] true)}))
        "validator that always passes → no violations")))

(deftest snapshot-violations-reports-non-conforming-keys
  (testing "rf2-lancu — when args break the schema the violation list
            names the offending keys + their values so the save dialog
            can render the 'paste at your own risk' hint pre-paste"
    (let [violations (save-variant/snapshot-violations
                       {:a 1 :b "oops"}
                       [:map [:a :int] [:b :int]]
                       {:validate (fn [s v]
                                    ;; trivial int-only validator for the test
                                    (if (= :int s) (int? v) true))})]
      (is (= 1 (count violations)))
      (is (= :b (-> violations first :key)))
      (is (= "oops" (-> violations first :value))))))

(deftest open-stamps-violations-on-dialog-state
  (testing "rf2-lancu — save-variant/open's 5-arity stamps the violations
            vector on the dialog state so the save dialog can render the
            non-blocking hint above the snippet"
    (let [vs [{:key :b :value "oops" :schema :int :explain nil}]
          s  (save-variant/open save-variant/initial-dialog-state
                                :story.x/y {:a 1 :b "oops"} 0 vs)]
      (is (true? (:open? s)))
      (is (= vs (:violations s))
          "violations ride the dialog state under :violations"))))

(deftest open-3-arity-defaults-violations-to-empty-vec
  (testing "rf2-lancu — back-compat: the legacy 3-arity (without
            violations) stamps an empty vec so the dialog hint path
            renders nothing"
    (let [s (save-variant/open save-variant/initial-dialog-state
                               :story.x/y {:a 1} 0)]
      (is (= [] (:violations s))))))

#?(:cljs
   (deftest save-dialog-renders-no-violations-hint-when-empty
     (testing "rf2-lancu — when the snapshot conforms (no violations) the
               dialog renders the snippet without the violations hint"
       (reset! ui-sv/ui-dialog
               (save-variant/open save-variant/initial-dialog-state
                                  :story.x/source
                                  {:label "hi"}
                                  12345
                                  []))
       (let [flat (str (ui-sv/save-dialog))]
         (is (not (str/includes? flat "story-save-variant-violations-hint"))
             "no hint when violations is empty")
         (is (not (str/includes? flat "paste at your own risk"))
             "no scary hint text on a conforming snapshot")))))

#?(:cljs
   (deftest save-dialog-renders-violations-hint-when-non-empty
     (testing "rf2-lancu — when the snapshot violates the schema the
               dialog renders a non-blocking hint above the snippet
               listing the offending keys. Non-blocking — the user can
               still copy / paste; the snippet carries the violating
               args as captured"
       (reset! ui-sv/ui-dialog
               (save-variant/open save-variant/initial-dialog-state
                                  :story.x/source
                                  {:label "hi" :count "not-an-int"}
                                  12345
                                  [{:key :count :value "not-an-int"
                                    :schema :int :explain nil}]))
       (let [flat (str (ui-sv/save-dialog))]
         (is (str/includes? flat "story-save-variant-violations-hint")
             "the hint container is present")
         (is (str/includes? flat "paste at your own risk")
             "the scary hint text is present")
         (is (str/includes? flat "story-save-variant-violation-row")
             "the violation list renders rows")
         (is (str/includes? flat ":count")
             "the offending key name appears in the hint")
         (is (str/includes? flat "story-save-variant-snippet")
             "the snippet still renders — the hint is non-blocking")))))

;; ---- rf2-ba86n.6: eight-slice capture model -----------------------------

(deftest capture-slices-covers-all-eight-slices
  (testing "every slice in spec/019 §3 is classified — none silently dropped"
    (let [report (save-variant/capture-slices {:n 1} nil {})
          slices (set (map :slice report))]
      (is (= (set save-variant/slice-order) slices)
          "the report covers exactly the eight canonical slices")
      (is (= save-variant/slice-order (mapv :slice report))
          "rows render in the canonical slice-order"))))

(deftest capture-slices-args-is-projectable
  (testing "args (+ transient-controls) are the projectable pair; args emits"
    (let [report   (save-variant/capture-slices {:n 7} nil {})
          by-slice (into {} (map (juxt :slice identity)) report)]
      (is (= :projectable (-> by-slice :args :status)))
      (is (true? (-> by-slice :args :emit?)) "args contributes the :args slot")
      (is (= {:n 7} (-> by-slice :args :value)))
      (is (= :projectable (-> by-slice :transient-controls :status)))
      (is (false? (-> by-slice :transient-controls :emit?))
          "transient-controls folds into :args — no second slot"))))

(deftest capture-slices-unwired-slices-warn-not-fabricate
  (testing "with a bare source body, the not-yet-wired slices are :not-wired
            — captured nothing, honest warning, never a fabricated value"
    (let [report   (save-variant/capture-slices {:n 1} nil {:viewport :tablet})
          by-slice (into {} (map (juxt :slice identity)) report)]
      (doseq [s [:sub-overrides :db-seed :route :network :fx-overrides :viewport]]
        (is (= :not-wired (-> by-slice s :status))
            (str s " is honestly not-wired with no declared source value"))
        (is (false? (-> by-slice s :emit?)) (str s " emits no body slot"))
        (is (string? (-> by-slice s :note)) (str s " carries an honest note")))
      (is (str/includes? (-> by-slice :route :note) "7pgiz")
          "route names the blocking bead")
      (is (str/includes? (-> by-slice :db-seed :note) "blw1q")
          "db-seed names the blocking bead")
      (is (str/includes? (-> by-slice :viewport :note) "FORK")
          "viewport is flagged as the product fork")
      (is (str/includes? (-> by-slice :viewport :note) ":tablet")
          "viewport note names the live chrome-wide selection it is NOT projecting"))))

(deftest capture-slices-declared-source-slots-are-captured-as-declared
  (testing "when the source variant body declares the unwired slices, they are
            captured-as-declared (carried forward via :extends), warned, honest"
    (let [body     {:sub-overrides {[:s] :v}
                    :network       {[:get "/u"] {:reply {}}}
                    :fx-overrides  {:my/fx 1}
                    :setup         [[:e]]
                    :viewport      :tablet}
          report   (save-variant/capture-slices {:n 1} body {})
          by-slice (into {} (map (juxt :slice identity)) report)]
      (doseq [[s v] {:sub-overrides {[:s] :v}
                     :network       {[:get "/u"] {:reply {}}}
                     :fx-overrides  {:my/fx 1}
                     :db-seed       [[:e]]
                     :viewport      :tablet}]
        (is (= :captured-as-declared (-> by-slice s :status))
            (str s " carries the declared source value forward"))
        (is (= v (-> by-slice s :value)) (str s " value is the declared slot")))
      (is (= :not-wired (-> by-slice :route :status))
          "route has no declared source slot — still not-wired"))))

(deftest slice-warnings-filters-projectable
  (testing "slice-warnings keeps only the rows the user must see — every slice
            that is NOT a clean live projection"
    (let [report   (save-variant/capture-slices {:n 1} nil {})
          warnings (save-variant/slice-warnings report)]
      (is (every? #(not= :projectable (:status %)) warnings)
          "no projectable rows in the warnings")
      (is (not (some #(= :args (:slice %)) warnings))
          "args (projectable) is filtered out")
      (is (some #(= :route (:slice %)) warnings)
          "route (not-wired) is surfaced"))))

(deftest open-6-arity-stamps-slices
  (testing "rf2-ba86n.6 — open's 6-arity stamps the slice report on the dialog"
    (let [report (save-variant/capture-slices {:n 1} nil {})
          s      (save-variant/open save-variant/initial-dialog-state
                                    :story.x/y {:n 1} 0 [] report)]
      (is (true? (:open? s)))
      (is (= report (:slices s)) "the slice report rides the dialog state"))))

(deftest open-4-arity-defaults-slices-to-empty-vec
  (testing "rf2-ba86n.6 — back-compat: pre-slices arities default :slices to []"
    (is (= [] (:slices (save-variant/open save-variant/initial-dialog-state
                                          :story.x/y {:n 1} 0))))
    (is (= [] (:slices (save-variant/open save-variant/initial-dialog-state
                                          :story.x/y {:n 1} 0 []))))))

#?(:cljs
   (deftest slice-report-renders-warnings-when-present
     (testing "rf2-ba86n.6 — the slice-report component renders a row per
               non-projectable slice with its status + honest note"
       (let [report (save-variant/capture-slices {:n 1} nil {:viewport :tablet})
             flat   (str (ui-sv/slice-report report))]
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
     (testing "rf2-ba86n.6 — when every slice projects cleanly there is
               nothing to warn about and the report renders nil"
       (let [all-clean [{:slice :args :status :projectable}
                        {:slice :transient-controls :status :projectable}]]
         (is (nil? (ui-sv/slice-report all-clean)))))))

#?(:cljs
   (deftest save-dialog-renders-slice-report-when-open
     (testing "rf2-ba86n.6 — the open dialog renders the slice report below
               the snippet so the save is honest about what it captures"
       (reset! ui-sv/ui-dialog
               (save-variant/open save-variant/initial-dialog-state
                                  :story.x/source
                                  {:n 1}
                                  12345
                                  []
                                  (save-variant/capture-slices {:n 1} nil {})))
       (let [flat (str (ui-sv/save-dialog))]
         (is (str/includes? flat "story-save-variant-slice-report")
             "the slice report renders inside the open dialog")
         (is (str/includes? flat "story-save-variant-snippet")
             "the snippet still renders — the report is non-blocking")))))
