(ns re-frame.schemas-conformance-test
  "The schemas artefact's conformance gate: drives every
  `spec/conformance/fixtures/schema-*.edn` fixture, plus
  `error-schema-failure.edn`, through the live runtime and asserts each
  fixture's recorded `:final-app-db` (submap), `:sub-values` and
  `:trace-emissions` (order-preserving partial match, per
  `spec/conformance/README.md` §Fixture lifecycle).

  Per §Capability tagging, a fixture whose `:fixture/capabilities` are not a
  subset of `claimed-capabilities` is reported out-of-claim and does not block
  the suite. The cross-spec fixtures run at their own artefact gates or at
  core's full-corpus gate."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.conformance :as rf.conformance]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Load-bearing beyond its alias: loading the facade is what
            ;; publishes the Malli validate/explain hooks and
            ;; binds core's `reg-app-schema` re-export through late-bind.
            ;; clj-kondo reports the ALIAS unused here; the require is not.
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.subs :as rf.subs]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The corpus at the repo root's `spec/conformance/fixtures/`, anchored to this
  namespace's own source on the classpath rather than the working directory:
  a cwd-relative `../../` resolves above the repo root when the combined
  `implementation/deps.edn` alias runs from `implementation/`, and the corpus
  would then discover nothing."
  (let [res (io/resource "re_frame/schemas_conformance_test.clj")]
    (assert res
            (str "schemas-conformance-test cannot locate its own source on "
                 "the classpath — the schemas test/ dir must be on the test "
                 "classpath for fixture discovery to anchor."))
    (-> (io/file res)        ; .../schemas/test/re_frame/schemas_conformance_test.clj
        .getParentFile       ; .../schemas/test/re_frame
        .getParentFile       ; .../schemas/test
        .getParentFile       ; .../schemas
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

(defn- read-one-form
  "Read `text` as EXACTLY ONE top-level EDN form, or throw. `read-string`
  returns only the FIRST and silently discards the rest, so a fixture whose
  expectation block closes early passes having verified less than it claims.
  Throws rather than returning `:fixture/load-error`, which the
  runner classifies as a SKIP — as silent as the defect. Full rationale on
  `re-frame.conformance-fixtures/read-one-form`."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why
                                    " (see re-frame.conformance-fixtures/read-one-form)")
                               (assoc data :fixture/file fixture-name))))
        rd   (fn []
               (try (edn/read {:eof eof} rdr)
                    (catch Exception e
                      (fail (str "is not readable EDN: " (.getMessage e))
                            {:fixture/reader-error (.getMessage e)}))))
        form (rd)]
    (when (identical? eof form)
      (fail "holds no top-level EDN form" {:fixture/forms 0}))
    (when-not (identical? eof (rd))
      (fail (str "must hold exactly ONE top-level EDN form — a plain read"
                 " returns the first and silently discards the rest")
            {:fixture/forms :more-than-one}))
    form))

(defn- load-fixture
  "Read one EDN fixture verbatim as exactly one top-level form, or throw.
  Schema fixtures carry no auto-resolved `::name` keywords, so no alias
  rewrite applies."
  [file]
  (read-one-form (slurp file) (.getName file)))

(defn- schema-fixture-file?
  "True for `schema-*.edn` (the four Spec 010 validation points) and
  `error-schema-failure.edn` (the same surface from the error-trace angle)."
  [file]
  (let [n (.getName file)]
    (or (and (str/starts-with? n "schema-")
             (str/ends-with? n ".edn"))
        (= n "error-schema-failure.edn"))))

