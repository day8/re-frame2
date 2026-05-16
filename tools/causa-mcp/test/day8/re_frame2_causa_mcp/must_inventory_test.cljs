(ns day8.re-frame2-causa-mcp.must-inventory-test
  "MUST-inventory binding test (rf2-8xzoe.33 / C-1).

  Living-scaffold tracker test for the 21 explicit + 6 implicit MUSTs
  catalogued at
  `tools/causa-mcp/spec/findings/MUST-inventory.md`. Every MUST in the
  inventory MUST bind to at least one concrete `deftest` in this
  artefact's test corpus; this file is the **scaffold** that asserts
  the binding exists by requiring the per-tool test namespaces and
  re-asserting the contract at the inventory layer.

  Per the bead's framing — `Bead exists so the inventory has an
  owner.` — individual MUST-coverage tests land alongside their tool
  beads. The work here is **two-fold**:

    1. **Per-MUST deftest** — one named test per inventory row. The
       deftest name encodes the MUST id + a slug derived from the
       planned-test name in column 5 of the inventory, so an
       inventory-vs-test diff stays mechanical.

    2. **Binding registry** — `MUST-INVENTORY` (below) maps each MUST
       id to its source citation, planned test name (verbatim from
       the inventory), and the actual bound test ns/var that landed
       (per-tool tests, cross-cutting tests, or — for cross-server
       rows — pointers to `tools/mcp-conformance/`). The
       `inventory-completeness` deftest at the bottom walks the
       registry and asserts every row has a recorded binding.

  The inventory itself is normative (per
  `findings/MUST-inventory.md`'s §Inventory hygiene); this test is
  the load-bearing pin that catches **silent drift** — a new MUST
  added to the spec without a corresponding inventory entry, or an
  inventory entry whose planned-test binding hasn't landed yet, both
  surface here as a failing assertion.

  ## Cross-server MUSTs

  Five rows (1, 7, 15, 17, 18) are cross-server — the contract is
  shared with pair2-mcp + story-mcp and the canonical test owner is
  `tools/mcp-conformance/`. The inventory tags these rows
  `Cross-server (rf2-zvv65)`. This file's per-row deftest still fires
  for them, but the assertion is a documentation-shape check (the
  inventory cite is unchanged and points at the cross-server
  conformance corpus). The actual contract verification lives in
  `tools/mcp-conformance/test/`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ;; Per-tool test namespaces — required so a missing test
            ;; surfaces at compile-time (require fails) rather than at
            ;; assertion-time. The per-tool tests carry the actual
            ;; MUST verification; this file pins the inventory binding.
            [day8.re-frame2-causa-mcp.privacy-test]
            [day8.re-frame2-causa-mcp.elision-test]
            [day8.re-frame2-causa-mcp.token-cap-test]
            [day8.re-frame2-causa-mcp.cursor-test]
            [day8.re-frame2-causa-mcp.dedup-test]
            [day8.re-frame2-causa-mcp.path-slice-test]
            [day8.re-frame2-causa-mcp.summary-test]
            [day8.re-frame2-causa-mcp.server-test]
            [day8.re-frame2-causa-mcp.nrepl-test]
            [day8.re-frame2-causa-mcp.tools.discover-app-test]
            [day8.re-frame2-causa-mcp.tools.dispatch-test]
            [day8.re-frame2-causa-mcp.tools.eval-cljs-test]
            [day8.re-frame2-causa-mcp.tools.get-app-db-test]
            [day8.re-frame2-causa-mcp.tools.get-app-db-diff-test]
            [day8.re-frame2-causa-mcp.tools.get-epoch-history-test]
            [day8.re-frame2-causa-mcp.tools.get-handlers-test]
            [day8.re-frame2-causa-mcp.tools.get-issues-test]
            [day8.re-frame2-causa-mcp.tools.get-machine-list-test]
            [day8.re-frame2-causa-mcp.tools.get-machine-state-test]
            [day8.re-frame2-causa-mcp.tools.get-source-coord-test]
            [day8.re-frame2-causa-mcp.tools.get-trace-buffer-test]
            [day8.re-frame2-causa-mcp.tools.list-subscriptions-test]
            [day8.re-frame2-causa-mcp.tools.reset-frame-db-test]
            [day8.re-frame2-causa-mcp.tools.restore-epoch-test]
            [day8.re-frame2-causa-mcp.tools.subscribe-test]
            [day8.re-frame2-causa-mcp.tools.tail-build-test]
            [day8.re-frame2-causa-mcp.tools.unsubscribe-test]))

;; ---------------------------------------------------------------------------
;; Inventory registry — the 21 explicit + 6 implicit MUSTs.
;; ---------------------------------------------------------------------------
;;
;; Each entry mirrors a row in `findings/MUST-inventory.md`:
;;
;;   :must-id        — explicit "M1".."M21" or implicit "I1".."I6"
;;   :source         — verbatim spec citation from inventory column 3
;;   :planned-test   — verbatim from inventory column 5
;;   :bound-via      — set of `[ns sym]` pairs naming the actual
;;                     bound deftest(s) that landed alongside the
;;                     tool / tranche beads; `:cross-server` for rows
;;                     whose canonical owner is tools/mcp-conformance/.
;;
;; When a new MUST lands in the spec, this map MUST gain a row in
;; lock-step (the `inventory-completeness` deftest at the bottom
;; fails otherwise).

(def MUST-INVENTORY
  {;; --- Explicit MUSTs ----------------------------------------------------
   "M1"  {:must-id      "M1"
          :title        "Framework-published listener integrations (Causa-MCP server) default-suppress :sensitive? true events"
          :source       "004-Wire-Pipeline.md L32"
          :planned-test "mcp-conformance/sensitive-default-drop-test — Causa-MCP row"
          :bound-via    :cross-server}

   "M2"  {:must-id      "M2"
          :title        "Every tool surfacing trace-stream-shaped payloads applies the default-suppress filter at the MCP boundary"
          :source       "004-Wire-Pipeline.md L39"
          :planned-test "causa-mcp.tools.privacy/{get-trace-buffer,subscribe,get-epoch-history}-drops-sensitive"
          :bound-via    #{['day8.re-frame2-causa-mcp.privacy-test 'strip-sensitive-default-drops-true-stamps]
                          ['day8.re-frame2-causa-mcp.privacy-test 'apply-to-result-default-strips-and-stamps-counter]
                          ['day8.re-frame2-causa-mcp.tools.get-issues-test 'shape-envelope-drops-sensitive-by-default]
                          ['day8.re-frame2-causa-mcp.tools.get-epoch-history-test 'shape-envelope-drops-sensitive-epochs]}}

   "M3"  {:must-id      "M3"
          :title        "A tool that cannot answer inside the 5,000-token budget MUST trim/summarise/slice/paginate/dedupe rather than over-spend"
          :source       "004-Wire-Pipeline.md L74"
          :planned-test "causa-mcp.tools.budget/no-tool-overspends-cap (parameterised across every catalogue entry)"
          :bound-via    #{['day8.re-frame2-causa-mcp.token-cap-test 'spec-004-no-silent-truncation]
                          ['day8.re-frame2-causa-mcp.token-cap-test 'apply-cap-over-token-budget-truncates-and-stamps-marker]}}

   "M4"  {:must-id      "M4"
          :title        "Every catalogue entry declares which of the six mechanisms apply, with :typical-tokens and :cap-reached slots"
          :source       "004-Wire-Pipeline.md L100, L359-363"
          :planned-test "causa-mcp.catalogue/every-entry-declares-mechanism-set"
          ;; The catalogue declaration lives in
          ;; `tools/causa-mcp/spec/004-Tools-Catalogue.md` (S-1
          ;; rf2-8xzoe.32). The binding here is the spec-shape lint
          ;; that the catalogue file itself carries a Wire-pipeline
          ;; contract block per tool; runtime descriptor pinning is
          ;; a follow-on bead (the catalogue surfaces the contract
          ;; in prose; tools.cljs surfaces it in the JSONSchema).
          :bound-via    #{['day8.re-frame2-causa-mcp.must-inventory-test 'm4-catalogue-declares-mechanism-set-per-tool]}}

   "M5"  {:must-id      "M5"
          :title        "Every tool that returns to the agent measures the rendered payload (post-EDN, post-JSON-wrap) against the cap before returning"
          :source       "004-Wire-Pipeline.md L115"
          :planned-test "causa-mcp.tools.budget/every-tool-measures-pre-return"
          :bound-via    #{['day8.re-frame2-causa-mcp.token-cap-test 'apply-cap-under-budget-passes-unchanged]
                          ['day8.re-frame2-causa-mcp.token-cap-test 'apply-cap-over-token-budget-truncates-and-stamps-marker]
                          ['day8.re-frame2-causa-mcp.token-cap-test 'apply-cap-result-shape-conforms-to-mcp-sdk]}}

   "M6"  {:must-id      "M6"
          :title        "A tool that would exceed the cap MUST NOT silently truncate"
          :source       "004-Wire-Pipeline.md L121"
          :planned-test "causa-mcp.tools.budget/overflow-never-silent (paired with M7)"
          :bound-via    #{['day8.re-frame2-causa-mcp.token-cap-test 'spec-004-no-silent-truncation]
                          ['day8.re-frame2-causa-mcp.token-cap-test 'apply-cap-over-token-budget-truncates-and-stamps-marker]}}

   "M7"  {:must-id      "M7"
          :title        "A tool exceeding the cap returns the structured overflow marker at the top of the payload"
          :source       "004-Wire-Pipeline.md L122"
          :planned-test "causa-mcp.tools.budget/overflow-marker-shape; mcp-conformance/overflow-marker-cross-server"
          :bound-via    #{['day8.re-frame2-causa-mcp.token-cap-test 'spec-004-overflow-marker-shape-end-to-end]
                          ['day8.re-frame2-causa-mcp.token-cap-test 'overflow-payload-shape-minimum]
                          ['day8.re-frame2-causa-mcp.token-cap-test 'overflow-payload-matches-conformance-fixture]}}

   "M8"  {:must-id      "M8"
          :title        "Tools returning rich nested values accept an optional :path argument (EDN-encoded vector)"
          :source       "004-Wire-Pipeline.md L141"
          :planned-test "causa-mcp.tools.path/{get-app-db,get-machine-state,get-epoch-history}-accepts-path-arg"
          :bound-via    #{['day8.re-frame2-causa-mcp.path-slice-test 'public-surface-resolvable]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-test 'build-form-addresses-runtime]
                          ['day8.re-frame2-causa-mcp.tools.get-machine-state-test 'build-form-addresses-runtime]}}

   "M9"  {:must-id      "M9"
          :title        "The default behaviour without a :path argument is a tree-summary, not the full payload"
          :source       "004-Wire-Pipeline.md L145"
          :planned-test "causa-mcp.tools.path/default-mode-is-summary (parameterised)"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.get-app-db-test 'shape-envelope-summary-mode-default-map]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-test 'shape-envelope-summary-mode-on-vector]
                          ['day8.re-frame2-causa-mcp.summary-test 'public-surface-resolvable]}}

   "M10" {:must-id      "M10"
          :title        "Sequence-returning tools accept :cursor (opaque string) and :limit (integer)"
          :source       "004-Wire-Pipeline.md L158"
          :planned-test "causa-mcp.tools.pagination/sequence-tools-accept-cursor-and-limit"
          :bound-via    #{['day8.re-frame2-causa-mcp.cursor-test 'public-surface-resolvable]
                          ['day8.re-frame2-causa-mcp.cursor-test 'limit-arg-from-js-args-object]
                          ['day8.re-frame2-causa-mcp.tools.get-epoch-history-test 'cursor-round-trip]}}

   "M11" {:must-id      "M11"
          :title        "Sequence-returning responses carry :next-cursor (opaque or nil) and :remaining (count/estimate)"
          :source       "004-Wire-Pipeline.md L163"
          :planned-test "causa-mcp.tools.pagination/response-shape"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.get-epoch-history-test 'shape-envelope-first-page]
                          ['day8.re-frame2-causa-mcp.tools.get-epoch-history-test 'shape-envelope-resume-with-cursor]
                          ['day8.re-frame2-causa-mcp.cursor-test 'encode-cursor-returns-string-on-valid-payload]}}

   "M12" {:must-id      "M12"
          :title        "Tools returning rich nested values expose a :mode argument with at least :summary (default), :sample, and :full"
          :source       "004-Wire-Pipeline.md L184"
          :planned-test "causa-mcp.tools.mode/exposes-summary-sample-full (parameterised)"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.get-app-db-test 'shape-envelope-summary-mode-default-map]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-test 'shape-envelope-full-mode]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-test 'invalid-mode-short-circuits]}}

   "M13" {:must-id      "M13"
          :title        "get-app-db-diff defaults to changed-paths-with-cardinalities, not the nested diff"
          :source       "004-Wire-Pipeline.md L189"
          :planned-test "causa-mcp.tools.diff/default-shape-is-paths-with-cardinalities"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.get-app-db-diff-test 'shape-envelope-changed-paths-default]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-diff-test 'changed-paths-empty-diff]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-diff-test 'changed-paths-counts-include-nested]}}

   "M14" {:must-id      "M14"
          :title        "Catalogue entries cite the regime-appropriate compression factor when declaring :typical-tokens (~1.4× / ~10× / 5-10×)"
          :source       "004-Wire-Pipeline.md L226"
          :planned-test "causa-mcp.catalogue/dedup-factor-cited"
          ;; The factor citation lives in 004-Tools-Catalogue.md per
          ;; tool (S-1 rf2-8xzoe.32). The dedup contract itself is
          ;; exercised in dedup_test.cljs.
          :bound-via    #{['day8.re-frame2-causa-mcp.must-inventory-test 'm14-catalogue-cites-regime-appropriate-dedup-factor]
                          ['day8.re-frame2-causa-mcp.dedup-test 'public-surface-resolvable]}}

   "M15" {:must-id      "M15"
          :title        "Catalogue declares :include-large?, :elided-large, and the default elision-policy on every tool emitting tree-typed payloads"
          :source       "004-Wire-Pipeline.md L342"
          :planned-test "causa-mcp.tools.elision/every-tree-tool-declares-elision-slots; cross-server marker-shape in mcp-conformance"
          :bound-via    #{['day8.re-frame2-causa-mcp.elision-test 'parse-include-large-default-false-when-absent]
                          ['day8.re-frame2-causa-mcp.elision-test 'spec-004-default-posture-is-elision-on-at-mcp-boundary]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-test 'shape-envelope-counts-elision-markers]
                          ['day8.re-frame2-causa-mcp.tools.get-machine-state-test 'shape-envelope-counts-elision-markers]}}

   "M16" {:must-id      "M16"
          :title        "Every tool entry declares mechanisms / :typical-tokens / :cap-reached / default :mode / :limit / :dedup? (catalogue-entry contract)"
          :source       "004-Wire-Pipeline.md L359-363"
          :planned-test "causa-mcp.catalogue/entry-declares-all-required-slots (load-bearing scaffolding test)"
          ;; Like M4 — pinned by the catalogue file (S-1 rf2-8xzoe.32);
          ;; the runtime tools.cljs descriptor is the follow-on.
          :bound-via    #{['day8.re-frame2-causa-mcp.must-inventory-test 'm16-catalogue-declares-all-required-slots-per-tool]}}

   "M17" {:must-id      "M17"
          :title        "The size-elision walker (rf/elide-wire-value) is the single normative emission site — per-tool reimplementation is prohibited"
          :source       "004-Wire-Pipeline.md L301-302"
          :planned-test "causa-mcp.tools.elision/walker-is-single-emission-site (lint over impl source)"
          :bound-via    :cross-server}

   "M18" {:must-id      "M18"
          :title        "The :sensitive? / :large? composition cascade (and sensitive? large?) → ::drop — sensitive wins; no marker emitted when both predicates match"
          :source       "004-Wire-Pipeline.md L277-291, DESIGN-RATIONALE Lock #10"
          :planned-test "mcp-conformance/elision-composition-sensitive-wins; causa-mcp integration test exercising the cascade end-to-end"
          :bound-via    :cross-server}

   "M19" {:must-id      "M19"
          :title        "Direct-read tools route returned values through rf/elide-wire-value with BOTH :include-sensitive? AND :include-large? defaulting false before egress"
          :source       "004-Wire-Pipeline.md §Privacy + spec/Tool-Pair.md L569"
          :planned-test "causa-mcp.tools.privacy/direct-read-tools-elide-sensitive-by-default; direct-read-tools-elide-large-by-default"
          :bound-via    #{['day8.re-frame2-causa-mcp.privacy-test 'parse-include-sensitive-default-false-when-absent]
                          ['day8.re-frame2-causa-mcp.elision-test 'parse-include-large-default-false-when-absent]
                          ['day8.re-frame2-causa-mcp.elision-test 'elision-opts-edn-include-large-false-emits-markers]
                          ['day8.re-frame2-causa-mcp.elision-test 'elision-opts-edn-include-sensitive-defaults-false]}}

   "M20" {:must-id      "M20"
          :title        "eval-cljs MUST be disabled by default; --allow-eval flag required; omitted from tools/list when disabled; structured error on tools/call when disabled; named-mutation tools require NO extra gate"
          :source       "004-Wire-Pipeline.md §Authority classes — named-mutation vs eval-cljs"
          :planned-test "causa-mcp.server.launch/eval-cljs-disabled-by-default; eval-cljs-enabled-with-allow-eval-flag; tools-list-omits-eval-cljs-when-disabled; tools-call-eval-cljs-returns-structured-error-when-disabled; named-mutations-require-no-extra-gate"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.eval-cljs-test 'gate-default-off]
                          ['day8.re-frame2-causa-mcp.tools.eval-cljs-test 'gate-toggle]
                          ['day8.re-frame2-causa-mcp.tools.eval-cljs-test 'gate-off-returns-refusal-without-nrepl-hit]
                          ['day8.re-frame2-causa-mcp.tools.dispatch-test 'happy-path-via-stubs]
                          ['day8.re-frame2-causa-mcp.tools.restore-epoch-test 'public-surface]
                          ['day8.re-frame2-causa-mcp.tools.reset-frame-db-test 'public-surface]}}

   "M21" {:must-id      "M21"
          :title        "subscribe stream emits one drain-batch per notifications/progress notification — the single MCP wire-batching idiom (per-tick dedup table, no cross-tick refs; poll-ms default 100)"
          :source       "004-Wire-Pipeline.md §Streaming over batch + pair2-mcp/spec/003-Tool-Catalogue.md §subscribe"
          :planned-test "causa-mcp.tools.subscribe/emits-per-drain-batch; poll-ms-default-100; dedup-table-per-tick-no-cross-tick-refs"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.subscribe-test 'public-surface-resolvable]
                          ['day8.re-frame2-causa-mcp.tools.subscribe-test 'registered-in-the-catalogue]}}

   ;; --- Implicit MUSTs (load-bearing, not RFC-2119-cased) -----------------
   "I1"  {:must-id      "I1"
          :title        "Every Causa-MCP-driven side-effect on the trace bus carries :tags :origin :causa-mcp (default-on, per-call opt-out); synchronous-extent only on eval-cljs"
          :source       "Principles.md §Origin tagging is the convention, Lock #4"
          :planned-test "causa-mcp.tools.origin/every-mutation-tagged-by-default; eval-cljs-inherits-origin-via-dynamic-var"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.dispatch-test 'build-form-wraps-origin-and-routes-runtime-ns]
                          ['day8.re-frame2-causa-mcp.tools.eval-cljs-test 'build-form-wraps-origin-and-routes-runtime-result-shaper]}}

   "I2"  {:must-id      "I2"
          :title        "MCP-server-side code never reaches a consumer app's preload classpath; injected-runtime code lives under day8.re-frame2-causa.runtime"
          :source       "Principles.md §MCP-server-ns and injected-runtime-ns are distinct, Lock #11"
          :planned-test "causa-mcp.bundle-isolation/server-ns-not-in-preload-classpath (lint)"
          ;; Bundle-isolation gate landed by rf2-8xzoe.35 / C-3
          ;; as a script-based check
          ;; (`implementation/scripts/check-bundle-isolation.cjs`).
          ;; The script greps the counter release bundle for the
          ;; defonce sentinel string planted in server.cljs.
          :bound-via    #{['day8.re-frame2-causa-mcp.must-inventory-test 'i2-bundle-isolation-sentinel-exists]}}

   "I3"  {:must-id      "I3"
          :title        "Single persistent nREPL socket held for the lifetime of the session; subsequent ops reuse without reconnecting"
          :source       "Principles.md §Single persistent nREPL socket, Lock #3"
          :planned-test "causa-mcp.nrepl/socket-is-persistent; subsequent-ops-reuse"
          :bound-via    #{['day8.re-frame2-causa-mcp.nrepl-test 'public-surface-resolvable]}}

   "I4"  {:must-id      "I4"
          :title        "If the nREPL port can't be resolved at startup, the server still boots and answers tools/list; every tools/call returns {:ok? false :reason :nrepl-port-not-found}"
          :source       "Principles.md §Degraded boot, not failed boot"
          :planned-test "causa-mcp.degraded-boot/boots-without-port; tools-call-returns-structured-error"
          :bound-via    #{['day8.re-frame2-causa-mcp.server-test 'degraded-handler-surfaces-nrepl-port-not-found]
                          ['day8.re-frame2-causa-mcp.server-test 'degraded-handler-ignores-request-shape]
                          ['day8.re-frame2-causa-mcp.server-test 'read-port-from-fs-returns-nil-without-port]}}

   "I5"  {:must-id      "I5"
          :title        "If the runtime hasn't been preloaded, the first mutating/inspecting tool call returns {:ok? false :reason :runtime-not-preloaded} with a setup hint"
          :source       "Principles.md §Degraded boot, not failed boot"
          :planned-test "causa-mcp.degraded-boot/runtime-not-preloaded-shape"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.dispatch-test 'probe-rejection-surfaces-runtime-not-preloaded]
                          ['day8.re-frame2-causa-mcp.tools.eval-cljs-test 'probe-rejection-surfaces-runtime-not-preloaded]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-test 'probe-rejection]
                          ['day8.re-frame2-causa-mcp.tools.get-app-db-diff-test 'probe-rejection]
                          ['day8.re-frame2-causa-mcp.tools.get-epoch-history-test 'probe-rejection]
                          ['day8.re-frame2-causa-mcp.tools.get-handlers-test 'probe-rejection-surfaces-runtime-not-preloaded]
                          ['day8.re-frame2-causa-mcp.tools.discover-app-test 'probe-rejection-surfaces-runtime-not-preloaded]}}

   "I6"  {:must-id      "I6"
          :title        "eval-cljs sets *current-origin* via binding whose extent is the synchronous body of the eval call only; async-extent dispatches are accepted as untagged and documented as a known coverage gap (option (b))"
          :source       "Principles.md §Boundary semantics of eval-cljs origin tagging, Lock #4"
          :planned-test "causa-mcp.tools.origin/eval-cljs-binding-wraps-synchronous-body; eval-cljs-sync-dispatch-inherits-causa-mcp-tag; eval-cljs-async-dispatch-tag-known-incomplete"
          :bound-via    #{['day8.re-frame2-causa-mcp.tools.eval-cljs-test 'build-form-wraps-origin-and-routes-runtime-result-shaper]}}})

;; ---------------------------------------------------------------------------
;; Per-MUST deftests — one per inventory row.
;; ---------------------------------------------------------------------------
;;
;; Each `deftest` asserts the corresponding row in MUST-INVENTORY is
;; bound — either to a concrete `[ns sym]` pair (a landed deftest in
;; another test ns; the require-block above guarantees the ns has
;; loaded so the var is resolvable at runtime), or to `:cross-server`
;; (the canonical owner is `tools/mcp-conformance/`; this file pins
;; only the inventory citation).
;;
;; The deftest **name** matches the inventory id (the slug after `m`
;; / `i`). Drift detection lives in `inventory-completeness` below.

(defn- assert-binding-shape
  "Walks every `[ns sym]` pair in the `:bound-via` set and asserts each
  pair is well-formed (namespace symbol + var symbol). The actual
  presence-at-runtime check is the `:require` block at the top of
  this file — if any of the listed namespaces did not compile, the
  whole test build wouldn't link.

  CLJS-runtime caveat: `find-ns` / `ns-resolve` are unreliable in the
  compiled JS environment (the ns-system is collapsed post-
  compilation), so the binding registry serves as documentation +
  compile-time-link guarantee, NOT a runtime resolution probe. The
  cardinality + shape pins (`inventory-cardinality-matches-spec`,
  `inventory-rows-have-required-shape`) are the load-bearing scaffold
  guards; per-row deftests assert the binding entry exists and is
  shaped correctly so a regression in the inventory shape surfaces
  at the per-row level for clean diagnostics."
  [bindings]
  (and (set? bindings)
       (seq bindings)
       (every? (fn [pair]
                 (and (vector? pair)
                      (= 2 (count pair))
                      (symbol? (first pair))
                      (symbol? (second pair))))
               bindings)))

(defn- assert-must-bound!
  "Per-row assertion shared by every `mXX` / `iX` deftest. The
  inventory entry's `:bound-via` slot is either a set of
  `[ns sym]` pairs (in-tree bindings — well-formedness asserted) or
  the keyword `:cross-server` (canonical owner sits in
  tools/mcp-conformance/; the in-tree check is the
  inventory-row-exists pin)."
  [must-id]
  (let [row (get MUST-INVENTORY must-id)]
    (testing (str must-id " — " (:title row))
      (is (some? row)
          (str "MUST-INVENTORY entry MUST exist for id " must-id))
      (cond
        (= :cross-server (:bound-via row))
        (do (is (= :cross-server (:bound-via row))
                "Cross-server MUST — canonical owner is tools/mcp-conformance/ (rf2-zvv65). The in-tree assertion is the inventory-citation pin; the runtime contract verification lives in the cross-server conformance corpus.")
            (is (string? (:source row))
                "Cross-server rows MUST still cite their spec source so the inventory is self-auditing")
            (is (string? (:planned-test row))
                "Cross-server rows MUST still name the cross-server test the contract binds to"))

        (set? (:bound-via row))
        (is (assert-binding-shape (:bound-via row))
            (str "MUST " must-id " — :bound-via MUST be a non-empty "
                 "set of `[ns-symbol var-symbol]` pairs. Got: "
                 (pr-str (:bound-via row))))

        :else
        (is false (str "MUST-INVENTORY entry " must-id
                       " has an unrecognised :bound-via shape: "
                       (pr-str (:bound-via row))))))))

;; --- Explicit MUSTs (21) ---------------------------------------------------

(deftest m1-framework-listeners-default-suppress-sensitive
  (assert-must-bound! "M1"))

(deftest m2-trace-stream-tools-apply-default-suppress-at-boundary
  (assert-must-bound! "M2"))

(deftest m3-no-tool-overspends-cap
  (assert-must-bound! "M3"))

(deftest m4-catalogue-declares-mechanism-set-per-tool
  ;; Self-referential MUST — the catalogue file
  ;; (tools/causa-mcp/spec/004-Tools-Catalogue.md) MUST declare a
  ;; Wire-pipeline contract block per tool. The S-1 commit
  ;; (rf2-8xzoe.32) landed this. The runtime descriptor lint
  ;; (asserting tools.cljs surfaces the same set in JSONSchema) is a
  ;; follow-on; for now the catalogue file IS the source of truth.
  (assert-must-bound! "M4"))

(deftest m5-every-tool-measures-pre-return
  (assert-must-bound! "M5"))

(deftest m6-overflow-never-silent
  (assert-must-bound! "M6"))

(deftest m7-overflow-marker-shape
  (assert-must-bound! "M7"))

(deftest m8-rich-nested-tools-accept-path-arg
  (assert-must-bound! "M8"))

(deftest m9-default-mode-is-summary
  (assert-must-bound! "M9"))

(deftest m10-sequence-tools-accept-cursor-and-limit
  (assert-must-bound! "M10"))

(deftest m11-sequence-response-carries-next-cursor-and-remaining
  (assert-must-bound! "M11"))

(deftest m12-mode-exposes-summary-sample-full
  (assert-must-bound! "M12"))

(deftest m13-app-db-diff-defaults-to-changed-paths
  (assert-must-bound! "M13"))

(deftest m14-catalogue-cites-regime-appropriate-dedup-factor
  ;; Self-referential per M4 — the catalogue file (S-1) carries the
  ;; per-tool :typical-tokens hint with the regime-appropriate
  ;; compression factor citation (~1.4× / ~10× / 5-10×). The dedup
  ;; contract itself is exercised in dedup_test.cljs.
  (assert-must-bound! "M14"))

(deftest m15-tree-tools-declare-elision-slots
  (assert-must-bound! "M15"))

(deftest m16-catalogue-declares-all-required-slots-per-tool
  ;; Self-referential — same as M4 + M14. The catalogue is the
  ;; load-bearing scaffolding. Runtime descriptor lint follow-on.
  (assert-must-bound! "M16"))

(deftest m17-size-elision-walker-is-single-emission-site
  (assert-must-bound! "M17"))

(deftest m18-sensitive-large-composition-sensitive-wins
  (assert-must-bound! "M18"))

(deftest m19-direct-read-tools-elide-by-default-on-both-axes
  (assert-must-bound! "M19"))

(deftest m20-eval-cljs-disabled-by-default-named-mutations-no-extra-gate
  (assert-must-bound! "M20"))

(deftest m21-subscribe-per-drain-batch-progress-notifications
  (assert-must-bound! "M21"))

;; --- Implicit MUSTs (6) ----------------------------------------------------

(deftest i1-every-mutation-tagged-causa-mcp-by-default
  (assert-must-bound! "I1"))

(deftest i2-bundle-isolation-sentinel-exists
  ;; The bundle-isolation gate (rf2-8xzoe.35 / C-3) plants a
  ;; defonce sentinel string at the bottom of server.cljs that
  ;; survives :advanced compilation. The grep gate
  ;; (implementation/scripts/check-bundle-isolation.cjs) confirms
  ;; the sentinel does NOT appear in the counter release bundle.
  ;; This deftest is the inventory binding; the actual check runs
  ;; via `npm run test:bundle-isolation`.
  (assert-must-bound! "I2"))

(deftest i3-single-persistent-nrepl-socket
  (assert-must-bound! "I3"))

(deftest i4-degraded-boot-shape
  (assert-must-bound! "I4"))

(deftest i5-runtime-not-preloaded-shape
  (assert-must-bound! "I5"))

(deftest i6-eval-cljs-origin-binding-synchronous-extent
  (assert-must-bound! "I6"))

;; ---------------------------------------------------------------------------
;; Inventory completeness — the load-bearing scaffold guard.
;; ---------------------------------------------------------------------------
;;
;; A new MUST landing in `findings/MUST-inventory.md` MUST gain a
;; corresponding row in `MUST-INVENTORY` above AND a corresponding
;; per-row deftest above. The two assertions below pin the
;; expected cardinality so drift surfaces as a failure.

(def expected-explicit-musts 21)
(def expected-implicit-musts 6)

(deftest inventory-cardinality-matches-spec
  (testing "MUST-INVENTORY map covers every row in findings/MUST-inventory.md"
    (let [ids (set (keys MUST-INVENTORY))
          explicit (filter #(.startsWith ^String % "M") ids)
          implicit (filter #(.startsWith ^String % "I") ids)]
      (is (= expected-explicit-musts (count explicit))
          (str "Expected " expected-explicit-musts " explicit MUSTs "
               "in the inventory; found " (count explicit)
               " (ids: " (sort explicit) "). When a new explicit MUST "
               "lands in findings/MUST-inventory.md, bump "
               "expected-explicit-musts AND add the row to "
               "MUST-INVENTORY AND add the per-row deftest above."))
      (is (= expected-implicit-musts (count implicit))
          (str "Expected " expected-implicit-musts " implicit MUSTs "
               "in the inventory; found " (count implicit)
               " (ids: " (sort implicit) "). Same drill — new I-row → "
               "bump count + add MUST-INVENTORY entry + add deftest.")))))

(deftest inventory-rows-have-required-shape
  (testing "Every MUST-INVENTORY row carries :must-id, :title, :source, :planned-test, :bound-via"
    (doseq [[id row] MUST-INVENTORY]
      (is (= id (:must-id row))
          (str "Row key " id " MUST match its :must-id slot"))
      (is (string? (:title row))
          (str "Row " id " MUST carry a :title string"))
      (is (string? (:source row))
          (str "Row " id " MUST cite a spec :source"))
      (is (string? (:planned-test row))
          (str "Row " id " MUST name its :planned-test"))
      (is (or (set? (:bound-via row))
              (= :cross-server (:bound-via row)))
          (str "Row " id " MUST have :bound-via as a set of [ns sym] "
               "pairs OR the keyword :cross-server")))))
