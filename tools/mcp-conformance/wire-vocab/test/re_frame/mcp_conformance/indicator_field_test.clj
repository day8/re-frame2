(ns re-frame.mcp-conformance.indicator-field-test
  "Cross-MCP indicator-field conformance (rf2-6m8tq).

  Pins the MUST-level contract from
  [Spec 009 §Indicator field on tool responses][1] and
  [Conventions §Cross-MCP indicator-field vocabulary][2]:

    Tools that return structured response maps and walk a tree-typed
    payload MUST carry an `:elided-large` count alongside the existing
    `:dropped-sensitive` count. Both slots are unqualified keys. Omit
    when zero.

  Sibling to [`wire_vocab_test.clj`](wire_vocab_test.clj) — that file
  pins the **wire MARKER** vocabulary (`:rf.mcp/*` / `:rf.size/*`
  namespaced shapes). This file pins the **envelope SLOT** vocabulary
  (`:dropped-sensitive` / `:elided-large` unqualified scalar counters).
  The two vocabularies compose on every tool response: the markers
  populate values inside the payload, the slots summarise suppression
  totals on the envelope.

  Coverage gap closed (per audit `ai/findings/refactor-audit-tools-pair2-mcp-2026-05-14.md`
  §TE8):

  - (a) Every tool that walks a tree-typed payload routes its envelope
        through a single emit-path (the centralised
        `wire/with-indicators` helper). This file grep-pins the
        catalogue of tree-walking tools and asserts each routes through
        the helper.
  - (b) The slot is omitted when the count is zero, included when the
        count is positive. This file replicates the helper's semantics
        as a pure-Clojure simulation (the helper itself is CLJS; the
        same `cond->` shape is reproduced here, then exercised against
        a fixture grid).
  - (c) The slot's value matches the count handed in. The simulation
        asserts identity-preservation.

  ## Why pure-Clojure simulation (not live-server)

  The helper (`tools/pair2-mcp/src/re_frame_pair2_mcp/tools/wire.cljs`)
  is CLJS — it can't be `require`d from a JVM test. The contract is
  tiny enough (one `cond->` with two arms) that a pure-Clojure
  reproduction inside this test is the right shape: the test is the
  contract's redundant copy, divergence trips the grep step (the
  helper-source pin below).

  The alternative — exercising the contract through a live
  pair2-mcp/story-mcp server — is the job of `test/end-to-end-*.js`
  (protocol conformance) and the live-pair2-overflow path (runtime
  cap-trigger conformance). This file's gate is at the wire-shape
  layer, same posture as `wire_vocab_test.clj`.

  [1]: ../../../spec/009-Instrumentation.md#size-elision-in-traces
  [2]: ../../../spec/Conventions.md#cross-mcp-indicator-field-vocabulary-suppression-counters"
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [malli.core      :as m]
            [re-frame.mcp-conformance.fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; Repo-root + slurp helpers live in `re-frame.mcp-conformance.fixtures`
;; (rf2-113ti). `io` is still required below for the `pair2-mcp-source-files`
;; walker and the `causa-mcp-impl-still-absent` directory probe.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Contract — the canonical envelope-slot vocabulary.
;;
;; Both slots are unqualified keywords (per Conventions:157 — they ride
;; alongside tool-shaped payloads where the tool's slot vocabulary is
;; unqualified by convention). Values are non-negative integers (`0`
;; never appears — the omit-when-zero rule turns 0 into "key absent").
;; ---------------------------------------------------------------------------

(def ^:private dropped-sensitive-key :dropped-sensitive)
(def ^:private elided-large-key      :elided-large)

(def ^:private DroppedSensitive
  "Envelope shape with the `:dropped-sensitive` slot present and
  positive."
  [:map {:closed false} [:dropped-sensitive pos-int?]])

(def ^:private ElidedLarge
  "Envelope shape with the `:elided-large` slot present and positive."
  [:map {:closed false} [:elided-large pos-int?]])

;; ---------------------------------------------------------------------------
;; Helper simulation. Mirror of
;; `tools/pair2-mcp/src/re_frame_pair2_mcp/tools/wire.cljs/with-indicators`.
;; The CLJS implementation:
;;
;;   (defn with-indicators
;;     [envelope {:keys [dropped elided]}]
;;     (cond-> envelope
;;       (pos? (or dropped 0)) (assoc :dropped-sensitive dropped)
;;       (pos? (or elided  0)) (assoc :elided-large      elided)))
;;
;; The pure-Clojure reproduction below is byte-identical in semantics.
;; If the CLJS helper drifts, the grep step (`helper-source-pin`) trips;
;; if the contract drifts (e.g. someone changes "omit when zero" to
;; "always include"), the fixture grid below trips.
;; ---------------------------------------------------------------------------

(defn- with-indicators
  "Pure-Clojure mirror of `wire/with-indicators`. Splice the two
  indicator-field slots onto an envelope, honouring the omit-when-zero
  rule."
  [envelope {:keys [dropped elided]}]
  (cond-> envelope
    (pos? (or dropped 0)) (assoc :dropped-sensitive dropped)
    (pos? (or elided  0)) (assoc :elided-large      elided)))

;; ---------------------------------------------------------------------------
;; Fixture grid. Pins the contract's three axes:
;;
;;   (b) omit when zero
;;   (b) include when positive
;;   (c) value passes through unchanged
;;
;; Each row is `[label dropped-in elided-in expected-envelope-delta]`.
;; `expected-envelope-delta` is the EXACT map the slot-splice MUST
;; produce on top of the empty envelope `{}`. Drift in the helper's
;; semantics (e.g. a future "include zero as `:dropped-sensitive 0`"
;; regression) trips this grid.
;; ---------------------------------------------------------------------------

(def ^:private fixture-grid
  [;; --- both zero — no slots, envelope unchanged ----------------------
   {:label    "neither slot present when both counts are zero"
    :dropped  0
    :elided   0
    :expected {}}

   ;; --- both nil — defensive — treated as zero -------------------------
   {:label    "neither slot present when both counts are nil"
    :dropped  nil
    :elided   nil
    :expected {}}

   ;; --- dropped only ---------------------------------------------------
   {:label    "only :dropped-sensitive present when only dropped > 0"
    :dropped  3
    :elided   0
    :expected {:dropped-sensitive 3}}

   {:label    "only :dropped-sensitive present when elided is nil"
    :dropped  1
    :elided   nil
    :expected {:dropped-sensitive 1}}

   ;; --- elided only ----------------------------------------------------
   {:label    "only :elided-large present when only elided > 0"
    :dropped  0
    :elided   2
    :expected {:elided-large 2}}

   {:label    "only :elided-large present when dropped is nil"
    :dropped  nil
    :elided   7
    :expected {:elided-large 7}}

   ;; --- both positive --------------------------------------------------
   {:label    "both slots present when both counts > 0"
    :dropped  4
    :elided   11
    :expected {:dropped-sensitive 4 :elided-large 11}}

   ;; --- large counts pass through verbatim -----------------------------
   {:label    "counts preserved verbatim (no coercion / clamping)"
    :dropped  10000
    :elided   99999
    :expected {:dropped-sensitive 10000 :elided-large 99999}}])

;; ---------------------------------------------------------------------------
;; Gate (b) + (c) — semantic contract: omit-when-zero AND
;; value-passes-through.
;; ---------------------------------------------------------------------------

(deftest indicator-helper-semantics
  (doseq [{:keys [label dropped elided expected]} fixture-grid]
    (testing label
      (let [result (with-indicators {} {:dropped dropped :elided elided})]
        (is (= expected result)
            (str "Helper result mismatch for " label
                 " — got " (pr-str result)
                 ", expected " (pr-str expected)))))))

(deftest indicator-helper-preserves-envelope-shape
  ;; The helper MUST be additive — it splices slots onto an existing
  ;; envelope without dropping its own keys. Critical because every
  ;; tool calls `(with-indicators <full-envelope> indicators)` where
  ;; `<full-envelope>` carries the tool's own keys (`:ok?`,
  ;; `:snapshot`, `:epochs`, ...).
  (let [envelope {:ok? true :snapshot {:foo :bar} :extra "string"}
        result   (with-indicators envelope {:dropped 1 :elided 2})]
    (is (= true (:ok? result)))
    (is (= {:foo :bar} (:snapshot result)))
    (is (= "string" (:extra result)))
    (is (= 1 (:dropped-sensitive result)))
    (is (= 2 (:elided-large result)))))

