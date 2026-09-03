(ns re-frame.testbed.open-in-editor-server
  "Dev-only Ring endpoint for opening source coordinates in an editor.

  The JVM handler resolves classpath-relative files at request time and invokes
  the Node `launch-editor` package. Launches require POST, a loopback Host, and
  a loopback Origin when one is present. This `.clj` namespace runs only in the
  shadow-cljs server and is never part of a browser bundle."
  (:require [clojure.string :as str]
            [re-frame.source-coords :as source-coords]
            [re-frame.source-coords.editor-uri :as editor-uri])
  (:import [java.net URI]
           [java.io File InputStream InputStreamReader]
           [java.util.concurrent TimeUnit]))

(set! *warn-on-reflection* true)

(def endpoint-path
  "The dev-server path handled by this namespace."
  "/__rf-open-in-editor")

;; launch-editor expects a binary name, not the editor keyword in host config.

(def editor-command-by-keyword
  "Open-in-editor keyword strings mapped to launch-editor binary names."
  {"vscode"          "code"
   "vscode-insiders" "code-insiders"
   "cursor"          "cursor"
   "windsurf"        "windsurf"
   "zed"             "zed"
   "idea"            "idea"})

(defn editor-hint
  "Resolve an editor query value to a command hint, or nil for auto-detect."
  [editor]
  (when (and (string? editor) (not (str/blank? editor)))
    (get editor-command-by-keyword (str/lower-case (str/trim editor)))))

;; launch-editor encodes a source position PER EDITOR BINARY. A binary it has
;; no case for falls through to a bare-file launch — silently, and with a
;; successful exit, which this endpoint would otherwise report as a 200.

