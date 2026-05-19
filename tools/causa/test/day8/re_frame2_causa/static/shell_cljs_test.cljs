(ns day8.re-frame2-causa.static.shell-cljs-test
  "CLJS wiring + render tests for Causa's Static surface scaffold
  (rf2-o5f5f.1).

  ## What's under test

    1. Mode-state lives on `:rf.causa/mode` (default `:runtime`);
       `:rf.causa/set-mode` writes a specific mode; `:rf.causa/
       toggle-mode` flips between modes. Both attach the
       `:rf.causa.static/persist-mode` fx so the value round-trips
       through localStorage.

    2. localStorage round-trip — the persisted slot survives a frame
       reset (the hydrate path in `mount/ensure-causa-frame!`
       restores the value via `:rf.causa/set-mode`).

    3. Static shell renders the 3-layer chrome (ribbon · tab-bar ·
       detail panel) with the 5 placeholder sub-tabs (Machines /
       Routes / Schemas / Views / Events). Each placeholder card
       names its sibling-bead id.

    4. `:rf.causa.static/select-tab` flips the Static-scoped tab
       slot (does NOT clobber the Runtime `:rf.causa/selected-tab`).

    5. Sub-tab routing — clicking a Static tab swaps the detail
       panel; an unknown tab id is rejected by the event handler.

    6. The mode pill renders as a two-segment radio with the active
       segment carrying `aria-checked='true'`.

  ## Pure hiccup walk

  Same approach as `shell_cljs_test.cljs` — we walk the view's
  hiccup tree by `data-testid` rather than mounting to a real DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.mode-pill :as mode-pill]
            [day8.re-frame2-causa.static.persistence :as static-persistence]
            [day8.re-frame2-causa.static.shell :as static-shell]
            [day8.re-frame2-causa.shell :as shell]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixture ------------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!)
  (config/set-static-mode-enabled! nil)   ; back to default false
  (static-persistence/clear!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- hiccup walker (mirrors shell_cljs_test) ----------------------------

(declare expand-tree)

(defn- expand-tree
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- hiccup-seq [tree]
  (let [expanded (expand-tree tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filterv (fn [node]
             (and (vector? node)
                  (map? (second node))
                  (when-let [tid (:data-testid (second node))]
                    (= 0 (.indexOf tid prefix)))))
           (hiccup-seq tree)))

(defn- text-nodes [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

;; ---- helpers ------------------------------------------------------------

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- frame-sub [q]
  (rf/with-frame :rf/causa
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync ev)))

;; -------------------------------------------------------------------------
;; (1) mode-state lifecycle — set / toggle
;; -------------------------------------------------------------------------

(deftest mode-default-is-runtime
  (testing "with no host opt-in, the mode slot defaults to :runtime"
    (causa-setup!)
    (is (= :runtime (frame-sub [:rf.causa/mode])))))

(deftest set-mode-writes-the-slot
  (testing ":rf.causa/set-mode :static lands :static on the slot"
    (causa-setup!)
    (frame-dispatch [:rf.causa/set-mode :static])
    (is (= :static (frame-sub [:rf.causa/mode])))
    (frame-dispatch [:rf.causa/set-mode :runtime])
    (is (= :runtime (frame-sub [:rf.causa/mode])))))

(deftest set-mode-normalises-unknown-values
  (testing "unknown / string mode values normalise back to :runtime"
    (causa-setup!)
    (frame-dispatch [:rf.causa/set-mode :nonsense])
    (is (= :runtime (frame-sub [:rf.causa/mode]))
        "unknown keyword → :runtime")
    (frame-dispatch [:rf.causa/set-mode "static"])
    (is (= :static (frame-sub [:rf.causa/mode]))
        "string 'static' normalises to :static")))

(deftest toggle-mode-flips-runtime-and-static
  (testing ":rf.causa/toggle-mode flips between modes idempotently"
    (causa-setup!)
    (is (= :runtime (frame-sub [:rf.causa/mode])) "starts on :runtime")
    (frame-dispatch [:rf.causa/toggle-mode])
    (is (= :static (frame-sub [:rf.causa/mode])))
    (frame-dispatch [:rf.causa/toggle-mode])
    (is (= :runtime (frame-sub [:rf.causa/mode])))
    (frame-dispatch [:rf.causa/toggle-mode])
    (is (= :static (frame-sub [:rf.causa/mode])))))

;; -------------------------------------------------------------------------
;; (2) localStorage persistence — round-trip
;; -------------------------------------------------------------------------

(deftest persistence-normalise-runs-on-input
  (testing "static.persistence/normalise-mode coerces keywords + strings"
    (is (= :runtime (static-persistence/normalise-mode :runtime)))
    (is (= :static  (static-persistence/normalise-mode :static)))
    (is (= :runtime (static-persistence/normalise-mode "runtime")))
    (is (= :static  (static-persistence/normalise-mode "static")))
    (is (= :runtime (static-persistence/normalise-mode nil)))
    (is (= :runtime (static-persistence/normalise-mode :nonsense)))
    (is (= :runtime (static-persistence/normalise-mode "junk")))))

(deftest persistence-raw-round-trip
  (testing "->raw / <-raw lossless on canonical values"
    (is (= :runtime (static-persistence/<-raw (static-persistence/->raw :runtime))))
    (is (= :static  (static-persistence/<-raw (static-persistence/->raw :static))))))

(deftest persistence-load-default-empty-slot
  (when (and (exists? js/window) (.-localStorage js/window))
    (static-persistence/clear!)
    (testing "empty localStorage slot → :runtime fallback"
      (is (= :runtime (static-persistence/load))))))

(deftest persistence-save-and-load-round-trip
  (when (and (exists? js/window) (.-localStorage js/window))
    (testing "save! + load round-trip"
      (static-persistence/clear!)
      (static-persistence/save! :static)
      (is (= :static (static-persistence/load)))
      (static-persistence/save! :runtime)
      (is (= :runtime (static-persistence/load))))))

(deftest persistence-fx-installed-by-set-mode
  (when (and (exists? js/window) (.-localStorage js/window))
    (testing ":rf.causa/set-mode lands the value in localStorage via the fx"
      (causa-setup!)
      (static-persistence/clear!)
      (frame-dispatch [:rf.causa/set-mode :static])
      (is (= :static (static-persistence/load))
          ":static was persisted")
      (frame-dispatch [:rf.causa/toggle-mode])
      (is (= :runtime (static-persistence/load))
          "toggle back to :runtime was persisted"))))

;; -------------------------------------------------------------------------
;; (3) Static surface — 3-layer chrome render
;; -------------------------------------------------------------------------

(deftest static-surface-renders-three-layers
  (testing "Static shell renders ribbon · tab-bar · detail panel
            (NO L2 event list — Static is event-INDEPENDENT)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (static-shell/surface)]
        (is (some? (find-by-testid tree "rf-causa-static-surface"))
            "Static surface envelope present")
        (is (some? (find-by-testid tree "rf-causa-static-ribbon"))
            "L1 ribbon present")
        (is (some? (find-by-testid tree "rf-causa-static-tab-bar"))
            "L3 tab bar present")
        ;; default tab is :machines → detail panel testid carries the tab name
        (is (some? (find-by-testid tree "rf-causa-static-detail-panel-machines"))
            "L4 detail panel present (default :machines tab)")
        ;; CRITICAL: no L2 event list in Static mode
        (is (nil? (find-by-testid tree "rf-causa-event-list"))
            "no L2 event list (Static is event-INDEPENDENT)")))))

