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

  ## The TAGS-COLUMN arm (rf2-6tags)

  Invariants 1-5 reach the `Channel` column and the emit sites. They
  never reached the `:tags` column, and that is where the drift
  accumulated — the EP-0037 planner rows never landed, and
  `:rf.error/resource-route-plan` shipped a Tags cell missing both
  `:plan-cause` and `:contributor`:

    6. TAGS KEYS-SET DIFF — Spec-Schemas.md already defines one canonical
       `*Tags` Malli schema per trace-emitting catalogue row, so every key
       a schema declares must be named in its row's `:tags` cell (minus
       the two envelope-level slots 009 excludes by rule). No new source
       of truth, no roster: the pairing derives the schema name from the
       WHOLE `:operation` and the diff is a set difference. A key must be
       LISTED, not merely mentioned — cross-reference links are stripped
       before keys are harvested (`markdown-link-re`), so a correctly-
       spelled keyword in link text cannot green a cell that misspells
       the key it lists. See the section comment above `spec-schemas-file`
       for what falls out of the pairing by construction and why.
    7. PAIRING COVERAGE LEDGER (rf2-23qsg) — invariant 6 can only diff
       the rows it PAIRS, so losing a pairing loses coverage silently.
       `tags-column-paired-floor` records how many schemas the diff
       reaches; it reds when that drops, which is what a deleted or
       renamed-away schema does and what `CLAIMED == PAIRED` can never
       see (both numbers move together). One integer, so it enumerates
       no members and stays inside the rf2-6tags no-roster ruling.

  JVM-only (`.clj`, NOT `*-cljs-test`): it `slurp`s repo markdown + source
  files, which only the JVM `clojure -M:test` runner can do. The exercise
  leg is the dual-runtime half."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
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

