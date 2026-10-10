(ns re-frame.mcp-conformance.indicator-field-test
  "Cross-MCP indicator-field ROUTING conformance.

  Pins the MUST-level contract from
  [Spec 009 §Indicator field on tool responses][1] and
  [Conventions §Cross-MCP indicator-field vocabulary][2]:

    Tools that return structured response maps and walk a tree-typed
    payload MUST carry an `:elided-large` count alongside the
    `:dropped-sensitive` count. Both slots are unqualified keys. Omit
    when zero.

  ## Scope — routing ONLY

  This file pins that every tree-walking tool ROUTES its envelope through
  the ONE centralised emit-path. It deliberately does NOT re-test the
  helper's behaviour or re-declare the slot schemas — those have single
  owners:

  - Helper SEMANTICS (the omit-when-zero `cond->`, value pass-through,
    canonical vocab-key usage) — owned by
    `re-frame.mcp-base.envelope/with-indicators` and its direct unit
    tests in `tools/mcp-base/test/re_frame/mcp_base/envelope_test.clj`.
  - Slot SCHEMAS (`DroppedSensitive` / `ElidedLarge`, `pos-int?`) plus
    their positive-fixture conformance AND the present-zero rejection —
    owned by `wire_vocab/schemas.clj` + `wire_vocab_test.clj`.

  What this file OWNS — the centralised single-emit-path guarantee:

    Every re-frame2-pair-mcp tool that walks a tree-typed payload routes
    its envelope through the centralised `wire/with-indicators` helper,
    and pair-mcp's `wire.cljs` delegates to the mcp-base canonical
    helper.

  Sibling to [`wire_vocab_test.clj`](wire_vocab_test.clj) — that file
  pins the **wire MARKER** vocabulary (`:rf.mcp/*` / `:rf.size/*`
  namespaced shapes) AND owns the **envelope SLOT** schemas
  (`:dropped-sensitive` / `:elided-large` unqualified scalar counters),
  and pins story-mcp's routing through the same mcp-base helper
  (`story-mcp-routes-envelope-through-the-centralised-helper`). The two
  vocabularies compose on every tool response: the markers populate
  values inside the payload, the slots summarise suppression totals on
  the envelope.

  ## Why source-text pins (not live-server)

  The alternative — exercising the contract through a live
  re-frame2-pair-mcp/story-mcp server — is the job of `test/end-to-end-*.js`
  (protocol conformance) and the live-re-frame2-pair-overflow path (runtime
  cap-trigger conformance). This file's gate is at the wire-routing
  layer, same posture as `wire_vocab_test.clj`.

  [1]: ../../../spec/009-Instrumentation.md#size-elision-in-traces
  [2]: ../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters"
  (:require [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

;; ---------------------------------------------------------------------------
;; Tree-walking-tool routing pin.
;;
;; The catalogue below lists every re-frame2-pair-mcp tool that walks a
;; tree-typed payload (per Spec 009 §Size elision in traces — "one MUST-level row per
;; consumer-facing tool that walks a tree-typed payload"). Each MUST
;; route its envelope through the centralised `wire/with-indicators`
;; helper — that single emit-path is the contract's structural
;; guarantee.
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
      ;; the routing pin — only a real CODE reference counts. Any tool
      ;; source can name the helper in a docstring.
      (let [src      (rf.mcp-conformance.fixtures/read-source rel)
            stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings src)]
        (is (str/includes? stripped "wire/with-indicators")
            (str "Tool " tool " at " rel
                 " does not route its envelope through `wire/with-indicators`. "
                 "The centralised emit-path is the structural contract — a "
                 "tool that inlines `(assoc :dropped-sensitive ...)` or "
                 "`(assoc :elided-large ...)` directly violates the MUST-"
                 "level parity rule per Conventions §Cross-MCP indicator-field vocabulary / Spec 009 §Size elision in traces."))))))

;; ---------------------------------------------------------------------------
;; Pair-mcp delegation pin — the per-tool call-sites read
;; `wire/with-indicators` (the pair-local namespace the tools require), but
;; the RULE BODY lives ONCE in the shared `mcp-base.envelope` namespace,
;; unit-tested there (`envelope_test.clj`). A regression that inlined the
;; rule in `wire.cljs` (forking the emit-path across servers) trips here.
;; ---------------------------------------------------------------------------

(def ^:private pair-mcp-reexport-rel
  "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/wire.cljs")

(deftest pair-mcp-wire-re-exports-the-canonical-helper
  (is (str/includes? (rf.mcp-conformance.fixtures/read-source pair-mcp-reexport-rel)
                     "rf.mcp-base.envelope/with-indicators")
      (str "pair-mcp `with-indicators` does not delegate to "
           "`re-frame.mcp-base.envelope/with-indicators`. The emit-path "
           "MUST stay centralised in mcp-base — an "
           "inlined copy forks the omit-when-zero MUST across "
           "servers.")))
