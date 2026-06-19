(ns re-frame.http.privacy-body
  "HTTP response-body classification — EP-0015 §8 / Spec 015 §HTTP response
  bodies, ruled issue 5 (rf2-ppkh3v). Graduated into
  [`spec/014-HTTPRequests.md` §Privacy — response-body classification].

  ## The model (EP-0015 issue 5, ruled)

  A managed HTTP response body is a **registration-owned transient
  payload**, classified per-slot via `:sensitive?` / `:large?` props on the
  request's `:decode` SCHEMA — the EP-0005 mechanism reused (the `:decode`
  schema lives on the owning call / resource / mutation declaration). This
  is the SAME shared schema-walker hook
  (`:schemas/extract-sensitive-paths-from-schema` /
  `:schemas/extract-large-paths-from-schema`) the resource `:data-schema`
  surface (`re-frame.resources.classification`) and the app-schema elision
  path consume — never a body-private walker.

  Three rules pin the contract:

  1. **Per-slot.** A `:decode` Malli schema marking `[:token {:sensitive?
     true} :string]` redacts the decoded body's `[:token]` slot to the
     `:rf/redacted` sentinel BEFORE the body rides any `:rf.http/*` trace
     event — even when the request was NOT declared per-call `:sensitive?`.
     A slot marked `[:blob {:large? true} :string]` elides to the
     `:rf.size/large-elided` marker instead. Sensitive wins over large at
     the same slot. The owner's schema declaration is the signal.

  2. **Whole-body root prop.** A root-level (`[]`) `:sensitive?` prop on the
     `:decode` schema (e.g. an opaque-token response whose WHOLE body is the
     secret) redacts the whole body. `:large?` at the root elides it to the
     `:rf.size/large-elided` marker.

  3. **Unschematized is whole-sensitive (fail-closed).** A managed request
     with NO Malli-schema `:decode` (`:auto` / `:json` / `:text` / a custom
     fn / a binary mode) carries an UNKNOWN body shape. For an OFF-BOX
     egress the body is treated as whole-sensitive and omitted entirely;
     only a classified projection (a schema-classified body, or an explicit
     opt-in) lets a body ride off-box. On the in-process DEV trace stream
     the unschematized body rides as today (the dev operator sees their own
     process); the fail-closed posture is the off-box / capture rule.

  ## What gets a schema

  Only a Malli-SCHEMA `:decode` carries per-slot marks. The keyword decode
  modes (`:auto` / `:json` / `:text` / `:blob` / `:array-buffer` /
  `:form-data`) and a custom decoder fn are NOT schemas — `schema-decode?`
  is the predicate. An inline schema form contributes its marks directly; a
  keyword registry ref is an opaque leaf to the shared walker (the walker's
  documented limitation, shared with every other schema-mark consumer).

  ## Composition with per-call `:sensitive?`

  The existing per-call / per-request `:sensitive?` flag (Spec 014 §Privacy)
  is the COARSE escape hatch: it redacts the whole body wholesale regardless
  of schema marks. This namespace is the FINE-grained, schema-driven layer
  that fires INDEPENDENTLY of that flag. Sensitive wins over large, matching
  the shared `re-frame.elision/walk` ordering and the frame-classification
  install-time rule.

  ## Per-slot `:sensitive?` and `:large?` (rf2-jhyccs)

  `classify-decoded` applies BOTH the `:decode` schema's `:sensitive?` and
  `:large?` per-slot marks via the shared `re-frame.marks/redact-with-paths`
  walker (sensitive → `:rf/redacted`, large → `:rf.size/large-elided`,
  sensitive wins over large) — the SAME walker the resource / app-schema
  surfaces use, never a body-private walker. `decode-schema-marks` extracts
  both `:sensitive` and `:large` path maps; both are now consumed.

  ## On-box dev trace vs off-box egress (rf2-t55hxg.6)

  `classify-decoded` is the IN-PROCESS dev-trace projection — it applies the
  per-slot marks but does NOT omit an unschematized body (the local operator
  inspects their own process). `off-box-classify-body` is the OFF-BOX egress
  projection — an unschematized body is whole-sensitive and OMITTED, a schema
  body rides classified. The HTTP trace-emit site stamps `off-box-body-
  disposition` forward onto the `:rf.http/*` trace event (under
  `:rf.http/off-box-body`); the off-box trace-events projector
  (`re-frame.epoch.tool-pair`) enforces it (omits an `:omit` event's body
  slot), since the request's `:decode` is request-private and never on the
  trace event.

  ## Production elision

  Like the rest of the HTTP privacy machinery, the classification runs only
  at trace-emit / capture sites that gate on `interop/debug-enabled?`; in
  production builds the trace surface elides entirely and no body walk runs."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.marks :as marks]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; Is the request's `:decode` a Malli SCHEMA (carries per-slot marks), or a
