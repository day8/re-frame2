(ns re-frame.mcp-conformance.verb-vocab-test
  "Cross-MCP verb-vocabulary conformance (rf2-vkx7m).

  Pins the cross-server **tool-name verb vocabulary** catalogued in
  `tools/mcp-conformance/NAMING.md` §The verb table. Every tool the
  pair (re-frame2-pair-mcp, story-mcp) advertises MUST start with one
  of the catalogued verb prefixes — or appear on the bare-verb /
  bare-noun exception list — so an agent that learned the verb table
  once recognises the surface across servers.

  Sibling to:

  - [`wire_vocab_test.clj`](wire_vocab_test.clj)     — wire MARKER
    vocabulary (`:rf.mcp/*` / `:rf.size/*` namespaced payload shapes).
  - [`indicator_field_test.clj`](indicator_field_test.clj) —
    envelope SLOT vocabulary for suppression counters.
  - [`slot_name_test.clj`](slot_name_test.clj)       — `tools/call`
    INPUT-arg slot vocabulary.
  - **This file**                                    — tool-NAME verb
    vocabulary.

  ## What this test guards

  Each server ships a canonical `tool-names.json` fixture used by the
  per-server unit tests (story-mcp's `tools_test.clj`,
  re-frame2-pair-mcp's stdio roundtrip). This linter reads each
  fixture and asserts every tool name starts with a catalogued verb,
  is a catalogued bare-verb, or appears on the bare-noun-exception
  allowlist (Clojure-idiomatic projections like `variant->edn`, single-
  primitive reads like `snapshot-identity`).

  A new tool with an off-convention verb (`fetch-foo`, `update-bar`,
  …) lands today without anything tripping — the convention is doc-
  only. This gate makes the convention executable.

  ## Adding a new verb

  Extending the catalogue requires a Lock entry in the relevant
  server's `spec/DESIGN-RATIONALE.md` AND a row in NAMING.md §The
  verb table. THEN extend `verb-prefixes` / `bare-verbs` /
  `bare-noun-exceptions` below. The friction is intentional — every
  added verb is a new surface an agent must learn."
  (:require [clojure.data.json :as json]
            [clojure.string    :as str]
            [clojure.test      :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; Catalogued verb prefixes — every entry mirrors a row in NAMING.md
;; §The verb table. Order is non-significant; matching is "first prefix
;; that matches", so register-/unregister- precede register precedes
;; nothing — we test by prefix-with-trailing-dash so no ordering hazard.
;; ---------------------------------------------------------------------------

(def ^:private verb-prefixes
  "Catalogued prefix verbs from NAMING.md §The verb table. Each entry
  matches as `<prefix>-<rest>` against a tool name."
  #{"get"
    "list"
    "read"
    "discover"
    "restore"
    "reset"
    "replace"     ; rf2-0acvdb (EP-0001 rf2-tfepxu) — `replace-<thing>`
                  ; whole-value state injection, bypassing the normal
                  ; cascade. `replace-app-db` (re-frame2-pair) is gated
                  ; behind `--allow-writes`; it wraps the framework's
                  ; `replace-frame-state!` Tool-Pair write primitive as an
                  ; app-only partial map (rf2-t3lftq — API-shrink #3
                  ; consolidated the former `replace-app-db!` into this —
                  ; a db-shaped key must never silently replace
                  ; runtime-db). Mirrors NAMING.md §The verb table
                  ; `replace-<thing>` row.
    "set"         ; rf2-zomfq — the SOLE catalogued `set-` carve-out:
                  ; `set-operating-frame` (re-frame2-pair) pins ONE named
                  ; session setting the Tool-Pair contract mandates under
                  ; that exact name. NAMING.md §"What's NOT a locked verb"
                  ; still rejects generic `set-<arbitrary-slot>`; this
                  ; prefix entry is admissible because the only `set-` tool
                  ; that exists is the spec-mandated operating-frame pin.
    "register"
    "unregister"
    "run"
    "describe"    ; rf2-srobm0 — `describe-<thing>` reads the COMPOSED /
                  ; derived behaviour a frame runs PLUS where each piece
                  ; resolved from. `describe-image` (re-frame2-pair) is the
                  ; EP-0023 Use-Case 7 read over a frame's running image
                  ; generation (images / kinds / capability requires /
                  ; per-kind counts / optional per-registration provenance).
                  ; Distinct from `get-` (stored body), `read-` (no-recompute
                  ; reflection of an already-run artefact) and `list-`
                  ; (enumeration): `describe-` answers "what behaviour does
                  ; THIS frame run, and which source won each resolution".
                  ; Lock #11 in pair-mcp DESIGN-RATIONALE.md; NAMING.md §The
                  ; verb table `describe-<thing>` row.
    "preview"
    "explain"     ; rf2-ba86n.17 — `explain-variant` (story-mcp; the agent
                  ; mirror of the human Explain panel over `story/explain`)
    "record-as"
    "watch"       ; rf2-zo4b9 — `watch-<until>` blocking watch family
                  ; (re-frame2-pair; block until a predicate over a signal
                  ; holds). Generalises the `watch-epochs` bare verb into a
                  ; prefix; `watch-epochs` stays on the bare-verb allowlist
                  ; below to avoid churn.
    "tail"})

(def ^:private bare-verbs
  "Catalogued bare-verb tool names from NAMING.md §The verb table
  (`dispatch`, `eval-cljs`, `subscribe` / `unsubscribe`) plus the
  mega-op bare verbs (`snapshot`, `trace-window`, `watch-epochs`,
  `record`)."
  #{"dispatch"
    "dispatch-dry-run"  ; sibling of `dispatch` (rf2-ee38b.18 dry-run gate)
    "eval-cljs"
    "subscribe"
    "unsubscribe"
    "snapshot"
    "trace-window"
    "watch-epochs"
    "orient"            ; rf2-3bu3d.8 — bare-verb mega-op app-shape summary
                        ; (re-frame2-pair; spans liveness / frames /
                        ; app-db-top-keys / registry counts+ids / machines in
                        ; one round-trip). First-contact orientation on an
                        ; unfamiliar app; composes the existing introspection
                        ; surfaces. Lock #9 in pair-mcp DESIGN-RATIONALE.md.
    "record"})          ; rf2-zo4b9 — bare-verb mega-op signal recorder
                        ; (re-frame2-pair; spans app-db / sub / DOM / focus
                        ; signals). Distinct from the `record-as-` prefix
                        ; (capture-as-artefact); `record` installs a live
                        ; change-log observer.

(def ^:private bare-noun-exceptions
  "Bare-noun reads catalogued in NAMING.md §Server alignment today as
  'Deviation — accepted exception'. Each entry needs a row in the
  alignment table explaining the exception:

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
  means adding a row here AND extending `fx/known-servers`."
  {:re-frame2-pair-mcp "tools/re-frame2-pair-mcp/test/fixtures/tool-names.json"
   :story-mcp          "tools/story-mcp/test/fixtures/tool-names.json"})

(defn- read-tool-names
  "Slurp + JSON-parse the canonical tool-names fixture for `server`.
  Returns a vector of strings."
  [server]
  (-> (fx/read-source (get tool-name-fixtures server))
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
  (testing "the verb-vocab linter knows about every server in fx/known-servers"
    (doseq [server fx/known-servers]
      (is (contains? tool-name-fixtures server)
          (str "fx/known-servers carries " server " but the verb-vocab "
               "linter has no tool-names.json fixture path for it. Add "
               "a row to tool-name-fixtures.")))))
