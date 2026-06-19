(ns re-frame.http.encoding
  "Pure-fn helpers for the `:rf.http/managed` request/response pipeline.

  Extracted from `re-frame.http.managed` per rf2-3i9b. The fns here are
  fully pure — they touch no atoms and dispatch no events — so they are
  trivially testable and shared by the unified Fetch (CLJS) +
  `HttpClient` (JVM) transport in `re-frame.http.transport`.

  Surfaces (post-rf2-5ijhk split):

  - URL / query-string encoding: `url-encode`, `params->query`,
    `merge-params`.
  - Request body encoding: `encode-body` (handles
    `:request-content-type` :json / :form / :text / explicit string,
    plus heuristics for raw Clojure colls; the single thunk-realisation
    site is inlined into `run-attempt!` per rf2-sz4n0).
  - `:accept` normalisation: `run-accept` (and the inline default).
  - Reply addressing: `resolve-origin-event`, `build-reply-event`,
    `dispatch-reply-via-late-bind!`.
  - Backoff: `compute-backoff-ms`.

  Per rf2-sz4n0 (round-2 http audit, findings 1.1, 1.2): two thin
  one-liner helpers from the earlier split — `failure-map` (just
  `(assoc tags :kind kind)`) and `realise-body` (just `(if (fn? body)
  (body) body)`) — were inlined into their call sites. The earlier
  shape required readers to trace into the helper to confirm it was a
  plain `assoc` / `if`; the inline form reads as ordinary Clojure with
  no behaviour loss. The third audit-flagged closure, `default-accept-
  fn`, is folded into `run-accept` (see the docstring on `run-accept`).

  The response-side decode pipeline (`content-type-of`, `sniff-decoder`,
  `malli-decode`, `decode-response-body`) lives in `re-frame.http-
  decode` per rf2-5ijhk.

  Per Spec 014 §Body encoding, §`:accept`, §Failure categories,
  §Reply addressing, §Retry and backoff."
  (:require [clojure.string  :as str]
            [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]
            [re-frame.http.json :as util-json])
  #?(:clj (:import [java.net URLEncoder])))

;; ---- query string + URL helpers -------------------------------------------

(defn url-encode
  "Percent-encode `s` for safe splicing into a URL query string.

  JVM `URLEncoder/encode` is an `application/x-www-form-urlencoded`
  encoder — it renders a space as `+`. We rewrite `+` → `%20` so the
  output is also correct in non-form contexts (and matches the CLJS
  `js/encodeURIComponent` path, which percent-encodes spaces as `%20`).
  Keeping the two hosts byte-identical means `params->query` produces
  the same wire string everywhere."
  [s]
  #?(:clj  (-> (URLEncoder/encode (str s) "UTF-8")
               (.replace "+" "%20"))
     :cljs (js/encodeURIComponent (str s))))