(def commands-without-position-support
  "launch-editor commands it invokes with the BARE FILE, dropping any
  requested line/column.

  `launch-editor`'s `get-args.js` switches on the command's basename and
  encodes a position for `code`, `code-insiders`, `cursor`, `zed` and the
  JetBrains binaries, then falls through to `return [fileName]` for
  everything else. At the pinned 2.14.1 — which is also the newest published
  release — it has no `windsurf` case, so Windsurf alone in this endpoint's
  vocabulary loses the coordinate. Drop an entry here when a pinned release
  starts encoding that editor's position; the contract test comes with it.

  Covers the commands this endpoint NAMES, and only those — it is the cheap
  half, letting a hint we already know about be declined without spawning
  Node at all. The binary auto-detect picks is decided inside the dependency,
  so `launch-shim` asks `get-args.js` itself; that probe is the general
  answer and this set is a fast path in front of it."
  #{"windsurf"})

(defn position-would-be-dropped?
  "Whether launching `command` would discard a requested `line`/`column`.

  A request carrying no coordinate loses nothing by going through the
  endpoint, and the endpoint's classpath resolution is real value the
  client-side `editor://` URI fallback cannot supply — so only a
  coordinate-BEARING request to a position-blind command is declined.

  Answers only for a command this endpoint NAMED. A nil `command` is
  launch-editor's auto-detect, whose binary is chosen inside the dependency
  from the running process list — unknowable here, so the same capability
  question is asked at launch time by `launch-shim` instead. Both routes
  answer with `position-unsupported-error`."
  [command line column]
  (boolean (and (or line column)
                (contains? commands-without-position-support command))))

(def position-unsupported-error
  "The single client-visible error token for a launch declined because the
  coordinate would not have survived it — emitted by BOTH capability routes
  (the declared-vocabulary check above and the launch-time probe in
  `launch-shim`), so the browser sees one contract rather than two."
  "editor-position-unsupported")

(def position-unsupported-exit
  "The shim's exit code for a launch DECLINED by its capability probe.
  Distinct from 1 (a launch that was attempted and failed) so `launch!` can
  tell a refusal from a failure without scraping stderr."
  3)

;; Core owns classpath URL decoding; this endpoint adds runtime cwd fallback.

(defn resolve-file
  "Resolve `path` through core's classpath resolver, then the dev-process cwd.
  Absolute paths pass through, unresolved paths remain unchanged, and blank
  input returns nil."
  [path]
  (cond
    (or (nil? path) (str/blank? path)) nil
    (editor-uri/absolute-path? path)   path
    :else
    ;; An unchanged result signals that the classpath lookup did not resolve.
    (let [absolutised (#'source-coords/absolutise-file path)]
      (if (not= absolutised path)
        absolutised
        (or
          ;; Off-classpath coordinates may still be relative to the dev cwd.
          (try
            (let [f     (File. ^String path)
                  cwd-f (File. ^String (System/getProperty "user.dir") ^String path)]
              (cond
                (.exists f)     (.getAbsolutePath f)
                (.exists cwd-f) (.getAbsolutePath cwd-f)
                :else           nil))
            (catch Throwable _ nil))
          ;; launch! applies the final existence check.
          path)))))

(defn ^:private file-exists?
  "Return whether `path` exists, treating invalid input and IO errors as false."
  [path]
  (boolean
    (when-not (str/blank? path)
      (try (.exists (File. ^String path)) (catch Throwable _ false)))))

;; launch-editor has no CLI, so we run `node -e` requiring it. The file
;; spec and optional editor binary are passed as separate argv tokens.

(def ^:private launch-shim
  ;; Runs launch-editor's OWN capability question before launching, then exits
  ;; nonzero from its callback so the JVM sees failures.
  ;;
  ;; `commands-without-position-support` can only answer for a command this
  ;; endpoint NAMED. When no `editor` hint is sent — the client omits it for a
  ;; nil preference and for `{:custom …}` — `guessEditor` picks the binary from
  ;; the running process list, and that list reaches editors `get-args.js` has
  ;; no case for: `Brackets.exe` on all three platforms, and on Windows
  ;; `Cursor.exe` too (the registry stores the capitalised process name while
  ;; the switch matches lowercase `cursor`). Either would launch the bare file,
  ;; exit 0, and make this endpoint answer 200 to a coordinate-bearing request
  ;; (rf2-1i1ec audit).
  ;;
  ;; So the probe asks the dependency instead of predicting it: resolve the
  ;; editor with the same `guessEditor` the launch will use, then ask
  ;; `getArgumentsForPosition` what it would emit for a sentinel filename.
  ;; `get-args.js` interpolates the position into every case it encodes and
  ;; falls through to `return [fileName]` for the rest, so an argv that is the
  ;; sentinel ALONE is exactly the documented drop — no editor list here to
  ;; keep in step with the dependency, and a future release that learns an
  ;; editor's position syntax lifts the decline with no edit.
  ;;
  ;; Auto-detect is therefore scanned twice on this path (once here, once
  ;; inside `launchEditor`). The resolved binary cannot be handed back as
  ;; `specifiedEditor` to save the second scan — launch-editor shell-parses
  ;; that argument, which would split a Windows path at its spaces.
  (str "var l=require('launch-editor');"
       "var guess=require('launch-editor/guess');"
       "var getArgs=require('launch-editor/get-args');"
       ;; argv: <file-spec> <line> <column> [<editor hint>] — the optional
       ;; hint is last so the coordinate tokens sit at fixed positions.
       "var f=process.argv[1];"
       "var line=process.argv[2]||undefined;var col=process.argv[3]||undefined;"
       "var e=process.argv[4]||undefined;"
       ;; Only a coordinate-bearing launch has anything to lose — and a
       ;; COLUMN ALONE is a coordinate. `build-file-spec` normalises a
       ;; column with no line to `path:1:<column>`, so a `column=7` request
       ;; asks the launcher for 1:7 and can lose it exactly as a line-bearing
       ;; one can. Gating this probe on `line` alone let every column-only
       ;; auto-detect launch past the capability check entirely, so a
       ;; position-blind binary could strip `:1:7`, exit 0 and win a 200 that
       ;; suppressed the coordinate-preserving fallback (rf2-1i1ec audit).
       ;;
       ;; The normalisation is repeated here rather than derived from the
       ;; file spec: `line||1` is the same rule `build-file-spec` applies, and
       ;; the two must agree or the probe answers about a different
       ;; coordinate than the one being launched. `get-args.js` switches on
       ;; the command BASENAME only, so the substituted values never change
       ;; the fall-through verdict — they only have to be present.
       ;; The decline is reported by its EXIT CODE alone. `launch!` reads
       ;; `position-unsupported-exit` and answers with the namespace's own
       ;; `position-unsupported-error` constant, so a token written here
       ;; would be drained and discarded — a second diagnostic channel
       ;; that nothing consumes. Generic launch failures below DO write
       ;; stderr, because their message is not derivable from the exit
       ;; code.
       "if(line||col){"
       "var ed=guess(e)[0];"
       "var a=ed?getArgs(ed,'F',line||1,col||1):null;"
       "if(a&&a.length===1&&a[0]==='F'){"
       "process.exit(" position-unsupported-exit ");}}"
       "l(f,e,function(file,msg){"
       "process.stderr.write('launch-editor: '+(msg||'no editor found')+'\\n');"
       "process.exit(1);});"))

(defn ^:private build-file-spec
  "Build launch-editor's `path[:line][:column]` token. A column without a
  line uses line 1 so the column is not parsed as a line number."
  [abs-path line column]
  (let [line (if (and column (nil? line)) 1 line)]
    (cond-> abs-path
      line   (str ":" line)
      column (str ":" column))))

(def ^:dynamic *launch-timeout-ms*
  "Max wall-clock ms to wait for the launch child to exit before terminating
  it and reporting a timeout. Dynamic so tests can shrink the budget to
  exercise the timeout branch without a real ten-second stall."
  10000)

(def ^:private stderr-diagnostic-cap
  "Max chars of child stderr retained as a launch diagnostic. The stream is
  ALWAYS drained to EOF (so the child's stderr pipe can never fill and block
  the child before it exits); only the RETAINED head is bounded, so a runaway
  dependency cannot trade the pipe deadlock for unbounded parent memory."
  8192)

(defn ^:private drain-stream
  "Read `in` to EOF, returning up to `stderr-diagnostic-cap` chars of its head.
  ALWAYS consumes the whole stream — draining the OS pipe even past the
  retained cap. Runs on its own thread (a future) concurrently with the timed
  wait so a child that fills its stderr pipe still makes progress to exit,
  rather than blocking on a write the parent hasn't read. Never throws."
  [^InputStream in]
  (let [sb  (StringBuilder.)
        buf (char-array 4096)
        cap (int stderr-diagnostic-cap)]
    (try
      (with-open [r (InputStreamReader. in java.nio.charset.StandardCharsets/UTF_8)]
        (loop []
          (let [n (.read r buf)]
            (when-not (neg? n)                       ;; -1 = EOF
              (let [room (- cap (.length sb))]
                (when (and (pos? n) (pos? room))
                  (.append sb buf (int 0) (int (min n room)))))
              (recur)))))
      (catch Throwable _ nil))
    (.toString sb)))

(defn ^:private terminate!
  "Best-effort stop of a child that overran its budget: destroy, wait briefly,
  then FORCE-destroy if it is still alive and wait again. Returns true when the
  child is confirmed dead. Force-termination is the fallback for a child that
  ignores the graceful destroy signal."
  [^Process proc]
  (.destroy proc)
  (when (and (.isAlive proc)
             (not (.waitFor proc 500 TimeUnit/MILLISECONDS)))
    (.destroyForcibly proc)
    (.waitFor proc 1000 TimeUnit/MILLISECONDS))
  (not (.isAlive proc)))

(defn launch!
  "Invoke launch-editor from the dev JVM cwd and return `{:ok ...}`.

  Missing files are rejected before spawning Node because launch-editor
  otherwise exits successfully without opening anything. Other failures are
  returned as messages instead of escaping the Ring boundary.

  The child's pipes are owned before the wait: stdout is DISCARDed (the
  endpoint never consumes it) and stderr is drained CONCURRENTLY on its own
  thread. OS pipes are bounded, so a child that fills an undrained pipe blocks
  on the write and never reaches `process.exit` — the parent would then time
  out waiting for an exit its own undrained pipe prevents (rf2-j538f7.21). A
  bounded head of stderr is retained as the failure diagnostic.

  `line` and `column` are ALSO passed as their own argv tokens, not only
  folded into the file spec: the shim's capability probe has to know whether
  a coordinate is at stake before it hands the spec to launch-editor, and
  re-parsing the spec there would duplicate the dependency's own position
  regex. An absent value is an empty token, which the shim reads as falsy.
  The optional editor hint stays LAST so those positions are fixed."
  [abs-path line column command]
  (if-not (file-exists? abs-path)
    {:ok false :message "file-not-found"}
    (let [file-spec (build-file-spec abs-path line column)
          args (cond-> ["node" "-e" launch-shim file-spec
                        (str line) (str column)]
                 command (conj command))]
      (try
        (let [pb (doto (ProcessBuilder. ^java.util.List args)
                   ;; stdout is unused by the endpoint — discard it so a chatty
                   ;; child can never fill an undrained stdout pipe and wedge.
                   (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
                   (.redirectErrorStream false)
                   (.directory (File. (System/getProperty "user.dir"))))
              proc      (.start pb)
              ;; Drain stderr CONCURRENTLY with the wait — the whole point: a
              ;; child that fills its stderr pipe must still reach exit.
              err-drain (future (drain-stream (.getErrorStream proc)))
              done?     (.waitFor proc (long *launch-timeout-ms*) TimeUnit/MILLISECONDS)]
          (if-not done?
            ;; The child overran its budget: kill it (which EOFs stderr, so the
            ;; drain future completes on its own) and report the timeout.
            (do (terminate! proc)
                {:ok false :message "launch-editor timed out"})
            (let [exit (.exitValue proc)
                  err  (deref err-drain 1000 "")]
              (cond
                (zero? exit) {:ok true}

                ;; The shim's capability probe refused BEFORE launching: read
                ;; the verdict off the exit code rather than off stderr, so the
                ;; client-visible error is a contract and not a scraped string.
                (= exit position-unsupported-exit)
                {:ok false :message position-unsupported-error}

                :else
                {:ok false
                 :message (or (when (seq err) (str/trim err))
                              (str "launch-editor exited " exit))}))))
        (catch Throwable t
          {:ok false :message (.getMessage t)})))))

;; ---- query parsing -------------------------------------------------------

(defn ^:private decode-component
  "Percent-decode with URI semantics, preserving a literal `+`. Malformed
  escapes throw `IllegalArgumentException` for the handler to map to 400."
  [^String s]
  (if (.isEmpty s)
    s
    (.getFragment (URI/create (str "#" s)))))

(defn ^:private parse-query
  "Parse a query string into a decoded map; the last value wins."
  [qs]
  (if (str/blank? qs)
    {}
    (reduce
      (fn [acc pair]
        (let [eq  (str/index-of pair "=")
              k   (if eq (subs pair 0 eq) pair)
              v   (if eq (subs pair (inc eq)) "")]
          (if (str/blank? k)
            acc
            (assoc acc
                   (decode-component k)
                   (decode-component v)))))
      {}
      (str/split qs #"&"))))

(defn ^:private ->int
  "Parse a positive integer from `s`, or nil."
  [s]
  (when (and (string? s) (re-matches #"\d+" s))
    (try (Long/parseLong s) (catch Throwable _ nil))))

;; Launches must be addressed to loopback and, when supplied, originate there.
;; The handler separately enforces POST and reflects only validated origins.

(defn ^:private loopback-host?
  "Recognize localhost, 127.0.0.0/8, and IPv6 loopback host values."
  [host]
  (boolean
    (when (and (string? host) (not (str/blank? host)))
      (let [h    (str/lower-case (str/trim host))
            ;; A single colon separates a port; multiple colons are IPv6.
            bare (cond
                   (str/starts-with? h "[")
                   (let [close (str/index-of h "]")]
                     (if close (subs h 1 close) h))

                 (and (str/includes? h ":")
                        (= 1 (count (filter #(= % \:) h))))
                   (subs h 0 (str/index-of h ":"))

                   :else h)]
        (or (= bare "localhost")
            (= bare "::1")
            (and (str/starts-with? bare "127.")
                 ;; a bare IPv4 in 127.0.0.0/8
                 (re-matches #"127\.\d{1,3}\.\d{1,3}\.\d{1,3}" bare)))))))

(defn ^:private origin-host
  "Extract an Origin host, rejecting blank, malformed, and opaque origins."
  [origin]
  (when (and (string? origin)
             (not (str/blank? origin))
             (not= "null" (str/trim origin)))
    (try
      (.getHost (URI. (str/trim origin)))
      (catch Throwable _ nil))))

(defn ^:private get-header
  "Read a Ring request header case-insensitively."
  [headers nm]
  (when (map? headers)
    (or (get headers nm)
        (some (fn [[k v]] (when (and (string? k)
                                     (.equalsIgnoreCase ^String k nm))
                            v))
              headers))))

(defn ^:private local-request?
  "Require a loopback Host and, when present, a loopback Origin."
  [{:keys [headers]}]
  (let [host   (get-header headers "host")
        origin (get-header headers "origin")]
    (and (loopback-host? host)
         (or (nil? origin)
             (str/blank? origin)
             (loopback-host? (origin-host origin))))))

(defn ^:private allow-origin
  "Reflect a validated loopback Origin; otherwise deny with `null`."
  [{:keys [headers]}]
  (let [origin (get-header headers "origin")]
    (if (and origin (loopback-host? (origin-host origin)))
      origin
      "null")))

(defn ^:private escape-json-string
  "Escape a JSON string in one pass, including every C0 control character."
  [^String s]
  (let [sb (StringBuilder. (.length s))]
    (doseq [c s]
      (case c
        \\        (.append sb "\\\\")
        \"        (.append sb "\\\"")
        \newline  (.append sb "\\n")
        \return   (.append sb "\\r")
        \tab      (.append sb "\\t")
        (if (< (int c) 0x20)
          (.append sb (format "\\u%04x" (int c)))
          (.append sb c))))
    (.toString sb)))

(defn ^:private json-resp
  [status allow-origin-val m]
  {:status  status
   :headers {"content-type"                "application/json"
             ;; A cross-port local testbed receives its own validated origin.
             "access-control-allow-origin" allow-origin-val
             "vary"                        "origin"
             "cache-control"               "no-store"}
   :body    (str "{"
                 (str/join ","
                           (for [[k v] m]
                             (str "\"" (name k) "\":"
                                  (cond
                                    (boolean? v) (str v)
                                    (number? v)  (str v)
                                    (nil? v)     "null"
                                    :else (str "\"" (escape-json-string (str v)) "\"")))))
                 "}")})

;; ---- the Ring handler ----------------------------------------------------

(defn handle
  "Handle the open-in-editor path or return nil for another path.

  Query keys are `file` (required), `line`, `column`, and `editor`. Only a
  local POST reaches `launch!`. Invalid input produces JSON 400/403/405
  responses; missing files and launch failures produce 422, as does a
  coordinate-bearing request whose editor cannot carry the position — either
  because this endpoint named a position-blind command
  (`position-would-be-dropped?`, answered here without spawning Node) or
  because the binary `launch-editor` picked by auto-detect turned out to be
  one (`launch-shim`'s probe, answered at launch time). Every one of those
  non-2xx answers hands the launch to the client's `editor://` URI fallback,
  which does carry the coordinate."
  [{:keys [uri request-method query-string] :as req}]
  (when (= uri endpoint-path)
    (let [ao (allow-origin req)]
      (cond
        ;; Cross-port local testbeds require an OPTIONS response.
        (= request-method :options)
        {:status 204
         :headers {"access-control-allow-origin"  ao
                   "access-control-allow-methods" "POST, OPTIONS"
                   "access-control-allow-headers" "content-type"
                   "vary"                          "origin"}}

        ;; Apply the network boundary before resolving a path.
        (not (local-request? req))
        (json-resp 403 ao {:ok false :error "forbidden"})

        ;; A simple GET/HEAD request must never trigger an editor launch.
        (not= request-method :post)
        (json-resp 405 ao {:ok false :error "method-not-allowed"})

        :else
        ;; Convert URI decoding failures into the endpoint's JSON error shape.
        (try
          (let [q      (parse-query query-string)
                file   (get q "file")
                line   (->int (get q "line"))
                column (->int (get q "column"))
                cmd    (editor-hint (get q "editor"))]
            (cond
              (str/blank? file)
              (json-resp 400 ao {:ok false :error "missing-file"})

              ;; A 200 here is a claim that the COORDINATE arrived, not merely
              ;; that a process exited. Where the launcher would drop it,
              ;; decline before spawning so the client's coordinate-preserving
              ;; `editor://` URI fallback gets its turn (rf2-1i1ec).
              (position-would-be-dropped? cmd line column)
              (json-resp 422 ao {:ok false :error position-unsupported-error})

              :else
              (let [abs-path (resolve-file file)
                    {:keys [ok message]} (launch! abs-path line column cmd)]
                (if ok
                  (json-resp 200 ao {:ok true :file abs-path})
                  (json-resp 422 ao {:ok false :error (or message "launch-failed")})))))
          (catch IllegalArgumentException _
            (json-resp 400 ao {:ok false :error "malformed-query"})))))))

(defn handler
  "shadow-cljs `:dev-http` fallback entry point."
  [req]
  (or (handle req)
      {:status 404
       :headers {"content-type" "text/plain"}
       :body "not found"}))
