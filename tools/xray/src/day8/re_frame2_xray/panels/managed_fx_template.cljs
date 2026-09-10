(ns day8.re-frame2-xray.panels.managed-fx-template
  "Managed-fx wire-boundary diff template (rf2-uyp86, parent rf2-5aw5v).

  The headline cross-cutting Xray feature from
  [`tools/xray/spec/019-Cross-Cutting-Insight.md`](../../../../tools/xray/spec/019-Cross-Cutting-Insight.md)
  §2.4 / F-C2. Renders one panel per managed-fx invocation inside the
  focused event-bundle's six-domino window, surfacing the eight-property
  contract from [`spec/Managed-Effects.md`](../../../../spec/Managed-Effects.md)
  as a uniform UI:

  ```
  ┌─ MANAGED FX [HTTP] · :user/load-profile · 250ms ──────────────┐
  │ STATUS: ✓ 200 OK · correlation: c-abc12 · phase: completed     │
  │                                                                │
  │ ▶ REQUEST                                                      │
  │ ▼ WIRE TIMING                                                  │
  │ ▶ RESPONSE                                                     │
  │ ▶ HANDLER DISPATCHED                                           │
  │ ▼ APP-DB SLICE TOUCHED                                         │
  └────────────────────────────────────────────────────────────────┘
  ```

  Every section header is a disclosure: clicking one toggles it. The
  first-paint state is `managed-fx-helpers/section-defaults` (three shut,
  two open, as drawn above); the operator's overrides live in app-db,
  keyed per record so sibling panels open independently.

  ## Five surfaces, one template

  The same renderer handles HTTP, WebSocket, machine-`:spawn`, SSR
  `:rf.server/*`, and `:rf.flow/*` records. Per-surface variation lives
  in the helpers ns (`panels/managed_fx_helpers`); this ns is purely a
  hiccup folder over the record shape.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Xray panel — pure hiccup, no Reagent /
  UIx references. Frame isolation is provided by the enclosing
  `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`. Every
  `subscribe` / `dispatch` here resolves to the `:rf/xray` frame.

  ## Cross-link

  The HANDLER DISPATCHED row uses `:rf.xray/focus-event` to pivot the
  spine to the child event-bundle — clicking '→ jump to handler' moves
  focus to wherever the response landed, so the user can follow the
  event-bundle chain hop-by-hop. Cross-link wiring lives in
  `panels/managed_fx_subs/install!` so the panel view stays thin."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panels.managed-fx-helpers :as h]
            [day8.re-frame2-xray.chart.timing-waterfall :as waterfall]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack sans-stack with-alpha]]
            [day8.re-frame2-xray.theme.section :as section]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

;; Section rhythm hoisted to `theme/section.cljc` per rf2-pie8q —
;; identical visual contract is shared with `panels/event_detail`.
;; This panel passes `:container-padding "8px 0"` so the section sits
;; flush against the record-panel's surrounding `padding "8px 12px"`
;; outer wrapper (added below in `record-panel`).

;; ---- panel header ------------------------------------------------------

(defn- status-pill
  "Coloured status pill anchoring the panel header. HTTP-status-band
  colour wins when an HTTP status is present (green for 2xx, etc.);
  otherwise we fall back to the generic status colour."
  [{:keys [status http-status]}]
  (let [band-tok    (when (number? http-status)
                      (h/format-http-status-band http-status))
        tok         (or band-tok (get h/status->colour-token status :text-tertiary))
        colour      (get tokens tok (:text-tertiary tokens))
        glyph       (get h/status->glyph status "—")
        label       (h/format-status-label status)]
    [:span {:data-testid (str "rf-xray-managed-fx-status-" (name status))
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "6px"
                          :padding       "1px 8px"
                          :border-radius "3px"
                          :background    "rgba(255,255,255,0.04)"
                          :color         colour
                          :font-family   mono-stack
                          :font-size     "11px"
                          :font-weight   700
                          :letter-spacing "0.4px"}}
     [:span glyph]
     (str label
          (when (number? http-status)
            (str " " http-status)))]))

