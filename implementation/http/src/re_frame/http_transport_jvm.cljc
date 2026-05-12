(ns re-frame.http-transport-jvm
  "JVM `java.net.http.HttpClient` transport + attempt-and-retry loop for
  `:rf.http/managed`.

  Extracted from `re-frame.http-managed` per rf2-3i9b. On CLJS this
  namespace compiles to nothing (every form is gated `#?(:clj ...)`)
  so the CLJS browser bundle does not carry any JVM HttpClient wiring.

  The reader is platform-conditional rather than a flat `.clj` file so
  the http artefact's source tree is uniform (every sub-namespace is a
  .cljc), matching the rf2-hoiu / rf2-5hnn split conventions in
  `re-frame.core-*` and `re-frame.machines.*`.

  Per-row CLJS-only request keys (`:abort-signal`, `:mode`, `:cache`,
  `:referrer`, `:integrity`) are no-ops on JVM with a one-line trace
  per occurrence via `check-cljs-only-keys!`.

  Per Spec 014 §Failure categories the attempt loop classifies status
  codes BEFORE decode; per §Retry and backoff the loop drives the
  retry / final-failure decision identical to the CLJS path."
  (:require [clojure.string         :as str]
            [re-frame.http-encoding :as encoding]
            [re-frame.http-registry :as registry]
            [re-frame.interop       :as interop]
            [re-frame.late-bind     :as late-bind]
            [re-frame.trace         :as trace])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient HttpRequest
                                  HttpRequest$BodyPublisher HttpRequest$BodyPublishers
                                  HttpResponse HttpResponse$BodyHandlers]
                   [java.time Duration]
                   [java.util.concurrent CompletableFuture])))

;; ---- transport (JVM — java.net.http.HttpClient) --------------------------

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
     "Flatten Java HttpHeaders into a plain Clojure map of string->string."
     [^java.net.http.HttpHeaders hh]
     (into {}
           (for [[k vs] (.map hh)]
             [k (clojure.string/join "," vs)]))))

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

;; ---- per-row CLJS-only-key tracing on JVM ---------------------------------

#?(:clj
   (defn- emit-cljs-only-skipped! [k url]
     (when interop/debug-enabled?
       (trace/emit! :warning :rf.http/cljs-only-key-ignored-on-jvm
                    {:key k :url url}))))

#?(:clj
   (defn check-cljs-only-keys! [{:keys [request abort-signal] :as args}]
     (let [url (:url request)]
       (doseq [k [:mode :cache :referrer :integrity]]
         (when (contains? request k)
           (emit-cljs-only-skipped! k url)))
       (when abort-signal
         (emit-cljs-only-skipped! :abort-signal url)))))

;; ---- attempt-and-retry loop (JVM) -----------------------------------------

#?(:clj
   (declare run-attempt!))

#?(:clj
   (defn- dispatch-reply!
     [{:keys [origin-event explicit-on-success explicit-on-failure
              kind reply-payload frame]}]
     (let [explicit (case kind
                      :success explicit-on-success
                      :failure explicit-on-failure)
           ev (encoding/build-reply-event {:origin-event origin-event
                                           :explicit-on  explicit
                                           :reply-payload reply-payload
                                           :kind          kind})]
       (when ev
         (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
           (dispatch! ev (cond-> {} frame (assoc :frame frame))))))))

#?(:clj
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
                                               :failure (encoding/failure-map :rf.http/accept-failure
                                                                              {:detail     (:failure accepted)
                                                                               :decoded    (:decoded ctx)
                                                                               :request-id (:request-id ctx)})})))))

#?(:clj
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
       (trace/emit-error! (:kind failure)
                          (assoc failure
                                 :request-id (:request-id ctx)
                                 :url        (:url ctx)
                                 :recovery   :no-recovery)))
     (let [superseded? (and (= :rf.http/aborted (:kind failure))
                            (= :request-id-superseded (:reason failure)))]
       (when-not superseded?
         (dispatch-reply! (assoc ctx
                                 :kind          :failure
                                 :reply-payload {:kind    :failure
                                                 :failure failure}))))))

#?(:clj
   (defn- maybe-retry!
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
                          {:request-id   request-id
                           :url          (:url ctx)
                           :attempt      attempt
                           :max-attempts max-attempts
                           :failure      failure
                           :next-backoff-ms delay-ms}))
           ;; Clear the prior attempt's handle from both indexes before
           ;; scheduling the retry — same rationale as the CLJS path
           ;; (rf2-wvkn).
           (registry/clear-in-flight! request-id (:handle ctx))
           ;; Sleep + retry on a worker thread to avoid blocking the caller.
           (.start (Thread.
                     ^Runnable
                     (fn []
                       (try (Thread/sleep delay-ms) (catch InterruptedException _ nil))
                       (run-attempt! (-> ctx
                                         (dissoc :handle)
                                         (update :attempt inc)))))))
         (do
           (when (and interop/debug-enabled?
                      (some? max-attempts)
                      (> max-attempts 1))
             (trace/emit! :info :rf.http/retry-attempt
                          {:request-id      request-id
                           :url             (:url ctx)
                           :attempt         attempt
                           :max-attempts    max-attempts
                           :failure         failure
                           :next-backoff-ms nil}))
           (finalise-failure! ctx failure))))))

