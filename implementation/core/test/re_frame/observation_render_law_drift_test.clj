(ns re-frame.observation-render-law-drift-test
  "rf2-vxgfnd.167 — the REPO-WIDE render-law drift gate.

  The retired per-epoch render law (`rf2-vxgfnd.66` / PR #5790) falsely
  equated an event/derivation EPOCH with a UI notification, component
  render, or React commit — the shape that lets a reader infer that every
  epoch closes a UI batch (`epoch-close notify` → React work). The shipped
  scheduler does not work that way: an epoch is a WRITE / EVIDENCE unit; the
  owner-notification's `mark-dirty` schedules a render/commit that flushes at
  a later pending host checkpoint (coalesced across a batch), decoupled from
  epoch count (the host-checkpoint contract itself is `rf2-vxgfnd.166`).

  ## Why this gate is repo-wide, and why the roster comes from git

  #5790's sweep missed the residue because it reasoned about DIRECTORIES: it
  treated `ai/` as wholly gitignored and therefore out of scope. That premise
  was false — `git ls-files ai/` lists force-tracked files, including the
  24-file `new-substrate-codex` design manual that presents itself as
  normative. A directory-walking census repeats exactly that mistake, and the
  earlier version of THIS gate had the narrower form of the same flaw: it read
  one hardcoded `io/resource` path, so it stayed green while the named residue
  stood everywhere else.

  So the census is `git ls-files` — the tracked-file set itself, which by
  construction includes force-tracked files beneath ignored directories. It
  cannot silently shrink: an empty or implausibly small roster FAILS rather
  than passing vacuously, and every allowlist entry must still exist and still
  match, so a stale exemption reddens instead of rotting.

  JVM-only (`_test.clj`) by design — it reads SOURCE TEXT, so no CLJS runtime
  is involved (the `no-rf-default-floor-lint` / `doc-metadata-prod-elision`
  gates use the same JVM-source-text idiom)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; The census — tracked files, from git, never from a directory walk.
;; ---------------------------------------------------------------------------

(defn- repo-root
  "Walk up from the test's working directory to the repository root. `.git` is
  a DIRECTORY in a normal clone and a FILE in a worktree, so test for either."
  []
  (loop [dir (.getAbsoluteFile (io/file (System/getProperty "user.dir")))]
    (cond
      (nil? dir)                        nil
      (.exists (io/file dir ".git"))    dir
      :else                             (recur (.getParentFile dir)))))

(def ^:private scanned-extensions
  "Prose-bearing tracked surfaces: docs/specs and the source families whose
  docstrings and comments carry contract prose. Deliberately EXCLUDES
  `.beads/issues.jsonl` and other data files — a bead body legitimately quotes
  the retired law in order to describe and retire it."
  #{".md" ".clj" ".cljc" ".cljs"})

(defn- tracked-files
  "Every TRACKED path in the repository, via `git ls-files -z`. Throws rather
  than returning a short list if git is unavailable or errors, so this gate can
  never degrade into a vacuous pass."
  [^java.io.File root]
  (let [{:keys [exit out err]} (shell/with-sh-dir root
                                 (shell/sh "git" "ls-files" "-z"))]
    (when-not (zero? exit)
      (throw (ex-info "git ls-files failed — the drift census cannot be built"
                      {:exit exit :err err})))
    (->> (str/split out #"\x00")
         (remove str/blank?)
         vec)))

;; ---------------------------------------------------------------------------
;; Forbidden: the retired per-epoch UI render/notify law.
;; ---------------------------------------------------------------------------
;;
;; These scan RAW text (docstrings + comments included, NOT stripped): the
;; retired law is taught in PROSE, so prose is exactly what must stay clean.

(def ^:private forbidden-render-law-res
  "The retired-per-epoch-render-law shapes. `epoch-close` (or `epoch close`)
  adjacent to UI-scheduling verbs is the exact #5790-missed residue; the
  `<ui-verb> … per … epoch` shape catches the sibling `one notification /
  render per epoch` / `rendered once per epoch` / `React work per input
  epoch` phrasings. `commit` is deliberately EXCLUDED from the per-epoch arm
  because the core spine's derivation-epoch cache/commit law is legitimate
  per-epoch terminology (bead: must not be blindly replaced); the
  `epoch-close` arm still catches an `epoch-close … commit` UI claim.

  Note that HYPHENATED `per-epoch` never matches: the second arm requires
  `per` followed by whitespace. That is deliberate — `per-epoch` storage,
  caches, evidence and projections are legitimate and must survive."
  [;; `epoch-close notify` / `epoch close notification` / `epoch-close
   ;; render|commit|react|batch` / `epoch close triggers|causes|fires` —
   ;; the equation that an epoch's CLOSE drives UI work.
   #"(?i)epoch[-\s]close\s+(?:notif|render|commit|react|batch|trigger|cause|fire)"
   ;; A UI verb claimed to happen once per epoch: `render/react/notification
   ;; per epoch`, `rendered once per (input) epoch`, `notification per
   ;; re-frame2 epoch`.
   #"(?i)(?:notif\w*|render\w*|react\w*)\s+(?:work\s+)?(?:once\s+)?per\s+(?:\S+\s+){0,2}epoch"])

(def ^:private allowlist
  "Tracked paths permitted to carry a matching line, each with the reason it is
  NOT the retired law. Every entry is verified below to still exist AND still
  match — a stale exemption is itself a failure, so this map cannot quietly
  accumulate dead weight or paper over a fixed file."
  {"implementation/core/test/re_frame/observation_render_law_drift_test.clj"
   "this gate: its own docstring and patterns name the retired law in order to forbid it"

   "implementation/core/src/re_frame/substrate/spine.cljs"
   (str "the core spine's own glitch-free derivation law — a multi-input derived "
        "value notifies once per coherent input epoch. That is a DERIVATION-layer "
        "statement about recompute coherence, not a claim about UI notification, "
        "render, or React commit counts, and the bead explicitly preserves it")})

(defn- offending-lines
  "`[line-no line]` pairs of `content` carrying a retired render-law claim."
  [content]
  (->> (str/split-lines content)
       (map-indexed (fn [i line] [(inc i) line]))
       (keep (fn [[n line]]
               (when (some #(re-find % line) forbidden-render-law-res)
                 [n (str/trim line)])))))

(defn- scannable?
  [path]
  (some #(str/ends-with? path %) scanned-extensions))

(defn- census
  "Scan every tracked, prose-bearing file. Returns
  `{:scanned n :chars n :hits {path [[line-no line] …]}}`.

  `:chars` and NOT `:bytes` (rf2-2rtt6.135): `slurp` hands back a DECODED
  `String`, so `(count content)` is UTF-16 code units — which is not the file's
  size on disk for any file carrying a non-ASCII character, and this corpus is
  full of em-dashes. The figure is a pure anti-vacuity magnitude check (\"did we
  actually read the corpus, or silently census nothing?\"), a same-vs-same
  comparison against a floor, so the honest fix is to say what it counts rather
  than to re-encode 500+ files' content to satisfy a name."
  []
  (let [root  (repo-root)
        _     (assert root "repository root not found — the drift census cannot be built")
        paths (filterv scannable? (tracked-files root))]
    (reduce (fn [acc path]
              (let [f (io/file root path)]
                (if-not (.isFile f)
                  ;; tracked but absent from the working tree (sparse checkout
                  ;; or a mid-operation state) — count it, do not silently skip
                  (update acc :missing conj path)
                  (let [content (slurp f)
                        hits    (offending-lines content)]
                    (cond-> (-> acc
                                (update :scanned inc)
                                (update :chars + (count content)))
                      (seq hits) (assoc-in [:hits path] hits))))))
            {:scanned 0 :chars 0 :hits {} :missing []}
            paths)))

(def ^:private census-result (delay (census)))

;; ---------------------------------------------------------------------------
;; Anti-vacuity: the census must actually have looked at the repository.
;; ---------------------------------------------------------------------------

(deftest drift-census-is-not-vacuous
  (testing "rf2-vxgfnd.167: the roster comes from `git ls-files` and is real —
            a census that silently shrank to nothing must FAIL, not pass"
    (let [{:keys [scanned chars missing]} @census-result]
      (is (> scanned 500)
          (str "the tracked prose census collapsed to " scanned " files; this "
               "gate is only meaningful over the whole tracked corpus"))
      (is (> chars 1000000)
          (str "the census read only " chars " characters — it is not actually "
               "reading file contents"))
      (is (empty? missing)
          (str "tracked paths absent from the working tree: " (pr-str missing))))))

(deftest drift-patterns-actually-detect-the-retired-law
  (testing "rf2-vxgfnd.167: the matcher itself works — a seeded claim in each
            retired shape is detected, and the legitimate hyphenated
            `per-epoch` storage terminology is NOT"
    (doseq [seeded ["the epoch-close notify drives React work"
                    "epoch close notification advances the cell"
                    "one notification per epoch"
                    "view :v rendered once per epoch"
                    ;; NB `commit` is deliberately absent from the per-epoch arm
                    ;; (the spine's derivation-epoch commit law is legitimate),
                    ;; so a bare "commit per input epoch" is NOT seeded here —
                    ;; the epoch-close arm above covers the UI-commit claim.
                    "one React render per input epoch"
                    "one ViewCell notification per framework epoch"]]
      (is (seq (offending-lines seeded))
          (str "the drift patterns FAILED to detect a seeded retired claim: "
               (pr-str seeded))))
    (doseq [legit ["the per-epoch evidence store keys by frame-epoch"
                   "EPOCH CLOSE — the Xray lifecycle row"
                   "one derivation-cache entry per-epoch"
                   "the commit-epoch law is per-epoch and legitimate"]]
      (is (empty? (offending-lines legit))
          (str "the drift patterns wrongly flagged LEGITIMATE per-epoch "
               "terminology: " (pr-str legit))))))

;; ---------------------------------------------------------------------------
;; The gate itself.
;; ---------------------------------------------------------------------------

(deftest no-tracked-file-teaches-the-retired-per-epoch-render-law
  (testing "rf2-vxgfnd.167: no tracked file equates an epoch with a UI
            notification / render / React commit — no `epoch-close notify`, no
            `render/react/notification per epoch`. An epoch is a write/evidence
            unit; `mark-dirty` schedules the render/commit at a later host
            checkpoint (rf2-vxgfnd.166), decoupled from epoch count."
    (let [offenders (apply dissoc (:hits @census-result) (keys allowlist))]
      (is (empty? offenders)
          (str "Tracked files teach the RETIRED per-epoch render law. An "
               "epoch-close does NOT cause a notification / render / React "
               "commit — `mark-dirty` schedules UI work at a later pending "
               "host checkpoint, decoupled from epoch count (rf2-vxgfnd.167 / "
               ".166). If a hit is legitimate per-epoch EVIDENCE or "
               "derivation-cache terminology, prefer hyphenating it "
               "(`per-epoch`); allowlist it only with a stated reason. "
               "Offending lines:\n  "
               (str/join "\n  "
                         (for [[path hits] (sort offenders)
                               [n line]    hits]
                           (str path ":" n "  " line))))))))

(deftest allowlist-carries-no-stale-entries
  (testing "rf2-vxgfnd.167: every allowlisted path still exists and still
            matches. An entry whose file was fixed or deleted is stale, and a
            stale exemption must RED rather than rot — the same failure mode
            that let #5790's directory-ignore premise survive unexamined."
    (let [{:keys [hits]} @census-result
          root           (repo-root)]
      (doseq [[path reason] allowlist]
        (is (.isFile (io/file root path))
            (str "allowlisted path no longer exists: " path " (" reason ")"))
        (is (contains? hits path)
            (str "allowlisted path no longer carries a matching line — remove "
                 "the exemption: " path " (" reason ")"))))))

;; ---------------------------------------------------------------------------
;; Adversarial (negative): the LEGITIMATE per-epoch EVIDENCE terminology must
;; survive — the bead forbids a blind global scrub of every `epoch` mention.
;; ---------------------------------------------------------------------------

(def ^:private observation-resource
  "re_frame/substrate/observation.cljc")

(deftest observation-port-preserves-legitimate-per-epoch-evidence-terms
  (testing "rf2-vxgfnd.167: the sweep must PRESERVE the port's legitimate
            per-epoch evidence axes (`:frame-epoch` / `:registry-epoch`) and
            the derivation `commit-epoch` law — a blind textual scrub of
            every `epoch` mention is itself a regression the bead calls out."
    (let [url (io/resource observation-resource)
          _   (assert url (str "observation-port source not on the classpath: "
                               observation-resource))
          src (slurp url)]
      (doseq [term ["frame-epoch" "registry-epoch" "commit-epoch"]]
        (is (str/includes? src term)
            (str "legitimate per-epoch evidence term `" term "` was scrubbed "
                 "from the observation-port source; rf2-vxgfnd.167 requires "
                 "the per-epoch EVIDENCE/derivation-cache terminology be kept, "
                 "only the retired per-epoch RENDER law removed."))))))
