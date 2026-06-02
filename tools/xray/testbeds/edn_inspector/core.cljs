(ns edn-inspector.core
  "EDN-INSPECTOR testbed (rf2-74u2s → rf2-1niob) — a standard-epochs-style
  driving surface that exercises the Xray edn-inspector THROUGH its primary
  use case: the Epoch and App-db PANELS.

  ## Shape (rework — rf2-1niob)

  ONE tall column of NUMBERED buttons, top to bottom — exactly the
  `standard_epochs` shape. Each button DISPATCHES one event that changes
  REAL app-db at a meaningful path, and the Xray SIDECAR mounted INLINE on
  the right (`[data-rf-xray-host]`, like `standard_epochs`) shows the change
  via the EPOCH panel (db-before / db-after) and the APP-DB panel (the diff).

  Both of those panels render their CLJS values THROUGH the edn-inspector
  (`day8.re-frame2-xray.views.edn-inspector`) — the single value renderer
  behind every Xray panel. So the inspector is demonstrated where it actually
  earns its keep: a button writes a stressing shape into app-db, and the
  inspector renders that shape (and its diff) inside the panels.

  This SUPERSEDES the earlier widget-first deck (PR #2702), which mounted a
  standalone `inspector-stage` widget below the buttons and seeded a single
  `:edn-inspector/fixture` slot. Per Mike (2026-06-01) the inspector is seen
  ONLY through the Epoch + App-db panels — there is no standalone widget
  'elsewhere'.

  ## Per-press delta (standard_epochs pattern)

  Every action event bumps a shared `:baseline` counter (the `bump` helper),
  so the App-db / Epoch panels always show a delta on every press, and the
  per-button capability shape is the ADDITIONAL change layered on top.

  ## Button ladder — each a real app-db change exercising one inspector
  capability AS THE PANELS RENDER IT

    1  Large collection → ELISION. A 50-key map at `:metrics`; the App-db
       panel's inspector elides past its threshold + the body scrolls.
    2  Deeply nested → PATH RENDER / COLLAPSE. A six-level nested map at
       `:tenant`; the inspector renders the path + `▸ {…N keys}` summaries
       past the depth ceiling.
    3  Diff: ADDED. A wholly-new `:flash` subtree (assoc-in) — the App-db
       diff paints `+` / green wash.
    4  Diff: REMOVED → empty (rf2-8pfkk). Dissoc the ONLY key under
       `:session` so the after-tree is `{}` — a struck-through `−`
       removal, NOT a `:added ::missing` leak.
    5  Diff: CHANGED in place (diff-mode-3). Bump a nested scalar
       `[:metrics :counter]` — the value-side `~` glyph + `← was N`.
    5b Diff: SET-MEMBER REMOVED (rf2-l0us2). Remove one member from the
       set at `[:metrics :tags]` (`#{:a :b :c}` → `#{:a :c}` → … → `#{}`,
       re-seeding when empty). The removed member renders member-level
       `−:b` with the set's KEY INTACT — NOT a struck-through whole-key
       'sea of red'. The last-member → `#{}` press exercises the
       empty-collection-replacement expansion of the same fix.
    6  Sensitive → :rf/redacted. Write the `:rf/redacted` sentinel three
       levels deep at `[:session-secure :user :password]` — the inspector
       renders a `redacted` chip, never the raw value.
    7  Large-elided sentinel. Write `{:rf.size/large-elided {…}}` (Spec 015)
       at `[:report-cache :report-42]` — routed through the size-chip
       renderer, not the regular map path.
    8  Mixed types / tagged literals. Write a vector of every scalar kind
       plus `#uuid` + `#inst` tagged literals at `:scalar-mix` — every
       syntax-palette token + the default formatters' compact headers.

  Each press: the Epoch panel shows the cascade (db-before/after); the
  App-db panel shows the inspector rendering the shape / diff.

  ## Inline Xray sidecar (no preload edit)

  Unlike the `_epochs` trio (standard_epochs / routes_epochs / machine_epochs)
  which wire `day8.re-frame2-xray.preload` in shadow-cljs, this deck mounts the
  inline shell from `run` via the public `day8.re-frame2-xray.core/init!` +
  `open!` — the documented manual alternative to the `:preloads` wiring (see
  `core.cljs` §init!). That keeps the inline-host behaviour identical
  (`[data-rf-xray-host]`, the same shell, the same Epoch + App-db panels)
  WITHOUT touching the hot-zone `implementation/shadow-cljs.edn` (the
  `:examples/edn-inspector` build-id already exists from #2702).

  ## Test surface, not tutorial

  Per `feedback_testbeds_are_test_surfaces`: no deliberate bugs, no teaching
  layers, no anti-pattern demos. Each button writes a clean inspector-
  stressing shape; captions are guidance, not lessons.

  ## Test-free + self-contained

  Per rf2-8cevm this testbed carries no spec.cjs; the edn-inspector's
  regression coverage lives in the `panel_gallery` Story gallery
  (gated by the Xray feature-matrix gate) + the substrate contract tests.
  This deck is the manual LIVE driver of the inspector inside the real
  Epoch + App-db panels. The events / subs / views are OWNED here."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Xray's public mount facade — `init!` wires the four
            ;; foundation side-effects (registry, trace-cb, epoch-cb,
            ;; keybinding) and `open!` mounts the inline shell into
            ;; `[data-rf-xray-host]`. The documented manual alternative to
            ;; the `:preloads` wiring, so no shadow-cljs.edn edit is needed.
            [day8.re-frame2-xray.core :as xray]
            ;; Xray's `configure!` to seed `:project-root` so the Event
            ;; lens 'open' chip resolves a classpath-relative `:file` to
            ;; an absolute on-disk URI.
            [day8.re-frame2-xray.config :as xray-config]
            ;; Shared testbed-config helper (rf2-5dphw): derives the
            ;; open-in-editor project-root from the build env.
            [re-frame.testbed.config :as testbed-config])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; APP-DB SEED
;; ============================================================================
;;
;; One flat, named seed. `:baseline` is the shared counter every button
;; bumps (so App-db / Epoch always show a delta on every press). The other
;; slots are the real, meaningful paths the per-button events write the
;; inspector-stressing shapes into — the App-db panel renders them (and their
;; diffs) THROUGH the edn-inspector.

(def initial-db
  {:baseline      0
   :metrics       {:counter 5
                   :tags    #{:a :b :c}}
   :tenant        {}
   :session       {:only-key {:label "removed" :n 42}}
   :session-secure {:user {:id 7}}
   :report-cache  {}
   :scalar-mix    nil})

(rf/reg-event-db :edn-inspector/reset
  {:doc "Button 0 — re-seed app-db."}
  (fn handler-reset [_db _ev]
    initial-db))

;; ============================================================================
;; A small shared helper: every action event bumps the baseline counter.
;; ============================================================================
;;
;; Kept as a plain db->db fn (not an interceptor) so the baseline bump is
;; visible inline in each handler body — the App-db / Epoch delta on every
;; press comes from here, and the per-button capability shape is the
;; ADDITIONAL change layered on top (the standard_epochs pattern).

(defn- bump [db] (update db :baseline inc))

;; ============================================================================
;; EVENTS — the button ladder (each a real app-db write the panels render)
;; ============================================================================

;; -- 1. large collection → elision -------------------------------------------
(rf/reg-event-db :edn-inspector/large-collection
  {:doc "Button 1 — write a 50-key map to `:metrics/grid`."}
  (fn handler-large-collection [db _ev]
    (-> db
        bump
        (assoc-in [:metrics :grid]
                  (into {} (for [i (range 50)]
                             [(keyword (str "metric-" i)) (str "value-" i)]))))))

;; -- 2. deeply nested → path render / collapse -------------------------------
(rf/reg-event-db :edn-inspector/deeply-nested
  {:doc "Button 2 — write a six-level nested map to `:tenant`. The inspector
         renders the path; deep nodes render `▸ {…N keys}` summaries past the
         depth ceiling, ancestors open under the width-aware heuristic."}
  (fn handler-deeply-nested [db _ev]
    (-> db
        bump
        (assoc :tenant
               {:acme {:department {:engineering {:team {:platform
                                                         {:project :xray
                                                          :status  :active
                                                          :leads   ["Ada" "Grace"]}}}}}}))))

;; -- 3. diff: ADDED (green +) ------------------------------------------------
(rf/reg-event-db :edn-inspector/diff-added
  {:doc "Button 3 — assoc-in a wholly-new `:flash` subtree."}
  (fn handler-diff-added [db _ev]
    (-> db
        bump
        (assoc-in [:metrics :flash] {:level :ok :text "Order placed"}))))

;; -- 4. diff: REMOVED → empty (rf2-8pfkk) ------------------------------------
(rf/reg-event-db :edn-inspector/diff-removed-to-empty
  {:doc "Button 4 — dissoc the ONLY key under `:session` so the after-tree is
         `{}`. The App-db diff shows a struck-through `−` removal, NOT a
         `:added ::missing` leak (the bug rf2-8pfkk fixed). Re-seeds the key
         first if it was already removed so there is always something to
         remove."}
  (fn handler-diff-removed-to-empty [db _ev]
    (let [db (bump db)]
      (if (seq (:session db))
        (assoc db :session {})
        (assoc db :session {:only-key {:label "removed" :n 42}})))))

;; -- 5. diff: CHANGED in place (diff-mode-3) ---------------------------------
(rf/reg-event-db :edn-inspector/diff-changed
  {:doc "Button 5 — bump the nested scalar `[:metrics :counter]` in place. The
         App-db diff shows the value-side `~` glyph + `← was N` annotation
         (diff-mode-3 R1)."}
  (fn handler-diff-changed [db _ev]
    (-> db
        bump
        (update-in [:metrics :counter] (fnil inc 0)))))

;; -- 5b. diff: SET-MEMBER REMOVED (rf2-l0us2) --------------------------------
(rf/reg-event-db :edn-inspector/diff-set-member-removed
  {:doc "Button 5b — remove ONE member from the set at `[:metrics :tags]`
         (`#{:a :b :c}` → `#{:a :c}` → … → `#{}`), re-seeding the full set
         once it has been emptied so there is always a member to remove. The
         App-db diff renders the removed member member-level (`−:b`) with the
         set's KEY INTACT — NOT a struck-through whole-key removal (the
         `mark-wholly-changed` 'sea of red' rf2-l0us2 fixed). The
         last-member → `#{}` press exercises the same fix's
         empty-collection-replacement expansion."}
  (fn handler-diff-set-member-removed [db _ev]
    (let [db   (bump db)
          tags (get-in db [:metrics :tags])]
      (if (seq tags)
        (assoc-in db [:metrics :tags] (disj tags (first (sort tags))))
        (assoc-in db [:metrics :tags] #{:a :b :c})))))

;; -- 6. sensitive → :rf/redacted ---------------------------------------------
(rf/reg-event-db :edn-inspector/redacted
  {:doc "Button 6 — write the `:rf/redacted` sentinel three levels deep at
         `[:session-secure :user :password]`. The inspector renders a
         `redacted` chip, never the raw value."}
  (fn handler-redacted [db _ev]
    (-> db
        bump
        (assoc-in [:session-secure :user :password] :rf/redacted))))

;; -- 7. large-elided sentinel (Spec 015) -------------------------------------
(rf/reg-event-db :edn-inspector/large-elided
  {:doc "Button 7 — write a `:rf.size/large-elided` sentinel (Spec 015) at
         `[:report-cache :report-42]`. The inspector routes it through the
         size-chip renderer, not the regular map path."}
  (fn handler-large-elided [db _ev]
    (-> db
        bump
        (assoc-in [:report-cache :report-42]
                  {:rf.size/large-elided {:source        :app-db
                                          :path          [:report-cache :report-42]
                                          :original-size 12480293
                                          :bytes         12480293
                                          :handle        :report/payload-42
                                          :reason        :over-budget}}))))

;; -- 8. mixed types / tagged literals ----------------------------------------
(rf/reg-event-db :edn-inspector/mixed-types
  {:doc "Button 8 — write a vector of every scalar kind PLUS `#uuid` + `#inst`
         tagged literals at `:scalar-mix`. The inspector paints every
         syntax-palette token + the default formatters' compact headers."}
  (fn handler-mixed-types [db _ev]
    (-> db
        bump
        (assoc :scalar-mix
               [nil true false
                42 -7 3.14159
                "hello" "with \"escaped\" quotes"
                :simple :ns/qualified
                'plain-sym 'my.ns/qualified-sym
                (random-uuid)
                (js/Date.)]))))

;; ============================================================================
;; SUBSCRIPTIONS — the on-page mirror of the baseline counter
;; ============================================================================

(rf/reg-sub :edn-inspector/baseline (fn [db _] (:baseline db)))

;; ============================================================================
;; THE BUTTON LADDER
;; ============================================================================

(def ^:private ladder
  "The ordered button ladder. Each row: [n label caption event]. `event` is
  the dispatch vector; `:section` rows are separators (label only)."
  [[:section "Resting / elision / nesting"]
   [1 "Large collection → elision"
    "Add a 50-key map at :metrics/grid"
    [:edn-inspector/large-collection]]
   [2 "Deeply nested → path + collapse"
    "Adds six-level nested map at :tenant — deep nodes shown as summary"
    [:edn-inspector/deeply-nested]]

   [:section "Diff ops — added / removed / changed"]
   [3 "Diff: ADDED (green +)"
    "added new :flash subtree - see the `+` glyph + green wash"
    [:edn-inspector/diff-added]]
   [4 "Diff: REMOVED → empty"
    "remove only key under :session → is `{}`"
    [:edn-inspector/diff-removed-to-empty]]
   [5 "Diff: CHANGED in place"
    "Increment [:metrics :counter] — see value-side `~` glyph + `← was N`"
    [:edn-inspector/diff-changed]]
   ["5b" "Remove set member"
    "Drop one member from the set [:metrics :tags] — removed member renders `−:b`, key intact (not a whole-key strike); last press empties the set"
    [:edn-inspector/diff-set-member-removed]]

   [:section "Sentinels — redaction / size-elision"]
   [6 "Sensitive → :rf/redacted"
    "Add a :rf/redacted sentinel at [:session-secure :user :password] renders a `redacted` chip, never the raw value"
    [:edn-inspector/redacted]]
   [7 "Large-elided sentinel"
    "Add a :rf.size/large-elided sentinel at [:report-cache :report-42]"
    [:edn-inspector/large-elided]]

   [:section "Tagged literals / mixed types"]
   [8 "Mixed types / tagged literals"
    "Add a vector of scalar kinds — nil, boolean, int, float, string, keyword, symbol, #uuid, #inst tagged literals"
    [:edn-inspector/mixed-types]]])

(defn- testid-for [event]
  (-> (first event) name (str "-button")))

(reg-view ladder-button
  "One numbered ladder row: a numbered button on the left, its caption on the
  right. Pressing it dispatches the row's event — a REAL app-db change the
  Epoch + App-db panels render through the inspector."
  [n label caption event]
  [:div {:style {:display "grid" :grid-template-columns "auto 1fr"
                 :gap "0.75em" :align-items "center" :margin "0.35em 0"}}
   [:button {:data-testid (testid-for event)
             :on-click    #(dispatch event)
             :style {:min-width "18em" :text-align "left"
                     :padding "0.4em 0.6em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#fff"}}
    [:span {:style {:font-weight "bold" :color "#7C5CFF" :margin-right "0.5em"}}
     (str n ".")]
    label]
   [:span {:style {:color "#666" :font-size "12px"}} caption]])

(reg-view section-heading
  "A section separator inside the ladder."
  [label]
  [:div {:style {:margin "1em 0 0.25em 0" :font-size "11px" :font-weight "bold"
                 :color "#7C5CFF" :text-transform "uppercase"
                 :letter-spacing "0.04em" :border-top "1px dashed #ddd"
                 :padding-top "0.5em"}}
   label])

(reg-view root []
  [:div {:data-testid "edn-inspector-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "760px"}}
   [:header {:style {:margin-bottom "0.5em"}}
    [:h2 {:style {:margin 0}} "edn-inspector"]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     [:code "edn-inspector"] " is a component used within "
     [:code "xray"] " panels like " [:code "Epoch"] " and " [:code "app-db"]
     ". This is a test app for that component."]
    [:p {:style {:color "#444" :margin "0.5em 0 0 0"}}
     "It is a tall column of buttons. Click them one after the other to "
     "see a series of " [:code "app-db"] " changes rendered within xray "
     "on the right."]]
   ;; Button 0 — Reset.
   [:button {:data-testid "edn-inspector-reset"
             :on-click #(dispatch [:edn-inspector/reset])
             :style {:padding "0.4em 0.8em" :cursor "pointer"
                     :border "1px solid #cfc8ff" :border-radius "6px"
                     :background "#f4f1ff" :margin "0.5em 0"}}
    "0. Reset — re-seed app-db"]
   ;; The ladder.
   (for [row ladder]
     (if (= :section (first row))
       ^{:key (second row)} [section-heading (second row)]
       (let [[n label caption event] row]
         ^{:key n} [ladder-button n label caption event])))])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

;; rf2-5dphw — open-in-editor project-root is derived from the build
;; environment, not a hardcoded personal path (mirrors standard_epochs).
(defn- resolve-project-root []
  (testbed-config/resolve-project-root "tools/xray/testbeds"))

(defn ^:export run []
  ;; Configure Xray BEFORE `rf/init!` so any source-coord chip a panel
  ;; surfaces resolves its classpath-relative `:file` to an on-disk URI.
  (xray-config/configure! {:rf.xray/project-root (resolve-project-root)})
  (rf/init! reagent-adapter/adapter)
  ;; Seed app-db on the single, plain default frame — no URL machinery.
  (rf/dispatch-sync [:edn-inspector/reset])
  (rdc/render react-root [root])
  ;; Mount the inline Xray sidecar (Epoch + App-db panels) into
  ;; `[data-rf-xray-host]`, standard_epochs-style. `init!` is the public
  ;; manual alternative to the `:preloads` wiring (so no shadow-cljs.edn
  ;; edit); `open!` finds the host, registers the `:rf/xray` frame, and
  ;; renders the shell via the installed Reagent adapter. Called AFTER
  ;; `rf/init!` so the substrate adapter is present.
  (xray/init!)
  (xray/open!))
