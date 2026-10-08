(ns re-frame.birth-always-cljs-test
  "A machine's INITIAL MACROSTEP is initial entry followed by the same raise
  drain + `:always` fixed-point settle an event macrostep runs, before the
  birth commit — so a transient initial leaf whose `:always` guard already
  holds is settled past, unobserved, on start (XState v5
  `createActor(m).start()`; SCXML §3.13).

  PURE tests drive `apply-initial-entry-cascade`, the single birth site, from
  arguments alone. LIVE tests drive `reg-machine` + `dispatch-sync` through
  both birth triggers: the eager `[:rf.machine/start]` and the lazy first
  event. Parallel birth rounds are pinned in `parallel_always_round_cljs_test`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.parallel :as rf.machines.parallel]
   [re-frame.machines.result :as rf.machines.result]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate-adapter]
      :cljs [re-frame.adapter.reagent :as substrate-adapter])))

;; ---- PURE ------------------------------------------------------------------

(defn- birth
  "Run `machine`'s initial macrostep from arguments alone; return the Result."
  [machine]
  (rf.machines.parallel/apply-initial-entry-cascade
    machine (rf.machines.parallel/build-initial-snapshot machine {:bootstrap-pending? false})))

(defn- boot
  "The settled `{:state :data :fx}` of `machine`'s initial macrostep."
  [machine]
  (let [r (birth machine)]
    (assoc (select-keys (:snapshot r) [:state :data]) :fx (:fx r))))

(deftest pure-birth-entry-raise-preserves-nonraise-fx-ordering
  (testing "an initial `:entry` `:raise` drains inside the birth macrostep: the
            machine settles on the raised target, no reserved `:raise` fx
            escapes, and the non-raise fx keep their order"
    (is (= {:state :b
            :data  {}
            :fx    [[:log :before] [:log :after] [:log :in-b]]}
           (boot {:initial :a
                  :data    {}
                  :states  {:a {:entry (fn [_] {:fx [[:log :before] [:raise [:go]] [:log :after]]})
                                :on    {:go :b}}
                            :b {:entry (fn [_] {:fx [[:log :in-b]]})}}})))))

(deftest pure-birth-entry-raise-drains-parallel-region
  (testing "a region's initial `:entry` `:raise` re-enters the PARENT queue
            before birth commit, so a sibling region takes it too, and no
            reserved `:raise` fx escapes"
    ;; :right declares `:go` but never raises it — it moves only if :left's
    ;; raise was re-broadcast through the parent queue.
    (is (= {:state {:left :b :right :s} :data {} :fx []}
           (boot {:type    :parallel
                  :data    {}
                  :regions {:left  {:initial :a
                                    :states  {:a {:entry (fn [_] {:fx [[:raise [:go]]]})
                                                  :on    {:go :b}}
                                              :b {}}}
                            :right {:initial :r
                                    :states  {:r {:on {:go :s}}
                                              :s {}}}}})))))

(deftest pure-birth-always-chains-to-fixed-point
  (testing "birth `:always` runs to a FIXED POINT — :a → :b → :c on start —
            and each hop's `:action` :data write commits with the target"
    (is (= {:state :c :data {:n 2}}
           (select-keys (boot {:initial :a
                               :data    {:n 0}
                               :actions {:bump (fn [{data :data}] {:data (update data :n inc)})}
                               :states  {:a {:always {:target :b :action :bump}}
                                         :b {:always {:target :c :action :bump}}
                                         :c {}}})
                        [:state :data])))))

(deftest pure-birth-always-depth-limit-surfaces-as-failed-macrostep
  (testing "a birth `:always` cycle trips `:always-depth-limit` and fails the
            birth macrostep (XState v5 throws on such a runaway)"
    (let [r (birth {:initial            :a
                    :data               {}
                    :always-depth-limit 5
                    :states             {:a {:always :b}
                                         :b {:always :a}}})]
      (is (rf.machines.result/depth-abort? r))
      (is (nil? (:snapshot r)) "the runaway settle commits nothing"))))

;; ---- LIVE ------------------------------------------------------------------

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter substrate-adapter/adapter}))

(def ^:private snapshot rf.machines.test-support/snapshot)

(deftest live-eager-start-settles-birth-always
  (testing "eager `[:rf.machine/start]` settles past a transient initial leaf
            with no user event"
    (rf/reg-machine :birth/eager
      {:initial :booting
       :data    {:ready? true}
       :guards  {:ready? (fn [{data :data}] (:ready? data))}
       :states  {:booting {:always [{:guard :ready? :target :ready}]}
                 :ready   {}}})
    (rf/dispatch-sync [:birth/eager [:rf.machine/start]])
    (is (= :ready (:state (snapshot :birth/eager))))))

(deftest live-lazy-first-event-settles-birth-always
  (testing "lazy birth settles `:always` in the SAME first dispatch, before the
            user event — `:note` is declared only on :ready"
    (rf/reg-machine :birth/lazy
      {:initial :booting
       :data    {}
       :actions {:noted (fn [{data :data}] {:data (assoc data :noted? true)})}
       :states  {:booting {:always :ready}
                 :ready   {:on {:note {:action :noted}}}}})
    (rf/dispatch-sync [:birth/lazy [:note]])
    (is (= {:state :ready :data {:noted? true}}
           (select-keys (snapshot :birth/lazy) [:state :data])))))

(deftest live-eager-start-settling-onto-final-auto-destroys
  (testing "an eager start whose birth `:always` lands on a `:final?` leaf
            auto-destroys at start (XState v5: the actor is done immediately)"
    (rf/reg-machine :birth/final
      {:initial :booting
       :states  {:booting {:always :done}
                 :done    {:final? true}}})
    (rf/dispatch-sync [:birth/final [:rf.machine/start]])
    (is (nil? (snapshot :birth/final)))))