(def ^:private catalogue-tag-row-re
  "Matches one catalogue table row for the TAGS arm, capturing only the
  `:operation` category keyword. The `:tags` cell is read positionally
  (see `parse-catalogue-tag-rows`) rather than through the regex — the
  cell is long, link-bearing prose and a regex over it is a liability.

  Anchored the same way `catalogue-row-re` is, so the retired-row
  sentinel (a strikethrough `~~:rf...~~` first cell) does not match: a
  struck row documents a category the runtime no longer emits and has no
  live `:tags` payload to reconcile."
  #"^\|\s*`(:rf\.[^`]+)`\s*\|")

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
  throw `:rf.error/invalid-cofx` (NOT gated on `interop/debug-enabled?`, so it
  fires in `:advanced` production) is diagnostic-channel: it is a pure
  `throw-error!` that does NOT fan out on the error-emit listener, so it rides
  the diagnostic channel for catalogue purposes (the catalogue's
  thrown-ex-info-is-diagnostic rule).

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

(def ^:private always-on-mechanism-re
  "`(… emit-error-both! :rf.<area>/<cat> …)` / `(… dispatch-on-error!
  :rf.<area>/<cat> …)` — the `emit-error-re` alternation NARROWED to the two
  ALWAYS-ON chokepoints alone (rf2-h4f0n). `dispatch-on-error!` is the
  production-survivable error-emit axis (surface #4), NOT gated on
  `interop/debug-enabled?`, and `emit-error-both!`'s axis 1 IS
  `dispatch-on-error!` — so a category passed literally to either fn reaches
  off-box shippers from an `:advanced` + `goog.DEBUG=false` build, whatever
  the catalogue says. The trace-only fns (`emit-error!` / `emit-warning!`)
  are deliberately absent: a diagnostic-catalogued category may ride those.

  Same CONSERVATIVE limitation as the shared scan: a category emitted through
  the always-on mechanism only via a VARIABLE arg (`:rf.error/handler-
  exception` — the router computes the category and passes a variable) is not
  captured; literal-only stays literal-only, under-reporting never
  false-positives."
  (re-pattern (str "(?:emit-error-both!|dispatch-on-error!)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(defn- always-on-mechanism-categories
  "The SET of categories passed as a LITERAL first arg to `emit-error-both!`
  / `dispatch-on-error!` anywhere in non-test runtime source — the categories
  the code fans onto the always-on axis regardless of their catalogue Channel
  cell (rf2-h4f0n)."
  []
  (->> (non-test-source-files)
       (mapcat (fn [f] (map second (re-seq always-on-mechanism-re (slurp f)))))
       (map (fn [s] (keyword (subs s 1))))
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
  throws — each a DIAGNOSTIC catalogue row, including the production-reachable
  dispatch-boundary throw `:rf.error/invalid-cofx`: it fires in production but
  is a pure `throw-error!` that never reaches the error-emit listener, so it
  rides the diagnostic channel for catalogue purposes). The allow-list below
  holds ONLY the residue that must NOT become a catalogue row.

  TOTALITY — the allow-list is now EMPTY (rf2-cs0kd1):
    - `:rf.error/flow-cycle-extract-invariant` — `re-frame.flows.topo`'s
      cycle-path-extraction dead-end guard (fires ONLY on an impossible-by-
      construction internal-invariant violation, a framework bug) was the LAST
      allow-list entry. Mike ruled Option A (2026-07-10): ONE catalogue holds
      everything — no out-of-catalogue exemption registry, no renames. It is now
      CATALOGUED in Spec 009 §Error event catalogue (diagnostic channel,
      `:no-recovery`) and DROPPED from this list, which is therefore EMPTY. The
      seam is KEPT (a future deliberate non-catalogue emit could land here with a
      rationale), but the terminal-state posture is TOTALITY — every emitted
      category is catalogued, so `allow-list-stays-honest` now guards an empty
      set.

  rf2-ho20xj CATALOGUE FIX (verified from #5229 impl-review):
    - `:rf.error/unknown-registry-kind` — `re-frame.registrar/register!`'s
      unknown-kind guard was held here as an INTERNAL-INVARIANT / framework-bug
      category (a mis-wired internal caller, not ordinary app code). The 009
      catalogue's own co-edit invariant (\"Every `:rf.<area>/<category>` error /
      warning / advisory event MUST land as a row in this catalogue\") carries
      no internal-invariant carve-out, so the omission was a genuine gap — it
      IS now CATALOGUED in Spec 009 §Error event catalogue (diagnostic
      channel, `:fix-registration` recovery) and therefore DROPPED from this
      allow-list, as `allow-list-stays-honest` requires the moment the row
      lands. (The sibling `:rf.error/flow-cycle-extract-invariant` was likewise
      catalogued later under rf2-cs0kd1 — see the TOTALITY note above.)

  EP-0025 PURGE TRANSITION (rf2-j3jlgu / rf2-5fqlz1):
    - `:rf.error/bad-classification` is now CATALOGUED in Spec 009 §Error event
      catalogue (B6, rf2-5fqlz1 — the EP-0025 successor row that renamed the
      removed `:rf.error/bad-marks` row). It is therefore DROPPED from this
      allow-list, as `allow-list-stays-honest` requires the moment the row lands.

  EP-0026 IMAGE-API SIMPLIFICATION (rf2-6ls85a — image-order resolution):
    - `:rf.error/image-within-image-collision` and `:rf.error/image-duplicate-
      image-id` are now CATALOGUED in Spec 009 §Error event catalogue (the
      spec-normative-review reconciliation pass that landed the EP-0026
      image-order-resolution rows held here transitionally since rf2-6ls85a).
      They are therefore DROPPED from this allow-list, as
      `allow-list-stays-honest` requires the moment the rows land.

  rf2-rf3zgt DEV-HOT-RELOAD DIAGNOSTIC PAIR (rf2-lh9ioj CATALOGUE FIX):
    - `:rf.warning/reprojection-failed` — `re-frame.live-frame`'s deferred
      (`next-tick`) reprojection sweep now DIAGNOSES a per-frame assembly
      failure on the trace channel instead of silently aborting the whole
      sweep (the mid-sweep-abort defect the bead fixed); carries `:frame` +
      `:exception`.
    - `:rf.warning/reprojection-flush-failed` — the same ns's belt-and-
      suspenders outer catch around the deferred flush, for a failure outside
      the per-frame boundary (vanishingly unlikely — enumerating live frame
      ids, the dirty-flag swap itself).
      Both were DIAGNOSTIC-channel-only (dev hot-reload, gated on
      `interop/debug-enabled?` inside `trace/emit-error!` — zero production
      cost) and were added by a worker scoped to `live_frame.cljc` alone (not
      the hot-zone Spec 009 file); held transitionally per this list's own
      convention (see the `:rf.error/cofx-registration-invalid` precedent
      above). rf2-lh9ioj now CATALOGUES both in Spec 009 §Error event
      catalogue (diagnostic channel) and DROPS them from this allow-list, as
      `allow-list-stays-honest` requires the moment the rows land.

  `allow-list-stays-honest` fails if any entry becomes catalogued or stops being
  emitted, forcing the co-edit so the list cannot rot into a silent blanket
  suppression."
  #{})

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

(deftest always-on-mechanism-emits-are-catalogued-always-on
  (testing "Per rf2-h4f0n: every category passed as a LITERAL to
            `emit-error-both!` / `dispatch-on-error!` in non-test runtime
            source must be catalogued `always-on`. The two fns ARE the
            always-on axis (surface #4, not debug-gated), so a category
            emitted through them and catalogued `diagnostic` is a stale
            Channel cell over a production-reaching emission — exactly the
            drift that let `:rf.error/classification-effect-shape` and
            `:rf.error/legacy-runtime-root` reach Sentry/Datadog from a
            `goog.DEBUG=false` build while their rows read `diagnostic`.
            The prior invariants never compared the emit MECHANISM against
            the Channel column, so that state passed every test; this makes
            the class unreintroducible. Literal-only, like the shared scan:
            a VARIABLE category arg (`:rf.error/handler-exception`) is out
            of scope by design."
    (let [channel-by-cat (into {} (map (juxt :category :channel))
                                (parse-catalogue))
          not-always-on  (->> (always-on-mechanism-categories)
                              (remove #(= "always-on" (channel-by-cat %)))
                              sort)]
      (is (empty? not-always-on)
          (str "categories emitted through the always-on mechanism "
               "(emit-error-both! / dispatch-on-error!) whose Spec 009 "
               "catalogue row is NOT `always-on` (rf2-h4f0n): "
               (pr-str (mapv (juxt identity channel-by-cat) not-always-on))
               " — either graduate the row to `always-on` (and add the "
               "category to the exercise literal in the same commit) or "
               "demote the emission to the trace-only fns.")))))

;; ---------------------------------------------------------------------------
;; The TAGS-COLUMN arm (rf2-6tags) — the catalogue's sixth column against the
;; canonical `*Tags` schema, which nothing had ever compared.
;; ---------------------------------------------------------------------------
;;
;; Everything above pins the CHANNEL column and the emit sites. Nothing reached
;; the `:tags` column, and that is where the drift accumulated: the EP-0037
;; planner rows never landed (`:rf.route/prefetched` appeared ZERO times in the
;; catalogue while shipping), and `:rf.error/resource-route-plan`'s Tags column
;; omitted both `:plan-cause` and `:contributor` while its narrative still
;; taught partial planning against the ratified atomicity rule. A gate whose
;; coverage never reaches the column where drift accumulates keeps reporting
;; green over it.
;;
;; The arm needs NO new source of truth. Spec-Schemas.md already defines one
;; `*Tags` Malli schema per trace-emitting catalogue row, and both files are
;; already parsed here, so the check is a keys-set difference: every key the
;; SCHEMA declares must be named in the ROW's `:tags` cell.
;;
;; DECLINED, and recorded so it is not re-proposed: a trace-OP arm. There is no
;; per-op schema roster to diff against, so the only available check is "is
;; every emitted op documented somewhere in 2500 lines of prose" — a scan with
;; a bad false-positive rate. The expensive half for the weaker signal.
;;
;; WHAT FALLS OUT OF THE PAIRING BY CONSTRUCTION, and why that is not a hole.
;; Spec-Schemas carries a `:tags` schema only for TRACE-EMITTING categories.
;; Thrown-`ex-info` rows and always-on union-record rows have no `:tags` map at
;; all — `re-frame.error/thrown-ex-info` builds a FLAT ex-data map, and Spec 009
;; §The thrown-error shape already declares `:recovery` among four REQUIRED
;; slots on it — so they never derive a schema and the arm never reasons about
;; them. 486 active rows, 93 paired — and those are the trace-emitting ones,
;; which is why the trace-event-scoped exclusion rule below is the right one.
;;
;; PAIRING KEYS OFF THE WHOLE `:operation`, NOT ITS NAME HALF. Spec-Schemas
;; names a schema in one of two ways, and `derived-schema-names` reads both:
;; the category's NAME half (`:rf.error/resource-route-plan` →
;; `ResourceRoutePlanTags`) or the WHOLE operation, namespace tail included
;; (`:rf.fx/handled` → `FxHandledTags`). Two distinct operations can share a
;; name half — `:rf.http/stale-suppressed` and
;; `:rf.route.nav-token/stale-suppressed` both derive `StaleSuppressedTags` —
;; and the schema belongs to the nav-token category alone. That is what the
;; second spelling is for: the author disambiguates IN THE CORPUS by naming the
;; schema for its owning namespace, and the derivation reads what they wrote.
;; No alias table, which is the drift generator this repo keeps removing.
;;
;; AN AMBIGUOUS NAME IS REPORTED, NOT DROPPED (rf2-ehy4l). A derived name
;; claimed by more than one operation still identifies no operation and is
;; diffed against neither — pairing `StaleSuppressedTags` to the HTTP row would
;; manufacture a false pair, not surface debt. But the arm used to drop it in
;; silence, so the pairing quietly ran one row short while both count floors
;; stayed green. `tags-column-ambiguities` reports every collision and
;; `tags-column-pairing-is-live` asserts CLAIMED == PAIRED, so a schema the arm
;; holds but never exercises cannot hide behind a floor. The live collision was
;; resolved the way the report asks: `spec/Spec-Schemas.md` renamed
;; `StaleSuppressedTags` → `RouteNavTokenStaleSuppressedTags`, which pairs the
;; nav-token row on the whole-`:operation` spelling and leaves the HTTP row
;; correctly unpaired.
;;
;; THE SCHEMA-DELETION MUTATION, AND WHY THE IDENTITY COULD NEVER CATCH IT
;; (rf2-23qsg, the revised rf2-6tags ruling). Deleting a SCHEMA that pairs today
;; — say `ResourceRoutePlanTags` — un-pairs its row; renaming it to something no
;; row claims does the same at an unchanged schema count. `CLAIMED == PAIRED` is
;; powerless against both, because it is an IDENTITY BETWEEN TWO NUMBERS THAT
;; MOVE TOGETHER: the deletion takes claimed 93 → 92 and paired 93 → 92, and the
;; equal-count rename does exactly the same. The assertion is true in every
;; mutated world, so it can never red on this class — which is not a gap in the
;; assertion but the wrong KIND of assertion for a coverage question.
;;
;; Catching it BY NAME would need a total, identity-preserving pairing, and the
;; two corpora cannot derive one: 22 of the 111 `*Tags` schemas are legitimately
;; claimed by NO catalogue row (`FxHandledTags`' siblings for non-error ops, and
;; 12 whose operation 009 never names by any derivation), only 5 schema names
;; are cited anywhere in 009, and nothing in `implementation/` references a
;; schema name — so an orphaned schema is indistinguishable from a correctly-
;; unpaired one without a third authority. The rf2-6tags no-roster ruling
;; therefore STANDS rather than needing an exception: no orphan allow-list, and
;; no reading of `implementation/` to discover schema names.
;;
;; The answer is the mechanism the ROW side already ships, applied to the
;; pairing itself. `tags-column-paired-floor` records the paired COUNT, so a
;; drop reds without the arm ever knowing WHICH schema went. One integer, not 22
;; entries: it names no member, so it cannot rot into the hand-maintained roster
;; the ruling forbids, and the only edit it admits is a deliberate lowering in
;; the same commit that removes a pairing.
;;
;; The ROW half of the same mutation is covered separately: deleting
;; `:rf.error/resource-route-plan`'s row makes invariant #5's source scan fire on
;; the now-uncatalogued emitted category. `scripts/check_keyword_catalogue_drift.py`
;; CHECK A does NOT also fire — `catalogue_ids` scans the WHOLE document and the
;; id survives in 009's prose — so invariant #5 is the whole of that coverage.
;; (This comment previously claimed both; rf2-23qsg's mutation proof corrected
;; it, and an overstated cross-gate claim is how a real gap gets left alone.)
;;
;; The two ENVELOPE-level slots are excluded by the catalogue's own rule
;; (009 §Error event catalogue, *Reading the two right-hand columns*):
;; "**On a trace-event row**, two envelope-level slots are excluded from the
;; column **by rule**" — `:recovery` (which `build-event` hoists to the
;; envelope top level) and `:category` (synthesized on the `:error` branch
;; only). That rule is scoped to the TRACE-EVENT reading, which is exactly the
;; set the pairing reaches: rf2-dxuzp rescoped it from "every row" precisely
;; because thrown rows contradict it (009 §The thrown-error shape declares
;; `:recovery` REQUIRED on the flat ex-data map), and those are the rows that
;; already fall out above. So the arm's exclusion and the catalogue's rule
;; agree on the same domain, rather than the arm applying a wider rule than
;; the corpus states. The same bullet says a trace-event row's column "lists
;; the keys that genuinely ride under `:tags` **on the wire**", so every
;; finding is a row the catalogue's own contract requires to change.

(def ^:private spec-schemas-file
  "`spec/Spec-Schemas.md`, resolved the same way `spec-009-file` is."
  (let [nested (io/file "../../spec/Spec-Schemas.md")
        legacy (io/file "../spec/Spec-Schemas.md")]
    (if (.exists nested) nested legacy)))

(def ^:private tags-schema-def-re
  "The opening of a canonical per-category tags schema in Spec-Schemas.md —
  `(def <Pascal>Tags`, at the start of a line inside a ```clojure fence."
  #"(?m)^\(def ([A-Za-z0-9]+Tags)\s")

(defn- read-form-at
  "The single Clojure form beginning at `idx` in `text`, read as DATA. The
  schemas are Malli vectors, so `edn/read` is both sufficient and safer than
  the full reader; `:default` keeps an unexpected tagged literal from throwing."
  [^String text ^long idx]
  (with-open [r (java.io.PushbackReader.
                  (java.io.StringReader. (subs text idx)))]
    (edn/read {:eof nil :default (fn [_tag v] v)} r)))

(defn- schema-map-keys
  "The top-level entry keys of the first `[:map …]` inside a parsed
  `(def XxxTags …)` form. Descends because a schema may wrap its map
  (`[:and [:map …] …]`); takes only DIRECT entries of that map, so a nested
  `[:map …]` inside an entry's value does not contribute keys. A properties
  map after `:map` is skipped (it is not a vector)."
  [form]
  (if-let [m (->> (tree-seq coll? seq form)
                  (filter #(and (vector? %) (= :map (first %))))
                  first)]
    (into #{} (comp (filter vector?) (map first) (filter keyword?)) (rest m))
    #{}))

(defn- parse-tags-schemas
  "`{\"HandlerExceptionTags\" #{:category :failing-id …}, …}` — every
  `*Tags` schema Spec-Schemas.md defines, by name, with its declared key set."
  ([] (parse-tags-schemas (slurp spec-schemas-file)))
  ([text]
   (let [m (re-matcher tags-schema-def-re text)]
     (loop [acc {}]
       (if (.find m)
         (recur (assoc acc (.group m 1)
                       (schema-map-keys (read-form-at text (.start m)))))
         acc)))))

(def ^:private tags-cell-key-re
  "A `:tags` cell entry: a back-ticked span that is EXACTLY one keyword.
  Anchored on the whole span on purpose — the cells carry long prose in which
  a bare `:foo` appears inside sentences and inside multi-token spans like
  `` `:op :subscribe` ``. Reading those as documented keys would make the arm
  MORE permissive (findings are schema keys the cell omits), i.e. would turn a
  real red into a green. The narrow rule is the safe direction."
  #"`(:[\w.*+!?<>=/'-]+)`")

(def ^:private markdown-link-re
  "A markdown inline link — `[text](target)` — anywhere in a `:tags` cell.
  Thirteen live cells carry one, all cross-references to an owning spec
  section (`per [014 §Privacy](014-HTTPRequests.md#privacy)`).

  WHY THE LINK IS STRIPPED BEFORE KEYS ARE HARVESTED. `tags-cell-key-re` reads
  ANY back-ticked keyword span in the cell, so a keyword sitting in a link's
  TEXT counted as a documented key — and a cross-reference is PROSE ABOUT a
  key, not the row listing it. That is a false-green generator in the one
  direction that matters: a cell may MISSPELL the key it lists and still
  satisfy the diff off a correctly-spelled mention in an adjacent link, which
  is precisely the drift the arm exists to catch. Reproduced against the live
  corpus before this rule landed — respelling
  `:rf.error/resource-route-plan`'s `:plan-cause` as `:plan-cauze` while
  adding `(see [`:plan-cause`](#error-event-catalogue))` to the same cell ran
  the suite GREEN; `tags-column-key-in-link-text-is-not-a-documented-key`
  pins it.

  Stripping the WHOLE construct (text and target) is the rule, not just the
  text: a target carries no back-ticks so it can hold no key, and removing the
  pair together needs no reasoning about which half a match fell in. Zero keys
  leave the harvest on today's corpus — no live cell documents a key only from
  inside a link — so the rule is a hardening, not a corpus change."
  #"\[[^\]]*\]\([^)]*\)")

(defn- cell-tag-keys
  "The keys a `:tags` cell DOCUMENTS — every back-ticked lone keyword outside a
  markdown cross-reference link (see `markdown-link-re` for why the link goes
  first)."
  [cell]
  (into #{} (map (comp keyword #(subs % 1) second))
        (re-seq tags-cell-key-re (str/replace cell markdown-link-re " "))))

(defn- parse-catalogue-tag-rows
  "`[{:category <kw> :tags-cell <string>} …]` for every ACTIVE row of the
  canonical catalogue section. The `:tags` cell is the SIXTH column; `-1` keeps
  trailing empties so a row whose `:tags` cell is BLANK still parses (and so
  still reds when its schema declares keys)."
  ([] (parse-catalogue-tag-rows (slurp spec-009-file)))
  ([text]
   (->> (str/split-lines text)
        (catalogue-section-lines)
        (keep (fn [line]
                (when-let [[_ cat-str] (re-find catalogue-tag-row-re line)]
                  (let [cells (str/split line #"\|" -1)]
                    (when (>= (count cells) 7)
                      {:category  (keyword (subs cat-str 1))
                       :tags-cell (str/trim (nth cells 6))})))))
        vec)))

(defn- pascal
  "`\"resource-route-plan\"` → `\"ResourceRoutePlan\"`."
  [s]
  (->> (str/split s #"[-.]") (map str/capitalize) (apply str)))

(defn- derived-schema-names
  "The canonical `*Tags` schema names an `:operation` may carry — BOTH
  spellings Spec-Schemas actually uses, so the pairing keys off the whole
  `:operation` rather than only its name half:

    the NAME half alone   `:rf.error/resource-route-plan` → `ResourceRoutePlanTags`
    the WHOLE operation   `:rf.fx/handled`                → `FxHandledTags`
                          `:rf.http.interceptor/registered` → `HttpInterceptorRegisteredTags`
                          `:rf.route.nav-token/stale-suppressed`
                                            → `RouteNavTokenStaleSuppressedTags`

  Reading both is what resolves a shared name half WITHOUT an alias table
  (rf2-ehy4l): the author disambiguates in the corpus, by naming the schema
  for its namespace, and the derivation simply reads what they wrote. Four
  rows the name-half-only derivation could never reach — `:rf.fx/handled`,
  `:rf.fx/skipped-on-platform`, `:rf.http.interceptor/registered` and
  `:rf.http.interceptor/cleared` — pair on the second spelling."
  [category]
  (let [nspace (namespace category)
        tail   (when (str/starts-with? (str nspace) "rf.") (subs nspace 3))
        nm     (pascal (name category))]
    (cond-> [(str nm "Tags")]
      tail (conj (str (pascal tail) nm "Tags")))))

(defn- schema-claims
  "`{\"SchemaName\" [row …]}` — every canonical schema an ACTIVE catalogue row
  CLAIMS, by either spelling. A schema claimed by exactly ONE row is paired
  and gets its keys diffed; a schema claimed by MORE THAN ONE identifies no
  operation and is reported (see `tags-column-ambiguities`) rather than
  dropped. Silently dropping it was the coverage the arm lost without saying
  so."
  [rows schemas]
  (reduce (fn [acc row]
            (reduce (fn [acc nm]
                      (cond-> acc
                        (contains? schemas nm) (update nm (fnil conj []) row)))
                    acc
                    (derived-schema-names (:category row))))
          {}
          rows))

(def ^:private envelope-only-tag-keys
  "The two slots 009 excludes from every row's `:tags` cell BY RULE, so a
  schema declaring them is not evidence of a row defect."
  #{:recovery :category})

(defn tags-column-findings
  "`[{:category … :schema … :missing #{…}} …]` — every paired row whose `:tags`
  cell omits a key its canonical schema declares. Pure over its two inputs so
  the non-vacuity proof can drive it with synthetic corpora."
  [rows schemas]
  (->> (schema-claims rows schemas)
       (keep (fn [[schema-name group]]
               (when (= 1 (count group))
                 (let [row     (first group)
                       missing (set/difference (get schemas schema-name)
                                               envelope-only-tag-keys
                                               (cell-tag-keys (:tags-cell row)))]
                   (when (seq missing)
                     {:category (:category row)
                      :schema   schema-name
                      :missing  missing})))))
       (sort-by :category)
       vec))

(defn tags-column-ambiguities
  "`[{:schema … :categories [… …]} …]` — every canonical schema whose name is
  claimed by MORE THAN ONE active catalogue row. Such a schema identifies no
  operation, so it cannot be diffed against any row — but that is a REPORTABLE
  loss of coverage, not a quiet one (rf2-ehy4l). The corpus resolves it by
  naming the schema for its owning namespace, which the second derivation
  spelling then reads; until it does, the pairing is one row short and says
  so. Pure over its two inputs."
  [rows schemas]
  (->> (schema-claims rows schemas)
       (keep (fn [[schema-name group]]
               (when (< 1 (count group))
                 {:schema schema-name :categories (mapv :category group)})))
       (sort-by :schema)
       vec))

(defn- claimed-count
  "How many canonical schemas at least one active row claims."
  [rows schemas]
  (count (schema-claims rows schemas)))

(defn- paired-count
  "How many claimed schemas resolve to exactly one row — the population the
  keys-set diff actually reaches."
  [rows schemas]
  (->> (schema-claims rows schemas) vals (filter #(= 1 (count %))) count))

(def ^:private tags-column-paired-floor
  "THE PAIRING COVERAGE LEDGER — one integer, and the whole answer to rf2-23qsg.

  How many canonical schemas the keys-set diff reaches today. Re-derive with
  `(paired-count (parse-catalogue-tag-rows) (parse-tags-schemas))`.

  WHY A COUNT AND NOT A ROSTER. Deleting a paired schema, or renaming one to a
  name no row claims, un-pairs a row silently: `CLAIMED == PAIRED` holds in both
  mutated worlds because both numbers move together, and the old slack `>= 80`
  never noticed. Identifying the missing schema BY NAME is not derivable from
  the two corpora (see the section comment above), and the rf2-6tags ruling bars
  the hand-maintained orphan list that would fake it. A count needs no names: it
  drops, and it reds.

  SHRINK-ONLY, in the sense its sibling `tags-column-shrink-only-baseline` is —
  the number may not move by accident in EITHER direction. Removing a pairing
  reds `tags-column-pairing-is-live`, and the fix is to lower this integer in
  the SAME commit, with the removal in the diff next to it. Adding one reds
  `tags-column-baseline-stays-honest`, because a floor left below the coverage
  the corpus already achieves has rotted: it would sit there absorbing the next
  deletion in silence, which is the drift this ledger exists to make impossible.
  Either way the edit is deliberate, reviewable, and one line.

  Raised 93 → 94 by rf2-6tags's `SchemaValidationTags` → `SchemaValidationFailureTags`
  rename (spec/Spec-Schemas.md): the PR #7207 audit found the always-on
  `:rf.error/schema-validation-failure` row was unpaired from the start — its
  derived name `SchemaValidationFailureTags` never matched the schema's
  as-shipped name `SchemaValidationTags`, so invariant 6 held a schema
  (claimed) it never diffed (paired), the same class `tags-column-pairing-is-
  unambiguous` catches for a collision, just silent instead of reported
  because there was no second claimant to trip `tags-column-ambiguities`.
  Renaming the schema to the name-half derivation — the `StaleSuppressedTags`
  worked example, applied here — pairs it with no alias table and no code
  change; the row's Tags cell was completed alongside it (see
  `tags-column-schema-validation-failure-row-is-paired-and-mutation-proven`
  below) so the newly-live pairing does not immediately red."
  94)

(def ^:private tags-column-shrink-only-baseline
  "SHRINK-ONLY. The rows that still red when the arm is armed — pre-existing
  debt the arm did not create, held so the arm can ship rather than waiting on
  a corpus reconciliation. Entries LEAVE this set and never join it: a NEW
  omission fails immediately, and `tags-column-baseline-stays-honest` fails the
  moment a listed row is fixed, forcing the drop in the SAME PR.

  EMPTY, and that is the finished state — rf2-zk1xu reconciled the corpus
  rather than leaving debt parked here. The last entry was
  `:rf.ssr/hydration-mismatch`, whose Tags cell spelled the optional wire key
  `:first-diff-path?`; the trailing `?` was never part of the key (Spec 011
  §The `:first-diff-path` tag names `:first-diff-path`,
  `re-frame.ssr.hydrate/verify-hydration!` conditionally `assoc`es
  `:first-diff-path`, and `HydrationMismatchTags` declares
  `[:first-diff-path {:optional true} [:vector :any]]`) — it was the row author
  writing optionality into the key rather than into prose. The cell now names
  the literal key and states optionality in prose, which is also rf2-zk1xu's
  'prove the Tags-column key parser observes the literal key' clause: with the
  `?` gone, `tags-cell-key-re` reads `:first-diff-path` and the diff empties.

  An empty baseline means `tags-column-keys-are-documented` is now an
  unqualified invariant: the next undocumented schema key reds it outright.
  Do NOT re-add an entry to buy a red back off — the set shrinks only, so a
  new omission is a corpus fix, not a baseline edit."
  #{})

(deftest tags-column-pairing-is-live
  (testing "Sanity, in the shape the sibling scans use: the arm actually reaches
            both corpora. A derivation change, a Spec-Schemas fence rename, or
            a catalogue column reorder would collapse the pairing to zero and
            every finding-based invariant below would pass VACUOUSLY. The
            schema-count floor is COLLAPSE insurance; the PAIRED floor is the
            coverage ledger (rf2-23qsg) — it is the only assertion here that
            moves when a paired schema is deleted or renamed away, because the
            CLAIMED == PAIRED identity below it holds in both mutated worlds."
    (let [rows    (parse-catalogue-tag-rows)
          schemas (parse-tags-schemas)]
      (is (.exists spec-schemas-file)
          (str "spec/Spec-Schemas.md not found at " spec-schemas-file))
      (is (>= (count schemas) 100)
          (str "Spec-Schemas.md yielded the per-category tags schemas "
               "(>= 100), not a partial parse; found " (count schemas)))
      ;; THE COVERAGE LEDGER (rf2-23qsg). Not collapse insurance: this is the
      ;; assertion that reds when a paired schema is DELETED or renamed to a
      ;; name no row claims. Both mutations drop the paired count by one and
      ;; leave every other guard here green.
      (is (>= (paired-count rows schemas) tags-column-paired-floor)
          (str "canonical schemas PAIRED and diffed: "
               (paired-count rows schemas) ", below the recorded floor of "
               tags-column-paired-floor ". A pairing was lost — a `*Tags` "
               "schema deleted, or renamed to a name no catalogue row derives. "
               "Restore the pairing, or lower `tags-column-paired-floor` IN "
               "THIS COMMIT, with the removal in the diff beside it."))
      ;; Anchor on a schema whose keys are non-trivial, so a parse that finds
      ;; the def but reads an empty key set is caught…
      (is (contains? (get schemas "HandlerExceptionTags") :exception-message)
          "HandlerExceptionTags parsed with its declared keys")
      ;; …and generalise that anchor: EVERY parsed schema must have declared
      ;; keys. One named schema proves the reader works on one shape; a schema
      ;; that parses to `#{}` contributes a pair that can never produce a
      ;; finding, which is coverage in name only.
      (is (empty? (->> schemas (filter (comp empty? val)) (map key) sort))
          (str "`*Tags` schemas that parsed with an EMPTY key set — the reader "
               "found the def and lost its `[:map …]`: "
               (pr-str (->> schemas (filter (comp empty? val)) (map key) sort))))
      ;; THE RATCHET ON PAIRING IDENTITY. Every schema an active row claims
      ;; must resolve to exactly one row and be diffed. Before rf2-ehy4l a
      ;; collision was dropped silently: 89 schemas were claimed, 88 diffed,
      ;; and the difference was invisible because both numbers sat above their
      ;; floors. This is an identity, not a count — it holds at any corpus
      ;; size, and it is what makes the ambiguity test below unmissable.
      (is (= (claimed-count rows schemas) (paired-count rows schemas))
          (str "canonical schemas CLAIMED by an active catalogue row: "
               (claimed-count rows schemas) ", schemas actually PAIRED and "
               "diffed: " (paired-count rows schemas) ". The difference is "
               "coverage the arm holds and does not exercise — see "
               "`tags-column-pairing-is-unambiguous` for the offenders.")))))

(deftest tags-column-pairing-is-unambiguous
  (testing "A canonical schema name claimed by two operations identifies
            neither, so it can be diffed against neither — a real loss of
            coverage that used to happen SILENTLY (rf2-ehy4l). The corpus
            resolves it by naming the schema for its owning namespace, which
            `derived-schema-names`' second spelling reads; reporting it is
            what forces that resolution instead of letting the pairing quietly
            run one row short."
    (let [ambiguous (tags-column-ambiguities (parse-catalogue-tag-rows)
                                             (parse-tags-schemas))]
      (is (empty? ambiguous)
          (str "canonical `*Tags` schemas claimed by more than one catalogue "
               "row. Rename the schema for its owning operation "
               "(`StaleSuppressedTags` → `RouteNavTokenStaleSuppressedTags` is "
               "the worked example) so the whole-`:operation` spelling pairs "
               "it, and leave the other row unpaired: "
               (pr-str ambiguous))))))

(deftest tags-column-keys-are-documented
  (testing "Per rf2-6tags: every key a canonical `*Tags` schema declares must
            be named in its catalogue row's `:tags` cell, minus the two
            envelope-level slots 009 excludes by rule. This is the column the
            ratchet never reached — the one the EP-0037 planner rows drifted in."
    (let [findings (tags-column-findings (parse-catalogue-tag-rows)
                                         (parse-tags-schemas))
          beyond   (remove (comp tags-column-shrink-only-baseline :category)
                           findings)]
      (is (empty? beyond)
          (str "catalogue rows whose `:tags` cell omits keys their canonical "
               "Spec-Schemas schema declares (rf2-6tags). Either add the keys "
               "to the row, or correct the schema if the SCHEMA is the wrong "
               "side (several of the rf2-zk1xu findings were): "
               (pr-str (mapv (juxt :category :schema (comp sort :missing))
                             beyond)))))))

(deftest tags-column-baseline-stays-honest
  (testing "The shrink-only baseline may only shrink. An entry that no longer
            reds must be DROPPED in the same PR that fixes it — otherwise the
            set rots into a blanket suppression, exactly as
            `allow-list-stays-honest` guards its sibling."
    (let [redding (->> (tags-column-findings (parse-catalogue-tag-rows)
                                             (parse-tags-schemas))
                       (map :category)
                       set)
          stale   (set/difference tags-column-shrink-only-baseline redding)]
      (is (empty? stale)
          (str "tags-column-shrink-only-baseline entries that no longer red — "
               "drop them from the set in this file (rf2-6tags / rf2-zk1xu): "
               (pr-str (sort stale))))))
  (testing "…and so does the paired-count ledger, in the other direction
            (rf2-23qsg). A floor left BELOW the coverage the corpus already
            achieves is stale, and stale in the dangerous way: it silently
            absorbs the next lost pairing. Recording the gain is the same
            one-line edit that lowering it would be."
    (let [paired (paired-count (parse-catalogue-tag-rows) (parse-tags-schemas))]
      (is (<= paired tags-column-paired-floor)
          (str "the pairing now reaches " paired " canonical schemas but "
               "`tags-column-paired-floor` still records "
               tags-column-paired-floor ". Raise it to " paired " in this PR: "
               "a floor below live coverage protects nothing above itself.")))))

;; --- non-vacuity ------------------------------------------------------------
;;
;; An arm that cannot red on the defect that motivated it has not been tested,
;; and this repo has shipped exactly that mistake before. The corpora below
;; reproduce `:rf.error/resource-route-plan` as it stood at ded86cff64 — the
;; parent of the rf2-wsopx fix — where the row genuinely omitted `:plan-cause`
;; and `:contributor`. Run against the historical files themselves the arm
;; reports 22 findings including that one; here the same pair is inlined so the
;; proof stays runnable after those commits scroll away.

(def ^:private pre-wsopx-schemas
  {"ResourceRoutePlanTags"
   #{:category :reason :route-id :frame :contributor :plan-cause}})

(defn- catalogue-fixture
  "A minimal catalogue section carrying `rows` verbatim, so the real parser
  (section scoping, column split, cell extraction) is what is exercised."
  [& rows]
  (str/join "\n"
            (concat ["### Error event catalogue"
                     ""
                     "| `:operation` | `:op-type` | Channel | Trigger | Default `:recovery` | `:tags` |"
                     "|---|---|---|---|---|---|"]
                    rows
                    ["" "### Schemas" ""])))

(deftest tags-column-arm-reds-on-its-motivating-defect
  (testing "Per rf2-6tags the arm MUST red against the pre-rf2-wsopx catalogue
            state. The row below is the pre-fix cell: it names the four keys it
            documented and omits the two the schema declares."
    (let [pre  (parse-catalogue-tag-rows
                 (catalogue-fixture
                   (str "| `:rf.error/resource-route-plan` | `:error` | diagnostic "
                        "| A route resource plan failed. | `:no-recovery` "
                        "| `:route-id`, `:reason`, `:frame` |")))
          post (parse-catalogue-tag-rows
                 (catalogue-fixture
                   (str "| `:rf.error/resource-route-plan` | `:error` | diagnostic "
                        "| A route resource plan failed. | `:no-recovery` "
                        "| `:route-id`, `:reason`, `:frame`, `:contributor`, "
                        "`:plan-cause` |")))]
      (is (= [{:category :rf.error/resource-route-plan
               :schema   "ResourceRoutePlanTags"
               :missing  #{:contributor :plan-cause}}]
             (tags-column-findings pre pre-wsopx-schemas))
          "the arm reds on the exact defect that motivated it")
      (is (empty? (tags-column-findings post pre-wsopx-schemas))
          "…and greens on the fix, so the red is the defect and not the arm"))))

(deftest tags-column-key-in-link-text-is-not-a-documented-key
  (testing "A key named only inside a markdown cross-reference link is PROSE
            ABOUT the key, not the row listing it — so it must not satisfy the
            diff. Without this rule the arm greens on a cell that MISSPELLS the
            key it lists, as long as some adjacent link happens to spell it
            correctly, which is the exact drift class the arm exists to catch.
            The two rows below are the same defect with and without the link;
            both must report the same finding."
    (let [row (fn [tags]
                (parse-catalogue-tag-rows
                  (catalogue-fixture
                    (str "| `:rf.error/resource-route-plan` | `:error` | diagnostic "
                         "| A route resource plan failed. | `:no-recovery` | "
                         tags " |"))))
          expected [{:category :rf.error/resource-route-plan
                     :schema   "ResourceRoutePlanTags"
                     :missing  #{:plan-cause}}]]
      (is (= expected
             (tags-column-findings
               (row "`:route-id`, `:reason`, `:frame`, `:contributor`, `:plan-cauze`")
               pre-wsopx-schemas))
          "the misspelling alone reds")
      (is (= expected
             (tags-column-findings
               (row (str "`:route-id`, `:reason`, `:frame`, `:contributor`, "
                         "`:plan-cauze` (see [`:plan-cause`](#error-event-catalogue))"))
               pre-wsopx-schemas))
          "…and STILL reds when a link in the same cell spells the key correctly
           — the link text is not the row's key list")
      (is (empty?
            (tags-column-findings
              (row (str "`:route-id`, `:reason`, `:frame`, `:contributor`, "
                        "`:plan-cause` (see [011 §The tag](011-SSR.md#the-tag))"))
              pre-wsopx-schemas))
          "…while an ordinary cross-reference beside a correctly-listed key is
           untouched, so the rule costs the corpus nothing"))))

(deftest tags-column-arm-excludes-only-the-two-envelope-slots
  (testing "`:recovery` and `:category` are envelope-level by the catalogue's
            own rule, so a schema declaring them is not a row defect. Nothing
            else is exempt: an ordinary key the cell omits still reds."
    (let [rows (parse-catalogue-tag-rows
                 (catalogue-fixture
                   (str "| `:rf.error/resource-route-plan` | `:error` | diagnostic "
                        "| … | `:no-recovery` | `:route-id` |")))]
      (is (= [{:category :rf.error/resource-route-plan
               :schema   "ResourceRoutePlanTags"
               :missing  #{:reason :frame :contributor :plan-cause}}]
             (tags-column-findings rows pre-wsopx-schemas))
          "`:category` and `:recovery` are absent from the missing set; every
           other undocumented key is present"))))

(deftest tags-column-pairing-keys-off-the-whole-operation
  (testing "Two operations sharing a name half derive one schema name and so
            identify no operation. Pairing `StaleSuppressedTags` to
            `:rf.http/stale-suppressed` — an `:info` reply-family trace with a
            different payload entirely — would manufacture a finding that is a
            false pair, not debt. So the ambiguous name pairs with neither; but
            per rf2-ehy4l it must SAY SO, because dropping it silently is a
            pair of rows' worth of coverage vanishing behind a count floor."
    (let [schemas {"StaleSuppressedTags" #{:category :carried-token
                                           :current-token :rf.trace/event-id}}
          http-row (str "| `:rf.http/stale-suppressed` | `:info` | diagnostic "
                        "| A stale HTTP reply was suppressed. | `:dropped` "
                        "| `:rf.reply/status` |")
          nav-row  (str "| `:rf.route.nav-token/stale-suppressed` | `:error` "
                        "| diagnostic | A stale navigation result was suppressed. "
                        "| `:dropped` | `:carried-token`, `:current-token` |")
          both     (parse-catalogue-tag-rows (catalogue-fixture http-row nav-row))]
      (is (empty? (tags-column-findings both schemas))
          "an ambiguous derived name is diffed against neither operation")
      (is (= [{:schema     "StaleSuppressedTags"
               :categories [:rf.http/stale-suppressed
                            :rf.route.nav-token/stale-suppressed]}]
             (tags-column-ambiguities both schemas))
          "…and the collision is REPORTED rather than silently costing the pair")
      (is (= [{:category :rf.route.nav-token/stale-suppressed
               :schema   "StaleSuppressedTags"
               :missing  #{:rf.trace/event-id}}]
             (tags-column-findings
               (parse-catalogue-tag-rows (catalogue-fixture nav-row))
               schemas))
          "…and the owning row alone still pairs, so ambiguity costs coverage
           only where the corpus is genuinely ambiguous"))))

(deftest tags-column-collision-resolves-by-naming-the-owning-operation
  (testing "The corpus fix for a collision, exercised end to end — this is what
            `spec/Spec-Schemas.md` did to `StaleSuppressedTags` (rf2-ehy4l).
            Naming the schema for its owning operation makes the WHOLE-
            `:operation` spelling pair it, leaves the unrelated row unpaired,
            and needs no alias table: the derivation reads the name the author
            wrote."
    (let [schemas  {"RouteNavTokenStaleSuppressedTags"
                    #{:category :carried-token :current-token :rf.trace/event-id}}
          http-row (str "| `:rf.http/stale-suppressed` | `:info` | diagnostic "
                        "| A stale HTTP reply was suppressed. | `:dropped` "
                        "| `:rf.reply/status` |")
          nav-row  (str "| `:rf.route.nav-token/stale-suppressed` | `:error` "
                        "| diagnostic | A stale navigation result was suppressed. "
                        "| `:dropped` | `:carried-token`, `:current-token` |")
          both     (parse-catalogue-tag-rows (catalogue-fixture http-row nav-row))]
      (is (empty? (tags-column-ambiguities both schemas))
          "the renamed schema is claimed by one operation only")
      (is (= [{:category :rf.route.nav-token/stale-suppressed
               :schema   "RouteNavTokenStaleSuppressedTags"
               :missing  #{:rf.trace/event-id}}]
             (tags-column-findings both schemas))
          "the owning row is paired and diffed; the HTTP row stays unpaired")
      (is (= 1 (paired-count both schemas) (claimed-count both schemas))
          "claimed == paired: nothing is held and left unexercised"))))

(deftest tags-column-claimed-equals-paired-catches-the-silent-drop
  (testing "The identity `tags-column-pairing-is-live` asserts, driven at the
            mutation it exists for. A collision holds a schema (claimed) that
            no diff reaches (unpaired), and BOTH counts stay above the broad
            floors — which is exactly how the loss used to hide."
    (let [schemas  {"StaleSuppressedTags" #{:category :carried-token}}
          http-row (str "| `:rf.http/stale-suppressed` | `:info` | diagnostic "
                        "| … | `:dropped` | `:rf.reply/status` |")
          nav-row  (str "| `:rf.route.nav-token/stale-suppressed` | `:error` "
                        "| diagnostic | … | `:dropped` | `:carried-token` |")
          one      (parse-catalogue-tag-rows (catalogue-fixture nav-row))
          both     (parse-catalogue-tag-rows (catalogue-fixture http-row nav-row))]
      (is (= 1 (claimed-count one schemas) (paired-count one schemas))
          "unambiguous: claimed == paired")
      (is (= [1 0] [(claimed-count both schemas) (paired-count both schemas)])
          "ambiguous: the schema is claimed but never diffed — the identity
           reds where a `>= 80` floor would not have moved"))))

(deftest tags-column-paired-floor-reds-on-a-lost-pairing
  (testing "Non-vacuity for the coverage ledger (rf2-23qsg), on the two
            mutations the bead recorded — driven against the REAL corpora, with
            the mutation applied to the parsed schema map so the proof stays
            runnable without editing `spec/Spec-Schemas.md`. Both are invisible
            to every other assertion in this arm: the schema count survives the
            rename, the finding set is unchanged, and CLAIMED == PAIRED holds
            throughout because both numbers move together. Only the floor moves."
    (let [rows    (parse-catalogue-tag-rows)
          schemas (parse-tags-schemas)
          victim  "ResourceRoutePlanTags"
          ;; MUTATION 1 — delete the schema outright.
          deleted (dissoc schemas victim)
          ;; MUTATION 2 — rename it to a name no catalogue row derives. The
          ;; schema COUNT is unchanged, which is what defeats a count-shaped
          ;; guard placed on the schemas rather than on the pairing.
          renamed (-> schemas
                      (dissoc victim)
                      (assoc "SomeUnrelatedThingTags" (get schemas victim)))]
      (is (contains? schemas victim)
          (str "the witness schema " victim " is still in spec/Spec-Schemas.md; "
               "if it was legitimately renamed, name the new one here"))
      (is (= tags-column-paired-floor (paired-count rows schemas))
          "the floor is the live paired count, so the drops below are one apiece")
      (is (< (paired-count rows deleted) tags-column-paired-floor)
          "MUTATION 1: deleting a paired schema drops the count under the floor")
      (is (= (claimed-count rows deleted) (paired-count rows deleted))
          "…while CLAIMED == PAIRED still holds — the identity cannot see it")
      (is (= (count schemas) (count renamed))
          "MUTATION 2 is equal-count by construction")
      (is (< (paired-count rows renamed) tags-column-paired-floor)
          "MUTATION 2: an equal-count rename drops the count under the floor too")
      (is (= (claimed-count rows renamed) (paired-count rows renamed))
          "…and here too the identity holds, in the mutated world"))))

(deftest tags-column-schema-validation-failure-row-is-paired-and-mutation-proven
  (testing "Per the rf2-6tags PR #7207 audit: `:rf.error/schema-validation-
            failure` — the newly always-on structural-only production egress
            record — was unpaired FROM THE START. Its derived name
            `SchemaValidationFailureTags` never matched the schema's as-
            shipped name `SchemaValidationTags`, so invariant 6 held the
            schema (CLAIMED) without ever diffing it (PAIRED) — the same loss
            `tags-column-pairing-is-unambiguous` catches for a collision, only
            silent here because there was no second claimant to trip
            `tags-column-ambiguities`. `spec/Spec-Schemas.md` renamed the
            schema to the name-half derivation — the `StaleSuppressedTags`
            worked example applied here — so this proves the pairing live,
            end to end, rather than trusting the rename alone."
    (let [rows    (parse-catalogue-tag-rows)
          schemas (parse-tags-schemas)
          claims  (schema-claims rows schemas)]
      (is (contains? schemas "SchemaValidationFailureTags")
          "Spec-Schemas.md defines the renamed schema")
      (is (= [:rf.error/schema-validation-failure]
             (mapv :category (get claims "SchemaValidationFailureTags")))
          "the schema is claimed by exactly the one row it names, and by no
           other — a real pairing, not a collision")
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:category %))
                           (tags-column-findings rows schemas)))
          "the live row's `:tags` cell documents every key the schema declares
           (minus the two envelope slots) — the newly-live pairing does not
           immediately red")))
  (testing "MUTATION, catalogue side. Driven against the schema's REAL parsed
            key set (not a hand-copied literal) so the proof tracks the corpus
            rather than a snapshot of it: dropping one of the per-arm keys the
            row's cell documents reds the diff naming exactly it; restoring it
            clears the finding."
    (let [schema  (get (parse-tags-schemas) "SchemaValidationFailureTags")
          without (str "`:where`, `:failing-id`, `:reason`, `:path`, `:value`, "
                       "`:explain`, `:received`, `:rf.sub/query-v`, "
                       "`:rollback?`, `:registered-path`, `:machine-id`, "
                       "`:phase`")
          with    (str without ", `:schema`")
          row-of  (fn [tags]
                    (parse-catalogue-tag-rows
                      (catalogue-fixture
                        (str "| `:rf.error/schema-validation-failure` | `:error` "
                             "| always-on | … | Per-`:where` | " tags " |"))))
          schemas {"SchemaValidationFailureTags" schema}]
      (is (= [{:category :rf.error/schema-validation-failure
               :schema   "SchemaValidationFailureTags"
               :missing  #{:schema}}]
             (tags-column-findings (row-of without) schemas))
          "dropping `:schema` from the cell reds, naming exactly it")
      (is (empty? (tags-column-findings (row-of with) schemas))
          "restoring it clears the finding")))
  (testing "MUTATION, schema side. Adding a key to the parsed schema that the
            live row's cell does not document reds the diff naming it;
            removing the mutation is clean again — the live row itself,
            unmutated, stays clean throughout."
    (let [rows    (parse-catalogue-tag-rows)
          schemas (parse-tags-schemas)
          mutated (update schemas "SchemaValidationFailureTags"
                           conj :rf2-6tags/probe-key)]
      (is (= [{:category :rf.error/schema-validation-failure
               :schema   "SchemaValidationFailureTags"
               :missing  #{:rf2-6tags/probe-key}}]
             (tags-column-findings rows mutated))
          "an undocumented added schema key reds, naming exactly it")
      (is (empty? (filter #(= :rf.error/schema-validation-failure (:category %))
                           (tags-column-findings rows schemas)))
          "…while the unmutated schema stays clean, so the red above is the
           mutation and not the arm"))))

(deftest tags-column-arm-ignores-retired-rows
  (testing "A struck-through row documents a category the runtime no longer
            emits; it has no live `:tags` payload, so it must not pair."
    (let [rows (parse-catalogue-tag-rows
                 (catalogue-fixture
                   (str "| ~~`:rf.error/resource-route-plan`~~ | — | n/a (retired) "
                        "| **RETIRED.** … | — | — |")))]
      (is (empty? rows) "a struck row does not parse as a tags row")
      (is (empty? (tags-column-findings rows pre-wsopx-schemas))))))
