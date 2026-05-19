(ns day8.re-frame2-causa.panels.event-detail
  "Event lens — the L4 default-tab panel rewritten per Mike's verbatim
  6-section design (rf2-zh2qc, parent rf2-twt7m for substrate keys).

  This panel replaces the v1 'six-domino' renderer with a focused
  Event lens shaped after Chrome DevTools: top-of-panel cascade-outcome
  line + eight stacked sections that read top-to-bottom as the developer
  scans:

      ▼ DISPATCH SITE         where the dispatch happened (source coord)
      ▼ EVENT                 the dispatched event vector
      ▼ COEFFECTS             user-injected coeffects (silent when zero)
      ▼ INTERCEPTORS          non-standard chain (silent when empty)
      ▼ HANDLER               where the handler is defined (source coord)
      ▼ EFFECTS RETURNED      the {:db ... :fx [...]} the handler returned
      ▼ EFFECTS HANDLERS RAN  per-fx-handler rows + managed-fx inline
      ▼ FLOWS                 auto-fired flow recomputes (silent when none)

  All other v1 dominos (subs, renders, other / errors) move to their
  own tabs (Views / Issues). See `tools/causa/spec/018-Event-Spine.md`
  §5.1 + `ai/findings/2026-05-18-event-lens-design.md` for design.

  ## Substrate-driven (rf2-twt7m + rf2-jhhqt)

  Four substrate changes supply the data this lens needs:

    Change 1 — `:event/dispatched` traces carry `:rf.trace/call-site`
               on success-path emits (previously error-only). DISPATCH
               SITE section reads it.
    Change 2 — `:event/do-fx` traces carry `:fx` (the returned vector)
               and `:db-present?` (boolean) on their `:tags`. EFFECTS
               RETURNED section reads them.
    Change 3 — Framework-auto-wrapped interceptors carry
               `:rf/default? true` so INTERCEPTORS can filter them out
               without an allowlist.
    Change 4 — (rf2-jhhqt) `:event/do-fx` traces additionally carry
               `:coeffects` on their `:tags` — the USER-INJECTED subset
               of the handler's final coeffects map (framework defaults
               filtered out at the substrate). COEFFECTS section reads
               it.

  Handler-site coord reads via `(rf/handler-meta :event event-id)` —
  no trace involvement; reads the registry at render time.

  ## Pure hiccup, substrate-agnostic

  The panel emits hiccup; the substrate adapter installed via `rf/init!`
  handles rendering. Per-section helpers mirror
  `managed_fx_template/section` so the visual rhythm is uniform.

  ## What replaced the v1 cascade-detail

    - cascade-detail (six-domino renderer)  → event-lens (7 sections)
    - event-row / handler-row / fx-row      → section/section-header
    - subs-row / renders-row / other-row    → DELETED
                                              (Views / Issues tabs)
    - effects-row                           → §6 EFFECTS RETURNED
                                              + §7 EFFECTS HANDLERS RAN
    - 'Event detail' h1                     → cascade-outcome line

  ## What survives

    - install! (selection slot + composite sub + select/clear events)
    - cascade-list (no-event-selected empty state)
    - tier-dot (reused in cascade-outcome + per-fx duration)"
  (:require [clojure.string :as string]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.panels.managed-fx-helpers :as managed-fx-h]
            [day8.re-frame2-causa.panels.managed-fx-template :as managed-fx]
            [day8.re-frame2-causa.spine :as spine]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.theme.section :as section]
            [day8.re-frame2-causa.theme.perf-tier :as perf-tier]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]))

;; ---- selection plumbing (survives from v1) -----------------------------

(defn- selected-ref
  "Normalise the selection slot. Newer callers pass
  `{:dispatch-id <id> :frame <frame-id>}`; older callers continue to
  resolve by raw id."
  [selection]
  (if (map? selection)
    selection
    {:dispatch-id selection}))

(defn- cascade-has-event?
  "True iff `cascade` carries a real `:event` vector. The `:ungrouped`
  bucket produced by `re-frame.trace.projection/group-cascades` for
  registry-time emits / frame lifecycle outside a drain / REPL evals
  carries no event vector — skip those for default-focus per rf2-639lc."
  [cascade]
  (vector? (:event cascade)))

(defn- default-head-cascade
  "Pick the head (most recent) routed cascade from the cascade vector,
  or nil when none exist. Cascades are oldest-first per group-cascades'
  contract; `last` returns the head."
  [cascades]
  (last (filterv cascade-has-event? cascades)))

(defn- cascade-matches-selection?
  [{:keys [dispatch-id frame]} selection]
  (let [{selected-id :dispatch-id selected-frame :frame} (selected-ref selection)]
    (and (= selected-id dispatch-id)
         (or (nil? selected-frame)
             (= selected-frame frame)))))

;; ---- pure projection helpers --------------------------------------------

(defn- format-coord-display
  "Render a structured source-coord `{:file :line :column :ns}` as the
  display string `\"file:line\"` (or just `\"file\"`). nil when the
  coord lacks `:file`."
  [{:keys [file line]}]
  (when (and (string? file) (seq file))
    (cond-> file
      line (str ":" line))))

(defn- dispatched-event-trace
  "The `:event/dispatched` trace event for the cascade. Carries
  `:rf.trace/call-site` per rf2-twt7m Change 1 + `:source` / `:origin`
  hoisted by `trace.cljc/build-event`. The projection keeps it on
  the `:dispatched` slot per `group-cascades`' contract."
  [{:keys [dispatched]}]
  dispatched)

(defn- do-fx-trace
  "The `:event/do-fx` trace event for the cascade — supplies
  `:fx` + `:db-present?` per rf2-twt7m Change 2. Lives on the
  cascade's `:fx` slot per `group-cascades`."
  [{:keys [fx]}]
  fx)

(defn- handler-trace
  "The `:run-end` handler trace — the cascade's `:handler` slot.
  Carries `:duration-ms` on `:tags`."
  [{:keys [handler]}]
  handler)

(defn- has-handler-exception?
  "True iff the cascade's `:other` bucket carries a handler exception
  trace. Used to swap the cascade-outcome glyph from ✓ to ✗ and to
  suppress §5/§6 (the handler never returned)."
  [{:keys [other]}]
  (boolean
    (some (fn [ev]
            (let [op (:operation ev)]
              (or (= :rf.error/handler-exception op)
                  (= :rf.error/handler-threw op))))
          (or other []))))

