(ns re-frame.http-transport-cljs
  "CLJS Fetch-API transport + attempt-and-retry loop for
  `:rf.http/managed`.

  Extracted from `re-frame.http-managed` per rf2-3i9b. On the JVM this
  namespace compiles to nothing (every form is gated `#?(:cljs ...)`)
  so the JVM transport in `re-frame.http-transport-jvm` does not pay
  any cost for the Fetch wiring.

  The reader is platform-conditional rather than a flat `.cljs` file so
  the http artefact's source tree is uniform (every sub-namespace is a
  .cljc), matching the rf2-hoiu / rf2-5hnn split conventions in
  `re-frame.core-*` and `re-frame.machines.*`.

  Per Spec 014 §Failure categories the attempt loop classifies status
  codes BEFORE decode (4xx/5xx route to `:rf.http/http-4xx` /
  `:rf.http/http-5xx` with the raw body-text). Per Spec 014 §Retry and
  backoff `maybe-retry!` decides between retry, immediate-final-failure,
  and successful completion based on the failing attempt's failure
  category and the request's `:retry` config."
  (:require [clojure.string         :as str]
            [re-frame.http-encoding :as encoding]
            [re-frame.http-privacy  :as privacy]
            [re-frame.http-registry :as registry]
            [re-frame.interop       :as interop]
            [re-frame.late-bind     :as late-bind]
            [re-frame.trace         :as trace]))

;; ---- transport (CLJS — Fetch) ---------------------------------------------

#?(:cljs
   (defn- ^:private fetch-headers->map [^js fetch-headers]
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

;; ---- attempt-and-retry loop (CLJS) ----------------------------------------

#?(:cljs
   (declare run-attempt!))

#?(:cljs
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

#?(:cljs
   (defn- finalise-success! [ctx accepted]
     (registry/clear-in-flight! (:request-id ctx) (:handle ctx))
     (case (first (keys accepted))
       :ok      (dispatch-reply! (assoc ctx
                                        :kind :success
                                        :reply-payload {:kind :success
                                                        :value (:ok accepted)}))
       :failure (dispatch-reply! (assoc ctx
                                        :kind :failure
                                        :reply-payload {:kind :failure
                                                        :failure (assoc (encoding/failure-map :rf.http/accept-failure
                                                                                              {:detail (:failure accepted)
                                                                                               :decoded (:decoded ctx)})
                                                                        :request-id (:request-id ctx))})))))

#?(:cljs
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
       ;; surface sees them; stamp :sensitive? when applicable.
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

#?(:cljs
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
                            {:request-id   request-id
                             :url          (:url ctx)
                             :attempt      attempt
                             :max-attempts max-attempts
                             :failure      failure
                             :next-backoff-ms delay-ms}
                            (true? (:sensitive? ctx)))))
           ;; Clear the prior attempt's handle from both indexes before
           ;; scheduling the retry. The next run-attempt! invocation
           ;; will record a fresh handle. Without this clear the
           ;; actor-in-flight index would accumulate stale handles
           ;; across retries (rf2-wvkn).
           (registry/clear-in-flight! request-id (:handle ctx))
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
           (finalise-failure! ctx failure))))))

#?(:cljs
   (defn- classify-cljs-error
     "Map a Fetch rejection / promise-error to a `:rf.http/*` failure shape."
     [^js err]
     (let [data (when (.-data err) (ex-data err))]
       (cond
         (:rf.http/timeout? data)
         (encoding/failure-map :rf.http/timeout
                               {:elapsed-ms (:elapsed-ms data)
                                :limit-ms   (:limit-ms data)})

         (or (= "AbortError" (.-name err))
             (:rf.http/aborted? data))
         (encoding/failure-map :rf.http/aborted
                               {:request-id (:request-id data)
                                :reason     (or (:reason data) :user)})

         :else
         (encoding/failure-map :rf.http/transport
                               {:message (or (.-message err) (str err))
                                :cause   err})))))

