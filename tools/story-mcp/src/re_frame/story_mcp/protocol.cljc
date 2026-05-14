(ns re-frame.story-mcp.protocol
  "MCP / JSON-RPC 2.0 wire-format helpers.

  Per https://modelcontextprotocol.io/specification/2025-06-18/basic/transports
  the stdio transport is newline-delimited JSON-RPC: one message per
  line on stdin/stdout, UTF-8, no embedded newlines, no extra noise on
  stdout (only valid MCP messages). stderr is free-form logging.

  This namespace owns the wire framing — read a line, parse JSON,
  validate the JSON-RPC envelope shape, hand off to a handler, serialise
  the response, write a line. The handler logic itself (`tools/list`,
  `tools/call`, etc.) lives in `re-frame.story-mcp.server`; the tool
  implementations live under `re-frame.story-mcp.tools.*` (`tools.cap`,
  `tools.registry`, `tools.dev`, `tools.docs`, `tools.testing`,
  `tools.write`, `tools.recorder`, `tools.helpers`, `tools.schemas`).

  ## JSON-RPC error codes

  Per JSON-RPC 2.0 §5.1 and MCP's reuse of them:

  | Code     | Name              | When                                    |
  |----------|-------------------|-----------------------------------------|
  | -32700   | parse-error       | Malformed JSON on the wire.             |
  | -32600   | invalid-request   | Not a valid JSON-RPC request envelope.  |
  | -32601   | method-not-found  | Unknown method (incl. unknown tool).    |
  | -32602   | invalid-params    | Method recognised, params shape wrong.  |
  | -32603   | internal-error    | Server-side fault.                      |

  Tool-execution errors (a known tool returning a failure) use the
  `tools/call` result shape with `isError: true` per the MCP spec §Error
  Handling guidance — they are NOT protocol-level errors.

  ## Cross-MCP factoring (rf2-vw4sq)

  The JSON-RPC error-code constants live in `re-frame.mcp-base.vocab`
  — the cross-MCP shared vocabulary artefact. The internal use-sites
  in this ns reach them through the `vocab` alias directly; callers
  outside the ns should `:require [re-frame.mcp-base.vocab :as vocab]`
  for the same source of truth (no parallel re-export here)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [re-frame.mcp-base.vocab :as vocab]))

;; ---- sentinels -----------------------------------------------------------

(def eof-sentinel
  "Returned by `read-frame` when the reader has closed. Consumers
  should compare against this def rather than re-spell the
  auto-namespaced `::eof` form across namespace boundaries."
  ::eof)

;; ---- frame-length cap (rf2-g9fje, fix 3/3) -------------------------------

(def ^:const max-frame-bytes
  "Hard ceiling on a single newline-delimited JSON-RPC frame from the
  stdio transport. 4 MB — well above any legitimate MCP message a
  cooperating client would send (the largest reasonable payload is a
  `tools/list` response, which Stage 7's nineteen-tool registry
  prints in ~15 KB), but small enough that a hostile or runaway
  producer can't OOM the JVM by sending a one-frame `readLine` that
  never terminates. When a frame exceeds the cap, `read-frame` throws
  an `ex-info` with `:rf.error/frame-too-large`; the run-loop catches
  and writes a parse-error response, then continues to the next
  frame."
  (* 4 1024 1024))

;; ---- envelope construction -----------------------------------------------

(defn response
  "Build a JSON-RPC success response envelope. `id` is the request id
  (echoed from the request); `result` is the method-specific payload."
  [id result]
  {:jsonrpc "2.0"
   :id      id
   :result  result})

(defn error-response
  "Build a JSON-RPC error response envelope.

  `id` may be `nil` (for top-of-protocol failures where the id couldn't
  be parsed); per JSON-RPC 2.0 §5 the `id` slot is always present in an
  error response — `null` is the correct value when unknown."
  ([id code message]
   (error-response id code message nil))
  ([id code message data]
   {:jsonrpc "2.0"
    :id      id
    :error   (cond-> {:code code :message message}
               (some? data) (assoc :data data))}))

(defn notification?
  "Per JSON-RPC 2.0 §4.1, a notification is a request without an `id`.
  Notifications get no response. Assumes keys have been keywordised
  (parse-json does this)."
  [message]
  (and (map? message)
       (not (contains? message :id))))

(defn request?
  "True iff `message` has the JSON-RPC request shape (`jsonrpc:\"2.0\"`,
  `method` present, `id` present). The `id` MAY be a number, string, or
  null per the spec — we accept any non-vector / non-map scalar."
  [message]
  (and (map? message)
       (= "2.0" (:jsonrpc message))
       (contains? message :method)
       (contains? message :id)))

(defn valid-envelope?
  "True iff `message` is a syntactically-valid JSON-RPC 2.0 request OR
  notification — both shapes are accepted here; the dispatcher decides
  which to act on. Notifications omit `id`; requests carry it."
  [message]
  (and (map? message)
       (= "2.0" (:jsonrpc message))
       (string? (:method message))
       (not (str/blank? (:method message)))))

;; ---- JSON I/O ------------------------------------------------------------

