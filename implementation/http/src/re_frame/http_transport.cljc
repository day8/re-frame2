(ns re-frame.http-transport
  "Shared transport + attempt-and-retry loop for `:rf.http/managed`.

  Extracted from `re-frame.http-managed` per rf2-3i9b; collapsed onto a
  single .cljc per rf2-921qy. The CLJS path uses the Fetch API and the
  JVM path uses `java.net.http.HttpClient`; everything else
  (`dispatch-reply!`, `finalise-success!`, `finalise-failure!`,
  `maybe-retry!`, the 4xx/5xx/2xx/else response cascade, the
  in-flight registry interaction, the retry-trace emission, the
  rf2-bma05 privacy redaction, the rf2-lxd3 supersede suppression)
  is platform-agnostic and shared. Platform-specific fragments
  (`cljs-fetch` + `classify-cljs-error` on CLJS; `jvm-fetch` +
  `classify-jvm-error` + `check-cljs-only-keys!` on JVM) are gated
  with reader conditionals.

  Per-row CLJS-only request keys (`:abort-signal`, `:mode`, `:cache`,
  `:referrer`, `:integrity`) are no-ops on JVM with a one-line trace
  per occurrence via `check-cljs-only-keys!`.

  Per Spec 014 §Failure categories the attempt loop classifies status
  codes BEFORE decode (4xx/5xx route to `:rf.http/http-4xx` /
  `:rf.http/http-5xx` with the raw body-text). Per Spec 014 §Retry and
  backoff `maybe-retry!` decides between retry, immediate-final-failure,
  and successful completion based on the failing attempt's failure
  category and the request's `:retry` config."
  (:require [clojure.string         :as str]
            [re-frame.http-decode   :as decode]
            [re-frame.http-encoding :as encoding]
            [re-frame.http-privacy  :as privacy]
            [re-frame.http-registry :as registry]
            [re-frame.interop       :as interop]
            [re-frame.trace         :as trace])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient HttpRequest
                                  HttpRequest$BodyPublishers
                                  HttpResponse HttpResponse$BodyHandlers]
                   [java.time Duration]
                   [java.util.concurrent CompletableFuture])))

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
              integrity timeout-ms abort-signal internal-controller]}]
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
                                     (when (and timeout-ms internal-controller)
                                       (reset! timeout-handle
                                               (js/setTimeout
                                                 (fn []
                                                   (try (.abort internal-controller "timeout")
                                                        (catch :default _ nil))
                                                   (reject (ex-info "timeout"
                                                                    {:rf.http/timeout? true
                                                                     :elapsed-ms timeout-ms
                                                                     :limit-ms timeout-ms})))
                                                 timeout-ms)))))])
               (.then (fn [resp]
                        (when-let [h @timeout-handle] (js/clearTimeout h))
                        (-> (.text resp)
                            (.then (fn [body-text]
                                     {:ok? (.-ok resp)
                                      :status (.-status resp)
                                      :status-text (.-statusText resp)
                                      :headers (fetch-headers->map (.-headers resp))
                                      :body-text body-text}))))))]
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
   (defonce ^:private jvm-http-client
     ;; 10s connect timeout — distinct from `:timeout-ms` (which bounds
     ;; the whole request). Caps the TCP/TLS handshake so a black-holed
     ;; host fails fast instead of leaning on the per-request timeout.
     (delay (-> (HttpClient/newBuilder)
                (.connectTimeout (Duration/ofSeconds 10))
                (.build)))))

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
       (when timeout-ms
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
     "Flatten Java HttpHeaders into a plain Clojure map of string->string.

     Header names are lower-cased at the boundary, matching the CLJS
     Fetch path (`fetch-headers->map` consumes Fetch's `Headers` object,
     which iterates with normalised lower-case names). Per Spec 014
     §Request envelope, HTTP header names are case-insensitive — fixing
     casing at the transport boundary keeps every downstream consumer
     (decode sniffer, failure-map headers ridden by `:on-failure` reply
     payloads, privacy redactor) on a single canonical shape across
     hosts."
     [^java.net.http.HttpHeaders hh]
     (into {}
           (for [[k vs] (.map hh)]
             [(str/lower-case k) (str/join "," vs)]))))

