(ns re-frame.story.ui.schema-validation-cljs-test
  "Tests for the Schema-validation panel (rf2-dvue).

  Runs on both the JVM (cognitect.test-runner under
  `clojure -M:test`) and the CLJS node-test build (shadow's
  `:node-test` target; ns-regexp `cljs-test$` picks up this ns
  because its name ends in `cljs-test`).

  ## Coverage layers

  - **Pure data** (JVM + CLJS): `schema-validation-event?`
    classification, `project-failure` / `project-failures`
    projection, `map-schema?` + `map-entries` Malli walk,
    `args-violations` with synthetic validator/explainer pairs, and
    `format-explain` rendering.
  - **CLJS-only side-effects**: panel registration shape (the panel
    is registered with `:placement :right` against the canonical
    `:rf.story.panel/schema-validation` id), panel-render view is
    registered against the framework view registry, and the Reagent
    `panel` component returns hiccup when called against a
    registered variant."
  (:require [clojure.test :refer [deftest is testing]]
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
  "Build a `:rf.error/schema-validation-failure` trace event with
  sensible defaults. `tags` is merged over the canonical base tags so
  per-test overrides land cleanly. When `tags` carries `:recovery`,
  that value is also hoisted to the top-level `:recovery` slot —
  matching the framework's emit shape (`re-frame.trace/emit-error!`
  hoists `(:recovery tags :no-recovery)` onto the event itself)."
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
  "Build a non-schema-failure trace event (something that should be
  filtered out — a regular dispatch, a handler exception, etc.)."
  [id op-type operation]
  {:op-type   op-type
   :operation operation
   :id        id
   :time      (+ 1700000000000 (* id 10))
   :tags      {:rf.trace/dispatch-id (+ 1000 id)}})

;; ---- pure: schema-validation-event? classification ----------------------

(deftest schema-validation-event?-classifies-failures
  (testing ":rf.error/schema-validation-failure trace events qualify"
    (is (rf.story.ui.schema-validation/schema-validation-event? (failure-event 1 :event)))
    (is (rf.story.ui.schema-validation/schema-validation-event? (failure-event 2 :sub-return)))
    (is (rf.story.ui.schema-validation/schema-validation-event? (failure-event 3 :app-db)))
    (is (rf.story.ui.schema-validation/schema-validation-event? (failure-event 4 :cofx)))
    (is (rf.story.ui.schema-validation/schema-validation-event? (failure-event 5 :fx-args)))))

(deftest schema-validation-event?-rejects-unrelated
  (testing "non-error trace events and unrelated error categories are filtered out"
    (is (not (rf.story.ui.schema-validation/schema-validation-event?
               (unrelated-trace-event 10 :rf.event :rf.event/dispatched))))
    (is (not (rf.story.ui.schema-validation/schema-validation-event?
               (unrelated-trace-event 11 :error :rf.error/handler-exception))))
    (is (not (rf.story.ui.schema-validation/schema-validation-event?
               (unrelated-trace-event 12 :error :rf.error/no-such-sub))))
    (is (not (rf.story.ui.schema-validation/schema-validation-event?
               (unrelated-trace-event 13 :warning :rf.fx/skipped-on-platform))))))

(deftest schema-validation-event?-rejects-malformed
  (testing "empty / partial trace events do not classify"
    (is (not (rf.story.ui.schema-validation/schema-validation-event? {})))
    (is (not (rf.story.ui.schema-validation/schema-validation-event? {:op-type :error})))
    (is (not (rf.story.ui.schema-validation/schema-validation-event?
               {:op-type :error :operation :rf.error/something-else})))))

;; ---- pure: project-failure ----------------------------------------------

(deftest project-failure-event-row
  (testing "a :where :event failure projects the event-id under :failing-id"
    (let [ev  (failure-event 1 :event {:event-id  :auth/login
                                       :received  [:auth/login {}]
                                       :explain   {:errors [{:path [:email] :message "missing"}]}})
          row (rf.story.ui.schema-validation/project-failure ev)]
      (is (= 1                       (:id row)))
      (is (= :event                  (:where row)))
      (is (= :auth/login             (:failing-id row)))
      (is (= [:auth/login {}]        (:received row)))
      (is (some? (:explain row)))
      (is (= :no-recovery            (:recovery row)))
      (is (= ev                      (:raw row))))))

