(ns re-frame.http-privacy
  "Spec 014 §Privacy — sensitive-data honouring for `:rf.http/managed` (rf2-bma05).

  HTTP is the canonical privacy surface: passwords ride request bodies,
  auth tokens ride request headers, user PII rides response bodies. The
  trace events emitted by the managed-HTTP fx must not surface those
  values verbatim — either by stamping the top-level `:sensitive?` flag
  on the trace event (so consumers filter per Spec 009 §Privacy) or by
  redacting known-sensitive slots before the value reaches the trace
  surface.

  This namespace is the orchestrating seam — the sensitivity resolver
  (`request-sensitive?`) and the trace-event composers (`prepare-emit-
  tags` / `prepare-emit-failure` and the redact-* / stamp-sensitive
  primitives they compose). The two leaf surfaces it draws on live in
  sibling namespaces:

  - `re-frame.http-privacy-headers` — header denylist + `redact-headers`.
  - `re-frame.http-url` — query-param denylist + `redact-url` /
    `redact-url-query-string`. (URL *building* — `url-encode` /
    `params->query` / `merge-params` — lives in `re-frame.http-encoding`,
    not here.)

  Three cooperating mechanisms, mirroring Spec 009's schema-first
  privacy split:

  1. **Header denylist** (`re-frame.http-privacy-headers`) — a canonical
     set of always-sensitive header names. Redacted in trace events
     regardless of the handler / request `:sensitive?` flag, because
     the headers' names themselves declare them sensitive.

  2. **Query-param denylist** (`re-frame.http-url`, rf2-2p8wr) — a
     canonical set of always-sensitive query-string parameter names
     (e.g. `api_key`, `access_token`, `auth`). URLs carrying these
     params are redacted **inline** in every `:rf.http/*` trace event
     that carries a `:url` slot, regardless of the handler / request
     `:sensitive?` flag. Same rationale as the header denylist: the
     param name itself is the signal.

  3. **Per-call `:sensitive?`** — a per-call `:sensitive?` arg on the
     `:rf.http/managed` args map opts in for a specific request (e.g.
     a generic POST handler issuing a sensitive POST to `/auth/login`).
     When the request is sensitive, **all** query params are redacted
     (broader rule than the denylist). NOTE: the originating event
     handler's `:sensitive?` registration metadata is no longer
     consulted — handler-level sensitivity has been removed in favour
     of path-marked / per-call classification.

  The runtime stamps `:sensitive? true` on the emitted trace event's
  `:tags` (and at the top level when the core runtime stamp lands per
  rf2 G1) so consumers consult one keyword to decide ship / drop /
  redact. When ANY query param is redacted by the denylist alone (no
  per-call sensitivity), the trace event still stamps `:sensitive? true`
  — the presence of a denylisted query param is itself a signal that
  the request carries an auth secret.

  ## Frame-local carriers (EP-0015 §3, rf2-ppkh3v)

  Beyond the immutable built-in header / query-param denylists, the
  EMITTING FRAME may extend each with its own app-specific carrier names
  via `:sensitive {:http {:headers [..] :query-params [..]}}` frame policy.
  The composers resolve the emitting frame's extension sets ONCE per emit
  (`frame-http-carriers`, via the `:frame-classification/http-carriers`
  late-bind hook) and thread them to the header / URL redactors, which
  union them onto the built-in defaults. The frame id rides in the emit
  opts map (`:frame`); when absent — a completion fired outside any frame
  scope, or core absent — only the built-in defaults apply. The old
  process-global `declare-sensitive-*!` surface is removed: app-specific
  carriers are frame policy now.

  ## Production elision

  The redact / stamp helpers all gate on `interop/debug-enabled?` at
  their call sites (the same gate as `trace/emit!`). In production
  builds the trace surface elides entirely; the privacy machinery is
  moot, and no walker runs against the denylists without the trace
  surface."
  (:require [re-frame.http-privacy-headers :as headers]
            [re-frame.http-url :as url]
            [re-frame.late-bind :as late-bind]))