#?(:clj
   (defn- jvm-fetch
     "Issue a single HTTP attempt via java.net.http.HttpClient. Returns a
     CompletableFuture that completes with `{:ok? :status :status-text
     :headers :body-text}` or completes-exceptionally with an ex-info.

     `opts` carries `:sensitive?` so `jvm-build-request` can route any
     header-validation warning through the privacy composer (rf2-1jcpm)."
     [opts]
     (let [client ^HttpClient @jvm-http-client
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
     correct catch-all for unknown JDK failures."
     [^Throwable t]
     (let [cause (or (.getCause t) t)
           msg   (.getMessage cause)
           cls   (.getName (class cause))]
       (cond
         (instance? java.net.http.HttpTimeoutException cause)
         {:kind :rf.http/timeout :elapsed-ms nil :limit-ms nil :message msg}

         (instance? java.util.concurrent.CancellationException cause)
         {:kind :rf.http/aborted :reason :user :message msg}

         :else
         {:kind :rf.http/transport :message msg :cause cls}))))

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
       (doseq [k [:mode :cache :referrer :integrity]]
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

(defn- dispatch-reply!
  [{:keys [origin-event explicit-on-success explicit-on-failure
           kind reply-payload frame]}]
  (let [explicit (case kind
                   :success explicit-on-success
                   :failure explicit-on-failure)]
    (encoding/dispatch-reply-via-late-bind!
      {:origin-event  origin-event
       :explicit-on   explicit
       :reply-payload reply-payload
       :kind          kind}
      frame)))

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

(defn- finalise-success! [ctx accepted]
  (when-not (already-replied? ctx)
    (registry/clear-in-flight! (:request-id ctx) (:handle ctx))
    (cond
      (contains? accepted :ok)
      (dispatch-reply! (assoc ctx
                              :kind :success
                              :reply-payload {:kind  :success
                                              :value (:ok accepted)}))

      (contains? accepted :failure)
      (dispatch-reply! (assoc ctx
                              :kind :failure
                              :reply-payload {:kind    :failure
                                              :failure {:kind       :rf.http/accept-failure
                                                        :detail     (:failure accepted)
                                                        :decoded    (:decoded ctx)
                                                        :request-id (:request-id ctx)}})))))

(defn- finalise-failure!
  "Final-failure dispatch (after retries exhausted or non-retriable).

  Per rf2-lxd3: when a fresh request supersedes a prior one with the
  same `:request-id`, the prior request's `:on-failure` reply is NOT
  dispatched (the supersede semantic = the new request replaces the
  old one — the original `:on-failure` would race the new request's
  outcome and corrupt debounce-search patterns). The supersede event
  still emits to the trace bus (`:rf.http/aborted` with
  `:reason :request-id-superseded`); consumers wanting abort telemetry
  subscribe via `register-trace-cb!`.

  Per rf2-on7sj: guarded by the once-only `:finalised?` CAS so the
  abort path and a later natural-completion path can't both dispatch
  a reply for the same request. The trace emit + registry clear ALSO
  live inside the guard — a doubled trace would be just as observable
  as a doubled reply on the dev surface."
  [ctx failure]
  (when-not (already-replied? ctx)
    (registry/clear-in-flight! (:request-id ctx) (:handle ctx))
    (when interop/debug-enabled?
      ;; rf2-bma05 — redact response-side payload slots (body, body-text,
      ;; decoded, detail) and the headers denylist before the trace
      ;; surface sees them; stamp :sensitive? when applicable. The CLJS
      ;; and JVM transports share the same contract.
      (let [sensitive? (true? (:sensitive? ctx))
            redacted   (privacy/prepare-emit-failure
                         (assoc failure
                                :request-id (:request-id ctx)
                                :url        (:url ctx)
                                :recovery   :no-recovery)
                         sensitive?)]
        (trace/emit-error! (:kind failure) redacted)))
    (let [superseded? (and (= :rf.http/aborted (:kind failure))
                           (= :request-id-superseded (:reason failure)))]
      (when-not superseded?
        (dispatch-reply! (assoc ctx
                                :kind          :failure
                                :reply-payload {:kind    :failure
                                                :failure failure}))))))

(defn- maybe-retry!
  "Decide between retry, immediate-final-failure, and successful-completion.
  `failure` is the failure map for the just-finished attempt."
  [ctx failure]
  (let [{:keys [retry attempt request-id]} ctx
        {:keys [on max-attempts backoff]} retry
        on-set (or on #{})
        kind   (:kind failure)
        can-retry? (and (some? max-attempts)
                        (> max-attempts attempt)
                        (contains? on-set kind)
                        (not= :rf.http/aborted kind))]
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
        ;; Clear the prior attempt's handle from both indexes before
        ;; scheduling the retry. The next run-attempt! invocation
        ;; will record a fresh handle. Without this clear the
        ;; actor-in-flight index would accumulate stale handles
        ;; across retries (rf2-wvkn).
        (registry/clear-in-flight! request-id (:handle ctx))
        ;; `interop/set-timeout!` is defined on both platforms (CLJS:
        ;; `js/setTimeout`; JVM: `ScheduledExecutorService`) so retry
        ;; scheduling is uniform across hosts.
        (interop/set-timeout!
          (fn [] (run-attempt! (-> ctx
                                   (dissoc :handle)
                                   (update :attempt inc))))
          delay-ms))
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
        {:keys [ok? status status-text headers body-text]} result]
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
                          :headers          headers
                          :decode           decode
                          :decode-supplied? decode-supplied?
                          :request-id       request-id
                          :url              url
                          :sensitive?       (:sensitive? ctx)
                          ;; rf2-wu1n5 — thread the keyword-cap from the
                          ;; normalised ctx into the decoder; nil means
                          ;; the reader uses its default.
                          :max-decoded-keys (:max-decoded-keys ctx)})
              accepted (encoding/run-accept accept decoded {:status status})]
          (finalise-success! (assoc ctx :decoded decoded) accepted))
        (catch #?(:clj Throwable :cljs :default) e
          (let [d (ex-data e)]
            (maybe-retry!
              ctx
              {:kind                       :rf.http/decode-failure
               :body-text                  body-text
               :cause                      #?(:clj (.getMessage ^Throwable e)
                                              :cljs (.-message e))
               :schema-validation-failure? (boolean (:malli-error? d))}))))

      :else
      ;; Non-2xx that didn't fall in 4xx/5xx (e.g., 1xx/3xx that the
      ;; runtime didn't follow) — surface as 4xx-shaped failure with
      ;; the raw body-text.
      (finalise-failure!
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
        ;; rf2-on7sj (JVM) — the abort-fn closure must `.cancel cf true`
        ;; on the underlying CompletableFuture, but cf only exists AFTER
        ;; this binding (built inside the try-body below). Forward via a
        ;; one-cell atom that the JVM body fills after construction; the
        ;; abort-fn reads it lazily through `@cf-holder`.
        #?@(:clj  [cf-holder (atom nil)])
        ;; rf2-on7sj — the abort-fn dispatches a synthesised reply
        ;; directly (no finalise-failure! re-entry). Reuses the same
        ;; trace-emit + reply-payload shape `finalise-failure!` uses
        ;; for the natural path so abort + natural failures look
        ;; identical to consumers. Bypassing finalise-failure! keeps
        ;; the cancel + CAS + dispatch sequence atomic in the abort
        ;; path and means the once-only guard has a single owner.
        dispatch-aborted! (fn [reason]
                            (let [failure {:kind       :rf.http/aborted
                                           :request-id request-id
                                           :reason     reason
                                           :actor-id   actor-id}]
                              (when interop/debug-enabled?
                                (let [sensitive? (true? (:sensitive? ctx-no-handle))
                                      redacted   (privacy/prepare-emit-failure
                                                   (assoc failure
                                                          :url      (:url ctx-no-handle)
                                                          :recovery :no-recovery)
                                                   sensitive?)]
                                  (trace/emit-error! :rf.http/aborted redacted)))
                              ;; Per rf2-lxd3 — supersede semantics
                              ;; suppress the reply. Other abort reasons
                              ;; (`:user`, `:actor-destroyed`, `:timeout`)
                              ;; all dispatch the failure reply normally.
                              (when-not (= :request-id-superseded reason)
                                (dispatch-reply!
                                  (assoc ctx-no-handle
                                         :kind          :failure
                                         :reply-payload {:kind    :failure
                                                         :failure failure})))))
        ;; Register the abort handle. The handle ref is stamped into ctx
        ;; so finalise-* can clear it from both indexes without needing
        ;; the request-id (handles anonymous-from-actor requests too —
        ;; rf2-wvkn).
        handle   (registry/record-in-flight!
                   request-id actor-id
                   {:abort-fn (fn [reason]
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
                                  (dispatch-aborted! reason)))
                    :url url
                    ;; rf2-on7sj — once-only reply guard, see comment above.
                    :finalised? finalised?
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
                        :internal-controller internal-controller})
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
                                (maybe-retry! ctx' (classify-jvm-error throwable))
                                (handle-response! ctx' result))))))
         (catch Throwable t
           (maybe-retry! ctx' (classify-jvm-error t)))))))
