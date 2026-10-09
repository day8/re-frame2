(ns re-frame.security.validation-redaction-invariant-security-cljs-test
  "Class-invariant security tier — uniform `:sensitive?` redaction across
  EVERY framework-side validation-failure emission site.

  ## The class

  A validation-failure emit site that ignores a per-slot `:sensitive?`
  inside its schema ships the failing value verbatim through `:received` /
  `:value` / `:explain` to every trace listener (and onward to off-box
  monitors and the AI/MCP boundary) — a failing login payload would carry
  the password. It is the same defect class as a `:sensitive?` slot nested
  under `:set` / `:and` / `:or` / `:multi`, and guarding one site does not
  guard the others: each of these builds its own failure tags, so each
  must consult the schema's `:sensitive?` declaration itself:

    - `:where :event`         (re-frame.schemas/validate-event!)
    - `:where :machine-data`  (re-frame.machines.data-validation)
    - `:where :sub-override`  (re-frame.subs — a `:sub-overrides` pin that
                               fails the sub's own output schema; bypasses
                               `validate-sub!`)
    - `:where :flow-output`   (re-frame.flows)

  ## The invariant

  EVERY `:rf.error/schema-validation-failure` the framework emits MUST route
  its value-bearing slots through the ONE shared schema-aware redactor
  (`re-frame.schemas/redact-validation-tags`, reached off-namespace via the
  `:schemas/redact-validation-tags` late-bind hook). When the failing slot's
  schema declares ANY `:sensitive?` slot, the value-bearing slots
  (`:value` / `:received` / `:explain` / `:explain-humanized` / `:rf.fx/args`
  / `:rf.sub/query-v`) MUST be scrubbed to `:rf/redacted` and the trace
  stamped `:sensitive? true`. Per Spec 010 §`:sensitive?` — privacy in
  schema-validation error traces (the §Uniform across every validation
  `:where` surface invariant).

  This namespace fails RED if ANY validation kind ships an un-redacted
  value-bearing slot for a `:sensitive?`-marked surface. A new emit site
  that forgets the seam trips this gate.

  ## Why property-style + a per-kind corpus

  A unique unguessable SENTINEL is planted at a `:sensitive?` slot of a
  schema, a type failure is forced there, and the test asserts the sentinel
  NEVER appears anywhere in the emitted trace (deep-walked) — across BOTH a
  fuzzer (arbitrary collection/map nestings of the sensitive slot) AND a
  fixed per-kind corpus that drives each named validation surface. One
  escaped surface = one leak.

  ## Net property

  An emit site that emits the raw `:value` / `:received` / `:explain`
  without routing through `redact-validation-tags` makes that kind's corpus
  assertion go RED — the sentinel surfaces unredacted."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Publishes the Malli late-bind validate/explain hooks; without
            ;; it the default validator soft-passes and no failure fires.
            [re-frame.schemas.malli]
            [re-frame.schemas :as rf.schemas]
            [re-frame.machines.data-validation :as rf.machines.data-validation]
            ;; Publishes the `:flows/run-flows-on-db` late-bind hook, so the
            ;; router's flows-after-interceptor runs the flow transform on
            ;; `dispatch-sync`.
            [re-frame.flows]
            ;; Used only by the CLJS-only sub-override driver below.
            #?(:cljs [re-frame.late-bind :as rf.late-bind])
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            #?(:clj  [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support :refer-macros [with-trace-recorder!]])
            [re-frame.security.gen :as rf.security.gen]))

