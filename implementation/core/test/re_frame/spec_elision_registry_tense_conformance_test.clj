(ns re-frame.spec-elision-registry-tense-conformance-test
  "rf2-qgsp2o — the spec-content PIN that keeps `spec/Tool-Pair.md` and
  `spec/Security.md` from teaching the RETIRED app-db elision registry
  `[:rf/runtime :elision …]` in CURRENT TENSE.

  ## What this pins

  The elision declaration registry is durable **runtime-db** state at
  `[:rf.runtime/elision …]` — one namespaced keyword — owned by core
  (`implementation/core/src/re_frame/elision.cljc`; see
  [Ownership.md](../../../spec/Ownership.md) row for `:rf.runtime/elision`).
  It is NOT the app-db `[:rf/runtime :elision …]` root (two keywords). A spec
  that teaches the app-db path in CURRENT TENSE produces false-green
  direct-read privacy checks: a walker reading a dead registry emits raw
  values. Both `spec/Tool-Pair.md §Direct-read privacy` and
  `spec/Security.md §Direct-read privacy` are the authoritative direct-read
  privacy contract that pair / off-box tools follow, so their prose MUST name
  the live runtime-db registry, not the retired app-db one.

  ## Provenance — re-homed from the skills corpus (rf2-qgsp2o)

  This invariant was previously the `tool-pair-spec-elision-registry-is-
  runtime-db-not-app-db` / `security-spec-elision-registry-is-runtime-db-not-
  app-db` guards inside `skills/shared/tests/tool_pair_surfaces_test.clj`.
  rf2-u3anaj (#5509) slimmed that skills leaf to a routing index and removed
  its second-spec token-pinning suite, per the `exact semantics tested by the
  owning suites` acceptance — which left this spec-content invariant with NO
  guard. It is re-homed HERE, into the core JVM suite that owns the elision
  registry and already hosts the sibling direct-read-privacy source pins
  (`egress_chokepoint_conformance_test`, `error_catalogue_channel_conformance_
  test`). The predicate is carried over verbatim (retired-history framing is
  allowed; only a CURRENT-TENSE app-db reference fails).

  ## How the guard works

  For each of the two spec files: scan its lines for the retired app-db path
  form `[:rf/runtime :elision` and DROP any line whose sentence frames the
  mention as retired history (`retired` / `no longer` / `legacy` / `formerly`
  / `used to` / `briefly sat`). Any surviving line is a CURRENT-TENSE claim of
  the dead registry and fails the gate. The regex keys on the two-keyword
  vector `[:rf/runtime :elision`, so the live one-keyword `[:rf.runtime/elision …]`
  form (no space after `:rf.runtime/elision`) never matches and is not
  disturbed.

  A sanity anchor (`spec-files-read-and-still-teach-the-live-registry`) fails
  loud if either file cannot be resolved / read, or no longer contains the
  live `[:rf.runtime/elision` form at all — so a moved / renamed / gutted
  section cannot make the absence check pass vacuously.

  JVM-only (`.clj`, NOT `*-cljs-test`): it `slurp`s the repo's `spec/*.md`
  files, which only the JVM `clojure -M:test` runner can do. This test only
  READS the spec files — it never edits them."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Repo-root + spec-file resolution
;; ---------------------------------------------------------------------------

(def ^:private guarded-spec-files
  "The two authoritative direct-read-privacy spec files whose prose this gate
  pins. Relative to the repo `spec/` dir."
  ["Tool-Pair.md" "Security.md"])

(def ^:private repo-root
  "Repo root resolved from the JVM test CWD. The core `:test` alias runs from
  `implementation/core/`, so the repo root is `../../`; the fallback bases
  tolerate a transitional REPL run from `implementation/` or the repo root
  itself. Pick the first candidate whose `spec/Tool-Pair.md` exists so the
  slurp targets real content, not a phantom path."
  (->> ["../.." ".." "." "../../.."]
       (map io/file)
       (filter #(.isFile (io/file % "spec" (first guarded-spec-files))))
       first))

(defn- spec-md
  "Slurp `spec/<file-name>` from the resolved repo root, or nil if the root did
  not resolve."
  [file-name]
  (when repo-root
    (let [f (io/file repo-root "spec" file-name)]
      (when (.isFile f) (slurp f)))))

;; ---------------------------------------------------------------------------
;; The current-tense app-db elision-registry predicate (carried over verbatim
;; from the removed skills guard, rf2-kvpr74 → re-homed rf2-qgsp2o)
;; ---------------------------------------------------------------------------

(defn- retired-framing?
  "True if the line names the legacy path inside an explicit retired-history
  framing (so it is documentation OF the retirement, not a live claim)."
  [line]
  (let [l (str/lower-case line)]
    (boolean (or (str/includes? l "retired")
                 (str/includes? l "no longer")
                 (str/includes? l "legacy")
                 (str/includes? l "formerly")
                 (str/includes? l "used to")
                 (str/includes? l "briefly sat")))))

(defn- current-tense-app-db-elision-lines
  "Return the lines of `md` that reference the retired app-db elision registry
  `[:rf/runtime :elision …]` in a CURRENT-TENSE framing (i.e. not inside a
  retired-history sentence)."
  [md]
  (->> (str/split-lines md)
       (filter #(re-find #"\[:rf/runtime\s+:elision" %))
       (remove retired-framing?)))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest spec-files-read-and-still-teach-the-live-registry
  (testing "Sanity: the repo root resolved from the JVM test CWD and BOTH
            guarded spec files were read and still teach the live runtime-db
            `[:rf.runtime/elision` registry. A nil root, an unread file, or a
            file that no longer mentions the live registry would make the
            absence check below pass vacuously — fail loud instead (rf2-qgsp2o)."
    (is (some? repo-root)
        (str "repo root did not resolve from the JVM test CWD — expected a "
             "candidate whose spec/" (first guarded-spec-files) " exists "
             "(the core :test alias runs from implementation/core/, so ../.. "
             "is the repo root)."))
    (doseq [file-name guarded-spec-files]
      (let [body (spec-md file-name)]
        (is (some? body)
            (str "spec/" file-name " could not be read from the resolved "
                 "repo root — a moved / renamed file would silently void this "
                 "gate."))
        (when body
          (is (str/includes? body "[:rf.runtime/elision")
              (str "spec/" file-name " no longer mentions the live runtime-db "
                   "`[:rf.runtime/elision` registry at all — the direct-read "
                   "privacy section was moved or gutted, which would make the "
                   "current-tense-app-db absence check pass vacuously. Confirm "
                   "the section still lives here (or re-home this pin).")))))))

(deftest tool-pair-spec-elision-registry-is-runtime-db-not-app-db
  (testing "spec/Tool-Pair.md direct-read privacy does NOT teach the app-db
            [:rf/runtime :elision] registry in current tense (rf2-qgsp2o,
            re-homed from rf2-kvpr74)"
    (let [bad (current-tense-app-db-elision-lines (spec-md "Tool-Pair.md"))]
      (is (empty? bad)
          (str "spec/Tool-Pair.md references the RETIRED app-db "
               "`[:rf/runtime :elision …]` elision registry in current tense. "
               "Per EP-0001 the registry is runtime-db state at "
               "`[:rf.runtime/elision …]` (elision.cljc). Update the text or "
               "frame the mention as retired history. Offending line(s): "
               (pr-str bad))))))

(deftest security-spec-elision-registry-is-runtime-db-not-app-db
  (testing "spec/Security.md does NOT teach the app-db [:rf/runtime :elision]
            registry in current tense (rf2-qgsp2o, re-homed from rf2-kvpr74)"
    (let [bad (current-tense-app-db-elision-lines (spec-md "Security.md"))]
      (is (empty? bad)
          (str "spec/Security.md references the RETIRED app-db "
               "`[:rf/runtime :elision …]` elision registry in current tense. "
               "Per EP-0001 the sensitive-rollup reads the runtime-db "
               "`[:rf.runtime/elision :sensitive-declarations]` registry. "
               "Update the text or frame the mention as retired history. "
               "Offending line(s): " (pr-str bad))))))
