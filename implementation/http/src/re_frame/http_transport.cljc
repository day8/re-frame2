(ns re-frame.http-transport
  "Shared transport + attempt-and-retry loop for `:rf.http/managed`.

  The CLJS path uses the Fetch API and the JVM path uses
  `java.net.http.HttpClient`; everything else (`dispatch-reply!`,
  `finalise-success!`, `finalise-failure!`, `maybe-retry!`, the
  4xx/5xx/2xx/else response cascade, the in-flight registry interaction,
  the retry-trace emission, the rf2-bma05 privacy redaction, the
  rf2-lxd3 supersede suppression) is platform-agnostic and shared.
  Platform-specific fragments (`cljs-fetch` + `classify-cljs-error` on
  CLJS; `jvm-fetch` + `classify-jvm-error` + `check-cljs-only-keys!` on
  JVM) are gated with reader conditionals.

  Per-row CLJS-only request keys (`:abort-signal`, `:mode`, `:cache`,
  `:referrer`, `:integrity`) are no-ops on JVM with a one-line trace
  per occurrence via `check-cljs-only-keys!`.

  Per Spec 014 §Failure categories the attempt loop classifies status
  codes BEFORE decode (4xx/5xx route to `:rf.http/http-4xx` /
  `:rf.http/http-5xx` with the raw body-text). Per Spec 014 §Retry and
  backoff `maybe-retry!` decides between retry, immediate-final-failure,
  and successful completion based on the failing attempt's failure
  category and the request's `:retry` config."
  (:require [clojure.string           :as str]
            [re-frame.http-decode     :as decode]
            [re-frame.http-encoding   :as encoding]
            [re-frame.http-middleware :as middleware]
            [re-frame.http-privacy    :as privacy]
            [re-frame.http-registry   :as registry]
            [re-frame.interop         :as interop]
            [re-frame.trace           :as trace])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect
                                  HttpRequest
                                  HttpRequest$BodyPublishers
                                  HttpResponse HttpResponse$BodyHandlers]
                   [java.time Duration]
                   [java.util.concurrent CompletableFuture])))

;; rf2-b45uc — reflection warnings ON in the densest JVM-interop ns of
;; the http surface (HttpClient, HttpRequest, BodyPublishers, HttpHeaders,
;; CompletableFuture). The code is type-hinted today; the flag is the
;; tripwire that catches a future un-hinted interop call compiling to
;; reflective dispatch — exactly where interop is densest. Mirrors
;; http_test_support.cljc:86. CLJS ignores the form.
#?(:clj (set! *warn-on-reflection* true))

;; ---- platform transport: CLJS Fetch ---------------------------------------

#?(:cljs
   (defn- fetch-headers->map [^js fetch-headers]
     (let [out #js {}]
       (.forEach fetch-headers
                 (fn [v k] (aset out k v)))
       (js->clj out))))

