(ns re-frame.http.transport-jvm
  "JVM (`java.net.http.HttpClient`) platform adapter for `:rf.http/managed`.

  Extracted from `re-frame.http.transport` per rf2-hp772l — the platform
  transports were extracted into per-platform sibling namespaces so the
  shared attempt-and-retry lifecycle (`re-frame.http.transport`) reads as
  platform-neutral Clojure and each platform's reader-conditional code has
  a named home.

  This namespace owns the JDK `HttpClient` path: the redirect-policy →
  client memoisation, per-attempt request building (`jvm-build-request`),
  the response-header / charset boundary normalisation, issuing a single
  attempt (`jvm-fetch`), and the JVM-throwable → `:rf.http/*` failure
  classifier (`classify-jvm-error`). It also owns the JVM per-row CLJS-only
  key degradation tracing (`check-cljs-only-keys!`) — the CLJS host honours
  every CLJS-only key, so the `:cljs` form is a no-op (kept here, gated by
  reader conditional, because the shared handler calls this fn on BOTH
  platforms).

  The shared lifecycle calls `jvm-fetch` to issue an attempt,
  `classify-jvm-error` to map a throwable, and the handler calls
  `check-cljs-only-keys!`; nothing reaches back into this namespace's
  internals.

  The JDK-interop forms are `#?(:clj …)` — on CLJS this namespace loads as
  effectively empty save the `check-cljs-only-keys!` no-op. Keeping the
  conditionals local to the adapter is the rf2-hp772l contract: the shared
  loop carries no JDK interop."
  (:require [clojure.string         :as str]
            [re-frame.http.decode   :as decode]
            [re-frame.http.encoding :as encoding]
            [re-frame.http.privacy  :as privacy]
            [re-frame.interop       :as interop]
            [re-frame.trace         :as trace])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect
                                  HttpRequest
                                  HttpRequest$BodyPublishers
                                  HttpResponse HttpResponse$BodyHandlers]
                   [java.nio.charset Charset StandardCharsets]
                   [java.time Duration]
                   [java.util.concurrent CompletableFuture])))