(defn- has-warning?
  "True iff the cascade carries a non-fatal warning that should pivot
  the outcome glyph to ⚠ (amber). Per §5.1 the warning set is:
  depth-exceeded, schema-violation-then-skipped. Pure predicate over
  the cascade's `:other` bucket."
  [{:keys [other]}]
  (boolean
    (some (fn [ev]
            (let [op (:operation ev)]
              (or (= :rf.warning/depth-exceeded op)
                  (= :rf.warning/schema-violation-skipped op))))
          (or other []))))

(defn cascade-outcome
  "Project a cascade record into a outcome-summary map for the top-of-
  panel cascade-outcome line:

      {:event-id    <kw>           ;; first element of :event vec
       :glyph       \"✓\" | \"✗\" | \"⚠\"
       :outcome     :ok | :error | :warning
       :duration-ms <num-or-nil>
       :dispatch-id <int>
       :ssr?        <bool>}        ;; true when this was an SSR-hydration cascade

  Pure data → data. JVM-portable."
  [{:keys [event handler dispatch-id] :as cascade}]
  (let [event-id    (when (vector? event) (first event))
        duration-ms (get-in handler [:tags :duration-ms])
        ssr?        (or (= :rf.ssr/hydrated event-id)
                        (= :rf.ssr/hydration-complete event-id))
        [outcome glyph] (cond
                          (has-handler-exception? cascade) [:error   "✗"]
                          (has-warning? cascade)           [:warning "⚠"]
                          :else                            [:ok      "✓"])]
    {:event-id    event-id
     :glyph       glyph
     :outcome     outcome
     :duration-ms duration-ms
     :dispatch-id dispatch-id
     :ssr?        ssr?}))

(defn- outcome-colour
  [outcome]
  (case outcome
    :error   (:red tokens)
    :warning (:yellow tokens)
    :ok      (:green tokens)
    (:text-secondary tokens)))

