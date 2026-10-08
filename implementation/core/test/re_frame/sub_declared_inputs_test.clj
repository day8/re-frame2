(ns re-frame.sub-declared-inputs-test
  "DECLARED subscription inputs, `(reg-sub id {:inputs …} body)` (Spec 006
  §Subscription input producers, Spec 008 §`compute-sub` algorithm, API
  §`reg-sub` `:inputs`). The declaration is a literal vector of query vectors
  (`:static`, shape-checked at registration but never looked up) or a fn/Var of
  the query vector (`:parametric`, never run at registration), and a declared
  dependency list ALWAYS reaches the body as a VECTOR, at zero, one or many
  inputs, on every read path. Single-source readers (`:db` / `:runtime-db` /
  `:frame-state`) receive their bare container value instead. The retired `:<-`
  chain and two-fn tail are refused at registration."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- reg-sub-refusal
  "Register `args` through the FN form `rf.subs/reg-sub` (the public one is a
  macro); return the refusal's ex-data, or ::accepted."
  [& args]
  (try (apply rf.subs/reg-sub args)
       ::accepted
       (catch clojure.lang.ExceptionInfo e
         (ex-data e))))

(defn- seed! [db]
  (rf/reg-event :seed (fn [_ _] {:db db}))
  (rf/dispatch-sync [:seed]))

