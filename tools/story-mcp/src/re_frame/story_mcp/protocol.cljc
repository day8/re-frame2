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
  implementations live under `re-frame.story-mcp.tools.*`
  (`tools.wire-pipeline`, `tools.registry`, `tools.dev`, `tools.docs`,
  `tools.testing`, `tools.write`, `tools.recorder`, `tools.result`,
  `tools.args`, `tools.cljs-resolve`, `tools.egress`, `tools.schemas`).

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
            [re-frame.mcp-base.args :as args]
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
  `tools/list` response, which Stage 7's twenty-tool registry
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
  Notifications get no response. Assumes the envelope keys have been
  keywordised (`normalize-frame` does this — `read-frame` runs it after
  `parse-json`). This predicate is the single home for the
  request-vs-notification rule — `server/dispatch` calls it rather than
  re-spelling the `(not (contains? message :id))` check inline
  (rf2-ee38b.17)."
  [message]
  (and (map? message)
       (not (contains? message :id))))

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
  "Parse a JSON string into a Clojure map with STRING keys (no recursive
  keywordisation). Returns the parsed value, or throws `ex-info` with
  `:rf.error/id :rf.error/story-mcp-json-parse-failure` on a malformed
  payload (the run-loop catches it and writes a `-32700` parse-error
  response).

  ## No-intern ingress (rf2-3luf3)

  Cheshire's `keywordize-keys` mode (`json/parse-string s true`)
  recursively interns EVERY object key — including arbitrary attacker-/
  AI-supplied nested keys under `params.arguments`, `cell-overrides`,
  write bodies, or any extra args — into the JVM's never-shrinking
  keyword table, BEFORE any bounded allowlist (`tools.args/safe-keyword`)
  runs. That both grows the keyword table without bound (a slow-burn DoS
  mirroring the `:rf.http/max-decoded-keys` threat — see
  `spec/014-HTTPRequests.md` §Keyword-interning cap) and lets an unknown
  string key intern into a keyword that a downstream `find-keyword`-based
  allowlist would then resolve.

  This fn therefore parses with STRING keys. The finite JSON-RPC
  envelope keys and the known tool-argument keys are converted to
  keywords downstream by `normalize-frame` (no-intern: unknown keys are
  dropped, never interned). Genuinely data-bearing nested maps
  (`:cell-overrides`, the object-form `:body`) stay string-keyed and are
  routed to the tool layer, which applies its own bounded keyword policy
  where a value-bearing keyword is required."
  [^String s]
  (try
    (json/parse-string s false)
    (catch Throwable e
      (throw (ex-info ":rf.error/story-mcp-json-parse-failure"
                      {:rf.error/id :rf.error/story-mcp-json-parse-failure
                       :where    'story-mcp/parse-json
                       :recovery :no-recovery
                       :reason   "re-frame2-story-mcp: JSON parse failure"
                       :raw      (when s (subs s 0 (min 200 (count s))))}
                      e)))))

;; ---- no-intern frame normalisation (rf2-3luf3) ---------------------------

