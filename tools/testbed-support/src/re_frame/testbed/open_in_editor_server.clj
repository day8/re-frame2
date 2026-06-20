(ns re-frame.testbed.open-in-editor-server
  "Dev-server 'open in editor' endpoint (Option B).

  The JS-ecosystem standard for jump-to-source is a dev-server endpoint:
  Vite's `/__open-in-editor`, react-dev-utils' `launchEditorEndpoint`,
  Next.js' launch-editor middleware. This namespace is the re-frame2
  equivalent — a shadow-cljs `:dev-http` Ring `:handler` (a not-found
  fallback) that answers `POST /__rf-open-in-editor?file=<…>&line=<n>
  &column=<c>` by resolving the (classpath-relative) `:file` against the
  dev JVM's source-paths AT RUNTIME and launching the editor via the
  `launch-editor` npm package.

  ## Why it is POST-only + loopback-guarded

  The endpoint LAUNCHES the developer's editor on a local file path, so
  left open it is a drive-by vector in the historic Vite / react-dev-utils
  CVE class — any page the developer is visiting could fire a request at
  `http://localhost:<dev-port>` and open an attacker-chosen file. The
  handler therefore acts only on a POST that is addressed to a loopback
  `Host` and (when one is present) carries a loopback `Origin`; CORS is
  reflected to that validated loopback origin, never `*`. A simple GET/HEAD
  drive-by, a request reaching a non-loopback hostname, or a POST from a
  remote page's Origin is rejected before any path resolution. The default
  client (`re-frame.source-coords.open-endpoint`) already POSTs from the
  same/cross-PORT dev origin, so the dev DX is unchanged.

  ## Why server-side beats the editor:// URI scheme

  The editor:// open-in-editor path (kept as the fallback — see
  `day8.re-frame2-xray.open-in-editor` / `re-frame.story.ui.open-in-editor`)
  builds an `editor://file/<abs-path>:<line>:<column>` URI and hands it to
  `window.location`. That works zero-config for the common local-build
  case (`absolutise-file` bakes the absolute path in at macro-expansion
  time), but it has two residual gaps Option B closes:

    1. `absolutise-file` can only absolutise coords whose
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
  OS + editor, so no per-editor `editor://` scheme table is needed). The
  editor hint maps the configured editor keyword (`:vscode`/`:cursor`/…)
  to the editor's launch command; when no hint is given launch-editor
  auto-detects the running editor (and honours `$LAUNCH_EDITOR`)."
  (:require [clojure.string :as str]
            [re-frame.source-coords.editor-uri :as editor-uri])
  (:import [java.net URI URL URLDecoder]
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

;; ---- runtime :file resolution (mirrors absolutise-file) ------------------
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

(defn ^:private file-url->path
  "Decode a classpath `file:` resource `url` to its on-disk path.

  Uses `URI`-based path decoding rather than `URLDecoder`: `URLDecoder`
  is for `application/x-www-form-urlencoded` form bodies, where `+` means
  a space — but a `file:` URL path is NOT form-encoded, so a literal `+`
  in a checkout path (e.g. `C:/code/re-frame2+wip`) must survive verbatim.
  `URI.getPath` decodes percent-escapes (`%20` → space, `%2B` → `+`) while
  leaving a literal `+` untouched, which is the correct grammar for a
  `file:` URL path. URL paths on Windows come out as `/C:/Users/...`, so
  strip the leading slash before a drive-letter to the canonical shape."
  [^URL url]
  (let [decoded (.getPath (.toURI url))]
    (if (and (> (.length decoded) 2)
             (= \/ (.charAt decoded 0))
             (= \: (.charAt decoded 2)))
      (.substring decoded 1)
      decoded)))

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
            (file-url->path url)))
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

;; ---- localhost / origin guard --------------------------------------------
;;
;; This endpoint LAUNCHES the developer's editor on an arbitrary local file
;; path. Left open it is a drive-by vector in the historic Vite /
;; react-dev-utils CVE class: any web page the developer happens to be
;; visiting could fire a request at `http://localhost:<dev-port>` and open
;; an attacker-chosen file in the editor. The guard pins the endpoint to the
;; local machine and to local browsing contexts:
;;
;;   1. request-method must be POST (the launch path). A simple GET/HEAD —
;;      the only thing an `<img>`/`<form>`/`<link>` drive-by or a `no-cors`
;;      simple request can issue — never reaches `launch!`.
;;   2. the `Host` header must name a loopback address: the request must
;;      have been addressed to localhost / 127.0.0.1 / [::1]. This rejects a
;;      request that reached the dev JVM via a non-loopback hostname (a
;;      DNS-rebinding / public-binding attempt).
;;   3. when an `Origin` header is present it must itself be a loopback
;;      origin. A page served from `https://evil.example` carries that
;;      Origin on its cross-origin POST and is rejected; a legitimate
;;      cross-PORT dev request (Story shell on :8042 → app on :8031) carries
;;      a `http://localhost:8042` Origin and passes. (A same-origin POST may
;;      omit Origin entirely, which is allowed once 1 + 2 hold.)
;;
;; CORS is then reflected to the validated loopback Origin only — never a
;; `*` wildcard.

