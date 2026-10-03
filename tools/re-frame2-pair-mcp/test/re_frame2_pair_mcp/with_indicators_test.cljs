(ns re-frame2-pair-mcp.with-indicators-test
  "Conformance gate for `wire/with-indicators`.

  ## The MUST rule

  Per `spec/Conventions.md` §Cross-MCP indicator-field vocabulary and
  `spec/009-Instrumentation.md` §Indicator field on tool responses:

  > Every tool that walks a tree-typed payload MUST carry the
  > `:dropped-sensitive` and `:elided-large` slots on its response
  > envelope WHEN their counts are non-zero, and MUST OMIT them when
  > the counts are zero.

  The re-frame2-pair-mcp impl centralises this rule at `wire/with-indicators`
  — the tree-walking tool emit sites (`snapshot`, `get-path`,
  `trace-window`, `watch-epochs`, `read-sub`, `dispatch-dry-run`) route
  their envelope-tail through it. The MUST-level contract lives in one
  `cond->` form; this conformance suite pins that contract so a
  regression at the choke-point — or at a pinned caller that bypasses
  it — fails loudly.

  ## What's pinned

  - **The choke-point**: `wire/with-indicators` emits each slot when
    its count is non-zero, omits the other when that count is zero,
    and never confuses the two slots. The primitive it delegates to
    (`re-frame.mcp-base.envelope/with-indicators`, including nil-as-zero)
    is pinned by mcp-base's own suite on both hosts.
  - **The pinned emit sites**: each of the four tool source files
    named below (`snapshot`, `get_path`, `trace_window`,
    `watch_epochs`) references `with-indicators`, so a bypass in
    any of them is a build failure. A NEW tool that walks a payload
    needs its own row here, or only hand-review would catch a
    bypass."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.tools.wire :as wire]))

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- repo-root
  "Walk upward from the test process's cwd until we find a directory
  whose `tools/re-frame2-pair-mcp/src` subdir exists. Robust against the test
  runner's working directory (`tools/re-frame2-pair-mcp` vs repo root)."
  []
  (loop [d (.cwd js/process)]
    (cond
      (.existsSync fs (.join path d "tools/re-frame2-pair-mcp/src"))
      (.join path d "tools/re-frame2-pair-mcp")

      (.existsSync fs (.join path d "src/re_frame2_pair_mcp"))
      d

      (= d (.dirname path d))
      (throw (ex-info "Could not locate re-frame2-pair-mcp root from cwd"
                      {:cwd (.cwd js/process)}))

      :else (recur (.dirname path d)))))

(defn- read-source [rel-path]
  (let [root (repo-root)
        full (.join path root rel-path)]
    (.toString (.readFileSync fs full))))

;; ---------------------------------------------------------------------------
;; The choke-point itself.
;; ---------------------------------------------------------------------------

(deftest with-indicators-emits-each-slot-independently-when-non-zero
  ;; Dropped only.
  (let [out (wire/with-indicators {:foo :bar} {:dropped 3 :elided 0})]
    (is (= 3 (:dropped-sensitive out)))
    (is (not (contains? out :elided-large))))
  ;; Elided only.
  (let [out (wire/with-indicators {:foo :bar} {:dropped 0 :elided 5})]
    (is (= 5 (:elided-large out)))
    (is (not (contains? out :dropped-sensitive))))
  ;; Both — the common shape on a real tool response.
  (let [out (wire/with-indicators {:foo :bar} {:dropped 3 :elided 5})]
    (is (= 3 (:dropped-sensitive out)))
    (is (= 5 (:elided-large out)))
    (is (= :bar (:foo out)))))

;; ---------------------------------------------------------------------------
;; Per-emit-site choke-point check.
;;
;; Each tool that walks a tree-typed payload MUST route its
;; envelope-tail through `wire/with-indicators`. The sites pinned here
;; all use the helper; a tool that adds a tree-walk and bypasses the
;; helper would let the omit-when-zero rule regress silently. Grep
;; each source file for the literal `with-indicators` reference.
;; ---------------------------------------------------------------------------

(defn- contains-with-indicators? [src]
  (boolean (re-find #"wire/with-indicators|with-indicators" src)))

(deftest every-pinned-emit-site-routes-through-with-indicators
  (doseq [file ["snapshot.cljs" "get_path.cljs" "trace_window.cljs" "watch_epochs.cljs"]]
    (is (contains-with-indicators? (read-source (str "src/re_frame2_pair_mcp/tools/" file)))
        (str file " MUST route its envelope through wire/with-indicators"))))