#?(:cljs
   (defn run-attempt!
     "Issue one Fetch attempt, then dispatch reply or retry."
     [ctx]
     (let [{:keys [request decode decode-supplied? accept timeout-ms
                   request-id actor-id abort-signal]} ctx
           method (or (:method request) :get)
           url    (encoding/merge-params (:url request) (:params request))
           [enc-body ct] (encoding/encode-body (encoding/realise-body (:body request))
                                               (:request-content-type request))
           headers (cond-> (or (:headers request) {})
                     (and ct (not (get (or (:headers request) {}) "content-type"))
                          (not (get (or (:headers request) {}) "Content-Type")))
                     (assoc "Content-Type" ct))
           internal-controller (when-not abort-signal (js/AbortController.))
           ;; Register the abort handle. The handle ref is stamped into
           ;; the ctx so finalise-* can clear it from both indexes
           ;; without needing the request-id (handles anonymous-from-actor
           ;; requests too — rf2-wvkn).
           handle (registry/record-in-flight!
                    request-id actor-id
                    {:abort-fn (fn [reason]
                                 (try
                                   (cond
                                     internal-controller
                                     (.abort internal-controller (clj->js reason))
                                     ;; External signal — user owns it.
                                     :else nil)
                                   (finalise-failure!
                                     (assoc ctx :url url)
                                     (encoding/failure-map :rf.http/aborted
                                                           {:request-id request-id
                                                            :reason     reason
                                                            :actor-id   actor-id}))
                                   (catch :default _ nil)))
                     :url url
                     ;; rf2-bma05 — propagate the :sensitive? flag onto
                     ;; the in-flight handle so the actor-destroy abort
                     ;; emit (lives in the registry ns) can stamp the
                     ;; trace event without re-resolving registration
                     ;; metadata.
                     :sensitive? (true? (:sensitive? ctx))})
           ctx    (assoc ctx :handle handle)]
       (-> (cljs-fetch {:method method
                        :url url
                        :headers headers
                        :body enc-body
                        :credentials (:credentials request)
                        :mode (:mode request)
                        :redirect (:redirect request)
                        :cache (:cache request)
                        :referrer (:referrer request)
                        :integrity (:integrity request)
                        :timeout-ms timeout-ms
                        :abort-signal abort-signal
                        :internal-controller internal-controller})
           (.then (fn [{:keys [ok? status status-text headers body-text]}]
                    (let [ctx' (assoc ctx :url url)]
                      ;; Per Spec 014 §Failure categories: status classification
                      ;; runs BEFORE decode. 4xx/5xx route to :rf.http/http-4xx /
                      ;; :rf.http/http-5xx with the raw body-text — decode never
                      ;; fires on a non-success response, so an HTML 404 from a
                      ;; JSON endpoint classifies as :rf.http/http-4xx (not
                      ;; :rf.http/decode-failure). Decode runs only on 2xx; if
                      ;; that fails the failure category is :rf.http/decode-failure.
                      (cond
                        (and (>= status 400) (< status 500))
                        (maybe-retry! ctx'
                                      (encoding/failure-map :rf.http/http-4xx
                                                            {:status status
                                                             :status-text status-text
                                                             :body body-text
                                                             :headers headers}))

                        (>= status 500)
                        (maybe-retry! ctx'
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
                                           :url        url
                                           :sensitive? (:sensitive? ctx')})
                                accepted (encoding/run-accept accept decoded {:status status})]
                            (cond
                              (contains? accepted :ok)
                              (finalise-success! ctx' accepted)

                              (contains? accepted :failure)
                              (finalise-failure!
                                ctx'
                                (encoding/failure-map :rf.http/accept-failure
                                                      {:detail  (:failure accepted)
                                                       :decoded decoded
                                                       :request-id request-id}))))
                          (catch :default e
                            (let [d (ex-data e)]
                              (maybe-retry!
                                ctx'
                                (encoding/failure-map :rf.http/decode-failure
                                                      {:body-text body-text
                                                       :cause     (.-message e)
                                                       :schema-validation-failure? (boolean (:malli-error? d))})))))

                        :else
                        ;; Non-2xx that didn't fall in 4xx/5xx (e.g., 1xx/3xx
                        ;; that the runtime didn't follow) — surface as 4xx-shaped
                        ;; failure with the raw body-text.
                        (finalise-failure!
                          ctx'
                          (encoding/failure-map :rf.http/http-4xx
                                                {:status status
                                                 :status-text status-text
                                                 :body body-text
                                                 :headers headers}))))))
           (.catch (fn [err]
                     (let [failure (classify-cljs-error err)]
                       (maybe-retry! (assoc ctx :url url) failure))))))))
