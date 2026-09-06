(ns re-frame.sub-declared-inputs-test
  "Tests for DECLARED subscription inputs — `(reg-sub id {:inputs …} body)`
  (rf2-kuky.46; ruled on rf2-kuky.45; Spec 006 §Subscription input producers,
  Spec 008 §`compute-sub` algorithm, API §`reg-sub` `:inputs`).

  A subscription declares its dependencies ONCE, in the metadata map, and a
  declared dependency list ALWAYS reaches the body as a VECTOR — at zero, one
  or many inputs. The declaration is a literal vector of query vectors (a
  `:static` producer, shape-checked at registration) or a fn/Var of the query
  vector (a `:parametric` producer, validated at materialisation).

  Coverage:
    - parser: literal / fn / Var / `[]` accepted; malformed literals, an
      explicit `nil`, and over-specification with the retired `:<-` or a second trailing
      fn all raise `:rf.error/reg-sub-bad-args` AT REGISTRATION
    - `:inputs` is a KNOWN registration key (no unknown-key warning) and is
      LIFTED, never stored twice — `handler-meta` carries the runtime-owned
      slots and no `:inputs`
    - registration-order freedom: the literal check is SHAPE-only, never a
      registry lookup
    - delivery agrees across all three read paths (reactive `subscribe`,
      `subscribe-once`, pure `compute-sub`) for 0 / 1 / N inputs and for map,
      vector and nil upstream values
    - the SAME body under `{:inputs [[:a]]}` and `{:inputs (fn [_] [[:a]])}`
      returns the same value on every path (the second reviewer's acceptance)
    - single-source readers (`:db` / `:runtime-db` / `:frame-state`) still
      receive their bare CONTAINER value — the delivery collapse is
      \"single-source kind → container; declared dependencies → vector\", not
      \":db → db; else → vector\"
    - `sub-topology` reports a literal declaration as `:static` with its edges
      and a producer as the `:parametric` sentinel
    - the retired `:<-` chain and two-fn tail are refused at registration"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as rf.subs]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- reg-sub-error
  "Register `args` and return the `:rf.error/id` it raised, or `::accepted`
  when the registration was (wrongly) accepted. Uses the FN form
  `rf.subs/reg-sub` because the public `rf/reg-sub` is a macro (source-coord
  capture) and a macro var is not a callable value; the parser under test is
  the same one."
  [& args]
  (try (apply rf.subs/reg-sub args)
       ::accepted
       (catch clojure.lang.ExceptionInfo e
         (:rf.error/id (ex-data e)))))

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

;; ---- the parser -----------------------------------------------------------

(deftest literal-inputs-lift-into-the-runtime-owned-slots
  (testing "a literal `:inputs` registers as `:static` with its query vectors"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :one {:inputs [[:a]]} (fn [[a] _] a))
    (let [m (rf.registrar/lookup :sub :one)]
      (is (= :static (:input-kind m)))
      (is (= [[:a]] (:input-signals m)))
      (is (nil? (:input-fn m)))
      (is (not (contains? m :inputs))
          "`:inputs` is LIFTED into the runtime-owned slots, never stored twice"))))

(deftest producer-inputs-lift-into-the-parametric-slots
  (testing "a fn `:inputs` registers as `:parametric` and is not executed"
    (let [ran (atom 0)
          producer (fn [[_ id]] (swap! ran inc) [[:a id]])]
      (rf/reg-sub :a (fn [db [_ id]] (get-in db [:a id])))
      (rf/reg-sub :p {:inputs producer} (fn [[a] _] a))
      (let [m (rf.registrar/lookup :sub :p)]
        (is (= :parametric (:input-kind m)))
        (is (= producer (:input-fn m)))
        (is (= [] (:input-signals m)))
        (is (not (contains? m :inputs))))
      (is (zero? @ran) "the producer is NEVER executed at registration"))))

(def ^:private var-producer (fn [_] [[:a]]))

