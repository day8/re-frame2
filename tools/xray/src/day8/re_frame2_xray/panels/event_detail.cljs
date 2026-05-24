(ns day8.re-frame2-xray.panels.event-detail
  "Event panel — the L4 default-tab panel. Answers \"what did this event
  DO?\" by rendering the focused epoch's end-to-end mutation pipeline.

  ## Numbered vertical-flow pipeline (spec/021 §2.2 · Figma design)

  Reconciled to the Figma design under rf2-ad7zx.5
  (`tools/xray/design-reference/xray_devtools_reference.cljs`, the
  `event-panel` component +
  `tools/xray/spec/021-Dynamic-Panel-Designs.md` §2.2). The handling
  perspective is a top-to-bottom ONE-WAY pipeline drawn as a thin left
  RAIL with a NUMBERED STEP CIRCLE at each section. Sections, in order:

      1. DISPATCH             event vector + `FROM: <source>` (click-to-source)
      2. COEFFECTS  (opt)     user-injected coeffects + the value each added
      3. EVENT HANDLER        reg-event-* flavour + syntax-highlighted source
      4. DB CHANGES           the app-db diff (+ SSR hydration addendum)
      5. AFTER INTERCEPTORS (opt) non-standard after-interceptors
      6. FLOWS      (opt)     flows that recomputed + the db path written
      7. FX                   the fx handlers that ran

  Steps are numbered DYNAMICALLY 1..N — an absent OPTIONAL section
  (COEFFECTS / AFTER INTERCEPTORS / FLOWS) consumes no number; absence is
  conveyed by OMISSION, not an empty-state line. There is **no outcome
  badge** and **no `db committed` footer** (the prior superseded shape):
  a throwing handler simply omits DB CHANGES and the later steps —
  absence conveys the throw.

  Per `EventPanel` there is **no top header/ribbon** (rf2-ad7zx.17):
  the panel leads directly with step 1 (DISPATCH). The vertical pipeline
  RAIL runs through the CENTRE of the numbered step circles, starting at
  circle 1 (not the panel top).

  All non-handling dominos (subs, renders, errors) live in their own
  tabs (Views / Issues).

  ## Substrate-driven (rf2-twt7m + rf2-jhhqt)

  Substrate changes supply the data this panel reads:

    Change 1 — `:rf.event/dispatched` traces carry `:rf.trace/call-site`
               on success-path emits. DISPATCH reads it.
    Change 3 — Framework-auto-wrapped interceptors carry
               `:rf/default? true` so AFTER INTERCEPTORS can filter them
               out without an allowlist.
    Change 4 — (rf2-jhhqt) `:rf.fx/do-fx` traces carry
               `:rf.event/coeffects` on their `:tags` — the USER-INJECTED
               subset of the handler's coeffects (framework defaults
               filtered at the substrate). COEFFECTS reads it.

  Handler-site coord + source read via `(rf/handler-meta :event id)` —
  no trace involvement; reads the registry at render time.

  ## Pure hiccup, substrate-agnostic

  The panel emits hiccup; the substrate adapter installed via `rf/init!`
  handles rendering. Each step body is a body-returning helper composed
  into a numbered `step-section` by `event-lens`.

  ## What survives

    - install! (selection slot + composite sub + select/clear events)
    - cascade-list (no-event-selected empty state)
    - tier-dot (reused in per-fx duration)"
  (:require [clojure.string :as string]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.views.edn-widget.widget :as edn]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]
            [day8.re-frame2-xray.panels.overflow-indicator :as overflow]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as managed-fx-h]
            [day8.re-frame2-xray.panels.managed-fx-template :as managed-fx]
            [day8.re-frame2-xray.spine :as spine]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-xray.theme.perf-tier :as perf-tier]))

;; ---- selection plumbing (survives from v1) -----------------------------

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
  "True iff `cascade` is the one named by the `selection`
  `{:dispatch-id <id> :frame <frame-id>}` map. A nil selection frame
  matches any frame (frame-agnostic selection)."
  [{:keys [dispatch-id frame]} {selected-id :dispatch-id selected-frame :frame}]
  (and (= selected-id dispatch-id)
       (or (nil? selected-frame)
           (= selected-frame frame))))

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
  "The `:rf.event/dispatched` trace event for the cascade. Carries
  `:rf.trace/call-site` per rf2-twt7m Change 1 + `:source` / `:rf.event/origin`
  hoisted by `trace.cljc/build-event`. The projection keeps it on
  the `:dispatched` slot per `group-cascades`' contract."
  [{:keys [dispatched]}]
  dispatched)

(defn- do-fx-trace
  "The `:rf.fx/do-fx` trace event for the cascade — supplies
  `:rf.event/fx` + `:rf.event/db-present?` per rf2-twt7m Change 2. Lives on the
  cascade's `:fx` slot per `group-cascades`."
  [{:keys [fx]}]
  fx)

(defn- has-handler-exception?
  "True iff the cascade's `:other` bucket carries the specific
  `:rf.error/handler-exception` trace — the handler threw and never
  returned. This is the narrow signal used to SUPPRESS §5/§6 (effects
  returned / fx handlers ran): when the handler itself blew up there
  were no effects to walk. The broader `has-error?` predicate (any
  `:op-type :error` / `:rf.error/*` op) drives the outcome glyph; this
  one is reserved for the section-suppression behaviour where only a
  thrown handler is meaningful. Pure predicate over `:other`."
  [{:keys [other]}]
  (boolean
    (some (fn [ev] (= :rf.error/handler-exception (:operation ev)))
          (or other []))))

(defn- error-trace?
  "True iff `ev` is an error trace — classified by the universal
  severity axis (`:op-type :error`, per Spec 009) with a namespace
  fallback for any `:rf.error/*` operation. Mirrors the namespace-based
  idiom in `shell/row-badges` and `issues-ribbon-helpers/op-type->severity`
  rather than enumerating individual ops the substrate may add over time."
  [{:keys [op-type operation] :as _ev}]
  (or (= :error op-type)
      (and (keyword? operation) (= "rf.error" (namespace operation)))))

(defn- warning-trace?
  "True iff `ev` is a warning trace — `:op-type :warning` (per Spec 009)
  with an `:rf.warning/*` namespace fallback. Severity-driven, not
  op-enumerated."
  [{:keys [op-type operation] :as _ev}]
  (or (= :warning op-type)
      (and (keyword? operation) (= "rf.warning" (namespace operation)))))

