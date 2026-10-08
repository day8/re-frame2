(ns re-frame.story-decorator-chain-test
  "Decorator-chain composition run end to end, and the per-variant
  frame-isolation pair (002-Runtime §Decorator composition, §Per-variant
  frame allocation)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            ;; `:rf.assert/effect-emitted` reads the epoch tape.
            [re-frame.epoch]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.frames     :as rf.story.frames]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play       :as rf.story.play]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (reset! rf.story.play/stepper-state            {})
  (reset! rf.story.frames/stub-call-log          {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

(defn- run-v! [vid]
  (rf.story.async/deref-blocking (rf.story/run-variant vid) 5000))

(defn- hiccup-ids [vid]
  (mapv :id (:hiccup (rf.story/resolve-decorators vid))))

(deftest hiccup-multi-decorator-applies-in-declared-order
  (testing "story decorators in declared order wrap outside variant decorators
            in declared order"
    (doseq [k ["A" "B" "C" "D"]]
      (rf.story/reg-decorator (keyword (str "wrap-" k))
        {:kind :hiccup :wrap (fn [body _] [(keyword (str "div." k)) body])}))
    (rf.story/reg-story :story.chain {:decorators [[:wrap-A] [:wrap-B]]})
    (rf.story/reg-variant :story.chain/v {:decorators [[:wrap-C] [:wrap-D]] :setup []})
    (is (= [:div.A [:div.B [:div.C [:div.D [:span "leaf"]]]]]
           (rf.story.decorators/apply-hiccup-decorators
             (:hiccup (rf.story/resolve-decorators :story.chain/v)) [:span "leaf"] {})))))

(deftest multi-kind-stack-composition-runs-on-canvas
  (testing "one :hiccup, one :frame-setup and one :fx-override decorator
            classify into their slots and run together: the :init seed lands
            before :setup and the fx stub is live"
    (rf.story/reg-decorator :centered-pane {:kind :hiccup :wrap (fn [body _] [:div.centered body])})
    (rf/reg-event :mock/seed
      (fn [{:keys [db]} _] {:db (assoc db :mock-user {:name "alice" :role :admin})}))
    (rf.story/reg-decorator :seed-user {:kind :frame-setup :init [[:mock/seed]]})
    (rf/reg-event :record/observed (fn [{:keys [db]} _] {:db (assoc db :seen-user (:mock-user db))}))
    (rf/reg-event :emit/track (fn [_ _] {:fx [[:analytics {:event :loaded}]]}))
    (rf.story/reg-variant :story.multi-kind/v
      {:decorators [[:centered-pane]
                    [:seed-user]
                    [:rf.story/force-fx-stub :analytics {:ack? true}]]
       :setup      [[:record/observed]]
       :script     [[:dispatch-sync [:rf.assert/path-equals [:seen-user :name] "alice"]]
                    [:dispatch-sync [:emit/track]]
                    [:dispatch-sync [:rf.assert/effect-emitted :analytics]]]})
    (is (= [1 1 1 0]
           (mapv #(count (get (rf.story/resolve-decorators :story.multi-kind/v) %))
                 [:hiccup :frame-setup :fx-override :errors])))
    (let [r (run-v! :story.multi-kind/v)]
      (is (= [:ready "alice" [true true]]
             [(:lifecycle r) (-> r :app-db :seen-user :name) (mapv :passed? (:assertions r))])))
    (rf.story/destroy-variant! :story.multi-kind/v)))

(deftest extends-inherits-decorators-when-child-declares-none
  (testing "the plan compiler folds :decorators through :extends child-wins:
            a bare child inherits the parent's, an own slot replaces them"
    (rf.story/reg-decorator :parent-only-deco
      {:kind :hiccup :wrap (fn [body _] [:div.parent-only body])})
    (rf.story/reg-decorator :child-replacement
      {:kind :hiccup :wrap (fn [body _] [:div.child body])})
    (rf.story/reg-variant :story.ext.dec/parent {:decorators [[:parent-only-deco]] :setup []})
    (rf.story/reg-variant :story.ext.dec/inherit-bare {:extends :story.ext.dec/parent :setup []})
    (rf.story/reg-variant :story.ext.dec/inherit-and-replace
      {:extends :story.ext.dec/parent :decorators [[:child-replacement]] :setup []})
    (is (= [[:parent-only-deco] [:child-replacement]]
           (mapv hiccup-ids [:story.ext.dec/inherit-bare :story.ext.dec/inherit-and-replace])))))

(deftest frame-isolation-pair-app-db-and-emitted-fx
  (testing "two variants sharing event and decorator ids, differing only in
            their :frame-setup seed, keep separate app-db, assertions and
            per-frame stub logs"
    (rf/reg-event :seed/at-100 (fn [{:keys [db]} _] {:db (assoc db :counter 100)}))
    (rf/reg-event :seed/at-200 (fn [{:keys [db]} _] {:db (assoc db :counter 200)}))
    (rf/reg-event :inc-and-track
      (fn [{:keys [db]} _]
        {:db (update db :counter inc)
         :fx [[:analytics {:event :inc :from (:counter db)}]]}))
    (rf.story/reg-decorator :seed-A {:kind :frame-setup :init [[:seed/at-100]]})
    (rf.story/reg-decorator :seed-B {:kind :frame-setup :init [[:seed/at-200]]})
    (doseq [[vid seed n] [[:story.isolation/A :seed-A 100] [:story.isolation/B :seed-B 200]]]
      (rf.story/reg-variant vid
        {:decorators [[seed] [:rf.story/force-fx-stub :analytics {:ack? true}]]
         :setup      [[:inc-and-track] [:inc-and-track]]
         :script     [[:dispatch-sync [:rf.assert/path-equals [:counter] (+ n 2)]]
                      [:dispatch-sync [:inc-and-track]]
                      [:dispatch-sync [:rf.assert/effect-emitted :analytics]]
                      [:dispatch-sync [:rf.assert/path-equals [:counter] (+ n 3)]]]}))
    (doseq [[vid n] [[:story.isolation/A 100] [:story.isolation/B 200]]]
      (let [r (run-v! vid)]
        (is (= [:ready (+ n 3) true] [(:lifecycle r) (-> r :app-db :counter)
                                      (every? :passed? (:assertions r))]))
        (is (= (mapv (fn [from] {:event :inc :from from}) (range n (+ n 3)))
               (mapv :payload (rf.story.frames/stub-call-log-for vid)))
            "each frame's stub log carries exactly its own three emissions")))
    (rf.story/destroy-variant! :story.isolation/A)
    (rf.story/destroy-variant! :story.isolation/B)))

(deftest frame-isolation-pair-destroy-one-survives-other
  (rf/reg-event :ping (fn [{:keys [db]} _] {:db (update db :pings (fnil inc 0))}))
  (rf.story/reg-variant :story.iso2/A {:setup [[:ping]]})
  (rf.story/reg-variant :story.iso2/B {:setup [[:ping]]})
  (run-v! :story.iso2/A)
  (run-v! :story.iso2/B)
  (rf.story/destroy-variant! :story.iso2/A)
  (is (= [false true] (mapv rf.story/variant-frame? [:story.iso2/A :story.iso2/B])))
  (let [r (rf.story.async/deref-blocking (rf.story/reset-variant :story.iso2/B) 5000)]
    (is (= [:ready 1] [(:lifecycle r) (-> r :app-db :pings)])))
  (rf.story/destroy-variant! :story.iso2/B))