(defn- read-three-ways
  "Read `query-v` through all three paths and return `{:reactive … :once …
  :compute …}`. The reactive read subscribes, derefs and releases."
  [query-v db]
  (let [r (rf/subscribe query-v)
        reactive @r]
    (rf/unsubscribe query-v)
    {:reactive reactive
     :once     (rf/subscribe-once query-v)
     :compute  (rf.subs/compute-sub query-v db)}))

(defn- same-three-ways [v]
  {:reactive v :once v :compute v})

;; ---- the parser -----------------------------------------------------------

(deftest producer-inputs-lift-into-the-parametric-slots
  (let [ran      (atom 0)
        producer (fn [[_ id]] (swap! ran inc) [[:a id]])]
    (rf/reg-sub :a (fn [db [_ id]] (get-in db [:a id])))
    (rf/reg-sub :p {:inputs producer} (fn [[a] _] a))
    (is (= [:parametric 0] [(:input-kind (rf.registrar/lookup :sub :p)) @ran])
        "the producer is never executed at registration")))

(def ^:private var-producer (fn [_] [[:a]]))

(deftest a-var-is-accepted-as-a-producer
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :v {:inputs #'var-producer} (fn [[a] _] a))
  (seed! {:a 7})
  (is (= 7 (rf/subscribe-once [:v]))))

(deftest malformed-literal-inputs-are-refused-at-registration
  ;; A scalar query vector takes the literal grammar's check; an explicit nil
  ;; is not the same as absent; and `:inputs` needs exactly one trailing fn.
  (doseq [args [[{:inputs [:a :b]} (fn [in _] in)]
                [{:inputs nil} (fn [in _] in)]
                [{:inputs [[:a]]} :not-a-fn]]]
    (is (= :rf.error/reg-sub-bad-args (:rf.error/id (apply reg-sub-refusal :x args)))
        (pr-str args))))

;; ^:requires-debug: the unknown-key warning is dev-gated end to end, so under
;; the production gate "nothing warned" would hold vacuously. Only the INLINE
;; path sees `:inputs` at the key check (the public path lifts it first), so
;; it is the call that goes red if `:inputs` leaves the `:sub` vocabulary.
(deftest ^:requires-debug inputs-is-a-known-registration-key
  (let [acc     (atom [])
        warned? #(boolean (some (fn [ev] (= :rf.warning/unknown-registration-key (:operation ev)))
                                @acc))]
    (rf.trace.tooling/register-listener! ::inputs-warnings (fn [ev] (swap! acc conj ev)))
    (try
      (rf.subs/lower-inline-sub :inline {:doc "documented" :inputs [[:a]]} (fn [[a] _] a))
      (is (not (warned?)) "`:inputs` is in the `:sub` bare-key vocabulary")
      ;; The control: an unknown bare key on the same seam does warn.
      (rf.subs/lower-inline-sub :typo {:inpts [[:a]]} (fn [db _] db))
      (is (warned?) "control: an unknown bare key does warn")
      (finally (rf.trace.tooling/unregister-listener! ::inputs-warnings)))))

(deftest a-literal-declaration-does-not-require-its-upstream-to-exist-yet
  ;; The literal check is shape-only, never a registry lookup.
  (is (= :forward (rf/reg-sub :forward {:inputs [[:not-yet-registered]]} (fn [[v] _] v))))
  (rf/reg-sub :not-yet-registered (fn [db _] (:v db)))
  (seed! {:v 42})
  (is (= 42 (rf/subscribe-once [:forward]))))

;; ---- delivery -------------------------------------------------------------

(deftest a-producer-declaration-receives-the-outer-query-vector
  (rf/reg-sub :item (fn [db [_ id]] (get-in db [:items id])))
  (rf/reg-sub :title {:inputs (fn [[_ id]] [[:item id]])} (fn [[item] _] (:title item)))
  (let [db {:items {:x {:title "X"} :y {:title "Y"}}}]
    (seed! db)
    (is (= [(same-three-ways "X") (same-three-ways "Y")]
           [(read-three-ways [:title :x] db) (read-three-ways [:title :y] db)]))))

(deftest literal-and-producer-declarations-of-the-same-body-agree
  ;; For map / vector / nil upstream values, on every path.
  (rf/reg-sub :m (fn [db _] (:m db)))
  (rf/reg-sub :v (fn [db _] (:v db)))
  (rf/reg-sub :n (fn [db _] (:n db)))
  (let [body (fn [[a] _] {:seen a})]
    (doseq [up [:m :v :n]]
      (rf/reg-sub (keyword "lit" (name up))  {:inputs [[up]]}          body)
      (rf/reg-sub (keyword "prod" (name up)) {:inputs (fn [_] [[up]])} body)))
  (let [db {:m {:k 1} :v [1 2] :n nil}]
    (seed! db)
    (doseq [up [:m :v :n]]
      (let [w (same-three-ways {:seen (get db up)})]
        (is (= [w w] [(read-three-ways [(keyword "lit" (name up))] db)
                      (read-three-ways [(keyword "prod" (name up))] db)])
            (str "upstream " up))))))

(deftest the-retired-spellings-are-refused-naming-inputs-and-the-migration-rule
  ;; Refused at namespace load, naming `:inputs` and the migration rule, rather
  ;; than registered with a delivery shape the runtime has no arm for.
  (rf/reg-sub :a (fn [db _] (:a db)))
  (doseq [args [[:x/single :<- [:a] (fn [v _] v)]
                [:x/two-fn (fn [_q] [[:a]]) (fn [in _] in)]]]
    (let [{:keys [reason] :as data} (apply reg-sub-refusal args)]
      (is (= [:rf.error/reg-sub-bad-args true true]
             [(:rf.error/id data) (boolean (re-find #":inputs" (str reason)))
              (boolean (re-find #"M-75" (str reason)))])
          (pr-str (first args))))))

(deftest declared-inputs-deliver-a-vector-at-zero-one-and-many
  ;; Every declared count arrives as a VECTOR in declaration order, with no
  ;; bare-for-one arm, through a literal and through a producer. `:a` reads
  ;; nil, which a single input still delivers wrapped.
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db _] (:b db)))
  (rf/reg-sub :zero   {:inputs []}                     (fn [in _] {:seen in}))
  (rf/reg-sub :one    {:inputs [[:a]]}                 (fn [in _] {:seen in}))
  (rf/reg-sub :many   {:inputs [[:b] [:a]]}            (fn [in _] {:seen in}))
  (rf/reg-sub :one-p  {:inputs (fn [_] [[:a]])}        (fn [in _] {:seen in}))
  (rf/reg-sub :many-p {:inputs (fn [_] [[:b] [:a]])}   (fn [in _] {:seen in}))
  (let [db {:b 2}]
    (seed! db)
    (doseq [[query-v expected] [[[:zero]   []]
                                [[:one]    [nil]]
                                [[:many]   [2 nil]]
                                [[:one-p]  [nil]]
                                [[:many-p] [2 nil]]]]
      (is (= (same-three-ways {:seen expected}) (read-three-ways query-v db))
          (pr-str query-v)))))

(deftest single-source-readers-receive-the-same-container-on-all-three-read-paths
  ;; The collapse is "single-source kind -> container; declared -> vector".
  (rf.subs/reg-runtime-sub :rt/seen (fn [runtime-db _] {:seen runtime-db}))
  (rf.subs/reg-frame-state-sub :fs/seen (fn [frame-state _] {:seen frame-state}))
  (rf/reg-sub :db/seen (fn [db _] {:seen db}))
  (let [db {:a 1}]
    (seed! db)
    (let [rt (rf.frame/frame-runtime-db-value :rf/default)
          fs @(rf.frame/frame-state-container :rf/default)]
      (is (= (same-three-ways {:seen db}) (read-three-ways [:db/seen] db)))
      (is (= (same-three-ways {:seen rt}) (read-three-ways [:rt/seen] rt)))
      (is (= (same-three-ways {:seen fs}) (read-three-ways [:fs/seen] fs))))))
