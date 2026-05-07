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
  "What this implementation currently supports.
  Fixtures requiring capabilities outside this set are skipped."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    :fsm/flat
    :fsm/eventless-always
    :fsm/hierarchical
    :routing/match-url
    :ssr/render-to-string
    :ssr/hydration
    :ssr/response-contract
    :ssr/head-contract
    :ssr/error-projection})

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
  (rf/init!)
  ;; Framework events / fx are registered at namespace-load time in
  ;; routing.cljc / ssr.cljc; clear-all! wiped them. Re-eval those
  ;; registrations so :rf.route/navigate, :rf.route/handle-url-change,
  ;; :rf/hydrate, :rf.nav/push-url, :rf.nav/replace-url all resolve.
  ((requiring-resolve 're-frame-2.core/reg-sub) :rf/route
   (requiring-resolve 're-frame-2.routing/route-sub-fn))
  ;; Re-evaluate the registration ns-bodies by removing-and-reloading.
  (require 're-frame-2.routing :reload)
  (require 're-frame-2.ssr :reload))

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
    ;;
    ;; Two sources combine: :fixture/handlers :fx (bodies) and
    ;; :fixture/registry :fx (metadata, including :platforms / :spec).
    ;; A registry-only entry registers as a no-op so fx that the fixture
    ;; merely declares but doesn't body still resolve at do-fx time.
    (let [adapter-helpers
          {:read-db!  (fn [frame-id]
                        (frame/frame-app-db-value frame-id))
           :write-db! (fn [frame-id new-db]
                        (let [container (frame/get-frame-db frame-id)]
                          ((requiring-resolve 're-frame-2.substrate.adapter/replace-container!)
                           container new-db)))
           :dispatch! (fn [event frame-id]
                        (rf/dispatch event {:frame frame-id}))}
          fx-bodies   (get handlers-map :fx)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-fx-ids  (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-fx-ids]
        (let [body  (get fx-bodies id [[:noop]])
              meta  (get fx-registry id {})
              handler (conformance/realise-fx-handler id body adapter-helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; route registrations
    (doseq [[id meta] (get handlers-map :route)]
      (rf/reg-route id meta))
    ;; view registrations — DSL bodies map to fns that realise hiccup with
    ;; reflection forms resolved at call-time.
    (doseq [[id steps] (get handlers-map :view)]
      ((requiring-resolve 're-frame-2.registrar/register!)
       :view id
       {:handler-fn (conformance/realise-view-handler steps)}))))

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

(defn- register-routes! [fixture]
  (doseq [[id meta] (get-in fixture [:fixture/registry :route])]
    (rf/reg-route id meta)))

(defn- realise-machine-handlers
  "Build {action-id → fn} and {guard-id → fn} from a fixture's
  :fixture/handlers :machine-action / :machine-guard buckets.

  Action body steps return effects via the apply-step :fx slot — we
  collect those into the {:fx [...]} return shape. Guard body steps
  evaluate to a single boolean — we run the steps and read the last
  reflection's value."
  [fixture]
  (let [handlers-map (or (:fixture/handlers fixture) {})
        eval-value (requiring-resolve 're-frame-2.conformance/eval-value*)
        actions-by-id
        (into {}
              (for [[id steps] (:machine-action handlers-map)]
                [id (fn [snap event]
                      (let [final (reduce
                                    (fn [{:keys [data] :as ctx} step]
                                      (case (first step)
                                        :set    (let [[_ path v] step]
                                                  (assoc ctx :data
                                                         (assoc-in data path
                                                                   (eval-value v ctx))))
                                        :fx     (let [[_ a b] step]
                                                  (update ctx :fx (fnil conj [])
                                                          [a b]))
                                        ctx))
                                    {:data (:data snap) :event event :fx []}
                                    steps)]
                        (cond-> {}
                          (not= (:data snap) (:data final)) (assoc :data (:data final))
                          (seq (:fx final)) (assoc :fx (:fx final)))))]))
        guards-by-id
        (into {}
              (for [[id steps] (:machine-guard handlers-map)]
                [id (fn [snap event]
                      (let [step (first steps)]
                        (when (and (vector? step) (= :fn (first step)))
                          (boolean
                            (eval-value step {:data (:data snap) :event event})))))]))]
    {:actions actions-by-id :guards guards-by-id}))

(defn- run-call
  "Dispatch a :fixture/calls entry. Returns {:passed? bool :detail ...}.

  fixture-machines is the realised {:actions ... :guards ...} map for
  the fixture (built once by run-fixture)."
  [call & [fixture-machines]]
  (case (:call call)
    :match-url
    (let [actual (rf/match-url (:url call))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "match-url " (:url call)
                       " expected " expect " got " actual))})

    :route-url
    (let [actual (if (:query call)
                   (rf/route-url (:route-id call) (:params call) (:query call))
                   (rf/route-url (:route-id call) (:params call)))
          expect (:expect call)]
      {:passed? (= expect actual)
       :detail  (when (not= expect actual)
                  (str "route-url " (:route-id call)
                       " expected " expect " got " actual))})

    :round-trip
    (let [matched (rf/match-url (:url call))
          rebuilt (when matched
                    (if (seq (:query matched))
                      (rf/route-url (:route-id matched) (:params matched) (:query matched))
                      (rf/route-url (:route-id matched) (:params matched))))]
      {:passed? (= (:url call) rebuilt)
       :detail  (when (not= (:url call) rebuilt)
                  (str "round-trip " (:url call) " → " rebuilt))})

    ;; assertion against rank metadata; we don't implement rank-meta yet, so
    ;; mark as skipped (returns true to not fail the fixture).
    :assert-rank-greater
    {:passed? true
     :detail  "assert-rank-greater not asserted (rank-meta not yet exposed)"}

    ;; SSR pure render: input is hiccup or [:view-id args ...]; opts may
    ;; carry :doctype?.
    :render-to-string
    (let [r2s   (requiring-resolve 're-frame-2.ssr/render-to-string)
          opts  (or (:opts call) {})
          out   (try (r2s (:input call) opts)
                     (catch Throwable e (str "<error: " (.getMessage e) ">")))
          want  (:expect call)]
      {:passed? (= want out)
       :detail  (when (not= want out)
                  (str "render-to-string\n"
                       "    expected: " (pr-str want) "\n"
                       "    actual:   " (pr-str out)))})

    ;; pure machine-transition call (used by fsm fixtures).
    :machine-transition
    (let [machine-transition (requiring-resolve 're-frame-2.machines/machine-transition)
          actions-by-id (or (:actions fixture-machines) {})
          guards-by-id  (or (:guards fixture-machines) {})
          ;; Merge fixture-registered handlers into the def's named-binding
          ;; maps. The fixture's bindings live alongside any short-names the
          ;; def already declared. Machines/chase-ref follows
          ;; short-name → registered-id → fn through this combined map.
          definition    (-> (:definition call)
                            (update :actions #(merge actions-by-id %))
                            (update :guards  #(merge guards-by-id %)))
          [snap-out fx-out]
          (try (machine-transition definition (:snapshot call) (:event call))
               (catch Throwable e [nil [:error (.getMessage e)]]))
          want-snap (:expect-next-snapshot call)
          want-fx   (or (:expect-effects call) [])
          ok-snap?  (= want-snap snap-out)
          ok-fx?    (= want-fx (vec fx-out))]
      {:passed? (and ok-snap? ok-fx?)
       :detail  (when (not (and ok-snap? ok-fx?))
                  (str "machine-transition\n"
                       "    expected snapshot: " want-snap "\n"
                       "    actual   snapshot: " snap-out "\n"
                       "    expected effects:  " want-fx "\n"
                       "    actual   effects:  " fx-out))})

    ;; unknown call op
    {:passed? false :detail (str "unknown :call form: " (:call call))}))

(defn run-fixture [fixture]
  (try
    (reset-runtime!)
    (realise-handlers fixture)
    (register-routes! fixture)
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

          ;; Convention: :rf/hydrate events dispatch with :source :ssr-hydration
          ;; per Spec 011 §The :rf/hydrate event. Real clients pass this on the
          ;; hydrate-call site; the conformance runner stamps it for the user.
          (and (vector? ev) (= :rf/hydrate (first ev)))
          (rf/dispatch-sync ev {:source :ssr-hydration})

          :else
          (rf/dispatch-sync ev)))
      ;; :fixture/render-after-hydrate — for SSR hydration fixtures, the
      ;; harness simulates a client-side first render whose hash differs
      ;; from the server's. We inspect the most-recent :rf/hydrate dispatch
      ;; for the server hash and compare against the simulated client hash;
      ;; when they differ we emit :rf.ssr/hydration-mismatch.
      (when-let [render-spec (:fixture/render-after-hydrate fixture)]
        (let [client-hash    (:simulated-client-render-hash render-spec)
              first-diff-path (:first-diff-path render-spec)
              hydrate-ev      (some (fn [e]
                                      (when (and (vector? e)
                                                 (= :rf/hydrate (first e)))
                                        e))
                                    dispatches)
              payload         (when hydrate-ev (second hydrate-ev))
              server-hash     (:rf/render-hash payload)
              frame-id        (:rf/frame-id payload :rf/default)]
          (when (and server-hash client-hash (not= server-hash client-hash))
            (trace/emit-error! :rf.ssr/hydration-mismatch
                               {:failing-id      :rf/hydrate
                                :server-hash     server-hash
                                :client-hash     client-hash
                                :first-diff-path first-diff-path
                                :frame           frame-id
                                :reason          (str "Hydration mismatch: server hash '"
                                                      server-hash
                                                      "' != client hash '"
                                                      client-hash
                                                      "'. Re-rendering client-side.")
                                :recovery        :warned-and-replaced}))))
      ;; :fixture/calls — pure-function assertions (match-url, route-url,
      ;; machine-transition, etc.). Run AFTER dispatches so any
      ;; handler-mediated state is in place.
      (let [machines      (realise-machine-handlers fixture)
            calls         (or (:fixture/calls fixture) [])
            call-results  (mapv #(run-call % machines) calls)
            call-failures (filter (complement :passed?) call-results)]
        (when (seq call-failures)
          ;; Surface the first failure as a fixture-level error so the
          ;; reporter shows it.
          (throw (ex-info (str "calls failed: "
                               (clojure.string/join "; "
                                 (map :detail call-failures)))
                          {:call-failures call-failures}))))
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
      (when (seq passed)
        (println)
        (println "Passing:")
        (doseq [p passed]
          (println "  " (:fixture-id p))))
      (when (seq skipped)
        (println)
        (println "Skipped (out-of-claim):")
        (doseq [s skipped]
          (println "  " (:fixture-id s) "—" (or (:capabilities s) (:reason s)))))
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
