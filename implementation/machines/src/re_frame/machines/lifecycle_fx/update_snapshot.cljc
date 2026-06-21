(ns re-frame.machines.lifecycle-fx.update-snapshot
  "The `:rf.machine/update-snapshot` reserved fx — the snapshot-level
  escape hatch. Per Spec 005 §Path conventions §Snapshot-level escape
  hatch:

  > If a callback NEEDS to touch `:state` / `:meta` / `:data` plus
  > something else in one atomic write, emit
  > `[:rf.machine/update-snapshot {...}]` from inside the callback's
  > `:fx` vector — NOT a return-shape hidden contract.

  Machine callbacks (`:action` / `:entry` / `:exit` / `:on-spawn`) return
  only a fresh `:data` map (or a `{:data :fx}` effects map); they cannot
  reach `:state` / `:meta` atomically. This fx is the sanctioned, traced,
  named alternative to a hidden return-shape contract — `apply-on-spawn`'s
  docstring (`transition.cljc`) directs callers here, so the fx MUST exist.

  Args shape: `{:rf/machine-id <id> :rf/patch {<snapshot-keys> ...}}`.
  `:rf/machine-id` names the actor whose snapshot at
  `[:rf.runtime/machines :snapshots <id>]` is patched; `:rf/patch` is the map merged onto
  that snapshot. Only the spec-permitted top-level snapshot keys flow
  through (`:state` / `:meta` / `:data`); any other key is ignored (the
  escape hatch can't graft arbitrary slots onto a snapshot). User
  error/status state is user-domain working memory and lives under
  `:data` (schema-covered) — not as bare snapshot-root keys. A `:db` key
  in the patch is the same hard-disallow the action-effect path enforces
  (Spec 005:463), surfaced as `:rf.error/machine-action-wrote-db`."
  (:require [re-frame.frame :as frame]
            [re-frame.machines.data-validation :as data-validation]
            [re-frame.machines.paths :as paths]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; Per Spec 005 §Snapshot-level escape hatch the escape hatch may touch
;; exactly these snapshot top-level keys. `:db` is NOT among them
;; (Spec 005:463 hard-disallow). User error/status state belongs under
;; `:data` (schema-covered), not as bare snapshot-root keys.
(def ^:private permitted-patch-keys #{:state :meta :data})

(defn update-snapshot-fx
  "fx handler for `:rf.machine/update-snapshot`. Merges the spec-permitted
  keys of `:rf/patch` onto the snapshot at `[:rf.runtime/machines :snapshots <machine-id>]`
  in the emitting frame's runtime-db. No-op when the actor has no snapshot
  (destroyed / not-yet-materialised) or when `:rf/machine-id` is absent.
  Per Spec 005 §Snapshot-level escape hatch.

  Validation scope: the merged candidate's `:data` IS validated against the
  actor's `:data-schema` (below). A `:state` / `:meta` patch is NOT
  occupiability-checked — the escape hatch
  trusts the caller to supply a `:state` that resolves to a real, occupiable
  state-node of the machine's definition. A `:state` patch naming a non-
  existent / non-occupiable node writes a snapshot the next transition will
  treat as out-of-definition (the standard re-registration
  `:rf.error/machine-state-not-in-definition` reconcile path applies on the
  next dispatch); the patch fx itself does not run `state-resolves?` on the
  candidate. This is the deliberate escape-hatch trade-off: the hatch is the
  low-frequency \"I need to touch `:state` + something else atomically\"
  primitive, not a guarded `:on`-transition."
  [{frame-id :frame} args]
  (let [;; The cascade envelope frame is the fx-context `:frame`; a nil
        ;; stamp is an invariant failure (`:rf.error/no-frame-context`),
        ;; never a synthesised `:rf/default`.
        frame-id   (frame/require-frame-stamp!
                     frame-id :rf.machine/update-snapshot
                     {:where 'rf.machine/update-snapshot
                      :event-id (:rf/machine-id args)})
        machine-id (:rf/machine-id args)
        patch      (:rf/patch args)]
    (when (and machine-id (map? patch))
      ;; Hard-disallow `:db` — symmetric with the action-effect path
      ;; (Spec 005:463). Canonical id / tags per Spec 009 §Error event
      ;; catalogue.
      ;;
      ;; The addressed-id tag shape aligns with the action-effect path
      ;; (`transition/enforce-db-disallow`) and Spec 009 §`:rf.machine/*`:
      ;; the offending write ran against a LIVE actor INSTANCE, so it rides
      ;; under `:actor-id` — `:rf.machine/update-snapshot` carries the
      ;; actor id under `:rf/machine-id`, which IS the running instance address
      ;; (a singleton's registration id, or a spawned actor's `<type>#<n>` /
      ;; fixed id). `:offending-value` (the whole app-db the patch wrongly
      ;; carried) is summarized at the trace egress chokepoint by
      ;; `re-frame.classification/project-machine-wrote-db-tags` so it never egresses raw.
      (when (contains? patch :db)
        (trace/emit-error! :rf.error/machine-action-wrote-db
                           {:actor-id        machine-id
                            :action-id       :rf.machine/update-snapshot
                            :offending-value (:db patch)
                            :frame           frame-id
                            :recovery        :logged-and-skipped}))
      (let [clean-patch (select-keys patch permitted-patch-keys)]
        (when (seq clean-patch)
          ;; Machine snapshots are durable runtime-db state: the
          ;; escape-hatch patch is a runtime-db partition write.
          ;;
          ;; The escape-hatch `:data` patch is NOT exempt from the
          ;; `:where :machine-data` boundary. Spec 005 §Snapshot-
          ;; level escape hatch says user error/status state lives under
          ;; `:data` *where `:data-schema` validation covers it*. The fx
          ;; runs on the single drainer (Spec 002), so read-then-write is
          ;; atomic for this actor's snapshot: read the current snapshot,
          ;; compute the would-be-merged candidate, validate its `:data`
          ;; against the actor's resolved `:data-schema`, and SKIP the
          ;; write when it fails (a PRE-WRITE rejection — nothing is
          ;; committed, so `:rollback? false`; the trace fires with
          ;; `:phase :update-snapshot`). A valid patch — or a machine with
          ;; no `:data-schema` — writes exactly as before. Absent actor
          ;; (destroyed / unknown) is a no-op: nothing to merge into, and
          ;; nothing to validate.
          (let [snapshots (get-in (frame/frame-runtime-db-value frame-id)
                                  (paths/snapshot-path))]
            (when (contains? snapshots machine-id)
              (let [merged (merge (get snapshots machine-id) clean-patch)]
                (when (data-validation/validate-update-snapshot-data!
                        machine-id merged)
                  (frame/swap-runtime-db!
                    frame-id
                    (fn [runtime-db]
                      ;; Re-check presence inside the swap — never conjure
                      ;; a snapshot for an actor torn down between the read
                      ;; and the write.
                      (if (contains? (get-in runtime-db (paths/snapshot-path)) machine-id)
                        (update-in runtime-db (paths/snapshot-path machine-id) merge clean-patch)
                        runtime-db))))))))))
    nil))
