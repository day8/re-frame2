(ns re-frame.hicasso.genspike-cljs-test
  "THE SCHEMA-DRIVEN GENERATIVE SPIKE — a bounded pilot (rf2-hic-057).

  Product specification §11 lists *schema-driven generators* as a SPIKE
  with a pre-registered deciding rule: **graduate only if they find real
  defects or refusal gaps and shrink usefully**. This file is the whole
  experiment, and it is written to be READ as evidence rather than kept
  as a fixture — it is a spike, not a product surface, and nothing here
  is `re-frame.hicasso.test`'s business unless the verdict is graduate.

  ## What is derived, and from what

  Two generators, neither of them hand-written:

  1. `state-gen` — `malli.generator/generator` applied to the schema the
     slice app REGISTERED, read back out of the registry with
     `re-frame.schemas/app-schema-at`. Nothing about the shape of a valid
     app-db is restated here; the registration is the single source.
  2. `intent-gen` / `intents-gen` — one generator per slice event,
     derived from the `:schema` each `rf/reg-event` registered, read back
     with `rf/handler-meta`. A sequence generator is `gen/vector` over
     `gen/one-of` of those.

  ## The three properties

  - [[p-render]] — every schema-valid state renders through the L2 kit
    and the tree it answers agrees with the pure model: one row per
    visible todo, in order, carrying that todo's text, and the alert
    region present exactly when the state carries an error.
  - [[p-preserves-schema]] — folding any schema-valid intent SEQUENCE
    through the registered reducers, from any schema-valid start state,
    lands on a state the SAME registered schema still accepts.
  - [[p-sabotage]] — the identical property against a deliberately broken
    reducer. It must go RED, and its counterexample is what the spike
    measures shrinking on. A property suite with no sabotage control
    cannot tell a passing invariant from an invariant that cannot fail.

  ## Runtime

  Node lane. Nothing here mounts, no React element is created and no hook
  runs: [[re-frame.hicasso.test/tree]] runs one hook-free body under
  injected read fixtures on its own probe frame, which is why the whole
  pilot is a pure function of a generated value and can be run thousands
  of times in a test.

  `org.clojure/test.check` needed no dependency edit: Malli carries it
  (1.1.3, transitively through `metosin/malli` in
  `implementation/shadow-cljs.edn`), so `malli.generator` and
  `clojure.test.check` are already on this lane's classpath."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop :include-macros true]
            [malli.core :as m]
            [malli.generator :as mg]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.test :as ht]
            [re-frame.schemas :as rfs]))

;; ---------------------------------------------------------------------------
;; The slice app
;; ---------------------------------------------------------------------------
;;
;; Phase 2's "one lovable vertical slice" does not exist in-tree yet, so the
;; pilot carries the smallest app of that SHAPE: a keyed list, a filter, a
;; draft field, an error region and a reset. It is deliberately small — the
;; spike measures the generators, and a bigger app would only make the
;; counterexamples harder to read, which is the thing under measurement.

(def ^:private frame-id ::slice)

(def ^:private Todo
  [:map {:closed true}
   [:id   [:int {:min 0 :max 20}]]
   [:text [:string {:min 1 :max 6}]]
   [:done :boolean]])

(def ^:private Slice
  [:map {:closed true}
   [:todos  [:vector {:max 5} Todo]]
   [:filter [:enum :all :active :done]]
   [:draft  [:string {:max 6}]]
   [:error  [:maybe [:string {:min 1 :max 10}]]]])

;; The registration. Everything downstream reads the registry, never `Slice`.
(rfs/reg-app-schema [:slice] {:frame frame-id} Slice)

;; --- the pure model ---------------------------------------------------------

(defn- visible
  "The rows a given slice shows. The model the view is asserted against,
  and the same function the `:slice/visible` subscription answers with."
  [{:keys [todos filter]}]
  (case filter
    :all    (vec todos)
    :active (filterv (complement :done) todos)
    :done   (filterv :done todos)))

(defn- next-id [{:keys [todos]}]
  (if (seq todos) (inc (apply max (map :id todos))) 0))

;; --- the reducers, which are also the registered event handlers -------------

