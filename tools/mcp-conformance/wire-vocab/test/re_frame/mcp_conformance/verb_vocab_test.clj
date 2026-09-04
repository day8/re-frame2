(ns re-frame.mcp-conformance.verb-vocab-test
  "Executable tool-name vocabulary for both MCP servers.

  Reads each server's canonical `tool-names.json` fixture and requires
  every advertised name to use a prefix, bare verb, or explicit exception
  documented in `tools/mcp-conformance/NAMING.md`. A new verb requires the
  owning server rationale, a NAMING.md row, and an update here."
  (:require [clojure.data.json :as json]
            [clojure.string    :as str]
            [clojure.test      :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

(def ^:private verb-prefixes
  "Catalogued prefix verbs from NAMING.md §The verb table. Each entry
  matches as `<prefix>-<rest>` against a tool name."
  #{"get"
    "list"
    "read"
    "discover"
    "restore"
    ;; rf2-ov144 — re-drive a recorded thing through the live system
    ;; (`replay-epoch`); the recorded run is the input, never a payload.
    "replay"
    "reset"
    "replace"
    ;; Reserved for the one operating-frame session pin.
    "set"
    "register"
    "unregister"
    "run"
    "describe"
    "preview"
    "explain"
    "record-as"
    "watch"
    "tail"})

(def ^:private bare-verbs
  "Catalogued bare-verb tool names from NAMING.md §The verb table
  (`dispatch`, `eval-cljs`) plus the
  mega-op bare verbs (`snapshot`, `trace-window`, `watch-epochs`,
  `record`)."
  #{"dispatch"
    "dispatch-dry-run"
    "eval-cljs"
    "snapshot"
    "trace-window"
    "watch-epochs"
    "orient"
    "record"})

(def ^:private bare-noun-exceptions
  "Bare-noun reads catalogued as explicit exceptions in NAMING.md:

  - `handler-meta` — bare-noun read of a single-record metadata map
    (re-frame2-pair-mcp; accepted under the bare-noun-exception clause
    for structured metadata blobs the agent reads as one record).
  - `variant->edn` — Clojure-idiomatic projection name (story-mcp;
    `<thing>->edn` is preferable to `get-<thing>-edn` when the
    operation is a canonical-form serialiser).
  - `snapshot-identity` — bare-noun read of a content-hash (story-mcp;
    accepted under the bare-noun-exception clause when the return
    value is a single primitive)."
  #{"handler-meta"
    "variant->edn"
    "snapshot-identity"})

;; ---------------------------------------------------------------------------
;; Tool-name fixtures. The canonical per-server `tool-names.json`
;; (already used by per-server tests for `tools/list` agreement) — we
;; read the same fixture so this linter and the per-server tests can't
;; drift apart.
;; ---------------------------------------------------------------------------

(def ^:private tool-name-fixtures
  "Per-server `tool-names.json` fixture paths. Adding a new server
  means adding a row here AND extending `rf.mcp-conformance.fixtures/known-servers`."
  {:re-frame2-pair-mcp "tools/re-frame2-pair-mcp/test/fixtures/tool-names.json"
   :story-mcp          "tools/story-mcp/test/fixtures/tool-names.json"})

(defn- read-tool-names
  "Slurp + JSON-parse the canonical tool-names fixture for `server`.
  Returns a vector of strings."
  [server]
  (-> (rf.mcp-conformance.fixtures/read-source (get tool-name-fixtures server))
      (json/read-str)
      (get "names")
      vec))

;; ---------------------------------------------------------------------------
;; Verb classification.
;; ---------------------------------------------------------------------------

(defn- starts-with-catalogued-prefix?
  "True when `tool-name` begins with one of the catalogued verb
  prefixes followed by a `-`. The trailing-dash check avoids matching
  e.g. `runtime` against the `run` prefix — only `run-<rest>` is the
  catalogued shape."
  [tool-name]
  (some (fn [prefix]
          (str/starts-with? tool-name (str prefix "-")))
        verb-prefixes))

(defn- classify-tool
  "Classify `tool-name` against the catalogued vocabulary. Returns one
  of `:prefix` / `:bare-verb` / `:exception` / `:unknown`. `:unknown`
  is the only failing classification."
  [tool-name]
  (cond
    (starts-with-catalogued-prefix? tool-name) :prefix
    (contains? bare-verbs tool-name)           :bare-verb
    (contains? bare-noun-exceptions tool-name) :exception
    :else                                      :unknown))

;; ---------------------------------------------------------------------------
;; Tests.
;; ---------------------------------------------------------------------------

(deftest every-tool-name-uses-a-catalogued-verb
  (testing "every tool in every server's tool-names.json starts with a
            catalogued NAMING.md verb prefix, is a catalogued bare verb,
            or appears on the bare-noun-exception allowlist"
    (doseq [server (sort (keys tool-name-fixtures))
            tool   (read-tool-names server)]
      (let [verdict (classify-tool tool)]
        (is (not= :unknown verdict)
            (str server " ships tool '" tool "' which does not match a "
                 "catalogued verb shape. See NAMING.md §The verb table. "
                 "If the tool is genuinely a new verb shape, follow "
                 "NAMING.md §How to extend this table (Lock entry + row "
                 "+ catalogue update); if it's a near-synonym for a "
                 "catalogued verb, rename to the catalogued form."))))))

(deftest catalogued-fixture-paths-resolve
  (testing "every entry in tool-name-fixtures resolves to a file that
            yields at least one tool name (catches a moved/removed
            fixture)"
    (doseq [server (sort (keys tool-name-fixtures))]
      (let [names (read-tool-names server)]
        (is (pos? (count names))
            (str server "'s tool-names.json fixture at "
                 (get tool-name-fixtures server)
                 " yielded zero tool names — fixture moved or empty?"))))))

(deftest catalogue-covers-every-known-server
  (testing "the verb-vocab linter knows about every server in rf.mcp-conformance.fixtures/known-servers"
    (doseq [server rf.mcp-conformance.fixtures/known-servers]
      (is (contains? tool-name-fixtures server)
          (str "rf.mcp-conformance.fixtures/known-servers carries " server " but the verb-vocab "
               "linter has no tool-names.json fixture path for it. Add "
               "a row to tool-name-fixtures.")))))
