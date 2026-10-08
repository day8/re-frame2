(ns re-frame.story.ui.schema-validation-cljs-test
  "Tests for the Schema-validation panel.

  Runs on both the JVM (cognitect.test-runner under
  `clojure -M:test`) and the CLJS node-test build (shadow's
  `:node-test` target; ns-regexp `cljs-test$` picks up this ns
  because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `project-failure` / `project-failures`
    projection, `args-violations` with synthetic and real-Malli
    validator/explainer pairs, and `format-explain` rendering.
  - **CLJS-only side-effects**: panel registration, the panel-render
    view's DOM root, and the Reagent `panel` component rendering the live
    Controls value."
  (:require [clojure.test :refer [are deftest is testing]]
            [malli.core :as m]
            #?@(:cljs [[reagent.core              :as r]
                       [reagent.ratom             :as ratom]
                       [re-frame.core             :as rf]
                       [re-frame.frame            :as rf.frame]
                       [re-frame.registrar        :as rf.registrar]
                       [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
                       [re-frame.story            :as rf.story]
                       [re-frame.story.ui.panels  :as rf.story.ui.panels]
                       [re-frame.story.ui.state   :as rf.story.ui.state]])
            [re-frame.story.ui.schema-validation :as rf.story.ui.schema-validation]))

;; ---- fixtures ------------------------------------------------------------

(defn failure-event
  "Build a `:rf.error/schema-validation-failure` trace event. `tags` is
  merged over the canonical base tags; a `:recovery` in `tags` is also
  hoisted to the top-level `:recovery` slot, matching the framework's emit
  shape (`re-frame.trace/emit-error!`)."
  ([id where]
   (failure-event id where {}))
  ([id where tags]
   {:op-type   :error
    :operation :rf.error/schema-validation-failure
    :id        id
    :time      (+ 1700000000000 (* id 10))
    :recovery  (:recovery tags :no-recovery)
    :tags      (merge {:category :rf.error/schema-validation-failure
                       :where    where
                       :frame    :story.x/y}
                      tags)}))

(defn unrelated-trace-event
  "Build a non-schema-failure trace event (one the panel filters out)."
  [id op-type operation]
  {:op-type   op-type
   :operation operation
   :id        id
   :time      (+ 1700000000000 (* id 10))
   :tags      {:rf.trace/dispatch-id (+ 1000 id)}})

;; ---- pure: project-failure ----------------------------------------------

(deftest project-failure-app-db-row
  (testing "an app-db failure projects to the full row: path, received,
            explain and recovery, with no failing-id"
    (let [ev (failure-event 3 :app-db {:path     [:user :credentials]
                                       :received {:no-id true}
                                       :explain  {:errors [{:path [:id] :message "missing"}]}})]
      (is (= {:id         3
              :time       1700000000030
              :where      :app-db
              :failing-id nil
              :path       [:user :credentials]
              :received   {:no-id true}
              :explain    {:errors [{:path [:id] :message "missing"}]}
              :recovery   :no-recovery
              :raw        ev}
             (rf.story.ui.schema-validation/project-failure ev))))))

(deftest project-failure-failing-id-per-boundary
  (testing "each boundary's id lands under :failing-id; an explicit
            :failing-id wins over the event-id / sub-id / cofx-id / fx-id
            fallbacks"
    (are [where tags failing-id]
         (= failing-id
            (:failing-id (rf.story.ui.schema-validation/project-failure
                           (failure-event 1 where tags))))
      :event      {:event-id :auth/login}            :auth/login
      :sub-return {:sub-id :pending-todos}           :pending-todos
      :cofx       {:cofx-id :now}                    :now
      :fx-args    {:fx-id :http-xhrio}               :http-xhrio
      :cofx       {:failing-id :foo/explicit
                   :event-id   :foo/bar
                   :cofx-id    :now}                 :foo/explicit)))

;; ---- pure: project-failures filter+project ------------------------------

(deftest project-failures-filters-and-projects
  (testing "a mixed buffer returns only schema-failure rows, projected,
            in input order — an :error of another category is filtered too"
    (let [buf  [(failure-event 1 :event {:event-id :foo/bar})
                (unrelated-trace-event 2 :rf.event :rf.event/dispatched)
                (unrelated-trace-event 3 :error :rf.error/handler-exception)
                (failure-event 4 :app-db {:path [:user]})
                (unrelated-trace-event 5 :rf.view :rf.view/render)
                (failure-event 6 :sub-return {:sub-id :pending-todos})]
          rows (rf.story.ui.schema-validation/project-failures buf)]
      (is (= [1 4 6]                      (map :id rows)))
      (is (= [:event :app-db :sub-return] (map :where rows))))))

