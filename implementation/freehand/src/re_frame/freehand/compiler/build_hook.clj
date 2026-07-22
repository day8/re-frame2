(ns re-frame.freehand.compiler.build-hook
  "The Shadow build-lifecycle adapter for re-frame.freehand compiler state.

  Shadow's retained functional build-state is the successful-build authority.
  The whole-build compiler registries — view digests, the Layer-1 root/plan
  indexes, the Root Descriptor index, and the compile-time custom-element
  declarations — are HARVESTED at build time from cache-durable carriers, not
  reconstructed from macro-expansion side effects (rf2-u53yy.1). That is what let
  the old `:cache-blockers #{re-frame.freehand}` install tax be removed at the S6
  cut-over: the hook is now the ONLY re-frame.freehand build setting, and a warm daemon
  start REUSES Shadow's disk cache instead of re-expanding the UI-consuming
  namespace set.

  `:compile-prepare`
    - harvests every authoritative member's literal `(v/custom-element …)`
      declarations from SOURCE into a flat all-members manifest — a pure function
      of the build's source, independent of which sources re-expand (S1);
    - coarse-invalidates the UI literal-consumer set when that manifest CHANGED
      on a warm build, so no view keeps a stale baked property/attribute lowering
      (rf2-vxgfnd.141 dim 3);
    - opens a disposable pass seeded from the incoming accepted snapshot,
      pre-touching exactly the CLJS sources Shadow scheduled to compile so a
      recompiled source that dropped its final declaration evicts its accepted
      row, while output-present cache hits keep theirs;
    - wires the runtime removed-source projection (rf2-4vm19): points the
      devtools client's `:build-notify` receiver at
      `re-frame.freehand.rules/shadow-build-notify` (watched dev builds only, and
      never over a consumer's own receiver), so each successful build's
      broadcast `:build-sources` membership evicts a deleted/renamed-away
      source's runtime custom-element rows without a page reload.

  `:compile-finish`
    - folds every authoritative member's descriptor — RESTORED from Shadow's disk
      cache for a cache-hit member (rf2-u53yy.1.1, S0) and freshly stamped for a
      compiled one — into the whole-build registries, running the cross-source
      uniqueness/conflict laws there (a compile-finish error is still a build-time
      error), and carries the finalized candidate in the RETURNED compiler-env.

  The finish harvest is why removing a declaration evicts without any inference
  of \"which sources recompiled\": a recompiled source is re-analyzed fresh (its
  removed def/site simply vanishes from the analyzer map) and a cache-hit source's
  descriptor is restored intact, so the registries are a pure aggregation over the
  current graph's cache-durable descriptors — present identically for cached and
  compiled members.

  There is deliberately no external last-known-good commit. Shadow retains the
  returned state only after the complete optimize/check/flush/watch pipeline
  succeeds; any later failure discards the candidate. The next prepare therefore
  starts from the prior accepted snapshot and overwrites all dirty scratch.

  Shadow's no-pass REPL path runs no build hook. Its macro bookkeeping lives in
  an isolated compiler-env overlay and never changes the accepted snapshot;
  saving and completing a real build publishes the next accepted snapshot.

  Transaction boundary: the accepted build-state and active HMR runtime are
  last-known-good. Shadow may have partially rewritten its raw output directory
  before a late failure; re-frame.freehand does not claim filesystem rollback for that
  raw directory — Shadow's output directory is Shadow's to publish. A fresh page
  load served straight from it can therefore briefly execute rejected candidate
  bytes until the next accepted build overwrites them; the accepted compiler
  snapshot, which re-frame.freehand does own, reverts."
  (:require [re-frame.freehand.compiler.build :as build]
            [re-frame.freehand.compiler.harvest :as harvest]))

;; ---------------------------------------------------------------------------
;; Authoritative membership + the prepare-time compile schedule
;; ---------------------------------------------------------------------------

