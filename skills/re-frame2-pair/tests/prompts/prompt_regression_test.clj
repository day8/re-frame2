;;;; tests/prompts/prompt_regression_test.clj — catches silent drift in the
;;;; skill's recipes and in the contract claims its docs make (docs/TESTING.md
;;;; §3): each canonical prompt's recipe still exists and names its ops, and
;;;; the docs keep the claims an agent acts on.
;;;;
;;;; Run: bb tests/prompts/prompt_regression_test.clj

(ns prompt-regression-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def ^:private skill-root
  (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile .getParentFile))

(defn- doc [rel] (delay (slurp (io/file skill-root rel))))

(def ^:private recipes-md      (doc "references/recipes.md"))
(def ^:private ops-md          (doc "references/ops.md"))
(def ^:private screen-reads-md (doc "references/screen-reads.md"))
(def ^:private skill-md        (doc "SKILL.md"))
(def ^:private errors-md       (doc "references/errors.md"))
(def ^:private vocabulary-md   (doc "references/vocabulary.md"))
(def ^:private stories-md      (doc "references/stories.md"))
(def ^:private readme-md       (doc "README.md"))
(def ^:private capabilities-md (doc "docs/capabilities.md"))
(def ^:private local-dev-md    (doc "docs/LOCAL_DEV.md"))

(def ^:private manifest-text
  (delay (slurp (io/file skill-root ".." ".." "tools" "re-frame2-pair-mcp" "tool-descriptors.edn"))))