(defn- correlation-pill
  "Render the correlation-id pill. Right-click fires
  `:rf.xray/filter-by-http-correlation` to drop a typed
  `:http-correlation` IN pill in the ribbon (rf2-piye4) — narrows
  the L2 event list to event-bundles that touched this exchange (issuing
  effect, retries, response, downstream handler)."
  [dispatch correlation-id]
  (when correlation-id
    [:span {:data-testid "rf-xray-managed-fx-correlation"
            :on-context-menu (fn [^js e]
                               (.preventDefault e)
                               (dispatch
                                 [:rf.xray/filter-by-http-correlation correlation-id]))
            :title "Right-click to filter the event list to this HTTP exchange"
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    (:bg-3 tokens)
                    :color         (:text-secondary tokens)
                    :font-family   mono-stack
                    :font-size     "10px"
                    :cursor        "context-menu"}}
     (str "correlation: " correlation-id)]))

(defn- phase-pill
  [phase]
  (when phase
    [:span {:data-testid (str "rf-xray-managed-fx-phase-" (name phase))
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    (:bg-3 tokens)
                    :color         (:text-secondary tokens)
                    :font-family   mono-stack
                    :font-size     "10px"}}
     (str "phase: " (name phase))]))

(defn- cancel-pill
  [cancel-cause]
  (when cancel-cause
    [:span {:data-testid "rf-xray-managed-fx-cancel"
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    "rgba(232, 121, 249, 0.12)"
                    :color         (:magenta tokens)
                    :font-family   mono-stack
                    :font-size     "10px"
                    :font-weight   600}}
     (str "cancel: " cancel-cause)]))

(defn- stub-pill
  [stubbed?]
  (when stubbed?
    [:span {:data-testid "rf-xray-managed-fx-stub"
            :title "Stubbed — this effect is redirected by an :fx-overrides entry instead of running for real."
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    "rgba(124, 92, 255, 0.15)"
                    :border        (str "1px solid " (:accent tokens))
                    :color         (:accent tokens)
                    :font-family   mono-stack
                    :font-size     "10px"
                    :font-weight   700
                    :letter-spacing "0.5px"}}
     "STUB"]))

(defn- panel-header
  [dispatch
   {:keys [surface fx-id duration-ms status http-status correlation-id phase cancel-cause stubbed?]
    :as   record}]
  [:header {:data-testid (str "rf-xray-managed-fx-header-" (name surface))
            :style       {:display       "flex"
                          :align-items   "center"
                          :flex-wrap     "wrap"
                          :gap           "6px"
                          :padding       "8px 12px"
                          :background    (:bg-3 tokens)
                          :border-bottom (str "1px solid " (:border-subtle tokens))
                          :font-family   sans-stack
                          :font-size     "12px"
                          :color         (:text-primary tokens)}}
   [:span {:data-testid (str "rf-xray-managed-fx-surface-" (name surface))
           :style {:display       "inline-flex"
                   :align-items   "center"
                   :gap           "4px"
                   :padding       "1px 8px"
                   :border-radius "3px"
                   :background    (with-alpha :info 12)
                   :color         (:info tokens)
                   :font-family   mono-stack
                   :font-size     "11px"
                   :font-weight   700
                   :letter-spacing "0.5px"}}
    [:span (get h/surface->glyph surface "•")]
    (str "MANAGED FX [" (get h/surface->label surface (str surface)) "]")]
   [:span {:data-testid "rf-xray-managed-fx-fx-id"
           :on-context-menu (fn [^js e]
                              (when fx-id
                                (.preventDefault e)
                                (dispatch
                                  [:rf.xray/filter-by-fx fx-id])))
           :title "Right-click to filter the event list to events triggering this fx"
           :style {:color (:accent tokens)
                   :font-family mono-stack
                   :font-size "12px"
                   :font-weight 600
                   :cursor "context-menu"}}
    (h/format-fx-id fx-id)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "11px"}}
    (h/format-duration-ms duration-ms)]
   [:span {:style {:flex 1}}]
   (status-pill record)
   (correlation-pill dispatch correlation-id)
   (phase-pill phase)
   (cancel-pill cancel-cause)
   (stub-pill stubbed?)])

