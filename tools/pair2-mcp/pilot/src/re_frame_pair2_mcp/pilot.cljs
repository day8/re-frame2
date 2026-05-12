(ns re-frame-pair2-mcp.pilot
  "Pilot for re-frame-pair2 MCP server.

  Goals:
    1. Register an MCP tool `ping` that returns `pong`. Proves the MCP
       SDK + stdio transport interops cleanly from compiled CLJS.
    2. Register an MCP tool `nrepl-ping {:port N}` that opens a TCP
       socket to a running nREPL, sends `{:op \"eval\" :code \"(+ 1 2)\"}`
       and returns the eval result. Proves a persistent nREPL connection
       round-trips correctly from CLJS+Node.

  If both tools work from a real MCP client (or from a stdio test
  harness), the toolchain is green and the full port can proceed."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            ["@modelcontextprotocol/sdk/server/index.js" :as mcp-server]
            ["@modelcontextprotocol/sdk/server/stdio.js" :as mcp-stdio]
            ["@modelcontextprotocol/sdk/types.js" :as mcp-types]
            ["bencode" :as bencode]
            ["net" :as net]))

;; ---------------------------------------------------------------------------
;; nREPL client (minimal — proves the protocol works from Node).
;;
;; bencode is the wire format. We open a socket, write a request, read
;; bencoded frames until {"status": ["done"]} arrives. A real production
;; client (in the full port) will keep the socket open across calls,
;; share a session id, and dispatch concurrent ops by request id; the
;; pilot just opens-eval-closes per call.
;; ---------------------------------------------------------------------------

(defn- ->buf
  "Convert a JS object/string to a Node Buffer via bencode."
  [v]
  (bencode/encode (clj->js v)))

(defn- decode-all-frames
  "Decode every complete bencode frame from `buf` (concatenated nREPL
  responses arrive that way). Returns [frames remaining-buffer]; frames
  is a JS array of decoded values, remaining-buffer is the trailing
  partial frame (if any).

  Uses bencode@2's `position` after-decode marker to walk the buffer one
  frame at a time. The accessor name varies by minor version; we read
  whichever attribute is exposed."
  [^js buf]
  (let [frames (array)]
    (loop [^js b buf]
      (if (zero? (.-length b))
        [frames b]
        (let [decoded (try (bencode/decode b "utf8") (catch :default _ nil))
              ;; bencode@2 stores the byte cursor on the decode fn —
              ;; `bencode.decode.bytes` is the position AFTER the most
              ;; recent successful decode. Without this, multi-frame
              ;; TCP chunks (typical nREPL responses) get parsed only
              ;; once and the loop hangs.
              ;; bencode@2: prefer `decode.position` (offset after the
              ;; just-decoded frame). `decode.bytes` is the total
              ;; buffer length consumed and lies when multiple frames
              ;; are concatenated. See README/spec/decode-position.
              consumed (or (j/get-in bencode [:decode :position])
                           (j/get-in bencode [:decode :bytes])
                           0)]
          (cond
            (nil? decoded)        [frames b]
            (zero? consumed)      ;; no position info — give up; full buf decoded
                                  (do (.push frames decoded) [frames (js/Buffer.alloc 0)])
            :else
            (do (.push frames decoded)
                (recur (.slice b consumed)))))))))

