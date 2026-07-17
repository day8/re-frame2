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
     read from re-frame.ui's OWN per-pass provenance marker, never output-map
     identity, never Shadow's `:js` object identity, and never a wall-clock stamp
     relationship. At `:compile-prepare` re-frame.ui stamps an opaque per-pass
     token onto every retained output MAP Shadow handed it. Shadow's compile
     path (`do-compile-cljs-resource`) builds a FRESH output map for a
     (re)compiled source, dropping the marker; a later non-scheduling hook that
     merely annotates or transforms a retained output (assoc/update-in —
     INCLUDING replacing only `[:output rid :js]` with a fresh equal-byte
     string) PRESERVES the marker. So a source was (re)compiled THIS pass iff its
     finish output LACKS the current pass token; `:cached true` then distinguishes
     a disk-cache load (no macro rerun) from a fresh compile. Keying on
     re-frame.ui's own marker — not on the identity of Shadow's `:js` artifact —
     is immune to the ordinary output-transforming shape (a retained output whose
     `:js` is swapped for a fresh, even byte-identical, String by a non-scheduling
     hook) that a raw `:js`-identity test misreads as a recompile and so evicts an
     untouched accepted view; and — reading provenance rather than comparing
     `:compiled-at` milliseconds — immune to the same-/backwards-millisecond stamp
     a `>` test misreads as untouched (leaving a ghost accepted view). A later
     `:compile-prepare` hook (Shadow deep-merges build-local hooks after
     `:build-defaults` and lets them mutate build state) can force a source to
     recompile AFTER re-frame.ui observed the schedule; reading the finish-time
     marker, not the intermediate prepare schedule, closes that hook-order gap so
     a forced recompile that removed a source's final `ui/defview` evicts its
     accepted row instead of leaving a ghost view. Missing, malformed, or
     contradictory per-source provenance fails loudly before any candidate is
     published, never silently treated as untouched;
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
  raw directory. The opt-in `promote-served-generation` helper below can copy
  that output onto a separate served directory after earlier pipeline steps, but
  it is a best-effort incremental copy, not content-atomic activation: partial
  failure can expose mixed bytes and candidate-absent destinations are retained.
  It is currently wired only by the digest-probe build, not `:build-defaults`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.ui.compiler.build :as build]))

(def digest-sentinel
  "The unique fixed-width literal emitted by re-frame.ui.digest-carrier.
  Exactly the same width as a bd1- + 16-hex digest. Internal build-tool
  contract, not a library API."
  "__RF2_UI_DIGEST_XX__")