(defn- member-nss
  "Authoritative declaring namespaces from Shadow's resolved build graph."
  [{:keys [build-sources sources]}]
  (reduce
   (fn [acc resource-id]
     (let [rc (get sources resource-id)]
       (into acc (or (:provides rc)
                     (when-let [n (:ns rc)] #{n})))))
   #{}
   build-sources))

(defn- parallel-build?
  "Whether Shadow schedules this build in parallel — output presence is then the
  exact cache-hit / recompile signal."
  [build-state]
  (and (:executor build-state)
       (not (false? (get-in build-state
                            [:compiler-options :parallel-build])))))

(defn- pass-recompiles-cljs?
  "Whether Shadow will actually (re)compile CLJS resource `resource-id` this pass.
  At `:compile-prepare`, watch reset has already removed output for modified and
  affected sources. Parallel compilation schedules precisely sources with no
  retained output map; sequential compilation calls `generate-output-for-source`,
  which also recompiles retained outputs carrying warnings. Mirror those two
  Shadow branches rather than treating every graph member as dirty: warm cache-hit
  silence must preserve accepted registry rows."
  [{:keys [sources output] :as build-state} parallel? resource-id]
  (let [{:keys [type]} (get sources resource-id)
        prior-output (get output resource-id)]
    (and (= :cljs type)
         (if parallel?
           (not (map? prior-output))
           (or (nil? prior-output)
               (seq (:warnings prior-output)))))))

(defn- recompiled-member-nss
  "Declaring namespaces whose CLJS source Shadow will actually compile this pass —
  Shadow's own compile schedule, read from output presence at `:compile-prepare`.
  They are pre-touched so a recompiled source that dropped its final declaration
  evicts its accepted row; output-present cache hits keep theirs."
  [{:keys [build-sources sources] :as build-state}]
  (let [parallel? (parallel-build? build-state)]
    (reduce
     (fn [acc resource-id]
       (if (pass-recompiles-cljs? build-state parallel? resource-id)
         (let [{:keys [ns provides]} (get sources resource-id)]
           (into acc (or provides (when ns #{ns}))))
         acc))
     #{}
     build-sources)))

;; ---------------------------------------------------------------------------
;; Custom-element harvest (rf2-vxgfnd.141 dim 2, rf2-u53yy.1 S1)
;;
;; Compile-time property lowering reads the custom-element manifest at
;; macroexpansion, and macros expand top-to-bottom, so a view expanded before its
;; tag's `(v/custom-element …)` declaration — or in a source with no `:require`
;; edge to the declaring source — would classify a declared property as an HTML
;; attribute. Harvesting EVERY authoritative member's literal declarations from
;; SOURCE here, at `:compile-prepare`, and storing them beside the open pass makes
;; classification a pure function of the build's declarations rather than of
;; evaluation order OR of which sources re-expand — the direction that let S6 drop
;; the cache blocker.
;; ---------------------------------------------------------------------------

(defn- ui-cache-blocked-source?
  "Shadow's `is-cache-blocked?` predicate specialized to re-frame.freehand — the
  re-frame.freehand literal-consumer set (a `:cljs` source that is, requires, or
  macro-requires `re-frame.freehand`)."
  [{:keys [type ns requires macro-requires]}]
  (and (= :cljs type)
       (or (= 're-frame.freehand ns)
           (contains? (set requires) 're-frame.freehand)
           (contains? (set macro-requires) 're-frame.freehand))))

(defn- resource-source
  "Source text of a Shadow CLJS resource: its already-loaded `:source` string,
  else read from `:url` / `:file`. nil (a source we cannot read) makes the
  harvest a graceful no-op for that member — the `v/custom-element` macro
  remains the authority."
  [{:keys [source url file]}]
  (cond
    (string? source) source
    url  (try (slurp url) (catch Throwable _ nil))
    file (try (slurp file) (catch Throwable _ nil))
    :else nil))

(defn- harvest-all-seed
  "The `[source tag decl]` triples for EVERY authoritative re-frame.freehand-consumer
  source in the build graph — not just the subset Shadow recompiles this pass.

  Harvesting the WHOLE authoritative member set (rf2-u53yy.1 S1) makes the
  custom-element manifest a pure function of the build's SOURCE: a warm
  cache-hit's declarations are re-derived by reading its source, never by
  re-running its macros. That is what lets the manifest be DECOUPLED from the
  view-slice — a warm source's declarations no longer need staging into the pass
  (which marked it touched and evicted its committed views), and the manifest no
  longer depends on macro re-expansion (the direction S6 needed to drop the cache
  blocker). `harvest/source-seed` is a bounded syntactic reader; a source we
  cannot read is a graceful no-op."
  [{:keys [build-sources sources]}]
  (into []
        (mapcat
         (fn [resource-id]
           (let [{:keys [ns] :as rc} (get sources resource-id)]
             (when (and ns (ui-cache-blocked-source? rc))
               (some->> (resource-source rc)
                        (harvest/source-seed ns))))))
        build-sources))

;; ---------------------------------------------------------------------------
;; Declaration-edit warm staleness — COARSE invalidation (rf2-vxgfnd.141, dim 3)
;;
;; A literal view bakes its property/attribute classification at compile from the
;; effective manifest. So a WARM edit that changes only a declaration — a source
;; whose `(v/custom-element …)` grew, shrank, or vanished — leaves every
;; consumer view that Shadow does NOT recompile (no `:require` edge to that
;; declaring source) with a STALE baked lowering: the warm build no longer equals
;; a clean build.
;;
;; The ruled fix (rf2-vxgfnd.141 DECISION, coarse per the Codex opinion) is
;; deliberately NOT a per-tag declaration->consumer dependency graph: declarations
;; are few and change rarely, so when the harvested manifest changes THIS pass we
;; recompile the whole re-frame.freehand literal-consumer set (the set the hook already
;; identifies). Every consumer then re-bakes against the new manifest = a clean
;; build. A change is detected against the ACCEPTED manifest, so an ordinary
;; view-only edit (no declaration delta) invalidates nothing, and a pure
;; cross-namespace move that leaves the tag->properties manifest identical does
;; not either.
;; ---------------------------------------------------------------------------

(defn- manifest-changed?
  "Whether this pass's freshly-harvested all-members `tag -> {:properties …}`
  manifest differs from the accepted one — the coarse invalidation trigger.
  Because the manifest is now a wholesale all-members re-derivation over the
  build's source (rf2-u53yy.1 S1), the comparison is simply the fresh manifest
  against the accepted manifest; no accepted/recompiled overlay is needed. The
  manifest carries `:properties` only, so a declaration's provenance (owning
  namespace) moving does not count as a change unless the properties differ."
  [build-state fresh-manifest]
  (not= (build/accepted-element-manifest build-state) fresh-manifest))

(defn- invalidate-ui-consumers
  "Reset retained output for every re-frame.freehand literal-consumer source, so the
  next schedule recompiles and re-bakes them against the changed manifest. Coarse
  by design (the ruled dimension-3 mechanism): a declaration change is rare, and
  a full UI-consumer recompile trivially restores warm=clean without a per-tag
  dependency graph."
  [build-state]
  (reduce (fn [bs resource-id]
            (if (ui-cache-blocked-source? (get-in bs [:sources resource-id]))
              (update bs :output dissoc resource-id)
              bs))
          build-state
          (:build-sources build-state)))

(defn- reconcile-declaration-edits
  "If this pass's freshly-harvested `fresh-manifest` changes the custom-element
  manifest on a WARM build (accepted version > 0), coarse-invalidate the UI
  literal-consumer set so no view keeps a stale baked lowering (rf2-vxgfnd.141,
  dimension 3). On a cold (version-0) build there is no prior baked lowering to
  invalidate, so this only acts warm."
  [build-state fresh-manifest]
  (let [version (long (or (:version (build/accepted-snapshot build-state)) 0))]
    (if (and (pos? version)
             (manifest-changed? build-state fresh-manifest))
      (invalidate-ui-consumers build-state)
      build-state)))

;; ---------------------------------------------------------------------------
;; The runtime removed-source projection (rf2-4vm19)
;;
;; A deleted or renamed-away saved source never re-runs, so the runtime's
;; SHADOW_NS_RESET producer can never report it and its custom-element ledger
;; rows would persist until a full page load. The ONLY authority for that delta
;; is the compile side's `:build-sources` membership — which shadow itself
;; already broadcasts to every connected runtime on each successful watch build
;; (`:build-complete` `:info :sources`). The pinned client exposes exactly one
;; receiver seam for that broadcast: the `:build-notify` fn, carried as the
;; `custom-notify-fn` closure-define and resolved by munged name off `$CLJS` at
;; call time. Pointing that define at `re-frame.freehand.rules/shadow-build-notify`
;; projects the removed-namespace delta into the existing runtime reload cycle
;; (notify-reload! -> note-reloaded-sources! -> commit-reload!) with no second
;; protocol and no consumer configuration — the hook stays the one re-frame.freehand
;; build setting (rf2-u53yy.1 S6).
;;
;; Injected only where the delta can exist and land: a WATCHED dev build
;; (`:worker-info` — the only mode that hot-reloads; release builds carry no
;; devtools client, so the define would be dead weight there) whose graph
;; actually contains the runtime ledger namespace. In dev mode the define is
;; not baked into any cached source — shadow emits the whole define map into
;; the flushed bootstrap (`CLOSURE_DEFINES`) on every build — so injection at
;; `:compile-prepare` lands unconditionally, with zero cache interaction.
;; ---------------------------------------------------------------------------

(def ^:private client-notify-define
  "The shadow devtools client's ONE `:build-notify` receiver slot
  (`shadow.cljs.devtools.client.env/custom-notify-fn`), the closure-define
  `shadow.build.targets.shared/repl-defines` fills from a build's
  `:devtools {:build-notify …}`."
  'shadow.cljs.devtools.client.env/custom-notify-fn)

(def ^:private runtime-notify-fn
  "`re-frame.freehand.rules/shadow-build-notify`, munged the way the client resolves
  the receiver off `$CLJS` (`cljs.compiler/munge` of the fn symbol)."
  "re_frame.freehand.rules.shadow_build_notify")

(defn- wire-removed-source-notify
  "Point the shadow client's `:build-notify` receiver at
  `re-frame.freehand.rules/shadow-build-notify` for a watched dev build whose graph
  contains the runtime ledger, unless the build claimed the seam itself — a
  consumer's own `:devtools {:build-notify …}` (already merged into the
  closure-defines at configure) always wins; shadow has exactly one receiver
  slot, and removed-source eviction then degrades to the documented
  full-reload bound unless that receiver delegates."
  [build-state members]
  (if (and (= :dev (:shadow.build/mode build-state))
           (:worker-info build-state)
           (contains? members 're-frame.freehand.rules)
           (not (get-in build-state
                        [:compiler-options :closure-defines client-notify-define])))
    (assoc-in build-state
              [:compiler-options :closure-defines client-notify-define]
              runtime-notify-fn)
    build-state))

;; ---------------------------------------------------------------------------
;; The hook
;; ---------------------------------------------------------------------------

(defn hook
  "Shadow build hook. Configure once in `:build-defaults` as
  `:build-hooks [(re-frame.freehand.compiler.build-hook/hook)]`."
  {:shadow.build/stages #{:compile-prepare :compile-finish}}
  [{build-id :shadow.build/build-id
    stage    :shadow.build/stage
    :as      build-state}]
  (case stage
    :compile-prepare
    ;; Harvest EVERY authoritative member's literal custom-element declarations
    ;; ONCE into the flat all-members manifest (rf2-u53yy.1 S1) — a pure function
    ;; of source, independent of macro re-expansion. `element-manifest` enforces
    ;; the cross-source conflict law (throws on a contradiction — a build-time
    ;; error). A warm edit that changed the manifest coarse-invalidates the UI
    ;; literal consumers so none keeps a stale baked lowering; that must precede
    ;; the schedule read so the widened set is scheduled and re-baked this pass.
    (let [fresh-manifest (build/element-manifest build-id
                                                 (harvest-all-seed build-state))
          build-state    (reconcile-declaration-edits build-state fresh-manifest)
          members        (member-nss build-state)
          ;; Wire the runtime removed-source projection (rf2-4vm19): the
          ;; client's `:build-notify` receiver becomes the producer that feeds
          ;; the removed `:build-sources` delta into the runtime ledger — the
          ;; delta a deleted/renamed-away source can never self-report.
          build-state    (wire-removed-source-notify build-state members)]
      (-> (build/prepare-shadow-build build-state
                                      build-id
                                      members
                                      (recompiled-member-nss build-state))
          ;; Store the all-members manifest beside the open pass BEFORE any view
          ;; analyzes, so property lowering is a pure function of the build's
          ;; declarations rather than of evaluation order OR of which sources
          ;; re-expand (rf2-vxgfnd.141 dim 2, rf2-u53yy.1 S1). The manifest sits
          ;; outside the per-source `:touched` ledger, so it can never evict a
          ;; warm source's committed views.
          (build/set-shadow-element-manifest fresh-manifest)))

    :compile-finish
    ;; Fold every authoritative member's cache-durable descriptor into the
    ;; whole-build registries (views S2, roots/plans S4, descriptors S5), restored
    ;; from Shadow's disk cache for a cache-hit member and freshly stamped for a
    ;; compiled one. A pure build-state transform; a later Shadow failure discards
    ;; the returned candidate transactionally.
    (let [candidate (build/shadow-finish-candidate
                     build-state build-id (member-nss build-state))]
      (build/carry-shadow-candidate build-state candidate))

    build-state))