(deftest a-var-is-accepted-as-a-producer
  (testing "`:inputs` accepts a Var as well as a plain fn"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :v {:inputs #'var-producer} (fn [[a] _] a))
    (is (= :parametric (:input-kind (rf.registrar/lookup :sub :v))))
    (seed! {:a 7})
    (is (= 7 (rf/subscribe-once [:v])))))

(deftest explicit-empty-inputs-is-a-declaration
  (testing "`{:inputs []}` declares NO dependencies — `:static` with no edges"
    (rf/reg-sub :none {:inputs []} (fn [in _] {:in in}))
    (let [m (rf.registrar/lookup :sub :none)]
      (is (= :static (:input-kind m)))
      (is (= [] (:input-signals m))))))

(deftest malformed-literal-inputs-are-refused-at-registration
  (testing "the literal grammar is a vector of QUERY VECTORS; every near-miss is loud"
    (doseq [[label bad] [["scalar query vector" [:a :b]]
                         ["bare keyword"        :a]
                         ["map"                 {:a [:a]}]
                         ["string"              "[:a]"]
                         ["non-keyword head"    [["a"]]]
                         ["mixed"               [[:a] :b]]]]
      (is (= :rf.error/reg-sub-bad-args
             (reg-sub-error :x {:inputs bad} (fn [in _] in)))
          (str "a " label " `:inputs` must be refused at registration")))))

(deftest explicit-nil-inputs-is-not-absent
  (testing "`{:inputs nil}` is refused — nil is not \"absent\""
    (is (= :rf.error/reg-sub-bad-args
           (reg-sub-error :x {:inputs nil} (fn [db _] db))))))

(deftest inputs-cannot-be-combined-with-the-transitional-grammars
  (testing "`:inputs` beside the retired `:<-` chain or a second trailing fn is refused"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (is (= :rf.error/reg-sub-bad-args
           (reg-sub-error :x {:inputs [[:a]]} :<- [:a] (fn [in _] in))))
    (is (= :rf.error/reg-sub-bad-args
           (reg-sub-error :x {:inputs [[:a]]} (fn [_] [[:a]]) (fn [in _] in))))))

(deftest inputs-with-no-computation-fn-is-refused
  (testing "`:inputs` still requires exactly one trailing computation fn"
    (is (= :rf.error/reg-sub-bad-args (reg-sub-error :x {:inputs [[:a]]})))
    (is (= :rf.error/reg-sub-bad-args (reg-sub-error :x {:inputs [[:a]]} :not-a-fn)))))

;; ^:requires-debug — the unknown-bare-key warning is dev-gated end to end:
;; `reg-meta/validate-registration-metadata!` binds `known` but reads it only
;; inside `(when interop/debug-enabled? …)`, so under `-Dre-frame.debug=false`
;; nothing warns and there is no production behaviour here to assert. The tag
;; rather than a `(when interop/debug-enabled? …)` split around the control:
;; the subject is "nothing warned", which the production gate satisfies
;; VACUOUSLY, so guarding only the control would leave a deftest reporting
;; success for an assertion that inspected nothing — the class-2 false green
;; `scripts/test-core-prod-gate.sh` exists to close.
;;
;; THE INLINE CALL IS THE DISCRIMINATING ONE, and the public call alone would
;; NOT be. The two registration paths lift `:inputs` in opposite orders:
;; `parse-reg-sub-args` dissocs it BEFORE `normalize-sub-metadata` runs, so on
;; the public path the key check never sees `:inputs` and this assertion holds
;; however the vocabulary is spelled. `lower-inline-sub` normalizes the RAW
;; metadata and lifts AFTER, so `:inputs` IS present at the check there — the
;; inline path is the only one where the `:sub` vocabulary entry is load-
;; bearing. Verified by planting the removal of `:inputs` from
;; `reg-meta/known-bare-keys`: the public-path form passed unchanged; the
;; inline form below goes red.
(deftest ^:requires-debug inputs-is-a-known-registration-key
  (testing "`:inputs` does not warn as an unknown registration key"
    (let [acc     (atom [])
          warned? #(seq (filterv (fn [ev]
                                   (= :rf.warning/unknown-registration-key
                                      (:operation ev)))
                                 @acc))]
      (rf.trace/register-listener! ::inputs-warnings (fn [ev] (swap! acc conj ev)))
      (try
        (rf/reg-sub :a (fn [db _] (:a db)))
        ;; Public path: `:inputs` is stripped before the check (see above), so
        ;; this pins the strip's effect rather than the vocabulary.
        (rf/reg-sub :k {:doc "documented" :inputs [[:a]]} (fn [[a] _] a))
        ;; Inline path: `:inputs` reaches the check, so THIS is what fails if
        ;; the `:sub` vocabulary entry is dropped.
        (rf.subs/lower-inline-sub :inline {:doc "documented" :inputs [[:a]]}
                                  (fn [[a] _] a))
        (is (not (warned?))
            "`:inputs` is in the `:sub` bare-key vocabulary")
        ;; The control: a genuinely unknown bare key on the SAME inline seam
        ;; DOES warn, so the empty result above is a pass and not a listener
        ;; that never fired.
        (reset! acc [])
        (rf.subs/lower-inline-sub :typo {:inpts [[:a]]} (fn [db _] db))
        (is (warned?) "control: an unknown bare key still warns")
        (finally (rf.trace/unregister-listener! ::inputs-warnings))))))