;; ---- pure: args-violations ----------------------------------------------

(defn- truthy-validator
  "A validator that accepts every (schema, value) pair."
  [_schema _value]
  true)

(defn- string-validator
  "A validator mimicking Malli's scalar predicates; anything else passes."
  [schema value]
  (cond
    (= :string schema)            (string? value)
    (= :int    schema)            (integer? value)
    (= :boolean schema)           (boolean? (or value false))
    (and (vector? schema)
         (= :map (first schema))) (map? value)
    :else                         true))

(defn- string-explainer
  "Companion explainer for `string-validator` — a Malli-shaped explain map."
  [schema value]
  {:errors [{:path    []
             :message (str "expected " (pr-str schema)
                           " got "    (pr-str value))}]})

(deftest args-violations-no-validator-soft-passes
  (testing "no validator registered (nil) → every value passes; empty
            vector returned (per Spec 010 §Recommended soft-pass)"
    (is (= [] (rf.story.ui.schema-validation/args-violations {:a 7 :b "x"}
                                  [:map [:a :string] [:b :int]]
                                  {:validate nil :explain nil})))))

(deftest args-violations-walks-map-entries
  (testing "every :map entry's value is checked against its child schema;
            failures collect in declared order with {:key :value :schema :explain}"
    (let [viols (rf.story.ui.schema-validation/args-violations
                  {:name 42 :age "old" :active "yes"}
                  [:map
                   [:name   :string]
                   [:age    :int]
                   [:active :boolean]]
                  {:validate string-validator
                   :explain  string-explainer})]
      (is (= [[:name 42 :string] [:age "old" :int] [:active "yes" :boolean]]
             (mapv (juxt :key :value :schema) viols)))
      (is (some? (:explain (first viols)))))))

;; ---- pure: args-violations agrees with Malli's map rule -----------------
;;
;; Against REAL Malli: absence is judged by the entry's optionality, a
;; present value by its child schema.

(def ^:private malli-fns {:validate m/validate :explain m/explain})

(def ^:private optional-heading [:map [:heading {:optional true} [:string {:min 1}]]])

(def ^:private required-nullable-heading [:map [:heading [:maybe :string]]])

(deftest args-violations-respects-optionality-and-key-presence
  (are [args schema violation-keys]
       (= violation-keys
          (mapv :key (rf.story.ui.schema-validation/args-violations args schema malli-fns)))
    ;; optional entry, key absent → no violation
    {}            optional-heading          []
    ;; required entry, key absent → a violation, though the child accepts nil
    {}            required-nullable-heading [:heading]
    ;; optional entry, present invalid value → a violation
    {:heading ""} optional-heading          [:heading]
    ;; required nullable entry, present nil → no violation
    {:heading nil} required-nullable-heading [])
  (testing "the absent required key's row explains the missing key rather
            than a nil the child accepts"
    (let [row (first (rf.story.ui.schema-validation/args-violations
                       {} required-nullable-heading malli-fns))]
      (is (= [nil [:maybe :string] ":heading: schema violation"]
             [(:value row)
              (:schema row)
              (rf.story.ui.schema-validation/format-explain (:explain row))])))))

(deftest args-violations-non-map-schema-validates-whole-args
  (testing "a non-:map top-level schema validates the whole args; a failure
            surfaces under ::root"
    (is (= [[::rf.story.ui.schema-validation/root {:name "x"}]]
           (mapv (juxt :key :value)
                 (rf.story.ui.schema-validation/args-violations
                   {:name "x"} :string
                   {:validate string-validator :explain string-explainer})))))
  (testing "a conforming whole-args value emits no violation"
    (is (= [] (rf.story.ui.schema-validation/args-violations
                {:any "x"} :something
                {:validate truthy-validator :explain string-explainer})))))

;; ---- pure: format-explain -----------------------------------------------