;; ---- section bodies ----------------------------------------------------
;;
;; rf2-twil — `edn/inspect` IS CALLED, NEVER PUT IN HEAD POSITION.
;;
;; `views/edn-widget/inspect` is a plain `defn`. Under Reagent a plain
;; function in hiccup head position is a form-1 component and renders
;; happily, so `[edn/inspect v k]` worked; under Fresco it is a LOUD ERROR
;; by design (HD-016, `:rf.error/fresco-bad-head`), and the throw escapes
;; with no error boundary above it — React unmounts the entire Xray root,
;; which presents as a panel that never appears rather than as an error.
;; That is exactly the shape of the rf2-qhoj P1, one token wide.
;;
;; The four sites below were this tree's ONLY head-position users of
;; `inspect`; the other six caller files already call it. Calling it is
;; correct on BOTH substrates — the value rendered is the vector `inspect`
;; RETURNS — so this is the shape that survives the migration, and the
;; facade keeps its one-renderer-many-call-sites property because
;; `inspect`'s own shape is untouched.
;;
;; NOT THE SAME THING AS BEING FRESCO-READY. `inspect` returns
;; `[ei/edn-inspector …]`, a `reg-view` head, which Fresco's codec also
;; refuses — `views/edn_inspector.cljs`'s own ns docstring puts it plainly:
;; "Only `edn-inspector-view` is a head in a Fresco body." The day
;; `panels/ManagedFxList` becomes a boundary, these four calls become
;; `edn/inspect-view`, which exists for precisely that and takes the same
;; `node-key`. Until then the Reagent head is the correct one.

(defn- request-section
  [{:keys [req surface fx-id]}]
  (cond
    (and (nil? req) (= surface :flow))
    [:span {:style {:color (:text-tertiary tokens)}} "(flow input — see registration)"]

    (nil? req)
    [:span {:style {:color (:text-tertiary tokens)}} "(no request payload)"]

    :else
    (edn/inspect req (str "managed-fx/" (h/format-fx-id fx-id) "/req"))))

(defn- wire-section
  "Wire timing section. When the surface emits per-phase wire data we
  render the waterfall; when only synthesised round-trip is available
  we render the single bar; when nothing is available we render the
  documented `n/a` placeholder per the divergence allowance."
  [{:keys [wire]}]
  (cond
    (and wire (seq (:phases wire)))
    (waterfall/render wire)

    :else
    [:div {:data-testid "rf-xray-managed-fx-wire-na"
           :style {:color (:text-tertiary tokens) :font-style "italic"}}
     "n/a — this surface does not emit per-phase wire timing today."]))

(defn- response-section
  [{:keys [res surface fx-id failure]}]
  (cond
    failure
    [:div
     [:div {:style {:color (:red tokens)
                    :font-weight 600
                    :margin-bottom "4px"}}
      (str "✗ " (or (some-> failure :kind name) "FAILURE"))]
     (edn/inspect (or (:tags failure) failure)
                  (str "managed-fx/" (h/format-fx-id fx-id) "/failure"))]

    (and (nil? res) (= surface :flow))
    [:span {:style {:color (:text-tertiary tokens)}}
     "(in flight / no output yet)"]

    (nil? res)
    [:span {:style {:color (:text-tertiary tokens)}}
     "(no response payload yet)"]

    :else
    (edn/inspect res (str "managed-fx/" (h/format-fx-id fx-id) "/res"))))

(defn- handler-section
  "Renders the dispatched handler event vector + a click-to-focus
  affordance that pivots the spine to that child event-bundle. Anchors the
  F.3 'failed response handler' diagnostic."
  [dispatch {:keys [handler frame dispatch-id]}]
  (if (and (vector? handler) (seq handler))
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "12px"
                   :flex-wrap "wrap"}}
     [:div {:style {:flex 1 :min-width 0}}
      (edn/inspect handler "managed-fx/handler")]
     [:button {:data-testid "rf-xray-managed-fx-focus-handler"
               :on-click    #(dispatch [:rf.xray/focus-event dispatch-id frame])
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:accent tokens)
                             :font-family mono-stack
                             :font-size   "10px"
                             :padding     "2px 8px"
                             :border-radius "3px"
                             :cursor      "pointer"
                             :flex-shrink 0}}
      "→ focus event ↗"]]
    [:span {:style {:color (:text-tertiary tokens)}}
     "(no handler dispatched — this fx had no :on-success / :on-failure / :on-done)"]))

