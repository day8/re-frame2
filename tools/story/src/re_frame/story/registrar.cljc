(ns re-frame.story.registrar
  "Story's registration side-table.

  Per IMPL-SPEC §1.1 + the no-new-registries discipline (AGENTS.md
  `downstream-EPs-consume-foundation`), Story does not add new kinds to
  `re-frame.registrar`'s closed kind-set (which is locked at the
  spec/001 v1 closed list). Instead, Story owns a **side-table** keyed
  by Story kind, living inside the `tools/story/` artefact and never
  reaching a production bundle (via the §6 elision contract).

  ## Stage 2 design tension noted in bd rf2-7ho2

  spec/007 line 447 says 'the framework registrar already supports new
  kinds via the existing reg- machinery' — but the implementation's
  registrar (`re-frame.registrar/kinds`) is a **closed** set. The
  side-table here is the chosen reconciliation: Story-only registrations
  live in Story's own atom; the framework registrar stays closed. A
  bridge (`story/registrations`) preserves the spec/007 §Public-query-surfaces
  contract without crossing the framework boundary.

  ## Kinds Story registers

  - `:story` — parent of variants
  - `:variant` — concrete scenario; one per frame at runtime (Stage 3)
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
  3. Resolves `:extends` (variants only) — Stage 2 punts the cycle check
     to `re-frame.story.extends`.
  4. Stamps source-coords (per spec/009 / spec/001) — the macro layer
     captures these from `&form` and threads them via the
     `*pending-coords*` dynamic var (mirroring `re-frame.source-coords`).
  5. Writes the resolved body into the side-table.

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
  (:require [re-frame.story.late-bind :as late-bind]
            [re-frame.story.schemas   :as schemas]
            #?(:clj [re-frame.story.extends :as extends]
               :cljs [re-frame.story.extends :as extends])))

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

;; ---- mutation tick (rf2-zrswb) -------------------------------------------
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

(defn- validate-shape!
  "Validate `body` against the schema for `kind`. Throws `:rf.error/<kind>-shape`
  on a miss. Returns the body unchanged on success."
  [kind id body]
  (when-let [explain (schemas/validate kind body)]
    (let [error-kw (keyword "rf.error" (str (name kind) "-shape"))]
      (throw (ex-info (str error-kw)
                      {:rf.error/id error-kw
                       :where    'rf.story/reg-story
                       :recovery :fix-registration
                       :reason   (str "re-frame2-story: " (name kind) " body for "
                                      id " does not match " (name kind) " schema")
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
  grammar for `kind`. Per spec/007 §Canonical id grammar."
  [kind id]
  (let [ok? (case kind
              :story       schemas/story-id?
              :variant     schemas/variant-id?
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

;; ---- auto-install hook (rf2-p1ydc) ---------------------------------------
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
    (swap! kind->id->body assoc-in [:story id] body)
    (bump-tick!)
    id))

(defn reg-variant*
  "Runtime helper for `reg-variant` macro. Per IMPL-SPEC §10 Stage 2:

  1. Validate the body shape.
  2. Resolve `:extends` (merge parent body, child wins).
  3. Cross-check tag membership.
  4. Stamp source coords.
  5. Write to the side-table."
  [id body]
  (maybe-auto-install!)
  (assert-id! :variant id)
  (let [resolved (extends/resolve-extends body
                                          (fn [pid] (handler-meta :variant pid)))
        body     (-> resolved
                     merge-coords
                     (->> (validate-shape! :variant id)))
        _        (validate-tag-membership! id (:tags body))]
    (swap! kind->id->body assoc-in [:variant id] body)
    (bump-tick!)
    id))

(defn reg-workspace*
  "Runtime helper for `reg-workspace` macro."
  [id body]
  (maybe-auto-install!)
  (assert-id! :workspace id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :workspace id)))]
    (swap! kind->id->body assoc-in [:workspace id] body)
    (bump-tick!)
    id))

(defn reg-mode*
  "Runtime helper for `reg-mode` macro. Per IMPL-SPEC §2.8.3 modes ship
  in v1."
  [id body]
  (maybe-auto-install!)
  (assert-id! :mode id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :mode id)))]
    (swap! kind->id->body assoc-in [:mode id] body)
    (bump-tick!)
    id))

(defn reg-story-panel*
  "Runtime helper for `reg-story-panel` macro. Per spec/007 §Story-tool
  extension hook."
  [id body]
  (maybe-auto-install!)
  (assert-id! :story-panel id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :story-panel id)))]
    (swap! kind->id->body assoc-in [:story-panel id] body)
    (bump-tick!)
    id))

(defn reg-decorator*
  "Runtime helper for `reg-decorator` macro. Per IMPL-SPEC §3.1 the
  decorator's `:wrap` slot is the one fn-valued slot allowed in the
  Story surface — it lives at the decorator's registration site, NOT in
  a variant body. The schema enforces this."
  [id body]
  (maybe-auto-install!)
  (assert-id! :decorator id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :decorator id)))]
    (swap! kind->id->body assoc-in [:decorator id] body)
    (bump-tick!)
    id))

(defn reg-tag*
  "Runtime helper for `reg-tag` macro."
  [id body]
  (maybe-auto-install!)
  (assert-id! :tag id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :tag id)))]
    (swap! kind->id->body assoc-in [:tag id] body)
    (bump-tick!)
    id))

;; ---- canonical tag bootstrap ---------------------------------------------

(defn install-canonical-tags!
  "Register the seven canonical tags from spec/007 §Inclusion tags.

  Called at Story load (via `re-frame.story/install-canonical-vocabulary!`).
  Safe to call multiple times — re-registration is idempotent at the
  side-table level (same body replaces same body)."
  []
  (doseq [t schemas/canonical-tags]
    (reg-tag* t {:doc (str "Canonical Story inclusion tag — " (name t)
                           ". See spec/007 §Inclusion tags.")})))

;; ---- query helpers -------------------------------------------------------

(defn variants-of
  "Return the variant ids whose story is `story-id`. Cheap O(N) scan
  over the variant side-table — fine for dev-time use; if perf becomes
  a concern Stage 3 indexes.

  Per spec/007 §Canonical id grammar a story `:story.foo` has nil
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

  Replaces the O(S × V) pattern of calling `variants-of` per story (used
  e.g. by `tool-list-stories` in story-mcp, rf2-d3iso). Variant ids whose
  namespace doesn't match any registered story id are skipped — they're
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

;; ---- variants-with-tags (memoised on mutation-tick, rf2-c5nwl) ----------
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
  (->> (registrations :variant)
       (filter (fn [[_ body]]
                 (let [tset (:tags body #{})]
                   (some #(contains? tset %) qs))))
       (map first)
       set))

(defn variants-with-tags
  "Return the variant ids whose `:tags` set intersects `query-tags`. Per
  IMPL-SPEC §3.2 — the public `variants-with-tags` wraps this.

  Memoised on the registrar mutation-tick (rf2-c5nwl): repeated calls
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
  tags into facet rows in the sidebar tag-filter UI (rf2-v05qb SB9
  parity). Tags registered without `:axis` are excluded from every
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

;; ---- faceted tag-filter helpers (rf2-7ncf9) ------------------------------

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
  `state/variant-tag-match?` AND-across-axes predicate (rf2-7ncf9)."
  []
  (reduce-kv (fn [acc tid body]
               (assoc acc tid (or (:axis body) no-axis-key)))
             {}
             (registrations :tag)))