(deftest format-explain-renders-each-shape
  (testing "nil renders as the empty string so the renderer can interpolate it"
    (is (= "" (rf.story.ui.schema-validation/format-explain nil))))
  (testing "a Malli-shaped explain map flattens to joined path-and-message"
    (is (= ":email: missing; (root): bad"
           (rf.story.ui.schema-validation/format-explain
             {:errors [{:path [:email] :message "missing"}
                       {:path []       :message "bad"}]}))))
  (testing "anything else falls back to pr-str"
    (is (= "\"a plain string\"" (rf.story.ui.schema-validation/format-explain "a plain string")))))

(deftest format-explain-reads-a-real-malli-explanation
  (testing "the explanation `malli.core/explain` really returns — `:errors`
            a seq, not a vector, and no `:message` on the error maps —
            renders as path + message, never as the pr-str of the map"
    (let [explanation (m/explain optional-heading {:heading ""})]
      ;; Guard the fixture: a VECTOR `:errors` would pass whether or not
      ;; `format-explain` reads a non-vector seq.
      (is (not (vector? (:errors explanation)))
          "fixture carries Malli's real non-vector :errors")
      (is (= ":heading: schema violation"
             (rf.story.ui.schema-validation/format-explain explanation)))))
  (testing "the per-entry explanation the Controls row and the args panel
            render — `args-violations` explains the CHILD schema, so the
            path is the root"
    (is (= "(root): schema violation"
           (rf.story.ui.schema-validation/format-explain
             (:explain (first (rf.story.ui.schema-validation/args-violations
                                {:heading ""} optional-heading malli-fns))))))))

;; ---- CLJS-only: panel registration --------------------------------------

#?(:cljs
   (defn- reset-all! []
     (rf.story/clear-all!)
     (rf.registrar/clear-all!)
     (reset! rf.frame/frames {})
     (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
     (rf.story/install-canonical-vocabulary!)
     (rf.frame/ensure-default-frame!)))

#?(:cljs
   (deftest ^:cljs panel-id-stable
     (testing "panel-id is :rf.story.panel/schema-validation per
              tools/story/spec/014-Chrome-Features.md"
       (is (= :rf.story.panel/schema-validation rf.story.ui.schema-validation/panel-id)))))

#?(:cljs
   (deftest ^:cljs panel-registered-by-canonical-bootstrap
     (testing "the canonical bootstrap points the panel at its render view"
       (reset-all!)
       (is (= rf.story.ui.schema-validation/panel-render-id
              (:render (rf.story/handler-meta :story-panel
                                              rf.story.ui.schema-validation/panel-id)))))))

#?(:cljs
   (deftest ^:cljs panel-render-view-roots-in-dom-element
     (testing "the panel-render view's hiccup root is a DOM element (`:div`),
              not a bare component reference: per Spec 006 §Source-coord
              annotation the annotator can only attach
              `data-rf2-source-coord` to a DOM root, so a bare
              `[panel variant-id]` root hides the panel from Story and
              Xray Inspect Mode"
       (reset-all!)
       (let [out ((rf/view rf.story.ui.schema-validation/panel-render-id) :story.unknown/y)]
         (is (vector? out))
         (is (= :div (first out)))))))

#?(:cljs
   (deftest ^:cljs panel-renders-hiccup
     (testing "the panel render fn returns a hiccup vector for an
              unregistered variant id (graceful empty state)"
       (reset-all!)
       (let [out ((rf.story.ui.schema-validation/panel :story.unknown/y) :story.unknown/y)]
         (is (vector? out))
         (is (= :div (first out)))))))

#?(:cljs
   (deftest ^:cljs panel-shows-up-in-right-placement
     (testing "render-panels-at-placement :right picks up the schema
              validation panel"
       (reset-all!)
       (let [out (rf.story.ui.panels/render-panels-at-placement
                   :right :story.x/y {})]
         ;; One entry per visible panel; find the schema-validation slot by
         ;; serialising and looking for the panel id.
         (is (vector? out))
         (is (re-find (re-pattern (str rf.story.ui.schema-validation/panel-id))
                      (pr-str out)))))))

;; ---- CLJS-only: the panel validates the LIVE Controls value -------------
;;
;; Controls validates `effective-args` resolved with the active modes and the
;; variant's cell overrides. A panel that resolved with NO opts would see the
;; stored args, and one that derefed no shell state would never re-render on
;; a control edit. Each witness drives shell state and reads what the panel
;; RENDERS.

