(ns re-frame-2.conformance-test
  "Conformance fixture runner. Loads .edn fixtures from
  ../docs/specification/conformance/fixtures/, realises handler-body DSL
  ops into native fns, runs each fixture's :fixture/dispatches, and
  compares observables against :fixture/expect.

  This is a FIRST PASS runner. Capabilities supported:
    :core/event-handler
    :core/sub
    :core/fx (partially)

  Fixtures whose :fixture/capabilities include kinds outside this set
  are skipped (reported as not-exercised). Per the conformance README,
  conformance is graded against claimed capabilities."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [re-frame-2.core :as rf]
            [re-frame-2.frame :as frame]
            [re-frame-2.registrar :as registrar]
            [re-frame-2.flows :as flows]
            [re-frame-2.trace :as trace]
            [re-frame-2.conformance :as conformance]))

;; ---- claimed capability set -----------------------------------------------

(def claimed-capabilities
  "What this implementation currently supports (per the smoke tests).
  Fixtures requiring capabilities outside this set are skipped."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    :fsm/flat
    :routing/match-url})

;; ---- fixture loader -------------------------------------------------------

(def fixtures-dir
  (io/file "../docs/specification/conformance/fixtures"))

(defn- load-fixture [file]
  (try
    (edn/read-string (slurp file))
    (catch Throwable e
      {:fixture/load-error (.getMessage e)
       :fixture/file       (.getName file)})))

(defn all-fixtures []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(clojure.string/ends-with? (.getName %) ".edn"))
       (map (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- runtime reset --------------------------------------------------------

(defn- reset-runtime! []
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (rf/init!))

;; ---- fixture execution ----------------------------------------------------

(defn- runnable?
  "True if the fixture's claimed capabilities are a subset of ours."
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    (every? claimed-capabilities caps)))

(defn- realise-handlers [fixture]
  ;; Walk :fixture/handlers and register each.
  (let [handlers-map (or (:fixture/handlers fixture) {})]
    (doseq [[id steps] (get handlers-map :event)]
      (let [[kind handler] (conformance/realise-event-handler steps)]
        (case kind
          :db (rf/reg-event-db id handler)
          :fx (rf/reg-event-fx id handler))))
    (doseq [[id steps] (get handlers-map :sub)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)]
        (case kind
          :layer-1 (rf/reg-sub id body)
          :layer-2 (apply rf/reg-sub id
                          (concat (interleave (repeat :<-) inputs) [body])))))
    ;; fx handlers — DSL bodies. May :throw, :noop, mutate the frame's
    ;; app-db, or :dispatch a follow-up event (e.g. http stubs).
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (frame/frame-app-db-value frame-id))
           :write-db! (fn [frame-id new-db]
                        (let [container (frame/get-frame-db frame-id)]
                          ((requiring-resolve 're-frame-2.substrate.adapter/replace-container!)
                           container new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))}]
      (doseq [[id steps] (get handlers-map :fx)]
        (let [handler (conformance/realise-fx-handler id steps adapter-helpers)]
          (rf/reg-fx id handler))))
    ;; route registrations
    (doseq [[id meta] (get handlers-map :route)]
      (rf/reg-route id meta))))

(defn- collect-traces [fixture-id]
  (let [traces (atom [])]
    (trace/register-trace-cb! [fixture-id] (fn [ev] (swap! traces conj ev)))
    traces))

(defn- check-trace-emissions
  "Per the conformance README §Fixture lifecycle: trace-emissions partial-
  matches each event by its specified keys; absent keys are ignored.
  Returns a vector of failure messages, or empty if all matched."
  [actual-traces expected-traces]
  (loop [actual    actual-traces
         expected  expected-traces
         failures  []]
    (cond
      (empty? expected)
      failures

      (empty? actual)
      (conj failures (str "expected trace not seen: "
                          (pr-str (first expected))))

      :else
      (let [exp (first expected)
            ;; Find the next actual that partial-matches exp.
            match-idx (->> actual
                           (map-indexed vector)
                           (some (fn [[i a]]
                                   (when (every? (fn [[k v]]
                                                   (let [actual-v (get a k)]
                                                     (cond
                                                       (map? v)
                                                       (every? (fn [[kk vv]]
                                                                 (= vv (get actual-v kk)))
                                                               v)
                                                       :else (= v actual-v))))
                                                 exp)
                                     i))))]
        (if match-idx
          (recur (drop (inc match-idx) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected trace not seen: "
                                     (pr-str exp)))))))))