(def ^:private tool-count
  "The live Pair-MCP catalogue size, read from the generated descriptor manifest."
  (delay (let [m (re-find #":tool-count\s+(\d+)" @manifest-text)]
           (assert m "could not read :tool-count from tool-descriptors.edn")
           (Long/parseLong (second m)))))

(defn- has? [text needle]
  (boolean (if (string? needle) (str/includes? text needle) (re-find needle text))))

(defn- claims
  "Each row is [label text needle why]; the text must carry the needle (a
   string, or a regex)."
  [rows]
  (doseq [[label text needle why] rows]
    (is (has? text needle) (str label " must carry " (pr-str (str needle)) ": " why))))

(defn- section-from
  "The chunk of `md` from the `## ` heading containing `anchor` to the next
   `## ` heading, or \"\" when there is no such heading."
  [md anchor]
  (or (re-find (re-pattern (str "(?ms)## .*" (java.util.regex.Pattern/quote anchor) ".*?(?=^## |\\z)")) md)
      ""))

;; ---------------------------------------------------------------------------
;; The canonical prompts (docs/TESTING.md §3). `:must-mention` holds
;; alternations: the skill names one op several ways (MCP tool, runtime fn),
;; so a row fails only when EVERY spelling of an op is gone. `:leaf :stories`
;; reads references/stories.md instead of references/recipes.md.
;; ---------------------------------------------------------------------------

(def canonical-prompts
  [{:id :app-db-snapshot
    :prompt "What's in app-db under :user/profile?"
    :recipe-anchor "What's in `app-db`"
    :must-mention [["app-db/snapshot" "app-db/get" "snapshot"]]}

   {:id :trace-explain-dispatch
    :prompt "Trace `[:cart/apply-coupon \"SPRING25\"]`"
    :recipe-anchor "Explain this dispatch"
    :must-mention [["dispatch-and-collect" "trace/dispatch-and-collect"]
                   [":rf/epoch-record" "epoch-record"]
                   [":sub-runs"]
                   [":renders"]]}

   {:id :why-no-update
    :prompt "Why didn't the header update after `[:profile/save ...]`?"
    :recipe-anchor "Why didn't my view update"
    :must-mention [[":sub-runs"]
                   ["trace/last-epoch" "trace/last-pair-epoch" "last-epoch"]
                   ["equality" "cache-hit"]]}

   {:id :experiment-loop
    :prompt "Iterate on the cart handler until expired coupons are rejected"
    :recipe-anchor "Experiment loop"
    :must-mention [["dispatch-and-collect"]
                   ["restore-epoch"]
                   ["reg-event"]]}

   {:id :where-in-code
    :prompt "Where in the code does this button come from?"
    :recipe-anchor "Where in the code"
    :must-mention [["dom/source-at" "source-at"]
                   ["data--coord" "source-coord"]]}

   {:id :story-in-the-open-app
    :prompt "Run this variant in the app I have open"
    :recipe-anchor "Drive a Story variant"
    :leaf :stories
    :must-mention [["mcp__re-frame2-pair__eval-cljs"]
                   ["re-frame.story/run-variant"]
                   ["await"]
                   ["set-operating-frame"]]}])

(deftest canonical-prompts-still-mentioned
  (doseq [{:keys [id prompt recipe-anchor must-mention leaf]} canonical-prompts]
    (testing (str id " — " prompt)
      (let [[leaf-name md] (if (= :stories leaf) ["stories.md" @stories-md] ["recipes.md" @recipes-md])
            section        (section-from md recipe-anchor)]
        (is (seq section)
            (str leaf-name " has no `" recipe-anchor "` heading; update the recipe or this table together"))
        (doseq [alts must-mention]
          (is (some #(str/includes? section %) alts)
              (str "recipe " recipe-anchor " names none of " (pr-str alts))))))))

;; ---------------------------------------------------------------------------
;; Routing and setup prerequisites
;; ---------------------------------------------------------------------------

(deftest skill-routes-to-its-leaves-and-names-its-prerequisites
  (claims
   [["SKILL.md" @skill-md "references/recipes.md" "the router points at the recipe leaf"]
    ["SKILL.md" @skill-md "references/ops.md" "the router points at the op leaf"]
    ["SKILL.md" @skill-md "references/errors.md" "the router points at the error leaf"]
    ["SKILL.md" @skill-md "ops.md#hot-reload-coordination" "spec/design.md relies on this anchored read"]
    ["SKILL.md" @skill-md ":preloads" "§Setup installs the runtime as a devtools preload"]
    ["SKILL.md" @skill-md "re-frame2-pair.runtime" "§Setup names the preload namespace"]
    ["SKILL.md" @skill-md "day8/re-frame2-epoch"
     "without it discover-app still passes and every epoch read comes back empty"]
    ["references/errors.md" @errors-md ":rf.error/epoch-artefact-missing"
     "the one symptom of a missing epoch artefact that raises rather than reading empty"]
    ["references/errors.md" @errors-md "day8/re-frame2-epoch" "the artefact that fixes it"]
    ["docs/LOCAL_DEV.md" @local-dev-md "day8/re-frame2-epoch" "a cause of watch ops coming back empty"]
    ["references/errors.md" @errors-md ":runtime-not-preloaded" "the likeliest first-run failure"]]))

;; ---------------------------------------------------------------------------
;; Privacy: the guarantee covers the STRUCTURED MCP read tools, not raw
;; `eval-cljs`, which is default-ON and returns its value un-walked.
;; ---------------------------------------------------------------------------

(deftest privacy-guarantee-is-scoped-to-the-structured-tools
  (claims
   [["SKILL.md" @skill-md #"(?i)raw-eval carve-out" "the privacy bullet names the eval-cljs carve-out"]
    ["SKILL.md" @skill-md #"not governed by this gate|NOT governed by this gate"
     "eval-cljs is not governed by --allow-sensitive-reads"]
    ["SKILL.md" @skill-md "without running the elision walker" "eval-cljs returns its value un-elided"]
    ["references/vocabulary.md" @vocabulary-md "project-egress" "the one record-level egress boundary"]
    ["references/vocabulary.md" @vocabulary-md ":rf.epoch/sensitive?" "the epoch rollup stamp"]
    ["references/vocabulary.md" @vocabulary-md "raw-eval carve-out" "the carve-out section"]
    ["references/ops.md" @ops-md #"(?i)privacy carve-out" "the raw eval-cljs read rows document an un-elided path"]
    ["references/screen-reads.md" @screen-reads-md #"(?i)un-elided"
     "its raw eval-cljs DOM rows must state the carve-out locally, not by a second leaf"]
    ["references/screen-reads.md" @screen-reads-md #"(?i)eval-cljs" "the rows the local rule governs"]])
  (is (not (str/includes? @skill-md "Sensitive data does not cross the LLM boundary by default."))
      "SKILL.md must narrow the guarantee to the structured MCP reads, not state it as a blanket lede"))

;; ---------------------------------------------------------------------------
;; Recipe correctness
;; ---------------------------------------------------------------------------

;; `handler-meta {kind: "view"}` is keyed by the FIRST slot of the
;; `[<view-id-or-:rf.view/anonymous> <instance-token>]` render-key.
(deftest render-key-recipe-uses-first-tuple-slot
  (claims
   [["references/recipes.md" @recipes-md "first render-key" "the view id is the first render-key slot"]
    ["references/recipes.md" @recipes-md ":rf.view/anonymous" "the anonymous first slot falls back to read-ui's :source-coord"]
    ["docs/capabilities.md" @capabilities-md "first render-key" "it must agree with recipes.md"]]))

(deftest hot-reload-protocol-waits-on-a-pre-edit-probe
  (let [hr (section-from @ops-md "Hot-reload coordination")]
    (claims
     [["ops.md §Hot-reload" hr "tail-build" "the probe-based reload wait SKILL.md's cardinal rule points at"]
      ["ops.md §Hot-reload" hr ":probe-values" "the timeout diagnostic that tells a stuck probe from a compile error"]
      ["ops.md §Hot-reload" hr ":probe-errored" "a malformed probe is not a compile error"]
      ["ops.md §Hot-reload" hr #"(?i)does not tail|historical" "tail-build does not tail the shadow-cljs log"]
      ["ops.md §Hot-reload" hr #"(?i)capture[^.\n]{0,120}(pre-edit|before)" "capture the probe's value BEFORE the edit"]
      ["ops.md §Hot-reload" hr ":missing-baseline" "the refusal when no baseline is passed"]
      ["ops.md §Hot-reload" hr #"(?i)(before or after|lands? before)[^.\n]{0,80}(first (probe )?sample|first probe)"
       "a reload is recognised whether it lands before or after the first sample"]
      ["SKILL.md" @skill-md #"(?i)baseline" "the cardinal rule names the pre-edit baseline"]
      ["ops.md §Hot-reload" hr ":soft? false"
       "a probe-less wait returns {:ok? true :soft? true}, so the proceed-gate must require :soft? false"]
      ["ops.md §Hot-reload" hr #"(?i)(never evidence|not evidence|a delay, not)"
       "a probe-less wait is a delay, never evidence the reload landed"]])))

;; restore-epoch reinstalls the whole frame-state (both partitions); the
;; dedicated write tools are canonical and the raw eval forms the backstop.
(deftest restore-and-writes-are-taught-through-the-dedicated-tools
  (is (and (str/includes? @ops-md "frame-state") (re-find #"(?i)runtime-db" @ops-md))
      "ops.md's restore caveat must say restore rewinds frame-state, runtime-db included")
  (claims
   [["README.md" @readme-md "restore-epoch {epoch-id:" "the worked time-travel example calls the restore-epoch tool"]
    ["references/ops.md" @ops-md #"(?i)backstop" "the raw eval restore/reset forms stay documented as the backstop"]]))

;; `snapshot` reads only the plural `:frames` arg; a singular `frame` is
;; ignored and snapshots the operating frame twice — a false comparison.
(deftest snapshot-recipe-uses-plural-frames-not-singular-frame
  (let [section (section-from @stories-md "Diff two variants")]
    (is (str/includes? section "snapshot {frames:") "the variant-diff recipe must call snapshot {frames: [...]}")
    (is (not (re-find #"snapshot \{frame:" section)) "and never the ignored singular snapshot {frame: ...}")))

;; restore-epoch reinstalls the named epoch's `:frame-state-after`, so
;; restoring the baseline dispatch's OWN result epoch stacks the edited
;; handler on top of the baseline's mutation: a +1 baseline then a +2 edit
;; reads 3, not 2.
(deftest experiment-loop-rewinds-to-the-pre-dispatch-anchor
  (let [section (section-from @recipes-md "Experiment loop")]
    (is (str/includes? section "mcp__re-frame2-pair__restore-epoch {epoch-id: \"<pre-dispatch-epoch-id>\"}")
        "the restore step must pass the pre-dispatch anchor, not the baseline's result epoch")
    (let [anchor-at   (str/index-of section "pre-dispatch-epoch-id")
          baseline-at (str/index-of section "baseline-epoch-id")]
      (is (and anchor-at baseline-at (< anchor-at baseline-at))
          "the anchor must be captured BEFORE the baseline dispatch"))
    (is (str/includes? section ":frame-state-after") "the recipe states the mechanism, so the rule is not undone")
    (is (str/includes? section ":head-id") "reading the frame's :head-id IS the anchor capture")
    (is (and (str/includes? section "STOP") (str/includes? section "dispatch-dry-run"))
        "with no retained anchor the agent must STOP and fall back to dispatch-dry-run")
    (is (every? #(str/includes? section %) ["{:n 0}" "{:n 1}" "{:n 2}" "{:n 3}"])
        "the worked control walks {:n 0} -> {:n 1} -> {:n 2}, naming {:n 3} as the confounded reading")
    (is (str/includes? section "pre-edit-handler-meta")
        "the verification step compares against a separately captured pre-edit-handler-meta")))

;; `registrar-describe` strips the live handler fn and emits
;; `:handler-fn-hash`, so a bare `:handler-fn` is never on the wire, and the
;; hash is the only discriminator when an in-place edit leaves line/column alone.
(deftest experiment-loop-names-the-handler-fn-hash-wire-key
  (let [section (section-from @recipes-md "Experiment loop")]
    (is (not (re-find #":handler-fn(?!-hash)" section))
        "a bare :handler-fn compares nil with nil and reports the patch landed")
    (is (re-find #"(?is)strip[^.\n]{0,120}:handler-fn-hash" section)
        "the recipe must say the live fn is stripped and replaced by :handler-fn-hash")
    (is (re-find #"(?is):handler-fn-hash[^.\n]{0,120}(only discriminator|line[^.\n]{0,20}column unchanged)" section)
        "the recipe must say the hash discriminates when :line / :column are unchanged")))

;; `eval-cljs` analyses a form in shadow's default namespace, which has no
;; `rf` alias, so a published `form: "(rf/…)"` fails every copy-paste with
;; :rf.error/eval-cljs-compile-error. Prose citations of `rf/…` are fine; only
;; the inside of a published `form:` string is read. The class spans newlines
;; because a published form can wrap.
(def ^:private published-eval-forms
  (delay
    (vec (for [f    (sort-by #(.getPath %)
                             (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md"))
                                     (file-seq skill-root)))
               :let [rel (-> (.getPath f)
                             (subs (inc (count (.getPath skill-root))))
                             (str/replace (System/getProperty "file.separator") "/"))]
               m    (re-seq #"(?s)form:\s*\"([^\"]*)\"" (slurp f))]
           [rel (second m)]))))

(deftest published-eval-forms-are-fully-qualified
  (let [forms @published-eval-forms]
    (is (some (fn [[_ body]] (str/includes? body "(re-frame2-pair.runtime/")) forms)
        "control: the skill's commonest eval spelling must be found, or the extraction is reading nothing")
    (is (empty? (filterv (fn [[_ body]] (str/includes? body "(rf/")) forms))
        (str "published eval-cljs forms spelled (rf/…) fail to resolve; spell re-frame.core/… — offending leaves: "
             (pr-str (vec (distinct (map first (filter (fn [[_ body]] (str/includes? body "(rf/")) forms)))))))))

;; ---------------------------------------------------------------------------
;; Single host: Pair drives ONE attached browser runtime. story-mcp runs
;; variants in a headless JVM registry, so a variant it runs is not the frame
;; Pair reads. `scripts/check_skill_mcp_drift.py` pins the frontmatter; this
;; pins the prose.
;; ---------------------------------------------------------------------------

(deftest story-work-stays-on-the-attached-runtime
  (is (= [] (for [[label md] [["SKILL.md" skill-md] ["references/stories.md" stories-md]
                              ["references/recipes.md" recipes-md] ["references/ops.md" ops-md]
                              ["references/vocabulary.md" vocabulary-md]]
                  :when (str/includes? @md "mcp__re-frame2-story-mcp__")]
              label))
      "no live-session doc may grant or call an mcp__re-frame2-story-mcp__ tool; drive variants through eval-cljs")
  (is (str/includes? @skill-md "mcp__re-frame2-pair__")
      "control: the same check finds the prefix that IS present"))

(deftest story-leaf-carries-the-variant-as-frame-content
  (is (= [] (remove #(str/includes? @stories-md %)
                    ["variant-id IS the frame-id" "rf/make-frame" "reset-frame!" "destroy-frame!" "frame-diff"]))
      "references/stories.md is the one home for the variant-as-frame content")
  (is (str/includes? @skill-md "references/stories.md") "SKILL.md must route to references/stories.md"))

;; `re-frame.story/start-recording!` takes a variant id and
;; `gen-play-snippet` takes `[events opts]`; a zero-arg call throws before it
;; captures or generates anything.
(deftest stories-recorder-recipe-passes-the-required-arguments
  (doseq [sym ["start-recording!" "gen-play-snippet"]]
    (is (not (str/includes? @stories-md (str "(re-frame.story/" sym ")")))
        (str "references/stories.md publishes a zero-arg (re-frame.story/" sym ")")))
  (is (str/includes? @stories-md "(re-frame.story/start-recording! :story.")
      "start-recording! must be passed the variant id")
  (is (str/includes? @stories-md ":variant-id :story.")
      "gen-play-snippet must be given its required :variant-id opt"))

;; ---------------------------------------------------------------------------
;; Catalogue drift. check_skill_mcp_drift.py compares the allow-list with the
;; server's tool SET but never reads prose counts, so every doc that states
;; the count is anchored to the live manifest. Add a doc to `count-docs` when
;; it starts stating the count.
;; ---------------------------------------------------------------------------

(def ^:private count-docs
  [["README.md" readme-md]
   ["SKILL.md" skill-md]
   ["STATUS.md" (doc "STATUS.md")]
   ["references/mcp-transport.md" (doc "references/mcp-transport.md")]
   ["references/vocabulary.md" vocabulary-md]
   ["docs/capabilities.md" capabilities-md]
   ["docs/initial-spec.md" (doc "docs/initial-spec.md")]
   ["spec/inputs.md" (doc "spec/inputs.md")]
   ["spec/design.md" (doc "spec/design.md")]
   ["spec/authoring-prompt.md" (doc "spec/authoring-prompt.md")]])

(deftest catalogue-count-matches-live-manifest
  (let [live (str @tool-count)]
    (is (= [] (for [[label md] count-docs :when (not (str/includes? @md live))] label))
        (str "these docs do not state the live " live "-tool count from tool-descriptors.edn"))
    ;; Counts the catalogue has held (tool-descriptors.edn's history), matched
    ;; only in a tool framing so dates and line counts never trip it.
    (is (= [] (for [[label md] count-docs
                    stale ["26" "27" "28" "29" "30" "33" "35"]
                    :when (and (not= stale live)
                               (re-find (re-pattern (str "(?i)\\b" stale "[ -]tools?\\b|\\ball " stale "\\b")) @md))]
                [label stale]))
        (str "these docs carry a stale tool count; the live surface is " live " tools"))))

;; list-handlers' parser refuses `flow` and `frame` (they have their own
;; doors), so ops.md's kind list must equal the kinds the server publishes.
(deftest ops-supported-kinds-match-the-live-server-parser
  (let [kinds  (fn [text cut sep]
                 (let [i (str/index-of text "Supported kinds:")]
                   (assert i "no `Supported kinds:` list")
                   (->> (str/split (first (str/split (subs text (+ i (count "Supported kinds:"))) cut 2)) sep)
                        (map #(str/replace (str/trim %) "`" ""))
                        (remove str/blank?)
                        set)))
        server (kinds @manifest-text #"—" #",")
        skill  (kinds @ops-md #"\(the closed registrar set" #"/")]
    (is (seq server) "control: read at least one kind from the descriptor manifest")
    (is (= server skill)
        (str "ops.md's list-handlers kinds must match the server's. Only in ops.md: "
             (pr-str (sort (remove server skill))) "; only in the manifest: " (pr-str (sort (remove skill server)))))
    (is (str/includes? @ops-md "flows-snapshot") "ops.md must name the flow door where it declines the kind")))

(let [{:keys [fail error]} (run-tests 'prompt-regression-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
