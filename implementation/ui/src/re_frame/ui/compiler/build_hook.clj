(ns re-frame.ui.compiler.build-hook
  "The Shadow 3.4.10 build-lifecycle adapter for re-frame.ui compiler state.

  Shadow's retained functional build-state is the successful-build authority.
  `:compile-prepare` seeds disposable compiler-env scratch from the incoming
  accepted snapshot, captures authoritative namespace membership, pre-touches
  exactly the CLJS sources Shadow scheduled to compile, and snapshots the
  `:output` map Shadow handed it. That pre-touch makes removing a source's final
  declaration observable even though no registry macro then runs, while
  output-present cache hits remain accepted. Macro contributions write only that
  scratch. `:compile-finish`:

  0. reconcile against Shadow's FINAL authoritative compile schedule — every
     CLJS source actually compiled THIS pass is pre-touched too. Membership is
     TOTAL (every CLJS graph member is reconciled; a missing/nil/non-map final
     output fails loud, never silently skipped) and UNFORGEABLE by an output-map
     replacement. It is decided by re-frame.ui's OWN per-pass provenance marker
     corroborated, for a marker-absent replaced map, by re-frame.ui's OWN per-pass
     COMPILE WITNESS — never output-map identity, never Shadow's `:js` object
     identity, and never a wall-clock stamp relationship (re-frame.ui's own or
     Shadow's). At `:compile-prepare` re-frame.ui stamps an opaque per-pass token
     onto every retained output MAP Shadow handed it. Shadow's compile path
     (`do-compile-cljs-resource`) builds a FRESH output map for a (re)compiled
     source, dropping the marker; a later non-scheduling hook that merely
     annotates or transforms a retained output (assoc/update-in — INCLUDING
     replacing only `[:output rid :js]` with a fresh equal-byte string) PRESERVES
     the marker. So a source that still carries the current pass token is
     unforgeably RETAINED (a replacement cannot forge the private token). A
     marker-ABSENT output means the whole map was replaced: re-frame.ui then reads
     its own compile witness — a `cljs.analyzer` pass installed at
     `:compile-prepare` which fires only while Shadow's COMPILER is analyzing a
     source, and whose record lives outside the `[:output rid]` map a replacement
     controls — to tell a genuine COMPILER replacement (a real compile) from a
     non-scheduling HOOK replacement. When the compiler did NOT analyze it,
     `:cached true` is a legitimate disk-cache load and `:cached false` is a
     whole-map replacement that dropped the marker without any compile — the
     latter CANNOT masquerade as compilation and fails loud rather than evict a
     valid accepted view. Keying on re-frame.ui's own marker plus its own compile
     witness — not on the identity of Shadow's `:js` artifact and not on the
     forgeable `:cached` field alone — is immune to the ordinary
     output-transforming shape (a retained output whose `:js` is swapped for a
     fresh, even byte-identical, String by a non-scheduling hook) that a raw
     `:js`-identity test misreads as a recompile and so evicts an untouched
     accepted view; and — no `:compiled-at` millisecond is compared anywhere on
     this path, by re-frame.ui or on its behalf — immune to the
     same-/backwards-millisecond stamp a `>` test misreads as untouched (leaving a
     ghost accepted view). Deliberately NOT Shadow's own
     `[:shadow.build/build-info :compiled]` record: pinned Shadow computes it in
     `shadow.build/resources-compiled-recently` as
     `(and (not cached) (> compiled-at compile-start))`, so it is that very
     wall-clock ordering authority under another name and DROPS a genuine
     same-/backwards-millisecond recompile (rf2-suz5b). A later `:compile-prepare` hook
     (Shadow deep-merges build-local hooks after `:build-defaults` and lets them
     mutate build state) can force a source to recompile AFTER re-frame.ui observed
     the schedule; reading the finish-time marker — and, where the marker is
     absent, re-frame.ui's OWN analyzer-pass compile witness — rather than the
     intermediate prepare schedule closes that hook-order gap, so a forced
     recompile that removed a source's final `ui/defview` evicts its accepted row
     instead of leaving a ghost view. The witness is what makes this work without
     re-admitting the retired wall-clock authority: it records the fact that
     Shadow's compiler ANALYZED the source, outside the `[:output rid]` map any
     replacement controls, so no `:compiled-at` comparison and no
     `::build-info :compiled` membership is consulted anywhere on this path. Missing, malformed, or contradictory
     per-source provenance fails loudly before any candidate is published, never
     silently treated as untouched;
  1. derive the candidate finalized slice (commit staged sources, evict sources
     absent from authoritative membership);
  2. carries `{registries,version}` in the RETURNED compiler-env.

  There is deliberately no external last-known-good commit. Shadow retains the
  returned state only after the complete optimize/check/flush/watch pipeline
  succeeds; any later failure discards the candidate. The next prepare therefore
  starts from the prior accepted snapshot and overwrites all dirty scratch.

  Shadow's no-pass REPL path runs no build hook. Its macro bookkeeping lives in
  an isolated compiler-env overlay and never changes the accepted snapshot;
  saving and completing a real build publishes the next accepted snapshot.

  The hook and `:cache-blockers #{re-frame.ui}` are both load-bearing. On the
  version-0 pass the hook clears any retained output for blocker-covered UI
  consumers; the blocker then prevents a stale disk-cache reload, forcing the
  registry macros to reconstruct the accepted snapshot. Later output-present
  cache hits remain untouched. The hook also supplies pass boundaries and
  deletion eviction.

  Transaction boundary: the accepted build-state and active HMR runtime are
  last-known-good. Shadow may have partially rewritten its raw output directory
  before a late failure; re-frame.ui does not claim filesystem rollback for that
  raw directory — Shadow's output directory is Shadow's to publish. A fresh page
  load served straight from it can therefore briefly execute rejected candidate
  bytes until the next accepted build overwrites them; the accepted compiler
  snapshot, which re-frame.ui does own, reverts."
  (:require [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.harvest :as harvest]))

(def ^:private compile-marker-key
  "re-frame.ui's OWN private per-pass provenance marker key, stamped onto every
  retained output map at `:compile-prepare` (see `stamp-retained-outputs`). It
  is the per-pass fact that decides compile membership at `:compile-finish` —
  Shadow's compile path drops it by replacing the whole output map, an ordinary
  non-scheduling assoc/update-in on a retained output preserves it. Opaque and
  namespaced so no Shadow or hook code collides with it; it is a key on the JVM
  output MAP only and is never emitted into any bundle."
  ::pass-marker)

(def ^:private compile-witness-pass-key
  "Metadata marker identifying re-frame.ui's own analyzer pass, so a warm daemon
  re-installs exactly ONE witness per pass instead of accumulating one per
  compile."
  ::compile-witness-pass)

(defn- analyzed-ns
  "The declaring namespace an analyzer node belongs to.

  Shadow analyzes a source's leading `(ns …)` form while its compile state still
  reads `cljs.user` (`do-analyze-cljs-string` only advances the ns in
  `post-analyze`, AFTER `cljs.analyzer/analyze*` has run the passes), so an `:ns`
  node's OWN `:name` is authoritative for that first form. Every later node
  carries the resolved namespace in its analyzer env. Reading the ns node is what
  makes a viewless source — one whose only remaining form IS its `ns` form,
  precisely the zero-declaration recompile that must evict — witnessed."
  [env ast]
  (if (= :ns (:op ast))
    (:name ast)
    (let [ns (:ns env)]
      (if (symbol? ns) ns (:name ns)))))

(defn- compile-witness-pass
  "re-frame.ui's OWN per-pass CAUSAL compile witness: a `cljs.analyzer` pass
  (`cljs.analyzer/*passes*`, which Shadow binds from `[:analyzer-passes]` on
  every `shadow.build.compiler/analyze` call) recording the declaring namespace
  of every source Shadow's COMPILER actually analyzed this pass.

  It is causal, not corroborative: the pass runs if and only if
  `do-compile-cljs-resource` is analyzing that source's forms. A disk-cache load
  (`load-cached-cljs-resource`) restores analysis data without analyzing and is
  therefore NOT witnessed; a warm hit runs nothing; a hook that replaces
  `[:output rid]` executes no analyzer pass at all. And the record lives in a
  closed-over atom re-frame.ui created this pass — outside the `[:output rid]`
  map — so an output-map replacement can neither reach nor forge it.

  Deliberately NOT Shadow's `[:shadow.build/build-info :compiled]` set: pinned
  Shadow derives that from `(> compiled-at compile-start)`
  (`shadow.build/resources-compiled-recently`), which silently omits a genuine
  recompile whose `System/currentTimeMillis` stamp lands on or before the compile
  start — the same wall-clock ordering authority this path exists to avoid
  (rf2-suz5b, rf2-ialoij). Nothing here reads a millisecond."
  [witnessed]
  (with-meta
    (fn compile-witness [env ast _opts]
      (when-let [ns (analyzed-ns env ast)]
        ;; Read-mostly: only the first node of each namespace writes, so the
        ;; parallel-compile threads contend once per source, not per AST node.
        (when-not (contains? @witnessed ns)
          (swap! witnessed conj ns)))
      ast)
    {compile-witness-pass-key true}))

(defn- install-compile-witness
  "Install this pass's compile witness as the last `[:analyzer-passes]` entry,
  first removing any witness a previous pass of a warm watch daemon left behind
  (they are identified by `compile-witness-pass-key` metadata, never by position)
  so passes cannot accumulate.

  When the build-state carries no `:analyzer-passes` key at all — a plain-JVM
  finish, never a Shadow build, where `shadow.build.api/init` always seeds it —
  this is a no-op rather than a guess at Shadow's defaults. That fails SAFE: an
  unwitnessed marker-absent output is refused loudly at `:compile-finish`, never
  quietly trusted as compiled."
  [build-state witnessed]
  (if-not (contains? build-state :analyzer-passes)
    build-state
    (update build-state :analyzer-passes
            (fn [passes]
              (-> (into [] (remove #(get (meta %) compile-witness-pass-key)) passes)
                  (conj (compile-witness-pass witnessed)))))))

(defn- witnessed-compile?
  "Did re-frame.ui's compile witness see Shadow's compiler analyze this
  resource's declaring namespace (or any namespace it provides) this pass?"
  [witnessed ns provides]
  (boolean (or (and ns (contains? witnessed ns))
               (some #(contains? witnessed %) provides))))

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
  "Whether Shadow 3.4.10 will actually (re)compile CLJS resource `resource-id`
  this pass. At `:compile-prepare`, watch reset has already removed output for
  modified and affected sources. Parallel compilation schedules precisely
  sources with no retained output map; sequential compilation calls
  `generate-output-for-source`, which also recompiles retained outputs carrying
  warnings. Mirror those two Shadow branches rather than treating every graph
  member as dirty: warm cache-hit silence must preserve accepted registry rows."
  [{:keys [sources output] :as build-state} parallel? resource-id]
  (let [{:keys [type]} (get sources resource-id)
        prior-output (get output resource-id)]
    (and (= :cljs type)
         (if parallel?
           (not (map? prior-output))
           (or (nil? prior-output)
               (seq (:warnings prior-output)))))))

(defn- recompiled-member-nss
  "Declaring namespaces whose CLJS source Shadow 3.4.10 will actually compile."
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

(defn- compile-verdict
  "Classify one CLJS `final-output`'s per-pass provenance. A PURE classifier
  returning a keyword; the caller (`actually-recompiled-member-nss`) turns a
  fail-loud verdict into a typed error carrying full per-resource context.

    :compiled  — Shadow (re)compiled this source THIS pass;
    :retained  — a warm hit / marker-preserving metadata-or-`:js` transform /
                 disk-cache load; NOT compiled this pass;

  or a fail-loud reason keyword (`:stale-provenance-marker`,
  `:marker-dropped-without-compile`, `:unmarked-output-missing-cached-flag`).

  Two facts decide it, in order, and NEITHER is a `:js` object identity or a
  `:compiled-at` wall-clock relationship:

  1. re-frame.ui's OWN per-pass MARKER. At `:compile-prepare` re-frame.ui stamped
     `pass-token` onto every retained output MAP (see `stamp-retained-outputs`).
     A warm hit keeps it; a non-scheduling assoc/update-in on a retained output
     (INCLUDING replacing only `[:output rid :js]` with a fresh, even
     byte-identical, String) PRESERVES it; Shadow's compile path AND an
     uncooperative whole-map replacement both DROP it. So marker present and
     equal to `pass-token` is unforgeably RETAINED (a replacement cannot forge
     the private token). A marker present but NOT equal to the token is a stale
     cross-pass artefact and fails loud.

  2. For a marker-ABSENT output — where the whole map was replaced —
     re-frame.ui's OWN per-pass compile witness `compile-witnessed?` (this
     resource's namespace was analyzed by Shadow's compiler this pass, recorded
     by `compile-witness-pass`). It distinguishes a genuine COMPILER replacement
     (`:compiled`) from a non-scheduling HOOK replacement, WITHOUT trusting the
     output map's forgeable `:cached` field to conclude compilation. When the
     compiler did NOT analyze it: `:cached true` is a legitimate disk-cache load
     (RETAINED); `:cached false` is a whole-map replacement that dropped the
     marker without any compile — it cannot be silently treated as a compile and
     fails loud (`:marker-dropped-without-compile`); any other `:cached` value is
     unusable evidence and fails loud.

  Because the marker gate is checked before the witness, a genuine EQUAL-stamp or
  BACKWARDS-stamp recompile is still classified `:compiled` (it dropped the marker
  AND its namespace was analyzed). No `:compiled-at` millisecond is compared here
  or in the witness — unlike Shadow's own `[:shadow.build/build-info :compiled]`
  set, which is exactly such a comparison and would drop that recompile."
  [final-output pass-token compile-witnessed?]
  (let [marker (get final-output compile-marker-key ::unmarked)]
    (cond
      (= marker pass-token)    :retained
      (not= marker ::unmarked) :stale-provenance-marker
      compile-witnessed?       :compiled
      :else
      (case (:cached final-output)
        true  :retained                        ; disk-cache load: replaced map, not compiled
        false :marker-dropped-without-compile   ; non-scheduling whole-map replacement
        :unmarked-output-missing-cached-flag))))

(defn- stamp-retained-outputs
  "Stamp re-frame.ui's opaque per-pass provenance `pass-token` onto every
  retained output MAP present at `:compile-prepare`. Purely additive — it adds
  one private namespaced key and changes no Shadow-visible output byte (in
  particular no `:js`), so it is inert to Shadow's flush and to the emitted
  bundle. Shadow's compile path REPLACES a (re)compiled source's whole output
  map, dropping the marker; a later non-scheduling hook's assoc/update-in on a
  retained output preserves it. The token is regenerated every prepare, so a
  retained output is re-stamped and no stale marker survives from a prior pass.
  Pure build-state transform."
  [build-state pass-token]
  (reduce-kv
   (fn [bs rid output]
     (if (map? output)
       (assoc-in bs [:output rid compile-marker-key] pass-token)
       bs))
   build-state
   (:output build-state)))

(defn- actually-recompiled-member-nss
  "Declaring namespaces of every CLJS source Shadow ACTUALLY (re)compiled in
  THIS pass, read from the FINAL build-state at `:compile-finish` — after every
  schedule-mutating `:compile-prepare` hook and after compilation ran.

  Provenance is TOTAL: EVERY CLJS member of the authoritative build graph is
  reconciled. A missing, nil, non-map, or otherwise unusable final output is
  never silently skipped (which would let an accepted row survive with no
  per-resource evidence); it throws a typed compiler error naming the build id,
  resource id, declaring namespace/provides, reason, and recovery before any
  candidate is derived.

  Provenance is UNFORGEABLE by an output-map replacement: a member counts as
  recompiled per `compile-verdict`, which gates FIRST on re-frame.ui's own
  per-pass marker (unforgeable RETAINED) and, only for a marker-absent replaced
  map, on re-frame.ui's own per-pass compile witness (`compile-witness-pass`)
  rather than the output map's forgeable `:cached` field. A whole-map replacement
  that drops the marker without the compiler having analyzed the source cannot
  masquerade as compilation — it fails loud. This remains immune to a
  `:js`-object-identity misread and to same-/backwards-millisecond compile stamps
  — neither re-frame.ui nor anything it consults compares a `:compiled-at`."
  [{:keys [build-sources sources output] :as build-state} pass-token witnessed]
  (let [build-id (:shadow.build/build-id build-state)]
    (reduce
     (fn [acc resource-id]
       (let [{:keys [type ns provides]} (get sources resource-id)]
         (if (not= :cljs type)
           acc
           (let [final-output (get output resource-id)]
             (if-not (map? final-output)
               (throw
                (ex-info
                 (str "re-frame.ui compile-finish found CLJS build member "
                      resource-id " (" (or ns provides) ") with no usable final "
                      "output map; refusing to publish an accepted candidate while "
                      "a graph member's per-resource compile evidence is absent")
                 {::error ::missing-compile-output
                  :build-id build-id
                  :resource-id resource-id
                  :ns ns
                  :provides provides
                  :reason :absent-or-non-map-final-output
                  :final-output final-output
                  :recovery :ensure-shadow-output-for-cljs-member}))
               (case (compile-verdict final-output pass-token
                                      (witnessed-compile? witnessed ns provides))
                 :compiled (into acc (or provides (when ns #{ns})))
                 :retained acc
                 :stale-provenance-marker
                 (throw
                  (ex-info
                   (str "re-frame.ui compile-finish found CLJS build member "
                        resource-id " carrying a STALE per-pass provenance marker "
                        "(not this pass's token); refusing to guess whether it was "
                        "compiled this pass")
                   {::error ::ambiguous-compile-evidence
                    :build-id build-id
                    :resource-id resource-id
                    :ns ns
                    :provides provides
                    :reason :stale-provenance-marker
                    :recovery :preserve-ui-pass-marker-or-remove-output-to-schedule}))
                 :marker-dropped-without-compile
                 (throw
                  (ex-info
                   (str "re-frame.ui compile-finish found CLJS build member "
                        resource-id " whose retained output map was REPLACED "
                        "(re-frame.ui's per-pass marker was dropped) although "
                        "Shadow's compiler never analyzed it this pass; a "
                        "non-scheduling whole-map replacement cannot count as "
                        "compilation")
                   {::error ::ambiguous-compile-evidence
                    :build-id build-id
                    :resource-id resource-id
                    :ns ns
                    :provides provides
                    :reason :marker-dropped-without-compile
                    :recovery :preserve-ui-pass-marker-or-remove-output-to-schedule}))
                 :unmarked-output-missing-cached-flag
                 (throw
                  (ex-info
                   (str "re-frame.ui compile-finish found CLJS build member "
                        resource-id " with no per-pass provenance marker and no "
                        "usable Shadow :cached flag; refusing to guess whether it "
                        "was compiled this pass")
                   {::error ::ambiguous-compile-evidence
                    :build-id build-id
                    :resource-id resource-id
                    :ns ns
                    :provides provides
                    :reason :unmarked-output-missing-cached-flag
                    :cached (:cached final-output)
                    :recovery :preserve-ui-pass-marker-or-remove-output-to-schedule}))))))))
     #{}
     build-sources)))

(defn- reconcile-final-schedule
  "Before deriving the finish candidate, pre-touch every source Shadow actually
  recompiled this pass but which re-frame.ui did NOT pre-touch at
  `:compile-prepare` — a source a later prepare hook forced to recompile after
  re-frame.ui observed the schedule. Pre-touching evicts the accepted row of a
  source whose successful recompile contributed no re-frame.ui declaration (its
  final `ui/defview` was removed), closing the ghost-view gap of CLOSED
  rf2-n7ff9f; a recompiled source that DID re-declare is already touched+staged,
  so the union is idempotent, and a warm cache hit (compile stamp unchanged from
  the prepare snapshot) keeps its accepted row.

  When no re-frame.ui pass is open (a non-Shadow / plain-JVM finish with no
  scratch) this is a no-op — the prepare-time pre-touch is the sole reconciler.
  When a pass IS open BOTH prepare-time provenance facts MUST be present — the
  `:pass-token` stamped onto retained outputs and the `:compile-witness` the
  analyzer pass writes into; either one missing is unobservable provenance and
  fails loudly rather than silently reconciling against an assumed-empty compile
  schedule. Pure build-state transform (apart from that guard)."
  [build-state]
  (let [scratch (get-in build-state [:compiler-env build/scratch-key])]
    (if-not scratch
      build-state
      (do
        (doseq [k [:pass-token :compile-witness]]
          (when-not (contains? scratch k)
            (throw
             (ex-info
              (str "re-frame.ui compile-finish observed no per-pass compile "
                   "provenance (missing prepare-time " k "); refusing to "
                   "reconcile against an assumed-empty compile schedule")
              {::error ::missing-pass-provenance
               :reason k
               :recovery :configure-ui-build-hook-once}))))
        (let [extra (actually-recompiled-member-nss build-state
                                                    (:pass-token scratch)
                                                    @(:compile-witness scratch))]
          (if (seq extra)
            (update-in build-state
                       [:compiler-env build/scratch-key :touched]
                       (fnil into #{}) extra)
            build-state))))))

(defn- ui-client-build?
  [build-state]
  (boolean (some (member-nss build-state)
                 '#{re-frame.ui.client
                    re-frame.ui.runtime})))

(defn- validate-ui-cache-blocker!
  "Fail a dev UI build before opening scratch unless the cache blocker which
  makes registry macros authoritative on warm daemon startup is configured."
  [build-state]
  (when (ui-client-build? build-state)
    (let [blockers (get-in build-state [:build-options :cache-blockers])]
      (when-not (and (set? blockers) (contains? blockers 're-frame.ui))
        (throw
         (ex-info
          (str "re-frame.ui dev builds require "
               ":cache-blockers #{re-frame.ui}; refusing a plausible but "
               "incomplete warm-cache build")
          {::error ::cache-blocker-missing
           :configured blockers
           :expected '#{re-frame.ui}
           :recovery :configure-ui-build-hook-and-cache-blocker}))))))

(defn- ui-cache-blocked-source?
  "Shadow's `is-cache-blocked?` predicate specialized to re-frame.ui."
  [{:keys [type ns requires macro-requires]}]
  (and (= :cljs type)
       (or (= 're-frame.ui ns)
           (contains? (set requires) 're-frame.ui)
           (contains? (set macro-requires) 're-frame.ui))))

(defn- reset-cold-ui-consumer-output
  "On the first accepted pass of a daemon, remove retained build output for
  every source Shadow's re-frame.ui cache blocker covers.

  `:cache-blockers` prevents loading a macro-side-effecting source FROM the
  disk cache, but Shadow may enter compile-prepare with an output map retained
  by its wider build cache. Removing those maps here closes that earlier skip
  path. Compile then macroexpands the sources (the validated blocker prevents
  a stale disk reload), reconstructing the version-0 accepted registries."
  [build-state]
  (if (and (ui-client-build? build-state)
           (zero? (long (:version (build/accepted-snapshot build-state)))))
    (reduce (fn [state resource-id]
              (if (ui-cache-blocked-source?
                   (get-in state [:sources resource-id]))
                (update state :output dissoc resource-id)
                state))
            build-state
            (:build-sources build-state))
    build-state))


;; ---------------------------------------------------------------------------
;; Deterministic custom-element pre-seed (rf2-vxgfnd.141, dimension 2)
;;
;; Compile-time property lowering reads the `elements` slice at macroexpansion,
;; and macros expand top-to-bottom, so a view expanded before its tag's
;; `(ui/custom-element …)` declaration — or in a source that compiled before the
;; declaring source, with no `:require` edge to order them — saw an empty slice
;; and lowered a declared property to an HTML attribute. Reading each RECOMPILED
;; UI source's top-level literal declarations here, at `:compile-prepare`, and
;; staging them into the open pass BEFORE any view analyzes makes classification
;; a pure function of the build's declarations rather than evaluation order.
;; Restricted to recompiled sources: a warm cache-hit keeps its accepted rows
;; (re-staging elements-only would evict its committed views at commit) and its
;; declarations stay visible through the committed aggregate anyway.
;; ---------------------------------------------------------------------------

(defn- resource-source
  "Source text of a Shadow CLJS resource: its already-loaded `:source` string,
  else read from `:url` / `:file`. nil (a source we cannot read) makes the
  harvest a graceful no-op for that member — the `ui/custom-element` macro
  remains the authority."
  [{:keys [source url file]}]
  (cond
    (string? source) source
    url  (try (slurp url) (catch Throwable _ nil))
    file (try (slurp file) (catch Throwable _ nil))
    :else nil))

(defn- harvest-seed
  "The `[source tag decl]` triples for every RECOMPILED re-frame.ui-consumer
  source scheduled this pass — the declarations that must seed the elements
  slice before any view analyzes."
  [{:keys [build-sources sources] :as build-state}]
  (let [parallel? (parallel-build? build-state)]
    (into []
          (mapcat
           (fn [resource-id]
             (let [{:keys [ns] :as rc} (get sources resource-id)]
               (when (and ns
                          (pass-recompiles-cljs? build-state parallel? resource-id)
                          (ui-cache-blocked-source? rc))
                 (some->> (resource-source rc)
                          (harvest/source-seed ns))))))
          build-sources)))

;; ---------------------------------------------------------------------------
;; Declaration-edit warm staleness — COARSE invalidation (rf2-vxgfnd.141, dim 3)
;;
;; A literal view bakes its property/attribute classification at compile from the
;; effective manifest. So a WARM edit that changes only a declaration — a source
;; whose `(ui/custom-element …)` grew, shrank, or vanished — leaves every
;; consumer view that Shadow does NOT recompile (no `:require` edge to that
;; declaring source) with a STALE baked lowering: the warm build no longer equals
;; a clean build.
;;
;; The ruled fix (rf2-vxgfnd.141 DECISION, coarse per the Codex opinion) is
;; deliberately NOT a per-tag declaration->consumer dependency graph: declarations
;; are few and change rarely, so when the harvested manifest changes THIS pass we
;; recompile the whole re-frame.ui literal-consumer set (the cache-blocked set the
;; hook already identifies). Every consumer then re-bakes against the new manifest
;; = a clean build. A change is detected against the ACCEPTED manifest, so an
;; ordinary view-only edit (no declaration delta) invalidates nothing, and a pure
;; cross-namespace move that leaves the tag->properties manifest identical does
;; not either.
;; ---------------------------------------------------------------------------

(defn- effective-manifest
  "The `tag -> decl` manifest this pass WILL commit: the accepted rows of the
  sources NOT recompiled this pass, overlaid with the harvested declarations of
  the recompiled sources (whose accepted rows are being replaced). A pure
  function of the accepted snapshot plus this pass's harvest."
  [build-state recompiled-nss seed]
  (let [warm (reduce-kv
              (fn [m src regs]
                (if (contains? recompiled-nss src)
                  m
                  (merge m (get regs build/elements))))
              {}
              (:registries (build/accepted-snapshot build-state)))]
    (reduce (fn [m [_src tag decl]] (assoc m tag decl)) warm seed)))

(defn- manifest-changed?
  "Whether this pass's effective `tag -> decl` manifest differs from the accepted
  one — the coarse invalidation trigger. Compares only the property manifest, so
  a declaration's provenance (owning namespace) moving does not count as a change
  unless the declared properties actually differ."
  [build-state recompiled-nss seed]
  (not= (build/accepted-aggregate build/elements build-state)
        (effective-manifest build-state recompiled-nss seed)))

(defn- invalidate-ui-consumers
  "Reset retained output for every re-frame.ui literal-consumer source, so the
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
  "If this pass changes the custom-element manifest on a WARM build (accepted
  version > 0), coarse-invalidate the UI literal-consumer set so no view keeps a
  stale baked lowering (rf2-vxgfnd.141, dimension 3). On the version-0 pass
  `reset-cold-ui-consumer-output` already covers it, so this only acts warm."
  [build-state]
  (let [version (long (or (:version (build/accepted-snapshot build-state)) 0))]
    (if (and (pos? version)
             (manifest-changed? build-state
                                (recompiled-member-nss build-state)
                                (harvest-seed build-state)))
      (invalidate-ui-consumers build-state)
      build-state)))

(defn hook
  "Shadow build hook. Configure once in `:build-defaults` as
  `:build-hooks [(re-frame.ui.compiler.build-hook/hook)]`."
  {:shadow.build/stages #{:compile-prepare :compile-finish}}
  [{build-id :shadow.build/build-id
    stage    :shadow.build/stage
    :as      build-state}]
  (case stage
    :compile-prepare
    (do
      (validate-ui-cache-blocker! build-state)
      (let [build-state (reset-cold-ui-consumer-output build-state)
            ;; Coarse dimension-3 invalidation: a WARM edit that changed the
            ;; custom-element manifest recompiles every UI literal consumer so
            ;; none keeps a stale baked lowering (rf2-vxgfnd.141). Must precede
            ;; the schedule/stamp so the widened consumer set is scheduled and
            ;; re-seeded this same pass.
            build-state (reconcile-declaration-edits build-state)
            pass-token  (str (java.util.UUID/randomUUID))
            witnessed   (atom #{})
            stamped     (-> build-state
                            (stamp-retained-outputs pass-token)
                            (install-compile-witness witnessed))]
        ;; Stamp an opaque per-pass provenance token onto every retained `:output`
        ;; map Shadow handed us BEFORE any compilation, install this pass's causal
        ;; compile witness, and remember both in scratch, so `:compile-finish` can
        ;; identify the sources Shadow actually (re)compiled this pass: a compiled
        ;; source's fresh output map LOSES the token, while a later non-scheduling
        ;; hook's assoc/update-in on a retained output — INCLUDING replacing only
        ;; `[:output rid :js]` with a fresh equal-byte string — PRESERVES it. For
        ;; the marker-absent remainder the witness says whether Shadow's compiler
        ;; actually analyzed the source. Robust to that output-transforming shape
        ;; (which a raw `:js`-identity test misread as a recompile) and free of any
        ;; `:compiled-at` wall-clock comparison — including Shadow's own
        ;; `(> compiled-at compile-start)` `::build-info :compiled` set — so
        ;; same-/backwards-millisecond recompiles are still caught (rf2-v7wqk,
        ;; rf2-vxgfnd.194, rf2-vxgfnd.255, rf2-vxgfnd.282, rf2-ialoij, rf2-suz5b).
        (-> (build/prepare-shadow-build stamped
                                        build-id
                                        (member-nss stamped)
                                        (recompiled-member-nss stamped))
            ;; Pre-seed this pass's recompiled custom-element declarations into
            ;; the open scratch BEFORE any view analyzes, so property lowering is
            ;; order-independent (rf2-vxgfnd.141, dimension 2). Pure build-state
            ;; transform — `cljs.env/*compiler*` is not bound during the hook.
            (build/seed-shadow-elements (harvest-seed stamped))
            (assoc-in [:compiler-env build/scratch-key :pass-token]
                      pass-token)
            (assoc-in [:compiler-env build/scratch-key :compile-witness]
                      witnessed))))

    :compile-finish
    ;; Reconcile against Shadow's final compile schedule. All are pure
    ;; build-state transforms; a later Shadow failure discards the returned
    ;; state transactionally.
    (let [build-state (reconcile-final-schedule build-state)
          candidate (build/shadow-finish-candidate
                     build-state build-id (member-nss build-state))]
      (build/carry-shadow-candidate build-state candidate))

    build-state))