(defn- has-error?
  "True iff the cascade carries ANY error trace (severity `:error` /
  `:rf.error/*`) in its `:other` bucket — handler exceptions, drain-depth
  overflow, flow-eval failures, fx/cofx errors, machine action throws,
  etc. Drives the outcome glyph to ✗. Pure predicate over `:other`."
  [{:keys [other]}]
  (boolean (some error-trace? (or other []))))

(defn- has-warning?
  "True iff the cascade carries any non-fatal warning that should pivot
  the outcome glyph to ⚠ (amber). Classified by the universal severity
  axis (`:op-type :warning` / `:rf.warning/*`), so every warning the
  substrate emits flips the glyph — not a hand-maintained op enumeration.
  Pure predicate over the cascade's `:other` bucket."
  [{:keys [other]}]
  (boolean (some warning-trace? (or other []))))

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
                          (has-error? cascade)   [:error   "✗"]
                          (has-warning? cascade) [:warning "⚠"]
                          :else                  [:ok      "✓"])]
    {:event-id    event-id
     :glyph       glyph
     :outcome     outcome
     :duration-ms duration-ms
     :dispatch-id dispatch-id
     :ssr?        ssr?}))

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
      {:fx-id       (get-in ev [:tags :rf.fx/id])
       :fx-args     (get-in ev [:tags :rf.fx/args])
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
    :overridden  (:accent tokens)
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
  `:rf.event/dispatched`'s tags); we look in both places to be tolerant."
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
  "Pluck the dispatch-site coord off the cascade's `:rf.event/dispatched`
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
     (or (:origin ev) (get-in ev [:tags :rf.event/origin]))]))

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
      [:span {:data-testid (str "rf-xray-event-detail-tier-dot-" (name tier))
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

;; ---- step DISPATCH (spec/021 §2.2 step 1) ------------------------------

(defn- coord-chip
  "Reusable 'open in editor' click-to-source affordance. Renders the
  Figma `↗` external-link glyph; nothing when `coord` has no `:file`.
  Dispatches `:rf.xray/open-in-editor` with the structured coord; the
  trace-bus thereby records the click + the editor handler resolves the
  URI through the rf2-cm93v allowlist."
  [coord testid]
  (when (and (map? coord) (seq (:file coord)))
    [:button {:data-testid testid
              :title       "open in editor"
              :on-click    (fn [e]
                             (.stopPropagation e)
                             (rf/dispatch [:rf.xray/open-in-editor
                                           {:source-coord coord}]
                                          {:frame :rf/xray}))
              :style       {:background  "transparent"
                            :color       (:accent tokens)
                            :border      "none"
                            :padding     "0 4px"
                            :margin-left "6px"
                            :cursor      "pointer"
                            :font-family mono-stack
                            :font-size   "12px"
                            :line-height 1}}
     "↗"]))

(defn- dispatch-body
  "Step DISPATCH body — the dispatched event vector + the `FROM:
  <source>` dispatch-origin (a click-to-source link). Per spec/021 §2.2
  step 1 these are the SAME step (the prior split DISPATCH SITE / EVENT
  sub-sections are merged; rf2-ad7zx.5).

  The dispatched event vector renders in a BOXED block (`bg-muted p-3
  rounded` in the mock; raised `:bg-3` fill + subtle border + 3px radius
  here) per `EventPanel` (rf2-l3h1m) — the same surface family as the
  §3 EVENT HANDLER source code-block.

  The FROM row matches `EventPanel`'s dispatch presentation
  (rf2-ad7zx.17): `FROM:` then the dispatch SOURCE rendered as a single
  accent-coloured click-to-source link (`view ↗`) — the `↗`
  external-link glyph trails the source text and opens the call-site in
  the editor. The prior `· origin <origin>` clutter and the standalone
  `file:line` coord span are dropped (the mock surfaces neither); when no
  call-site coord was captured the source renders as plain muted text
  with no link affordance. Returns body hiccup."
  [cascade event-vec]
  (let [coord      (dispatch-call-site cascade)
        [source _] (dispatch-source+origin cascade)
        label      (or (some-> source name) "unknown")
        linked?    (and (map? coord) (seq (:file coord)))]
    [:div
     ;; The dispatched event vector — rendered in a boxed `bg-muted p-3
     ;; rounded` block per `EventPanel`'s DISPATCH presentation
     ;; (rf2-l3h1m). The surface treatment (raised `:bg-3` fill, subtle
     ;; border, 3px radius) mirrors the §3 EVENT HANDLER source code-block
     ;; (`edn/code-block`) so the two pipeline steps read as a consistent
     ;; family of boxed payloads. `min-width:0` keeps the inner inspector
     ;; free to shrink so a wide vector scrolls within the box rather than
     ;; clipping at narrow panel widths.
     [:div {:data-testid "rf-xray-event-detail-event-vector"
            :style {:background    (:bg-3 tokens)
                    :border        (str "1px solid " (:border-subtle tokens))
                    :border-radius "3px"
                    :padding       "8px 10px"
                    :margin-bottom "4px"
                    :min-width     "0"
                    :overflow-x    "auto"
                    :font-weight   600}}
      (edn/inspect event-vec "event-detail/event")]
     ;; FROM: <source> ↗ — the dispatch-origin as a single
     ;; click-to-source link (EventPanel shape; rf2-ad7zx.17).
     [:div {:data-testid "rf-xray-event-detail-dispatch-caption"
            :style {:display "flex"
                    :align-items "center"
                    :gap "6px"
                    :color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "11px"}}
      "FROM:"
      (if linked?
        ;; Accent-coloured source link + trailing ↗ (the coord-chip).
        [:span {:data-testid "rf-xray-event-detail-dispatch-from"
                :style {:display "inline-flex"
                        :align-items "center"
                        :color (:accent tokens)
                        :font-family mono-stack}}
         label
         (coord-chip coord "rf-xray-event-detail-dispatch-open-chip")]
        ;; No call-site coord — plain muted source text, no link.
        [:span {:data-testid "rf-xray-event-detail-dispatch-from"
                :style {:color (:text-secondary tokens)
                        :font-family mono-stack}}
         label])]]))

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
  user-injected subset on `:tags :rf.event/coeffects`). Pure fn; JVM-testable.

  Returns the map (preserving id → value pairs) or nil when the
  cascade carries no coeffects stamp / the stamp is empty. The
  substrate filters the framework defaults (`:db` `:event` `:frame`
  `:source` `:trace-id`) at emit-time so this fn is a thin reader —
  it does NOT re-filter."
  [cascade]
  (let [m (some-> (do-fx-trace cascade) :tags :rf.event/coeffects)]
    (when (and (map? m) (seq m))
      m)))

(defn- coeffect-row
  [id value]
  (let [suffix (interceptor-testid-suffix id)]
    [:div {:data-testid (str "rf-xray-event-detail-coeffect-row-" suffix)
           :style {:display "flex"
                   :align-items "flex-start"
                   :padding "2px 0"}}
     [:span {:style {:color (:accent tokens)
                     :min-width "180px"
                     :margin-right "12px"}}
      (pr-str id)]
     [:span {:style {:color (:text-primary tokens)
                     :min-width 0
                     :flex 1
                     :word-break "break-word"}}
      (edn/inspect value (str "event-detail/coeffect/" suffix))]]))

(defn- coeffects-body
  "Step COEFFECTS body — one row per user-injected coeffect. Returns
  nil when zero coeffects were injected; the OPTIONAL step is then
  omitted entirely (spec/021 §2.2 — absence by omission, no empty-state
  line). Returns body hiccup otherwise."
  [cascade]
  (let [user-cofx (user-coeffects cascade)]
    (when (seq user-cofx)
      (into [:div]
            ;; Per rf2-ppzid — `with-meta` on fn return preserves :key.
            (for [[id v] user-cofx]
              (with-meta (coeffect-row id v)
                         {:key (pr-str id)}))))))

;; ---- step AFTER INTERCEPTORS (spec/021 §2.2 step 5) -------------------

(defn- interceptor-row
  [{:keys [id file line] :as _interceptor}]
  (let [coord   (when (string? file) {:file file :line line})
        display (format-coord-display coord)
        suffix  (interceptor-testid-suffix id)]
    [:div {:data-testid (str "rf-xray-event-detail-interceptor-row-" suffix)
           :style {:display "flex"
                   :align-items "center"
                   :padding "2px 0"}}
     [:span {:style {:color (:accent tokens)
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
                 (str "rf-xray-event-detail-interceptor-open-chip-" suffix))]))

(defn- after-interceptors-body
  "Step AFTER INTERCEPTORS body — one row per non-standard interceptor.
  Returns nil when the user has no non-standard interceptors; the
  OPTIONAL step is then omitted entirely (spec/021 §2.2 — absence by
  omission). Pre-computed via `user-interceptors` (test-level helper)
  before invoking this fn. Returns body hiccup otherwise."
  [user-icpts]
  (when (seq user-icpts)
    (into [:div]
          ;; Per rf2-ppzid: `^{:key ...}` reader-meta on a fn CALL FORM
          ;; (a list) is lost — `with-meta` on the fn return preserves
          ;; the key correctly.
          (for [icpt user-icpts]
            (with-meta (interceptor-row icpt)
                       {:key (pr-str (:id icpt))})))))

;; ---- step EVENT HANDLER (spec/021 §2.2 step 3) ------------------------

(defn handler-source-string
  "Read the registered handler's source-form string from `(rf/handler-meta
  :event event-id)`. Per spec/021 §11.2 the substrate stamps this via a
  DEBUG-gated `goog.DEBUG`-elided macro under the `:rf.handler/source`
  meta key (rf2-xgfuy in flight). Returns the source string when
  present, otherwise nil. Pure fn; JVM-portable."
  [meta]
  (let [s (:rf.handler/source meta)]
    (when (and (string? s) (seq s))
      s)))

(defn- handler-source-line
  "Renders the `↳ source` block under the handler flavour+coord row.
  Per Mike-direction 2026-05-21 (rf2-n4ad0) the handler source now
  routes through the canonical EDN widget's `code-block` for
  syntax-highlighted rendering (keywords + builtins in the mode accent,
  strings green, numbers cyan). When the substrate meta hasn't
  yet been captured (e.g. before rf2-xgfuy lands, or in a production
  goog.DEBUG=false build) the row renders the `<source not yet
  captured>` placeholder per the task brief."
  [meta]
  (let [src (handler-source-string meta)]
    [:div {:data-testid "rf-xray-event-detail-handler-source"
           ;; `min-width:0` keeps the shrink-permission unbroken right down
           ;; to the syntax-highlighted `<pre>`, so it scrolls within the
           ;; panel rather than clipping at narrow widths (rf2-l7ha9).
           :style {:margin-top "4px"
                   :min-width "0"
                   :padding-left "16px"}}
     [:div {:data-testid "rf-xray-event-detail-handler-source-arrow"
            :style {:color        (:text-tertiary tokens)
                    :font-family  sans-stack
                    :font-size    "11px"
                    :margin-bottom "4px"}}
      "↳ source"]
     (if src
       (edn/code-block
         {:source src
          :lang   :clojure
          :testid "rf-xray-event-detail-handler-source-body"})
       [:span {:data-testid "rf-xray-event-detail-handler-source-placeholder"
               :style {:font-style "italic"
                       :font-family mono-stack
                       :font-size   "11px"
                       :color       (:text-tertiary tokens)}}
        "<source not yet captured>"])]))

(defn- handler-body
  "Step EVENT HANDLER body — `reg-event-<kind>` flavour + source coord
  (a click-to-source link), then the syntax-highlighted handler source
  (`:rf.handler/source` meta, DEBUG-gated · rf2-xgfuy; placeholder when
  absent). Per spec/021 §2.2 step 3. Returns body hiccup."
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
    [:div
     [:div {:style {:display "flex" :align-items "center"}}
      [:span {:data-testid "rf-xray-event-detail-handler-flavour"
              :style {:color (:accent tokens)
                      :font-weight 600
                      :margin-right "8px"}}
       flavour]
      [:span {:style {:color (:text-tertiary tokens)
                      :margin-right "8px"}}
       "·"]
      (if display
        [:span {:data-testid "rf-xray-event-detail-handler-coord"
                :style {:color (:text-primary tokens)}}
         display]
        [:span {:data-testid "rf-xray-event-detail-handler-coord-absent"
                :style {:color (:text-tertiary tokens)
                        :font-style "italic"}}
         (if event-id
           (str "no registration found for " (pr-str event-id))
           "no handler registered")])
      (coord-chip coord "rf-xray-event-detail-handler-open-chip")]
     (handler-source-line meta)]))

;; ---- hydration outcome (SSR addendum) --------------------------------

(defn- hydration-issues-jump-button
  []
  [:div {:data-testid "rf-xray-event-detail-hydration-issues-jump"
         :style {:padding "4px 0 0 0"}}
   [:button {:on-click #(rf/dispatch [:rf.xray/select-tab :issues]
                                     {:frame :rf/xray})
             :style {:background  "transparent"
                     :color       (:accent tokens)
                     :border      (str "1px solid " (:border-default tokens))
                     :padding     "1px 8px"
                     :border-radius "3px"
                     :cursor      "pointer"
                     :font-family mono-stack
                     :font-size   "10px"}}
    "→ jump to Issues bisector"]])

(defn- hydration-body
  "SSR addendum — when the focused event is `:rf.ssr/hydrated` the DB
  CHANGES step appends the hydration-outcome row (and, when mismatches
  > 0, the jump-to-Issues bisector affordance). Returns nil for ordinary
  client cascades. The prior standalone EFFECTS RETURNED step (which
  duplicated `:db` against DB CHANGES and `:fx` against FX) is retired
  per spec/021 §2.2 (rf2-ad7zx.5); only the SSR-unique hydration row
  survives, folded into DB CHANGES where the post-handler db state is
  the natural home."
  [cascade]
  (let [hydration  (hydration-outcome-row cascade)
        mismatches (or (:mismatches hydration)
                       (:rf.ssr/mismatches hydration)
                       0)]
    (when hydration
      [:div {:data-testid "rf-xray-event-detail-hydration-outcome"
             :style {:margin-top "6px"
                     :padding-top "4px"
                     :border-top (str "1px solid " (:border-subtle tokens))}}
       [:div {:data-testid "rf-xray-event-detail-effects-returned-row-hydration"
              :style {:display "flex"
                      :align-items "flex-start"
                      :padding "2px 0"}}
        [:span {:style {:color (:accent tokens)
                        :min-width "180px"
                        :margin-right "12px"}}
         ":rf.ssr/hydration-outcome"]
        [:span {:style {:color (:text-primary tokens)
                        :min-width 0
                        :flex 1
                        :word-break "break-word"}}
         (edn/inspect hydration "event-detail/hydration")]]
       (when (pos? mismatches)
         (hydration-issues-jump-button))])))

;; ---- step FX (spec/021 §2.2 step 7) ----------------------------------

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
    [:div {:data-testid (str "rf-xray-event-detail-effects-ran-row-" suffix)
           :style {:padding "4px 0"}}
     [:div {:style {:display "flex"
                    :align-items "center"
                    :gap "10px"
                    :flex-wrap "wrap"}}
      [:span {:style {:color (:accent tokens)
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
       [:div {:data-testid (str "rf-xray-event-detail-effects-ran-managed-fx-"
                                 (or id "x"))
              :style {:margin "6px 0 4px 16px"}}
        (managed-fx/record-panel managed-fx-record)])]))

(defn- fx-body
  "Step FX body — one row per `:rf.fx/handled` fx-handler that ran.
  Inline-mounts the managed-fx record beneath its causing fx-handler row
  per §8.3 (colocation). Per spec/021 §2.2 step 7 FX is a REQUIRED step
  (always shown — `(none)` when no fx handlers ran, per the sparse-case
  mockup), unlike the optional COEFFECTS / AFTER INTERCEPTORS / FLOWS
  steps. Returns body hiccup."
  [cascade]
  (let [rows    (effects-handlers-ran cascade)
        records (managed-fx-h/cascade->managed-fx-records cascade)]
    (if (seq rows)
      (into [:div]
            ;; Per rf2-ppzid — `with-meta` on the fn return, not
            ;; `^{:key ...}` on the call form.
            (for [{:keys [id] :as row} rows]
              (with-meta
                (fx-handler-row row (managed-fx-record-for-row records id))
                {:key id})))
      [:div {:data-testid "rf-xray-event-detail-fx-none"
             :style {:color (:text-tertiary tokens)
                     :font-style "italic"
                     :font-family sans-stack
                     :font-size "11px"}}
       "(none)"])))

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
;;   `:input-values`, `:before`, `:result`, `:path`, `:frame` in
;;   `:tags`. Input PATHS are not in the trace — they live on the
;;   flow registry entry and are looked up via
;;   `(rf/handler-meta :flow id)` at render time.
;;
;; Per rf2-qlzh4: `:before` carries the value at `:path` immediately
;; before this flow's drain wrote — `nil` when the slot was unwritten
;; — so the wrote-line renders self-contained without walking the
;; surrounding epoch's `:db-before` snapshot. Rendering currently
;; surfaces only `:result`; the `:before` slot is projected through
;; for forthcoming "wrote [:path] <before> → <after>" diff rendering.

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
       :before       <any>          ;; the value at :path BEFORE this
                                    ;; drain wrote (rf2-qlzh4); nil when
                                    ;; the slot had never been written
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
         :before       (:before tags)
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
    [:div {:data-testid (str "rf-xray-event-detail-flow-row-" suffix)
           :data-via    (str via?)
           :style {:padding     "4px 0"
                   :padding-left (if via? "20px" "0")}}
     ;; Header line: glyph + flow-id + via attribution
     [:div {:style {:display     "flex"
                    :align-items "center"
                    :gap         "8px"
                    :flex-wrap   "wrap"}}
      [:span {:data-testid (str "rf-xray-event-detail-flow-row-glyph-" suffix)
              :style {:color       (if via?
                                     (:text-secondary tokens)
                                     (:text-tertiary tokens))
                      :font-weight 600
                      :font-size   "12px"}}
       (if via? "↳" "▸")]
      [:span {:data-testid (str "rf-xray-event-detail-flow-row-id-" suffix)
              :style {:color       (:accent tokens)
                      :font-weight 600
                      :min-width   "160px"}}
       (pr-str flow-id)]
      (when via?
        [:span {:data-testid (str "rf-xray-event-detail-flow-row-via-" suffix)
                :style {:color       (:text-tertiary tokens)
                        :font-family sans-stack
                        :font-style  "italic"
                        :font-size   "11px"}}
         (str "via "
              (string/join
                ", "
                (map pr-str via-flow-ids)))])]
     ;; wrote line
     [:div {:data-testid (str "rf-xray-event-detail-flow-row-wrote-" suffix)
            :style {:display      "flex"
                    :align-items  "flex-start"
                    :padding      "2px 0 2px 24px"}}
      [:span {:style {:color       (:text-tertiary tokens)
                      :margin-right "10px"
                      :min-width   "48px"
                      :font-family sans-stack
                      :font-size   "11px"}}
       "wrote"]
      [:span {:data-testid (str "rf-xray-event-detail-flow-row-write-path-" suffix)
              :style {:color       (:accent tokens)
                      :margin-right "12px"}}
       (pr-str write-path)]
      [:span {:style {:color    (:text-primary tokens)
                      :min-width 0
                      :flex     1}}
       (edn/inspect result
                          (str "event-detail/flow/"
                               (or trace-id "x")
                               "/result"))]]
     ;; read line — placeholder when registry lookup failed (flow cleared)
     [:div {:data-testid (str "rf-xray-event-detail-flow-row-read-" suffix)
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
                [:span {:style {:color (:accent tokens)
                                :margin-right "8px"}}
                 (pr-str p)]))
        [:span {:data-testid (str "rf-xray-event-detail-flow-row-read-absent-" suffix)
                :style {:color       (:text-tertiary tokens)
                        :font-style  "italic"
                        :font-size   "11px"}}
         "input paths unavailable (flow may have been cleared)"])]]))

(defn- flows-body
  "Step FLOWS body — one row per `:rf.flow/computed` trace in cascade
  firing order. Chained flows (a downstream flow that reads from an
  upstream flow's write path) carry the `↳ via :upstream` indicator.

  OPTIONAL step (spec/021 §2.2): returns nil when the cascade carries NO
  flow firings, so the step is omitted entirely (absence by omission).
  Returns body hiccup otherwise."
  [cascade]
  (let [rows (flows-with-chain-marks (flows-fired cascade))]
    (when (seq rows)
      (into [:div]
            (for [{:keys [trace-id flow-id] :as row} rows]
              (with-meta
                (flow-row row)
                ;; Trace-id is the stable per-firing key. Fall back to
                ;; flow-id when the trace lacks an :id (older fixtures).
                {:key (or trace-id flow-id)}))))))

;; ---- numbered vertical-flow pipeline chrome (spec/021 §2.2 · rf2-ad7zx) -
;;
;; Per the Figma design (`tools/xray/design-reference/xray_devtools_reference.cljs`,
;; the `event-panel` component) + spec/021 §2.2 the Event panel expresses its
;; top-to-bottom one-way pipeline as a thin vertical RAIL running
;; through the CENTRE of a column of NUMBERED STEP CIRCLEs (1, 2, …),
;; one per section (rf2-ad7zx.17). The rail is muted (`:border-subtle`)
;; and starts at circle 1; the circles are filled muted
;; (`:text-tertiary` background) with white numerals and ride on it.
;;
;; Steps are numbered DYNAMICALLY — an absent optional section consumes
;; no number, so the visible steps always read 1..N contiguously. This
;; replaces the prior chevron + bare-label chrome (rf2-n4ad0): the
;; circles + the rail now carry the ordering rhythm, and optional
;; sections (COEFFECTS / AFTER INTERCEPTORS / FLOWS) are shown ONLY when
;; present — absence is conveyed by omission, NOT an empty-state line.

(defn- step-section
  "Render one numbered pipeline step: a step circle on the rail + an
  uppercase caption-weight label + the section `body`.

  `n` is the dynamically-assigned 1-based step number; `id` seeds the
  stable testid suffix (`rf-xray-event-detail-section-<id>-*`); `title`
  is the uppercase section label. `body` is opaque hiccup.

  The step circle sits in the left gutter (negative-margin onto the
  rail) per the Figma `EventPanel` `<section>` shape. testids:

    - section root   `rf-xray-event-detail-section-<id>`
    - step circle    `rf-xray-event-detail-step-circle-<id>`
    - section label  `rf-xray-event-detail-section-<id>-label`"
  [n id title body]
  [:section {:data-testid (str "rf-xray-event-detail-section-" id)
             :data-step-number (str n)
             ;; `min-width:0` lets this flex item (the pipeline is a flex
             ;; column) shrink below its content's intrinsic width, so a
             ;; wide handler-source `<pre>` scrolls within the panel
             ;; instead of expanding the column past the panel edge
             ;; (rf2-l7ha9). Flex items default to `min-width:auto`, which
             ;; refuses to shrink — that is what clips the EVENT HANDLER
             ;; source at narrow widths.
             :style {:position "relative"
                     :min-width "0"
                     :padding  "0 12px 0 12px"}}
   ;; Numbered step circle — filled muted with a white numeral, pulled
   ;; left onto the rail (spec/021 §2.2 — "filled muted with white
   ;; numerals").
   [:div {:data-testid (str "rf-xray-event-detail-step-circle-" id)
          :aria-hidden "true"
          :style {:position        "absolute"
                  :left            "-22px"
                  :top             "0"
                  :width           "20px"
                  :height          "20px"
                  :border-radius   "50%"
                  :background      (:text-tertiary tokens)
                  :color           "#FFFFFF"
                  :display         "flex"
                  :align-items     "center"
                  :justify-content "center"
                  :font-family     mono-stack
                  :font-size       "11px"
                  :font-weight     600
                  :z-index         1}}
    (str n)]
   [:div {:data-testid (str "rf-xray-event-detail-section-" id "-label")
          :style {:padding        "0 0 4px 0"
                  :font-family    sans-stack
                  :font-size      "11px"
                  :font-weight    600
                  :letter-spacing "0.6px"
                  :text-transform "uppercase"
                  :color          (:text-secondary tokens)}}
    title]
   [:div {:data-testid (str "rf-xray-event-detail-section-" id "-body")
          ;; `min-width:0` propagates the shrink-permission down to the
          ;; handler-source `<pre>` so its `overflow-x:auto` engages
          ;; within the panel (rf2-l7ha9).
          :style {:font-family mono-stack
                  :font-size   "12px"
                  :min-width   "0"
                  :color       (:text-primary tokens)}}
    body]])

;; ---- §2 step DB CHANGES — flat changed-paths diff list ----------------
;;
;; Per spec/021 §2.2 step 4 (line 229) the DB CHANGES section is the
;; app-db diff rendered as a FLAT list of changed paths ONLY:
;;
;;     ~ [path] old → new        (modified)
;;     + [path] value            (added)
;;     - [path]                  (removed)
;;
;; one line per changed path with the cascade diff glyph (`~` modified ·
;; `+` added · `-` removed). It is **slice-centric, not tree-centric**
;; (spec/004-App-DB-Diff.md §43/§69): it never renders untouched
;; top-level keys nor the whole tree. The prior implementation routed
;; the raw `:db-before` / `:db-after` snapshots through `edn/diff` — the
;; §10 tree renderer — which paints the WHOLE app-db with changes
;; highlighted (untouched keys dimmed in place). That violated 004 and
;; the §2.2 mockups; rf2-mn3gt switches the section to the flat
;; changed-paths projection.
;;
;; The changed-paths set is the SAME structural-sharing diff the App-db
;; tab consumes — the `:rf.xray/selected-epoch-diff` sub (cached per
;; `:epoch-id` via `app_db_diff_subs/install!`), which derives
;; `{:op :path :before :after}` triples from the focused epoch record's
;; `:db-before` / `:db-after`. The Event-panel section is the inline
;; (`~ [path] old → new`) projection of that diff, NOT a second
;; derivation. The §10 widget still owns leaf-value rendering — each
;; value renders through `edn/inspect-inline` (the one-line, sentinel-
;; aware §10 facade).

(defn- db-fx-evicted?
  "True when the focused epoch has aged out of the buffer — the
  `:rf.xray/selected-epoch-record` sub returns nil but a non-nil
  selection exists. Drives the evicted-epoch placeholder (§10.7)."
  [selected-record selected-id]
  (and (some? selected-id) (nil? selected-record)))

(def ^:private op->glyph
  "The cascade diff glyph per op (spec/021 §2.2 line 244-245 / §10.3).
  Mirrors `diff/render.cljs`'s gutter glyphs — `~` modified · `+`
  added · `-` removed."
  {:added    "+"
   :modified "~"
   :removed  "-"})

(def ^:private op->tone
  "Glyph + path colour per op, per spec/021 §10.3 cascade-gutter token
  mapping: `+` green (success) · `-` red (error) · `~` amber (warning)."
  {:added    :green
   :modified :yellow
   :removed  :red})

(defn- path-suffix
  "Stable testid suffix for a changed path — a `pr-str` of the path
  vector with characters that break a data-testid selector folded to
  `_`. e.g. `[:counter]` → `:counter`, `[:cart :items]` →
  `:cart_:items`."
  [path]
  (-> (string/join " " (map pr-str path))
      (string/replace #"\s+" "_")))

(defn- db-change-row
  "Render one changed-path row in the flat DB CHANGES list. `triple` is
  one `app-db-diff-helpers/diff-paths` triple
  (`{:op :path :before :after}`). Shape per spec/021 §2.2 mockup
  (lines 272-275):

      ~ [:counter]  1 → 2
      + [:last-updated]  #inst \"…\"
      - [:stale]

  The glyph + path colour follow the op tone (§10.3). The value
  rendering routes through the §10 widget (`edn/inspect-inline`) so the
  data-classification sentinels + the cljs-devtools leaf look are
  shared with every other Xray surface. A `:modified` row shows
  `before → after`; an `:added` row shows the added value; a `:removed`
  row shows the path alone (per line 229 — `- [path]`)."
  [{:keys [op path before after]}]
  (let [suffix     (path-suffix path)
        glyph      (get op->glyph op "?")
        tone       (get tokens (get op->tone op) (:text-secondary tokens))
        path-label (f/format-edn (vec path))]
    [:div {:data-testid (str "rf-xray-event-detail-db-change-row-" suffix)
           :data-op     (name op)
           :style {:display      "flex"
                   :align-items  "baseline"
                   :flex-wrap    "wrap"
                   :gap          "8px"
                   :padding      "2px 0"}}
     [:span {:data-testid (str "rf-xray-event-detail-db-change-glyph-" suffix)
             :style {:flex        "0 0 12px"
                     :color       tone
                     :font-family mono-stack
                     :font-weight 700
                     :text-align  "center"
                     :user-select "none"}}
      glyph]
     [:span {:data-testid (str "rf-xray-event-detail-db-change-path-" suffix)
             :style {:color       tone
                     :font-family mono-stack}}
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

       ;; :removed — path alone (spec/021 line 229: `- [path]`).
       nil)]))

(defn- db-changes-body
  "Step DB CHANGES body — the app-db diff for the focused epoch as a
  FLAT changed-paths list (spec/021 §2.2 step 4 · spec/004 §slice-
  centric). One `~`/`+`/`-` row per changed path; NO untouched
  top-level keys, NO whole-tree render.

  Per spec/021 §2.2 this step is the app-db diff ALONE — the prior
  combined `:db + :fx` shape (and the `db now committed for epoch #N`
  close-rule footer) is retired (rf2-ad7zx.5). The fx that ran now live
  in their own FX step; spec forbids a pipeline footer.

  Reads:
    `:rf.xray/selected-epoch-record` — `{:epoch-id … :db-before …
        :db-after …}` — used only for the eviction / empty-state
        decision (was the epoch evicted? did the epoch carry snapshots
        at all?).
    `:rf.xray/selected-epoch-diff` — the CACHED structural-sharing
        changed-paths triples (`app_db_diff_subs/install!`), the same
        derivation the App-db tab consumes (spec/004 §Changed-paths
        derivation, O(changed paths) not O(db size), cached per
        `:epoch-id`). The Event-panel section is the inline projection
        of THIS diff, not a re-derivation.

  Returns the body hiccup (mounted inside a `step-section`)."
  [{:keys [dispatch-id] :as _cascade}]
  (let [record    @(rf/subscribe [:rf.xray/selected-epoch-record])
        triples   @(rf/subscribe [:rf.xray/selected-epoch-diff])
        db-before (:db-before record)
        db-after  (:db-after record)
        had-snap? (or (some? db-before) (some? db-after))
        evicted?  (db-fx-evicted? record dispatch-id)]
    (cond
      evicted?
      [:div {:data-testid "rf-xray-event-detail-db-changes-evicted"
             :style {:padding "6px 0"
                     :color (:text-tertiary tokens)
                     :font-style "italic"
                     :font-family sans-stack
                     :font-size "12px"}}
       "Epoch evicted from buffer — increase :epoch-history to retain more."]

      ;; The focused epoch carried db snapshots AND the structural diff
      ;; found changed paths — render the flat list (changed paths only).
      (and (some? record) had-snap? (seq triples))
      [:div {:data-testid "rf-xray-event-detail-db-diff"
             :style {:padding     "4px 0"
                     :font-family mono-stack
                     :font-size   "12px"}}
       (into [:div]
             (for [{:keys [path] :as triple} triples]
               (with-meta (db-change-row triple)
                          {:key (pr-str path)})))]

      :else
      [:div {:data-testid "rf-xray-event-detail-db-changes-empty"
             :style {:color (:text-tertiary tokens)
                     :font-style "italic"
                     :font-family sans-stack
                     :font-size "12px"}}
       "no app-db change this epoch"])))

;; ---- the lens (numbered vertical-flow pipeline · spec/021 §2.2) --------

(defn- event-lens
  "Render the Event-panel pipeline for a cascade per
  spec/021-Dynamic-Panel-Designs.md §2.2 + the Figma design
  (`tools/xray/design-reference/xray_devtools_reference.cljs`, the
  `event-panel` component), reconciled
  under rf2-ad7zx.5.

  The handling perspective is a top-to-bottom ONE-WAY pipeline expressed
  as a thin left RAIL with a NUMBERED STEP CIRCLE at each section. Steps
  are numbered DYNAMICALLY 1..N — an absent OPTIONAL section consumes no
  number. There is NO outcome badge and NO `db committed` footer; absence
  of a step is conveyed by omission.

  Step order (numbered; optional steps shown only when present):

    1. DISPATCH            — the event vector + `FROM: <source>`
                             (click-to-source).
    2. COEFFECTS  (opt)    — user-injected coeffects + the value each
                             added.
    3. EVENT HANDLER       — flavour (`reg-event-*`, click-to-source) +
                             syntax-highlighted handler source.
    4. DB CHANGES          — the app-db diff (+ SSR hydration-outcome
                             addendum when the focused event hydrated).
    5. AFTER INTERCEPTORS (opt) — non-standard after-interceptors.
    6. FLOWS      (opt)    — flows that recomputed + the db path each
                             wrote.
    7. FX                  — the fx handlers that ran.

  ## Handler-threw branch

  When the cascade carries a handler exception the handler never
  returned, so the post-handler steps (DB CHANGES, AFTER INTERCEPTORS,
  FLOWS, FX) are simply omitted — absence conveys the throw (spec/021
  §2.2: no footer). Only DISPATCH / COEFFECTS? / EVENT HANDLER render.

  ## No top header (rf2-ad7zx.17)

  Per `EventPanel` the panel has NO top header/ribbon — it leads
  directly with step 1 (DISPATCH). The prior identity ribbon (⚡ icon +
  lifecycle status dot + event-id + `epoch #N` + SSR badge) is removed;
  the film-strip header is a future MVP item, not the current chrome.

  The stripe (mode `:accent`) sits on the outer container per §17.1.3."
  [{:keys [dispatch-id frame event] :as cascade}]
  (let [event-id   (when (vector? event) (first event))
        meta       (when event-id (rf/handler-meta :event event-id))
        user-icpts (user-interceptors (:interceptors meta))
        threw?     (has-handler-exception? cascade)
        ;; Build the DB CHANGES body — the app-db diff, with the SSR
        ;; hydration-outcome addendum appended when present.
        db-changes (when-not threw?
                     (let [hyd (hydration-body cascade)]
                       (if hyd
                         [:div (db-changes-body cascade) hyd]
                         (db-changes-body cascade))))
        ;; Candidate steps in spec order. REQUIRED steps carry a body
        ;; unconditionally; OPTIONAL steps carry nil → omitted (absence
        ;; by omission, spec/021 §2.2). The post-handler steps are nil
        ;; on the threw branch.
        candidates [["dispatch"          "DISPATCH"           (dispatch-body cascade event)]
                    ["coeffects"         "COEFFECTS"          (coeffects-body cascade)]
                    ["handler"           "EVENT HANDLER"      (handler-body event-id meta)]
                    ["db-changes"        "DB CHANGES"         db-changes]
                    ["after-interceptors" "AFTER INTERCEPTORS" (when-not threw?
                                                                 (after-interceptors-body user-icpts))]
                    ["flows"             "FLOWS"              (when-not threw? (flows-body cascade))]
                    ["fx"                "FX"                 (when-not threw? (fx-body cascade))]]
        ;; Drop omitted (nil-body) steps, then number what remains 1..N
        ;; — dynamic numbering so the visible steps read contiguously.
        present    (filterv (fn [[_ _ body]] (some? body)) candidates)]
    [:div {:data-testid "rf-xray-event-detail-cascade"
           :data-dispatch-id (str dispatch-id)
           :data-frame (str frame)
           ;; §17.1.3 / spec/022 — Event panel header stripe is the
           ;; single :accent (GitHub blue). No top ribbon (rf2-ad7zx.17):
           ;; the panel leads with the numbered pipeline.
           :style {:border-left (str "3px solid " (:accent tokens))}}

     ;; Numbered vertical-flow pipeline body. A thin vertical RAIL runs
     ;; through the CENTRE of the numbered step circles (rf2-ad7zx.17,
     ;; matching `EventPanel`): an absolutely-positioned line whose
     ;; `left` sits on the circles' centre-x and whose `top` starts at
     ;; circle 1's centre (NOT the panel top). Each step's circle rides
     ;; on it. Optional steps are already filtered out of `present`.
     (into [:div {:data-testid "rf-xray-event-detail-pipeline"
                  :style {:position      "relative"
                          :margin-left   "28px"
                          :padding-left  "24px"
                          :padding-top   "12px"
                          :padding-bottom "12px"
                          :display       "flex"
                          :flex-direction "column"
                          :gap           "20px"}}
            ;; The vertical pipeline RAIL — centred through the step
            ;; circles. Circle centre-x sits at 12px from the container's
            ;; inner-left (section content-left 24px + circle left -22px +
            ;; half the 20px circle = 12px); the 2px line centres there at
            ;; left 11px. It STARTS at circle 1's centre-y (padding-top
            ;; 12px + half the 20px circle = 22px) and runs to the bottom.
            [:div {:data-testid "rf-xray-event-detail-pipeline-rail"
                   :aria-hidden  "true"
                   :style {:position   "absolute"
                           :left       "11px"
                           :top        "22px"
                           :bottom     "0"
                           :width      "2px"
                           :background (:border-subtle tokens)}}]]
           (map-indexed
             (fn [i [id title body]]
               (with-meta
                 (step-section (inc i) id title body)
                 {:key id}))
             present))]))