(defn params->query
  "Encode a `:params` map as a query string (no leading `?`).

  Scalar values (string / number / keyword / boolean) encode to a single
  `k=v` pair. A sequential value (vector / seq / list) encodes as one
  repeated `k=v` pair per element — `{:tag [\"a\" \"b\"]}` → `tag=a&tag=b`
  (rf2-mag59). Repeat-key is the conventional HTTP-client idiom for
  multi-valued query params; Spec 012 §Query strings and fragments does
  not normatively pin a multi-valued shape, so the client picks the most
  interoperable one. An empty sequential value contributes no pair. A
  `nil` value still emits a bare `k=` pair (the key carries no value)."
  [params]
  (->> params
       (mapcat (fn [[k v]]
                 (let [k-enc (url-encode (if (keyword? k) (name k) (str k)))]
                   (if (sequential? v)
                     (map #(str k-enc "=" (url-encode %)) v)
                     [(str k-enc "=" (url-encode v))]))))
       (str/join "&")))

(defn merge-params
  "Merge `:params` onto `:url`, returning the URL with the encoded query
  string spliced in BEFORE any `#fragment`.

  Two correctness rules a naive append breaks (rf2-rznrz):

  1. **Fragment-aware splice.** Query params after a `#` are fragment
     text — real HTTP clients send the fragment to nobody, so a naive
     append (`/items#frag` + `{:page 2}` → `/items#frag?page=2`)
     silently drops the params. We split at the first `#`, append the
     query string to the pre-fragment part with `?` (or `&` when that
     part already carries a `?`), then reattach the fragment verbatim.

  2. **Decide on the ENCODED query, not the raw params.** A params map
     can encode to no pairs (e.g. `{:tag []}` → `\"\"` per
     `params->query`'s empty-sequential rule), so deciding to append
     from `(seq params)` alone produces a dangling `?`/`&`. We build the
     query string first and return the URL unchanged when it is blank."
  [url params]
  (let [qs (params->query params)]
    (if (str/blank? qs)
      url
      (let [hash-idx (str/index-of url "#")
            base     (if hash-idx (subs url 0 hash-idx) url)
            fragment (if hash-idx (subs url hash-idx) "")
            sep      (if (str/includes? base "?") "&" "?")]
        (str base sep qs fragment)))))

;; ---- request header normalisation -----------------------------------------

(defn normalize-header-pairs
  "Flatten a request-headers map into an ordered seq of `[name value]`
  wire pairs (rf2-rznrz).

  Per Spec 014 §Request envelope a request header value is `string` for
  the single-value case or `vector-of-strings` (more generally any
  ordered `sequential?` collection) for the multi-valued case. HTTP
  represents a multi-valued header as the header NAME repeated once per
  value — `Accept: a` + `Accept: b`, never a single `Accept: [\"a\" \"b\"]`
  line. So:

  - a scalar value yields one `[name (str value)]` pair, and
  - a sequential value yields one `[name (str element)]` pair per
    element, in order.

  The name is stringified once; values are stringified per element so a
  keyword / number value lands as its plain string form on the wire (the
  prior CLJS path assigned a vector straight into a `HeadersInit`, and
  the JVM path called `(str v)` on the whole vector — both produced a
  single malformed wire value). An empty sequential value contributes no
  pair (the header is simply absent), mirroring `params->query`'s
  empty-sequential rule."
  [headers]
  (mapcat (fn [[k v]]
            (let [name (str k)]
              (if (sequential? v)
                (map (fn [el] [name (str el)]) v)
                [[name (str v)]])))
          headers))

;; ---- body encoding --------------------------------------------------------

(defn encode-body
  "Per Spec 014 §Body encoding. Returns a tuple `[encoded-body content-type]`.
  `content-type` may be nil — the caller decides whether to set the header."
  [body request-content-type]
  (cond
    (nil? body)
    [nil nil]

    (= request-content-type :json)
    [(util-json/json-stringify body) "application/json"]

    (= request-content-type :form)
    [(params->query body) "application/x-www-form-urlencoded"]

    (= request-content-type :text)
    [(str body) "text/plain"]

    (string? request-content-type)
    [(str body) request-content-type]

    ;; No explicit content-type — heuristics for clj colls (default to JSON).
    (or (map? body) (sequential? body) (set? body))
    [(util-json/json-stringify body) "application/json"]

    :else
    ;; pass-through (Blob / FormData / ArrayBuffer / pre-encoded string)
    [body nil]))

;; ---- :accept normalisation ------------------------------------------------

(defn run-accept
  "Per Spec 014 §`:accept` — domain-failure normalisation. Run the
  user's `:accept` fn (or the default) against `decoded`. Returns
  `{:ok v}` or `{:failure m}`.

  Per rf2-5ijhk + audit finding 3.3 the default-accept shape is
  inlined here (the earlier `default-accept-fn` allocated a fresh
  closure per response even when the user didn't supply `:accept`).

  Per rf2-7iji6 the default is unconditionally `{:ok decoded}`. The only
  call site (`http-transport/handle-response!`) reaches `run-accept`
  exclusively inside the 2xx branch — status classification (4xx / 5xx /
  non-2xx-else) runs BEFORE decode per Spec 014 §Failure categories, so
  the default never sees a non-2xx status. The earlier non-2xx arm was
  dead on the live cascade and emitted an off-taxonomy `:kind
  :http-status` (not a member of the closed `:rf.http/*` failure set);
  it has been removed. `:accept` runs only against a successfully
  decoded 2xx body, so it needs no status."
  [accept-fn-or-nil decoded]
  (if accept-fn-or-nil
    (accept-fn-or-nil decoded)
    {:ok decoded}))

(defn valid-accept-return?
  "rf2-rznrz — is `v` a recognised `:accept` return shape? Per Spec 014
  §`:accept` the contract is `(decoded → {:ok v} | {:failure m})`: a map
  carrying EXACTLY one of `:ok` / `:failure`.

  Used by `http-transport/handle-response!` to validate the accept-phase
  return AFTER the request has completed. A malformed return (nil, a
  non-map, or a map carrying neither key) previously cleared the
  in-flight request and dispatched NO reply — the caller hung forever.
  Validating the shape lets the transport classify a malformed return as
  `:rf.http/accept-failure` and always emit a reply."
  [v]
  (and (map? v)
       (let [has-ok?      (contains? v :ok)
             has-failure? (contains? v :failure)]
         ;; Exactly one of the two recognised keys — a map carrying both
         ;; is ambiguous and rejected alongside the map-carrying-neither
         ;; case.
         (not= has-ok? has-failure?))))

;; ---- reply addressing -----------------------------------------------------

(defn resolve-origin-event
  "Per Spec 014 §Reply addressing — single source of truth for the
  originating event vector used as the default reply target.

  Resolution order:
   1. `(:event frame-ctx)` — the originating event the runtime threads
      through `do-fx`'s 5-arity (when the fx fired from inside an event
      handler).
   2. `(:rf.http/origin-event args-map)` — an explicit override on the
      args map (machine-shape wrapper sets this when there is no
      ambient event, e.g. spawn-time entry actions).
   3. `[:rf.http/managed]` — synthetic fallback so the reply addressing
      machinery (`build-reply-event`) always has a non-nil event-id.

  Extracted from the three verbatim copies in `http-managed`
  (`normalise-args`, `managed-handler`) and `http-machine-wrapper`
  (`origin-event-from`) per rf2-622e3."
  [frame-ctx args-map]
  (or (:event frame-ctx)
      (:rf.http/origin-event args-map)
      [:rf.http/managed]))

(declare build-reply-event)

(defn dispatch-reply-via-late-bind!
  "Compose `build-reply-event` with a late-bind `:router/dispatch!` lookup
  and fire the dispatch. The single truth point for 'how does http
  dispatch its reply' — shared by `http-transport/dispatch-reply!` and
  the canned-stub handlers in `http-test-support`. Per rf2-2utlm.

  `args` is the same map `build-reply-event` consumes; `frame` (optional)
  is threaded as `{:frame <id>}` onto the dispatch options when present.
  No-op when the registered router is absent or `build-reply-event`
  returns nil (silenced reply).

  Per rf2-t1lxr / rf2-1ve9h: reply dispatches self-tag with
  `:source :http` so Xray's L2 timeline + tools can
  discriminate HTTP-completion cascades from user-origin events.
  Per rf2-1ve9h (Mike-approved 2026-05-28) the prior parallel
  `:rf/dispatch-origin :http` was collapsed — `:source :http` is now
  the single axis.

  EP-0010 / EP-0017 (rf2-n1rh0f / rf2-40dqi6 / rf2-r65m41 / rf2-alc1lf): the
  reply is a CAUSAL TOKEN, and the host completion time (`:completed-at`, read
  ONCE at finalisation in `reply-ctx`) is carried as the reply dispatch's
  `:rf.cofx` `:rf/time-ms`. The router PRESERVES a supplied `:rf.cofx` that
  already carries `:rf/time-ms` (it only fills a missing one), so a reply
  reducer deriving a durable timestamp reads it off the reply event's
  recordable coeffects — the causal completion fact, never a fresh ambient
  clock read in the handler. When `completed-at` is absent (a synthetic /
  canned reply with no host clock read), the router stamps the dispatch time
  as usual."
  [args frame]
  (when-let [ev (build-reply-event args)]
    (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
      (dispatch! ev (cond-> {:source :http}
                      frame                  (assoc :frame frame)
                      (:completed-at args)   (assoc :rf.cofx
                                                    {:rf/time-ms (:completed-at args)}))))))

(defn build-reply-event
  "Per Spec 014 §Reply addressing. Returns the event vector to dispatch,
  or nil when silenced.

  - explicit :on-success / :on-failure: append `{:kind ... ...}` as last
    arg of the supplied event vector.
  - default (omitted): originating event-id with `:rf/reply` merged into
    the original message.
  - explicit nil: silenced."
  [{:keys [origin-event explicit-on reply-payload]}]
  (let [supplied? (:supplied? explicit-on)
        value     (:value explicit-on)]
    (cond
      ;; explicit nil — silenced (caller passed :on-success nil / :on-failure nil)
      (and supplied? (nil? value))
      nil

      ;; explicit event vector — append payload
      (and supplied? (vector? value))
      (conj value reply-payload)

      ;; rf2-smqkq — an explicitly supplied non-vector non-nil reply target
      ;; (e.g. a bare keyword or a map) is malformed: Spec 014 §Reply
      ;; addressing types `:on-success` / `:on-failure` as "event vector or
      ;; nil". Earlier this fell into the `:else` default-merge branch and
      ;; silently re-routed the reply to the originating event-id, dropping
      ;; the caller's explicit intent. Reject it at the dispatch site rather
      ;; than swallow the misuse.
      supplied?
      (error/throw-error!
        :rf.error/http-bad-reply-target :rf.http/managed
        "`:on-success` / `:on-failure` must be an event vector or nil per Spec 014 §Reply addressing; a non-vector non-nil value cannot be dispatched as an event"
        {:extra {:value value}})

      ;; default — back to originator with :rf/reply merged into message
      :else
      (let [event-id (first origin-event)
            orig-msg (or (when (>= (count origin-event) 2) (nth origin-event 1))
                         {})
            merged   (cond
                       (map? orig-msg) (assoc orig-msg :rf/reply reply-payload)
                       :else           {:rf/reply reply-payload})]
        [event-id merged]))))

;; ---- backoff --------------------------------------------------------------

(def default-backoff
  "rf2-t5mzx (F5) — the single source of truth for the exponential-backoff
  defaults. Previously `:base-ms 250`, `:factor 2`, `:max-ms 5000` were
  spelled inline in `compute-backoff-ms`'s `:or` map AND restated in
  Spec 014 §Retry and backoff prose + the §The shape sample; the two
  copies could silently drift. Naming the def makes the defaults a
  referenceable constant the spec can point at, and any future change
  edits one place."
  {:base-ms 250 :factor 2 :max-ms 5000})

(defn compute-backoff-ms
  "Per Spec 014 §Retry and backoff. Returns the delay in ms before the
  next attempt. `attempt` is the failing attempt number (1-based).

  Defaults (`default-backoff`): base-ms 250, factor 2, max-ms 5000.

  Per Spec 014 §Retry and backoff — `:jitter true` adds random ±25%
  jitter to each delay (offset uniformly in [-0.25 × capped, +0.25 ×
  capped])."
  [{:keys [base-ms factor max-ms jitter]
    :or   {base-ms (:base-ms default-backoff)
           factor  (:factor default-backoff)
           max-ms  (:max-ms default-backoff)}} attempt]
  (let [raw      (* base-ms (Math/pow factor (max 0 (dec attempt))))
        capped   (min raw max-ms)
        ;; (- 1.0 (* 2.0 (rand))) is uniform in [-1.0, +1.0]; scaled by
        ;; 0.25 × capped this yields a true ±25% offset matching the
        ;; spec's stated range. (The earlier impl used (- 0.5 (rand)) ×
        ;; 0.25 × capped which is only ±12.5%.)
        jittered (if jitter
                   (let [offset (* capped 0.25 (- 1.0 (* 2.0 (rand))))]
                     (max 0 (+ capped offset)))
                   capped)]
    (long jittered)))
