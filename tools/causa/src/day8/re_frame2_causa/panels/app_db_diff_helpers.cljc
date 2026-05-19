(ns day8.re-frame2-causa.panels.app-db-diff-helpers
  "Pure-data helpers for Causa's App-DB Diff panel (Phase 5, rf2-jps1o).

  ## Why a separate `.cljc` ns

  The panel view in `app_db_diff.cljs` touches DOM event handlers
  (right-click affordances, pin buttons). The *logic* — the
  structural-sharing diff, the reserved-keys partitioning, the pin-
  store transitions, the 'Show me when this changed' walker — is
  pure data → data. Splitting that logic into `.cljc` so it runs
  under the JVM unit-test target (`clojure -M:test`) is required by
  the standing rule `feedback_jvm_interop_must_work.md`.

  ## Diff algorithm — `diff-paths`

  Per spec/004-App-DB-Diff.md §Changed-paths derivation the diff is
  **structural-sharing**:

    - **Map-pointer-equality at each level.** When two sub-maps are
      `identical?` the subtree is unchanged; skip.
    - **Recurse only where pointers differ.** O(changed paths), not
      O(db size).
    - **Emit a sorted vector of `{:op :path :before :after}`** triples
      where `:op` is `:added` / `:modified` / `:removed`.

  `identical?` is the same predicate `clojure.core` uses for pointer
  equality. On the JVM it's `==` over object references; in CLJS
  it's `===`. PersistentHashMap instances produced by `assoc-in`
  share structure with their predecessors — every untouched sub-map
  is `identical?` to the predecessor's sub-map at the same path.

  ## Reserved-keys partition — `partition-reserved`

  Per spec §Reserved-keys group the runtime owns five top-level keys
  (`:rf/machines`, `:rf/route`, `:rf/system-ids`,
  `:rf/pending-navigation`, `:rf/spawned`). The diff triples for
  those keys render in a separate `[runtime]` group; the rest render
  as slice mini-panels.

  ## rf2-e9tb0 — pin-store helpers dropped

  `pin-path` / `unpin-path` / `reorder-paths` / `slice-pins-for-frame`
  / `live-pinned-slices` and their `pinned-slices-store` slot were
  removed when path-segment click-to-inspect replaced the pinned-
  watches strip (Mike 2026-05-19 Q13). The matching subs / events
  were pulled in lockstep from `app_db_diff_subs.cljs` and
  `app_db_diff_events.cljs`.

  ## 'Show me when this changed' — `epochs-touching-path`

  Walks the epoch-history, diffing each epoch's `:db-before` and
  `:db-after` and filtering to those that touched the focused path.
  Pure data → vector of hit maps. Per spec §'Show me when this
  changed'.")

;; ---- reserved keys --------------------------------------------------------

(def reserved-app-db-keys
  "Per spec/Conventions.md §Reserved app-db keys the runtime owns six
  top-level slots in `app-db`. Diff triples whose path roots in one
  of these keys render in the `[runtime]` group rather than as a
  slice mini-panel.

  - `:rf/machines` — machine snapshots (per spec/005-StateMachines.md)
  - `:rf/system-ids` — system-id reverse index
  - `:rf/route` — current route slice (per spec/012-Routing.md)
  - `:rf/pending-navigation` — pending-nav slot
  - `:rf/spawned` — spawned-actor table
  - `:rf/elision` — wire-elision declaration registry (per spec/009-Instrumentation.md §Size elision)

  Lockstep contract: this set MUST match the rows of
  spec/Conventions.md §Reserved app-db keys exactly. The drift-detector
  test `reserved-app-db-keys-matches-conventions-md` in
  `app_db_diff_helpers_cljs_test.cljc` enforces the invariant."
  #{:rf/machines
    :rf/route
    :rf/system-ids
    :rf/pending-navigation
    :rf/spawned
    :rf/elision})

(defn reserved-path?
  "True when `path`'s root key is a reserved-app-db key. Pure data →
  bool."
  [path]
  (boolean (and (sequential? path)
                (seq path)
                (contains? reserved-app-db-keys (first path)))))

;; ---- diff algorithm -------------------------------------------------------

(defn- map-like?
  "True when `x` is a map. Recursion-friendly — only walks into nested
  maps; vectors / lists / sets are leaf values for diff purposes (per
  spec §Changed-paths derivation — the diff bottoms out at the first
  non-map level, so a slice's `before` / `after` is the whole nested
  value)."
  [x]
  (map? x))