#?(:clj
   (defn- classify-jvm-error [^Throwable t]
     (let [cause (or (.getCause t) t)
           msg   (.getMessage cause)
           cls   (.getName (class cause))]
       (cond
         (or (instance? java.net.http.HttpTimeoutException cause)
             (and msg (str/includes? (str/lower-case msg) "timed out")))
         (encoding/failure-map :rf.http/timeout {:elapsed-ms nil :limit-ms nil :message msg})

         (or (instance? java.util.concurrent.CancellationException cause)
             (and msg (str/includes? (str/lower-case msg) "abort")))
         (encoding/failure-map :rf.http/aborted {:reason :user :message msg})

         :else
         (encoding/failure-map :rf.http/transport {:message msg :cause cls})))))

#?(:clj
   (defn run-attempt!
     [ctx]
     (let [{:keys [request decode decode-supplied? accept timeout-ms request-id actor-id]} ctx
           method (or (:method request) :get)
           url    (encoding/merge-params (:url request) (:params request))
           [enc-body ct] (encoding/encode-body (encoding/realise-body (:body request))
                                               (:request-content-type request))
           headers (cond-> (or (:headers request) {})
                     (and ct (not (get (or (:headers request) {}) "content-type"))
                          (not (get (or (:headers request) {}) "Content-Type")))
                     (assoc "Content-Type" ct))
           ;; Assemble ctx WITHOUT the handle first; record-in-flight!
           ;; returns the stamped handle, which we then assoc onto ctx so
           ;; finalise-* can clear by identity (rf2-wvkn).
           ctx-no-handle (assoc ctx :url url)
           handle (registry/record-in-flight!
                    request-id actor-id
                    {:abort-fn (fn [reason]
                                 (finalise-failure!
                                   ctx-no-handle
                                   (encoding/failure-map :rf.http/aborted
                                                         {:request-id request-id
                                                          :reason     reason
                                                          :actor-id   actor-id})))
                     :url url})
           ctx' (assoc ctx-no-handle :handle handle)]
       (try
         (let [^CompletableFuture cf
               (jvm-fetch {:method method
                           :url url
                           :headers headers
                           :body enc-body
                           :timeout-ms timeout-ms})]
           (.whenComplete cf
                          (reify java.util.function.BiConsumer
                            (accept [_ result throwable]
                              (cond
                                throwable
                                (maybe-retry! ctx' (classify-jvm-error throwable))

                                :else
                                (let [{:keys [ok? status status-text headers body-text]} result]
                                  ;; Per Spec 014 §Failure categories: status
                                  ;; classification runs BEFORE decode. 4xx/5xx
                                  ;; route to :rf.http/http-4xx / :rf.http/http-5xx
                                  ;; with the raw body-text — decode never fires
                                  ;; on a non-success response, so an HTML 404 from
                                  ;; a JSON endpoint classifies as :rf.http/http-4xx
                                  ;; (not :rf.http/decode-failure). Decode runs
                                  ;; only on 2xx; if that fails the failure category
                                  ;; is :rf.http/decode-failure.
                                  (cond
                                    (and (>= status 400) (< status 500))
                                    (maybe-retry!
                                      ctx'
                                      (encoding/failure-map :rf.http/http-4xx
                                                            {:status status
                                                             :status-text status-text
                                                             :body body-text
                                                             :headers headers}))

                                    (>= status 500)
                                    (maybe-retry!
                                      ctx'
                                      (encoding/failure-map :rf.http/http-5xx
                                                            {:status status
                                                             :status-text status-text
                                                             :body body-text
                                                             :headers headers}))

                                    ok?
                                    (try
                                      (let [decoded (encoding/decode-response-body
                                                      {:body-text body-text
                                                       :headers   headers
                                                       :decode    decode
                                                       :decode-supplied? decode-supplied?
                                                       :request-id request-id
                                                       :url        url})
                                            accepted (encoding/run-accept accept decoded {:status status})]
                                        (finalise-success!
                                          (assoc ctx' :decoded decoded) accepted))
                                      (catch Throwable e
                                        (let [d (ex-data e)]
                                          (maybe-retry!
                                            ctx'
                                            (encoding/failure-map :rf.http/decode-failure
                                                                  {:body-text body-text
                                                                   :cause (.getMessage e)
                                                                   :schema-validation-failure? (boolean (:malli-error? d))})))))

                                    :else
                                    ;; Non-2xx that didn't fall in 4xx/5xx (e.g.
                                    ;; 1xx/3xx that the runtime didn't follow) —
                                    ;; surface as 4xx-shaped failure with the raw
                                    ;; body-text.
                                    (finalise-failure!
                                      ctx'
                                      (encoding/failure-map :rf.http/http-4xx
                                                            {:status status
                                                             :status-text status-text
                                                             :body body-text
                                                             :headers headers})))))))))
         (catch Throwable t
           (maybe-retry! ctx' (classify-jvm-error t)))))))
