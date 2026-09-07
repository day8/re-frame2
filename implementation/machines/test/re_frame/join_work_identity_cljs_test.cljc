(ns re-frame.join-work-identity-cljs-test
  "Cross-platform counterfixtures for `:spawn-all` canonical work identity.

  A fixed actor address is reusable after teardown, so its name cannot identify
  a join attempt. These tests drive real CLJ/CLJS machine cascades and require
  every reply-bearing path to reuse the runtime-minted `:rf/attempt` token
  as the fixed actor's existing machine-work-id generation slot. Generated
  `<type>#<n>` actors keep their actor-name generation, and a normal fixed-id
  single spawn keeps generation 1."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines]
   [re-frame.machines.reply :as rf.machines.reply]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- completing-child
  "A join child that completes the ONE way every machine completes: `:go`
  reaches the top-level `:final?` leaf `:done`, `:fail` the `:error? true` leaf
  `:failed`. It names no parent and no completion event — the runtime's finalize
  cascade mints the carrier, reading the exact-attempt coordinate off the
  child's own `:rf/join-child` record. That record IS the provenance this file
  is about, so it now reaches the fold by the same route the runtime uses."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:remember-id (fn [{data :data event :event}]
                            {:data (assoc data :id (second event))})}
   :states  {:running {:on {:set-id {:action :remember-id}
                            :go     {:target :done}
                            :fail   {:target :failed}}}
             :done    {:final? true :output-key :id}
             :failed  {:final? true :error? true :output-key :id}}})

(defn- register-fixed-parent!
  [parent-id child-a-type child-b-type fixed-a fixed-b join]
  (rf/reg-machine child-a-type (completing-child))
  (rf/reg-machine child-b-type (completing-child))
  (rf/reg-machine
    parent-id
    {:initial :idle
     :states
     {:idle   {:on {:start :racing}}
      :racing {:spawn-all
               {:children [{:id :a :machine-id child-a-type
                            :fixed-actor-id fixed-a :start [:set-id :a]}
                           {:id :b :machine-id child-b-type
                            :fixed-actor-id fixed-b :start [:set-id :b]}]
                :join join
                :on-all-complete [:join/all]
                :on-some-complete [:join/some]}
               ;; Deliberately leave resolution events unhandled so the frozen
               ;; join record remains available for exact-current late probes.
               :on {:abort :idle}}}})
  (rf/dispatch-sync [parent-id [:start]]))

(defn- register-generated-successor-parent!
  [parent-id child-a-type child-b-type fixed-b]
  (rf/reg-machine
    parent-id
    {:initial :idle
     :states
     {:idle   {:on {:start :racing}}
      :racing {:spawn-all
               {:children [{:id :a :machine-id child-a-type :start [:set-id :a]}
                           {:id :b :machine-id child-b-type
                            :fixed-actor-id fixed-b :start [:set-id :b]}]
                :join :all
                :on-all-complete [:join/all]}
               :on {:abort :idle}}}})
  (rf/dispatch-sync [parent-id [:start]]))

(defn- register-imperative-destroy-parent!
  [parent-id child-a-type child-b-type fixed-a fixed-b join
   child-a-machine]
  (rf/reg-machine child-a-type child-a-machine)
  (rf/reg-machine child-b-type (completing-child))
  (rf/reg-machine
    parent-id
    {:initial :idle
     :actions {:destroy-folded-a
               (fn [_]
                 {:fx [[:rf.machine/destroy fixed-a]]})}
     :states
     {:idle   {:on {:start :racing}}
      :racing {:spawn-all
               {:children [{:id :a :machine-id child-a-type
                            :fixed-actor-id fixed-a :start [:set-id :a]}
                           {:id :b :machine-id child-b-type
                            :fixed-actor-id fixed-b :start [:set-id :b]}]
                :join join
                :on-all-complete [:join/all]
                :on-some-complete [:join/some]}
               ;; A separate parent event runs only after the test observes the
               ;; accepted non-decisive fold, then imperatively tears A down
               ;; without leaving the unresolved spawn-all state.
               :on {:destroy-a {:action :destroy-folded-a}
                    :abort :idle}}}})
  (rf/dispatch-sync [parent-id [:start]]))

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- join-attempt [actor-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :snapshots actor-id :data :rf/join-child]))

(defn- events-of [op]
  (rf.machines.test-support/events-of op))

(defn- work-id-of [event]
  (:rf.reply/work-id (:tags event)))