#?(:cljs
   (defn- cljs-fetch
     "Issue a single HTTP attempt via Fetch. Returns a Promise that resolves
     to `{:ok? bool :status N :status-text S :headers M :body-text S}` on
     transport-OK, or rejects with an ex-info classified by the caller.

     Per rf2-1jcpm — the per-attempt timeout fires regardless of whether
     the caller supplied `:abort-signal`. We always own
     `internal-controller`; when the caller also supplies `:abort-signal`,
     its `abort` event is forwarded to our controller, so:

     - the caller can still cancel via their own signal, AND
     - the timeout still arms (previously the timeout was silently
       disabled the moment a caller signal arrived, even when the args
       map carried a `:timeout-ms` and/or the security default — round-2
       security audit finding 2).

     The single `signal` Fetch accepts is always our internal one; any
     caller signal funnels through `addEventListener` for cancellation."
     [{:keys [method url headers body credentials mode redirect cache referrer
              integrity timeout-ms abort-signal internal-controller decode]}]
     (let [init     #js {}
           _        (do (aset init "method" (str/upper-case (name method)))
                        (when (seq headers)
                          (let [h #js {}]
                            (doseq [[k v] headers] (aset h k v))
                            (aset init "headers" h)))
                        (when (some? body) (aset init "body" body))
                        (when credentials  (aset init "credentials" (name credentials)))
                        (when mode         (aset init "mode"        (name mode)))
                        (when redirect     (aset init "redirect"    (name redirect)))
                        (when cache        (aset init "cache"       (name cache)))
                        (when referrer     (aset init "referrer"    referrer))
                        (when integrity    (aset init "integrity"   integrity))
                        (when internal-controller
                          (aset init "signal" (.-signal internal-controller)))
                        ;; rf2-1jcpm — forward caller's abort-signal into
                        ;; our internal controller so the timeout still
                        ;; fires when a caller signal is present.
                        (when (and abort-signal internal-controller)
                          (if (.-aborted abort-signal)
                            (try (.abort internal-controller
                                         (or (.-reason abort-signal) "caller-aborted"))
                                 (catch :default _ nil))
                            (.addEventListener
                              abort-signal
                              "abort"
                              (fn []
                                (try (.abort internal-controller
                                             (or (.-reason abort-signal) "caller-aborted"))
                                     (catch :default _ nil)))
                              #js {:once true}))))
           timeout-handle (atom nil)
           promise
           (-> (js/Promise.race
                 #js [(js/fetch url init)
                      (js/Promise. (fn [_ reject]
                                     ;; Per Spec 014 §`:timeout-ms` security
                                     ;; defaults: BOTH `nil` and `0` are
                                     ;; explicit opt-outs (no per-attempt
                                     ;; timeout). `0` is truthy in CLJS, so
                                     ;; `(when timeout-ms …)` would arm a
                                     ;; `setTimeout(…, 0)` that aborts the
                                     ;; request on the next macrotask — the
                                     ;; opposite of "unbounded". The
                                     ;; `(pos? …)` guard collapses
                                     ;; `nil`/`0`/negative to "no timeout".
                                     (when (and timeout-ms
                                                (pos? timeout-ms)
                                                internal-controller)
                                       (reset! timeout-handle
                                               (js/setTimeout
                                                 (fn []
                                                   (try (.abort internal-controller "timeout")
                                                        (catch :default _ nil))
                                                   (reject (ex-info ":rf.error/http-timeout"
                                                                    {:rf.error/id      :rf.error/http-timeout
                                                                     :where            ':rf.http/managed
                                                                     :recovery         :no-recovery
                                                                     :reason           (str "the request exceeded its " timeout-ms "ms timeout and was aborted")
                                                                     ;; co-stamp the registry-hook signal the
                                                                     ;; downstream classifier branches on
                                                                     :rf.http/timeout? true
                                                                     :elapsed-ms       timeout-ms
                                                                     :limit-ms         timeout-ms})))
                                                 timeout-ms)))))])
               (.then (fn [resp]
                        (when-let [h @timeout-handle] (js/clearTimeout h))
                        (let [ok?     (.-ok resp)
                              headers (fetch-headers->map (.-headers resp))
                              base    {:ok?         ok?
                                       :status      (.-status resp)
                                       :status-text (.-statusText resp)
                                       :headers     headers}
                              ;; rf2-5zj6t — a Fetch Response body may be
                              ;; consumed only once, so the body-reader is
                              ;; chosen up front from the resolved `:decode`
                              ;; mode (mirroring `decode-response-body`).
                              ;; Binary modes (`:blob` / `:array-buffer` /
                              ;; `:form-data`) read the native body and ride
                              ;; under `:body-binary`; everything else (and
                              ;; every non-2xx response, whose raw text is
                              ;; what the 4xx/5xx failure paths carry) reads
                              ;; `.text()` into `:body-text`. Decode runs
                              ;; only on 2xx, so a non-OK response always
                              ;; takes the text path regardless of `:decode`.
                              binary-read-mode (when ok?
                                                 (decode/binary-read-kind decode headers))]
                          (case binary-read-mode
                            :blob
                            (-> (.blob resp)
                                (.then (fn [b] (assoc base :body-binary b))))

                            :array-buffer
                            (-> (.arrayBuffer resp)
                                (.then (fn [b] (assoc base :body-binary b))))

                            :form-data
                            (-> (.formData resp)
                                (.then (fn [b] (assoc base :body-binary b))))

                            ;; nil / text-based decode → read text.
                            (-> (.text resp)
                                (.then (fn [body-text]
                                         (assoc base :body-text body-text)))))))))]
       promise)))

#?(:cljs
   (defn- cross-origin?
     "Heuristic: is `url` cross-origin relative to the current page?
     Returns `false` for same-origin URLs, relative URLs (no scheme/host),
     `data:`/`blob:`/`file:` schemes, and any URL the host can't parse.
     The conservative path returns `false` so we never misclassify a
     same-origin Fetch failure as CORS.

     `js/location.origin` is the comparison base in browser hosts; on
     non-browser CLJS targets (Node / shadow-cljs node tests) the global
     is absent and we return `false`."
     [^String url]
     (try
       (let [loc-origin (some-> js/globalThis
                                (aget "location")
                                (aget "origin"))]
         (cond
           (nil? url)        false
           (nil? loc-origin) false
           ;; Relative URLs are always same-origin.
           (or (str/starts-with? url "/")
               (str/starts-with? url "?")
               (str/starts-with? url "#"))           false
           ;; data:/blob:/file: schemes are not http(s) origins; treat
           ;; as not-cross-origin (transport errors there are not CORS).
           (or (str/starts-with? url "data:")
               (str/starts-with? url "blob:")
               (str/starts-with? url "file:"))       false
           :else
           (let [parsed (js/URL. url)
                 origin (.-origin parsed)]
             (and (string? origin)
                  (not= origin loc-origin)))))
       (catch :default _ false))))

#?(:cljs
   (defn- classify-cljs-error
     "Map a Fetch rejection / promise-error to a `:rf.http/*` failure shape.

     Per rf2-r40km (Spec 014 §Failure categories closed-set row
     `:rf.http/cors`): the Fetch API gives no formal signal for a CORS
     rejection — every CORS failure surfaces as a `TypeError` with a
     vendor-specific message (`Failed to fetch`, `Load failed`,
     `NetworkError when attempting to fetch resource`, …). We use a
     conservative heuristic: a `TypeError`-shaped rejection against a
     cross-origin URL classifies as `:rf.http/cors`; everything else
     stays at `:rf.http/transport`. Same-origin transport errors and
     non-`TypeError` rejections are never reclassified, so a real
     network drop on a same-origin endpoint still classifies correctly
     as `:rf.http/transport`.

     CLJS-only — the JVM transport (`classify-jvm-error`) never emits
     `:rf.http/cors` per Spec 014 (CORS is a browser-policy concern)."
     [^js err url]
     (let [data     (when (.-data err) (ex-data err))
           ;; `.-name` on a JS Error is the most stable signal we get
           ;; for the rejection class. Fetch CORS rejections are always
           ;; `TypeError`s; AbortErrors and rf2-bma05 ex-infos take
           ;; their own branches above.
           err-name (some-> err .-name)]
       (cond
         (:rf.http/timeout? data)
         {:kind       :rf.http/timeout
          :elapsed-ms (:elapsed-ms data)
          :limit-ms   (:limit-ms data)}

         (or (= "AbortError" err-name)
             (:rf.http/aborted? data))
         {:kind       :rf.http/aborted
          :request-id (:request-id data)
          :reason     (or (:reason data) :user)}

         ;; rf2-r40km — TypeError + cross-origin URL = CORS rejection.
         ;; Both signals required: the type narrows the universe to
         ;; Fetch-style transport rejections (network drops surface as
         ;; TypeErrors too), the cross-origin check separates CORS-
         ;; eligible URLs from same-origin ones (where CORS doesn't
         ;; apply by definition).
         (and (= "TypeError" err-name)
              (cross-origin? url))
         {:kind    :rf.http/cors
          :message (or (.-message err) (str err))
          :url     url}

         :else
         {:kind    :rf.http/transport
          :message (or (.-message err) (str err))
          :cause   err}))))

;; ---- platform transport: JVM java.net.http.HttpClient ---------------------

#?(:clj
   (defn- redirect->policy
     "Map the request envelope's `:redirect` (Spec 014 §Request envelope:
     `:follow` / `:error` / `:manual`, default `:follow`) onto a JDK
     `HttpClient.Redirect` policy.

     The JDK client only models `ALWAYS` / `NORMAL` / `NEVER` — there is
     no `:manual` (caller-handles-the-3xx) analogue. So:

     - `:follow` (the spec default) → `NORMAL` (follow same-or-more-
       secure redirects; the JDK declines HTTPS→HTTP downgrades, which
       is the safe reading of \"follow\").
     - `:error` / `:manual`         → `NEVER` (do not auto-follow; the
       3xx surfaces to the caller).
     - anything else / nil          → `NORMAL` (honour the spec default).

     The `:error`-vs-`:manual` distinction (error = treat 3xx as a
     failure; manual = hand the raw 3xx back) is not separable on the
     JDK; both collapse to NEVER, which surfaces the 3xx through the
     `:else` arm of `handle-response!`. This is documented as a JVM
     limitation rather than silently dropping the whole key."
     [redirect]
     (case redirect
       :follow HttpClient$Redirect/NORMAL
       :error  HttpClient$Redirect/NEVER
       :manual HttpClient$Redirect/NEVER
       HttpClient$Redirect/NORMAL)))

#?(:clj
   (defn- build-jvm-http-client
     "Build a JDK `HttpClient` for the given redirect policy.

     10s connect timeout — distinct from `:timeout-ms` (which bounds the
     whole request). Caps the TCP/TLS handshake so a black-holed host
     fails fast instead of leaning on the per-request timeout. The
     redirect policy is a per-CLIENT setting on the JDK (not per-request),
     so we memoise one client per distinct policy (`jvm-http-clients`)
     to preserve connection pooling per rf2-ee38b.7."
     [^HttpClient$Redirect policy]
     (-> (HttpClient/newBuilder)
         (.connectTimeout (Duration/ofSeconds 10))
         (.followRedirects policy)
         (.build))))

#?(:clj
   (defonce ^:private jvm-http-clients
     ;; redirect-policy → memoised HttpClient. The JDK applies its
     ;; redirect policy at the client level, not per request, so honouring
     ;; the spec's `:redirect` envelope key (default `:follow`) requires a
     ;; client per policy. Two policies are live in practice (NORMAL for
     ;; `:follow`, NEVER for `:error`/`:manual`), so this caches at most a
     ;; handful of clients while preserving each one's connection pool.
     (atom {})))

#?(:clj
   (defn- jvm-http-client-for
     "Return the memoised JDK `HttpClient` honouring `redirect` (Spec 014
     §Request envelope default `:follow`)."
     [redirect]
     (let [policy (redirect->policy redirect)]
       (or (get @jvm-http-clients policy)
           (get (swap! jvm-http-clients
                       (fn [m]
                         (if (contains? m policy)
                           m
                           (assoc m policy (build-jvm-http-client policy)))))
                policy)))))

