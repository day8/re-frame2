(ns re-frame.ssr.ring.node
  "The JVM->Node adapter: `renderer`, the one non-local implementation of
  `ssr-handler`'s render-body seam (`:renderer`, Spec 011 §HTTP response
  contract) — slice D of the ssr-node crossing programme (rf2-8arzr, shared
  contract S1–S7). It dials the bounded sidecar `implementation/ssr-node`
  ships and takes back body markup, and nothing else.

  ## What crosses, and what does not (S1, S2, S3)

  Per request, INSIDE the request frame's scope and AFTER the boot-event
  drain and the blocking-resource settle, the renderer:

    1. projects the settled frame under the caller's `:render-state`
       policy — `re-frame.ssr.render-state/project` (slice C), the
       fail-closed per-partition allowlist that is DISTINCT in policy from
       the hydration payload's `:payload` and shares its envelope;
    2. serialises both partitions per key to EDN text
       (`render-state/serialize`), so the sidecar can enforce its
       entry-owned allowlists without decoding application data;
    3. POSTs ONE JSON request to `<endpoint>/render` — `protocol` 1,
       `entry`, `args` (root arguments as EDN text, when given), `state`
       (the app-db partition), `runtime` (the runtime-db partition),
       `buildId`, `timeoutMs`, `requestId` (the request frame's id, echoed
       back as `x-rf-ssr-request`);
    4. returns the sidecar's body bytes as `:body-html`, verbatim, with
       `:render-hash nil` — a native root carries no structural hash, so
       the page ships no `data-rf-render-hash` marker and no payload
       `:rf/render-hash` (the unresolved-root behaviour, rf2-q1b96).

  The JVM keeps everything else: the request frame, the drain, the head,
  `__rf_payload` (built from the JVM's own app-db under the SEPARATE
  `:payload` policy), the shell, status, headers, cookies, redirects, error
  projection and frame teardown. `:root-view` is not read — with a custom
  renderer it is optional and ignored (`lifecycle/validate-required-opts!`).

  ## Transport (S6)

  ONE `java.net.http.HttpClient` per renderer, built at construction and
  reused — a per-call client defeats connection pooling. EVERY request
  carries an explicit timeout the adapter derives (`http-timeout-ms`):

      :timeout-ms + :admission-ms + wire-margin-ms

  — the render deadline the sidecar is asked to honour, plus the time a
  request may sit in the sidecar's admission queue before it answers 503
  (its `--admission-ms`, which the adapter cannot discover and so is
  told), plus a fixed margin for the wire. Sized this way the sidecar
  refuses before the JVM gives up: the sidecar's render-timeout refusal is
  a diagnosable event, a socket the caller abandoned is not.

  JSON is `org.clojure/data.json` — ssr-ring's one dependency beyond core
  and ssr, accepted by the ruling (first-party, no transitive deps).

  ## Errors (S5)

  Every way the crossing can fail throws AT THE RENDER CALL SITE with a
  live frame, so every one routes through the EXISTING render-failure
  projection — `re-frame.ssr/project-render-exception!`, the `:error-view`
  side of Spec 011's error-handling division — as a projected 5xx. The
  sidecar's own HTTP status is NEVER copied to the browser, and no partial
  page is possible: the body render fails before any shell is assembled.
  The codes are DISTINCT (the `:rf.error/id` of the thrown ex-data, which
  rides the `:rf.error/ssr-render-failed` record's `:exception` into the
  trace stream; the wire sees only the projector's sanitised public
  error), so an operator can tell them apart:

    :rf.error/ssr-node-unreachable  no HTTP answer at all — connection
                                    refused, connect timeout, an I/O fault
                                    mid-exchange. ex-data `:endpoint`,
                                    `:ex-class`, `:ex-message`.
    :rf.error/ssr-node-deadline     the render did not finish within the
                                    deadline, observed by the sidecar (its
                                    504 — the transport's status for a
                                    deadline) or by the JVM (its own HTTP
                                    timeout expired first). ex-data
                                    `:observed-by` (`:sidecar` / `:jvm`),
                                    `:timeout-ms`, `:http-timeout-ms`.
    :rf.error/ssr-node-refused      any other non-200 answer — a caller-fault
                                    4xx, 503 saturation or shutdown, 500 on
                                    the sidecar's side. ex-data `:status`,
                                    `:refusal` (the `x-rf-ssr-refusal` code
                                    as a keyword; nil when the answer carried
                                    none), `:message`, `:detail`.

  The adapter classifies by the transport's documented STATUS contract
  (ssr-node README §The serve command: 4xx caller fault, 503 saturation or
  shutdown, 504 deadline, 500 theirs) and carries the refusal code as an
  opaque value: the sidecar's code vocabulary is the sidecar's, spelled
  nowhere on the JVM — which is also what its own absence witness holds.
    :rf.error/ssr-node-build-skew   a 200 whose `x-rf-ssr-build` is not the
                                    `:build-id` this renderer was built with
                                    — INCLUDING an absent header, since an
                                    answer that names no build is not a
                                    verifiable match and `:render-hash` is
                                    nil here, so nothing downstream would
                                    catch it. The sidecar already refuses a
                                    mismatched REQUEST; this is the defensive
                                    check on the ANSWER (S7). ex-data
                                    `:expected`, `:serving` (nil when the
                                    answer named no build).

  `:on-error` keeps its meaning — the net for when the projector cannot
  run — and nothing here reaches it. A malformed construction opt throws
  `:rf.error/ssr-node-renderer-opt-invalid` at construction (ex-data
  `:opt`, `:got`); a missing or malformed `:render-state` throws the
  payload family's ids with `:opt :render-state`, exactly as
  `render-state/validate-policy-opts!` documents.

  ## Deployment posture (S7)

  The default endpoint is `http://127.0.0.1:8148`, the launcher's default
  bind. Any absolute http(s) URL is accepted — a non-loopback sidecar is
  not refused (trust the programmer) — but render state may carry
  server-only values, so a remote sidecar is the operator's network and
  transport to secure."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ssr.manifest :as manifest]
            [re-frame.ssr.render-state :as render-state])
  (:import [java.io IOException]
           [java.net URI URISyntaxException]
           [java.net.http HttpClient HttpClient$Version HttpConnectTimeoutException
            HttpRequest HttpRequest$BodyPublishers HttpResponse
            HttpResponse$BodyHandlers HttpTimeoutException]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(set! *warn-on-reflection* true)

(def ^:private error-origin 'rf.ssr.ring.node/renderer)

(def default-endpoint
  "The launcher's default bind — what a JVM host assumes when told nothing
  else (ssr-node README §The serve command)."
  "http://127.0.0.1:8148")

(def default-timeout-ms
  "The render deadline sent as `timeoutMs` when the caller names none.
  Matches the launcher's `--timeout-ms` default."
  1000)

(def default-admission-ms
  "The sidecar's admission budget assumed when the caller names none.
  Matches the launcher's `--admission-ms` default; an operator who raised
  that flag raises `:admission-ms` to match, or the JVM's own timeout can
  expire while the request still waits for an isolate."
  250)

(def wire-margin-ms
  "The fixed margin the derived HTTP timeout adds for the wire itself —
  loopback round trip, JSON framing, a busy event loop answering."
  500)

(def ^:private sidecar-deadline-status
  "The HTTP status the sidecar's transport answers a deadline with — the
  one refusal this adapter reports as a deadline rather than a refusal."
  504)

(def ^:private renderer-defaults
  {:endpoint     default-endpoint
   :timeout-ms   default-timeout-ms
   :admission-ms default-admission-ms})

;; ---- construction validation ----------------------------------------------

(defn- throw-opt-invalid! [option-key received reason]
  (error/throw-error!
    :rf.error/ssr-node-renderer-opt-invalid error-origin
    (str "re-frame.ssr.ring.node/renderer " option-key " " reason ".")
    {:recovery :correct-the-renderer-opt
     :extra    {:opt option-key :got received}}))

(defn- require-non-empty-string! [option-key value]
  (when-not (and (string? value) (seq value))
    (throw-opt-invalid! option-key value "must be a non-empty string")))

(defn- require-integer! [option-key value minimum]
  (when-not (and (integer? value) (>= value minimum))
    (throw-opt-invalid! option-key value
                        (str "must be an integer of at least "
                             minimum " (milliseconds)"))))

(defn- endpoint-uri
  "`:endpoint` as a `URI`, or the construction error. Absolute, http(s),
  with a host — the shape a dialler needs; nothing about WHERE the host
  is (S7)."
  ^URI [endpoint]
  (require-non-empty-string! :endpoint endpoint)
  (let [^URI uri (try (URI. ^String endpoint) (catch URISyntaxException _ nil))]
    (when-not (and uri
                   (#{"http" "https"} (.getScheme uri))
                   (.getHost uri))
      (throw-opt-invalid! :endpoint endpoint
                          (str "must be an absolute http(s) URL such as "
                               (pr-str default-endpoint))))
    uri))

(defn validate-opts!
  "Throw a structured error when a Node renderer opt is absent or
  malformed; return `opts` unchanged on success. `renderer` calls this at
  construction so a misconfigured deployment fails at boot, not at first
  request. `opts` is the DEFAULT-MERGED map (see `renderer`).

    :endpoint     — absolute http(s) URL (default `default-endpoint`)
    :entry        — non-empty string: the bundle's entry id
    :build-id     — non-empty string: the deployed bundle identity
    :args         — OPTIONAL root arguments; when present, EDN the
                    sidecar's safe reader reads back EQUAL
                    (`re-frame.ssr.manifest/edn-carryable?`)
    :timeout-ms   — positive integer (default `default-timeout-ms`)
    :admission-ms — non-negative integer (default `default-admission-ms`)
    :render-state — REQUIRED; the allowlist map or projector fn of
                    `re-frame.ssr.render-state/validate-policy-opts!`

  Anything else throws `:rf.error/ssr-node-renderer-opt-invalid` naming
  the `:opt` and carrying `:got`; `:render-state` faults throw the payload
  family's ids with `:opt :render-state`."
  [{:keys [endpoint entry build-id args timeout-ms admission-ms] :as opts}]
  (when-not (map? opts)
    (throw-opt-invalid! :opts opts "takes an opts map"))
  (endpoint-uri endpoint)
  (require-non-empty-string! :entry entry)
  (require-non-empty-string! :build-id build-id)
  (when (and (contains? opts :args) (not (manifest/edn-carryable? args)))
    (throw-opt-invalid! :args args
                        (str "must be EDN the sidecar's safe reader reads back "
                             "EQUAL — no fn, host object, record, ratio, "
                             "bigdec, bigint, float, integer past 2^53, #inst "
                             "or #uuid")))
  (require-integer! :timeout-ms timeout-ms 1)
  (require-integer! :admission-ms admission-ms 0)
  (render-state/validate-policy-opts! opts)
  opts)

(defn http-timeout-ms
  "The explicit per-request HTTP timeout derived from DEFAULT-MERGED
  `opts` — `:timeout-ms` + `:admission-ms` + `wire-margin-ms` (S6). Pure;
  exposed so a deployment can read the number the adapter will wait."
  [{:keys [timeout-ms admission-ms]}]
  (+ timeout-ms admission-ms wire-margin-ms))

;; ---- the wire -------------------------------------------------------------

(defn- render-uri ^URI [^URI endpoint]
  (URI/create (str (str/replace (str endpoint) #"/+$" "") "/render")))

(defn- build-client ^HttpClient [transport-timeout-ms]
  (-> (HttpClient/newBuilder)
      ;; The sidecar is `node:http` — HTTP/1.1. Pinning it skips the h2c
      ;; upgrade dance the JDK client otherwise opens with.
      (.version HttpClient$Version/HTTP_1_1)
      (.connectTimeout (Duration/ofMillis (long transport-timeout-ms)))
      (.build)))

(defn- request-body
  "The JSON-ready request map for one render: string keys, the protocol's
  field names, both partitions as per-key EDN text. `args` rides only when
  the caller gave one (the protocol makes it optional)."
  [{:keys [entry args build-id timeout-ms] :as opts} frame-id partitions]
  (let [serialized-partitions (render-state/serialize partitions)]
    (cond-> {"protocol"  1
             "entry"     entry
             "state"     (:rf/app-db serialized-partitions)
             "runtime"   (:rf/runtime-db serialized-partitions)
             "buildId"   build-id
             "timeoutMs" timeout-ms
             "requestId" (str (symbol frame-id))}
      (contains? opts :args) (assoc "args" (pr-str args)))))

(defn- build-request
  ^HttpRequest [^URI render-endpoint ^String request-json transport-timeout-ms]
  (-> (HttpRequest/newBuilder render-endpoint)
      (.timeout (Duration/ofMillis (long transport-timeout-ms)))
      (.header "content-type" "application/json; charset=utf-8")
      (.POST (HttpRequest$BodyPublishers/ofString request-json
                                                  StandardCharsets/UTF_8))
      (.build)))

(defn- throw-unreachable! [{:keys [endpoint]} ^Throwable cause]
  (error/throw-error!
    :rf.error/ssr-node-unreachable error-origin
    (str "the Node render sidecar at " endpoint " gave no HTTP answer ("
         (.getName (class cause)) "). Start the sidecar (re-frame2-ssr-node "
         "--module <bundle>) or correct :endpoint.")
    {:recovery :start-the-sidecar-or-correct-the-endpoint
     :extra    {:endpoint   endpoint
                :ex-class   (.getName (class cause))
                :ex-message (.getMessage cause)}}))

(defn- throw-deadline! [{:keys [timeout-ms] :as opts} observed-by]
  (error/throw-error!
    :rf.error/ssr-node-deadline error-origin
    (str "the Node render did not finish within its " timeout-ms " ms deadline "
         "(observed by the " (name observed-by) "). Raise :timeout-ms, or make "
         "the entry render faster.")
    {:recovery :raise-the-deadline-or-speed-the-render
     :extra    {:observed-by     observed-by
                :timeout-ms      timeout-ms
                :http-timeout-ms (http-timeout-ms opts)}}))

(defn- throw-refused! [{:keys [endpoint]} status refusal message detail]
  (error/throw-error!
    :rf.error/ssr-node-refused error-origin
    (str "the Node render sidecar at " endpoint " refused the render (HTTP "
         status (when refusal (str ", " refusal)) ")"
         (when message (str ": " message)) ". Read the refusal code.")
    {:recovery :read-the-refusal-code
     :extra    {:status  status
                :refusal refusal
                :message message
                :detail  detail}}))

(defn- throw-build-skew!
  "The answer-side build refusal, for both ways a 200 fails to prove it came
  from `:build-id`: `serving` names a DIFFERENT build, or it is nil because
  the answer named none. The two causes read differently to an operator, so
  the sentence names which one it is; the id and the ex-data slots are one."
  [{:keys [build-id endpoint]} serving]
  (error/throw-error!
    :rf.error/ssr-node-build-skew error-origin
    (str "the Node render sidecar at " endpoint " answered 200 "
         (if serving
           (str "from build " (pr-str serving) " where this renderer was built "
                "against " (pr-str build-id)
                ". Redeploy the bundle that matches the JVM host.")
           (str "with no x-rf-ssr-build header, so its bytes cannot be shown "
                "to come from " (pr-str build-id)
                ". Find what stripped the header, or what is answering in "
                "the sidecar's place.")))
    {:recovery :redeploy-the-matching-bundle
     :extra    {:expected build-id
                :serving  serving}}))

(defn- refusal-keyword
  "The `x-rf-ssr-refusal` header text as its keyword — `\":ns/code\"` ->
  `:ns/code` — or nil for anything that is not that shape. The sidecar's
  vocabulary is carried, never spelled here."
  [header-value]
  (when (and (string? header-value)
             (str/starts-with? header-value ":")
             (> (count header-value) 1))
    (keyword (subs header-value 1))))

(defn- response-header [^HttpResponse http-response ^String header-name]
  (.orElse (.firstValue (.headers http-response) header-name) nil))

(defn- send-request!
  "Send `http-request` on `client`; the response, or the transport-class throw
  (`unreachable` for no answer, `deadline` for the JVM's own timeout)."
  ^HttpResponse [^HttpClient client ^HttpRequest http-request opts]
  (try
    (.send client http-request (HttpResponse$BodyHandlers/ofString))
    ;; Order matters: HttpConnectTimeoutException IS-A HttpTimeoutException
    ;; IS-A IOException. A connect timeout is unreachability, not a
    ;; deadline; a body-read timeout is the JVM observing the deadline.
    (catch HttpConnectTimeoutException cause
      (throw-unreachable! opts cause))
    (catch HttpTimeoutException _ (throw-deadline! opts :jvm))
    (catch IOException cause
      (throw-unreachable! opts cause))))

(defn- interpret-response!
  "The seam result from a sidecar response, or the sidecar-class throw."
  [{:keys [build-id] :as opts} ^HttpResponse http-response]
  (let [status (.statusCode http-response)
        body   (.body http-response)]
    (if (= 200 status)
      (let [serving (response-header http-response "x-rf-ssr-build")]
        ;; An EQUALITY, not a mismatch test: absence is not a verifiable
        ;; match. A 200 that names no build reads the same whether a proxy
        ;; stripped the header, the service is malformed, or something that
        ;; is not the sidecar answered — and `:render-hash` is nil for a
        ;; native root, so nothing downstream can catch it either. The bytes
        ;; are refused rather than wrapped in the JVM-owned document.
        (when-not (= serving build-id)
          (throw-build-skew! opts serving))
        {:body-html (str body) :render-hash nil})
      (let [error-payload (try
                            (json/read-str (str body))
                            (catch Exception _ nil))
            refusal (refusal-keyword
                      (or (response-header http-response "x-rf-ssr-refusal")
                          (get error-payload "code")))]
        (if (= sidecar-deadline-status status)
          (throw-deadline! opts :sidecar)
          (throw-refused! opts status refusal
                          (get error-payload "message")
                          (get error-payload "detail")))))))

;; ---- the renderer ---------------------------------------------------------

(defn renderer
  "Construct the JVM->Node renderer — the fn `ssr-handler`'s `:renderer`
  opt takes (`(fn [{:keys [frame-id request opts]}] -> {:body-html
  :render-hash})`). Validates `opts` at construction (`validate-opts!`),
  builds the ONE `HttpClient` the renderer reuses, and returns the fn.

      (ssr-ring/ssr-handler
        {:initial-events [[:app/init]]
         :payload        [:todos]
         :renderer       (node/renderer
                           {:endpoint     \"http://127.0.0.1:8148\"
                            :entry        \"app/root\"
                            :build-id     \"2026-09-02T08:00Z-a1b2c3\"
                            :render-state {:app-db     [:todos :session]
                                           :runtime-db [:rf.runtime/routing]}
                            :timeout-ms   1000})})

  See the namespace docstring for the opts, the transport and the error
  codes."
  [opts]
  (let [validated-opts       (validate-opts! (merge renderer-defaults opts))
        transport-timeout-ms (http-timeout-ms validated-opts)
        render-endpoint      (render-uri
                               (endpoint-uri (:endpoint validated-opts)))
        client               (build-client transport-timeout-ms)]
    (fn node-renderer [{:keys [frame-id]}]
      (let [partitions   (render-state/project frame-id validated-opts)
            request-json (json/write-str
                           (request-body validated-opts frame-id partitions)
                           :escape-slash false)
            http-request (build-request render-endpoint request-json
                                        transport-timeout-ms)
            http-response (send-request! client http-request validated-opts)]
        (interpret-response! validated-opts http-response)))))
