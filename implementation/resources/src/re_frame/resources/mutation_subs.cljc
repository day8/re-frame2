(ns re-frame.resources.mutation-subs
  "The passive mutation-instance subscriptions: pure reads of one instance's
  pending / result / error state. Instances are keyed by instance id, so a
  view never sees another concurrent submission of the same mutation. The
  derived booleans are computed here, never stored.

  The instance stores the decoded result under `:result`, the durable status
  record a view reads long after the reply was consumed; the transient reply
  map carries the same fact as `:value` (Managed-Effects §The reply map).
  Spec 016 §Mutations (first public-beta gate)."
  (:require [re-frame.resources.mutation-runtime :as rf.resources.mutation-runtime]
            [re-frame.subs :as rf.subs]))

#?(:clj (set! *warn-on-reflection* true))

(defn- instance-for
  "The durable instance for a sub payload's `:instance` id, or nil."
  [runtime-db [_id {:keys [instance]}]]
  (get-in runtime-db (rf.resources.mutation-runtime/instance-path instance)))

(defn optimistic?-for
  "True while an optimistic apply is showing and not yet settled: the
  instance is `:pending` and its `:patch-summary` carries a `:snapshot-id`.
  Derived from those two durable facts, never stored.
  Spec 016 §Optimistic mutations."
  [instance]
  (and (= :pending (:status instance))
       (some? (-> instance :patch-summary :snapshot-id))))

(defn state-sub-fn
  "The `:rf/mutation` view-model: the stored `:status` / `:result` / `:error`
  / `:affected-keys` plus the derived booleans, or the idle shape when no
  instance exists. Spec 016 §Status semantics."
  [runtime-db sub-v]
  (let [i (instance-for runtime-db sub-v)]
    (if (nil? i)
      {:status :idle :result nil :error nil :affected-keys nil
       :pending? false :success? false :error? false :settled? false
       :optimistic? false}
      {:status        (:status i)
       :result        (:result i)
       :error         (:error i)
       :affected-keys (:affected-keys i)
       :pending?      (= :pending (:status i))
       :success?      (= :success (:status i))
       :error?        (= :error (:status i))
       :settled?      (rf.resources.mutation-runtime/terminal? (:status i))
       :optimistic?   (optimistic?-for i)})))

(defn status-sub-fn
  "`:rf.mutation/status`: the instance's `:status`, `:idle` when absent."
  [runtime-db sub-v]
  (or (:status (instance-for runtime-db sub-v)) :idle))

(defn pending?-sub-fn
  "`:rf.mutation/pending?`: the write is in flight."
  [runtime-db sub-v]
  (= :pending (:status (instance-for runtime-db sub-v))))

(defn result-sub-fn
  "`:rf.mutation/result`: the decoded success result, or nil."
  [runtime-db sub-v]
  (:result (instance-for runtime-db sub-v)))

(defn error-sub-fn
  "`:rf.mutation/error`: the `:rf.http/*` failure envelope, or nil."
  [runtime-db sub-v]
  (:error (instance-for runtime-db sub-v)))

(defn register-subs!
  "Registers the `:rf.mutation/*` sub family. Called from the
  `re-frame.resources` façade, so a reload on a fresh registrar re-wires them.
  Spec 016 §Subscriptions (passive)."
  []
  (rf.subs/reg-runtime-sub :rf/mutation
    {:doc "Passive read of a mutation instance's full view-model `{:status :result :error :affected-keys :pending? :success? :error? :settled? :optimistic?}`, keyed by :instance id. The `:optimistic?` flag is true while a live optimistic apply is showing and not yet settled. Per Spec 016 §Mutations."}
    state-sub-fn)
  (rf.subs/reg-runtime-sub :rf.mutation/status
    {:doc "Passive read of a mutation instance's :status keyword (:idle / :pending / :success / :error). Per Spec 016 §Mutations."}
    status-sub-fn)
  (rf.subs/reg-runtime-sub :rf.mutation/pending?
    {:doc "Passive read: true iff a mutation instance's write is in flight. Per Spec 016 §Mutations."}
    pending?-sub-fn)
  (rf.subs/reg-runtime-sub :rf.mutation/result
    {:doc "Passive read of a mutation instance's decoded success result (or nil). Per Spec 016 §Mutations."}
    result-sub-fn)
  (rf.subs/reg-runtime-sub :rf.mutation/error
    {:doc "Passive read of a mutation instance's failure envelope (or nil). Per Spec 016 §Mutations."}
    error-sub-fn)
  nil)
