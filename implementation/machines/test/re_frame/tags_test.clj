(ns re-frame.tags-test
  "Per Spec 005 §State tags and rf2-ee0d (Nine States Stage 1).

  State-tag semantics covered:
    - Flat machine: the snapshot's :tags is the active state's tag set.
    - Compound machine: the snapshot's :tags is the union of every
      state-node's tag set along the active path from root to leaf.
    - No-tags machine: the snapshot has no :tags slot (empty union
      elided per Spec 005 §Snapshot shape change).
    - Tag recomputation on every transition, including :always microsteps.
    - The :rf/machine-has-tag? framework sub returns true iff the snapshot's
      :tags set contains the queried keyword.
    - pure machine-transition recomputes :tags without a frame.
    - Initial-snapshot synthesis stamps :tags before the first event.
    - Print/read round-trip: :tags is a set of keywords, no surprises.

  These JVM tests pair with the conformance fixtures
  spec/conformance/fixtures/tags-{flat,compound,empty,round-trip}.edn.
  The CLJS surface is exercised by re-frame.machines-cljs-test."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.machines :reload)
  (machines/reset-counters!)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- snapshot [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

;; ---- 1. flat machine, tags on each state ---------------------------------

(deftest tags-flat-active-state-union
  (testing "flat machine — snapshot's :tags is the active state's tag set"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:tags #{:active :empty}
                                 :on   {:fetch :loading}}
                       :loading {:tags #{:active :loading :transient}
                                 :on   {:loaded :done
                                        :failed :error}}
                       :done    {:tags #{:done :read-only :terminal}}
                       :error   {:tags #{:error :terminal}
                                 :on   {:fetch :loading}}}}]
      (rf/reg-machine :tags/flat m)
      ;; First event materialises the initial snapshot. The snapshot's
      ;; :tags must reflect :idle's tags AFTER it transitions to :loading.
      (rf/dispatch-sync [:tags/flat [:fetch]])
      (is (= #{:active :loading :transient}
             (:tags (snapshot :tags/flat)))
          ":loading's tag set on the committed snapshot")
      (rf/dispatch-sync [:tags/flat [:loaded]])
      (is (= #{:done :read-only :terminal}
             (:tags (snapshot :tags/flat)))
          ":done's tag set on the committed snapshot"))))

;; ---- 2. compound machine, active-configuration union ---------------------

(deftest tags-compound-active-configuration-union
  (testing "compound machine — :tags is the union along the active path"
    (let [m {:initial :authenticated
             :data    {}
             :states
             {:authenticated
              {:tags    #{:auth :gated}
               :initial :dashboard
               :states
               {:dashboard {:tags #{:home :read-only}
                            :on   {:open-settings :settings}}
                :settings  {:tags #{:settings :writable}
                            :on   {:close :dashboard}}}}}}]
      (rf/reg-machine :tags/compound m)
      ;; Initial-cascade lands at [:authenticated :dashboard]; the
      ;; first-event dispatch synthesises the snapshot. We need ONE event
      ;; to force initialisation.
      (rf/dispatch-sync [:tags/compound [:open-settings]])
      (is (= [:authenticated :settings] (:state (snapshot :tags/compound)))
          "transitioned to the settings leaf")
      (is (= #{:auth :gated :settings :writable}
             (:tags (snapshot :tags/compound)))
          "snapshot's :tags is the union of every active state-node's :tags"))))

;; ---- 3. no-tags machine — empty union, slot elided -----------------------

(deftest tags-empty-when-no-declaration
  (testing "no-tags machine — :tags slot is elided (empty union)"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:on {:go :done}}
                       :done    {}}}]
      (rf/reg-machine :tags/none m)
      (rf/dispatch-sync [:tags/none [:go]])
      (let [s (snapshot :tags/none)]
        (is (= :done (:state s)) "transitioned to :done")
        (is (not (contains? s :tags))
            "empty tag union is elided — :tags key absent from the snapshot map"))))

  (testing "machine with :tags on SOME states but not others — empty union elides"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:tags #{:loading}
                                 :on   {:done :resolved}}
                       :resolved {}}}]
      (rf/reg-machine :tags/partial m)
      (rf/dispatch-sync [:tags/partial [:done]])
      (let [s (snapshot :tags/partial)]
        (is (= :resolved (:state s)))
        (is (not (contains? s :tags))
            "transitioned to a tag-less state — :tags elided from snapshot")))))

;; ---- 4. tags recomputed on :always microsteps ---------------------------

