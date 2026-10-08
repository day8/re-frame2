(ns re-frame.classification-effects-cljs-test
  "The four COMMIT-PLANE data-classification effects `:sensitive` / `:large`
  / `:clear-sensitive` / `:clear-large` (EP-0025), applied WITH the `:db`
  write at the commit point into the per-frame elision registry.

  Registry and egress legs read durable state and run in the production gate.
  The always-on rejection record is pinned by
  `re-frame.classification-effect-shape-record-cljs-test`; the dev-trace rows
  here sit inside `(when rf.interop/debug-enabled? …)` arms, and the
  `^:requires-debug` test reads the same event's own t1 / t2 traces.

  Dual-runtime `*_cljs_test.cljc`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            ;; Side-effect load: the flow transform behind the t2 leg (a
            ;; test-only dep of core).
            [re-frame.flows]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- sensitive-decls []
  (rf.elision/sensitive-declarations))

(defn- large-decls []
  (rf.elision/declarations))

(defn- wire []
  (rf.elision/elide-wire-value (rf.frame/frame-app-db-value :rf/default)))

(defn- record-traces! [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(deftest sensitive-effect-records-path-and-redacts-at-egress
  (testing "a handler returning `:sensitive` alongside `:db` redacts the value
            it writes from its first egress, while app-db keeps the real value"
    (rf/reg-event :auth/login
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:user :token] "Bearer secret-xyz")
         :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:auth/login])
    (is (= "Bearer secret-xyz"
           (get-in (rf.frame/frame-app-db-value :rf/default) [:user :token])))
    (is (= :rf/redacted (get-in (wire) [:user :token])))))

(deftest large-effect-records-path-and-marks-at-egress
  (testing "a `:large` path elides to an `:rf.size/large-elided` marker at egress"
    (rf/reg-event :docs/upload
      (fn [{:keys [db]} _]
        {:db    (assoc-in db [:docs :csv] (apply str (repeat 500 "X")))
         :large [[:docs :csv]]}))
    (rf/dispatch-sync [:docs/upload])
    (is (= [:docs :csv] (get-in (wire) [:docs :csv :rf.size/large-elided :path])))))

(deftest clear-sensitive-is-source-scoped-does-not-un-redact-another-source
  (testing "an effect SET unions with another owner's claim, and the effect
            CLEAR removes only its own, so a path another owner (here a flow)
            still classifies stays redacted"
    (rf.elision/swap-elision-slot! :rf/default
      (fn [reg]
        (rf.elision/add-claims (or reg {}) :sensitive-declarations
                               {:source :flow :flow-id :token-watch} [[:user :token]])))
    (rf/reg-event :effect-classify-token
      (fn [{:keys [db]} _]
        {:db        (assoc-in db [:user :token] "Bearer secret-xyz")
         :sensitive [[:user :token]]}))
    (rf/dispatch-sync [:effect-classify-token])
    (is (= #{{:source :flow :flow-id :token-watch} {:source :effect}}
           (get (sensitive-decls) [:user :token])))
    (rf/reg-event :effect-clear-token
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token]]}))
    (rf/dispatch-sync [:effect-clear-token])
    (is (= #{{:source :flow :flow-id :token-watch}}
           (get (sensitive-decls) [:user :token])))
    (is (= :rf/redacted (get-in (wire) [:user :token])))))

(deftest same-event-set-and-clear-of-one-path-clear-wins
  (testing "SET axes apply before CLEAR axes within one effect map, so a
            same-event set+clear of one path leaves it unclassified on both
            axes and its value ships raw"
    (let [csv (apply str (repeat 500 "Y"))]
      (rf/reg-event :set-and-clear-same
        (fn [{:keys [db]} _]
          {:db              (-> db
                                (assoc-in [:user :token] "Bearer set-then-cleared")
                                (assoc-in [:docs :csv] csv))
           :sensitive       [[:user :token]]
           :clear-sensitive [[:user :token]]
           :large           [[:docs :csv]]
           :clear-large     [[:docs :csv]]}))
      (rf/dispatch-sync [:set-and-clear-same])
      (is (= {:user {:token "Bearer set-then-cleared"} :docs {:csv csv}}
             (select-keys (wire) [:user :docs]))))))

