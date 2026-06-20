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
       error!` / `emit-error-both!`, the second keyword of an `emit!`
       whose op-type is `:warning` / `:advisory`, AND — rf2-scuobk — the
       first literal keyword arg of the canonical thrown-error builders
       `throw-error!` / `thrown-ex-info`, closing the THROW-axis blind
       spot the emit-only scan left open), across EVERY artefact's `src/`
       tree.
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

(def ^:private catalogue-heading-re
  "The canonical `### Error event catalogue` section heading — the one
  five-column table (`:operation | :op-type | Channel | …`) that IS the
  single source of truth. Anchored to the bare heading text so it does
  NOT match the later `### Error event catalogue (single source of
  truth)` prose subsection under §Resolved decisions (which carries no
  table)."
  #"^###\s+Error event catalogue\s*$")

(def ^:private section-heading-re
  "Any `#`/`##`/`###`-level heading — the boundary that ends the
  catalogue section. The catalogue table is followed by the
  `#### History-error tag layering` subsection (a `####`, which does NOT
  terminate the section — its rows are part of the catalogue's tag
  prose, carrying no table rows) and then the sibling `### Schemas`
  heading, which DOES terminate it."
  #"^#{1,3}\s+\S")

(defn- catalogue-section-lines
  "The lines of `spec/009-Instrumentation.md` that belong to the
  canonical `### Error event catalogue` section — from the catalogue
  heading (exclusive) to the next sibling-or-higher heading (exclusive).

  Why scope to the section (rf2-i6p308): `catalogue-row-re` is anchored
  at `^|` so it only matches genuine table rows, but the doc carries a
  SECOND, differently-shaped table — the `#### Per-`:operation` quick
  reference` (a 3-column `:operation | :op-type | One-line meaning`
  table, NO Channel column). A single-category quick-ref row matches the
  regex too, and its `One-line meaning` cell parses as the `Channel` —
  an out-of-set value that fails every channel invariant. (Multi-
  category slash-joined quick-ref rows escape the regex; single-category
  ones don't — 17 of them, exactly the categories that surfaced.) Adding
  that quick-ref table introduced the breakage independently of any test
  change; the durable fix is to parse ONLY the canonical catalogue
  section so a future second `:rf.*` table elsewhere in the doc can't
  pollute the parse either. The blank/typo'd-channel invariant
  (rf2-9fvp25) is unaffected: a malformed cell WITHIN the catalogue
  section still parses as a row and fails."
  [lines]
  (->> lines
       (drop-while #(not (re-find catalogue-heading-re %)))
       (drop 1)                                     ;; the heading line itself
       (take-while #(not (re-find section-heading-re %)))))

(defn- parse-catalogue
  "Parse the Spec 009 error-event catalogue into a vector of
  `{:category <kw> :channel <string>}` maps, in table order. The
  `:channel` is the TRIMMED third-column cell — possibly the empty
  string when the cell is blank — so the blank/invalid-channel
  invariant is testable directly (rf2-9fvp25) rather than relying on a
  row-count floor. Scoped to the canonical `### Error event catalogue`
  section so the doc's other `:rf.*` tables (e.g. the quick-reference
  table) don't pollute the parse (rf2-i6p308). Reads the markdown fresh
  each call (cheap; one file)."
  []
  (->> (slurp spec-009-file)
       (str/split-lines)
       (catalogue-section-lines)
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
  :rf.<area>/<cat> …)` / `(… emit-warning! :rf.<area>/<cat> …)` /
  `(… emit-error-both! :rf.<area>/<cat> …)` — the category keyword is the
  token immediately after the fn symbol. The fn may be ns-qualified
  (`trace/emit-error!`, `error-emit/dispatch-on-error!`,
  `error-emit/emit-error-both!`) or bare.

  `emit-error-both!` (rf2-c4oycd) is the shared two-channel fan-out the
  open-coded `dispatch-on-error!` + `trace/emit-error!` two-step collapsed
  onto: it takes the category as its FIRST arg exactly like the others, so
  the scanner reaches the categories now routed through it (e.g.
  `:rf.error/no-such-handler`, `:rf.error/frame-destroyed`)."
  (re-pattern (str "(?:emit-error-both!|emit-error!|dispatch-on-error!|emit-warning!)\\s+"
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

(def ^:private throw-error-re
  "`(… throw-error! :rf.<area>/<cat> …)` / `(… thrown-ex-info
  :rf.<area>/<cat> …)` — the canonical thrown-error builders (Spec 009
  §The thrown-error shape). The category keyword is the FIRST argument; it
  may sit on the SAME line as the fn token or on the NEXT line (the
  dominant multi-line idiom — `(error/throw-error!\\n  :rf.error/x\\n
  'rf/where …)`), so `\\s+` spans newlines (the scan runs `re-seq` over the
  whole slurped file string). The fn may be ns-qualified (`error/throw-
  error!`, `rf-error/throw-error!`) or bare.

  Closing the rf2-scuobk blind spot: the pre-existing emit scan (`emit-
  error!` / `dispatch-on-error!` / `emit-warning!` / `emit-error-both!` /
  `emit! :warning|:advisory`) saw only the trace/error-emit axis — it was
  BLIND to the THROW axis, so a `:rf.error/*` category that the runtime
  ONLY ever THROWS (a registration-time / dispatch-boundary `throw-error!`,
  never trace-emitted) read as un-emitted and never forced a catalogue row.
  A thrown ex-info registration rejection IS a catalogue category (Spec 009
  §Error event catalogue marks it diagnostic-channel — it is not delivered
  to the always-on error-emit listener, but it is an emitted `:rf.error/*`
  the catalogue must carry). Even the PRODUCTION-REACHABLE dispatch-boundary
  throws (`:rf.error/invalid-cofx`, `:rf.error/dispatched-at-retired` — NOT
  gated on `interop/debug-enabled?`, so they fire in `:advanced` production)
  are diagnostic-channel: they are pure `throw-error!`s that do NOT fan out
  on the error-emit listener, so they ride the diagnostic channel for
  catalogue purposes (the catalogue's thrown-ex-info-is-diagnostic rule).

  Same CONSERVATIVE limitation as the emit scan: a category THROWN only via
  a VARIABLE first arg (the shared per-surface throwers that take the
  category as a parameter — `cofx/raise-removed!`, `std-interceptors`'
  removed-stub table, `reply/reply-category->error-id`,
  `machines/transition` `category`, `routing/registry` `error-kw`) is NOT
  captured here; it under-reports, never false-positives. The dominant idiom
  is the direct literal `(throw-error! :rf.x/y …)` form, so coverage is high."
  (re-pattern (str "(?:throw-error!|thrown-ex-info)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(defn- emitted-categories
  "Scan every non-test source file for the diagnostic/error/advisory emit
  AND throw chokepoints and return the SET of emitted category keywords.
  Pure text scan — no classpath load — so it sees every artefact regardless
  of which are on the test classpath. The throw arm (rf2-scuobk) harvests
  the first literal keyword arg of `throw-error!` / `thrown-ex-info`, which
  the original emit-only scan was blind to."
  []
  (->> (non-test-source-files)
       (mapcat (fn [f]
                 (let [src (slurp f)]
                   (concat (map second (re-seq emit-error-re src))
                           (map second (re-seq emit-bang-warning-re src))
                           (map second (re-seq throw-error-re src))))))
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

  rf2-r8oiw7 has now CATALOGUED the entire wider-scan backlog as DIAGNOSTIC
  rows (Spec 009 §Error event catalogue) and dropped them from this list in
  the same PR. The earlier rf2-hhutya pass folded FOUR original-set rows into
  the catalogue (`:rf.epoch.cb/listener-exception`,
  `:rf.warning/epoch-redact-fn-exception`,
  `:rf.warning/resource-sub-scope-mismatch`,
  `:rf.warning/on-spawn-return-ignored`) and PROMOTED + catalogued
  `:rf.error/ssr-ring-error-view-failed`. rf2-r8oiw7 then catalogued the rest:
  the resources clock-skew advisories, the routing `:can-leave` / navigate
  diagnostics, the machines `:after` timer + spawn-join diagnostics, the SSR
  hydration / streaming / ring-host diagnostics, the HTTP transport / decode
  diagnostics, the resource clear-scope advisory, and the router dispatch-opt
  typo advisory — each a DIAGNOSTIC catalogue row (they fail the EP-0008
  promotion criterion and correctly stay diagnostic).

  TWO categories were ruled INTENTIONALLY-OUT-OF-CATALOGUE with a one-line
  note in Spec 009 §Error event catalogue (rf2-r8oiw7), NEITHER needing an
  allow-list entry now:
    - `:rf.route/navigation-blocked` — a `:rf.event` op-type user-event
      lifecycle trace, not an error/warning category; it never matched this
      scan's chokepoints anyway, so it needs no allow-list entry.
    - `:rf.warning/plain-fn-under-non-default-frame-once` — was a
      catalogue/source DRIFT: RETIRED in the catalogue (the strikethrough row,
      superseded by `:rf.error/no-frame-context` per EP-0002) but
      `re-frame.views.warn_once` still carried the dev-gated emit site as a
      structural no-op (the firing case was unreachable — the no-frame-context
      throw happens first). rf2-7yqn39 deleted that dead emit site, so the
      category is NO LONGER emitted from source; its allow-list entry — the
      last remaining one — was dropped in the same PR. Source now matches the
      catalogue's RETIRED ruling.

  The allow-list is kept as a live ratchet seam — a deliberate non-catalogue
  emit category lands here with a rationale — and `allow-list-stays-honest`
  fails if any entry becomes catalogued or stops being emitted, forcing the
  co-edit so the list cannot rot into a silent blanket suppression.

  CO-EDIT RESOLVED (rf2-d8mvke.1 / rf2-d8mvke.6 finding-1):
    - `:rf.error/cofx-registration-invalid` — the malformed-`reg-cofx`-metadata
      error introduced by the EP-0017 round-2 review (`reject :provided?+supplier
      contradiction; reserve `:rf.error/cofx-name-collision` for genuine
      duplicate ownership) — was held here transitionally while this code-side
      PR emitted the error-id ahead of its catalogue row. The Spec 009 §Error
      event catalogue row was authored + MERGED by the EP-0017 completeness-sync
      worker (rf2-d8mvke.2, which owns the hot-zone 009 file). The category is
      now CATALOGUED, so per the ratchet it has been DROPPED from this list:
      `allow-list-stays-honest` forced exactly this co-edit (a listed entry that
      becomes catalogued must leave the allow-list).

  THROW-AXIS WIDENING (rf2-scuobk). Extending the scan to the THROW chokepoints
  (`throw-error!` / `thrown-ex-info` first literal arg) surfaced the catalogue
  blind spot the bead names: a `:rf.error/*` category the runtime only ever
  THROWS (never trace-emits) was invisible to the old emit-only scan. The
  rf2-scuobk PR CATALOGUED the genuinely-emitted-but-uncatalogued throw
  categories the widened scan found (the core registration / dispatch-boundary
  throws, the flows / http / machines / resources / routing / ssr feature
  throws — each a DIAGNOSTIC catalogue row, including the two
  production-reachable dispatch-boundary throws `:rf.error/invalid-cofx` /
  `:rf.error/dispatched-at-retired`: they fire in production but are pure
  `throw-error!`s that never reach the error-emit listener, so they ride the
  diagnostic channel for catalogue purposes). The allow-list below holds ONLY
  the residue that must NOT become a catalogue row.

  GENUINELY-INTERNAL-INVARIANT (NOT caller-fixable, NOT a public contract — no
  catalogue row by design):
    - `:rf.error/flow-cycle-extract-invariant` — `re-frame.flows.topo`'s
      cycle-path-extraction dead-end guard. It fires ONLY when the topo state is
      internally inconsistent (a Kahn-stuck node found no stuck dependency to
      follow) — an impossible-by-construction state that signals a framework bug,
      not a user error. The source explicitly contrasts it with the sibling
      `:rf.error/flow-cycle` (a caller-fixable `:fix-registration` registration
      rejection, which IS catalogued): \"that one is a genuine internal-invariant
      violation, not caller-fixable\" (topo.cljc). An internal invariant is not a
      consumer-facing error category, so it stays out of the catalogue.

  INTERNAL-INVARIANT / framework-bug categories, held here rather than catalogued:
    - `:rf.error/unknown-registry-kind` — `re-frame.registrar/register!`
      unknown-kind guard; a framework-bug guard, not a consumer-facing category.

  `allow-list-stays-honest` fails if any entry becomes catalogued or stops being
  emitted, forcing the co-edit so the list cannot rot into a silent blanket
  suppression."
  #{:rf.error/flow-cycle-extract-invariant
    :rf.error/unknown-registry-kind})

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
      ;; A floor well below today's row count — catches a broken parse
      ;; without pinning an exact count (the vocabulary grows; the
      ;; rf2-scuobk throw-axis reconciliation added ~65 rows).
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