#?(:clj
   (defn- jvm-build-request
     [{:keys [method url headers body timeout-ms sensitive?]}]
     (let [b (HttpRequest/newBuilder (URI/create url))
           publisher (cond
                       (nil? body) (HttpRequest$BodyPublishers/noBody)
                       (string? body) (HttpRequest$BodyPublishers/ofString ^String body)
                       (bytes? body) (HttpRequest$BodyPublishers/ofByteArray ^bytes body)
                       :else (HttpRequest$BodyPublishers/ofString ^String (str body)))]
       (.method b (str/upper-case (name method)) publisher)
       ;; Per Spec 014 §`:timeout-ms` security defaults: BOTH `nil` and
       ;; `0` are explicit opt-outs (no per-attempt timeout). `0` is
       ;; truthy in Clojure, so `(when timeout-ms …)` is NOT enough — and
       ;; `(Duration/ofMillis 0)` throws `IllegalArgumentException` on the
       ;; JDK, surfacing the opt-out as a spurious `:rf.http/transport`
       ;; failure. The `(pos? …)` guard collapses `nil`/`0`/negative to
       ;; "no timeout" so the JDK request carries no per-request deadline.
       (when (and timeout-ms (pos? timeout-ms))
         (.timeout b (Duration/ofMillis (long timeout-ms))))
       (doseq [[k v] headers]
         ;; rf2-9lun0 — surface JDK HttpClient header-validation throws
         ;; via a `:rf.warning/http-header-invalid` trace rather than
         ;; silently dropping. Stray `\r`/`\n` / control chars / empty
         ;; name / forbidden header (`Host`, `Connection`, …) all hit
         ;; this path. Security-relevant middleware (e.g. auth-header
         ;; attachment) needs the signal — the canonical "swallowed
         ;; error" anti-pattern. The request still proceeds without
         ;; the offending header so a stray bad header doesn't sink
         ;; an otherwise-valid request; the trace is the alarm.
         ;;
         ;; rf2-1jcpm — the `:url` slot on the warning event is routed
         ;; through `privacy/prepare-emit-tags` so a denylisted query
         ;; param (`?api_key=…`) is scrubbed and `:sensitive?` is
         ;; stamped when the request is sensitive. Previously the raw
         ;; URL rode the trace surface even when the handler / request
         ;; was declared sensitive.
         (try (.header b (str k) (str v))
              (catch Throwable t
                (when interop/debug-enabled?
                  (trace/emit! :warning :rf.warning/http-header-invalid
                               (privacy/prepare-emit-tags
                                 {:url     url
                                  :header  (str k)
                                  :cause   (.getMessage t)}
                                 (true? sensitive?)))))))
       (.build b))))

#?(:clj
   (defn- jvm-headers->map
     "Flatten Java HttpHeaders into a plain Clojure map.

     Header names are lower-cased at the boundary, matching the CLJS
     Fetch path (`fetch-headers->map` consumes Fetch's `Headers` object,
     which iterates with normalised lower-case names). Per Spec 014
     §Request envelope, HTTP header names are case-insensitive — fixing
     casing at the transport boundary keeps every downstream consumer
     (decode sniffer, failure-map headers ridden by `:on-failure` reply
     payloads, privacy redactor) on a single canonical shape across
     hosts.

     Per rf2-0xvm1 — per-header value shape is `string` for the
     single-value case and `vector-of-strings` for the multi-valued
     case (the spec's `string → string (or string → vector of strings
     for multi-valued)` branch). The previous comma-join
     (`(str/join \",\" vs)`) was wrong for `Set-Cookie`: cookie values
     legally embed commas (e.g. `Expires=Wed, 21 Oct 2026 ...`), so
     comma-joining N `Set-Cookie:` lines produced a single unparseable
     string. RFC 6265 §3 forbids comma-folding `Set-Cookie` for exactly
     this reason; RFC 7230 §3.2.2 generalises the rule (header values
     containing literal commas must not be folded into a single field).
     Vector-on-multi preserves the original lines verbatim — every
     `Set-Cookie:` line is its own element and downstream consumers can
     parse each independently. Single-valued headers (the 99% case)
     keep the string shape so the common path doesn't pay a vector
     allocation or destructuring tax."
     [^java.net.http.HttpHeaders hh]
     (into {}
           (for [[k vs] (.map hh)]
             [(str/lower-case k)
              (if (= 1 (count vs))
                (first vs)
                (vec vs))]))))

#?(:clj
   (defn- jvm-fetch
     "Issue a single HTTP attempt via java.net.http.HttpClient. Returns a
     CompletableFuture that completes with `{:ok? :status :status-text
     :headers :body-text}` or completes-exceptionally with an ex-info.

     `opts` carries `:sensitive?` so `jvm-build-request` can route any
     header-validation warning through the privacy composer (rf2-1jcpm).
     `opts` carries `:redirect` (Spec 014 §Request envelope, default
     `:follow`) so the client honouring the right redirect policy is
     selected per rf2-ee38b.7."
     [opts]
     (let [client ^HttpClient (jvm-http-client-for (:redirect opts))
           req    (jvm-build-request opts)
           future-resp (.sendAsync client req
                                   (HttpResponse$BodyHandlers/ofString))]
       (.thenApply future-resp
                   (reify java.util.function.Function
                     (apply [_ resp]
                       (let [^HttpResponse r resp
                             status (.statusCode r)]
                         {:ok? (and (>= status 200) (< status 300))
                          :status status
                          :status-text ""
                          :headers (jvm-headers->map (.headers r))
                          :body-text (.body r)})))))))

#?(:clj
   (defn- classify-jvm-error
     "Map a JVM-side throwable to a `:rf.http/*` failure shape.

     Per rf2-q3ts4: the JDK reliably surfaces `HttpTimeoutException` for
     per-attempt timeouts and `CancellationException` for explicit
     cancellations, so we narrow to instance-checks only. The earlier
     `str/includes? msg \"timed out\"` / `\"abort\"` fallbacks could
     misclassify a downstream service's error body (whose message
     happened to contain those words) as `:rf.http/timeout` /
     `:rf.http/aborted`, polluting the failure taxonomy. Anything not
     matching an instance check stays at `:rf.http/transport` — the
     correct catch-all for unknown JDK failures.

     Per rf2-ee38b.7 the optional `timeout-ms` (the configured per-attempt
     limit, in scope at the `run-attempt!` call sites) fills the
     `:limit-ms` tag on a timeout failure so the JVM shape matches the
     CLJS path (Spec 014 §Failure categories types `:rf.http/timeout` as
     `:elapsed-ms` / `:limit-ms`). `:elapsed-ms` stays nil on the JVM —
     the JDK's `HttpTimeoutException` does not surface the elapsed wall
     clock, and there is no faithful value to synthesise."
     ([^Throwable t] (classify-jvm-error t nil))
     ([^Throwable t timeout-ms]
      (let [cause (or (.getCause t) t)
            msg   (.getMessage cause)
            cls   (.getName (class cause))]
        (cond
          (instance? java.net.http.HttpTimeoutException cause)
          {:kind :rf.http/timeout :elapsed-ms nil :limit-ms timeout-ms :message msg}

          (instance? java.util.concurrent.CancellationException cause)
          {:kind :rf.http/aborted :reason :user :message msg}

          :else
          {:kind :rf.http/transport :message msg :cause cls})))))

;; ---- per-row CLJS-only-key tracing on JVM ---------------------------------