;; ---------------------------------------------------------------------------
;; Schema gate — when present, the slots conform to the canonical
;; envelope shape (open map, `pos-int?` value). A regression that
;; serialised the count as a string or as `0` would trip here.
;; ---------------------------------------------------------------------------

(deftest indicator-fixtures-conform-to-canonical-schema
  (doseq [{:keys [label expected]} fixture-grid
          :when (contains? expected :dropped-sensitive)]
    (testing (str "dropped-sensitive schema — " label)
      (is (m/validate DroppedSensitive expected)
          (str "Fixture " expected " failed DroppedSensitive schema"))))
  (doseq [{:keys [label expected]} fixture-grid
          :when (contains? expected :elided-large)]
    (testing (str "elided-large schema — " label)
      (is (m/validate ElidedLarge expected)
          (str "Fixture " expected " failed ElidedLarge schema")))))

;; ---------------------------------------------------------------------------
;; Gate (a) — tree-walking-tool routing pin.
;;
;; The catalogue below lists every pair2-mcp tool that walks a
;; tree-typed payload (per Spec 009:1411 — "one MUST-level row per
;; consumer-facing tool that walks a tree-typed payload"). Each MUST
;; route its envelope through the centralised `wire/with-indicators`
;; helper — that single emit-path is the contract's structural
;; guarantee. Drift (a tool emitting `:dropped-sensitive` / `:elided-
;; large` directly without going through the helper) trips this gate.
;; ---------------------------------------------------------------------------

(def ^:private tree-walking-tool-sources
  "Per-tool source files for pair2-mcp's tree-walking tools. Each
  source MUST contain at least one `wire/with-indicators` call —
  that's the centralised emit-path the contract pins. Adding a new
  tree-walking tool means extending this list AND wiring the helper
  call; the new entry without the wiring fails this gate."
  {:snapshot     "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/snapshot.cljs"
   :get-path     "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/get_path.cljs"
   :trace-window "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/trace_window.cljs"
   :watch-epochs "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/watch_epochs.cljs"
   ;; subscribe-emit owns BOTH the streaming progress payload AND the
   ;; final summary payload for `subscribe` — they each splice the
   ;; helper independently (per Conventions:159 — "Streaming payloads.
   ;; Subscribe-style notifications ... carry the same two slots on
   ;; each progress payload and on the final summary").
   :subscribe    "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/subscribe.cljs"})

(deftest every-tree-walking-tool-routes-through-the-helper
  (doseq [[tool rel] tree-walking-tool-sources]
    (testing (str "tool " tool " — wire/with-indicators call-site in " rel)
      (let [src (fx/read-source rel)]
        (is (str/includes? src "wire/with-indicators")
            (str "Tool " tool " at " rel
                 " does not route its envelope through `wire/with-indicators`. "
                 "The centralised emit-path is the structural contract — a "
                 "tool that inlines `(assoc :dropped-sensitive ...)` or "
                 "`(assoc :elided-large ...)` directly violates the MUST-"
                 "level parity rule per Conventions:154 / Spec 009:1411."))))))

(deftest subscribe-emit-carries-both-streaming-and-final-splice
  ;; Streaming-payload extra pin (Conventions:159): subscribe-emit
  ;; MUST splice the helper TWICE — once for per-tick progress, once
  ;; for the final summary. A regression that dropped the streaming
  ;; splice would silently ship per-tick payloads without indicator
  ;; counts on the streaming path while still showing them on the
  ;; final summary — invisible to single-payload conformance.
  (let [rel "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/subscribe.cljs"
        src (fx/read-source rel)
        n   (count (re-seq #"wire/with-indicators" src))]
    (is (>= n 2)
        (str "Expected `subscribe-emit` to call `wire/with-indicators` "
             "at least twice (once on the per-tick progress payload, "
             "once on the final summary) per Conventions:159 "
             "streaming-payload rule, but found " n " call-sites in "
             rel ". Drift here means streaming progress payloads ship "
             "without indicator counts."))))

;; ---------------------------------------------------------------------------
;; Helper-source pin — the simulation above MUST stay byte-faithful
;; with the CLJS helper. We grep the CLJS source for the canonical
;; `cond->` shape; a rename / re-implementation surfaces here so the
;; reviewer updates the simulation alongside the helper.
;; ---------------------------------------------------------------------------

(def ^:private helper-source-rel
  "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/wire.cljs")

(deftest helper-source-shape-matches-simulation
  (let [src (fx/read-source helper-source-rel)]
    (testing "helper defines `with-indicators`"
      (is (str/includes? src "(defn with-indicators")
          (str "`with-indicators` defn missing from " helper-source-rel)))
    (testing "helper guards :dropped-sensitive on pos? dropped"
      (is (str/includes? src "(pos? (or dropped 0)) (assoc :dropped-sensitive dropped)")
          (str "Canonical omit-when-zero shape for `:dropped-sensitive` "
               "drifted from the simulation in this file. If you changed "
               "the helper, update the simulation here in lockstep.")))
    (testing "helper guards :elided-large on pos? elided"
      (is (str/includes? src "(pos? (or elided  0)) (assoc :elided-large      elided)")
          (str "Canonical omit-when-zero shape for `:elided-large` "
               "drifted from the simulation in this file. If you changed "
               "the helper, update the simulation here in lockstep.")))))

;; ---------------------------------------------------------------------------
;; Inline-emit anti-pin — neither slot literal may appear inline in any
;; pair2-mcp source file OTHER than the helper itself (and the
;; descriptors / subscribe-tool internal-state file, both whitelisted
;; below). A tool that bypasses the helper to `(assoc envelope
;; :dropped-sensitive N)` directly violates the MUST-level parity rule
;; — the helper exists precisely to centralise the omit-when-zero rule
;; and the parity invariant.
;; ---------------------------------------------------------------------------

(def ^:private inline-emit-whitelist
  "Files allowed to contain the literal `:dropped-sensitive` or
  `:elided-large` keywords. Anything else MUST go through the
  `with-indicators` helper.

  - `wire.cljs`        — the helper itself (the canonical emit-path).
  - `descriptors.cljs` — tool-descriptor docstrings reference the slot
                         names; these are documentation strings shipped
                         in the `tools/list` response shape, not envelope
                         emissions.
  - `subscribe.cljs`   — internal mutable-atom names carry a
                         `-sensitive*` / `-large*` suffix to mirror the
                         envelope slots they feed into; the literals
                         appear as KEYS of an INTERNAL options map
                         passed to `subscribe-emit`, never as keys of
                         an emitted envelope. The emit happens in
                         `subscribe.cljs` and goes through the
                         helper."
  #{"tools/pair2-mcp/src/re_frame_pair2_mcp/tools/wire.cljs"
    "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors.cljs"
    "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/subscribe.cljs"})

(defn- pair2-mcp-source-files
  "Walk `tools/pair2-mcp/src/` and return every `.cljs` file as a
  repo-relative path string."
  []
  (let [src-root (io/file fx/repo-root "tools/pair2-mcp/src")]
    (when (.isDirectory src-root)
      (->> (file-seq src-root)
           (filter #(and (.isFile ^java.io.File %)
                         (str/ends-with? (.getName ^java.io.File %) ".cljs")))
           (map (fn [^java.io.File f]
                  (-> (.getAbsolutePath f)
                      (str/replace "\\" "/")
                      (str/replace (str/replace fx/repo-root "\\" "/") "")
                      (subs 1))))                                ;; strip leading "/"
           sort))))

(deftest no-inline-indicator-slot-emit-outside-the-helper
  ;; The grep is applied AFTER `fx/strip-comments-and-strings` neuters
  ;; docstring / comment / descriptor-string mentions — descriptor
  ;; descriptions ship the slot names as user-visible documentation
  ;; (e.g. `"Dropped count surfaces as `:dropped-sensitive` ..."` in
  ;; `descriptors_data.cljs`), and tool-source docstrings cross-link the
  ;; helper they delegate to (e.g. `subscribe.cljs`'s
  ;; `final-summary` docstring names both slots while the actual emit
  ;; goes through `wire/with-indicators`). Those are documentation, not
  ;; emissions; the strip-then-grep posture catches real inline emits
  ;; (`(assoc envelope :dropped-sensitive N)`) while letting prose
  ;; through. Same posture as the wire-vocab gate's source-text pin
  ;; (rf2-vj8y3).
  ;;
  ;; The substring grep uses `fx/variant-regex` (rf2-qnmne) rather than
  ;; raw `str/includes?` so a future legitimate extension like
  ;; `:dropped-sensitive-warning` or `:elided-large-summary` wouldn't
  ;; false-positive-trip the gate on the prefix match. Same pattern
  ;; as `slot_name_test.clj`'s near-miss-variant grep.
  (let [slot-literals [":dropped-sensitive" ":elided-large"]
        srcs          (pair2-mcp-source-files)]
    (is (seq srcs)
        "Expected to find pair2-mcp source files; classpath walk returned empty.")
    (doseq [rel srcs
            slot slot-literals
            :when (not (contains? inline-emit-whitelist rel))]
      (testing (str rel " — must not inline " slot)
        (let [src      (fx/read-source rel)
              stripped (fx/strip-comments-and-strings src)
              pat      (fx/variant-regex slot)]
          (is (not (re-find pat stripped))
              (str "Inline `" slot "` literal found in " rel
                   " (in code, AFTER stripping comments/docstrings/strings).\n"
                   "Every emit MUST go through `wire/with-indicators` "
                   "(per Conventions:154 / Spec 009:1411). If this file "
                   "is a legitimate exception (a destructuring binding "
                   "reading internal state, an internal state-atom name), "
                   "add it to `inline-emit-whitelist` with a justification.")))))))

;; ---------------------------------------------------------------------------
;; Cross-server tripwire — story-mcp / causa-mcp posture.
;;
;; The sibling `wire_vocab_test.clj` already pins:
;; - story-mcp emits ZERO cross-MCP markers
;; - story-mcp emits ZERO envelope indicators (it doesn't walk
;;   tree-typed payloads today)
;;
;; This file adds the symmetric pin for causa-mcp: its spec text
;; cross-references the slot vocabulary (per Principles.md), but its
;; implementation hasn't landed yet — the spec mentions don't count as
;; emit-sites. The day causa-mcp's `src/` lands a tree-typed-payload
;; walker, the reviewer extends `tree-walking-tool-sources` (a new
;; entry routed through causa's own helper, which must also be added
;; to `inline-emit-whitelist` analogue for causa).
;; ---------------------------------------------------------------------------

(deftest causa-mcp-impl-still-absent
  ;; Sanity tripwire. causa-mcp's `src/` is empty today (spec-only).
  ;; When it lands, this test fails and the reviewer extends the
  ;; tree-walking-tool catalogue with causa entries.
  (let [src-dir (io/file fx/repo-root "tools/causa-mcp/src")]
    (is (or (not (.exists src-dir))
            (empty? (filter #(.isFile ^java.io.File %) (file-seq src-dir))))
        (str "tools/causa-mcp/src/ now contains source files. "
             "Extend `tree-walking-tool-sources` and "
             "`inline-emit-whitelist` (or their causa-mcp equivalents) "
             "to cover causa-mcp's tree-walking tools per Conventions:154 "
             "/ Spec 009:1411 MUST-level parity."))))