(def redacted-sentinel
  "The framework-reserved redaction sentinel per Spec 009 §Privacy. Sits
  in the `:rf/` reserved-keyword namespace so apps cannot legitimately
  produce it as a payload value. Consumers wanting \"was this redacted?\"
  check `(= :rf/redacted v)`.

  rf2-ee38b.7 — re-exported from the canonical `re-frame.http-url`
  definition so the keyword + its string form (`redacted-url-token`)
  cannot drift across the privacy cluster. This var preserves the
  public `re-frame.http-privacy/redacted-sentinel` surface."
  url/redacted-sentinel)

;; ---- frame-local carrier resolution (EP-0015 §3, rf2-ppkh3v) ---------------

(defn frame-http-carriers
  "Resolve the emitting frame's frame-local HTTP carrier extension sets
  `{:headers #{..} :query-params #{..}}` (lower-cased), or `nil`. Reaches
  `re-frame.frame-classification/http-carriers` through the
  `:frame-classification/http-carriers` late-bind hook (HTTP sits below
  core in load order — a static require would cycle and would not resolve
  when the http artefact loads before any frame is registered). Returns
  `nil` when `frame-id` is nil, the hook is unbound (frame-classification
  absent), or the frame declares no `:sensitive :http` block — the common
  case, so a defaults-only emit allocates nothing."
  [frame-id]
  (when frame-id
    (when-let [resolve (late-bind/get-fn-cached :frame-classification/http-carriers)]
      (resolve frame-id))))

;; ---- trace-event redaction helpers ----------------------------------------

(defn- redact-url-in
  "Internal helper: if `m` carries a string `:url`, redact it and return
  `[m' any-redacted?]`. The flag captures whether the query-string walk
  scrubbed any param value — callers use this to stamp `:sensitive?`
  on the trace event (a denylisted param name is itself a signal that
  the request carries a secret) without re-walking the URL.

  `param-extras` is the emitting frame's frame-local query-param carrier
  extension set (EP-0015 §3), or `nil` for defaults-only.

  Per rf2-02vzz — fuses the redact + denylist-hit lookups so the URL's
  query string is parsed exactly once per trace emit."
  [m sensitive? param-extras]
  (if (string? (:url m))
    (let [[redacted any?] (url/redact-url-query-string (:url m) sensitive? param-extras)]
      [(assoc m :url redacted) any?])
    [m false]))

