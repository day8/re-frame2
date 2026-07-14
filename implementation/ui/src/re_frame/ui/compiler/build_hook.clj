(ns re-frame.ui.compiler.build-hook
  "The Shadow 3.4.10 build-lifecycle adapter for re-frame.ui compiler state.

  Shadow's retained functional build-state is the successful-build authority.
  `:compile-prepare` seeds disposable compiler-env scratch from the incoming
  accepted snapshot, captures authoritative namespace membership, pre-touches
  exactly the CLJS sources Shadow scheduled to compile, and records the pass
  start time. That pre-touch makes removing a source's final declaration
  observable even though no registry macro then runs, while output-present cache
  hits remain accepted. Macro contributions write only that scratch.
  `:compile-finish`:

  0. reconcile against Shadow's FINAL authoritative compile schedule — every
     CLJS source actually compiled THIS pass is pre-touched too. Membership is
     read from EXACT per-pass provenance, never wall-clock ordering: at
     `:compile-prepare` re-frame.ui snapshots the `:output` map Shadow handed
     it, and a source counts as recompiled iff its finish-time output OBJECT is
     present and not `identical?` to that snapshot's. Shadow installs a
     freshly-built output object only for the sources it recompiles this pass (a
     warm cache hit keeps the byte-identical retained object), so identity is
     the ground truth — immune to the millisecond collision where a retained
     warm output's `:compiled-at` stamp equals the next pass start. A later
     `:compile-prepare` hook (Shadow deep-merges build-local hooks after
     `:build-defaults` and lets them mutate build state) can force a source to
     recompile AFTER re-frame.ui observed the schedule; comparing the finish
     output objects against the prepare snapshot, not the intermediate prepare
     schedule, closes that hook-order gap so a forced recompile that removed a
     source's final `ui/defview` evicts its accepted row instead of leaving a
     ghost view;
  1. derive the candidate finalized slice (commit staged sources, evict sources
     absent from authoritative membership) and its whole-build digest;
  2. purely validate and project that digest into exactly one compiled
     re-frame.ui.digest-carrier `[:output resource-id :js]` string;
  3. carries `{registries,digest,version}` in the RETURNED compiler-env.

  There is deliberately no external last-known-good commit. Shadow retains the
  returned state only after the complete optimize/check/flush/watch pipeline
  succeeds; any later failure discards the candidate. The next prepare therefore
  starts from the prior accepted snapshot and overwrites all dirty scratch.
  Missing/duplicate carrier output fails before a candidate is returned. The
  equal-width replacement preserves source-map offsets.

  Shadow's no-pass REPL path runs no build hook. Its macro bookkeeping lives in
  an isolated compiler-env overlay and never changes the accepted snapshot or
  carrier; saving and completing a real build publishes the next digest.

  The hook and `:cache-blockers #{re-frame.ui}` are both load-bearing. On the
  version-0 pass the hook clears any retained output for blocker-covered UI
  consumers; the blocker then prevents a stale disk-cache reload, forcing the
  registry macros to reconstruct the accepted snapshot. Later output-present
  cache hits remain untouched. The hook also supplies pass boundaries,
  deletion eviction and client publication.

  Transaction boundary: the accepted build-state and active HMR runtime are
  last-known-good. Shadow may have partially rewritten its raw output directory
  before a late failure; re-frame.ui does not claim filesystem rollback for that
  raw directory. `promote-served-generation` below closes exactly that gap for
  the SERVED bytes by publishing the candidate output-dir onto a separate stable
  served directory only when the whole pipeline succeeds (rf2-vxgfnd.237)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.ui.compiler.build :as build]))

(def digest-sentinel
  "The unique fixed-width literal emitted by re-frame.ui.digest-carrier.
  Exactly the same width as a bd1- + 16-hex digest. Internal build-tool
  contract, not a library API."
  "__RF2_UI_DIGEST_XX__")

