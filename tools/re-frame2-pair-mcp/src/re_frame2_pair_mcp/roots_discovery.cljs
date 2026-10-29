(ns re-frame2-pair-mcp.roots-discovery
  "Workspace-roots-based discovery of the consumer project's nREPL port file.

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

  The MCP protocol's `roots` capability lets a client surface the workspace
  roots the user has opened via `roots/list`. We walk each root for
  `shadow-cljs.edn`, then test the standard port-file candidates relative to
  that project root. A candidate counts only when its port accepts a
  connect: shadow-cljs deletes its port files from a JVM shutdown hook, so
  a JVM that was force-killed or crashed leaves a readable file naming a
  port nothing listens on. The walk is bounded
  shallow (typical locations: the root itself, `<root>/implementation/`, plus
  one extra level for monorepos) and skips directories that can't contain a
  project (`node_modules`, `.git`, `target`, `.shadow-cljs`).

  The workspace supplies project roots, so this path needs neither an
  operator-specific absolute path nor port-range probing.

  ## What this namespace is NOT

  This file is the pure file-system walk + roots-list orchestrator. It
  doesn't open sockets, parse argv, or connect to nREPL — those concerns
  live in `nrepl.cljs` (the transport, which owns the port probe) and
  `server.cljs` (the boot wiring, which threads that probe in). The walk and
  the candidate-branch logic are unit-testable here by injecting the
  directory reader, the port probe and the `roots/list` fn.

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
  paths relative to the project root, in precedence order (same list as
  `nrepl/port-file-candidates` — kept in sync deliberately). A project's
  candidate is the first of these whose port accepts a connect."
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

(defn project-home->candidates*
  "Given an absolute project-home directory, read the port file at each of
  the three standard relative locations. Returns a vector of
  `{:project-home <abs> :port-file <abs> :port <int>}`, one per file that
  reads, in precedence order — empty when none does. Every readable file is
  kept because a file a force-killed JVM left behind can precede the live
  one; which port is live is the orchestrator's question
  ([[discover-via-roots*]]), not this fn's.

  Injected `read-file-fn` is the fs reader (path → string, throws on missing).
  Production reaches this fn through [[candidates-from-edns*]], which
  [[discover-via-roots*]] calls with the reader threaded down from
  [[discover-via-roots]]; tests call it directly with a stub reader."
  [read-file-fn project-home]
  (into []
        (keep (fn [rel]
                (let [pf (node-path/join project-home rel)]
                  (when-let [port (read-port-from* read-file-fn pf)]
                    {:project-home project-home
                     :port-file    pf
                     :port         port}))))
        port-file-relpaths))

(defn candidates-from-edns*
  "Map a JS array of `shadow-cljs.edn` paths to each project's readable
  port files. Each edn's parent directory is the project root; we ask
  [[project-home->candidates*]] which port files read there.

  Returns a CLJS vector with one entry per project that has a readable port
  file — that project's candidates, in precedence order; a project without
  one is filtered out. A readable file is not yet a live build:
  [[discover-via-roots*]] probes them and keeps each project's first live
  one."
  [read-file-fn edn-paths]
  (->> (array-seq edn-paths)
       (map (fn [edn] (node-path/dirname edn)))
       (distinct)
       (map #(project-home->candidates* read-file-fn %))
       (filterv seq)))

(defn- first-live-of
  "One project's pick from its `candidates` (in precedence order) and the
  probe `verdicts` for them, in the same order: the first whose port accepts
  as `:candidate` (nil when none does), and the `:port-file` of each one
  refused ahead of it as `:stale`."
  [candidates verdicts]
  (let [[refused [[hit] & _]] (split-with (comp not second) (map vector candidates verdicts))]
    {:candidate hit
     :stale     (mapv (comp :port-file first) refused)}))

;; ---------------------------------------------------------------------------
;; Top-level discovery — `roots/list` → walk → candidates
;; ---------------------------------------------------------------------------

(defn- result-no-shadow [root-paths edn-paths]
  {:reason       :no-running-shadow-in-workspace
   :roots        (vec root-paths)
   :checked-edns (vec (array-seq edn-paths))
   :hint         (str "No `shadow-cljs.edn` in the open workspace roots has "
                      "a port file whose port accepts a connect. "
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
  - `probe-port-fn`   `(port) → Promise<boolean>` — does the port accept a
                      connect (production: `nrepl/port-accepts?`).

  Every readable port file is probed. A project's candidate is its first
  file in precedence order whose port accepts, so a stale file left ahead of
  the live one does not hide it; a project with no such file contributes
  none. Each file refused ahead of a project's pick (all of them, when it
  has none) is listed under `:stale-port-files` (only when there is one)
  beside any status, so the caller can name it.

  Returns a Promise resolving to one of:

    - `{:status :one  :candidate {:project-home :port-file :port}}`
      single live match — attach silently.
    - `{:status :many :candidates [{...} {...}]}`
      caller (server.cljs) is expected to elicit a choice.
    - `{:status :error :error <reason-map>}` with `:reason` one of
      `:workspace-discovery-unsupported`, `:no-running-shadow-in-workspace`
      (no candidate, or every candidate refused), or `:ambiguous-shadow`
      (the latter applies when the caller can't elicit —
      `discover-via-roots*` itself doesn't synthesize this branch; it
      returns `:many` and lets the caller decide).

  Pure orchestrator — no globals, no side effects beyond the injected fns."
  [list-roots-fn walk-fn read-file-fn probe-port-fn]
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
                projects    (candidates-from-edns* read-file-fn edn-array)
                pick        (fn [cs]
                              (-> (js/Promise.all (into-array (map #(probe-port-fn (:port %)) cs)))
                                  (.then #(first-live-of cs (array-seq %)))))]
            (-> (js/Promise.all (into-array (map pick projects)))
                (.then
                  (fn [picks]
                    (let [picks (array-seq picks)
                          live  (vec (keep :candidate picks))
                          stale (vec (mapcat :stale picks))]
                      (cond-> (case (count live)
                                0 {:status :error :error (result-no-shadow root-paths edn-array)}
                                1 {:status :one :candidate (first live)}
                                {:status :many :candidates live})
                        (seq stale) (assoc :stale-port-files stale)))))))))
      (.catch
        (fn [_err]
          (js/Promise.resolve
            {:status :error :error (result-unsupported)})))))

(defn discover-via-roots
  "Production wrapper around [[discover-via-roots*]]. Takes the MCP
  server's `list-roots-fn` (the only piece that varies per-call in
  production) and the port probe the transport owns, and threads in the
  real filesystem walkers."
  [list-roots-fn probe-port-fn]
  (discover-via-roots* list-roots-fn walk-for-shadow-edns (.-readFileSync fs) probe-port-fn))

;; ---------------------------------------------------------------------------
;; Helpers exposed for the caller's branching on ambiguity
;; ---------------------------------------------------------------------------

(defn ambiguous-result-payload
  "Build the `:reason :ambiguous-shadow` payload from a vector of
  candidates. Used by callers that exhausted the elicitation path
  (client doesn't support `elicitation/create`)."
  [candidates]
  (result-ambiguous candidates))
