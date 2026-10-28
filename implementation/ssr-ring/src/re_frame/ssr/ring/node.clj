(ns re-frame.ssr.ring.node
  "The JVM->Node adapter: `renderer`, the one non-local implementation of
  `ssr-handler`'s render-body seam (`:renderer`, Spec 011 §HTTP response
  contract). It asks the bounded sidecar `implementation/ssr-node` ships for
  body markup, and takes back nothing else.

  ## Who runs the sidecar

  Two modes, chosen by which of two opts the caller names (Spec 011
  §Client-side hydration boot helper):

    :module    the JVM OWNS the sidecar. ssr-ring's jar carries the
               sidecar's runtime as classpath resources; construction copies
               them to a temp directory (Node cannot run a file inside a
               jar), spawns `node bin/serve.cjs --module <bundle> --port 0`,
               and waits for the ready line, refusing a launcher that speaks
               another protocol or a bundle built as anything but
               `:build-id` — so a skewed deployment fails at boot. The JVM
               passes the sidecar its deadline and admission budget itself,
               so the two halves cannot drift. A child that exits is
               relaunched after a capped backoff; while it is down, requests
               fail as unreachable.
    :endpoint  the operator runs the sidecar, and the renderer dials it.

  The renderer is a fn that is also `java.io.Closeable`: `.close` (or
  `with-open`) stops a sidecar the renderer owns and does nothing for one it
  dials, and a JVM shutdown hook stops an owned sidecar nobody closed.

  ## What crosses, and what does not

  Per request, INSIDE the request frame's scope and AFTER the boot-event
  drain and the blocking-resource settle, the renderer:

    1. projects the settled frame under the caller's `:render-state`
       policy — `re-frame.ssr.render-state/project`, the
       fail-closed per-partition allowlist that is DISTINCT in policy from
       the hydration payload's `:payload` and shares its envelope, and the
       one render-visibility policy (the sidecar checks shape, never which
       keys);
    2. serialises both partitions per key to EDN text
       (`render-state/serialize`) — every value a string, so the sidecar's
       freeze, key-shape check and byte ceiling work without decoding
       application data;
    3. POSTs ONE JSON request to `<endpoint>/render` — `protocol` 1,
       `entry`, `args` (root arguments as EDN text, when given), `state`
       (the app-db partition), `runtime` (the runtime-db partition),
       `buildId`, `timeoutMs`, `requestId` (the request frame's id, echoed
       back as `x-rf-ssr-request`);
    4. returns the sidecar's body bytes as `:body-html`, verbatim.

  The JVM keeps everything else: the request frame, the drain, the head,
  `__rf_payload` (built from the JVM's own app-db under the SEPARATE
  `:payload` policy), the shell, status, headers, cookies, redirects, error
  projection and frame teardown. `:root-view` is not read — with a custom
  renderer it is optional and ignored (`lifecycle/validate-required-opts!`).

  ## Transport

  ONE `java.net.http.HttpClient` per renderer, built at construction and
  reused — a per-call client defeats connection pooling. EVERY request
  carries an explicit timeout the adapter derives (`http-timeout-ms`):

      :timeout-ms + :admission-ms + wire-margin-ms

  — the render deadline the sidecar is asked to honour, plus the time a
  request may sit in the sidecar's admission queue before it answers 503
  (its `--admission-ms`, which a dialled sidecar cannot report and so the
  adapter is told), plus a fixed margin for the wire. Sized this way the
  sidecar refuses before the JVM gives up: the sidecar's render-timeout
  refusal is a diagnosable event, a socket the caller abandoned is not.

  That budget bounds the WHOLE exchange, body included.
  `HttpRequest.Builder.timeout` bounds only the phase before the response
  headers arrive, so a peer that answers 200 promptly and then stalls
  mid-body is a wait the JDK never ends — and the Ring request, and its
  frame, are held open for the length of it. The adapter therefore sends
  ASYNCHRONOUSLY and waits on completion for the budget itself, CANCELLING
  the exchange when it expires. Both halves throw the same
  `:rf.error/ssr-node-deadline` with `:observed-by :jvm`.

  JSON is `org.clojure/data.json` — ssr-ring's one dependency beyond core
  and ssr (first-party, no transitive deps).

  ## Errors

  Every way the crossing can fail throws AT THE RENDER CALL SITE with a
  live frame, so every one routes through the same render-failure
  projection as any render throw — `re-frame.ssr/project-render-exception!`, the `:error-view`
  side of Spec 011's error-handling division — as a projected 5xx. The
  sidecar's own HTTP status is NEVER copied to the browser, and no partial
  page is possible: the body render fails before any shell is assembled.
  The codes are DISTINCT (the `:rf.error/id` of the thrown ex-data, which
  rides the `:rf.error/ssr-render-failed` record's `:exception` into the
  trace stream; the wire sees only the projector's sanitised public
  error), so an operator can tell them apart:

    :rf.error/ssr-node-unreachable  no HTTP answer at all — connection
                                    refused, connect timeout, an I/O fault
                                    mid-exchange, or an owned sidecar that
                                    is down while it restarts. ex-data
                                    `:endpoint` (nil while it is down),
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
                                    verifiable match, and nothing downstream
                                    checks the bytes. The sidecar already refuses a
                                    mismatched REQUEST; this is the defensive
                                    check on the ANSWER. ex-data
                                    `:expected`, `:serving` (nil when the
                                    answer named no build).

  `:on-error` keeps its meaning — the net for when the projector cannot
  run — and nothing here reaches it. A malformed construction opt throws
  `:rf.error/ssr-node-renderer-opt-invalid` at construction (ex-data
  `:opt`, `:got`); a missing or malformed `:render-state` throws the
  payload family's ids with `:opt :render-state`, exactly as
  `render-state/validate-policy-opts!` documents. Under `:module`, a sidecar
  that cannot start throws `:rf.error/ssr-node-unreachable` at construction,
  and one serving a bundle built as another id throws
  `:rf.error/ssr-node-build-skew` there.

  ## Deployment posture

  The default endpoint is `http://127.0.0.1:8148`, the launcher's default
  bind, and an owned sidecar binds loopback on a port the OS picks. Any
  absolute http(s) URL is accepted as `:endpoint` — a non-loopback sidecar
  is not refused (trust the programmer) — but render state may carry
  server-only values, so a remote sidecar is the operator's network and
  transport to secure."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.error :as rf.error]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.ssr.wire :as rf.ssr.wire])
  (:import [java.io Closeable File IOException]
           [java.net URI URISyntaxException]
           [java.net.http HttpClient HttpClient$Version HttpConnectTimeoutException
            HttpRequest HttpRequest$BodyPublishers HttpResponse
            HttpResponse$BodyHandlers HttpTimeoutException]
           [java.nio.charset StandardCharsets]
           [java.nio.file CopyOption Files Path]
           [java.nio.file.attribute FileAttribute]
           [java.time Duration]
           [java.util.concurrent CompletableFuture ExecutionException TimeUnit
            TimeoutException]))

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
  that flag on a sidecar they run raises `:admission-ms` to match, or the
  JVM's own timeout can expire while the request still waits for an
  isolate. An owned sidecar is handed this value."
  250)