(defn- all-schemas-fixtures
  "Every fixture file relevant to the schemas runner. Returns
  `[[filename fixture] ...]` in stable lex order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter schema-fixture-file?)
       (sort-by #(.getName %))
       (mapv (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- claimed capability + spec-version sets ------------------------------

(def claimed-capabilities
  "The four `:schemas/*` validation points of Spec 010 §Validation order, plus
  the `:core/*` cross-cuts every schema fixture declares. `:schemas/cofx` is
  the recordable-cofx `:schema` path, whose failure emits
  `:rf.error/cofx-value-invalid` and skips the handler."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    :core/trace
    :schemas/runtime
    :schemas/event-payload
    :schemas/cofx
    :schemas/sub-return})

(def claimed-spec-versions
  "Conformance corpus spec versions this runner claims to conform
  against. Matches the core runner's set."
  #{"1.0"})

(defn- runnable-capability-set?
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    (every? claimed-capabilities caps)))

(defn- spec-version-claimed?
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

;; ---- handler realisation --------------------------------------------------
;;
;; The `re-frame.conformance` interpreter (core/src) lifts the corpus's
;; data-shaped handler bodies into native fns. The wiring mirrors the schemas
;; slice of `re-frame.conformance-runner/realise-handlers`.

(defn- adapter-helpers
  "Helper map for `realise-fx-handler`: the frame's app-db and the dispatch
  surface."
  []
  {:read-db!  (fn [frame-id] (rf.frame/frame-app-db-value frame-id))
   ;; EP-0001: write the app-db PARTITION via swap-frame-db! —
   ;; app-db-container is a read-only projection over the one physical
   ;; frame-state container.
   :write-db! (fn [frame-id new-db]
                (rf.frame/swap-frame-db! frame-id (constantly new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- realise-handlers
  "Register every handler the fixture declares (events / subs / cofx /
  fx). App-db schemas are registered separately, by `realise-app-schemas`."
  [fixture]
  (let [hmap          (or (:fixture/handlers fixture) {})
        event-meta    (get-in fixture [:fixture/registry :event] {})
        sub-meta      (get-in fixture [:fixture/registry :sub] {})
        cofx-registry (get-in fixture [:fixture/registry :cofx] {})
        cofx-bodies   (get hmap :cofx)
        helpers       (adapter-helpers)
        ;; cofx that auto-wire onto a consuming event's `:rf.cofx/requires`
        ;; declaration. Stable lex order on cofx-id so the last-write-wins
        ;; outcome is deterministic.
        cofx-by-key
        (->> cofx-registry
             (sort-by key)
             (group-by (fn [[cofx-id _]] (keyword (namespace cofx-id))))
             (reduce-kv (fn [acc k pairs]
                          (assoc acc k (mapv first pairs)))
                        {}))]
    ;; ---- cofx ----------------------------------------------------------
    ;; A `:provided?` recordable fact carries no supplier (its value rides
    ;; the dispatch token's `:rf.cofx` map, and `reg-cofx` rejects
    ;; `:provided?` beside a supplier), so it registers bare.
    (let [all-cofx-ids (into #{} (concat (keys cofx-bodies) (keys cofx-registry)))]
      (doseq [cofx-id all-cofx-ids]
        (let [body (get cofx-bodies cofx-id [[:noop]])
              meta (get cofx-registry cofx-id {})]
          (if (:provided? meta)
            (rf/reg-cofx cofx-id meta)
            (rf/reg-cofx cofx-id meta (rf.conformance/realise-cofx-supplier body))))))
    ;; ---- events --------------------------------------------------------
    ;; A body that reads `[:cofx-key K]` declares the coeffect ids it consumes
    ;; under `:rf.cofx/requires`; `normalize-event-handler` lifts either DSL
    ;; body shape into the single `reg-event` effects-map handler.
    (doseq [[id steps] (:event hmap)]
      (let [handler  (rf.conformance/normalize-event-handler
                       (rf.conformance/realise-event-handler steps))
            meta     (get event-meta id {})
            ks       (rf.conformance/collect-cofx-keys steps)
            cofx-ids (vec (mapcat (fn [k]
                                    (or (get cofx-by-key k)
                                        (when (contains? cofx-registry k) [k])))
                                  ks))
            meta'    (cond-> meta
                       (seq cofx-ids)
                       (assoc :rf.cofx/requires cofx-ids))]
        (if (seq meta')
          (rf/reg-event id meta' handler)
          (rf/reg-event id handler))))
    ;; ---- subs ----------------------------------------------------------
    (doseq [[id steps] (:sub hmap)]
      (let [{:keys [kind inputs body]} (rf.conformance/realise-sub steps)
            meta                       (get sub-meta id {})]
        (case kind
          :layer-1 (if (seq meta) (rf/reg-sub id meta body) (rf/reg-sub id body))
          ;; The fn-form `subs/reg-sub`, because the public `rf/reg-sub`
          ;; is a JVM macro (Spec 001 §Source-coordinate capture); the
          ;; fixture's inputs ride the metadata map as data.
          :layer-2 (rf.subs/reg-sub id (assoc meta :inputs (vec inputs)) body))))
    ;; ---- fxs -----------------------------------------------------------
    (let [fx-bodies   (:fx hmap)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-ids     (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-ids]
        (let [body    (get fx-bodies id [[:noop]])
              meta    (get fx-registry id {})
              handler (rf.conformance/realise-fx-handler id body helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    nil))

(defn- realise-app-schemas
  "Register the fixture's app-db schemas. Called after the runner destroys
  the frame, which drops its schemas, and before `make-frame`, so the
  `:initial-events` commit is validated against them."
  [fixture]
  (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
    (rf/reg-app-schema path schema)))

;; ---- :fixture/dispatches runner ------------------------------------------

(defn- run-dispatch
  "Drive one `:fixture/dispatches` entry. `dispatch-error-failures` is an atom
  collecting `:expect-error` mismatch strings.

  A map entry with `:expect-error` asserts the dispatch RAISES that
  `:rf.error/id`. Context-assembly throws (the cofx delivery errors) escape
  `dispatch-sync` rather than being captured into the interceptor chain, so
  the runner catches the throw here and compares the ex-data `:rf.error/id`.
  The remaining opts (e.g. `:rf.cofx`) pass through to `dispatch-sync`."
  [ev dispatch-error-failures]
  (cond
    (and (map? ev) (contains? ev :expect-error))
    (let [{event :event want :expect-error} ev
          opts (dissoc ev :event :expect-error)
          got  (try (rf/dispatch-sync event opts) ::no-throw
                    (catch clojure.lang.ExceptionInfo e
                      (:rf.error/id (ex-data e)))
                    (catch Throwable e e))]
      (cond
        (= got ::no-throw)
        (swap! dispatch-error-failures conj
               (str "dispatch " (pr-str event) " expected to throw "
                    want " but did not throw"))
        (not= got want)
        (swap! dispatch-error-failures conj
               (str "dispatch " (pr-str event) " expected error " want
                    " but got " (if (instance? Throwable got)
                                  (str "a non-ExceptionInfo throw: "
                                       (some-> ^Throwable got .getMessage))
                                  got)))))

    (map? ev)
    (let [{event :event :as opts} ev]
      (rf/dispatch-sync event (dissoc opts :event)))

    :else
    (rf/dispatch-sync ev)))

;; ---- single-fixture execution -------------------------------------------

(defn- run-fixture
  "Run one fixture; return a result map shaped like the core runner's."
  [fixture]
  (try
    (with-trace-recorder! [traces]
      (let [fid          (:fixture/id fixture)
            _            (realise-handlers fixture)
            frame-config (or (:fixture/frame-config fixture) {})
            ;; `reset-runtime` already created :rf/default WITHOUT any
            ;; :initial-events. `make-frame` against an existing id is a
            ;; surgical update that does NOT re-fire :initial-events (Spec 002).
            ;; Destroy first so the fixture's :initial-events cascade fires
            ;; under its declared frame config.
            _            (rf/destroy-frame! :rf/default)
            _            (realise-app-schemas fixture)
            ;; `reset-runtime` binds `*current-frame*`, and `make-frame` under
            ;; a bound frame queues `:initial-events` asynchronously (Spec 002
            ;; §make-frame from inside a handler), which would land the seed
            ;; after the first dispatch.
            _            (binding [rf.frame/*current-frame* nil]
                           (rf/make-frame (assoc frame-config :id :rf/default)))
            dispatches   (or (:fixture/dispatches fixture) [])
            dispatch-error-failures (atom [])]
        (doseq [ev dispatches]
          (run-dispatch ev dispatch-error-failures))
        (let [expect        (or (:fixture/expect fixture) {})
              expected-db   (:final-app-db expect)
              final-db      (rf/app-db-value :rf/default)
              sub-checks
              (doall
                (for [[query-v expected-val] (or (:sub-values expect) {})]
                  (let [[frame-id qv] (rf.conformance/resolve-sub :rf/default query-v)]
                    {:query    query-v
                     :expected expected-val
                     :actual   (rf/subscribe-once qv {:frame frame-id})})))
              trace-failures (rf.conformance/check-trace-emissions
                               @traces (:trace-emissions expect))]
          {:fixture-id     fid
           :passed?        (and (or (nil? expected-db) (rf.conformance/submap? expected-db final-db))
                                (every? #(= (:expected %) (:actual %)) sub-checks)
                                (empty? trace-failures)
                                (empty? @dispatch-error-failures))
           :dispatch-error-failures @dispatch-error-failures
           :final-db       final-db
           :expected-db    expected-db
           :sub-checks     sub-checks
           :trace-failures trace-failures})))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})))

;; ---- the test entrypoint -------------------------------------------------

(deftest run-schemas-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-schemas-fixtures)]
      ;; No load-error arm: `load-fixture` throws on an unreadable, empty or
      ;; multi-form fixture, which fails the gate rather than skipping.
      (cond
        (not (spec-version-claimed? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :fname        fname
                             :skipped?     true
                             :reason       "spec-version not in claimed set"
                             :spec-version (:fixture/spec-version fixture)})

        (not (runnable-capability-set? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :fname        fname
                             :skipped?     true
                             :reason       "capabilities outside schemas-runner claim"
                             :capabilities (:fixture/capabilities fixture)})

        :else
        (swap! results conj (assoc (run-fixture fixture) :fname fname))))
    (let [all     @results
          run     (remove :skipped? all)
          passed  (filter :passed? run)
          failed  (remove :passed? run)
          skipped (filter :skipped? all)]
      ;; A zero-failure check alone passes over an empty or fully-orphaned
      ;; corpus (a wrong cwd, a fixtures-dir rename, a capability-vocab
      ;; rename), so the runnable set — the four Spec 010 validation points
      ;; plus error-schema-failure — has a floor.
      (is (>= (count run) 4)
          (str "schemas corpus runnable-fixture floor (>= 4): only "
               (count run) " executed — a fixtures-dir/cwd fault or a "
               "capability-vocab rename has orphaned the corpus."))
      ;; Silent-on-success: summary prints only on failure.
      (when (seq failed)
        (println)
        (println "Schemas conformance corpus (schema-*.edn + error-schema-failure.edn):")
        (println "  total fixtures:" (count all))
        (println "  runnable:      " (count run))
        (println "  passed:        " (count passed))
        (println "  failed:        " (count failed))
        (println "  skipped:       " (count skipped))
        (when (seq skipped)
          (println)
          (println "Skipped:")
          (doseq [s skipped]
            (println "  " (:fixture-id s) "—" (:reason s)
                     (or (:capabilities s) (:spec-version s)))))
        (println)
        (println "Failures:")
        (doseq [f failed]
          (println "  " (:fixture-id f))
          (when (:error f)
            (println "    error:" (:error f)))
          (when-let [td (:expected-db f)]
            (when (not= td (:final-db f))
              (println "    expected app-db:" td)
              (println "    actual   app-db:" (:final-db f))))
          (doseq [sc (:sub-checks f)]
            (when (not= (:expected sc) (:actual sc))
              (println "    sub" (:query sc)
                       "expected:" (:expected sc)
                       "actual:" (:actual sc))))
          (doseq [tf (:trace-failures f)]
            (println "    trace:" tf))
          (doseq [df (:dispatch-error-failures f)]
            (println "    dispatch-error:" df))))
      (is (zero? (count failed))
          (str "All claim-runnable schemas conformance fixtures must pass; "
               (count failed) " failed.")))))
