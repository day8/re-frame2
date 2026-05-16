(ns re-frame.mcp-conformance.slot-name-test
  "Cross-MCP slot-name conformance (rf2-zvv65).

  Pins the cross-server **argument-slot vocabulary** that an agent
  learns once and recognises identically across every MCP server in
  the re-frame2 triplet (pair2-mcp, story-mcp, causa-mcp).

  Sibling to:

  - [`wire_vocab_test.clj`](wire_vocab_test.clj)     — wire MARKER
    vocabulary (`:rf.mcp/*` / `:rf.size/*` namespaced payload shapes).
  - [`indicator_field_test.clj`](indicator_field_test.clj) —
    envelope SLOT vocabulary for suppression counters
    (`:dropped-sensitive` / `:elided-large`).
  - **This file**                                    — `tools/call`
    INPUT-arg slot vocabulary (the keys an agent puts INTO an
    `arguments` object).

  The three vocabularies compose. An agent learns once that:

  - the input arg `:include-sensitive?` opts back in to sensitive
    events on any tool surfacing trace-like payloads,
  - the input arg `:max-tokens` overrides the 5,000-token cap on any
    tool,
  - over-budget responses come back as `{:rf.mcp/overflow {...}}`,
  - tree-walking responses carry `:dropped-sensitive` /
    `:elided-large` counters on the envelope.

  ... and the same vocabulary works on every server. Cross-server
  divergence on any of these slot names breaks the agent's mental
  model in the cross-MCP workflow (chained pair2-mcp +
  story-mcp + causa-mcp in one session). The audit (rf2-m9yoi
  §TE2) called out that the cross-server promise was **unenforced**
  before this gate landed — multiple Principles.md sections claim
  identity but no test actually pinned the wire-level agreement.

  Composes on the indicator-field gate pattern from rf2-6m8tq
  (#866) and the wire-vocab marker gate from rf2-j2z7o.

  ## What this test guards

  1. **Tool-arg slot-name parity** — `:include-sensitive?` /
     `:max-tokens` literals appear as INPUT-arg keys in every server's
     descriptor source and arg-parsing source. A rename on any server
     trips this gate.
  2. **No near-miss variants** — snake_case / pluralised / quoted
     forms (`:include_sensitive`, `:max_tokens`, `\"includeSensitive\"`)
     don't appear in any server's tool source.
  3. **Cross-server divergence pin** — `:include-large?` is the
     spec'd cross-server slot per causa-mcp Principles §Mechanism 6;
     pair2-mcp today surfaces the same posture under a different
     spelling (`:elision`). The divergence is captured EXPLICITLY
     in this file so a future bead either (a) renames pair2-mcp to
     `:include-large?` and removes the divergence pin, or (b) extends
     causa-mcp's spec to recognise `:elision` as an alias. The pin
     surfaces the choice; it doesn't silently green-light drift.
  4. **mcp-base re-export pin** — the canonical slot keywords are
     defined ONCE in `tools/mcp-base/src/re_frame/mcp_base/vocab.cljc`
     (`include-sensitive-opt`, `include-large-opt`) and re-used by
     consumers. The grep step asserts the literal keyword form in the
     vocab ns so a rename there breaks everyone.
  5. **causa-mcp impl-landed pin** — causa-mcp's T-Insp tool cluster
     (rf2-8xzoe.14..22) shipped `src/.../tools/` with nine
     tree-walking tools; the per-server source-file grep covers them
     alongside pair2-mcp + story-mcp. The historical tripwire (this
     row was a `causa-mcp-impl-still-absent` gate) has done its job
     and is replaced by an impl-landed assertion below.

  ## Why pure JVM Clojure (not Node SDK)

  Same posture as `wire_vocab_test.clj` / `indicator_field_test.clj`:
  this is a SOURCE-text + SPEC-text + SHAPE conformance gate. The
  sibling `test/end-to-end-*.js` files validate live PROTOCOL
  conformance (the SDK's `tools/list` parse step indirectly verifies
  arg-schema shapes against the agent-host's view). This file pins
  the agreement at the source-of-truth — the literal keyword bytes
  in every server's descriptor and arg-parsing code.

  [1]: ../../../../spec/Conventions.md
  [2]: ../../TOKEN-BUDGETS.md"
  (:require [clojure.java.io :as io]
            [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [malli.core      :as m]
            [re-frame.mcp-conformance.fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; Repo-root + slurp helpers live in `re-frame.mcp-conformance.fixtures`
;; (rf2-113ti). `io` is still required for the `causa-mcp-impl-still-absent`
;; directory probe below.
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Canonical slot-name table. The single source of truth — every cross-
;; server contract row references back to this map.
;;
;; Each entry pins:
;;
;;   :slot         — the canonical keyword literal an agent supplies as
;;                   an `arguments` key. Bytes-identical across servers.
;;   :role         — `:opt-in-boolean` / `:override-integer` / etc. Used
;;                   by the schema gate below.
;;   :servers      — set of servers that contract for this slot. A
;;                   singleton means single-server today; multi-server
;;                   means the literal MUST appear in every named
;;                   server's source.
;;   :sources      — per-server `[rel-path-to-source-file ...]` grep
;;                   targets. The literal keyword (or its plain-keyword
;;                   form for descriptor maps that use unquoted keys)
;;                   MUST appear in at least one of the listed files
;;                   per server.
;;   :divergent    — optional. Non-canonical spellings deliberately
;;                   tolerated on the listed servers TODAY (the
;;                   `:include-large?` vs `:elision` example). Future
;;                   rename closes the divergence; the test surfaces
;;                   the choice rather than silently allowing it.
;;
;; Adding a new cross-server arg slot means extending this table AND
;; the divergence-pin section below — that's the right friction; the
;; conformance contract knows about every shared arg surface.
;; ---------------------------------------------------------------------------

(def ^:private canonical-slots
  [{:slot     :include-sensitive?
    :role     :opt-in-boolean
    :servers  #{:pair2-mcp :story-mcp :causa-mcp}
    :sources  {:pair2-mcp ["tools/pair2-mcp/src/re_frame_pair2_mcp/tools/sensitive.cljs"
                           "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors.cljs"]
               ;; story-mcp's `:include-sensitive?` parsing lives in
               ;; `helpers.cljc` (rf2-73wuj — the cross-MCP `args/parse-
               ;; boolean` reader) and the slot schema lives in
               ;; `schemas.cljc` (`include-sensitive-schema` injector).
               ;; No `sensitive.cljc` (the path was a stale guess from
               ;; the pair2-mcp shape that doesn't apply to story-mcp).
               :story-mcp ["tools/story-mcp/src/re_frame/story_mcp/tools/helpers.cljc"
                           "tools/story-mcp/src/re_frame/story_mcp/tools/schemas.cljc"]
               ;; causa-mcp's T-Insp cluster (rf2-8xzoe.14..22) shipped
               ;; per-tool tree-walkers under `src/.../tools/`. The
               ;; canonical literal `:include-sensitive?` appears in the
               ;; per-tool args-schema splices (e.g. `get_app_db.cljs`'s
               ;; `:inputSchema` :properties map) and the runtime-opts
               ;; map handed to the walker. Both pair2-mcp and story-mcp
               ;; already pin per-tool sources; causa-mcp matches that
               ;; posture.
               :causa-mcp ["tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db_diff.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_epoch_history.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_handlers.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_issues.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_list.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_state.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_trace_buffer.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/privacy.cljs"]}
    :doc      "Opt-in boolean — pass `true` to disable the spec/009 §Privacy
               default-drop on `:sensitive? true` items. Default false."}

   {:slot     :max-tokens
    :role     :override-integer
    :servers  #{:pair2-mcp :story-mcp :causa-mcp}
    :sources  {;; pair2-mcp surfaces the slot as the Clojure keyword
               ;; `:max-tokens` in the descriptor-splice helper
               ;; (`descriptors.cljs/with-budget-knob`) and reads the
               ;; JS-side `"max-tokens"` string off the args object in
               ;; `cap.cljs/max-tokens-arg`. The keyword form is the
               ;; canonical literal — the descriptor splice IS what
               ;; pins the wire-side name agents see in `tools/list`.
               :pair2-mcp ["tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors.cljs"
                           "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors_knobs.cljs"]
               :story-mcp ["tools/story-mcp/src/re_frame/story_mcp/tools/schemas.cljc"
                           "tools/story-mcp/src/re_frame/story_mcp/tools/cap.cljc"]
               ;; causa-mcp's T-Insp cluster splices `:max-tokens` into
               ;; every per-tool `:inputSchema` :properties map and reads
               ;; it off the args object in `token_cap.cljs`. Same posture
               ;; as pair2-mcp's descriptor + cap split.
               :causa-mcp ["tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db_diff.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_epoch_history.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_handlers.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_issues.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_list.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_state.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_source_coord.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_trace_buffer.cljs"
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/token_cap.cljs"]}
    :doc      "Override integer — per-call wire-cap override (default 5,000).
               `0` disables the cap. Triggers an `:rf.mcp/overflow` marker
               when the rendered payload exceeds the cap (cross-server)."}])

;; ---------------------------------------------------------------------------
;; Divergence pin — `:include-large?` (causa-mcp) vs `:elision`
;; (pair2-mcp). Pinned explicitly so the divergence cannot creep in
;; silently — the reviewer either renames pair2-mcp to align with
;; `:include-large?` or extends the vocabulary to recognise `:elision`
;; cross-server. The test does NOT choose for them; it surfaces the
;; choice.
;;
;; Both slots are *semantically* the cross-MCP size-elision opt-out.
;; The pair2-mcp implementation predates causa-mcp's spec naming
;; (rf2-urjnc landed `:elision` before causa-mcp's mechanism 6
;; consolidated under `:include-large?`).
;; ---------------------------------------------------------------------------

(def ^:private elision-arg-divergence
  {:canonical :include-large?
   :servers   {:causa-mcp {:slot    :include-large?
                           ;; T-Insp cluster (rf2-8xzoe.14..22) shipped
                           ;; `:include-large?` as the canonical slot in
                           ;; every per-tool `:inputSchema` and in the
                           ;; runtime-opts map handed to the elision
                           ;; walker. The spec-only stand-in is no longer
                           ;; needed; tool sources carry the literal.
                           :sources ["tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db_diff.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_epoch_history.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_handlers.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_issues.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_list.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_state.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_trace_buffer.cljs"
                                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/elision.cljs"]
                           :status  :impl-landed}
               :pair2-mcp {:slot    :elision
                           :sources ["tools/pair2-mcp/src/re_frame_pair2_mcp/tools/elision.cljs"
                                     "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors_knobs.cljs"]
                           :status  :impl-divergent}}
   :resolution
   "Resolve in one of two ways. Option A — rename pair2-mcp's
    `:elision` arg to `:include-large?` (the polarity flips too —
    `:elision true` becomes `:include-large? false`); remove this
    divergence pin. Option B — extend causa-mcp's spec to document
    `:elision` as cross-server cognate; update this pin's `:canonical`
    slot. Today the gate prevents the slot from drifting further (e.g.
    a third server inventing a fourth spelling)."})

;; ---------------------------------------------------------------------------
;; mcp-base vocab pin — the keyword constants. The canonical home for
;; every cross-server slot keyword. A rename here breaks every
;; consumer simultaneously; the grep step asserts the literal byte
;; sequence appears in the vocab ns.
;; ---------------------------------------------------------------------------

(def ^:private mcp-base-vocab-source
  "tools/mcp-base/src/re_frame/mcp_base/vocab.cljc")

(def ^:private mcp-base-vocab-literals
  "The canonical slot keywords that MUST appear verbatim in the vocab
  ns. The vocab ns is the cross-MCP single source of truth; a rename
  here breaks every server in lockstep — which is the right
  invariant. The slot keys in this set are the NAMESPACED framework
  forms (`:rf.size/include-large?` /
  `:rf.size/include-sensitive?` — the walker opts) PLUS the
  unqualified MCP-surface forms (`:include-sensitive?` —
  inferred from the docstrings in vocab.cljc)."
  #{":rf.size/include-large?"
    ":rf.size/include-sensitive?"
    ":include-sensitive?"})

;; ---------------------------------------------------------------------------
;; Argument-role schema gate. Each role pins a Malli shape — a
;; well-formed `arguments` payload the test fixtures below MUST
;; conform to. The schemas don't reach into a live server; they pin
;; the contract a consumer can encode against.
;; ---------------------------------------------------------------------------

(def ^:private OptInBoolean
  "A boolean-typed input arg payload."
  [:map {:closed false} [:include-sensitive? :boolean]])

(def ^:private OverrideInteger
  "An integer-typed input arg payload — non-negative because `0` is
  the documented cap-disabled escape hatch."
  [:map {:closed false} [:max-tokens nat-int?]])

(def ^:private role->schema
  {:opt-in-boolean    OptInBoolean
   :override-integer  OverrideInteger})

(def ^:private role->fixtures
  "Per-role fixture grid. Each fixture is a well-formed `arguments`
  payload an agent might construct; each MUST validate against the
  role's canonical schema. Drift in the role contract (e.g. a server
  silently accepting `:max-tokens \"5000\"` as a string) is caught
  upstream — these fixtures pin the AGENT-FACING canonical shape."
  {:opt-in-boolean   [{:include-sensitive? true}
                      {:include-sensitive? false}]
   :override-integer [{:max-tokens 5000}
                      {:max-tokens 0}                       ; cap disabled
                      {:max-tokens 1}                       ; tight cap
                      {:max-tokens 50000}]})                ; loose cap

;; ---------------------------------------------------------------------------
;; Near-miss variants — spellings that look right but aren't. A rename
;; to any of these MUST NOT slip through.
;;
;; Each variant is matched with a regex that compiles a trailing
;; negative-lookahead so we only match the variant when it's NOT
;; immediately followed by a keyword-extender character. Plain
;; `str/includes?` on a substring would false-positive: the variant
;; `:include-sensitive` is technically a substring of the canonical
;; `:include-sensitive?`, so a naive check would always trip even in
;; a clean codebase. The regex form pins the variant as a full
;; keyword token — followed by whitespace, paren, brace, colon, comma,
;; etc — anything that isn't a keyword-extender.
;; ---------------------------------------------------------------------------

;; `variant-regex` lives in `re-frame.mcp-conformance.fixtures`
;; (rf2-qnmne) — promoted from a private defn here so
;; `indicator_field_test.clj`'s inline-emit anti-pin can share the same
;; keyword-extender-aware pattern.

(defn- near-miss-variants
  "Generate near-miss spellings of a slot keyword. Conservative — we
  only generate variants likely to appear from a refactor mistake
  (snake_case, dropped predicate `?`, dropped namespace). The list is
  curated so a docstring or unrelated symbol can't trip a false
  positive."
  [slot]
  (let [nm       (name slot)
        nm-no-q  (str/replace nm #"\?$" "")           ; strip predicate `?`
        nm-snake (str/replace nm #"-" "_")]           ; kebab→snake
    (cond-> []
      ;; snake_case form (e.g. :include_sensitive?)
      (str/includes? nm "-")
      (conj (str ":" nm-snake))

      ;; dropped predicate `?` (e.g. :include-sensitive)
      (str/ends-with? nm "?")
      (conj (str ":" nm-no-q))

      ;; quoted-string form sometimes appears in JSON shapes; we don't
      ;; want to forbid descriptor docstrings from mentioning the slot,
      ;; so we only watch for the snake_case quoted form.
      (str/includes? nm "-")
      (conj (str \" nm-snake \")))))

;; ---------------------------------------------------------------------------
;; Gate 1 — schema conformance. Every fixture validates against its
;; role's canonical schema.
;; ---------------------------------------------------------------------------

(deftest every-fixture-conforms-to-its-role-schema
  (doseq [{:keys [slot role]} canonical-slots
          fixture             (get role->fixtures role)]
    (testing (str "slot " slot " — role " role " — fixture " fixture)
      (let [schema (get role->schema role)]
        (is (m/validate schema fixture)
            (str "Fixture " fixture " for slot " slot
                 " failed role-schema validation. Role: " role))))))

;; ---------------------------------------------------------------------------
;; Gate 2 — source-text pin. The canonical slot literal appears in at
;; least one of each server's named source files.
;; ---------------------------------------------------------------------------

(defn- slot-literal
  "Render a slot keyword as the literal string that MUST appear in
  source — printed as `:name?` with `clojure.core/pr-str`."
  [slot]
  (pr-str slot))

(deftest canonical-slot-literal-appears-in-every-contracted-server
  (doseq [{:keys [slot servers sources]} canonical-slots
          server                         servers]
    (testing (str "slot " slot " literal in " server " sources")
      (let [literal (slot-literal slot)
            files   (get sources server)]
        (is (seq files)
            (str "No source files registered for server " server
                 " under slot " slot
                 " — extend `canonical-slots` :sources map."))
        (is (some (fn [rel]
                    (str/includes? (fx/read-source rel) literal))
                  files)
            (str "Slot literal " literal " missing from " server
                 " sources: " files
                 ".\nIf the slot moved to a new file, extend the "
                 ":sources entry for " server "."))))))

;; ---------------------------------------------------------------------------
;; Gate 3 — no near-miss variants. Defence-in-depth against typo-style
;; drift (snake_case, dropped predicate `?`).
;; ---------------------------------------------------------------------------

(deftest no-near-miss-variants-in-server-tool-sources
  (let [;; Only grep the SERVER SOURCE files (not the spec/docs files,
        ;; which prose-reference the slots in passing and may mention
        ;; alternative names in resolution notes).
        impl-sources-by-server
        {:pair2-mcp ["tools/pair2-mcp/src/re_frame_pair2_mcp/tools/sensitive.cljs"
                     "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors.cljs"
                     "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/descriptors_knobs.cljs"
                     "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/cap.cljs"
                     "tools/pair2-mcp/src/re_frame_pair2_mcp/tools/elision.cljs"]
         ;; story-mcp's `:include-sensitive?` parsing / schema lives in
         ;; `helpers.cljc` + `schemas.cljc` (no `sensitive.cljc` —
         ;; that's pair2-mcp's shape). The other entries below are the
         ;; full tools/ surface, covered for near-miss-variant defence.
         :story-mcp ["tools/story-mcp/src/re_frame/story_mcp/tools/cap.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/registry.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/helpers.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/schemas.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/dev.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/docs.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/testing.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/write.cljc"
                     "tools/story-mcp/src/re_frame/story_mcp/tools/recorder.cljc"]
         ;; causa-mcp's T-Insp cluster (rf2-8xzoe.14..22) shipped the
         ;; per-tool tree-walkers — the full surface gets near-miss-
         ;; variant coverage alongside pair2-mcp + story-mcp.
         :causa-mcp ["tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_app_db_diff.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_epoch_history.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_handlers.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_issues.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_list.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_machine_state.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_source_coord.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/get_trace_buffer.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/elision.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/privacy.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/token_cap.cljs"
                     "tools/causa-mcp/src/day8/re_frame2_causa_mcp/wire.cljs"]}]
    (doseq [{:keys [slot]}     canonical-slots
            variant            (near-miss-variants slot)
            [server files]     impl-sources-by-server
            rel                files
            :when              (seq files)]
      (testing (str server " — " rel " — near-miss " variant " for " slot)
        (let [src    (fx/read-source rel)
              pat    (fx/variant-regex variant)]
          (is (not (re-find pat src))
              (str "Found near-miss variant " variant " for slot " slot
                   " in " server "/" rel
                   ".\nThis is a slot-name drift bug. The canonical "
                   "form is " (slot-literal slot)
                   "; if " variant " is an intentional alias, document "
                   "it in the divergence pin section of this test.")))))))

;; ---------------------------------------------------------------------------
;; Gate 4 — divergence pin for `:include-large?` vs `:elision`. The
;; pin doesn't ASSERT anything different from gate 2 — it makes the
;; deliberate divergence visible to a future reviewer.
;;
;; If a third spelling appears on any server, the gate trips. If
;; causa-mcp's impl lands and pair2-mcp's rename happens in lockstep
;; (option A in the divergence-pin doc), the reviewer removes the
;; entry from `elision-arg-divergence` and the test confirms zero
;; divergence remains.
;; ---------------------------------------------------------------------------

(deftest elision-arg-divergence-stays-bounded
  ;; Assert the canonical-slot keyword from causa-mcp's spec appears
  ;; in causa-mcp's spec sources.
  (let [causa-entry (get-in elision-arg-divergence [:servers :causa-mcp])
        causa-slot  (:slot causa-entry)
        causa-files (:sources causa-entry)
        literal     (slot-literal causa-slot)]
    (is (some (fn [rel]
                (str/includes? (fx/read-source rel) literal))
              causa-files)
        (str "Canonical slot " literal " for elision opt-out missing "
             "from causa-mcp spec sources " causa-files
             ". Update `elision-arg-divergence` or the spec."))

    ;; Assert pair2-mcp's divergent spelling still lives where the pin
    ;; says it does — i.e. the divergence has NOT silently closed via
    ;; a rename the reviewer didn't notice.
    (let [pair2-entry (get-in elision-arg-divergence [:servers :pair2-mcp])
          pair2-slot  (:slot pair2-entry)
          pair2-files (:sources pair2-entry)
          pair2-lit   (slot-literal pair2-slot)]
      (is (some (fn [rel]
                  (str/includes? (fx/read-source rel) pair2-lit))
                pair2-files)
          (str "pair2-mcp's divergent spelling " pair2-lit
               " missing from " pair2-files
               ".\nIf the rename to " literal " landed, remove the "
               ":pair2-mcp entry from `elision-arg-divergence`."))))

  ;; Defence-in-depth: causa-mcp's spec sources MUST NOT use the
  ;; pair2-mcp divergent spelling — that would silently close the
  ;; divergence the wrong way (causa-mcp adopting pair2-mcp's name
  ;; without bumping the spec naming).
  (let [pair2-slot  (get-in elision-arg-divergence [:servers :pair2-mcp :slot])
        pair2-lit   (slot-literal pair2-slot)
        causa-files (get-in elision-arg-divergence [:servers :causa-mcp :sources])]
    (doseq [rel causa-files]
      (testing (str "causa-mcp source " rel " must not adopt pair2-mcp's "
                    pair2-lit)
        (is (not (str/includes? (fx/read-source rel) pair2-lit))
            (str "causa-mcp source " rel " now contains the pair2-mcp "
                 "divergent slot " pair2-lit
                 ". If the cross-MCP convention is moving from "
                 ":include-large? to " pair2-lit ", update "
                 "`canonical-slots` and `elision-arg-divergence`."))))))

;; ---------------------------------------------------------------------------
;; Gate 5 — mcp-base vocab ns is the single source of truth for the
;; canonical keywords. Grep the vocab.cljc source for each literal.
;; ---------------------------------------------------------------------------

(deftest mcp-base-vocab-pins-the-canonical-slot-keywords
  (let [src (fx/read-source mcp-base-vocab-source)]
    (doseq [literal mcp-base-vocab-literals]
      (testing (str "mcp-base/vocab.cljc pins literal " literal)
        (is (str/includes? src literal)
            (str "Literal " literal " missing from " mcp-base-vocab-source
                 ".\nThe vocab ns is the cross-MCP single source of "
                 "truth for slot keywords (Conventions §Reserved "
                 "namespaces). A rename here breaks every consumer "
                 "in lockstep — which is the right invariant; restore "
                 "the literal or update this test."))))))

;; ---------------------------------------------------------------------------
;; Gate 6 — causa-mcp impl-landed pin. The T-Insp tool cluster
;; (rf2-8xzoe.14..22) landed the per-tool tree-walkers under
;; `src/.../tools/`. The historical `causa-mcp-impl-still-absent`
;; tripwire fired correctly when impl landed; it's replaced here by
;; an impl-landed assertion so a future regression that DELETES the
;; tool directory (and reverts to spec-only stand-ins) trips the
;; gate. The per-tool `:sources` entries above are the load-bearing
;; coverage; this gate is the structural floor.
;; ---------------------------------------------------------------------------

(deftest causa-mcp-tools-directory-present
  (let [tools-dir (io/file fx/repo-root
                           "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools")]
    (is (.exists tools-dir)
        (str "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/ is "
             "missing. The T-Insp cluster (rf2-8xzoe.14..22) landed "
             "this directory; if it was intentionally removed, revert "
             "the `:causa-mcp` :sources entries on `canonical-slots` "
             "and `elision-arg-divergence` to spec-only stand-ins."))
    (is (seq (filter #(.isFile ^java.io.File %) (file-seq tools-dir)))
        (str "tools/causa-mcp/src/day8/re_frame2_causa_mcp/tools/ "
             "exists but contains no files. Same restoration path as "
             "the missing-directory case."))))

;; ---------------------------------------------------------------------------
;; Gate 7 — server-coverage sanity. Every server referenced in
;; `canonical-slots` and in `elision-arg-divergence` is one of the
;; three known servers. Typos surface here.
;; ---------------------------------------------------------------------------

(deftest server-references-are-all-known
  (doseq [{:keys [slot servers]} canonical-slots]
    (testing (str "slot " slot " — :servers values")
      (is (every? fx/known-servers servers)
          (str "Unknown server in :servers for " slot ": "
               (remove fx/known-servers servers)))))
  (doseq [server (keys (:servers elision-arg-divergence))]
    (testing (str "elision-arg-divergence — :servers key " server)
      (is (contains? fx/known-servers server)
          (str "Unknown server in elision-arg-divergence: " server)))))

;; ---------------------------------------------------------------------------
;; Gate 8 — slot uniqueness. No two `canonical-slots` rows share the
;; same `:slot` keyword. A duplicate row is a definition error.
;; ---------------------------------------------------------------------------

(deftest canonical-slot-keys-are-unique
  (let [slot-keys (map :slot canonical-slots)]
    (is (= (count slot-keys) (count (set slot-keys)))
        (str "Duplicate :slot keys in canonical-slots: "
             (->> slot-keys
                  frequencies
                  (filter (fn [[_ n]] (> n 1)))
                  (map first)
                  vec)))))
