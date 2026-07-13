(ns re-frame.ui.compiler.build-hook
  "The Shadow 3.4.10 build-lifecycle adapter for re-frame.ui compiler state.

  Shadow's retained functional build-state is the successful-build authority.
  `:compile-prepare` seeds disposable compiler-env scratch from the incoming
  accepted snapshot, captures authoritative namespace membership, and
  pre-touches exactly the CLJS sources Shadow scheduled to compile. That last
  step makes removing a source's final declaration observable even though no
  registry macro then runs, while output-present cache hits remain accepted.
  Macro contributions write only that scratch. `:compile-finish`:

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
  before a late failure; re-frame.ui does not claim filesystem rollback."
  (:require [clojure.string :as str]
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
        (build/prepare-shadow-build build-state
                                    build-id
                                    (member-nss build-state)
                                    (recompiled-member-nss build-state))))

    :compile-finish
    ;; Projection and candidate carriage are pure build-state transforms. A
    ;; later Shadow failure discards the returned state transactionally.
    (let [candidate (build/shadow-finish-candidate
                     build-state build-id (member-nss build-state))
          projected (if (ui-client-build? build-state)
                      (project-build-digest build-state (:digest candidate))
                      build-state)]
      (build/carry-shadow-candidate projected candidate))

    build-state))
