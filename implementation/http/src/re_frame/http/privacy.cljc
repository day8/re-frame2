(ns re-frame.http.privacy
  "Spec 014 §Privacy for `:rf.http/managed`.

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

  - `re-frame.http.privacy-headers` — header denylist + `redact-headers`.
  - `re-frame.http.url` — query-param denylist + `redact-url` /
    `redact-url-query-string`. (URL *building* — `url-encode` /
    `params->query` / `merge-params` — lives in `re-frame.http.encoding`,
    not here.)

  Three cooperating mechanisms, mirroring Spec 009's schema-first
  privacy split:

  1. **Header denylist** (`re-frame.http.privacy-headers`) — a canonical
     set of always-sensitive header names. Redacted in trace events
     regardless of the request's `:sensitive?` flag, because
     the headers' names themselves declare them sensitive.

  2. **Query-param denylist** (`re-frame.http.url`) — a
     canonical set of always-sensitive query-string parameter names
     (e.g. `api_key`, `access_token`, `auth`). URLs carrying these
     params are redacted **inline** in every `:rf.http/*` trace event
     that carries a `:url` slot, regardless of the request
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
  `:tags` so consumers consult one keyword to decide ship, drop, or redact.
  When any query param is redacted by the denylist alone (no
  per-call sensitivity), the trace event still stamps `:sensitive? true`
  — the presence of a denylisted query param is itself a signal that
  the request carries an auth secret.

  ## Managed-HTTP carriers

  Beyond the immutable built-in header / query-param denylists, an app may
  extend each with its own app-specific carrier names via the
  `:rf.http/managed` `reg-fx` registration metadata — the `:carriers
  {:headers [..] :query-params [..]}` block. The composers resolve the
  registration's extension sets once per
  emit (`managed-carriers`, reading `re-frame.registrar/handler-meta :fx
  :rf.http/managed`) and thread them to the header / URL redactors, which
  union them onto the built-in defaults. Carriers are process-global (one
  `:rf.http/managed` registration), so resolution needs no frame id; when
  no app re-registered `:rf.http/managed` with a `:carriers` block, only the
  built-in defaults apply.

  ## Production elision

  The redact / stamp helpers all gate on `interop/debug-enabled?` at
  their call sites (the same gate as `trace/emit!`). In production
  builds the trace surface elides entirely; the privacy machinery is
  moot, and no walker runs against the denylists without the trace
  surface."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.http.privacy-headers :as headers]
            [re-frame.http.url :as url]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]))

;; The URL leaf owns the public HTTP alias so privacy composers can share the
;; core sentinel without creating a leaf-to-parent cycle.
(def ^:private redacted-sentinel url/redacted-sentinel)

;; ---- managed-HTTP carrier resolution --------------------------------------
;;
;; App-specific carrier names ride the `:rf.http/managed` `reg-fx`
;; registration metadata — the `:carriers {:headers [..] :query-params [..]}`
;; block (the transient-payload case). The block rides the registration
;; verbatim; this resolver lowers it ONCE per emit to lower-cased extension
;; sets the header / URL redactors union onto the immutable built-in carrier
;; denylists (Spec 014 §Privacy). Carriers are process-global (one
;; registration), so resolution needs no frame id — it reads the registration
;; directly via `re-frame.registrar/handler-meta` (http requires registrar;
;; no late-bind hop, no load cycle).
;;
;; Carrier names are matched case-insensitively on the wire, so the resolver
;; lower-cases them ONCE here (the redactor lower-cases the incoming header /
;; param name and does a set lookup). The resolver is the single
;; normalisation point so the redactors never re-derive the lower-cased form
;; per emit.

;; ---- carrier shape validation (fail-loud) ---------------------------------
;;
;; The `:carriers` block on the `:rf.http/managed` registration is validated
;; at consumption (the single resolver below). Carrier names MUST be strings
;; (header / query-param names are strings on the wire); the grammar is
;; closed (only `:headers` / `:query-params`, and a query-param policy map
;; only `:include` / `:except`). A malformed block FAILS LOUD with
;; `:rf.error/bad-classification` (the canonical thrown-error shape) — the
;; same fail-loud posture as other classification metadata.