(defn- terminal-statuses-for [work-id]
  (into []
        (comp (map :tags)
              (filter #(= work-id (:rf.reply/work-id %)))
              (keep :rf.reply/work-status)
              (filter #{:completed :failed :cancelled}))
        (rf.machines.test-support/captured-events)))

(defn- forged-completion!
  "Hand-dispatch the reserved completion carrier
  `[<parent> [:rf.machine.spawn/done <invoke-id> <completion>]]` that
  `lifecycle-fx.finalize` mints at a child's finality, carrying `auth` — a
  `:rf/join-child` membership record read off a live child's snapshot — as the
  exact-attempt coordinate.

  The coordinate rides IN THE CARRIER, which is the only slot the fold reads.
  Hand-authoring the carrier is how this file drives the duplicate / late /
  superseded arrivals the runtime itself would never re-mint: read `auth` while
  the child is alive, then deliver it after its attempt has closed."
  [parent-id auth]
  (rf/dispatch-sync
    [parent-id [:rf.machine.spawn/done (:invoke-id auth)
                (assoc (select-keys auth [:parent-id :invoke-id :child-id
                                          :spawned-id :attempt :work-generation])
                       :result (:child-id auth)
                       :error? false)]]))

(deftest numeric-tail-fixed-id-uses-runtime-attempt-not-keyword-spelling
  (testing "sequential attempts at a fixed address ending in #7 get distinct work ids"
    (register-fixed-parent! :jwi/numeric-parent
                            :jwi/numeric-a-type :jwi/numeric-b-type
                            :jwi/fixed-a#7 :jwi/fixed-b :all)
    (let [attempt-a (:rf/attempt (join-state :jwi/numeric-parent))]
      (rf/dispatch-sync [:jwi/fixed-a#7 [:go]])
      (let [work-a (work-id-of
                     (first (events-of :rf.machine.spawn-all/child-completed)))]
        (rf/dispatch-sync [:jwi/numeric-parent [:abort]])
        (rf/dispatch-sync [:jwi/numeric-parent [:start]])
        (let [attempt-b (:rf/attempt (join-state :jwi/numeric-parent))
              tampered-generation (if (= attempt-b 7) 8 7)
              ;; Tamper a non-authoritative carrier field to the misleading
              ;; keyword suffix (or a distinct sentinel if the opaque attempt
              ;; itself happens to be 7). The interceptor must derive from the
              ;; durable join spec/attempt, never trust this carried value.
              tampered-auth (assoc (join-attempt :jwi/fixed-a#7)
                                   :work-generation tampered-generation)]
          (rf.machines.test-support/reset-captured!)
          (forged-completion! :jwi/numeric-parent tampered-auth)
          (let [work-b (work-id-of
                         (first (events-of :rf.machine.spawn-all/child-completed)))]
            (is (= [:rf.work/machine :jwi/fixed-a#7 [:racing] attempt-a]
                   work-a))
            (is (= [:rf.work/machine :jwi/fixed-a#7 [:racing] attempt-b]
                   work-b))
            (is (not= tampered-generation (last work-b))
                "carried work-generation tampering cannot override runtime provenance")
            (is (not= work-a work-b)
                "the literal #7 suffix never overrides fixed-id provenance")))))))

(deftest superseded-evidence-keeps-the-old-attempts-explicit-provenance
  (testing "a fixed old attempt is not reclassified by a generated successor spec"
    (register-fixed-parent! :jwi/spec-change-parent
                            :jwi/spec-change-a-type :jwi/spec-change-b-type
                            :jwi/spec-change-fixed-a#7
                            :jwi/spec-change-fixed-b :all)
    (let [attempt-a (:rf/attempt (join-state :jwi/spec-change-parent))
          auth-a    (join-attempt :jwi/spec-change-fixed-a#7)
          old-work  [:rf.work/machine :jwi/spec-change-fixed-a#7
                     [:racing] attempt-a]]
      (rf/dispatch-sync [:jwi/spec-change-parent [:abort]])
      ;; Model HMR/re-entry changing the same logical child from fixed to
      ;; generated. The live successor spec cannot classify old evidence.
      (register-generated-successor-parent!
        :jwi/spec-change-parent
        :jwi/spec-change-a-type :jwi/spec-change-b-type
        :jwi/spec-change-fixed-b)
      (let [successor (get-in (join-state :jwi/spec-change-parent)
                              [:children :a])]
        (is (not= :jwi/spec-change-fixed-a#7 successor))
        (is (not= attempt-a (:rf/attempt (join-state :jwi/spec-change-parent))))
        (rf.machines.test-support/reset-captured!)
        (forged-completion! :jwi/spec-change-parent auth-a)
        (let [stale-work (work-id-of
                           (first (events-of
                                    :rf.machine.spawn-all/stale-completion)))]
          (is (= old-work stale-work)
              "superseded evidence uses its carried old provenance")
          (is (not= 7 (last stale-work))
              "the successor's generated policy cannot parse the old fixed name"))))))

(deftest pre-resolution-exit-does-not-cancel-an-already-folded-child
  (testing "a non-decisive completion stays A's ONLY outcome when the parent exits"
    (register-fixed-parent! :jwi/exit-parent
                            :jwi/exit-a-type :jwi/exit-b-type
                            :jwi/exit-fixed-a :jwi/exit-fixed-b :all)
    (let [attempt (:rf/attempt (join-state :jwi/exit-parent))
          work-a  [:rf.work/machine :jwi/exit-fixed-a [:racing] attempt]
          work-b  [:rf.work/machine :jwi/exit-fixed-b [:racing] attempt]]
      ;; A completes and folds, non-decisively for :all — and, completion being
      ;; finality, destroys ITSELF on the way out. Exiting before B reports must
      ;; therefore find nothing of A left to cancel, and cancel only B.
      (rf/dispatch-sync [:jwi/exit-fixed-a [:go]])
      (rf/dispatch-sync [:jwi/exit-parent [:abort]])
      ;; Two agreeing rows on A's work-id: its own finality reply and the join's
      ;; non-decisive fold. The parent's exit adds no third, contradictory one.
      (is (= [:completed :completed] (terminal-statuses-for work-a))
          "A's terminals all agree on :completed — the exit adds no cancellation")
      (is (= [:cancelled] (terminal-statuses-for work-b))
          "the still-running sibling is cancelled exactly once")
      (is (= :rf.machine/finished
             (->> (events-of :rf.machine/destroyed)
                  (filter #(= :jwi/exit-fixed-a (get-in % [:tags :actor-id])))
                  first :tags :reason))
          "A's physical teardown is its OWN finality, not a parent-exit cancellation"))))

(deftest imperative-destroy-of-a-done-folded-child-adds-no-terminal
  (testing "an unresolved :all join has folded A's done completion, and an
            imperative destroy of that already-finished address adds nothing"
    (register-imperative-destroy-parent!
      :jwi/direct-done-parent :jwi/direct-done-a-type :jwi/direct-done-b-type
      :jwi/direct-done-a#7 :jwi/direct-done-b :all
      (completing-child))
    (let [attempt (:rf/attempt (join-state :jwi/direct-done-parent))
          work-a  [:rf.work/machine :jwi/direct-done-a#7 [:racing] attempt]]
      (rf/dispatch-sync [:jwi/direct-done-a#7 [:go]])
      (is (= #{:a} (:done (join-state :jwi/direct-done-parent))))
      (is (false? (:resolved? (join-state :jwi/direct-done-parent))))
      ;; A destroyed itself at finality, so this imperative destroy names a dead
      ;; address — a silent idempotent no-op, never a late cancellation.
      (rf/dispatch-sync [:jwi/direct-done-parent [:destroy-a]])
      (is (= [:completed :completed] (terminal-statuses-for work-a))
          "imperative teardown cannot add cancelled after accepted done")
      (is (= [:rf.machine/finished]
             (mapv #(get-in % [:tags :reason])
                   (filter #(= :jwi/direct-done-a#7 (get-in % [:tags :actor-id]))
                           (events-of :rf.machine/destroyed))))
          "exactly one destroyed trace for A, its own finality"))))

(deftest imperative-destroy-of-a-failed-folded-child-adds-no-terminal
  (testing "an unresolved :any join has folded A's failed completion, and an
            imperative destroy of that already-finished address adds nothing"
    (register-imperative-destroy-parent!
      :jwi/direct-failed-parent
      :jwi/direct-failed-a-type :jwi/direct-failed-b-type
      :jwi/direct-failed-a#7 :jwi/direct-failed-b :any
      (completing-child))
    (let [attempt (:rf/attempt (join-state :jwi/direct-failed-parent))
          work-a  [:rf.work/machine :jwi/direct-failed-a#7 [:racing] attempt]]
      (rf/dispatch-sync [:jwi/direct-failed-a#7 [:fail]])
      (is (= #{:a} (:failed (join-state :jwi/direct-failed-parent))))
      (is (false? (:resolved? (join-state :jwi/direct-failed-parent))))
      (rf/dispatch-sync [:jwi/direct-failed-parent [:destroy-a]])
      (is (= [:failed :failed] (terminal-statuses-for work-a))
          "imperative teardown cannot add cancelled after accepted failure")
      (is (= [:rf.machine/finished]
             (mapv #(get-in % [:tags :reason])
                   (filter #(= :jwi/direct-failed-a#7 (get-in % [:tags :actor-id]))
                           (events-of :rf.machine/destroyed))))
          "exactly one destroyed trace for A, its own finality"))))

;; These two arcs used to be two tests, separated only by HOW a child-authored
;; completion got held back until after its attempt closed: `:dispatch-later`
;; through a stubbed host timer, and a same-fx queue behind the child's own
;; `[:rf.machine/destroy …]`. Neither delivery route survives the child-
;; completion protocol — the child authors no completion at all, and the carrier
;; the runtime mints at finality is dispatched from inside finalize — so the two
;; fixtures collapse into the one arc that is still constructible and is what
;; both were really pinning: a carrier that was EXACT-CURRENT when it was formed,
;; delivered after an explicit cancellation closed that attempt.

(deftest late-carrier-after-explicit-cancellation-cannot-fold
  (testing "an exact-attempt carrier formed while A was live is suppressed once
            an explicit destroy has closed that attempt: no fold, and
            cancellation remains the attempt's SOLE terminal"
    (register-imperative-destroy-parent!
      :jwi/late-cancel-parent
      :jwi/late-cancel-a-type :jwi/late-cancel-b-type
      :jwi/late-cancel-a#7 :jwi/late-cancel-b :all
      (completing-child))
    (let [attempt (:rf/attempt (join-state :jwi/late-cancel-parent))
          work-a  [:rf.work/machine :jwi/late-cancel-a#7 [:racing] attempt]
          ;; Form the carrier while A is still live and unfolded — every
          ;; coordinate field read off A's own membership record, so it is
          ;; exact-current at this instant.
          auth-a  (join-attempt :jwi/late-cancel-a#7)]
      (is (some? auth-a) "A carries its join membership record while live")
      (is (= #{} (:done (join-state :jwi/late-cancel-parent))))
      ;; Explicitly destroy A. That closes the attempt as a cancellation.
      (rf/dispatch-sync [:jwi/late-cancel-parent [:destroy-a]])
      (is (= [:cancelled] (terminal-statuses-for work-a)))
      ;; Now deliver the held carrier. It is exact-current by coordinate, and
      ;; must STILL be refused — the attempt it names is closed.
      (forged-completion! :jwi/late-cancel-parent auth-a)
      (is (= #{} (:done (join-state :jwi/late-cancel-parent)))
          "the exact-current late carrier cannot fold")
      (is (= #{:a} (:cancelled (join-state :jwi/late-cancel-parent))))
      (is (= [:cancelled] (terminal-statuses-for work-a))
          "cancellation remains the attempt's sole terminal")
      (is (= :rf.machine.spawn-all/duplicate-completion
             (-> (events-of :rf.machine.spawn-all/stale-completion)
                 first :tags :rf.reply/stale-reason)))
      (rf/dispatch-sync [:jwi/late-cancel-parent [:abort]])
      (rf/dispatch-sync [:jwi/late-cancel-parent [:start]])
      (is (= #{} (:cancelled (join-state :jwi/late-cancel-parent)))
          "re-entry seeds an explicit empty tombstone set for the new attempt"))))

(deftest fixed-id-join-attempt-authority-is-the-machine-work-generation
  (testing "sequential fixed-id attempts stay distinct across accepted, late,
            superseded, and cancellation evidence"
    (register-fixed-parent! :jwi/parent :jwi/a-type :jwi/b-type
                            :jwi/fixed-a :jwi/fixed-b :all)
    (let [attempt-1 (:rf/attempt (join-state :jwi/parent))
          auth-a-1 (join-attempt :jwi/fixed-a)]
      ;; A is non-decisive; B is decisive. Both are accepted terminals from
      ;; the same attempt, but each has its own actor-specific work id.
      (rf/dispatch-sync [:jwi/fixed-a [:go]])
      (let [a1-terminal (work-id-of (first (events-of
                                             :rf.machine.spawn-all/child-completed)))]
        (rf/dispatch-sync [:jwi/fixed-b [:go]])
        (let [b1-terminal (work-id-of (first (events-of
                                               :rf.machine.spawn-all/all-completed)))]
          ;; An exact-current post-resolution arrival is stale on the SAME
          ;; attempt arc; it must not mint or borrow another identity.
          (forged-completion! :jwi/parent auth-a-1)
          (let [a1-late (work-id-of (first (events-of
                                            :rf.machine.spawn-all/late-completion)))]
            (is (= [:rf.work/machine :jwi/fixed-a [:racing] attempt-1]
                   a1-terminal))
            (is (= [:rf.work/machine :jwi/fixed-b [:racing] attempt-1]
                   b1-terminal))
            (is (= a1-terminal a1-late)
                "late evidence retains the exact current attempt identity"))

          ;; Re-enter the same parent/invoke path. The fixed actor addresses
          ;; are intentionally identical; only the carried coordinate can
          ;; distinguish A from B.
          (rf/dispatch-sync [:jwi/parent [:abort]])
          (rf/dispatch-sync [:jwi/parent [:start]])
          (let [attempt-2 (:rf/attempt (join-state :jwi/parent))]
            (is (not= attempt-1 attempt-2))
            (is (= :jwi/fixed-a (get-in (join-state :jwi/parent)
                                        [:children :a])))
            (rf.machines.test-support/reset-captured!)
            (forged-completion! :jwi/parent auth-a-1)
            (let [old-stale (work-id-of (first (events-of
                                                 :rf.machine.spawn-all/stale-completion)))]
              (rf/dispatch-sync [:jwi/fixed-a [:go]])
              (let [a2-terminal (work-id-of (first (events-of
                                                     :rf.machine.spawn-all/child-completed)))]
                (is (= a1-terminal old-stale)
                    "the stale carrier uses attempt A's carried coordinate")
                (is (= [:rf.work/machine :jwi/fixed-a [:racing] attempt-2]
                       a2-terminal))
                (is (not= old-stale a2-terminal)
                    "attempt A suppression cannot land on attempt B's arc"))))))))

  (testing "join resolution cancellation uses the same exact fixed-id attempt"
    (rf.machines.test-support/reset-captured!)
    (register-fixed-parent! :jwi/some-parent :jwi/c-type :jwi/d-type
                            :jwi/fixed-c :jwi/fixed-d :any)
    (let [attempt (:rf/attempt (join-state :jwi/some-parent))]
      (rf/dispatch-sync [:jwi/fixed-c [:go]])
      (let [expected  [:rf.work/machine :jwi/fixed-d [:racing] attempt]
            cancelled (first (events-of
                               :rf.machine.spawn/cancelled-on-join-resolution))
            destroyed (first (filter #(and (= :jwi/fixed-d
                                              (get-in % [:tags :actor-id]))
                                           (= :explicit
                                              (get-in % [:tags :reason])))
                                     (events-of :rf.machine/destroyed)))]
        (is (= expected (work-id-of cancelled)))
        (is (= expected (work-id-of destroyed))
            "the destroy-side cancellation reuses the join attempt identity"))))

  (testing "a fixed join child final leaf and its accepted fold share one id"
    (rf.machines.test-support/reset-captured!)
    (rf/reg-machine
      :jwi/final-child-type
      {:initial :running
       :states {:running {:on {:go {:target :done}}}
                :done {:final? true}}})
    (rf/reg-machine
      :jwi/final-parent
      {:initial :idle
       :states {:idle {:on {:start :racing}}
                :racing
                {:spawn-all
                 {:children [{:id :only :machine-id :jwi/final-child-type
                              :fixed-actor-id :jwi/fixed-final}]
                  :join :all
                  :on-all-complete [:join/done]}}}})
    (rf/dispatch-sync [:jwi/final-parent [:start]])
    (let [attempt  (:rf/attempt (join-state :jwi/final-parent))
          expected [:rf.work/machine :jwi/fixed-final [:racing] attempt]]
      (rf/dispatch-sync [:jwi/fixed-final [:go]])
      (is (= expected (work-id-of (first (events-of :rf.machine/done))))
          "the final-leaf actor terminal uses its private join membership")
      (is (= expected
             (work-id-of (first (events-of
                                  :rf.machine.spawn-all/all-completed))))
          "the join fold reuses that same canonical work identity")))

  (testing "generated actor and ordinary single-spawn identities are unchanged"
    (is (= [:rf.work/machine :jwi/generated#7 [:racing] 7]
           (:rf.reply/work-id
             (rf.machines.reply/join-child-reply
               {:parent-id :jwi/parent :invoke-id [:racing]
                :child-id :a :spawned-id :jwi/generated#7
                :work-generation 7}
               :done [])))
        "a generated actor keeps its exact #n generation")
    (is (= [:rf.work/machine :jwi/single-fixed [:working] 1]
           (:rf.reply/work-id
             (rf.machines.reply/success-reply
               {:actor-id :jwi/single-fixed :work-bearing-path [:working]}
               :ok)))
        "normal fixed-id single spawn remains generation 1")))