;; keyword mode / custom fn (no marks)?
;;
;; The keyword decode modes and a decoder fn are NOT schemas. Anything else
;; (an inline `[:map ...]` form or a registered-schema keyword ref) is
;; treated as a schema and handed to the shared walker, which returns `{}`
;; for an opaque leaf. A nil `:decode` is `:auto` (no schema).
;; ---------------------------------------------------------------------------

(def ^:private keyword-decode-modes
  #{:auto :json :text :blob :array-buffer :form-data})

(defn schema-decode?
  "True iff `decode` is a Malli-schema `:decode` (carries per-slot
  `:sensitive?` / `:large?` marks), as opposed to a keyword decode mode
  (`:auto` / `:json` / `:text` / binary) or a custom decoder fn. A nil
  `:decode` is `:auto` — no schema."
  [decode]
  (boolean
    (and (some? decode)
         (not (fn? decode))
         (not (and (keyword? decode) (contains? keyword-decode-modes decode))))))

(defn introspectable-schema-decode?
  "True iff `decode` is a Malli-schema `:decode` the shared schema walker can
  actually INTROSPECT for per-slot marks — i.e. the raw EDN VECTOR form
  (`[op props? children...]`, the shape `(rf/reg-app-schema …)` users write).

  This is NARROWER than `schema-decode?` (rf2-y1pgdl). `schema-decode?` is
  true for ANY non-mode/non-fn `:decode`, including OPAQUE schema values the
  walker treats as a leaf and returns `{}` marks for:

    - a KEYWORD REGISTRY REF (`:my-app/token-schema`) — a bare keyword is a
      valid Malli schema (registry ref), but the walker (per Spec 010 §The
      `:schema` value is opaque to re-frame) MUST NOT consult the registry /
      validator, so it cannot see the ref'd schema's per-slot marks;
    - a COMPILED `m/schema` object / a map / any non-vector non-keyword form
      — also an opaque leaf to the pure-data walker.

  An opaque-leaf decode returns NO `:sensitive?` / `:large?` paths even when
  the underlying schema DOES mark slots sensitive. Treating it as
  `schema-decode?` → `:classify` would ride the body UNCHANGED off-box — the
  EP-0015 issue 5 fail-OPEN leak. The off-box egress therefore gates on this
  predicate (fail-CLOSED to `:omit` for an opaque schema); only an
  introspectable vector form earns `:classify`. The on-box dev-trace
  per-slot classification (`classify-decoded`) still keys on `schema-decode?`
  — it is a harmless no-op on an opaque leaf, and the local operator sees
  their own raw process anyway."
  [decode]
  (and (schema-decode? decode)
       (vector? decode)))

;; ---------------------------------------------------------------------------
;; Per-slot marks from the `:decode` schema (the shared walker hooks).
;; ---------------------------------------------------------------------------

(defn- extract-paths
  "Extract the `{path decl}` map of `:sensitive?` (or `:large?`) per-slot
  marks from `schema` rooted at `[]` (the body root), via the late-bound
  shared schema walker hook. Returns `{}` when the hook is unbound (no
  schemas artefact) or `schema` carries no marks."
  [hook schema]
  (if-let [extract (and schema (late-bind/get-fn-cached hook))]
    (or (extract schema []) {})
    {}))

(defn decode-schema-marks
  "The per-slot `:sensitive?` / `:large?` classification the request's
  `:decode` SCHEMA declares for the response body, as
  `{:sensitive {path decl} :large {path decl}}` rooted at the body root
  (`[]`). Empty maps when `decode` is not a schema or carries no marks.
  Pure (modulo the memoised shared walker)."
  [decode]
  (if (schema-decode? decode)
    {:sensitive (extract-paths :schemas/extract-sensitive-paths-from-schema decode)
     :large     (extract-paths :schemas/extract-large-paths-from-schema     decode)}
    {:sensitive {} :large {}}))

(defn whole-body-sensitive-mark?
  "True iff the `:decode` schema marks the WHOLE body (`[]`, the root prop)
  `:sensitive?` — an opaque-token response whose entire body is the secret.
  The fine-grained complement of the per-slot marks."
  [decode]
  (contains? (:sensitive (decode-schema-marks decode)) []))

;; ---------------------------------------------------------------------------
;; Off-box body disposition (EP-0015 issue 5, fail-closed rule).
;;
;; For an OFF-BOX egress (a production capture / off-box trace), a body rides
;; ONLY when its shape is schema-classified; an unschematized body is
;; whole-sensitive and omitted. This is the disposition the off-box capture
;; surface consults; the in-process dev trace uses `classify-decoded` (below)
;; which applies the schema's per-slot marks without omitting an
;; unschematized body the local operator is entitled to see.
;; ---------------------------------------------------------------------------

(def ^:const off-box-body-marker
  "The off-box sentinel an UNSCHEMATIZED body is replaced with when it would
  otherwise ride off-box. The `:rf/redacted` sentinel — the same scalar the
  rest of the egress projection (event args, runtime-db partition, sensitive
  app-db slots) fails closed to. The omission is the disposition: the slot is
  present but carries no body content."
  :rf/redacted)

