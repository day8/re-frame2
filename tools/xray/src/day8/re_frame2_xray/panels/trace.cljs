(ns day8.re-frame2-xray.panels.trace
  "Trace panel — the whole-epoch trace ARC (spec/023-Trace-Panel.md).

  ## What this panel shows

  The complete trace arc of a single epoch — every trace operation the
  substrate emits during the focused epoch, in fire order, organised by
  the epoch's phase shape (spec/023 §1). Its contract is COMPLETENESS:
  every op-family in the Spec-009 vocabulary surfaces.

  The arc reads top-down:

      ○ EPOCH OPEN   #N :event · frame · snapshotted    (the envelope)
      ▾ ① DISPATCH
          +0.0  EVENT     dispatched   [:counter-inc] from view↗   —
      ▾ ② EVENT HANDLING
          +0.1  COEFFECT  run          :now → #inst…              0.1 ms
          +0.2  EVENT     handler ran  reg-event-db               0.2 ms
          +0.3  FLOW      computed     :totals → [:totals]        1.5 ms
          +1.8  DB        changed      [:counter] 1 → 2             —
      ▾ ③ EFFECTS / FX
          +1.9  FX        :http-xhrio  GET /api/data (queued)      —
      ▾ ④ REACTIVE RENDERING
          +2.6  SUB       recalculated :counter/value 1→2         0.3 ms
          +3.1  VIEW      re-rendered  counter-display            1.8 ms
      ● EPOCH CLOSE  outcome :ok

  Each op is a row of five columns (spec/023 §3):

      Δt · area badge · what-happened · target/detail · duration

  The area badge is a NEUTRAL text badge (EVENT · COEFFECT · DB · FX ·
  FLOW · SUB · VIEW · MACHINE · ROUTING · EPOCH · ERROR · WARNING) — no
  per-family colour; the family identity rides a 3px left-border band
  instead. The four phase bands are COLLAPSIBLE with sticky headers
  (spec/023 §2 / §14); empty bands render dimmed with `(none)` so the
  4-phase shape is always legible (spec/023 §13).

  Errors / warnings are cross-cutting (spec/023 §7) — they render inline
  at their chronological point within whatever band they occurred,
  emphasised so failures stand out. Clicking any row expands its raw
  trace-event EDN inline (spec/023 §3) via the first-class edn-inspector
  widget (spec/021 §10 / `views.edn-inspector`).

  ## Design system (PR #2089 · Handler panel idiom)

  The arc is rendered in the established Xray devtools design language —
  the `--devtools-*` dark tokens via `theme/tokens`, the 13/12/11/10
  type scale, mono font for the data columns, the numbered uppercase
  band headers + thin left rail (mirroring the Handler panel's numbered
  step-circle pipeline in `panels/event_detail.cljs`), and the
  `+`/`~`/`-` diff idiom for DB rows.

  ## Epoch-scoped feed (spec/018 §6)

  Every L4 panel is a lens on the spine's FOCUSED EPOCH, not a global
  ribbon. The Trace tab reads the focused epoch record's `:trace-events`
  via `:rf.xray/trace-feed` — resolved through the shared
  `panels.shared.focus-resolver` against `:rf.xray/focus` +
  `:rf.xray/epoch-history`, exactly as the Issues / App-DB Diff panels
  do. No mock data is baked into the live panel; the arc is fed by real
  trace data throughout.

  ## Empty states

    :no-events     → 'No events.' (focused epoch carries no trace events)
    :no-focus      → 'Select an event to see its trace arc.' (spec/023 §14)
    :epoch-evicted → the focused epoch aged out of the ring buffer.

  ## Pure hiccup

  Same contract as every other Xray panel — pure hiccup, no Reagent /
  UIx / Helix references. Frame isolation comes from the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in shell.cljs.

  ## Helpers

  All pure-data logic — row projection, area / phase / verb / target
  classification, band projection, epoch-scoped feed, empty-state
  classification — lives in `trace_helpers.cljc` so the algebra runs
  under the JVM unit-test target."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]
            [day8.re-frame2-xray.panels.cancellation-cascade-helpers :as cch]
            [day8.re-frame2-xray.panels.event-detail :as event-detail]
            [day8.re-frame2-xray.panels.event.event-status-colour :as event-status]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]
            [day8.re-frame2-xray.panels.trace-helpers :as h]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-widget.widget :as edn]))

