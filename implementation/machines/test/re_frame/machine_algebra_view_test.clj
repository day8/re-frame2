(ns re-frame.machine-algebra-view-test
  "Tests for the derivation/process algebra view of machines. Per
  [spec/Derivations.md] §Machines expose algebra views and the
  `:rf/derivation-node` shape in [spec/Spec-Schemas.md].

  `re-frame.machines.tooling/machine-algebra-view` lowers every registered
  machine into the normalized algebra node every declared fact/process
  shares: the canonical `:process` member — a `:process` (refinement
  `:machine-process`) whose
  snapshot MATERIALIZES into runtime-db, evaluated `:on-transition` (plus
  `:scheduled` / `:on-reply` when the spec declares timers / spawns), owned by
  its `:machine-instance` lifecycle. `…/machine-instance-algebra-view` is the
  live counterpart (one node per materialized snapshot — singleton AND spawned
  actor). `…/machine-selector?` recognizes a sub that reads a machine snapshot;
  `…/machine-selector-targets` extracts the SET of machine ids that selector
  reads (so a graph tool draws the `:selector` edge to the SPECIFIC machine,
  never the cross product).

  These tests pin the registrar-derived projection: the fixed classifications
  (`:kind` / `:storage` / `:lifecycle` / `:materialized?`), the declared
  `[:event …]` inputs walked from the whole state tree (flat / compound /
  hierarchical / parallel), the derived evaluation-policy set, the runtime-db
  output snapshot path, the `:owner` / `:spawns?`, the source-form metadata,
  and the live spawned-actor projection.

  There is NO public accessor: the views live in the bundle-isolated
  `re-frame.machines.tooling` sibling, reached on JVM through the
  `re-frame.machines/machine-*` convenience aliases, and consumed by Xray +
  the conformance fixtures. There is no `re-frame.core/machine-algebra-view`
  public facade export."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.paths :as paths]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.tooling :as mtooling]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as core-test-support]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; The fixed classifications EVERY machine-process node carries (Derivations
;; §Machines expose algebra views — the machine column: the canonical
;; runtime-db / machine-instance MATERIALIZED process member). `:kind` is the
;; CLOSED superkind `:process` (Spec-Schemas DerivationKind); the informative
;; `:machine-process` refinement rides on `:refinement` (matching the routes
;; `:route-fact` convention), never in `:kind`.
(def fixed-classifications
  {:kind          :process
   :refinement    :machine-process
   :storage       :runtime-db
   :lifecycle     :machine-instance
   :materialized? true})

(defn- has-fixed-classifications? [node]
  (= fixed-classifications (select-keys node (keys fixed-classifications))))

;; A small spec exercising flat + compound + the :on triggers we expect to
;; surface as declared [:event …] inputs.
(def upload-machine
  {:initial :idle
   :data    {:progress 0}
   :actions {:start-upload    (fn [_] {})
             :record-progress (fn [_] {})}
   :states  {:idle      {:on {:upload/start :uploading}}
             :uploading {:entry :start-upload
                         :on    {:upload/progress {:action :record-progress}
                                 :upload/succeeded :done
                                 :upload/failed    :failed}}
             :failed    {}
             :done      {}}})

;; ---- empty / shape contract ----------------------------------------------

(deftest empty-registry-returns-empty-map
  ;; Cross-ns isolation: the shared-JVM registrar baseline can carry machines
  ;; registered by a sibling test ns (the reset fixture snapshot/restores rather
  ;; than `clear-all!`s), so assert the empty-registry contract against a
  ;; freshly-cleared registrar bracketed by snapshot/restore.
  (core-test-support/with-fresh-registrar
    (fn []
      (registrar/clear-all!)
      (testing "(machine-algebra-view) returns {} (not nil) when no machines are registered"
        (is (= {} (mtooling/machine-algebra-view)))
        (is (map? (mtooling/machine-algebra-view))))
      (testing "(machine-algebra-view machine-id) returns nil for an unregistered id"
        (is (nil? (mtooling/machine-algebra-view :nope/missing)))))))