(defn- add-todo [slice [_ text]]
  (if (re-matches #"\s*" text)
    (assoc slice :error "empty todo")
    (-> slice
        (update :todos conj {:id (next-id slice) :text text :done false})
        (assoc :error nil))))

(defn- toggle [slice [_ id]]
  (update slice :todos
          (fn [ts] (mapv #(cond-> % (= id (:id %)) (update :done not)) ts))))

(defn- remove-todo [slice [_ id]]
  (update slice :todos (fn [ts] (filterv #(not= id (:id %)) ts))))

(defn- set-filter [slice [_ f]] (assoc slice :filter f))
(defn- edit-draft [slice [_ text]] (assoc slice :draft text))
(defn- reset-slice [_ _] {:todos [] :filter :all :draft "" :error nil})

;; The registrations. Each carries the event `:schema` Spec 010 §Schemas as a
;; tooling and agent surface makes queryable — and it is those schemas, not
;; these vars, that `intent-gen` reads.

(rf/reg-event :slice/add
  {:schema [:cat [:= :slice/add] [:string {:max 6}]]}
  (fn [{:keys [db]} ev] {:db (update db :slice add-todo ev)}))

(rf/reg-event :slice/toggle
  {:schema [:cat [:= :slice/toggle] [:int {:min 0 :max 20}]]}
  (fn [{:keys [db]} ev] {:db (update db :slice toggle ev)}))

(rf/reg-event :slice/remove
  {:schema [:cat [:= :slice/remove] [:int {:min 0 :max 20}]]}
  (fn [{:keys [db]} ev] {:db (update db :slice remove-todo ev)}))

(rf/reg-event :slice/set-filter
  {:schema [:cat [:= :slice/set-filter] [:enum :all :active :done]]}
  (fn [{:keys [db]} ev] {:db (update db :slice set-filter ev)}))

(rf/reg-event :slice/edit-draft
  {:schema [:cat [:= :slice/edit-draft] [:string {:max 6}]]}
  (fn [{:keys [db]} ev] {:db (update db :slice edit-draft ev)}))

(rf/reg-event :slice/reset
  {:schema [:cat [:= :slice/reset]]}
  (fn [{:keys [db]} ev] {:db (update db :slice reset-slice ev)}))

(def ^:private reducers
  "id → the pure `(slice, event) -> slice` the registration wraps. The fold
  in [[p-preserves-schema]] runs these rather than the router, deliberately:
  the property is about the reducers' invariant preservation, and a spike
  that also had to seat a live frame per trial could not run 200 trials."
  {:slice/add        add-todo
   :slice/toggle     toggle
   :slice/remove     remove-todo
   :slice/set-filter set-filter
   :slice/edit-draft edit-draft
   :slice/reset      reset-slice})

(rf/reg-sub :slice/visible (fn [db _] (visible (:slice db))))
(rf/reg-sub :slice/draft   (fn [db _] (:draft (:slice db))))
(rf/reg-sub :slice/error   (fn [db _] (:error (:slice db))))

;; --- the view ---------------------------------------------------------------

(h/defview slice-list
  "One hook-free body: the keyed list, the controlled draft field and the
  error region. Every dynamic value arrives through a subscription, so L2
  can drive it entirely from injected read fixtures."
  []
  (let [items (h/sub [:slice/visible])
        draft (h/sub [:slice/draft])
        error (h/sub [:slice/error])]
    [:div
     [:input {:type      "text"
              :value     draft
              :on-change [:slice/edit-draft :re-frame.hicasso/value]}]
     (when error [:p {:role "alert"} error])
     [:ul
      (for [t items]
        [:li {:key (:id t) :on-click [:slice/toggle (:id t)]}
         (:text t)])]]))

(defn- fixtures
  "The read fixtures a slice implies — computed with the SAME pure model
  the subscriptions delegate to, so a disagreement between view and model
  is a real disagreement and not a second transcription of the model."
  [slice]
  {[:slice/visible] (visible slice)
   [:slice/draft]   (:draft slice)
   [:slice/error]   (:error slice)})

;; ---------------------------------------------------------------------------
;; The generators — derived, not written
;; ---------------------------------------------------------------------------

(def ^:private registered-slice-schema
  (rfs/app-schema-at [:slice] frame-id))

(def ^:private state-gen
  "Valid app-db slice states, straight off the registered schema."
  (mg/generator registered-slice-schema))

(def ^:private intent-gen
  "One event vector per registered slice event, off that event's own
  registered `:schema`.

  `(gen/fmap vec …)` is not decoration. Spec 010 teaches event schemas in
  the `:cat` spelling, and `:cat` is a SEQUENCE schema: it VALIDATES an
  event vector but GENERATES a seq. A generator derived from the
  registered schema therefore does not produce the thing the schema
  describes without a coercion the schema does not express."
  (gen/one-of
    (for [id (keys reducers)]
      (gen/fmap vec (mg/generator (:schema (rf/handler-meta :event id)))))))

(def ^:private intents-gen (gen/vector intent-gen 0 6))

;; ---------------------------------------------------------------------------
;; The properties
;; ---------------------------------------------------------------------------

(defn- rendered-rows
  "The `:li` nodes the tree carries, in document order."
  [tree]
  (ht/find-all tree :li))

(def ^:private p-render
  "Every schema-valid state renders, and the tree agrees with the model."
  (prop/for-all [slice state-gen]
    (let [t     (ht/tree [slice-list] {:subs (fixtures slice)})
          rows  (rendered-rows t)
          model (visible slice)]
      (and (= (count model) (count rows))
           (= (mapv :text model) (mapv ht/text rows))
           (= (some? (:error slice))
              (boolean (seq (ht/find-all t :p))))
           ;; The controlled field shows the draft it was given.
           (= (:draft slice)
              (:value (ht/attrs (first (ht/find-all t :input)))))))))

(defn- fold
  "Apply an intent sequence to a slice through the registered reducers."
  [slice intents]
  (reduce (fn [s [id :as ev]] ((reducers id) s ev)) slice intents))

(def ^:private p-preserves-schema
  "Any valid intent sequence, from any valid state, lands on a valid state."
  (prop/for-all [slice   state-gen
                 intents intents-gen]
    (m/validate registered-slice-schema (fold slice intents))))

;; --- the sabotage control ---------------------------------------------------

(defn- broken-toggle
  "`toggle`, with one character wrong: `:done` is written as the string
  \"true\"/\"false\" instead of a boolean. A hand-written corpus that only
  ever toggles a todo it just added will not notice, because the row still
  reads truthy — the registered schema is the only thing that objects."
  [slice [_ id]]
  (update slice :todos
          (fn [ts] (mapv #(cond-> % (= id (:id %)) (assoc :done (str (not (:done %))))) ts))))

(def ^:private sabotaged (assoc reducers :slice/toggle broken-toggle))

(def ^:private p-sabotage
  (prop/for-all [slice   state-gen
                 intents intents-gen]
    (m/validate registered-slice-schema
                (reduce (fn [s [id :as ev]] ((sabotaged id) s ev)) slice intents))))

;; ---------------------------------------------------------------------------
;; The runs
;; ---------------------------------------------------------------------------

(def ^:private trials 200)

(deftest schema-valid-states-render-and-agree-with-the-model
  (let [r (tc/quick-check trials p-render)]
    (is (:pass? r) (pr-str (select-keys r [:seed :smallest :shrunk])))))

(deftest valid-intent-sequences-preserve-the-registered-schema
  (let [r (tc/quick-check trials p-preserves-schema)]
    (is (:pass? r) (pr-str (select-keys r [:seed :smallest :shrunk])))))

(deftest the-sabotage-control-fails-and-shrinks
  (let [r        (tc/quick-check trials p-sabotage)
        smallest (first (:smallest (:shrunk r)))
        found    (second (:smallest (:shrunk r)))]
    (is (false? (:pass? r))
        "the control must go red — a property that cannot fail proves nothing")
    (testing "the counterexample is small enough to read"
      ;; The measurement the deciding rule turns on. A useful shrink lands
      ;; on the MINIMUM structure the defect needs: one todo (the thing to
      ;; toggle) and one intent (the toggle). Anything larger is a shrink
      ;; that did not finish.
      (is (>= 1 (count (:todos smallest)))
          (str "start state shrank to " (pr-str smallest)))
      (is (>= 1 (count found))
          (str "intent sequence shrank to " (pr-str found))))))

;; ---------------------------------------------------------------------------
;; The observation the deciding rule needs, recorded as a test
;; ---------------------------------------------------------------------------

(deftest schema-derived-states-are-wider-than-app-reachable-states
  "NOT a defect claim — the single most transferable finding of the spike,
  pinned so it cannot rot.

  `next-id` makes ids unique in every state the app can REACH, and the
  view keys rows by id. The registered schema does not say so, so the
  generator freely draws states with duplicate ids, and the keyed list
  those states render carries duplicate `:key`s — a React defect the L2
  kit is silent about (its own key complaint is about MISSING keys; the
  duplicate channel is React's `console.error` at L3, per
  `keywarn-dom-cljs-test`).

  A property asserting key-uniqueness would therefore go red, and the red
  would be about the SCHEMA's underspecification rather than about
  Hicasso, the view or the reducers. That is the shape of nearly every
  failure a schema-derived generator produces here, and it is why this
  row measures the rate rather than asserting the invariant."
  (let [states    (gen/sample state-gen 100)
        multi     (filter #(< 1 (count (:todos %))) states)
        dup-keyed (filter (fn [{:keys [todos]}]
                            (not= (count todos) (count (set (map :id todos)))))
                          states)]
    (is (seq multi) "the sample must contain lists long enough to collide")
    ;; No threshold assertion: the number is the finding. It is reported
    ;; through the failure message of a claim that always holds, so the
    ;; spike's own evidence survives in the file rather than only in a log.
    (is (<= 0 (count dup-keyed) (count states))
        (str (count dup-keyed) "/" (count states)
             " schema-valid states carry duplicate todo ids"))))