(def ^:private default-interceptor-id?
  "Per rf2-twt7m Change 3 the framework-auto-wrapped handler
  interceptors carry `:rf/default? true` on the interceptor map.
  Until older registrations migrate we also fallback-match the three
  known ids so the panel never surfaces them."
  #{:rf/db-handler :rf/fx-handler :rf/ctx-handler})

(defn user-interceptors
  "Filter `interceptors` (the chain on `(rf/handler-meta :event id)
  :interceptors`) down to the user-visible ones — drop any flagged
  `:rf/default? true`, plus the known auto-wrapper ids as a belt-and-
  braces fallback. Pure fn; JVM-testable."
  [interceptors]
  (vec
    (remove (fn [i]
              (or (true? (:rf/default? i))
                  (contains? default-interceptor-id? (:id i))))
            (or interceptors []))))

(defn effects-handlers-ran
  "Build a vector of fx-handler rows for the §6 EFFECTS HANDLERS RAN
  section. Each row carries `{:fx-id :operation :id :ev :duration-ms
  :status}`. Read directly off the cascade's `:effects` slot. Pure fn."
  [{:keys [effects]}]
  (vec
    (for [ev (or effects [])]
      {:fx-id       (get-in ev [:tags :fx-id])
       :fx-args     (get-in ev [:tags :fx-args])
       :operation   (:operation ev)
       :id          (:id ev)
       :duration-ms (get-in ev [:tags :duration-ms])
       :ev          ev})))

(defn- fx-handled-status
  "Map a fx-handled trace's `:operation` onto a compact status keyword
  the row caption uses."
  [operation]
  (case operation
    :rf.fx/handled                  :ok
    :rf.fx/override-applied         :overridden
    :rf.fx/skipped-on-platform      :skipped
    :rf.error/fx-handler-exception  :error
    :rf.error/no-such-fx            :error
    :ok))

(defn- fx-status-colour
  [status]
  (case status
    :ok          (:green tokens)
    :overridden  (:accent-violet tokens)
    :skipped     (:text-tertiary tokens)
    :error       (:red tokens)
    (:text-secondary tokens)))

(defn hydration-outcome-row
  "Project the `:rf.ssr/hydration-outcome` row for §5 when the focused
  event is a hydration-completion synthetic. Pure fn; returns nil when
  the cascade isn't an SSR-hydration cascade or has no payload to
  surface.

  Reads the outcome data off the cascade's `:other` bucket — the
  substrate emits `:rf.ssr/hydration-outcome` (or carries it on the
  `:event/dispatched`'s tags); we look in both places to be tolerant."
  [{:keys [event other] :as _cascade}]
  (let [event-id (when (vector? event) (first event))]
    (when (or (= :rf.ssr/hydrated event-id)
              (= :rf.ssr/hydration-complete event-id))
      (or
        ;; Preferred: dedicated outcome trace on the cascade's :other
        ;; bucket.
        (some (fn [ev]
                (when (= :rf.ssr/hydration-outcome (:operation ev))
                  (:tags ev)))
              (or other []))
        ;; Fallback: payload rode in on the :event vector itself.
        (when (and (vector? event) (>= (count event) 2))
          (second event))))))

(defn- dispatch-call-site
  "Pluck the dispatch-site coord off the cascade's `:event/dispatched`
  trace. Per rf2-twt7m Change 1 the coord rides as
  `:rf.trace/call-site` on the success-path emit. Returns the
  structured source-coord map (`{:file :line :column :ns}`) or nil."
  [cascade]
  (some-> (dispatched-event-trace cascade) :rf.trace/call-site))

(defn- dispatch-source+origin
  "Pluck `:source` (e.g. `:ui` `:timer` `:http`) and `:origin` from the
  dispatched trace. Both are hoisted to the top level on success-path
  emits via `trace.cljc/build-event`. Returns `[source origin]`."
  [cascade]
  (let [ev (dispatched-event-trace cascade)]
    [(or (:source ev) (get-in ev [:tags :source]))
     (or (:origin ev) (get-in ev [:tags :origin]))]))

(defn- effects-returned
  "Project the §5 EFFECTS RETURNED rows from the cascade's `:event/do-fx`
  trace. Per rf2-twt7m Change 2 the trace carries `:fx` (the vector
  returned) and `:db-present?` (boolean). Returns
  `{:fx [...] :db-present? <bool> :present? <bool>}`. `:present?` is
  true iff EITHER `:fx` or `:db-present?` is non-empty / true — used
  to decide whether to render §5 at all (silent-by-default)."
  [cascade]
  (let [tags        (some-> (do-fx-trace cascade) :tags)
        fx          (:fx tags)
        db-present? (boolean (:db-present? tags))]
    {:fx          fx
     :db-present? db-present?
     :present?    (or db-present? (seq fx))}))

;; Section rhythm hoisted to `theme/section.cljc` per rf2-pie8q —
;; identical visual contract is shared with
;; `panels/managed_fx_template`. The Event lens panel uses the
;; primitive's defaults: body always expanded (the lens does not
;; collapse), `:container-padding` defaults to "8px 12px".

;; ---- tier-dot (reused from v1, survives) -------------------------------

(defn- tier-dot
  "Render a perf-tier coloured dot + label for `duration-ms`.
  Reused in the cascade-outcome line + per-fx-handler rows."
  [duration-ms]
  (when (number? duration-ms)
    (let [tier   (perf-tier/classify-tier duration-ms)
          colour (perf-tier/tier-colour tier)
          glyph  (perf-tier/tier-glyph tier)
          label  (perf-tier/tier-label tier)]
      [:span {:data-testid (str "rf-causa-event-detail-tier-dot-" (name tier))
              :aria-label  (str label " (" duration-ms "ms)")
              :title       (str label " — " duration-ms "ms")
              :style       {:display      "inline-flex"
                            :align-items  "center"
                            :gap          "6px"
                            :color        colour
                            :font-weight  600}}
       [:span {:style {:font-size "12px"}} glyph]
       [:span {:style {:font-family mono-stack
                       :font-size   "11px"
                       :color       (:text-secondary tokens)}}
        (str duration-ms "ms")]])))

;; ---- chrome: cascade-outcome line --------------------------------------

(defn- cascade-outcome-line
  "Top-of-panel single-line health summary. Replaces the v1
  literal 'Event detail' h1."
  [cascade]
  (let [{:keys [event-id glyph outcome duration-ms dispatch-id ssr?]}
        (cascade-outcome cascade)]
    [:header {:data-testid "rf-causa-event-detail-outcome"
              :data-outcome (name outcome)
              :style {:display       "flex"
                      :align-items   "center"
                      :gap           "10px"
                      :padding       "12px 16px"
                      :background    (:bg-3 tokens)
                      :border-bottom (str "1px solid " (:border-subtle tokens))
                      :font-family   sans-stack
                      :font-size     "13px"}}
     [:span {:data-testid "rf-causa-event-detail-outcome-event-id"
             :style {:color (:accent-violet tokens)
                     :font-family mono-stack
                     :font-weight 600}}
      (pr-str event-id)]
     [:span {:style {:flex 1}}]
     [:span {:data-testid (str "rf-causa-event-detail-outcome-glyph-" (name outcome))
             :style {:color       (outcome-colour outcome)
                     :font-weight 700
                     :font-size   "14px"}}
      glyph]
     [:span {:style {:color (outcome-colour outcome)
                     :font-family mono-stack
                     :font-weight 600}}
      (case outcome :ok "ok" :error "error" :warning "warning")]
     [:span {:style {:color (:text-tertiary tokens)}} "·"]
     (or (tier-dot duration-ms)
         [:span {:style {:color (:text-tertiary tokens)
                         :font-family mono-stack
                         :font-size "11px"}}
          "—"])
     [:span {:style {:color (:text-tertiary tokens)}} "·"]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size "11px"}}
      (str "cascade #" dispatch-id)]
     (when ssr?
       [:span {:data-testid "rf-causa-event-detail-outcome-ssr-badge"
               :style {:color (:cyan tokens)
                       :font-family mono-stack
                       :font-weight 700
                       :font-size "11px"
                       :margin-left "6px"}}
        "SSR✓"])]))

;; ---- §2 EVENT ----------------------------------------------------------

(defn- event-section
  [event-vec]
  (section/section-row
    {:label "EVENT"
     :testid "rf-causa-event-detail-section-event"}
    [:div {:data-testid "rf-causa-event-detail-event-vector"
           :style {:font-weight 600}}
     (inspector/inspect event-vec "event-detail/event")]))

;; ---- §1 DISPATCH SITE --------------------------------------------------

(defn- coord-chip
  "Reusable 'open in editor' chip. Renders nothing when `coord` has no
  `:file`. Dispatches `:rf.causa/open-in-editor` with the structured
  coord; the trace-bus thereby records the click + the editor handler
  resolves the URI through the rf2-cm93v allowlist."
  [coord testid]
  (when (and (map? coord) (seq (:file coord)))
    [:button {:data-testid testid
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.causa/open-in-editor
                                           {:source-coord coord}]
                                          {:frame :rf/causa}))
              :style       {:background  "transparent"
                            :color       (:cyan tokens)
                            :border      (str "1px solid " (:border-default tokens))
                            :padding     "1px 8px"
                            :border-radius "3px"
                            :margin-left "8px"
                            :cursor      "pointer"
                            :font-family mono-stack
                            :font-size   "10px"}}
     "open ↗"]))

(defn- dispatch-site-section
  [cascade]
  (let [coord            (dispatch-call-site cascade)
        [source origin]  (dispatch-source+origin cascade)
        display          (format-coord-display coord)]
    (section/section-row
      {:label "DISPATCH SITE"
       :testid "rf-causa-event-detail-section-dispatch"}
      [:div
       [:div {:style {:display "flex" :align-items "center"}}
        (if display
          [:span {:data-testid "rf-causa-event-detail-dispatch-coord"
                  :style {:color (:text-primary tokens)}}
           display]
          [:span {:data-testid "rf-causa-event-detail-dispatch-coord-absent"
                  :style {:color (:text-tertiary tokens)
                          :font-style "italic"}}
           "source coord unavailable"])
        (coord-chip coord "rf-causa-event-detail-dispatch-open-chip")]
       (when (or source origin)
         [:div {:data-testid "rf-causa-event-detail-dispatch-caption"
                :style {:color (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-size "11px"
                        :margin-top "4px"}}
          (cond-> "via "
            source (str source)
            (and source origin) (str " · origin ")
            (and (not source) origin) (str "origin ")
            origin (str origin))])])))

;; ---- shared id → testid-suffix renderer --------------------------------

(defn- interceptor-testid-suffix
  "Render an interceptor / cofx / fx id into a stable testid suffix.
  Qualified keywords (`:auth/require-login`) render as
  `auth/require-login`; bare keywords use their name; non-keyword ids
  fall through `str`. Shared across §3 COEFFECTS, §4 INTERCEPTORS, and
  §7 EFFECTS HANDLERS RAN so a test asserting on a per-row testid sees
  the same id the registration declares."
  [id]
  (cond
    (qualified-keyword? id) (str (namespace id) "/" (name id))
    (keyword? id)           (name id)
    (nil? id)               "unknown"
    :else                   (str id)))

;; ---- §3 COEFFECTS ------------------------------------------------------

(defn user-coeffects
  "Project the user-injected coeffects map off the cascade's
  `:event/do-fx` trace (rf2-jhhqt — substrate Change 4 stamps the
  user-injected subset on `:tags :coeffects`). Pure fn; JVM-testable.

  Returns the map (preserving id → value pairs) or nil when the
  cascade carries no coeffects stamp / the stamp is empty. The
  substrate filters the framework defaults (`:db` `:event` `:frame`
  `:source` `:trace-id`) at emit-time so this fn is a thin reader —
  it does NOT re-filter."
  [cascade]
  (let [m (some-> (do-fx-trace cascade) :tags :coeffects)]
    (when (and (map? m) (seq m))
      m)))

(defn- coeffect-row
  [id value]
  (let [suffix (interceptor-testid-suffix id)]
    [:div {:data-testid (str "rf-causa-event-detail-coeffect-row-" suffix)
           :style {:display "flex"
                   :align-items "flex-start"
                   :padding "2px 0"}}
     [:span {:style {:color (:accent-violet tokens)
                     :min-width "180px"
                     :margin-right "12px"}}
      (pr-str id)]
     [:span {:style {:color (:text-primary tokens)
                     :min-width 0
                     :flex 1
                     :word-break "break-word"}}
      (inspector/inspect value (str "event-detail/coeffect/" suffix))]]))

(defn- coeffects-section
  "§3. Silent-by-default — section is ABSENT entirely when zero user
  coeffects were injected (mirrors INTERCEPTORS' posture)."
  [cascade]
  (let [user-cofx (user-coeffects cascade)]
    (when (seq user-cofx)
      (section/section-row
        {:label "COEFFECTS"
         :count* (count user-cofx)
         :testid "rf-causa-event-detail-section-coeffects"}
        (into [:div]
              ;; Per rf2-ppzid — `with-meta` on fn return preserves :key.
              (for [[id v] user-cofx]
                (with-meta (coeffect-row id v)
                           {:key (pr-str id)})))))))

;; ---- §4 INTERCEPTORS ---------------------------------------------------

(defn- interceptor-row
  [{:keys [id file line] :as _interceptor}]
  (let [coord   (when (string? file) {:file file :line line})
        display (format-coord-display coord)
        suffix  (interceptor-testid-suffix id)]
    [:div {:data-testid (str "rf-causa-event-detail-interceptor-row-" suffix)
           :style {:display "flex"
                   :align-items "center"
                   :padding "2px 0"}}
     [:span {:style {:color (:accent-violet tokens)
                     :margin-right "12px"
                     :min-width "180px"}}
      (pr-str id)]
     (if display
       [:span {:style {:color (:text-secondary tokens)}}
        display]
       [:span {:style {:color (:text-tertiary tokens)
                       :font-style "italic"
                       :font-size "11px"}}
        "rf2 std-interceptor"])
     (coord-chip coord
                 (str "rf-causa-event-detail-interceptor-open-chip-" suffix))]))

(defn- interceptors-section
  "Silent-by-default per §4.2 — the section is ABSENT entirely when
  the user has no non-standard interceptors. Pre-computed via
  `user-interceptors` (test-level helper) before invoking this fn."
  [user-icpts]
  (when (seq user-icpts)
    (section/section-row
      {:label "INTERCEPTORS"
       :count* (count user-icpts)
       :testid "rf-causa-event-detail-section-interceptors"}
      (into [:div]
            ;; Per rf2-ppzid: `^{:key ...}` reader-meta on a fn
            ;; CALL FORM (a list) is lost — the fn returns a
            ;; fresh vector; `get-react-key` only reads :key
            ;; meta off the returned vector. `with-meta` on
            ;; the fn return preserves the key correctly.
            (for [icpt user-icpts]
              (with-meta (interceptor-row icpt)
                         {:key (pr-str (:id icpt))}))))))

;; ---- §5 HANDLER --------------------------------------------------------

(defn- handler-section
  "Renders the handler-meta read off `(rf/handler-meta :event id)`.
  Per Q2: shows `reg-event-<kind>` flavour + source coord; does NOT
  duplicate the event-id (already shown in §1)."
  [event-id meta]
  (let [kind   (:event/kind meta)
        coord  (when (string? (:file meta))
                 {:file (:file meta) :line (:line meta)})
        display (format-coord-display coord)
        flavour (case kind
                  :db  "reg-event-db"
                  :fx  "reg-event-fx"
                  :ctx "reg-event-ctx"
                  (if kind (str "reg-event-" (name kind)) "reg-event-?"))]
    (section/section-row
      {:label "HANDLER"
       :testid "rf-causa-event-detail-section-handler"}
      [:div {:style {:display "flex" :align-items "center"}}
       [:span {:data-testid "rf-causa-event-detail-handler-flavour"
               :style {:color (:cyan tokens)
                       :font-weight 600
                       :margin-right "8px"}}
        flavour]
       [:span {:style {:color (:text-tertiary tokens)
                       :margin-right "8px"}}
        "·"]
       (if display
         [:span {:data-testid "rf-causa-event-detail-handler-coord"
                 :style {:color (:text-primary tokens)}}
          display]
         [:span {:data-testid "rf-causa-event-detail-handler-coord-absent"
                 :style {:color (:text-tertiary tokens)
                         :font-style "italic"}}
          (if event-id
            (str "no registration found for " (pr-str event-id))
            "no handler registered")])
       (coord-chip coord "rf-causa-event-detail-handler-open-chip")])))

;; ---- §6 EFFECTS RETURNED -----------------------------------------------

(defn- effects-returned-row
  [label value testid value-style]
  [:div {:data-testid testid
         :style {:display "flex"
                 :align-items "flex-start"
                 :padding "2px 0"}}
   [:span {:style {:color (:accent-violet tokens)
                   :min-width "180px"
                   :margin-right "12px"}}
    label]
   [:span {:style (merge {:color (:text-primary tokens)
                          :min-width 0
                          :flex 1
                          :word-break "break-word"}
                         (or value-style {}))}
    value]])

(defn- hydration-issues-jump-button
  []
  [:div {:data-testid "rf-causa-event-detail-hydration-issues-jump"
         :style {:padding "4px 0 0 192px"}}
   [:button {:on-click #(rf/dispatch [:rf.causa/select-tab :issues]
                                     {:frame :rf/causa})
             :style {:background  "transparent"
                     :color       (:accent-violet tokens)
                     :border      (str "1px solid " (:border-default tokens))
                     :padding     "1px 8px"
                     :border-radius "3px"
                     :cursor      "pointer"
                     :font-family mono-stack
                     :font-size   "10px"}}
    "→ jump to Issues bisector"]])

(defn- effects-returned-section
  "§5. Silent-by-default per §7.3 — the section is ABSENT when neither
  `:db` nor `:fx` was returned. Optionally surfaces the hydration-
  outcome row when the focused event is `:rf.ssr/hydrated`."
  [cascade]
  (let [{:keys [fx db-present? present?]} (effects-returned cascade)
        hydration  (hydration-outcome-row cascade)
        mismatches (or (:mismatches hydration)
                       (:rf.ssr/mismatches hydration)
                       0)
        db-row     (when db-present?
                     (effects-returned-row
                       ":db"
                       "<… changed; see App-db tab …>"
                       "rf-causa-event-detail-effects-returned-row-db"
                       {:color (:text-tertiary tokens)
                        :font-style "italic"}))
        fx-row     (when (seq fx)
                     (effects-returned-row
                       ":fx"
                       (inspector/inspect (vec fx) "event-detail/effects-returned/fx")
                       "rf-causa-event-detail-effects-returned-row-fx"
                       nil))
        hyd-row    (when hydration
                     (effects-returned-row
                       ":rf.ssr/hydration-outcome"
                       (inspector/inspect hydration
                                          "event-detail/effects-returned/hydration")
                       "rf-causa-event-detail-effects-returned-row-hydration"
                       nil))
        jump-row   (when (and hydration (pos? mismatches))
                     (hydration-issues-jump-button))
        rows       (filterv some? [db-row fx-row hyd-row jump-row])]
    (when (or present? hydration)
      (section/section-row
        {:label "EFFECTS RETURNED"
         :testid "rf-causa-event-detail-section-effects-returned"}
        (into [:div] rows)))))

;; ---- §7 EFFECTS HANDLERS RAN -------------------------------------------

(defn- dispatch-fx-summary
  "Render the `:dispatch` fx-handler row's caption — `→ queued event
  [:foo …]` plus a click-to-focus affordance when the child cascade is
  resolvable. For v1 we surface the dispatched event vector verbatim;
  the focus pivot needs the child cascade-id which the fx-args don't
  always carry — the row is best-effort."
  [fx-args]
  (let [child-event (first fx-args)]
    [:span
     [:span {:style {:color (:text-secondary tokens)}}
      "→ queued "]
     [:span {:style {:color (:text-primary tokens)}}
      (pr-str child-event)]]))

(defn- managed-fx-record-for-row
  "Find the managed-fx record that corresponds to a given fx-handled
  row, keyed by `:origin-event-id` (the row's trace-event `:id`).
  Returns nil for non-managed fxs."
  [records origin-event-id]
  (some (fn [rec]
          (when (= origin-event-id (:origin-event-id rec))
            rec))
        records))

(defn- fx-handler-row
  "One row inside §6. Renders fx-id chip + tier-dot + status caption,
  followed (when applicable) by an inline managed-fx record-panel
  per §8.3."
  [{:keys [fx-id fx-args operation id duration-ms]} managed-fx-record]
  (let [status (fx-handled-status operation)
        colour (fx-status-colour status)
        suffix (interceptor-testid-suffix fx-id)]
    [:div {:data-testid (str "rf-causa-event-detail-effects-ran-row-" suffix)
           :style {:padding "4px 0"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "10px"
                    :flex-wrap "wrap"}}
      [:span {:style {:color (:accent-violet tokens)
                      :min-width "160px"}}
       (pr-str fx-id)]
      (when duration-ms (tier-dot duration-ms))
      [:span {:style {:color colour :font-weight 600 :font-size "11px"}}
       (case status
         :ok          "✓ handled"
         :overridden  "◑ overridden"
         :skipped     "○ skipped on platform"
         :error       "✗ errored"
         "—")]
      (when (= :dispatch fx-id)
        (dispatch-fx-summary fx-args))]
     (when managed-fx-record
       [:div {:data-testid (str "rf-causa-event-detail-effects-ran-managed-fx-"
                                 (or id "x"))
              :style {:margin "6px 0 4px 16px"}}
        (managed-fx/record-panel managed-fx-record)])]))

(defn- effects-handlers-ran-section
  "§6. Silent-by-default per §7.3 — the section is ABSENT when no fx
  handlers ran (e.g. a no-op `reg-event-db` returning the same db).
  Inline-mounts the managed-fx record beneath its causing fx-handler
  row per §8.3 (colocation)."
  [cascade]
  (let [rows    (effects-handlers-ran cascade)
        records (managed-fx-h/cascade->managed-fx-records cascade)]
    (when (seq rows)
      (section/section-row
        {:label "EFFECTS HANDLERS RAN"
         :count* (count rows)
         :testid "rf-causa-event-detail-section-effects-ran"}
        (into [:div]
              ;; Per rf2-ppzid — `with-meta` on the fn return,
              ;; not `^{:key ...}` on the call form.
              (for [{:keys [id] :as row} rows]
                (with-meta
                  (fx-handler-row row (managed-fx-record-for-row records id))
                  {:key id})))))))

;; ---- §8 FLOWS ----------------------------------------------------------
;; rf2-lo37i — Flows fire automatically AFTER fx handlers run. Each flow's
;; `:output` fn reads from `:inputs` paths and writes to a `:path`. Without
;; first-class visibility here a developer cannot attribute an app-db
;; change to the flow that caused it. Surfaced as a peer section sitting
;; after §7 EFFECTS HANDLERS RAN — the cascade-order placement: flows are
;; the framework's automatic step after the handler-effects complete.
;;
;; Per spec/013-Flows.md + spec/009-Instrumentation.md:
;;   `:rf.flow/computed` (op-type `:flow`) carries `:flow-id`,
;;   `:input-values`, `:result`, `:path`, `:frame` in `:tags`. Input
;;   PATHS are not in the trace — they live on the flow registry entry
;;   and are looked up via `(rf/handler-meta :flow id)` at render time.
;;
;; The before-value at the output path (the value the flow OVERWROTE)
;; is not in the current trace; rf2-qlzh4 (open) adds a `:before` slot
;; to `:rf.flow/computed` for full self-containment. Until then we
;; render `(after-value)` only — better partial visibility than zero.

(defn- flow-computed?
  [ev]
  (= :rf.flow/computed (:operation ev)))

(defn- flow-skip?
  [ev]
  (= :rf.flow/skip (:operation ev)))

(defn flows-fired
  "Project the ordered seq of flow firings from a cascade's `:other`
  bucket. Each row is the projection of one `:rf.flow/computed` trace
  in cascade firing order (which is the framework's topo order — a
  flow downstream of another flow's output ALWAYS fires after the
  upstream flow).

  Per-row shape:

      {:flow-id      <keyword>      ;; the flow's :id
       :write-path   <vec>          ;; the flow's :path (where it wrote)
       :input-values <vec>          ;; raw values read from input paths
       :result       <any>          ;; the new output value at :path
       :frame        <kw-or-nil>    ;; the host frame
       :trace-id     <int>}         ;; trace event :id (stable row key)

  Pure data → data. Returns an empty vector when the cascade carries
  no `:rf.flow/computed` events (silent-by-default — the section is
  OMITTED entirely for the empty state)."
  [{:keys [other]}]
  (vec
    (for [ev (filterv flow-computed? (or other []))]
      (let [tags (:tags ev)]
        {:flow-id      (:flow-id tags)
         :write-path   (:path tags)
         :input-values (:input-values tags)
         :result       (:result tags)
         :frame        (:frame tags)
         :trace-id     (:id ev)}))))

(defn flows-skipped
  "Project the ordered seq of `:rf.flow/skip` firings (value-equal
  dirty-check suppression per Spec 013 §Dirty-check semantics).

  Skips are NOT rendered as flow rows — a flow that didn't recompute
  didn't write app-db, so it's noise inside the cascade-detail. The
  helper exists for tests + future surfaces (a future toggle could
  expose them; for the silent-by-default rendering policy they stay
  hidden)."
  [{:keys [other]}]
  (vec
    (for [ev (filterv flow-skip? (or other []))]
      (let [tags (:tags ev)]
        {:flow-id  (:flow-id tags)
         :reason   (:reason tags)
         :frame    (:frame tags)
         :trace-id (:id ev)}))))

(defn flow-read-paths
  "Look up the registered `:inputs` paths for a flow id. Reads
  `(rf/handler-meta :flow flow-id)` so the read paths render even
  though the per-firing `:rf.flow/computed` trace doesn't carry them.

  Returns the input-paths vector (e.g. `[[:cart :items] [:tax :rate]]`)
  or `nil` when the flow is no longer registered (e.g. cleared
  mid-session via `:rf.fx/clear-flow`)."
  [flow-id]
  (when flow-id
    (some-> (rf/handler-meta :flow flow-id) :inputs)))

(defn flows-with-chain-marks
  "Tag each flow row with `:via?` — true when ANY of its read paths
  matches a preceding flow row's write path. Subtle indicator for
  the chained-flow case (Mike's §13 design — '↳ via :upstream-flow').

  Pure data → data. Walks rows left-to-right; the `:via?` decision
  depends on every preceding row's `:write-path`, so the result is
  order-sensitive — call AFTER `flows-fired` (which preserves
  cascade order).

  Returns a vector matching the input order, each row enriched with:

    `:read-paths`  — input-paths vec (looked up from registry; nil
                      when the flow is no longer registered)
    `:via?`        — true iff at least one read-path overlaps with a
                      preceding row's write-path
    `:via-flow-ids` — vec of upstream flow-ids the chain rides on
                      (empty when `:via?` is false). Stable order:
                      first-write-wins per upstream path."
  [rows]
  (vec
    (reduce
      (fn [acc {:keys [flow-id] :as row}]
        (let [read-paths   (flow-read-paths flow-id)
              path->writer (into {} (map (juxt :write-path :flow-id) acc))
              via-flows    (vec (distinct
                                  (keep path->writer (or read-paths []))))]
          (conj acc
                (assoc row
                       :read-paths   (vec read-paths)
                       :via?         (boolean (seq via-flows))
                       :via-flow-ids via-flows))))
      []
      (or rows []))))

(defn- flow-row
  "One row inside the §8 FLOWS section. Shape per design:

      ▸ :flow-id              wrote [:write :path]   <result>
                              read  [:in1] [:in2]
      ↳ :chained-flow         wrote [:other :path]   <result>
                              read  [:in :read :the-upstream :wrote]"
  [{:keys [flow-id write-path result read-paths via? via-flow-ids trace-id]}]
  (let [suffix (interceptor-testid-suffix flow-id)]
    [:div {:data-testid (str "rf-causa-event-detail-flow-row-" suffix)
           :data-via    (str via?)
           :style {:padding     "4px 0"
                   :padding-left (if via? "20px" "0")}}
     ;; Header line: glyph + flow-id + via attribution
     [:div {:style {:display     "flex"
                    :align-items "center"
                    :gap         "8px"
                    :flex-wrap   "wrap"}}
      [:span {:data-testid (str "rf-causa-event-detail-flow-row-glyph-" suffix)
              :style {:color       (if via?
                                     (:text-secondary tokens)
                                     (:text-tertiary tokens))
                      :font-weight 600
                      :font-size   "12px"}}
       (if via? "↳" "▸")]
      [:span {:data-testid (str "rf-causa-event-detail-flow-row-id-" suffix)
              :style {:color       (:accent-violet tokens)
                      :font-weight 600
                      :min-width   "160px"}}
       (pr-str flow-id)]
      (when via?
        [:span {:data-testid (str "rf-causa-event-detail-flow-row-via-" suffix)
                :style {:color       (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-style  "italic"
                        :font-size   "11px"}}
         (str "via "
              (string/join
                ", "
                (map pr-str via-flow-ids)))])]
     ;; wrote line
     [:div {:data-testid (str "rf-causa-event-detail-flow-row-wrote-" suffix)
            :style {:display      "flex"
                    :align-items  "flex-start"
                    :padding      "2px 0 2px 24px"}}
      [:span {:style {:color       (:text-tertiary tokens)
                      :margin-right "10px"
                      :min-width   "48px"
                      :font-family sans-stack
                      :font-size   "11px"}}
       "wrote"]
      [:span {:data-testid (str "rf-causa-event-detail-flow-row-write-path-" suffix)
              :style {:color       (:cyan tokens)
                      :margin-right "12px"}}
       (pr-str write-path)]
      [:span {:style {:color    (:text-primary tokens)
                      :min-width 0
                      :flex     1}}
       (inspector/inspect result
                          (str "event-detail/flow/"
                               (or trace-id "x")
                               "/result"))]]
     ;; read line — placeholder when registry lookup failed (flow cleared)
     [:div {:data-testid (str "rf-causa-event-detail-flow-row-read-" suffix)
            :style {:display     "flex"
                    :align-items "flex-start"
                    :padding     "2px 0 2px 24px"}}
      [:span {:style {:color       (:text-tertiary tokens)
                      :margin-right "10px"
                      :min-width   "48px"
                      :font-family sans-stack
                      :font-size   "11px"}}
       "read"]
      (if (seq read-paths)
        (into [:span {:style {:color (:text-secondary tokens)
                              :flex 1
                              :word-break "break-word"}}]
              (for [p read-paths]
                [:span {:style {:color (:cyan tokens)
                                :margin-right "8px"}}
                 (pr-str p)]))
        [:span {:data-testid (str "rf-causa-event-detail-flow-row-read-absent-" suffix)
                :style {:color       (:text-tertiary tokens)
                        :font-style  "italic"
                        :font-size   "11px"}}
         "input paths unavailable (flow may have been cleared)"])]]))

(defn- flows-section
  "§8 FLOWS — peer section between §7 EFFECTS HANDLERS RAN and any
  future RETURNED-VALUE / handler-return section. Renders one row per
  `:rf.flow/computed` trace in cascade firing order. Chained flows
  (a downstream flow that reads from an upstream flow's write path)
  carry the `↳ via :upstream` indicator.

  Silent-by-default per Mike's policy (rf2-yn86j wave + bead): when
  the cascade carries NO flow firings the section is ABSENT entirely.
  A no-op cascade should not produce a 'FLOWS — none fired' row."
  [cascade]
  (let [rows (flows-with-chain-marks (flows-fired cascade))]
    (when (seq rows)
      (section/section-row
        {:label "FLOWS"
         :count* (count rows)
         :testid "rf-causa-event-detail-section-flows"}
        (into [:div]
              (for [{:keys [trace-id flow-id] :as row} rows]
                (with-meta
                  (flow-row row)
                  ;; Trace-id is the stable per-firing key. Fall back to
                  ;; flow-id when the trace lacks an :id (older fixtures).
                  {:key (or trace-id flow-id)})))))))

;; ---- handler-threw footnote --------------------------------------------

(defn- handler-threw-footer
  "Per §7.5 — the ONE inline cross-reference to Issues tab when the
  handler threw. §5/§6 are suppressed (the handler never returned);
  the footer offers the explicit jump."
  []
  [:div {:data-testid "rf-causa-event-detail-handler-threw-footer"
         :style {:padding "10px 16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "12px"
                 :font-style "italic"}}
   "Handler threw — see Issues tab "
   [:span {:style {:color (:red tokens) :font-weight 600}} "⚠"]
   " for the exception detail."])

;; ---- the lens (replaces v1 cascade-detail) -----------------------------

(defn- event-lens
  "Render the 8-section Event lens for a cascade. Replaces the v1
  `cascade-detail` six-domino renderer per rf2-zh2qc; COEFFECTS slot
  added in rf2-jhhqt + section order corrected to honour Mike's Q1
  answer (DISPATCH SITE first, then EVENT); FLOWS slot added in
  rf2-lo37i — peer section after EFFECTS HANDLERS RAN that surfaces
  the framework's automatic-after-fx flow firings (Spec 013 cascade
  step 4 — previously invisible to Causa).

  Order (top → bottom):
    §1 DISPATCH SITE         where the dispatch happened (source coord)
    §2 EVENT                 the dispatched event vector
    §3 COEFFECTS             user-injected coeffects (silent when zero)
    §4 INTERCEPTORS          non-standard chain (silent when zero)
    §5 HANDLER               where the handler is defined (source coord)
    §6 EFFECTS RETURNED      {:db ... :fx [...]} the handler returned
    §7 EFFECTS HANDLERS RAN  per-fx-handler rows + managed-fx inline
    §8 FLOWS                 auto-fired flow recomputes in cascade order"
  [{:keys [dispatch-id frame event] :as cascade}]
  (let [event-id   (when (vector? event) (first event))
        meta       (when event-id (rf/handler-meta :event event-id))
        user-icpts (user-interceptors (:interceptors meta))
        threw?     (has-handler-exception? cascade)]
    [:div {:data-testid "rf-causa-event-detail-cascade"
           :data-dispatch-id (str dispatch-id)
           :data-frame (str frame)}
     (cascade-outcome-line cascade)
     (dispatch-site-section cascade)
     (event-section event)
     (coeffects-section cascade)
     (interceptors-section user-icpts)
     (handler-section event-id meta)
     (when-not threw?
       (effects-returned-section cascade))
     (when-not threw?
       (effects-handlers-ran-section cascade))
     (when-not threw?
       (flows-section cascade))
     (when threw?
       (handler-threw-footer))]))

;; ---- cascade-list (empty-state, survives from v1) -----------------------

(defn- format-edn
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- cascade-list-row
  "One row in the cascade-list view. Clicking the row fires the
  `:rf.causa/select-dispatch-id` event-db so the panel switches into
  cascade-detail mode."
  [{:keys [dispatch-id frame event] :as _cascade}]
  [:li {:key       [frame dispatch-id]
        :data-testid (str "rf-causa-cascade-row-" dispatch-id)
        :on-click   #(rf/dispatch
                       [:rf.causa/select-dispatch-id dispatch-id frame]
                       {:frame :rf/causa})
        :style      {:padding      "8px 12px"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :cursor       "pointer"
                     :font-family  mono-stack
                     :font-size    "13px"
                     :color        (:text-primary tokens)}}
   [:span {:style {:color (:accent-violet tokens) :margin-right "8px"}}
    (str "#" dispatch-id)]
   (when frame
     [:span {:style {:color (:text-tertiary tokens) :margin-right "8px"}}
      (str frame)])
   (format-edn (or event :ungrouped))])

(defn- cascade-list
  "Empty-state list of cascades for the user to click into. Silent-by-
  default (rf2-b9f6z) — no prose; the panel reflects the L2 event-list
  focus like every other panel."
  [cascades]
  [:div {:data-testid "rf-causa-event-detail-empty"
         :style       {:padding "16px"}}
   (when (seq cascades)
     (overflow/capped-list
       cascades
       {:panel-id "event-detail"
        :ul-attrs {:data-testid "rf-causa-cascade-list"
                   :style {:list-style "none"
                           :margin     0
                           :padding    0
                           :border     (str "1px solid " (:border-subtle tokens))
                           :border-radius "4px"
                           :background (:bg-3 tokens)}}
        :row-fn   cascade-list-row}))])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Event lens panel's root view. Subscribes to
  `:rf.causa/event-detail` and renders either the 6-section
  `event-lens` (when a cascade is focused) or the cascade-list empty
  state (when not)."
  []
  (let [{:keys [selected-dispatch-id selected-dispatch-frame selected-cascade cascades]}
        @(rf/subscribe [:rf.causa/event-detail])]
    [:section {:data-testid "rf-causa-event-detail"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:div {:style {:flex 1 :overflow "auto"}}
      (cond
        (and selected-dispatch-id selected-cascade)
        (event-lens selected-cascade)

        selected-dispatch-id
        [:div {:data-testid "rf-causa-event-detail-orphaned"
               :style       {:padding "16px"
                             :color   (:text-tertiary tokens)
                             :font-family sans-stack
                             :font-size "13px"}}
         "Selected dispatch-id "
         [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
          (str selected-dispatch-id)]
         (when selected-dispatch-frame
           [:span " in frame "
            [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
             (str selected-dispatch-frame)]])
         " is no longer in the trace buffer. Pick another cascade from the event list."]

        :else
        (cascade-list cascades))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Event Detail panel's Causa-side
  registrations. Owns the selection slot the panel + its cross-panel
  `:rf.causa/cascades` consumers read off:

    - `:rf.causa/selected-dispatch-id` sub (cascade selection)
    - `:rf.causa/event-detail` composite sub
    - `:rf.causa/select-dispatch-id` event
    - `:rf.causa/clear-selected-dispatch-id` event

  The cross-panel `:rf.causa/cascades` projection itself lives in
  `registry.cljs` — it is shared with the Performance panel."
  []
  (rf/reg-sub :rf.causa/selected-dispatch-id
    (fn [db _query]
      (:dispatch-id (selected-ref (or (get db :selected-dispatch)
                                      (get db :selected-dispatch-id))))))

  (rf/reg-sub :rf.causa/selected-dispatch-frame
    (fn [db _query]
      (:frame (selected-ref (or (get db :selected-dispatch)
                                (get db :selected-dispatch-id))))))

  ;; Event-detail composite — produces everything the panel needs in
  ;; one read so the view stays a thin renderer. Reads the EFFECTIVE
  ;; focused dispatch-id off the spine sub (`:rf.causa/focus`); spine
  ;; auto-advances to head in `:live` mode, so the panel never pins
  ;; to a stale id that `focus-cascade-reducer` last wrote.
  ;;
  ;; Per rf2-639lc Bug 1: if the spine landed on `:ungrouped` (the
  ;; projection's catch-all bucket for registry-time emits / frame
  ;; lifecycle outside a drain), fall back to the most recent ROUTED
  ;; cascade so the L4 default-focus never lands on the projection's
  ;; internal bucket.
  (rf/reg-sub :rf.causa/event-detail
    :<- [:rf.causa/cascades]
    :<- [:rf.causa/focus]
    (fn [[cascades focus] _query]
      (let [focus-id       (:dispatch-id focus)
            focus-frame    (:frame focus)
            ungrouped?     (= :ungrouped focus-id)
            head           (when (or (nil? focus-id) ungrouped?)
                             (default-head-cascade cascades))
            selected-id    (cond
                             ungrouped?      (:dispatch-id head)
                             (nil? focus-id) (:dispatch-id head)
                             :else           focus-id)
            selected-frame (cond
                             ungrouped?      (:frame head)
                             (nil? focus-id) (:frame head)
                             :else           focus-frame)
            selection      (when selected-id
                             {:dispatch-id selected-id
                              :frame       selected-frame})
            by-id          (when selection
                             (some #(when (cascade-matches-selection? % selection) %)
                                   cascades))]
        {:cascades                cascades
         :selected-dispatch-id    selected-id
         :selected-dispatch-frame selected-frame
         :selected-cascade        by-id})))

  ;; Spine shim (rf2-adve5) — `:rf.causa/select-dispatch-id` is the
  ;; legacy entry point used by machine-inspector / issues-ribbon /
  ;; performance / routes / schema-violation-timeline / trace /
  ;; mcp-server. It writes through the spine via the same reducer the
  ;; spec-018 `:rf.causa/focus-cascade` event uses.
  (rf/reg-event-db :rf.causa/select-dispatch-id
    (fn [db [_ dispatch-id frame-id]]
      (let [history  (get db :epoch-history [])
            epoch-id (spine/epoch-id-for-cascade history dispatch-id)
            head-id  (spine/focusable-head-id (spine/db->cascades db))]
        (spine/focus-cascade-reducer db dispatch-id frame-id epoch-id head-id))))

  ;; Programmatic clear of the focused cascade. Resets the spine focus
  ;; back to LIVE (head-tracking) per the rf2-s0s5x Phase A semantics.
  (rf/reg-event-db :rf.causa/clear-selected-dispatch-id
    (fn [db _event]
      (-> db
          (dissoc :selected-dispatch :selected-dispatch-id :selected-epoch-id)
          (update :focus (fnil assoc {})
                  :dispatch-id nil
                  :epoch-id    nil
                  :mode        :live
                  :previewing? false)))))