;; ---- cascade-list (empty-state, survives from v1) -----------------------

(defn- format-edn
  [v]
  (try
    (pr-str v)
    (catch :default _
      (str v))))

(defn- cascade-list-row
  "One row in the cascade-list view. Clicking the row fires the
  `:rf.xray/select-dispatch-id` event-db so the panel switches into
  cascade-detail mode."
  [{:keys [dispatch-id frame event] :as _cascade}]
  [:li {:key       [frame dispatch-id]
        :data-testid (str "rf-xray-cascade-row-" dispatch-id)
        :on-click   #(rf/dispatch
                       [:rf.xray/select-dispatch-id dispatch-id frame]
                       {:frame :rf/xray})
        :style      {:padding      "8px 12px"
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :cursor       "pointer"
                     :font-family  mono-stack
                     :font-size    "13px"
                     :color        (:text-primary tokens)}}
   [:span {:style {:color (:accent tokens) :margin-right "8px"}}
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
  [:div {:data-testid "rf-xray-event-detail-empty"
         :style       {:padding "16px"}}
   (when (seq cascades)
     (overflow/capped-list
       cascades
       {:panel-id "event-detail"
        :ul-attrs {:data-testid "rf-xray-cascade-list"
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
  `:rf.xray/event-detail` and renders either the 6-section
  `event-lens` (when a cascade is focused) or the cascade-list empty
  state (when not)."
  []
  (let [{:keys [selected-dispatch-id selected-dispatch-frame selected-cascade cascades]}
        @(rf/subscribe [:rf.xray/event-detail])]
    [:section {:data-testid "rf-xray-event-detail"
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
        [:div {:data-testid "rf-xray-event-detail-orphaned"
               :style       {:padding "16px"
                             :color   (:text-tertiary tokens)
                             :font-family sans-stack
                             :font-size "13px"}}
         "Selected dispatch-id "
         [:code {:style {:color (:accent tokens) :font-family mono-stack}}
          (str selected-dispatch-id)]
         (when selected-dispatch-frame
           [:span " in frame "
            [:code {:style {:color (:accent tokens) :font-family mono-stack}}
             (str selected-dispatch-frame)]])
         " is no longer in the trace buffer. Pick another cascade from the event list."]

        :else
        (cascade-list cascades))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Event Detail panel's Xray-side
  registrations:

    - `:rf.xray/event-detail` composite sub (the panel's single read —
      derives the focused cascade off the spine `:rf.xray/focus`)
    - `:rf.xray/select-dispatch-id` event (writes focus through the spine)
    - `:rf.xray/clear-selected-dispatch-id` event (resets focus to LIVE)

  The cross-panel `:rf.xray/cascades` projection itself lives in
  `registry.cljs`."
  []
  ;; Event-detail composite — produces everything the panel needs in
  ;; one read so the view stays a thin renderer. Reads the EFFECTIVE
  ;; focused dispatch-id off the spine sub (`:rf.xray/focus`); spine
  ;; auto-advances to head in `:live` mode, so the panel never pins
  ;; to a stale id that `focus-cascade-reducer` last wrote.
  ;;
  ;; Per rf2-639lc Bug 1: if the spine landed on `:ungrouped` (the
  ;; projection's catch-all bucket for registry-time emits / frame
  ;; lifecycle outside a drain), fall back to the most recent ROUTED
  ;; cascade so the L4 default-focus never lands on the projection's
  ;; internal bucket.
  (rf/reg-sub :rf.xray/event-detail
    :<- [:rf.xray/cascades]
    :<- [:rf.xray/focus]
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

  ;; Spine shim (rf2-adve5) — `:rf.xray/select-dispatch-id` is the
  ;; legacy entry point used by machine-inspector / issues-ribbon /
  ;; performance / routes / schema-violation-timeline / trace /
  ;; mcp-server. It writes through the spine via the same reducer the
  ;; spec-018 `:rf.xray/focus-cascade` event uses.
  (rf/reg-event-db :rf.xray/select-dispatch-id
    (fn [db [_ dispatch-id frame-id]]
      (let [history  (get db :epoch-history [])
            epoch-id (spine/epoch-id-for-cascade history dispatch-id)
            head-id  (spine/focusable-head-id (spine/db->cascades db))]
        (spine/focus-cascade-reducer db dispatch-id frame-id epoch-id head-id))))

  ;; Programmatic clear of the focused cascade. Resets the spine focus
  ;; back to LIVE (head-tracking) per the rf2-s0s5x Phase A semantics.
  (rf/reg-event-db :rf.xray/clear-selected-dispatch-id
    (fn [db _event]
      (-> db
          (dissoc :selected-epoch-id)
          (update :focus (fnil assoc {})
                  :dispatch-id nil
                  :epoch-id    nil
                  :mode        :live
                  :previewing? false))))

  ;; rf2-2moh1 — register the Dynamic Event tab with the internal L4
  ;; tab registry. The runtime shell's L3 tab bar + L4 detail panel
  ;; pick the entry up by `tabs-for-mode :dynamic`.
  (panel-registry/reg-l4-tab!
    {:id    :event
     :label "Event"
     :mnem  "e"
     :modes #{:dynamic}
     :order 0
     :panel Panel}))