(defn ^:private loopback-host?
  "True when `host` (a `Host`-header value, optionally `name:port`, or the
  host portion of an Origin/URL) names a loopback address: `localhost`,
  `127.0.0.0/8`, or the IPv6 `[::1]`/`::1`. Case-insensitive; nil/blank is
  not loopback."
  [host]
  (boolean
    (when (and (string? host) (not (str/blank? host)))
      (let [h    (str/lower-case (str/trim host))
            ;; Drop a trailing :port. For a bracketed IPv6 literal
            ;; (`[::1]:8080`) split on `]:`; otherwise on the last `:` only
            ;; when there is exactly one (an unbracketed IPv6 has many and
            ;; carries no port in a Host header).
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
  "Extract the host portion of an `Origin` header value (a serialized
  origin, e.g. `http://localhost:8042`). Returns nil when it cannot be
  parsed. The literal string `\"null\"` (an opaque origin — sandboxed
  iframe, `file:`, some redirects) yields nil so it never passes the
  loopback check."
  [origin]
  (when (and (string? origin)
             (not (str/blank? origin))
             (not= "null" (str/trim origin)))
    (try
      (.getHost (URI. (str/trim origin)))
      (catch Throwable _ nil))))

(defn ^:private get-header
  "Read a request header case-insensitively from the Ring `:headers` map
  (shadow-cljs dev-http lowercases header names, but read defensively)."
  [headers nm]
  (when (map? headers)
    (or (get headers nm)
        (some (fn [[k v]] (when (and (string? k)
                                     (.equalsIgnoreCase ^String k nm))
                            v))
              headers))))

(defn ^:private local-request?
  "Guard predicate: true when the request is safe to act on — addressed to a
  loopback `Host` AND, if it carries an `Origin`, that Origin is itself a
  loopback origin. A same-origin request that omits Origin passes on the
  Host check alone. See the `loopback-host?` comment for the threat model."
  [{:keys [headers]}]
  (let [host   (get-header headers "host")
        origin (get-header headers "origin")]
    (and (loopback-host? host)
         (or (nil? origin)
             (str/blank? origin)
             (loopback-host? (origin-host origin))))))

(defn ^:private allow-origin
  "The `access-control-allow-origin` value to reflect: the request's own
  Origin when it is a validated loopback origin (so a legitimate cross-PORT
  dev request gets a usable CORS header), else `\"null\"` (deny). Never a
  `*` wildcard."
  [{:keys [headers]}]
  (let [origin (get-header headers "origin")]
    (if (and origin (loopback-host? (origin-host origin)))
      origin
      "null")))

(defn ^:private json-resp
  [status allow-origin-val m]
  {:status  status
   :headers {"content-type"                "application/json"
             ;; CORS reflected to the validated loopback Origin only —
             ;; never `*`. A legitimate cross-PORT dev request (Story shell
             ;; on :8042 reaching an app on :8031) gets its own origin back;
             ;; everything else gets `null` (deny).
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

  Security: only a POST addressed to a loopback `Host` (with a loopback
  `Origin` when one is present) reaches `launch!` — see `local-request?`.
  A non-loopback / cross-origin request is rejected 403 before any path
  resolution or editor launch; a non-POST (e.g. a drive-by GET) gets 405.

  Responses:
    200 `{\"ok\":true,\"file\":\"<abs>\"}`        — editor launched.
    400 `{\"ok\":false,\"error\":\"missing-file\"}` — no `file` param.
    403 `{\"ok\":false,\"error\":\"forbidden\"}`    — non-loopback / cross-origin.
    405 `{\"ok\":false,\"error\":\"method-not-allowed\"}` — not POST.
    422 `{\"ok\":false,\"error\":\"<msg>\"}`       — launch failed."
  [{:keys [uri request-method query-string] :as req}]
  (when (= uri endpoint-path)
    (let [ao (allow-origin req)]
      (cond
        ;; CORS preflight for the cross-PORT dev POST. Reflect the validated
        ;; loopback origin (or deny via `null`); only POST is offered.
        (= request-method :options)
        {:status 204
         :headers {"access-control-allow-origin"  ao
                   "access-control-allow-methods" "POST, OPTIONS"
                   "access-control-allow-headers" "content-type"
                   "vary"                          "origin"}}

        ;; Origin / host guard: reject anything not driven from the local
        ;; machine + a local browsing context BEFORE touching the launch path.
        (not (local-request? req))
        (json-resp 403 ao {:ok false :error "forbidden"})

        ;; The launch path is POST-only — a simple GET/HEAD drive-by
        ;; (`<img>`/`<form>`/`no-cors` fetch) can never trigger an editor launch.
        (not= request-method :post)
        (json-resp 405 ao {:ok false :error "method-not-allowed"})

        :else
        (let [q      (parse-query query-string)
              file   (get q "file")
              line   (->int (get q "line"))
              column (->int (get q "column"))
              cmd    (editor-hint (get q "editor"))]
          (if (str/blank? file)
            (json-resp 400 ao {:ok false :error "missing-file"})
            (let [abs-path (resolve-file file)
                  {:keys [ok message]} (launch! abs-path line column cmd)]
              (if ok
                (json-resp 200 ao {:ok true :file abs-path})
                (json-resp 422 ao {:ok false :error (or message "launch-failed")})))))))))

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
