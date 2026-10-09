(ns re-frame.flows-trace-test
  "JVM coverage for Spec 009 §Flow trace events / Spec 013 §Flow tracing: the
  `:rf.flow/*` payloads, first-time-only and per-frame registration evidence,
  the error-emit routing of flow failures, dirty-check rollback on a flow
  throw, and payload elision and privacy.

  The conformance fixtures pin the rest as data, driven by
  `re-frame.flows-conformance-test`: `flow-lifecycle-emits-traces.edn` the
  lifecycle order, and `flow-eval-exception.edn` the throw's atomic abort, the
  skipped `:fx` and the absent `:rf.event/db-changed`. The `:rf.flow/cleared`
  payload is pinned on both hosts by
  `re-frame.flows-replace-clear-trace-incarnation-cljs-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.elision :as rf.elision]
            [re-frame.frame :as rf.frame]
            [re-frame.privacy :as rf.privacy]
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:dynamic ^:private *captured* nil)

(defn- with-flow-trace-recorder
  "Record every `:flow`-op-type trace event into `*captured*` for one test."
  [test-fn]
  (let [captured (atom [])]
    (binding [*captured* captured]
      (rf.trace.tooling/register-listener!
        ::flow-trace-recorder
        (fn [ev] (when (= :flow (:op-type ev)) (swap! captured conj ev))))
      (try
        (test-fn)
        (finally
          (rf.trace.tooling/unregister-listener! ::flow-trace-recorder))))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  with-flow-trace-recorder)

(defn- by-op [op]
  (filterv #(= op (:operation %)) @*captured*))

(defn- tags-of
  "A trace's tags without the per-dispatch id the trace bus stamps."
  [tags]
  (dissoc tags :rf.trace/dispatch-id))

;; EP-0025: durable app-db classification rides the commit-plane
;; classification effects (`:source :effect`).
(defn- classify! [frame-id axis & paths]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {axis (mapv vec paths)}))))

;; ---- registration evidence -------------------------------------------------

(deftest reg-flow-registered-fires-first-time-only
  ;; A replacement's hot-reload signal is `:rf.registry/handler-replaced`; the
  ;; two never double-emit.
  (dotimes [_ 2]
    (rf/reg-flow :area {:inputs [[:w]] :output-path [:area]} (fn [w] w)))
  (is (= 1 (count (by-op :rf.flow/registered)))))

(deftest reg-flow-registered-is-per-frame-not-per-global-id
  ;; The same id on a second frame is an independent FIRST registration.
  (rf/make-frame {:id :left})
  (rf/make-frame {:id :right})
  (doseq [f [:left :right]]
    (rf/reg-flow :shared {:frame f :inputs [[:n]] :output-path [:result]} identity))
  (is (= [[:shared :left] [:shared :right]]
         (mapv (comp (juxt :flow-id :frame) :tags) (by-op :rf.flow/registered)))))

;; ---- computed / skip / failed --------------------------------------------

(deftest flow-computed-fires-on-input-change
  ;; `:before` is the slot's value immediately before this write, so a trace
  ;; consumer renders "wrote [path] before -> after" without the epoch's db.
  ;; The key is present, nil, on a slot never written.
  (rf/reg-event :init (fn [_ _] {:db {:w 3 :h 4}}))
  (rf/reg-event :grow (fn [{:keys [db]} _] {:db (assoc db :w 5)}))
  (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* w h)))
  (rf/dispatch-sync [:init])
  (rf/dispatch-sync [:grow])
  (let [computes (by-op :rf.flow/computed)]
    (is (= [{:flow-id :area :input-values [3 4] :before nil :result 12
             :path [:rect :area] :frame :rf/default}
            {:flow-id :area :input-values [5 4] :before 12 :result 20
             :path [:rect :area] :frame :rf/default}]
           (mapv #(dissoc (:tags %) :elapsed-ms :rf.trace/dispatch-id) computes)))
    (is (every? #(and (number? %) (not (neg? %))) (map (comp :elapsed-ms :tags) computes)))))

(deftest flow-skip-fires-on-value-equal-rewrite
  ;; `:input-paths-unchanged` lists every declared input, in order.
  (rf/reg-event :rewrite (fn [{:keys [db]} _] {:db (assoc db :w 3 :h 4)}))
  (rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:rect :area]} (fn [w h] (* w h)))
  (rf/dispatch-sync [:rewrite])
  (reset! *captured* [])
  (rf/dispatch-sync [:rewrite])
  (is (= [{:flow-id :area :reason :inputs-value-equal :input-paths-unchanged [[:w] [:h]]
           :frame :rf/default}]
         (mapv (comp tags-of :tags) @*captured*))))

(deftest flow-failed-fires-when-output-throws
  ;; A structured, EDN-safe exception summary: no raw Throwable rides the bus.
  (rf/reg-event :init (fn [_ _] {:db {:n 1}}))
  (rf/reg-flow :boom {:inputs [[:n]] :output-path [:doomed]}
    (fn [_] (throw (ex-info "boom" {:why :test}))))
  (rf/dispatch-sync [:init])
  (is (= [{:flow-id :boom :phase :derive :path [:doomed] :exception-message "boom"
           :exception-data {:why :test} :inputs [1] :frame :rf/default}]
         (mapv (comp tags-of :tags) (by-op :rf.flow/failed)))))

;; ---- error routing -------------------------------------------------------

(deftest flow-eval-exception-routes-through-error-emit-substrate
  ;; A flow failure reaches corpus-wide error listeners on the always-on
  ;; substrate, which survives CLJS production elision, and the rethrown
  ;; ex-info carries the canonical thrown-error shape.
  (let [seen     (atom [])
        expected {:error :rf.error/flow-eval-exception :event [:init] :event-id :init
                  :frame :rf/default :where :flow-eval :flow-id :boom :phase :derive}]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (rf/reg-event :init (fn [_ _] {:db {:n 1}}))
    (rf/reg-flow :boom {:inputs [[:n]] :output-path [:doomed]}
      (fn [_] (throw (ex-info "flow boom" {:why :test}))))
    (rf/dispatch-sync [:init])
    (let [[r & more] @seen
          thrown     (:exception r)
          data       (ex-data thrown)]
      (is (nil? more) "one record for one throw")
      (is (= expected (select-keys r (keys expected))))
      (is (= (into #{:exception :time :elapsed-ms :source-coord} (keys expected))
             (set (keys r))))
      (is (and (number? (:time r)) (nat-int? (:elapsed-ms r))))
      ;; `run-flows-on-db` is not on the `rf/` facade, so `:where` names its
      ;; owning namespace.
      (is (= {:rf.error/id :rf.error/flow-eval-exception :where 're-frame.flows/run-flows-on-db
              :recovery :no-recovery :rf.flow/failed-id :boom}
             (select-keys data [:rf.error/id :where :recovery :rf.flow/failed-id])))
      (is (and (some? (:cause data)) (string? (:reason data))))
      (is (re-find #"\[:rf\.error/flow-eval-exception\]" (ex-message thrown))))))

(deftest fx-reg-flow-cycle-routes-through-error-emit-substrate
  ;; A cycle closed by `:rf.fx/reg-flow` reaches the always-on substrate as the
  ;; typed `:rf.error/flow-cycle`, carrying the chain tools render.
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    (rf/reg-flow :a {:inputs [[:b-out]] :output-path [:a-out]} identity)
    (rf/reg-event :introduce-cycle
      (fn [_ _] {:fx [[:rf.fx/reg-flow [:b {:inputs [[:a-out]] :output-path [:b-out]} identity]]]}))
    (rf/dispatch-sync [:introduce-cycle])
    (is (= [[:rf.error/flow-cycle :rf.error/flow-cycle true]]
           (mapv (fn [r]
                   (let [d (ex-data (:exception r))]
                     [(:error r) (:rf.error/id d) (contains? #{[:a :b :a] [:b :a :b]} (:cycle d))]))
                 @seen)))))

;; ---- atomicity -----------------------------------------------------------

(deftest failed-flow-rolls-back-last-inputs-so-prior-flows-retry
  ;; A flow throw aborts the whole event, so a prior flow's dirty-check advance
  ;; must roll back with it: otherwise an identical next drain would skip that
  ;; flow and its output, never installed, would be lost.
  (rf/reg-event :init (fn [_ _] {:db {:n 5}}))
  (let [calls (atom [])]
    (rf/reg-flow :A {:inputs [[:n]] :output-path [:a-out]} (fn [n] (swap! calls conj :A) (* 2 n)))
    (rf/reg-flow :B {:inputs [[:a-out]] :output-path [:b-out]}
      (fn [_] (swap! calls conj :B) (throw (ex-info "boom" {}))))
    (dotimes [_ 2] (rf/dispatch-sync [:init]))
    (is (= [:A :B :A :B] @calls))))

;; ---- elision and privacy -------------------------------------------------

(deftest computed-trace-elides-large-before
  ;; `:before` rides the wire walker exactly as `:result` does.
  (rf/reg-event :init (fn [_ _] {:db {:n 1 :derived {:blob {:bytes "PRESEEDED"}}}}))
  (rf/reg-flow :payload {:inputs [[:n]] :output-path [:derived :blob]}
    (fn [n] {:bytes (str "blob-" n)}))
  (classify! :rf/default :large [:derived :blob])
  (rf/dispatch-sync [:init])
  (let [tags (:tags (last (by-op :rf.flow/computed)))]
    (is (= {:path [:derived :blob] :reason :effect}
           (select-keys (:rf.size/large-elided (:before tags)) [:path :reason])))
    (is (rf.elision/marker? (:result tags)))))

(deftest flow-failed-redacts-ex-data-when-frame-sensitive
  ;; On a frame holding sensitive data the author-keyed ex-data may embed a
  ;; secret, so it is redacted whole; attribution survives.
  (classify! :rf/default :sensitive [:auth :token])
  (rf/reg-event :auth/signed-in {:interceptors [[:rf.interceptor/path [:auth]]]}
    (fn [{:keys [db]} [_ token]] {:db (assoc db :token token)}))
  (rf/reg-flow :auth/derived-user {:inputs [[:auth :token]] :output-path [:auth :derived-user]}
    (fn [tok] (throw (ex-info "derive failed" {:leaked-token tok}))))
  (rf/dispatch-sync [:auth/signed-in "TOP-SECRET"])
  (let [[ev & more] (by-op :rf.flow/failed)]
    (is (nil? more))
    (is (= {:flow-id :auth/derived-user :frame :rf/default
            :exception-data rf.privacy/redacted-sentinel :sensitive? true}
           (assoc (select-keys (:tags ev) [:flow-id :frame :exception-data])
                  :sensitive? (:sensitive? ev))))
    (is (not (str/includes? (pr-str ev) "TOP-SECRET")))))

(deftest flow-trace-sensitive-value-also-redacted-on-wire
  ;; Both privacy layers fire for a sensitive flow: the cascade's top-level
  ;; stamp, and wire redaction of the classified input and output values.
  (classify! :rf/default :sensitive [:auth :token] [:auth :derived-token])
  (rf/reg-flow :auth/derived-token {:inputs [[:auth :token]] :output-path [:auth :derived-token]}
    (fn [t] (str "derived-" t)))
  (rf/reg-event :auth/signed-in {:interceptors [[:rf.interceptor/path [:auth]]]}
    (fn [{:keys [db]} [_ token]] {:db (assoc db :token token)}))
  (rf/dispatch-sync [:auth/signed-in "secret-token"])
  (let [ev (first (by-op :rf.flow/computed))]
    (is (= [true :rf/redacted [:rf/redacted]]
           [(:sensitive? ev) (get-in ev [:tags :result]) (get-in ev [:tags :input-values])]))))

(deftest flow-and-effect-claims-union-and-remove-independently
  ;; A flow's output claim and an app effect's claim on the same absolute path
  ;; union, and each removes only itself: the path stays classified while any
  ;; owner claims it.
  (let [p      [:auth :creds :secret]
        owners #(set (map :source (get (rf.elision/sensitive-declarations :rf/default) p)))]
    (rf/reg-flow :creds {:inputs [[:n]] :output-path [:auth :creds] :sensitive [[:secret]]}
      (fn [n] {:secret n}))
    (rf/reg-event :classify (fn [{:keys [db]} _] {:db db :sensitive [p]}))
    (rf/reg-event :declassify (fn [{:keys [db]} _] {:db db :clear-sensitive [p]}))
    (rf/dispatch-sync [:classify])
    (is (= #{:flow :effect} (owners)))
    (rf/dispatch-sync [:declassify])
    (is (= #{:flow} (owners)))
    (rf/dispatch-sync [:classify])
    (rf/clear :flow :creds {:frame :rf/default})
    (is (= #{:effect} (owners)))))