(deftest project-failure-sub-return-row
  (testing "a :where :sub-return failure projects the sub-id under :failing-id"
    (let [ev  (failure-event 2 :sub-return {:sub-id   :pending-todos
                                            :received [{:bad "shape"}]
                                            :recovery :replaced-with-default})
          row (rf.story.ui.schema-validation/project-failure ev)]
      (is (= :sub-return             (:where row)))
      (is (= :pending-todos          (:failing-id row)))
      (is (= :replaced-with-default  (:recovery row))))))

(deftest project-failure-app-db-row
  (testing "a :where :app-db failure projects the path and the failing-id is nil"
    (let [ev  (failure-event 3 :app-db {:path     [:user :credentials]
                                        :received {:no-id true}})
          row (rf.story.ui.schema-validation/project-failure ev)]
      (is (= :app-db                 (:where row)))
      (is (= [:user :credentials]    (:path row)))
      (is (nil? (:failing-id row))))))

(deftest project-failure-cofx-row
  (testing "a :where :cofx failure carries an explicit :failing-id (the
            event-id whose handler was about to run — per Spec 010
            §Validation order step 2's emit shape); the projector
            surfaces that under :failing-id"
    (let [ev  (failure-event 4 :cofx {:cofx-id    :now
                                      :event-id   :foo/bar
                                      :failing-id :foo/bar})
          row (rf.story.ui.schema-validation/project-failure ev)]
      (is (= :cofx                   (:where row)))
      (is (= :foo/bar                (:failing-id row))))))

(deftest project-failure-falls-back-to-id-when-no-explicit-failing-id
  (testing "when an emission shape omits :failing-id, the projector
            falls back through event-id → sub-id → cofx-id → fx-id"
    (let [row (rf.story.ui.schema-validation/project-failure (failure-event 50 :cofx {:cofx-id :now}))]
      (is (= :now (:failing-id row))))))

(deftest project-failure-fx-args-row
  (testing "a :where :fx-args failure projects the fx-id under :failing-id"
    (let [ev  (failure-event 5 :fx-args {:fx-id :http-xhrio
                                         :received {:url 7}})
          row (rf.story.ui.schema-validation/project-failure ev)]
      (is (= :fx-args                (:where row)))
      (is (= :http-xhrio             (:failing-id row))))))

;; ---- pure: project-failures filter+project ------------------------------

(deftest project-failures-filters-and-projects
  (testing "a mixed buffer returns only schema-failure rows, projected,
            in input order"
    (let [buf  [(failure-event 1 :event {:event-id :foo/bar})
                (unrelated-trace-event 2 :rf.event :rf.event/dispatched)
                (unrelated-trace-event 3 :rf.sub :rf.sub/run)
                (failure-event 4 :app-db {:path [:user]})
                (unrelated-trace-event 5 :rf.view :rf.view/render)
                (failure-event 6 :sub-return {:sub-id :pending-todos})]
          rows (rf.story.ui.schema-validation/project-failures buf)]
      (is (= 3 (count rows)))
      (is (= [1 4 6]              (map :id rows)))
      (is (= [:event :app-db :sub-return] (map :where rows)))
      (is (= [:foo/bar nil :pending-todos] (map :failing-id rows))))))

(deftest project-failures-empty
  (testing "empty / all-unrelated buffers project to empty vector"
    (is (= [] (rf.story.ui.schema-validation/project-failures [])))
    (is (= [] (rf.story.ui.schema-validation/project-failures
                [(unrelated-trace-event 1 :rf.event :rf.event/dispatched)
                 (unrelated-trace-event 2 :error :rf.error/handler-exception)])))))

;; ---- pure: map-schema? + map-entries ------------------------------------

(deftest map-schema?-classifies-map-shapes
  (testing "[:map ...] vectors are recognised; everything else is not"
    (is (rf.story.ui.schema-validation/map-schema? [:map [:a :string]]))
    (is (rf.story.ui.schema-validation/map-schema? [:map {:closed true} [:a :string]]))
    (is (not (rf.story.ui.schema-validation/map-schema? [:vector :string])))
    (is (not (rf.story.ui.schema-validation/map-schema? :string)))
    (is (not (rf.story.ui.schema-validation/map-schema? nil)))
    (is (not (rf.story.ui.schema-validation/map-schema? {})))
    (is (not (rf.story.ui.schema-validation/map-schema? [:enum :a :b])))))

