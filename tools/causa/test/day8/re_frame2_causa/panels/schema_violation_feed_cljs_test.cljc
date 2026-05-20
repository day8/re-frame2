(ns day8.re-frame2-causa.panels.schema-violation-feed-cljs-test
  "Schema-violation focused-cascade contract for the Issues ribbon
  (rf2-w1mnq · Wave 5 of rf2-tglku, replaces the `runSchemaViolation`
  Issues-feed assertion in `tools/causa/testbeds/feature_matrix/
  scenarios.cjs`).

  ## What this replaces

  The Playwright `schema-violation` scenario (now skipped — see
  commit `7b943bfc1` for rf2-kgkht path B) drove a browser through
  four `:where` surfaces (`:app-db`, `:event`, `:cofx`, `:fx-args`)
  and asserted the Issues-feed locator surfaced at least one
  `:rf.error/schema-validation-failure` row for the focused cascade.

  Per rf2-jio48 + rf2-h0120 the Issues panel rebuild + head-fallback
  contract require explicit `:rf.causa/epoch-history` population — the
  testbed-integration locator races against the rebuild. The browser
  assertion timed out reliably enough to block PR #1745.

  Per Mike's testing direction (feedback_causa_story_cljs_unit_tests_
  not_playwright) + the Wave 1-4 migration pattern (rf2-tglku epic):
  the architectural fix is a CLJS unit test that calls `h/project-feed`
  directly with seeded `:rf.error/schema-validation-failure` trace
  events covering all four `:where` surfaces.

  ## What's under test

  Spec/010 §Schema validation pins four canonical `:where` surfaces
  for the schema-validation-failure trace event:

  - `:app-db`   — the post-handler `:db` walk failed
  - `:event`    — the event envelope failed before the handler ran
  - `:cofx`     — a cofx return value failed its `:spec`
  - `:fx-args`  — fx args failed before the fx handler ran

  Per spec/018 §Tabs all four surface through the Issues panel as
  `:rf.error/schema-validation-failure` rows. Per rf2-u6dhp the feed
  is cascade-scoped (focused-cascade lens), so each violation must
  land in its own cascade and the focused-cascade projection must
  surface ≥1 schema row.

  ## Why `.cljc` + `_cljs_test`

  Same dual-target pattern as `issues_ribbon_helpers_cljs_test.cljc`:
  the helper under test is pure data → data and runs on both JVM
  (Cognitect test-runner via `cljs-test$` regex on the ns name) and
  CLJS (Shadow's `:node-test` build via the same regex)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.issues-ribbon-helpers :as h]))

;; ---- fixture builders --------------------------------------------------

(defn- schema-violation-ev
  "Build a `:rf.error/schema-validation-failure` trace event for the
  given `:where` surface, optionally pinned to a focused
  `:dispatch-id` so the cascade-scope projection has something to
  match on. Mirrors the trace shape `re-frame.schemas.validate`
  emits at the four canonical emit sites
  (`validate-app-db!`, the router's event validator,
  `validate-cofx!`, `validate-fx-args!`)."
  ([id where]
   (schema-violation-ev id where nil))
  ([id where dispatch-id]
   (cond-> {:id        id
            :op-type   :error
            :operation :rf.error/schema-validation-failure
            :time      (* 100 id)
            :recovery  :no-recovery
            :tags      (cond-> {:where where
                                :path  [:demo where]
                                :reason (str "schema violation at " where)}
                         dispatch-id (assoc :dispatch-id dispatch-id))})))

(defn- non-issue-ev
  "A success-path trace event — never reaches the ribbon. Sanity
  noise so the projection is exercised against a mixed buffer."
  [id]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :time      (* 100 id)
   :tags      {}})

;; ---- (1) all four :where surfaces surface in the global feed -----------

