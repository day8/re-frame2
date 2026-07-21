(ns re-frame.ui.shadow-config-contract-jvm-test
  "rf2-vxgfnd.196 — pin the PUBLISHED re-frame.ui Shadow configuration against
  the repository's own real wiring.

  `docs/core/how-to/install-re-frame-ui.md` ships ONE setting as a consumer
  contract (the old `:cache-blockers #{re-frame.ui}` tax was removed at the S6
  cut-over, rf2-u53yy.1):

      :build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}

  and a supported shadow-cljs version. This suite proves the published text
  still matches what the repository ACTUALLY configures — never a fourth
  hand-copy of the rule.

  THE DESIGN CONSTRAINT, and the whole point of the bead: this namespace
  contains NO literal expected value. It never spells the hook form, never
  spells a version number. The expected value IS
  `implementation/shadow-cljs.edn`, read at run time. The only literals here are
  the KEY name being compared at (`:build-defaults`) and the markdown anchors —
  coordinates, not truth. A gate that restates the rule can only ever confirm
  its own copy of it (the rf2-5e3ic failure mode: gates that answered `true` for
  a 404 and for a path outside the repo).

  Four holders, compared as DATA:

    1. the published page       docs/core/how-to/install-re-frame-ui.md
    2. this repo's own build    implementation/shadow-cljs.edn        (top level)
    3. the consumer scaffold    examples/ui/minimal-counter/shadow-cljs.edn
    4. the setup skill          skills/re-frame2-setup/references/shadow-cljs.md

  Holder 4 arrives with rf2-ftb3s. The setup skill points authors at the
  published page for the reasoning and carries only the settings themselves,
  behind the same `rf2:shadow-ui-contract` anchor — so the skill is pinned to
  the real build config rather than becoming a fourth copy free to drift.

  Each is a separate lever: mutate any ONE of the three and this suite reds,
  naming which holder diverged.

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

(def ^:private page-rel "docs/core/how-to/install-re-frame-ui.md")
(def ^:private impl-config-rel "implementation/shadow-cljs.edn")
(def ^:private example-config-rel "examples/ui/minimal-counter/shadow-cljs.edn")
(def ^:private example-deps-rel "examples/ui/minimal-counter/deps.edn")
(def ^:private impl-package-rel "implementation/package.json")
(def ^:private skill-rel "skills/re-frame2-setup/references/shadow-cljs.md")

