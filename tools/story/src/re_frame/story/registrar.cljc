(ns re-frame.story.registrar
  "Story's registration side-table.

  Per `001-Authoring.md` §Registration macros + the no-new-registries discipline (AGENTS.md
  `downstream-EPs-consume-foundation`), Story does not add new kinds to
  `re-frame.registrar`'s closed kind-set (which is locked at the
  spec/001 v1 closed list). Instead, Story owns a **side-table** keyed
  by Story kind, living inside the `tools/story/` artefact and never
  reaching a production bundle (via the §6 elision contract).

  ## Closed framework registrar, Story-owned side-table

  /spec/007-Stories.md §Story-tool extension hook says 'the framework registrar already supports new
  kinds via the existing reg- machinery', while the implementation's
  registrar (`re-frame.registrar/kinds`) is a **closed** set. The
  side-table here is the reconciliation: Story-only registrations
  live in Story's own atom; the framework registrar stays closed. A
  bridge (`story/registrations`) preserves the /spec/007-Stories.md §Public-query-surfaces
  contract without crossing the framework boundary.

  ## Kinds Story registers

  - `:story` — parent of variants
  - `:variant` — concrete scenario; one per frame at runtime (Stage 3)
  - `:fragment` — reusable setup/script/world mixin composed via `:compose`
  - `:check` — reusable assertion pack composed via `:compose` / inherited
  - `:workspace` — layout artefact
  - `:mode` — saved-tuple of args
  - `:story-panel` — extension hook into the story-tool's chrome
  - `:decorator` — closure-bearing wrappers / setup actions
  - `:tag` — registered inclusion-tag vocabulary

  ## Validation flow

  Each `reg-*!` here:

  1. Validates the body against the matching `re-frame.story.schemas`
     schema.
  2. Cross-validates `:tags` against the registered tag vocabulary.
  3. Stamps source-coords (per spec/009 / spec/001) — the macro layer
     captures these from `&form` and threads them via the
     `*pending-coords*` dynamic var (mirroring `re-frame.source-coords`).
  4. Writes the RAW body into the side-table.

  `:extends` is NOT resolved here: the raw variant body is stored with
  `:extends` intact and the plan compiler (`re-frame.story.plan`) is the
  SINGLE merge authority, walking the parent chain per spec/017 §8.4
  (setup APPENDS, checks INHERIT). See `reg-variant*` below.

  Hot-reload semantics mirror `re-frame.registrar`: re-registering the
  same id replaces the slot atomically; nothing in the runtime is
  paused; `:on-replacement` hooks (Stage 3 wires this) get notified.

  ## Elision

  The whole namespace lives behind the §6 sentinel: macro expansions
  call into `reg-*!` only under `goog.DEBUG=true`; under `:advanced`
  builds the call sites disappear and this entire side-table never gets
  populated. Production code that accidentally calls a Story query
  returns empty.

  ## What this namespace does NOT do (deferred to Stage 3)

  - Per-variant frame allocation (`rf/reg-frame` for each variant)
  - Args resolution precedence
  - Decorator composition order
  - Loader four-phase lifecycle
  - Play execution
  - Snapshot identity computation"
  (:require [clojure.string           :as str]
            [malli.core               :as m]
            [re-frame.story.late-bind :as late-bind]
            [re-frame.story.schemas   :as schemas]
            [re-frame.story.tags      :as tags]))

;; ---- source-coord plumbing ------------------------------------------------
;;
;; Mirrors `re-frame.source-coords/*pending-coords*` — the macros in
;; `re-frame.story.macros` bind this around the call to `reg-*!` so the
;; stored slot carries `{:file :line :ns :column}`. Per spec/009 this
;; metadata is plain data and stays in dev bundles; the macro path is
;; what is gated by the elision flag.

(def ^:dynamic *pending-coords*
  "Per-thread source coords captured at the macro-expansion site. nil
  when a registration is driven programmatically (REPL, hot-reload tool,
  MCP write surface)."
  nil)

