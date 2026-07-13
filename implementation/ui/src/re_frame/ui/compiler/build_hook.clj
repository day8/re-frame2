(ns re-frame.ui.compiler.build-hook
  "The Shadow 3.4.10 build-lifecycle adapter for re-frame.ui compiler state.

  `:compile-prepare` opens the explicit build-id's pass and captures the
  already-resolved graph's authoritative namespace membership. Contributions
  then stage in the compiler authority. `:compile-finish` owns one transaction:

  1. derive the candidate finalized slice (commit staged sources, evict sources
     absent from authoritative membership) and its whole-build digest;
  2. purely validate and project that digest into exactly one compiled
     re-frame.ui.digest-carrier `[:output resource-id :js]` string;
  3. only after projection succeeds, publish the candidate compiler slice.

  Missing/duplicate carrier resources or sentinel literals throw before step 3,
  so last-known-good remains committed. The equal-width source replacement
  preserves source-map offsets. Shadow's REPL form-eval path runs no build hook;
  no-pass defview expansion commits immediately and its emitted dev registration
  installs the same compiler digest into the carrier.

  The hook and `:cache-blockers #{re-frame.ui}` are both load-bearing. Cache
  blocking forces side-effecting macros to re-expand on warm daemon starts; the
  hook supplies pass boundaries, deletion eviction and client publication."
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
  non-carrier byte are retained. Throws before authority commit on drift."
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
    (do (build/begin-build! build-id (member-nss build-state))
        build-state)

    :compile-finish
    ;; Projection first: malformed output can never advance compiler authority.
    (if (build/pass-open? build-id)
      (if (ui-client-build? build-state)
        (let [candidate (build/finish-candidate build-id (member-nss build-state))
              projected (project-build-digest build-state (:digest candidate))]
          (build/commit-finish-candidate! candidate)
          projected)
        ;; Builds with no client carrier still need ordinary registry pass
        ;; finalization (e.g. JVM-shaped compiler tests), but have no output
        ;; identity to publish.
        (do (build/finish-build! build-id (member-nss build-state))
            build-state))
      build-state)

    build-state))
