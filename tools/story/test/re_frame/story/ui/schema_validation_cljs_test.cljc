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
            #?@(:cljs [[re-frame.core             :as rf]
                       [re-frame.frame            :as frame]
                       [re-frame.registrar        :as registrar]
                       [re-frame.substrate.plain-atom :as plain-atom]
                       [re-frame.story            :as story]
                       [re-frame.story.ui.panels  :as panels]])
            [re-frame.story.ui.schema-validation :as sv]))

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
   :tags      {:dispatch-id (+ 1000 id)}})

;; ---- pure: schema-validation-event? classification ----------------------

(deftest schema-validation-event?-classifies-failures
  (testing ":rf.error/schema-validation-failure trace events qualify"
    (is (sv/schema-validation-event? (failure-event 1 :event)))
    (is (sv/schema-validation-event? (failure-event 2 :sub-return)))
    (is (sv/schema-validation-event? (failure-event 3 :app-db)))
    (is (sv/schema-validation-event? (failure-event 4 :cofx)))
    (is (sv/schema-validation-event? (failure-event 5 :fx-args)))))

(deftest schema-validation-event?-rejects-unrelated
  (testing "non-error trace events and unrelated error categories are filtered out"
    (is (not (sv/schema-validation-event?
               (unrelated-trace-event 10 :event :event/dispatched))))
    (is (not (sv/schema-validation-event?
               (unrelated-trace-event 11 :error :rf.error/handler-exception))))
    (is (not (sv/schema-validation-event?
               (unrelated-trace-event 12 :error :rf.error/no-such-sub))))
    (is (not (sv/schema-validation-event?
               (unrelated-trace-event 13 :warning :rf.fx/skipped-on-platform))))))

(deftest schema-validation-event?-rejects-malformed
  (testing "empty / partial trace events do not classify"
    (is (not (sv/schema-validation-event? {})))
    (is (not (sv/schema-validation-event? {:op-type :error})))
    (is (not (sv/schema-validation-event?
               {:op-type :error :operation :rf.error/something-else})))))

;; ---- pure: project-failure ----------------------------------------------

(deftest project-failure-event-row
  (testing "a :where :event failure projects the event-id under :failing-id"
    (let [ev  (failure-event 1 :event {:event-id  :auth/login
                                       :received  [:auth/login {}]
                                       :explain   {:errors [{:path [:email] :message "missing"}]}})
          row (sv/project-failure ev)]
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
          row (sv/project-failure ev)]
      (is (= :sub-return             (:where row)))
      (is (= :pending-todos          (:failing-id row)))
      (is (= :replaced-with-default  (:recovery row))))))

(deftest project-failure-app-db-row
  (testing "a :where :app-db failure projects the path and the failing-id is nil"
    (let [ev  (failure-event 3 :app-db {:path     [:user :credentials]
                                        :received {:no-id true}})
          row (sv/project-failure ev)]
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
          row (sv/project-failure ev)]
      (is (= :cofx                   (:where row)))
      (is (= :foo/bar                (:failing-id row))))))

(deftest project-failure-falls-back-to-id-when-no-explicit-failing-id
  (testing "when an emission shape omits :failing-id, the projector
            falls back through event-id → sub-id → cofx-id → fx-id"
    (let [row (sv/project-failure (failure-event 50 :cofx {:cofx-id :now}))]
      (is (= :now (:failing-id row))))))

(deftest project-failure-fx-args-row
  (testing "a :where :fx-args failure projects the fx-id under :failing-id"
    (let [ev  (failure-event 5 :fx-args {:fx-id :http-xhrio
                                         :received {:url 7}})
          row (sv/project-failure ev)]
      (is (= :fx-args                (:where row)))
      (is (= :http-xhrio             (:failing-id row))))))

;; ---- pure: project-failures filter+project ------------------------------