(defn- app-db-slice-section
  "Per spec/019 §2.4 F.4 — 'app-db wasn't updated' lights up when the
  status is OK but the slice paths-touched list is empty. The renderer
  highlights the empty-paths case with an amber hint so the bug class
  is immediately legible."
  [{:keys [paths-touched status]}]
  (cond
    (and (= status :ok) (empty? paths-touched))
    [:div
     [:div {:style {:color (:yellow tokens)
                    :font-weight 600
                    :font-family mono-stack
                    :font-size "11px"
                    :margin-bottom "4px"}}
      "⚠ STATUS :ok but no app-db paths changed — app-db wasn't updated"]
     [:div {:style {:color (:text-tertiary tokens) :font-style "italic"}}
      "The handler dispatched but did not write a slice. Likely a "
      "missing :db assoc or a guard that no-op'd."]]

    (empty? paths-touched)
    [:span {:style {:color (:text-tertiary tokens)}}
     "(no app-db changes in this event-bundle)"]

    :else
    [:ul {:style {:list-style "none"
                  :margin     0
                  :padding    0}}
     (for [[i path] (map-indexed vector paths-touched)]
       ^{:key i}
       [:li {:style {:padding "2px 0"
                     :font-family mono-stack
                     :font-size "12px"
                     :color (:text-primary tokens)}}
        [:span {:style {:color (:accent tokens)}}
         (pr-str path)]])]))

;; ---- disclosure ---------------------------------------------------------
;;
;; `theme/section/section-row` renders the `▶`/`▼` glyph from `:expanded?`
;; and renders its body under `(when expanded? …)`, but it attaches no
;; handler — "No interactivity. Click-to-toggle wiring is the caller's
;; responsibility", stated in its own docstring, and it is shared with
;; `panels/fresco` and `panels/module_view`, so the wiring is OURS to do.
;;
;; The caller's half is a wrapper carrying the click. Two details in it are
;; load-bearing:
;;
;;   - The BODY stops propagation. The wrapper has to enclose the whole
;;     section (the primitive renders its own header, so there is no inner
;;     header node to hang the handler on), which would otherwise make every
;;     click inside an opened payload collapse it again — including the
;;     '→ focus event ↗' button and the edn-inspector's own expand chevrons.
;;   - `:expanded?` is passed THROUGH to the primitive, so the glyph and the
;;     body agree by construction. The primitive stays the single source of
;;     truth for the visual state; this wrapper only decides what that state is.

(defn- disclosing-section
  "One `section-row` plus the click-to-toggle the primitive deliberately
  omits. `expanded?` is the resolved state (see
  `managed-fx-helpers/resolve-expanded?`); clicking dispatches the panel's
  toggle for `[rec-key section-id]`."
  [{:keys [dispatch rec-key section-id label testid expanded?]} body]
  [:div {:data-testid   (str testid "-toggle")
         :on-click      (fn [_]
                          (dispatch [:rf.xray/managed-fx-toggle-section
                                     rec-key section-id]))
         :aria-expanded (if expanded? "true" "false")
         :title         (if expanded?
                          "Click to collapse this section"
                          "Click to expand this section")
         :style         {:cursor "pointer"}}
   (section/section-row
     {:label             label
      :expanded?         expanded?
      :testid            testid
      :container-padding "8px 0"}
     ;; Clicks on the payload belong to the payload, not to the disclosure.
     [:div {:data-testid (str testid "-body-inner")
            :on-click    (fn [^js e] (.stopPropagation e))
            :style       {:cursor "auto"}}
      body])])

;; ---- one record's panel ------------------------------------------------

