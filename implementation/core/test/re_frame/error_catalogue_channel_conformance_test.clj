(ns re-frame.error-catalogue-channel-conformance-test
  "EP-0008 / rf2-sgz1zq — the conformance PIN that makes the Spec 009
  channel assignment REAL, not documentary.

  Spec 009 §Error event catalogue graduated a `Channel` column from
  EP-0008: every emitted `:rf.error/*` / `:rf.warning/*` category rides
  exactly one of two observability channels — `always-on` (the
  production-survivable `register-error-listener!` error-emit axis,
  surface #4) or `diagnostic` (the dev-only trace surface, DCE'd under
  CLJS `:advanced` + `goog.DEBUG=false`). The catalogue's own §note
  names this bead as the test that pins the contract:

    > Every emitted category therefore carries a `Channel`; a
    > conformance test (EP-0008 bead `rf2-sgz1zq`) pins it — every
    > emitted category appears in this catalogue with a channel, and
    > every always-on category is exercised through the error-emit
    > listener in at least one test (so promotion is real, not
    > documentary).

  This file is the FIRST leg (the catalogue-side structural pin); the
  always-on exercise leg lives in
  `re-frame.always-on-axis-conformance-cljs-test` (dual-runtime, drives
  every always-on category through `error-emit/dispatch-on-error!`).

  ## Why data-driven against the catalogue markdown

  The catalogue is the SINGLE SOURCE OF TRUTH for category names and
  channels (Spec 009 §Error event catalogue). Rather than hardcode a
  fragile fixed list that drifts as categories are added, this test
  PARSES the catalogue table out of `spec/009-Instrumentation.md` and
  asserts structural invariants over the parsed rows:

    1. Every catalogue row carries a `Channel` value, and that value is
       one of the two graduated channels (`always-on` / `diagnostic`).
       A new row added WITHOUT a channel (a blank cell, or a typo'd
       channel name) fails DIRECTLY — the parser now captures the whole
       third column (rf2-9fvp25), so a blank cell parses as a row with an
       out-of-set channel and is flagged, rather than silently dropped
       and caught only by the row-count floor.
    2. No category appears twice (the vocabulary is a set, not a bag).
    3. The three EP-0008-promoted categories are present and `always-on`
       (`:rf.error/frame-teardown-failed` — the teardown report;
       `:rf.error/write-after-destroy` — the suppressed-write partner of
       frame-destroyed; `:rf.error/on-destroy-handler-exception` — the
       dedicated teardown-throw discriminator).
    4. The parsed always-on set EQUALS the literal the dual-runtime
       exercise test iterates (`always-on-axis-conformance-cljs-test`'s
       `always-on-categories`). This couples the two legs: a category
       graduated to `always-on` in the catalogue but NOT added to the
       exercise literal fails HERE, and once added the exercise test
       automatically drives it through the listener. Promotion stays
       real, not documentary, with no fragile fixed list.

  ## The co-edit ratchet against the EMIT SITES (rf2-9fvp25)

  Invariants 1-4 pin the catalogue's INTERNAL consistency (and its
  coupling to the exercise literal) — but they only ever compare the
  catalogue against ITSELF + that literal. A production runtime that
  EMITS a `:rf.error/*` / `:rf.warning/*` / advisory category with NO
  catalogue row would pass all of them: the emitted-but-uncatalogued
  category is invisible to a catalogue-only scan. rf2-sgz1zq was meant
  to pin exactly that co-edit invariant; the durable ratchet lives here:

    5. SOURCE-SCAN — derive the set of diagnostic/error/advisory
       categories actually EMITTED from non-test runtime source (the
       keyword arg to `emit-error!` / `emit-warning!` / `dispatch-on-
       error!`, and the second keyword of an `emit!` whose op-type is
       `:warning` / `:advisory`), across EVERY artefact's `src/` tree.
       Every emitted category must be catalogued OR on the explicit
       `out-of-catalogue-allow-list` (the EP-0008 audit-ruled intentional
       exclusions, rf2-r8oiw7). A new uncatalogued emitted category fails
       with a missing-row diagnostic. The allow-list itself is kept honest
       (`allow-list-stays-honest`): an entry that stops being emitted, or
       that gets catalogued, must be dropped.

  JVM-only (`.clj`, NOT `*-cljs-test`): it `slurp`s repo markdown + source
  files, which only the JVM `clojure -M:test` runner can do. The exercise
  leg is the dual-runtime half."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            ;; The dual-runtime exercise leg's always-on literal — Test A
            ;; pins it against the parsed catalogue (invariant #4), Test B
            ;; iterates it. Requiring it here closes the coupling in code.
            [re-frame.always-on-axis-conformance-cljs-test :as exercise]))