#?(:cljs
   (def ^:private heading-schema
     "The login card's props schema."
     [:map [:heading {:optional true} [:string {:min 1}]]]))

#?(:cljs
   (defn- panel-violation-keys
     "The arg keys the rendered panel reports as violating, or `:empty` when
     it renders its 'no args violations' state. The rows are
     `[args-violation-row v]` component vectors, so read `v` off each."
     [tree]
     (let [nodes (atom [])]
       (letfn [(walk [n]
                 (cond
                   (vector? n) (do (swap! nodes conj n) (doseq [c (rest n)] (walk c)))
                   (seq? n)    (doseq [c n] (walk c))))]
         (walk tree))
       (let [test-id (fn [n] (:data-test (when (map? (second n)) (second n))))]
         (if (some #(= "story-schema-args-empty" (test-id %)) @nodes)
           :empty
           (when-let [rows (some #(when (= "story-schema-args-violations" (test-id %)) %) @nodes)]
             (->> (drop 2 rows)
                  (mapcat #(if (seq? %) % [%]))
                  (mapv (comp :key second)))))))))

#?(:cljs
   (defn- assert-live-validator!
     "PRECONDITION: a validator that really rejects the value under test, so
     a soft-pass (no validator on the classpath) cannot stand in for a result."
     []
     (let [vfns (rf.story.ui.schema-validation/validator-fns)]
       (is (fn? (:validate vfns)) "PRECONDITION — a live validator is registered")
       (is (= [:heading]
              (mapv :key (rf.story.ui.schema-validation/args-violations
                           {:heading ""} heading-schema vfns)))
           "PRECONDITION — the reader flags a cleared :heading"))))

#?(:cljs
   (deftest ^:cljs panel-reports-the-cell-override-and-re-renders-on-a-control-edit
     (testing "a Controls edit that clears :heading reaches the panel through
               shell state — it reports the violation, and the edit alone
               re-runs its render (no manual refresh)"
       (reset-all!)
       (rf.story.ui.state/reset-shell-state!)
       (assert-live-validator!)
       (rf/reg-view* :view.lzzrw/card {:rf/props heading-schema} (fn [_] [:div]))
       (rf.story/reg-variant :story.lzzrw/card
         {:component :view.lzzrw/card :args {:heading "Sign in"} :setup []})
       (let [vid     :story.lzzrw/card
             render  (rf.story.ui.schema-validation/panel vid)
             renders (atom 0)
             tracked (r/track! (fn [] (swap! renders inc) (render vid)))]
         (try
           (is (= :empty (panel-violation-keys @tracked))
               "CONTROL — the stored args conform, so the panel starts clean")
           (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override-scalar
                                          vid :heading "")
           (ratom/flush!)
           (is (= 2 @renders)
               "the shell-state edit re-ran the panel's render")
           (is (= [:heading] (panel-violation-keys @tracked))
               "the panel reports the override's violation, as Controls does")
           (finally
             (r/dispose! tracked)
             (rf.story.ui.state/reset-shell-state!)))))))

#?(:cljs
   (deftest ^:cljs panel-resolves-args-under-the-active-modes
     (testing "an active mode that supplies an invalid :heading (the variant
               declares none, so the story's valid default would otherwise
               show) makes the panel report the violation"
       (reset-all!)
       (rf.story.ui.state/reset-shell-state!)
       (assert-live-validator!)
       (rf/reg-view* :view.lzzrw/moded {:rf/props heading-schema} (fn [_] [:div]))
       (rf.story/reg-story :story.lzzrw-moded
         {:component :view.lzzrw/moded :args {:heading "Sign in"}})
       (rf.story/reg-mode :Mode.lzzrw/blank {:args {:heading ""}})
       (rf.story/reg-variant :story.lzzrw-moded/v {:setup []})
       (let [vid :story.lzzrw-moded/v]
         (try
           (is (= :empty (panel-violation-keys ((rf.story.ui.schema-validation/panel vid) vid)))
               "CONTROL — with no mode active the story default conforms")
           (rf.story.ui.state/swap-state! rf.story.ui.state/set-active-modes [:Mode.lzzrw/blank])
           (is (= [:heading] (panel-violation-keys ((rf.story.ui.schema-validation/panel vid) vid)))
               "the panel resolves args under the active mode, as Controls does")
           (finally
             (rf.story.ui.state/reset-shell-state!)))))))
