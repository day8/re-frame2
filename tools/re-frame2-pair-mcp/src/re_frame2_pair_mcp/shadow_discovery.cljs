(ns re-frame2-pair-mcp.shadow-discovery
  "Locate the consumer project's nREPL port file via shadow-cljs's
  built-in HTTP API — the cwd-robust auto-discovery step.

  ## The cwd problem the nREPL file-scan can't solve

  shadow-cljs writes its nREPL port to a relative path inside the
  consumer project (`target/shadow-cljs/nrepl.port`,
  `.shadow-cljs/nrepl.port`, or `.nrepl-port`). The MCP server can read
  that file when its `process.cwd()` is the project root — but
  `process.cwd()` is set by the spawning process, which for MCP servers
  is the *agent host* (Claude Code / Cursor / Copilot), not the user.
  Agent hosts pick their own cwd, frequently the host's install
  directory or `$HOME`, so the file scan misses silently and pairing
  breaks with `:nrepl-port-not-found`.

  ## Why shadow's HTTP API closes the gap

  shadow-cljs runs a web server (default port 9630) for its dashboard.
  That server exposes `GET /api/project-info`, which returns a
  transit-json map carrying the live build's `:project-home` (absolute
  path to the project root). With that absolute root in hand, the
  existing port-file candidates (`target/shadow-cljs/nrepl.port`, …)
  become absolute paths the cwd can't perturb.

  The HTTP server's port is fixed by shadow's `:http :port` config and
  doesn't randomise across restarts (unlike the nREPL port, which is
  ephemeral by default). 9630 is the documented default; exotic
  configurations can pass `--http-port <n>`.

  ## What this namespace does

  One pure async fn: probe the shadow HTTP API, return the project root,
  bail to nil on every error. The orchestrator in `nrepl.cljs` runs the
  same port-file candidates against this absolute root before falling
  back to the legacy cwd-relative scan.

  ## Transit-json shape

  The `/api/project-info` payload is transit-json's verbose map-as-array:

      [\"^ \", \"~:project-config\", \"...\",
              \"~:project-home\",   \"<abs-project-root>\",
              \"~:version\",        \"3.4.10\"]

  We don't pull in a transit library for one key — a JSON.parse plus a
  linear walk for the `~:project-home` sentinel is honest about the one
  shape we depend on. If shadow changes the wire format we want a clear
  parser failure here, not a transit library silently doing the wrong
  thing under an evolving spec."
  (:require [applied-science.js-interop :as j]
            ["http" :as http]))

(def ^:const default-http-port
  "Shadow-cljs's documented default web-server port. Overridable via the
  `--http-port` launch flag for the (rare) consumer that pins shadow's
  `:http :port` to something else."
  9630)

(def ^:const default-host "127.0.0.1")

(def ^:const probe-timeout-ms
  "Bound on the HTTP probe. Shadow's HTTP server is local — `connect`
  either binds or refuses within milliseconds. If shadow's process is
  alive but unresponsive (the only realistic stall case) we'd rather
  fall through to the cwd-relative scan than hang the MCP boot."
  500)

(defn extract-project-home
  "Pull `:project-home` out of a transit-json `/api/project-info` body
  (a JSON string). Returns the absolute path string or nil.

  Transit-json encodes a map as `[\"^ \" k1 v1 k2 v2 …]` with keyword
  keys prefixed by `~:`. We walk the parsed array looking for the
  `~:project-home` sentinel and return the next element as the value.

  Public so the unit tests can pin the parser shape directly
  without standing up a fake HTTP server. A defensive parser — every
  branch where the shape disagrees collapses to nil so the caller falls
  through cleanly."
  [body]
  (try
    (let [parsed (js/JSON.parse body)]
      (when (and (array? parsed)
                 (= "^ " (aget parsed 0)))
        ;; Walk index 1.. pairs; `~:project-home` is the key sentinel.
        (loop [i 1]
          (cond
            (>= i (.-length parsed)) nil
            (= "~:project-home" (aget parsed i))
            (let [v (aget parsed (inc i))]
              (when (string? v) v))
            :else (recur (+ i 2))))))
    (catch :default _ nil)))