(deftest a-literal-declaration-does-not-require-its-upstream-to-exist-yet
  (testing "the literal check is SHAPE-only — never a registry lookup"
    (is (= :forward (rf/reg-sub :forward {:inputs [[:not-yet-registered]]}
                                (fn [[v] _] v)))
        "registration-order freedom: declare before the upstream exists")
    (rf/reg-sub :not-yet-registered (fn [db _] (:v db)))
    (seed! {:v 42})
    (is (= 42 (rf/subscribe-once [:forward])))))

;; ---- delivery: always a vector, on every path -----------------------------

(deftest a-single-declared-input-arrives-as-a-one-element-vector
  (testing "one declared input → `[v]` on all three read paths"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :one {:inputs [[:a]]} (fn [in _] {:seen in}))
    (let [db {:a 1}]
      (seed! db)
      (is (= {:reactive {:seen [1]} :once {:seen [1]} :compute {:seen [1]}}
             (read-three-ways [:one] db))))))

(deftest declared-inputs-arrive-in-declaration-order
  (testing "N declared inputs → a vector in DECLARATION order"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :c (fn [db _] (:c db)))
    (rf/reg-sub :three {:inputs [[:c] [:a] [:b]]} (fn [in _] in))
    (let [db {:a 1 :b 2 :c 3}]
      (seed! db)
      (is (= {:reactive [3 1 2] :once [3 1 2] :compute [3 1 2]}
             (read-three-ways [:three] db))))))

(deftest an-empty-declaration-delivers-an-empty-vector
  (testing "`{:inputs []}` delivers `[]` — NOT nil, and not app-db"
    (rf/reg-sub :none {:inputs []} (fn [in _] {:seen in}))
    (let [db {:a 1}]
      (seed! db)
      (is (= {:reactive {:seen []} :once {:seen []} :compute {:seen []}}
             (read-three-ways [:none] db))))))

(deftest omitting-inputs-is-the-layer-1-app-db-reader
  (testing "absent `:inputs` delivers app-db — distinct from `{:inputs []}`"
    (rf/reg-sub :whole (fn [db _] {:seen db}))
    (let [db {:a 1}]
      (seed! db)
      (is (= {:reactive {:seen db} :once {:seen db} :compute {:seen db}}
             (read-three-ways [:whole] db))))))