(defn off-box-body-disposition
  "Classify how a decoded response body MAY ride an OFF-BOX egress, reading
  the request's `:decode`. Returns one of:

    :classify  — the body has an INTROSPECTABLE Malli `:decode` schema (the
                 raw EDN VECTOR form); ride it with the schema's per-slot
                 marks applied (`off-box-classify-body`);
    :omit      — the body is UNSCHEMATIZED (`:auto` / `:json` / `:text` /
                 binary / custom fn) OR carries an OPAQUE schema the walker
                 cannot inspect (a keyword registry ref / a compiled
                 `m/schema` object); whole-sensitive, omitted entirely.

  An unschematized body OR an opaque-schema body fails CLOSED off-box
  (EP-0015 issue 5 — fail-closed when classification is UNKNOWN; rf2-y1pgdl).
  An opaque keyword registry ref returns NO per-slot marks from the shared
  walker (Spec 010 forbids resolving the registry), so `:classify` would ride
  its body unchanged — the fail-open leak. Off-box gates on
  `introspectable-schema-decode?` (vector form only). Pure.

  This is the policy the HTTP trace-emit site stamps forward onto the
  `:rf.http/replied` / `:rf.http/accept-failure` trace event (under
  `:rf.http/off-box-body`), so the off-box trace-events projector
  (`re-frame.epoch.tool-pair`) can enforce the fail-closed rule without
  re-reading the request's `:decode` (a request-private artefact never on the
  trace event)."
  [decode]
  (if (introspectable-schema-decode? decode) :classify :omit))

;; ---------------------------------------------------------------------------
;; Apply the schema's per-slot marks to a decoded body value.
;;
;; `classify-decoded` is the IN-PROCESS dev-trace projection (the local
;; operator sees their own process); `off-box-classify-body` is the OFF-BOX
;; egress projection (fail-closed for an unschematized body). Both delegate
;; to the shared `re-frame.marks/redact-with-paths` walker (sensitive →
;; `:rf/redacted`, large → `:rf.size/large-elided`, sensitive wins over large)
;; — never a body-private walker.
;; ---------------------------------------------------------------------------

(defn classify-decoded
  "Apply the request's `:decode` schema per-slot `:sensitive?` / `:large?`
  marks to a decoded response body `decoded` for the IN-PROCESS dev trace:
  each `:sensitive?` slot redacts to the `:rf/redacted` sentinel, each
  `:large?` slot elides to the `:rf.size/large-elided` marker, via the shared
  `re-frame.marks/redact-with-paths` walker (sensitive wins over large at the
  same slot — the shared `re-frame.elision/walk` ordering, EP-0015 issue 5).

  This is the FINE-grained, schema-driven layer: it fires irrespective of the
  request's per-call `:sensitive?` flag, so a body slot the owner's `:decode`
  schema marks sensitive (`[:token]`) redacts on the dev trace even when the
  whole request was not declared sensitive.

  A root-level (`[]`) sensitive mark — a whole-body opaque-token response —
  redacts the WHOLE body (the walker substitutes the sentinel for the empty
  path); a root-level `:large?` mark elides the whole body. When `decode` is
  not a schema, the body is returned unchanged (the keyword/fn-decode and
  unschematized cases are governed by the per-call `:sensitive?` flag on the
  dev trace and by `off-box-body-disposition` for off-box egress). Pure."
  [decoded decode]
  (let [{:keys [sensitive large]} (decode-schema-marks decode)]
    (if (or (seq sensitive) (seq large))
      (marks/redact-with-paths decoded (keys sensitive) (keys large))
      decoded)))

(defn off-box-classify-body
  "Project a decoded response body `decoded` for an OFF-BOX egress, reading
  the request's `:decode` disposition (EP-0015 issue 5, fail-closed):

    - UNSCHEMATIZED `:decode` (`:auto` / `:json` / `:text` / binary / custom
      fn) OR an OPAQUE schema the walker cannot inspect (a keyword registry
      ref `:my-app/token-schema` / a compiled `m/schema` object): the body is
      whole-sensitive and OMITTED entirely — replaced with the
      `off-box-body-marker` (`:rf/redacted`). The local operator's raw view
      does NOT cross the trust boundary; an opaque ref whose marks the walker
      cannot see fails CLOSED rather than riding unchanged (rf2-y1pgdl — the
      EP-0015 issue 5 fail-open leak).

    - INTROSPECTABLE Malli-SCHEMA `:decode` (the raw EDN VECTOR form): the
      body rides CLASSIFIED — the per-slot `:sensitive?` / `:large?` marks
      applied via `classify-decoded` (the same shared walker the dev trace
      uses), letting the non-sensitive structure through while sensitive
      slots redact and large slots elide.

  This is the projection the off-box trace-events egress
  (`re-frame.epoch.tool-pair`) applies to an `:rf.http/*` trace event's body
  slot, gated on the disposition the emit site stamped forward. Pure."
  [decoded decode]
  (if (introspectable-schema-decode? decode)
    (classify-decoded decoded decode)
    off-box-body-marker))