;; Reset per-test so app-schema registrations don't bleed across cases. App
;; schemas are frame-local, so the body runs with `:rf/default` bound as the
;; carried scope; the production-path drivers stand up their own substrate.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture)
  (fn [test-fn]
    (binding [rf.frame/*current-frame* :rf/default]
      (test-fn))))

(def ^:private sentinel "S3CR3T-rf2-o69h5-VALIDATION-DO-NOT-LEAK")

(defn- contains-sentinel?
  [x]
  (rf.security.gen/contains-string? x sentinel))

(defn- capture-op
  "Run `f` with a trace listener attached; return the first trace event whose
  `:operation` is `op` (or nil if none fired)."
  [op f]
  (with-trace-recorder! [traces]
    (f)
    #?(:clj (rf.schemas/clear-sensitive-paths-cache!))
    (first (filter #(= op (:operation %)) @traces))))

(defn- capture-failure
  [f]
  (capture-op :rf.error/schema-validation-failure f))

(defn- redaction
  "`[:sensitive? sentinel-present? & the named :tags slots]` read off `trace`."
  [trace & slots]
  (into [(:sensitive? trace) (contains-sentinel? trace)]
        (map #(get-in trace [:tags %]) slots)))

(def ^:private sensitive-map-schema
  [:map [:secret {:sensitive? true} :string]])

(def ^:private failing-sensitive-value
  ;; A vector where a :string is required, carrying the sentinel.
  {:secret [sentinel]})

;; ---------------------------------------------------------------------------
;; CONFORMING SENSITIVE SIBLING: the secret rides at a slot that CONFORMS while
;; a different, non-sensitive slot FAILS. The whole-payload slots (`:value` /
;; `:received` / `:explain`) carry the whole checked value, so a leaf-precise
;; decision keyed on the failing slot would leak the conforming secret; those
;; slots must redact under the ROOT check. Event, fx and sub validation share
;; one decision in `run-validation`; app-db and machine-data validation each
;; make their own.
;; ---------------------------------------------------------------------------

(def ^:private sibling-sensitive-map-schema
  [:map
   [:jwt   {:sensitive? true} :string]
   [:count :int]])

(def ^:private failing-sibling-value
  {:jwt sentinel :count "not-an-int"})

(deftest event-validation-redacts-conforming-sensitive-sibling
  (let [trace (capture-failure
                #(rf.schemas/validate-event!
                   :auth/profile [:auth/profile {:password sentinel :age "old"}]
                   {:schema [:cat [:= :auth/profile]
                             [:map [:password {:sensitive? true} :string] [:age :int]]]}))]
    (is (= [true false :rf/redacted :rf/redacted :rf/redacted]
           (redaction trace :received :value :explain)))))

(deftest app-db-validation-redacts-conforming-sensitive-sibling-whole-explain
  ;; The narrowed :value is the failing non-sensitive :count leaf and rides
  ;; verbatim; the whole-payload :explain carries the conforming :jwt.
  (rf/reg-app-schema [:root] sibling-sensitive-map-schema)
  (let [trace (capture-failure
                #(rf.schemas/validate-app-schema!
                   {:root failing-sibling-value} :root/bad))]
    (is (= [true false "not-an-int" :rf/redacted]
           (redaction trace :value :explain)))))

(deftest machine-data-validation-redacts-conforming-sensitive-sibling
  (let [trace (capture-failure
                #(rf.machines.data-validation/validate-snapshot-data!
                   :my/machine {:data failing-sibling-value}
                   sibling-sensitive-map-schema :macrostep))]
    (is (= [true false :rf/redacted :rf/redacted]
           (redaction trace :value :explain)))))

;; ---------------------------------------------------------------------------
;; PRODUCTION PATHS. Each driver registers a real flow, cofx or sub-override on
;; a `:sensitive?`-marked schema and drives the actual cascade far enough to
;; emit, so a refactor of the emit site that drops the redaction, adds an
;; unlisted value-bearing slot, or loses the stamp goes red.
;; ---------------------------------------------------------------------------

(defn- with-runtime*
  "Install the plain-atom substrate and the `:rf/default` frame, which the
  drain and subscribe paths need, around `thunk`. Setup failures propagate:
  a security invariant asserted against a runtime that failed to stand up is
  worth nothing. The outer fixture owns teardown."
  [thunk]
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (thunk))

(defn- thrown-by
  "Call `f`; return the ExceptionInfo it throws, or nil."
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      e)))

(deftest flow-output-production-path-redacts-sensitive
  (with-runtime*
    (fn []
      ;; The flow copies the sentinel-bearing input to its output path, where
      ;; it fails the :sensitive? output schema.
      (rf/reg-event :flow/seed (fn [_ _] {:db {:in failing-sensitive-value}}))
      (rf/reg-flow :flow/secret {:inputs [[:in]] :output-path [:derived] :schema sensitive-map-schema} (fn [in] in))
      (let [trace (capture-failure #(rf/dispatch-sync [:flow/seed]))]
        (is (= [true false :flow-output :rf/redacted :rf/redacted]
               (redaction trace :where :value :explain)))))))

(deftest recordable-cofx-schema-failure-production-path-redacts-sensitive
  ;; A supplied recordable value that fails its :sensitive?-marked schema.
  ;; Both the trace and the thrown ex-data are off-box egress.
  (with-runtime*
    (fn []
      (rf/reg-cofx :cofx/recordable-secret
        {:recordable? true
         :schema      [:map [:token {:sensitive? true} :string]]}
        (fn [] {:token "ignored-supplied-wins"}))
      (rf/reg-event :cofx/uses-recordable-secret
        {:rf.cofx/requires [:cofx/recordable-secret]}
        (fn [_ _] {}))
      (let [thrown (atom nil)
            trace  (capture-op
                     :rf.error/cofx-value-invalid
                     #(reset! thrown (thrown-by
                                       (fn []
                                         (rf/dispatch-sync
                                           [:cofx/uses-recordable-secret]
                                           {:rf.cofx {:cofx/recordable-secret {:token [sentinel]}}})))))
            d      (ex-data @thrown)]
        (is (= [true false :rf/redacted :rf/redacted]
               (redaction trace :value :explain)))
        (is (= [:rf.error/cofx-value-invalid true :rf/redacted :rf/redacted false]
               [(:rf.error/id d) (:sensitive? d) (:value d) (:explain d) (contains-sentinel? d)]))))))

(deftest generated-recordable-cofx-schema-failure-no-pre-validation-leak
  ;; A generated value that fails its :sensitive?-marked schema. The
  ;; `:rf.cofx/generated` trace carries the raw value and the marks projection
  ;; does not cover schema slots, so `run-generator` must validate before it
  ;; emits: the throw aborts before any trace sees the value.
  (with-runtime*
    (fn []
      (rf/reg-cofx :cofx/generated-secret
        {:recordable? true
         :schema      [:map [:token {:sensitive? true} :string]]}
        (fn [] {:token [sentinel]}))
      (rf/reg-event :cofx/uses-generated-secret
        {:rf.cofx/requires [:cofx/generated-secret]}
        (fn [_ _] {}))
      (with-trace-recorder! [all]
        (let [d   (ex-data (thrown-by #(rf/dispatch-sync [:cofx/uses-generated-secret])))
              inv (first (filter #(= :rf.error/cofx-value-invalid (:operation %)) @all))]
          (is (= [true :rf/redacted] [(:sensitive? inv) (-> inv :tags :value)]))
          (is (= [:rf.error/cofx-value-invalid :rf/redacted] [(:rf.error/id d) (:value d)]))
          (is (not (contains-sentinel? [@all d]))
              "the generated sentinel appears nowhere in the trace stream or the throw"))))))

#?(:cljs
   (deftest sub-override-production-path-redacts-sensitive
     ;; A :sub-overrides hit pinning a value that fails the sub's
     ;; :sensitive?-marked output schema, through the real subscribe ->
     ;; resolve-sub-override -> validate-sub-override! path, a CLJS-only seam.
     (with-runtime*
       (fn []
         (rf/reg-sub :sub/secret {:schema sensitive-map-schema}
                     (fn [_db _] {:secret "ok"}))
         ;; The resolver the Story carriage publishes: an exact-query-vector
         ;; HIT returns `[value]`.
         (rf.late-bind/set-fn! :subs/resolve-sub-override
           (fn [query-v]
             (when (= query-v [:sub/secret])
               [failing-sensitive-value])))
         (try
           (let [trace (capture-failure #(deref (rf/subscribe [:sub/secret])))]
             (is (= [true false :sub-override :rf/redacted :rf/redacted :rf/redacted]
                    (redaction trace :where :value :received :rf.sub/query-v))))
           (finally
             (rf.late-bind/set-fn! :subs/resolve-sub-override nil)))))))

;; ---------------------------------------------------------------------------
;; PROPERTY: across arbitrary collection/map nestings of a sensitive slot, no
;; callable validation kind leaks the sentinel. The nested-shape generator is
;; shared with the schema-redaction suite; this suite's own arm order and 1..5
;; depth fix its draws, so a failing draw reproduces from its seed.
;; ---------------------------------------------------------------------------

(def ^:private gen-nested-sensitive
  (rf.security.gen/nested-sensitive-generator
    sentinel
    [:map :vector :sequential :map-of :map-of-key :tuple :set :and :or :multi :orn]
    5))

(defn- all-kinds-redact?
  "Run every callable validation kind on a `[schema value]` draw; true iff each
  emitted a stamped trace carrying no sentinel."
  [[schema value]]
  (every? (fn [validate!]
            (let [trace (capture-failure validate!)]
              (and (true? (:sensitive? trace))
                   (not (contains-sentinel? trace)))))
          [#(rf.schemas/validate-event! :gen/id [:gen/id value] {:schema [:cat [:= :gen/id] schema]})
           #(rf.schemas/validate-fx! :gen/fx :gen/ev value {:schema schema})
           #(rf.schemas/validate-sub! :gen/sub [:gen/sub sentinel] value {:schema schema})
           #(rf.machines.data-validation/validate-snapshot-data!
              :gen/machine {:data value} schema :macrostep)]))

(deftest every-kind-redacts-at-arbitrary-nesting
  (let [result (rf.security.gen/for-all gen-nested-sensitive 120 17 all-kinds-redact?)]
    (is (nil? result)
        (str "a validation kind leaked the sensitive sentinel (or missed the "
             ":sensitive? stamp) for a generated nesting: "
             (pr-str (when result (dissoc result :threw)))))))

(deftest non-sensitive-failure-not-over-redacted
  ;; The stamp lives on the envelope: the trace builder strips `:sensitive?`
  ;; from `:tags` on every trace.
  (let [plain-schema  [:map [:n :int]]
        plain-value   {:n "not-an-int"}
        event-trace   (capture-failure
                        #(rf.schemas/validate-event!
                           :plain/ev [:plain/ev plain-value]
                           {:schema [:cat [:= :plain/ev] plain-schema]}))
        machine-trace (capture-failure
                        #(rf.machines.data-validation/validate-snapshot-data!
                           :plain/machine {:data plain-value} plain-schema :macrostep))]
    (is (= [false [:plain/ev plain-value] plain-value]
           [(contains? event-trace :sensitive?)
            (-> event-trace :tags :value)
            (-> machine-trace :tags :value)]))))