(deftest map-entries-projects-pairs
  (testing "map-entries returns [k child-schema] pairs in declared order,
            skipping the optional properties map at index 1"
    (is (= [[:a :string] [:b :int]]
           (rf.story.ui.schema-validation/map-entries [:map [:a :string] [:b :int]])))
    (is (= [[:a :string] [:b :int]]
           (rf.story.ui.schema-validation/map-entries [:map {:closed true} [:a :string] [:b :int]])))
    (is (nil? (rf.story.ui.schema-validation/map-entries [:vector :string]))))
  (testing "Malli map-entry with per-entry properties — `[k {:optional true} schema]`
            — projects to the child schema (the third element)"
    (is (= [[:a :string]]
           (rf.story.ui.schema-validation/map-entries [:map [:a {:optional true} :string]])))))

;; ---- pure: args-violations ----------------------------------------------

(defn- truthy-validator
  "A validator fn that returns true on every (schema, value) pair —
  the test stand-in for 'every value conforms'."
  [_schema _value]
  true)

(defn- string-validator
  "A validator fn that mimics Malli's `:string` predicate: passes
  strings, rejects everything else."
  [schema value]
  (cond
    (= :string schema)            (string? value)
    (= :int    schema)            (integer? value)
    (= :boolean schema)           (boolean? (or value false))
    (and (vector? schema)
         (= :map (first schema))) (map? value)
    :else                         true))