(defn redact-request-tags-with-flag
  "Like `redact-request-tags` but returns `[tags url-redacted?]` so
  callers (`prepare-emit-tags`) can decide whether to stamp
  `:sensitive?` without re-walking the URL. Per rf2-02vzz.

  `carriers` is the emitting frame's frame-local carrier extension map
  `{:headers #{..} :query-params #{..}}` (EP-0015 §3), or `nil`."
  ([tags sensitive?] (redact-request-tags-with-flag tags sensitive? nil))
  ([tags sensitive? carriers]
   (let [[tags url-hit?] (redact-url-in tags sensitive? (:query-params carriers))
         tags' (cond-> tags
                 ;; Always-on header redaction (built-in defaults ∪ frame extras).
                 (map? (:headers tags))
                 (update :headers headers/redact-headers (:headers carriers))

                 ;; Sensitive-request redaction — body becomes the sentinel.
                 (and sensitive? (contains? tags :body))
                 (assoc :body redacted-sentinel)

                 ;; Sensitive-request redaction — params (URL query string) too.
                 (and sensitive? (contains? tags :params))
                 (assoc :params redacted-sentinel))]
     [tags' url-hit?])))

(defn redact-request-tags
  "Given a tags map about to ride a `:rf.http/*` trace event, redact
  the request-side payload when `sensitive?` is true. Always redacts
  denylisted headers regardless of `sensitive?` — the header denylist
  is unconditional. URL query-string values whose param name is in the
  query-param denylist (rf2-2p8wr) are always redacted regardless of
  `sensitive?` — denylisted param names are themselves the signal.

  Idempotent and total: missing slots are left alone.

  rf2-ee38b.7 — public for direct test assertion only; production reaches
  redaction via the `prepare-emit-*` composers (which use the `*-with-flag`
  forms). No production caller invokes this non-flag wrapper."
  ([tags sensitive?] (first (redact-request-tags-with-flag tags sensitive? nil)))
  ([tags sensitive? carriers]
   (first (redact-request-tags-with-flag tags sensitive? carriers))))

(defn redact-failure-with-flag
  "Like `redact-failure` but returns `[failure url-redacted?]` so callers
  (`prepare-emit-failure`, `prepare-emit-tags`) can decide whether to
  stamp `:sensitive?` without re-walking the URL. Per rf2-02vzz.

  `carriers` is the emitting frame's frame-local carrier extension map
  (EP-0015 §3), or `nil`."
  ([failure sensitive?] (redact-failure-with-flag failure sensitive? nil))
  ([failure sensitive? carriers]
   (when failure
     (let [[failure url-hit?] (redact-url-in failure sensitive? (:query-params carriers))
           failure' (cond-> failure
                      (map? (:headers failure))
                      (update :headers headers/redact-headers (:headers carriers))

                      (and sensitive? (contains? failure :body))
                      (assoc :body redacted-sentinel)

                      (and sensitive? (contains? failure :body-text))
                      (assoc :body-text redacted-sentinel)

                      (and sensitive? (contains? failure :decoded))
                      (assoc :decoded redacted-sentinel)

                      ;; Accept-failure carries the user's domain failure-map at :detail
                      ;; — opaque to us; redact wholesale when sensitive.
                      (and sensitive? (contains? failure :detail))
                      (assoc :detail redacted-sentinel)

                      ;; rf2-eusm1 — a string `:cause` is the free-text throw
                      ;; message from the interceptor/transport path. It is
                      ;; author-controlled and can echo a secret the
                      ;; interceptor was handling (e.g. a token-validation
                      ;; message embedding the token), so it rides the same
                      ;; sensitive redaction as the response-side slots. The
                      ;; `string?` guard preserves keyword `:cause`
                      ;; discriminators (e.g. decode-failure's
                      ;; `:cause :too-many-keys`) — those are security-relevant
                      ;; signals, not secret payload (http-decode §too-many-keys).
                      (and sensitive? (string? (:cause failure)))
                      (assoc :cause redacted-sentinel))]
       [failure' url-hit?]))))

(defn redact-failure
  "Given a failure map (per Spec 014 §Failure categories) about to ride
  a `:rf.http/*` trace event, redact response-side payload slots when
  `sensitive?` is true. Always redacts headers via the denylist; always
  redacts URL query-string values whose param name is in the query-
  param denylist (rf2-2p8wr) — denylisted param names are the signal.

  rf2-ee38b.7 — public for direct test assertion only; production reaches
  redaction via `prepare-emit-failure` (which uses the `*-with-flag`
  form). No production caller invokes this non-flag wrapper."
  ([failure sensitive?] (redact-failure failure sensitive? nil))
  ([failure sensitive? carriers]
   (when failure
     (first (redact-failure-with-flag failure sensitive? carriers)))))

(defn stamp-sensitive
  "Stamp the `:sensitive?` flag onto a tags map when `sensitive?` is true.

  Per Spec 009 §Privacy the canonical contract is that `:sensitive?`
  rides at the **top level** of the trace event. Both `re-frame.trace/emit!`
  and `re-frame.trace/emit-error!` (per rf2-isdwf) consult a
  caller-supplied `:sensitive?` slot in tags and hoist it to top-level,
  dissoc'ing it from tags — so we stamp into tags here and let core do
  the hoist. Consumers always read `(:sensitive? ev)` at the top level,
  per the spec contract.

  When `sensitive?` is false the slot is OMITTED (not stamped to false)
  per Spec 009 line 1176: \"Consumers treat absent as false.\"

  rf2-ee38b.7 — public for direct test assertion + composer reuse;
  production reaches it through the `prepare-emit-*` composers."
  [tags sensitive?]
  (cond-> tags
    sensitive? (assoc :sensitive? true)))

;; ---- per-request :sensitive? ----------------------------------------------

(defn request-sensitive?
  "Compute the effective `:sensitive?` reading for a `:rf.http/managed`
  request. Two sources, OR-reduced:

  1. Per-call `:sensitive?` on the args map (`true` opts in for this
     specific request).
  2. Per-call `:sensitive?` under `:request` (sugar — callers reaching
     for `(rf.http/post ... {:request {:sensitive? true}})` get the
     same effect as the top-level form).

  Returns `true` if any source declares sensitivity; `false` otherwise.

  NOTE: handler-level `:sensitive?` registration metadata is no longer
  consulted. Sensitive data marking is path-based per the upcoming
  data-classification mechanism (separate spec doc; in progress)."
  [args-map _origin-event]
  (or (true? (:sensitive? args-map))
      (true? (get-in args-map [:request :sensitive?]))))

;; ---- trace-event composers ------------------------------------------------

(defn prepare-emit-tags
  "Compose `redact-request-tags` + `stamp-sensitive` for a request-side
  trace event (`:rf.http/retry-attempt`, `:rf.http/aborted-on-actor-destroy`,
  `:rf.warning/cljs-only-key-ignored-on-jvm`).
  Returns a tags map ready for `trace/emit!`.

  Two distinct decisions per rf2-2p8wr:

  - **What to redact in `:url`** uses the `sensitive?` arg (handler /
    per-call). When `sensitive?` true, ALL query-string param values
    are scrubbed; when false, only denylisted param values are scrubbed.
    The denylist-only redaction does NOT promote the URL scrub to ALL
    params — denylisted names are signals, not licenses to scrub
    unrelated params.

  - **Whether to stamp `:sensitive?`** OR-combines `sensitive?` with a
    denylist-hit check on `:url` / `:failure :url`. A denylisted query-
    param name alone is enough to stamp the event — the name is the
    signal that the request carries a secret.

  Per rf2-02vzz the redact + denylist-hit are computed in a single URL
  walk: `redact-request-tags-with-flag` and `redact-failure-with-flag`
  return the redacted shape paired with a flag telling us whether any
  query-string value was scrubbed. Earlier the helpers ran two walks
  per trace emit (denylist-hit then redact).

  Per rf2-ppkh3v (EP-0015 §3) the optional `opts` map carries `:frame` —
  the emitting frame id. The frame's frame-local carrier extension sets
  (`:sensitive {:http {...}}`) are resolved ONCE here and threaded to the
  header / URL redactors so app-specific carriers redact alongside the
  built-in defaults. Absent `:frame` (a completion outside any frame
  scope) → built-in defaults only."
  ([tags sensitive?] (prepare-emit-tags tags sensitive? nil))
  ([tags sensitive? {:keys [frame]}]
   (let [s?                          (true? sensitive?)
         carriers                    (frame-http-carriers frame)
         [tags' tag-url-hit?]        (redact-request-tags-with-flag tags s? carriers)
         [tags'' fail-url-hit?]      (if (contains? tags :failure)
                                       (let [[f' h?] (redact-failure-with-flag (:failure tags) s? carriers)]
                                         [(assoc tags' :failure f') h?])
                                       [tags' false])
         stamp?                      (or s? tag-url-hit? fail-url-hit?)]
     (stamp-sensitive tags'' stamp?))))

(defn prepare-emit-failure
  "Compose `redact-failure` + `stamp-sensitive` for an error-side trace
  event (`emit-error!` on a `:rf.http/*` failure kind). The whole
  failure map is the tags map for `emit-error!`.

  Two distinct decisions per rf2-2p8wr — same split as `prepare-emit-
  tags`: URL redaction uses the explicit `sensitive?` arg; `:sensitive?`
  stamping OR-combines that arg with a query-param denylist hit on the
  failure's `:url`.

  Per rf2-02vzz the URL walk happens once: `redact-failure-with-flag`
  returns `[failure url-hit?]` so we don't re-parse the query string.

  Per rf2-ppkh3v (EP-0015 §3) the optional `opts` map carries `:frame`;
  the emitting frame's frame-local carrier extension sets are resolved
  once and threaded to the header / URL redactors. Absent `:frame` →
  built-in defaults only."
  ([failure sensitive?] (prepare-emit-failure failure sensitive? nil))
  ([failure sensitive? {:keys [frame]}]
   (let [s?                  (true? sensitive?)
         carriers            (frame-http-carriers frame)
         [failure' url-hit?] (redact-failure-with-flag failure s? carriers)
         stamp?              (or s? url-hit?)]
     (when failure'
       (stamp-sensitive failure' stamp?)))))