(def envelope-keys
  "The finite JSON-RPC 2.0 / MCP envelope keys `normalize-frame`
  keywordises at the top level of a frame. Every one is a literal
  keyword interned at compile time, so resolving the wire string to it
  via `find-keyword` never mints a fresh keyword. Any other top-level
  key (a stray field a hostile producer slips into the envelope) is
  DROPPED — it never interns and the dispatcher never sees it.

  `:result` / `:error` are response-envelope keys: the server only ever
  *reads* request / notification frames (which carry `:method` +
  `:params`, never `:result` / `:error`), so they cannot appear on a
  real inbound frame. They are listed here purely so `normalize-frame`
  is a faithful round-trip for ANY JSON-RPC envelope — tests write a
  response then read it back through `read-frame`. Both are compile-time
  interned, so admitting them carries no intern risk."
  #{:jsonrpc :id :method :params :result :error})

(def params-keys
  "The finite `params` keys for the MCP methods we dispatch on. `:name`
  + `:arguments` are the `tools/call` shape; `initialize` /
  `notifications/initialized` carry `:protocolVersion` /
  `:capabilities` / `:clientInfo` which we never read (`handle-initialize`
  ignores its `_params`), so we keywordise only the slots a handler
  actually consults. As with `envelope-keys`, each is compile-time
  interned — resolving to it never mints a fresh keyword."
  #{:name :arguments})

(def arg-keys
  "Bounded allowlist of the TOP-LEVEL `tools/call` argument keys any
  registered handler reads. `normalize-frame` keywordises only these
  (via `find-keyword` — no intern of anything outside the set); any
  other agent-supplied argument key is DROPPED before it reaches a
  handler, so an attacker can neither intern a fresh keyword nor smuggle
  an unrecognised key into the arguments map.

  This is the single source of truth for the read-side argument
  vocabulary; it MUST list every key a `tool-*` handler pulls off its
  `arguments` map. The values these keys point at (variant ids, mode
  strings, EDN bodies, cell-override maps) are NOT walked here — they
  ride through to the tool layer as-is (string-keyed where they are
  JSON objects), where each is routed through its own bounded keyword
  policy (`safe-keyword` against a live registry, the hardened EDN
  `read-edn-body`, etc.). Per `spec/001-Wire-Protocol.md` +
  `tools/mcp-base/spec/args.md` §Keyword-interning safety."
  #{:variant-id :story-id :new-variant-id :extends           ; ids
    :substrate :active-modes :cell-overrides :base-url        ; run opts
    :body :doc :alias                                         ; write payload slots
    :tags :kind :limit :cursor                                ; filters / pagination
    :write-back :duration-ms :timeout-ms                      ; behaviour knobs
    :include-sensitive :max-tokens :dedup})                   ; egress / budget knobs

(defn- rename-allowed-keys
  "Return `m` (a possibly-string-keyed map) with every key that the
  bounded `allowed` keyword-set recognises converted to its keyword
  form, and every other key DROPPED. No-intern: a string key is
  resolved via `args/safe-keyword` against `allowed`, which routes
  through `find-keyword` on the JVM and never mints a fresh keyword for
  an unrecognised input. Non-map input passes through untouched."
  [m allowed]
  (if (map? m)
    (persistent!
     (reduce-kv
      (fn [acc k v]
        (if-let [kw (args/safe-keyword k allowed)]
          (assoc! acc kw v)
          acc))
      (transient {})
      m))
    m))

(defn normalize-frame
  "Normalise a string-keyed parsed JSON frame into the keyword-keyed
  shape the dispatcher + tool layer expect, WITHOUT interning any
  attacker-controlled key (rf2-3luf3).

  Only the finite JSON-RPC envelope keys (`envelope-keys`), the finite
  `params` keys (`params-keys`), and the bounded top-level
  argument-key allowlist (`arg-keys`) are converted to keywords — each
  resolved through `find-keyword` (via `args/safe-keyword`) so an
  unrecognised wire string never mints a fresh JVM keyword. Keys outside
  these sets are dropped at their level; they neither intern nor reach a
  handler. Nested data-bearing VALUES (a `:cell-overrides` map, an
  object-form `:body`) are passed through verbatim — string-keyed —
  for the tool layer to apply its own bounded keyword policy.

  Non-map input (a bare JSON scalar / array on the wire) passes through
  untouched; the dispatcher's `valid-envelope?` check then rejects it."
  [parsed]
  (if-not (map? parsed)
    parsed
    (let [envelope (rename-allowed-keys parsed envelope-keys)]
      (cond-> envelope
        (map? (:params envelope))
        (update :params
                (fn [params]
                  (let [p (rename-allowed-keys params params-keys)]
                    (cond-> p
                      (map? (:arguments p))
                      (update :arguments rename-allowed-keys arg-keys)))))))))

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
                                               ":rf.error/story-mcp-frame-too-large"
                                               {:rf.error/id :rf.error/story-mcp-frame-too-large
                                                :where     'story-mcp/read-frame
                                                :recovery  :no-recovery
                                                :reason    "re-frame2-story-mcp: frame exceeds cap"
                                                :max-bytes max-frame-bytes}))
        (str/blank? line)             (recur)
        :else                         (normalize-frame (parse-json line))))))

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
