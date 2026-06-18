# Frame State And History

Status: draft finding.

## Crowding Signal

The frame value is a product of app-db and runtime-db, with epoch history over
the whole frame state. The public read/write surface exposes several
near-projections of that same state.

Current similar spellings:

- `(rf/app-db-value frame)`
- `(rf/runtime-db-value frame)`
- `(rf/frame-state-value frame)`
- `(rf/snapshot-of path frame)`
- `(rf/epoch-history frame)`
- `(rf/restore-epoch! frame epoch-id)`
- `(rf/replace-app-db! frame db)`
- `(rf/reset-app-db! frame)`
- `(rf/replace-runtime-db! frame runtime-db)`
- `(rf/replace-frame-state! frame state)`

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:1810-1868` implements
  `app-db-value`, `runtime-db-value`, `frame-state-value`, and `snapshot-of`.
- `implementation/core/src/re_frame/core.cljc:2807-2914` re-exports epoch
  history, restore, and replacement operations.
- `implementation/epoch/test/re_frame/*` uses these functions heavily for
  restore, concurrency, and production-elision assertions.
- `tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md` exposes restore and
  replacement operations as privileged tool operations.

## Observed Use Cases

1. Unit tests assert a frame's app-db after dispatch.

2. Test fixtures inject app-db or whole frame state before assertions.

3. Xray reads app-db, runtime-db, and epoch records for panels.

4. Pair tools restore or replace state in a running frame.

5. SSR reads and installs durable runtime-db slices during render and
   hydration.

6. Epoch tests need full-frame replacement, not app-db-only replacement,
   because machine actors and resources live in runtime-db.

7. User code occasionally wants a path read for convenience, but that is just a
   projection of app-db.

## Proposed Cleanup

Separate app/test convenience from privileged tool state surgery.

The small app/test surface:

```clojure
(rf/app-db-value :app/main)
(rf/subscribe-once [:some/query] {:frame :app/main})
```

The privileged frame-state surface should be one map-shaped read and one
map-shaped write:

```clojure
(rf/frame-state :app/main {:part :app-db})
(rf/frame-state :app/main {:part :runtime-db})
(rf/frame-state :app/main {:part :all})
(rf/frame-state :app/main {:part :app-db :path [:todos]})

(rf/replace-frame-state! :app/main {:part :app-db :value db})
(rf/replace-frame-state! :app/main {:part :runtime-db :value runtime-db})
(rf/replace-frame-state! :app/main {:part :all :value frame-state})
```

Keep `epoch-history` and `restore-epoch!` because they are semantic operations,
not mere projections. Treat `snapshot-of`, `runtime-db-value`,
`reset-app-db!`, and partition-specific replacement functions as compatibility
or convenience projections over the two primitives.

## Why This Is Better

The first-principles model says there is one frame state with two partitions.
The current API makes that feel like several independent stores. A map-shaped
read/write pair says what is actually true: the caller is reading or replacing a
selected part of one value.

Clojure APIs age well when a small function accepts a clear value describing
the operation. A growing family of nearly identical names does not age as well.
