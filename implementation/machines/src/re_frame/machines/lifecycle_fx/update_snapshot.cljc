(ns re-frame.machines.lifecycle-fx.update-snapshot
  "The `:rf.machine/update-snapshot` reserved fx — the snapshot-level
  escape hatch. Per Spec 005 §Path conventions §Snapshot-level escape
  hatch (005:489):

  > If a callback NEEDS to touch `:state` / `:meta` / `:errors` /
  > `:status` / `:data` plus something else in one atomic write, emit
  > `[:rf.machine/update-snapshot {...}]` from inside the callback's
  > `:fx` vector — NOT a return-shape hidden contract.

  Machine callbacks (`:action` / `:entry` / `:exit` / `:on-spawn`) return
  only a fresh `:data` map (or a `{:data :fx}` effects map); they cannot
  reach `:state` / `:meta` / `:errors` / `:status` atomically. This fx is
  the sanctioned, traced, named alternative to a hidden return-shape
  contract — `apply-on-spawn`'s docstring (`transition.cljc`) directs
  callers here, so the fx MUST exist.

  Args shape: `{:rf/machine-id <id> :rf/patch {<snapshot-keys> ...}}`.
  `:rf/machine-id` names the actor whose snapshot at
  `[:rf/runtime :machines :snapshots <id>]` is patched; `:rf/patch` is the map merged onto
  that snapshot. Only the spec-permitted top-level snapshot keys flow
  through (`:state` / `:meta` / `:errors` / `:status` / `:data`); any
  other key is ignored (the escape hatch can't graft arbitrary slots
  onto a snapshot). A `:db` key in the patch is the same hard-disallow
  the action-effect path enforces (Spec 005:463), surfaced as
  `:rf.error/machine-action-wrote-db`."
  (:require [re-frame.frame :as frame]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; Per Spec 005:489 the escape hatch may touch exactly these snapshot
;; top-level keys. `:db` is NOT among them (Spec 005:463 hard-disallow).
(def ^:private permitted-patch-keys #{:state :meta :errors :status :data})

(defn update-snapshot-fx
  "fx handler for `:rf.machine/update-snapshot`. Merges the spec-permitted
  keys of `:rf/patch` onto the snapshot at `[:rf/runtime :machines :snapshots <machine-id>]`
  in the emitting frame's app-db. No-op when the actor has no snapshot
  (destroyed / not-yet-materialised) or when `:rf/machine-id` is absent.
  Per Spec 005 §Snapshot-level escape hatch."
  [{frame-id :frame :or {frame-id :rf/default}} args]
  (let [machine-id (:rf/machine-id args)
        patch      (:rf/patch args)]
    (when (and machine-id (map? patch))
      ;; Hard-disallow `:db` — symmetric with the action-effect path
      ;; (Spec 005:463). Canonical id / tags per Spec 009 §Error event
      ;; catalogue.
      (when (contains? patch :db)
        (trace/emit-error! :rf.error/machine-action-wrote-db
                           {:machine-id      machine-id
                            :action-id       :rf.machine/update-snapshot
                            :offending-value (:db patch)
                            :frame           frame-id
                            :recovery        :logged-and-skipped}))
      (let [clean-patch (select-keys patch permitted-patch-keys)]
        (when (seq clean-patch)
          (frame/swap-frame-db!
            frame-id
            (fn [db]
              ;; No-op merge target when the snapshot is absent — never
              ;; conjure a snapshot for a destroyed / unknown actor.
              (if (contains? (get-in db [:rf/runtime :machines :snapshots]) machine-id)
                (update-in db [:rf/runtime :machines :snapshots machine-id] merge clean-patch)
                db))))))
    nil))