(deftest static-ribbon-mounts-mode-pill-and-right-icons
  (testing "Static ribbon carries the mode pill at left + right icons
            cluster (Settings · Close). Runtime's nav / frame /
            filter clusters are hidden (Static has no spine)."
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (static-shell/surface)]
        (is (some? (find-by-testid tree "rf-causa-mode-pill"))
            "mode pill present at ribbon-left")
        (is (some? (find-by-testid tree "rf-causa-static-ribbon-icons"))
            "right icons cluster present")
        (is (some? (find-by-testid tree "rf-causa-static-icon-settings"))
            "settings icon present")
        (is (some? (find-by-testid tree "rf-causa-static-icon-close"))
            "close icon present")
        ;; Runtime ribbon clusters MUST NOT mount in Static surface
        (is (nil? (find-by-testid tree "rf-causa-ribbon-nav"))
            "no nav cluster")
        (is (nil? (find-by-testid tree "rf-causa-ribbon-frame-picker"))
            "no frame picker")
        (is (nil? (find-by-testid tree "rf-causa-ribbon-filters"))
            "no filter pills")))))

;; -------------------------------------------------------------------------
;; (4) Static tab inventory — 5 sub-tabs, each with a placeholder card
;; -------------------------------------------------------------------------

(def ^:private expected-static-tab-ids
  [:machines :routes :schemas :views :events])

