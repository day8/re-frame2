(ns re-frame.http-privacy-body
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
     The owner's schema declaration is the signal.

  2. **Whole-body root prop.** A root-level (`[]`) `:sensitive?` prop on the
     `:decode` schema (e.g. an opaque-token response whose WHOLE body is the
     secret) redacts the whole body. `:large?` at the root omits it.

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

  ## Production elision

  Like the rest of the HTTP privacy machinery, the classification runs only
  at trace-emit / capture sites that gate on `interop/debug-enabled?`; in
  production builds the trace surface elides entirely and no body walk runs."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.privacy :as privacy]))

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

(defn off-box-body-disposition
  "Classify how a decoded response body MAY ride an OFF-BOX egress, reading
  the request's `:decode`. Returns one of:

    :classify  — the body has a Malli `:decode` schema; ride it with the
                 schema's per-slot marks applied (`classify-decoded`);
    :omit      — the body is UNSCHEMATIZED (`:auto` / `:json` / `:text` /
                 binary / custom fn); whole-sensitive, omitted entirely.

  An unschematized body fails CLOSED off-box (EP-0015 issue 5). Pure."
  [decode]
  (if (schema-decode? decode) :classify :omit))

;; ---------------------------------------------------------------------------
;; Apply the schema's per-slot marks to a decoded body value.
;; ---------------------------------------------------------------------------

(defn classify-decoded
  "Apply the request's `:decode` schema per-slot `:sensitive?` marks to a
  decoded response body `decoded`, redacting each marked slot to the
  `:rf/redacted` sentinel via the shared `re-frame.privacy/redact-paths`.

  This is the FINE-grained, schema-driven layer (EP-0015 issue 5): it fires
  irrespective of the request's per-call `:sensitive?` flag, so a body slot
  the owner's `:decode` schema marks sensitive (`[:token]`) redacts on the
  dev trace even when the whole request was not declared sensitive.

  A root-level (`[]`) sensitive mark — a whole-body opaque-token response —
  redacts the WHOLE body (`redact-paths` substitutes the sentinel for the
  empty path). When `decode` is not a schema, the body is returned unchanged
  (the keyword/fn-decode and unschematized cases are governed by the per-call
  `:sensitive?` flag for the dev trace and by `off-box-body-disposition` for
  off-box egress). Pure."
  [decoded decode]
  (let [sensitive-paths (keys (:sensitive (decode-schema-marks decode)))]
    (if (seq sensitive-paths)
      (privacy/redact-paths decoded sensitive-paths)
      decoded)))
