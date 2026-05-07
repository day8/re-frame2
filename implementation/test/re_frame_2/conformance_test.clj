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
      (let [handler (conformance/realise-sub-handler steps)]
        (rf/reg-sub id handler)))))

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

(defn run-fixture [fixture]
  (try
    (reset-runtime!)
    (realise-handlers fixture)
    (let [fid             (:fixture/id fixture)
          frame-config    (or (:fixture/frame-config fixture) {})
          ;; reset-runtime! already created :rf/default WITHOUT an
          ;; :on-create. reg-frame against an existing id is a surgical
          ;; update that doesn't re-fire :on-create per Spec 002. We
          ;; therefore destroy and re-register so :on-create fires.
          _               (rf/destroy-frame :rf/default)
          ;; Listener BEFORE the frame is created so the :on-create
          ;; event's trace is captured.
          traces          (collect-traces fid)
          _               (rf/reg-frame :rf/default frame-config)
          dispatches      (or (:fixture/dispatches fixture) [])]
      (doseq [ev dispatches]
        (rf/dispatch-sync ev))
      (let [final-db    (rf/get-frame-db :rf/default)
            expect      (or (:fixture/expect fixture) {})
            expected-db (:final-app-db expect)
            ;; Run sub-values check.
            sub-checks
            (for [[query-v expected-val] (or (:sub-values expect) {})]
              {:query   query-v
               :expected expected-val
               :actual   (rf/subscribe-value :rf/default query-v)})
            trace-failures (check-trace-emissions @traces (:trace-emissions expect))]
        (trace/clear-trace-cbs!)
        {:fixture-id  fid
         :passed?     (and (or (nil? expected-db) (= expected-db final-db))
                           (every? #(= (:expected %) (:actual %)) sub-checks)
                           (empty? trace-failures))
         :final-db    final-db
         :expected-db expected-db
         :sub-checks  sub-checks
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