(deftest static-tab-bar-renders-five-tabs
  (testing "Static L3 tab bar renders 5 sub-tabs per parent-epic
            rf2-o5f5f sub-bead list (Machines / Routes / Schemas /
            Views / Events)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (static-shell/surface)]
        (doseq [tab-id expected-static-tab-ids]
          (is (some? (find-by-testid tree (str "rf-causa-static-tab-" (name tab-id))))
              (str "tab button for " tab-id)))))))

(deftest static-tab-bar-uses-tablist-aria
  (testing "Static tab-bar uses the canonical ARIA tab pattern
            (role='tablist' on the container, role='tab' on each
            button, aria-selected matching the active state)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree    (static-shell/surface)
            tab-bar (find-by-testid tree "rf-causa-static-tab-bar")
            attrs   (second tab-bar)]
        (is (= "tablist" (:role attrs))
            "container carries role='tablist'")
        (is (string? (:aria-label attrs))
            "container has an accessible name"))
      (let [tree (static-shell/surface)]
        (doseq [tab-id expected-static-tab-ids]
          (let [btn   (find-by-testid tree (str "rf-causa-static-tab-" (name tab-id)))
                attrs (second btn)]
            (is (= "tab" (:role attrs))
                (str "tab " tab-id " carries role='tab'"))
            (is (= (if (= tab-id :machines) "true" "false")
                   (:aria-selected attrs))
                (str "tab " tab-id " aria-selected matches the active tab"))))))))

(def ^:private filled-static-tab-ids
  "Static tab ids that have a real panel installed — the placeholder
  card for these tabs is no longer rendered. Sibling beads tick one
  off each time they land."
  ;; rf2-o5f5f.3 — :routes now mounts the Static Routes panel.
  #{:routes})

(deftest static-placeholder-cards-name-sibling-bead
  (testing "each placeholder card surfaces its sibling-bead id
            (rf2-o5f5f.<N> will fill this) — except for tabs already
            replaced by a real panel (filled-static-tab-ids)"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (doseq [tab-id expected-static-tab-ids
              :when (not (contains? filled-static-tab-ids tab-id))]
        (frame-dispatch [:rf.causa.static/select-tab tab-id])
        (let [tree (static-shell/surface)
              card (find-by-testid tree (str "rf-causa-static-placeholder-" (name tab-id)))
              text (text-nodes card)]
          (is (some? card)
              (str "placeholder card for " tab-id " rendered"))
          (is (re-find #"rf2-o5f5f\." text)
              (str "card text names a sibling bead id (got: " text ")"))
          (is (re-find #"will fill this" text)
              "card mentions 'will fill this'"))))))

;; -------------------------------------------------------------------------
;; (5) Static tab routing — selection + isolation
;; -------------------------------------------------------------------------

(deftest static-select-tab-flips-the-slot
  (testing ":rf.causa.static/select-tab writes the Static-scoped slot"
    (causa-setup!)
    (is (= :machines (frame-sub [:rf.causa.static/selected-tab]))
        "default is :machines")
    (frame-dispatch [:rf.causa.static/select-tab :routes])
    (is (= :routes (frame-sub [:rf.causa.static/selected-tab])))
    (frame-dispatch [:rf.causa.static/select-tab :events])
    (is (= :events (frame-sub [:rf.causa.static/selected-tab])))))