#?(:clj
   (defn- emit-cljs-only-skipped! [k url sensitive?]
     (when interop/debug-enabled?
       ;; rf2-1jcpm — route through the privacy composer so a denylisted
       ;; query param in the URL is scrubbed and `:sensitive?` is stamped
       ;; on the warning event when the originating handler / request is
       ;; sensitive. Previously the raw URL rode the trace surface.
       (trace/emit! :warning :rf.http/cljs-only-key-ignored-on-jvm
                    (privacy/prepare-emit-tags
                      {:key k :url url}
                      (true? sensitive?))))))

#?(:clj
   (defn check-cljs-only-keys! [{:keys [request abort-signal]} sensitive?]
     (let [url (:url request)]
       ;; rf2-ee38b.7 — `:credentials` joins the JVM-degraded set. Unlike
       ;; `:redirect` (now honoured on JVM via the redirect-policy client),
       ;; the browser same-origin/include cookie model has no faithful
       ;; `HttpClient` analogue — the shared client configures no
       ;; CookieHandler, so cookies are neither sent nor stored regardless
       ;; of the value. Rather than silently no-op, emit the standard
       ;; `:rf.http/cljs-only-key-ignored-on-jvm` trace so the dropped key
       ;; is visible to off-box monitors. Spec 014 §JVM degradation table
       ;; carries the `:credentials` row (rf2-t5mzx) so the table and the
       ;; shipped behaviour agree.
       (doseq [k [:mode :cache :referrer :integrity :credentials]]
         (when (contains? request k)
           (emit-cljs-only-skipped! k url sensitive?)))
       (when abort-signal
         (emit-cljs-only-skipped! :abort-signal url sensitive?)))))

;; A no-op JVM-only no-op stub on CLJS so callers can reach
;; `http-transport/check-cljs-only-keys!` unconditionally without
;; needing their own reader-conditional. The CLJS body would do nothing
;; — by definition CLJS-only keys are always honoured on CLJS.
#?(:cljs
   (defn check-cljs-only-keys! [_args _sensitive?] nil))

;; ---- shared attempt-and-retry loop ----------------------------------------

(declare run-attempt!)
(declare finalise-failure!)
(declare schedule-backoff-handle!)

(defn- dispatch-reply!
  "Threads the reply-payload through the per-frame `:after` interceptor
  chain (REVERSE registration order) BEFORE handing off to the
  late-bind router for `:on-success` / `:on-failure` dispatch.

  Per rf2-uheqq + Spec 014 §Middleware: each `:after` sees `(ctx,
  response)` — `ctx` is the SAME middleware-ctx the `:before` chain
  produced for this request (carried forward via the normalised ctx's
  `:middleware-ctx` slot, populated by `managed-handler`). The
  `:after` chain may transform the response shape; its return value is
  what `build-reply-event` appends to the user's `:on-success` /
  `:on-failure` event vector.

  When no middleware-ctx is in scope (synthetic / test-path callers that
  build a ctx directly without going through `managed-handler`), the
  `:after` chain is skipped and the reply-payload passes through
  unchanged — the chain's contract is to see the `:before`'s ctx, not a
  synthesised one."
  [{:keys [origin-event explicit-on-success explicit-on-failure
           kind reply-payload frame middleware-ctx]}]
  (let [explicit       (case kind
                         :success explicit-on-success
                         :failure explicit-on-failure)
        ;; rf2-uheqq — `:after` chain. Reverse-order threading of the
        ;; response through registered interceptors' `:after`s. Skipped
        ;; when no middleware-ctx is present (synthetic callers).
        final-payload  (if middleware-ctx
                         (middleware/run-after-chain! frame middleware-ctx reply-payload)
                         reply-payload)]
    (encoding/dispatch-reply-via-late-bind!
      {:origin-event  origin-event
       :explicit-on   explicit
       :reply-payload final-payload
       :kind          kind}
      frame)))

;; rf2-ee38b.7 — the failure-reply and success-reply dispatch shapes were
;; spelled out inline at four / two sites across finalise-success!,
;; finalise-failure! and the abort path's dispatch-aborted!. These two
;; helpers collapse each to one line and make the abort/natural symmetry
;; the surrounding comments describe visible in code. The load-bearing
;; concurrency comments stay at the call sites.

(defonce ^:private failure-swallowed-warned?
  ;; rf2-rl5tt — one-shot latch so the "real failure swallowed by
  ;; `:on-failure nil`" warning fires once per runtime, not once per
  ;; swallowed request. Fire-and-forget telemetry beacons (`:on-failure
  ;; nil`) are a legitimate steady-state pattern, so a per-request trace
  ;; would be noise; the single warning makes the FIRST silently-dropped
  ;; non-aborted failure visible (the no-silent-swallow principle) without
  ;; flooding the trace surface for callers who knowingly opted out.
  (atom false))

(defn- warn-failure-swallowed!
  "rf2-rl5tt — surface a swallowed REAL failure once per runtime.

  When a request fails and the caller passed an explicit `:on-failure
  nil`, `build-reply-event` legitimately silences the reply (fire-and-
  forget). But a NON-aborted failure (transport / 5xx / decode / accept /
  timeout) routed into that silence is a real error the app never sees —
  the anti-pattern the committed no-silent-swallow principle calls out.
  Emit a one-shot `:rf.warning/failure-swallowed` so the dropped failure
  is observable in dev / tooling.

  Aborts (`:rf.http/aborted`, any reason) are EXCLUDED: a cancelled
  request that no longer wants its reply is correct-by-design silence,
  not a swallowed error."
  [failure url sensitive?]
  (when (and interop/debug-enabled?
             (not= :rf.http/aborted (:kind failure))
             (compare-and-set! failure-swallowed-warned? false true))
    (trace/emit! :warning :rf.warning/failure-swallowed
                 (privacy/prepare-emit-tags
                   {:url     url
                    :failure failure
                    :reason  (str "an HTTP request failed with `:kind "
                                  (pr-str (:kind failure))
                                  "` but `:on-failure nil` silenced the "
                                  "reply — the failure was dropped with no "
                                  "handler. If the silence is intentional "
                                  "(fire-and-forget telemetry), ignore this; "
                                  "otherwise supply an `:on-failure` target.")}
                   (true? sensitive?)))))

(defn- on-failure-silenced?
  "True when the ctx carries an explicit `:on-failure nil` — the exact
  condition under which `build-reply-event` silences a `:failure` reply
  (`:supplied?` true with a `nil` `:value`). Mirrors the encoding-side
  silence branch so the swallow-warning fires for precisely the replies
  that get dropped."
  [ctx]
  (let [explicit (:explicit-on-failure ctx)]
    (and (:supplied? explicit) (nil? (:value explicit)))))

(defn- dispatch-failure!
  "Dispatch a `:failure` reply carrying `failure` as its `:failure` slot.

  rf2-rl5tt — when the reply is silenced by an explicit `:on-failure nil`
  AND the failure is not an abort, surface it once via
  `warn-failure-swallowed!` before the (no-op) dispatch."
  [ctx failure]
  (when (on-failure-silenced? ctx)
    (warn-failure-swallowed! failure (:url ctx) (:sensitive? ctx)))
  (dispatch-reply! (assoc ctx
                          :kind          :failure
                          :reply-payload {:kind    :failure
                                          :failure failure})))

(defn- dispatch-success!
  "Dispatch a `:success` reply carrying `value` as its `:value` slot."
  [ctx value]
  (dispatch-reply! (assoc ctx
                          :kind          :success
                          :reply-payload {:kind  :success
                                          :value value})))