(defn record-panel
  "Render one managed-fx record as a hiccup panel. Pure function over
  the record; CLJS-only (consumes Reagent re-frame subs via
  `edn/inspect`).

  Section default-expanded state per the bead's contract:

    - STATUS + WIRE + APP-DB SLICE TOUCHED expanded by default
    - REQUEST + RESPONSE + HANDLER DISPATCHED collapsed by default
      (one click reveals the payload — keeps the panel scannable on
      first paint).

  Those defaults live in `managed-fx-helpers/section-defaults`, and every
  section is now a real disclosure: the panel owns the open/closed state
  and each header toggles it. Before rf2-s6m6 the five `:expanded?` values
  were literals, so the three collapsed sections drew a `▶` nothing could
  operate and their payloads — request, response (and the failure tags),
  and the dispatched handler vector — were unreachable in the UI.

  `expanded` is the per-section override map (the value of
  `managed-fx-helpers/expansion-slot`), read at the panel's reactive
  boundary and threaded down. `nil` — the shape the short arities pass —
  resolves every section to its `section-defaults` value, which is the
  first-paint state. This fn stays a PURE fn of `(dispatch, expanded,
  record)`: per Spec 006 §Plain-fn footgun an ambient `subscribe` in a
  plain fn raises `:rf.error/no-frame-context`, so the read cannot happen
  here and the sub is read by the `reg-view` that mounts this panel.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher captured by the
  `panels/ManagedFxList` `reg-view` body, threaded to the header /
  handler / disclosure affordances. Defaults to `rf/dispatch` so the test
  seam (and any pre-sweep caller) renders without a captured dispatcher."
  ([record] (record-panel rf/dispatch nil record))
  ([dispatch record] (record-panel dispatch nil record))
  ([dispatch expanded record]
  ;; rf2-hxfy — the React key lives in this ATTRIBUTE MAP rather than as
  ;; `^{:key …}` reader meta on the `(record-panel …)` call in
  ;; `records-list` below. Reader meta on a CALL form attaches to the
  ;; source LIST; the value the call returns carries none of it, so React
  ;; received no key at all (measured: `REACT .-key [nil nil]` across the
  ;; two-record fixture). The attribute map is the shape that survives the
  ;; Fresco migration too — Fresco's codec reads a literal `:key` from the
  ;; attr map of a native tag and reads Clojure metadata nowhere, while
  ;; Reagent reads meta THEN props — so one attribute satisfies both
  ;; substrates. The composed value is unchanged from the call site's:
  ;; the same three record fields, same order, same separator.
  (let [rec-key (h/record-key record)
        ;; One `section` per row: resolve this record's stored state for
        ;; the section (falling back to its default) and hand the whole
        ;; lot to the disclosure wrapper.
        section (fn [section-id label testid body]
                  (disclosing-section
                    {:dispatch   dispatch
                     :rec-key    rec-key
                     :section-id section-id
                     :label      label
                     :testid     testid
                     :expanded?  (h/resolve-expanded? expanded rec-key section-id)}
                    body))]
  [:section {:key         rec-key
             :data-testid (str "rf-xray-managed-fx-record-"
                               (name (:surface record))
                               "-" (or (:origin-event-id record) "x"))
             :data-fx-id  (h/format-fx-id (:fx-id record))
             :data-status (name (:status record))
             :style       {:margin "8px 12px"
                           :border (str "1px solid " (:border-subtle tokens))
                           :border-radius "4px"
                           :background    (:bg-2 tokens)}}
   (panel-header dispatch record)
   [:div {:style {:padding "8px 12px"}}
    (section :request  "REQUEST"
             "rf-xray-managed-fx-section-request"  (request-section record))
    (section :wire     "WIRE TIMING"
             "rf-xray-managed-fx-section-wire"     (wire-section record))
    (section :response "RESPONSE"
             "rf-xray-managed-fx-section-response" (response-section record))
    (section :handler  "HANDLER DISPATCHED"
             "rf-xray-managed-fx-section-handler"  (handler-section dispatch record))
    (section :app-db   "APP-DB SLICE TOUCHED"
             "rf-xray-managed-fx-section-app-db"   (app-db-slice-section record))]])))

;; ---- list panel --------------------------------------------------------

(defn records-list
  "Render a vector of managed-fx records as a stack of panels. Pure fn
  over the records vector; used by `event_detail.cljs` to mount the
  list under the six-domino event-bundle view, and by the tests to render
  a deterministic stack against canned records.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher threaded to each
  `record-panel`. Defaults to `rf/dispatch` for the test seam.

  `expanded` is the per-section override map threaded to each
  `record-panel`; `nil` renders every section at its default. Keyed per
  record, so opening one panel's REQUEST leaves its siblings shut."
  ([records] (records-list rf/dispatch nil records))
  ([dispatch records] (records-list dispatch nil records))
  ([dispatch expanded records]
  (when (seq records)
    [:div {:data-testid "rf-xray-managed-fx-list"
           :style {:padding "8px 0"
                   :border-top (str "1px solid " (:border-subtle tokens))}}
     [:div {:style {:padding "8px 12px 0 12px"
                    :font-family sans-stack
                    :font-size "12px"
                    :color (:text-tertiary tokens)}}
      [:span (str (count records) " managed-fx record"
                  (if (= 1 (count records)) "" "s")
                  " in this event-bundle")]]
     ;; rf2-hxfy — each panel carries its own `:key` in the `:section`
     ;; attribute map `record-panel` returns (see the comment there).
     ;; Reader meta here would attach to the CALL form and be lost.
     (for [rec records]
       (record-panel dispatch expanded rec))])))