(deftest jvm-alias-mirrors-the-tooling-fn
  (testing "the JVM `re-frame.machines/machine-*` aliases are the tooling fns"
    (rf/reg-machine :upload/main upload-machine)
    (is (= (mtooling/machine-algebra-view)
           (machines/machine-algebra-view))
        "machine-algebra-view alias projects identically to the tooling sibling")
    (is (= mtooling/machine-selector? machines/machine-selector?)
        "machine-selector? alias is the tooling fn")
    (is (= mtooling/machine-instance-algebra-view machines/machine-instance-algebra-view)
        "machine-instance-algebra-view alias is the tooling fn")))

;; ---- a registered machine exposes its process node -----------------------

(deftest machine-exposes-its-full-process-node
  (testing "a reg-machine exposes the full machine-process / runtime-db / machine-instance node"
    (rf/reg-machine :upload/main upload-machine)
    (let [node (get (mtooling/machine-algebra-view) :upload/main)]
      (is (some? node) "the machine is present in the static view")
      (is (has-fixed-classifications? node)
          "machine carries the fixed machine-process / runtime-db / machine-instance / materialized classifications")
      (is (= :upload/main (:id node)))
      (is (= {:kind :reg-machine :id :upload/main} (:source-form node)))
      (is (= [:runtime (paths/snapshot-path :upload/main)] (:output node))
          "the output materializes the snapshot into runtime-db at the machine's snapshot path")
      (is (= [:machine :upload/main] (:owner node)))
      (is (false? (:spawns? node)) "this machine declares no :spawn"))))

(deftest declared-event-inputs-are-walked-from-the-whole-state-tree
  (testing ":on event triggers across all states lower to sorted, de-duped [:event …] inputs"
    (rf/reg-machine :upload/main upload-machine)
    (let [node (get (mtooling/machine-algebra-view) :upload/main)]
      (is (= [[:event :upload/failed]
              [:event :upload/progress]
              [:event :upload/start]
              [:event :upload/succeeded]]
             (:inputs node))
          "every author-declared :on key across :idle + :uploading, sorted + distinct"))))

(deftest reserved-and-wildcard-events-are-not-declared-inputs
  (testing "reserved framework events + the :* wildcard are filtered from :inputs"
    (rf/reg-machine :guarded/main
      {:initial :a
       :data    {}
       :states  {:a {:on {:go            :b
                          :*             :a            ;; wildcard — not a concrete input
                          :rf.machine/x  :b}}          ;; reserved ns — framework plumbing
                 :b {}}})
    (let [node (get (mtooling/machine-algebra-view) :guarded/main)]
      (is (= [[:event :go]] (:inputs node))
          "only the concrete app event :go survives the reserved-ns / wildcard filter"))))

;; ---- evaluation-policy set -----------------------------------------------

