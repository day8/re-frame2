(ns re-frame.epoch-egress-redaction-cljs-test
  "The epoch record's off-box egress contract through `rf/project-egress`.

  Pinned here: the app-db `:sensitive` substitution in `:db-before` /
  `:db-after` with bookkeeping passing through; the shared app-db axes kept
  independent of each other and of the three epoch-only axes (`:trigger-event`
  args, the `:rf.db/runtime` partition, `:effects` args); the t1/t2 pending-db
  trace re-root; the `:rf.epoch/sensitive?` badge; the `:trace-events-keep`
  bound; the explicit `:frame` override; the profile floor resolved once at the
  record boundary; and guards G1 (absent projector) and G2 (a core whose door
  does not dispatch `:rf/epoch-record`). Every redaction assertion sits beside a
  value that rides raw through the same call, or an opt-in revealing it.

  Dual-lane `.cljc`: the `-cljs-test` suffix puts it in the shadow-cljs
  `:node-test` build, and the artefact's `clojure -M:test` runs it on the JVM."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch :as rf.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; A unique sentinel, so a whole-record scan that finds it can only be hitting
;; this suite's secret.
(def ^:private secret "EPOCH-EGRESS-SECRET-p4515")

;; Written at an UNCLASSIFIED path: it must ride raw wherever the secret redacts.
(def ^:private benign "EPOCH-EGRESS-BENIGN-p4515")

(def ^:private payload-size 25000)

(defn- big-string [n] (apply str (repeat n "X")))

(def ^:private frame-id         :epoch-egress-redaction/frame)
;; A never-classified frame. A fresh id rather than a re-made `frame-id`, whose
;; app-db would still hold the secret.
(def ^:private control-frame-id :epoch-egress-redaction/control-frame)

(defn- last-record
  ([] (last-record frame-id))
  ([fid] (last (rf/epoch-history fid))))

(defn- fresh-frame!
  "Make the suite's frame with `[:auth :password]` sensitive and
  `[:blob :payload]` large — the registry write a `reg-event` returning
  `:sensitive` / `:large` performs."
  []
  (rf/make-frame {:id frame-id})
  (rf.frame/swap-runtime-db! frame-id
    (fn [rt] (rf.elision/apply-classification-effects
               rt {:sensitive [[:auth :password]]
                   :large     [[:blob :payload]]})))
  nil)

(defn- contains-secret?
  "True when `secret` appears anywhere in a nested EDN value."
  [x]
  (cond
    (string? x) (str/includes? x secret)
    (map? x)    (boolean (or (some contains-secret? (keys x))
                             (some contains-secret? (vals x))))
    (coll? x)   (boolean (some contains-secret? x))
    :else       false))

(defn- count-leaves-at-least
  "Count leaf strings of length >= `n`."
  [n x]
  (let [c (atom 0)]
    ((fn walk [v]
       (cond
         (string? v) (when (>= (count v) n) (swap! c inc))
         (map? v)    (do (run! walk (keys v)) (run! walk (vals v)))
         (coll? v)   (run! walk v)))
     x)
    @c))

(defn- reg-login!
  "One event writing the sensitive path and the unclassified `benign` sibling."
  []
  (rf/reg-event :egress/login
    (fn [{:keys [db]} [_ pw]]
      {:db (-> db
               (assoc-in [:auth :password] pw)
               (assoc-in [:audit :note] benign))})))

(defn- login-record!
  "Make the classified frame, run one login cascade, return the RAW record."
  []
  (fresh-frame!)
  (reg-login!)
  (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
  (last-record))

;; ---- app-db slots -----------------------------------------------------------

(deftest app-db-slots-redact-sensitive-leaf-at-egress
  (testing "`:db-before` and `:db-after` both substitute `:rf/redacted` at the
            declared leaf while the unclassified sibling rides raw, no secret
            bytes survive anywhere, and the bookkeeping slots consumers navigate
            by pass through unchanged"
    (fresh-frame!)
    (reg-login!)
    (rf/reg-event :egress/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (rf/dispatch-sync [:egress/inc]          {:frame frame-id})
    (let [raw         (last-record)
          proj        (rf/project-egress raw)
          bookkeeping [:kind :epoch-id :frame :committed-at :event-id :outcome
                       :schema-digest :rf.epoch/sensitive?]]
      (is (= {:auth {:password :rf/redacted} :audit {:note benign}} (:db-before proj)))
      (is (= {:auth {:password :rf/redacted} :audit {:note benign} :n 1} (:db-after proj)))
      (is (not (contains-secret? proj)))
      (is (= (select-keys raw bookkeeping) (select-keys proj bookkeeping))))))

(deftest projection-does-not-mutate-the-ring
  (testing "a forwarder projects on every cascade, so projection must leave the
            raw ring — the material replay and `restore-epoch!` read — untouched"
    (login-record!)
    (let [before (rf/epoch-history frame-id)]
      (dotimes [_ 2] (mapv rf/project-egress (rf/epoch-history frame-id)))
      (is (contains-secret? before) "the ring holds the raw secret to begin with")
      (is (= before (rf/epoch-history frame-id))))))

(deftest include-sensitive-keeps-large-elision-independent
  (testing "`:rf.egress/include-sensitive?` and `:rf.egress/include-large?` are
            independent: each opt-in lifts its own axis and no other"
    (fresh-frame!)
    (rf/reg-event :egress/both
      (fn [{:keys [db]} [_ pw p]]
        {:db (-> db (assoc-in [:auth :password] pw)
                    (assoc-in [:blob :payload] p))}))
    (rf/dispatch-sync [:egress/both secret (big-string payload-size)] {:frame frame-id})
    (let [raw (last-record)]
      (let [proj (rf/project-egress raw {:rf.egress/include-sensitive? true})]
        (is (= secret (get-in proj [:db-after :auth :password])))
        (is (rf.elision/marker? (get-in proj [:db-after :blob :payload])))
        (is (zero? (count-leaves-at-least payload-size proj))
            "no raw payload bytes anywhere in the record"))
      (let [proj (rf/project-egress raw {:rf.egress/include-large? true})]
        (is (= [:rf/redacted (big-string payload-size)]
               [(get-in proj [:db-after :auth :password])
                (get-in proj [:db-after :blob :payload])]))))))

;; ---- `:trigger-event` args fail closed --------------------------------------

(deftest trigger-event-positional-secret-fails-closed
  (testing "the dispatched event's args are not app-db-rooted, so off-box egress
            keeps the head event-id and redacts every arg, trace-event carriers
            included; `:rf.egress/include-event-args?` reveals them and leaves the
            app-db leaf redacted"
    (let [raw  (login-record!)
          proj (rf/project-egress raw)
          wide (rf/project-egress raw {:rf.egress/include-event-args? true})]
      (is (= [:egress/login :rf/redacted] (:trigger-event proj)))
      (is (not (contains-secret? proj)))
      (is (= [[:egress/login secret] :rf/redacted]
             [(:trigger-event wide) (get-in wide [:db-after :auth :password])])))))

;; ---- `:trace-events` --------------------------------------------------------

(defn- synthetic-record
  "A minimal `:rf/epoch-record` around `trace-events`, isolating the projector
  from whatever traces the live router emits."
  [fid trace-events]
  {:kind                :rf/epoch-record
   :epoch-id            1
   :frame               fid
   :committed-at        0
   :event-id            :egress/synthetic
   :trigger-event       [:egress/synthetic]
   :db-before           {}
   :db-after            {}
   :outcome             :ok
   :rf.epoch/sensitive? false
   :trace-events        trace-events
   :sub-runs            []
   :renders             []
   :effects             []})

(deftest trace-events-db-pending-tag-is-rerooted-and-redacted
  (testing "the t1 / t2 pending-db traces carry the whole pending app-db under
            `[:tags :rf.event/db]`, where a walk rooted at the trace vector never
            matches `[:auth :password]`; egress re-roots that walk at the frame's
            app-db, redacting by classification rather than blanking the tag"
    (fresh-frame!)
    (let [tags     {:rf.event/db {:auth {:password secret} :audit {:note benign}}}
          rec      (synthetic-record
                     frame-id
                     [{:op-type :rf.event :operation :rf.event/db-pending          :tags tags}
                      {:op-type :rf.event :operation :rf.event/db-pending-post-flow :tags tags}])
          redacted {:rf.event/db {:auth {:password :rf/redacted} :audit {:note benign}}}]
      (is (= [redacted redacted]
             (mapv :tags (:trace-events (rf/project-egress rec))))))))

(deftest sensitive-rollup-badge-survives-projection
  (testing "`:rf.epoch/sensitive?` is derived from the RAW record at assembly, so
            it survives projection: true where a declared path holds a value,
            strict false on a frame that classifies nothing"
    (fresh-frame!)
    (reg-login!)
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (rf/make-frame {:id control-frame-id})
    (rf/reg-event :egress/plain (fn [_ _] {:db {:audit {:note benign}}}))
    (rf/dispatch-sync [:egress/plain] {:frame control-frame-id})
    (is (= [true false]
           (mapv #(:rf.epoch/sensitive? (rf/project-egress (last-record %)))
                 [frame-id control-frame-id])))))

(deftest trace-events-retention-cap-bounds-what-egresses
  (testing "records older than the `:trace-events-keep` window (5, set by the
            fixture) drop `:trace-events`, and projection does not restore the slot"
    (rf/make-frame {:id frame-id})
    (rf/reg-event :egress/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (dotimes [_ 7] (rf/dispatch-sync [:egress/inc] {:frame frame-id}))
    (is (= [false false true true true true true]
           (mapv #(contains? (rf/project-egress %) :trace-events)
                 (rf/epoch-history frame-id))))))

;; ---- the one door's epoch arm -----------------------------------------------

(deftest explicit-frame-override-beats-the-records-own-frame
  (testing "an explicit `:frame` opt wins over the record's own slot: under a
            frame that classifies nothing the leaf rides raw, and an explicit
            nil — no frame governs — fails the payload closed rather than
            borrowing the record's frame"
    (let [raw  (login-record!)
          opts {:rf.egress/profile :rf.egress/off-box-tool}
          _    (rf/make-frame {:id control-frame-id})
          none (rf/project-egress raw (assoc opts :frame nil))]
      (is (= [secret :rf/redacted]
             [(get-in (rf/project-egress raw (assoc opts :frame control-frame-id))
                      [:db-after :auth :password])
              (:db-after none)]))
      (is (not (contains-secret? none))))))

(deftest shared-axes-lift-through-the-door-but-epoch-only-axes-do-not
  (testing "pair-MCP's boundary — `off-box-tool` plus
            `:rf.egress/include-sensitive?`, deliberately NOT `local-raw` — lifts
            the app-db leaf while event args, the runtime-db partition and effect
            args stay closed; `:rf.egress/include-runtime-db?` opens the partition"
    (fresh-frame!)
    (rf/reg-fx :egress/login-fx (fn [_ _] nil))
    (rf/reg-event :egress/login
      (fn [{:keys [db]} [_ pw]]
        {:db (assoc-in db [:auth :password] pw)
         :fx [[:egress/login-fx {:password pw}]]}))
    (rf/dispatch-sync [:egress/login secret] {:frame frame-id})
    (let [raw  (last-record)
          opts {:rf.egress/profile            :rf.egress/off-box-tool
                :rf.egress/include-sensitive? true}
          proj (rf/project-egress raw opts)]
      (is (= [secret [:egress/login :rf/redacted] :rf/redacted [:rf/redacted]]
             [(get-in proj [:db-after :auth :password])
              (:trigger-event proj)
              (get-in proj [:frame-state-after :rf.db/runtime])
              (mapv :args (filter #(= :egress/login-fx (:fx-id %)) (:effects proj)))]))
      (is (= (get-in raw [:frame-state-after :rf.db/runtime])
             (get-in (rf/project-egress raw (assoc opts :rf.egress/include-runtime-db? true))
                     [:frame-state-after :rf.db/runtime]))))))

(deftest local-raw-profile-floor-is-honoured-not-overridden-to-false
  (testing "the profile is the floor and only keys the caller supplies overlay
            it: `:rf.egress/local-raw` opts sensitive back in with no explicit
            key, and an explicit false still wins"
    (let [raw  (login-record!)
          leaf #(get-in (rf/project-egress raw %) [:db-after :auth :password])]
      (is (= [secret :rf/redacted]
             [(leaf {:rf.egress/profile :rf.egress/local-raw})
              (leaf {:rf.egress/profile            :rf.egress/local-raw
                     :rf.egress/include-sensitive? false})])))))

(defn- large-everywhere-record!
  "One cascade populating the three slots the shared large axis governs: the
  declared `[:blob :payload]` app-db path (tree-walked) and a whole-output
  `:large?` sub's `:sub-runs` row and `:rf.sub/run` trace tag (read off the
  epoch opts by key)."
  []
  (fresh-frame!)
  (rf/reg-sub :egress/big {:large? true} (fn [_ _] (big-string payload-size)))
  (rf/reg-event :egress/upload-and-read
    (fn [{:keys [db]} [_ payload]]
      (rf/subscribe-once [:egress/big] {:frame frame-id})
      {:db (assoc-in db [:blob :payload] payload)}))
  (rf/dispatch-sync [:egress/upload-and-read (big-string payload-size)]
                    {:frame frame-id})
  (last-record))

(defn- large-surfaces
  [record]
  {:db-after  (get-in record [:db-after :blob :payload])
   :sub-run   (->> (:sub-runs record)
                   (filter #(= :egress/big (:sub-id %)))
                   first
                   :value)
   :trace-tag (->> (:trace-events record)
                   (filter #(= :rf.sub/run (:operation %)))
                   (filter #(= :egress/big (get-in % [:tags :rf.sub/id])))
                   first
                   :tags
                   :rf.sub/value)})

(defn- all-raw?
  [surfaces]
  (every? #(and (string? %) (= payload-size (count %))) (vals surfaces)))

(defn- all-elided?
  [surfaces]
  (every? rf.elision/marker? (vals surfaces)))

(deftest local-raw-large-axis-reaches-every-whole-output-slot
  (testing "the shared large axis is resolved once at the record boundary, so
            the tree-walked `:db-after` slot and both whole-output subscription
            slots always agree: a fail-closed profile elides all three, an
            explicit key overrides the profile in either direction, and
            `local-raw`'s floor reaches all three — while the epoch-only
            runtime-db axis stays closed under it"
    (let [raw       (large-everywhere-record!)
          projected (fn [opts] (large-surfaces (rf/project-egress raw opts)))]
      (is (all-raw?    (projected {:rf.egress/profile :rf.egress/local-raw})))
      (is (all-elided? (projected {:rf.egress/profile        :rf.egress/local-raw
                                   :rf.egress/include-large? false})))
      (is (all-elided? (projected {:rf.egress/profile :rf.egress/off-box-tool})))
      (is (all-raw?    (projected {:rf.egress/profile        :rf.egress/off-box-tool
                                   :rf.egress/include-large? true})))
      (is (= :rf/redacted
             (get-in (rf/project-egress raw {:rf.egress/profile :rf.egress/local-raw})
                     [:frame-state-after :rf.db/runtime]))))))

;; ---- guards G1 and G2 -------------------------------------------------------

(deftest guard-g1-absent-projector-yields-no-payload-at-all
  (testing "with the projector absent the door throws before returning anything,
            and the thrown ex-data carries no record payload either"
    (let [raw      (login-record!)
          original (rf.late-bind/get-fn :epoch/project-record)
          data     (try (rf.late-bind/set-fn! :epoch/project-record nil)
                        (rf/project-egress raw)
                        nil
                        (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                          (ex-data e))
                        (finally (rf.late-bind/set-fn! :epoch/project-record original)))]
      (is (= :rf.error/epoch-artefact-missing (:rf.error/id data)))
      (is (not (contains-secret? data))))))

(deftest guard-g2-refuses-a-core-whose-door-does-not-dispatch-the-kind
  (testing "`late-bind/set-fns!` validates no key, so a new epoch artefact would
            register against an old core in silence and that core would
            bare-walk every record; the artefact refuses to load against a core
            whose door does not dispatch `:rf/epoch-record`"
    (is (= {:rf.epoch/load-refusal :rf.epoch/core-version-skew
            :kind                  :rf/epoch-record
            :hook                  :epoch/project-record}
           (try (rf.epoch/assert-core-dispatches-epoch-records! false)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                  (select-keys (ex-data e) [:rf.epoch/load-refusal :kind :hook])))))))