(def ^:private carrier-ns 're-frame.ui.digest-carrier)

(def ^:private compile-marker-key
  "re-frame.ui's OWN private per-pass provenance marker key, stamped onto every
  retained output map at `:compile-prepare` (see `stamp-retained-outputs`). It
  is the per-pass fact that decides compile membership at `:compile-finish` —
  Shadow's compile path drops it by replacing the whole output map, an ordinary
  non-scheduling assoc/update-in on a retained output preserves it. Opaque and
  namespaced so no Shadow or hook code collides with it; it is a key on the JVM
  output MAP only and is never emitted into any bundle."
  ::pass-marker)

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

(defn- compiled-this-pass?
  "Whether Shadow (re)compiled `final-output`'s source THIS pass, decided by
  re-frame.ui's OWN per-pass provenance marker — never Shadow's `:js` object
  identity and never a wall-clock stamp.

  At `:compile-prepare` re-frame.ui stamped `pass-token` onto every retained
  output MAP (see `stamp-retained-outputs`). Shadow's compile path
  (`do-compile-cljs-resource`, the sole producer of a freshly compiled output)
  builds a FRESH output map, so a real recompile's finish output LACKS the
  marker. A warm cache HIT keeps its stamped map. A later non-scheduling
  `:compile-prepare` hook that annotates or transforms a retained output
  (assoc/update-in — INCLUDING replacing only `[:output rid :js]` with a fresh,
  even byte-identical, String) PRESERVES the marker, because assoc keeps every
  other key. A disk-cache load also lacks the marker but is `:cached true`.

  So, checking the marker FIRST (it is authoritative over `:cached`, which is
  sticky on retained outputs):

  - marker == the current pass token  -> RETAINED (warm hit / metadata
    annotation / a non-scheduling `:js` transform); NOT compiled this pass;
  - marker absent + `:cached false`   -> a fresh compile this pass;
  - marker absent + `:cached true`    -> a disk-cache load; NOT compiled;
  - marker absent + `:cached` neither `true` nor `false`, OR a marker that is
    PRESENT but is NOT the current pass token -> missing/contradictory
    per-source provenance: fails loudly rather than being silently classified
    as untouched (rf2-ialoij's missing/ambiguous-evidence criterion).

  No branch is `>`, `>=`, `!=`, or any relationship on the `:compiled-at` wall
  clock, so a genuine EQUAL-stamp or BACKWARDS-stamp recompile (same-millisecond
  `System/currentTimeMillis`, clock skew, a non-monotonic clock) is still
  classified as a recompile, while an ordinary output transform and a warm cache
  hit are never misread as one."
  [final-output pass-token]
  (let [marker (get final-output compile-marker-key ::unmarked)]
    (cond
      (= marker pass-token) false

      (= marker ::unmarked)
      (case (:cached final-output)
        false true
        true  false
        (throw
         (ex-info
          (str "re-frame.ui compile-finish found a CLJS output with no per-pass "
               "provenance marker and no usable Shadow :cached flag; refusing to "
               "guess whether it was compiled this pass")
          {::error ::ambiguous-compile-evidence
           :reason :unmarked-output-missing-cached-flag
           :cached (:cached final-output)
           :recovery :configure-ui-build-hook-once})))

      :else
      (throw
       (ex-info
        (str "re-frame.ui compile-finish found a CLJS output carrying a STALE "
             "per-pass provenance marker (not this pass's token); refusing to "
             "guess whether it was compiled this pass")
        {::error ::ambiguous-compile-evidence
         :reason :stale-provenance-marker
         :recovery :configure-ui-build-hook-once})))))

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
  schedule-mutating `:compile-prepare` hook and after compilation ran —
  distinguished by re-frame.ui's OWN per-pass provenance marker, never output-map
  identity, never Shadow's `:js` object identity, and never a wall-clock stamp.

  `pass-token` is the opaque token re-frame.ui stamped onto every retained output
  map at `:compile-prepare` (see `stamp-retained-outputs`). See
  `compiled-this-pass?`: a source counts as recompiled iff its finish output
  LACKS that token (Shadow replaced the whole map) and is not a `:cached true`
  disk load. This is immune to a later hook annotating or transforming a retained
  output's map — INCLUDING replacing only its `:js` with a fresh equal-byte
  string — which a raw `:js`-identity test misread as a recompile and so evicted
  an untouched accepted view; to a same- or backwards-millisecond compile stamp
  (which a `>`/`>=` stamp test misreads as untouched, leaving a ghost accepted
  view); and to the build-local hook merge order re-frame.ui cannot control."
  [{:keys [build-sources sources output]} pass-token]
  (reduce
   (fn [acc resource-id]
     (let [{:keys [type ns provides]} (get sources resource-id)
           final-output (get output resource-id)]
       (if (and (= :cljs type)
                (some? final-output)
                (compiled-this-pass? final-output pass-token))
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
  so the union is idempotent, and a warm cache hit (compile stamp unchanged from
  the prepare snapshot) keeps its accepted row.

  When no re-frame.ui pass is open (a non-Shadow / plain-JVM finish with no
  scratch) this is a no-op — the prepare-time pre-touch is the sole reconciler.
  When a pass IS open the prepare-time `:pass-token` provenance MUST be present;
  its absence is unobservable provenance and fails loudly rather than silently
  reconciling against an assumed-empty compile schedule. Pure build-state
  transform (apart from that guard)."
  [build-state]
  (let [scratch (get-in build-state [:compiler-env build/scratch-key])]
    (if-not scratch
      build-state
      (do
        (when-not (contains? scratch :pass-token)
          (throw
           (ex-info
            (str "re-frame.ui compile-finish observed no per-pass compile "
                 "provenance (missing prepare-time :pass-token); refusing to "
                 "reconcile against an assumed-empty compile schedule")
            {::error ::missing-pass-provenance
             :recovery :configure-ui-build-hook-once})))
        (let [extra (actually-recompiled-member-nss build-state
                                                    (:pass-token scratch))]
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
;; Opt-in served-output copy helper (rf2-vxgfnd.237)
;;
;; Shadow's browser target flushes the candidate module bytes to its raw
;; `:output-dir` at `:flush`, BEFORE any later `:flush` step can fail; on a
;; downstream failure Shadow discards the returned build-state (reverting the
;; accepted compiler snapshot) but does NOT roll back that raw directory. So a
;; fresh page load or a first lazy-module request served straight from
;; `:output-dir` would execute/advertise the rejected candidate generation.
;;
;; With a separate dev-http `:http-root`, configuring this hook last means an
;; earlier flush failure aborts before copying starts. The copy itself is NOT
;; atomic generation activation: `publish-tree!` overwrites files one by one,
;; uses only existence/length/mtime as its freshness check, has no rollback, and
;; retains destination files absent from the candidate. A copy failure can
;; therefore leave mixed served bytes. Only the digest-probe build currently
;; installs this helper; the standard `:build-defaults` do not.
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
  "A `:flush`-stage build hook that makes an opt-in, best-effort incremental
  copy of just-flushed candidate output onto a separate served directory.

  Configure it as the LAST entry in a dev `:browser` build's `:build-hooks`,
  with the build's `:output-dir` pointing at a CANDIDATE directory and the
  dev-http `:http-root` pointing at a separate STABLE served directory:

    :output-dir \"out/<build>/candidate\"
    :devtools   {:http-root \"out/<build>/stable\" ...}
    :build-hooks [... (re-frame.ui.compiler.build-hook/promote-served-generation)]

  When configured as the terminal `:flush` hook, ordering ensures earlier
  pipeline failures abort before copying starts. The hook does not enforce that
  placement. Once started, it overwrites files one by one using
  existence/length/mtime freshness, without rollback or removal of destinations
  absent from the candidate. It is therefore not content-atomic: copy failure
  can leave mixed served bytes, and a later hook can still reject the build.

  The repository currently installs this helper only in
  `:ui-digest-probe-lazy`; standard `:build-defaults` do not install it.

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
      (let [build-state (reset-cold-ui-consumer-output build-state)
            pass-token  (str (java.util.UUID/randomUUID))
            stamped     (stamp-retained-outputs build-state pass-token)]
        ;; Stamp an opaque per-pass provenance token onto every retained `:output`
        ;; map Shadow handed us BEFORE any compilation, and remember it in scratch,
        ;; so `:compile-finish` can identify the sources Shadow actually
        ;; (re)compiled this pass: a compiled source's fresh output map LOSES the
        ;; token, while a later non-scheduling hook's assoc/update-in on a retained
        ;; output — INCLUDING replacing only `[:output rid :js]` with a fresh
        ;; equal-byte string — PRESERVES it. Robust to that output-transforming
        ;; shape (which a raw `:js`-identity test misread as a recompile) and free
        ;; of any `:compiled-at` wall-clock comparison so same-/backwards-
        ;; millisecond recompiles are still caught (rf2-v7wqk, rf2-vxgfnd.194,
        ;; rf2-vxgfnd.255, rf2-vxgfnd.282, rf2-ialoij).
        (-> (build/prepare-shadow-build stamped
                                        build-id
                                        (member-nss stamped)
                                        (recompiled-member-nss stamped))
            (assoc-in [:compiler-env build/scratch-key :pass-token]
                      pass-token))))

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