(deftest upstream-values-of-every-shape-survive-the-wrapping
  (testing "map / vector / nil upstream values arrive intact inside the vector"
    (rf/reg-sub :m (fn [db _] (:m db)))
    (rf/reg-sub :v (fn [db _] (:v db)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :shapes {:inputs [[:m] [:v] [:n]]} (fn [in _] in))
    (let [db {:m {:k 1} :v [1 2] :n nil}]
      (seed! db)
      (is (= {:reactive [{:k 1} [1 2] nil]
              :once     [{:k 1} [1 2] nil]
              :compute  [{:k 1} [1 2] nil]}
             (read-three-ways [:shapes] db))))))

(deftest a-single-nil-upstream-value-still-arrives-wrapped
  (testing "a nil upstream value is `[nil]`, never bare nil — the shape does not collapse"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :wrapped {:inputs [[:n]]} (fn [in _] {:seen in}))
    (let [db {:n nil}]
      (seed! db)
      (is (= {:reactive {:seen [nil]} :once {:seen [nil]} :compute {:seen [nil]}}
             (read-three-ways [:wrapped] db))))))

(deftest a-producer-declaration-receives-the-outer-query-vector
  (testing "a `:parametric` declaration realizes its inputs per concrete query-v"
    (rf/reg-sub :item (fn [db [_ id]] (get-in db [:items id])))
    (rf/reg-sub :title {:inputs (fn [[_ id]] [[:item id]])}
                (fn [[item] _] (:title item)))
    (let [db {:items {:x {:title "X"} :y {:title "Y"}}}]
      (seed! db)
      (is (= {:reactive "X" :once "X" :compute "X"} (read-three-ways [:title :x] db)))
      (is (= {:reactive "Y" :once "Y" :compute "Y"} (read-three-ways [:title :y] db))))))

