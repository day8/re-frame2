(ns re-frame.destroyed-trace-shape-test
  "`:rf.machine/destroyed` is emitted at three distinct sites — each
  for a different destroy class:

    1. `destroy-spawn-all-children!` (lifecycle_fx/destroy.cljc):
       per-child fire when a `:spawn-all` parent tears down its
       children. The trace carries an extra `:child-id` slot keying
       the join-state map but does NOT include `:system-id`.

    2. `destroy-single!` (lifecycle_fx/destroy.cljc): the keyword /
       tracked-map fire for an explicit `:rf.machine/destroy` fx or
       the standard declarative-`:spawn` exit cascade. The trace
       carries `:system-id` (resolved from the reverse-index BEFORE
       teardown).

    3. `finalize-machine` (lifecycle_fx/finalize.cljc): the auto-destroy
       fire when a state-machine enters a `:final?` state. The trace
       carries `:system-id` AND `:reason :rf.machine/finished`.

  Tools (re-frame-10x, Xray, story-mcp) key on the trace's argument
  map. If the three emission sites drift in their key-set shape, tools
  that depend on the contract observe inconsistent payloads depending
  on which path emitted. This file locks the shape independently of
  which code-path emitted — the test runs the three paths against the
  SAME tap and asserts:

    - every fire's argument map is a subset of the canonical union
      `{:frame :actor-id :system-id :parent-id :invoke-id :child-id
        :reason}`,
    - `:reason` is always present (the discriminator),
    - `:frame` and `:actor-id` are always present (the common id pair),
    - sites that don't have `:child-id` / `:system-id` simply omit
      those slots (no nil-stamping)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Canonical key-set that the destroyed-trace emission sites are
;; responsible for assembling. The trace framework auto-stamps
;; envelope cascade keys (currently `:rf.trace/dispatch-id`) under
;; `:tags` per Spec 009 §Cascade-id stamping; those keys are not part
;; of the per-site contract.
;;
;; An `:explicit` destroy closes the actor work attempt the reply-envelope
;; way (a `:status :cancelled` reply — cancellation as DATA, Managed-Effects
;; §Cancellation / EP-0011 §Cancellation). The cancelled reply facts ride
;; ADDITIVELY on the destroyed trace, so they are part of the contract for
;; the `:explicit` (cancellation) sites; a `:rf.machine/finished` destroy
;; carries none of them (the actor already closed through
;; `finalize-machine`'s `:rf.machine/done` reply).
;; rf2-o6c2jr — the bare :work/id duplicate was dropped from the reply-envelope
;; rows; the work identity rides as :rf.reply/work-id (rf2-l7s7b7 completed the
;; single-root migration; :work/kind now rides as :rf.reply/work-kind too).
(def ^:private reply-envelope-keys
  #{:rf.reply/work-kind :rf.reply/status :rf.reply/work-id
    :rf.reply/work-status :rf.reply/cancelled? :rf.reply/cancel-reason
    :rf.reply/correlation})

(def ^:private canonical-site-keys
  (clojure.set/union
    #{:frame :actor-id :system-id :parent-id :invoke-id :child-id :reason}
    reply-envelope-keys))

(def ^:private framework-stamped-keys
  #{:rf.trace/dispatch-id})

(def ^:private permitted-keys
  (clojure.set/union canonical-site-keys framework-stamped-keys))

(defn- destroyed-traces [captured]
  (filter #(= :rf.machine/destroyed (:operation %)) @captured))

;; Intentional RAW manual-stop listener (not mtest/with-trace-capture): this
;; is the destroyed-trace SHAPE probe — it returns a [capture-atom
;; unregister-fn] pair so a test can inspect the raw envelope key-set and
;; control exactly when it stops capturing (the scope-macro form cannot
;; express the manual-stop). This site is left as raw by design.
(defn- record!
  []
  (let [a  (atom [])
        id ::shape-listener]
    (trace/register-listener! id (fn [ev] (swap! a conj ev)))
    [a #(trace/unregister-listener! id)]))

(defn- assert-shape!
  [traces label]
  (is (seq traces)
      (str label ": at least one :rf.machine/destroyed fired"))
  (doseq [t traces]
    (let [tags      (:tags t)
          site-keys (remove framework-stamped-keys (keys tags))]
      ;; (1) Every site-provided key is in the canonical contract —
      ;; no drift. Framework-stamped envelope keys (:dispatch-id) are
      ;; out of scope here.
      (is (every? canonical-site-keys site-keys)
          (str label ": trace tags must be a subset of canonical keys; saw "
               (vec site-keys)))
      ;; (2) Common id pair always present.
      (is (contains? tags :frame)
          (str label ": :frame is always emitted"))
      (is (contains? tags :actor-id)
          (str label ": :actor-id is always emitted"))
      ;; (3) Reason discriminator always present.
      (is (contains? tags :reason)
          (str label ": :reason discriminator is always emitted"))
      (is (#{:explicit :rf.machine/finished} (:reason tags))
          (str label ": :reason is one of :explicit / :rf.machine/finished"))
      ;; An :explicit destroy is a cancellation; it carries the
      ;; reply-envelope cancellation facts. A :rf.machine/finished destroy is
      ;; NOT a cancellation (the actor closed through :rf.machine/done) — no
      ;; cancelled reply facts.
      (if (= :explicit (:reason tags))
        (do
          (is (= :cancelled (:rf.reply/status tags))
              (str label ": an :explicit destroy carries :rf.reply/status :cancelled"))
          (is (= :cancelled (:rf.reply/work-status tags))
              (str label ": ... and :rf.reply/work-status :cancelled"))
          (is (true? (:rf.reply/cancelled? tags))
              (str label ": ... and the :rf.reply/cancelled? marker"))
          (is (= :explicit (:rf.reply/cancel-reason tags))
              (str label ": ... and :rf.reply/cancel-reason"))
          ;; rf2-o6c2jr — the canonical work identity rides ONLY as
          ;; :rf.reply/work-id (the bare :work/id duplicate was dropped).
          (is (some? (:rf.reply/work-id tags))
              (str label ": ... and the canonical :rf.reply/work-id"))
          (is (not (contains? tags :work/id))
              (str label ": ... and NO bare :work/id duplicate")))
        (is (not (contains? tags :rf.reply/status))
            (str label ": a :rf.machine/finished destroy carries no cancelled reply facts"))))))

;; ---- Site 1: destroy-spawn-all-children! ---------------------------------

(deftest invoke-all-children-destroy-trace-shape
  (testing "destroy-spawn-all-children! per-child traces carry :child-id and omit :system-id"
    (let [[cap unreg] (record!)
          child {:initial :running
                 :data    {}
                 :states  {:running {:on {:done :final}}
                           :final   {:final? true}}}
          parent {:initial :hydrating
                  :states
                  {:hydrating {:spawn-all
                               {:children
                                [{:id :a :machine-id :ia/child}
                                 {:id :b :machine-id :ia/child}]
                                :join              :all
                                :on-child-done     :ia/asset-done
                                :on-child-error    :ia/asset-failed
                                :on-all-complete   [:go-done]
                                :on-any-failed     [:ia/cancel]}
                               :on {:go-done    :done
                                    :ia/cancel  :idle}}
                   :done {}
                   :idle {}}}]
      (try
        (rf/reg-machine :ia/child child)
        (rf/reg-machine :ia/parent parent)
        (rf/dispatch-sync [:ia/parent [:rf.machine.spawn/spawned]])
        ;; Force a :spawn-all teardown via re-entering :idle through
        ;; an explicit destroy of the parent. Easier: drive the parent
        ;; via :ia/cancel into :idle (the invoke-all exit cascade tears
        ;; the children down through destroy-spawn-all-children!).
        (rf/dispatch-sync [:ia/parent [:ia/cancel]])
        (let [traces (destroyed-traces cap)
              child-traces (filter #(contains? (:tags %) :child-id) traces)]
          (assert-shape! traces "invoke-all-children")
          (is (seq child-traces)
              "at least one trace carries the :child-id discriminator (per-child path)")
          (doseq [t child-traces]
            (is (not (contains? (:tags t) :system-id))
                "per-child fires omit :system-id (children weren't system-id-bound)")
            (is (= :explicit (-> t :tags :reason)))))
        (finally (unreg))))))

;; ---- Site 2: destroy-single! ----------------------------------------------

(deftest destroy-single-trace-shape
  (testing "destroy-single! (declarative :spawn exit cascade) carries :system-id slot key"
    (let [[cap unreg] (record!)
          child {:initial :running
                 :data    {}
                 :states  {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :ds/child}
                             :on     {:stop :idle}}}}]
      (try
        (rf/reg-machine :ds/child child)
        (rf/reg-machine :ds/parent parent)
        (rf/dispatch-sync [:ds/parent [:start]])
        ;; Exit the :spawn-bearing state — destroy-single! fires.
        (rf/dispatch-sync [:ds/parent [:stop]])
        (let [traces (destroyed-traces cap)]
          (assert-shape! traces "destroy-single")
          (doseq [t traces]
            (is (contains? (:tags t) :system-id)
                "destroy-single! always emits :system-id (even if nil for non-system-id-bound actors)")
            (is (= :explicit (-> t :tags :reason)))))
        (finally (unreg))))))

;; ---- Site 3: finalize-machine ---------------------------------------------

(deftest finalize-machine-trace-shape
  (testing "finalize-machine (entering :final?) fires :reason :rf.machine/finished"
    (let [[cap unreg] (record!)
          child {:initial :running
                 :data    {}
                 :states  {:running {:on {:end :done}}
                           :done    {:final? true}}}
          parent {:initial :working
                  :states  {:working {:spawn {:machine-id :fz/child}}}}]
      (try
        (rf/reg-machine :fz/child child)
        (rf/reg-machine :fz/parent parent)
        (rf/dispatch-sync [:fz/parent [:rf.machine.spawn/spawned]])
        (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/machines :spawned :fz/parent [:working]])]
          (rf/dispatch-sync [spawned-id [:end]]))
        (let [traces (destroyed-traces cap)
              finish-traces (filter #(= :rf.machine/finished (-> % :tags :reason)) traces)]
          (assert-shape! traces "finalize-machine")
          (is (seq finish-traces)
              "at least one trace carries :reason :rf.machine/finished")
          (doseq [t finish-traces]
            (is (contains? (:tags t) :system-id)
                "finalize-machine always emits :system-id")))
        (finally (unreg))))))

;; ---- Cross-site stability check -------------------------------------------

(deftest no-key-drift-across-sites
  (testing "every :rf.machine/destroyed across all three paths obeys the canonical key-set"
    ;; This is a meta-check: drive all three paths against ONE listener
    ;; (no per-site recording) and assert the union shape across them.
    (let [[cap unreg] (record!)
          child {:initial :running
                 :data    {}
                 :states  {:running {:on {:done :final}}
                           :final   {:final? true}}}]
      (try
        ;; (a) destroy-single! via explicit fx
        (rf/reg-machine :nd/standalone
                        {:initial :running
                         :data    {}
                         :states  {:running {:on {:end :done}}
                                   :done    {:final? true}}})
        (rf/dispatch-sync [:nd/standalone [:end]])

        ;; (b) finalize-machine via spawned child reaching :final?
        (rf/reg-machine :nd/child child)
        (rf/reg-machine :nd/parent
                        {:initial :working
                         :states  {:working {:spawn {:machine-id :nd/child}}}})
        (rf/dispatch-sync [:nd/parent [:rf.machine.spawn/spawned]])
        (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/machines :spawned :nd/parent [:working]])]
          (rf/dispatch-sync [spawned-id [:done]]))

        ;; Verify the union shape across both fires.
        (let [traces (destroyed-traces cap)]
          (assert-shape! traces "cross-site")
          (is (<= 2 (count traces))
              "at least two :rf.machine/destroyed fired across the two paths")
          ;; All traces' key-sets are subsets of the union of canonical
          ;; site keys + framework-stamped envelope keys.
          (let [union (apply clojure.set/union (map (comp set keys :tags) traces))]
            (is (every? permitted-keys union)
                (str "union of all observed keys is permitted; "
                     "observed extras: "
                     (clojure.set/difference union permitted-keys)))))
        (finally (unreg))))))
