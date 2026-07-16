(ns re-frame.observation-render-law-drift-test
  "rf2-vxgfnd.167 — the observation-port render-law drift gate.

  The retired per-epoch render law (`rf2-vxgfnd.66` / PR #5790) falsely
  equated an event/derivation EPOCH with a UI notification, component
  render, or React commit — the shape that lets a reader infer that every
  epoch closes a UI batch (`epoch-close notify` → React work). The shipped
  scheduler does not work that way: an epoch is a WRITE / EVIDENCE unit; the
  owner-notification's `mark-dirty` schedules a render/commit that flushes at
  a later pending host checkpoint (coalesced across a batch), decoupled from
  epoch count (the host-checkpoint contract itself is `rf2-vxgfnd.166`).

  #5790's changed-file roster missed this force-tracked residue: the
  `*in-owner-fan-out?*` docstring in `re-frame.substrate.observation` taught
  `renders and commits *caused by* the epoch-close notify`. This gate is the
  drift check that keeps the observation-port source free of that retired
  equation — a seeded `epoch-close notify` (or a `render/react/notify per
  epoch` claim) reddens it.

  Scoped to the ONE core-substrate surface this worker owns
  (`observation.cljc`, where the port's render-law prose lives). The
  repo-wide force-tracked census (git-ls-files roster across the `ai/`
  design tree, Spec 006/009, Story) is the broader `rf2-vxgfnd.167` sweep;
  this file pins the observation-port slice.

  JVM-only (`_test.clj`) by design — it reads the `.cljc` SOURCE TEXT via
  `io/resource`, the same host-agnostic bytes both hosts compile, so no CLJS
  runtime is involved (the `no-rf-default-floor-lint` / `doc-metadata-prod-
  elision` gates use the same JVM-source-text idiom)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private observation-resource
  "Classpath resource path of the observation-port source (`:paths [\"src\"]`
  in the core `deps.edn` puts it on the classpath as a resource)."
  "re_frame/substrate/observation.cljc")

(defn- observation-source
  "The observation-port `.cljc` SOURCE TEXT. Fail LOUD (not silently empty)
  if the resource cannot be found, so a classpath change can never turn this
  gate into a vacuous pass."
  []
  (let [url (io/resource observation-resource)]
    (assert url (str "observation-port source not on the classpath: "
                     observation-resource))
    (slurp url)))

;; ---------------------------------------------------------------------------
;; Forbidden: the retired per-epoch UI render/notify law.
;; ---------------------------------------------------------------------------
;;
;; These scan the RAW source (docstrings + comments included, NOT stripped):
;; the retired law is taught in PROSE, so prose is exactly what must stay
;; clean. Each pattern is case-insensitive.

(def ^:private forbidden-render-law-res
  "The retired-per-epoch-render-law shapes. `epoch-close` (or `epoch close`)
  adjacent to UI-scheduling verbs is the exact #5790-missed residue; the
  `<ui-verb> … per … epoch` shape catches the sibling `one notification /
  render per epoch` / `rendered once per epoch` / `React work per input
  epoch` phrasings. `commit` is deliberately EXCLUDED from the per-epoch arm
  because the core spine's derivation-epoch cache/commit law is legitimate
  per-epoch terminology (bead: must not be blindly replaced); the
  `epoch-close` arm still catches an `epoch-close … commit` UI claim."
  [;; `epoch-close notify` / `epoch close notification` / `epoch-close
   ;; render|commit|react|batch` / `epoch close triggers|causes|fires` —
   ;; the equation that an epoch's CLOSE drives UI work.
   #"(?i)epoch[-\s]close\s+(?:notif|render|commit|react|batch|trigger|cause|fire)"
   ;; A UI verb claimed to happen once per epoch: `render/react/notification
   ;; per epoch`, `rendered once per (input) epoch`, `notification per
   ;; re-frame2 epoch`.
   #"(?i)(?:notif\w*|render\w*|react\w*)\s+(?:work\s+)?(?:once\s+)?per\s+(?:\S+\s+){0,2}epoch"])

(defn- offending-lines
  "`[line-no line]` pairs of `content` that carry a retired render-law claim."
  [content]
  (->> (str/split-lines content)
       (map-indexed (fn [i line] [(inc i) line]))
       (keep (fn [[n line]]
               (when (some #(re-find % line) forbidden-render-law-res)
                 [n (str/trim line)])))))

(deftest observation-port-carries-no-retired-per-epoch-render-law
  (testing "rf2-vxgfnd.167: the observation-port source no longer equates an
            epoch with a UI notification / render / React commit — no
            `epoch-close notify`, no `render/react/notification per epoch`.
            An epoch is a write/evidence unit; `mark-dirty` schedules the
            render/commit at a later host checkpoint (rf2-vxgfnd.166),
            decoupled from epoch count."
    (let [offenders (offending-lines (observation-source))]
      (is (empty? offenders)
          (str "The observation-port source teaches the RETIRED per-epoch "
               "render law (an epoch-close does NOT cause a notification / "
               "render / React commit — `mark-dirty` schedules UI work at a "
               "later pending host checkpoint, decoupled from epoch count; "
               "see rf2-vxgfnd.167 / .166). Offending "
               observation-resource " lines:\n  "
               (str/join "\n  "
                         (for [[n line] offenders]
                           (str observation-resource ":" n "  " line))))))))

;; ---------------------------------------------------------------------------
;; Adversarial (negative): the LEGITIMATE per-epoch EVIDENCE terminology must
;; survive — the bead forbids a blind global scrub of every `epoch` mention.
;; ---------------------------------------------------------------------------

(deftest observation-port-preserves-legitimate-per-epoch-evidence-terms
  (testing "rf2-vxgfnd.167: the sweep must PRESERVE the port's legitimate
            per-epoch evidence axes (`:frame-epoch` / `:registry-epoch`) and
            the derivation `commit-epoch` law — a blind textual scrub of
            every `epoch` mention is itself a regression the bead calls out."
    (let [src (observation-source)]
      (doseq [term ["frame-epoch" "registry-epoch" "commit-epoch"]]
        (is (str/includes? src term)
            (str "legitimate per-epoch evidence term `" term "` was scrubbed "
                 "from the observation-port source; rf2-vxgfnd.167 requires "
                 "the per-epoch EVIDENCE/derivation-cache terminology be kept, "
                 "only the retired per-epoch RENDER law removed."))))))