(defn- carrier-error
  [reason extras]
  (error/thrown-ex-info
    :rf.error/bad-classification
    'rf/reg-fx
    reason
    {:recovery :fix-registration
     :extra    (merge {:fx :rf.http/managed} extras)}))

(defn- validate-carrier-name-vector!
  "Each carrier name in `names` must be a string; a non-string name fails
  loudly. `bad-key` locates the slot. Returns the (unchanged) vector."
  [carrier-key bad-key names]
  (when-not (vector? names)
    (throw (carrier-error
             (str ":rf.http/managed :carriers " carrier-key ", when present, "
                  "must be a vector of carrier-name strings")
             {:bad-key bad-key :bad-value names})))
  (doseq [n names]
    (when-not (string? n)
      (throw (carrier-error
               (str ":rf.http/managed :carriers " carrier-key " names must be "
                    "strings (header / query-param names are strings)")
               {:bad-key bad-key :bad-carrier n}))))
  names)

(def ^:private query-param-policy-keys #{:include :except})
(def ^:private carrier-keys #{:headers :query-params})

(defn validate-carriers!
  "Validate the `:carriers {:headers […] :query-params …}` block on the
  `:rf.http/managed` registration (EP-0025 §HTTP carriers). FAILS LOUD with
  `:rf.error/bad-classification` on a non-map block, an unknown carrier key,
  a non-string carrier name, a non-map/unknown-key query-param policy map, or
  a non-vector sub-value. `:headers` is vector-only (the header denylist is
  immutable — a default-off header would be a real leak); `:query-params`
  additionally accepts a `{:include […] :except […]}` policy map. Returns nil;
  no-op when `carriers` is nil (the common case)."
  [carriers]
  (when (some? carriers)
    (when-not (map? carriers)
      (throw (carrier-error
               (str ":rf.http/managed :carriers, when present, must be a map of "
                    "{:headers [..] :query-params [..]}")
               {:bad-key :carriers :bad-value carriers})))
    (doseq [k (keys carriers)]
      (when-not (contains? carrier-keys k)
        (throw (carrier-error
                 (str "unknown :rf.http/managed :carriers key " k "; valid keys "
                      "are :headers and :query-params")
                 {:bad-key [:carriers k] :valid carrier-keys}))))
    (when-some [header-names (:headers carriers)]
      (validate-carrier-name-vector! :headers [:carriers :headers] header-names))
    (let [query-param-config (:query-params carriers)]
      (cond
        (nil? query-param-config) nil
        ;; query-params may carry a {:include :except} policy map.
        (map? query-param-config)
        (do
          (doseq [k (keys query-param-config)]
            (when-not (contains? query-param-policy-keys k)
              (throw (carrier-error
                       (str "unknown :rf.http/managed :carriers :query-params key "
                            k "; valid keys are :include and :except")
                       {:bad-key [:carriers :query-params k]
                        :valid   query-param-policy-keys}))))
          (when-some [include-names (:include query-param-config)]
            (validate-carrier-name-vector!
              :query-params [:carriers :query-params :include] include-names))
          (when-some [except-names (:except query-param-config)]
            (validate-carrier-name-vector!
              :query-params [:carriers :query-params :except] except-names)))
        :else
        (validate-carrier-name-vector!
          :query-params [:carriers :query-params] query-param-config))))
  nil)

(defn- lower-set
  "Lower-case a sequence of carrier names into a set, or nil when empty."
  [names]
  (when (seq names)
    (into #{} (map str/lower-case) names)))

(defn managed-carriers
  "Resolve the app-declared HTTP carrier extension sets from the
  `:rf.http/managed` `reg-fx` registration metadata (`:carriers` block,
  EP-0025 §HTTP carriers). Returns

      {:headers      #{<lower-cased header name>...}
       :query-params <query-param policy>}

  where the `:query-params` policy is:
   - a `#{<lower-cased name>...}` SET for the include-only vector form
     (`:query-params [\"shop_token\"]`) — names EXTEND the built-in defaults; OR
   - a `{:include #{..} :except #{..}}` MAP for the `{:include [..] :except [..]}`
     form — `:include` extends the defaults, `:except` subtracts from them for
     this app's own dev trace (`(defaults − except) ∪ include`). Empty
     include/except sub-sets are dropped; a policy that resolves to nothing
     (e.g. all-empty vectors) yields `nil` for `:query-params`.

  `:headers` are EXTENSIONS only (the immutable built-in defaults are owned by
  the http layer and unioned there). Returns `nil` when no app re-registered
  `:rf.http/managed` with a `:carriers` block — the common case, so a
  defaults-only emit allocates nothing. Pure (modulo the registry read)."
  []
  (let [carriers (:carriers (registrar/handler-meta :fx :rf.http/managed))]
    (when (some? carriers)
      (validate-carriers! carriers)
      (let [header-carriers        (lower-set (:headers carriers))
            raw-query-param-policy (:query-params carriers)
            query-param-policy     (if (map? raw-query-param-policy)
                                     ;; {:include :except} policy map — lower-case
                                     ;; both sub-sets, drop empties, and collapse
                                     ;; to nil when nothing remains.
                                     (let [included-carriers
                                           (lower-set (:include raw-query-param-policy))
                                           excepted-carriers
                                           (lower-set (:except raw-query-param-policy))]
                                       (when (or included-carriers excepted-carriers)
                                         (cond-> {}
                                           included-carriers
                                           (assoc :include included-carriers)
                                           excepted-carriers
                                           (assoc :except excepted-carriers))))
                                     ;; include-only vector → plain extension set
                                     (lower-set raw-query-param-policy))]
        (when (or header-carriers query-param-policy)
          (cond-> {}
            header-carriers    (assoc :headers header-carriers)
            query-param-policy (assoc :query-params query-param-policy)))))))

;; ---- trace-event redaction helpers ----------------------------------------

(defn- redact-url-in
  "Internal helper: if `payload` carries a string `:url`, redact it and return
  `[redacted-payload any-redacted?]`. The flag captures whether the query-string
  walk scrubbed any param value — callers use this to stamp `:sensitive?`
  on the trace event (a denylisted param name is itself a signal that
  the request carries a secret) without re-walking the URL.

  `query-param-policy` is the registration-owned query-param carrier policy, or
  `nil` for defaults only.

  A self-identifying failure map also carries the request echo
  `:request {:method :url}`; its nested
  `:url` is an egress surface too, so it is walked through the SAME
  redactor. On-box the app reply carries the raw url (the caller's own
  data); this redaction fires only on the trace egress path the
  `prepare-emit-*` composers drive.

  Redaction and denylist-hit detection share one walk, so the query string is
  parsed once per trace emit."
  [payload sensitive? query-param-policy]
  (let [[payload-after-top-level-url top-level-url-redacted?]
        (if (string? (:url payload))
          (let [[redacted-url url-redacted?]
                (url/redact-url-query-string
                  (:url payload) sensitive? query-param-policy)]
            [(assoc payload :url redacted-url) url-redacted?])
          [payload false])
        [redacted-payload nested-url-redacted?]
        (if (string? (get-in payload-after-top-level-url [:request :url]))
          (let [[redacted-url url-redacted?]
                (url/redact-url-query-string
                  (get-in payload-after-top-level-url [:request :url])
                  sensitive?
                  query-param-policy)]
            [(assoc-in payload-after-top-level-url [:request :url] redacted-url)
             url-redacted?])
          [payload-after-top-level-url false])]
    [redacted-payload (or top-level-url-redacted? nested-url-redacted?)]))

(defn redact-request-tags-with-flag
  "Like `redact-request-tags` but returns `[tags url-redacted?]` so
  callers (`prepare-emit-tags`) can decide whether to stamp
  `:sensitive?` without re-walking the URL.

  `carriers` is the registration-owned carrier extension map
  `{:headers #{..} :query-params #{..}}`, or `nil`."
  ([tags sensitive?] (redact-request-tags-with-flag tags sensitive? nil))
  ([tags sensitive? carriers]
   (let [[tags url-hit?] (redact-url-in tags sensitive? (:query-params carriers))
         tags' (cond-> tags
                 ;; Always-on header redaction (built-in defaults plus extensions).
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
  query-param denylist are always redacted regardless of
  `sensitive?` — denylisted param names are themselves the signal.

  Idempotent and total: missing slots are left alone.

  Public for direct test assertion; production reaches
  redaction via the `prepare-emit-*` composers (which use the `*-with-flag`
  forms). No production caller invokes this non-flag wrapper."
  ([tags sensitive?] (first (redact-request-tags-with-flag tags sensitive? nil)))
  ([tags sensitive? carriers]
   (first (redact-request-tags-with-flag tags sensitive? carriers))))

(defn redact-failure-with-flag
  "Like `redact-failure` but returns `[failure url-redacted?]` so callers
  (`prepare-emit-failure`, `prepare-emit-tags`) can decide whether to
  stamp `:sensitive?` without re-walking the URL.

  `carriers` is the registration-owned carrier extension map, or `nil`."
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

(defn redact-response-meta
  "Redact the sensitive response-header carriers on a canonical reply's
  `[:meta :headers]` before the reply rides a trace surface (rf2-lddbk).

  A successful completion's reply carries the response wire facts under
  `:meta` (`{:status :status-text :headers}` — `re-frame.http.reply/
  success-reply`). The reply DELIVERED to the app rides raw (on-box app
  data — the caller's own response); this redactor fires only on the
  trace-egress path, substituting `:rf/redacted` for every header whose
  name is in the merged denylist (the immutable built-in defaults ∪ the
  app-declared `:carriers {:headers [..]}` extension, resolved via
  `managed-carriers` exactly as the failure-map `:headers` redaction
  does). A reply with no `:meta` headers passes through untouched.

  Per-call `:sensitive?` wholesale redaction is NOT this fn's job — `:meta`
  is a core wire-bearing slot, so `trace-reply`'s
  `:rf.privacy/force-redact-wire?` translation already redacts the whole
  slot for a sensitive request."
  [reply]
  (if (map? (get-in reply [:meta :headers]))
    (update-in reply [:meta :headers]
               headers/redact-headers (:headers (managed-carriers)))
    reply))

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

  Handler registration metadata is not consulted; request sensitivity is a
  per-call decision."
  [args-map _origin-event]
  (or (true? (:sensitive? args-map))
      (true? (get-in args-map [:request :sensitive?]))))

;; ---- fx-args trace projection (rf2-32ffq1) ---------------------------------

(defn project-managed-fx-args
  "Redact a `:rf.http/managed` fx ARGS MAP for the generic fx-arg-bearing
  trace slots the CORE classification projector walks — the `:rf.event/fx`
  aggregate on `:rf.fx/do-fx` and every `[:rf.fx/id :rf.fx/args]`-shaped slot
  (`:rf.fx/handled`, the always-on fx error traces). The managed fx's privacy
  model is DYNAMIC — the per-call `:sensitive?` flag inside the args map
  (`request-sensitive?`, Spec 014 §Privacy) plus the carrier denylists —
  which a static registration `:sensitive` path cannot express, so core
  consults this fn through the `:http/project-managed-fx-args` late-bind hook
  (published by `re-frame.http.managed`; core stays decoupled).

  Mirrors the dedicated `:rf.http/*` trace composers (`prepare-emit-tags`):
  the `:request` map rides `redact-request-tags` — denylisted headers +
  denylisted URL query values ALWAYS; `:body` / `:params` / all query values
  when the request is sensitive — with the registration's `:carriers`
  extensions applied. The `:on-success` / `:on-failure` reply-address event
  vectors additionally ride the TARGET event registration's own
  classification (through the `:classification/redact-event-by-registration`
  hook — bound by core at boot), so a payload-carrying reply address redacts
  exactly as the dispatched reply event itself will. Total: a non-map `args`
  passes through untouched."
  [args]
  (if-not (map? args)
    args
    (let [sensitive?   (request-sensitive? args nil)
          carriers     (managed-carriers)
          redact-event (or (late-bind/get-fn :classification/redact-event-by-registration)
                           identity)
          redact-addr  (fn [v] (if (vector? v) (redact-event v) v))]
      (cond-> args
        (map? (:request args))       (update :request #(redact-request-tags % sensitive? carriers))
        (contains? args :on-success) (update :on-success redact-addr)
        (contains? args :on-failure) (update :on-failure redact-addr)))))

;; ---- trace-event composers ------------------------------------------------

(defn prepare-emit-tags
  "Compose `redact-request-tags` + `stamp-sensitive` for a request-side
  trace event (`:rf.http/retry-attempt`, `:rf.http/aborted-on-actor-destroy`,
  `:rf.warning/cljs-only-key-ignored-on-jvm`).
  Returns a tags map ready for `trace/emit!`.

  Two distinct decisions:

  - **What to redact in `:url`** uses the per-call `sensitive?` arg. When
    true, all query-string param values
    are scrubbed; when false, only denylisted param values are scrubbed.
    The denylist-only redaction does NOT promote the URL scrub to ALL
    params — denylisted names are signals, not licenses to scrub
    unrelated params.

  - **Whether to stamp `:sensitive?`** OR-combines `sensitive?` with a
    denylist-hit check on `:url` / `:failure :url`. A denylisted query-
    param name alone is enough to stamp the event — the name is the
    signal that the request carries a secret.

  Redaction and denylist-hit detection share one URL walk; the helpers return
  the redacted shape paired with a flag reporting whether any value changed.

  App-declared carrier extension sets ride
  the `:rf.http/managed` `reg-fx` registration (`:carriers` block); they
  are resolved ONCE here (`managed-carriers`) and threaded to the header /
  URL redactors so app-specific carriers redact alongside the built-in
  defaults. Carriers are process-global, so the optional third arg (kept
  for call-site compatibility — callers still pass `{:frame …}`) no longer
  drives carrier resolution. No app `:carriers` block → built-in defaults
  only."
  ([tags sensitive?] (prepare-emit-tags tags sensitive? nil))
  ([tags sensitive? _opts]
   (let [s?                          (true? sensitive?)
         carriers                    (managed-carriers)
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

  Two distinct decisions, matching `prepare-emit-
  tags`: URL redaction uses the explicit `sensitive?` arg; `:sensitive?`
  stamping OR-combines that arg with a query-param denylist hit on the
  failure's `:url`.

  The URL walk happens once: `redact-failure-with-flag`
  returns `[failure url-hit?]` so we don't re-parse the query string.

  App-declared carrier extension sets ride
  the `:rf.http/managed` `reg-fx` registration (`:carriers` block), resolved
  once (`managed-carriers`) and threaded to the header / URL redactors.
  Carriers are process-global, so the optional third arg (kept for call-site
  compatibility — callers still pass `{:frame …}`) no longer drives carrier
  resolution. No app `:carriers` block → built-in defaults only."
  ([failure sensitive?] (prepare-emit-failure failure sensitive? nil))
  ([failure sensitive? _opts]
   (let [s?                  (true? sensitive?)
         carriers            (managed-carriers)
         [failure' url-hit?] (redact-failure-with-flag failure s? carriers)
         stamp?              (or s? url-hit?)]
     (when failure'
       (stamp-sensitive failure' stamp?)))))
