(ns re-frame.machine-event-schema-reserved-test
  "A machine's event `:schema` describes the author's events only. The
  router never checks a runtime-minted `:rf.machine/*` lifecycle event
  against it (Spec 005 §What validates machine events today), so a singleton
  whose schema admits nothing but its own vocabulary still starts on its
  kick, takes a `:spawn` child's `:on-done` and fires an `:after`, in the dev
  arm and in the production `:boundary? true` arm. An author event the
  schema refuses is still refused, and a non-machine handler's schema still
  sees every vector it is handed."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.spec :as rf.spec]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private snapshot rf.machines.test-support/snapshot)

(def ^:private NarrowEvent
  "Admits exactly the author's two events, and nothing the runtime mints."
  [:tuple [:= :mesr/p] [:tuple [:enum :go :wait]]])

(defn- event-refusals
  "The `:event-id` of every `:where :event` schema refusal so far."
  []
  (into []
        (comp (filter #(= :event (-> % :tags :where)))
              (map (comp :event-id :tags)))
        (rf.machines.test-support/events-of :rf.error/schema-validation-failure)))

(defn- reg-narrow-machine!
  "`:mesr/p` under `opts`. `:stray` is declared, so only the schema stops it."
  [opts]
  (rf/reg-machine :mesr/kid
    {:initial :w
     :states  {:w    {:on {:finish :done}}
               :done {:final? true}}})
  (rf/reg-machine :mesr/p opts
    {:initial :idle
     :states  {:idle      {:on {:go :waiting :stray :timed-out}}
               :waiting   {:spawn {:machine-id :mesr/kid :on-done {:target :finished}}}
               :finished  {:on {:wait :timing}}
               :timing    {:after {5000 :timed-out}}
               :timed-out {}}}))

(defn- drive-lifecycle!
  "The kick, a `:spawn` child's completion and an `:after` firing, each
  reaching `:mesr/p` as a reserved inner event. The timer's wire shape is
  dispatched as the host clock would (Spec 005 §Reserved synthetic events).
  Returns the state after each."
  []
  (rf/dispatch-sync [:mesr/p [:rf.machine/start]])
  (let [started (:state (snapshot :mesr/p))]
    (rf/dispatch-sync [:mesr/p [:go]])
    (rf/dispatch-sync [:mesr/kid#1 [:finish]])
    (let [on-done (:state (snapshot :mesr/p))]
      (rf/dispatch-sync [:mesr/p [:wait]])
      (let [epoch (get-in (snapshot :mesr/p) [:rf/runtime :after-epoch [:timing]])]
        (rf/dispatch-sync [:mesr/p [:rf.machine.timer/after-elapsed 5000 epoch [:timing]]]))
      {:started started :on-done on-done :after (:state (snapshot :mesr/p))})))

(deftest a-narrow-schema-machine-runs-its-lifecycle
  (reg-narrow-machine! {:schema NarrowEvent})
  (is (= {:started :idle :on-done :finished :after :timed-out}
         (drive-lifecycle!))
      "the kick starts it, the child's completion reaches :on-done, the :after fires")
  (is (= [] (event-refusals))
      "no reserved lifecycle event met the author's schema"))

(deftest a-narrow-schema-machine-runs-its-lifecycle-in-the-production-arm
  (with-redefs [rf.spec/dev-mode? (constantly false)]
    (reg-narrow-machine! {:schema NarrowEvent :boundary? true})
    (is (= {:started :idle :on-done :finished :after :timed-out}
           (drive-lifecycle!))
        "the :boundary? true check skips the reserved events too")))

(deftest the-narrow-schema-still-refuses-an-author-event-it-does-not-admit
  (reg-narrow-machine! {:schema NarrowEvent})
  (rf/dispatch-sync [:mesr/p [:rf.machine/start]])
  (rf/dispatch-sync [:mesr/p [:stray]])
  (is (= [[:mesr/p] :idle]
         [(event-refusals) (:state (snapshot :mesr/p))])
      "refused at :where :event, so the declared :stray transition never runs"))

(deftest a-non-machine-handler-schema-still-sees-a-reserved-inner-id
  (let [ran? (atom false)]
    (rf/reg-event :mesr/plain
      {:schema [:tuple [:= :mesr/plain] [:tuple [:= :go]]]}
      (fn [_ _] (reset! ran? true) {}))
    (rf/dispatch-sync [:mesr/plain [:rf.machine/start]])
    (is (= [false [:mesr/plain]]
           [@ran? (event-refusals)])
        "the skip is for machine handlers alone")))
