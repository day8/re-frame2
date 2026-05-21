(ns re-frame.ssr.payload-policy
  "Hydration-payload policy contract — explicit and fail-closed.

  Per Spec 011 §Payload scope (canonical boundary): the
  `:rf/hydration-payload` carries the **bounded** state needed to
  recompute the server's view on the client. Whether to ship a slice or
  the whole `app-db` is a security decision — the wrong default leaks
  internal state (auth tokens stashed for in-flight handlers, internal
  feature flags, server-only working scratch) to every visitor of every
  page.

  Pre-rf2-gtgf9 the payload builder defaulted to `app-db` in toto when
  `:payload-keys` was nil/empty — a fail-OPEN default that made the
  privacy decision implicit. Audits surfaced this as the
  `:rf/response`-accumulator-on-app-db trap (rf2-jbcmt landed the
  side-channel storage substrate to defend against ONE path of the
  same family); rf2-briq0's review surfaced that the underlying
  default was still wrong.

  This namespace makes the policy:

    1. **Explicit** — callers must declare intent.
    2. **Fail-closed** — absence of an opt is a structural error, not
       a license to ship everything.
    3. **Allowlist-shaped** — denylists go stale silently as new keys
       land in `app-db`; an allowlist requires a deliberate edit per
       new wire-bound key. The masterpiece-bar choice for a security
       contract.

  ---- The contract ----

  The policy is one of two shapes:

    `:payload-keys [<kw> <kw> ...]`
      An **allowlist** of top-level `app-db` keys to ship. Other keys
      are dropped — including any keys added later as the app evolves.
      The recommended primary mechanism.

    `:payload-policy :rf.ssr.payload/whole-app-db`
      An explicit opt-in to ship the whole `app-db`. Use only when the
      app's `app-db` is structurally safe to expose end-to-end — e.g.
      a small SPA where every key the server populates is intended
      for the client. The keyword is namespaced under `:rf.ssr.payload/`
      (per Conventions §`:rf/*` reserved namespace) so consumers
      reading the opt see the security weight of the choice.

  Absence of both is a structural error
  (`:rf.error/ssr-missing-payload-policy`) — fail-closed.

  Both shapes may be combined: when `:payload-keys` is present,
  `:payload-policy` is ignored (explicit allowlist wins, and presenting
  both is not a contradiction — the allowlist IS a more-restrictive
  policy choice). Empty `:payload-keys` (`[]`) is treated as **no
  allowlist supplied**, since shipping zero keys is almost certainly
  a programmer error rather than an intent. Callers that genuinely
  want to ship an empty `:rf/app-db` use `:payload-policy
  :rf.ssr.payload/whole-app-db` against an empty `app-db` (i.e. don't
  populate it server-side).

  ---- Where this is consumed ----

  Two payload builders share this contract:

    `re-frame.ssr.ring.payload/build-payload`     — non-streaming SSR
    `re-frame.ssr.streaming/build-final-payload`  — streaming SSR

  The Ring host adapter (`re-frame.ssr.ring/ssr-handler` and
  `stream-handler`) validates the policy at handler-construction time
  via `validate-policy-opts!` so misconfigured deployments fail at
  boot rather than at first request — the canonical fail-closed
  pattern."
  (:refer-clojure :exclude [resolve]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- policy-spec keyword constants ----------------------------------------
;;
;; Both keywords are reserved under the `:rf.ssr.payload/*` namespace
;; (per Conventions §`:rf/*` reserved namespaces — `:rf.ssr/*` is the
;; SSR sub-namespace; `:rf.ssr.payload/*` is the policy slot under it).

(def whole-app-db-policy
  "Opt-in policy keyword for shipping the entire `app-db` in the
  hydration payload. Set as `:payload-policy` on the handler opts."
  :rf.ssr.payload/whole-app-db)

;; ---- policy resolution ---------------------------------------------------

(defn- valid-allowlist?
  "An allowlist is a non-empty sequential coll of top-level keys."
  [x]
  (and (sequential? x) (seq x)))

(defn- valid-policy-keyword?
  "Currently the only explicit-policy keyword recognised is the
  whole-app-db opt-in. New policies (e.g. schema-driven projections)
  would extend this set."
  [x]
  (= x whole-app-db-policy))

(defn validate-policy-opts!
  "Throw a structured `:rf.error/ssr-missing-payload-policy` when the
  caller declared neither `:payload-keys` nor a recognised
  `:payload-policy`. Called at handler-construction time by the Ring
  host adapter so misconfigured deployments fail at boot, not at first
  request.

  Returns `opts` unchanged on success — composes into a `let` /
  threading position cleanly."
  [{:keys [payload-keys payload-policy] :as opts}]
  (cond
    (valid-allowlist? payload-keys)
    opts

    (valid-policy-keyword? payload-policy)
    opts

    ;; Caller passed `:payload-policy` but not the recognised keyword —
    ;; surface as a distinct error so a typo (e.g.
    ;; `:rf.ssr.payload/whole-db`) doesn't silently land in the
    ;; missing-policy bucket.
    (some? payload-policy)
    (throw (ex-info ":rf.error/ssr-unknown-payload-policy"
                    {:rf.error/id    :rf.error/ssr-unknown-payload-policy
                     :where          'rf.ssr/payload-policy
                     :reason         (str "ssr-handler :payload-policy must be "
                                          (pr-str whole-app-db-policy)
                                          " (or omit it and pass :payload-keys instead)")
                     :got            payload-policy
                     :recognised     #{whole-app-db-policy}
                     :recovery       :declare-payload-policy}))

    :else
    (throw (ex-info ":rf.error/ssr-missing-payload-policy"
                    {:rf.error/id :rf.error/ssr-missing-payload-policy
                     :where    'rf.ssr/payload-policy
                     :reason   (str "ssr-handler requires an explicit hydration-"
                                    "payload policy: pass :payload-keys "
                                    "[<top-level-app-db-keys>] (allowlist, "
                                    "preferred) OR :payload-policy "
                                    (pr-str whole-app-db-policy)
                                    " to opt-in to shipping the whole app-db.")
                     :recovery :declare-payload-policy}))))

(defn apply-policy
  "Project `app-db` to the wire slice per the caller's declared policy.
  Returns the slice (a map) — the value that lands on the
  `:rf/hydration-payload`'s `:rf/app-db` key.

  Per the contract:

    - `:payload-keys [<kws>]` (allowlist, recommended) wins when
      present and non-empty.
    - `:payload-policy :rf.ssr.payload/whole-app-db` ships `app-db`
      verbatim.
    - Absence of both throws `:rf.error/ssr-missing-payload-policy`
      — the fail-closed default.

  This is the runtime arm of the contract; the construction-time arm
  is `validate-policy-opts!` (called by the Ring host adapter so
  misconfigured deployments fail at boot)."
  [app-db {:keys [payload-keys payload-policy] :as opts}]
  (cond
    (valid-allowlist? payload-keys)
    (select-keys app-db payload-keys)

    (valid-policy-keyword? payload-policy)
    app-db

    :else
    ;; Re-use the construction-time validator's throw so the runtime
    ;; arm and the construction arm produce the same structured error
    ;; shape — tests can assert on one keyword and cover both surfaces.
    (validate-policy-opts! opts)))
