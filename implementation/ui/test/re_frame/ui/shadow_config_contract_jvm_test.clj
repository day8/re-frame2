(ns re-frame.ui.shadow-config-contract-jvm-test
  "rf2-vxgfnd.196 — pin the re-frame.ui Shadow configuration the repository
  hands authors against the repository's own real wiring.

  ONE setting is the whole consumer contract (the old `:cache-blockers
  #{re-frame.ui}` tax was removed at the S6 cut-over, rf2-u53yy.1):

      :build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}

  plus a supported shadow-cljs version. This suite proves every holder of that
  rule still matches what the repository ACTUALLY configures — never a
  hand-copy free to drift.

  THE DESIGN CONSTRAINT, and the whole point of the bead: this namespace
  contains NO literal expected value. It never spells the hook form, never
  spells a version number. The expected value IS
  `implementation/shadow-cljs.edn`, read at run time. The only literals here are
  the KEY name being compared at (`:build-defaults`) and the markdown anchor —
  coordinates, not truth. A gate that restates the rule can only ever confirm
  its own copy of it (the rf2-5e3ic failure mode: gates that answered `true` for
  a 404 and for a path outside the repo).

  Three holders, compared as DATA:

    1. this repo's own build    implementation/shadow-cljs.edn        (top level)
    2. the consumer scaffold    examples/ui/minimal-counter/shadow-cljs.edn
    3. the setup skill          skills/re-frame2-setup/references/shadow-cljs.md

  The setup skill carries only the settings themselves, behind the
  `rf2:shadow-ui-contract` anchor — so it is pinned to the real build config
  rather than being a copy free to drift.

  THERE WAS A FOURTH (rf2-ote5u). `docs/core/how-to/install-re-frame-ui.md`
  published the same contract and a supported shadow-cljs version, and this
  suite compared it like the rest. The page is GONE: it advertised a
  `day8/re-frame2-ui` Maven coordinate that was ruled never-to-be-published
  (rf2-a32r7), and re-frame.ui is the donor being absorbed into Freehand. Its
  content is not homeless — the contract it restated lives in the two real
  configs and the skill above, all still compared here, and the shadow-cljs
  version it published lives in the two REAL pins (`implementation/package.json`
  and the scaffold's `deps.edn`), which are still compared to each other below.
  A published page was always a restatement; deleting it removes a copy, not a
  source. The Freehand install page (`docs/core/freehand/install.md`) is NOT its
  successor here — it configures a DIFFERENT hook
  (`re-frame.freehand.compiler.build-hook`) and belongs to its own contract.

  Each holder is a separate lever: mutate any ONE and this suite reds, naming
  which holder diverged.

  Shape assertions run BEFORE any comparison (the
  `frame-destroyed-op-schema-jvm-test` precedent): a moved anchor, a deleted
  fence, or an emptied setting THROWS with a diagnosis rather than silently
  comparing two nils and passing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Locating the repo — cwd-independent, no hardcoded depth
;; ---------------------------------------------------------------------------

;; The repo-root MARKER. A durable, always-present root file with no stake in
;; any artefact's fate — deliberately not a docs page and not a compared
;; holder, because locating the repo BY a holder means deleting that holder
;; errors every test in the suite rather than failing the one comparison that
;; actually lost its subject (rf2-ote5u: this locator used to walk for the
;; published how-to). Same marker and same walk as the sibling
;; `slice-memo-lifetime-census-jvm-test` in this directory.
(def ^:private root-marker-rel "AGENTS.md")
(def ^:private impl-config-rel "implementation/shadow-cljs.edn")
(def ^:private example-config-rel "examples/ui/minimal-counter/shadow-cljs.edn")
(def ^:private example-deps-rel "examples/ui/minimal-counter/deps.edn")
(def ^:private impl-package-rel "implementation/package.json")
(def ^:private skill-rel "skills/re-frame2-setup/references/shadow-cljs.md")

(defn- repo-root
  "Walk up from the working dir until a directory holds BOTH the repo-root
  marker and this repo's own shadow config. `clojure -M:test` runs from
  implementation/ui; CI and editors may start elsewhere.

  Both anchors are kept deliberately: the marker identifies the repository, and
  `implementation/shadow-cljs.edn` is the source of truth every comparison
  below reads, so a walk that found a root without it would defer a certain
  failure to a less obvious place."
  []
  (or (some (fn [dir]
              (let [d (.getCanonicalFile (io/file dir))]
                (when (and (.isFile (io/file d root-marker-rel))
                           (.isFile (io/file d impl-config-rel)))
                  d)))
            (take 8 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "rf2-vxgfnd.196: cannot locate the repo root"
                      {:looking-for [root-marker-rel impl-config-rel]
                       :cwd (System/getProperty "user.dir")}))))

(def ^:private root (delay (repo-root)))

(defn- slurp-rel [rel]
  (let [f (io/file @root rel)]
    (when-not (.isFile f)
      (throw (ex-info "rf2-vxgfnd.196: a compared holder is missing"
                      {:missing rel})))
    (slurp f)))

(defn- read-config
  "Read a shadow-cljs.edn / deps.edn as data. `:default` absorbs build-tool
  reader tags (`#shadow/env`) that appear deeper in this repo's config."
  [rel]
  (edn/read-string {:default (fn [_tag v] v)} (slurp-rel rel)))

;; ---------------------------------------------------------------------------
;; The prose holder — parsed out of the markdown at run time
;; ---------------------------------------------------------------------------

(defn- anchored-block
  "The body of the fenced ```<lang> block immediately following the HTML comment
  anchor `<!-- anchor -->` in `rel`. Throws when the anchor or its fence is
  gone, so moving either reds here instead of quietly yielding nil. `rel` is
  carried for the diagnosis only — it names the holder that lost its anchor."
  [rel markdown anchor lang]
  (let [pat  (re-pattern (str "(?s)<!--\\s*" (java.util.regex.Pattern/quote anchor)
                              "\\s*-->\\s*```" lang "\\r?\\n(.*?)```"))
        body (second (re-find pat markdown))]
    (when (str/blank? (str body))
      (throw (ex-info (str "rf2-vxgfnd.196: no ```" lang " block follows the "
                           anchor " anchor in " rel)
                      {:holder rel :anchor anchor})))
    body))

(defn- anchored-contract
  "The one-setting map a MARKDOWN holder carries behind the shared
  `rf2:shadow-ui-contract` anchor, as data. The reader is written against the
  ANCHOR rather than against one file, so a second prose holder enrols by
  carrying the anchor rather than by growing a reader of its own."
  [rel]
  (let [form (edn/read-string (anchored-block rel
                                              (slurp-rel rel)
                                              "rf2:shadow-ui-contract"
                                              "clojure"))]
    (when-not (map? form)
      (throw (ex-info "rf2-vxgfnd.196: an anchored contract block is not a map"
                      {:holder rel :read form})))
    form))

(defn- skill-contract
  "The one-setting map the re-frame2-setup skill hands authors, as data. The
  skill carries only the settings — this is what stops that block being a copy
  free to drift from the build config it claims to describe."
  []
  (anchored-contract skill-rel))

;; ---------------------------------------------------------------------------
;; The real holders
;; ---------------------------------------------------------------------------

(def ^:private contract-keys [:build-defaults])

(defn- top-level-contract
  "The contract slice of a real shadow-cljs.edn — the source of truth this gate
  compares the published page AGAINST."
  [rel]
  (select-keys (read-config rel) contract-keys))

(defn- deps-shadow-version [rel]
  (->> (get-in (read-config rel) [:aliases :shadow :extra-deps])
       (keep (fn [[sym coord]]
               (when (= "shadow-cljs" (name sym)) (:mvn/version coord))))
       first))

(defn- npm-shadow-version
  "The npm half's pin, read straight out of package.json. Regex rather than a
  JSON dependency: this artefact's test classpath carries no JSON reader, and
  the value is still DERIVED from the real file."
  [rel]
  (second (re-find #"\"shadow-cljs\"\s*:\s*\"([^\"]+)\"" (slurp-rel rel))))

;; ---------------------------------------------------------------------------
;; Shape guards — run before any comparison
;; ---------------------------------------------------------------------------

(deftest source-of-truth-is-non-vacuous
  (testing "this repo's own build actually carries the setting, non-empty"
    ;; Without this, deleting the setting from ALL holders would make every
    ;; comparison below `{} = {}` and the suite would pass on a repo that no
    ;; longer configures re-frame.ui at all.
    (let [real (top-level-contract impl-config-rel)]
      (is (= (set contract-keys) (set (keys real)))
          (str impl-config-rel " no longer carries the top-level build-hook "
               "setting; found " (sort (keys real))))
      (let [hooks (get-in real [:build-defaults :build-hooks])]
        (is (and (vector? hooks) (seq hooks))
            (str impl-config-rel " :build-defaults :build-hooks is not a non-empty vector")))))
  (testing "the removed :cache-blockers tax is gone from every real config (S6 cut-over, rf2-u53yy.1)"
    ;; The whole point of S6: the tax line must not creep back into any real
    ;; shadow-cljs.edn holder. (The skill's anchored contract block is pinned
    ;; one-setting by the comparison below.)
    (doseq [rel [impl-config-rel example-config-rel]]
      (is (not (contains? (read-config rel) :cache-blockers))
          (str rel " still carries the removed :cache-blockers install tax")))
    ;; And the anchored contract block itself must be exactly one setting.
    (is (not (contains? (skill-contract) :cache-blockers))
        (str skill-rel " anchored contract block still carries :cache-blockers"))))

(deftest prose-holder-is-parseable
  (testing "the setup skill's anchored block resolves to a block that reads as data"
    ;; anchored-block throws on a moved anchor; calling it here converts that
    ;; into a named failure rather than an error inside a comparison test —
    ;; delete the skill's anchor or its fence and the failure names the skill.
    (is (map? (skill-contract)))))

;; ---------------------------------------------------------------------------
;; The drift comparisons — one lever per holder
;; ---------------------------------------------------------------------------

(deftest setup-skill-matches-this-repos-own-build
  (testing "the setup skill hands authors exactly what a real build configures"
    ;; Compared against the CONFIG, which is the source of truth this suite
    ;; exists to defend, so mutating either the skill's block or the real build
    ;; reds here.
    (is (= (top-level-contract impl-config-rel) (skill-contract))
        (str "the anchored block in " skill-rel " has drifted from "
             impl-config-rel ". An author following the setup skill would not "
             "configure re-frame.ui the way this repo actually does — "
             "reconcile the skill against the real build config."))))

(deftest the-two-real-configs-agree
  (testing "this repo and the scaffold configure re-frame.ui identically"
    ;; A consumer copying the runnable scaffold must get what this repo itself
    ;; builds with. Asserted directly so a failure names the two configs.
    (is (= (top-level-contract impl-config-rel)
           (top-level-contract example-config-rel))
        (str impl-config-rel " and " example-config-rel
             " no longer configure re-frame.ui the same way."))))

;; ---------------------------------------------------------------------------
;; Version posture — a concrete pin within the supported, tested range
;; 3.4.0–3.4.11 (rf2-u53yy.1 S6). The npm half and the scaffold's Clojure half
;; must pin the SAME shadow-cljs — lockstep across the two real holders.
;;
;; This compared a third value until rf2-ote5u: the version the published
;; how-to advertised. That page is gone, and with it the only holder that was
;; a restatement rather than a build input. What remains is the comparison
;; that could always break a real build — the npm CLI this repo runs and the
;; Clojure coordinate the scaffold resolves are the same shadow-cljs, or a
;; consumer following the scaffold builds against a different one than we test.
;; ---------------------------------------------------------------------------

(deftest the-two-real-pins-agree
  (testing "the npm half and the scaffold's Clojure half pin the same shadow-cljs"
    (let [npm     (npm-shadow-version impl-package-rel)
          example (deps-shadow-version example-deps-rel)]
      ;; Non-vacuity first: two blank reads would compare equal and pass on a
      ;; repo that had lost both pins.
      (is (not (str/blank? (str npm)))
          (str "no shadow-cljs pin found in " impl-package-rel))
      (is (not (str/blank? (str example)))
          (str "no thheller/shadow-cljs coordinate found in " example-deps-rel))
      (is (= npm example)
          (str "the shadow-cljs pin in " impl-package-rel " (" npm ") has "
               "drifted from " example-deps-rel " (" example "). A consumer "
               "following the scaffold would build against a different "
               "shadow-cljs than this repo tests with.")))))
