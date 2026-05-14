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
     transport-OK, or rejects with an ex-info classified by the caller."
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
                        (cond
                          abort-signal        (aset init "signal" abort-signal)
                          internal-controller (aset init "signal" (.-signal internal-controller))))
           timeout-handle (atom nil)
           timeout-fired? (atom false)
           promise
           (-> (js/Promise.race
                 #js [(js/fetch url init)
                      (js/Promise. (fn [_ reject]
                                     (when (and timeout-ms internal-controller)
                                       (reset! timeout-handle
                                               (js/setTimeout
                                                 (fn []
                                                   (reset! timeout-fired? true)
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
   (defn- classify-cljs-error
     "Map a Fetch rejection / promise-error to a `:rf.http/*` failure shape."
     [^js err]
     (let [data (when (.-data err) (ex-data err))]
       (cond
         (:rf.http/timeout? data)
         {:kind       :rf.http/timeout
          :elapsed-ms (:elapsed-ms data)
          :limit-ms   (:limit-ms data)}

         (or (= "AbortError" (.-name err))
             (:rf.http/aborted? data))
         {:kind       :rf.http/aborted
          :request-id (:request-id data)
          :reason     (or (:reason data) :user)}

         :else
         {:kind    :rf.http/transport
          :message (or (.-message err) (str err))
          :cause   err}))))

;; ---- platform transport: JVM java.net.http.HttpClient ---------------------

#?(:clj
   (defonce ^:private jvm-http-client
     (delay (-> (HttpClient/newBuilder)
                (.connectTimeout (Duration/ofSeconds 10))
                (.build)))))

#?(:clj
   (defn- jvm-build-request
     [{:keys [method url headers body timeout-ms]}]
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
         (try (.header b (str k) (str v))
              (catch Throwable _ nil)))
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
     :headers :body-text}` or completes-exceptionally with an ex-info."
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
   (defn- classify-jvm-error [^Throwable t]
     (let [cause (or (.getCause t) t)
           msg   (.getMessage cause)
           cls   (.getName (class cause))]
       (cond
         (or (instance? java.net.http.HttpTimeoutException cause)
             (and msg (str/includes? (str/lower-case msg) "timed out")))
         {:kind :rf.http/timeout :elapsed-ms nil :limit-ms nil :message msg}

         (or (instance? java.util.concurrent.CancellationException cause)
             (and msg (str/includes? (str/lower-case msg) "abort")))
         {:kind :rf.http/aborted :reason :user :message msg}

         :else
         {:kind :rf.http/transport :message msg :cause cls}))))

;; ---- per-row CLJS-only-key tracing on JVM ---------------------------------

#?(:clj
   (defn- emit-cljs-only-skipped! [k url]
     (when interop/debug-enabled?
       (trace/emit! :warning :rf.http/cljs-only-key-ignored-on-jvm
                    {:key k :url url}))))

#?(:clj
   (defn check-cljs-only-keys! [{:keys [request abort-signal]}]
     (let [url (:url request)]
       (doseq [k [:mode :cache :referrer :integrity]]
         (when (contains? request k)
           (emit-cljs-only-skipped! k url)))
       (when abort-signal
         (emit-cljs-only-skipped! :abort-signal url)))))

;; A no-op JVM-only no-op stub on CLJS so callers can reach
;; `http-transport/check-cljs-only-keys!` unconditionally without
;; needing their own reader-conditional. The CLJS body would do nothing
;; — by definition CLJS-only keys are always honoured on CLJS.
#?(:cljs
   (defn check-cljs-only-keys! [_args] nil))

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

(defn- finalise-success! [ctx accepted]
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
                                                      :request-id (:request-id ctx)}}))))

(defn- finalise-failure!
  "Final-failure dispatch (after retries exhausted or non-retriable).

  Per rf2-lxd3: when a fresh request supersedes a prior one with the
  same `:request-id`, the prior request's `:on-failure` reply is NOT
  dispatched (the supersede semantic = the new request replaces the
  old one — the original `:on-failure` would race the new request's
  outcome and corrupt debounce-search patterns). The supersede event
  still emits to the trace bus (`:rf.http/aborted` with
  `:reason :request-id-superseded`); consumers wanting abort telemetry
  subscribe via `register-trace-cb!`."
  [ctx failure]
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
                                              :failure failure})))))

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
                          :sensitive?       (:sensitive? ctx)})
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
        ;; CLJS: an internal AbortController backs the Fetch signal when
        ;; the caller didn't supply one. JVM: no per-attempt controller —
        ;; abort signalling is host-specific and lives outside the
        ;; sendAsync future.
        #?@(:cljs [internal-controller (when-not abort-signal (js/AbortController.))])
        ;; Register the abort handle. The handle ref is stamped into ctx
        ;; so finalise-* can clear it from both indexes without needing
        ;; the request-id (handles anonymous-from-actor requests too —
        ;; rf2-wvkn).
        handle   (registry/record-in-flight!
                   request-id actor-id
                   {:abort-fn (fn [reason]
                                #?(:cljs
                                   (try
                                     (when internal-controller
                                       (.abort internal-controller (clj->js reason)))
                                     (finalise-failure!
                                       ctx-no-handle
                                       {:kind       :rf.http/aborted
                                        :request-id request-id
                                        :reason     reason
                                        :actor-id   actor-id})
                                     (catch :default _ nil))
                                   :clj
                                   (finalise-failure!
                                     ctx-no-handle
                                     {:kind       :rf.http/aborted
                                      :request-id request-id
                                      :reason     reason
                                      :actor-id   actor-id})))
                    :url url
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
           (.catch (fn [err]
                     (maybe-retry! ctx' (classify-cljs-error err)))))
       :clj
       (try
         (let [^CompletableFuture cf
               (jvm-fetch {:method     method
                           :url        url
                           :headers    headers
                           :body       enc-body
                           :timeout-ms timeout-ms})]
           (.whenComplete cf
                          (reify java.util.function.BiConsumer
                            (accept [_ result throwable]
                              (if throwable
                                (maybe-retry! ctx' (classify-jvm-error throwable))
                                (handle-response! ctx' result))))))
         (catch Throwable t
           (maybe-retry! ctx' (classify-jvm-error t)))))))
