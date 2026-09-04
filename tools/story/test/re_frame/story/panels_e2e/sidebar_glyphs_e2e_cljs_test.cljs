(ns re-frame.story.panels-e2e.sidebar-glyphs-e2e-cljs-test
  "Multi-frame e2e coverage for the sidebar amber-glyph rhythm
  (rf2-p0wur; rf2-lj773).

  The sidebar leads every row with a role-distinguishing glyph so the
  three row types parse visually without reading text:

      ◆  story row      — diamond glyph (the parent container)
      ●  variant row    — solid dot glyph (the renderable unit) —
                          superseded by the per-variant STATUS dot
                          when the variant is testable
      ▦  workspace row  — grid glyph (the multi-variant composition)

  ## What this catches

  - **Story row → story-glyph wiring**: `rf.story.ui.sidebar/story-block` builds
    `[:span {:style (:story-glyph styles)} [rf.story.theme.glyphs/story-glyph 13]]`
    as the first child of every story-row. A regression that removes
    the glyph slot (or wires the wrong glyph) would drop the iconic
    prefix and leave story labels visually indistinguishable from
    the variant labels indented underneath.

  - **Variant row → variant-glyph wiring (non-testable case)**:
    `rf.story.ui.sidebar/variant-row` builds
    `[:span {:style (:variant-glyph styles)} [rf.story.theme.glyphs/variant-glyph 10]]`
    when the variant is NOT testable. A regression that drops the
    fallback glyph would leave non-testable variant rows visually
    indistinguishable from the status-dot rows underneath (or worse,
    leave a ragged left edge when the glyph slot is absent entirely).

  - **Workspace row → workspace-glyph wiring**:
    `rf.story.ui.sidebar/workspace-row` builds
    `[:span {:style (:workspace-glyph styles)} [rf.story.theme.glyphs/workspace-glyph 12]]`
    as the first child of every workspace-row. A regression that
    drops the glyph slot would erase the workspace iconography from
    the bottom section of the sidebar.

  ## Why a multi-frame e2e (not Playwright)

  The bead originally specced this as a Playwright assertion. Per
  session direction, e2e coverage is moving to multi-frame CLJS
  Node tests — sub-millisecond per case, no DOM, no browser, and
  the layered hiccup walk catches the same wiring bugs the browser
  scenario would have.

  ## Detection strategy

  `expand-tree` recursively invokes every fn-headed vector — which
  means the inline `[rf.story.theme.glyphs/story-glyph 13]` vectors would expand
  into the underlying SVG (a `:path`, `:circle`, four `:rect`s),
  losing the symbolic link to the glyph fn at the call site. To
  preserve a stable, role-typed marker through the walk, the tests
  `with-redefs` each glyph fn to return a sentinel `[:rf-test-glyph
  <role>]` vector. The sentinel survives `expand-tree` (its head is
  a keyword, not a fn) and lets the assertions count role-typed
  glyphs per row type without fishing through SVG internals.

  Surface tested via hiccup walking; no DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.story :as rf.story]
            [re-frame.story.theme.glyphs :as rf.story.theme.glyphs]
            [re-frame.story.test-helpers.e2e-multi-frame :as rf.story.test-helpers.e2e-multi-frame]
            [re-frame.story.ui.sidebar :as rf.story.ui.sidebar]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- fixture -----------------------------------------------------------

