(ns re-frame.ssr.server-fx-schemas
  "Malli args schemas for the standard `:rf.server/*` server-side fx.
  Per Spec 011 §HTTP response contract §Standard fx and [Spec-Schemas
  §Standard fx args schemas]. These are the `:rf.fx.server/*-args`
  schemas the spec names as 'registered' plus the `:rf.server/cookie`
  shape they build on.

  The schemas here are **plain Malli EDN forms** — vectors + keywords,
  no Malli code dependency. The ssr artefact does NOT depend on Malli /
  the schemas artefact in production (Spec 006 §Adapter shipping
  convention — schemas is an optional sibling). The `:schema` value an
  `reg-fx` carries is consumed by whatever validator the (optional)
  schemas artefact has installed — `re-frame.schemas.validator` routes
  it through `malli.core/validate` by default (per Spec 010 §Validation
  order step 5). When schemas / Malli is not on the classpath the
  fx-args validation soft-passes (per Spec 010 §Recommended soft-pass);
  these vars are inert EDN that still travels with the registration so
  the `:schema` boundary EXISTS the moment a validator is present.

  Attaching these schemas to the registrations makes malformed arguments
  surface at the `:fx-args` boundary while the dispatching event is still in
  scope, before response state is mutated.

  Spec ↔ var map (per [Spec-Schemas §Standard fx args schemas]):

    :rf.server/cookie                  — `cookie`
    :rf.fx.server/set-status-args      — `set-status-args`
    :rf.fx.server/set-header-args      — `set-header-args`
    :rf.fx.server/append-header-args   — `append-header-args`
    :rf.fx.server/set-cookie-args      — `set-cookie-args`
    :rf.fx.server/delete-cookie-args   — `delete-cookie-args`
    :rf.fx.server/redirect-args        — `redirect-args`

  These are SHAPE checks — the structural contract on the fx args. The
  per-attribute CRLF / token-grammar gates in `re-frame.ssr.response`
  (`:rf.error/header-invalid-value`, `:rf.error/cookie-invalid-attribute`, …)
  are a SEPARATE, complementary defence layer that the args schema
  cannot express (a CRLF-bearing string is still a `:string`). Spec 011
  §CRLF fail-fast: 'args validation surfaces the [structural] bug at
  the dispatch site; … the fail-fast posture composes cleanly with the
  structured-fx-args schemas'.

  ## What runs when (rf2-dtpfv)

  These schemas are a DEV-POSTURE boundary and always were: `validate-fx!`
  is `(if interop/debug-enabled? (run-validation …) true)`, read once at
  namespace-load time, so under `-Dre-frame.debug=false` the Spec 010
  §Validation order step-5 gate does not run at all and its documented
  `:skipped` recovery never happens. That is intended for USER fx —
  trust-the-programmer, no hot-path cost — but it left the framework's own
  wire-adjacent contract unguarded: a malformed reserved fx RAN and its args
  landed on the response accumulator `ssr/get-response` publishes.

  So the SHAPE contract is now enforced twice, deliberately, with a
  different job each time:

  - **Here, in dev** — the Malli boundary rejects BEFORE the handler runs,
    the fx is `:skipped`, siblings continue, the page still renders, and the
    programmer gets the rich `:rf.error/schema-validation-failure :where
    :fx-args` diagnostic naming the failing path.
  - **In `re-frame.ssr.response`, in EVERY build** — cheap predicate guards
    (`validate-status!`, `validate-string-arg!`, `validate-cookie-shape!`)
    throw `:rf.error/server-fx-args-invalid` before the first
    `swap-response!`, so the accumulator is never mutated in a release
    build either. That is the only half a production JVM has.

  The two must agree on the canonical cases, and nothing but discipline
  keeps EDN in this file aligned with predicates in another. So they don't
  rely on discipline: `ssr-reserved-fx-guards-test` drives ONE acceptance
  corpus through both, and a widened schema that the guard still rejects
  (or the reverse) fails there.

  ## An optional key present with `nil` is absent

  Every OPTIONAL slot below is `[:maybe …]`, because `{:path nil}` and `{}` say
  the same thing in Clojure and the always-on guards read them that way — a
  cookie assembled from an options map (`{:secure (:secure? opts)}`) writes the
  first while meaning the second. A bare `[:path {:optional true} :string]`
  says something different: absent is fine, present-and-nil is a violation. The
  audit of the first fix measured what that gap did — `{:secure nil}` skipped
  in dev, persisted in production — which is the same posture-dependent
  accumulator this whole gate exists to close, just one key deep. REQUIRED
  slots keep the bare type: `{:value nil}` is a cookie with no value, and `nil`
  is not a status.")

;; ---- :rf.server/cookie — the structured-cookie shape ---------------------
;;
;; Per Spec 011 §Cookie shape + [Spec-Schemas §`:rf.server/cookie`].
;; Either :max-age or :expires may be supplied (or neither — session
;; cookie); both are optional. :expires is ms-since-epoch (an :int).

(def cookie
  "Malli schema for `:rf.server/cookie` — the structured cookie map
  `:rf.server/set-cookie` / `:rf.server/delete-cookie` produce. Per
  Spec 011 §Cookie shape / [Spec-Schemas §`:rf.server/cookie`].

  Ingress tolerance differs from the canonical wire shape. The `:max-age`,
  `:expires`, and `:same-site` attrs carry a
  `[:or <canonical-type> :string]` shape rather than the bare canonical
  type, because `re-frame.ssr.response/validate-cookie!` accepts and
  `str`-coerces non-canonical scalar
  forms for these three attrs — a string `:max-age`, a string/instant
  `:expires`, a string `:same-site` (\"Strict\"/\"Lax\") — because apps
  build cookie attrs from host-data that often arrives as strings, and the
  per-attribute CRLF gate must SEE those strings to reject a forged
  `\"3600\\r\\nSet-Cookie: admin=1\"` payload at the fx boundary. The
  ssr CRLF tests (`ssr-set-cookie-crlf-checks-every-attribute`) and the
  clean-attributes control (`ssr-set-cookie-clean-attributes-still-
  accepted`, which passes `:same-site \"Strict\"` + a string `:expires`)
  lock that tolerance in.

  A schema that enforced strict canonical types would reject those string
  forms at the Spec 010 §step-5 boundary BEFORE the CRLF gate runs —
  changing the surfaced error from the specific
  `:rf.error/cookie-invalid-attribute` to a generic
  `:rf.error/schema-validation-failure`, and
  rejecting the legitimately-tolerated string `:same-site`/`:expires`. So
  the three coercible attrs widen to `[:or <canonical-type> :string]`: the
  canonical type is documented + validated, AND the deliberately-tolerated
  string form passes the shape gate so it reaches the CRLF defence.
  `:secure` / `:http-only` / `:name` / `:value` / `:path` / `:domain` carry
  their canonical type and nothing else (the optional ones inside the
  `[:maybe …]` every optional slot carries — see the ns docstring). `:name` in
  particular is a `:string`: `re-frame.ssr.response/validate-cookie!` refuses a
  keyword / symbol name in every build, so what an adapter reads off
  `(:cookies response)` is the type published here. This is ingress
  CR/LF-inspection tolerance,
  NOT a serialisability promise: the string form is tolerated at the fx
  ingress but is not a generally-valid canonical shape — `:expires`, in
  particular, MUST be an epoch-millis int at the host boundary (the Ring
  adapter throws `:rf.error/cookie-invalid-expires` on a non-integer
  `:expires`), so a string `:expires` fails at head materialisation by
  design."
  [:map
   [:name      :string]
   [:value     :string]
   [:max-age   {:optional true} [:maybe [:or :int :string]]]    ;; spec: :int (see divergence note)
   [:expires   {:optional true} [:maybe [:or :int :string]]]    ;; spec: :int ms-since-epoch (see divergence note)
   [:secure    {:optional true} [:maybe :boolean]]
   [:http-only {:optional true} [:maybe :boolean]]
   [:same-site {:optional true} [:maybe [:or [:enum :strict :lax :none] :string]]] ;; spec: enum only (see divergence note)
   [:path      {:optional true} [:maybe :string]]
   [:domain    {:optional true} [:maybe :string]]])

;; ---- :rf.fx.server/*-args — the standard fx args schemas -----------------
;;
;; Per Spec 011 §HTTP response contract §Standard fx (line 438) +
;; [Spec-Schemas §Standard fx args schemas].

(def http-status
  "An HTTP status code — an int in the RFC 9110 §15 status-line range
  100–599 (rf2-dtpfv). Named once and shared by every `:status` slot below
  so the three registrations cannot drift from each other, or from
  `re-frame.ssr.response/validate-status!` — the always-on guard that
  enforces the SAME range in a release build, where this schema does not
  run at all (Spec 010 §Validation order step 5 is dev-posture for user fx;
  the reserved `:rf.server/*` family guards its own args unconditionally).
  `ssr-reserved-fx-guards-test`'s acceptance corpus drives the schema and
  the guard from one literal, so a widened range cannot land on one side
  only."
  [:int {:min 100 :max 599}])

(def set-status-args
  "Args of `:rf.server/set-status` — `:rf.fx.server/set-status-args`.
  An HTTP status code int (e.g. 200 / 404 / 500), bounded to 100–599: the
  status line admits nothing else, and a bare `:int` let `0` / `-1` /
  `99999` through the dev boundary that exists to catch exactly that
  (rf2-dtpfv)."
  http-status)

(def set-header-args
  "Args of `:rf.server/set-header` — `:rf.fx.server/set-header-args`.
  `{:name <string> :value <string>}` (replaces a same-name header,
  case-insensitive)."
  [:map
   [:name  :string]
   [:value :string]])

(def append-header-args
  "Args of `:rf.server/append-header` — `:rf.fx.server/append-header-args`.
  `{:name <string> :value <string>}` (appends another instance —
  Set-Cookie-style multi-valued headers)."
  [:map
   [:name  :string]
   [:value :string]])

(def set-cookie-args
  "Args of `:rf.server/set-cookie` — `:rf.fx.server/set-cookie-args`.
  Per [Spec-Schemas] this is `[:ref :rf.server/cookie]` — the
  `:rf.server/cookie` shape. The framework keeps no global Malli
  registry, so the ref is made self-contained via a local `:registry`
  schema property: the validator resolves `:rf.server/cookie` from the
  inline registry, validating exactly the `cookie` shape above without
  any external registry binding."
  [:schema {:registry {:rf.server/cookie cookie}}
   [:ref :rf.server/cookie]])

(def delete-cookie-args
  "Args of `:rf.server/delete-cookie` — `:rf.fx.server/delete-cookie-args`.
  `{:name <string> :path? <string> :domain? <string>}` — clear a named
  cookie at a path/domain."
  [:map
   [:name   :string]
   [:path   {:optional true} [:maybe :string]]
   [:domain {:optional true} [:maybe :string]]])

(def redirect-args
  "Args of `:rf.server/redirect` — `:rf.fx.server/redirect-args`.
  `{:status? <int> :location <string>}` — set status (default 302) and
  the redirect target. The CRLF / open-redirect gates live in
  `re-frame.ssr.response`; this is the structural shape check.

  [Spec-Schemas §`:rf.fx.server/redirect-args`]'s `RedirectFxArgs`, Spec
  011 §Standard fx, and `re-frame.ssr.response/redirect-fx` all use
  `:location`, matching HTTP `Location` response-header vocabulary. The
  unsupported synonyms `:url` / `:to` are
  NOT accepted: `redirect-fx` throws `:rf.error/redirect-retired-target-
  key` naming `:location` (no back-compat alias). The schema accepts the
  optional `:string` `:location` plus the optional `:int` `:status`.

  `:location` is optional, and a redirect with no `:location` passes this
  shape gate because it is not a structural error. A
  target-less redirect is the established
  `:rf.ssr/ssr-redirect-no-target` graceful-degradation path: the
  redirect-fx accepts it (location is caller-trusted/optional at the fx
  boundary) and the adapter emits the warning trace + a 3xx with no
  Location header so the defect is observable rather than silently
  shipping a broken redirect. A `[:fn]` clause requiring a target key
  here would 400 the no-target redirect at the Spec 010 §step-5 boundary
  before that warn→302 path runs, contradicting the graceful-degradation
  contract. The schema therefore
  stays a pure shape check (key types only) and lets the no-target case
  fall through to the runtime's warning path."
  [:map
   [:status   {:optional true} [:maybe http-status]]  ;; default 302
   [:location {:optional true} [:maybe :string]]])    ;; canonical and only target key

(def safe-redirect-args
  "Args of `:rf.server/safe-redirect` — `:rf.fx.server/safe-redirect-args`.
  `{:location <string> :status? <int> :relative-only? <boolean>
    :allow? [<string> …]}` — the caller-untrusted redirect. `:location` is
  the validation target, so unlike
  the caller-trusted `:rf.server/redirect` (which has a documented no-
  target graceful path) safe-redirect requires a `:location`
  to validate — a target-less safe-redirect is a programmer error with no
  defensible interpretation. The five-step URL-parse / scheme / relative-
  only? / allowlist gate lives in `re-frame.ssr.response/safe-redirect-fx`
  and emits the specific `:rf.error/safe-redirect-*` categories; this is
  the structural SHAPE check, completing the `:schema` boundary the other
  six `:rf.server/*` fxs already carry. A malformed argument therefore
  fails before the response accumulator is mutated.

  Only
  `:location` is required; `:status` / `:relative-only?` / `:allow` are
  optional. `:allow` is a SEQUENCE of host strings (the fx reads `:allow`,
  not a scalar `:allowlist`). Closed map is avoided — extra keys pass —
  so the shape gate never over-constrains a legitimate call."
  [:map
   [:location       :string]
   [:status         {:optional true} [:maybe http-status]]     ;; default 302
   [:relative-only? {:optional true} [:maybe :boolean]]
   [:allow          {:optional true} [:maybe [:sequential :string]]]])
