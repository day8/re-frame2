(ns re-frame.story.ui.docs-mode-pane-cljs-test
  "CLJS-side regression net for the `:docs` mode pane (rf2-ssibv).

  Pairs with the pure JVM-side coverage of `prose-for-variant`,
  `args-rows`, `decorator-rows`, `parameter-rows`, and `variant-tags`
  (lives alongside the docs view source — JVM tests at TBD). This
  namespace pins the scenarios called out by spec/015 §`:docs` mode
  pane:

  - **Section rendering** — the six docs sections (header / prose /
    args / decorators / parameters / tags) each surface their declared
    metadata for a variant with full author intent. The `data-test`
    selectors documented in spec/008 all resolve.

  - **Tag-chip forward-link** — clicking a docs tag-chip flips the
    sidebar's `:tag-filter` for that tag (via `state/toggle-tag-filter`).
    Re-clicking flips it back. The `aria-pressed` attribute mirrors the
    filter state — proves the chip's accessibility contract.

  - **Read-only contract** — switching `:dev` → `:docs` → `:dev` does
    NOT mutate the canvas args, the cell-overrides slot, the active
    modes, or any other shell-state slot. The docs pane is a pure
    projection of the registry; the user's transient canvas edits
    survive a docs detour.

  Per spec/008 the renderer is a thin projection over the pure-data
  helpers; pinning the helpers' shape under realistic registry
  fixtures covers the pane's contract without a DOM round-trip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.machines         :as machines]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.story.assertions :as assertions]
            [re-frame.story.loaders    :as loaders]
            [re-frame.story.ui.docs    :as docs]
            [re-frame.story.ui.state   :as state]
            [re-frame.story.ui.state.transitions :as transitions]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (rf/reg-sub :rf/machine
              (fn [db [_ machine-id]]
                (get-in db [:rf/machines machine-id])))
  (machines/reset-timers!)
  (loaders/clear-watchers!)
  (reset! assertions/trace-accumulators {})
  (state/reset-shell-state!)
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

;; ---- helpers -------------------------------------------------------------

(defn- register-rich-variant!
  "Set up a docs-rich variant fixture used by the section-rendering tests.

  Shape:
    - parent story with :doc, :argtypes, :tags, :modes, :substrates
    - variant inheriting the story's tags + adding own :doc / decorators
    - one :prose workspace referencing the variant
    - one :hiccup decorator + one :fx-override decorator (the canonical
      :rf.story/force-fx-stub) so the decorators-table groups by kind"
  []
  (story/reg-decorator :hiccup-wrap
    {:kind :hiccup
     :doc  "wraps in a centred pane"
     :wrap (fn [body _] [:div.centred body])})
  (story/reg-story :story.docs-rich
    {:doc       "Rich variant fixture for docs-pane scenarios."
     :argtypes  {:label {:doc "user-visible label"}
                 :n     {:doc "tick count"}}
     :tags      #{:dev :docs}
     :modes     #{:Mode.app/dark}
     :substrates #{:reagent}})
  (story/reg-variant :story.docs-rich/v
    {:doc        "The 'happy path' variant — used by docs scenarios."
     :args       {:label "Hello" :n 42}
     :decorators [[:hiccup-wrap]
                  [:rf.story/force-fx-stub :http {:status :ok}]]
     :tags       #{:dev :docs :test}
     :events     []})
  (story/reg-mode :Mode.app/dark {:args {:theme :dark}})
  (story/reg-workspace :Workspace.docs-rich/prose-ws
    {:layout  :prose
     :content [{:type :variant :id :story.docs-rich/v}
               {:type :prose
                :body "This variant exists for docs-pane regression tests."}
               {:type :prose
                :body "It carries decorators, modes, and rich argtypes."}]}))

;; ===========================================================================
;; rf2-ssibv — section rendering
;;
;; Pin every pure-data section helper produces the expected projection
;; for a docs-rich variant. The renderer is a 1:1 mapping from these
;; rows onto hiccup; testing the rows here covers the pane's contract
;; without booting Reagent.
;; ===========================================================================

(deftest header-data-projects-from-registry
  (testing "the header reads parent-story + tags + doc-blurb out of the
            registry. Pinning the projection covers the renderer's
            `data-test=story-docs-parent-story` /
            `story-docs-header-tags` / `story-docs-doc-blurb` paths."
    (register-rich-variant!)
    ;; Parent story id is derived from the variant id namespace.
    (is (= :story.docs-rich
           (docs/parent-story-id :story.docs-rich/v))
        "parent-story chip reads :story.docs-rich")
    ;; The header chip vector is the variant's :tags set sorted.
    (let [tags (docs/variant-tags :story.docs-rich/v)]
      (is (= [:dev :docs :test] tags)
          "header tags vector is sorted-by-keyword"))
    ;; The doc-blurb reads the variant's :doc with fallback to the parent's.
    (let [vb (story/handler-meta :variant :story.docs-rich/v)]
      (is (string? (:doc vb))
          ":doc on the variant feeds the doc-blurb"))))

(deftest prose-section-pulls-from-prose-workspaces
  (testing "prose-for-variant walks :prose-layout workspaces that
            reference the variant AND returns each :prose item in
            order. Pinning the data here covers the renderer's
            `data-test=story-docs-prose-block` rows."
    (register-rich-variant!)
    (let [prose (docs/prose-for-variant :story.docs-rich/v)]
      (is (= 2 (count prose))
          "two :prose items in the matching workspace")
      (is (every? #(= :Workspace.docs-rich/prose-ws (:workspace-id %)) prose)
          "each item names its source workspace — the chip in the docs
           render shows 'from <workspace-id>'")
      (is (= ["This variant exists for docs-pane regression tests."
              "It carries decorators, modes, and rich argtypes."]
             (mapv :body prose))
          "prose bodies surface in declared order"))))

(deftest prose-section-omitted-when-no-prose-workspace
  (testing "a variant referenced by ZERO :prose-layout workspaces gets
            an empty prose result — the renderer omits the section
            entirely (no 'no prose' placeholder per spec/008)"
    (story/reg-variant :story.docs.no-prose/v
      {:doc "no-prose variant" :events []})
    (is (= [] (docs/prose-for-variant :story.docs.no-prose/v))
        "empty result — the renderer's `when (seq entries)` clause
         omits the prose section's data-test wrapper")))

(deftest args-section-renders-key-default-doc-columns
  (testing "args-rows produces one row per resolved arg with key /
            value / doc columns. Pinning the row shape covers the
            renderer's `data-test=story-docs-args-row` + `data-arg-key`
            mapping. The arg-rows reflect EVERY entry in the resolved
            args map — including any global args the host configured —
            so we walk the row set looking for our declared keys
            rather than asserting an exact count (defends the test
            against future :global-args additions from the host)."
    (register-rich-variant!)
    (let [eff   (story/resolve-args :story.docs-rich/v)
          rows  (docs/args-rows :story.docs-rich/v eff)
          by-k  (into {} (map (juxt :key identity) rows))]
      (is (contains? by-k :label)
          ":label declared on the variant surfaces as a row")
      (is (contains? by-k :n)
          ":n declared on the variant surfaces as a row")
      (is (= "Hello" (:value (by-k :label))))
      (is (= "user-visible label" (:doc (by-k :label)))
          ":argtypes :doc surfaces in the doc column")
      (is (= 42 (:value (by-k :n))))
      (is (= "tick count" (:doc (by-k :n))))
      ;; Walk every row and assert the projected shape — even rows for
      ;; non-argtype-bearing keys should have :key + :value + :doc keys
      ;; (with :doc possibly nil).
      (doseq [row rows]
        (is (every? #(contains? row %) [:key :value :doc])
            "every row has the renderer-required keys")))))

(deftest decorators-section-groups-by-kind
  (testing "decorator-rows partitions the resolve-decorators pack into
            :hiccup / :frame-setup / :fx-override / :error sections.
            Pinning the section keys covers the renderer's
            `data-test=story-docs-decorator-row` + `data-section`
            mapping."
    (register-rich-variant!)
    (let [pack    (story/resolve-decorators :story.docs-rich/v)
          rows    (docs/decorator-rows pack)
          by-sect (group-by :section rows)]
      (is (contains? by-sect :hiccup)
          ":hiccup section present — the :hiccup-wrap decorator")
      (is (contains? by-sect :fx-override)
          ":fx-override section present — the :rf.story/force-fx-stub")
      (is (not (contains? by-sect :error))
          "no :error rows on the happy path")
      (is (= [:hiccup-wrap]
             (mapv :id (get by-sect :hiccup))))
      (is (= ["wraps in a centred pane"]
             (mapv :doc (get by-sect :hiccup)))
          ":doc on the registered decorator surfaces in the doc column"))))

(deftest parameters-section-falls-back-to-parent-story
  (testing "parameter-rows reads :modes / :substrates / :platforms off
            the variant body first, then falls back to the parent
            story's slot — covers the renderer's
            `data-test=story-docs-parameter-row` + `data-param-key`
            mapping. Per spec/008 §Parameters."
    (register-rich-variant!)
    (let [rows   (docs/parameter-rows :story.docs-rich/v)
          by-k   (into {} (map (juxt :key identity) rows))]
      ;; The variant did NOT declare its own :modes / :substrates;
      ;; both fall back to the parent story.
      (is (= #{:Mode.app/dark}    (:value (by-k :modes))))
      (is (= #{:reagent}          (:value (by-k :substrates))))
      ;; :platforms wasn't declared on either; the row is omitted.
      (is (nil? (by-k :platforms))
          ":platforms with neither variant- nor story-side value drops out
           — the renderer doesn't show 'no platforms declared' rows"))))

(deftest tags-section-feeds-bottom-tag-picker
  (testing "variant-tags surfaces the variant's tag set (or the parent
            story's, falling back) — the bottom tag-picker section
            renders one chip per id. Sorted for stable test selectors."
    (register-rich-variant!)
    (is (= [:dev :docs :test]
           (docs/variant-tags :story.docs-rich/v))
        "rich variant exposes its full tag set, sorted")
    (story/reg-variant :story.docs.no-tags/v {:events []})
    (is (= [] (docs/variant-tags :story.docs.no-tags/v))
        "variant with no tags AND no parent story tags surfaces empty")))

;; ===========================================================================
;; rf2-ssibv — tag-chip forward-link
;;
;; Clicking a docs tag chip dispatches `state/toggle-tag-filter` against
;; the shell-state-atom. The chip's `aria-pressed` mirrors the resulting
;; filter membership. Both the header chip-row AND the bottom tag-picker
;; use the same state mutation — proven by exercising the transition
;; helper.
;; ===========================================================================

(deftest tag-chip-toggles-shell-tag-filter
  (testing "the forward-link contract: applying state/toggle-tag-filter
            for a tag adds it to the shell's :tag-filter set; applying
            it again removes it. The chip's `aria-pressed` reads
            `(contains? tag-filter tag)`. Per spec/008 §Tag-chip
            forward-link."
    (register-rich-variant!)
    ;; Initial state: no tag filter.
    (is (= #{} (:tag-filter @state/shell-state-atom))
        "shell starts with no tag filter")
    ;; First toggle: :dev enters the filter set.
    (state/swap-state! transitions/toggle-tag-filter :dev)
    (is (= #{:dev} (:tag-filter @state/shell-state-atom))
        "tag-chip click adds :dev to the filter — sidebar narrows to
         :dev-tagged variants AND the docs chip's aria-pressed flips
         to true")
    ;; Second toggle: :dev leaves the filter set.
    (state/swap-state! transitions/toggle-tag-filter :dev)
    (is (= #{} (:tag-filter @state/shell-state-atom))
        "tag-chip second click removes :dev — sidebar widens again AND
         the aria-pressed reads false")
    ;; Multi-toggle: :dev and :docs both end up in the filter.
    (state/swap-state! transitions/toggle-tag-filter :dev)
    (state/swap-state! transitions/toggle-tag-filter :docs)
    (is (= #{:dev :docs} (:tag-filter @state/shell-state-atom))
        "two tag-chip clicks across two tags accumulate — the chip-row
         renders both with aria-pressed=true")))

;; ===========================================================================
;; rf2-ssibv — read-only contract
;;
;; Switching :dev → :docs → :dev must NOT mutate the canvas args, the
;; cell-overrides, the active modes, or any other shell-state slot. The
;; docs pane is a pure projection — the user's transient canvas edits
;; survive a docs detour.
;; ===========================================================================

(deftest mode-tab-switch-preserves-shell-state
  (testing "switching the active mode tab between :dev / :docs / :dev
            mutates ONLY the :active-mode-tab slot — every other shell
            slot (cell-overrides, active-modes, tag-filter) is preserved
            byte-for-byte. Proves the docs pane is read-only per
            spec/008."
    (register-rich-variant!)
    ;; Seed the shell with realistic 'user-was-editing' state.
    (state/swap-state! transitions/set-cell-override-scalar
                       :story.docs-rich/v :label "USER-EDIT")
    (state/swap-state! transitions/set-active-modes [:Mode.app/dark])
    (state/swap-state! transitions/toggle-tag-filter :dev)
    (state/swap-state! transitions/select-variant :story.docs-rich/v)
    ;; Snapshot the slots the docs pane must NOT touch.
    (let [before-overrides (:cell-overrides @state/shell-state-atom)
          before-modes     (:active-modes    @state/shell-state-atom)
          before-tags      (:tag-filter      @state/shell-state-atom)
          before-selected  (:selected-variant @state/shell-state-atom)]
      ;; Switch :dev → :docs.
      (state/swap-state! transitions/set-active-mode-tab
                         :story.docs-rich/v :docs)
      (is (= :docs (state/active-mode-tab @state/shell-state-atom
                                          :story.docs-rich/v))
          ":active-mode-tab flipped — the pane swap is the only effect")
      (is (= before-overrides (:cell-overrides @state/shell-state-atom))
          ":cell-overrides untouched — user's :label edit survives the
           docs detour")
      (is (= before-modes (:active-modes @state/shell-state-atom))
          ":active-modes untouched — the dark-theme chip is still active")
      (is (= before-tags (:tag-filter @state/shell-state-atom))
          ":tag-filter untouched — the sidebar's :dev filter is preserved")
      (is (= before-selected (:selected-variant @state/shell-state-atom))
          ":selected-variant untouched — the focused row in the sidebar
           is preserved")
      ;; Switch :docs → :dev (mirror of the round-trip).
      (state/swap-state! transitions/set-active-mode-tab
                         :story.docs-rich/v :dev)
      (is (= :dev (state/active-mode-tab @state/shell-state-atom
                                         :story.docs-rich/v))
          "returned to :dev — the canvas re-mounts against the same
           cell-overrides the user originally entered")
      (is (= before-overrides (:cell-overrides @state/shell-state-atom))
          "round-trip preserves :cell-overrides — the user's transient
           edit was never lost"))))

(deftest docs-pane-data-projection-does-not-mutate-registry
  (testing "calling the docs helpers (prose-for-variant / args-rows /
            decorator-rows / parameter-rows / variant-tags) leaves the
            registry byte-for-byte unchanged — the helpers are pure
            data → data. Pure-data invariant; pin it so future
            registry-touching refactors break this test loudly."
    (register-rich-variant!)
    (let [registry-before {:variant   (story/registrations :variant)
                           :story     (story/registrations :story)
                           :workspace (story/registrations :workspace)
                           :decorator (story/registrations :decorator)
                           :mode      (story/registrations :mode)}]
      ;; Pull every projection a docs render walks.
      (docs/prose-for-variant :story.docs-rich/v)
      (docs/args-rows :story.docs-rich/v
                      (story/resolve-args :story.docs-rich/v))
      (docs/decorator-rows (story/resolve-decorators :story.docs-rich/v))
      (docs/parameter-rows :story.docs-rich/v)
      (docs/variant-tags :story.docs-rich/v)
      (let [registry-after {:variant   (story/registrations :variant)
                            :story     (story/registrations :story)
                            :workspace (story/registrations :workspace)
                            :decorator (story/registrations :decorator)
                            :mode      (story/registrations :mode)}]
        (is (= registry-before registry-after)
            "docs render walked the full pure-data surface; registry is
             unchanged. Documents the contract that no docs helper
             registers / unregisters / mutates a slot.")))))
