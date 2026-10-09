(ns re-frame.epoch-privacy-test
  "Epoch privacy surfaces outside the per-slot egress matrix
  (`re-frame.epoch-egress-redaction-cljs-test`): the record-level
  `:rf.epoch/sensitive?` rollup, `:effects` args failing closed off-box, a depth
  halt carrying no raw event args, listener fan-out delivering the RAW record,
  and the `:rf.epoch/redacted-modified-paths-count` counter."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Side-effect requires (mirrors epoch_test.clj):
            [re-frame.machines]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- last-record [frame-id]
  (last (rf/epoch-history frame-id)))

(defn- install-sensitive-paths!
  "Declare `paths` sensitive against `frame-id` — the registry write a
  `reg-event` returning `:sensitive` performs."
  [frame-id paths]
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects rt {:sensitive paths})))
  nil)

(defn- contains-leaf?
  "True when `secret` appears as (part of) any string anywhere in `x`."
  [x secret]
  (cond
    (string? x) (.contains ^String x ^String secret)
    (map? x)    (or (some #(contains-leaf? % secret) (keys x))
                    (some #(contains-leaf? % secret) (vals x)))
    (coll? x)   (boolean (some #(contains-leaf? % secret) x))
    :else       false))

(deftest rollup-reads-non-nil-declared-leaves-in-either-db
  (testing "`:rf.epoch/sensitive?` is true when a declared sensitive path holds a
            non-nil leaf in `:db-before` OR `:db-after`: a declaration alone is
            not enough, and a value the handler clears still counts from the
            pre-cascade snapshot"
    (rf/make-frame {:id :test/main})
    (install-sensitive-paths! :test/main [[:auth :password]])
    (rf/reg-event :unrelated (fn [{:keys [db]} _] {:db (assoc db :n 42)}))
    (rf/reg-event :seed      (fn [_ _] {:db {:auth {:password "old-secret"}}}))
    (rf/reg-event :clear-pw  (fn [{:keys [db]} _] {:db (update db :auth dissoc :password)}))
    (doseq [event-id [:unrelated :seed :clear-pw]]
      (rf/dispatch-sync [event-id] {:frame :test/main}))
    (is (= [false true true]
           (mapv :rf.epoch/sensitive? (rf/epoch-history :test/main))))))

;; The structured :effects rows carry :args — the RAW fx-handler argument, not
;; rooted at the frame's app-db, so no classification can prove it safe.
;; Off-box egress fails closed on every outcome row; the raw ring keeps the
;; exact args and `:rf.egress/include-fx-args?` lifts the redaction.

(deftest project-egress-elides-fx-args-on-every-outcome-row
  (testing "the four outcome rows — :ok, :error from a throwing handler, :error
            from a missing fx, :skipped-on-platform — keep their exact args on
            the raw ring and egress them as `:rf/redacted`"
    (rf/make-frame {:id :test/main})
    (rf/reg-fx :fxp/login (fn [_ _] nil))
    (rf/reg-fx :fxp/boom (fn [_ _] (throw (ex-info "boom" {}))))
    ;; Client-only, so skipped on the JVM (:server) host.
    (rf/reg-fx :fxp/local-storage {:platforms #{:client}} (fn [_ _] nil))
    (rf/reg-event :run
      (fn [_ [_ args]]
        {:fx (mapv vector [:fxp/login :fxp/boom :fxp/missing :fxp/local-storage] args)}))
    (let [args [{:password "topsecret"} {:ssn "123-45-6789"}
                {:card "4111-1111-1111-1111"} {:session-key "secret-value"}]
          _    (rf/dispatch-sync [:run args] {:frame :test/main})
          raw  (last-record :test/main)]
      (is (= (map vector [:ok :error :error :skipped-on-platform] args)
             (map (juxt :outcome :args) (:effects raw))))
      (is (= (repeat 4 :rf/redacted)
             (map :args (:effects (rf/project-egress raw)))))
      (is (= (:effects raw)
             (:effects (rf/project-egress raw {:rf.egress/include-fx-args? true})))))))

(def ^:private halt-secret "halt-secret-do-not-leak")

(deftest halted-depth-record-carries-no-raw-event-args
  (testing "a depth halt's `:halt-reason` names the last SETTLED event by id
            only, and the halting event (which never ran) is
            registration-classified before it becomes the record's
            `:trigger-event`, so the raw halt record — and every projection,
            replay or restore refusal built from it — carries the secret
            nowhere. The dev depth trace keeps the settled event's vector,
            classified. DISTINCT events: A settles and dispatches B, which the
            depth limit refuses."
    (rf/make-frame {:id :test/halt :drain-depth 1})
    (let [traces (atom [])]
      (rf/reg-event :halt/settled {:sensitive [[:token]]}
        (fn [_ [_ {:keys [token]}]]
          {:fx [[:dispatch [:halt/pending {:token token :visible "pending"}]]]}))
      (rf/reg-event :halt/pending {:sensitive [[:token]]}
        (fn [_ _] {}))
      (rf/register-listener! :trace ::halt-traces (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:halt/settled {:token halt-secret :visible "settled"}]
                        {:frame :test/halt})
      (let [ring     (rf/epoch-history :test/halt)
            halt     (last ring)
            depth-ev (some #(when (= :rf.error/drain-depth-exceeded (:operation %)) %)
                           @traces)]
        (is (= [:ok :halted-depth] (mapv :outcome ring))
            "PRECONDITION: A settled, then the depth limit halted B")
        (is (= :halt/settled (get-in halt [:halt-reason :last-event-id])))
        (is (not (contains-leaf? halt halt-secret)))
        (is (= [:halt/settled {:token :rf/redacted :visible "settled"}]
               (get-in depth-ev [:tags :last-event])))))))

(deftest listener-fan-out-delivers-raw-record
  (testing "epoch listeners receive the RAW record — Xray's diff visualiser and
            on-box restore drivers read the raw :db-after; forwarders that egress
            off-box opt INTO projection at the wire boundary"
    (rf/make-frame {:id :test/main})
    (install-sensitive-paths! :test/main [[:auth :password]])
    (let [seen (atom [])]
      (rf/register-listener! :epoch ::raw-listener (fn [r] (swap! seen conj r)))
      (rf/reg-event :login
        (fn [{:keys [db]} [_ pw]] {:db (assoc-in db [:auth :password] pw)}))
      (rf/dispatch-sync [:login "topsecret"] {:frame :test/main})
      (is (= ["topsecret"] (mapv #(get-in % [:db-after :auth :password]) @seen))))))

(deftest redacted-modified-paths-count-counts-only-changed-declared-paths
  (testing "the count of declared sensitive paths whose value changed across the
            cascade — the one signal left once projection redacts both sides to
            the same sentinel, so it must survive projection. 0 with no
            declarations however much the db changed; nil -> value counts; an
            unchanged declared path does not"
    (rf/make-frame {:id :test/plain})
    (rf/make-frame {:id :test/main})
    (install-sensitive-paths! :test/main [[:auth :password] [:auth :token]])
    (rf/reg-event :seed         (fn [_ _] {:db {:auth {:password "pw-1" :token "tk-1"}}}))
    (rf/reg-event :rotate-token (fn [{:keys [db]} _] {:db (assoc-in db [:auth :token] "tk-2")}))
    (rf/reg-event :login-both   (fn [_ _] {:db {:auth {:password "pw-2" :token "tk-3"}}}))
    (rf/dispatch-sync [:seed] {:frame :test/plain})
    (doseq [event-id [:seed :rotate-token :login-both]]
      (rf/dispatch-sync [event-id] {:frame :test/main}))
    (is (= [[0] [2 1 2]]
           (for [frame-id [:test/plain :test/main]]
             (mapv #(:rf.epoch/redacted-modified-paths-count (rf/project-egress %))
                   (rf/epoch-history frame-id)))))))
