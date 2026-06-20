(ns re-frame2-pair-mcp.roots-discovery
  "Workspace-roots–based discovery of the consumer project's nREPL port file.
  The generic solution to the cwd-leak: zero hardcoding, no shadow-specific
  port probing.

  ## The problem

  shadow-cljs writes its nREPL port to a relative path inside the consumer
  project (`target/shadow-cljs/nrepl.port`, `.shadow-cljs/nrepl.port`,
  `.nrepl-port`). The MCP server runs as a subprocess of the agent host
  (Claude Code / Cursor / Copilot); the host picks its own cwd, frequently
  `$HOME` or the host's install dir. Other escape hatches exist, but each
  carries hardcoding (operator config) or shadow-specific port probing:

  - `--port-file <absolute-path>`    — operator config.
  - `$SHADOW_CLJS_NREPL_PORT`        — operator config.
  - Shadow HTTP probe at `:9630`     — shadow-specific.

  ## What this namespace adds

  The MCP protocol's own `roots` capability: the client (Claude Code) surfaces
  the workspace roots the user has opened via `roots/list`. We walk each root
  for `shadow-cljs.edn`, then check the adjacent `.shadow-cljs/nrepl.port` —
  if present, that project has a live shadow build. The walk is bounded
  shallow (typical locations: the root itself, `<root>/implementation/`, plus
  one extra level for monorepos) and skips directories that can't contain a
  project (`node_modules`, `.git`, `target`, `.shadow-cljs`).

  Zero hardcoded paths, zero port-range probing — the workspace tells us
  where to look. Generic across MCP servers and future tools facing the same
  CWD-discovery problem.

  ## What this namespace is NOT

  This file is the pure file-system walk + roots-list orchestrator. It
  doesn't open sockets, parse argv, or connect to nREPL — those concerns
  live in `nrepl.cljs` (the transport) and `server.cljs` (the boot wiring).
  The walk and the candidate-branch logic are unit-testable here by injecting
  the directory reader and the `roots/list` fn.

  ## Fallbacks for older clients

  If `roots/list` rejects (older client, no capability), the caller
  (`nrepl.cljs`) falls through to the HTTP probe + cwd-scan
  cascade. The fallback path is observable: `discover-via-roots` resolves
  to `:reason :workspace-discovery-unsupported` (vs the
  `:no-running-shadow-in-workspace` branch where roots WAS supported but
  no shadow was running)."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as node-path]
            ["url" :as node-url]))

;; ---------------------------------------------------------------------------
;; Walk parameters
;; ---------------------------------------------------------------------------

(def ^:const ^:private walk-max-depth
  "Bound on the recursive shadow-cljs.edn search. The walk visits each
  root + one or two levels below — enough for the typical layouts
  (root-level edn, `<root>/implementation/edn`, monorepo `<root>/<pkg>/edn`)
  without scanning arbitrary deep trees. A monorepo with deeper nesting
  can still use the HTTP probe fallback, or pass `--port-file`."
  3)

(def ^:const ^:private skip-dir-names
  "Directories we skip during the walk. Each one has a reason:

  - `node_modules`  large npm trees with no project edns; one root can
                    have ~30k entries here, the walk would dominate boot.
  - `.git`          opaque blobs; never contains shadow-cljs.edn.
  - `.shadow-cljs`  shadow's per-project state (incl. the port file we're
                    looking for); a nested `.shadow-cljs/` is never a
                    sibling of a sibling `shadow-cljs.edn`.
  - `target`        compile output for shadow + lein; never contains edn.
  - `out`           shadow + lein output convention.
  - `.cpcache`      Clojure CLI cache.
  - `.idea`/`.vscode`  IDE state.
  - `dist`/`build`  generic build-output naming.
  "
  #{"node_modules" ".git" ".shadow-cljs" "target" "out" ".cpcache"
    ".idea" ".vscode" "dist" "build" ".cache" "tmp"})

;; ---------------------------------------------------------------------------
;; URI parsing — `roots/list` returns `[{:uri \"file:///abs/path\"}]`
;; ---------------------------------------------------------------------------

(defn uri->path
  "Convert a `file://` URI into a local filesystem path. Returns the path
  string or nil if the URI isn't a `file://` URI (the roots spec allows
  custom schemes; we only handle the common case).

  Uses Node's `url.fileURLToPath` so Windows path conventions
  (`file:///C:/...`) and POSIX (`file:///home/...`) decode identically.
  A malformed URI surfaces as nil — the caller skips that root."
  [uri]
  (try
    (when (and (string? uri) (str/starts-with? uri "file:"))
      (node-url/fileURLToPath uri))
    (catch :default _ nil)))