(deftest project-failures-filters-and-projects
  (testing "a mixed buffer returns only schema-failure rows, projected,
            in input order"
    (let [buf  [(failure-event 1 :event {:event-id :foo/bar})
                (unrelated-trace-event 2 :event :event/dispatched)
                (unrelated-trace-event 3 :sub/run :sub/run)
                (failure-event 4 :app-db {:path [:user]})
                (unrelated-trace-event 5 :view :view/render)
                (failure-event 6 :sub-return {:sub-id :pending-todos})]
          rows (sv/project-failures buf)]
      (is (= 3 (count rows)))
      (is (= [1 4 6]              (map :id rows)))
      (is (= [:event :app-db :sub-return] (map :where rows)))
      (is (= [:foo/bar nil :pending-todos] (map :failing-id rows))))))

(deftest project-failures-empty
  (testing "empty / all-unrelated buffers project to empty vector"
    (is (= [] (sv/project-failures [])))
    (is (= [] (sv/project-failures
                [(unrelated-trace-event 1 :event :event/dispatched)
                 (unrelated-trace-event 2 :error :rf.error/handler-exception)])))))

;; ---- pure: map-schema? + map-entries ------------------------------------

(deftest map-schema?-classifies-map-shapes
  (testing "[:map ...] vectors are recognised; everything else is not"
    (is (sv/map-schema? [:map [:a :string]]))
    (is (sv/map-schema? [:map {:closed true} [:a :string]]))
    (is (not (sv/map-schema? [:vector :string])))
    (is (not (sv/map-schema? :string)))
    (is (not (sv/map-schema? nil)))
    (is (not (sv/map-schema? {})))
    (is (not (sv/map-schema? [:enum :a :b])))))

(deftest map-entries-projects-pairs
  (testing "map-entries returns [k child-schema] pairs in declared order,
            skipping the optional properties map at index 1"
    (is (= [[:a :string] [:b :int]]
           (sv/map-entries [:map [:a :string] [:b :int]])))
    (is (= [[:a :string] [:b :int]]
           (sv/map-entries [:map {:closed true} [:a :string] [:b :int]])))
    (is (nil? (sv/map-entries [:vector :string]))))
  (testing "Malli map-entry with per-entry properties — `[k {:optional true} schema]`
            — projects to the child schema (the third element)"
    (is (= [[:a :string]]
           (sv/map-entries [:map [:a {:optional true} :string]])))))

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
    (is (= [] (sv/args-violations {:a 7 :b "x"}
                                  [:map [:a :string] [:b :int]]
                                  {:validate nil :explain nil})))))