(deftest schema-violation-all-four-where-surfaces-in-global-feed
  (testing "Spec/010 §Schema validation — all four canonical
            :where surfaces (:app-db, :event, :cofx, :fx-args) project
            as :rf.error/schema-validation-failure rows when the feed
            is read globally (no :focus-dispatch-id). This is the
            'all four surfaces fire' assertion the Playwright trace-
            level check already pinned (scenarios.cjs lines 1062-1086);
            it's pinned here at the helper level too so the helper's
            shape contract is testable without the testbed."
    (let [stream [(schema-violation-ev 1 :app-db)
                  (schema-violation-ev 2 :event)
                  (schema-violation-ev 3 :cofx)
                  (schema-violation-ev 4 :fx-args)
                  (non-issue-ev 5)]
          feed   (h/project-feed stream {} 1000)]
      (is (= 4 (:rendered feed))
          "all four schema-violation rows project; the non-issue drops")
      (is (= #{:rf.error/schema-validation-failure}
             (set (mapv :operation (:issues feed))))
          "every projected row is a schema-validation-failure operation")
      (is (= #{:app-db :event :cofx :fx-args}
             (->> feed :issues (mapv (comp :where :tags :raw)) set))
          "every :where surface round-trips through the projection — the
           four canonical recovery surfaces are all visible to the
           Issues tab consumer"))))

;; ---- (2) focused-cascade contract — ≥1 schema row per cascade ----------

(deftest schema-violation-focused-cascade-surfaces-at-least-one-row
  (testing "rf2-u6dhp — when the spine focus is on cascade D and that
            cascade emitted a schema-validation-failure (regardless of
            :where surface), the feed renders ≥1 schema row in the
            focused-cascade slice. The Playwright assertion the bead
            pulled out (`schemaRowCount >= 1`) is pinned here at the
            helper level — each `violate-*` click in the testbed is
            its own cascade, so each focus carries one schema row."
    (doseq [[where dispatch-id] [[:app-db  7]
                                 [:event   8]
                                 [:cofx    9]
                                 [:fx-args 10]]]
      (testing (str ":where " where " · :dispatch-id " dispatch-id)
        (let [stream [(schema-violation-ev 1 :app-db  7)
                      (schema-violation-ev 2 :event   8)
                      (schema-violation-ev 3 :cofx    9)
                      (schema-violation-ev 4 :fx-args 10)]
              feed   (h/project-feed stream
                                     {:focus-dispatch-id dispatch-id}
                                     1000)
              schema-rows (filter
                            #(= :rf.error/schema-validation-failure
                                (:operation %))
                            (:issues feed))]
          (is (= 1 (:total feed))
              "cascade scope narrows to the focused cascade's single row")
          (is (= 1 (:rendered feed))
              "no chip filters → rendered = total")
          (is (= 1 (count schema-rows))
              "≥1 schema-validation-failure row projects for the focused
               cascade — the assertion the Playwright scenario was
               trying to make via the DOM")
          (is (nil? (:empty-kind feed))
              "feed is non-empty for the focused cascade"))))))

;; ---- (3) :where survives the projection (description path) -------------

(deftest schema-violation-where-survives-the-projection
  (testing "the row's `:raw` slot retains the original trace event so
            consumers (the Issues-row click-through, MCP, dashboards)
            can read back `:tags :where` — the panel's `:description`
            cell is built from `:operation + :tags[:path]`
            (`short-description`), but downstream consumers need the
            `:where` surface to disambiguate among the four"
    (let [stream [(schema-violation-ev 1 :fx-args)]
          feed   (h/project-feed stream {} 1000)
          row    (first (:issues feed))]
      (is (= :fx-args (-> row :raw :tags :where))
          "raw trace event preserves :where in :tags")
      (is (re-find #":rf.error/schema-validation-failure"
                   (:description row))
          ":description includes the schema-validation operation keyword")
      ;; `short-description` uses `:reason` when present (higher
      ;; priority than `:path`), so the violation's reason surfaces in
      ;; the rendered description.
      (is (re-find #"schema violation at :fx-args"
                   (:description row))
          ":description surfaces the violation's :reason text"))))

;; ---- (4) cascade scope drops non-focused schema rows -------------------

(deftest schema-violation-cascade-scope-drops-non-focused
  (testing "rf2-u6dhp — when the spine focus is on cascade 7 the
            feed must DROP schema-violations from other cascades. The
            Playwright scenario relies on this to assert the
            focused-cascade lens; the unit pins it directly."
    (let [stream [(schema-violation-ev 1 :app-db  7)
                  (schema-violation-ev 2 :event   8)
                  (schema-violation-ev 3 :cofx    8)
                  (schema-violation-ev 4 :fx-args 8)]
          feed   (h/project-feed stream
                                 {:focus-dispatch-id 7}
                                 1000)]
      (is (= 1 (:total feed))
          "cascade 7 had one schema violation; cascade 8's three drop")
      (is (= [:app-db]
             (->> feed :issues (mapv (comp :where :tags :raw))))
          "only the focused-cascade's :where surfaces, no other"))))

;; ---- (5) :ungrouped lane still surfaces schema violations --------------

(deftest schema-violation-ungrouped-projection-includes-schema-rows
  (testing "rf2-2f40y — the `:ungrouped` escape-hatch lane projects
            issues whose `:dispatch-id` defaults to `:ungrouped` (no
            cascade context). Schema violations from a no-cascade
            emit site MUST still surface in the `:ungrouped` lane so
            they aren't silently dropped."
    (let [stream [(schema-violation-ev 1 :app-db)   ; no dispatch-id
                  (schema-violation-ev 2 :event)]
          ungrouped (h/project-ungrouped-feed stream)]
      (is (= 2 (:total ungrouped))
          "both schema rows surface in the :ungrouped lane")
      (is (= #{:rf.error/schema-validation-failure}
             (set (mapv :operation (:issues ungrouped))))
          "every :ungrouped row is a schema-validation-failure"))))