(defn- string-explainer
  "Companion explainer for `string-validator` — returns a Malli-shaped
  explain map with one `:errors` entry per failure."
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

(deftest args-violations-no-schema-passes
  (testing "no schema on file → nothing to validate; empty vector"
    (is (= [] (rf.story.ui.schema-validation/args-violations {:a 7}
                                  nil
                                  {:validate truthy-validator :explain nil})))))

(deftest args-violations-walks-map-entries
  (testing "every :map entry's value is checked against its child schema;
            failures collect with {:key :value :schema :explain}"
    (let [args   {:name 42 :age "old" :active "yes"}
          schema [:map
                  [:name   :string]
                  [:age    :int]
                  [:active :boolean]]
          viols  (rf.story.ui.schema-validation/args-violations args schema
                                     {:validate string-validator
                                      :explain  string-explainer})
          by-key (into {} (map (juxt :key identity)) viols)]
      (is (= 3 (count viols)))
      (is (contains? by-key :name))
      (is (contains? by-key :age))
      (is (contains? by-key :active))
      ;; The value carried under each violation matches the supplied arg.
      (is (= 42    (get-in by-key [:name :value])))
      (is (= "old" (get-in by-key [:age :value])))
      ;; The child schema rides on each violation.
      (is (= :string  (get-in by-key [:name :schema])))
      (is (= :int     (get-in by-key [:age :schema])))
      (is (= :boolean (get-in by-key [:active :schema])))
      ;; The explainer's output is captured.
      (is (some? (get-in by-key [:name :explain]))))))

(deftest args-violations-conforming-args-empty
  (testing "when every arg conforms, the violation vector is empty"
    (is (= [] (rf.story.ui.schema-validation/args-violations {:name "alice" :age 30}
                                  [:map [:name :string] [:age :int]]
                                  {:validate string-validator
                                   :explain  string-explainer})))))

(deftest args-violations-decides-absent-keys-by-presence-not-by-validator
  (testing "an absent REQUIRED key is a violation whatever the child
            validator says about nil — one that rejects nil and one that
            accepts it agree (rf2-scheh: this test used to pin the
            opposite, routing the absent key's nil through the child)"
    (let [args   {:name "alice"}    ;; :age missing
          schema [:map [:name :string] [:age :int]]]
      (doseq [vfns [{:validate string-validator :explain string-explainer}
                    {:validate truthy-validator :explain nil}]]
        (let [viols (rf.story.ui.schema-validation/args-violations args schema vfns)]
          (is (= [:age] (mapv :key viols)))
          (is (nil? (-> viols first :value)))))))
  (testing "an absent OPTIONAL key is no violation, even under a validator
            that rejects nil"
    (is (= [] (rf.story.ui.schema-validation/args-violations
                {:name "alice"}
                [:map [:name :string] [:age {:optional true} :int]]
                {:validate string-validator :explain string-explainer})))))

;; ---- pure: args-violations agrees with Malli's map rule (rf2-scheh) -----
;;
;; The five-shape discriminator, against REAL Malli. Each shape first asks
;; Malli about the WHOLE map (the oracle), then checks the per-key rows
;; agree: absence is judged by the entry's optionality, a present value by
;; its child schema.

(def ^:private malli-fns {:validate m/validate :explain m/explain})

(def ^:private optional-heading [:map [:heading {:optional true} [:string {:min 1}]]])

(def ^:private required-nullable-heading [:map [:heading [:maybe :string]]])

(defn- malli-violation-keys [args schema]
  (mapv :key (rf.story.ui.schema-validation/args-violations args schema malli-fns)))

(deftest args-violations-respects-optionality-and-key-presence
  (testing "1. optional entry, key absent → no violation"
    (is (m/validate optional-heading {}) "ORACLE — Malli accepts the omitted optional key")
    (is (= [] (malli-violation-keys {} optional-heading))))
  (testing "2. required nullable entry, key absent → a :heading violation"
    (is (not (m/validate required-nullable-heading {})) "ORACLE — Malli rejects the missing required key")
    (is (m/validate [:maybe :string] nil) "the child alone accepts nil, so presence must decide")
    (is (= [:heading] (malli-violation-keys {} required-nullable-heading)))
    (let [row (first (rf.story.ui.schema-validation/args-violations
                       {} required-nullable-heading malli-fns))]
      (is (nil? (:value row)))
      (is (= [:maybe :string] (:schema row)))
      (is (= ":heading: schema violation"
             (rf.story.ui.schema-validation/format-explain (:explain row)))
          "the explanation locates the missing key rather than a nil the child accepts")))
  (testing "3. optional entry, present invalid value → a violation"
    (is (not (m/validate optional-heading {:heading ""})) "ORACLE")
    (is (= [:heading] (malli-violation-keys {:heading ""} optional-heading))))
  (testing "4. optional entry, present valid value → no violation"
    (is (m/validate optional-heading {:heading "Sign in"}) "ORACLE")
    (is (= [] (malli-violation-keys {:heading "Sign in"} optional-heading))))
  (testing "5. required nullable entry, present nil → no violation"
    (is (m/validate required-nullable-heading {:heading nil}) "ORACLE")
    (is (= [] (malli-violation-keys {:heading nil} required-nullable-heading)))))

(deftest args-violations-non-map-schema-root-violation
  (testing "a non-:map top-level schema validates the whole args
            against the schema; failure surfaces under ::root"
    ;; string-validator treats `:string` as 'must be a string'; the
    ;; whole args map is not a string → violation.
    (let [viols (rf.story.ui.schema-validation/args-violations {:name "x"} :string
                                    {:validate string-validator
                                     :explain  string-explainer})]
      (is (= 1 (count viols)))
      (is (= ::rf.story.ui.schema-validation/root (-> viols first :key)))
      (is (= {:name "x"} (-> viols first :value))))))

(deftest args-violations-non-map-schema-passing
  (testing "a non-:map top-level schema that conforms emits no violation"
    ;; A truthy validator accepts every value, so the whole-args check
    ;; passes.
    (is (= [] (rf.story.ui.schema-validation/args-violations {:any "x"} :something
                                  {:validate truthy-validator
                                   :explain  string-explainer})))))

;; ---- pure: format-explain -----------------------------------------------

(deftest format-explain-nil
  (testing "nil renders as the empty string so the renderer can
            interpolate it safely"
    (is (= "" (rf.story.ui.schema-validation/format-explain nil)))))

(deftest format-explain-malli-shape
  (testing "a Malli explain map (`{:errors [...]}`) flattens to a
            joined path-and-message string"
    (let [explanation {:errors [{:path [:email] :message "missing"}
                                {:path []       :message "bad"}]}
          out         (rf.story.ui.schema-validation/format-explain explanation)]
      (is (string? out))
      (is (re-find #":email" out))
      (is (re-find #"missing" out))
      (is (re-find #"\(root\)" out))
      (is (re-find #"bad" out)))))

(deftest format-explain-reads-a-real-malli-explanation
  (testing "the explanation `malli.core/explain` really returns — `:errors`
            a seq, not a vector, and no `:message` on the error maps —
            renders as path + message, never as the pr-str of the map
            (rf2-fhjke: the login card's cleared `:heading`)"
    (let [schema      [:map [:heading {:optional true} [:string {:min 1}]]]
          explanation (m/explain schema {:heading ""})]
      ;; Guard the fixture: a hand-built VECTOR `:errors` passes with or
      ;; without the fix, so this test only proves anything while the
      ;; real shape is not a vector.
      (is (sequential? (:errors explanation)))
      (is (not (vector? (:errors explanation)))
          "fixture carries Malli's real non-vector :errors")
      (is (= ":heading: schema violation"
             (rf.story.ui.schema-validation/format-explain explanation)))))
  (testing "the per-entry explanation the Controls row and the args panel
            actually render — `args-violations` explains the CHILD schema,
            so the path is the root"
    (let [viols (rf.story.ui.schema-validation/args-violations
                  {:heading ""}
                  [:map [:heading {:optional true} [:string {:min 1}]]]
                  {:validate m/validate :explain m/explain})]
      (is (= [:heading] (mapv :key viols)))
      (is (= "(root): schema violation"
             (rf.story.ui.schema-validation/format-explain
               (:explain (first viols))))))))

(deftest format-explain-falls-back-to-pr-str
  (testing "explanations that aren't Malli-shaped fall back to pr-str"
    (is (= "\"a plain string\"" (rf.story.ui.schema-validation/format-explain "a plain string")))
    (is (= ":a-keyword"         (rf.story.ui.schema-validation/format-explain :a-keyword)))))

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
     (testing "panel-id is :rf.story.panel/schema-validation per the
              SOTA audit's reservation"
       (is (= :rf.story.panel/schema-validation rf.story.ui.schema-validation/panel-id)))))

#?(:cljs
   (deftest ^:cljs panel-renders-against-canonical-bootstrap
     (testing "after install-canonical-vocabulary! the schema-validation
              panel is registered against the story side-table"
       (reset-all!)
       (let [ps (rf.story/registrations :story-panel)]
         (is (contains? ps rf.story.ui.schema-validation/panel-id))
         (let [body (rf.story/handler-meta :story-panel rf.story.ui.schema-validation/panel-id)]
           (is (= :right                  (:placement body)))
           (is (= rf.story.ui.schema-validation/panel-render-id      (:render body)))
           (is (re-find #"(?i)schema"     (or (:title body) ""))))))))

#?(:cljs
   (deftest ^:cljs panel-render-view-registered
     (testing "the panel-render view is registered against the framework
              view registry so the late-bind lookup in re-frame.core/view
              finds it"
       (reset-all!)
       (is (some? (rf/view rf.story.ui.schema-validation/panel-render-id))))))

#?(:cljs
   (deftest ^:cljs panel-render-view-roots-in-dom-element
     (testing "the schema-validation panel-render view returns hiccup
              whose root is a DOM element keyword (`:div`), not a bare
              component reference.

              Per Spec 006 §Source-coord annotation the annotator can
              only attach `data-rf2-source-coord` to hiccup DOM roots;
              a bare `[panel variant-id]` root makes the panel
              invisible to Story Inspect Mode + Xray Inspect Mode
              (rf2-iwny7). The `[:div]` wrap is load-bearing."
       (reset-all!)
       (let [view-fn (rf/view rf.story.ui.schema-validation/panel-render-id)
             out     (view-fn :story.unknown/y)]
         (is (vector? out)
             "panel-render returns a hiccup vector")
         (is (keyword? (first out))
             "hiccup root must be a keyword (DOM element), not a component ref")
         (is (= :div (first out))
             "hiccup root is specifically `:div` per the source-coord-annotator wrap")))))

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
         ;; The host returns a hiccup vector with one entry per visible
         ;; panel; assert the schema-validation slot is among the entries
         ;; by serialising and looking for the panel id.
         (is (vector? out))
         (is (re-find (re-pattern (str rf.story.ui.schema-validation/panel-id))
                      (pr-str out)))))))

;; ---- CLJS-only: the panel validates the LIVE Controls value (rf2-lzzrw) --
;;
;; Controls validates `effective-args` resolved with the active modes and the
;; variant's cell overrides. The panel used to resolve with NO opts, so it saw
;; the stored args, and it derefed no shell state, so a control edit never
;; re-rendered it. Each witness drives shell state and reads what the panel
;; RENDERS; a test that only checked stored args would pass either way.

#?(:cljs
   (def ^:private heading-schema
     "The login card's props schema (rf2-fhjke's measured case)."
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

#?(:cljs
   (deftest ^:cljs validator-fns-defaults-to-nil-without-schemas-artefact
     (testing "when re-frame.schemas hasn't published its hooks, the
              validator-fns lookup returns {:validate nil :explain nil}
              and args-violations soft-passes. When the artefact IS on
              the classpath (the common case in this repo's tests), the
              validator publishes truthy hooks — both shapes are
              acceptable, the panel adapts to either"
       (let [{:keys [validate explain]} (rf.story.ui.schema-validation/validator-fns)]
         ;; Both fns are either nil (soft-pass) or callable.
         (is (or (nil? validate) (fn? validate)))
         (is (or (nil? explain)  (fn? explain)))))))