(def wire-margin-ms
  "The fixed margin the derived HTTP timeout adds for the wire itself —
  loopback round trip, JSON framing, a busy event loop answering."
  500)

(def ^:private completion-grace-ms
  "The slack the completion wait allows over the derived HTTP budget before
  it cancels the exchange itself.

  The JDK bounds the phase BEFORE the response headers arrive and
  classifies it precisely — a connect timeout is unreachability, a
  header-wait timeout is a deadline. The completion wait exists to bound
  what the JDK does not: consumption of the response BODY. Sized a little
  longer than the budget, the JDK's own timer wins whenever it is going to
  fire at all, so those classifications survive and this wait answers only
  for the stall the JDK never sees."
  100)

(def ^:private sidecar-deadline-status
  "The HTTP status the sidecar's transport answers a deadline with — the
  one refusal this adapter reports as a deadline rather than a refusal."
  504)

(def ^:private default-node
  "The Node executable an owned sidecar runs on when `:node` is absent —
  resolved on the JVM's PATH."
  "node")

;; ---- construction validation ----------------------------------------------

(defn- throw-opt-invalid! [option-key received reason]
  (rf.error/throw-error!
    :rf.error/ssr-node-renderer-opt-invalid error-origin
    (str "re-frame.ssr.ring.node/renderer " option-key " " reason ".")
    {:recovery :correct-the-renderer-opt
     :extra    {:opt option-key :got received}}))