(defn- dispatch-aborted!
  "Emit the `:rf.http/aborted` trace + dispatch the abort reply for a
  cancelled request, honouring rf2-lxd3 supersede suppression.

  Shared by BOTH cancellation states (rf2-wj8vv):
   - the in-flight-fetch abort-fn in `run-attempt!` (a fetch / future is
     live), and
   - the backoff-sleeping abort-fn in `maybe-retry!` (no fetch is live;
     a retry timer is pending).

  Both flip their once-only `:finalised?` cell, perform the state-
  specific teardown (CLJS `.abort` / JVM `.cancel` vs `clear-timeout!`),
  clear the registry, then land here so an aborted request looks
  identical to consumers regardless of which lifecycle phase it was in.
  `reason` is `:user` / `:actor-destroyed` / `:request-id-superseded` /
  `:timeout`. `ctx` must carry `:request-id`, `:actor-id`, `:url`,
  `:sensitive?`."
  [ctx reason]
  (let [failure {:kind       :rf.http/aborted
                 :request-id (:request-id ctx)
                 :reason     reason
                 :actor-id   (:actor-id ctx)}]
    (when interop/debug-enabled?
      (let [sensitive? (true? (:sensitive? ctx))
            redacted   (privacy/prepare-emit-failure
                         (assoc failure
                                :url      (:url ctx)
                                :recovery :no-recovery)
                         sensitive?)]
        (trace/emit-error! :rf.http/aborted redacted)))
    ;; Per rf2-lxd3 — supersede semantics suppress the reply. Other
    ;; abort reasons (`:user`, `:actor-destroyed`, `:timeout`) all
    ;; dispatch the failure reply normally.
    (when-not (= :request-id-superseded reason)
      (dispatch-failure! ctx failure))))

(defn- already-replied?
  "rf2-on7sj — the once-only reply guard. The handle carries a
  `:finalised?` atom stamped at `record-in-flight!` time; the abort
  path AND the natural-completion paths both reach `finalise-*` and
  must NOT both dispatch a reply for the same request. CAS the flag
  from false→true on first arrival; subsequent calls see `true` and
  bail. Returns truthy when the caller MUST NOT proceed (already
  replied OR no handle present at all — defensive, see below).

  Synthetic / test-path callers may pass a ctx with no `:handle`
  (e.g. some failure-shape unit tests build ctx maps directly). In
  that case the guard is a no-op — the flag's nil and the call
  proceeds. The real run-attempt! path always stamps a handle."
  [ctx]
  (when-let [flag (:finalised? (:handle ctx))]
    (not (compare-and-set! flag false true))))

