# Frame State And History

Status: draft finding.

## Crowding Signal

The frame value is a product of app-db and runtime-db, with epoch history over
the whole frame state. The earlier Codex pass treated the partition readers as
mostly redundant. The Claude pass is more precise: the three partition reads
are distinct and heavily used. The real crowding is around sugar, privileged
tooling readers, and retired address vocabulary.

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
- `sub-cache` / `sub-topology` facade aliases for tooling reads
- retired address reads covered in
  [registrar-query-addressing.md](registrar-query-addressing.md)

Implementation evidence:

- `implementation/core/src/re_frame/core.cljc:1810-1868` implements
  `app-db-value`, `runtime-db-value`, `frame-state-value`, and `snapshot-of`.
- `implementation/core/src/re_frame/core.cljc:2807-2914` re-exports epoch
  history, restore, and replacement operations.
- `implementation/core/src/re_frame/core.cljc` also exposes `sub-cache` and
  `sub-topology` facade aliases, while CLJS tool consumers generally import
  `re-frame.subs.tooling/*` directly.
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

8. Subscription tooling needs cache and topology reads, but those are tooling
   surfaces, not app-author front-door surfaces.

## Proposed Cleanup

Do not collapse the three primary partition readers:

```clojure
(rf/app-db-value frame)       ;; app partition
(rf/runtime-db-value frame)   ;; runtime partition
(rf/frame-state-value frame)  ;; coherent whole frame-state projection
```

They answer different questions and have substantial test/tool adoption.

Separate app/test convenience from privileged tool state surgery.

The small app/test surface:

```clojure
(rf/app-db-value :app/main)
(rf/subscribe-once [:some/query] {:frame :app/main})
```

`snapshot-of` should be documented as sugar over `app-db-value` plus frame
context resolution:

```clojure
(rf/snapshot-of [:todos] :app/main)
;; roughly
(get-in (rf/app-db-value :app/main) [:todos])
```

Keep `epoch-history` and `restore-epoch!` because they are semantic operations,
not mere projections. Keep `replace-app-db!`, `replace-runtime-db!`, and
`replace-frame-state!` as privileged tool/test writes, but document them in one
state-surgery section rather than scattering them as everyday app APIs.

Move `sub-cache` and `sub-topology` off the app-facing facade if their only
real callers are tests/REPLs and tooling namespaces. The owning tooling
namespace is the clearer home.

## Why This Is Better

The first-principles model says there is one frame state with two partitions.
That does not mean every partition reader is redundant. `app-db-value`,
`runtime-db-value`, and `frame-state-value` are the three useful projections of
the model.

The cleanup target is the front porch: app authors should see the small read
surface they actually use; tools can import deeper state and cache readers from
tooling namespaces. That keeps the public API small without flattening real
semantic distinctions.
