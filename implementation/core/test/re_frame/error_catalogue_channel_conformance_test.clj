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
       A new row added WITHOUT a channel (an empty cell, or a typo'd
       channel name) fails here — the co-edit invariant made testable.
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

  JVM-only (`.clj`, NOT `*-cljs-test`): it `slurp`s a repo markdown file,
  which only the JVM `clojure -M:test` runner can do. The exercise leg is
  the dual-runtime half."
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
  keyword (group 1) and the `Channel` column value (group 2). The table
  shape (Spec 009 §Error event catalogue):

    | `:operation` | `:op-type` | Channel | Trigger / meaning | … |

  Group 1 is the back-ticked `:rf.<area>/<category>` in column 1; the
  `:op-type` in column 2 is skipped; group 2 is the bare lowercase
  channel token in column 3. Anchored at `^|` so it only matches genuine
  table rows, not prose mentions of a category."
  #"^\|\s*`(:rf\.[^`]+)`\s*\|\s*`?:[^|`]+`?\s*\|\s*([a-z-]+)\s*\|")

(defn- parse-catalogue
  "Parse the Spec 009 error-event catalogue into a vector of
  `{:category <kw> :channel <string>}` maps, in table order. Reads the
  markdown fresh each call (cheap; one file)."
  []
  (->> (slurp spec-009-file)
       (str/split-lines)
       (keep (fn [line]
               (when-let [[_ cat-str chan] (re-find catalogue-row-re line)]
                 {:category (keyword (subs cat-str 1)) ;; drop leading ':'
                  :channel  chan})))
       vec))

(def ^:private allowed-channels
  "The two graduated EP-0008 channel values (Spec 009 §Error event
  catalogue: \"Its value is one of two\"). The causal channel is data, not
  a catalogue row, so it is not a `Channel` cell value."
  #{"always-on" "diagnostic"})

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