(defn parse-json
  "Parse a JSON string into a Clojure map. Returns the parsed value, or
  throws `ex-info` with `:rf.error/parse-error` on a malformed payload.

  Keys are converted to keywords for ergonomic dispatch — this matches
  what the rest of the namespace expects (`(:method m)`, `(:jsonrpc m)`,
  `(:id m)`). String keys inside `params` are NOT converted: tool
  argument maps are typically keyword-keyed at the tools layer, but
  user-supplied JSON-keys may be arbitrary strings; the tool dispatcher
  re-keywordises the top level when it parses arguments."
  [^String s]
  (try
    (json/parse-string s true)
    (catch Throwable e
      (throw (ex-info "re-frame2-story-mcp: JSON parse failure"
                      {:rf.error :rf.error/parse-error
                       :raw      (when s (subs s 0 (min 200 (count s))))}
                      e)))))

(defn write-json
  "Serialise `message` to JSON. Returns the string (no trailing newline —
  the line-writer at `server.cljc` adds it). Throws on a non-encodable
  value; the catch path at the dispatcher turns the throw into an
  internal-error response."
  [message]
  (json/generate-string message))

;; ---- frame I/O (stdio line-delimited) ------------------------------------

(defn- read-bounded-line
  "Read characters from `^java.io.BufferedReader reader` until newline,
  EOF, or `max-bytes` is reached. Returns:

    - `nil`               — EOF before any character was read.
    - bounded string      — happy path: the read line, without its
                            trailing newline.
    - `::frame-too-large` — the cap was reached before a newline.
                            The remaining bytes of the over-cap frame
                            are drained to the next newline (or EOF)
                            so the next `read-bounded-line` call
                            starts on a fresh frame boundary.

  Unlike `BufferedReader.readLine`, which will happily allocate
  unbounded memory for a `readLine` that never sees a newline, this
  helper enforces a hard ceiling — the rf2-g9fje DoS bound for the
  stdio transport."
  [^java.io.BufferedReader reader max-bytes]
  (let [sb (StringBuilder.)]
    (loop [n 0]
      (let [c (.read reader)]
        (cond
          (neg? c)
          (if (zero? n) nil (.toString sb))

          (= c (int \newline))
          (.toString sb)

          (>= n max-bytes)
          ;; Drain the rest of this frame so the next call lands on a
          ;; fresh boundary. We don't bound this drain (the bytes have
          ;; already been allocated on the wire); we just don't keep
          ;; them in the StringBuilder.
          (do (loop []
                (let [d (.read reader)]
                  (when (and (not (neg? d))
                             (not= d (int \newline)))
                    (recur))))
              ::frame-too-large)

          :else
          (do (.append sb (char c))
              (recur (inc n))))))))

(defn read-frame
  "Read one newline-delimited JSON frame from `^java.io.BufferedReader reader`.
  Returns the parsed map, or `::eof` when the reader has closed, or
  throws an `ex-info` on malformed JSON / oversize frame.

  Empty / whitespace lines are silently consumed (some agent hosts emit
  trailing newlines). Frames exceeding `max-frame-bytes` (rf2-g9fje)
  throw with `:rf.error/frame-too-large`; the run-loop catches that
  and writes a parse-error response before continuing — one oversize
  frame can't park the loop."
  [^java.io.BufferedReader reader]
  (loop []
    (let [line (read-bounded-line reader max-frame-bytes)]
      (cond
        (nil? line)                   eof-sentinel
        (= ::frame-too-large line)    (throw (ex-info
                                               "re-frame2-story-mcp: frame exceeds cap"
                                               {:rf.error :rf.error/frame-too-large
                                                :max-bytes max-frame-bytes}))
        (str/blank? line)             (recur)
        :else                         (parse-json line)))))

(defn write-frame!
  "Write one newline-delimited JSON frame to `^java.io.Writer writer`,
  flushing immediately. Per the MCP stdio transport spec, every message
  must terminate with a newline and contain no embedded newlines —
  Cheshire's default `generate-string` doesn't emit newlines.

  Throws if the encoder fails (the caller is expected to translate that
  into an internal-error response before retrying the write)."
  [^java.io.Writer writer message]
  (.write writer ^String (write-json message))
  (.write writer "\n")
  (.flush writer)
  nil)

;; ---- error helpers -------------------------------------------------------

(defn parse-error
  "Build a parse-error response. `id` is `nil` because by definition we
  couldn't parse the request to extract one."
  []
  (error-response nil vocab/code-parse-error "Parse error"))

(defn invalid-request
  "Build an invalid-request error for a syntactically-malformed envelope.
  `id` may be `nil` when the envelope failed shape-validation before
  the id could be extracted."
  [id details]
  (error-response id vocab/code-invalid-request details))

(defn method-not-found
  "Build a method-not-found error for `method`. Used by the dispatcher
  when `(:method message)` doesn't match a registered handler — and by
  `tools/call` when the named tool isn't in the registry."
  [id method]
  (error-response id vocab/code-method-not-found
                  (str "Method not found: " method)))

(defn invalid-params
  "Build an invalid-params error. `details` is human-readable."
  [id details]
  (error-response id vocab/code-invalid-params
                  (str "Invalid params: " details)))

(defn internal-error
  "Build an internal-error response. `details` is human-readable. The
  `:data` slot carries a structured error map per JSON-RPC 2.0 §5.1
  (the spec allows servers to attach arbitrary `data` to error
  responses; agent clients may surface it for debugging)."
  ([id details]
   (internal-error id details nil))
  ([id details data]
   (error-response id vocab/code-internal-error details data)))
