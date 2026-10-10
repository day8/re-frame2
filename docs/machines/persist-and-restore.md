# Persist and restore

A machine's snapshot is part of the frame's state, so persisting a machine
means saving that state and installing it again at boot. There is no
machine-specific serializer.

```clojure
(ns app.persist
  (:require [cljs.reader]
            [re-frame.core :as rf]))

;; Save: any time. The app decides what to keep.
(defn save! [frame]
  (let [{app :rf.db/app runtime :rf.db/runtime} (rf/frame-state-value frame)]
    (.setItem js/localStorage "app/saved-state"
              (pr-str {:rf.db/app     app
                       :rf.db/runtime (select-keys runtime [:rf.runtime/machines])}))))

;; Load: at boot, before the app's own boot events.
(defn load! [frame]
  (when-let [stored (.getItem js/localStorage "app/saved-state")]
    (when-let [saved (try (cljs.reader/read-string stored)
                          (catch :default _ nil))]
      (rf/dispatch-sync [:rf/install-frame-state saved] {:frame frame}))))
```

`frame-state-value` returns the frame's app-db under `:rf.db/app` and its
runtime-db under `:rf.db/runtime`. The runtime-db subtree
`:rf.runtime/machines` holds every machine in the frame: singletons, spawned
children and the spawn registry.

`:rf/install-frame-state` replaces app-db with `:rf.db/app` and replaces only
the runtime-db subtrees you saved, so the route slice and anything else the
frame booted with stay as they are. Entry actions are not re-run, and spawned
children come back with their state. Each restored machine's live `:after`
timer is armed again for its full delay, and its `:sensitive` / `:large`
declarations are re-derived from its machine definition, so you save no
classification alongside the snapshots and a restored secret redacts exactly
as the live one did.

Only values that survive `pr-str` and `read-string` persist, which is why a
machine's `:data` holds no functions, atoms or host objects
([The snapshot](concepts.md#the-snapshot)).

## Resume work after restoring

Load the machines artefact and register the current machine definitions before
installing the saved state — spawned children's types as well as singletons, since
a child's snapshot names its type by keyword. A restored actor's snapshot makes it addressable,
but a previously open socket or in-flight HTTP request is not recreated:
restoring does not replay the entry effect that opened it.

Give resumable states an explicit event that re-enters them. The entry action
must reconstruct its input from restored `:data`, because the resume event
does not carry the original request:

```clojure
:working
{:entry :start-work
 :on {:work/resume {:target :working :reenter? true}
      :work/done :complete
      :work/failed :failed}}
```

After installing, dispatch `[machine-id [:work/resume]]` with
`{:frame frame}` when resuming that request is appropriate. Re-entry also
restarts state-bound children. For work that should not resume, migrate the
saved machine to an idle state before installing instead. Only `:after`
timers are automatically re-armed, each with its full delay.

## What the app owns

- **Storage and selection.** Where the string goes and which subtrees it keeps
  are yours. `frame-state-value` returns actual values; trace redaction does
  not sanitize what this `save!` writes. Select or remove fields as needed.
  Leave the resource runtime (`:rf.runtime/resources`,
  `:rf.runtime/work-ledger`, `:rf.runtime/mutations`) out: the install refuses
  it, because a resource cache is not persisted. Resources refetch.
- **Versioning and migration.** A saved snapshot whose `:state` a later deploy
  removed restarts from `:initial` on its first event, with
  `:rf.error/machine-state-not-in-definition`; a changed
  `:meta :rf/snapshot-version` does the same, with
  `:rf.error/machine-snapshot-version-mismatch`. Migrating old data forward is
  the app's job.
- **Reading it back.** `read-string` on stored text is your code, so guard it,
  as `load!` does.

The full contract is in the
[`:rf/install-frame-state` reference](../api/re-frame.core.md#rfinstall-frame-state).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Snapshot restored but the request or socket never starts | Restore does not replay entry effects | Send an explicit resume event that re-enters the state, or restore to idle |
| Install changes nothing and reports `:rf.error/handler-exception` | The payload is not a map, a partition is not a map, or it carries a resource runtime subtree | Save maps from `frame-state-value`, and select only `:rf.runtime/machines` from runtime-db |
| A restored machine restarts from `:initial`, with `:rf.error/machine-state-not-in-definition` | The saved `:state` names a state the deployed definition no longer has | Migrate old snapshots before installing, or accept the restart |
| A restored machine restarts from `:initial`, with `:rf.error/machine-snapshot-version-mismatch` | The definition's `:meta :rf/snapshot-version` changed since the save | Migrate old snapshots before installing, or accept the restart |
