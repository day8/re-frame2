(ns re-frame.mcp-conformance.indicator-field-test
  "Cross-MCP indicator-field ROUTING conformance (rf2-6m8tq, rf2-tdn6oi).

  Pins the MUST-level contract from
  [Spec 009 §Indicator field on tool responses][1] and
  [Conventions §Cross-MCP indicator-field vocabulary][2]:

    Tools that return structured response maps and walk a tree-typed
    payload MUST carry an `:elided-large` count alongside the existing
    `:dropped-sensitive` count. Both slots are unqualified keys. Omit
    when zero.

  ## Scope — routing + no-inline-emission ONLY

  This file pins that every tree-walking tool ROUTES its envelope through
  the ONE centralised emit-path, and that no tool inlines the slot
  literals to bypass it. It deliberately does NOT re-test the helper's
  behaviour or re-declare the slot schemas — those have single owners
  (rf2-tdn6oi deduplication):

  - Helper SEMANTICS (the omit-when-zero `cond->`, value pass-through,
    canonical vocab-key usage) — owned by
    `re-frame.mcp-base.envelope/with-indicators` and its direct unit
    tests in `tools/mcp-base/test/re_frame/mcp_base/envelope_test.clj`.
  - Slot SCHEMAS (`DroppedSensitive` / `ElidedLarge`, `pos-int?`) plus
    their positive-fixture conformance AND the present-zero rejection —
    owned by `wire_vocab/schemas.clj` + `wire_vocab_test.clj`.

  What this file OWNS — the centralised single-emit-path guarantee (per
  audit `ai/findings/refactor-audit-tools-re-frame2-pair-mcp-2026-05-14.md`
  §TE8):

    Every re-frame2-pair-mcp tool that walks a tree-typed payload routes
    its envelope through the centralised `wire/with-indicators` helper;
    pair-mcp's `wire.cljs` re-exports and delegates to the mcp-base
    canonical helper; and no re-frame2-pair-mcp source inlines the slot
    literals outside the whitelisted helper/descriptor files.

  Sibling to [`wire_vocab_test.clj`](wire_vocab_test.clj) — that file
  pins the **wire MARKER** vocabulary (`:rf.mcp/*` / `:rf.size/*`
  namespaced shapes) AND owns the **envelope SLOT** schemas
  (`:dropped-sensitive` / `:elided-large` unqualified scalar counters).
  The two vocabularies compose on every tool response: the markers
  populate values inside the payload, the slots summarise suppression
  totals on the envelope.

  ## Why source-text pins (not live-server)

  The alternative — exercising the contract through a live
  re-frame2-pair-mcp/story-mcp server — is the job of `test/end-to-end-*.js`
  (protocol conformance) and the live-re-frame2-pair-overflow path (runtime
  cap-trigger conformance). This file's gate is at the wire-routing
  layer, same posture as `wire_vocab_test.clj`.

  [1]: ../../../spec/009-Instrumentation.md#size-elision-in-traces
  [2]: ../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters"
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

;; ---------------------------------------------------------------------------
;; Repo-root + slurp helpers live in `re-frame.mcp-conformance.fixtures`
;; (rf2-113ti). `io` is still required below for the `re-frame2-pair-mcp-source-files`
;; walker.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Tree-walking-tool routing pin.
;;
;; The catalogue below lists every re-frame2-pair-mcp tool that walks a
;; tree-typed payload (per Spec 009:1411 — "one MUST-level row per
;; consumer-facing tool that walks a tree-typed payload"). Each MUST
;; route its envelope through the centralised `wire/with-indicators`
;; helper — that single emit-path is the contract's structural
;; guarantee. Drift (a tool emitting `:dropped-sensitive` / `:elided-
;; large` directly without going through the helper) trips this gate.
;; ---------------------------------------------------------------------------

(def ^:private tree-walking-tool-sources
  "Per-tool source files for re-frame2-pair-mcp's tree-walking tools. Each
  source MUST contain at least one `wire/with-indicators` call —
  that's the centralised emit-path the contract pins. Adding a new
  tree-walking tool means extending this list AND wiring the helper
  call; the new entry without the wiring fails this gate."
  {:snapshot     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/snapshot.cljs"
   :get-path     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/get_path.cljs"
   :trace-window "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/trace_window.cljs"
   :watch-epochs "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/watch_epochs.cljs"})

(deftest every-tree-walking-tool-routes-through-the-helper
  (doseq [[tool rel] tree-walking-tool-sources]
    (testing (str "tool " tool " — wire/with-indicators call-site in " rel)
      ;; Match against `rf.mcp-conformance.fixtures/strip-comments-and-strings`-neutered source so a
      ;; docstring / comment MENTION of `wire/with-indicators` can't satisfy
      ;; the routing pin — only a real CODE reference counts (rf2-qyfy1m).
      ;; (The motivating case was the since-retired subscribe.cljs, which
      ;; named the helper in two docstrings — rf2-ahjbc; the hazard is
      ;; generic to any tool source and the strip stays.)
      (let [src      (rf.mcp-conformance.fixtures/read-source rel)
            stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings src)]
        (is (str/includes? stripped "wire/with-indicators")
            (str "Tool " tool " at " rel
                 " does not route its envelope through `wire/with-indicators`. "
                 "The centralised emit-path is the structural contract — a "
                 "tool that inlines `(assoc :dropped-sensitive ...)` or "
                 "`(assoc :elided-large ...)` directly violates the MUST-"
                 "level parity rule per Conventions:154 / Spec 009:1411."))))))

;; ---------------------------------------------------------------------------
;; Pair-mcp delegation pin — the per-tool call-sites read
;; `wire/with-indicators` (the pair-local namespace the tools already
;; require), but the RULE BODY lives ONCE in mcp-base. The canonical helper
;; was HOISTED out of pair-mcp into the shared `mcp-base.envelope`
;; namespace (rf2-ee38b.19) so the single emit-path the spec mandates lives
;; in one CLJC place — and its semantics are unit-tested directly there
;; (`envelope_test.clj`), not re-simulated here. pair-mcp's `wire.cljs`
;; keeps a thin re-export `with-indicators` that delegates to the base, so
;; the per-tool call-sites still read `wire/with-indicators` (the routing
;; pin above) while the omit-when-zero MUST stays centralised. This pin
;; asserts that re-export still exists and still delegates — a regression
;; that re-inlined the rule (forking the emit-path across servers) trips
;; here. The story-mcp delegation pin is the sibling
;; `story-mcp-routes-envelope-through-the-centralised-helper` in
;; `wire_vocab_test.clj`.
;; ---------------------------------------------------------------------------

(def ^:private pair-mcp-reexport-rel
  "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/wire.cljs")

(deftest pair-mcp-wire-re-exports-the-canonical-helper
  (let [src (rf.mcp-conformance.fixtures/read-source pair-mcp-reexport-rel)]
    (testing "pair-mcp wire.cljs re-exports `with-indicators`"
      (is (str/includes? src "(defn with-indicators")
          (str "`with-indicators` re-export missing from " pair-mcp-reexport-rel)))
    (testing "pair-mcp wire.cljs delegates to the mcp-base canonical helper"
      (is (str/includes? src "base-envelope/with-indicators")
          (str "pair-mcp `with-indicators` no longer delegates to "
               "`re-frame.mcp-base.envelope/with-indicators`. The emit-path "
               "MUST stay centralised in mcp-base (rf2-ee38b.19) — a "
               "re-inlined copy forks the omit-when-zero MUST across "
               "servers.")))))

;; ---------------------------------------------------------------------------
;; Inline-emit anti-pin — neither slot literal may appear inline in any
;; re-frame2-pair-mcp source file OTHER than the helper itself. A tool
;; that bypasses the helper to `(assoc envelope
;; :dropped-sensitive N)` directly violates the MUST-level parity rule
;; — the helper exists precisely to centralise the omit-when-zero rule
;; and the parity invariant.
;; ---------------------------------------------------------------------------

(def ^:private inline-emit-whitelist
  "Files allowed to contain the literal `:dropped-sensitive` or
  `:elided-large` keywords. Anything else MUST go through the
  `with-indicators` helper.

  - `wire.cljs` — the helper itself (the canonical emit-path).

  This set holds ONE entry by design. Documentation that names the slots
  — descriptor prose shipped in the `tools/list` response, docstrings
  cross-linking the helper — needs no entry: the gate strips comments,
  docstrings and strings BEFORE it greps (efd0c8dbf32), so prose is
  already invisible to it. A whitelist row is strictly stronger than that:
  it skips the file WHOLE, so a later real emission there would evade the
  gate. Add a row only for a file that must carry the literal in CODE."
  #{"tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/wire.cljs"})

(defn- mcp-source-files
  "Walk `src-rel` (a repo-relative subdir) and return every `.cljs`
  file as a repo-relative path string. Used by the re-frame2-pair-mcp
  `tools/<server>/src/` walker — the inline-emit gate applies the
  same shape to any tree-walking MCP surface."
  [src-rel]
  (let [src-root (io/file rf.mcp-conformance.fixtures/repo-root src-rel)]
    (when (.isDirectory src-root)
      (->> (file-seq src-root)
           (filter #(and (.isFile ^java.io.File %)
                         (str/ends-with? (.getName ^java.io.File %) ".cljs")))
           (map (fn [^java.io.File f]
                  (-> (.getAbsolutePath f)
                      (str/replace "\\" "/")
                      (str/replace (str/replace rf.mcp-conformance.fixtures/repo-root "\\" "/") "")
                      (subs 1))))                                ;; strip leading "/"
           sort))))

(defn- re-frame2-pair-mcp-source-files
  "Walk `tools/re-frame2-pair-mcp/src/` and return every `.cljs` file as a
  repo-relative path string."
  []
  (mcp-source-files "tools/re-frame2-pair-mcp/src"))

(deftest no-inline-indicator-slot-emit-outside-the-helper
  ;; The grep is applied AFTER `rf.mcp-conformance.fixtures/strip-comments-and-strings` neuters
  ;; docstring / comment / descriptor-string mentions — descriptor
  ;; descriptions ship the slot names as user-visible documentation
  ;; (e.g. `"Dropped count surfaces as `:dropped-sensitive` ..."` in
  ;; `descriptors_data.cljs`, which is NOT whitelisted and is scanned like
  ;; any other file), and tool-source docstrings cross-link the
  ;; helper they delegate to. Those are documentation, not
  ;; emissions; the strip-then-grep posture catches real inline emits
  ;; (`(assoc envelope :dropped-sensitive N)`) while letting prose
  ;; through. Same posture as the wire-vocab gate's source-text pin
  ;; (rf2-vj8y3).
  ;;
  ;; The substring grep uses `rf.mcp-conformance.fixtures/variant-regex` (rf2-qnmne) rather than
  ;; raw `str/includes?` so a future legitimate extension like
  ;; `:dropped-sensitive-warning` or `:elided-large-summary` wouldn't
  ;; false-positive-trip the gate on the prefix match. Same pattern
  ;; as `slot_name_test.clj`'s near-miss-variant grep.
  (let [slot-literals [":dropped-sensitive" ":elided-large"]
        srcs          (re-frame2-pair-mcp-source-files)]
    (is (seq srcs)
        "Expected to find re-frame2-pair-mcp source files; classpath walk returned empty.")
    (doseq [rel srcs
            slot slot-literals
            :when (not (contains? inline-emit-whitelist rel))]
      (testing (str rel " — must not inline " slot)
        (let [src      (rf.mcp-conformance.fixtures/read-source rel)
              stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings src)
              pat      (rf.mcp-conformance.fixtures/variant-regex slot)]
          (is (not (re-find pat stripped))
              (str "Inline `" slot "` literal found in " rel
                   " (in code, AFTER stripping comments/docstrings/strings).\n"
                   "Every emit MUST go through `wire/with-indicators` "
                   "(per Conventions:154 / Spec 009:1411). If this file "
                   "is a legitimate exception (a destructuring binding "
                   "reading internal state, an internal state-atom name), "
                   "add it to `inline-emit-whitelist` with a justification.")))))))

;; ---------------------------------------------------------------------------
;; Cross-server posture pin — story-mcp.
;;
;; The sibling `wire_vocab_test.clj` pins:
;; - story-mcp emits no uncontracted cross-MCP markers;
;; - story-mcp emits both envelope indicators: its
;;   `run-variant` / `preview-variant` / `read-failures` payload
;;   builders drop `:sensitive? true` assertion records and elide
;;   over-threshold `:app-db` leaves, surfacing the counts via the
;;   centralised `egress/with-indicators` helper (delegating to
;;   `re-frame.mcp-base.envelope/with-indicators`) +
;;   `egress/count-elided` (delegating to
;;   `re-frame.mcp-base.elision/count-elided-markers`). The
;;   `envelope-slot-parity-across-emitting-servers` +
;;   `story-mcp-routes-envelope-through-the-centralised-helper` gates in
;;   `wire_vocab_test.clj` pin that routing.
;;
;; The story-mcp tool sources are CLJC (not CLJS), so the
;; `re-frame2-pair-mcp-source-files` walker above (CLJS-only) does not
;; cover them; the story-mcp routing pin lives in `wire_vocab_test.clj`
;; against the `.cljc` helper + tool sources.
;; ---------------------------------------------------------------------------
