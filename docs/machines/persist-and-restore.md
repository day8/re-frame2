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

## What the app owns

- **Storage and selection.** Where the string goes and which subtrees it keeps
  are yours. Leave the resource runtime (`:rf.runtime/resources`,
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
| Install changes nothing and reports `:rf.error/handler-exception` | The payload is not a map, a partition is not a map, or it carries a resource runtime subtree | Save maps from `frame-state-value`, and select only `:rf.runtime/machines` from runtime-db |
| A restored machine restarts from `:initial`, with `:rf.error/machine-state-not-in-definition` | The saved `:state` names a state the deployed definition no longer has | Migrate old snapshots before installing, or accept the restart |
| A restored machine restarts from `:initial`, with `:rf.error/machine-snapshot-version-mismatch` | The definition's `:meta :rf/snapshot-version` changed since the save | Migrate old snapshots before installing, or accept the restart |
