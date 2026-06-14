(ns re-frame.testbed.open-in-editor-server
  "Dev-server 'open in editor' endpoint (Option B, rf2-wn3bh).

  The JS-ecosystem standard for jump-to-source is a dev-server endpoint:
  Vite's `/__open-in-editor`, react-dev-utils' `launchEditorEndpoint`,
  Next.js' launch-editor middleware. This namespace is the re-frame2
  equivalent — a shadow-cljs `:dev-http` Ring `:handler` (a not-found
  fallback) that answers `GET|POST /__rf-open-in-editor?file=<…>&line=<n>
  &column=<c>` by resolving the (classpath-relative) `:file` against the
  dev JVM's source-paths AT RUNTIME and launching the editor via the
  `launch-editor` npm package.

  ## Why server-side beats the editor:// URI scheme

  The historic open-in-editor path (still kept as the fallback — see
  `day8.re-frame2-xray.open-in-editor` / `re-frame.story.ui.open-in-editor`)
  builds an `editor://file/<abs-path>:<line>:<column>` URI and hands it to
  `window.location`. That works zero-config for the common local-build
  case (rf2-wvsxg bakes the absolute path in at macro-expansion time), but
  it has two residual gaps Option B closes:

    1. `absolutise-file` (rf2-wvsxg) can only absolutise coords whose
       sources are classpath `file:` resources — sources consumed as JARs
       / in-jar / unusual classpaths leave a RELATIVE `:file` that needs a
       manual `:project-root`. This endpoint resolves the relative file
       against the live source-paths on the dev machine, so jump-to-source
       is zero-config for EVERYONE including the JAR/relative-coord case.
    2. `absolutise-file` bakes the BUILDER's absolute home path into the
       dev bundle (DCE-d in prod, so not a shipped leak, but it puts the
       dev username/path into the dev JS). This endpoint resolves the path
       at runtime on the server — nothing baked into the bundle.

  ## Bundle isolation

  This is a `.clj` file (JVM-only) that runs on the shadow-cljs SERVER
  JVM — it is NEVER part of any browser/CLJS build (shadow ignores `.clj`
  sources for CLJS compilation), so it cannot leak into a production
  bundle. It is wired into the testbed `:dev-http` ports in
  `implementation/shadow-cljs.edn`; the `:dev-http` block itself is honoured
  only by `shadow-cljs watch`/`compile`, never by `release`.

  ## Editor launch

  `launch-editor` (the Vite/CRA/Next package) is a Node-only library with
  no CLI, so we shell out to `node` running a one-liner that requires it
  and passes the resolved `<abs-path>:<line>:<column>` string plus an
  optional editor-command hint. `launch-editor` parses the `:line:column`
  suffix itself and builds the per-editor argument shape (it handles every
  OS + editor, superseding the per-editor `editor://` scheme table). The
  editor hint maps the configured editor keyword (`:vscode`/`:cursor`/…)
  to the editor's launch command; when no hint is given launch-editor
  auto-detects the running editor (and honours `$LAUNCH_EDITOR`)."
  (:require [clojure.string :as str]
            [re-frame.source-coords.editor-uri :as editor-uri])
  (:import [java.net URLDecoder]
           [java.io File]))

(set! *warn-on-reflection* true)

(def endpoint-path
  "The request path this handler answers. Mirrors Vite's
  `/__open-in-editor`, namespaced under the `__rf-` dev-affordance prefix
  so it cannot collide with an app route."
  "/__rf-open-in-editor")

;; ---- editor-keyword → launch-editor command hint -------------------------
;;
;; `launch-editor`'s optional 2nd arg is an editor *command* (a bin name,
;; e.g. "code"), NOT the editor:// keyword our config carries. Map the
;; configured keyword to the conventional launch command so a host that
;; has already set `:rf.xray/editor :cursor` / `:rf.story/editor :idea`
;; gets the same editor server-side. Unknown / `{:custom …}` shapes return
;; nil → launch-editor falls back to running-process detection + the
;; `$LAUNCH_EDITOR` env override (the same auto-detection Vite relies on).

(def editor-command-by-keyword
  "Map of the open-in-editor keyword vocabulary → the editor's launch
  command for `launch-editor`. `:custom` templates and unknown keywords
  resolve to nil (auto-detect)."
  {"vscode"          "code"
   "vscode-insiders" "code-insiders"
   "cursor"          "cursor"
   "windsurf"        "windsurf"
   "zed"             "zed"
   "idea"            "idea"})