(defn- require-non-empty-string! [option-key value]
  (when-not (and (string? value) (seq value))
    (throw-opt-invalid! option-key value "must be a non-empty string")))

(defn- require-integer! [option-key value minimum unit]
  (when-not (and (integer? value) (>= value minimum))
    (throw-opt-invalid! option-key value
                        (str "must be an integer of at least "
                             minimum " (" unit ")"))))

(defn- endpoint-uri
  "`:endpoint` as a `URI`, or the construction error. Absolute, http(s),
  with a host — the shape a dialler needs; nothing about WHERE the host
  is."
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

(defn- validate-sidecar-opts!
  "The opts that say who runs the sidecar. `:module` makes the renderer own
  one, so `:endpoint` beside it is a contradiction; `:node` and `:isolates`
  configure that spawn, so without `:module` they would be silently inert."
  [{:keys [module endpoint node isolates] :as opts}]
  (if (contains? opts :module)
    (do
      (when (contains? opts :endpoint)
        (throw-opt-invalid! :endpoint endpoint
                            (str "cannot be named beside :module — the renderer "
                                 "either owns a sidecar (:module) or dials one "
                                 "(:endpoint)")))
      (require-non-empty-string! :module module)
      (when-not (.isFile ^File (io/file module))
        (throw-opt-invalid! :module module
                            "must name the server bundle file the sidecar loads"))
      (require-non-empty-string! :node node)
      (when (contains? opts :isolates)
        (require-integer! :isolates isolates 1 "worker threads")))
    (do
      (endpoint-uri endpoint)
      (doseq [option-key [:node :isolates]
              :when (contains? opts option-key)]
        (throw-opt-invalid! option-key (get opts option-key)
                            "applies only with :module, which spawns the sidecar")))))

(defn validate-opts!
  "Throw a structured error when a Node renderer opt is absent or
  malformed; return `opts` unchanged on success. `renderer` calls this at
  construction so a misconfigured deployment fails at boot, not at first
  request. `opts` is the DEFAULT-MERGED map (see `renderer`).

    :module       — path to the server bundle: the renderer spawns and owns
                    the sidecar; must name a file. Exclusive with :endpoint
    :node         — with :module: the Node executable (default \"node\")
    :isolates     — with :module: the sidecar's worker threads, a positive
                    integer (default: the launcher's)
    :endpoint     — absolute http(s) URL of a sidecar the operator runs
                    (default `default-endpoint` when :module is absent)
    :entry        — non-empty string: the bundle's entry id
    :build-id     — non-empty string: the deployed bundle identity
    :args         — OPTIONAL root arguments; when present, EDN the
                    sidecar's safe reader reads back EQUAL
                    (`re-frame.ssr.wire/edn-carryable?`)
    :timeout-ms   — positive integer (default `default-timeout-ms`)
    :admission-ms — non-negative integer (default `default-admission-ms`)
    :render-state — REQUIRED; the allowlist map or projector fn of
                    `re-frame.ssr.render-state/validate-policy-opts!`

  Anything else throws `:rf.error/ssr-node-renderer-opt-invalid` naming
  the `:opt` and carrying `:got`; `:render-state` faults throw the payload
  family's ids with `:opt :render-state`."
  [{:keys [entry build-id args timeout-ms admission-ms] :as opts}]
  (when-not (map? opts)
    (throw-opt-invalid! :opts opts "takes an opts map"))
  (validate-sidecar-opts! opts)
  (require-non-empty-string! :entry entry)
  (require-non-empty-string! :build-id build-id)
  (when (and (contains? opts :args) (not (rf.ssr.wire/edn-carryable? args)))
    (throw-opt-invalid! :args args
                        (str "must be EDN the sidecar's safe reader reads back "
                             "EQUAL — no fn, host object, record, ratio, "
                             "bigdec, bigint, float, integer past 2^53, #inst "
                             "or #uuid, and no map or set holding two numbers "
                             "the reader takes as one (1 beside 1.0, 0 beside "
                             "-0.0)")))
  (require-integer! :timeout-ms timeout-ms 1 "milliseconds")
  (require-integer! :admission-ms admission-ms 0 "milliseconds")
  (rf.ssr.render-state/validate-policy-opts! opts)
  opts)

(defn http-timeout-ms
  "The explicit per-request HTTP timeout derived from DEFAULT-MERGED
  `opts` — `:timeout-ms` + `:admission-ms` + `wire-margin-ms`. Pure;
  exposed so a deployment can read the number the adapter will wait."
  [{:keys [timeout-ms admission-ms]}]
  (+ timeout-ms admission-ms wire-margin-ms))

;; ---- the wire -------------------------------------------------------------

(defn- render-uri ^URI [endpoint]
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
  (let [serialized-partitions (rf.ssr.render-state/serialize partitions)]
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

(defn- throw-unreachable!
  "No usable sidecar. `opts` carries the `:endpoint` the request dialled —
  nil when an owned sidecar is down or never came up — and `:module` when
  the renderer owns the sidecar, which changes what the operator can do."
  [{:keys [endpoint module]} ^Throwable cause]
  (rf.error/throw-error!
    :rf.error/ssr-node-unreachable error-origin
    (if module
      (str "the Node render sidecar this renderer runs on " module
           (when endpoint (str " (at " endpoint ")"))
           " gave no HTTP answer: " (.getMessage cause) ". The renderer "
           "relaunches it whenever it exits, and its diagnostics are on this "
           "JVM's stderr.")
      (str "the Node render sidecar at " endpoint " gave no HTTP answer ("
           (.getName (class cause)) "). Start the sidecar (re-frame2-ssr-node "
           "--module <bundle>) or correct :endpoint."))
    {:recovery :start-the-sidecar-or-correct-the-endpoint
     :extra    {:endpoint   endpoint
                :ex-class   (.getName (class cause))
                :ex-message (.getMessage cause)}}))

(defn- throw-deadline! [{:keys [timeout-ms] :as opts} observed-by]
  (rf.error/throw-error!
    :rf.error/ssr-node-deadline error-origin
    (str "the Node render did not finish within its " timeout-ms " ms deadline "
         "(observed by the " (name observed-by) "). Raise :timeout-ms, or make "
         "the entry render faster.")
    {:recovery :raise-the-deadline-or-speed-the-render
     :extra    {:observed-by     observed-by
                :timeout-ms      timeout-ms
                :http-timeout-ms (http-timeout-ms opts)}}))

(defn- throw-refused! [{:keys [endpoint]} status refusal message detail]
  (rf.error/throw-error!
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
  (rf.error/throw-error!
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

(defn- throw-boot-build-skew!
  "The boot-side build refusal: the bundle an owned sidecar loaded names a
  build other than `:build-id`. Refused before any request, with the
  answer-side refusal's id and ex-data slots."
  [{:keys [build-id module]} serving]
  (rf.error/throw-error!
    :rf.error/ssr-node-build-skew error-origin
    (str "the server bundle " module " is build " (pr-str serving)
         " where this renderer was built against " (pr-str build-id)
         ". Deploy the bundle that matches the JVM host, or correct :build-id.")
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
  "Send `http-request` on `client` and return the COMPLETED response, or the
  transport-class throw (`unreachable` for no answer, `deadline` for the
  JVM's own budget expiring).

  `sendAsync` and a bounded wait on completion, rather than the blocking
  `send`: the derived budget has to bound COMPLETE body
  consumption, and `HttpRequest.Builder.timeout` does not — a peer that
  answers 200 promptly and then stalls mid-body is a wait the JDK never
  ends. Expiry CANCELS the exchange (`cancel(true)` reaches the underlying
  JDK operation) rather than abandoning a live body reader behind a settled
  outer result: the throw releases the caller's request frame, so a reader
  left running would outlive the request that owns it."
  ^HttpResponse [^HttpClient client ^HttpRequest http-request opts
                 transport-timeout-ms]
  (let [^CompletableFuture pending
        (.sendAsync client http-request (HttpResponse$BodyHandlers/ofString))]
    (try
      (.get pending
            (long (+ transport-timeout-ms completion-grace-ms))
            TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (.cancel pending true)
        (throw-deadline! opts :jvm))
      ;; An interrupted host thread keeps its own meaning — it is
      ;; neither a deadline nor unreachability — but the exchange still has
      ;; to go, and the flag is restored for whoever set it.
      (catch InterruptedException cause
        (.cancel pending true)
        (.interrupt (Thread/currentThread))
        (throw cause))
      (catch ExecutionException wrapper
        (let [cause (or (.getCause wrapper) wrapper)]
          ;; Order matters: HttpConnectTimeoutException IS-A
          ;; HttpTimeoutException IS-A IOException. A connect timeout is
          ;; unreachability, not a deadline; the JDK's header-wait timeout
          ;; is the JVM observing the deadline.
          (cond
            (instance? HttpConnectTimeoutException cause)
            (throw-unreachable! opts cause)

            (instance? HttpTimeoutException cause)
            (throw-deadline! opts :jvm)

            (instance? IOException cause)
            (throw-unreachable! opts cause)

            :else (throw cause)))))))

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
        ;; is not the sidecar answered, and nothing downstream checks the
        ;; bytes. They are refused rather than wrapped in the JVM-owned
        ;; document.
        (when-not (= serving build-id)
          (throw-build-skew! opts serving))
        {:body-html (str body)})
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

;; ---- the owned sidecar (`:module`) ----------------------------------------

(def ^:private sidecar-resource-root
  "Where ssr-ring's classpath carries the sidecar's runtime: a copy of the
  `bin/` and `src/` of `implementation/ssr-node`, which that package's own
  suite pins byte for byte."
  "re_frame/ssr_node/")

(def ^:private sidecar-files
  "The runtime under `sidecar-resource-root`, the launcher first. A JVM test
  pins this list to the resource directory, so a file the sidecar gains
  cannot be left behind by the copy below."
  ["bin/serve.cjs"
   "src/http.cjs" "src/isolate.cjs" "src/pool.cjs" "src/protocol.cjs"
   "src/service.cjs" "src/worker.cjs"])

(def ^:private boot-timeout-ms
  "How long a launched child has to print its ready line. Generous: it
  covers the bundle's first evaluation in every isolate on a loaded host."
  30000)

(def ^:private first-relaunch-delay-ms 100)

(def ^:private max-relaunch-delay-ms
  "The cap on the relaunch backoff, which doubles from
  `first-relaunch-delay-ms` while relaunches keep failing and resets once
  one succeeds."
  10000)

(def ^:private stop-grace-ms
  "How long a stop waits for the child to exit after a SIGTERM before it
  kills it outright, and again after that."
  5000)

(def ^:private stderr-tail-lines
  "How many of a failed launch's last stderr lines its error carries."
  20)

(defn- log! [^String line]
  (.println System/err (str "[re-frame.ssr.ring.node] " line)))

(defn- delete-tree! [^Path dir]
  (doseq [^File f (reverse (file-seq (.toFile dir)))]
    (.delete f)))

(defn- extract-sidecar!
  "Copy the sidecar's runtime off the classpath into a fresh temp directory
  and return the directory — Node cannot run a file inside a jar."
  ^Path []
  (let [dir (Files/createTempDirectory "rf2-ssr-node-" (make-array FileAttribute 0))]
    (try
      (doseq [^String rel sidecar-files]
        (let [resource (io/resource (str sidecar-resource-root rel))
              target   (.resolve dir rel)]
          (when-not resource
            (throw (IOException. (str "the sidecar's runtime is not on the classpath ("
                                      sidecar-resource-root rel " is missing)"))))
          (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
          (with-open [in (io/input-stream resource)]
            (Files/copy ^java.io.InputStream in target ^"[Ljava.nio.file.CopyOption;" (make-array CopyOption 0)))))
      dir
      (catch Throwable t
        (delete-tree! dir)
        (throw t)))))

(defn- pump-lines!
  "Read `stream` line by line on a daemon thread, calling `on-line` for each
  and `on-eof` once it closes. Returns the thread."
  ^Thread [stream ^String thread-name on-line on-eof]
  (doto (Thread. ^Runnable
                 (fn []
                   (try
                     (with-open [reader (io/reader stream)]
                       (doseq [line (line-seq reader)] (on-line line)))
                     (catch IOException _ nil)
                     (finally (on-eof))))
                 thread-name)
    (.setDaemon true)
    (.start)))

(defn- ready-line
  "The launcher's ready line as a map, or nil for any other stdout line — a
  bundle that logs at boot shares the stream (ssr-node README §The ready
  line)."
  [line]
  (let [parsed (try (json/read-str line) (catch Exception _ nil))]
    (when (and (map? parsed) (= "ready" (get parsed "rf.ssr-node")))
      parsed)))

(defn- destroy! [^Process process]
  ;; SIGTERM where the platform has one (a graceful close); Windows
  ;; terminates outright either way. Forcibly, if it lingers.
  (.destroy process)
  (when-not (.waitFor process stop-grace-ms TimeUnit/MILLISECONDS)
    (.destroyForcibly process)
    (.waitFor process stop-grace-ms TimeUnit/MILLISECONDS)))

(defn- launch-command
  [^Path dir {:keys [module node isolates timeout-ms admission-ms]}]
  (cond-> [node (str (.resolve dir "bin/serve.cjs"))
           "--module" module
           "--host" "127.0.0.1"
           "--port" "0"
           ;; The JVM's own numbers, so the two halves cannot disagree: the
           ;; deadline is also the ceiling, so it is never clamped.
           "--timeout-ms" (str timeout-ms)
           "--max-timeout-ms" (str timeout-ms)
           "--admission-ms" (str admission-ms)]
    isolates (into ["--isolates" (str isolates)])))

(defn- launch!
  "Spawn one sidecar child on the runtime in `dir` and wait for its ready
  line; return the endpoint it reports. `on-spawn` receives the process
  before the wait, so a stop that lands mid-boot can reach it.

  A child that never produces a usable ready line — it could not start,
  exited, said nothing in time, or speaks another protocol — is killed and
  throws `:rf.error/ssr-node-unreachable` with its last stderr lines; one
  whose bundle is another build throws `:rf.error/ssr-node-build-skew`."
  [^Path dir {:keys [build-id] :as opts} on-spawn]
  (let [^Process process (try (.start (ProcessBuilder. ^java.util.List (launch-command dir opts)))
                              (catch IOException cause
                                (throw-unreachable! opts cause)))]
    (on-spawn process)
    ;; From here on, every throw takes the child with it.
    (try
      (let [ready        (promise)
            stderr-tail  (atom [])
            _            (pump-lines! (.getInputStream process) "rf2-ssr-node-stdout"
                                      (fn [line]
                                        (if-let [parsed (when-not (realized? ready)
                                                          (ready-line line))]
                                          (deliver ready parsed)
                                          (.println System/out ^String line)))
                                      #(deliver ready ::exited))
            stderr-pump  (pump-lines! (.getErrorStream process) "rf2-ssr-node-stderr"
                                      (fn [line]
                                        (.println System/err ^String line)
                                        (swap! stderr-tail
                                               #(vec (take-last stderr-tail-lines (conj % line)))))
                                      (fn []))
            ready-or-not (deref ready boot-timeout-ms ::silent)
            fail!        (fn [reason]
                           ;; Dead first, so its last stderr lines are in.
                           (destroy! process)
                           (.join stderr-pump 1000)
                           (throw-unreachable!
                             opts
                             (IOException.
                               (str reason
                                    (when (seq @stderr-tail)
                                      (str "; its stderr ended:\n"
                                           (str/join "\n" @stderr-tail)))))))]
        (cond
          (= ::silent ready-or-not)
          (fail! (str "it printed no ready line within " boot-timeout-ms " ms"))

          (= ::exited ready-or-not)
          (fail! (str "it exited"
                      (when (.waitFor process 1000 TimeUnit/MILLISECONDS)
                        (str " with code " (.exitValue process)))
                      " before its ready line"))

          (not= 1 (get ready-or-not "protocol"))
          (fail! (str "its ready line names protocol "
                      (pr-str (get ready-or-not "protocol"))
                      ", where this renderer speaks protocol 1"))

          (not= build-id (get ready-or-not "buildId"))
          (throw-boot-build-skew! opts (get ready-or-not "buildId"))

          :else
          (get ready-or-not "url")))
      (catch Throwable t
        (destroy! process)
        (throw t)))))

(defn- own-sidecar!
  "Launch the sidecar for `opts` and keep it running until stopped. The
  first launch is synchronous, so a sidecar that cannot start fails the
  renderer's construction. A daemon thread then relaunches the child each
  time it exits, after a backoff that doubles while relaunches fail. Returns
  `{:endpoint (fn [] url-or-nil) :stop! (fn [])}`; a JVM shutdown hook runs
  `stop!` if nobody else does."
  [opts]
  (let [dir      (try (extract-sidecar!)
                      (catch IOException cause (throw-unreachable! opts cause)))
        lock     (Object.)
        state    (atom {:process nil :endpoint nil :stopped? false})
        on-spawn (fn [^Process process]
                   (locking lock
                     (if (:stopped? @state)
                       (do (.destroyForcibly process)
                           (throw (IOException. "the renderer was closed")))
                       (swap! state assoc :process process))))
        launch   (fn []
                   (let [endpoint (launch! dir opts on-spawn)]
                     (locking lock
                       (when-not (:stopped? @state)
                         (swap! state assoc :endpoint endpoint)))))
        _        (try (launch)
                      (catch Throwable t
                        (delete-tree! dir)
                        (throw t)))
        supervisor
        (Thread. ^Runnable
                 (fn []
                   (try
                     (loop [delay-ms first-relaunch-delay-ms]
                       (when-let [^Process process (:process @state)]
                         (.waitFor process))
                       (locking lock (swap! state assoc :endpoint nil))
                       (when-not (:stopped? @state)
                         (log! (str "relaunching the sidecar on " (:module opts)
                                    " in " delay-ms " ms"))
                         (Thread/sleep (long delay-ms))
                         (when-not (:stopped? @state)
                           (recur (try (launch)
                                       first-relaunch-delay-ms
                                       (catch InterruptedException interrupted
                                         (throw interrupted))
                                       (catch Exception failure
                                         (log! (str "relaunch failed: " (ex-message failure)))
                                         (min max-relaunch-delay-ms (* 2 delay-ms))))))))
                     (catch InterruptedException _ nil)))
                 "rf2-ssr-node-supervisor")
        hook     (atom nil)
        stop!    (fn stop! []
                   (let [[first-stop? process]
                         (locking lock
                           (let [{:keys [process stopped?]} @state]
                             (swap! state assoc :stopped? true :endpoint nil)
                             [(not stopped?) process]))]
                     (when first-stop?
                       (.interrupt supervisor)
                       (when process (destroy! process))
                       (delete-tree! dir)
                       (try (.removeShutdownHook (Runtime/getRuntime) ^Thread @hook)
                            (catch IllegalStateException _ nil)))))]
    (reset! hook (Thread. ^Runnable stop! "rf2-ssr-node-shutdown"))
    (.addShutdownHook (Runtime/getRuntime) ^Thread @hook)
    (doto supervisor (.setDaemon true) (.start))
    {:endpoint (fn [] (:endpoint @state))
     :stop!    stop!}))

;; ---- the renderer ---------------------------------------------------------

(defn- closeable-fn
  "`render` as the fn `ssr-handler` calls, which is also `java.io.Closeable`
  so a host stops what the renderer owns with `.close` or `with-open` — the
  JVM's own stop protocol, so no stop var of this namespace is needed."
  [render close]
  (reify
    clojure.lang.Fn
    clojure.lang.IFn
    (invoke [_ call] (render call))
    (applyTo [this arglist] (clojure.lang.AFn/applyToHelper this arglist))
    Closeable
    (close [_] (close))))

(defn renderer
  "Construct the JVM->Node renderer — the fn `ssr-handler`'s `:renderer`
  opt takes (`(fn [{:keys [frame-id request opts]}] -> {:body-html …})`),
  which is also `java.io.Closeable`. Validates `opts` at construction
  (`validate-opts!`), builds the ONE `HttpClient` the renderer reuses and,
  under `:module`, launches the sidecar and waits for its ready line, so a
  sidecar that cannot start or serves another build fails here.

      (def node-renderer
        (node/renderer
          {:module       \"out/my-app-server/server.js\"
           :entry        \"app/root\"
           :build-id     \"2026-09-02T08:00Z-a1b2c3\"
           :render-state {:app-db     [:todos :session]
                          :runtime-db [:rf.runtime/routing]}}))

      (ssr-ring/ssr-handler
        {:frame    {:initial-events [[:app/init]]}
         :payload  [:todos]
         :renderer node-renderer})

      (.close node-renderer)   ;; stops the sidecar it launched

  `:endpoint` in place of `:module` dials a sidecar the operator runs. See
  the namespace docstring for the opts, the transport and the error codes."
  [opts]
  (let [module?              (and (map? opts) (contains? opts :module))
        defaults             (if module?
                               {:node default-node}
                               {:endpoint default-endpoint})
        validated-opts       (cond-> (validate-opts!
                                       (if (map? opts)
                                         (merge {:timeout-ms   default-timeout-ms
                                                 :admission-ms default-admission-ms}
                                                defaults
                                                opts)
                                         opts))
                               module? (update :module
                                               #(.getAbsolutePath ^File (io/file %))))
        transport-timeout-ms (http-timeout-ms validated-opts)
        client               (build-client transport-timeout-ms)
        closed?              (atom false)
        {:keys [endpoint stop!]}
        (if module?
          (own-sidecar! validated-opts)
          {:endpoint (constantly (:endpoint validated-opts))
           :stop!    (fn [])})]
    (closeable-fn
      (fn node-renderer [{frame-id :frame-id handler-opts :opts}]
        (let [dialled      (endpoint)
              request-opts (assoc validated-opts :endpoint dialled)
              _            (when-not dialled
                             (throw-unreachable!
                               request-opts
                               (IOException. (if @closed?
                                               "the renderer was closed"
                                               "it is down, and being relaunched"))))
              ;; The HANDLER's `:payload`, whose paths permit, so the
              ;; markup the sidecar prints and the payload agree on a
              ;; permitted value.
              partitions   (rf.ssr.render-state/project
                             frame-id
                             (assoc validated-opts :payload (:payload handler-opts)))
              request-json (json/write-str
                             (request-body validated-opts frame-id partitions)
                             :escape-slash false)
              http-request (build-request (render-uri dialled) request-json
                                          transport-timeout-ms)
              http-response (send-request! client http-request request-opts
                                           transport-timeout-ms)]
          (interpret-response! request-opts http-response)))
      (fn close []
        (reset! closed? true)
        (stop!)))))
