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
      sequential coll). Other keys are dropped — including any keys added
      later as the app evolves. The recommended primary mechanism.

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
  (:require [re-frame.late-bind :as late-bind]))

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
  "An allowlist is a non-empty sequential coll of top-level keys — the
  vector shape of the `:payload` opt."
  [x]
  (and (sequential? x) (seq x)))

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

    - `:payload [<kws>]` (non-empty sequential) → OK (allowlist)
    - `:payload :rf.ssr.payload/whole-app-db`   → OK (whole-app-db opt-in)
    - `:payload <other keyword>`  → `:rf.error/ssr-unknown-payload-policy`
      (a typo'd policy keyword, e.g. `:rf.ssr.payload/whole-db`, surfaced
      distinctly so it doesn't silently land in the missing bucket)
    - `:payload` absent / empty `[]` / nil / any other non-allowlist
      non-keyword → `:rf.error/ssr-missing-payload-policy` (fail-closed)

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly."
  [{:keys [payload] :as opts}]
  (cond
    (valid-allowlist? payload)
    opts

    (valid-policy-keyword? payload)
    opts

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

    - `:payload [<kws>]` (allowlist, non-empty sequential) → `select-keys`.
    - `:payload :rf.ssr.payload/whole-app-db` → ships `app-db` verbatim.
    - Absence / malformed → throws (`:rf.error/ssr-missing-payload-policy`
      or `:rf.error/ssr-unknown-payload-policy`) — the fail-closed default.

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

(defn resolve-version
  "Pick the `:rf/version` value to ship in the hydration payload. The
  resolution order is:

    1. An explicit `:version` opt from the caller (host-supplied stamp;
       wins so test fixtures and apps that ship their own version source
       stay in control).
    2. The framework-global `:rf2/runtime-version` late-bind hook — the
       same source the client-side `:rf.ssr/check-version` fx reads. When
       the host registers this hook at boot, both sides of the wire pin
       the same value with no further wiring (per Spec 011 §The hydration
       payload + §fx-input shape + Conventions §Late-bind hook key
       grammar).
    3. `default-pattern-protocol-version` (v1 = 1) — the canonical schema
       slot is required (per Spec-Schemas §`:rf/hydration-payload`), so a
       terminal numeric fallback is structurally necessary. The
       check-version fx still no-ops cleanly on the client when neither
       side has the hook registered (matched value → no mismatch).

  Both sides of the wire read `:version` through the same hook so a host
  that doesn't pass `:version` explicitly still pins a value the client
  agrees with (the inline `(or version 1)` it replaced silently defeated
  the version-mismatch check; rf2-asmj1 S8 / rf2-l8fi6 non-streaming,
  rf2-via0g streaming)."
  [explicit-version]
  (or explicit-version
      (when-let [f (late-bind/get-fn :rf2/runtime-version)]
        (f))
      default-pattern-protocol-version))

(defn build-payload
  "Assemble the canonical `:rf/hydration-payload` map per Spec 011 §The
  hydration payload — the four canonical keys (`:rf/version`,
  `:rf/frame-id`, `:rf/app-db`, `:rf/render-hash`) plus the optional
  `:rf/schema-digest`.

  The `:rf/app-db` slice is the already-projected `db-slice` — callers
  run `apply-policy` (the fail-closed allowlist / whole-app-db contract,
  rf2-gtgf9) and hand the result here. `:rf/version` is resolved via
  `resolve-version` (caller's `:version` opt → `:rf2/runtime-version`
  hook → v1 stamp). Schema-digest is supplied by the caller when their
  app participates in the schema-digest check; nil otherwise.

  Shared verbatim by both SSR paths (rf2-8wrzz.4): the non-streaming
  `re-frame.ssr.ring.payload/build-payload` and the streaming
  `re-frame.ssr.streaming/build-final-payload`, which differ only in how
  they source `app-db` before projecting it."
  [frame-id db-slice render-hash {:keys [version schema-digest]}]
  (cond-> {:rf/version     (resolve-version version)
           :rf/frame-id    frame-id
           :rf/app-db      db-slice
           :rf/render-hash render-hash}
    schema-digest (assoc :rf/schema-digest schema-digest)))
