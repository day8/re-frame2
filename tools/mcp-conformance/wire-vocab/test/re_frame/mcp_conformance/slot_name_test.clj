(ns re-frame.mcp-conformance.slot-name-test
  "Cross-MCP `tools/call` input-slot vocabulary.

  An agent learns `:include-sensitive` and `:max-tokens` once and uses
  them on every server, so this gate pins:

  1. each slot literal in every contracted server's descriptor and
     arg-parsing source. The wire key carries no trailing `?`, which
     Anthropic's tool-input-schema regex `^[a-zA-Z0-9_.-]{1,64}$`
     rejects;
  2. no near-miss spelling (snake_case, an added or dropped `?`) in any
     server's tool source;
  3. the namespaced walker-opt keywords as code constants in
     `mcp-base/vocab.cljc`."
  (:require [clojure.string  :as str]
            [clojure.test    :refer [deftest is testing]]
            [re-frame.mcp-conformance.fixtures :as rf.mcp-conformance.fixtures]))

;; Each slot's literal MUST appear in at least one of `:sources` for every
;; server in `:servers`.
(def ^:private canonical-slots
  [{:slot     :include-sensitive
    :servers  #{:re-frame2-pair-mcp :story-mcp}
    :sources  {:re-frame2-pair-mcp ["tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/sensitive.cljs"
                           "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors.cljs"]
               ;; story-mcp's `:include-sensitive` parsing lives in
               ;; `args.cljc` (the cross-MCP `args/parse-boolean` reader)
               ;; and the slot schema lives in
               ;; `schemas.cljc` (`include-sensitive-schema` injector).
               ;; No `sensitive.cljc` (that is the re-frame2-pair-mcp
               ;; shape, which doesn't apply to story-mcp).
               :story-mcp ["tools/story-mcp/src/re_frame/story_mcp/tools/args.cljc"
                           "tools/story-mcp/src/re_frame/story_mcp/tools/schemas.cljc"]}}

   {:slot     :max-tokens
    :servers  #{:re-frame2-pair-mcp :story-mcp}
    :sources  {;; re-frame2-pair-mcp surfaces the slot as the Clojure keyword
               ;; `:max-tokens` in the descriptor-splice helper
               ;; (`descriptors.cljs/with-budget-knob`) and reads the
               ;; JS-side `"max-tokens"` string off the args object in
               ;; `cap.cljs/max-tokens-arg`. The keyword form is the
               ;; canonical literal — the descriptor splice IS what
               ;; pins the wire-side name agents see in `tools/list`.
               :re-frame2-pair-mcp ["tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors.cljs"
                           "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_knobs.cljs"]
               :story-mcp ["tools/story-mcp/src/re_frame/story_mcp/tools/schemas.cljc"
                           "tools/story-mcp/src/re_frame/story_mcp/tools/wire_pipeline.cljc"]}}])

(def ^:private mcp-base-vocab-source
  "tools/mcp-base/src/re_frame/mcp_base/vocab.cljc")

(def ^:private mcp-base-vocab-literals
  "The namespaced framework walker-opt keys, which vocab.cljc defines as
  constants. The unqualified wire key `:include-sensitive` is a per-server
  surface key, pinned by `canonical-slot-literal-appears-in-every-contracted-server`."
  #{":rf.egress/include-large?"
    ":rf.egress/include-sensitive?"})