(deftest axes-are-independent
  (testing "a clear removes only the paths it names, on its own axis: an
            unnamed sibling survives, the other axis is untouched, and a
            clear over a path classified only on the other axis is a
            committed no-op"
    (rf/reg-event :classify-both
      (fn [{:keys [db]} _]
        {:db        db
         :sensitive [[:user :token] [:user :pin]]
         :large     [[:docs :csv] [:docs :blob]]}))
    (rf/dispatch-sync [:classify-both])
    (rf/reg-event :clear-s
      (fn [{:keys [db]} _] {:db db :clear-sensitive [[:user :token] [:docs :blob]]}))
    (rf/dispatch-sync [:clear-s])
    (is (not (contains? (sensitive-decls) [:user :token])) "the named path is cleared")
    (is (contains? (sensitive-decls) [:user :pin]) "an unnamed sibling survives")
    (is (not (contains? (sensitive-decls) [:docs :blob]))
        "a wrong-axis clear adds no claim")
    (is (and (contains? (large-decls) [:docs :csv])
             (contains? (large-decls) [:docs :blob]))
        "a sensitive clear leaves the large axis intact, the wrong-axis path included")
    (rf/dispatch-sync [:classify-both])
    (rf/reg-event :clear-l
      (fn [{:keys [db]} _] {:db db :clear-large [[:docs :csv]]}))
    (rf/dispatch-sync [:clear-l])
    (is (not (contains? (large-decls) [:docs :csv])) "the large path is cleared")
    (is (contains? (sensitive-decls) [:user :token])
        "a large clear leaves the sensitive axis intact")))

(deftest malformed-classification-payload-fails-loud-with-no-db-commit
  (testing "a malformed payload aborts the event with no :db commit, and the
            dev trace names the offending key"
    (rf/reg-event :seed-axis (fn [{:keys [db]} _] {:db (assoc db :n 1)}))
    (rf/dispatch-sync [:seed-axis])
    (rf/reg-event :bad-classify
      (fn [{:keys [db]} _] {:db (assoc db :n 2) :clear-large [:not-a-path-vector]}))
    (let [recorded (record-traces! :bad-classify-probe)]
      (rf/dispatch-sync [:bad-classify])
      (rf/unregister-listener! :trace :bad-classify-probe)
      (when rf.interop/debug-enabled?
        (is (= [:clear-large]
               (->> @recorded
                    (filter #(= :rf.error/classification-effect-shape (:operation %)))
                    (mapv #(get-in % [:tags :offending-key])))))))
    (is (= 1 (:n (rf.frame/frame-app-db-value :rf/default))))))

(deftest classification-only-effect-commits-registry
  (testing "a handler returning ONLY a classification effect (no :db) still
            commits the registry write"
    (rf/reg-event :classify-only
      (fn [_ _] {:sensitive [[:secret :value]]}))
    (rf/dispatch-sync [:classify-only])
    (is (contains? (sensitive-decls) [:secret :value]))))

;; t1 `:rf.event/db-pending` and t2 `:rf.event/db-pending-post-flow` stamp the
;; pending app-db BEFORE the commit folds this event's classification into the
;; registry, so they are projected against the CANDIDATE registry; against the
;; committed one they would ship the very secret the event classifies.

(def ^:private t1-secret "SAME-EVENT-TRACE-SENTINEL-4x3")

(defn- same-event-login!
  "Write the secret at `[:user :token]` and under a map-of key
  (`[:user \"s1\" :token]`, which `[:user :token]` also governs), classify
  `[:user :token]` in the SAME return, dispatch, and return the t1 and t2
  traces."
  [event-id]
  (let [rec (record-traces! event-id)]
    (try
      (rf/reg-event event-id
        (fn [{:keys [db]} _]
          {:db        (-> db
                          (assoc :n 1)
                          (assoc-in [:user :token] t1-secret)
                          (assoc-in [:user "s1" :token] (str t1-secret "-map-of"))
                          (assoc-in [:user :name] "alice"))
           :sensitive [[:user :token]]}))
      (rf/dispatch-sync [event-id])
      {:t1 (filterv #(= :rf.event/db-pending (:operation %)) @rec)
       :t2 (filterv #(= :rf.event/db-pending-post-flow (:operation %)) @rec)}
      (finally
        (rf/unregister-listener! :trace event-id)))))

(defn- assert-same-event-db-redacted [where trace]
  (is (= {:token :rf/redacted "s1" {:token :rf/redacted} :name "alice"}
         (get-in trace [:tags :rf.event/db :user]))
      (str where ": the classified path and its map-of position redact; a benign sibling survives"))
  (is (not-any? #(and (keyword? %)
                      (some-> (namespace %) (str/starts-with? "re-frame.")))
                (keys (:tags trace)))
      (str where ": no private carrier tag reaches the listener")))

(deftest ^:requires-debug same-event-classification-redacts-its-own-t2-trace
  (testing "t1, and t2 after a flow reshaped the pending db, both take the
            candidate registry"
    (rf/reg-flow :same-event/doubled {:inputs [[:n]] :output-path [:doubled]}
      (fn [n] (* 2 n)))
    (let [{:keys [t1 t2]} (same-event-login! :auth/login-t2)]
      (assert-same-event-db-redacted :t1 (first t1))
      (assert-same-event-db-redacted :t2 (first t2)))))