;; ---- row grid template (spec/023 §3 · §14) -----------------------------
;;
;; Δt · area badge · what-happened · target/detail · duration. Δt +
;; badge + verb columns are fixed/compact; the target/detail column is
;; the flexible one and truncates first (spec/023 §14 — usable at the
;; ≈420px docked width, no horizontal scroll); duration right-aligns.

(def ^:private row-grid-columns
  "The 5-column grid template for an op row (spec/023 §3)."
  "56px 64px 92px minmax(0, 1fr) auto")

;; ---- payload renderer (spec/023 §3) -------------------------------------
;;
;; Row click → expand the full raw trace-event EDN inline via the
;; first-class edn-inspector widget (spec/021 §10 widget contract /
;; spec/023 §3 row click → expand). The widget owns its own per-mount
;; expansion state keyed by `[panel-id mount-id path]`, so two rows
;; expanded simultaneously each keep an independent expansion tree.
;;
;; Per rf2-hhtbl (rf2-oqa60 phase 2) this call site invokes
;; `[ei/edn-inspector value opts]` directly — no facade hop through
;; `edn/browse`. The per-row `panel-id` qualifier embeds the row id so
;; two simultaneously-expanded rows can't collide on expansion state
;; even if their mount-ids alias (and the auto-id guarantees they
;; won't — this is belt-and-braces).

(defn- render-payload
  "Per-row payload renderer — the raw `:operation` · `:tags` · timing ·
  `:rf.trace/dispatch-id` trace-event EDN (spec/023 §3), rendered via
  the first-class edn-inspector widget."
  [{:keys [id raw] :as _row}]
  [:div {:data-testid (str "rf-xray-trace-row-" id "-payload")
         :style       {:padding       "6px 16px 10px 56px"
                       :background    (:bg-1 tokens)
                       :border-radius "0 0 4px 4px"}}
   [ei/edn-inspector raw
    {:panel-id (keyword "rf.xray.trace" (str "row-" id))
     ;; rf2-pvsxs — trace rows survive tab leave-and-return because
     ;; the trace event-id is itself stable; the `:site-id` reuses it
     ;; so the operator's expansion choices persist across tab churn.
     :site-id  [:rf.xray.trace/row id]
     :default-expanded-depth 1
     ;; rf2-l4625 — trace rows expand within the row's narrow column;
     ;; tags + payload maps are routinely cramped. Popup gives the
     ;; operator a full-modal inspection surface.
     :popup-affordance? true}]])

;; ---- per-path db-changed diff rows (spec/023 §APP-DB CHANGES) -----------
;;
;; The trace event `:rf.event/db-changed` carries only `:event` + `:frame`
;; — no per-path diff payload (Mike-decided rf2-8q8i4 = (b), 2026-05-25:
;; PANEL-SIDE derive). Per-path before→after rows are derived at render
;; time from the focused epoch record's `:db-before` / `:db-after` slots
;; by `trace_helpers/db-changed-diff-triples` (which routes through
;; `app-db-diff-helpers/diff-paths` — the same structural-sharing engine
;; the App-DB Diff tab + the Event-panel APP-DB CHANGES section consume,
;; spec/004 §Changed-paths derivation). The feed projection attaches the
;; triples to every `:rf.event/db-changed` row's `:db-diff` slot so the
;; view stays dumb-and-pure.
;;
;; The row idiom mirrors the Event-panel APP-DB CHANGES section
;; (`panels/event_detail.cljs` `db-change-row`, spec/021 §2.2 step 6
;; mockup):
;;
;;     + [:path] new            (added — green)
;;     ~ [:path] old → new      (modified — amber)
;;     - [:path]                (removed — red — path alone)
;;
;; The diff helper itself (`diff-paths`) is already extracted to
;; `app_db_diff_helpers.cljc` (shared); the render-side `db-change-row`
;; in `event_detail.cljs` is private (`defn-`) — v1 keeps a small
;; trace-flavoured duplicate here (denser layout, slightly different
;; padding to fit the arc's row rhythm) per the rf2-b3zw2 brief's
;; safer-move guidance (event_detail.cljs is post-#2114 stabilised; the
;; lens cluster + B+ section reorder just landed). Dedupe is filed as a
;; follow-on bead.

(def ^:private diff-op->glyph
  "Cascade diff glyph per op (spec/021 §10.3 / event_detail.cljs `op->glyph`)."
  {:added    "+"
   :modified "~"
   :removed  "-"})

(def ^:private diff-op->tone
  "Glyph + path colour per op (spec/021 §10.3 / event_detail.cljs `op->tone`)."
  {:added    :green
   :modified :yellow
   :removed  :red})

(defn- path-suffix
  "Stable testid suffix for a changed path — pr-str of the path vector
  with characters that break a data-testid selector folded to `_`.
  Mirrors event_detail.cljs's `path-suffix` so the testids read
  consistently across the two panels."
  [path]
  (-> (str/join " " (map pr-str path))
      (str/replace #"\s+" "_")))

(defn- db-diff-row
  "Render one changed-path row beneath a `:rf.event/db-changed` op row.
  `triple` is one `app-db-diff-helpers/diff-paths` triple
  (`{:op :path :before :after}`). Pure hiccup; no nav (the parent row's
  click toggles the raw trace-event EDN, this sub-row is informational).
  Shape per spec/021 §2.2 step 6 mockup:

      ~ [:counter]  1 → 2
      + [:last-updated]  #inst \"…\"
      - [:stale]

  Indented under the parent op row so the diff reads as a sub-list of
  the db-changed event."
  [parent-row-id {:keys [op path before after] :as _triple}]
  (let [suffix     (path-suffix path)
        glyph      (get diff-op->glyph op "?")
        tone       (get tokens (get diff-op->tone op) (:text-secondary tokens))
        path-label (f/format-edn (vec path))
        row-test-id (str "rf-xray-trace-row-" parent-row-id
                         "-db-diff-row-" suffix)]
    [:div {:data-testid row-test-id
           :data-op     (name op)
           :on-click    (fn [e] (.stopPropagation e))
           :style       {:display       "flex"
                         :align-items   "baseline"
                         :flex-wrap     "wrap"
                         :gap           "8px"
                         :padding       "1px 16px 1px 56px"
                         :font-family   mono-stack
                         :font-size     "11px"}}
     [:span {:data-testid (str row-test-id "-glyph")
             :style       {:flex        "0 0 12px"
                           :color       tone
                           :font-weight 700
                           :text-align  "center"
                           :user-select "none"}}
      glyph]
     [:span {:data-testid (str row-test-id "-path")
             :style {:color tone}}
      path-label]
     (case op
       :modified
       [:span {:style {:display     "inline-flex"
                       :align-items "baseline"
                       :flex-wrap   "wrap"
                       :gap         "6px"
                       :min-width   0}}
        [:span {:style {:color           (:text-tertiary tokens)
                        :text-decoration "line-through"}}
         (edn/inspect-inline before)]
        [:span {:style {:color (:text-tertiary tokens)}} "→"]
        [:span {:style {:color (:text-primary tokens)}}
         (edn/inspect-inline after)]]

       :added
       [:span {:style {:color (:text-primary tokens) :min-width 0}}
        (edn/inspect-inline after)]

       ;; :removed — path alone (spec/021 line 229 / event_detail `db-change-row`).
       nil)]))

(defn- db-diff-rows
  "Render the per-path diff list under a `:rf.event/db-changed` row. When
  the diff is empty (`db-before == db-after`) renders no list — the spec
  treats the empty diff section as the no-changes case (spec/023
  §APP-DB CHANGES). The container carries the testid
  `rf-xray-trace-row-<id>-db-diff` so tests can target the section
  regardless of contents."
  [parent-row-id triples]
  (when (seq triples)
    (into [:div {:data-testid (str "rf-xray-trace-row-" parent-row-id "-db-diff")
                 :style       {:padding "2px 0 6px 0"}}]
          (for [{:keys [path] :as triple} triples]
            (with-meta (db-diff-row parent-row-id triple)
                       {:key (pr-str path)})))))

;; ---- one op row (spec/023 §3) -------------------------------------------

(defn- op-row
  "One op row in the arc — the five columns of spec/023 §3
  (Δt · area badge · what-happened · target/detail · duration) with the
  op-family colour as a 3px left-border band and the outcome tier tinting
  the what-happened column (spec/023 §8).

  The React `:key` is the row's stable trace `:id` (via `h/row-key`) so
  a trace push doesn't remount the viewport. The row testid keeps the
  `rf-xray-trace-row-` prefix so the shell's scoped rounded-pill hover
  rule (theme/global-styles) lights it.

  Row click → toggle inline raw-EDN payload expansion (spec/023 §3); no
  nav (the panel is already scoped to the focused epoch). Error /
  warning rows are visually emphasised inline (spec/023 §7) — the Δt
  leads with `!`, the badge + verb ride the semantic colour, and the row
  carries a tinted left rail."
  [{:keys [id operation rel-time time area area-badge verb target
           duration-ms source-coord dispatch-id db-diff]
    :as row}
   {:keys [expanded?]}]
  (let [row-test-id (str "rf-xray-trace-row-" id)
        band-colour (h/op-family-colour row)
        verb-colour (h/outcome-colour row)
        severity?   (#{:error :warning} area)
        sev-colour  (when severity?
                      (get tokens (if (= area :error) :red :yellow)))
        destroy?    (cch/destroy-event? {:operation operation})]
    [:li {:key         (h/row-key row)
          :data-testid row-test-id
          :data-rf-xray-expanded (boolean expanded?)
          :data-rf-xray-area     (some-> area name)
          :data-rf-xray-op-family (some-> (h/op-family row) name)
          :data-rf-xray-severity (when severity? (name area))
          :on-click    (fn []
                         (rf/dispatch [:rf.xray/toggle-trace-row-expand id]
                                      {:frame :rf/xray}))
          :on-context-menu (when destroy?
                             (fn [e]
                               (.preventDefault e)
                               (.stopPropagation e)
                               (rf/dispatch
                                 [:rf.xray/cancellation-cascade-open
                                  {:kind :dispatch-id :id dispatch-id}]
                                 {:frame :rf/xray})))
          :style       {:display       "block"
                        ;; op-family colour band — 3px LEFT-BORDER
                        ;; (error / warning override the band so a
                        ;; failure stands out · spec/023 §7).
                        :border-left   (str "3px solid "
                                            (or sev-colour band-colour))
                        :border-radius "4px"
                        :margin-bottom "1px"
                        ;; severity rows carry a faint tinted fill so the
                        ;; failure reads while scanning (spec/023 §7).
                        :background    (cond
                                         expanded? (:bg-1 tokens)
                                         (= area :error)
                                         "color-mix(in srgb, var(--rf-xray-red) 9%, transparent)"
                                         (= area :warning)
                                         "color-mix(in srgb, var(--rf-xray-yellow) 9%, transparent)"
                                         :else nil)
                        :cursor        "pointer"
                        :color         (:text-primary tokens)
                        :font-family   mono-stack
                        :font-size     "12px"
                        :line-height   1.35}}
     [:div {:data-testid (str row-test-id "-summary")
            :style       {:display               "grid"
                          :grid-template-columns row-grid-columns
                          :gap                   "10px"
                          :align-items           "baseline"
                          :padding               "5px 16px 5px 8px"}}
      ;; ① Δt — ms offset from EPOCH OPEN; `!` lead for severity rows.
      [:span {:data-testid (str row-test-id "-time")
              :title       (or (h/format-time time) "")
              :style       {:color       (if severity? sev-colour
                                             (:text-tertiary tokens))
                            :font-size   "11px"
                            :font-weight (when severity? 700)
                            :white-space "nowrap"
                            :text-align  "right"}}
       (cond
         (and severity? rel-time) (str "!" (subs rel-time 1))
         rel-time                 rel-time
         :else                    "—")]
      ;; ② area badge — neutral uppercase text badge (spec/023 §3); the
      ;; severity tiers ride their semantic colour so a failure's family
      ;; is unmistakeable (spec/023 §7 / §8).
      [:span {:data-testid (str row-test-id "-badge")
              :style       {:color          (or sev-colour (:text-tertiary tokens))
                            :font-size      "10px"
                            :font-weight    600
                            :letter-spacing "0.4px"
                            :white-space    "nowrap"}}
       area-badge]
      ;; ③ what-happened — the per-area verb, tinted by outcome tier.
      [:span {:data-testid (str row-test-id "-verb")
              :style       {:color         (if severity? sev-colour verb-colour)
                            :white-space   "nowrap"
                            :overflow      "hidden"
                            :text-overflow "ellipsis"}
              :title       verb}
       verb]
      ;; ④ target / detail — the op's subject; the flexible column that
      ;; truncates first (spec/023 §14). Source-coord ↗ rides at its end.
      [:span {:data-testid (str row-test-id "-target")
              :style       {:display     "flex"
                            :align-items "baseline"
                            :gap         "8px"
                            :min-width   0}}
       [:span {:style {:color         (:text-secondary tokens)
                       :overflow      "hidden"
                       :text-overflow "ellipsis"
                       :white-space   "nowrap"
                       :min-width     0}
               :title (or target "")}
        (or target "—")]
       (when source-coord
         [:button {:data-testid (str row-test-id "-source-coord")
                   ;; The bare `file:line` coord rides the button's title
                   ;; (the ↗ icon is the affordance); the open-in-editor
                   ;; bridge reads this title verbatim.
                   :title       source-coord
                   :on-click    (fn [e]
                                  (.stopPropagation e)
                                  (rf/dispatch [:rf.xray/open-in-editor
                                                {:source-coord source-coord}]
                                               {:frame :rf/xray}))
                   :style       {:flex-shrink   0
                                 :background    "transparent"
                                 :color         (:accent tokens)
                                 :border        "none"
                                 :padding       0
                                 :cursor        "pointer"
                                 :font-family   mono-stack
                                 :font-size     "11px"}}
          "↗"])
       (when destroy?
         [:button {:data-testid (str row-test-id "-cancellation-cascade")
                   :title       "Show cancellation cascade"
                   :on-click    (fn [e]
                                  (.stopPropagation e)
                                  (rf/dispatch
                                    [:rf.xray/cancellation-cascade-open
                                     {:kind :dispatch-id :id dispatch-id}]
                                    {:frame :rf/xray}))
                   :style       {:flex-shrink 0
                                 :background  "transparent"
                                 :color       (or (:red tokens)
                                                  (:text-secondary tokens))
                                 :border      "none"
                                 :padding     0
                                 :cursor      "pointer"
                                 :font-family mono-stack
                                 :font-size   "11px"}}
          "⟲"])]
      ;; ⑤ duration — `N.N ms` when timed, em-dash otherwise (spec/023 §6).
      [:span {:data-testid (str row-test-id "-duration")
              :style       {:color       (:text-tertiary tokens)
                            :font-size   "11px"
                            :white-space "nowrap"
                            :text-align  "right"}}
       (or (h/format-duration duration-ms) "—")]]
     ;; Per-path db-changed diff rows (rf2-b3zw2 / rf2-8q8i4 = (b)) —
     ;; only present on `:rf.event/db-changed` rows; derived panel-side
     ;; from the focused epoch record's `:db-before` / `:db-after` by
     ;; `trace_helpers/db-changed-diff-triples`. Empty diffs render no
     ;; sub-list per spec/023 §APP-DB CHANGES (the empty-diff case).
     (when (= operation :rf.event/db-changed)
       (db-diff-rows id db-diff))
     ;; Row click → raw trace-event EDN inline (spec/023 §3).
     (when expanded? (render-payload row))]))

;; ---- phase band (spec/023 §2 · §13 · §14) -------------------------------

(defn- band-header
  "A collapsible phase-band header — the numbered uppercase label
  (`① DISPATCH`, …) with a chevron + per-band op count, STICKY so the
  current phase stays labelled while a long arc scrolls (spec/023 §14).
  An empty band's header is dimmed (spec/023 §13)."
  [{:keys [id label count empty?]} expanded?]
  [:div {:data-testid (str "rf-xray-trace-band-header-" (name id))
         :data-rf-xray-band id
         :data-rf-xray-empty (boolean empty?)
         :on-click    (when-not empty?
                        (fn []
                          (rf/dispatch [:rf.xray/toggle-trace-band-collapse id]
                                       {:frame :rf/xray})))
         :style       {:position       "sticky"
                       :top            0
                       :z-index        1
                       :display        "flex"
                       :align-items    "center"
                       :gap            "8px"
                       :padding        "6px 16px 6px 8px"
                       :background     (:bg-2 tokens)
                       :border-bottom  (str "1px solid " (:border-subtle tokens))
                       :cursor         (if empty? "default" "pointer")
                       :user-select    "none"
                       :font-family    sans-stack
                       :font-size      "11px"
                       :font-weight    600
                       :letter-spacing "0.6px"
                       :text-transform "uppercase"
                       :color          (if empty?
                                         (:text-tertiary tokens)
                                         (:text-secondary tokens))}}
   (when-not empty?
     [:span {:aria-hidden "true"
             :style {:color (:text-tertiary tokens) :font-size "10px"}}
      (if expanded? "▾" "▸")])
   [:span label]
   [:span {:data-testid (str "rf-xray-trace-band-count-" (name id))
           :style {:color       (:text-tertiary tokens)
                   :font-weight 400
                   :font-size   "10px"
                   :letter-spacing "0"
                   :text-transform "none"}}
    (if empty? "(none)" (str count))]])

(defn- phase-band
  "Render one phase band — its sticky numbered header + its op rows in
  fire order (oldest-first). Empty bands render their dimmed header with
  `(none)` and no rows (spec/023 §13); collapsed bands render the header
  alone. A thin left rail (mirroring the Handler panel's pipeline rail)
  runs down the band's rows so the phase reads as a distinct segment of
  the arc (spec/023 §8)."
  [{:keys [id rows empty?] :as band} {:keys [expanded? expanded-row-ids]}]
  [:section {:data-testid (str "rf-xray-trace-band-" (name id))
             :data-rf-xray-band id
             :style {:margin-bottom "4px"}}
   (band-header band expanded?)
   (when (and expanded? (not empty?))
     [:ul {:data-testid (str "rf-xray-trace-band-rows-" (name id))
           :style {:list-style  "none"
                   :margin      0
                   :padding     "4px 8px 4px 8px"
                   ;; thin left rail per the arc-segment cue (spec/023 §8).
                   :border-left (str "1px solid " (:border-subtle tokens))
                   :margin-left "8px"}}
      (for [row rows]
        ^{:key (h/row-key row)}
        (op-row row {:expanded? (contains? (or expanded-row-ids #{})
                                           (:id row))}))])])

;; ---- epoch envelope (spec/023 §2) ---------------------------------------

(defn- envelope-row
  "Render the EPOCH OPEN / CLOSE envelope band that brackets the arc
  (spec/023 §2). EPOCH OPEN carries the epoch id · event · frame ·
  `snapshotted`; EPOCH CLOSE shows the `:rf.epoch/outcome`. The marker
  glyph (`○` open · `●` close) + the outcome tag distinguish them. The
  envelope's `:rf.epoch/*` ops render as ordinary rows beneath the
  marker so restore/replay lifecycle ops surface (spec/023 §2)."
  [{:keys [kind label outcome rows expanded-row-ids]}]
  (let [open?       (= kind :open)
        outcome-kw  outcome
        outcome-hex (case outcome-kw
                      :ok      (:success tokens)
                      :blocked (:warning tokens)
                      :error   (:error tokens)
                      (:text-tertiary tokens))]
    [:div {:data-testid (str "rf-xray-trace-envelope-" (name kind))
           :data-rf-xray-envelope kind
           :style {:display       "flex"
                   :align-items   "center"
                   :gap           "10px"
                   :padding       "8px 16px 8px 8px"
                   :font-family   sans-stack
                   :font-size     "12px"
                   :font-weight   600
                   :letter-spacing "0.4px"
                   :color         (:text-primary tokens)}}
     [:span {:aria-hidden "true"
             :style {:color     (if open? (:accent tokens) outcome-hex)
                     :font-size "14px"}}
      (if open? "○" "●")]
     [:span {:style {:text-transform "uppercase" :font-size "11px"}}
      label]
     (when (and (not open?) outcome-kw)
       [:span {:data-testid (str "rf-xray-trace-outcome-" (name outcome-kw))
               :data-rf-xray-outcome outcome-kw
               :style {:color          outcome-hex
                       :font-family    mono-stack
                       :font-size      "11px"
                       :font-weight    600
                       :letter-spacing "0"}}
        (str "outcome " outcome-kw)])
     (when (seq rows)
       [:ul {:data-testid (str "rf-xray-trace-envelope-rows-" (name kind))
             :style {:list-style "none" :margin 0 :padding 0 :flex 1 :min-width 0}}
        (for [row rows]
          ^{:key (h/row-key row)}
          (op-row row {:expanded? (contains? (or expanded-row-ids #{})
                                             (:id row))}))])]))

;; ---- empty states (spec/023 §14) ----------------------------------------

(defn- empty-state-message
  "Shared terse empty-state block. `kind` drives the data-testid + copy."
  [kind copy]
  [:div {:data-testid (str "rf-xray-trace-empty-" (name kind))
         :style       {:padding     "24px"
                       :font-family sans-stack
                       :font-size   "13px"
                       :line-height 1.5
                       :color       (:text-secondary tokens)}}
   [:p {:style {:margin 0 :color (:text-tertiary tokens)}}
    copy]])

(defn- empty-state-no-events []
  (empty-state-message :no-events "No events."))

(defn- empty-state-no-focus []
  ;; spec/023 §14 — "Select an event to see its trace arc."
  (empty-state-message :no-focus "Select an event to see its trace arc."))

(defn- empty-state-epoch-evicted []
  (empty-state-message
    :epoch-evicted
    "This epoch has been evicted from the history buffer."))

;; ---- cascade status timeline bar ----------------------------------------

(defn- cascade-status-bar
  "A 3px bar above the arc filling with the focused cascade's lifecycle-
  status colour — driven by `event-status/event-status-colour`, the same
  fn the L2 list rows + the Event L4 header dot consume, so the whole
  devtool speaks ONE lifecycle vocabulary."
  [{:keys [cascade focus]}]
  (let [status-state (event-status/cascade->state
                       cascade focus event-detail/cascade-outcome)
        status-kw    (event-status/classify-status status-state)
        status-hex   (event-status/event-status-colour status-state)]
    [:div {:data-testid (str "rf-xray-trace-cascade-status-bar-"
                             (name status-kw))
           :data-rf-xray-status (name status-kw)
           :title (case status-kw
                    :in-flight       "Focused cascade — in-flight"
                    :settled-success "Focused cascade — settled (success)"
                    :settled-error   "Focused cascade — settled (error)"
                    :paused-by-tool  "Focused cascade — paused by tool"
                    :stale           "Focused cascade — stale (replayed / RETRO)"
                    (str "Focused cascade — " (name status-kw)))
           :style {:height      "3px"
                   :background  status-hex
                   :flex-shrink 0}}]))

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Trace panel's root view — the whole-epoch trace arc
  (spec/023-Trace-Panel.md). Subscribes to `:rf.xray/trace-feed` (the
  epoch-scoped feed) and renders the focused epoch's arc: the EPOCH OPEN
  envelope, the four collapsible phase bands (① DISPATCH · ② EVENT
  HANDLING · ③ EFFECTS / FX · ④ REACTIVE RENDERING) in arc order, and
  the EPOCH CLOSE outcome. Empty bands render dimmed `(none)`
  (spec/023 §13). No mock data — fed by real trace data throughout."
  []
  (let [{:keys [envelope outcome bands empty-kind] :as _data}
        @(rf/subscribe [:rf.xray/trace-feed])
        cascades        @(rf/subscribe [:rf.xray/cascades])
        focus           @(rf/subscribe [:rf.xray/focus])
        focused-id      (:dispatch-id focus)
        focused-cascade (when focused-id
                          (some #(when (= focused-id (:dispatch-id %)) %)
                                cascades))
        expanded-ids    @(rf/subscribe [:rf.xray/trace-expanded-row-ids])
        collapsed-bands @(rf/subscribe [:rf.xray/trace-collapsed-band-ids])]
    [:section {:data-testid "rf-xray-trace"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     (when focused-cascade
       (cascade-status-bar {:cascade focused-cascade :focus focus}))
     [:div {:style {:flex 1 :overflow "auto"}}
      (case empty-kind
        :no-events     (empty-state-no-events)
        :no-focus      (empty-state-no-focus)
        :epoch-evicted (empty-state-epoch-evicted)
        nil
        ;; The arc container keeps the `rf-xray-trace-feed` testid as the
        ;; external "the arc rendered rows" contract surface. Every arc
        ;; child carries an explicit React key (envelope-open · the four
        ;; bands · envelope-close) so the reconciler keys the whole
        ;; sibling list uniformly.
        (into
          [:div {:data-testid "rf-xray-trace-feed"
                 :style {:padding "8px 8px 16px 8px"}}
           ;; ○ EPOCH OPEN — the arc's opening bracket (spec/023 §2).
           ^{:key "envelope-open"}
           (envelope-row {:kind            :open
                          :label           "Epoch open"
                          :rows            (filterv #(not= :rf.epoch/outcome
                                                           (:operation %))
                                                    envelope)
                          :expanded-row-ids expanded-ids})]
          ;; ①–④ the four phase bands, in arc order (spec/023 §2 / §4),
          ;; then the EPOCH CLOSE outcome (spec/023 §13).
          (conj
            (mapv (fn [{:keys [id] :as band}]
                    ^{:key (name id)}
                    (phase-band band
                                {:expanded?        (not (contains?
                                                          (or collapsed-bands #{})
                                                          id))
                                 :expanded-row-ids expanded-ids}))
                  bands)
            ^{:key "envelope-close"}
            (envelope-row {:kind    :close
                           :label   "Epoch close"
                           :outcome outcome
                           :rows    []
                           :expanded-row-ids expanded-ids}))))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Trace panel's Xray-side registrations
  (spec/023-Trace-Panel.md)."
  []
  ;; ---- epoch-scoped feed (spec/018 §6) -------------------------------
  ;;
  ;; The Trace tab reads the FOCUSED EPOCH's `:trace-events` — the
  ;; per-frame settling epoch record's raw trace slice, which folds the
  ;; complete domino trail for one event (the synchronous event-side
  ;; dispatch-id-N rows AND the async nil-dispatch-id reactive rows).
  ;; The focused epoch record is resolved exactly as the Issues / App-DB
  ;; Diff panels resolve theirs: join `:rf.xray/focus` (carrying
  ;; `:epoch-id`) + `:rf.xray/epoch-history`, then run the shared
  ;; `panels.shared.focus-resolver` — which classifies the focus status
  ;; (`:no-focus` / `:focused` / `:epoch-evicted`) and looks up the
  ;; record. `h/project-feed-from-epoch` projects that record's
  ;; `:trace-events` into the arc shape (envelope + 4 phase bands).
  ;;
  ;; Shape of `:rf.xray/trace-feed`:
  ;;
  ;;     {:rows       [<row> ...]   ;; the epoch's domino trail, oldest-first
  ;;      :envelope   [<row> ...]   ;; the EPOCH OPEN / CLOSE :rf.epoch/* ops
  ;;      :outcome    <:ok/:blocked/:error-or-nil>
  ;;      :bands      [{:id :label :rows :count :empty?} ...]  ;; 4 phase bands
  ;;      :total      <int>         ;; the epoch's trace-event count
  ;;      :rendered   <int>         ;; same as :total (no filtering)
  ;;      :epoch-id   <int-or-nil>  ;; the focused epoch's id
  ;;      :empty-kind <:no-events / :no-focus / :epoch-evicted / nil>}
  (rf/reg-sub :rf.xray/trace-feed
    :<- [:rf.xray/focus]
    :<- [:rf.xray/epoch-history]
    (fn [[focus epoch-history] _query]
      (let [focus-epoch-id (:epoch-id focus)
            focus-status   (focus/resolve-focus-status focus-epoch-id
                                                       epoch-history)
            record         (focus/find-epoch-record focus-epoch-id
                                                    epoch-history)]
        (h/project-feed-from-epoch record focus-status))))

  ;; ---- per-row inline payload expansion (spec/023 §3) ----------------
  ;;
  ;; Clicking a row expands its raw trace-event EDN inline (no nav). The
  ;; expanded set lives in app-db so the toggle survives sub-recomputes.

  (rf/reg-sub :rf.xray/trace-expanded-row-ids
    (fn [db _query]
      (get db :trace-expanded-row-ids #{})))

  (rf/reg-event-db :rf.xray/toggle-trace-row-expand
    (fn [db [_ row-id]]
      (let [current (get db :trace-expanded-row-ids #{})]
        (assoc db :trace-expanded-row-ids
               (if (contains? current row-id)
                 (disj current row-id)
                 (conj current row-id))))))

  (rf/reg-event-db :rf.xray/clear-trace-expand
    (fn [db _event]
      (dissoc db :trace-expanded-row-ids :trace-collapsed-band-ids)))

  ;; ---- collapsible phase bands (spec/023 §2 · §14) -------------------
  ;;
  ;; The four phase bands are collapsible (default: all expanded). The
  ;; COLLAPSED set lives in app-db (keyed by the band id) so a band stays
  ;; collapsed across sub-recomputes. An empty band's header is inert —
  ;; the view only dispatches the toggle for a non-empty band.

  (rf/reg-sub :rf.xray/trace-collapsed-band-ids
    (fn [db _query]
      (get db :trace-collapsed-band-ids #{})))

  (rf/reg-event-db :rf.xray/toggle-trace-band-collapse
    (fn [db [_ band-id]]
      (let [current (get db :trace-collapsed-band-ids #{})]
        (assoc db :trace-collapsed-band-ids
               (if (contains? current band-id)
                 (disj current band-id)
                 (conj current band-id))))))

  ;; rf2-2moh1 — register the Dynamic Trace tab with the internal L4
  ;; tab registry. Flows render right after the handler (spec/023 §2 —
  ;; the FLOW rows live in ② EVENT HANDLING), and the tab keeps its
  ;; `t` mnemonic + order-3 placement.
  (panel-registry/reg-l4-tab!
    {:id    :trace
     :label "Trace"
     :mnem  "t"
     :modes #{:dynamic}
     :order 3
     :panel Panel}))