;; rf2-b45uc — reflection warnings ON in the densest JVM-interop ns of
;; the http surface (HttpClient, HttpRequest, BodyPublishers, HttpHeaders,
;; CompletableFuture). The code is type-hinted today; the flag is the
;; tripwire that catches a future un-hinted interop call compiling to
;; reflective dispatch — exactly where interop is densest. CLJS ignores
;; the form.
#?(:clj (set! *warn-on-reflection* true))

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
   (defn jvm-http-client-for
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
   (defn jvm-build-request
     "Build a JDK `HttpRequest` for one attempt from the encoded request
     map. Selects the body publisher by `body` shape (none / String /
     bytes / stringified-else), applies the per-attempt `:timeout-ms`
     (the `(pos? …)` guard treats both `nil` and `0` as the no-timeout
     opt-out — see the inline note), and sets each header individually
     so a JDK header-validation throw can be isolated to the offending
     header (surfaced as a `:rf.warning/http-header-invalid` trace rather
     than sinking the whole request — rf2-9lun0). `sensitive?` is carried
     only so that warning's `:url` can be routed through the privacy
     composer (rf2-1jcpm).

     rf2-4y04lq — a RELATIVE `url` (e.g. `\"/api/items\"`) is rejected here
     with a clear `ex-info` before the JDK ever sees it. The browser Fetch
     transport resolves a relative url against the page's document base;
     the JVM has no such base. Left unchecked, `HttpRequest/newBuilder`
     throws its own `IllegalArgumentException: URI with undefined scheme`
     — a correct but unhelpful message that names neither the offending
     url nor the fix. This throw is uncaught HERE by design: the caller
     (`jvm-fetch`, called from `run-attempt!`'s try/catch) classifies any
     escaping `Throwable` via `classify-jvm-error`, which routes an
     `ex-info` like this one to the existing `:rf.http/transport`
     catch-all — no new failure category, no new `:rf.error/*` id. Per
     Spec 014 §JVM transport — absolute URLs required."
     [{:keys [method url headers body timeout-ms sensitive? frame]}]
     (let [uri (URI/create url)
           _   (when-not (.isAbsolute uri)
                 (throw (ex-info
                          (str "JVM HTTP transport requires an absolute URL "
                               "(scheme + host); got relative URL " (pr-str url)
                               ". The CLJS Fetch transport resolves a relative "
                               "URL against the browser's document base — the "
                               "JVM has no such base to resolve against. Supply "
                               "an absolute URL, or add a `:before` HTTP "
                               "interceptor (Spec 014 §Middleware) that "
                               "rewrites `:request :url` to an absolute form "
                               "before dispatch.")
                          {:url url})))
           b (HttpRequest/newBuilder uri)
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
       (doseq [[k v] (encoding/normalize-header-pairs headers)]
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
         ;; rf2-rznrz — `encoding/normalize-header-pairs` expands a
         ;; documented multi-valued request header (string → vector of
         ;; strings) into one `[name value]` pair per element, so we call
         ;; `.header` ONCE PER VALUE — the JDK accumulates repeated names
         ;; into the multi-valued wire form (`Accept: a` + `Accept: b`).
         ;; The prior `(str v)` on the whole value stringified a vector
         ;; into a single malformed `[\"a\" \"b\"]` header line.
         ;;
         ;; rf2-1jcpm — the `:url` slot on the warning event is routed
         ;; through `privacy/prepare-emit-tags` so a denylisted query
         ;; param (`?api_key=…`) is scrubbed and `:sensitive?` is
         ;; stamped when the request is sensitive. Previously the raw
         ;; URL rode the trace surface even when the handler / request
         ;; was declared sensitive.
         (try (.header b k v)
              (catch Throwable t
                (when interop/debug-enabled?
                  (trace/emit! :warning :rf.warning/http-header-invalid
                               (privacy/prepare-emit-tags
                                 {:url     url
                                  :header  k
                                  :cause   (.getMessage t)}
                                 (true? sensitive?)
                                 {:frame frame}))))))
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
   (defn- charset-of
     "rf2-a3wxe — resolve the response `Content-Type`'s `charset=` parameter
     to a `java.nio.charset.Charset`, defaulting to UTF-8. Mirrors the
     no-arg `HttpResponse.BodyHandlers/ofString` semantics (which honour
     the response charset, defaulting to UTF-8): now that `jvm-fetch` reads
     `ofByteArray` unconditionally so the binary-decode path can ride raw
     bytes, the text path must reproduce that charset handling rather than
     hard-coding UTF-8, so a `text/html; charset=ISO-8859-1` response
     decodes identically to the prior `ofString` behaviour. An unknown /
     unparseable charset falls back to UTF-8."
     ^Charset [headers]
     (or (when-let [ct (decode/content-type-of headers)]
           (let [m (re-find #"(?i)charset=\s*\"?([^\";\s]+)" (str ct))]
             (when-let [cs (second m)]
               (try (Charset/forName cs)
                    (catch Exception _ nil)))))
         StandardCharsets/UTF_8)))

#?(:clj
   (defn jvm-fetch
     "Issue a single HTTP attempt via java.net.http.HttpClient. Returns a
     CompletableFuture that completes with `{:ok? :status :status-text
     :headers :body-text}` (text path) or `{… :body-binary <bytes>}`
     (binary path), or completes-exceptionally with an ex-info.

     `opts` carries `:sensitive?` so `jvm-build-request` can route any
     header-validation warning through the privacy composer (rf2-1jcpm).
     `opts` carries `:redirect` (Spec 014 §Request envelope, default
     `:follow`) so the client honouring the right redirect policy is
     selected per rf2-ee38b.7.

     rf2-a3wxe — binary decode on the JVM. `opts` carries `:decode`; when
     it resolves to a binary mode (`:blob` / `:array-buffer` / `:form-data`,
     per `decode/binary-read-kind`, mirroring the CLJS transport's
     up-front body-reader selection) we read the response body with
     `BodyHandlers/ofByteArray` and ride the raw bytes under `:body-binary`
     so `decode-response-body` returns them verbatim. Previously every JVM
     response read `ofString`, so a `:blob`/`:array-buffer`/`:form-data`
     decode fell through to the lossy `body-text` fallback in
     `decode-response-body` — a UTF-8 decode of raw bytes that corrupts
     binary payloads (worse than a clean no-op). `binary-read-kind`
     consults the response headers for the `:auto` sniff, so we resolve it
     AFTER the response is in hand (status known); we only read bytes on a
     2xx (a non-2xx body is the raw error text the 4xx/5xx paths carry,
     same as CLJS — see `cljs-fetch`)."
     [opts]
     (let [client ^HttpClient (jvm-http-client-for (:redirect opts))
           req    (jvm-build-request opts)
           decode (:decode opts)
           future-resp (.sendAsync client req
                                   (HttpResponse$BodyHandlers/ofByteArray))]
       (.thenApply future-resp
                   (reify java.util.function.Function
                     (apply [_ resp]
                       (let [^HttpResponse r resp
                             status   (.statusCode r)
                             ok?      (and (>= status 200) (< status 300))
                             headers  (jvm-headers->map (.headers r))
                             ^bytes raw (.body r)
                             binary?  (and ok?
                                           (some? (decode/binary-read-kind decode headers)))
                             base     {:ok?         ok?
                                       :status      status
                                       :status-text ""
                                       :headers     headers}]
                         (if binary?
                           (assoc base :body-binary raw)
                           ;; Text path (and every non-2xx): decode the
                           ;; bytes as a String using the response charset
                           ;; (defaulting to UTF-8 via `charset-of`), faithfully
                           ;; reproducing the prior no-arg `ofString` semantics
                           ;; for the text/error path.
                           (assoc base :body-text (String. raw ^Charset (charset-of headers)))))))))))

#?(:clj
   (defn classify-jvm-error
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
     `:elapsed-ms` / `:limit-ms`).

     rf2-a3wxe — `:elapsed-ms` is now populated on the JVM. The JDK's
     `HttpTimeoutException` does not itself surface the elapsed wall
     clock, so `run-attempt!` captures a monotonic start mark
     (`System/nanoTime`) before issuing the request and passes the
     measured wall-clock delta here. rf2-6ecc6 — this matches the CLJS
     path's VALUE semantics, not just its shape: `cljs-fetch` likewise
     stamps a MEASURED `performance.now()`/`Date.now()` delta on its
     timeout rejection (Spec 014 §Failure categories). So on BOTH hosts
     `:elapsed-ms` is a measured wall-clock delta `>= :limit-ms` by the
     scheduling margin — a consumer can compute overshoot
     (`- :elapsed-ms :limit-ms`) portably (it was the synthetic constant
     `== :limit-ms` on CLJS before rf2-6ecc6).

     rf2-w59es5 — single 3-arity. The production caller (`run-attempt!`)
     always threads the configured `timeout-ms` and the measured
     `elapsed-ms`; the prior 1-/2-arity fallbacks existed only for
     synthetic/test callers and have been removed (pre-alpha, no
     back-compat). Pass `nil` explicitly for either when no value is in
     scope — on a timeout the corresponding tag rides `nil` (the JDK
     exposes no elapsed value on a synthetic throwable)."
     [^Throwable t timeout-ms elapsed-ms]
     (let [cause (or (.getCause t) t)
            msg   (.getMessage cause)
            cls   (.getName (class cause))]
        (cond
          (instance? java.net.http.HttpTimeoutException cause)
          {:kind :rf.http/timeout :elapsed-ms elapsed-ms :limit-ms timeout-ms :message msg}

          (instance? java.util.concurrent.CancellationException cause)
          {:kind :rf.http/aborted :reason :user :message msg}

          :else
          {:kind :rf.http/transport :message msg :cause cls}))))

;; ---- per-row CLJS-only-key tracing on JVM ---------------------------------

#?(:clj
   (defn- emit-cljs-only-skipped! [k url sensitive? frame]
     (when interop/debug-enabled?
       ;; rf2-1jcpm — route through the privacy composer so a denylisted
       ;; query param in the URL is scrubbed and `:sensitive?` is stamped
       ;; on the warning event when the originating handler / request is
       ;; sensitive. Previously the raw URL rode the trace surface.
       ;; rf2-ppkh3v — the originating frame is threaded so the URL
       ;; redaction also honours the frame's frame-local query-param
       ;; carriers (EP-0015 §3).
       (trace/emit! :warning :rf.http/cljs-only-key-ignored-on-jvm
                    (privacy/prepare-emit-tags
                      {:key k :url url}
                      (true? sensitive?)
                      {:frame frame})))))

#?(:clj
   (defn check-cljs-only-keys!
     ([args sensitive?] (check-cljs-only-keys! args sensitive? nil))
     ([{:keys [request abort-signal decode]} sensitive? frame]
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
           (emit-cljs-only-skipped! k url sensitive? frame)))
       (when abort-signal
         (emit-cljs-only-skipped! :abort-signal url sensitive? frame))
       ;; rf2-a3wxe — binary decode SHAPE-degradation notice on JVM.
       ;; A `:blob` / `:array-buffer` / `:form-data` decode is now HONOURED
       ;; on the JVM (jvm-fetch reads `ofByteArray` and rides the raw bytes
       ;; — no more lossy String fallback), but the returned value is a
       ;; `byte[]`, NOT the native browser `Blob` / `ArrayBuffer` /
       ;; `FormData` object the CLJS Fetch path yields. That host-shape
       ;; difference is a degradation worth surfacing — a caller asking
       ;; for a `Blob` gets bytes and would otherwise never learn. Emit a
       ;; dedicated one-line trace (NOT `:rf.http/cljs-only-key-ignored-
       ;; on-jvm`, since the decode is honoured rather than ignored). We
       ;; only flag an EXPLICIT binary `:decode` here: `:auto` resolves to
       ;; `:blob` from the response Content-Type, which isn't known at
       ;; dispatch time. Per Spec 014 §JVM degradation table.
       (when (and interop/debug-enabled?
                  (contains? #{:blob :array-buffer :form-data} decode))
         (trace/emit! :warning :rf.http/binary-decode-degraded-on-jvm
                      (privacy/prepare-emit-tags
                        {:decode decode :url url}
                        (true? sensitive?)
                        {:frame frame})))))))

;; A JVM-only no-op stub on CLJS so callers can reach
;; `check-cljs-only-keys!` unconditionally without needing their own
;; reader-conditional. The CLJS body does nothing — by definition
;; CLJS-only keys are always honoured on CLJS.
#?(:cljs
   (defn check-cljs-only-keys!
     ([_args _sensitive?] nil)
     ([_args _sensitive? _frame] nil)))
