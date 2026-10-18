(ns re-frame.flows-rollback-dirty-check-test
  "The flow pass advances a frame's evaluation memo, folding its outputs and
  its departed flows' vacations into the pending `:db`. When the event then
  fails to commit — a schema rejection, a chain error, or a final-effects
  abort — the memo rolls back with the discarded candidate. A row left
  advanced would make the next same-input drain skip the flow, so its output
  would never materialise; a dropped departure row would lose its vacation.

  A pass also rewrites its flows' output claims in the live elision registry
  ahead of the commit, so a pass that throws hands them back to the outputs
  the frame still holds.

  A predicate validator stands in for Malli (Spec 010 §Non-Malli validators)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(defn- with-schema-validator-reset [test-fn]
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (try
    (test-fn)
    (finally
      (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  with-schema-validator-reset)

(defn- db [] (rf/app-db-value :rf/default))

(defn- run-flows!
  "Re-make `:rf/default` running the flows `ids`."
  [& ids]
  (rf/make-frame {:id :rf/default :flows (vec ids)}))

(deftest app-db-rejection-restores-flow-last-inputs-and-recomputes-on-next-clean-drain
  (rf.schemas/set-schema-fns!
    {:validate (fn [schema value] (boolean (schema value)))
     :explain  (fn [schema value] (when-not (schema value) {:failed value}))})
  (let [reject-out? (atom false)]
    (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
    (rf/reg-event :touch-other (fn [{:keys [db]} _] {:db (assoc db :other true)}))
    (rf/reg-app-schema [:out] (fn [out] (or (nil? out) (not @reject-out?))))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:out]} (fn [n] (* 2 n)))
    (run-flows! :double)
    (reset! reject-out? true)
    ;; The flow computes :out into the candidate, which the schema rejects.
    (rf/dispatch-sync [:touch-other])
    (is (= {:n 1} (db)))
    (reset! reject-out? false)
    ;; A same-input drain recomputes only because the row was rolled back.
    (rf/dispatch-sync [:touch-other])
    (is (= {:n 1 :out 2 :other true} (db)))))

(deftest chain-error-handler-throw-does-not-poison-fresh-flow-first-firing
  ;; The flows `:after` is skipped on an interceptor error: no flow trace, no
  ;; advanced row, so the next same-input drain still materialises the output.
  (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
  (run-flows! :derived)
  (rf/reg-event :boom (fn [_ _] (throw (ex-info "handler boom" {:src :test}))))
  (let [ops (atom [])]
    (rf/register-listener! :trace ::spurious (fn [ev] (swap! ops conj (:operation ev))))
    (try
      (rf/dispatch-sync [:boom])
      (finally
        (rf/unregister-listener! :trace ::spurious)))
    (is (not-any? #{:rf.flow/computed :rf.event/db-pending-post-flow} @ops)))
  (rf/dispatch-sync [:seed])
  (is (= {:n 1 :out 10} (db))))

(deftest chain-error-preserves-a-pending-vacation
  ;; A departed flow keeps its memo row until a clean event vacates its output,
  ;; so an errored event in between loses nothing.
  (rf/reg-event :seed (fn [_ _] {:db {:n 5}}))
  (rf/reg-flow :keep {:inputs [[:n]] :output-path [:out-keep]} (fn [n] (* 7 n)))
  (rf/reg-flow :gone {:inputs [[:n]] :output-path [:out-gone]} inc)
  (run-flows! :keep :gone)
  (rf/dispatch-sync [:seed])
  (rf/clear :flow :gone)
  (rf/reg-event :boom (fn [_ _] (throw (ex-info "boom" {:src :test}))))
  (rf/dispatch-sync [:boom])
  (is (= {:n 5 :out-keep 35 :out-gone 6} (db)))
  (rf/reg-event :touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
  (rf/dispatch-sync [:touch])
  (is (= {:n 5 :out-keep 35 :touched true} (db))))

(deftest final-effects-abort-restores-flow-dirty-check
  ;; A user `:after` inserts a malformed classification effect after the
  ;; handler ran, so the flows `:after` runs cleanly and advances the row
  ;; before the commit aborts at the final-effects boundary.
  (rf/reg-event :seed (fn [_ _] {:db {:n 4}}))
  (rf/dispatch-sync [:seed])
  (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
  (run-flows! :derived)
  (rf/reg-interceptor :test/insert-bad-classification
                      {:after (fn [ctx] (assoc-in ctx [:effects :sensitive] :not-a-vector))})
  (rf/reg-event :bad-classification {:interceptors [:test/insert-bad-classification]}
    (fn [{:keys [db]} _] {:db db}))
  (rf/dispatch-sync [:bad-classification])
  (is (= {:n 4} (db)))
  (rf/dispatch-sync [:seed])
  (is (= {:n 4 :out 40} (db))))

;; ---- output claims -------------------------------------------------------
;;
;; The commit-boundary rejection of a pass that succeeded is pinned by
;; `re-frame.partitioned-commit-test/schema-rejection-keeps-flow-claims-on-the-committed-output`.

(defn- a-throwing-replacement
  "Run `:secret`, whose whole output at `[:old]` is classified, beside an
  effect's claim on `[:note]`. Then re-register it with `metadata` and a
  `:derive` that throws, and dispatch an event that changes nothing. Returns
  `[before after]`, each the app-db, the frame's sensitive claims and the
  committed output as the wire walker elides it."
  [metadata]
  (rf/reg-event :seed (fn [_ _] {:db {:n 3 :note "public"} :sensitive [[:note]]}))
  (rf/reg-event :touch (fn [{:keys [db]} _] {:db db}))
  (rf/reg-flow :secret {:inputs [[:n]] :output-path [:old] :sensitive [[]]}
    (fn [_] "flow-secret"))
  (run-flows! :secret)
  (rf/dispatch-sync [:seed])
  (let [observe (fn []
                  {:db     (db)
                   :claims (rf.elision/sensitive-declarations :rf/default)
                   :old    (rf.elision/elide-wire-value
                             (:old (db)) {:frame :rf/default :path [:old]})})
        before  (observe)]
    (rf/reg-flow :secret metadata (fn [_] (throw (ex-info "derive fails" {}))))
    (rf/dispatch-sync [:touch])
    [before (observe)]))

(deftest a-throwing-replacement-that-moves-the-output-keeps-the-committed-claims
  (let [[before after] (a-throwing-replacement
                         {:inputs [[:n]] :output-path [:new] :sensitive [[]]})]
    (is (= :rf/redacted (:old before)) "precondition: the committed output is classified")
    (is (= before after) "app-db, the claims and the redaction stay as committed")))

(deftest a-throwing-replacement-that-drops-the-mark-keeps-the-committed-claims
  (let [[before after] (a-throwing-replacement {:inputs [[:n]] :output-path [:old]})]
    (is (= :rf/redacted (:old before)) "precondition: the committed output is classified")
    (is (= before after) "app-db, the claims and the redaction stay as committed")))