(deftest evaluation-is-on-transition-by-default
  (testing "a timer-less, spawn-less machine evaluates only :on-transition"
    (rf/reg-machine :plain/main {:initial :a :data {} :states {:a {:on {:go :b}} :b {}}})
    (let [node (get (mtooling/machine-algebra-view) :plain/main)]
      (is (= #{:on-transition} (:evaluation node))))))

(deftest after-timer-adds-scheduled-policy
  (testing "an :after delayed transition adds :scheduled to the evaluation set"
    (rf/reg-machine :timed/main
      {:initial :waiting
       :data    {}
       :states  {:waiting {:after {1000 :timed-out}}
                 :timed-out {}}})
    (let [node (get (mtooling/machine-algebra-view) :timed/main)]
      (is (= #{:on-transition :scheduled} (:evaluation node))
          ":after timer means a scheduler delivers a synthetic timer event"))))

(deftest spawning-machine-adds-on-reply-and-spawns-flag
  (testing "a :spawn-bearing state adds :on-reply and sets :spawns? true"
    (rf/reg-machine :worker/child {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :parent/main
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:go :working}}
                 :working {:spawn {:machine-id :worker/child}}}})
    (let [node (get (mtooling/machine-algebra-view) :parent/main)]
      (is (= #{:on-transition :on-reply} (:evaluation node))
          "a spawning parent reacts to its children's reply events")
      (is (true? (:spawns? node))))))

;; ---- hierarchical + parallel state trees ---------------------------------

(deftest hierarchical-on-keys-are-walked-recursively
  (testing "nested :states :on triggers are collected from every depth"
    (rf/reg-machine :auth/flow
      {:initial :authenticated
       :data    {}
       :states  {:authenticated
                 {:initial :dashboard
                  :on      {:logout :loggedout}
                  :states  {:dashboard {:on {:open-settings :settings}}
                            :settings  {:on {:close :dashboard}}}}
                 :loggedout {}}})
    (let [node (get (mtooling/machine-algebra-view) :auth/flow)]
      (is (= [[:event :close] [:event :logout] [:event :open-settings]]
             (:inputs node))
          "leaf + parent :on keys collected across the hierarchy, sorted"))))

(deftest parallel-region-on-keys-are-walked
  (testing ":regions state-maps are walked like :states"
    (rf/reg-machine :par/main
      {:initial :running
       :data    {}
       :states  {:running
                 {:type    :parallel
                  :regions {:left  {:initial :l0 :states {:l0 {:on {:left/go :l1}} :l1 {}}}
                            :right {:initial :r0 :states {:r0 {:on {:right/go :r1}} :r1 {}}}}}}})
    (let [node (get (mtooling/machine-algebra-view) :par/main)]
      (is (= [[:event :left/go] [:event :right/go]] (:inputs node))
          "both regions' :on keys surface as inputs"))))

;; ---- metadata passthrough ------------------------------------------------

(deftest source-coords-surface-in-the-node
  (testing ":ns / :line / :file captured by reg-machine surface under :source"
    (rf/reg-machine :upload/main upload-machine)
    (let [node   (get (mtooling/machine-algebra-view) :upload/main)
          source (:source node)]
      (is (some? source) ":source map is present when the registration carried coords")
      (is (some? (:ns source)) ":ns captured at the call site"))))

(deftest data-schema-and-doc-pass-through
  (testing "[:schemas :data] surfaces as :schema and :doc passes through"
    (rf/reg-machine :doced/main
      {:initial :a
       :doc     "a documented machine"
       :data    {:n 0}
       :schemas {:data [:map [:n :int]]}
       :states  {:a {:on {:go :b}} :b {}}})
    (let [node (get (mtooling/machine-algebra-view) :doced/main)]
      (is (= [:map [:n :int]] (:schema node))
          "the machine's [:schemas :data] schema surfaces as the node :schema fact")
      (is (= "a documented machine" (:doc node))))))

(deftest no-schema-or-doc-when-absent
  (testing ":schema / :doc are absent when the registration didn't supply them"
    (rf/reg-machine :upload/main upload-machine)
    (let [node (get (mtooling/machine-algebra-view) :upload/main)]
      (is (not (contains? node :schema)))
      (is (not (contains? node :doc))))))

;; ---- live instance view (singleton) --------------------------------------

(defn- seed-snapshot!
  "Install a snapshot for `machine-id` directly into runtime-db via a
  `reg-event` seed handler (returning `{:rf.db/runtime …}`), repositioning
  the machine to a known live state (the hierarchical-test pattern).
  EP-0001: snapshots are durable runtime-db state."
  [machine-id snap]
  (let [seed-id (keyword "test" (str "seed-" (name machine-id)))]
    (rf/reg-event seed-id
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {}) (paths/snapshot-path machine-id) snap)}))
    (rf/dispatch-sync [seed-id])))

(deftest live-singleton-instance-projects
  (testing "a live singleton snapshot projects to an instance node with :state, not :spawned?"
    (rf/reg-machine :upload/main upload-machine)
    (seed-snapshot! :upload/main {:state :uploading :data {:progress 42}})
    (let [view (mtooling/machine-instance-algebra-view :rf/default)
          node (get view :upload/main)]
      (is (some? node) "the live singleton appears in the instance view")
      (is (has-fixed-classifications? node))
      (is (= :uploading (:state node)) "the instance node carries the live :state")
      (is (false? (:spawned? node)) "a singleton snapshot carries no :rf/machine-type")
      (is (= {:kind :reg-machine :id :upload/main} (:source-form node))
          "the singleton resolves its own registered spec")
      (is (not (contains? node :value)) "the snapshot value is not inlined (redacted at egress)"))))