(deftest literal-and-producer-declarations-of-the-same-body-agree
  (testing "the SAME body under a literal and a producer declaration returns the
            same value on every path, for map / vector / nil upstream values"
    (rf/reg-sub :m (fn [db _] (:m db)))
    (rf/reg-sub :v (fn [db _] (:v db)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (let [body (fn [[a] _] {:seen a})]
      (doseq [up [:m :v :n]]
        (let [lit  (keyword "lit" (name up))
              prod (keyword "prod" (name up))]
          (rf/reg-sub lit  {:inputs [[up]]}         body)
          (rf/reg-sub prod {:inputs (fn [_] [[up]])} body))))
    (let [db {:m {:k 1} :v [1 2] :n nil}]
      (seed! db)
      (doseq [up [:m :v :n]]
        (let [l (read-three-ways [(keyword "lit" (name up))] db)
              p (read-three-ways [(keyword "prod" (name up))] db)]
          (is (= l p) (str "literal and producer disagree for upstream " up))
          (is (= {:seen (get db up)} (:compute l))))))))

(deftest a-declared-input-recomputes-on-an-upstream-change
  (testing "the reactive node cascades exactly as any declared-input node does"
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :doubled {:inputs [[:n]]} (fn [[n] _] (* 2 n)))
    (rf/reg-event :seed  (fn [_ _] {:db {:n 5}}))
    (rf/reg-event :bump  (fn [{:keys [db]} _] {:db (update db :n inc)}))
    (rf/dispatch-sync [:seed])
    (let [r (rf/subscribe [:doubled])]
      (is (= 10 @r))
      (rf/dispatch-sync [:bump])
      (is (= 12 @r) "the declared-input node recomputed on the upstream change")
      (rf/unsubscribe [:doubled]))))

(deftest a-declared-input-node-memoises-on-an-equal-upstream-value
  (testing "the single-declared-input specialisation still short-circuits on an
            `=`-equal upstream value — `[v0]` delivery does not cost the memo hit"
    (let [runs (atom 0)]
      (rf/reg-sub :n (fn [db _] (:n db)))
      (rf/reg-sub :counted {:inputs [[:n]]}
                  (fn [[n] _] (swap! runs inc) (* 2 n)))
      (rf/reg-event :seed  (fn [_ _] {:db {:n 5 :other 0}}))
      (rf/reg-event :touch (fn [{:keys [db]} _] {:db (update db :other inc)}))
      (rf/dispatch-sync [:seed])
      (let [r (rf/subscribe [:counted])]
        (is (= 10 @r))
        (is (= 1 @runs))
        (rf/dispatch-sync [:touch])
        (is (= 10 @r))
        (is (= 1 @runs) "an unchanged upstream value must NOT re-run the body")
        (rf/unsubscribe [:counted])))))

;; ---- single-source readers keep their container value ---------------------

(deftest single-source-readers-still-receive-their-container-value
  (testing "`:db` / `:runtime-db` / `:frame-state` bodies receive the CONTAINER
            value, not a vector — the collapse is by single-source KIND, not
            by \"anything that is not :db\" (rf2-kuky.45 correction 1)"
    (rf/reg-sub :app-reader (fn [db _] {:seen db}))
    (rf.subs/reg-runtime-sub :runtime-reader (fn [rdb _] {:runtime-map? (map? rdb)}))
    (rf.subs/reg-frame-state-sub :frame-reader
                                 (fn [fs _] {:partitions (set (keys fs))}))
    (let [db {:a 1}]
      (seed! db)
      (is (= {:seen db} (rf/subscribe-once [:app-reader])))
      (is (= {:seen db} (rf.subs/compute-sub [:app-reader] db)))
      (is (= {:runtime-map? true} (rf/subscribe-once [:runtime-reader])))
      (is (= #{:rf.db/app :rf.db/runtime}
             (:partitions (rf/subscribe-once [:frame-reader])))
          "a `:frame-state` body still receives the WHOLE frame-state value")
      (let [frame-state {:rf.db/app db :rf.db/runtime {}}]
        (is (= #{:rf.db/app :rf.db/runtime}
               (:partitions (rf.subs/compute-sub [:frame-reader] frame-state))))
        (is (= {:runtime-map? true}
               (rf.subs/compute-sub [:runtime-reader] frame-state)))))))

;; ---- topology -------------------------------------------------------------

(deftest sub-topology-reports-declared-inputs-with-no-new-tool-code
  (testing "a literal declaration is a `:static` edge set; a producer is the sentinel"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :lit  {:inputs [[:a] [:b :arg]]} (fn [in _] in))
    (rf/reg-sub :prod {:inputs (fn [_] [[:a]])}  (fn [in _] in))
    (rf/reg-sub :zero {:inputs []}               (fn [in _] in))
    ;; `sub-topology` also reports the macro-captured source coords; the
    ;; edge shape is what this pins.
    (let [edge #(select-keys (get (rf.subs/sub-topology) %) [:input-kind :inputs])]
      (is (= {:input-kind :static :inputs [[:a] [:b :arg]]} (edge :lit))
          "args are preserved on a declared edge")
      (is (= {:input-kind :parametric :inputs :parametric} (edge :prod)))
      (is (= {:input-kind :static :inputs []} (edge :zero)))
      (is (= {:input-kind :db :inputs []} (edge :a))))))

;; ---- the retired grammars are REFUSED at registration ---------------------
;;
;; rf2-kuky.50 deleted the v1 declared-input chain and the two-trailing-fn `input-fn`
;; tail. Both now raise `:rf.error/reg-sub-bad-args` whose message names
;; `:inputs` and the migration rule, so a call site the sweep missed fails
;; LOUDLY at namespace load rather than registering with a delivery shape the
;; runtime no longer has an arm for.

(defn- reg-sub-refusal
  "Register `args` and return the refusal `:reason` string it raised, or
  `::accepted` when the registration was (wrongly) accepted."
  [& args]
  (try (apply rf.subs/reg-sub args)
       ::accepted
       (catch clojure.lang.ExceptionInfo e
         (:reason (ex-data e)))))

(deftest the-retired-spellings-are-refused-naming-inputs-and-the-migration-rule
  (testing "each retired shape raises reg-sub-bad-args, named and actionable"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (doseq [[label args]
            [["a single declared input"
              [:x/single :<- [:a] (fn [v _] v)]]
             ["a multi declared-input chain"
              [:x/multi  :<- [:a] :<- [:b] (fn [in _] in)]]
             ["a leading input-fn (the two-fn tail)"
              [:x/two-fn (fn [q] [[:a]]) (fn [in _] in)]]
             ["`:inputs` combined with a declared-input chain"
              [:x/mixed  {:inputs [[:a]]} :<- [:b] (fn [in _] in)]]]]
      (is (= :rf.error/reg-sub-bad-args (apply reg-sub-error args))
          (str label " is refused at registration"))
      (let [reason (apply reg-sub-refusal args)]
        (is (string? reason) (str label " carries a refusal message"))
        (is (re-find #":inputs" (str reason))
            (str "the refusal for " label " names `:inputs`"))
        (is (re-find #"M-75" (str reason))
            (str "the refusal for " label " names the migration rule")))
      (is (nil? (rf.registrar/lookup :sub (first args)))
          (str label " was never registered")))))

;; ---- delivery agrees on all three paths, with the bare arms gone ----------

(deftest declared-inputs-deliver-a-vector-at-zero-one-and-many
  (testing "reactive / subscribe-once / compute-sub agree, and every declared
            count arrives as a VECTOR — there is no bare-for-one arm left"
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :zero  {:inputs []}           (fn [in _] {:seen in}))
    (rf/reg-sub :one   {:inputs [[:a]]}       (fn [in _] {:seen in}))
    (rf/reg-sub :many  {:inputs [[:a] [:b]]}  (fn [in _] {:seen in}))
    ;; the same counts through a PRODUCER declaration rather than a literal
    (rf/reg-sub :one-p {:inputs (fn [_] [[:a]])}       (fn [in _] {:seen in}))
    (rf/reg-sub :many-p {:inputs (fn [_] [[:a] [:b]])} (fn [in _] {:seen in}))
    (let [db {:a 1 :b 2}]
      (seed! db)
      (doseq [[query-v expected] [[[:zero]   []]
                                  [[:one]    [1]]
                                  [[:many]   [1 2]]
                                  [[:one-p]  [1]]
                                  [[:many-p] [1 2]]]]
        (is (= {:reactive {:seen expected}
                :once     {:seen expected}
                :compute  {:seen expected}}
               (read-three-ways query-v db))
            (str query-v " delivers " (pr-str expected) " on all three paths"))))))

(deftest single-source-readers-still-receive-their-container-value
  (testing "the collapse is \"single-source kind → container; declared → vector\",
            so `:db`, `:runtime-db` and `:frame-state` are untouched by it —
            on all three read paths"
    (rf.subs/reg-runtime-sub :rt/seen (fn [runtime-db _] {:seen runtime-db}))
    (rf.subs/reg-frame-state-sub :fs/seen (fn [frame-state _] {:seen frame-state}))
    (rf/reg-sub :db/seen (fn [db _] {:seen db}))
    (let [db {:a 1}]
      (seed! db)
      (is (= {:reactive {:seen db} :once {:seen db} :compute {:seen db}}
             (read-three-ways [:db/seen] db))
          "a layer-1 reader still receives the bare app-db value")
      (let [rt (rf.frame/frame-runtime-db-value :rf/default)]
        (is (= {:reactive {:seen rt} :once {:seen rt} :compute {:seen rt}}
               (read-three-ways [:rt/seen] rt))
            "a :runtime-db reader still receives the bare runtime-db value"))
      (let [fs @(rf.frame/frame-state-container :rf/default)]
        (is (= {:reactive {:seen fs} :once {:seen fs} :compute {:seen fs}}
               (read-three-ways [:fs/seen] fs))
            "a :frame-state reader still receives the whole frame-state value")))))