(defn- near-miss-variants
  "Near-miss spellings of a slot keyword a refactor plausibly introduces:
  snake_case, and an added or dropped trailing `?`."
  [slot]
  (let [nm       (name slot)
        nm-no-q  (str/replace nm #"\?$" "")           ; strip predicate `?`
        nm-snake (str/replace nm #"-" "_")]           ; kebab→snake
    (cond-> []
      ;; snake_case form (e.g. :include_sensitive?)
      (str/includes? nm "-")
      (conj (str ":" nm-snake))

      ;; dropped predicate `?` (e.g. :include-sensitive) — fires when
      ;; the canonical form ends in `?`.
      (str/ends-with? nm "?")
      (conj (str ":" nm-no-q))

      ;; added predicate `?` — fires when the canonical
      ;; form does NOT end in `?`. Pins the decision: the
      ;; wire-key must NEVER carry `?` because Anthropic's regex
      ;; rejects it.
      (not (str/ends-with? nm "?"))
      (conj (str ":" nm "?"))

      ;; quoted-string form sometimes appears in JSON shapes; we don't
      ;; want to forbid descriptor docstrings from mentioning the slot,
      ;; so we only watch for the snake_case quoted form.
      (str/includes? nm "-")
      (conj (str \" nm-snake \")))))

(defn- slot-literal
  "Render a slot keyword as the literal string that MUST appear in
  source — printed as `:name` with `clojure.core/pr-str`."
  [slot]
  (pr-str slot))

;; Matched as a whole keyword token: `:include-sensitive` is a prefix of the
;; drifted `:include-sensitive?`, so a substring match would pass on it.
(deftest canonical-slot-literal-appears-in-every-contracted-server
  (doseq [{:keys [slot servers sources]} canonical-slots
          server                         servers]
    (testing (str "slot " slot " literal in " server " sources")
      (let [literal (slot-literal slot)
            pat     (rf.mcp-conformance.fixtures/variant-regex literal)
            files   (get sources server)]
        (is (some (fn [rel]
                    (re-find pat (rf.mcp-conformance.fixtures/read-source rel)))
                  files)
            (str "Slot literal " literal " missing from " server
                 " sources: " files
                 " (full-token match — a substring match does not count)."
                 "\nIf the slot moved to a new file, extend the "
                 ":sources entry for " server "."))))))

;; Server source only: the specs mention alternative spellings in prose.
(deftest no-near-miss-variants-in-server-tool-sources
  (let [impl-sources-by-server
        {:re-frame2-pair-mcp ["tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/sensitive.cljs"
                     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors.cljs"
                     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_knobs.cljs"
                     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/cap.cljs"
                     "tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/elision.cljs"]
         :story-mcp rf.mcp-conformance.fixtures/story-mcp-tool-source-files}]
    (doseq [{:keys [slot]}     canonical-slots
            variant            (near-miss-variants slot)
            [server files]     impl-sources-by-server
            rel                files
            :when              (seq files)]
      (testing (str server " — " rel " — near-miss " variant " for " slot)
        (let [src    (rf.mcp-conformance.fixtures/read-source rel)
              pat    (rf.mcp-conformance.fixtures/variant-regex variant)]
          (is (not (re-find pat src))
              (str "Found near-miss variant " variant " for slot " slot
                   " in " server "/" rel
                   ".\nThis is a slot-name drift bug. The canonical "
                   "form is " (slot-literal slot)
                   "; if " variant " is an intentional alias, document "
                   "it in the divergence pin section of this test.")))))))

;; Stripped of comments and strings, because each keyword is also named in
;; a docstring that would survive the constant's removal.
(deftest mcp-base-vocab-pins-the-canonical-slot-keywords
  (let [src      (rf.mcp-conformance.fixtures/read-source mcp-base-vocab-source)
        stripped (rf.mcp-conformance.fixtures/strip-comments-and-strings src)]
    (doseq [literal mcp-base-vocab-literals]
      (testing (str "mcp-base/vocab.cljc pins literal " literal " as a code constant")
        (is (str/includes? stripped literal)
            (str "Literal " literal " missing from the CODE of " mcp-base-vocab-source
                 " (present only in a docstring does not count).\nThe vocab ns "
                 "is the cross-MCP single source of truth for slot keywords "
                 "(Conventions §Reserved namespaces). A rename here breaks "
                 "every consumer in lockstep — which is the right invariant; "
                 "restore the constant or update this test."))))))