(defn- repo-root
  "Walk up from the working dir until a directory holds BOTH the published page
  and this repo's own shadow config. `clojure -M:test` runs from
  implementation/ui; CI and editors may start elsewhere."
  []
  (or (some (fn [dir]
              (let [d (.getCanonicalFile (io/file dir))]
                (when (and (.isFile (io/file d page-rel))
                           (.isFile (io/file d impl-config-rel)))
                  d)))
            (take 8 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "rf2-vxgfnd.196: cannot locate the repo root"
                      {:looking-for [page-rel impl-config-rel]
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
;; The published page — parsed out of the markdown at run time
;; ---------------------------------------------------------------------------

(defn- anchored-block
  "The body of the fenced ```<lang> block immediately following the HTML comment
  anchor `<!-- anchor -->`. Throws when the anchor or its fence is gone, so
  moving either reds here instead of quietly yielding nil."
  [markdown anchor lang]
  (let [pat  (re-pattern (str "(?s)<!--\\s*" (java.util.regex.Pattern/quote anchor)
                              "\\s*-->\\s*```" lang "\\r?\\n(.*?)```"))
        body (second (re-find pat markdown))]
    (when (str/blank? (str body))
      (throw (ex-info (str "rf2-vxgfnd.196: no ```" lang " block follows the "
                           anchor " anchor in the published page")
                      {:page page-rel :anchor anchor})))
    body))

(defn- anchored-contract
  "The two-setting map a MARKDOWN holder publishes behind the shared
  `rf2:shadow-ui-contract` anchor, as data. Every prose holder — the published
  page, the setup skill — carries the settings under the same anchor, so one
  reader serves them all."
  [rel]
  (let [form (edn/read-string (anchored-block (slurp-rel rel)
                                              "rf2:shadow-ui-contract"
                                              "clojure"))]
    (when-not (map? form)
      (throw (ex-info "rf2-vxgfnd.196: an anchored contract block is not a map"
                      {:holder rel :read form})))
    form))

(defn- published-contract
  "The two-setting map the published how-to page publishes, as data."
  []
  (anchored-contract page-rel))

(defn- skill-contract
  "The two-setting map the re-frame2-setup skill hands authors, as data. The
  skill points at the published page for the reasoning and carries only the
  settings — this is what stops that block being a fourth free-drifting copy."
  []
  (anchored-contract skill-rel))

(defn- published-shadow-version
  "The shadow-cljs version the page publishes, dug out of the `:shadow` alias it
  shows. Reads the coordinate the page actually prints — no version literal
  lives in this file."
  []
  (let [form (edn/read-string (anchored-block (slurp-rel page-rel)
                                              "rf2:shadow-ui-version"
                                              "clojure"))
        v    (->> (get-in form [:aliases :shadow :extra-deps])
                  (keep (fn [[sym coord]]
                          (when (= "shadow-cljs" (name sym)) (:mvn/version coord))))
                  first)]
    (when (str/blank? (str v))
      (throw (ex-info "rf2-vxgfnd.196: the published version block carries no shadow-cljs coordinate"
                      {:read form})))
    v))

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
    ;; shadow-cljs.edn holder. (The anchored contract blocks in the page and the
    ;; skill are pinned one-setting by the comparisons below; the page's prose
    ;; may still discuss the removal.)
    (doseq [rel [impl-config-rel example-config-rel]]
      (is (not (contains? (read-config rel) :cache-blockers))
          (str rel " still carries the removed :cache-blockers install tax")))
    ;; And the anchored contract blocks themselves must be exactly one setting.
    (is (not (contains? (published-contract) :cache-blockers))
        (str page-rel " anchored contract block still carries :cache-blockers"))
    (is (not (contains? (skill-contract) :cache-blockers))
        (str skill-rel " anchored contract block still carries :cache-blockers"))))

(deftest published-page-is-parseable
  (testing "both anchors resolve to blocks that read as data"
    ;; anchored-block / published-* throw on a moved anchor; calling them here
    ;; converts that into a named failure rather than an error inside a
    ;; comparison test.
    (is (map? (published-contract)))
    (is (string? (published-shadow-version))))
  (testing "the setup skill's anchored block resolves too"
    ;; Same conversion for holder 4: delete the skill's anchor or its fence and
    ;; the failure names the skill, rather than surfacing inside the comparison.
    (is (map? (skill-contract)))))

;; ---------------------------------------------------------------------------
;; The drift comparisons — three levers, one per holder
;; ---------------------------------------------------------------------------

(deftest published-contract-matches-this-repos-own-build
  (testing "the page publishes exactly what implementation/shadow-cljs.edn configures"
    (is (= (top-level-contract impl-config-rel) (published-contract))
        (str "the published snippet in " page-rel " has drifted from "
             impl-config-rel ". Reconcile the page against the real build "
             "config — the config is the source of truth."))))

(deftest published-contract-matches-the-consumer-scaffold
  (testing "the runnable example carries exactly what the page tells consumers to write"
    (is (= (top-level-contract example-config-rel) (published-contract))
        (str "the published snippet in " page-rel " has drifted from "
             example-config-rel ". A consumer copying the page would not get "
             "the scaffold's configuration."))))

(deftest setup-skill-matches-this-repos-own-build
  (testing "the setup skill hands authors exactly what a real build configures"
    ;; Compared against the CONFIG rather than the page, deliberately: the
    ;; config is the source of truth this suite exists to defend, so mutating
    ;; either the skill's block or the real build reds here. A skill compared
    ;; only against the page would agree with a page that had itself drifted.
    (is (= (top-level-contract impl-config-rel) (skill-contract))
        (str "the anchored block in " skill-rel " has drifted from "
             impl-config-rel ". An author following the setup skill would not "
             "configure re-frame.ui the way this repo actually does — "
             "reconcile the skill against the real build config, and see "
             page-rel " for the published reasoning."))))

(deftest the-two-real-configs-agree
  (testing "this repo and the scaffold configure re-frame.ui identically"
    ;; Transitively implied by the two tests above, but asserted directly so a
    ;; failure names the two configs rather than blaming the page.
    (is (= (top-level-contract impl-config-rel)
           (top-level-contract example-config-rel))
        (str impl-config-rel " and " example-config-rel
             " no longer configure re-frame.ui the same way."))))

;; ---------------------------------------------------------------------------
;; Version posture — a concrete pin (3.4.10) within the supported, tested range
;; 3.4.0–3.4.11 (rf2-u53yy.1 S6). The pin published on the page must still equal
;; the pins the repo and the scaffold actually build with — lockstep.
;; ---------------------------------------------------------------------------

(deftest published-version-matches-every-pin
  (testing "the supported version on the page is the version actually pinned"
    (let [published (published-shadow-version)
          npm       (npm-shadow-version impl-package-rel)
          example   (deps-shadow-version example-deps-rel)]
      (is (not (str/blank? (str npm)))
          (str "no shadow-cljs pin found in " impl-package-rel))
      (is (not (str/blank? (str example)))
          (str "no thheller/shadow-cljs coordinate found in " example-deps-rel))
      (is (= published npm)
          (str "the page's supported shadow-cljs version (" published ") has "
               "drifted from " impl-package-rel " (" npm ")"))
      (is (= published example)
          (str "the page's supported shadow-cljs version (" published ") has "
               "drifted from " example-deps-rel " (" example ")")))))