(defn- aborted-snapshot
  "rf2-wez75 — abort-always-wins precedence (Mike decision a, aligned with
  Fetch AbortController / Node HTTP / JVM HttpClient / gRPC universal
  convention). Returns the abort-state map `{:reason :actor-id}` if the
  handle's `:aborted?` atom has been flipped (by either `:rf.http/managed-
  abort` user-aborts OR `abort-on-actor-destroy` per Spec 014 §Abort on
  actor destroy), else nil. Read once at finalise-* entry so the late-
  arriving decode / status / transport classification gets reclassified
  to `:rf.http/aborted` rather than racing the abort-fn's CAS — see Spec
  014 §Abort precedence (abort always wins)."
  [ctx]
  (when-let [abort-cell (:aborted? (:handle ctx))]
    @abort-cell))

(defn- aborted-failure
  "Build the `:rf.http/aborted` failure shape from an abort snapshot."
  [ctx abort-state]
  {:kind       :rf.http/aborted
   :request-id (:request-id ctx)
   :reason     (:reason abort-state)
   :actor-id   (or (:actor-id abort-state) (:actor-id ctx))})

(defn- finalise-success! [ctx accepted]
  ;; rf2-wez75 — abort-precedence check. Two sampling points:
  ;;   (1) BEFORE the once-only CAS — covers the case where abort-fn
  ;;       already flipped `:aborted?` and lost the CAS to a
  ;;       synchronously-completing decode.
  ;;   (2) AFTER winning the CAS — covers the narrower window where
  ;;       abort-fn flips `:aborted?` between our sample-(1) read and
  ;;       our CAS. Sampling after the CAS pins the contract:
  ;;       any abort observed by a flag we hold ownership of has
  ;;       precedence over the success-classification reply.
  ;; Together these close every interleaving consistent with the
  ;; abort-always-wins rule (Spec 014 §Abort precedence). The CAS-loser
  ;; case (sample (1)) routes through finalise-failure! so the trace
  ;; emit + supersede-suppression path stays in one place.
  (if-let [abort-state (aborted-snapshot ctx)]
    (finalise-failure! ctx (aborted-failure ctx abort-state))
    (when-not (already-replied? ctx)
      (registry/clear-in-flight! (:request-id ctx) (:handle ctx))
      (if-let [post-cas-abort (aborted-snapshot ctx)]
        ;; Sample (2): abort flipped between our pre-CAS sample and
        ;; our CAS-win. We hold the once-only token; dispatch the
        ;; aborted reply directly rather than re-entering
        ;; finalise-failure! (which would double-clear the registry
        ;; and re-check `already-replied?`).
        (let [failure (aborted-failure ctx post-cas-abort)]
          (when interop/debug-enabled?
            (let [sensitive? (true? (:sensitive? ctx))
                  redacted   (privacy/prepare-emit-failure
                               (assoc failure
                                      :url      (:url ctx)
                                      :recovery :no-recovery)
                               sensitive?)]
              (trace/emit-error! :rf.http/aborted redacted)))
          (when-not (= :request-id-superseded (:reason failure))
            (dispatch-failure! ctx failure)))
        (cond
          (contains? accepted :ok)
          (dispatch-success! ctx (:ok accepted))

          (contains? accepted :failure)
          (dispatch-failure! ctx {:kind       :rf.http/accept-failure
                                  :detail     (:failure accepted)
                                  :decoded    (:decoded ctx)
                                  :request-id (:request-id ctx)}))))))

(defn- finalise-failure!
  "Final-failure dispatch (after retries exhausted or non-retriable).

  Per rf2-lxd3: when a fresh request supersedes a prior one with the
  same `:request-id`, the prior request's `:on-failure` reply is NOT
  dispatched (the supersede semantic = the new request replaces the
  old one — the original `:on-failure` would race the new request's
  outcome and corrupt debounce-search patterns). The supersede event
  still emits to the trace bus (`:rf.http/aborted` with
  `:reason :request-id-superseded`); consumers wanting abort telemetry
  subscribe via `register-listener!`.

  Per rf2-on7sj: guarded by the once-only `:finalised?` CAS so the
  abort path and a later natural-completion path can't both dispatch
  a reply for the same request. The trace emit + registry clear ALSO
  live inside the guard — a doubled trace would be just as observable
  as a doubled reply on the dev surface.

  Per rf2-wez75 (Mike decision a, abort-always-wins — aligned with
  Fetch AbortController / Node HTTP / JVM HttpClient / gRPC universal
  convention): the abort-precedence check fires BEFORE the CAS. If the
  handle's `:aborted?` cell has been flipped (by user abort OR
  actor-destroy), the incoming `failure` is replaced by the canonical
  `:rf.http/aborted` shape before trace-emit and reply-dispatch. This
  closes the window where a decode-failure / transport / 5xx
  classification could synchronously win the once-only `:finalised?`
  CAS in the same scheduler tick the abort-fn was running — the
  user-visible outcome is now deterministic by classification, not by
  CAS race ordering. See Spec 014 §Abort precedence (abort always wins)."
  [ctx failure]
  (when-not (already-replied? ctx)
    (let [;; rf2-wez75 — abort-precedence reclassification. Sampled
          ;; AFTER winning the once-only CAS so any abort-fn that
          ;; flipped `:aborted?` between our caller's classification
          ;; and this point is still observed. The abort-fn itself
          ;; flips the cell BEFORE racing the CAS, so the cell is
          ;; monotonic-set under contention — once flipped, every
          ;; subsequent sample reads non-nil.
          abort-state (aborted-snapshot ctx)
          effective   (if (and abort-state
                               (not= :rf.http/aborted (:kind failure)))
                        (aborted-failure ctx abort-state)
                        failure)]
      (registry/clear-in-flight! (:request-id ctx) (:handle ctx))
      (when interop/debug-enabled?
        ;; rf2-bma05 — redact response-side payload slots (body, body-text,
        ;; decoded, detail) and the headers denylist before the trace
        ;; surface sees them; stamp :sensitive? when applicable. The CLJS
        ;; and JVM transports share the same contract.
        (let [sensitive? (true? (:sensitive? ctx))
              redacted   (privacy/prepare-emit-failure
                           (assoc effective
                                  :request-id (:request-id ctx)
                                  :url        (:url ctx)
                                  :recovery   :no-recovery)
                           sensitive?)]
          (trace/emit-error! (:kind effective) redacted)))
      (let [superseded? (and (= :rf.http/aborted (:kind effective))
                             (= :request-id-superseded (:reason effective)))]
        (when-not superseded?
          (dispatch-failure! ctx effective))))))

(defn- schedule-backoff-handle!
  "rf2-wj8vv — arm the retry backoff timer AND keep the request
  registered (and therefore cancellable) for the whole backoff window.

  Two cells coordinate the timer-fires-vs-cancel race:
   - `fired?` is a once-only CAS owned jointly by the timer callback and
     the abort-fn. Whoever wins it owns the transition out of the
     backoff state; the loser is a no-op. This is the source of truth —
     even if `clear-timeout!` races and misses (e.g. a JVM cancel that
     arrives after the timer thread has begun but before our cell flips),
     a timer callback that LOST the CAS bails before issuing the next
     attempt, so no retry ever fires after a cancel.
   - `timer-cell` forwards the scheduler handle to the abort-fn closure,
     which is constructed before the handle exists. The abort-fn reads
     it lazily and calls `interop/clear-timeout!` (a best-effort fast
     cancel layered over the authoritative `fired?` CAS).

  The backoff handle carries the same `:request-id` / `:actor-id` /
  `:url` / `:sensitive?` shape every cancellation path expects, so
  `:rf.http/managed-abort`, `abort-on-actor-destroy`, and `supersede!`
  all cancel a sleeping request through their existing `:abort-fn`
  dispatch with no path-specific code.

  `interop/set-timeout!` / `interop/clear-timeout!` are defined on both
  platforms (CLJS: `js/setTimeout` / `js/clearTimeout`; JVM:
  `ScheduledExecutorService` + `ScheduledFuture.cancel`), so the backoff
  scheduling and its cancellation are uniform across hosts."
  [ctx delay-ms]
  (let [{:keys [request-id actor-id]} ctx
        fired?     (atom false)
        timer-cell (atom nil)
        abort-fn   (fn [reason]
                     ;; Win the once-only transition; a concurrent timer
                     ;; fire that loses here bails without retrying.
                     (when (compare-and-set! fired? false true)
                       (when-let [t @timer-cell]
                         (interop/clear-timeout! t))
                       ;; Drop the backoff handle from both indexes — the
                       ;; same teardown a live-fetch abort performs.
                       (registry/clear-in-flight! request-id)
                       (dispatch-aborted! ctx reason)))
        handle     (registry/record-in-flight!
                     request-id actor-id
                     {:abort-fn   abort-fn
                      :url        (:url ctx)
                      :sensitive? (true? (:sensitive? ctx))})
        ;; Schedule AFTER registering so the request is cancellable the
        ;; instant the timer is armed. The callback wins/loses the same
        ;; `fired?` CAS: on a win it clears its own handle and proceeds;
        ;; on a loss (a cancel beat it) it does nothing.
        timer      (interop/set-timeout!
                     (fn []
                       (when (compare-and-set! fired? false true)
                         (registry/clear-in-flight! request-id handle)
                         (run-attempt! (-> ctx
                                           (dissoc :handle)
                                           (update :attempt inc)))))
                     delay-ms)]
    (reset! timer-cell timer)
    ;; rf2-wj8vv — a cancel that arrived between `record-in-flight!` and
    ;; this `reset!` already won `fired?` (so the timer callback will
    ;; no-op) but may have read `timer-cell` as nil and skipped
    ;; `clear-timeout!`. Re-check and cancel the now-known timer so the
    ;; scheduler doesn't carry a doomed task for the full backoff.
    (when @fired?
      (interop/clear-timeout! timer))
    nil))

(defn- maybe-retry!
  "Decide between retry, immediate-final-failure, and successful-completion.
  `failure` is the failure map for the just-finished attempt.

  Per rf2-wez75 (abort always wins): a request whose handle's
  `:aborted?` cell has been flipped MUST NOT be retried, regardless of
  the just-classified failure category or the caller's `:retry :on`
  set. A user/actor-destroy abort that arrives mid-decode-failure
  retry-eligible classification would otherwise schedule a fresh
  attempt against a request the caller has already cancelled — a
  contract violation under Spec 014 §Abort precedence. Routing the
  aborted request through `finalise-failure!` lets the in-flight
  reclassification (built into finalise-failure!'s abort-snapshot
  read) replace the would-be retry-eligible failure with the canonical
  `:rf.http/aborted` shape.

  Per rf2-wj8vv (backoff window is cancellable): when a retry is
  scheduled, the request stays REGISTERED for the whole backoff window
  under a `schedule-backoff-handle!` handle whose `:abort-fn` cancels
  the pending retry timer and clears the registry, rather than firing
  a network abort (there is no live fetch between attempts). All three
  cancellation paths — `:rf.http/managed-abort`, `abort-on-actor-
  destroy`, `supersede!` — resolve a handle and fire its `:abort-fn`,
  so registering the backoff handle is sufficient to make the sleeping
  request cancellable through every path with no path-specific code.
  Previously `maybe-retry!` cleared the handle from both indexes BEFORE
  arming the timer, leaving the request invisible to every cancellation
  path for the whole backoff — the timer fired regardless."
  [ctx failure]
  (let [{:keys [retry attempt request-id]} ctx
        {:keys [on max-attempts backoff]} retry
        on-set      (or on #{})
        kind        (:kind failure)
        aborted?    (some? (aborted-snapshot ctx))
        can-retry?  (and (some? max-attempts)
                         (> max-attempts attempt)
                         (contains? on-set kind)
                         (not= :rf.http/aborted kind)
                         (not aborted?))]
    (if can-retry?
      (let [delay-ms (encoding/compute-backoff-ms (or backoff {}) attempt)]
        (when interop/debug-enabled?
          (trace/emit! :info :rf.http/retry-attempt
                       (privacy/prepare-emit-tags
                         {:request-id      request-id
                          :url             (:url ctx)
                          :attempt         attempt
                          :max-attempts    max-attempts
                          :failure         failure
                          :next-backoff-ms delay-ms}
                         (true? (:sensitive? ctx)))))
        ;; Clear the prior attempt's live-fetch handle from both indexes;
        ;; `schedule-backoff-handle!` immediately re-registers a fresh
        ;; backoff handle so the request is never invisible to a
        ;; cancellation path. Without the clear the actor-in-flight index
        ;; would accumulate stale handles across retries (rf2-wvkn).
        (registry/clear-in-flight! request-id (:handle ctx))
        (schedule-backoff-handle! ctx delay-ms))
      (do
        ;; Final attempt: emit retry-attempt with nil next-backoff if any retries occurred.
        (when (and interop/debug-enabled?
                   (some? max-attempts)
                   (> max-attempts 1))
          (trace/emit! :info :rf.http/retry-attempt
                       (privacy/prepare-emit-tags
                         {:request-id      request-id
                          :url             (:url ctx)
                          :attempt         attempt
                          :max-attempts    max-attempts
                          :failure         failure
                          :next-backoff-ms nil}
                         (true? (:sensitive? ctx)))))
        (finalise-failure! ctx failure)))))

(defn- handle-response!
  "Shared 4xx/5xx/2xx/else response cascade. `result` is the platform
  transport's normalised response map (`{:ok? :status :status-text
  :headers :body-text}`). Per Spec 014 §Failure categories: status
  classification runs BEFORE decode. 4xx/5xx route to
  `:rf.http/http-4xx` / `:rf.http/http-5xx` with the raw body-text —
  decode never fires on a non-success response, so an HTML 404 from a
  JSON endpoint classifies as `:rf.http/http-4xx` (not
  `:rf.http/decode-failure`). Decode runs only on 2xx; if that fails
  the failure category is `:rf.http/decode-failure`."
  [ctx result]
  (let [{:keys [decode decode-supplied? accept request-id url]} ctx
        {:keys [ok? status status-text headers body-text body-binary]} result]
    (cond
      (and (>= status 400) (< status 500))
      (maybe-retry! ctx
                    {:kind        :rf.http/http-4xx
                     :status      status
                     :status-text status-text
                     :body        body-text
                     :headers     headers})

      (>= status 500)
      (maybe-retry! ctx
                    {:kind        :rf.http/http-5xx
                     :status      status
                     :status-text status-text
                     :body        body-text
                     :headers     headers})

      ok?
      (try
        (let [decoded  (decode/decode-response-body
                         {:body-text        body-text
                          ;; rf2-5zj6t — the CLJS transport reads a native
                          ;; Blob / ArrayBuffer / FormData for binary decode
                          ;; modes and rides it here; `decode-response-body`
                          ;; returns it verbatim for `:blob` / `:array-buffer`
                          ;; / `:form-data`. Absent on the text path / on JVM.
                          :body-binary      body-binary
                          :headers          headers
                          :decode           decode
                          :decode-supplied? decode-supplied?
                          :request-id       request-id
                          :url              url
                          ;; rf2-xuvj7 — the originating event-id keys the
                          ;; one-shot-per-handler decode-defaulted latch.
                          ;; Nil for synthetic / test-path callers with no
                          ;; origin event; the latch degrades to a shared
                          ;; runtime-wide slot in that case.
                          :handler-id       (first (:origin-event ctx))
                          :sensitive?       (:sensitive? ctx)
                          ;; rf2-wu1n5 — thread the keyword-cap from the
                          ;; normalised ctx into the decoder; nil means
                          ;; the reader uses its default.
                          :max-decoded-keys (:max-decoded-keys ctx)})
              accepted (encoding/run-accept accept decoded)]
          (finalise-success! (assoc ctx :decoded decoded) accepted))
        (catch #?(:clj Throwable :cljs :default) e
          (let [d (ex-data e)]
            (maybe-retry!
              ctx
              {:kind                       :rf.http/decode-failure
               :body-text                  body-text
               :cause                      #?(:clj (.getMessage ^Throwable e)
                                              :cljs (.-message e))
               :schema-validation-failure? (= :rf.error/http-schema-validation-failed
                                              (:rf.error/id d))}))))

      :else
      ;; Non-2xx that didn't fall in 4xx/5xx (e.g., 1xx/3xx that the
      ;; runtime didn't follow) — surface as 4xx-shaped failure with
      ;; the raw body-text. Per rf2-ee38b.7 this routes through
      ;; `maybe-retry!` (was `finalise-failure!` directly) so the retry
      ;; semantics match the `:rf.http/http-4xx` category label: a caller
      ;; with `:retry {:on #{:rf.http/http-4xx} …}` retries a real 4xx,
      ;; and this synthetic-4xx (1xx/3xx) now retries consistently rather
      ;; than silently never retrying. The branch is rare in practice (the
      ;; JVM `NORMAL` / Fetch stacks follow 3xx by default), but the
      ;; inconsistency is removed.
      (maybe-retry!
        ctx
        {:kind        :rf.http/http-4xx
         :status      status
         :status-text status-text
         :body        body-text
         :headers     headers}))))

