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
  bridge (`story/handlers`) preserves the spec/007 §Public-query-surfaces
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
  (:require [re-frame.story.schemas :as schemas]
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

(defn clear-all!
  "Reset the side-table. Used by test fixtures."
  []
  (reset! kind->id->body (fresh-table))
  nil)

(defn clear-kind!
  "Remove every id under kind. Used by test fixtures and hot-reload."
  [kind]
  (swap! kind->id->body assoc kind {})
  nil)

(defn unregister!
  "Remove a single id under kind."
  [kind id]
  (swap! kind->id->body update kind dissoc id)
  nil)

;; ---- query API (mirrors spec/001 public registrar query API) -------------

(defn handlers
  "Return the `{id → body}` map for `kind`, or `{}`. Mirrors
  `re-frame.registrar/handlers`."
  [kind]
  (get @kind->id->body kind {}))

(defn handler-meta
  "Return the body for `(kind, id)`, or nil."
  [kind id]
  (get-in @kind->id->body [kind id]))

(defn ids
  "Just the id set for a kind."
  [kind]
  (-> (handlers kind) keys set))

(defn registered?
  "True iff `(kind, id)` is in the side-table."
  [kind id]
  (contains? (handlers kind) id))

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
    (throw (ex-info (str "re-frame2-story: " (name kind) " body for "
                         id " does not match " (name kind) " schema")
                    {:rf.error (keyword "rf.error" (str (name kind) "-shape"))
                     :kind     kind
                     :id       id
                     :explain  explain})))
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
        (throw (ex-info (str "re-frame2-story: unregistered tag(s) on " id
                             ": " (pr-str unknown))
                        {:rf.error :rf.error/unknown-tag
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
      (throw (ex-info (str "re-frame2-story: " (name kind) " id " (pr-str id)
                           " does not match the canonical id grammar")
                      {:rf.error (keyword "rf.error" (str (name kind) "-id-shape"))
                       :kind     kind
                       :id       id})))))

;; ---- write API (the runtime helpers the macros expand to) ----------------

(defn reg-story*
  "Runtime helper for `reg-story` macro. Validates the body, stamps
  source coords, writes to the side-table. Returns `id`.

  Form-B `:variants` desugaring lives in the *macro* — by the time the
  helper is called, the body's `:variants` (if any) has been peeled off
  and the N independent `reg-variant*` calls have been emitted as
  siblings. So the helper sees only the parent-story slice."
  [id body]
  (assert-id! :story id)
  (let [body (-> body
                 (dissoc :variants)               ; Form-B sugar is removed by the macro
                 merge-coords
                 (->> (validate-shape! :story id)))
        _    (validate-tag-membership! id (:tags body))]
    (swap! kind->id->body assoc-in [:story id] body)
    id))

(defn reg-variant*
  "Runtime helper for `reg-variant` macro. Per IMPL-SPEC §10 Stage 2:

  1. Validate the body shape.
  2. Resolve `:extends` (merge parent body, child wins).
  3. Cross-check tag membership.
  4. Stamp source coords.
  5. Write to the side-table."
  [id body]
  (assert-id! :variant id)
  (let [resolved (extends/resolve-extends body
                                          (fn [pid] (handler-meta :variant pid)))
        body     (-> resolved
                     merge-coords
                     (->> (validate-shape! :variant id)))
        _        (validate-tag-membership! id (:tags body))]
    (swap! kind->id->body assoc-in [:variant id] body)
    id))

(defn reg-workspace*
  "Runtime helper for `reg-workspace` macro."
  [id body]
  (assert-id! :workspace id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :workspace id)))]
    (swap! kind->id->body assoc-in [:workspace id] body)
    id))

(defn reg-mode*
  "Runtime helper for `reg-mode` macro. Per IMPL-SPEC §2.8.3 modes ship
  in v1."
  [id body]
  (assert-id! :mode id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :mode id)))]
    (swap! kind->id->body assoc-in [:mode id] body)
    id))

(defn reg-story-panel*
  "Runtime helper for `reg-story-panel` macro. Per spec/007 §Story-tool
  extension hook."
  [id body]
  (assert-id! :story-panel id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :story-panel id)))]
    (swap! kind->id->body assoc-in [:story-panel id] body)
    id))

(defn reg-decorator*
  "Runtime helper for `reg-decorator` macro. Per IMPL-SPEC §3.1 the
  decorator's `:wrap` slot is the one fn-valued slot allowed in the
  Story surface — it lives at the decorator's registration site, NOT in
  a variant body. The schema enforces this."
  [id body]
  (assert-id! :decorator id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :decorator id)))]
    (swap! kind->id->body assoc-in [:decorator id] body)
    id))

(defn reg-tag*
  "Runtime helper for `reg-tag` macro."
  [id body]
  (assert-id! :tag id)
  (let [body (-> body
                 merge-coords
                 (->> (validate-shape! :tag id)))]
    (swap! kind->id->body assoc-in [:tag id] body)
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
  a concern Stage 3 indexes."
  [story-id]
  (let [prefix (str (subs (str story-id) 1) "/")]   ; ":story.foo" → "story.foo/"
    (->> (ids :variant)
         (filter (fn [vid] (let [s (str vid)]      ; ":story.foo/empty"
                             (and (>= (count s) (inc (count prefix)))
                                  (= (subs s 1 (inc (count prefix))) prefix)))))
         set)))

(defn variants-with-tag
  "Return the variant ids whose `:tags` set contains `tag`."
  [tag]
  (->> (handlers :variant)
       (filter (fn [[_ body]] (contains? (:tags body) tag)))
       (map first)
       set))

(defn variants-with-tags
  "Return the variant ids whose `:tags` set intersects `query-tags`. Per
  IMPL-SPEC §3.2 — the public `variants-with-tags` wraps this."
  [query-tags]
  (let [qs (set query-tags)]
    (->> (handlers :variant)
         (filter (fn [[_ body]]
                   (let [tset (:tags body #{})]
                     (some #(contains? tset %) qs))))
         (map first)
         set)))