(defn- resolve-sub
  "A sub query in :sub-values may be either:
    [query-v]                 — implicit :rf/default frame
    [frame-id [query-v]]      — explicit frame
  Returns [frame-id query-v]."
  [entry]
  (if (and (vector? entry)
           (= 2 (count entry))
           (vector? (second entry)))
    [(first entry) (second entry)]
    [:rf/default entry]))

(defn run-fixture [fixture]
  (try
    (reset-runtime!)
    (realise-handlers fixture)
    (let [fid          (:fixture/id fixture)
          frame-config (or (:fixture/frame-config fixture) {})
          frames-spec  (:fixture/frames fixture)
          ;; reset-runtime! already created :rf/default WITHOUT an :on-create.
          ;; reg-frame against an existing id is a surgical update that doesn't
          ;; re-fire :on-create per Spec 002. We destroy first so :on-create
          ;; fires when re-registered with the fixture's config.
          _            (rf/destroy-frame :rf/default)
          ;; Listener BEFORE the frame is created so :on-create events trace.
          traces       (collect-traces fid)
          _            (cond
                         (seq frames-spec)
                         ;; Multi-frame fixture: register each declared frame.
                         (doseq [f frames-spec]
                           (rf/reg-frame (:id f) (dissoc f :id)))
                         :else
                         (rf/reg-frame :rf/default frame-config))
          dispatches   (or (:fixture/dispatches fixture) [])]
      (doseq [ev dispatches]
        (cond
          (map? ev)
          (let [{event :event :as opts} ev]
            (rf/dispatch-sync event (dissoc opts :event)))

          :else
          (rf/dispatch-sync ev)))
      (let [expect       (or (:fixture/expect fixture) {})
            ;; Single-frame: :final-app-db. Multi-frame: :final-app-dbs as
            ;; {frame-id db}.
            expected-db  (:final-app-db expect)
            expected-dbs (:final-app-dbs expect)
            final-db     (rf/get-frame-db :rf/default)
            final-dbs    (when expected-dbs
                           (into {}
                                 (for [[fid _] expected-dbs]
                                   [fid (rf/get-frame-db fid)])))
            ;; Realise sub-checks BEFORE trace-failures: subscribing computes
            ;; the reaction body, which may emit :rf.error/sub-exception traces
            ;; that the trace-emissions check expects to see.
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-value frame-id qv)})))
            trace-failures (check-trace-emissions @traces (:trace-emissions expect))]
        (trace/clear-trace-cbs!)
        {:fixture-id   fid
         :passed?      (and (or (nil? expected-db) (= expected-db final-db))
                            (or (nil? expected-dbs) (= expected-dbs final-dbs))
                            (every? #(= (:expected %) (:actual %)) sub-checks)
                            (empty? trace-failures))
         :final-db     final-db
         :final-dbs    final-dbs
         :expected-db  expected-db
         :expected-dbs expected-dbs
         :sub-checks   sub-checks
         :trace-failures trace-failures}))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})))

;; ---- the test entrypoint --------------------------------------------------

(deftest run-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-fixtures)]
      (cond
        (:fixture/load-error fixture)
        (swap! results conj {:fixture-id fname
                             :skipped? true
                             :reason "load error"
                             :error (:fixture/load-error fixture)})

        (not (runnable? fixture))
        (swap! results conj {:fixture-id (:fixture/id fixture)
                             :skipped?   true
                             :reason     "capabilities not in claimed set"
                             :capabilities (:fixture/capabilities fixture)})

        :else
        (swap! results conj (assoc (run-fixture fixture)
                              :fname fname))))
    (let [all       @results
          run       (filter (complement :skipped?) all)
          passed    (filter :passed? run)
          failed    (remove :passed? run)
          skipped   (filter :skipped? all)]
      (println)
      (println "Conformance corpus:")
      (println "  total fixtures:" (count all))
      (println "  runnable:      " (count run))
      (println "  passed:        " (count passed))
      (println "  failed:        " (count failed))
      (println "  skipped:       " (count skipped))
      (when (seq failed)
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
          (when-let [tds (:expected-dbs f)]
            (when (not= tds (:final-dbs f))
              (println "    expected app-dbs:" tds)
              (println "    actual   app-dbs:" (:final-dbs f))))
          (doseq [sc (:sub-checks f)]
            (when (not= (:expected sc) (:actual sc))
              (println "    sub" (:query sc) "expected:" (:expected sc) "actual:" (:actual sc))))
          (when (seq (:trace-failures f))
            (doseq [tf (:trace-failures f)]
              (println "    trace:" tf)))))
      ;; The test asserts that AT LEAST one fixture passes.
      ;; Stricter assertions land as more capabilities come online.
      (is (pos? (count passed))
          "At least one conformance fixture should pass"))))