(def ^:private carrier-ns 're-frame.ui.digest-carrier)

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

(defn- recompiled-member-nss
  "Declaring namespaces whose CLJS source Shadow 3.4.10 will actually compile.

  At `:compile-prepare`, watch reset has already removed output for modified
  and affected sources. Parallel compilation schedules precisely sources with
  no retained output map; sequential compilation calls
  `generate-output-for-source`, which also recompiles retained outputs carrying
  warnings. Mirror those two Shadow branches rather than treating every graph
  member as dirty: warm cache-hit silence must preserve accepted registry rows."
  [{:keys [build-sources sources output executor] :as build-state}]
  (let [parallel? (and executor
                       (not (false? (get-in build-state
                                           [:compiler-options
                                            :parallel-build]))))]
    (reduce
     (fn [acc resource-id]
       (let [{:keys [type ns provides]} (get sources resource-id)
             prior-output (get output resource-id)
             scheduled? (if parallel?
                          (not (map? prior-output))
                          (or (nil? prior-output)
                              (seq (:warnings prior-output))))]
         (if (and (= :cljs type) scheduled?)
           (into acc (or provides (when ns #{ns})))
           acc)))
     #{}
     build-sources)))

(defn- actually-recompiled-member-nss
  "Declaring namespaces of every CLJS source Shadow ACTUALLY (re)compiled in
  THIS pass, read from the FINAL build-state at `:compile-finish` — after every
  schedule-mutating `:compile-prepare` hook and after compilation ran —
  distinguished by EXACT per-pass provenance: output-object identity, never
  wall-clock ordering.

  `pass-output` is the `:output` map re-frame.ui snapshotted at
  `:compile-prepare` (the outputs Shadow was about to compile from). Shadow
  installs a freshly-built output OBJECT for a source iff it recompiles it this
  pass: parallel compile leaves already-present outputs untouched, and
  sequential `generate-output-for-source` returns the retained output object
  unchanged for a warm cache hit. So a source whose finish-time output is
  present and not `identical?` to its snapshot object was compiled this pass,
  regardless of its `:compiled-at` stamp. This is immune to the millisecond
  collision where a retained warm output's stamp equals the next pass start (the
  former `compiled-at >= pass-start` test wrongly counted it as recompiled and
  evicted its accepted row), and immune to the build-local hook merge order
  re-frame.ui cannot control."
  [{:keys [build-sources sources output]} pass-output]
  (reduce
   (fn [acc resource-id]
     (let [{:keys [type ns provides]} (get sources resource-id)
           final-output (get output resource-id)]
       (if (and (= :cljs type)
                (some? final-output)
                (not (identical? final-output (get pass-output resource-id))))
         (into acc (or provides (when ns #{ns})))
         acc)))
   #{}
   build-sources))

(defn- reconcile-final-schedule
  "Before deriving the finish candidate, pre-touch every source Shadow actually
  recompiled this pass but which re-frame.ui did NOT pre-touch at
  `:compile-prepare` — a source a later prepare hook forced to recompile after
  re-frame.ui observed the schedule. Pre-touching evicts the accepted row of a
  source whose successful recompile contributed no re-frame.ui declaration (its
  final `ui/defview` was removed), closing the ghost-view gap of CLOSED
  rf2-n7ff9f; a recompiled source that DID re-declare is already touched+staged,
  so the union is idempotent, and a warm cache hit (output object unchanged from
  the prepare snapshot) keeps its accepted row.

  When no re-frame.ui pass is open (a non-Shadow / plain-JVM finish with no
  scratch) this is a no-op — the prepare-time pre-touch is the sole reconciler.
  When a pass IS open the prepare-time `:pass-output` provenance snapshot MUST
  be present; its absence is unobservable provenance and fails loudly rather
  than silently reconciling against an assumed-empty compile schedule. Pure
  build-state transform (apart from that guard)."
  [build-state]
  (let [scratch (get-in build-state [:compiler-env build/scratch-key])]
    (if-not scratch
      build-state
      (do
        (when-not (contains? scratch :pass-output)
          (throw
           (ex-info
            (str "re-frame.ui compile-finish observed no per-pass compile "
                 "provenance (missing prepare-time :pass-output snapshot); "
                 "refusing to reconcile against an assumed-empty compile schedule")
            {::error ::missing-pass-provenance
             :recovery :configure-ui-build-hook-once})))
        (let [extra (actually-recompiled-member-nss build-state
                                                    (:pass-output scratch))]
          (if (seq extra)
            (update-in build-state
                       [:compiler-env build/scratch-key :touched]
                       (fnil into #{}) extra)
            build-state))))))

(defn- marker-count [^String s ^String marker]
  (loop [from 0 n 0]
    (let [i (.indexOf s marker (int from))]
      (if (neg? i)
        n
        (recur (+ i (count marker)) (inc n))))))

(defn- carrier-resource-ids
  [{:keys [build-sources sources]}]
  (into []
        (filter (fn [rid]
                  (let [{:keys [ns provides]} (get sources rid)]
                    (or (= carrier-ns ns)
                        (contains? (set provides) carrier-ns)))))
        build-sources))

(defn- ui-client-build?
  [build-state]
  (boolean (some (member-nss build-state)
                 '#{re-frame.ui.client
                    re-frame.ui.runtime
                    re-frame.ui.digest-carrier})))

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
               "incomplete warm-cache digest")
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

(defn- fail-carrier! [message data]
  (throw
   (ex-info message
            (merge {::error ::carrier-output-invalid
                    :recovery :configure-ui-build-hook-and-cache-blocker}
                   data))))

(defn project-build-digest
  "Pure Shadow-3.4.10 output projection. If the build has no UI client/runtime,
  return it unchanged. Otherwise require exactly one carrier resource and one
  sentinel in its compiled `[:output rid :js]`, then replace it with the equal-
  length compiler digest. Cached outputs, multi-entry/lazy module maps and every
  non-carrier byte are retained. Throws before candidate carriage on drift."
  [build-state digest]
  (let [ui-client? (ui-client-build? build-state)
        rids (carrier-resource-ids build-state)]
    (cond
      (and (not ui-client?) (empty? rids))
      build-state

      (not= 1 (count rids))
      (fail-carrier!
       "re-frame.ui expected exactly one compiled digest carrier output"
       {:carrier-resource-ids rids :count (count rids)})

      (or (not (string? digest))
          (not (str/starts-with? digest "bd1-"))
          (not= (count digest-sentinel) (count digest)))
      (fail-carrier!
       "re-frame.ui compiler produced an invalid fixed-width build digest"
       {:digest digest :expected-width (count digest-sentinel)})

      :else
      (let [rid (first rids)
            js  (get-in build-state [:output rid :js])
            n   (if (string? js) (marker-count js digest-sentinel) 0)]
        (when-not (= 1 n)
          (fail-carrier!
           "re-frame.ui digest carrier output must contain exactly one sentinel"
           {:carrier-resource-id rid :sentinel-count n :js-string? (string? js)}))
        (assoc-in build-state [:output rid :js]
                  (str/replace js digest-sentinel digest))))))

;; ---------------------------------------------------------------------------
;; Artifact generation/activation separation (rf2-vxgfnd.237)
;;
;; Shadow's browser target flushes the candidate module bytes to its raw
;; `:output-dir` at `:flush`, BEFORE any later `:flush` step can fail; on a
;; downstream failure Shadow discards the returned build-state (reverting the
;; accepted compiler snapshot) but does NOT roll back that raw directory. So a
;; fresh page load or a first lazy-module request served straight from
;; `:output-dir` would execute/advertise the rejected candidate generation.
;;
;; The fix separates GENERATION from ACTIVATION at the served-bytes boundary:
;; Shadow writes the candidate to `:output-dir`; the browser is served from a
;; SEPARATE stable directory (the dev-http `:http-root`); and this hook — the
;; LAST configured `:flush` hook — publishes the candidate onto the stable
;; directory only once every earlier flush step succeeded. Because Shadow runs
;; a stage's target flush first and then its `:build-hooks` in configured order
;; (defaults before build-local), placing this hook last makes the publish the
;; terminal action of a fully-successful build: any earlier downstream failure
;; aborts before it, leaving the served generation on the prior accepted bytes.
;; ---------------------------------------------------------------------------

(defn- stale-copy?
  "Cheap freshness check: copy `src` onto `dest` only when the destination is
  missing or differs in length / is older. Keeps warm publishes O(changed)."
  [^java.io.File src ^java.io.File dest]
  (or (not (.exists dest))
      (not= (.length src) (.length dest))
      (> (.lastModified src) (.lastModified dest))))

(defn- publish-tree!
  "Recursively copy every regular file under `from` onto `to`, creating parent
  directories and overwriting stale destinations. Deliberately does NOT delete
  destination files absent from `from`: the served shell (a hand-written
  index.html) lives in the stable directory and is not a build artifact."
  [^java.io.File from ^java.io.File to]
  (when (.isDirectory from)
    (let [from-path (.toPath from)]
      (doseq [^java.io.File src (file-seq from)
              :when (.isFile src)]
        (let [dest (io/file to (.toString (.relativize from-path (.toPath src))))]
          (when (stale-copy? src dest)
            (io/make-parents dest)
            (io/copy src dest)))))))

(defn promote-served-generation
  "A `:flush`-stage build hook that PUBLISHES the just-flushed candidate output
  onto the stable served directory, atomically w.r.t. the configured pipeline.

  Configure it as the LAST entry in a dev `:browser` build's `:build-hooks`,
  with the build's `:output-dir` pointing at a CANDIDATE directory and the
  dev-http `:http-root` pointing at a separate STABLE served directory:

    :output-dir \"out/<build>/candidate\"
    :devtools   {:http-root \"out/<build>/stable\" ...}
    :build-hooks [... (re-frame.ui.compiler.build-hook/promote-served-generation)]

  Being the terminal `:flush` hook, it runs only after Shadow's target flush and
  every other downstream flush step have succeeded, so the served generation
  advances iff the whole pipeline succeeds; a downstream failure aborts before
  the publish and leaves the served directory on the prior accepted generation.

  When no separation is configured (`:http-root` absent, or the same path as
  `:output-dir`) it is a no-op — the served directory IS the output directory,
  the un-separated legacy behaviour. Dev-only JVM build tooling; never part of
  any CLJS bundle."
  {:shadow.build/stages #{:flush}}
  [build-state]
  (let [candidate (get-in build-state [:build-options :output-dir])
        stable    (get-in build-state [:shadow.build/config :devtools :http-root])]
    (when (and candidate (seq (str stable)))
      (let [candidate (io/file candidate)
            stable    (io/file stable)]
        (when-not (= (.getCanonicalPath candidate) (.getCanonicalPath stable))
          (publish-tree! candidate stable)))))
  build-state)

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
      (let [build-state (reset-cold-ui-consumer-output build-state)]
        ;; Snapshot the `:output` map Shadow handed us BEFORE any compilation, so
        ;; `:compile-finish` can identify the sources Shadow actually recompiled
        ;; this pass by output-object identity — exact per-pass provenance,
        ;; robust to a later prepare hook mutating the schedule and immune to the
        ;; millisecond stamp collision (rf2-vxgfnd.194, rf2-vxgfnd.255).
        (-> (build/prepare-shadow-build build-state
                                        build-id
                                        (member-nss build-state)
                                        (recompiled-member-nss build-state))
            (assoc-in [:compiler-env build/scratch-key :pass-output]
                      (:output build-state)))))

    :compile-finish
    ;; Reconcile against Shadow's final compile schedule, then project. All are
    ;; pure build-state transforms; a later Shadow failure discards the returned
    ;; state transactionally.
    (let [build-state (reconcile-final-schedule build-state)
          candidate (build/shadow-finish-candidate
                     build-state build-id (member-nss build-state))
          projected (if (ui-client-build? build-state)
                      (project-build-digest build-state (:digest candidate))
                      build-state)]
      (build/carry-shadow-candidate projected candidate))

    build-state))
