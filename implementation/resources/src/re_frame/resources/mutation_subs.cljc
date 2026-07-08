(ns re-frame.resources.mutation-subs
  "The passive mutation-INSTANCE subscriptions — the read API over a
  mutation's pending / result / error state. Per Spec 016 §Deferred slices
  (mutations, first public-beta gate) and EP-0003 §Mutations.

  A view reads a mutation instance through `[:rf/mutation {:instance
  …}]` (or a narrower projection); `:rf.mutation/execute` causes the write.
  Subscriptions are PURE passive reads (mirroring the resource subs).

  Mutation instances are keyed by INSTANCE id (not mutation id) so a view
  reading one form submission's state never sees another concurrent
  submission of the same mutation. The derived booleans (`:pending?` /
  `:success?` / `:error?` / `:settled?`) are PUBLIC DERIVED SUB VALUES
  computed here from the durable instance facts, NOT stored on the instance
  (Spec 016 §Status semantics, the same posture as resource subs).

  ## `:result` is the instance-layer spelling of the decoded result (kh9jz6)

  The mutation INSTANCE stores the decoded write result under `:result` —
  the durable, queryable status-record spelling. The transient causal reply
  map (the uniform reply envelope, Managed-Effects §The reply map) carries
  the SAME decoded result under `:value` — the reply-map spelling, which is
  `:value` for EVERY managed-async family with no per-family synonym
  (EP-0007 one-name-per-fact). These are two NAMES for two FACTS in two
  LAYERS: `:value` is the transient continuation; `:result` is the durable
  instance status record (`{:pending? :success? :error? :settled? :result
  :error}`) a view subscribes to long after the reply was consumed. The
  events layer reads `(:value reply)` from the canonical reply and installs
  it under the instance's `:result` (mirroring the resource read path, which
  installs `(:value reply)` under the entry's `:data`). They are kept
  distinct deliberately — the instance sub answers \"what is the durable
  state of this write?\", not \"what did the continuation carry?\"."
  (:require [re-frame.resources.mutation-runtime :as mstate]
            [re-frame.subs :as subs]))

#?(:clj (set! *warn-on-reflection* true))

(defn- instance-for
  "Look up the durable mutation instance for a sub payload by its
  `:instance` id, or nil when no instance exists."
  [runtime-db [_id {:keys [instance]}]]
  (get-in runtime-db (mstate/instance-path instance)))

(defn optimistic?-for
  "PURE DERIVED: is this mutation instance showing a LIVE optimistic value — an
  optimistic apply landed (phase 1.5) but has NOT yet settled (commit / rollback
  / reconcile, phase 4)? EP-0019 Rider 1: the flag is true between the apply and
  the settle, so a view can render \"pending, but showing my optimistic value.\"

  Derived from two durable facts, never stored as a third:

  - the instance is non-terminally `:pending` (a settled `:success` / `:error`
    instance has had its optimistic apply committed / rolled back, so the value
    on the entry is authoritative or restored, NOT optimistic);
  - the instance recorded an optimistic apply — its `:patch-summary` carries a
    `:snapshot-id` (the phase-1.5 execute writes it onto the pending row; a
    purely-pessimistic write leaves it nil).

  A pessimistic write is `:optimistic? false` throughout (no snapshot id); a
  committed / rolled-back optimistic write is `:optimistic? false` after settle
  (no longer `:pending`)."
  [instance]
  (and (= :pending (:status instance))
       (some? (-> instance :patch-summary :snapshot-id))))

(defn state-sub-fn
  "Project the public `:rf/mutation` view-model from a durable
  instance: the stored facts (`:status` / `:result` / `:error` /
  `:affected-keys`) plus the DERIVED booleans (`:pending?` / `:success?` /
  `:error?` / `:settled?` / `:optimistic?`) computed here, never stored.
  Empty-state shape (idle, no instance) when none. Per EP-0003 §Mutations /
  EP-0019 Rider 1 (the `:optimistic?` derived flag)."
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
       :settled?      (mstate/terminal? (:status i))
       :optimistic?   (optimistic?-for i)})))

(defn status-sub-fn
  "Project `:rf.mutation/status` — the instance's `:status` keyword
  (:idle / :pending / :success / :error). Per EP-0003 §Mutations."
  [runtime-db sub-v]
  (or (:status (instance-for runtime-db sub-v)) :idle))

(defn pending?-sub-fn
  "Project `:rf.mutation/pending?` — the write is in flight. Per EP-0003
  §Mutations."
  [runtime-db sub-v]
  (= :pending (:status (instance-for runtime-db sub-v))))

(defn result-sub-fn
  "Project `:rf.mutation/result` — the decoded success result (or nil). The
  durable instance-layer spelling of the decoded write result; the transient
  reply envelope carries the same fact as `:value` (kh9jz6 / EP-0007 — see
  the ns docstring). Per EP-0003 §Mutations."
  [runtime-db sub-v]
  (:result (instance-for runtime-db sub-v)))

(defn error-sub-fn
  "Project `:rf.mutation/error` — the failure envelope (or nil; the closed
  `:rf.http/*` shape). Per EP-0003 §Mutations."
  [runtime-db sub-v]
  (:error (instance-for runtime-db sub-v)))

(defn register-subs!
  "Register the `:rf.mutation/*` passive sub family. Called from the
  `re-frame.resources` façade so a `(require … :reload)` on a fresh
  registrar re-wires them. Per EP-0003 §Mutations."
  []
  (subs/reg-runtime-sub :rf/mutation
    {:doc "Passive read of a mutation instance's full view-model `{:status :result :error :affected-keys :pending? :success? :error? :settled? :optimistic?}`, keyed by :instance id. The `:optimistic?` flag (EP-0019 Rider 1) is true while a live optimistic apply is showing (phase 1.5) and not yet settled. Per EP-0003 §Mutations."}
    state-sub-fn)
  (subs/reg-runtime-sub :rf.mutation/status
    {:doc "Passive read of a mutation instance's :status keyword (:idle / :pending / :success / :error). Per EP-0003 §Mutations."}
    status-sub-fn)
  (subs/reg-runtime-sub :rf.mutation/pending?
    {:doc "Passive read: true iff a mutation instance's write is in flight. Per EP-0003 §Mutations."}
    pending?-sub-fn)
  (subs/reg-runtime-sub :rf.mutation/result
    {:doc "Passive read of a mutation instance's decoded success result (or nil). Per EP-0003 §Mutations."}
    result-sub-fn)
  (subs/reg-runtime-sub :rf.mutation/error
    {:doc "Passive read of a mutation instance's failure envelope (or nil). Per EP-0003 §Mutations."}
    error-sub-fn)
  nil)