(defn editor-hint
  "Resolve the launch-editor command hint from an `editor` query value
  (the editor keyword as a bare string, e.g. \"cursor\"). Returns nil for
  blank / unknown values so launch-editor auto-detects."
  [editor]
  (when (and (string? editor) (not (str/blank? editor)))
    (get editor-command-by-keyword (str/lower-case (str/trim editor)))))

;; ---- runtime :file resolution (mirrors absolutise-file, rf2-wvsxg) -------
;;
;; The server runs on the dev JVM with the full shadow-cljs classpath, so
;; a classpath-relative `:file` (the common macro-captured shape — e.g.
;; "day8/re_frame2_xray/views/edn_inspector.cljs") resolves to its on-disk
;; absolute path via the context class-loader exactly like the JVM-side
;; `re-frame.source-coords/absolutise-file` does at macro-expansion time —
;; but here at RUNTIME, so it works even when the build-time bake didn't
;; (JARs, odd classpaths, relative coords that fell through).

(defn ^:private context-class-loader ^ClassLoader []
  (.getContextClassLoader (Thread/currentThread)))

(defn resolve-file
  "Resolve a (possibly classpath-relative) `path` to its absolute on-disk
  path. Returns the input unchanged when it is already absolute (per
  `editor-uri/absolute-path?`), and resolves a relative path against the
  classpath via the context class-loader (the same `getResource`-then-
  decode dance as `re-frame.source-coords/absolutise-file`). When the path
  cannot be resolved on the classpath, falls back to resolving it against
  the JVM's working directory (the dev process cwd is the consumer's
  project root under shadow-cljs); if that file does not exist either, the
  input is returned unchanged so launch-editor at least gets the raw path.
  Returns nil for nil/blank input."
  [path]
  (cond
    (or (nil? path) (str/blank? path)) nil
    (editor-uri/absolute-path? path)   path
    :else
    (or
      ;; 1. Classpath resource (the common case — source-paths are on the
      ;;    dev classpath; this is the runtime twin of absolutise-file).
      (try
        (when-let [url (.getResource (context-class-loader) path)]
          (when (= "file" (.getProtocol url))
            (let [decoded (URLDecoder/decode (.getPath url) "UTF-8")]
              ;; URL paths on Windows come out as "/C:/Users/..." — strip the
              ;; leading slash before a drive-letter to the canonical shape.
              (if (and (> (.length decoded) 2)
                       (= \/ (.charAt decoded 0))
                       (= \: (.charAt decoded 2)))
                (.substring decoded 1)
                decoded))))
        (catch Throwable _ nil))
      ;; 2. Relative to the dev process cwd (JAR / in-jar / off-classpath
      ;;    coords that getResource cannot reach — the gap Option B closes).
      (try
        (let [f     (File. ^String path)
              cwd-f (File. ^String (System/getProperty "user.dir") ^String path)]
          (cond
            (.exists f)     (.getAbsolutePath f)
            (.exists cwd-f) (.getAbsolutePath cwd-f)
            :else           nil))
        (catch Throwable _ nil))
      ;; 3. Unresolvable — hand the raw path back; launch-editor will try it.
      path)))

;; ---- the launch shim -----------------------------------------------------
;;
;; launch-editor has no CLI, so we run `node -e` requiring it. The file
;; spec carries the :line:column suffix launch-editor parses itself; the
;; editor command (if any) is the optional 2nd argv. Resolved relative to
;; the dev JVM's working directory so `node`'s require finds the package in
;; the consumer's node_modules.

(def ^:private launch-shim
  ;; Single-line Node program: require launch-editor and invoke it with
  ;; argv[1]=<file:line:col> argv[2]=<editor-or-empty>. On the error
  ;; callback, write the message to stderr and exit non-zero so the JVM
  ;; side sees the failure in the shell exit code.
  (str "var l=require('launch-editor');"
       "var f=process.argv[1];var e=process.argv[2]||undefined;"
       "l(f,e,function(file,msg){"
       "process.stderr.write('launch-editor: '+(msg||'no editor found')+'\\n');"
       "process.exit(1);});"))