(defn- register-variants!
  "Two stories with one non-testable variant each, plus one workspace.
  No `:test` tag / `:script` slot → both variants take the non-testable
  branch in `variant-row` (the branch that emits `[rf.story.theme.glyphs/variant-glyph
  10]`)."
  []
  (rf.story/reg-story :story.counter
    {:doc "Counter parent story."})
  (rf.story/reg-variant :story.counter/empty
    {:doc "empty counter" :setup []})
  (rf.story/reg-story :story.login
    {:doc "Login parent story."})
  (rf.story/reg-variant :story.login/blank
    {:doc "blank login form" :setup []})
  (rf.story/reg-workspace :Workspace.counter/all
    {:doc      "Counter workspace."
     :layout   :grid
     :variants [:story.counter/empty]
     :columns  1
     :tags     #{:docs}}))

;; ---- sentinel glyph stubs ----------------------------------------------
;;
;; Each glyph fn is redirected to return a `[:rf-test-glyph <role>]`
;; sentinel vector. The sentinel's head is a keyword (not a fn), so
;; `expand-tree` leaves it intact — the role marker survives the walk
;; and the assertions can count by role without parsing SVG internals.

(defn- story-glyph-stub    [& _] [:rf-test-glyph :story])
(defn- variant-glyph-stub  [& _] [:rf-test-glyph :variant])
(defn- workspace-glyph-stub [& _] [:rf-test-glyph :workspace])

;; ---- helpers -----------------------------------------------------------

(defn- render-sidebar-tree
  "`rf.story.ui.sidebar/sidebar` is a class-2 Reagent component — calling it
  returns the inner render fn. Invoking that returns hiccup; we walk
  the expanded tree so `story-block` / `variant-row` / `workspace-row`
  are surfaced as plain DOM-shaped hiccup with sentinel-glyph leaves."
  []
  (let [render-fn (rf.story.ui.sidebar/sidebar)]
    (rf.story.test-helpers.e2e-multi-frame/expand-tree (render-fn nil))))

(defn- glyph-roles-under
  "Walk `node` and return the multiset of `[:rf-test-glyph <role>]`
  roles found anywhere underneath. Used to assert that each row type
  carries the expected role glyph as its iconographic prefix."
  [node]
  (->> (tree-seq (some-fn vector? seq?) seq node)
       (keep (fn [n]
               (when (and (vector? n)
                          (= :rf-test-glyph (first n)))
                 (second n))))
       (frequencies)))

(defn- story-row-nodes
  "Every story-row hiccup vector in the tree. `story-block` builds the
  story row as the first child of a `:story-block` `:div`; the row
  itself has no `:data-test`, so we identify it by structure:
  `[:div {:style (:story-row styles)} [:span ...] ...]` whose first
  inner span carries a `[:rf-test-glyph :story]` sentinel."
  [tree]
  (->> (rf.story.test-helpers.e2e-multi-frame/hiccup-seq tree)
       (filter (fn [node]
                 (and (vector? node)
                      (= :div (first node))
                      (let [roles (glyph-roles-under node)]
                        (= 1 (get roles :story 0))))))
       ;; Drop any ancestor wrappers — keep the tightest match, which
       ;; is the row whose roles map contains exactly one :story entry
       ;; and no other roles inside.
       (filter (fn [node]
                 (let [roles (glyph-roles-under node)]
                   (= roles {:story 1}))))))

(defn- variant-row-nodes
  "Every variant-row hiccup vector in the tree, located by the
  authoritative `:data-test=\"story-sidebar-variant-row\"` selector."
  [tree]
  (rf.story.test-helpers.e2e-multi-frame/find-all-by-test-id tree "story-sidebar-variant-row"))

(defn- workspace-row-nodes
  "Every workspace-row hiccup vector in the tree, located by the
  authoritative `:data-test=\"story-sidebar-workspace-row\"` selector."
  [tree]
  (rf.story.test-helpers.e2e-multi-frame/find-all-by-test-id tree "story-sidebar-workspace-row"))

;; ---- assertions --------------------------------------------------------

(deftest story-rows-lead-with-story-glyph
  (testing "rf2-p0wur — every story-row in the sidebar carries a
            `[rf.story.theme.glyphs/story-glyph]` as its first iconographic prefix.
            Two stories registered → at least two story rows in the
            tree, each with exactly one story-role sentinel underneath."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variants!}
      (fn []
        (with-redefs [rf.story.theme.glyphs/story-glyph     story-glyph-stub
                      rf.story.theme.glyphs/variant-glyph   variant-glyph-stub
                      rf.story.theme.glyphs/workspace-glyph workspace-glyph-stub]
          (let [tree  (render-sidebar-tree)
                rows  (story-row-nodes tree)]
            (is (<= 2 (count rows))
                "both story rows render (one per registered story)")
            (is (every? (fn [row] (= {:story 1} (glyph-roles-under row)))
                        rows)
                "each story row carries exactly one story-role glyph
                 sentinel — the amber diamond at the row's leading edge")))))))

(deftest variant-rows-lead-with-variant-glyph
  (testing "rf2-p0wur — every non-testable variant-row carries a
            `[rf.story.theme.glyphs/variant-glyph]` sentinel as the iconographic
            prefix in the variant-glyph branch of `variant-row`. Both
            registered variants are non-testable (no `:test` tag, no
            `:script`), so both rows take this branch."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variants!}
      (fn []
        (with-redefs [rf.story.theme.glyphs/story-glyph     story-glyph-stub
                      rf.story.theme.glyphs/variant-glyph   variant-glyph-stub
                      rf.story.theme.glyphs/workspace-glyph workspace-glyph-stub]
          (let [tree (render-sidebar-tree)
                rows (variant-row-nodes tree)]
            (is (<= 2 (count rows))
                "both variant rows render in the sidebar tree")
            (is (every? (fn [row] (= 1 (get (glyph-roles-under row) :variant 0)))
                        rows)
                "each variant row contains exactly one variant-role glyph
                 sentinel — the non-testable branch of variant-row wired
                 `[rf.story.theme.glyphs/variant-glyph]` per rf2-p0wur")))))))

(deftest workspace-rows-lead-with-workspace-glyph
  (testing "rf2-p0wur — every workspace-row carries a
            `[rf.story.theme.glyphs/workspace-glyph]` sentinel as the iconographic
            prefix. One workspace registered → one workspace row, with
            exactly one workspace-role sentinel underneath."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variants!}
      (fn []
        (with-redefs [rf.story.theme.glyphs/story-glyph     story-glyph-stub
                      rf.story.theme.glyphs/variant-glyph   variant-glyph-stub
                      rf.story.theme.glyphs/workspace-glyph workspace-glyph-stub]
          (let [tree (render-sidebar-tree)
                rows (workspace-row-nodes tree)]
            (is (= 1 (count rows))
                "the single registered workspace renders one row")
            (is (= {:workspace 1} (glyph-roles-under (first rows)))
                "the workspace row contains exactly one workspace-role
                 glyph sentinel — the grid glyph at the row's leading
                 edge per rf2-p0wur")))))))

(deftest no-cross-role-glyph-leakage-in-rows
  (testing "rf2-p0wur — the three row types each carry ONE role of
            glyph and no other. Pins the inverse so a regression that
            wires the wrong glyph fn into a row (variant-glyph in a
            workspace row, etc.) surfaces as a mismatched role count
            instead of silently passing the per-row checks above."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variants!}
      (fn []
        (with-redefs [rf.story.theme.glyphs/story-glyph     story-glyph-stub
                      rf.story.theme.glyphs/variant-glyph   variant-glyph-stub
                      rf.story.theme.glyphs/workspace-glyph workspace-glyph-stub]
          (let [tree            (render-sidebar-tree)
                variant-rows    (variant-row-nodes tree)
                workspace-rows  (workspace-row-nodes tree)]
            (is (every? (fn [row]
                          (let [roles (glyph-roles-under row)]
                            (and (zero? (get roles :story     0))
                                 (zero? (get roles :workspace 0)))))
                        variant-rows)
                "no story- or workspace-role glyph leaks into a variant row")
            (is (every? (fn [row]
                          (let [roles (glyph-roles-under row)]
                            (and (zero? (get roles :story   0))
                                 (zero? (get roles :variant 0)))))
                        workspace-rows)
                "no story- or variant-role glyph leaks into a workspace row")))))))

;; ---- rf2-k3y92 — sidebar rows are keyboard-operable buttons -------------

(deftest variant-rows-expose-keyboard-button-semantics
  (testing "rf2-k3y92 — variant rows render as clickable `<div>`s; they
            must expose `role=\"button\"` + `tabindex=\"0\"` + a key
            handler so keyboard-only users can navigate into and
            activate them. Without these, the sidebar's `<nav>` landmark
            is reachable but rows inside it aren't — keyboard users
            can't select a variant from the sidebar at all."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variants!}
      (fn []
        (with-redefs [rf.story.theme.glyphs/story-glyph     story-glyph-stub
                      rf.story.theme.glyphs/variant-glyph   variant-glyph-stub
                      rf.story.theme.glyphs/workspace-glyph workspace-glyph-stub]
          (let [tree (render-sidebar-tree)
                rows (variant-row-nodes tree)]
            (is (<= 2 (count rows)))
            (is (every? (fn [row] (= "button" (get (second row) :role)))
                        rows)
                "every variant row exposes role=button")
            (is (every? (fn [row] (= "0" (get (second row) :tab-index)))
                        rows)
                "every variant row exposes tabindex=0 so it joins the
                 sequential focus order")
            (is (every? (fn [row] (fn? (get (second row) :on-key-down)))
                        rows)
                "every variant row carries an on-key-down handler so
                 Enter / Space can activate it")
            (is (every? (fn [row]
                          (let [aria (get (second row) :aria-label)]
                            (and (string? aria)
                                 (re-find #"Open variant" aria))))
                        rows)
                "every variant row carries an aria-label describing the
                 action — \"Open variant <id>\"")))))))

(deftest workspace-rows-expose-keyboard-button-semantics
  (testing "rf2-k3y92 — workspace rows mirror variant rows: clickable
            `<div>`s that must expose `role=\"button\"` + `tabindex=\"0\"`
            + a key handler. Without these the workspace section of the
            sidebar is unreachable from the keyboard."
    (rf.story.test-helpers.e2e-multi-frame/with-story-and-xray-frames
      {:register-stories register-variants!}
      (fn []
        (with-redefs [rf.story.theme.glyphs/story-glyph     story-glyph-stub
                      rf.story.theme.glyphs/variant-glyph   variant-glyph-stub
                      rf.story.theme.glyphs/workspace-glyph workspace-glyph-stub]
          (let [tree (render-sidebar-tree)
                rows (workspace-row-nodes tree)]
            (is (= 1 (count rows)))
            (let [props (second (first rows))]
              (is (= "button" (:role props)))
              (is (= "0"      (:tab-index props)))
              (is (fn? (:on-key-down props)))
              (is (re-find #"Open workspace" (:aria-label props))))))))))
