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

  Why this closes a gap (rf2-kjf3m.2): Spec 011 §Standard fx (line 438)
  and §Cookie shape (line 496) both assert these schemas are
  *registered* and that 'args validation runs as part of the standard
  `:schema` boundary check'. Pre-rf2-kjf3m.2 the six `:rf.server/*`
  `reg-fx` calls carried only `:doc` + `:platforms` — no `:schema` — so
  the Spec 010 §step-5 fx-args boundary never ran for them. A
  `(rf/dispatch [:rf.server/set-status \"nope\"])` assoc'd the string
  straight onto the response `:status` and emitted a non-integer status
  on the wire. With these schemas attached the malformed arg surfaces
  as `:rf.error/schema-validation-failure :where :fx-args` at the
  dispatch site, with the event in scope — exactly what the spec
  promises.

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
  (`:rf.error/header-invalid-value`, `:rf.error/cookie-invalid-*`, …)
  are a SEPARATE, complementary defence layer that the args schema
  cannot express (a CRLF-bearing string is still a `:string`). Spec 011
  §CRLF fail-fast: 'args validation surfaces the [structural] bug at
  the dispatch site; … the fail-fast posture composes cleanly with the
  structured-fx-args schemas'.")

;; ---- :rf.server/cookie — the structured-cookie shape ---------------------
;;
;; Per Spec 011 §Cookie shape + [Spec-Schemas §`:rf.server/cookie`].
;; Either :max-age or :expires may be supplied (or neither — session
;; cookie); both are optional. :expires is ms-since-epoch (an :int).

(def cookie
  "Malli schema for `:rf.server/cookie` — the structured cookie map
  `:rf.server/set-cookie` / `:rf.server/delete-cookie` produce. Per
  Spec 011 §Cookie shape / [Spec-Schemas §`:rf.server/cookie`].

  KNOWN DIVERGENCE from [Spec-Schemas §`:rf.server/cookie`] (filed for
  Mike's ruling — rf2-kjf3m.2 follow-up). Spec-Schemas declares the
  canonical types strictly:

      :max-age   :int
      :expires   :int                         ;; ms-since-epoch
      :same-site [:enum :strict :lax :none]

  …but `re-frame.ssr.response/validate-cookie!` (rf2-kjf3m.1 / rf2-z7gor)
  DELIBERATELY accepts and `str`-coerces non-canonical scalar forms for
  these three attrs — a string `:max-age`, a string/instant `:expires`,
  a string `:same-site` (\"Strict\"/\"Lax\") — because apps build cookie
  attrs from host-data that often arrives as strings, and the
  per-attribute CRLF gate must SEE those strings to reject a forged
  `\"3600\\r\\nSet-Cookie: admin=1\"` payload at the fx boundary. The
  ssr CRLF tests (`ssr-set-cookie-crlf-checks-every-attribute`) and the
  clean-attributes control (`ssr-set-cookie-clean-attributes-still-
  accepted`, which passes `:same-site \"Strict\"` + a string `:expires`)
  lock that tolerance in.

  A schema that enforced Spec-Schemas' strict types would reject those
  string forms at the Spec 010 §step-5 boundary BEFORE the CRLF gate runs
  — changing the surfaced error from the specific `:rf.error/cookie-
  invalid-<attr>` to a generic `:rf.error/schema-validation-failure`, and
  rejecting the legitimately-tolerated string `:same-site`/`:expires`. So
  the three coercible attrs widen to `[:or <spec-type> :string]` here: the
  canonical spec type is documented + validated, AND the deliberately-
  tolerated string form passes the shape gate so it reaches the CRLF
  defence. `:secure` / `:http-only` / `:name` / `:value` / `:path` /
  `:domain` match Spec-Schemas exactly. The divergence (tighten the fx to
  the spec's strict cookie schema, OR amend Spec-Schemas to bless the
  string forms) is Mike's call — see the follow-up decision bead."
  [:map
   [:name      :string]
   [:value     :string]
   [:max-age   {:optional true} [:or :int :string]]    ;; spec: :int (see divergence note)
   [:expires   {:optional true} [:or :int :string]]    ;; spec: :int ms-since-epoch (see divergence note)
   [:secure    {:optional true} :boolean]
   [:http-only {:optional true} :boolean]
   [:same-site {:optional true} [:or [:enum :strict :lax :none] :string]] ;; spec: enum only (see divergence note)
   [:path      {:optional true} :string]
   [:domain    {:optional true} :string]])

;; ---- :rf.fx.server/*-args — the standard fx args schemas -----------------
;;
;; Per Spec 011 §HTTP response contract §Standard fx (line 438) +
;; [Spec-Schemas §Standard fx args schemas].

(def set-status-args
  "Args of `:rf.server/set-status` — `:rf.fx.server/set-status-args`.
  An HTTP status code int (e.g. 200 / 404 / 500)."
  :int)

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
   [:path   {:optional true} :string]
   [:domain {:optional true} :string]])

(def redirect-args
  "Args of `:rf.server/redirect` — `:rf.fx.server/redirect-args`.
  `{:status? <int> :location <string>}` — set status (default 302) and
  the redirect target. The CRLF / open-redirect gates live in
  `re-frame.ssr.response`; this is the structural shape check.

  [Spec-Schemas]'s `RedirectFxArgs` names only `:location`, but Spec 011
  §Standard fx (line 435) documents the target as `:location` **or
  `:url`/`:to`**, and `re-frame.ssr.response/redirect-fx` reads all
  three (`(or :location :url :to)`). The schema must not be narrower
  than the documented + implemented fx contract, so it accepts any ONE
  of the three target keys (a `:string`), each optional, plus the
  optional `:int` `:status`. The fx handler resolves precedence
  (`:location` wins, then `:url`, then `:to`).

  PERMISSIVE on the no-target case (rf2-ee38b.11 contract, the live half
  of decision rf2-cwfy2). All three target keys are optional AND a
  redirect with ZERO target keys PASSES this shape gate — it is not a
  structural error. A target-less redirect is the established
  `:rf.ssr/ssr-redirect-no-target` graceful-degradation path: the
  redirect-fx accepts it (location is caller-trusted/optional at the fx
  boundary) and the adapter emits the warning trace + a 3xx with no
  Location header so the defect is observable rather than silently
  shipping a broken redirect. A `[:fn]` clause requiring a target key
  here would 400 the no-target redirect at the Spec 010 §step-5 boundary
  BEFORE that warn→302 path runs, contradicting ee38b.11 — so the schema
  stays a pure shape check (key types only) and lets the no-target case
  fall through to the runtime's warning path."
  [:map
   [:status   {:optional true} :int]      ;; default 302
   [:location {:optional true} :string]
   [:url      {:optional true} :string]
   [:to       {:optional true} :string]])