(defn default-request
  "Production HTTP-request seam: Node's `http.request`. Wrapped in a plain
  fn (rather than passing `http/request` bare) so the injected fake in
  `fetch-project-info`'s 3-arity is a drop-in — same `(opts callback) ->
  ClientRequest` contract, no `this`-binding surprises."
  [opts callback]
  (http/request opts callback))

(defn fetch-project-info
  "Issue `GET http://<host>:<http-port>/api/project-info` against shadow's
  web server. Returns a Promise resolving to the response body (string)
  on a 200, or rejecting on any other condition (connection refused,
  non-200 status, body-read error, timeout). Caller is expected to
  `.catch` and translate failures to nil — see `discover-project-home`.

  Kept separate from the parsing step so tests can stub one or the
  other in isolation.

  The 3-arity injects the HTTP-request seam (`request-fn`, a
  `(opts callback) -> ClientRequest`) so the below-the-socket branches —
  non-200 reject, multi-chunk body assembly, the bounded-timeout
  destroy+reject, and the settle-once double-settle guard — are unit-
  testable with a fake ClientRequest, no live socket. The 2-arity threads
  the production `default-request`."
  ([host http-port] (fetch-project-info host http-port default-request))
  ([host http-port request-fn]
  (js/Promise.
    (fn [resolve reject]
      (let [opts        #js {:host host
                             :port http-port
                             :path "/api/project-info"
                             :method "GET"}
            settled?    (atom false)
            settle-once (fn [f v]
                          (when (compare-and-set! settled? false true)
                            (f v)))
            ^js req     (request-fn
                          opts
                          (fn [^js res]
                            (let [status (.-statusCode res)]
                              (if-not (= 200 status)
                                (do (.resume res)
                                    (settle-once
                                      reject
                                      (js/Error.
                                        (str "shadow /api/project-info returned HTTP " status))))
                                (let [chunks (array)]
                                  (.setEncoding res "utf8")
                                  (j/call res :on "data" #(.push chunks %))
                                  (j/call res :on "end"
                                          #(settle-once resolve (.join chunks "")))
                                  (j/call res :on "error"
                                          #(settle-once reject %)))))))]
        (j/call req :on "error" #(settle-once reject %))
        ;; Bounded probe — see `probe-timeout-ms` for rationale.
        (j/call req :setTimeout probe-timeout-ms
                (fn []
                  (try (.destroy req (js/Error. "shadow HTTP probe timed out"))
                       (catch :default _ nil))
                  (settle-once reject (js/Error. "shadow HTTP probe timed out"))))
        (.end req))))))

(defn discover-project-home*
  "Like [[discover-project-home]] but takes the HTTP-fetch fn as an
  argument so tests can stub `fetch-project-info`. Production callers
  use the 0-/2-arity wrapper below.

  Returns a Promise resolving to the absolute project-home string or
  nil. Never rejects."
  [host http-port fetch-fn]
  (-> (fetch-fn host http-port)
      (.then (fn [body] (extract-project-home body)))
      (.catch (fn [_err] nil))))

(defn discover-project-home
  "Probe shadow's HTTP API at `host:http-port` and return a Promise
  resolving to the absolute project-home path (string) — or nil if the
  probe fails for any reason (shadow not running, wrong port, parse
  failure, timeout, non-200, malformed payload).

  Never rejects: the caller wants a value-or-nil so it can fall through
  to the next discovery step without a try/catch around every call site.

  The cascade in `nrepl.cljs` calls `discover-project-home*` via an
  injected probe seam (see `nrepl/discover-port*`); this 0-/2-arity
  wrapper is the boot-time default that threads `fetch-project-info` in."
  ([] (discover-project-home default-host default-http-port))
  ([host http-port]
   (discover-project-home* host http-port fetch-project-info)))