;; ---------------------------------------------------------------------------
;; Catalogue location + parser
;; ---------------------------------------------------------------------------

(def ^:private spec-009-file
  "`spec/009-Instrumentation.md` resolved from the JVM test CWD. Per
  rf2-0hxm the core JVM tests run from `implementation/core/`, so the
  catalogue is at `../../spec/009-Instrumentation.md`; fall back to the
  pre-split `../spec/...` layout for a transitional REPL run from
  `implementation/`. Mirrors `re-frame.conformance-test/fixtures-dir`."
  (let [nested (io/file "../../spec/009-Instrumentation.md")
        legacy (io/file "../spec/009-Instrumentation.md")]
    (if (.exists nested) nested legacy)))

(def ^:private catalogue-row-re
  "Matches one catalogue table row, capturing the `:operation` category
  keyword (group 1) and the RAW `Channel` column cell (group 2,
  possibly blank). The table shape (Spec 009 §Error event catalogue):

    | `:operation` | `:op-type` | Channel | Trigger / meaning | … |

  Group 1 is the back-ticked `:rf.<area>/<category>` in column 1; the
  `:op-type` in column 2 is skipped; group 2 is the WHOLE third column
  cell up to the next `|` — captured verbatim (NOT pre-filtered to a
  lowercase token) so a blank or typo'd channel cell still parses as a
  row and is validated downstream rather than silently dropped (rf2-9fvp25).
  Anchored at `^|` so it only matches genuine table rows, not prose
  mentions of a category. The retired-row sentinel (a strikethrough
  `~~:rf...~~` in column 1) does not match — group 1 requires the
  back-ticked category as the first cell content."
  #"^\|\s*`(:rf\.[^`]+)`\s*\|\s*`?:[^|`]+`?\s*\|([^|]*)\|")

(defn- parse-catalogue
  "Parse the Spec 009 error-event catalogue into a vector of
  `{:category <kw> :channel <string>}` maps, in table order. The
  `:channel` is the TRIMMED third-column cell — possibly the empty
  string when the cell is blank — so the blank/invalid-channel
  invariant is testable directly (rf2-9fvp25) rather than relying on a
  row-count floor. Reads the markdown fresh each call (cheap; one file)."
  []
  (->> (slurp spec-009-file)
       (str/split-lines)
       (keep (fn [line]
               (when-let [[_ cat-str chan] (re-find catalogue-row-re line)]
                 {:category (keyword (subs cat-str 1)) ;; drop leading ':'
                  :channel  (str/trim chan)})))
       vec))

(def ^:private allowed-channels
  "The two graduated EP-0008 channel values (Spec 009 §Error event
  catalogue: \"Its value is one of two\"). The causal channel is data, not
  a catalogue row, so it is not a `Channel` cell value."
  #{"always-on" "diagnostic"})

;; ---------------------------------------------------------------------------
;; Source-scan: derive the EMITTED diagnostic/error/advisory category set
;; from non-test runtime source, vs the parsed catalogue (rf2-9fvp25)
;; ---------------------------------------------------------------------------
;;
;; The catalogue-side invariants above pin the catalogue's internal shape
;; (every row has a valid channel; the always-on set matches the exercise
;; literal). They do NOT close the co-edit invariant the bead names: a
;; production runtime that EMITS a `:rf.error/*` / `:rf.warning/*` category
;; with NO catalogue row would pass every test above — the catalogue is
;; only compared against itself + the exercise literal, never against the
;; actual emit sites. rf2-sgz1zq was meant to pin exactly that; this scan
;; is the durable ratchet.
;;
;; Scope: the error/warning/advisory emit CHOKEPOINTS — the keyword passed
;; as the category arg to `emit-error!` / `emit-warning!` / `dispatch-on-
;; error!`, and the SECOND keyword of `emit!` when its op-type (first
;; keyword) is `:warning` / `:advisory`. This deliberately EXCLUDES
;; success-path / lifecycle traces (`(emit! :rf.fx :rf.fx/handled …)`,
;; `(emit! :rf.event …)`): those ride op-type families the catalogue lists
;; but are not the diagnostic/error/advisory vocabulary this co-edit
;; invariant governs (the bead's acceptance criterion is scoped to
;; "diagnostic/error/advisory category"). The scan is over every artefact's
;; `src/` tree — not just core — so a feature artefact that emits an
;; uncatalogued error is caught too.
;;
;; Limitation (CONSERVATIVE by design): a category emitted ONLY through an
;; intermediate helper that takes the category as a literal arg but calls
;; the chokepoint fns with a VARIABLE (e.g. `fx.cljc`'s `emit-fx-error!`,
;; or the router's computed `classify-pipeline-exception` operation) is NOT
;; captured here. That direction never false-POSITIVES (it under-reports,
;; so a genuinely uncatalogued category emitted only via a helper would
;; slip through) — acceptable for a ratchet whose job is to catch NEW
;; direct emit sites. The dominant emit idiom across the corpus is the
;; direct literal `(emit-error! :rf.x/y …)` / `(emit! :warning :rf.x/y …)`
;; form, so coverage is high; widening to follow helper indirection is a
;; future tightening, not a correctness gap in the ratchet's promise.

(def ^:private impl-src-roots
  "Every artefact's non-test source root, resolved from the JVM test CWD
  (`implementation/core/` per rf2-0hxm → repo root is `../../`). Falls
  back to the pre-split `../implementation/...` layout for a transitional
  REPL run from `implementation/`. Only existing dirs are kept."
  (let [bases ["../../implementation" "../implementation" "../.."]]
    (->> bases
         (mapcat (fn [base]
                   (let [d (io/file base)]
                     (when (.isDirectory d)
                       (->> (.listFiles d)
                            (filter #(.isDirectory %))
                            (map #(io/file % "src")))))))
         (filter #(.isDirectory %))
         distinct
         vec)))

(def ^:private source-file-exts
  #{".clj" ".cljc" ".cljs"})

(defn- non-test-source-files
  "Every `.clj` / `.cljc` / `.cljs` file under the artefact src roots.
  src roots carry only production source (tests live in sibling `test/`
  dirs), so no path-based test exclusion is needed; we guard with a
  `/test/` path check anyway in case a root ever nests one."
  []
  (->> impl-src-roots
       (mapcat (fn [root] (file-seq root)))
       (filter #(.isFile %))
       (filter (fn [f]
                 (let [n (.getName f)]
                   (some #(str/ends-with? n %) source-file-exts))))
       (remove (fn [f]
                 (let [p (str/replace (.getPath f) "\\" "/")]
                   (or (str/includes? p "/test/")
                       (str/ends-with? (.getName f) "_test.clj")
                       (str/ends-with? (.getName f) "_test.cljc")
                       (str/ends-with? (.getName f) "_test.cljs")))))))

;; The keyword char class includes the apostrophe so categories like
;; `:rf.warning/large-value-unschema'd` parse whole (NOT truncated at the
;; `'`). Names are `:rf.<area>/<cat>`; the area segment may be dotted
;; (`:rf.http.interceptor/…`, `:rf.epoch.cb/…`).
(def ^:private category-kw-class "[a-z0-9][a-z0-9'-]*")

(def ^:private emit-error-re
  "`(… emit-error! :rf.<area>/<cat> …)` / `(… dispatch-on-error!
  :rf.<area>/<cat> …)` / `(… emit-warning! :rf.<area>/<cat> …)` — the
  category keyword is the token immediately after the fn symbol. The fn
  may be ns-qualified (`trace/emit-error!`, `error-emit/dispatch-on-
  error!`) or bare."
  (re-pattern (str "(?:emit-error!|dispatch-on-error!|emit-warning!)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(def ^:private emit-bang-warning-re
  "`(… emit! :warning :rf.<area>/<cat> …)` / `(… emit! :advisory
  :rf.<area>/<cat> …)` — `emit!` takes the op-type FIRST and the category
  SECOND, so only treat the category as diagnostic when the op-type is a
  warning/advisory level. An `emit!` with op-type `:rf.event` / `:rf.fx`
  / `:rf.sub` etc. is a success-path or lifecycle trace, NOT a
  diagnostic/error/advisory category, and is intentionally skipped."
  (re-pattern (str "emit!\\s+:(?:warning|advisory)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(defn- emitted-categories
  "Scan every non-test source file for the diagnostic/error/advisory emit
  chokepoints and return the SET of emitted category keywords. Pure text
  scan — no classpath load — so it sees every artefact regardless of which
  are on the test classpath."
  []
  (->> (non-test-source-files)
       (mapcat (fn [f]
                 (let [src (slurp f)]
                   (concat (map second (re-seq emit-error-re src))
                           (map second (re-seq emit-bang-warning-re src))))))
       (map (fn [s] (keyword (subs s 1)))) ;; ":rf.x/y" string → keyword
       set))

(def ^:private out-of-catalogue-allow-list
  "Categories EMITTED from non-test runtime source that currently have NO
  Spec 009 §Error event catalogue row — the KNOWN backlog at the time this
  ratchet landed (rf2-9fvp25). The source-scan revealed the co-edit gap is
  WIDER than the original audit captured (rf2-r8oiw7 enumerated 7; the full
  scan finds the set below). Reconciling each — add a catalogue row with a
  Channel/recovery/tags ruling, or deliberately keep it out-of-catalogue
  with a one-line 009 note — is tracked under rf2-r8oiw7 (the catalogue
  co-edit-invariant bead); it is NOT this core-observability bead's job to
  rule + author ~30 catalogue rows across the feature artefacts (the
  hot-zone spec file). This list is the explicit ratchet baseline the bead
  authorises (\"an explicit allow/ignore list for intentional non-catalogue
  event IDs if needed\"): a NEW uncatalogued emitted category — beyond this
  frozen baseline — fails the coverage test loudly. As rf2-r8oiw7 catalogues
  each (or rules it intentionally out), DROP it from here in the SAME PR;
  `allow-list-stays-honest` fails if a listed entry becomes catalogued or
  stops being emitted, forcing the co-edit so the list cannot rot into a
  silent blanket suppression.

  Grouped by the EP-0008 audit's disposition (rf2-r8oiw7) where known;
  the remainder are feature-artefact diagnostics surfaced by the wider
  scan and awaiting the same triage."
  #{;; --- the original rf2-r8oiw7 audit set (ruled: stay diagnostic) ---
    ;; epoch callback isolation — :rf.epoch.cb op-type, time-axis tooling
    :rf.epoch.cb/listener-exception
    ;; epoch redaction fallback — DCE'd dev advisory
    :rf.warning/epoch-redact-fn-exception
    ;; resources dev advisories owned by the time-axis / resource family
    :rf.warning/resource-sub-scope-mismatch
    :rf.resource/hydrate-clock-skew
    ;; machines DCE'd dev teaching advisory
    :rf.warning/on-spawn-return-ignored
    ;; (`:rf.resource/restore-clock-skew` + `:rf.route/navigation-blocked`
    ;;  from the audit are NOT in this scan's set: the former is built as a
    ;;  deferred-trace record literal, the latter is emitted with op-type
    ;;  `:rf.event` — neither matches the diagnostic/error/advisory emit
    ;;  chokepoints this scan targets, so they need no allow-list entry.)

    ;; --- wider scan: feature-artefact diagnostics awaiting triage (rf2-r8oiw7) ---
    ;; routing :can-leave guard validation + artefact-missing advisories
    :rf.error/can-leave-non-boolean
    :rf.warning/can-leave-subs-artefact-missing
    :rf.error/navigate-arity-misuse
    ;; machines `:after` timer / spawn-join validation diagnostics
    :rf.error/machine-after-fn-threw
    :rf.error/machine-after-sub-threw
    :rf.error/machine-after-watch-failed
    :rf.error/machine-spawn-all-bad-child-id
    ;; SSR hydration / streaming / ring host diagnostics
    :rf.error/hydration-frame-id-mismatch
    :rf.error/suspense-boundary-duplicate-id
    :rf.ssr/suspense-boundary-failed
    :rf.error/ssr-ring-error-view-failed
    :rf.error/ssr-ring-on-error-failed
    :rf.ssr/csp-allowlist-violation
    :rf.ssr/destroy-frame-failed
    :rf.ssr/ssr-non-integer-status
    :rf.ssr/ssr-non-string-header-value
    :rf.ssr/ssr-redirect-no-target
    :rf.ssr.head/cleanup-failed
    ;; HTTP transport / decode diagnostics
    :rf.http/aborted
    :rf.warning/http-header-invalid
    :rf.warning/http-malli-absent
    :rf.warning/failure-swallowed
    ;; resources clear-scope advisory
    :rf.warning/resource-clear-scope-unresolved
    ;; router dispatch-opt typo advisory
    :rf.warning/unknown-dispatch-opt
    ;; views plain-fn advisory — catalogue marks RETIRED but source still
    ;; emits it (a catalogue/source drift rf2-r8oiw7 must reconcile)
    :rf.warning/plain-fn-under-non-default-frame-once})

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest catalogue-parses-to-a-nonempty-set-of-rows
  (testing "Sanity: the parser finds the catalogue table. A zero-row
            parse means the table shape changed out from under this test
            (a column reorder, a fence change) — fail loudly rather than
            vacuously pass every downstream invariant."
    (let [rows (parse-catalogue)]
      (is (.exists spec-009-file)
          (str "spec/009-Instrumentation.md not found at " spec-009-file))
      (is (seq rows) "catalogue parse yielded at least one row")
      ;; A floor well below today's 163 rows — catches a broken parse
      ;; without pinning an exact count (the vocabulary grows).
      (is (>= (count rows) 100)
          "catalogue parse yielded the full table (>= 100 rows), not a
           partial match from a shape change"))))

(deftest every-emitted-category-carries-a-channel
  (testing "Per Spec 009 §Error event catalogue (the rf2-sgz1zq pin):
            EVERY catalogue row carries a `Channel` value, and that value
            is exactly one of the two graduated channels. The parser only
            captures group 2 when a non-empty lowercase token sits in the
            Channel column, so a row with an EMPTY channel cell would not
            be captured as a row at all — to catch that, we assert the
            row is well-formed AND its channel is in the allowed set. A
            typo'd channel (e.g. `always_on`, `diag`) lands a row with an
            out-of-set channel and fails here."
    (let [rows (parse-catalogue)
          bad  (remove (comp allowed-channels :channel) rows)]
      (is (empty? bad)
          (str "every catalogue row's Channel must be one of "
               allowed-channels "; offending rows: " (pr-str bad))))))

(deftest catalogue-categories-are-unique
  (testing "The category vocabulary is a SET — each `:rf.<area>/<category>`
            appears in exactly one catalogue row. A duplicate row (e.g. a
            category re-listed under a different channel) is a contract
            bug: a consumer reading the catalogue as a map would silently
            take one binding."
    (let [rows (parse-catalogue)
          dups (->> (map :category rows)
                    frequencies
                    (filter (fn [[_ n]] (> n 1)))
                    (map first))]
      (is (empty? dups)
          (str "categories appearing in more than one catalogue row: "
               (pr-str dups))))))

(deftest ep0008-promoted-categories-are-present-and-always-on
  (testing "Per Spec 009 §Channel-promotion catalogue rows + the EP-0008
            wave (rf2-ini4wr / rf2-500ech / rf2-7b9r4l): the three
            promoted categories appear in the catalogue and ride the
            always-on axis. This is the explicit acceptance leg the bead
            calls out — the newly-promoted rows must be classified, not
            just emitted."
    (let [by-cat (into {} (map (juxt :category :channel)) (parse-catalogue))]
      (doseq [cat [:rf.error/frame-teardown-failed
                   :rf.error/write-after-destroy
                   :rf.error/on-destroy-handler-exception]]
        (is (contains? by-cat cat)
            (str cat " is catalogued"))
        (is (= "always-on" (get by-cat cat))
            (str cat " rides the always-on channel"))))))

(deftest parsed-always-on-set-equals-the-exercise-literal
  (testing "Per the rf2-sgz1zq pin (invariant #4): the set of always-on
            categories PARSED from the catalogue equals the literal
            `always-on-axis-conformance-cljs-test/always-on-categories`
            that the dual-runtime exercise test iterates. This couples the
            two legs WITHOUT a fragile hand-maintained duplicate:

              - A category graduated to `always-on` in the catalogue but
                NOT added to the exercise literal fails HERE — surfacing
                the missing always-on coverage.
              - A category in the literal that is NOT always-on in the
                catalogue (a stale entry, or one demoted) also fails here.

            Once the literal is corrected to match, the exercise test
            automatically drives the new category through
            `register-error-listener!`. Promotion is therefore enforced
            end-to-end, never documentary, and stays green as the
            always-on set grows."
    (let [catalogue-always-on
          (->> (parse-catalogue)
               (filter #(= "always-on" (:channel %)))
               (map :category)
               set)]
      (is (= catalogue-always-on exercise/always-on-categories)
          (str "catalogue always-on set vs exercise literal — "
               "only-in-catalogue: "
               (pr-str (sort (set/difference
                               catalogue-always-on
                               exercise/always-on-categories)))
               " ; only-in-literal: "
               (pr-str (sort (set/difference
                               exercise/always-on-categories
                               catalogue-always-on))))))))

(deftest every-table-row-has-a-nonblank-valid-channel
  (testing "Per rf2-9fvp25: the catalogue parser now captures the WHOLE
            third column (not a pre-filtered lowercase token), so a row
            whose Channel cell is BLANK or holds a typo'd value parses as
            a row with an out-of-set `:channel` and fails HERE directly —
            rather than silently vanishing from the parse (the old regex
            dropped it) and being caught only by the >= 100 row-count
            floor. This makes the blank/invalid-channel invariant explicit
            and independent of the floor."
    (let [rows         (parse-catalogue)
          blank        (filter #(str/blank? (:channel %)) rows)
          invalid-set  (->> rows
                            (remove #(str/blank? (:channel %)))
                            (remove (comp allowed-channels :channel)))]
      (is (empty? blank)
          (str "catalogue rows with a BLANK Channel cell (rf2-9fvp25): "
               (pr-str (map :category blank))))
      (is (empty? invalid-set)
          (str "catalogue rows with an invalid (non-"
               allowed-channels ") Channel cell: "
               (pr-str (map (juxt :category :channel) invalid-set)))))))

;; ---------------------------------------------------------------------------
;; Source-scan ratchet (rf2-9fvp25) — the co-edit invariant made testable
;; against the ACTUAL emit sites, not just the catalogue's self-consistency.
;; ---------------------------------------------------------------------------

(deftest source-scan-finds-the-emit-sites
  (testing "Sanity: the source scan reaches the artefact src trees and
            finds the known emit chokepoints. A zero / tiny result means
            the src roots did not resolve from the test CWD (a path-layout
            change) — fail loudly rather than vacuously passing the
            coverage invariant below with an empty emitted set."
    (let [roots impl-src-roots
          cats  (emitted-categories)]
      (is (seq roots)
          "at least one artefact src root resolved from the JVM test CWD")
      (is (>= (count cats) 50)
          (str "source scan found the diagnostic/error/advisory emit "
               "vocabulary (>= 50 categories), not a broken / empty scan; "
               "found " (count cats)))
      ;; Anchor on the EP-0008 common-path producer (rf2-87f7fb) — emitted
      ;; via `emit-error!` with a LITERAL category arg, so the emit-error!
      ;; arm of the scan is exercised. (`:rf.error/handler-exception` is
      ;; NOT a literal at its emit site — the router computes the category
      ;; from the captured component identity and passes a VARIABLE to
      ;; `dispatch-on-error!` — so it is intentionally not anchored here.)
      (is (contains? cats :rf.error/on-destroy-handler-exception)
          ":rf.error/on-destroy-handler-exception is among the scanned emit sites")
      ;; Anchor on a second literal `emit-error!` category from a different
      ;; ns (router/diagnostics) to prove the cross-file scan is live.
      (is (contains? cats :rf.error/no-such-handler)
          ":rf.error/no-such-handler is among the scanned emit sites"))))

(deftest every-emitted-category-is-catalogued
  (testing "Per rf2-9fvp25 (the durable co-edit ratchet, superseding the
            self-referential rf2-sgz1zq pin): EVERY diagnostic/error/
            advisory `:rf.*` category EMITTED from non-test runtime source
            must appear in the Spec 009 §Error event catalogue — UNLESS it
            is on the explicit `out-of-catalogue-allow-list` (the EP-0008
            audit-ruled intentional exclusions, rf2-r8oiw7). A production
            source that adds a NEW uncatalogued error/warning/advisory
            category — without a catalogue row and without an allow-list
            entry — fails HERE with a missing-row diagnostic, closing the
            gap the prior tests left open (they compared the catalogue only
            against itself + the exercise literal, never against the actual
            emit sites)."
    (let [catalogued (->> (parse-catalogue) (map :category) set)
          emitted    (emitted-categories)
          missing    (set/difference emitted catalogued out-of-catalogue-allow-list)]
      (is (empty? missing)
          (str "categories EMITTED from non-test runtime source with NO "
               "Spec 009 catalogue row and no allow-list entry "
               "(rf2-9fvp25 co-edit invariant): "
               (pr-str (sort missing))
               " — either add the catalogue row (with a Channel) or, if it "
               "is an intentional non-catalogue category, add it to "
               "out-of-catalogue-allow-list with a rationale.")))))

(deftest allow-list-stays-honest
  (testing "Per rf2-9fvp25: every category on the out-of-catalogue
            allow-list must STILL be emitted from non-test source AND must
            STILL be absent from the catalogue. This keeps the allow-list
            from rotting into a stale suppression: if a listed category is
            retired (no longer emitted) it must be dropped from the list,
            and if it is finally catalogued (r8oiw7 adds a row) it must
            ALSO be dropped (otherwise the list silently masks a real
            catalogued category from the coverage check). Both drifts fail
            here, forcing the co-edit."
    (let [catalogued (->> (parse-catalogue) (map :category) set)
          emitted    (emitted-categories)
          stale-unemitted  (set/difference out-of-catalogue-allow-list emitted)
          now-catalogued   (set/intersection out-of-catalogue-allow-list catalogued)]
      (is (empty? stale-unemitted)
          (str "allow-list entries no longer emitted from source (drop "
               "them, rf2-9fvp25): " (pr-str (sort stale-unemitted))))
      (is (empty? now-catalogued)
          (str "allow-list entries that are NOW catalogued (drop them so "
               "the coverage check governs them, rf2-9fvp25): "
               (pr-str (sort now-catalogued)))))))