(deftest static-select-tab-rejects-unknown-ids
  (testing ":rf.causa.static/select-tab ignores ids not in the
            inventory — guards against typos / drift between the
            sibling beads and this scaffold"
    (causa-setup!)
    (frame-dispatch [:rf.causa.static/select-tab :machines])
    (frame-dispatch [:rf.causa.static/select-tab :not-a-tab])
    (is (= :machines (frame-sub [:rf.causa.static/selected-tab]))
        "unknown tab id is rejected; slot stays on :machines")))

(deftest static-tab-isolated-from-runtime-tab
  (testing "Runtime and Static tab choices are independent —
            switching one does NOT clobber the other"
    (causa-setup!)
    (frame-dispatch [:rf.causa/select-tab :machines])
    (frame-dispatch [:rf.causa.static/select-tab :events])
    (is (= :machines (frame-sub [:rf.causa/selected-tab]))
        "Runtime tab unchanged")
    (is (= :events (frame-sub [:rf.causa.static/selected-tab]))
        "Static tab landed independently")))

;; -------------------------------------------------------------------------
;; (6) Mode-signal mechanism — stripe colour helper
;; -------------------------------------------------------------------------

(deftest stripe-token-runtime-violet-static-cyan
  (testing "mode-signal mechanism #2 — 2-px left-edge stripe is
            :accent-violet in Runtime, :cyan in Static. No new tokens
            introduced per the rf2-zhrwo audit constraint."
    (is (= :accent-violet (static-shell/stripe-token-for-mode :runtime)))
    (is (= :cyan          (static-shell/stripe-token-for-mode :static)))
    ;; Unknown / nil values fall back to runtime
    (is (= :accent-violet (static-shell/stripe-token-for-mode :nonsense)))
    (is (= :accent-violet (static-shell/stripe-token-for-mode nil)))))

;; -------------------------------------------------------------------------
;; (7) Mode pill — radio pattern + active segment
;; -------------------------------------------------------------------------

(deftest mode-pill-renders-two-segments
  (testing "mode pill is a 2-segment radio group with Runtime + Static"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree (mode-pill/mode-pill)]
        (is (some? (find-by-testid tree "rf-causa-mode-pill")))
        (is (some? (find-by-testid tree "rf-causa-mode-pill-runtime")))
        (is (some? (find-by-testid tree "rf-causa-mode-pill-static")))))))

(deftest mode-pill-active-segment-aria-checked
  (testing "the active segment carries aria-checked='true'"
    (causa-setup!)
    (rf/with-frame :rf/causa
      (let [tree     (mode-pill/mode-pill)
            runtime  (find-by-testid tree "rf-causa-mode-pill-runtime")
            static-b (find-by-testid tree "rf-causa-mode-pill-static")]
        (is (= "true"  (:aria-checked (second runtime))))
        (is (= "false" (:aria-checked (second static-b))))))
    ;; flip to Static and re-render
    (frame-dispatch [:rf.causa/set-mode :static])
    (rf/with-frame :rf/causa
      (let [tree     (mode-pill/mode-pill)
            runtime  (find-by-testid tree "rf-causa-mode-pill-runtime")
            static-b (find-by-testid tree "rf-causa-mode-pill-static")]
        (is (= "false" (:aria-checked (second runtime))))
        (is (= "true"  (:aria-checked (second static-b))))))))