(defn run-attempt!
  "Issue one HTTP attempt, then dispatch reply or retry. Platform-specific
  transport wiring (Fetch Promise on CLJS, CompletableFuture on JVM) is
  reader-conditional; the response cascade, retry decision, in-flight
  registry interaction, privacy redaction, and supersede suppression are
  all shared."
  [ctx]
  (let [{:keys [request timeout-ms request-id actor-id abort-signal]} ctx
        method   (or (:method request) :get)
        url      (encoding/merge-params (:url request) (:params request))
        ;; rf2-sz4n0 — `:body` may be a thunk (Spec 014 §Body encoding); each
        ;; attempt re-invokes it to obtain a fresh handle. Single call site,
        ;; inlined per the audit.
        body     (let [b (:body request)] (if (fn? b) (b) b))
        [enc-body ct] (encoding/encode-body body (:request-content-type request))
        headers  (cond-> (or (:headers request) {})
                   (and ct (nil? (decode/content-type-of (:headers request))))
                   (assoc "Content-Type" ct))
        ctx-no-handle (assoc ctx :url url)
        ;; CLJS: per rf2-1jcpm always own an internal AbortController so
        ;; the per-attempt timeout fires even when the caller supplied
        ;; `:abort-signal`. `cljs-fetch` forwards the caller's signal into
        ;; this controller via `addEventListener "abort"`. JVM: no per-
        ;; attempt controller — abort signalling is host-specific and
        ;; lives outside the sendAsync future.
        #?@(:cljs [internal-controller (js/AbortController.)])
        ;; rf2-on7sj — once-only reply guard. The abort path AND the
        ;; subsequent natural-completion path (Fetch .catch on CLJS,
        ;; CompletableFuture .whenComplete on JVM) both fan into
        ;; finalise-*; without this CAS each slow-server abort would
        ;; dispatch TWO replies for the same request — first the
        ;; synthesised :rf.http/aborted (immediate), then the natural-
        ;; completion reply (much later, after the underlying transport
        ;; drains). The flag is stamped on the handle map; finalise-*
        ;; reads it via `(:finalised? (:handle ctx))` and the abort-fn
        ;; closure CASes the same atom lexically before dispatching the
        ;; abort reply itself. JVM additionally cancels the underlying
        ;; CompletableFuture so the work actually stops (not just the
        ;; reply path).
        finalised? (atom false)
        ;; rf2-wez75 — abort-precedence cell. The abort-fn flips this
        ;; BEFORE racing the once-only `:finalised?` CAS, so even if a
        ;; synchronously-completing decode wins the CAS, the finalise-*
        ;; entry sees the abort snapshot and reclassifies the reply as
        ;; `:rf.http/aborted` per Spec 014 §Abort precedence (abort
        ;; always wins). The cell carries the abort reason map so the
        ;; canonical reply shape (and the `:actor-id` slot, when
        ;; actor-destroy was the source) is reconstructable inside
        ;; finalise-failure! without re-deriving from `failure`.
        aborted?   (atom nil)
        ;; rf2-on7sj (JVM) — the abort-fn closure must `.cancel cf true`
        ;; on the underlying CompletableFuture, but cf only exists AFTER
        ;; this binding (built inside the try-body below). Forward via a
        ;; one-cell atom that the JVM body fills after construction; the
        ;; abort-fn reads it lazily through `@cf-holder`.
        #?@(:clj  [cf-holder (atom nil)])
        ;; Register the abort handle. The handle ref is stamped into ctx
        ;; so finalise-* can clear it from both indexes without needing
        ;; the request-id (handles anonymous-from-actor requests too —
        ;; rf2-wvkn).
        handle   (registry/record-in-flight!
                   request-id actor-id
                   {:abort-fn (fn [reason]
                                ;; rf2-wez75 — flip `:aborted?` BEFORE the
                                ;; once-only CAS so that a concurrently-
                                ;; running finalise-* (decode-failure,
                                ;; transport, http-5xx, success) that wins
                                ;; the CAS still reads the abort snapshot
                                ;; on entry and reclassifies. The reset!
                                ;; is idempotent across re-entrant aborts
                                ;; (supersede + actor-destroy in rapid
                                ;; succession): subsequent flips just
                                ;; overwrite with the same shape, which is
                                ;; fine because the once-only CAS below
                                ;; collapses everything after the first
                                ;; pass into a no-op. We do NOT CAS this
                                ;; cell because a racing finalise-* that
                                ;; samples it as nil and then later sees
                                ;; it as set is exactly the window we're
                                ;; closing — abort always wins regardless
                                ;; of which side flipped first.
                                (reset! aborted? {:reason   reason
                                                  :actor-id actor-id})
                                ;; rf2-on7sj — single-shot CAS guard.
                                ;; A re-entrant abort (e.g. supersede +
                                ;; actor-destroy firing in rapid
                                ;; succession against the same handle)
                                ;; is a no-op past the first call.
                                (when (compare-and-set! finalised? false true)
                                  #?(:cljs
                                     (try
                                       (when internal-controller
                                         (.abort internal-controller (clj->js reason)))
                                       (catch :default _ nil))
                                     :clj
                                     ;; rf2-on7sj — cancel the underlying
                                     ;; future so it stops running. The
                                     ;; CompletableFuture's whenComplete
                                     ;; will still fire (with a
                                     ;; CancellationException), but
                                     ;; finalise-failure! is guarded by
                                     ;; the same :finalised? flag and
                                     ;; bails before re-emitting.
                                     ;; `true` = may-interrupt-if-running.
                                     (when-let [^CompletableFuture cf @cf-holder]
                                       (try (.cancel cf true)
                                            (catch Throwable _ nil))))
                                  ;; Registry cleanup happens here once;
                                  ;; finalise-failure! is bypassed. The
                                  ;; 1-arg form resolves the handle by
                                  ;; request-id and walks both indexes.
                                  ;; For anonymous (request-id-less)
                                  ;; requests aborted via actor-destroy,
                                  ;; the actor-side slot has already
                                  ;; been cleared atomically by
                                  ;; `abort-on-actor-destroy` before
                                  ;; this abort-fn was invoked, so the
                                  ;; no-op here is correct.
                                  (registry/clear-in-flight! request-id)
                                  ;; rf2-on7sj / rf2-wj8vv — the abort-fn
                                  ;; dispatches a synthesised reply directly
                                  ;; (no finalise-failure! re-entry). The
                                  ;; shared `dispatch-aborted!` reuses the
                                  ;; same trace-emit + reply shape the
                                  ;; backoff-window abort uses, so abort +
                                  ;; natural failures look identical to
                                  ;; consumers regardless of lifecycle phase.
                                  ;; Bypassing finalise-failure! keeps the
                                  ;; cancel + CAS + dispatch sequence atomic
                                  ;; and gives the once-only guard a single
                                  ;; owner.
                                  (dispatch-aborted! ctx-no-handle reason)))
                    :url url
                    ;; rf2-on7sj — once-only reply guard, see comment above.
                    :finalised? finalised?
                    ;; rf2-wez75 — abort-precedence cell read at finalise-*
                    ;; entry. See the `aborted?` binding above.
                    :aborted?   aborted?
                    ;; rf2-bma05 — propagate the :sensitive? flag onto
                    ;; the in-flight handle so the actor-destroy abort
                    ;; emit (lives in the registry ns) can stamp the
                    ;; trace event without re-resolving registration
                    ;; metadata.
                    :sensitive? (true? (:sensitive? ctx))})
        ctx'     (assoc ctx-no-handle :handle handle)]
    #?(:cljs
       (-> (cljs-fetch {:method              method
                        :url                 url
                        :headers             headers
                        :body                enc-body
                        :credentials         (:credentials request)
                        :mode                (:mode request)
                        :redirect            (:redirect request)
                        :cache               (:cache request)
                        :referrer            (:referrer request)
                        :integrity           (:integrity request)
                        :timeout-ms          timeout-ms
                        :abort-signal        abort-signal
                        :internal-controller internal-controller
                        ;; rf2-5zj6t — the transport picks the Fetch
                        ;; body-reader (`.text()` vs `.blob()` /
                        ;; `.arrayBuffer()` / `.formData()`) from the
                        ;; resolved decode mode; pass it through.
                        :decode              (:decode ctx)})
           (.then (fn [result] (handle-response! ctx' result)))
           ;; rf2-r40km — pass `url` so `classify-cljs-error` can
           ;; distinguish `:rf.http/cors` from `:rf.http/transport`
           ;; via the cross-origin heuristic.
           ;; rf2-on7sj — when the abort-fn fired and dispatch-aborted!
           ;; already replied, the Fetch promise still rejects (because
           ;; `.abort internal-controller` rejects the underlying
           ;; fetch); this .catch would call `maybe-retry!` →
           ;; `finalise-failure!` for a second pass. The once-only
           ;; `:finalised?` CAS on the handle short-circuits the second
           ;; dispatch inside finalise-*, so this path stays as the
           ;; natural-completion sink without a bespoke "did we abort?"
           ;; check here.
           (.catch (fn [err]
                     (maybe-retry! ctx' (classify-cljs-error err url)))))
       :clj
       (try
         (let [^CompletableFuture cf
               (jvm-fetch {:method     method
                           :url        url
                           :headers    headers
                           :body       enc-body
                           :timeout-ms timeout-ms
                           ;; rf2-ee38b.7 — honour the spec's `:redirect`
                           ;; envelope key on the JVM (default `:follow`).
                           ;; Selects the redirect-policy-specific client.
                           :redirect   (:redirect request)
                           :sensitive? (true? (:sensitive? ctx))})]
           ;; rf2-on7sj — publish cf to the abort-fn closure's holder
           ;; BEFORE wiring whenComplete. A racing abort that arrives
           ;; between `jvm-fetch` returning and `.whenComplete`
           ;; registering still finds cf in the holder and can cancel
           ;; it.
           (reset! cf-holder cf)
           ;; rf2-on7sj — the whenComplete callback fires even after
           ;; `.cancel cf true`: the cancel completes-exceptionally
           ;; with a CancellationException, which routes through this
           ;; BiConsumer as `throwable`. `maybe-retry!` →
           ;; `finalise-failure!` is then guarded by the once-only
           ;; `:finalised?` CAS (the abort-fn already finalised), so
           ;; the abort's reply is the only one that ever reaches the
           ;; user.
           (.whenComplete cf
                          (reify java.util.function.BiConsumer
                            (accept [_ result throwable]
                              (if throwable
                                (maybe-retry! ctx' (classify-jvm-error throwable timeout-ms))
                                (handle-response! ctx' result))))))
         (catch Throwable t
           (maybe-retry! ctx' (classify-jvm-error t timeout-ms)))))))