;; ---------------------------------------------------------------------------
;; Filesystem walk — locate `shadow-cljs.edn` files under each root.
;; ---------------------------------------------------------------------------

(defn- read-dir-safe
  "Wrap `fs.readdirSync` with `withFileTypes: true` so we get Dirent
  objects (name + isDirectory). Returns a JS array on success; an empty
  array on any error (ENOENT, EACCES, etc.). Callers expect an iterable
  — they never inspect the error reason."
  [readdir-fn dir-path]
  (try
    (readdir-fn dir-path #js {:withFileTypes true})
    (catch :default _ #js [])))

(defn walk-for-shadow-edns*
  "Walk `start-dir` (a directory path) recursively up to `walk-max-depth`,
  returning a JS array of absolute paths to every `shadow-cljs.edn` file
  found. Skips directories named in `skip-dir-names`.

  `readdir-fn` is injected so tests can stand up a synthetic filesystem
  without `fs/mkdirSync` plumbing — the contract is `(dir-path opts)`
  returning a JS array of Dirent-shaped objects with `.name` and
  `.isDirectory()`.

  Public-for-test — the bedrock primitive every higher-level
  fn here depends on; pinning it directly is what gives the discovery
  cascade unit coverage without a live shadow + Claude Code session."
  [readdir-fn start-dir]
  (let [hits (array)]
    (letfn [(visit [dir depth]
              (when (<= depth walk-max-depth)
                (doseq [^js entry (array-seq (read-dir-safe readdir-fn dir))]
                  (let [name (.-name entry)
                        full (node-path/join dir name)]
                    (cond
                      (and (.isFile entry) (= name "shadow-cljs.edn"))
                      (.push hits full)

                      (and (.isDirectory entry)
                           (not (contains? skip-dir-names name)))
                      (visit full (inc depth)))))))]
      (visit start-dir 0))
    hits))

(defn walk-for-shadow-edns
  "Default-arity wrapper around [[walk-for-shadow-edns*]] using the real
  `fs.readdirSync`. Returns a JS array of absolute paths to every
  `shadow-cljs.edn` under `start-dir`."
  [start-dir]
  (walk-for-shadow-edns* (.-readdirSync fs) start-dir))

;; ---------------------------------------------------------------------------
;; Candidate construction — pair each shadow-cljs.edn with its port file
;; ---------------------------------------------------------------------------

(def ^:private port-file-relpaths
  "Per shadow-cljs convention, the nREPL port file lives at one of these
  paths relative to the project root (same list as `nrepl/port-file-candidates`
  — kept in sync deliberately). The roots discovery walks each project's
  candidates in order and takes the first hit."
  ["target/shadow-cljs/nrepl.port"
   ".shadow-cljs/nrepl.port"
   ".nrepl-port"])

(defn- read-port-from*
  "Read + trim + parseInt a port file via the injected `read-file-fn`
  (path → string, throws on missing). Returns an integer port or nil.
  Mirrors `nrepl/read-port-file` but takes the reader as an argument so
  tests can drive this with a synthetic filesystem."
  [read-file-fn path]
  (try
    (let [content (str/trim (.toString (read-file-fn path)))
          n       (js/parseInt content 10)]
      (when-not (js/isNaN n) n))
    (catch :default _ nil)))

(defn project-home->candidate*
  "Given an absolute project-home directory, look for a port file at the
  three standard relative locations. Returns
  `{:project-home <abs> :port-file <abs> :port <int>}` on the first hit,
  or nil if no port file is present (shadow isn't running for that project).

  Injected `read-file-fn` is the fs reader (path → string, throws on missing).
  Test seam — production callers use [[project-home->candidate]]."
  [read-file-fn project-home]
  (some (fn [rel]
          (let [pf   (node-path/join project-home rel)
                port (read-port-from* read-file-fn pf)]
            (when port
              {:project-home project-home
               :port-file    pf
               :port         port})))
        port-file-relpaths))

(defn project-home->candidate
  "Default-arity wrapper around [[project-home->candidate*]] using
  `fs.readFileSync`. See that fn's docstring for the contract."
  [project-home]
  (project-home->candidate* (.-readFileSync fs) project-home))

(defn candidates-from-edns*
  "Map a JS array of `shadow-cljs.edn` paths to the live nREPL candidates.
  Each edn's parent directory is the project root; we ask
  `project-home->candidate*` whether a port file exists there.

  Returns a CLJS vector of `{:project-home :port-file :port}` maps — one
  per project with a running shadow. Projects whose shadow isn't running
  are filtered out (no port file ≡ no live build to attach to)."
  [read-file-fn edn-paths]
  (->> (array-seq edn-paths)
       (map (fn [edn] (node-path/dirname edn)))
       (distinct)
       (keep #(project-home->candidate* read-file-fn %))
       (vec)))

(defn candidates-from-edns
  "Default-arity wrapper around [[candidates-from-edns*]] using
  `fs.readFileSync`."
  [edn-paths]
  (candidates-from-edns* (.-readFileSync fs) edn-paths))

;; ---------------------------------------------------------------------------
;; Top-level discovery — `roots/list` → walk → candidates
;; ---------------------------------------------------------------------------

(defn- result-no-shadow [root-paths edn-paths]
  {:reason       :no-running-shadow-in-workspace
   :roots        (vec root-paths)
   :checked-edns (vec (array-seq edn-paths))
   :hint         (str "No `.shadow-cljs/nrepl.port` found beside any "
                      "`shadow-cljs.edn` in the open workspace roots. "
                      "Start `shadow-cljs watch <build>` in one of these "
                      "projects, or pass `--port-file <absolute-path>`.")})

(defn- result-ambiguous [candidates]
  {:reason     :ambiguous-shadow
   :candidates candidates
   :hint       (str "Multiple running shadow-cljs builds found in the "
                    "open workspace. Pass `--port-file <absolute-path>` "
                    "to disambiguate, or ask the MCP client to elicit a "
                    "choice (this server requested `elicitation/create`).")})

(defn- result-unsupported []
  {:reason :workspace-discovery-unsupported
   :hint   (str "MCP client doesn't surface workspace roots via "
                "`roots/list`. Pass `--port-file <absolute-path>` or "
                "upgrade the client.")})

(defn discover-via-roots*
  "Run the full roots-based discovery flow with all I/O injected:

  - `list-roots-fn`   `() → Promise<{:roots [{:uri ...} ...]}>` — the MCP
                      `server.listRoots()` call. Rejects when the client
                      doesn't support the capability.
  - `walk-fn`         `(dir) → JS array<edn-path>` — directory walk
                      (production: [[walk-for-shadow-edns]]).
  - `read-file-fn`    `(path) → string` — file reader (production: `fs.readFileSync`).

  Returns a Promise resolving to one of:

    - `{:status :one  :candidate {:project-home :port-file :port}}`
      single match — attach silently.
    - `{:status :many :candidates [{...} {...}]}`
      caller (server.cljs) is expected to elicit a choice.
    - `{:status :error :error <reason-map>}` with `:reason` one of
      `:workspace-discovery-unsupported`, `:no-running-shadow-in-workspace`,
      or `:ambiguous-shadow` (the latter applies when the caller can't
      elicit — `discover-via-roots*` itself doesn't synthesize this branch;
      it returns `:many` and lets the caller decide).

  Pure orchestrator — no globals, no side effects beyond the injected fns."
  [list-roots-fn walk-fn read-file-fn]
  (-> (try (list-roots-fn) (catch :default e (js/Promise.reject e)))
      (.then
        (fn [resp]
          (let [roots-array (j/get resp :roots)
                root-paths  (->> (array-seq (or roots-array #js []))
                                 (keep (fn [^js r] (uri->path (j/get r :uri))))
                                 (vec))
                edn-hits    (->> root-paths
                                 (mapcat (fn [p] (array-seq (walk-fn p))))
                                 (distinct))
                edn-array   (clj->js edn-hits)
                candidates  (candidates-from-edns* read-file-fn edn-array)]
            (cond
              (zero? (count candidates))
              {:status :error :error (result-no-shadow root-paths edn-array)}

              (= 1 (count candidates))
              {:status :one :candidate (first candidates)}

              :else
              {:status :many :candidates candidates}))))
      (.catch
        (fn [_err]
          (js/Promise.resolve
            {:status :error :error (result-unsupported)})))))

(defn discover-via-roots
  "Default-arity production wrapper around [[discover-via-roots*]]. Takes
  the MCP server's `list-roots-fn` (the only piece that varies per-call
  in production) and threads in the real filesystem walkers."
  [list-roots-fn]
  (discover-via-roots* list-roots-fn walk-for-shadow-edns (.-readFileSync fs)))

;; ---------------------------------------------------------------------------
;; Helpers exposed for the caller's branching on ambiguity
;; ---------------------------------------------------------------------------

(defn ambiguous-result-payload
  "Build the `:reason :ambiguous-shadow` payload from a vector of
  candidates. Used by callers that exhausted the elicitation path
  (client doesn't support `elicitation/create`)."
  [candidates]
  (result-ambiguous candidates))
