(ns re-frame.ui.compiler.build-hook
  "The thin shadow-cljs `:build-hooks` ADAPTER for the re-frame.ui
  compiler-state authority (rf2-df9873 → rf2-phlhfd).

  df9873 delivered the authority itself: per-build-id keyed registries,
  ambient build identity read from the compiler env, and the
  `begin-build!` / `finish-build!` / `abort-build!` pass-boundary
  primitives (all implemented + test-driven). Multi-build ISOLATION and
  warm-cache-start COMPLETENESS already hold with no hook — isolation
  comes from the ambient per-thread identity, completeness from the
  `:cache-blockers` re-macroexpansion. This adapter adds the ONE thing a
  hook is needed for: INCREMENTAL DELETED-SOURCE EVICTION in a live watch
  daemon. A namespace that declared a root / view / custom-element in one
  pass and whose FILE is then deleted between reloads drops out of the
  build graph; this hook evicts its stale registrations at the next
  `:compile-finish`.

  ## The one seam shadow gives us: build-lifecycle stage boundaries

  A `:build-hooks` fn is called by `shadow.build/process-stage` at the
  stages named in its `:shadow.build/stages` metadata, with the whole
  build-state, and MUST return a build-state. shadow's REPL eval path
  compiles through `shadow.build.api/compile-sources` DIRECTLY and never
  runs `shadow.build/compile` — so build hooks NEVER fire on a REPL form
  eval. That is what keeps the RULED REPL posture intact: with no hook
  firing, no pass opens, and a REPL contribution UPSERTS per-key straight
  into committed (no sibling eviction, no wipe). See build.cljc.

    :compile-prepare  ->  begin-build!  — open the pass BEFORE the
                          (possibly parallel) source compilation stages;
                          contributions made during the pass STAGE rather
                          than upsert. begin-build! also DISCARDS any
                          staging a prior FAILED pass left open — which is
                          the abort/failure recovery in a live daemon (see
                          below), so a doomed compile never half-commits.

    :compile-finish   ->  finish-build! against the AUTHORITATIVE member
                          set (the build graph's `:build-sources`). Commit
                          the pass, then evict every committed source
                          ABSENT from the current graph — a deleted FILE.
                          `:build-sources` is the WHOLE resolved graph on
                          every pass (incrementals included), so macro
                          silence from a cache hit is never mistaken for a
                          deletion: authoritative membership drives
                          eviction, not `touched`. Guarded by `pass-open?`
                          so a stray finish with no matching prepare can
                          never commit/evict (belt-and-suspenders REPL /
                          partial-state safety).

  ## Why no `:flush` / failure stage is wired

  shadow's build-hook pipeline exposes NO failure stage: when
  `compile-sources` throws, the exception propagates before
  `:compile-finish` runs, and `:flush` (the SUCCESS terminus, after
  `:compile-finish` has already committed and closed the pass) never runs
  on failure. So there is no stage at which an explicit `abort-build!`
  would be both reachable and non-redundant. The abort semantics an
  aborted pass needs — keep last-known-good, don't half-commit — are
  delivered instead by begin-build!'s discard-on-open at the NEXT
  `:compile-prepare`: the doomed pass's partial staging is dropped before
  any macro of the retry pass can read it, and the committed
  last-known-good is republished untouched. `abort-build!` remains the
  primitive an out-of-band failure handler would call; this adapter does
  not need a stage for it."
  (:require [re-frame.ui.compiler.build :as build]))

(defn- member-nss
  "The authoritative member set for the current pass: the declaring
  namespace symbols provided by every source in the resolved build graph.
  `:build-sources` is a vector of resource-ids into `:sources`; a source's
  `:provides` (falling back to its `:ns`) are the namespace symbols that
  key the compiler registries (build.cljc keys per declaring ns). A
  deleted FILE is absent from `:build-sources`, so its ns drops out of
  this set and `finish-build!` evicts its registrations."
  [{:keys [build-sources sources]}]
  (reduce
   (fn [acc resource-id]
     (let [rc (get sources resource-id)]
       (into acc (or (:provides rc)
                     (when-let [n (:ns rc)] #{n})))))
   #{}
   build-sources))

(defn hook
  "shadow-cljs `:build-hooks` adapter — wire as
  `:build-hooks [(re-frame.ui.compiler.build-hook/hook)]` on the
  re-frame.ui-consuming builds. Marks the re-frame.ui compiler's pass
  boundaries so a live watch daemon evicts a deleted source's stale
  registrations. Pure dispatch over the stage; side-effects into the
  build.cljc authority and returns the build-state unchanged."
  {:shadow.build/stages #{:compile-prepare :compile-finish}}
  [{build-id :shadow.build/build-id
    stage    :shadow.build/stage
    :as      build-state}]
  (case stage
    :compile-prepare
    (build/begin-build! build-id)

    :compile-finish
    ;; Only finalize a pass THIS hook opened — a finish with no matching
    ;; prepare (a stray/partial invocation) must never commit or evict.
    (when (build/pass-open? build-id)
      (build/finish-build! build-id (member-nss build-state)))

    nil)
  build-state)