(deftest live-spawned-actor-resolves-its-type
  (testing "a spawned-actor snapshot (carrying :rf/machine-type) resolves its type + is flagged :spawned?"
    (rf/reg-machine :worker/child {:initial :running :data {} :states {:running {:on {:tick :running}}}})
    ;; A spawned actor has NO per-instance registration — its snapshot carries
    ;; the reserved :rf/machine-type discriminator naming its registered type.
    (seed-snapshot! :worker#1 {:state :running :data {} :rf/machine-type :worker/child})
    (let [view (mtooling/machine-instance-algebra-view :rf/default)
          node (get view :worker#1)]
      (is (some? node) "the spawned actor appears in the instance view")
      (is (has-fixed-classifications? node))
      (is (true? (:spawned? node)) ":rf/machine-type present → spawned actor")
      (is (= :running (:state node)))
      (is (= {:kind :reg-machine :id :worker/child} (:source-form node))
          "the actor's source-form names its resolved TYPE, not the actor-id")
      (is (= [:runtime (paths/snapshot-path :worker#1)] (:output node))
          "the output is this INSTANCE's own snapshot slot")
      (is (= [[:event :tick]] (:inputs node))
          "inputs are derived from the resolved type spec"))))

(deftest live-instance-view-empty-without-snapshots
  (testing "instance view is {} for a frame with registered-but-uninstantiated machines"
    (rf/reg-machine :upload/main upload-machine)
    (is (= {} (mtooling/machine-instance-algebra-view :rf/default))
        "no snapshot materialized yet → no live instance node")))

;; ---- machine-selector? recognizer ----------------------------------------

(deftest machine-selector-recognizer
  (testing "a static :<- [:rf/machine …] sub is recognized as a machine selector"
    (rf/reg-machine :upload/main upload-machine)
    (rf/reg-sub :upload/progress
      :<- [:rf/machine :upload/main]
      (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
    (is (true? (mtooling/machine-selector? :upload/progress))
        "a sub reading [:rf/machine …] is a machine selector"))
  (testing "a sub reading [:rf.machine/has-tag? …] is also a selector"
    (rf/reg-sub :upload/has-tag
      :<- [:rf.machine/has-tag? :upload/main :busy]
      (fn [has? _] has?))
    (is (true? (mtooling/machine-selector? :upload/has-tag))))
  (testing "an ordinary sub is NOT a machine selector; nor is an unregistered id"
    (rf/reg-sub :plain/sub (fn [db _] (:x db)))
    (is (false? (mtooling/machine-selector? :plain/sub)))
    (is (false? (mtooling/machine-selector? :nope/missing)))))

;; ---- machine-selector-targets extractor ----------------------------------

(deftest machine-selector-targets-extractor
  (testing "extracts the target machine id from a [:rf/machine machine-id] selector"
    (rf/reg-machine :upload/main upload-machine)
    (rf/reg-sub :upload/progress
      :<- [:rf/machine :upload/main]
      (fn [snapshot _] (get-in snapshot [:data :progress] 0)))
    (is (= #{:upload/main} (mtooling/machine-selector-targets :upload/progress))
        "the second element of the [:rf/machine …] input is the target id"))
  (testing "extracts the target from a [:rf.machine/has-tag? machine-id tag] selector"
    (rf/reg-sub :upload/has-tag
      :<- [:rf.machine/has-tag? :upload/main :busy]
      (fn [has? _] has?))
    (is (= #{:upload/main} (mtooling/machine-selector-targets :upload/has-tag))
        "the has-tag? form's machine id (second element) is the target"))
  (testing "a selector reading two machines yields both targets"
    (rf/reg-sub :combined
      :<- [:rf/machine :upload/main]
      :<- [:rf/machine :download/main]
      (fn [[u d] _] [u d]))
    (is (= #{:upload/main :download/main}
           (mtooling/machine-selector-targets :combined))
        "every [:rf/machine …] input contributes its target id"))
  (testing "a non-selector sub and an unregistered id yield #{}"
    (rf/reg-sub :plain/sub (fn [db _] (:x db)))
    (is (= #{} (mtooling/machine-selector-targets :plain/sub)))
    (is (= #{} (mtooling/machine-selector-targets :nope/missing))))
  (testing "the JVM facade alias is the tooling fn"
    (is (= mtooling/machine-selector-targets machines/machine-selector-targets)
        "machine-selector-targets alias is the tooling fn")))