(defn- merge-coords
  "Merge `*pending-coords*` into `body`, under the `:source` key. User-
  supplied `:source` (e.g. an MCP write that wants to preserve the
  original site) overrides the auto-captured value.

  This mirrors `re-frame.source-coords/merge-coords` but writes to a
  named `:source` key instead of merging into the top level — the
  variant body is itself open-shape data, and `:file` / `:line` keys at
  the top level would conflict with potential user data. `:source` is a
  reserved Story-owned slot."
  [body]
  (let [coords *pending-coords*]
    (if coords
      (update body :source #(merge coords %))
      body)))

;; ---- the side-table -------------------------------------------------------

(defn- fresh-table []
  {:story       {}
   :variant     {}
   :fragment    {}
   :check       {}
   :workspace   {}
   :mode        {}
   :story-panel {}
   :decorator   {}
   :tag         {}})

(defonce
  ^{:doc "kind → id → body-map. Atomic. Per-process — like the
         framework registrar, Story's side-table is a single per-process
         atom keyed by Story kind."}
  kind->id->body
  (atom (fresh-table)))

;; ---- mutation tick -------------------------------------------------------
;;
;; Every write through the side-table bumps `mutation-tick`. Consumers that
;; do expensive registry-derived work (e.g. the shell's watch-mode hot-loop
;; in `re-frame.story.ui.shell/compute-testable-content-hashes`) can record
;; the tick they last hashed against and short-circuit when nothing has
;; changed. The tick is monotonic, per-process, atomic; cheap to read.
;;
;; Optionally we'd publish a richer mutation trace (per-kind / per-id);
;; the simple counter is sufficient for v1 — the watch-mode poll only
;; needs a `(tick-advanced? ?)` answer to decide whether to re-walk.

(defonce
  ^{:doc "Monotonic per-process mutation counter. Bumped by every write
         on `kind->id->body` (reg-*!, unregister!, clear-kind!, clear-all!).
         Public so downstream consumers can build registrar-driven caches."}
  mutation-tick
  (atom 0))

(defn current-mutation-tick
  "Return the current mutation tick. Use as a cheap dirty-bit alongside
  a memoised registry-derived value: cache the value AND the tick;
  recompute only when the tick has advanced."
  []
  @mutation-tick)

(defn- bump-tick! []
  (swap! mutation-tick inc)
  nil)

(defn clear-all!
  "Reset the side-table. Used by test fixtures."
  []
  (reset! kind->id->body (fresh-table))
  (bump-tick!)
  nil)

(defn clear-kind!
  "Remove every id under kind. Used by test fixtures and hot-reload."
  [kind]
  (swap! kind->id->body assoc kind {})
  (bump-tick!)
  nil)

(defn unregister!
  "Remove a single id under kind."
  [kind id]
  (swap! kind->id->body update kind dissoc id)
  (bump-tick!)
  nil)

;; ---- query API (mirrors spec/001 public registrar query API) -------------

(defn registrations
  "Return the `{id → body}` map for `kind`, or `{}`. Mirrors
  `re-frame.registrar/registrations`."
  [kind]
  (get @kind->id->body kind {}))

(defn handler-meta
  "Return the body for `(kind, id)`, or nil."
  [kind id]
  (get-in @kind->id->body [kind id]))

(defn ids
  "Just the id set for a kind."
  [kind]
  (-> (registrations kind) keys set))

(defn registered?
  "True iff `(kind, id)` is in the side-table."
  [kind id]
  (contains? (registrations kind) id))

(defn all-kinds-with-counts
  "{kind → count} — useful in dev tooling overlays."
  []
  (into {}
        (map (fn [[k m]] [k (count m)]))
        @kind->id->body))

;; ---- shape validation -----------------------------------------------------

;; The Story authoring-body schemas are `{:closed true}`
;; (re-frame.story.schemas), so an unknown / typo'd slot fails validation
;; with a `:malli.core/extra-key` error rather than being silently
;; swallowed. The helpers below turn that structural error into an
;; actionable `:reason` that NAMES the unknown key(s) and the nearest
;; declared slot ('did you mean :plays?'): an author who typos or carries
;; an unrecognised slot gets told exactly what to fix, at the `reg-*` call
;; site.

(defn- edit-distance
  "Levenshtein edit distance between keyword/string names. Clean,
  allocation-light DP over two rolling rows."
  [a b]
  (let [a (name a) b (name b)
        m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (loop [i 1
             prev (vec (range (inc n)))]
        (if (> i m)
          (nth prev n)
          (let [ai (nth a (dec i))
                cur (reduce
                     (fn [row j]
                       (let [cost (if (= ai (nth b (dec j))) 0 1)]
                         (conj row (min (inc (nth prev j))     ; deletion
                                        (inc (peek row))       ; insertion
                                        (+ cost (nth prev (dec j))))))) ; substitution
                     [i]
                     (range 1 (inc n)))]
            (recur (inc i) cur)))))))

(defn- nearest-key
  "The declared key closest to `unknown` by edit distance, or nil when
  the closest is too far to be a plausible typo (distance > half the
  unknown's length, min 2)."
  [unknown declared]
  (when (seq declared)
    (let [[k d] (apply min-key second
                       (map (fn [k] [k (edit-distance unknown k)]) declared))
          threshold (max 2 (quot (count (name unknown)) 2))]
      (when (<= d threshold) k))))

(defn- extra-key-errors
  "Pull the `:malli.core/extra-key` errors out of a malli `explain`.
  Each carries the offending key in `:in` and the offending `:map`
  subschema in `:schema` (from which the declared key set is read).
  Dedups by unknown key (an `:or` schema reports one per branch)."
  [explain]
  (->> (:errors explain)
       (filter #(= :malli.core/extra-key (:type %)))
       (reduce (fn [acc {:keys [in schema]}]
                 (let [k (first in)]
                   (if (contains? acc k) acc (assoc acc k schema))))
               {})))

(defn- unknown-key-reason
  "Build the actionable unknown-key clause for the error `:reason`, or
  nil when the explain carries no `:malli.core/extra-key` errors (a
  different shape failure — :reason falls back to the generic message)."
  [kind explain]
  (let [extras (extra-key-errors explain)]
    (when (seq extras)
      (let [declared (sort (m/explicit-keys (val (first extras))))
            clauses  (for [k (sort (keys extras))
                           :let [hint (nearest-key k declared)]]
                       (str k
                            (when hint (str " (did you mean " hint "?)"))))]
        (str "re-frame2-story: unknown " (name kind) " slot(s) "
             (str/join ", " clauses)
             " — the " (name kind) " authoring body is closed (rf2-mantt). "
             "Allowed keys: " (pr-str (vec declared)) ". "
             "Remove the slot, or fix the typo.")))))

(defn- validate-shape!
  "Validate `body` against the schema for `kind`. Throws `:rf.error/<kind>-shape`
  on a miss. Returns the body unchanged on success.

  When the failure is an unknown slot (the closed-schema
  `:malli.core/extra-key` error) the `:reason` names the offending key(s)
  and the nearest declared slot; otherwise it falls back to the generic
  schema-mismatch message. The full malli `:explain` rides `ex-data`
  either way."
  [kind id body]
  (when-let [explain (schemas/validate kind body)]
    (let [error-kw (keyword "rf.error" (str (name kind) "-shape"))
          unknown  (unknown-key-reason kind explain)]
      (throw (ex-info (str error-kw)
                      {:rf.error/id error-kw
                       :where    'rf.story/reg-story
                       :recovery :fix-registration
                       :reason   (or unknown
                                     (str "re-frame2-story: " (name kind) " body for "
                                          id " does not match " (name kind) " schema"))
                       :kind     kind
                       :id       id
                       :explain  explain}))))
  body)

(defn- validate-tag-membership!
  "Cross-check the variant / story `:tags` set against the registered
  tag vocabulary. Strips the `!`-prefix removal-syntax marker for the
  check (per Phase-2 §5.1 #11) — `:!dev` checks that `:dev` is
  registered.

  Throws `:rf.error/unknown-tag` on a miss."
  [id tags]
  (when (seq tags)
    (let [registered-tag-ids (ids :tag)
          unknown            (filterv
                              (fn [tag]
                                (let [base (if (and (keyword? tag)
                                                    (let [n (name tag)]
                                                      (and (pos? (count n))
                                                           (= \! (first n)))))
                                             (keyword (namespace tag)
                                                      (subs (name tag) 1))
                                             tag)]
                                  (not (contains? registered-tag-ids base))))
                              tags)]
      (when (seq unknown)
        (throw (ex-info ":rf.error/unknown-tag"
                        {:rf.error/id :rf.error/unknown-tag
                         :where    'rf.story/reg-story
                         :recovery :fix-registration
                         :reason   (str "re-frame2-story: unregistered tag(s) on " id
                                        ": " (pr-str unknown))
                         :id       id
                         :unknown  unknown}))))))

;; ---- id-shape policing ----------------------------------------------------

(defn- assert-id!
  "Throw `:rf.error/<kind>-id-shape` if `id` does not match the canonical
  grammar for `kind`. Per /spec/007-Stories.md §Canonical id grammar."
  [kind id]
  (let [ok? (case kind
              :story       schemas/story-id?
              :variant     schemas/variant-id?
              :fragment    schemas/fragment-id?
              :check       schemas/check-id?
              :workspace   schemas/workspace-id?
              :mode        schemas/mode-id?
              ;; story-panel, decorator, tag — any keyword.
              :story-panel keyword?
              :decorator   keyword?
              :tag         keyword?
              keyword?)]
    (when-not (ok? id)
      (let [error-kw (keyword "rf.error" (str (name kind) "-id-shape"))]
        (throw (ex-info (str error-kw)
                        {:rf.error/id error-kw
                         :where    'rf.story/reg-story
                         :recovery :fix-registration
                         :reason   (str "re-frame2-story: " (name kind) " id " (pr-str id)
                                        " does not match the canonical id grammar")
                         :kind     kind
                         :id       id}))))))

;; ---- auto-install hook ---------------------------------------------------
;;
;; Every `reg-*!` helper calls `maybe-auto-install!` at entry. If the
;; canonical vocabulary hasn't been installed in the current registrar
;; generation, the late-bound hook (set by `re-frame.story.canonical` at
;; ns load) runs the installer chain on demand. Once installed, the
;; hook is a single `deref` of an atom — negligible on the hot path.
;;
;; Spec: tools/story/spec/001-Authoring.md §Boot — auto-install of the
;; canonical vocabulary.

(defn- maybe-auto-install!
  "Trigger the canonical-vocabulary auto-install if it hasn't fired in
  the current registrar generation. Idempotent on every call after the
  first; cheap (one deref + one nil-check) on subsequent registrations.

  No-op when the late-bound hook is absent — e.g. JVM tests of the
  registrar in isolation that load `registrar.cljc` without
  `canonical.cljc`. The fallback keeps the registrar usable for the
  low-level test of side-table semantics; in normal use the hook is
  set at `re-frame.story` ns load."
  []
  (when-let [f (late-bind/get-fn :ensure-canonical-installed)]
    (f)))

;; ---- write API (the runtime helpers the macros expand to) ----------------
;;
;; Every `reg-*!` helper ends the same way — write the prepared body into the
;; side-table slot, bump the mutation tick, return the id (`store!`) — and the
;; seven helpers with NO per-kind preprocessing (fragment / check / workspace /
;; mode / story-panel / decorator / tag) share their ENTIRE body: auto-install,
;; assert the id shape, merge source-coords, validate the shape, store
;; (`reg-simple!`). `reg-story*` (Form-B `:variants` strip + tag check) and
;; `reg-variant*` (authored-body validation + public-vocabulary lowering + tag
;; check) carry kind-specific steps, so they expand the flow inline but still
;; finish through `store!`.

(defn- store!
  "Write `body` into the `[kind id]` side-table slot, bump the mutation tick,
  and return `id`. The shared write tail of every `reg-*!` helper."
  [kind id body]
  (swap! kind->id->body assoc-in [kind id] body)
  (bump-tick!)
  id)

(defn- reg-simple!
  "The full registration flow for a kind with NO per-kind preprocessing:
  auto-install the canonical vocabulary, police the id shape, merge source
  coords, validate the body against the kind schema, then `store!`. Returns
  `id`. Used by fragment / check / workspace / mode / story-panel / decorator /
  tag — every kind whose body is stored verbatim after the standard
  validate-and-stamp."
  [kind id body]
  (maybe-auto-install!)
  (assert-id! kind id)
  (->> (-> body merge-coords)
       (validate-shape! kind id)
       (store! kind id)))

(defn reg-story*
  "Runtime helper for `reg-story` macro. Validates the body, stamps
  source coords, writes to the side-table. Returns `id`.

  Form-B `:variants` desugaring lives in the *macro* — by the time the
  helper is called, the body's `:variants` (if any) has been peeled off
  and the N independent `reg-variant*` calls have been emitted as
  siblings. So the helper sees only the parent-story slice."
  [id body]
  (maybe-auto-install!)
  (assert-id! :story id)
  (let [body (-> body
                 (dissoc :variants)               ; Form-B sugar is removed by the macro
                 merge-coords
                 (->> (validate-shape! :story id)))
        _    (validate-tag-membership! id (:tags body))]
    (store! :story id body)))

(defn reg-variant*
  "Runtime helper for `reg-variant` macro. Per `001-Authoring.md` §Registration macros + spec/017
  §8.4 (the plan compiler is the SINGLE `:extends` merge authority):

  1. Validate the AUTHORED body shape.
  2. Lower the public vocabulary into the shipping slots.
  3. Cross-check tag membership.
  4. Stamp source coords.
  5. Write the RAW body — `:extends` INTACT, parents UNmerged — to the
     side-table.

  `:extends` is NOT resolved here. Storing the raw body lets the
  compiler's `resolve-source-chain` walk the parent chain on the default
  side-table lookup, so the plan compiler's per-field merge logic (setup
  APPENDS, checks INHERIT — spec/017 §8.4) applies to every registered
  variant. Registered variants and explicit-`:lookup` tests share ONE
  merge engine (`re-frame.story.plan/compile-body`); there is no second
  merge here to diverge."
  [id body]
  (maybe-auto-install!)
  (assert-id! :variant id)
  ;; Validate the AUTHORED body first so the public-vocabulary mutual-
  ;; exclusion (`:setup` xor `:events`, `:script` xor `:play-script` xor
  ;; `:plays`) fires on what the author actually wrote, before
  ;; `lower-public-vocabulary` folds the public spellings into the
  ;; shipping slots.
  (validate-shape! :variant id body)
  (let [;; Lower `:setup`→`:events` and `:script`→`:play-script` so the
        ;; stored body carries the shipping slots every downstream
        ;; consumer reads (per spec/017 §Public vocabulary; stripped when
        ;; the runtime reads the normalized plan).
        ;; `:extends` is preserved verbatim — the plan compiler resolves
        ;; it.
        lowered  (schemas/lower-public-vocabulary body)
        body     (-> lowered
                     merge-coords
                     (->> (validate-shape! :variant id)))
        _        (validate-tag-membership! id (:tags body))]
    (store! :variant id body)))

(defn reg-fragment*
  "Runtime helper for `reg-fragment` macro. A fragment is a
  reusable setup/script/world mixin a variant pulls in through `:compose`.

  Validates the body against the `Fragment` schema — which enforces the
  flat-fragment rule (a fragment MUST NOT carry `:compose` / `:extends`)
  and the world/behaviour-only shape (no `:checks` / `:assertions`). The
  body is stored verbatim (un-lowered): the plan compiler normalizes both
  `:setup`/`:events` and `:script`/`:play-script` spellings at compose
  time, so the fragment keeps the author's spelling."
  [id body]
  (reg-simple! :fragment id body))

(defn reg-check*
  "Runtime helper for `reg-check` macro. A check is a named,
  reusable assertion pack — the inheritable expectation form. Validates the
  body against the `Check` schema (`:assertions` required, no
  world/behaviour). Check identity (the id) is preserved by the plan
  compiler and by run results so a failed check shows its id alongside the
  underlying assertion records."
  [id body]
  (reg-simple! :check id body))

(defn reg-workspace*
  "Runtime helper for `reg-workspace` macro."
  [id body]
  (reg-simple! :workspace id body))

(defn reg-mode*
  "Runtime helper for `reg-mode` macro. Per `005-SOTA-Features.md` §`reg-mode` saved-tuple primitive modes ship
  in v1."
  [id body]
  (reg-simple! :mode id body))

(defn reg-story-panel*
  "Runtime helper for `reg-story-panel` macro. Per /spec/007-Stories.md §Story-tool
  extension hook."
  [id body]
  (reg-simple! :story-panel id body))

(defn reg-decorator*
  "Runtime helper for `reg-decorator` macro. Per `001-Authoring.md` §Registration macros the
  decorator's `:wrap` slot is the one fn-valued slot allowed in the
  Story surface — it lives at the decorator's registration site, NOT in
  a variant body. The schema enforces this."
  [id body]
  (reg-simple! :decorator id body))

(defn reg-tag*
  "Runtime helper for `reg-tag` macro."
  [id body]
  (reg-simple! :tag id body))

;; ---- canonical tag bootstrap ---------------------------------------------

(defn install-canonical-tags!
  "Register the seven canonical inclusion tags from /spec/007-Stories.md §Inclusion
  tags AND the canonical `:state/*` magnitude axis — five
  faceted tags (`:state/empty`, `:state/small`, `:state/medium`,
  `:state/large`, `:state/special`) on the `:state` axis. The `:state`
  axis communicates operator-facing fixture richness — a reviewer can
  scan a gallery by data density rather than by feature.

  Called at Story load (via `re-frame.story/install-canonical-vocabulary!`).
  Safe to call multiple times — re-registration is idempotent at the
  side-table level (same body replaces same body)."
  []
  (doseq [t schemas/canonical-tags]
    (reg-tag* t {:doc (str "Canonical Story inclusion tag — " (name t)
                           ". See /spec/007-Stories.md §Inclusion tags.")}))
  (doseq [t schemas/canonical-state-tags]
    (reg-tag* t {:axis :state
                 :doc  (str "Canonical Story state-magnitude tag — "
                            (pr-str t)
                            ". Operator-facing fixture-richness "
                            "classification; see `canonical-axes` "
                            "`:state` entry in tools/story/src/re_frame/"
                            "story/schemas.cljc.")})))

;; ---- query helpers -------------------------------------------------------

(defn variants-of
  "Return the variant ids whose story is `story-id`. Cheap O(N) scan
  over the variant side-table — fine for dev-time use; if perf becomes
  a concern Stage 3 indexes.

  Per /spec/007-Stories.md §Canonical id grammar a story `:story.foo` has nil
  namespace and name `\"story.foo\"`; its variants `:story.foo/empty`
  carry namespace `\"story.foo\"`. So variant membership is just
  `(= (namespace vid) (name story-id))` — no string surgery."
  [story-id]
  (let [story-ns (name story-id)]
    (into #{}
          (filter (fn [vid] (= (namespace vid) story-ns)))
          (ids :variant))))

(defn variants-by-story
  "Return a `{story-id #{variant-id ...}}` index built in one pass over
  the variant side-table — O(V) where V is the variant count. The
  per-story key is the `:story.<path>` keyword whose `name` matches each
  variant id's `namespace` (the same membership rule `variants-of` uses).
  Stories with zero registered variants land in the result with an empty
  set — the caller decides whether to elide them.

  Builds the index in one pass instead of the O(S × V) pattern of calling
  `variants-of` per story (used e.g. by `tool-list-stories` in story-mcp).
  Variant ids whose namespace doesn't match any registered story id are
  skipped — they're
  orphans (the registrar's reg-time validation forbids them under normal
  use, but the index stays defensive)."
  []
  (let [story-keys-by-name (into {}
                                 (map (fn [sid] [(name sid) sid]))
                                 (ids :story))
        seed               (into {} (map (fn [sid] [sid #{}])) (ids :story))]
    (reduce (fn [acc vid]
              (if-let [sid (get story-keys-by-name (namespace vid))]
                (update acc sid (fnil conj #{}) vid)
                acc))
            seed
            (ids :variant))))

;; ---- variants-with-tags (memoised on mutation-tick) --------------------------
;;
;; The hot path is the sidebar compose loop + the SOTA assertions/play
;; flows: every render computes
;; `(variants-with-tags qs)` to surface the testable / docs-tagged subset.
;; Each call is O(V × |query|) over the variant side-table. Once the
;; registrar's mutation-tick is bumped, every cached answer becomes stale;
;; until then, repeated calls with the same query collide on the same
;; underlying set.
;;
;; The cache key is `[tick, sorted-qs]` so callers that pass the same
;; query (in any order) hit the same slot. The cache is per-process and
;; invalidates on every registrar mutation; a single-slot variant
;; (last-call only) would also work, but the {tick → {qs → result}}
;; shape keeps the assertions + sidebar + docs queries from evicting
;; each other when they fire from the same render.

(defonce ^:private variants-with-tags-cache
  (atom {:tick -1 :by-query {}}))

(defn- compute-variants-with-tags [qs]
  ;; Match against each variant's EFFECTIVE tag set (the shared
  ;; `re-frame.story.tags` resolver — story/extends inheritance + `:!x`
  ;; marker resolution), NOT the raw child `:tags`. So a variant matches a
  ;; tag it inherits, and a `:!x` marker that cancels an inherited tag
  ;; excludes it (a child that removed `:dev` with `:!dev` is NOT returned
  ;; for the `#{:dev}` query, and `:!dev` is never itself a queryable tag).
  (let [variants (registrations :variant)
        lookups  {:variant variants :story (registrations :story)}]
    (->> variants
         (filter (fn [[vid _body]]
                   (let [tset (tags/effective-tags vid lookups)]
                     (some #(contains? tset %) qs))))
         (map first)
         set)))

(defn variants-with-tags
  "Return the variant ids whose EFFECTIVE tag set intersects `query-tags`.
  Per `002-Runtime.md` §Programmatic API — the public `variants-with-tags`
  wraps this. The effective set is resolved through the shared
  `re-frame.story.tags` resolver (story/extends inheritance + `:!x` removal
  markers), so an inherited tag matches and a removed one does not.

  Memoised on the registrar mutation-tick: repeated calls
  with the same query between two registrar writes return the same
  cached set in O(1)."
  [query-tags]
  (let [qs        (set query-tags)
        cache-key (sort qs)
        tick      @mutation-tick
        cache     @variants-with-tags-cache]
    (if (and (= tick (:tick cache))
             (contains? (:by-query cache) cache-key))
      (get (:by-query cache) cache-key)
      (let [result (compute-variants-with-tags qs)]
        (swap! variants-with-tags-cache
               (fn [{prev-tick :tick prev-by :by-query}]
                 (if (= prev-tick tick)
                   {:tick tick :by-query (assoc prev-by cache-key result)}
                   {:tick tick :by-query {cache-key result}})))
        result))))

(defn- query-tags-by
  "Pure data → data: return the set of registered tag ids whose body
  matches `pred`. Shared core of the `tags-by-axis` / `tags-without-
  axis` / `tags-default-excluded` queries — each is a one-line predicate
  over the same `{tag-id → tag-body}` scan."
  [pred]
  (->> (registrations :tag)
       (filter (fn [[_ body]] (pred body)))
       (map first)
       set))

(defn tags-by-axis
  "Return the set of registered tag ids whose body's `:axis` equals
  `axis-kw`. Per spec/001 §reg-tag the optional `:axis` slot groups
  tags into facet rows in the sidebar tag-filter UI (SB9 parity).
  Tags registered without `:axis` are excluded from every
  axis-keyed result."
  [axis-kw]
  (query-tags-by #(= axis-kw (:axis %))))

(defn tags-without-axis
  "Return the set of registered tag ids whose body carries no `:axis`.
  The sidebar renders these in a trailing un-grouped facet row."
  []
  (query-tags-by #(nil? (:axis %))))

(defn tags-default-excluded
  "Return the set of registered tag ids whose body's `:default-filter`
  is `:exclude`. The sidebar tag-filter pre-excludes variants carrying
  any of these at boot (e.g. `:internal` / `:experimental`)."
  []
  (query-tags-by #(= :exclude (:default-filter %))))

;; ---- faceted tag-filter helpers ------------------------------------------------

(def ^:private no-axis-key
  "Sentinel axis key for tags that have no `:axis` slot. Lives in
  this ns so the sidebar AND the filter logic name the same bucket
  without coupling either to the other."
  ::no-axis)

(defn tag->axis
  "Pure data → data: return the `:axis` keyword on the registered tag
  body for `tag-id`, or `::no-axis` if the tag is registered without
  one. Returns `::no-axis` for unregistered tags too — they're treated
  as un-grouped from the filter UI's perspective.

  This is the per-tag lookup; `tag->axis-index` builds the full map in
  one pass over the tag side-table for callers that need it across
  many tags."
  [tag-id]
  (or (:axis (handler-meta :tag tag-id))
      no-axis-key))

(defn tag->axis-index
  "Pure data → data: build the `{tag-id → axis-kw}` index across every
  registered tag in one O(T) pass. Tags without `:axis` map to
  `::no-axis`. Used by the sidebar's facet-grouped filter row + the
  `state/variant-tag-match?` AND-across-axes predicate."
  []
  (reduce-kv (fn [acc tid body]
               (assoc acc tid (or (:axis body) no-axis-key)))
             {}
             (registrations :tag)))