(defn launch!
  "Shell out to node + launch-editor for `abs-path` at `line`/`column`,
  with an optional launch-editor `command` hint. Returns a map
  `{:ok bool :message string?}`. Runs from the dev JVM cwd so node resolves
  the launch-editor package in the consumer's node_modules. Never throws —
  any failure (node missing, package absent, launch error) degrades to
  `{:ok false :message …}` so the endpoint can answer cleanly and the
  client falls back to the editor:// URI."
  [abs-path line column command]
  (let [file-spec (str abs-path
                       (when line (str ":" line
                                       (when column (str ":" column)))))
        args (cond-> ["node" "-e" launch-shim file-spec]
               command (conj command))]
    (try
      (let [pb (doto (ProcessBuilder. ^java.util.List args)
                 (.redirectErrorStream false))
            _  (.directory pb (File. (System/getProperty "user.dir")))
            proc (.start pb)
            ;; launch-editor returns ~immediately (it spawns the editor
            ;; detached); bound the wait so a hung node never wedges the
            ;; dev server's request thread.
            done? (.waitFor proc 10 java.util.concurrent.TimeUnit/SECONDS)
            exit  (when done? (.exitValue proc))
            err   (when done?
                    (slurp (.getErrorStream proc)))]
        (cond
          (not done?)  (do (.destroy proc)
                           {:ok false :message "launch-editor timed out"})
          (zero? exit) {:ok true}
          :else        {:ok false
                        :message (or (when (seq err) (str/trim err))
                                     (str "launch-editor exited " exit))}))
      (catch Throwable t
        {:ok false :message (.getMessage t)}))))

;; ---- query parsing -------------------------------------------------------

(defn ^:private parse-query
  "Parse a URL query string into a `{name value}` map (last value wins).
  Values are URL-decoded. Returns `{}` for nil/blank."
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
                   (URLDecoder/decode ^String k "UTF-8")
                   (URLDecoder/decode ^String v "UTF-8")))))
      {}
      (str/split qs #"&"))))

(defn ^:private ->int
  "Parse a positive integer from `s`, or nil."
  [s]
  (when (and (string? s) (re-matches #"\d+" s))
    (try (Long/parseLong s) (catch Throwable _ nil))))

(defn ^:private json-resp
  [status m]
  {:status  status
   :headers {"content-type"                "application/json"
             ;; The Xray/Story client fetches from the same origin
             ;; (its own dev-http port), so CORS is not strictly needed,
             ;; but the wildcard keeps a cross-port probe (e.g. a Story
             ;; shell on 8042 reaching an app on 8031) working.
             "access-control-allow-origin" "*"
             "cache-control"               "no-store"}
   :body    (str "{"
                 (str/join ","
                           (for [[k v] m]
                             (str "\"" (name k) "\":"
                                  (cond
                                    (boolean? v) (str v)
                                    (number? v)  (str v)
                                    (nil? v)     "null"
                                    :else (str "\"" (-> (str v)
                                                        (str/replace "\\" "\\\\")
                                                        (str/replace "\"" "\\\"")) "\"")))))
                 "}")})

;; ---- the Ring handler ----------------------------------------------------

(defn handle
  "Core request handler for the open-in-editor endpoint. Pure-ish: takes a
  standard Ring request map, returns a Ring response map (or nil to fall
  through). Answers only the `endpoint-path`; returns nil for every other
  path so the caller can defer to the next handler / push-state default.

  Query/form params:
    `file`   — required; the source-coord `:file` (classpath-relative or
               absolute). Resolved against source-paths at runtime.
    `line`   — optional; integer.
    `column` — optional; integer.
    `editor` — optional; the editor keyword as a bare string (e.g.
               \"cursor\"); maps to a launch-editor command hint.

  Responses:
    200 `{\"ok\":true,\"file\":\"<abs>\"}`        — editor launched.
    400 `{\"ok\":false,\"error\":\"missing-file\"}` — no `file` param.
    422 `{\"ok\":false,\"error\":\"<msg>\"}`       — launch failed."
  [{:keys [uri request-method query-string]}]
  (when (= uri endpoint-path)
    (if (= request-method :options)
      ;; CORS preflight — some browsers preflight a cross-port POST.
      {:status 204
       :headers {"access-control-allow-origin"  "*"
                 "access-control-allow-methods" "GET, POST, OPTIONS"
                 "access-control-allow-headers" "content-type"}}
      (let [q      (parse-query query-string)
            file   (get q "file")
            line   (->int (get q "line"))
            column (->int (get q "column"))
            cmd    (editor-hint (get q "editor"))]
        (if (str/blank? file)
          (json-resp 400 {:ok false :error "missing-file"})
          (let [abs-path (resolve-file file)
                {:keys [ok message]} (launch! abs-path line column cmd)]
            (if ok
              (json-resp 200 {:ok true :file abs-path})
              (json-resp 422 {:ok false :error (or message "launch-failed")}))))))))

(defn handler
  "The shadow-cljs `:dev-http` `:handler` entry-point. A not-found
  fallback: shadow tries the static file roots first, then calls this for
  anything unmatched. We answer only `endpoint-path` and otherwise return a
  404 (this is the last link in shadow's chain — returning nil here yields
  an empty response rather than deferring, so a plain 404 is the honest
  answer for paths we don't own)."
  [req]
  (or (handle req)
      {:status 404
       :headers {"content-type" "text/plain"}
       :body "not found"}))
