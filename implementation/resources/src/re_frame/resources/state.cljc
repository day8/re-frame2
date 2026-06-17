(ns re-frame.resources.state
  "Resource runtime-db paths + the durable entry / work-record shapes.
  Per Spec 016 §Cache home and write authority and §Frame work ledger.

  This namespace fixes the reserved runtime-db key paths and the canonical
  durable shapes the runtime reads/writes, plus the framework-write-
  authority registration-meta stamp every resource event handler carries.
  The runtime swaps over these paths (entry transition function, work-
  ledger join/dedupe, host side-table bookkeeping) live in the sibling
  runtime / work-ledger namespaces; the paths and shapes are pinned here
  so every sibling agrees on one home.

  Cache lives ONLY at `:rf.runtime/resources` inside the runtime-db
  partition (`:rf.db/runtime`); the work ledger lives at
  `:rf.runtime/work-ledger`. Both are reserved runtime-db keys (per
  [Conventions §Reserved runtime-db keys]) — allocated lazily, per-frame
  isolated, never an app-db location."
  (:require [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.identity :as identity]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- reserved runtime-db paths -------------------------------------------
;;
;; Inside runtime-db itself, framework code reads/writes the bare
;; `[:rf.runtime/resources …]` paths; inside a full frame-state
;; projection the resource subtree is at
;; `[:rf.db/runtime :rf.runtime/resources …]`. Per Spec 016 §Cache home.

(def resources-key
  "The reserved runtime-db key for the resource cache subtree
  (`:rf.runtime/resources`). Per Spec 016 §Cache home and write authority."
  :rf.runtime/resources)

(def work-ledger-key
  "The reserved runtime-db key for the frame work ledger subtree
  (`:rf.runtime/work-ledger`). Named neutrally — resources are its first
  writer, later slices extend it to timers / streams / route loaders /
  spawned actors / machine async work. Per Spec 016 §Frame work ledger."
  :rf.runtime/work-ledger)

(defn key-id
  "The CEDN-1 BYTE-IDENTITY map-key for a scoped resource key
  `[canonical-scope resource-id canonical-params]` — its `canonical-bytes`
  string (rf2-9e0tyq). This is the value the `:entries` map, the reverse
  indexes, and the work-ledger map are keyed on, replacing the scoped-key
  VECTOR as the map key.

  WHY (the EP-0012 `=`-collapse fix): the scoped-key vector was used directly
  as a Clojure map key, and Clojure map keys compare by `=` + hash. The SOLE
  place `=` is COARSER than the authoritative CEDN-1 byte identity is
  SEQUENTIAL vector-vs-list — `(= [1 2 3] '(1 2 3))` is TRUE while their
  `canonical-bytes` differ (`v[…]` vs `l(…)`). Keying on the canonical-bytes
  STRING makes the map-key comparison EXACTLY the CEDN-1 byte identity
  (strings compare by `=` over their content, which IS the byte identity), so
  a list-params key and a vector-params key get DISTINCT entries — without
  re-erasing the kind (the canonical scoped-key vector, kind-preserving, is
  stored alongside as `:resource/key`). The bytes string is plain serializable
  EDN, so it rides the SSR / epoch / trace wire with no custom transit handler
  (the failure mode a `deftype` key would have silently introduced).

  Total on an already-canonical scoped key (`scoped-resource-key` canonicalizes
  scope + params, and `canonical-bytes` is total on canonical EDN). Per Spec
  016 §Resource identity / Conventions §Canonical EDN identity."
  [scoped-key]
  (identity/canonical-bytes scoped-key))

(defn entries-path
  "Runtime-db-relative path to the cache entries map `{<key-id> <entry>}` —
  keyed on the CEDN-1 byte-identity `key-id` (NOT the scoped-key vector;
  rf2-9e0tyq). Per Spec 016 §Cache home."
  []
  [resources-key :entries])

(defn tag-index-path
  "Runtime-db-relative path to the reverse tag index
  `{<tag> #{<scoped-resource-key> …}}`. Recomputable-from-`:entries`
  (rebuilt on restore/hydration, never trusted from the snapshot). Per
  Spec 016 §Cache home / §Restore and replay."
  []
  [resources-key :tag-index])

(defn owner-index-path
  "Runtime-db-relative path to the reverse owner index
  `{<owner> #{<scoped-resource-key> …}}`. Recomputable-from-`:entries`.
  Per Spec 016 §Cache home / §Restore and replay."
  []
  [resources-key :owner-index])

;; ---- tag-set input normalization (Spec 016 §Invalidation) -----------------
;;
;; A resource TAG is itself a VECTOR — `[:article slug]`, `[:article-list]`,
;; `[:feed]` — a structured name for a remote FACT. A tag-SET is a set / seq of
;; those tag vectors. The two public surfaces that accept a tag-set input — the
;; mutation `:invalidates` bare shorthand (mutation-runtime) and the direct
;; `:rf.resource/invalidate-tags` event `:tags` (events) — both must lower an
;; author's bare input to the canonical `#{<tag> …}` set, and BOTH must treat a
;; LONE vector tag the same way. A naive `(set raw)` splits a lone `[:article
;; slug]` into `#{:article slug}` — a scalar tag-set that matches NOTHING (a
;; silent no-op). This one shared normalizer is the single tag-input contract.

(defn lone-tag?
  "True iff `raw` is a SINGLE tag written directly (a vector whose first
  element is NOT itself a collection — `[:article slug]`, `[:article-list]`),
  rather than a tag-SET (a set / seq whose elements are tag vectors). A tag's
  components are scalars (a keyword namespace marker + scalar ids), so a vector
  whose head is a scalar is one tag; a collection whose head is itself a
  collection is a set of tags. An empty vector is NOT a lone tag (it carries no
  marker). Per Spec 016 §Invalidation — a tag is a vector."
  [raw]
  (and (vector? raw)
       (seq raw)
       (not (coll? (first raw)))))

(defn normalize-tag-set
  "PURE: lower an author's bare tag-set input to the canonical `#{<tag> …}`
  set. Accepts a set / sequential collection of TAGS (each tag a vector) — the
  ordinary form — OR a LONE tag vector written directly (`[:article slug]`),
  which is treated as the ONE tag `#{[:article slug]}` rather than silently
  split into `#{:article slug}` (a scalar set that matches nothing). Both the
  mutation `:invalidates` bare shorthand and the direct
  `:rf.resource/invalidate-tags` `:tags` route through here, so a lone vector
  tag has ONE meaning across the cache. Per Spec 016 §Invalidation."
  [raw]
  (if (lone-tag? raw)
    #{raw}
    (set raw)))

(defn entry-path
  "Runtime-db-relative path to a single cache entry. Accepts the scoped
  resource key VECTOR `[cache-scope resource-id canonical-params]` and keys
  the entry on its CEDN-1 byte-identity `key-id` (rf2-9e0tyq) so a list- and
  a vector-params key never collapse. Per Spec 016 §Resource identity."
  [scoped-resource-key]
  [resources-key :entries (key-id scoped-resource-key)])

(defn entry-path-by-id
  "Runtime-db-relative path to a single cache entry by its already-computed
  `key-id` (the CEDN-1 byte string). Used by callers that hold a reverse-index
  member (already a `key-id`) and must not re-transform it through `entry-path`
  (rf2-9e0tyq). Per Spec 016 §Resource identity."
  [k-id]
  [resources-key :entries k-id])

;; ---- framework-write authority -------------------------------------------
;;
;; `:rf.runtime/resources` and `:rf.runtime/work-ledger` are framework-
;; owned runtime-db children, so resource writes MUST mint framework-write
;; authority — ordinary app authority is not enough. Every resource
;; `reg-event` registration site stamps this reserved registration-meta
;; key so the runtime recognises a returned `:rf.db/runtime` effect from a
;; resource handler as in-bounds (it governs only the
;; `:rf.warning/app-handler-runtime-effect` ownership diagnostic — a
;; convention, not a capability gate; Spec 002 Mike ruling #4). Mirrors
;; routing's `framework-authority-meta`. Per Spec 016 §Write authority.

(def framework-authority-meta
  "Reserved registration-meta map (`{:rf/framework-authority? true}`)
  stamped on every resource event handler so a returned runtime-db effect
  is recognised as a framework write. Per Spec 016 §Write authority."
  {:rf/framework-authority? true})

;; ---- durable shapes (documentation-grade defaults) -----------------------
;;
;; These constructors fix the canonical durable shapes the runtime fills
;; in. They allocate plain EDN — no host handles, which live OUTSIDE
;; durable frame-state in side tables keyed by `[frame-id work-id]`.

(def lifecycle-states
  "The five resource lifecycle FSM states (cache-entry status). The
  transition function over these states lives in the runtime; the closed
  set is pinned here so siblings agree on it. Per
  Spec 016 §Lifecycle is an FSM."
  #{:idle :loading :fetching :loaded :error})

(def terminal-work-statuses
  "The terminal work-ledger statuses an attempt may reach. Terminal rows
  are pruned on the linked entry's next successful transition (a small
  bounded per-resource-key tail is retained for Xray). Per Spec 016
  §Ledger row retention and identity."
  #{:completed :failed :timed-out :suppressed :cancelled})

(defn- empty-entry*
  "The base empty `:idle` durable cache entry shape for `resource-id`,
  `:resource/key` nil. `empty-entry` stamps the key. Private — callers use
  `empty-entry`."
  [resource-id]
  {:resource/id    resource-id
   :resource/key   nil
   :status         :idle
   :data           nil
   :error          nil
   :refresh-error  nil
   :loaded-at      nil
   :stale-at       nil
   :invalidated-at nil
   :attempt        0
   :generation     0
   ;; `:revision` is the per-entry WRITE identity (EP-0019 / byl7bk Open
   ;; Issue 5 ruling) — a monotone counter bumped on EVERY authoritative
   ;; durable entry write a rollback could clobber (load success, populate,
   ;; patch, invalidation-driven settle, and the later optimistic apply),
   ;; UNCONDITIONALLY (never gated on `(= old new)` of `:data`). It is DISTINCT
   ;; from `:generation`: `:generation` bumps at load START (`entry-start-load`)
   ;; — the work / stale-suppression identity — so reusing it would
   ;; false-conflict on every in-flight refetch, before any authoritative write
   ;; lands. `:revision` moves only when an authoritative write actually
   ;; SETTLES the entry. It is the substrate the EP-0019 optimistic-rollback
   ;; settle protocol compares against (`revision-conflict?`) to decide whether
   ;; a recorded inverse is still a truthful "before" or has been overtaken.
   ;; Base value 0. Per Spec 016 §Status semantics / EP-0019 §Decision 2.
   :revision       0
   :request-id     nil
   :current-work   nil
   :tags           #{}
   :active-owners  #{}
   ;; `:previous-key` is the prior scoped key whose data is PROJECTED while
   ;; this (new) key first-loads under `:keep-previous?` (Spec 016
   ;; §Paginated and previous data). It is a PROJECTION POINTER only — the
   ;; previous key's data is never inserted into THIS entry's `:data` and
   ;; never provides THIS key's `:tags`; the sub layer reads it to project
   ;; `:previous?` / `:previous-key` / `:previous-data`. Cleared once this
   ;; key becomes `:loaded` (it then has its own data). nil when this entry
   ;; does not keep previous data.
   :previous-key   nil})

(defn empty-entry
  "Construct an empty `:idle` durable cache entry for `resource-id`. The
  durable entry stores FACTS, not derived booleans (`:stale?` /
  `:loading?` / `:has-data?` are public derived sub values, computed in
  the subs layer, never stored). Per Spec 016 §Status semantics.

  The runtime populates / transitions this shape; this constructor pins
  the canonical key set so an entry written by one sibling reads correctly
  in another.

  The 2-arity stamps the entry's own `:resource/key` — the scoped-key VECTOR
  `[canonical-scope resource-id canonical-params]` (rf2-9e0tyq). Since the
  `:entries` map is now keyed on the CEDN-1 byte `key-id` (a string), the
  kind-preserving scoped-key vector is carried INSIDE the entry so every
  consumer that needs the `[scope rid params]` shape (the prior-sibling scan,
  the scope-mismatch heuristic, the clear-scope filter, the Xray live-node
  view, the SSR projection) reads it from the entry rather than the map key.
  The 1-arity (no key in scope — e.g. the SSR timeout settle) leaves
  `:resource/key` nil; callers that have the key use the 2-arity or stamp it."
  ([resource-id] (empty-entry* resource-id))
  ([resource-id scoped-key]
   (cond-> (empty-entry* resource-id)
     scoped-key (assoc :resource/key scoped-key))))

;; ---- canonicalization (Spec 016 §Canonicalization rule) -------------------
;;
;; Resource params + scopes are EP-0012 canonical-EDN identities: the SAME
;; CEDN-1 rule the work ledger, route params, and epoch/replay records use,
;; via the shared `re-frame.identity` algebra (Conventions §Canonical EDN
;; identity). There is no resource-local identity dialect — a thin wrapper
;; preserves the public resource error categories but delegates the actual
;; canonicalization / domain validation to `identity/canonical` (rf2-wgutc2,
;; EP-0012 correctness review item 1).
;;
;; This closes three divergences the prior resource-local canonicalizer
;; carried against CEDN-1:
;;   - it accepted broad `number?` values (floats, ratios, decimals,
;;     out-of-safe-range integers) — CEDN-1 admits only portable integers in
;;     the ECMAScript safe range, and rejects the rest fail-closed;
;;   - it collapsed lists to vectors (`(sequential? x) (mapv …)`), erasing
;;     the list-vs-vector EDN distinction CEDN-1 preserves (Conventions
;;     §Sequences and sets: "Vectors and lists … are distinct EDN facts");
;;   - it sorted map keys under a bespoke `total-edn-compare`, a second
;;     ordering definition the shared CEDN-1 byte order subsumes.
;;
;; `identity/canonical` also fails closed on DUPLICATE canonical map keys
;; (two distinct host keys whose CEDN-1 bytes collide), so a colliding cache
;; key can never silently collapse two identity-distinct param maps.

;; ---- explicit-nil vs omitted params (Spec 016 §Resource identity —
;; ---- "nil vs missing MUST be schema-defined, not accidental";
;; ---- EP-0012 §canonical-forms) -------------------------------------------
;;
;; A payload `:params` slot is one of THREE distinct things, and the cache-key
;; boundary must not silently fold them: a present non-nil value, a PRESENT
;; explicit `nil` (`{:params nil}`), or an ABSENT key (`{}`). Spec 016 + EP-0012
;; say present-nil and absent are DISTINCT unless explicitly elided — the
;; `:params-schema` (not the framework) decides whether nil / absence conforms.
;;
;; The defaulting policy (omitted `:params` → `{}`) is therefore a PAYLOAD-
;; BOUNDARY default, applied ONLY when the key is genuinely absent — never a
;; blanket `(or params {})` that also collapses an explicit nil before Malli /
;; canonicalization can decide. A handler that has destructured `:params` out
;; of its payload has lost the absent-vs-nil distinction, so it threads the
;; presence explicitly: a present slot (incl. explicit nil) passes its value,
;; an absent slot passes `missing-params`.

(def missing-params
  "The sentinel a payload boundary threads for an ABSENT `:params` slot
  (distinct from a PRESENT explicit `nil`). `default-omitted-params` lowers
  it to the documented omitted-params default (`{}`); a present value —
  INCLUDING explicit `nil` — is passed through to schema validation /
  canonicalization unchanged. Per Spec 016 §Resource identity (nil vs missing
  is schema-defined) / EP-0012 §canonical-forms."
  ::missing-params)

(defn params-present?
  "Resolve a payload's `:params` presence WITHOUT collapsing explicit nil:
  returns `missing-params` when `payload` lacks a `:params` key, else the
  present value (which may be `nil`). The single helper a handler uses to
  thread params presence to `validate+canonicalize-params` so an absent slot
  and a `{:params nil}` slot stay distinct at the validation boundary."
  [payload]
  (if (contains? payload :params)
    (:params payload)
    missing-params))

(defn default-omitted-params
  "Apply the documented omitted-`:params` API default: an ABSENT slot
  (`missing-params`) becomes `{}`; every present value — INCLUDING explicit
  `nil` — passes through unchanged so the `:params-schema` (not a blanket
  `(or params {})`) decides whether nil conforms. The single home for the
  defaulting policy both registrars share. Per Spec 016 §Resource identity /
  EP-0012 §canonical-forms."
  [params]
  (if (= params missing-params) {} params))

(defn serializable-edn?
  "True iff `x` is a portable CEDN-1 EDN identity value the cache key may
  carry — encodable by the shared `re-frame.identity` algebra without
  failing closed. A keyword / symbol / string / boolean / nil, a portable
  integer in the ECMAScript safe range, a UUID, an instant, or a collection
  (map / vector / list / set) recursively built from such. Host / opaque
  values (functions, dates beyond instants, promises, DOM nodes,
  AbortControllers, raw JS objects, atoms) AND non-portable numbers (floats,
  ratios, decimals, NaN / infinities, integers outside the safe range) are
  rejected — exactly the CEDN-1 domain (Conventions §Canonical EDN
  identity). Per Spec 016 §Resource identity (\"Host values … are rejected\")."
  [x]
  (try
    (identity/canonical-bytes x)
    true
    (catch #?(:clj Throwable :cljs :default) _
      false)))

(defn canonicalize
  "Pure canonicalization of an EDN value for use in a cache key — delegates
  to the shared CEDN-1 `re-frame.identity/canonical` (Spec 016
  §Canonicalization rule / Conventions §Canonical EDN identity). Map entries
  and set elements are reordered into CEDN-1 canonical order so two spellings
  (key/element order) collapse to one identity and `=`; nested values recurse;
  vectors and LISTS preserve their kind and order (distinct EDN facts, not
  collapsed). Two map spellings differing only in insertion order return an
  `=` canonical value (and therefore the identical scoped resource key).

  Fails closed with `:rf.error/non-edn-identity` for any out-of-domain value
  (a host handle, a float / ratio / out-of-safe-range integer) and for a map
  carrying DUPLICATE canonical keys. Callers route the value through
  `reject-non-edn!` first to surface the public resource error category."
  [x]
  (identity/canonical x))

(defn- throw-non-edn!
  "Throw the public `:rf.error/resource-non-edn-params` cache-key-boundary
  error for `value` (a params or scope map that is not a portable CEDN-1
  identity). The single home for the error shape, shared by `reject-non-edn!`
  (validate-only) and `canonicalize-or-rethrow` (validate+canonicalize in one
  walk) so the two surfaces can never drift. Never returns normally."
  [value where kind resource-id]
  (error/throw-error!
    :rf.error/resource-non-edn-params
    where
    (str "resource " resource-id " " (name kind)
         " is not a portable CEDN-1 EDN identity — "
         "host / opaque values (functions, promises, "
         "dates, DOM nodes, AbortControllers, JS "
         "objects) and non-portable numbers (floats, "
         "ratios, decimals, NaN/infinities, integers "
         "outside the safe range) are rejected at the "
         "cache-key boundary. Put every value that "
         "affects remote identity in params as plain "
         "portable EDN. Per Spec 016 §Resource "
         "identity / Conventions §Canonical EDN identity.")
    {:recovery :fix-params
     :extra    {:resource-id resource-id
                :kind        kind
                :value       (pr-str value)}}))

(defn reject-non-edn!
  "Throw `:rf.error/resource-non-edn-params` when `value` (a params or
  scope map) is not a portable CEDN-1 identity — a host / opaque value (fn,
  promise, date, DOM node, AbortController, JS object) OR a non-portable
  number (float, ratio, decimal, NaN / infinity, out-of-safe-range integer)
  reached the cache-key boundary. Per Spec 016 §Resource identity (host
  values are rejected) / §Canonicalization rule / Conventions §Canonical EDN
  identity. `where` / `kind` (`:params` | `:scope`) name the offending
  boundary. Returns `value` unchanged when it conforms.

  Preserves the public resource error category (`:rf.error/resource-non-edn-
  params`) while delegating the domain decision to the shared CEDN-1 rule
  (`serializable-edn?` → `identity/canonical-bytes`).

  Validate-ONLY: a caller that then needs the canonical value should call
  `canonicalize-or-rethrow` instead, which validates AND canonicalizes in a
  single CEDN-1 walk rather than rejecting (one walk) then canonicalizing
  (a second walk) — `reject-non-edn!` is for the boundaries that validate but
  carry the RAW value forward (e.g. a mutation arm that re-keys later)."
  [value where kind resource-id]
  (when-not (serializable-edn? value)
    (throw-non-edn! value where kind resource-id))
  value)

(defn canonicalize-or-rethrow
  "Validate + canonicalize `value` in ONE CEDN-1 walk (rf2-rplgkw): return its
  canonical EDN identity (`canonicalize`), re-throwing the public
  `:rf.error/resource-non-edn-params` cache-key-boundary category when the
  shared CEDN-1 rule fails it closed (`:rf.error/non-edn-identity`).

  This collapses the historical `(reject-non-edn! …)` + `(canonicalize …)`
  pair — which walked the value TWICE (once to validate via `serializable-
  edn?` → `canonical-bytes`, once to canonicalize via `canonical`) — into a
  single `canonical` walk, since `canonical` fails closed on EXACTLY the same
  CEDN-1 domain `reject-non-edn!` rejects (state.cljc). Behaviour-preserving:
  the public error category, the `where` / `kind` / `:resource-id` slots, and
  the canonical result are identical to the two-step form. `where` / `kind`
  (`:params` | `:scope`) name the offending boundary."
  [value where kind resource-id]
  (try
    (canonicalize value)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      (if (= :rf.error/non-edn-identity (:rf.error/id (ex-data e)))
        (throw-non-edn! value where kind resource-id)
        (throw e)))))

;; ---- concrete-scope validation (Spec 016 §Scope resolution) ---------------
;;
;; The SINGLE shared validation path for a CONCRETE scope value — the value
;; a resolved scope actually carries into the cache key (a payload `:scope`,
;; a route-resolver result, a fn-of-nothing result, a pure-data policy, or a
;; mutation invalidation default). Distinct from the registration-time scope
;; POLICY gate (`registry/valid-scope-policy?`), which validates the
;; declared policy slot. Every scope-bearing operation — event resolution,
;; sub resolution, route planning, mutation invalidation default — routes
;; its concrete scope through `canonicalize-scope` so the same three
;; guarantees hold everywhere (rf2-lzv9xc):
;;
;;   1. host / opaque scope values are rejected (`reject-non-edn!`);
;;   2. a BARE unknown `:rf.scope/*` keyword (a reserved-namespace typo such
;;      as `:rf.scope/glabal`) is rejected fail-closed (rf2-pd7akw) — it can
;;      NEVER become a silent wrong cache scope;
;;   3. a reserved bare-keyword scope wrapped in a vector (the canonical
;;      `:rf.scope/global` supplied as the singleton `[:rf.scope/global]`) is
;;      rejected fail-closed (rf2-bwwk6l) — the global scope IS the bare
;;      keyword, and a wrapped spelling that silently collapsed to it was a
;;      back-compat alias for historical prose, not a second spelling the
;;      contract blesses.

(def reserved-scope-ns
  "The framework-reserved scope namespace (`:rf.scope/*`, per Conventions
  §Reserved namespaces / the `:rf.<spec-area>/*` scheme). A *bare keyword*
  in this namespace is a CLOSED reserved enum (`#{:rf.scope/global
  :rf.scope/from-caller}`); any other `:rf.scope/*` bare keyword is a typo,
  NOT a literal scope. Note a scope VALUE like `[:rf.scope/session {…}]` is a
  vector tuple, not a bare keyword — the reserved namespace governs only the
  bare-keyword slot."
  "rf.scope")

(def reserved-concrete-scopes
  "The closed set of bare `:rf.scope/*` keywords that are VALID as a concrete
  resolved scope. Only `:rf.scope/global` is a concrete cache scope —
  `:rf.scope/from-caller` is a registration POLICY (it never resolves to a
  concrete value; the use site supplies one) and so is NOT a valid concrete
  scope. Any other `:rf.scope/*` bare keyword is a typo. Per Spec 016 §Scope
  resolution."
  #{:rf.scope/global})

(defn reserved-scope-typo?
  "True when `scope` is a BARE keyword in the framework-reserved
  `:rf.scope/*` namespace that is NOT a valid concrete scope (rf2-pd7akw) —
  i.e. a misspelled reserved scope like `:rf.scope/glabal`, or the
  policy-only `:rf.scope/from-caller` reaching a concrete boundary. A
  non-keyword scope (a `[:rf.scope/session …]` tuple, a map, a string) is
  NOT in the bare-keyword reserved slot and is never a typo here."
  [scope]
  (and (keyword? scope)
       (= reserved-scope-ns (namespace scope))
       (not (contains? reserved-concrete-scopes scope))))

(defn reject-wrapped-reserved-scope!
  "Throw `:rf.error/resource-invalid-scope` when `scope` is a VECTOR whose
  head is a reserved bare-keyword concrete scope (`:rf.scope/global`) —
  i.e. the singleton `[:rf.scope/global]` spelling (rf2-bwwk6l). The global
  scope IS the bare keyword `:rf.scope/global`; wrapping it in a vector was a
  back-compat alias the implementation silently collapsed, kept only for
  payloads copied from historical prose. Pre-alpha there is no alias: the
  wrapped form fails closed and the diagnostic names the canonical bare
  spelling. A genuine scope TUPLE like `[:rf.scope/session {…}]` carries a
  payload after the namespaced tag and is untouched — only a reserved
  bare-keyword head with NO concrete payload (the historical alias shape) is
  rejected. `where` / `resource-id` name the offending boundary. Returns
  `scope` unchanged when it conforms."
  [scope where resource-id]
  (when (and (vector? scope)
             (contains? reserved-concrete-scopes (first scope)))
    (error/throw-error!
      :rf.error/resource-invalid-scope
      where
      (str "resource " resource-id " was reached with a "
           "scope " (pr-str scope) " — a reserved "
           ":rf.scope/* concrete scope wrapped in a "
           "vector. The global scope IS the bare keyword "
           ":rf.scope/global; supply it bare, not as the "
           "singleton [:rf.scope/global]. Per Spec 016 "
           "§Scope resolution.")
      {:recovery :fix-scope
       :extra    {:resource-id resource-id
                  :scope       scope}}))
  scope)

(defn reject-reserved-scope-typo!
  "Throw `:rf.error/resource-invalid-scope` when `scope` is a reserved-
  namespace typo (`reserved-scope-typo?`) reaching a CONCRETE scope boundary
  (rf2-pd7akw). A bare `:rf.scope/*` keyword outside the concrete enum is a
  framework-namespace typo, never a literal app scope — accepting it would
  resolve to a silent WRONG cache scope (a tenant / user / permission leak),
  exactly the failure the fail-closed scope contract exists to prevent.
  `where` / `resource-id` name the offending boundary. Returns `scope`
  unchanged when it conforms."
  [scope where resource-id]
  (when (reserved-scope-typo? scope)
    (error/throw-error!
      :rf.error/resource-invalid-scope
      where
      (str "resource " resource-id " was reached with a "
           "scope " (pr-str scope) " in the framework-"
           "reserved :rf.scope/* namespace that is not a "
           "valid concrete scope. The only concrete "
           "reserved scope is :rf.scope/global; "
           ":rf.scope/from-caller is a registration "
           "policy (the use site supplies the concrete "
           "scope), and any other :rf.scope/* keyword is "
           "a typo. A framework-namespace typo MUST fail "
           "closed rather than become a silent wrong "
           "cache scope. Per Spec 016 §Scope resolution.")
      {:recovery :fix-scope
       :extra    {:resource-id resource-id
                  :scope       scope}}))
  scope)

(defn canonicalize-scope
  "The SINGLE shared concrete-scope validation + canonicalization path
  (rf2-lzv9xc). Given a CONCRETE resolved scope value, in order:

    1. reject a reserved-namespace typo fail-closed
       (`reject-reserved-scope-typo!`, rf2-pd7akw);
    2. reject a reserved bare-keyword scope wrapped in a vector — the
       singleton `[:rf.scope/global]` alias (`reject-wrapped-reserved-scope!`,
       rf2-bwwk6l);
    3. reject a host / opaque value (`reject-non-edn!`);
    4. canonicalize the EDN (`canonicalize`).

  Every scope-bearing operation routes its concrete scope through this fn so
  the typo / wrapped-global / host guarantees hold uniformly across event
  resolution, sub resolution, route planning, and mutation invalidation
  defaults. `where` / `resource-id` name the boundary for the structured
  errors. Returns the canonical scope."
  [scope where resource-id]
  (reject-reserved-scope-typo! scope where resource-id)
  (reject-wrapped-reserved-scope! scope where resource-id)
  ;; rf2-rplgkw: validate + canonicalize the concrete scope in ONE CEDN-1
  ;; walk rather than rejecting (one walk) then canonicalizing (a second).
  ;; `canonical` fails closed on exactly the host / non-portable-number
  ;; domain `reject-non-edn!` rejects, so the public error category holds.
  (canonicalize-or-rethrow scope where :scope resource-id))

;; ---- scoped resource key (Spec 016 §Resource identity) --------------------

(defn scoped-resource-key*
  "TRUSTED scoped-key constructor (rf2-rplgkw): assemble the scoped resource
  key vector `[scope resource-id params]` from scope + params that are
  ALREADY canonical — it does NOT re-canonicalize. Use this on the resolution
  hot paths (sub / event / route) where the scope arrived through
  `canonicalize-scope` and the params through `validate+canonicalize-params`,
  both of which already return the canonical value; canonicalizing again here
  re-proves an established invariant (`canonical` is idempotent) for pure
  overhead, and a resource sub re-runs this on every frame-state change before
  output memoization. Per Spec 016 §Canonicalization rule: canonicalization
  happens ONCE at the scope/params resolution boundary.

  CONTRACT: callers MUST pass already-canonical scope + params. Direct
  internal / test callers handed RAW values should use the defensive
  `scoped-resource-key` (below), which canonicalizes."
  [scope resource-id params]
  [scope resource-id params])

(defn scoped-resource-key
  "Build the canonical scoped resource key
  `[canonical-scope resource-id canonical-params]` — the cache key, the
  request-correlation token payload, and the unit Xray / SSR enumerate.

  DEFENSIVE: canonicalizes both scope and params under the SAME rule
  (`canonicalize`) before assembling the key, so key order in either map never
  affects identity, and the scope is part of the key (the same params in
  different scopes can't supersede each other). Per Spec 016 §Resource
  identity. Use this for direct internal / test callers handed RAW (not yet
  canonical) scope / params, and for the mutation map-form-target boundary
  (which validates serializability separately, then canonicalizes here). The
  resolution hot paths (sub / event / route) instead use the trusted
  `scoped-resource-key*` — their scope / params are ALREADY canonical
  (`canonicalize-scope` + `validate+canonicalize-params`), so re-canonicalizing
  here is pure overhead on a per-reaction path (rf2-rplgkw).

  The returned vector is the kind-PRESERVING canonical identity (a list value
  stays a list, distinct from a vector — rf2-wgutc2). It is NO LONGER used
  directly as a Clojure map key: the `:entries` map, the reverse indexes, and
  the work-ledger map are keyed on its CEDN-1 byte `key-id` (`state/key-id`,
  rf2-9e0tyq) so the map-key comparison is EXACTLY the CEDN-1 byte identity
  and a list- and a vector-params key get DISTINCT entries (Clojure `=` would
  otherwise collapse `[1 2 3]` and `'(1 2 3)`). The vector itself remains the
  kind-preserving value carried as the entry's `:resource/key`, embedded in
  the work-id, on the SSR wire, and in trace payloads — so the kind distinction
  rf2-wgutc2 introduced is preserved end-to-end, and the `=`-collapse is closed
  at the map-keying layer where it actually occurred."
  [scope resource-id params]
  (scoped-resource-key* (canonicalize scope) resource-id (canonicalize params)))

(defn prior-loaded-sibling-key
  "Find the prior loaded SIBLING key to project under `:keep-previous?`
  (Spec 016 §Paginated and previous data): among the cache `entries`, the
  key with the SAME `[scope resource-id]` as `new-key` but DIFFERENT
  params, that currently has usable `:data`, picking the most recently
  loaded (`:loaded-at`). Returns the sibling SCOPED KEY (the vector — the
  `:previous-key` projection pointer is the kind-preserving vector, not the
  byte `key-id`), or nil when there is no sibling to project (the new key
  first-loads with no placeholder). A pure selection — the projection pointer
  it returns never inserts data into the new entry.

  rf2-9e0tyq: `entries` is now keyed on the CEDN-1 byte `key-id`, so the
  scope/params comparison reads each candidate entry's stored `:resource/key`
  vector — NOT the map key (which is the opaque bytes string)."
  [entries new-key]
  (let [[scope rid params] new-key]
    (->> entries
         (keep (fn [[_k-id entry]]
                 (let [[s r p] (:resource/key entry)]
                   (when (and (= s scope) (= r rid) (not= p params)
                              (some? (:data entry)))
                     [(:resource/key entry) (:loaded-at entry)]))))
         (sort-by (fn [[_ loaded-at]] (or loaded-at 0)) >)
         ffirst)))

;; ---- compact lifecycle FSM (Spec 016 §Lifecycle is an FSM) ----------------
;;
;; A PURE transition function over the cache entry, NOT a spawned machine
;; per entry (Spec 016 §Lifecycle is an FSM: spawning a full machine per
;; ordinary resource entry is prohibited in v1). The transition function
;; over the five states answers \"given the current status and an event,
;; what is the next status?\" — it describes CACHE-ENTRY status, distinct
;; from the work-ledger attempt lifecycle (rf2-afpdkn).
;;
;;   :idle    + :start-load (no data)        -> :loading
;;   :loading + :success                     -> :loaded
;;   :loading + :failure                     -> :error
;;   :loaded  + :start-refresh               -> :fetching
;;   :fetching+ :success                     -> :loaded
;;   :fetching+ :failure                     -> :loaded   (:refresh-error; data kept)
;;   :error   + :start-load                  -> :loading
;;   <any>    + :start-load (has data)       -> :fetching (refresh, not first load)

(defn next-status
  "Pure status transition (Spec 016 §Lifecycle is an FSM). Given the
  current `status`, a transition `signal`
  (`:start-load` / `:success` / `:failure`), and whether the entry
  currently `has-data?`, return the next status.

  - `:start-load` with NO usable data -> `:loading` (first load); with
    usable data -> `:fetching` (refresh / stale-while-revalidate);
  - `:success` -> `:loaded`;
  - `:failure` from `:loading` (or `:idle`/`:error` first load) -> `:error`
    (no usable data because the first load failed);
  - `:failure` from `:fetching` -> `:loaded` (background-refresh failure:
    return to `:loaded`, keep prior `:data`, record `:refresh-error`).

  This is the SINGLE home for the cache-entry status semantics; the event
  handlers and the reply handlers both transition through it so the five
  states never drift between writers."
  [status signal has-data?]
  (case signal
    :start-load (if has-data? :fetching :loading)
    :success    :loaded
    :failure    (if (= :fetching status) :loaded :error)
    ;; unknown signal — no transition (defensive; callers pass the closed set)
    status))

;; `infinite-entry?` (the `:infinite?` feed predicate) is defined below in the
;; infinite-feed refinement section, but `has-data?` reads it — forward-declare
;; so the canonical predicate stays in one home (the infinite section) while the
;; shared status derivation can consult it (EP-0021 R1).
(declare infinite-entry?)

(defn has-data?
  "True iff the entry currently has usable last-known-good `:data`. The
  fact `:loading?` / `:fetching?` / `:has-data?` derive from. Spec 016
  §Status semantics — durable entries store facts, derived booleans are
  computed (here + in subs), never stored.

  EP-0021 R1: an INFINITE feed's `:data` is the ordered PAGE VECTOR, seeded
  EMPTY (`[]`) before page 0 loads. An empty page vector is NO usable data —
  the feed first-loads page 0 (`:loading`), not a refresh (`:fetching`). So a
  feed is `has-data?` iff it has at least one accumulated page; only an empty
  vector (not a non-empty one, and not a scalar nil) reads as no-data. A scalar
  entry is `has-data?` iff its `:data` is non-nil (unchanged)."
  [entry]
  (let [data (:data entry)]
    (if (infinite-entry? entry)
      (boolean (seq data))
      (some? data))))

(defn entry-stale?
  "Derived freshness fact: true iff `entry` is stale against `clock-ms` —
  it has been explicitly invalidated (`:invalidated-at` set) OR its
  `:stale-after-ms` window has elapsed (`:stale-at` set and
  `clock-ms >= :stale-at`). Freshness is computed from the DURABLE absolute
  timestamps, NOT from trusting a timer fired on time, and is ORTHOGONAL to
  load status (a `:loaded` entry may be stale). The SINGLE home for the
  staleness derivation so the subs projection, the SSR projection, and the
  stale-timer re-check never drift. Per Spec 016 §Status semantics / §Stale
  and GC scheduling. A computed value, never a stored fact."
  [entry clock-ms]
  (boolean
    (and entry
         (or (some? (:invalidated-at entry))
             (when-let [sa (:stale-at entry)] (>= clock-ms sa))))))

;; ---- shared stale / timer helpers (Spec 016 §Stale and GC scheduling) ------
;;
;; The cache-entry `:stale-at` derivation, the timer-delay positivity guard, and
;; the SSR/server-frame test are read identically by the READ path
;; (`events.cljc` — first load / refresh / poll) and the MUTATION-success path
;; (`mutation_events.cljc` — a patched / populated entry must age exactly as a
;; fetched one). They are pinned here so a patched entry's staleness, the timer
;; delay guard, and the no-wall-clock-background-timers-under-SSR rule never
;; drift between the two writers (rf2-366u0g).

(defn stale-at-for
  "Compute an entry's `:stale-at` from `loaded-at` + the resource's
  `:stale-after-ms` policy, or nil when the resource declares no staleness
  policy (it never goes stale on a timer). The single home both the read path
  (first load / refresh) and the mutation-success path (patch / populate) read
  so a patched entry ages exactly as a fetched one. Per Spec 016 §Stale and GC
  scheduling."
  [spec loaded-at]
  (when-let [ms (:stale-after-ms spec)]
    (+ loaded-at ms)))

(defn positive-or-nil
  "Return `ms` when it is a positive number, else nil (a non-positive / absent
  policy never arms a timer). Guards a timer delay derived from an absolute
  timestamp comparison so a clock-skewed or already-elapsed deadline yields nil
  rather than a negative wall-clock delay. The single guard both the read path
  and the mutation-success path arm their stale / GC / poll timers through. Per
  Spec 016 §Stale and GC scheduling."
  [ms]
  (when (and (number? ms) (pos? ms)) ms))

(defn server-frame?
  "True iff `frame-id` is an SSR / server frame (its `:config :platform` is
  `:server`, set by the `:ssr-server` preset). Reads ONLY the FRAME's platform
  — NOT the host-wide `active-platform` default (which is `:server` on the JVM,
  so a JVM client-mode unit test must still arm timers). The single home both
  the read path and the mutation-success path consult before arming a
  wall-clock background timer. Per Spec 016 §Stale and GC scheduling (no
  wall-clock background timers under SSR)."
  [frame-id]
  (= :server (:platform (frame/frame-meta frame-id))))

;; ---- per-entry revision (EP-0019 §Decision 2 / byl7bk Open Issue 5) --------
;;
;; `:revision` is the per-entry WRITE identity the optimistic-rollback settle
;; protocol compares against. The EP-0019 conflict check at settle is a
;; CANONICAL-IDENTITY comparison of the recorded revision against the entry's
;; current revision — NOT a value diff — answering "did an authoritative write
;; land on this entry between my optimistic apply and my reply settling?". If
;; it did, the recorded inverse is a stale "before" and a blind restore would
;; clobber newer truth; the settle reconciles by invalidation instead.
;;
;; The bump rule (byl7bk ruling, load-bearing): bump on EVERY authoritative
;; durable entry write a rollback could clobber — UNCONDITIONALLY, never gated
;; on `(= old new)` of `:data`. `entry-succeeded` / `patch-entry` /
;; `populate-entry` re-stamp `:loaded-at` / `:stale-at` / `:tags` even when the
;; new `:data` is `=`-shared, so a value-gated token would MISS that freshness
;; settle and let a later rollback silently clobber newer freshness with a
;; stale snapshot — a real cache-coherence bug. Bias to OVER-bump: a false
;; conflict costs one refetch (made safe by the `:on-conflict :invalidate`
;; default — the read path recovers truth); a MISSED conflict means silent
;; corruption. The asymmetry favours over-bumping.

(defn bump-revision
  "Pure: increment the entry's monotone per-entry `:revision` write identity
  (EP-0019 §Decision 2 / byl7bk Open Issue 5). The SINGLE home for the bump so
  every authoritative durable entry write (`entry-succeeded`, `patch-entry`,
  `populate-entry`, the invalidation-driven settle, the later optimistic apply)
  advances it the same way — UNCONDITIONALLY, never gated on whether `:data`
  changed. Treats an absent / nil `:revision` as 0 (a pre-EP-0019 entry or a
  freshly-seeded one), so the bump is total over any entry shape. Returns the
  entry with `:revision` incremented; a nil entry is returned unchanged (there
  is nothing to bump — a missing entry carries no revision)."
  [entry]
  (if entry
    (update entry :revision (fnil inc 0))
    entry))

(defn entry-revision
  "Pure: read an entry's per-entry `:revision` write identity, defaulting to 0
  for an absent / nil entry or a pre-EP-0019 entry that predates the fact. The
  value the optimistic-rollback settle protocol records at apply time and
  compares at settle time (`revision-conflict?`). Per EP-0019 §Decision 2."
  [entry]
  (if entry (:revision entry 0) 0))

(defn revision-conflict?
  "Pure: the EP-0019 settle-time conflict check (Decision 3). True iff the
  entry's CURRENT revision has MOVED away from the `recorded-revision` an
  optimistic apply observed — i.e. an authoritative durable write landed on the
  entry between the apply and the reply settling, so the recorded inverse is a
  stale \"before\" a blind rollback must NOT restore. A canonical-identity
  comparison (`not=` over the monotone counter), never a value diff.

  Conflict-positive (`true`) is the BIAS-SAFE answer: on a true conflict the
  settle reconciles by invalidation (the read path recovers authoritative
  truth); a false positive costs only a refetch, while a false negative would
  let a stale inverse clobber newer truth. The recorded revision is read
  through `entry-revision` semantics (an absent recorded value, e.g. an apply
  against a then-missing entry whose revision was 0, compares against the
  current entry's revision the same way). Per EP-0019 §Decision 3."
  [entry recorded-revision]
  (not= (entry-revision entry) (or recorded-revision 0)))

(defn entry-invalidate
  "Pure: mark `entry` durably STALE (set the `:invalidated-at` fact to
  `invalidated-at`) and bump the per-entry `:revision` (marking an entry stale
  moves its freshness — an authoritative durable write a later optimistic
  rollback could clobber, so it bumps the write identity; bias to over-bump,
  EP-0019 / byl7bk). The single home for the durable stale mark the scoped
  invalidation engine and the EP-0019 restore-dangle conflict-rollback share, so
  a `:stale?` sub derives `true` from `:invalidated-at` identically whether the
  staleness came from an invalidation pass or a dangle-inside-reconciler. A nil
  entry is returned unchanged. Per Spec 016 §Invalidation / §Status semantics."
  [entry invalidated-at]
  (if entry
    (bump-revision (assoc entry :invalidated-at invalidated-at))
    entry))

;; ---- entry transitions (Spec 016 §Status semantics / §Structural sharing) -
;;
;; Pure functions `(entry, …) -> entry`. They transition through
;; `next-status` so the five-state semantics stay in one place, write the
;; durable FACTS (status / data / errors / timestamps / generation /
;; revision / current-work / tags), and NEVER store the derived booleans.
;; Structural sharing preserves the old `:data` value when the newly-decoded
;; value equals the previous (identity-preserving — downstream subs stay quiet
;; on a background refresh that returns identical EDN), but the `:revision`
;; bump is UNCONDITIONAL (a value-gated bump would miss a freshness-only
;; settle — byl7bk ruling).

(defn entry-start-load
  "Transition an entry to its in-flight status for a fresh load attempt:
  `:loading` when it has no usable data (first load), `:fetching` when it
  does (refresh / stale-while-revalidate). Bumps `:generation` and
  `:attempt`, records the `:current-work` pointer + `:request-id`, and
  attaches `owner` to `:active-owners`. Clears `:invalidated-at` (the load
  satisfies any pending invalidation). Per Spec 016 §Status semantics /
  §Lifecycle is an FSM / §Frame work ledger."
  [entry {:keys [generation work-id request-id owner]}]
  (let [had-data? (has-data? entry)]
    (cond-> (assoc entry
                   :status       (next-status (:status entry) :start-load had-data?)
                   :generation   generation
                   :attempt      (inc (:attempt entry 0))
                   :current-work work-id
                   :request-id   request-id
                   ;; a fresh first load clears a prior first-load error; a
                   ;; refresh keeps prior data + clears stale refresh-error
                   ;; lazily on success (Spec 016 §Status semantics)
                   :error          (if had-data? (:error entry) nil)
                   :invalidated-at nil)
      owner (update :active-owners (fnil conj #{}) owner))))

(defn entry-succeeded
  "Transition an entry to `:loaded` on a successful load/refresh. Applies
  STRUCTURAL SHARING: preserves the old `:data` value (identity) when the
  newly-decoded `new-data` is `=` to the previous data, so downstream subs
  stay quiet. Sets `:loaded-at` / `:stale-at` from the supplied clock +
  stale policy, clears `:error` / `:refresh-error` / `:current-work`, and
  records the produced `:tags`. Per Spec 016 §Status semantics /
  §Structural sharing.

  Bumps the per-entry `:revision` UNCONDITIONALLY (EP-0019 / byl7bk Open Issue
  5) — even when `:data` is `=`-shared. A success re-stamps `:loaded-at` /
  `:stale-at` / `:tags` on the structural-sharing branch too, so this IS an
  authoritative durable write a later optimistic rollback could clobber; a
  value-gated bump would miss it. The `:revision` bump is therefore orthogonal
  to the `:data` structural sharing."
  [entry {:keys [data loaded-at stale-at tags]}]
  (let [prev      (:data entry)
        ;; Structural sharing: keep the OLD value when the decoded value is
        ;; equal, so `(identical? old new-data)` holds for quiet downstream
        ;; reactions. Per Spec 016 §Structural sharing.
        shared    (if (and (some? prev) (= prev data)) prev data)]
    (-> entry
        (assoc
          :status        :loaded
          :data          shared
          :error         nil
          :refresh-error nil
          :loaded-at     loaded-at
          :stale-at      stale-at
          :invalidated-at nil
          :current-work  nil
          ;; the new key now has its OWN data — drop the previous-key
          ;; projection pointer (Spec 016 §Paginated and previous data).
          :previous-key  nil
          :tags          (or tags (:tags entry) #{}))
        ;; EP-0019 / byl7bk: every authoritative durable write bumps the
        ;; per-entry write identity, unconditionally — including the
        ;; freshness-only (`=`-shared `:data`) settle the ruling names.
        bump-revision)))

(defn entry-failed
  "Transition an entry on a failed load/refresh (Spec 016 §Status
  semantics). A FIRST-load failure (no usable data) settles `:error` with
  the failure envelope and no data. A BACKGROUND-refresh failure (entry was
  `:fetching`, prior data present) returns to `:loaded`, PRESERVES the
  prior `:data`, and records `:refresh-error`. `next-status` decides which.
  Clears `:current-work`."
  [entry {:keys [error]}]
  (let [had-data?   (has-data? entry)
        next        (next-status (:status entry) :failure had-data?)]
    (if (= :loaded next)
      ;; background-refresh failure — keep data, record refresh-error
      (assoc entry
             :status        :loaded
             :refresh-error error
             :current-work  nil)
      ;; first-load failure — no usable data
      (assoc entry
             :status        :error
             :error         error
             :data          nil
             :current-work  nil))))

;; ---- infinite-feed entry refinement + transitions -------------------------
;; ---- (Spec 016 §Infinite resources and load-more feeds, EP-0021 R1/R2/R8) -
;;
;; An infinite feed is NOT a new entry KIND (R1) — it is the SAME durable
;; `:rf/resource-entry` whose `:data` is refined to be the ORDERED PAGE
;; VECTOR (one element per accumulated page, in load order). There is no
;; sixth FSM state (R2): a load-more reuses the existing `:fetching`
;; refresh-class transition (`entry-start-load` / `next-status`), and a
;; load-more reply settles through these pure transitions instead of the
;; whole-value `entry-succeeded`. The infinite-only facts the entry carries
;; ALONGSIDE the page vector:
;;
;;   :infinite?       true                    — the feed marker (R1)
;;   :data            [<page-0> <page-1> …]    — the ordered page sequence
;;   :page-params     [nil <param-1> …]        — one per page (page-0 = nil)
;;   :next-page-param <param-or-nil>           — recomputed each load; nil = terminal
;;   :prev-page-param <param-or-nil>           — bidirectional mirror (R7; load-prev deferred)
;;   :page-error      <envelope-or-nil>        — the THIRD error channel (load-more failure)
;;
;; `:data` / `:page-params` / `:next-page-param` are the durable facts; the
;; merged list, `:has-next-page?`, `:fetching-next?`, `:page-count`, etc. are
;; DERIVED in the subs layer (wave 4), never stored. The load-more EVENT
;; (wave 3) drives `entry-append-page` / `entry-page-failed` from the work
;; ledger reply path; these are the pure transitions it calls.

(defn infinite-entry?
  "True iff `entry` is an infinite-feed entry (`:infinite?` set). The single
  predicate the runtime / subs read so an infinite feed is distinguished from
  an ordinary single-value entry without re-inspecting `:data`'s shape. Per
  Spec 016 §Durable cache shape (R1)."
  [entry]
  (true? (:infinite? entry)))

(def initial-page-param
  "The framework default page-param for the FIRST page (page-0) of an infinite
  feed — `nil` (the TanStack `initialPageParam` analogue). A resource may
  override it with an optional `:initial-page-param` registration key. Per
  Spec 016 §Causal event — load-more (the first page is fetched with
  `:page-param nil`)."
  nil)

(defn page-param-for-spec
  "The page-0 param for an infinite resource `spec`: the resource's optional
  `:initial-page-param`, or the framework default (`nil`) when none is
  declared. The single home so the load-more event (wave 3) and the first-load
  page-0 fetch agree. Per Spec 016 §Causal event — load-more."
  [spec]
  (get spec :initial-page-param initial-page-param))

(defn empty-infinite-entry
  "Construct an empty `:idle` infinite-feed entry for `resource-id` (and the
  scoped key). Refines `empty-entry` with the infinite facts: the `:infinite?`
  marker, the page vector / page-params seeded EMPTY (no pages yet — page-0 is
  the first load), and the `:next-page-param` / `:prev-page-param` /
  `:page-error` channels nil. The page vector starts `[]` (an empty,
  not-yet-loaded feed) rather than nil so `next-param-for` / `page-count`
  reason about a vector from the first transition. Per Spec 016 §Durable cache
  shape (R1)."
  ([resource-id] (empty-infinite-entry resource-id nil))
  ([resource-id scoped-key]
   (assoc (empty-entry resource-id scoped-key)
          :infinite?       true
          :data            []
          :page-params     []
          :next-page-param nil
          :prev-page-param nil
          :page-error      nil)))

(defn next-param-for
  "PURE next-page-param derivation (R8): given the resource's `:next-page-param`
  fn `(fn [last-page all-pages] → next-param | nil)` and the accumulated
  `pages` vector, return the NEXT page param — or `nil`, the SINGLE canonical
  terminal (\"no more pages\"). An empty `pages` (no page loaded yet) yields
  `nil` (there is no last page to derive from — the page-0 param comes from
  `page-param-for-spec`, not this fn). `next-page-param-fn` is invoked with the
  LAST loaded page and ALL pages so far (TanStack `getNextPageParam(lastPage,
  allPages)`). The derived `:has-next-page?` is `(some? next-param)`. Per Spec
  016 §Registration — :infinite / §Causal event — load-more."
  [next-page-param-fn pages]
  (when (and next-page-param-fn (seq pages))
    (next-page-param-fn (peek pages) pages)))

(defn prev-param-for
  "PURE prev-page-param derivation (R7 bidirectional MIRROR): given the
  resource's optional `:prev-page-param` fn `(fn [first-page all-pages] →
  prev-param | nil)` and the accumulated `pages`, return the PREV page param
  (computed from the FIRST page) — or `nil`. The mirror is defined NOW (it is
  free — the same machinery as `next-param-for`); the prepend EVENT
  (`:rf.resource/load-prev`) is DEFERRED, so v1 never advances backward, but
  `:has-prev-page?` is observable. Returns nil when no `:prev-page-param` fn is
  declared or no page is loaded. Per Spec 016 §Causal event — load-more (R7)."
  [prev-page-param-fn pages]
  (when (and prev-page-param-fn (seq pages))
    (prev-page-param-fn (first pages) pages)))

(defn terminal?
  "PURE: true iff a feed whose derived `next-param` is the single canonical
  terminal `nil` has reached the end (no more pages). The complement of the
  derived `:has-next-page?` (`(some? next-param)`). The single home so the
  load-more event's no-op-on-terminal check and the subs `:has-next-page?`
  projection agree on the terminal rule. Per Spec 016 §Registration — :infinite
  (nil is the SINGLE terminal)."
  [next-param]
  (nil? next-param))

(defn page-count
  "PURE: the number of accumulated pages in an infinite feed `entry` (the
  derived `:page-count`). Reads the `:data` page vector length; total over an
  ordinary (nil `:data`) entry (0). Per Spec 016 §Subscription contract."
  [entry]
  (count (:data entry)))

(defn entry-append-page
  "PURE infinite-feed APPEND transition (R1/R2): append a freshly-fetched,
  decoded `page` (with its resolved `page-param`) to the feed `entry`, advance
  the cursor, and return the feed to `:loaded`. The single durable mutation a
  successful load-more performs:

    - APPEND `page` to the `:data` page vector (structural sharing keeps every
      PRIOR page identical — only the appended page is new, Spec 016 §Durable
      cache shape);
    - APPEND `page-param` to `:page-params` (one per page; page-0's param is
      whatever the page-0 fetch used — `page-param-for-spec`);
    - RECOMPUTE `:next-page-param` from the new tail via `next-page-param-fn`
      (`nil` = the terminal — `:has-next-page?` then derives false) and
      `:prev-page-param` from the head via `prev-page-param-fn`;
    - CLEAR `:page-error` (a successful load-more clears the prior load-more
      failure, Spec 016 §Causal event — load-more);
    - re-stamp `:loaded-at` / `:stale-at` and return to `:loaded` (the feed was
      `:fetching` during the load-more — the accumulated pages stayed visible),
      clear `:current-work`, and bump the per-entry `:revision` UNCONDITIONALLY
      (this is an authoritative durable write — EP-0019 / byl7bk).

  `opts`: `{:page …  :next-page-param-fn …  :prev-page-param-fn …  :page-param …
            :loaded-at …  :stale-at …}` — `:page` is the decoded page to append.
  A nil `entry` is returned unchanged (there is nothing to append to — a
  missing feed entry). Per Spec 016 §Causal event — load-more (R2) / §Durable
  cache shape (R1)."
  [entry {:keys [page next-page-param-fn prev-page-param-fn page-param loaded-at stale-at]}]
  (if entry
    (let [pages' (conj (or (:data entry) []) page)]
      (-> entry
          (assoc :status          :loaded
                 :data            pages'
                 :page-params     (conj (or (:page-params entry) []) page-param)
                 :next-page-param (next-param-for next-page-param-fn pages')
                 :prev-page-param (prev-param-for prev-page-param-fn pages')
                 :page-error      nil
                 :refresh-error   nil
                 :loaded-at       loaded-at
                 :stale-at        stale-at
                 :invalidated-at  nil
                 :current-work    nil)
          bump-revision))
    entry))

(defn entry-page-failed
  "PURE infinite-feed LOAD-MORE FAILURE transition: a load-more page fetch
  (page N>0) failed. UNLIKE a first-load failure (`entry-failed` → `:error`,
  no data) and a whole-feed refresh failure (`entry-failed` → `:loaded`,
  `:refresh-error`), a load-more failure is the THIRD error channel: the feed
  returns to `:loaded`, KEEPS ALL accumulated pages (the page vector + cursor
  are untouched), and records `:page-error` — so a view shows \"couldn't load
  more — retry\" without losing the feed. `:page-error` is cleared by the next
  successful load-more or whole-feed load. Clears `:current-work`. A nil entry
  is returned unchanged. Per Spec 016 §Causal event — load-more (the third
  error channel)."
  [entry {:keys [error]}]
  (if entry
    (assoc entry
           :status       :loaded
           :page-error   error
           :current-work nil)
    entry))

(defn entry-replace-page
  "PURE infinite-feed page REPLACE-IN-PLACE transition (R6 window-preserving
  refetch): replace the page at `page-index` of the feed `entry` with a
  freshly-fetched, decoded `page` (and its resolved `page-param`), WITHOUT
  growing the feed — the accumulated tail is preserved and stays visible. This
  is the settle a window-preserving `refetch`'s replacement page-0 performs
  (the ruled R6 default): the feed never collapses to page 0; page 0 is
  refreshed in place and the rest of the window is kept.

  Like `entry-append-page` it recomputes `:next-page-param` / `:prev-page-param`
  from the resulting page vector, clears `:page-error` / `:refresh-error`,
  re-stamps `:loaded-at` / `:stale-at`, returns to `:loaded`, clears
  `:current-work`, and bumps `:revision` UNCONDITIONALLY (an authoritative
  durable write — EP-0019 / byl7bk). Structural sharing keeps every OTHER page
  identical (only the replaced index is new).

  When `page-index` is at or beyond the current page count this DELEGATES to
  `entry-append-page` (a replacement past the tail is an append — e.g. a
  window-preserving refetch of a feed that was emptied), so one settle covers
  both the in-place refresh and the append. A nil entry is returned unchanged.
  Per Spec 016 §Refetch and invalidation of an infinite feed (R6)."
  [entry {:keys [page next-page-param-fn prev-page-param-fn page-param page-index
                 loaded-at stale-at] :as opts}]
  (if entry
    (let [pages (or (:data entry) [])]
      (if (>= page-index (count pages))
        (entry-append-page entry opts)
        (let [prev-page (nth pages page-index)
              ;; structural sharing: keep the OLD page value when the decoded
              ;; page is `=` (a refetch that returned identical page-0 stays
              ;; quiet downstream); only the replaced index is ever new.
              shared    (if (and (some? prev-page) (= prev-page page)) prev-page page)
              pages'    (assoc pages page-index shared)
              params'   (assoc (or (:page-params entry) []) page-index page-param)]
          (-> entry
              (assoc :status          :loaded
                     :data            pages'
                     :page-params     params'
                     :next-page-param (next-param-for next-page-param-fn pages')
                     :prev-page-param (prev-param-for prev-page-param-fn pages')
                     :page-error      nil
                     :refresh-error   nil
                     :loaded-at       loaded-at
                     :stale-at        stale-at
                     :invalidated-at  nil
                     :current-work    nil)
              bump-revision))))
    entry))

(defn refetch-keep-count
  "PURE: how many leading pages a `refetch` of an infinite feed KEEPS, given
  the resource's optional `:refetch` policy (R6) and the feed's current
  `page-count`. The ruled DEFAULT is window-preserving — keep EVERY
  accumulated page (`page-count`), so a focus/reconnect/invalidation refetch
  never collapses a loaded feed to page 0. The day-one opt-ins:

    - `:refetch-all-pages? true` — do NOT preserve the window: keep ONLY page 0
      (the replacement page-0 re-accumulates the feed from scratch, the
      TanStack-parity \"refresh the whole thing\" intent; v1 re-fetches page 0
      and the user re-loads forward rather than a synchronous N-page sweep);
    - `:refetch-window n` — bound the kept window to the first `n` pages
      (clamped to `[1, page-count]` — a refetch always keeps at least page 0,
      and never invents pages beyond what is loaded).

  Returns the keep-count (always ≥ 1 for a non-empty feed; 0 for an empty
  feed). Per Spec 016 §Refetch and invalidation of an infinite feed (R6)."
  [refetch-policy page-count]
  (let [{:keys [refetch-all-pages? refetch-window]} refetch-policy]
    (cond
      (zero? page-count)          0
      refetch-all-pages?          1
      (integer? refetch-window)   (max 1 (min refetch-window page-count))
      :else                       page-count)))

(defn entry-refetch-reset
  "PURE infinite-feed REFETCH reset transition (R6), applied at refetch-ISSUE
  (before the replacement page-0 fetch starts): truncate the feed's
  accumulation to the first `keep-count` pages per `refetch-keep-count`, so the
  in-flight refetch refreshes exactly the intended window. The ruled DEFAULT
  (window-preserving) keeps EVERY page — a pure no-op here — so the accumulated
  pages stay visible during the refetch (the feed is `:fetching`, not collapsed
  to page 0). The opt-ins (`:refetch-all-pages?` / `:refetch-window`) truncate
  the tail.

  The `:next-page-param` is RECOMPUTED from the (possibly truncated) tail so a
  windowed refetch resumes load-more from the kept window's edge; `:page-params`
  is truncated in step. `:data` / `:page-params` keep their leading pages by
  identity (structural sharing — only the dropped tail changes). Does NOT touch
  `:status` / `:current-work` / `:revision` (the caller's `entry-start-load`
  already transitioned the entry to `:fetching` and recorded the work). A nil /
  non-infinite / empty entry is returned unchanged. Per Spec 016 §Refetch and
  invalidation of an infinite feed (R6)."
  [entry {:keys [next-page-param-fn prev-page-param-fn refetch-policy]}]
  (if (and entry (infinite-entry? entry) (seq (:data entry)))
    (let [pages      (:data entry)
          keep-count (refetch-keep-count refetch-policy (count pages))]
      (if (>= keep-count (count pages))
        entry                                   ;; window-preserving — keep all
        (let [pages'  (subvec pages 0 keep-count)
              params' (subvec (or (:page-params entry) []) 0
                              (min keep-count (count (:page-params entry))))]
          (assoc entry
                 :data            pages'
                 :page-params     params'
                 :next-page-param (next-param-for next-page-param-fn pages')
                 :prev-page-param (prev-param-for prev-page-param-fn pages')))))
    entry))

(defn resolve-page->items
  "PURE: resolve a feed's `:page->items` accessor (a keyword key or a
  `(fn [page] → seq-of-items)`) into a fn `(page → seq-of-items)`. A keyword is
  lifted to its `get` accessor. When `page->items` is nil, returns nil — the
  caller (the merge site, wave 4) then applies the IDENTITY-flatten rule for a
  vector page and raises `:rf.error/infinite-missing-page-accessor` for a
  non-vector page (R3, loud over guessing). The single home so the registry
  shape-validation and the wave-4 merge agree on what shapes are accepted. Per
  Spec 016 §Subscription contract (R3)."
  [page->items]
  (cond
    (nil? page->items)     nil
    (keyword? page->items) #(get % page->items)
    (fn? page->items)      page->items
    :else                  nil))

(defn merge-pages->items
  "PURE infinite-feed MERGE projection (EP-0021 R3): flatten the accumulated
  page vector `pages` into the single merged item list — the headline
  `:rf.resource/items` read. The flatten rule is LOUD, not magic:

    - a page that is ALREADY A VECTOR flattens by identity (its elements ARE
      the items — no accessor needed);
    - a page that is non-vector / enveloped (e.g. `{:items […] :page-info …}`)
      flattens via the resource's REQUIRED `:page->items` accessor;
    - a non-vector page with NO `:page->items` accessor is a loud
      `:rf.error/infinite-missing-page-accessor` error — the runtime-detected
      counterpart the wave-2 registry validation deferred to this merge site
      (the registry cannot inspect a page shape at registration time; only the
      merge sees a concrete page). The framework NEVER guesses `:items` /
      `:data`.

  `resolved-accessor` is the already-resolved `:page->items` fn (via
  `resolve-page->items`) or nil. `resource-id` / `where` name the offending
  feed + the public sub surface for the error diagnostic. Returns a VECTOR of
  the concatenated items (`(into [] (mapcat …) pages)`); an empty / nil page
  vector yields `[]`. Per Spec 016 §Subscription contract — the merged list
  and page metadata (R3)."
  [pages resolved-accessor resource-id where]
  (into []
        (mapcat
          (fn [page]
            (cond
              ;; an already-vector page IS its items (identity flatten)
              (vector? page)         page
              ;; a declared accessor lifts a non-vector / enveloped page
              (some? resolved-accessor) (resolved-accessor page)
              ;; loud over guessing (R3): a non-vector page + no accessor
              :else
              (error/throw-error!
                :rf.error/infinite-missing-page-accessor
                where
                (str "infinite resource " resource-id " accumulated a non-vector "
                     "page but declares no :page->items accessor — the merged "
                     ":rf.resource/items list cannot flatten it. Declare "
                     ":page->items (a keyword key or a (fn [page] → items)) on the "
                     "reg-resource; the framework does NOT guess :items / :data. "
                     "Per Spec 016 §Subscription contract (R3).")
                {:recovery :fix-registration
                 :extra    {:resource-id resource-id
                            :page-shape  (cond (map? page) :map
                                               (seq? page) :seq
                                               :else       (type page))}}))))
        (or pages [])))

;; ---- reverse-index recompute (Spec 016 §Restore and replay part 5) --------
;;
;; `:tag-index` and `:owner-index` are DERIVED projections of the entries'
;; `:tags` and `:active-owners`. They are recomputable-from-`:entries`: on
;; restore / SSR-hydration they are rebuilt from the installed `:entries`
;; rather than trusted from the snapshot, so a stale or partial index can
;; never outlive the entries it describes. The runtime keeps them in step
;; incrementally, but `recompute-indexes` is the single authoritative
;; rebuild both restore and an in-cascade index repair use.

(defn recompute-indexes
  "Rebuild `:tag-index` (`{<tag> #{<key-id> …}}`) and `:owner-index`
  (`{<owner> #{<key-id> …}}`) from the resource subtree's `:entries`.
  Returns the resource subtree with both indexes replaced. Per Spec 016
  §Restore and replay part 5 / §Cache home.

  rf2-9e0tyq: the index MEMBERS are the CEDN-1 byte `key-id` — the SAME key
  the `:entries` map uses — so a list- and a vector-params key produce
  DISTINCT index members (the `=`-collapse would otherwise fold two sets'
  members into one). The member is the map key of `:entries` directly (it
  already IS the byte `key-id`), so consumers resolve an index member to its
  entry via `entry-path-by-id`."
  [resources-subtree]
  (let [entries (:entries resources-subtree)]
    (reduce-kv
      (fn [acc k-id entry]
        (-> acc
            (update :tag-index
                    (fn [ti] (reduce (fn [ti tag] (update ti tag (fnil conj #{}) k-id))
                                     ti (:tags entry))))
            (update :owner-index
                    (fn [oi] (reduce (fn [oi owner] (update oi owner (fnil conj #{}) k-id))
                                     oi (:active-owners entry))))))
      (assoc resources-subtree :tag-index {} :owner-index {})
      entries)))

;; ---- host-side transient generation allocator -----------------------------
;;
;; Per Spec 016 §Restore and replay part 1: the generation allocator is a
;; per-frame, HOST-SIDE monotonic high-water mark — never rewound by epoch
;; restore, so a pre-restore in-flight reply's generation can never match a
;; post-restore live entry (stale-suppression is structurally safe). This
;; is deliberately the OPPOSITE discipline from machine spawn-ids (which
;; never escape the frame and so may be snapshot-local).
;;
;; The PURE SEAM (handlers stay pure), mirroring routing's nav-allocation
;; (rf2-oosjmh / rf2-vcop6y): the next generation is minted by the
;; RECORDABLE `:rf.resource/generation-allocation` cofx GENERATOR (which
;; reads the active frame's high-water snapshot at processing-start and
;; records the minted value on the token — rf2-abyycr); the handler reads
;; the recorded `:generation` value flat and writes only it durably; WRITE
;; via the `:rf.resource/commit-generation` fx (advances the host high-water
;; with `max`, monotone). A frame's entry is released on frame destroy.

(defonce
  ^{:doc "Per-frame host-side generation high-water marks
   `{<frame-id> <int>}`. Host-side transient state (NOT runtime-db), so an
   epoch restore cannot rewind it and recycle a generation — the
   anti-recycling correctness boundary (Spec 016 §Restore and replay part
   1). Read by the recordable `:rf.resource/generation-allocation` cofx
   generator (which records the minted value on the token, rf2-abyycr),
   advanced via the `:rf.resource/commit-generation` fx (both monotone)."}
  generation-cache
  (atom {}))

(defn generation-snapshot
  "Read `frame-id`'s current generation high-water mark from the host
  `generation-cache` (0 when none). The value the recordable
  `:rf.resource/generation-allocation` cofx generator reads to mint the next
  monotone allocation."
  [frame-id]
  (get @generation-cache frame-id 0))

(defn next-generation
  "Pure: given a high-water `snapshot` int (or nil), return the next
  monotone generation `(inc snapshot)`. Does NOT mutate — the
  `:rf.resource/generation-allocation` cofx generator uses it to mint the
  allocation value at processing-start; the handler then emits a
  `:rf.resource/commit-generation` fx carrying it to advance the host
  high-water."
  [snapshot]
  (inc (or snapshot 0)))

(defn commit-generation!
  "Record `n` as `frame-id`'s generation high-water mark in the host
  `generation-cache`. MONOTONE — never lowers an existing value (a `max`
  install), so a reordered / replayed commit can never rewind the allocator
  and recycle a generation. Per Spec 016 §Restore and replay part 1.
  Returns nil."
  [frame-id n]
  (swap! generation-cache update frame-id (fn [cur] (max (or cur 0) n)))
  nil)

(defn release-frame!
  "Drop the destroyed frame's host-side generation high-water mark.
  Invoked by the resources frame-destroy teardown hook. Per Spec 016
  §Stale and GC scheduling (frame destroy cancels all resource timers /
  clears host handles for that frame) and §Restore and replay part 5."
  [frame-id]
  (swap! generation-cache dissoc frame-id)
  nil)

(defn reset-cache!
  "Drop EVERY frame's host-side generation high-water mark (test
  isolation). Published as a reset hook so the shared CLJS
  `make-reset-runtime-fixture` reset-hooks table clears it per test (it is
  host-side transient state, not cleared by the runtime/frames reset)."
  []
  (reset! generation-cache {})
  nil)

;; ---- the :rf.resource/generation-allocation cofx + commit-generation fx ---
;;
;; The RECORDABLE allocation seam over the host-side allocator (rf2-abyycr;
;; mirrors routing's `:rf.route/nav-allocation`, rf2-vcop6y). The generation
;; is a DURABLE JOIN KEY (it is written onto the entry / instance and stamped
;; onto the reply token as the stale-suppression correlation), so per
;; [002 §Durable join keys are recordable](spec/002-Frames.md) the minted
;; VALUE must be recordable even though the ALLOCATOR stays host-transient.
;;
;; The shape is a GENERATOR-BACKED recordable cofx (EP-0017 §5, the last rung
;; of the minting ladder — a genuinely fold-internal identity no recorded
;; state or event payload can supply):
;;   - the generator reads the active frame's host high-water snapshot and
;;     produces the next monotone allocation `{:generation N :counter N}` at
;;     PROCESSING-START — the produced value is written back into the
;;     in-flight `:rf.cofx` record so the epoch captures it and replay
;;     re-presents it (live = generate-and-record; replay = supplied,
;;     strict = no generator runs);
;;   - the ensure / refetch / mutation-execute handlers declare
;;     `:rf.cofx/requires [:rf.resource/generation-allocation]`, read the
;;     `:generation` value flat, and write ONLY that value durably (they no
;;     longer re-mint `(inc snapshot)` from an ambient read at the write
;;     site);
;;   - the WRITE half (`:rf.resource/commit-generation` fx) advances the host
;;     high-water with `max` so replay / restore can never rewind the
;;     allocator and recycle a generation (parts 1-5 of Spec 016 §Restore
;;     and replay stay correct — the allocator is still host-transient);
;;   - strict replay (Tool-Pair / `:test` preset) FAILS LOUD with
;;     `:rf.error/missing-required-cofx` if the recorded allocation is
;;     missing, rather than silently re-minting a divergent generation.
;;
;; `:counter` is carried alongside `:generation` (they are equal for the
;; resource allocator, whose generation IS the counter value) to mirror the
;; routing split-allocation shape and to let a restore re-establish the host
;; high-water from the recorded value when needed.

(def generation-allocation-cofx-meta
  "Metadata for the `:rf.resource/generation-allocation` cofx registration —
  a GENERATOR-BACKED recordable allocation (rf2-abyycr, EP-0017 §5)."
  {:recordable? true
   :schema [:map [:generation :int] [:counter :int]]
   :doc "The recordable generation allocation for the active frame's resource
/mutation work: `{:generation N :counter N}`, minted at processing-start from
the host-side monotone high-water allocator (`generation-cache`) and recorded
on the causal token. `:generation` is the durable join key written onto the
entry / instance and stamped on the reply token (the stale-suppression
correlation, EP-0011); `:counter` mirrors it (the resource allocator's
generation IS its counter) so a restore can re-establish the host high-water.
The ensure/refetch/mutation-execute handlers declare
`:rf.cofx/requires [:rf.resource/generation-allocation]`, read `:generation`
flat, and write only that value durably; the host allocator advances with
`max` via the `:rf.resource/commit-generation` fx so replay/restore cannot
rewind it. Recordable so replay reproduces an identical generation (and
therefore identical `:work/id` / `:instance/id`, which derive from it). Per
Spec 016 §Restore and replay + 002 §Durable join keys are recordable."})

(defn generation-allocation-cofx
  "Value-returning GENERATOR for the `:rf.resource/generation-allocation`
  recordable cofx (EP-0017 §5). Reads the in-flight cascade's frame
  (`frame/*current-frame*`, bound by the router during processing) and the
  frame's host-side generation high-water snapshot, and returns the next
  monotone allocation `{:generation N :counter N}` (N = `(inc snapshot)`).

  The generator only READS the host cache — it does NOT mutate it (the write
  is the separate `:rf.resource/commit-generation` fx, emitted by the
  allocating handler). Under `:live` the runtime records the produced value
  into the token's `:rf.cofx` so replay re-presents it; under `:strict`
  (replay / `:test` preset) the generator does NOT run — an absent recorded
  allocation is `:rf.error/missing-required-cofx`, so a recorded epoch
  reproduces the exact generation rather than re-minting a divergent one.
  Tests that need a deterministic allocation supply it on the dispatch token
  (`:rf.cofx {:rf.resource/generation-allocation {:generation N :counter N}}`)
  or re-register the generator (the visible seam)."
  []
  (let [n (next-generation (generation-snapshot frame/*current-frame*))]
    {:generation n :counter n}))

(def commit-generation-meta
  "Metadata for the `:rf.resource/commit-generation` fx registration. The
  WRITE half of the host-side generation seam: advances the host
  `generation-cache` high-water with `max`. Universal platform —
  the allocator is host-side on both client and server."
  {:doc "Advance the host-side `re-frame.resources.state` generation
high-water mark with `max` (monotone — never rewinds). Args: `{:value N}` (the
allocated `:generation` / `:counter` from the recordable
`:rf.resource/generation-allocation` cofx). Emitted by the
ensure/refetch/mutation-execute handlers on the branch that allocates a
generation; advancing with `max` is what makes replay/restore unable to
rewind the allocator and recycle a generation. Per Spec 016 §Restore and
replay."})

(defn commit-generation-handler
  "`:rf.resource/commit-generation` fx handler. Registered by the façade so
  a `:reload` re-wires it on a fresh registrar. Advances the host high-water
  with `max` under the cascade-envelope frame in the host `generation-cache`.
  The carried-frame invariant (EP-0002): the fx context carries the cascade
  frame as `:frame`; a nil stamp is an invariant failure
  (`:rf.error/no-frame-context`), never a synthesised default."
  [{:keys [frame]} {:keys [value]}]
  (let [frame-id (frame/require-frame-stamp!
                   frame :rf.resource/commit-generation
                   {:where 'rf.resource/commit-generation-handler})]
    (when (number? value)
      (commit-generation! frame-id value))
    nil))