(deftest args-violations-no-schema-passes
  (testing "no schema on file → nothing to validate; empty vector"
    (is (= [] (sv/args-violations {:a 7}
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
          viols  (sv/args-violations args schema
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
    (is (= [] (sv/args-violations {:name "alice" :age 30}
                                  [:map [:name :string] [:age :int]]
                                  {:validate string-validator
                                   :explain  string-explainer})))))

(deftest args-violations-skips-missing-keys-when-validator-accepts
  (testing "a missing arg key passes through to the validator as nil;
            a validator that accepts nil records no violation, one
            that rejects nil records a violation. The walker doesn't
            short-circuit on missing keys — it routes through the
            registered validator like the framework does"
    (let [args   {:name "alice"}    ;; :age missing
          schema [:map [:name :string] [:age :int]]
          viols  (sv/args-violations args schema
                                     {:validate string-validator
                                      :explain  string-explainer})]
      (is (= 1 (count viols)))
      (is (= :age (-> viols first :key)))
      (is (nil?   (-> viols first :value))))))

(deftest args-violations-non-map-schema-root-violation
  (testing "a non-:map top-level schema validates the whole args
            against the schema; failure surfaces under ::root"
    ;; string-validator treats `:string` as 'must be a string'; the
    ;; whole args map is not a string → violation.
    (let [viols (sv/args-violations {:name "x"} :string
                                    {:validate string-validator
                                     :explain  string-explainer})]
      (is (= 1 (count viols)))
      (is (= ::sv/root (-> viols first :key)))
      (is (= {:name "x"} (-> viols first :value))))))

(deftest args-violations-non-map-schema-passing
  (testing "a non-:map top-level schema that conforms emits no violation"
    ;; A truthy validator accepts every value, so the whole-args check
    ;; passes.
    (is (= [] (sv/args-violations {:any "x"} :something
                                  {:validate truthy-validator
                                   :explain  string-explainer})))))

;; ---- pure: format-explain -----------------------------------------------

(deftest format-explain-nil
  (testing "nil renders as the empty string so the renderer can
            interpolate it safely"
    (is (= "" (sv/format-explain nil)))))

(deftest format-explain-malli-shape
  (testing "a Malli explain map (`{:errors [...]}`) flattens to a
            joined path-and-message string"
    (let [explanation {:errors [{:path [:email] :message "missing"}
                                {:path []       :message "bad"}]}
          out         (sv/format-explain explanation)]
      (is (string? out))
      (is (re-find #":email" out))
      (is (re-find #"missing" out))
      (is (re-find #"\(root\)" out))
      (is (re-find #"bad" out)))))

(deftest format-explain-falls-back-to-pr-str
  (testing "explanations that aren't Malli-shaped fall back to pr-str"
    (is (= "\"a plain string\"" (sv/format-explain "a plain string")))
    (is (= ":a-keyword"         (sv/format-explain :a-keyword)))))

;; ---- CLJS-only: panel registration --------------------------------------

#?(:cljs
   (defn- reset-all! []
     (story/clear-all!)
     (registrar/clear-all!)
     (reset! frame/frames {})
     (try (rf/init! plain-atom/adapter) (catch :default _ nil))
     (story/install-canonical-vocabulary!)
     (frame/ensure-default-frame!)))

#?(:cljs
   (deftest ^:cljs panel-id-stable
     (testing "panel-id is :rf.story.panel/schema-validation per the
              SOTA audit's reservation"
       (is (= :rf.story.panel/schema-validation sv/panel-id)))))

#?(:cljs
   (deftest ^:cljs panel-renders-against-canonical-bootstrap
     (testing "after install-canonical-vocabulary! the schema-validation
              panel is registered against the story side-table"
       (reset-all!)
       (let [ps (story/registrations :story-panel)]
         (is (contains? ps sv/panel-id))
         (let [body (story/handler-meta :story-panel sv/panel-id)]
           (is (= :right                  (:placement body)))
           (is (= sv/panel-render-id      (:render body)))
           (is (re-find #"(?i)schema"     (or (:title body) ""))))))))

#?(:cljs
   (deftest ^:cljs panel-render-view-registered
     (testing "the panel-render view is registered against the framework
              view registry so the late-bind lookup in re-frame.core/view
              finds it"
       (reset-all!)
       (is (some? (rf/view sv/panel-render-id))))))

#?(:cljs
   (deftest ^:cljs panel-renders-hiccup
     (testing "the panel render fn returns a hiccup vector for an
              unregistered variant id (graceful empty state)"
       (reset-all!)
       (let [out ((sv/panel :story.unknown/y) :story.unknown/y)]
         (is (vector? out))
         (is (= :div (first out)))))))

#?(:cljs
   (deftest ^:cljs panel-shows-up-in-right-placement
     (testing "render-panels-at-placement :right picks up the schema
              validation panel"
       (reset-all!)
       (let [out (panels/render-panels-at-placement
                   :right :story.x/y {})]
         ;; The host returns a hiccup vector with one entry per visible
         ;; panel; assert the schema-validation slot is among the entries
         ;; by serialising and looking for the panel id.
         (is (vector? out))
         (is (re-find (re-pattern (str sv/panel-id))
                      (pr-str out)))))))

#?(:cljs
   (deftest ^:cljs validator-fns-defaults-to-nil-without-schemas-artefact
     (testing "when re-frame.schemas hasn't published its hooks, the
              validator-fns lookup returns {:validate nil :explain nil}
              and args-violations soft-passes. When the artefact IS on
              the classpath (the common case in this repo's tests), the
              validator publishes truthy hooks — both shapes are
              acceptable, the panel adapts to either"
       (let [{:keys [validate explain]} (sv/validator-fns)]
         ;; Both fns are either nil (soft-pass) or callable.
         (is (or (nil? validate) (fn? validate)))
         (is (or (nil? explain)  (fn? explain)))))))