(defn nrepl-eval
  "Open a one-shot nREPL connection to 127.0.0.1:port, send an eval op
  for `code-str`, and return a Promise resolving to the combined response
  {:value ... :out ... :err ...}.

  Pilot-only: no session pooling, no concurrent ops. The full port will
  hold one persistent connection per MCP session."
  [port code-str]
  (js/Promise.
    (fn [resolve reject]
      (let [sock     (net/createConnection #js {:host "127.0.0.1" :port port})
            buf-ref  (atom (js/Buffer.alloc 0))
            result   (atom {:out "" :err ""})
            req-id   (str (random-uuid))
            timeout  (js/setTimeout
                       (fn []
                         (.destroy sock)
                         (reject (js/Error. (str "nREPL eval timed out after 5s (port=" port ")"))))
                       5000)]
        (j/call sock :on "connect"
          (fn []
            (let [msg #js {"op" "eval" "code" code-str "id" req-id}]
              (.write sock (->buf msg)))))
        (j/call sock :on "data"
          (fn [chunk]
            (reset! buf-ref (js/Buffer.concat #js [@buf-ref chunk]))
            (let [[frames rest] (decode-all-frames @buf-ref)]
              (reset! buf-ref rest)
              (doseq [frame (array-seq frames)]
                (let [v   (j/get frame "value")
                      out (j/get frame "out")
                      err (j/get frame "err")
                      st  (or (j/get frame "status") #js [])]
                  (when v   (swap! result assoc :value v))
                  (when out (swap! result update :out str out))
                  (when err (swap! result update :err str err))
                  (when (some #(= "done" %) (js->clj st))
                    (js/clearTimeout timeout)
                    (.end sock)
                    (resolve (clj->js @result))))))))
        (j/call sock :on "error"
          (fn [err]
            (js/clearTimeout timeout)
            (reject err)))))))

;; ---------------------------------------------------------------------------
;; MCP tool definitions.
;;
;; The MCP SDK exposes `Server` (low-level) and `McpServer` (high-level).
;; The low-level Server expects setRequestHandler calls keyed by the
;; method name. We use it directly so the pilot mirrors the full port,
;; which will dispatch many tools.
;; ---------------------------------------------------------------------------

(def tools
  [{:name "ping"
    :description "Returns 'pong'. Liveness probe for the MCP server."
    :inputSchema {:type "object" :properties {} :additionalProperties false}}
   {:name "nrepl-ping"
    :description "Open a one-shot nREPL connection, eval (+ 1 2), return the value."
    :inputSchema {:type "object"
                  :properties {:port {:type "integer"
                                      :description "nREPL TCP port (default: read from .nrepl-port)"}
                               :code {:type "string"
                                      :description "Form to eval (default: (+ 1 2))"}}
                  :additionalProperties false}}])

(defn- read-port-file
  "Best-effort read of an nREPL port from common shadow-cljs locations."
  []
  (let [fs (js/require "fs")]
    (or (try (str/trim (.toString (.readFileSync fs "target/shadow-cljs/nrepl.port"))) (catch :default _ nil))
        (try (str/trim (.toString (.readFileSync fs ".shadow-cljs/nrepl.port"))) (catch :default _ nil))
        (try (str/trim (.toString (.readFileSync fs ".nrepl-port"))) (catch :default _ nil)))))

(defn- call-ping [_args]
  (js/Promise.resolve
    #js {:content #js [#js {:type "text" :text "pong"}]}))

(defn- call-nrepl-ping [args]
  (let [port (or (j/get args :port)
                 (some-> (read-port-file) js/parseInt))
        code (or (j/get args :code) "(+ 1 2)")]
    (if (or (nil? port) (js/isNaN port))
      (js/Promise.resolve
        #js {:isError true
             :content #js [#js {:type "text"
                                :text "No nREPL port supplied and none could be read from the standard port files."}]})
      (-> (nrepl-eval port code)
          (.then (fn [resp]
                   #js {:content #js [#js {:type "text"
                                           :text (js/JSON.stringify resp nil 2)}]}))
          (.catch (fn [err]
                    #js {:isError true
                         :content #js [#js {:type "text"
                                            :text (str "nREPL error: " (.-message err))}]}))))))

;; ---------------------------------------------------------------------------
;; Server wiring.
;; ---------------------------------------------------------------------------

(defn- handle-tools-list [_req]
  (js/Promise.resolve #js {:tools (clj->js tools)}))

(defn- handle-tools-call [req]
  (let [params (j/get req :params)
        name   (j/get params :name)
        args   (or (j/get params :arguments) #js {})]
    (case name
      "ping"        (call-ping args)
      "nrepl-ping"  (call-nrepl-ping args)
      (js/Promise.resolve
        #js {:isError true
             :content #js [#js {:type "text"
                                :text (str "Unknown tool: " name)}]}))))

(defn log!
  "Log to stderr — stdout is reserved for MCP messages per the stdio
  transport spec."
  [& parts]
  (.error js/console (str "[pair2-mcp-pilot] " (str/join " " parts))))

(defn main [& _args]
  (let [Server          (j/get mcp-server :Server)
        StdioTransport  (j/get mcp-stdio :StdioServerTransport)
        ListToolsSchema (j/get mcp-types :ListToolsRequestSchema)
        CallToolSchema  (j/get mcp-types :CallToolRequestSchema)
        server (Server.
                 #js {:name "re-frame-pair2-mcp-pilot"
                      :version "0.0.0-pilot"}
                 #js {:capabilities #js {:tools #js {}}})]
    (j/call server :setRequestHandler ListToolsSchema handle-tools-list)
    (j/call server :setRequestHandler CallToolSchema handle-tools-call)
    (log! "starting stdio transport")
    (-> (j/call server :connect (StdioTransport.))
        (.then (fn [_]
                 (log! "ready — awaiting MCP frames on stdin")))
        (.catch (fn [err]
                  (log! "fatal:" (.-message err))
                  (js/process.exit 1))))))
