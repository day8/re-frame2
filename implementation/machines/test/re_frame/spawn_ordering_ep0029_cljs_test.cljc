(ns re-frame.spawn-ordering-ep0029-cljs-test
  "EP-0029 A7 §spawn ordering — the deterministic-allocation lock.

  EP-0029 (docs/EP/EP-0029-xstate-v6-machine-parity.md §A7) makes three
  spawn-correctness claims; one of them is ORDERING:

    > restored parent/child runtime snapshots should restart active children
    > consistently [...] multiple spawns are deterministic.

  Spec 005 §Spawn lifecycle — ordering pins the mechanism: the spawn-id
  allocator picks the next `<id-prefix>#<n>` against `:rf/spawn-counter` at the
  parent snapshot root, and the entry cascade reduces over `entered-pairs`
  SHALLOWEST-FIRST (root → leaf). So when a single transition's entry cascade
  crosses MORE THAN ONE `:spawn`-bearing node, the shallower node's child is
  allocated `#1` and the deeper node's child `#2` — a deterministic, declaration-
  order allocation. `:spawn-all` allocates its children in declared-order too.

  The pre-existing spawn suite covered counter allocation through SEPARATE
  state entries (each entered on its own event) and reverse-creation DISPOSAL
  order (frame_destroy_cascade_test). This file locks the remaining ordering
  fact the EP A7 wording asserts and that no single test pinned: deterministic
  allocation order when MULTIPLE spawns fire in ONE entry cascade.

  Runs under both cognitect.test-runner (JVM, plain-atom) and shadow-cljs
  (CLJS, Reagent) — the `.cljc` shape matching machines_on_error_cljs_test.cljc.

  Spec contract: [Spec 005 §Spawn lifecycle — ordering], [EP-0029 §A7]."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading `re-frame.machines` installs the late-bind hooks
   ;; `reg-machine` resolves through.
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(defn- spawned-id-for
  [parent-id invoke-id]
  (get-in (rf/runtime-db-value :rf/default)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

;; ---- (1) two :spawn nodes entered in ONE cascade allocate in --------------
;;          declaration / shallowest-first order (#1 then #2).
;;
;; A compound parent whose entered state-node carries `:spawn` AND whose
;; entered-into descendant ALSO carries `:spawn` crosses two spawn-bearing
;; nodes in a single transition's entry cascade. `run-spawn-phase` reduces over
;; `entered-pairs` shallowest-first, so the OUTER (shallower) node's child is
;; `#1` and the INNER (deeper) node's child is `#2` — deterministic, per the EP
;; A7 ordering claim. Both spawns share the same `:rf/spawn-counter` of the
;; parent, so the two allocations are strictly ordered.

(deftest two-spawns-in-one-cascade-allocate-shallowest-first
  (testing "entering a compound node with :spawn whose initial descendant ALSO has :spawn allocates outer=#1, inner=#2 (deterministic shallowest-first order)"
    (rf/reg-machine :ord1/leaf
      {:initial :running :data {} :states {:running {}}})
    ;; `:outer` is a COMPOUND state that itself declares `:spawn` and whose
    ;; `:initial` child `:inner` ALSO declares `:spawn`. Entering `:outer`
    ;; descends to `:inner` in one cascade, crossing both spawn-bearing nodes.
    (rf/reg-machine :ord1/parent
      {:initial :idle
       :data    {}
       :states
       {:idle  {:on {:go :outer}}
        :outer {:spawn   {:machine-id :ord1/leaf :id-prefix :ord1/leaf}
                :initial :inner
                :states  {:inner {:spawn {:machine-id :ord1/leaf
                                          :id-prefix  :ord1/leaf}}}}}})
    (rf/dispatch-sync [:ord1/parent [:go]])
    ;; Two children of the SAME TYPE share one parent counter → strict #1/#2.
    (let [outer-child (spawned-id-for :ord1/parent [:outer])
          inner-child (spawned-id-for :ord1/parent [:outer :inner])]
      (is (= :ord1/leaf#1 outer-child)
          "the OUTER (shallower) :spawn allocated #1 — entered first in the cascade")
      (is (= :ord1/leaf#2 inner-child)
          "the INNER (deeper) :spawn allocated #2 — entered second")
      (is (some? (snapshot outer-child)) "outer child is alive")
      (is (some? (snapshot inner-child)) "inner child is alive")
      ;; The parent's per-TYPE spawn counter advanced to exactly 2.
      (is (= 2 (get-in (snapshot :ord1/parent) [:rf/spawn-counter :ord1/leaf]))
          "the parent's :rf/spawn-counter advanced by exactly the two spawns, in one cascade"))))

;; ---- (2) :spawn-all allocates its children in DECLARED order --------------
;;
;; A `:spawn-all` fans out N children; the EP A7 ordering claim covers
;; `:spawn-all` as well as `:spawn`. The children are allocated in the order
;; they are DECLARED in the `:children` vector — first declared = #1.

(deftest spawn-all-allocates-children-in-declared-order
  (testing ":spawn-all allocates its children in the order they appear in :children (#1 first-declared, #2 second-declared)"
    (rf/reg-machine :ord2/leaf
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :ord2/parent
      {:initial :idle
       :data    {}
       :states
       {:idle      {:on {:fan-out :hydrating}}
        :hydrating {:spawn-all
                    {:children       [{:id :first  :machine-id :ord2/leaf}
                                      {:id :second :machine-id :ord2/leaf}
                                      {:id :third  :machine-id :ord2/leaf}]
                     :join           :all
                     :on-child-done  :child/done
                     :on-child-error :child/error
                     :on-all-complete [:done]}}}})
    (rf/dispatch-sync [:ord2/parent [:fan-out]])
    ;; The join-state records each child id under its declared :id key; the
    ;; allocation order is reflected in the per-TYPE counter (#1/#2/#3).
    (let [children (get-in (rf/runtime-db-value :rf/default)
                           [:rf.runtime/machines :spawned :ord2/parent [:hydrating] :children])]
      (is (= :ord2/leaf#1 (:first children))
          "first-declared child allocated #1")
      (is (= :ord2/leaf#2 (:second children))
          "second-declared child allocated #2")
      (is (= :ord2/leaf#3 (:third children))
          "third-declared child allocated #3")
      (is (= 3 (get-in (snapshot :ord2/parent) [:rf/spawn-counter :ord2/leaf]))
          "the parent's :rf/spawn-counter advanced by exactly the three fan-out children"))))

;; ---- (3) post-action parent data + ordering combined (EP A7 §1 + ordering) ----
;;
;; The headline EP A7 invariant: a spawn's `:data` fn sees the parent's
;; POST-ACTION `:data` (the transition's `:action` has already run). This is
;; covered for a single spawn in spawn_data_fn_form_test.clj; here we lock that
;; it ALSO holds when MULTIPLE spawns fire in the same cascade — each child's
;; `:data` fn sees the same post-action parent snapshot, and the allocation is
;; still deterministic. This guards against a future refactor evaluating a
;; later child's `:data` against a pre-action snapshot or in a non-deterministic
;; order.

(deftest both-spawns-see-post-action-data-deterministically
  (testing "when two spawns fire in one cascade, BOTH :data fns see the post-action parent :data, allocated #1 then #2"
    (rf/reg-machine :ord3/leaf
      {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :ord3/parent
      {:initial :idle
       :data    {:base "https://api.example.com"}
       :actions {:assemble (fn [{data :data}]
                             ;; The transition action writes :endpoint; BOTH
                             ;; spawn :data fns (outer + inner) must see it.
                             {:data (assoc data :endpoint
                                           (str (:base data) "/v1/me"))})}
       :states
       {:idle  {:on {:go {:target :outer :action :assemble}}}
        :outer {:spawn   {:machine-id :ord3/leaf :id-prefix :ord3/leaf
                          :data (fn [{snap :snapshot}]
                                  {:url (-> snap :data :endpoint) :who :outer})}
                :initial :inner
                :states  {:inner {:spawn {:machine-id :ord3/leaf :id-prefix :ord3/leaf
                                          :data (fn [{snap :snapshot}]
                                                  {:url (-> snap :data :endpoint) :who :inner})}}}}}})
    (rf/dispatch-sync [:ord3/parent [:go]])
    (let [outer-child (spawned-id-for :ord3/parent [:outer])
          inner-child (spawned-id-for :ord3/parent [:outer :inner])]
      (is (= :ord3/leaf#1 outer-child) "outer allocated #1")
      (is (= :ord3/leaf#2 inner-child) "inner allocated #2")
      (is (= "https://api.example.com/v1/me"
             (:url (:data (snapshot outer-child))))
          "OUTER child's :data fn saw the post-action :endpoint")
      (is (= :outer (:who (:data (snapshot outer-child))))
          "outer child got its own :data")
      (is (= "https://api.example.com/v1/me"
             (:url (:data (snapshot inner-child))))
          "INNER child's :data fn ALSO saw the post-action :endpoint (same post-action snapshot)")
      (is (= :inner (:who (:data (snapshot inner-child))))
          "inner child got its own :data"))))
