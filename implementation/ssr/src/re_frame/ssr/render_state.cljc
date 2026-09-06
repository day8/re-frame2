(ns re-frame.ssr.render-state
  "The render-state contract for a NON-LOCAL renderer — the state a settled
  server frame hands to a renderer that does not share its heap (the Node
  sidecar in `implementation/ssr-node`), and the door that renderer uses to
  seed a fresh frame of its own from it. Per Spec 011 §SSR and the
  ssr-node crossing ruling (rf2-8arzr, shared contract S3).

  ## Two partitions, the payload's envelope, a DISTINCT policy

  The projection is the frame's coherent frame-state — BOTH partitions —
  in the SAME envelope the hydration payload already carries and the
  client's `:rf/hydrate` already installs:

      {:rf/app-db     {<top-level app-db key>     <value> …}
       :rf/runtime-db {<top-level runtime-db key> <value> …}}

  What differs is the POLICY. The hydration payload answers \"what may the
  BROWSER see?\"; the render state answers \"what does the RENDER need?\",
  and the two lists differ in both directions — a server-only key the
  render reads (a feature flag, an experiment bucket) never rides the
  payload, and a payload key the render never touches need not cross to
  the sidecar. So the render-state policy is declared beside `:payload`,
  never derived from it, under its own handler opt:

      :render-state {:app-db     [:todos :session]
                     :runtime-db [:rf.runtime/routing :rf.runtime/machines]}

  A fail-closed allowlist per partition — top-level app-db keys, and
  top-level runtime-db keys (the route slice, the machine snapshots).
  Either partition's list may be absent (that partition then projects to
  `{}`); a policy naming NEITHER is no policy. Absence of the opt is a
  construction-time error — `:rf.error/ssr-missing-payload-policy`, the
  payload family's id reused with `:opt :render-state` in its ex-data —
  because a renderer that read the whole frame by default would be the
  whole-app-db-by-accident egress the payload policy was written to
  prevent, one wire over.

  Or, for a projection the allowlist vocabulary cannot express, an escape
  hatch — the projector itself:

      :render-state (fn [frame-id] -> {:rf/app-db {…} :rf/runtime-db {…}})

  The fn REPLACES the allowlist step: it is handed the live post-drain
  frame's id and returns the partition map; the framework then validates
  the envelope shape and the wire domain exactly as it does for its own
  projection, but applies no allowlist and no classification of its own.
  A projector that wants the derived-sensitivity walk composes
  `re-frame.ssr.payload-policy/project-app-db-egress` /
  `project-runtime-db` itself.

  ## Derived sensitivity rides along

  The allowlist path reuses the payload's projection machinery
  (`re-frame.ssr.payload-policy`), including the frame's classification:
  the app-db slice runs through `project-app-db-egress`, and the runtime-db
  slice through `project-runtime-db` — machine snapshot `:data` and the
  route `:current` slice redact / elide their classified paths, the
  routing slice narrows to its durable `:current`, and the elision
  declaration registry is never carried (it is refused in the allowlist at
  construction; rf2-ybn1yb). The rendered markup goes to the browser, so a
  value the app classifies `:sensitive` is a value the render should not
  be able to print in the first place — projecting it here keeps the
  sidecar unable to.

  Runtime-db keys the projector has NO vocabulary for ride verbatim: the
  operator named them, top-level, explicitly, and the alternative — a key
  silently absent from the projection — is the silently-wrong page this
  contract exists to avoid.

  ## The wire domain is the manifest's, reused

  A value rides only if `pr-str` prints it in a form the safe EDN reader
  on the OTHER host reconstructs EQUAL — `re-frame.ssr.manifest/edn-carryable?`,
  the predicate Spec 011 §Root manifest already states for the manifest
  wire. Nothing here invents a second domain: a fn, a host object, a
  record, a JVM-only number (ratio, bigdec, bigint, float, an integer past
  2^53) fails AT PROJECTION with `:rf.error/ssr-render-state-invalid`
  naming the partition and key — never a silent `nil`, never `#object[…]`
  text discovered at the far end. `#inst` / `#uuid` are outside the domain
  for the same reason they are outside the manifest's; narrow a date to a
  string or epoch millis where you know what it means.

  ## The wire form

  `serialize` produces the per-key text the Node protocol carries —
  `{:rf/app-db {\":todos\" \"[…]\"} :rf/runtime-db {\":rf.runtime/routing\" \"{…}\"}}`,
  key text -> EDN text, so the sidecar can enforce its entry-owned
  allowlists WITHOUT decoding application data. `deserialize` is its
  inverse under the bundled safe reader. The adapter (`re-frame.ssr.ring.node`,
  slice D) names the JSON fields (`state` / `runtime`); this namespace
  keeps the envelope keys so both ends of the crossing speak the
  hydration payload's vocabulary.

  ## restore! — the second install door

  `restore!` seeds a FRESH per-request frame with both partitions in ONE
  atomic frame-state write, WITHOUT replaying boot events (the JVM drained
  them and the projection IS the settled result) and WITHOUT the client's
  hydration concerns — no hydration metadata, no compatibility-check fxs,
  no machine timer re-arm, no resources reconcile (that hook orphans SSR
  owners and plans refetches: client semantics, wrong for a renderer that
  must see exactly what the JVM saw). It is the framework's answer to the
  gap `implementation/ssr-node/README.md` declined to close in a sidecar:
  the install path is `re-frame.frame/replace-frame-state!`, the same
  explicit frame-state write surface epoch restore uses.

  An allowlisted key the live frame does NOT hold projects to nothing and
  restores to nothing: a view reading it renders `nil` where the value
  should be — the honest wrong page, and the operator's allowlist mistake
  rather than a framework failure. The framework cannot tell a key the
  render needs from one it merely could read; only the allowlist says."
  (:require [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private where 'rf.ssr/render-state)

(def ^:private partition-keys
  "The two envelope keys — the hydration payload's, reused (payload.clj)."
  #{:rf/app-db :rf/runtime-db})

(def ^:private policy-keys
  "The two allowlist slots a map-shaped `:render-state` may carry."
  #{:app-db :runtime-db})

(def ^:private projector-owned-runtime-keys
  "Runtime-db keys `payload-policy/project-runtime-db` has a vocabulary
  for: each is REPLACED by its projection (or absent when the projection
  carries nothing durable for it). Every other allowlisted runtime-db key
  rides verbatim — see the namespace docstring."
  #{:rf.runtime/machines :rf.runtime/routing :rf.runtime/ssr})

;; ---- policy ---------------------------------------------------------------

(defn- allowlist-shape-problem
  "-> a reason string when `xs` is not a non-empty sequential of keywords,
  else nil. The same shape `:payload` takes (a vector is canonical; a list
  or lazy-seq is admitted; a set is not — the allowlist is an ORDERED key
  selection)."
  [slot xs]
  (cond
    (not (sequential? xs))
    (str slot " must be a vector of keyword top-level keys; got " (pr-str xs))

    (empty? xs)
    (str slot " is empty — an allowlist that names nothing is no allowlist "
         "(drop the slot to project that partition as {})")

    (not (every? keyword? xs))
    (str slot " carries non-keyword entries " (pr-str (vec (remove keyword? xs)))
         " (a string key like \"todos\" should be the keyword :todos)")))

(defn- throw-malformed-policy!
  "The payload family's malformed-allowlist id, reused: a malformed
  `:render-state` allowlist is the same class of fault one wire over, and
  `:opt :render-state` in the ex-data carries the distinction."
  [reason render-state]
  (rf.error/throw-error!
    :rf.error/ssr-malformed-payload-allowlist where
    (str "ssr-handler :render-state — " reason ".")
    {:recovery :declare-payload-policy
     :extra    {:opt         :render-state
                :got         render-state
                :bad-entries (into []
                                   (comp (filter sequential?)
                                         (mapcat identity)
                                         (remove keyword?))
                                   (vals (select-keys render-state policy-keys)))}}))

(defn validate-policy-opts!
  "Throw a structured error when the caller's `:render-state` opt is absent
  or malformed; return `opts` unchanged on success. Called at
  handler-construction time by the host adapter so a misconfigured
  deployment fails at boot, not at first request.

    - `:render-state {:app-db [<kws>] :runtime-db [<kws>]}` — either slot
      may be absent; each present slot is a non-empty sequential of
      keywords → OK (allowlists)
    - `:render-state (fn [frame-id] -> partition-map)` → OK (escape hatch)
    - a map with an unknown slot, an empty / non-keyword allowlist, or
      `:rf.runtime/elision` in the runtime-db list
      → `:rf.error/ssr-malformed-payload-allowlist` (the payload family's
      id, reused — ex-data `:opt :render-state` says which policy)
    - absent / nil / `{}` / a keyword / anything else
      → `:rf.error/ssr-missing-payload-policy` (reused the same way;
      fail-closed, and there is deliberately no whole-partition keyword —
      the fn is the escape hatch)"
  [{:keys [render-state] :as opts}]
  (cond
    (fn? render-state)
    opts

    (and (map? render-state) (seq (select-keys render-state policy-keys)))
    (do
      (when-let [unknown (seq (remove policy-keys (keys render-state)))]
        (throw-malformed-policy!
          (str "unknown slot(s) " (pr-str (vec unknown))
               "; the policy carries :app-db and/or :runtime-db")
          render-state))
      (doseq [slot policy-keys
              :when (contains? render-state slot)]
        (when-let [reason (allowlist-shape-problem slot (get render-state slot))]
          (throw-malformed-policy! reason render-state)))
      (when (some #{:rf.runtime/elision} (:runtime-db render-state))
        (throw-malformed-policy!
          (str ":runtime-db names :rf.runtime/elision — the per-frame "
               "classification registry never crosses a wire (its keys are "
               "classified paths; rf2-ybn1yb), and a renderer has no use for "
               "it: the classified values are already projected here")
          render-state))
      opts)

    :else
    ;; The payload family's missing-policy id, reused — see
    ;; `throw-malformed-policy!`.
    (rf.error/throw-error!
      :rf.error/ssr-missing-payload-policy where
      (str "ssr-handler requires an explicit render-state policy for a "
           "non-local renderer: pass :render-state {:app-db [<top-level "
           "app-db keys>] :runtime-db [<top-level runtime-db keys>]} "
           "(fail-closed allowlists, either slot optional) OR :render-state "
           "(fn [frame-id] -> {:rf/app-db {…} :rf/runtime-db {…}}) to "
           "project it yourself. There is no whole-partition spelling.")
      {:recovery :declare-payload-policy
       :extra    {:opt :render-state
                  :got render-state}})))

;; ---- the envelope, validated ----------------------------------------------

(defn- throw-malformed-partitions! [reason partitions]
  (rf.error/throw-error!
    :rf.error/ssr-render-state-invalid where
    (str "render state must be {:rf/app-db <map> :rf/runtime-db <map>}; " reason ".")
    {:recovery :return-the-two-partition-envelope
     :extra    {:invalid :envelope
                :got     partitions}}))

(defn- normalise-partitions!
  "Fail-closed shape guard over a partition map from EITHER direction —
  a projector's output or a deserialised wire. Both partition keys may be
  absent (→ `{}`); a PRESENT key must carry a map; no third key is
  admitted (the envelope is two keys, and an extra one is a value crossing
  a boundary that has no policy for it). Returns the map with both keys
  present."
  [partitions]
  (when-not (map? partitions)
    (throw-malformed-partitions!
      (str "got " (if (nil? partitions) "nil" (pr-str (type partitions))))
      partitions))
  (when-let [extra (seq (remove partition-keys (keys partitions)))]
    (throw-malformed-partitions!
      (str "unknown key(s) " (pr-str (vec extra))) partitions))
  (doseq [k partition-keys
          :when (contains? partitions k)]
    (let [v (get partitions k)]
      (when-not (map? v)
        (throw-malformed-partitions!
          (str k " is " (if (nil? v) "nil" (pr-str (type v))) ", not a map")
          partitions))))
  {:rf/app-db     (get partitions :rf/app-db {})
   :rf/runtime-db (get partitions :rf/runtime-db {})})

(defn- check-wire-domain!
  "Every top-level entry of both partitions rides the wire key-and-value:
  the key must be a keyword (that is what a top-level key IS, and what the
  sidecar's key grammar admits), and both halves must satisfy
  `manifest/edn-carryable?`. Throws `:rf.error/ssr-render-state-invalid`
  (`:invalid :unserialisable`) naming the partition, the key and the
  offending half."
  [partitions]
  (doseq [[partition slice] partitions
          [k v] slice]
    (let [bad-key? (or (not (keyword? k)) (not (rf.ssr.manifest/edn-carryable? k)))]
      (when (or bad-key? (not (rf.ssr.manifest/edn-carryable? v)))
        (rf.error/throw-error!
          :rf.error/ssr-render-state-invalid where
          (str "render state " partition " entry " (pr-str k) " has a "
               (if bad-key? "KEY" "value")
               " the EDN wire cannot carry"
               (when (and bad-key? (not (keyword? k)))
                 " (a top-level key is a keyword)")
               ". A renderer on the other side of the wire reads the projection "
               "back with the safe EDN reader, so a value rides only if it "
               "reads back EQUAL: no fn, host object, record, ratio, bigdec, "
               "bigint, float, integer past 2^53, #inst or #uuid. Narrow the "
               "value where you know what it means, or leave the key off the "
               "allowlist.")
          {:recovery :narrow-the-value-or-drop-the-key
           :extra    {:invalid   :unserialisable
                      :partition partition
                      :key       k
                      :half      (if bad-key? :key :value)}})))))

;; The SSR liveness PRECONDITION — and a DELIBERATE throw. This id
;; recover-but-emits in the OPERATION realms (dispatch / dispatch-sync /
;; subscribe), where a teardown / hot-reload destroy is indistinguishable
;; from a real use-after-destroy bug. A server render has no such race: it
;; is a synchronous call from application code whose caller can handle the
;; refusal, and half-projected markup for a dead frame is worse than a loud
;; one. Recorded on the `:rf.error/frame-destroyed` row of Spec 009
;; §Error contract (rf2-t6yr) — do not "fix" this to recover.
(defn- require-live-frame! [frame-id]
  (when-not (rf.frame/frame frame-id)
    (rf.error/throw-error!
      :rf.error/frame-destroyed where
      (str "render state needs a LIVE frame; " (pr-str frame-id)
           " is not registered (destroyed, or never made).")
      {:recovery :pass-the-live-per-request-frame
       :extra    {:frame frame-id}})))

;; ---- project (server side) ------------------------------------------------

(defn- allowlist-project
  [frame-id {app-keys :app-db rt-keys :runtime-db}]
  (let [app-db     (rf.frame/frame-app-db-value frame-id)
        runtime-db (rf.frame/frame-runtime-db-value frame-id)
        app-slice  (if (seq app-keys)
                     ;; Allowlist FIRST, then the frame-scoped egress walk over
                     ;; the survivors, exactly as the payload builder orders it.
                     (rf.ssr.payload-policy/project-app-db-egress
                       (select-keys app-db app-keys) frame-id)
                     {})
        rt-slice   (if (seq rt-keys)
                     (let [selected (select-keys runtime-db rt-keys)]
                       ;; The projector's own keys are REPLACED by their
                       ;; projection (machines hook, routing :current under
                       ;; egress, ssr metadata) — absent when it carries nothing
                       ;; durable for them; the rest ride verbatim. Merge order
                       ;; lets a late-bound extension (resources) win for a key
                       ;; it projected.
                       (merge (apply dissoc selected projector-owned-runtime-keys)
                              (rf.ssr.payload-policy/project-runtime-db selected frame-id)))
                     {})]
    {:rf/app-db app-slice :rf/runtime-db rt-slice}))

(defn project
  "Project the LIVE frame `frame-id`'s settled frame-state to the
  render-state partition map `{:rf/app-db {…} :rf/runtime-db {…}}` per the
  caller's declared `:render-state` policy (see the namespace docstring).
  Both keys are always present. Called by the non-local renderer inside
  the request frame's scope, AFTER the boot-event drain and the
  blocking-resource settle.

  Throws the policy errors of `validate-policy-opts!` (the runtime arm
  re-validates, so a handler built around this validator and one that
  skipped it fail the same way), `:rf.error/frame-destroyed` when the
  frame is not live, `:rf.error/ssr-render-state-invalid` (`:invalid :envelope`) when the
  escape-hatch projector returns something other than the envelope, and
  `:rf.error/ssr-render-state-invalid` when a top-level entry of
  either partition cannot ride the EDN wire — at projection, never as a
  silent nil."
  [frame-id {:keys [render-state] :as opts}]
  (validate-policy-opts! opts)
  (require-live-frame! frame-id)
  (let [partitions (if (fn? render-state)
                     (render-state frame-id)
                     (allowlist-project frame-id render-state))
        partitions (normalise-partitions! partitions)]
    (check-wire-domain! partitions)
    partitions))

;; ---- the wire form --------------------------------------------------------

(defn- read-edn [s]
  #?(:clj  (edn/read-string s)
     :cljs (reader/read-string s)))

(defn serialize
  "The wire form of a `project` result: per partition, a map of key text
  -> EDN text, each half `pr-str`'d — `{:rf/app-db {\":todos\" \"[…]\"}
  :rf/runtime-db {\":rf.runtime/routing\" \"{:current …}\"}}`. Per-key
  rather than one blob so the sidecar can enforce its entry-owned
  allowlists without decoding application data. Both keys always present.

  Assumes a `project` result (already domain-checked); a hand-built map
  is the caller's to check."
  [partitions]
  (into {}
        (map (fn [partition]
               [partition
                (into {}
                      (map (fn [[k v]] [(pr-str k) (pr-str v)]))
                      (get partitions partition))]))
        partition-keys))

(defn deserialize
  "The inverse of `serialize` under the bundled SAFE EDN reader
  (`clojure.edn` / `cljs.reader` — no `#=`, no eval): per partition, key
  text -> EDN text becomes key -> value. An absent partition reads as
  `{}`. Malformed text throws the reader's own error; the shape of what
  comes out is validated by `restore!`."
  [wire]
  (into {}
        (map (fn [partition]
               [partition
                (into {}
                      (map (fn [[k v]] [(read-edn k) (read-edn v)]))
                      (get wire partition))]))
        partition-keys))

;; ---- restore! (renderer side) ---------------------------------------------

(defn restore!
  "Seed the FRESH per-request frame `frame-id` with `partitions` —
  `{:rf/app-db {…} :rf/runtime-db {…}}`, the `project` result as read
  back by `deserialize` — in ONE atomic frame-state write, replaying no
  boot events and running none of the client's hydration concerns (see
  the namespace docstring for what is deliberately NOT done here). Either
  partition key may be absent (that partition installs as `{}`). Returns
  the set of partition keys that changed.

  Fail-closed: a non-map partition, or a third key, throws
  `:rf.error/ssr-render-state-invalid` (`:invalid :envelope`) and installs nothing; a frame
  that is not live throws `:rf.error/frame-destroyed`. A key the
  projection does not carry installs nothing under it — the honest
  `nil` the operator's allowlist chose."
  [frame-id partitions]
  (let [{app :rf/app-db rt :rf/runtime-db} (normalise-partitions! partitions)]
    (require-live-frame! frame-id)
    (or (rf.frame/replace-frame-state! frame-id
                                    {rf.frame/app-partition-key     app
                                     rf.frame/runtime-partition-key rt})
        ;; `replace-frame-state!` answers nil only for a frame that vanished
        ;; between the liveness check and the write.
        (require-live-frame! frame-id))))