(deftest tags-recomputed-on-always-microstep
  (testing ":always microstep recomputes :tags on the committed snapshot"
    (let [m {:initial :asking
             :data    {:correct-count 0}
             :guards  {:enough? (fn [d _] (>= (:correct-count d) 1))}
             :actions {:bump   (fn [d _] {:data (update d :correct-count inc)})}
             :states
             {:asking
              {:tags   #{:active}
               :always [{:guard :enough? :target :winner}]
               :on     {:answer-correct {:action :bump}}}
              :winner {:tags #{:terminal :celebrate}}
              :loser  {:tags #{:terminal :sad}}}}]
      (rf/reg-machine :tags/always m)
      (rf/dispatch-sync [:tags/always [:answer-correct]])
      ;; The action bumps correct-count to 1 → :always fires → moves
      ;; to :winner in a single macrostep. The committed snapshot's
      ;; :tags must reflect :winner's tag set, NOT :asking's.
      (is (= :winner (:state (snapshot :tags/always))))
      (is (= #{:terminal :celebrate}
             (:tags (snapshot :tags/always)))
          ":tags reflects the post-:always-microstep state"))))

;; ---- 5. pure machine-transition surface ----------------------------------

(deftest tags-pure-machine-transition
  (testing "pure machine-transition produces a :tags-bearing snapshot"
    (let [m {:initial :a
             :data    {}
             :states  {:a {:tags #{:start} :on {:next :b}}
                       :b {:tags #{:middle :transient} :on {:next :c}}
                       :c {:tags #{:end}}}}
          [snap1 _] (machines/machine-transition m {:state :a :data {}} [:next])]
      (is (= :b (:state snap1)))
      (is (= #{:middle :transient} (:tags snap1))
          ":tags stamped on the pure-transition output")
      (let [[snap2 _] (machines/machine-transition m snap1 [:next])]
        (is (= :c (:state snap2)))
        (is (= #{:end} (:tags snap2))))))

  (testing "pure machine-transition elides :tags on a no-tags machine"
    (let [m {:initial :a
             :data    {}
             :states  {:a {:on {:next :b}}
                       :b {}}}
          [snap _]  (machines/machine-transition m {:state :a :data {}} [:next])]
      (is (= :b (:state snap)))
      (is (not (contains? snap :tags))
          "empty tag union elided on pure-transition output"))))

;; ---- 6. initial snapshot carries :tags before first event ---------------

(deftest tags-stamped-on-initial-snapshot
  (testing "first :rf/machine read returns :tags from initial-state declaration"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle {:tags #{:initial-ready :idle}}
                       :on   {}}}]
      (rf/reg-machine :tags/seed m)
      ;; A no-op event materialises the initial snapshot. Dispatch a
      ;; spurious event the handler won't transition on; the snapshot
      ;; still gets written at handler-call time.
      (rf/dispatch-sync [:tags/seed [:no-op-event]])
      (let [s (snapshot :tags/seed)]
        (is (= :idle (:state s)) "initial state lands at :idle")
        (is (= #{:initial-ready :idle} (:tags s))
            ":tags stamped from initial-state declaration before any transition")))))

;; ---- 7. round-trip via print/read ---------------------------------------

(deftest tags-pr-str-read-round-trip
  (testing ":tags is print/read round-trippable — keyword sets, no surprises"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle {:tags #{:active :a/qualified :nested.thing/keyword}
                              :on   {:go :done}}
                       :done {:tags #{:done}}}}]
      (rf/reg-machine :tags/print m)
      (rf/dispatch-sync [:tags/print [:no-op]])
      (let [snap          (snapshot :tags/print)
            serialised    (pr-str snap)
            deserialised  (edn/read-string serialised)]
        (is (= snap deserialised)
            "round-trip pr-str → read-string yields = value")
        (is (= #{:active :a/qualified :nested.thing/keyword}
               (:tags deserialised))
            ":tags set round-trips with qualified keywords intact")))))

;; ---- 8. :rf/machine-has-tag? framework sub ------------------------------

(deftest machine-has-tag-sub
  (testing ":rf/machine-has-tag? returns true iff :tags contains the tag"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:tags #{:loading :transient}
                                 :on   {:done :resolved}}
                       :resolved {:tags #{:done}}}}]
      (rf/reg-machine :tags/sub m)
      (rf/dispatch-sync [:tags/sub [:no-op]])
      ;; Direct read against app-db via compute-sub-style invocation.
      ;; The sub registers via subs/reg-sub so dispatch-sync's drain
      ;; doesn't gate the sub's value — we read it through the standard
      ;; subscribe surface.
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :tags/sub :loading]))
          ":loading is in the active tag set")
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :tags/sub :transient])))
      (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/sub :done]))
          ":done is NOT in the active tag set")
      (rf/dispatch-sync [:tags/sub [:done]])
      ;; After the transition, the booleans flip.
      (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/sub :loading])))
      (is (= true  @(rf/subscribe [:rf/machine-has-tag? :tags/sub :done])))))

  (testing ":rf/machine-has-tag? returns false for an unknown machine"
    (is (= false @(rf/subscribe [:rf/machine-has-tag? :tags/unknown-machine :x]))
        "no snapshot for the id → false (null-tolerant)")))

;; ---- 9. has-tag? sugar on the core namespace ----------------------------

(deftest has-tag-sugar
  (testing "rf/has-tag? returns the same reaction as the framework sub"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle {:tags #{:loading}}}}]
      (rf/reg-machine :tags/sugar m)
      (rf/dispatch-sync [:tags/sugar [:no-op]])
      (is (= true  @(rf/has-tag? :tags/sugar :loading))
          "sugar resolves through the framework sub")
      (is (= false @(rf/has-tag? :tags/sugar :missing))))))

;; ---- 10. internal transition recomputes :tags consistently --------------

(deftest tags-on-internal-transitions
  (testing "internal transition (no :target) leaves state unchanged; :tags also unchanged"
    (let [m {:initial :idle
             :data    {:count 0}
             :actions {:bump (fn [d _] {:data (update d :count inc)})}
             :states  {:idle {:tags #{:steady}
                              :on   {:tick {:action :bump}}}}}]
      (rf/reg-machine :tags/internal m)
      (rf/dispatch-sync [:tags/internal [:tick]])
      (let [s (snapshot :tags/internal)]
        (is (= :idle (:state s)))
        (is (= 1 (get-in s [:data :count])))
        (is (= #{:steady} (:tags s))
            ":tags re-stamped (idempotently) after the internal transition")))))
