(ns re-frame.ssr.server-fx-schemas
  "Malli args schemas for the standard `:rf.server/*` server-side fx: the
  published description of each args shape, for docs and tools. Per Spec
  011 §HTTP response contract §Standard fx and [Spec-Schemas §Standard fx
  args schemas]. These are the `:rf.fx.server/*-args` schemas the spec
  names plus the `:rf.server/cookie` shape they build on.

  The schemas here are **plain Malli EDN forms** — vectors + keywords,
  no Malli code dependency — so the ssr artefact ships them without
  depending on Malli or the schemas artefact (Spec 006 §Adapter shipping
  convention — schemas is an optional sibling).

  Spec ↔ var map (per [Spec-Schemas §Standard fx args schemas]):

    :rf.server/cookie                  — `cookie`
    :rf.fx.server/set-status-args      — `set-status-args`
    :rf.fx.server/set-header-args      — `set-header-args`
    :rf.fx.server/append-header-args   — `append-header-args`
    :rf.fx.server/set-cookie-args      — `set-cookie-args`
    :rf.fx.server/delete-cookie-args   — `delete-cookie-args`
    :rf.fx.server/redirect-args        — `redirect-args`

  ## What checks the args

  None of the six registrations carries these as its `:schema`. The one
  acceptance check, in every build, is the guards in `re-frame.ssr.response`
  (`validate-args-map!`, `validate-status!`, `validate-string-arg!` and
  `validate-cookie-shape!` over the `cookie-attr-shape` table), which throw
  `:rf.error/server-fx-args-invalid` before the first `swap-response!`, so a
  malformed reserved fx never mutates the accumulator `ssr/get-response`
  publishes. The same file holds the per-attribute CR/LF/NUL and
  token-grammar gates (`:rf.error/header-invalid-value`,
  `:rf.error/cookie-invalid-attribute`, …), which no args schema could
  express: a CRLF-bearing string is still a `:string`. Spec 011 §The
  reserved fx guard their own args.

  ## An optional key present with `nil` is absent

  Every OPTIONAL slot below is `[:maybe …]`, because `{:path nil}` and `{}` say
  the same thing in Clojure and the guards read them that way — a cookie
  assembled from an options map (`{:secure (:secure? opts)}`) writes the first
  while meaning the second. A bare `[:path {:optional true} :string]` would
  describe something the guards do not accept: absent is fine, present-and-nil
  a violation. REQUIRED slots keep the bare type: `{:value nil}` is a cookie
  with no value, and `nil` is not a status.")

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

  So the three coercible attrs read `[:or <canonical-type> :string]`: the
  schema describes what the guard accepts, naming the canonical type beside
  the deliberately-tolerated string form.
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
  100–599, the range `re-frame.ssr.response/validate-status!` enforces.
  Named once and shared by every `:status` slot below so they cannot drift
  from each other."
  [:int {:min 100 :max 599}])

(def set-status-args
  "Args of `:rf.server/set-status` — `:rf.fx.server/set-status-args`.
  An HTTP status code int (e.g. 200 / 404 / 500), bounded to 100–599
  because the status line admits nothing else."
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
  `{:location? <string> :allow? (:any | [<string> …]) :status? <int>}` — the
  redirect target, the hosts it may name beyond the request's own origin, and
  the status (default 302). The shape guard and the CR/LF/NUL and URL gates
  live in `re-frame.ssr.response/redirect-fx`; this describes the shape.

  [Spec-Schemas §`:rf.fx.server/redirect-args`]'s `RedirectFxArgs`, Spec
  011 §Standard fx, and `re-frame.ssr.response/redirect-fx` all use
  `:location`, matching HTTP `Location` response-header vocabulary. The
  unsupported synonyms `:url` / `:to` are
  NOT accepted: `redirect-fx` throws `:rf.error/redirect-retired-target-
  key` naming `:location` (no back-compat alias).

  `:allow` is `:any` or a SEQUENCE of host strings; a scalar host string is
  refused rather than read as an allowlist of characters. Absent, the
  redirect is relative-only. `:relative-only?` names that default, so the
  key is refused rather than read: the fx's own guard refuses a present
  non-nil value.

  `:location` is optional, because a redirect with no `:location` is not a
  structural error. A target-less redirect is the
  `:rf.ssr/ssr-redirect-no-target` graceful-degradation path: the
  redirect-fx accepts it and the adapter emits the warning trace + a 3xx
  with no Location header so the defect is observable rather than silently
  shipping a broken redirect. The map is open — extra keys pass — so the
  description never over-constrains a legitimate call."
  [:map
   [:status         {:optional true} [:maybe http-status]]  ;; default 302
   [:location       {:optional true} [:maybe :string]]      ;; canonical and only target key
   [:allow          {:optional true} [:maybe [:or [:= :any] [:sequential :string]]]]
   [:relative-only? {:optional true} :nil]])
