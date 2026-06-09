(ns re-frame.ssr.payload-policy
  "Hydration-payload policy contract — explicit and fail-closed.

  Per Spec 011 §Payload scope (canonical boundary): the
  `:rf/hydration-payload` carries the **bounded** state needed to
  recompute the server's view on the client. Whether to ship a slice or
  the whole `app-db` is a security decision — the wrong default leaks
  internal state (auth tokens stashed for in-flight handlers, internal
  feature flags, server-only working scratch) to every visitor of every
  page.

  A fail-OPEN default (ship whole `app-db` when `:payload` is
  nil/empty) would make the privacy decision implicit; the
  `:rf/response`-accumulator-on-app-db trap is one branch of the same
  family (rf2-jbcmt landed the side-channel storage substrate, rf2-gtgf9
  closed the default).

  This namespace makes the policy:

    1. **Explicit** — callers must declare intent.
    2. **Fail-closed** — absence of an opt is a structural error, not
       a license to ship everything.
    3. **Allowlist-shaped** — denylists go stale silently as new keys
       land in `app-db`; an allowlist requires a deliberate edit per
       new wire-bound key. The masterpiece-bar choice for a security
       contract.

  ---- The contract ----

  A SINGLE handler opt — `:payload` — carries the policy. It is one of
  two shapes (rf2-pffil consolidated the prior two-opt `:payload-keys` +
  `:payload-policy` surface into one — pre-alpha, no back-compat shim):

    `:payload [<kw> <kw> ...]`
      An **allowlist** of top-level `app-db` keys to ship (a non-empty
      **vector** of KEYWORDS). Other keys are dropped — including any
      keys added later as the app evolves. The recommended primary
      mechanism. A non-empty vector carrying a non-keyword element
      (a string typo, a stray `nil`, a nested coll) fails loud at boot with
      `:rf.error/ssr-malformed-payload-allowlist` rather than silently
      shipping a wrong/empty slice (rf2-hzttr). The allowlist shape is a
      vector specifically (rf2-d8vs9x — a list / seq is not an accepted
      spelling); the vector-vs-keyword distinction IS the allowlist-vs-
      whole-app-db policy selector, so a single precise shape keeps the
      fail-closed security boundary unambiguous.

    `:payload :rf.ssr.payload/whole-app-db`
      An explicit opt-in to ship the whole `app-db`. Use only when the
      app's `app-db` is structurally safe to expose end-to-end — e.g.
      a small SPA where every key the server populates is intended
      for the client. The keyword is namespaced under `:rf.ssr.payload/`
      (per Conventions §`:rf/*` reserved namespace) so consumers
      reading the opt see the security weight of the choice.

  Absence of `:payload` is a structural error
  (`:rf.error/ssr-missing-payload-policy`) — fail-closed.

  The single-opt shape removes the prior surface's ambiguity. The two-opt
  design had to define a precedence rule (`:payload-keys` wins over
  `:payload-policy` when both are passed) AND a silent-ignore branch (a
  `:payload-policy` passed alongside an allowlist was discarded without a
  word). One opt can hold exactly one value, so there is nothing to
  arbitrate: the allowlist-vs-whole-app-db choice is the value's SHAPE
  (vector vs keyword), not a contest between two opts. Empty `:payload`
  (`[]`) is treated as **no allowlist supplied**, since shipping zero
  keys is almost certainly a programmer error rather than an intent.
  Callers that genuinely want to ship an empty `:rf/app-db` use
  `:payload :rf.ssr.payload/whole-app-db` against an empty `app-db`
  (i.e. don't populate it server-side).

  ---- Where this is consumed ----

  Two payload builders share this contract:

    `re-frame.ssr.ring.payload/build-payload`     — non-streaming SSR
    `re-frame.ssr.streaming/build-final-payload`  — streaming SSR

  The Ring host adapter (`re-frame.ssr.ring/ssr-handler` and
  `stream-handler`) validates the policy at handler-construction time
  via `validate-policy-opts!` so misconfigured deployments fail at
  boot rather than at first request — the canonical fail-closed
  pattern.

  ---- Version resolution + payload assembly ----

  This namespace is also the single home for the hydration-payload's
  `:rf/version` resolution (`resolve-version`) and the canonical
  four-key payload assembly (`build-payload`). Both streaming and
  non-streaming SSR construct the identical `:rf/hydration-payload`
  shape and pin `:rf/version` from the identical source-of-truth: the
  caller's explicit `:version` opt, falling back to the
  `:rf2/runtime-version` late-bind hook the client-side
  `:rf.ssr/check-version` fx reads, falling back to the v1 pattern-
  protocol stamp. The two call sites differ only in how they source
  `app-db` (the non-streaming path is handed it; the streaming path
  reads it from the live frame after every continuation drains), so
  each owns a thin wrapper over `build-payload`:

    `re-frame.ssr.ring.payload/build-payload`     - non-streaming SSR
    `re-frame.ssr.streaming/build-final-payload`  - streaming SSR"
  (:refer-clojure :exclude [resolve])
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- policy-spec keyword constants ----------------------------------------
;;
;; The whole-app-db keyword is reserved under the `:rf.ssr.payload/*`
;; namespace (per Conventions §`:rf/*` reserved namespaces — `:rf.ssr/*`
;; is the SSR sub-namespace; `:rf.ssr.payload/*` is the policy slot under
;; it).

(def whole-app-db-policy
  "Opt-in policy keyword for shipping the entire `app-db` in the
  hydration payload. Set as the `:payload` opt's value on the handler
  opts (`:payload :rf.ssr.payload/whole-app-db`)."
  :rf.ssr.payload/whole-app-db)

;; ---- policy resolution ---------------------------------------------------

(defn- valid-allowlist?
  "An allowlist is a non-empty **vector** of top-level app-db keys — the
  vector shape of the `:payload` opt. Per Spec 011 §`:rf/app-db`
  projection the elements are *top-level app-db keys*, which in re-frame2
  are keywords; this validator therefore requires EVERY element to be a
  keyword.

  rf2-hzttr finding 2 — the prior element-shape gap: a check of
  `(and (sequential? x) (seq x))` only verified the OUTER shape, so a
  malformed allowlist — a string typo for a keyword (`[\"public/articles\"]`),
  a stray `nil` (`[:a nil]`), or a nested coll (`[[:a :b]]`) — passed
  construction-time validation and was caught only later by `select-keys`,
  silently shipping an empty or wrong hydration slice instead of failing
  closed at boot. Validating each element to a keyword closes that gap.

  rf2-d8vs9x — the OUTER-shape gap: the guard was `sequential?`, which also
  admits lists (`'(:a :b)`) and seqs, contradicting the documented
  `vector vs keyword` shape selector (Spec 011 §`:rf/app-db` projection —
  \"a non-empty VECTOR of keywords\" / \"the allowlist-vs-whole-app-db choice
  is the value's SHAPE (vector vs keyword)\"). A shape-selected fail-closed
  security boundary wants ONE precise contract, not two accepted spellings:
  the allowlist is a vector. A list / seq is no longer an accepted allowlist
  spelling — it fails closed into the missing-policy bucket (asserted by the
  list-rejection regression that replaced the prior vector-or-list test)."
  [x]
  (and (vector? x)
       (seq x)
       (every? keyword? x)))

(defn- malformed-allowlist?
  "A `:payload` that LOOKS like an allowlist attempt — a non-empty
  **vector** — but carries a non-keyword element (a string typo, a stray
  `nil`, a nested coll, …). Distinguished from `valid-allowlist?` so the
  construction-time validator can surface a diagnostic
  `:rf.error/ssr-malformed-payload-allowlist` (which element is bad)
  rather than dumping the caller into the generic missing-policy bucket
  — the developer clearly INTENDED an allowlist; telling them which entry
  is wrong is the fail-loud, masterpiece outcome (rf2-hzttr finding 2).
  An empty `[]` is NOT malformed — it is the documented `no-allowlist`
  shape that falls into the missing-policy bucket per Spec 011.

  rf2-d8vs9x — gated on `vector?` (not `sequential?`) to match
  `valid-allowlist?`'s tightened outer shape: a non-vector sequential
  (a list / seq) is not an allowlist ATTEMPT under the vector-shaped
  contract — it falls into the missing-policy bucket, not the
  malformed-allowlist diagnostic."
  [x]
  (and (vector? x)
       (seq x)
       (not (every? keyword? x))))

(defn- valid-policy-keyword?
  "Currently the only explicit-policy keyword recognised is the
  whole-app-db opt-in — the keyword shape of the `:payload` opt. New
  policies (e.g. schema-driven projections) would extend this set."
  [x]
  (= x whole-app-db-policy))

(defn validate-policy-opts!
  "Throw a structured error when the caller's `:payload` opt is absent or
  malformed. Called at handler-construction time by the Ring host adapter
  so misconfigured deployments fail at boot, not at first request.

    - `:payload [<kws>]` (non-empty VECTOR of keywords) → OK (allowlist)
    - `:payload :rf.ssr.payload/whole-app-db`   → OK (whole-app-db opt-in)
    - `:payload [<… non-keyword element …>]` (non-empty VECTOR with a
      string/nil/nested entry) → `:rf.error/ssr-malformed-payload-allowlist`
      (rf2-hzttr — a clear allowlist attempt with a bad element, surfaced
      distinctly with the offending entries so a `\"public/articles\"`
      string-typo fails loud at boot instead of silently shipping an empty
      slice)
    - `:payload <other keyword>`  → `:rf.error/ssr-unknown-payload-policy`
      (a typo'd policy keyword, e.g. `:rf.ssr.payload/whole-db`, surfaced
      distinctly so it doesn't silently land in the missing bucket)
    - `:payload` absent / empty `[]` / nil / a list-or-seq spelling /
      any other non-vector non-keyword → `:rf.error/ssr-missing-payload-policy`
      (fail-closed; rf2-d8vs9x — a list `'(:a :b)` is NOT an accepted
      allowlist spelling, the allowlist shape is a vector)

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly."
  [{:keys [payload] :as opts}]
  (cond
    (valid-allowlist? payload)
    opts

    (valid-policy-keyword? payload)
    opts

    ;; Caller passed a non-empty sequential coll that ISN'T an all-keyword
    ;; allowlist — a clear allowlist attempt with a malformed element
    ;; (string typo, stray nil, nested coll). Surface the offending entries
    ;; so the fix is obvious; do NOT let it slide into the generic
    ;; missing-policy bucket where a `select-keys` would otherwise ship a
    ;; wrong/empty slice (rf2-hzttr finding 2).
    (malformed-allowlist? payload)
    (throw (ex-info ":rf.error/ssr-malformed-payload-allowlist"
                    {:rf.error/id   :rf.error/ssr-malformed-payload-allowlist
                     :where         'rf.ssr/payload-policy
                     :reason        (str "ssr-handler :payload allowlist must be a "
                                         "non-empty VECTOR of KEYWORD "
                                         "top-level app-db keys; got "
                                         (pr-str payload)
                                         " — these entries are not keywords: "
                                         (pr-str (vec (remove keyword? payload)))
                                         " (a string key like \"public/articles\" "
                                         "should be the keyword :public/articles).")
                     :got           payload
                     :bad-entries   (vec (remove keyword? payload))
                     :recovery      :declare-payload-policy}))

    ;; Caller passed a keyword that isn't the recognised whole-app-db
    ;; opt-in — surface as a distinct error so a typo (e.g.
    ;; `:rf.ssr.payload/whole-db`) doesn't silently land in the
    ;; missing-policy bucket.
    (keyword? payload)
    (throw (ex-info ":rf.error/ssr-unknown-payload-policy"
                    {:rf.error/id    :rf.error/ssr-unknown-payload-policy
                     :where          'rf.ssr/payload-policy
                     :reason         (str "ssr-handler :payload keyword must be "
                                          (pr-str whole-app-db-policy)
                                          " (or pass a vector allowlist of "
                                          "top-level app-db keys instead)")
                     :got            payload
                     :recognised     #{whole-app-db-policy}
                     :recovery       :declare-payload-policy}))

    :else
    (throw (ex-info ":rf.error/ssr-missing-payload-policy"
                    {:rf.error/id :rf.error/ssr-missing-payload-policy
                     :where    'rf.ssr/payload-policy
                     :reason   (str "ssr-handler requires an explicit hydration-"
                                    "payload policy: pass :payload "
                                    "[<top-level-app-db-keys>] (allowlist, "
                                    "preferred) OR :payload "
                                    (pr-str whole-app-db-policy)
                                    " to opt-in to shipping the whole app-db.")
                     :got      payload
                     :recovery :declare-payload-policy}))))

(defn apply-policy
  "Project `app-db` to the wire slice per the caller's declared `:payload`
  policy. Returns the slice (a map) — the value that lands on the
  `:rf/hydration-payload`'s `:rf/app-db` key.

  Per the contract:

    - `:payload [<kws>]` (allowlist, non-empty VECTOR of keywords)
      → `select-keys`.
    - `:payload :rf.ssr.payload/whole-app-db` → ships `app-db` verbatim.
    - Absence / malformed → throws (`:rf.error/ssr-missing-payload-policy`,
      `:rf.error/ssr-malformed-payload-allowlist`, or
      `:rf.error/ssr-unknown-payload-policy`) — the fail-closed default.

  This is the runtime arm of the contract; the construction-time arm
  is `validate-policy-opts!` (called by the Ring host adapter so
  misconfigured deployments fail at boot)."
  [app-db {:keys [payload] :as opts}]
  (cond
    (valid-allowlist? payload)
    (select-keys app-db payload)

    (valid-policy-keyword? payload)
    app-db

    :else
    ;; Re-use the construction-time validator's throw so the runtime
    ;; arm and the construction arm produce the same structured error
    ;; shape — tests can assert on one keyword and cover both surfaces.
    (validate-policy-opts! opts)))

;; ---- runtime-db projection (EP-0001 rf2-30kzz2) --------------------------
;;
;; Per Spec 011 §The hydration payload (`:rf/runtime-db`) + §Off-box redaction
;; (Mike ruling #13): the optional `:rf/runtime-db` payload slice carries ONLY
;; the SERIALIZABLE DURABLE runtime-db facts the client needs to reconstitute
;; a coherent frame-state — machine snapshots / spawn registry, the active
;; route slice, elision declarations, and SSR hydration metadata. Transient
;; runtime state MUST NOT ride the wire: server-only request/response
;; accumulators, head snapshots, streaming continuation registries,
;; pending-error buffers, in-flight HTTP handles, host handles, and the
;; client-local scroll-position / nav-token caches (per [002 §Durable vs
;; transient]).
;;
;; The projection is an ALLOWLIST by subsystem child — symmetric to the
;; `:rf/app-db` policy's fail-closed allowlist posture (a new transient
;; sub-key under a shipped subsystem does NOT silently leak). Routing ships
;; only the durable `:current` slice; the scroll-position / nav-counter
;; siblings stay client-local. Absent / nil runtime-db projects to nil (the
;; client-only / no-server-runtime fallback) so `build-payload` omits the
;; optional key.

(def ^:private durable-routing-keys
  "The durable routing-runtime keys that ride the hydration payload. Only
  `:current` (the active route slice) is durable + needed client-side; the
  `:scroll-positions` / `:scroll-positions-order` / `:nav-token-counter` /
  `:pending-nav-counter` / `:pending-navigation` siblings are transient
  client-local caches (per [002 §Durable vs transient]) and stay off the wire."
  [:current])

(defn project-runtime-db
  "Project a frame's runtime-db value to the SERIALIZABLE durable slice that
  rides the `:rf/hydration-payload`'s optional `:rf/runtime-db` key (EP-0001
  rf2-30kzz2, Mike ruling #13). Allowlist-shaped per subsystem child:

    - `:rf.runtime/machines` — shipped whole (snapshots + spawn registry are
      durable serializable facts the client re-materialises actors from);
    - `:rf.runtime/routing`  — only the durable `:current` route slice
      (scroll / nav-token caches are transient client-local state);
    - `:rf.runtime/elision`  — the wire-elision declaration registry;
    - `:rf.runtime/ssr`       — the SSR hydration metadata.

  Returns nil for a nil / empty runtime-db OR when no durable subsystem fact
  is present, so `build-payload` omits the optional `:rf/runtime-db` key
  (the client-only / no-server-runtime fallback). nil-pruned: a subsystem
  whose durable slice is absent contributes no key."
  [runtime-db]
  (when (map? runtime-db)
    (let [slice (cond-> {}
                  (contains? runtime-db :rf.runtime/machines)
                  (assoc :rf.runtime/machines (:rf.runtime/machines runtime-db))

                  (seq (select-keys (:rf.runtime/routing runtime-db) durable-routing-keys))
                  (assoc :rf.runtime/routing
                         (select-keys (:rf.runtime/routing runtime-db) durable-routing-keys))

                  (contains? runtime-db :rf.runtime/elision)
                  (assoc :rf.runtime/elision (:rf.runtime/elision runtime-db))

                  (contains? runtime-db :rf.runtime/ssr)
                  (assoc :rf.runtime/ssr (:rf.runtime/ssr runtime-db)))]
      (when (seq slice) slice))))

;; ---- version resolution + payload assembly -------------------------------
;;
;; Single home for the hydration-payload `:rf/version` resolution and the
;; canonical four-key payload map, shared verbatim by the streaming and
;; non-streaming SSR paths (rf2-8wrzz.4 — previously duplicated in
;; `re-frame.ssr.ring.payload` and `re-frame.ssr.streaming`).

(def ^:private default-pattern-protocol-version
  "The v1 pattern-protocol version stamp (per Spec-Schemas
  §`:rf/hydration-payload` — \"integer; v1 = 1\"). Terminal fallback in
  `resolve-version` so the canonical `:rf/version` key is always present
  (Malli `:int` slot, not `:optional`)."
  1)

(defn- coerce-version
  "Coerce a candidate `:rf/version` source value to the canonical INTEGER
  pattern-protocol version (per Spec-Schemas §`:rf/hydration-payload` —
  `:rf/version` is `:int`, explicitly NOT a semver-style string; rf2-g00l2t).

  Returns:
    - the integer itself when `v` is already an int;
    - the parsed integer when `v` is a whole-number-valued string
      (`\"7\"` → 7) — a tolerant coercion for hosts that stamp the
      version as a string of digits;
    - `nil` when `v` is nil (the source is simply absent), OR when `v`
      is a non-integer / non-numeric-string value (a semver `\"1.0.0\"`,
      a float, a keyword) — REJECTED so the caller falls through to the
      next source rather than shipping a schema-violating `:rf/version`.

  A rejection (non-nil, non-coercible) emits a `:rf.ssr/invalid-version`
  warning trace so the drift surfaces in dev/CI rather than silently
  defaulting."
  [v]
  (cond
    (nil? v) nil

    (int? v) v

    (and (string? v) (re-matches #"\d+" v))
    #?(:clj  (Long/parseLong v)
       :cljs (js/parseInt v 10))

    :else
    (do
      ;; `trace/emit!` self-gates on `interop/debug-enabled?` (production
      ;; CLJS bundles DCE the body), so no outer guard is needed here.
      (trace/emit! :warning :rf.ssr/invalid-version
                   {:value    v
                    :reason   (str "Hydration :rf/version must be an integer "
                                   "pattern-protocol version (per Spec-Schemas "
                                   "§:rf/hydration-payload), not "
                                   (pr-str (type v)) " " (pr-str v)
                                   "; rejected — falling back to the next "
                                   "version source.")
                    :recovery :rejected-and-fell-back})
      nil)))

(defn resolve-version
  "Pick the canonical INTEGER `:rf/version` value to ship in the hydration
  payload (per Spec-Schemas §`:rf/hydration-payload` — `:rf/version` is
  `:int`, NOT a semver string). Each candidate source is coerced/validated
  via `coerce-version`: an int is taken verbatim, a whole-number string is
  parsed, and any other value (semver string, float, keyword) is REJECTED
  (with a `:rf.ssr/invalid-version` warning) so resolution falls through to
  the next source rather than shipping a schema-violating value (rf2-g00l2t).

  The resolution order is:

    1. An explicit `:version` opt from the caller (host-supplied stamp;
       wins so test fixtures and apps that ship their own version source
       stay in control) — when it coerces to an integer.
    2. The framework-global `:rf2/runtime-version` late-bind hook — the
       same source the client-side `:rf.ssr/check-version` fx reads — when
       it coerces to an integer. When the host registers this hook at boot,
       both sides of the wire pin the same value with no further wiring
       (per Spec 011 §The hydration payload + §fx-input shape + Conventions
       §Late-bind hook key grammar).
    3. `default-pattern-protocol-version` (v1 = 1) — the canonical schema
       slot is required (per Spec-Schemas §`:rf/hydration-payload`), so a
       terminal integer fallback is structurally necessary. Used when no
       source supplied a coercible integer (absent, or all rejected).

  Both sides of the wire read `:version` through the same hook so a host
  that doesn't pass `:version` explicitly still pins a value the client
  agrees with (the inline `(or version 1)` it replaced silently defeated
  the version-mismatch check; rf2-asmj1 S8 / rf2-l8fi6 non-streaming,
  rf2-via0g streaming)."
  [explicit-version]
  (or (coerce-version explicit-version)
      (coerce-version (when-let [f (late-bind/get-fn :rf2/runtime-version)]
                        (f)))
      default-pattern-protocol-version))

(defn build-payload
  "Assemble the canonical `:rf/hydration-payload` map per Spec 011 §The
  hydration payload — the four canonical keys (`:rf/version`,
  `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`) plus the optional
  `:rf/runtime-db` and `:rf/schema-digest`.

  The `:rf/app-db` slice is the already-projected `db-slice` — callers
  run `apply-policy` (the fail-closed allowlist / whole-app-db contract,
  rf2-gtgf9) and hand the result here. `:rf/version` is resolved via
  `resolve-version` (caller's `:version` opt → `:rf2/runtime-version`
  hook → v1 stamp). Schema-digest is supplied by the caller when their
  app participates in the schema-digest check; nil otherwise.

  EP-0001 (rf2-30kzz2): the optional `:runtime-db` opt carries the
  already-projected SERIALIZABLE runtime-db slice (callers run
  `project-runtime-db` on the frame's runtime-db value and hand the result
  here). When non-nil it rides the payload as `:rf/runtime-db` so the client
  `:rf/hydrate` handler installs a coherent frame-state (app-db + runtime-db);
  nil omits the optional key (the client-only / no-server-runtime shape).

  Shared verbatim by both SSR paths (rf2-8wrzz.4): the non-streaming
  `re-frame.ssr.ring.payload/build-payload` and the streaming
  `re-frame.ssr.streaming/build-final-payload`, which differ only in how
  they source `app-db` + runtime-db before projecting them."
  [frame-id db-slice render-hash {:keys [version schema-digest runtime-db]}]
  (cond-> {:rf/version     (resolve-version version)
           :rf/frame-id    frame-id
           :rf/app-db      db-slice
           :rf/render-hash render-hash}
    (some? runtime-db) (assoc :rf/runtime-db runtime-db)
    schema-digest      (assoc :rf/schema-digest schema-digest)))