(defn diff-paths
  "Diff two app-db values. Returns a vector of triples:

      [{:op :added    :path [:k1 :k2 ...] :before nil      :after v}
       {:op :modified :path [:k1 :k2 ...] :before v-old    :after v-new}
       {:op :removed  :path [:k1 :k2 ...] :before v        :after nil}
       ...]

  Triples are sorted by path-as-pr-str so the order is stable across
  re-renders (the spec doesn't dictate an order; lexical-by-path is
  the obvious choice and gives consistent test snapshots).

  ## Structural sharing

  When `before` and `after` are both maps and have `identical?` keys
  at level N, that subtree is skipped — pure pointer-equality short-
  circuit. This is the O(changed paths) guarantee per spec §Changed-
  paths derivation.

  ## Per-key diff classification

    - `(contains? after k)` and not `(contains? before k)` → `:added`
    - not `(contains? after k)` and `(contains? before k)` → `:removed`
    - both contain k:
      - `identical?` values → no diff (structural-sharing short-circuit)
      - both maps, not `identical?` → recurse with extended path
      - otherwise (one or both non-map; not identical) → `:modified`
        leaf at this path

  Non-map sub-trees (e.g., a vector slice that changed from `[a b]`
  to `[a b c]`) are emitted as a single `:modified` triple at the
  parent path — the slice mini-panel's `before` / `after` shows the
  whole nested value side-by-side.

  Pure data → data. JVM-runnable.

  Performance note (rf2-etwtm / audit 2c): the final sort caches
  `(pr-str :path)` onto each triple under `::sort-key` before the
  comparator runs, so `pr-str` runs O(N) (once per triple) instead of
  O(N log N) (once per comparator invocation) — measurable on large
  diffs. The `::sort-key` slot is dissoc'd after sorting so callers
  see the original triple shape."
  ([before after]
   (let [triples       (diff-paths before after [])
         with-keys     (mapv (fn [t] (assoc t ::sort-key (pr-str (:path t))))
                             triples)
         sorted        (sort-by ::sort-key with-keys)]
     (mapv #(dissoc % ::sort-key) sorted)))
  ([before after path]
   (cond
     ;; Pointer-equal subtrees — no recursion (structural-sharing
     ;; short-circuit). Mirrors clojure.core's `=` short-circuit on
     ;; PersistentHashMap pointer-equality.
     (identical? before after)
     []

     ;; Both maps, not identical — walk the union of keys without
     ;; allocating two intermediate sets per recursion (rf2-etwtm /
     ;; audit 2b). Walk `(keys after)` first, then `(keys before)`
     ;; skipping any key already seen.
     (and (map-like? before) (map-like? after))
     (let [walk-key (fn [acc k seen?]
                      (let [bv (get before k ::missing)
                            av (get after k ::missing)]
                        (cond
                          (and (= bv ::missing) (not= av ::missing))
                          [(conj acc {:op :added :path (conj path k)
                                      :before nil :after av})
                           (conj seen? k)]

                          (and (not= bv ::missing) (= av ::missing))
                          [(conj acc {:op :removed :path (conj path k)
                                      :before bv :after nil})
                           (conj seen? k)]

                          (identical? bv av)
                          [acc (conj seen? k)]

                          (and (map-like? bv) (map-like? av))
                          [(into acc (diff-paths bv av (conj path k)))
                           (conj seen? k)]

                          :else
                          [(conj acc {:op :modified :path (conj path k)
                                      :before bv :after av})
                           (conj seen? k)])))
           [acc-1 seen-1] (reduce (fn [[acc seen] k]
                                    (walk-key acc k seen))
                                  [[] #{}]
                                  (keys after))]
       (first (reduce (fn [[acc seen] k]
                        (if (contains? seen k)
                          [acc seen]
                          (walk-key acc k seen)))
                      [acc-1 seen-1]
                      (keys before))))

     ;; One side is missing (top-level non-map invocation: caller asks
     ;; for diff between nil and a map, or vice versa).
     (and (nil? before) (some? after))
     [{:op :added :path path :before nil :after after}]

     (and (some? before) (nil? after))
     [{:op :removed :path path :before before :after nil}]

     ;; Both non-map, not identical — :modified leaf.
     :else
     [{:op :modified :path path :before before :after after}])))

;; ---- reserved-keys partition --------------------------------------------

(defn partition-reserved
  "Split a vector of diff triples into two groups:

      {:reserved     [triples-whose-path-roots-in-reserved-key]
       :non-reserved [the-rest]}

  Pure data → data. Used by the view to render the changed-slice
  stack + the `[runtime]` group separately per spec §Reserved-keys
  group."
  [triples]
  (let [{:keys [reserved non-reserved]}
        (group-by (fn [t] (if (reserved-path? (:path t)) :reserved :non-reserved))
                  triples)]
    {:reserved     (vec reserved)
     :non-reserved (vec non-reserved)}))

(defn reserved-summary
  "Project the current `:rf/*` reserved-key slots of `db` into a sorted
  vector of `[key value]` pairs for the panel's `[runtime]` group.
  Drops keys absent from `db` so the group is sized by what's
  actually populated.

  Pure data → data."
  [db]
  (vec
    (for [k (sort reserved-app-db-keys)
          :when (contains? db k)]
      [k (get db k)])))

;; ---- 'Show me when this changed' walker ---------------------------------

(defn path-touched?
  "True when the diff between `db-before` and `db-after` produces a
  triple at `path` (or anywhere beneath `path`). Pure data → bool.

  Uses pointer-equality for the prefix walk so unchanged subtrees
  short-circuit without recursion. When we reach the end of `path`
  we compare leaves directly — a leaf change registers as 'touched'.

  This is the per-epoch test the 'Show me when this changed' walker
  applies across epoch-history."
  [db-before db-after path]
  (loop [bv db-before
         av db-after
         path path]
    (cond
      (identical? bv av) false
      (empty? path) (not (identical? bv av))
      :else
      (recur (when (map-like? bv) (get bv (first path)))
             (when (map-like? av) (get av (first path)))
             (rest path)))))

(defn- path-exists?
  "True when `path` resolves to an existing slot in `db` (the final
  key is `contains?`-true in its parent map). Pure data → bool.

  Distinguishes 'key absent' from 'key present with nil value' — the
  diff classifier needs that distinction to label `:removed` (key
  gone) vs `:modified` (key now nil)."
  [db path]
  (cond
    (empty? path) (some? db)
    (not (map-like? db)) false
    (not (contains? db (first path))) false
    :else (recur (get db (first path)) (rest path))))

(defn op-at-path
  "Classify the change at `path` between `db-before` and `db-after`.
  Returns one of `:added` / `:removed` / `:modified` / nil (when the
  path is untouched). Pure data → keyword or nil."
  [db-before db-after path]
  (when (path-touched? db-before db-after path)
    (let [had? (path-exists? db-before path)
          has? (path-exists? db-after  path)]
      (cond
        (and (not had?) has?) :added
        (and had? (not has?)) :removed
        :else                 :modified))))

(defn- event-of-epoch
  "Extract the dispatched event-vector off an epoch-record, for the
  hit-row label. Mirrors `time-travel-helpers/dispatch-id-from-epoch`
  in shape but returns the event vector, not the id."
  [epoch-record]
  (or (:trigger-event epoch-record)
      (some (fn [ev]
              (when (and (= :event (:op-type ev))
                         (= :event/dispatched (:operation ev)))
                (get-in ev [:tags :event])))
            (:trace-events epoch-record))))

(defn epochs-touching-path
  "Walk `history` and return a vector of hit maps for epochs that
  touched `path`:

      [{:epoch-id <id> :event <vec> :op :added|:removed|:modified
        :before  <prior-value-at-path>
        :after   <new-value-at-path>}
       ...]

  Newest-first order so the list reads as a reverse chronological
  audit. Pure data → data. Per spec §'Show me when this changed'.

  Used by the `:rf.causa/show-me-when-this-changed-result` sub."
  [history path]
  (vec
    (reverse
      (keep (fn [{:keys [epoch-id db-before db-after] :as record}]
              (when-let [op (op-at-path db-before db-after path)]
                {:epoch-id epoch-id
                 :event    (event-of-epoch record)
                 :op       op
                 :before   (get-in db-before path)
                 :after    (get-in db-after  path)}))
            history))))

;; ---- redacted-paths-modified hint (rf2-bz1cl) ---------------------------
;;
;; The elision contract substitutes the `:rf/redacted` sentinel (per
;; spec/015-Data-Classification.md §The classification model) at
;; emission-time / build-time for sensitive paths. An app that installs
;; an epoch `:redact-fn` (per docs/causa/03-time-travel.md §Privacy +
;; redact-fn / Security.md §Epoch privacy posture) can substitute the
;; sentinel into `:db-before` / `:db-after` directly. When that happens
;; AND the underlying value at the redacted path actually changed
;; across the epoch, the structural diff sees `:rf/redacted` on both
;; sides and (correctly per the contract) emits no diff row — the
;; renderer never tries to override the elision contract (per
;; `diff/annotated_tree.cljc` §Sentinel handling).
;;
;; This leaves the developer with an empty diff and no signal that
;; anything happened in the redacted slot. The hint surface
;; (rf2-bz1cl) closes that gap: a separate chip on the diff panel
;; says "N redacted paths modified" so the developer knows a real
;; change happened at an opaque path even though no diff row is
;; renderable.
;;
;; ## Count semantics — what counts as "redacted-modified"
;;
;; Causa runs in-process and reads RAW epoch records (not the
;; egress-projected ones), so the only `:rf/redacted` sentinels in
;; `:db-before` / `:db-after` come from an app-supplied `:redact-fn`
;; (the `with-redacted` interceptor only redacts event-payload trace
;; surfaces, not the recorded db). Wire-elision via
;; `elide-wire-value` happens at the off-box egress boundary and never
;; touches what Causa sees.
;;
;; The framework's egress projection (and the redact-fn that
;; substitutes the sentinel) discards information about whether the
;; underlying values actually changed — by design, that's what
;; "redacted" means. Causa can't reconstruct what was lost; it can
;; only emit a strong heuristic upper bound:
;;
;;   A path P is "redacted-modified" when:
;;     1. `(= :rf/redacted (get-in db-before P))`
;;     2. `(= :rf/redacted (get-in db-after  P))`
;;     3. The path's PARENT subtree is NOT `identical?` across
;;        `db-before` / `db-after` (something in this subtree changed).
;;
;; Condition (3) is the structural-sharing tightener. Without it,
;; every redacted path in `app-db` would count toward every cascade's
;; chip, swamping the signal. With it, only redacted paths inside a
;; subtree that actually mutated count — a tight upper bound:
;;
;;   - **Sound for non-zero:** if the count is > 0, the corresponding
;;     subtree provably mutated; the redacted slot is one possible
;;     site of that mutation (along with any sibling slots that also
;;     changed).
;;   - **Unsound for direction:** the count can over-state if a
;;     sibling slot changed and the redacted slot was incidentally
;;     untouched. The chip's tooltip surfaces this approximation
;;     ("N paths could have changed — the elision contract suppressed
;;     the values").
;;
;; ## What this does NOT cover
;;
;; - Paths nominated as sensitive but with no live value (`nil` /
;;   absent slot) — those don't carry the sentinel.
;; - The egress-side `elide-wire-value` projection (Causa never sees
;;   it; that's a property of off-box wire surfaces).
;; - `:rf.size/large-elided` markers (orthogonal to privacy; tracked
;;   independently).
;; - Sub-tree pointer-equality false negatives from `assoc-in` rewrites
;;   that produce a fresh subtree whose leaves are all equal. These
;;   would mark a redacted leaf as "potentially modified" even though
;;   nothing in the subtree changed. The structural-sharing diff has
;;   the same property — it walks the changed subtree but emits no
;;   rows when leaves match.
;;
;; ## Status: fallback only (rf2-dl3gx superseded the preferred path)
;;
;; The framework now ships `:rf.epoch/redacted-modified-paths-count`
;; on the epoch record (rf2-dl3gx) — an exact integer computed inside
;; `re-frame.epoch.assembly/build-record` from raw db-before /
;; db-after BEFORE the `:redact-fn` runs (per
;; `spec/Spec-Schemas.md §:rf/epoch-record`). The Causa sub
;; `:rf.causa/selected-epoch-redacted-modified-count` reads that
;; slot when present.
;;
;; This helper survives as the **absent-slot fallback** for records
;; without the egress slot — legacy snapshots, hand-rolled test
;; fixtures, hosts without a runtime schema layer that produces no
;; sensitive-declarations registry. The heuristic upper-bound
;; properties documented above still apply on that path; callers
;; using the framework's exact count get a precise figure.

(def redacted-sentinel
  "The framework's privacy sentinel keyword. Mirrors
  `re-frame.privacy/redacted-sentinel` so this artefact doesn't take a
  hard dep on the privacy ns (Causa is bundle-isolated from
  framework-internal helpers; the sentinel keyword is a public wire-
  vocabulary constant per spec/015-Data-Classification.md)."
  :rf/redacted)

(defn- redacted-value?
  "True when `v` is the framework's `:rf/redacted` sentinel.

  Currently the sentinel is the bare keyword; the spec's composed form
  (`:rf/redacted {:bytes N}` per spec/015 §Two parallel axes) is not
  used as a leaf substitution shape today (the size marker is a
  separate `:rf.size/large-elided` map and the privacy sentinel wins
  the composition per the API.md §Composition rule). This predicate
  matches the leaf-keyword form; if the framework ever introduces a
  wrapped sensitive-leaf shape, extend here."
  [v]
  (= redacted-sentinel v))

(defn count-redacted-modified-paths
  "Walk `db-before` and `db-after` and return the count of distinct
  paths where BOTH sides carry the `:rf/redacted` sentinel AND the
  parent subtree differs in pointer-identity (i.e., something in the
  enclosing subtree changed).

  Pure data → int. JVM-runnable. Used by the App-DB Diff panel's
  `redacted-paths-modified` chip (rf2-bz1cl) to surface 'N opaque
  paths potentially changed; the elision contract suppressed the
  values'.

  ## Algorithm

  Walk both maps in parallel, starting at the root. At each map level:

    - If the parent maps are `identical?`, skip the entire subtree —
      no descendant changed. (Structural-sharing short-circuit; same
      as `diff-paths`.)
    - Otherwise walk the union of keys:
      - When the value at key k is `:rf/redacted` on BOTH sides,
        increment the count (this counts the leaf path).
      - When both values are maps and not `identical?`, recurse.
      - Otherwise skip.

  ## Skipped subtrees

  Paths under reserved `:rf/elision` (the wire-elision declaration
  registry — its own values can include the `:rf/redacted` sentinel
  as a documentation example) are not counted. The exclusion matches
  the spirit of `reserved-app-db-keys` — the runtime owns those slots
  and their contents are not user data.

  Per rf2-bz1cl."
  ([db-before db-after]
   (count-redacted-modified-paths db-before db-after []))
  ([db-before db-after path]
   (cond
     ;; Both sides redacted at this slot → count this leaf and stop
     ;; (don't recurse into a redacted scalar). MUST run BEFORE the
     ;; pointer-equality short-circuit because the `:rf/redacted`
     ;; keyword is interned — `(identical? :rf/redacted :rf/redacted)`
     ;; is `true`, which would otherwise mask every redacted leaf as
     ;; "subtree didn't change → skip".
     (and (redacted-value? db-before) (redacted-value? db-after))
     1

     ;; Subtree-pointer short-circuit: nothing in this subtree
     ;; changed, so no redacted-modified path can live below. Applies
     ;; only AFTER the leaf-redacted check above, so we never use
     ;; pointer equality to short-circuit a leaf comparison.
     (identical? db-before db-after)
     0

     ;; Both maps, not identical → walk the union of keys. Skip the
     ;; reserved `:rf/elision` subtree (the elision registry's own
     ;; declarations carry the sentinel as an example/value form per
     ;; spec/015 §Two parallel axes; counting them confuses the
     ;; signal).
     (and (map? db-before) (map? db-after))
     (reduce
       (fn [acc k]
         (if (and (empty? path) (= :rf/elision k))
           acc
           (+ acc (count-redacted-modified-paths
                    (get db-before k)
                    (get db-after  k)
                    (conj path k)))))
       0
       (distinct (concat (keys db-before) (keys db-after))))

     :else 0)))

;; ---- diff caching --------------------------------------------------------

(defn cache-key-of
  "Return the cache key for a diff between an epoch's `:db-before`
  and `:db-after`. Per spec §Performance §Diff caching the cache is
  keyed by `:epoch-id` and the same epoch never re-diffs.

  Pure-data helper so the sub layer can build a `{epoch-id triples}`
  lookup without duplicating the key shape."
  [epoch-record]
  (:epoch-id epoch-record))