(deftest mode-pill-segment-glyphs
  (testing "active segment renders ● and inactive renders ○
            (mirrors Causa's existing tab-button glyph language)"
    (is (= "●" (mode-pill/segment-glyph {:mode :runtime} :runtime)))
    (is (= "○" (mode-pill/segment-glyph {:mode :static}  :runtime)))
    (is (= "○" (mode-pill/segment-glyph {:mode :runtime} :static)))
    (is (= "●" (mode-pill/segment-glyph {:mode :static}  :static)))))

;; -------------------------------------------------------------------------
;; (8) Surface composer — shell.cljs dispatches Runtime vs Static
;; -------------------------------------------------------------------------

(deftest surface-composer-defaults-to-runtime-when-flag-off
  (testing "with :experimental/static-mode? OFF (default), the composer
            ALWAYS renders Runtime chrome — even when the mode slot
            says :static. The pre-bead byte-identical surface."
    (causa-setup!)
    (config/set-static-mode-enabled! false)
    (frame-dispatch [:rf.causa/set-mode :static])
    (rf/with-frame :rf/causa
      (let [tree (shell/surface-composer)]
        ;; Runtime chrome surfaces by its testids
        (is (some? (find-by-testid tree "rf-causa-ribbon"))
            "Runtime ribbon still mounts")
        (is (some? (find-by-testid tree "rf-causa-event-list"))
            "Runtime L2 event list still mounts")
        ;; Static chrome does NOT mount
        (is (nil? (find-by-testid tree "rf-causa-static-surface"))
            "Static surface does NOT mount with flag off")))))

(deftest surface-composer-renders-static-when-mode-static-and-flag-on
  (testing "with :experimental/static-mode? ON + mode :static, the
            composer renders the Static surface"
    (causa-setup!)
    (config/set-static-mode-enabled! true)
    (frame-dispatch [:rf.causa/set-mode :static])
    (rf/with-frame :rf/causa
      (let [tree (shell/surface-composer)]
        (is (some? (find-by-testid tree "rf-causa-static-surface"))
            "Static surface mounts")
        (is (nil? (find-by-testid tree "rf-causa-ribbon"))
            "Runtime ribbon does NOT mount")
        (is (nil? (find-by-testid tree "rf-causa-event-list"))
            "Runtime L2 event list does NOT mount")))))

(deftest surface-composer-renders-runtime-when-mode-runtime-and-flag-on
  (testing "with :experimental/static-mode? ON + mode :runtime, the
            composer still renders the Runtime chrome"
    (causa-setup!)
    (config/set-static-mode-enabled! true)
    (frame-dispatch [:rf.causa/set-mode :runtime])
    (rf/with-frame :rf/causa
      (let [tree (shell/surface-composer)]
        (is (some? (find-by-testid tree "rf-causa-ribbon"))
            "Runtime ribbon mounts")
        (is (nil? (find-by-testid tree "rf-causa-static-surface"))
            "Static surface does NOT mount")))))

(deftest ribbon-mounts-mode-pill-only-when-flag-on
  (testing "the Runtime ribbon mounts the mode pill ONLY when
            :experimental/static-mode? is ON"
    (causa-setup!)
    ;; flag OFF
    (config/set-static-mode-enabled! false)
    (rf/with-frame :rf/causa
      (let [tree (shell/ribbon)]
        (is (nil? (find-by-testid tree "rf-causa-mode-pill"))
            "mode pill is absent in the Runtime ribbon when flag off")))
    ;; flag ON
    (config/set-static-mode-enabled! true)
    (rf/with-frame :rf/causa
      (let [tree (shell/ribbon)]
        (is (some? (find-by-testid tree "rf-causa-mode-pill"))
            "mode pill mounts in the Runtime ribbon when flag on")))))

;; -------------------------------------------------------------------------
;; (9) Static tab inventory — pure-data shape
;; -------------------------------------------------------------------------

(deftest static-tab-inventory-shape
  (testing "tab inventory carries id/label/mnem/placeholder-bead and
            preserves canonical order"
    (is (= [:machines :routes :schemas :views :events]
           (mapv :id static-shell/tabs))
        "5 tabs in canonical order")
    (doseq [{:keys [id label mnem placeholder-bead]} static-shell/tabs]
      (is (keyword? id) (str "id is keyword for " id))
      (is (string? label) (str "label is a string for " id))
      (is (and (string? mnem) (= 1 (count mnem)))
          (str "mnem is one character for " id))
      (is (re-matches #"rf2-o5f5f\.\d" placeholder-bead)
          (str "placeholder-bead names a sibling bead for " id)))))
