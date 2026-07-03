(ns day8.re-frame2-xray.spine-filters
  "Per-event-id mute / hide-from-spine filter (rf2-ikuwt).

  ## Why mute?

  Pre-rf2-ikuwt the only way to hide noisy events (clock-ticks,
  infrastructure heartbeats, mouse-move floods) was the heavyweight
  OUT-pill flow — right-click → popup → confirm → persisted as an OUT
  pill that affects every downstream event-bundle reader. Mike's directive
  watching the parallel-frames testbed live (2026-05-19): the OUT
  pill flow is too ceremonious for the common 'this event-id is noise
  right now, hide it from L2' gesture.

  This ns adds a separate one-step mute affordance:

    - Right-click an L2 row → context menu with 'Mute :event-id'
      (one-step) and 'Always hide this event-type…' (the existing
      OUT-pill flow). Both items prevent the browser context menu.
    - The mute set persists to localStorage under
      `xray.spine.muted-event-ids` (per bead) and round-trips
      through the same hydrate-on-install pattern as the IN/OUT pills.
    - The L1 ribbon carries a mute-count indicator (`🔇 N`) when at
      least one event-id is muted. Click it → unmute manager modal
      with a list of muted ids + per-row unmute buttons + a 'Clear
      all' affordance.

  ## What is filtered

  The mute filter runs at the event-bundle-list layer alongside the
  IN/OUT pill filter — the `:rf.xray/filtered-event-bundles` sub composes
  `event-bundle → frame-filter → typed-pill-filter → mute-filter` so every
  downstream consumer (L2 list, scrubber, palette, Issues counter,
  spine nav) sees the muted-events-stripped vector. The raw
  `:rf.xray/event-bundles` sub still carries every event so the Trace
  tab's own filter UI (separate concern) sees the full stream.

  ## Scope (v1, per bead rf2-ikuwt)

    - Exact event-id match only (no regex / glob — deferred to v1.1).
    - Global mute (not frame-scoped — deferred).
    - Mute list persists to localStorage.
    - Right-click context menu + L1 ribbon indicator + unmute
      manager modal.

  ## What does NOT live here

    - The right-click context menu UI lives in `shell.cljs`'s
      `event-row` (it's a tiny inline `<ul>` overlay, not a separate
      ns).
    - The L1 ribbon mute-count indicator + the unmute manager modal
      mount points live in `shell.cljs` so the chrome owns its
      placement; this ns exposes the `Modal` `reg-view` for the
      shell to mount alongside the other modals.

  ## Storage shape

      ;; localStorage value (EDN string):
      #{:parallel-frames.core/clock-tick
        :user/mouse-move}

  Set membership semantics — muting an already-muted id is a no-op;
  unmuting an unmuted id is a no-op. Hand-edited values that fail
  to parse fall back to the empty set so a corrupted slot never
  poisons boot."
  (:require [cljs.reader :as reader]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale sans-stack mono-stack]]))

;; ---- pure helpers --------------------------------------------------------

(defn event-bundle-event-id
  "Pluck the event-id from an event-bundle's `:event` slot. Mirrors
  `shell/event-id-of-event-bundle` but kept local so this ns is
  free-standing (no shell ↔ filters cycle)."
  [event-bundle]
  (let [ev (:event event-bundle)]
    (when (vector? ev)
      (first ev))))

(defn mute-event-id
  "Pure reducer — add `event-id` to the muted set. nil event-ids are
  ignored so a defensive call from an event-bundle with no event vector is
  a no-op rather than corrupting the slot with a nil entry."
  [muted event-id]
  (if (some? event-id)
    (conj (or muted #{}) event-id)
    (or muted #{})))

(defn unmute-event-id
  "Pure reducer — drop `event-id` from the muted set."
  [muted event-id]
  (disj (or muted #{}) event-id))

(defn clear-muted
  "Pure reducer — drop every entry."
  [_muted]
  #{})

(defn filter-event-bundles
  "Pure helper. Strip event-bundles whose event-id sits in `muted-set` from
  `event-bundles`. Empty / nil `muted-set` returns the input vector
  unchanged so the no-mutes-active case is allocation-free.

  Event bundles with no event vector (the `:ungrouped` bucket) survive the
  filter — they're handled by the spine's separate `show-ungrouped?`
  opt-in. Pure data; JVM-runnable."
  [event-bundles muted-set]
  (if (empty? muted-set)
    event-bundles
    (filterv (fn [event-bundle]
               (let [id (event-bundle-event-id event-bundle)]
                 (or (nil? id) (not (contains? muted-set id)))))
             event-bundles)))

;; ---- localStorage round-trip --------------------------------------------

(def default-storage-key
  "localStorage key the mute set persists under per bead rf2-ikuwt."
  "xray.spine.muted-event-ids")

;; Raw browser access lives in the shared `local-storage` seam
;; (rf2-jkake.24).

(defn ->edn
  "Serialise the muted set into a stable EDN string. Sorting the
  members before printing makes the persisted blob diffable across
  sessions (cljs `pr-str` on a set has undefined order)."
  [muted]
  (pr-str (into (sorted-set) (or muted #{}))))

(defn <-edn
  "Parse a stored EDN string. Returns the parsed set on success, or
  the empty set on parse failure / unrecognised shape — the load path
  never throws into init."
  [s]
  (let [parsed (try (reader/read-string s)
                    (catch :default _ nil))]
    (cond
      (set? parsed)        parsed
      (sequential? parsed) (set parsed)
      :else                #{})))

(defn read-raw
  []
  (ls/get-item default-storage-key))

(defn write-raw!
  [s]
  (ls/set-item! default-storage-key s))

(defn clear-raw!
  "Remove the persisted slot. Used by tests."
  []
  (ls/remove-item! default-storage-key))

(defn load
  "Read + parse the persisted muted set. Returns `#{}` when localStorage
  is unavailable / the slot is empty / the stored EDN is malformed."
  []
  (if-let [raw (read-raw)]
    (<-edn raw)
    #{}))

(defn save!
  "Write `muted` into localStorage. No-op when localStorage is
  unavailable. Swallows quota / serialisation errors so a write
  failure cannot poison the dispatch chain."
  [muted]
  (write-raw! (->edn muted))
  nil)

;; ---- hydration ----------------------------------------------------------

(defn hydrate!
  "Drive the localStorage → app-db hydration. Mirrors
  `filters/hydrate!`'s shape — re-entrant, safe to call from
  preload-time `install!` AND from `mount.cljs/ensure-xray-frame!`.
  Returns nil."
  []
  (let [loaded (load)]
    (when (and (seq loaded)
               (some? (frame/frame :rf/xray)))
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/hydrate-muted-event-ids loaded]))
      nil)))

;; ---- Row context menu --------------------------------------------------
;;
;; Per spec/018 §7 + rf2-ikuwt the L2 row's right-click opens a small
;; floating context menu with two items:
;;
;;   1. 'Mute <event-id>' — one-step mute via `:rf.xray/mute-event-id`.
;;      Hides the row from the spine immediately; persists; the L1
;;      ribbon's mute-count indicator increments. Reversible from the
;;      unmute manager.
;;
;;   2. 'Always hide this event-type…' — opens the rich OUT-filter
;;      popup via `:rf.xray/hide-event-type` (the existing flow,
;;      preserved verbatim). The user can fine-tune the OUT pill's
;;      pattern before confirming.
;;
;; The menu state lives in app-db at `:row-context-menu` as
;; `{:event-id <kw> :x <px> :y <px>}` or nil. The shell's `event-row`
;; writes the slot from the row's `on-context-menu`; this ns renders
;; the menu and owns the dismiss events.

(defn- menu-style [x y]
  ;; rf2-om6fa — `:fixed` positioning so the menu floats free of the
  ;; row's overflow:hidden clipping. z-index sits ABOVE the L2 list
  ;; but BELOW the modal backdrops so opening a modal while the menu
  ;; is up still puts the modal on top.
  {:position      "fixed"
   :top           (str y "px")
   :left          (str x "px")
   :background    (:bg-1 tokens)
   :border        (str "1px solid " (:border-default tokens))
   :border-radius "4px"
   :box-shadow    "rgba(0, 0, 0, 0.4) 0 4px 16px"
   :padding       "4px 0"
   :min-width     "220px"
   :max-width     "320px"
   :z-index       2147483050
   :font-family   sans-stack
   :font-size     (:body type-scale)
   :color         (:text-primary tokens)})

(defn- menu-item-style []
  {:width        "100%"
   :background   "transparent"
   :border       "none"
   :color        (:text-primary tokens)
   :font-family  sans-stack
   :font-size    (:body type-scale)
   :text-align   "left"
   :padding      "6px 12px"
   :cursor       "pointer"
   :white-space  "nowrap"
   :overflow     "hidden"
   :text-overflow "ellipsis"
   :display      "block"})

(defn- truncate-id
  "Cap the rendered event-id at ~28 chars so the menu item's text
  doesn't blow the menu width on long namespaced ids. Pure data."
  [event-id]
  (let [s (str event-id)]
    (if (<= (count s) 28)
      s
      (str (subs s 0 25) "…"))))

(defn row-context-menu
  "Hiccup for the row's right-click context menu. Pure rendering;
  state lives in `:rf.xray/row-context-menu`. Rendered at the
  shell-view root so the menu floats above the L2 list's
  overflow-hidden clipping.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher captured by the
  `RowContextMenu` `reg-view` body so the menu actions land on the
  surrounding instance frame, not a `{:frame :rf/xray}` literal."
  [dispatch]
  (when-let [menu @(rf/subscribe [:rf.xray/row-context-menu])]
    (let [{:keys [event-id x y]} menu
          close! (fn [] (dispatch [:rf.xray/close-row-context-menu]))]
      [:<>
       ;; Invisible backdrop catches outside-click → close. Sized to
       ;; the full viewport with a z-index just below the menu so the
       ;; menu sits on top.
       [:div {:data-testid "rf-xray-row-context-menu-backdrop"
              :on-click    (fn [^js e]
                             (.stopPropagation e)
                             (close!))
              :on-context-menu (fn [^js e]
                                 (.preventDefault e)
                                 (close!))
              :style       {:position "fixed"
                            :top      0
                            :left     0
                            :right    0
                            :bottom   0
                            :z-index  2147483049
                            :background "transparent"}}]
       [:ul {:data-testid "rf-xray-row-context-menu"
             :data-rf-xray-event-id (str event-id)
             :on-click    (fn [^js e] (.stopPropagation e))
             :on-key-down (fn [^js e]
                            (when (= "Escape" (.-key e))
                              (close!)))
             :style (assoc (menu-style x y)
                           :list-style "none"
                           :margin 0)}
        [:li {:style {:padding "4px 12px 6px"
                      :color   (:text-tertiary tokens)
                      :font-size (:caption type-scale)
                      :border-bottom (str "1px solid " (:border-subtle tokens))
                      :margin-bottom "4px"
                      :font-family mono-stack
                      :overflow "hidden"
                      :text-overflow "ellipsis"
                      :white-space "nowrap"}}
         (str event-id)]
        [:li
         [:button {:data-testid "rf-xray-row-context-menu-mute"
                   :on-click    (fn [_e]
                                  (dispatch [:rf.xray/mute-event-id event-id])
                                  (close!))
                   :title       (str "Mute " event-id " — hide from spine; reversible")
                   :style       (menu-item-style)}
          (str "Mute " (truncate-id event-id))]]
        [:li
         [:button {:data-testid "rf-xray-row-context-menu-hide-event-type"
                   :on-click    (fn [_e]
                                  (dispatch [:rf.xray/hide-event-type event-id])
                                  (close!))
                   :title       "Open the OUT-filter popup pre-filled with this event-id"
                   :style       (menu-item-style)}
          "Always hide this event-type…"]]]])))

(rf/reg-view RowContextMenu
  "The row context menu — rendered at the shell-view root so the
  popover floats above the L2 list's clipping. Per rf2-in6l2
  `reg-view`-registered so subscribes resolve to `:rf/xray`.

  rf2-nesy9 — threads the reg-view-injected frame-aware `dispatch` so
  menu actions land on the surrounding instance frame."
  []
  (row-context-menu dispatch))

;; ---- Modal --------------------------------------------------------------

(defn- backdrop-style [positioning]
  (let [absolute? (= positioning :absolute)]
    {:position        (if absolute? "absolute" "fixed")
     :top             0
     :left            0
     :right           0
     :bottom          0
     :background      "rgba(8, 9, 12, 0.65)"
     :backdrop-filter "blur(2px)"
     :display         "flex"
     :align-items     "center"
     :justify-content "center"
     :z-index         (if absolute? 98 2147483100)}))

(defn- dialog-style []
  {:background      (:bg-1 tokens)
   :border          (str "1px solid " (:border-default tokens))
   :border-radius   "6px"
   :box-shadow      "rgba(0, 0, 0, 0.5) 0 12px 32px"
   :width           "440px"
   :max-width       "92vw"
   :max-height      "70vh"
   :padding         "20px"
   :display         "flex"
   :flex-direction  "column"
   :gap             "14px"
   :font-family     sans-stack
   :color           (:text-primary tokens)})

(defn- header
  [dispatch]
  [:div {:style {:display "flex"
                 :align-items "center"
                 :justify-content "space-between"}}
   [:h2 {:id "rf-xray-mute-manager-title"
         :style {:margin 0
                 :font-size "14px"
                 :font-weight 600
                 :color (:text-primary tokens)
                 :text-transform "uppercase"
                 :letter-spacing "0.5px"}}
    "Muted events"]
   [:button
    {:data-testid "rf-xray-mute-manager-close"
     :aria-label  "Close muted-events manager"
     :on-click    #(dispatch [:rf.xray/close-mute-manager])
     :style       {:background "transparent"
                   :border "none"
                   :color (:text-tertiary tokens)
                   :font-size "16px"
                   :cursor "pointer"
                   :padding "0 6px"}
     :title       "Close (Esc)"}
    "✕"]])

(defn- empty-state
  []
  [:p {:data-testid "rf-xray-mute-manager-empty"
       :style {:margin 0
               :color (:text-secondary tokens)
               :font-size "12px"
               :line-height 1.5}}
   "No events are currently muted. Right-click an event row in the spine to mute it."])

(defn- mute-row
  [dispatch event-id]
  [:li {:data-testid (str "rf-xray-mute-manager-row-" (str event-id))
        :style {:display "flex"
                :align-items "center"
                :justify-content "space-between"
                :gap "8px"
                :padding "6px 8px"
                :background (:bg-2 tokens)
                :border (str "1px solid " (:border-subtle tokens))
                :border-radius "4px"}}
   [:span {:style {:font-family mono-stack
                   :font-size (:mono-body type-scale)
                   :color (:text-primary tokens)
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"
                   :flex "1 1 auto"
                   :min-width 0}}
    (str event-id)]
   [:button {:data-testid (str "rf-xray-mute-manager-unmute-" (str event-id))
             :on-click    #(dispatch [:rf.xray/unmute-event-id event-id])
             :title       (str "Unmute " event-id)
             :style       {:background    "transparent"
                           :border        (str "1px solid " (:border-default tokens))
                           :color         (:text-primary tokens)
                           :cursor        "pointer"
                           :font-family   sans-stack
                           :font-size     "11px"
                           :padding       "3px 10px"
                           :border-radius "3px"}}
    "Unmute"]])

(defn- list-section
  [dispatch muted-ids]
  (into [:ul {:data-testid "rf-xray-mute-manager-list"
              :style {:list-style "none"
                      :margin 0
                      :padding 0
                      :display "flex"
                      :flex-direction "column"
                      :gap "4px"
                      :overflow-y "auto"
                      :max-height "44vh"}}]
        (for [id (sort-by str muted-ids)]
          ^{:key (str id)} [mute-row dispatch id])))

(defn- clear-all-button
  [dispatch]
  [:button {:data-testid "rf-xray-mute-manager-clear-all"
            :on-click    #(dispatch [:rf.xray/clear-muted-event-ids])
            :title       "Unmute every event-id"
            :style       {:background    "transparent"
                          :border        (str "1px solid " (:border-subtle tokens))
                          :color         (:text-tertiary tokens)
                          :font-family   sans-stack
                          :font-size     "12px"
                          :padding       "6px 12px"
                          :border-radius "4px"
                          :cursor        "pointer"
                          :margin-left   "auto"}}
   "Unmute all"])

(defn dialog
  "The mute manager modal — backdrop + dialog scaffold (rf2-7oxvd)
  around the manager body. Pure hiccup.

  Returns the full `[:div backdrop [:div dialog …]]` tree (the
  `modals-aria-cljs-test` renders this fn directly and walks for the
  dialog node). `spine-filters/Modal` gates on
  `:rf.xray/mute-manager-open?` and calls this fn.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher captured by the
  `Modal` `reg-view` body — threaded to header / rows / clear-all so
  unmute actions land on the surrounding instance frame, not a
  `{:frame :rf/xray}` literal."
  [dispatch]
  (let [muted       @(rf/subscribe [:rf.xray/muted-event-ids])
        positioning @(rf/subscribe [:rf.xray/modal-positioning])]
    ;; rf2-7oxvd — shared backdrop + dialog scaffold. Keeps this modal's
    ;; own `backdrop-style` / `dialog-style`, its `tab-index "-1"` dialog
    ;; root (the empty-state body has no focusable child, so the trap
    ;; pins focus on the root), and its dialog-level Esc handler.
    ;; `modal-chrome` owns the positioning attribute, the click-outside
    ;; dismiss, `a11y/dialog-attrs` + the `a11y/dialog-ref` focus trap.
    (modal-chrome/modal-chrome
      {:positioning        positioning
       :backdrop-style     (backdrop-style positioning)
       :dialog-style       (dialog-style)
       :on-dismiss         #(dispatch [:rf.xray/close-mute-manager])
       :labelled-by        "rf-xray-mute-manager-title"
       :backdrop-testid    "rf-xray-mute-manager-backdrop"
       :dialog-testid      "rf-xray-mute-manager-dialog"
       :dialog-tab-index   "-1"
       :on-dialog-key-down (fn [^js e]
                             (when (= "Escape" (.-key e))
                               (dispatch [:rf.xray/close-mute-manager])))}
     (header dispatch)
     (if (empty? muted)
       (empty-state)
       [:<>
        (list-section dispatch muted)
        [:div {:style {:display "flex" :align-items "center" :gap "8px"}}
         (clear-all-button dispatch)]]))))

(rf/reg-view Modal
  "The mute manager modal. Renders only when
  `:rf.xray/mute-manager-open?` is true; closed-state is one
  subscribe + a `when`. Per rf2-in6l2 `reg-view`-registered so the
  body's subscribes resolve through React-context to `:rf/xray`."
  []
  (when @(rf/subscribe [:rf.xray/mute-manager-open?])
    (dialog dispatch)))

;; ---- ribbon indicator (mounted from shell.cljs) -------------------------

(defn ribbon-mute-indicator
  "Pure hiccup. Renders nothing when no event-ids are muted; when
  positive, renders a small `🔇 N` (speaker-mute glyph) clickable
  chip that opens the unmute manager modal.

  Mounted inline in the L1 ribbon next to the REDACTED indicator —
  surfaces the mute state without claiming a permanent ribbon slot.

  `dispatch` (rf2-nesy9) is the frame-aware dispatcher captured by the
  caller's `reg-view` body."
  [dispatch muted-count]
  (when (pos? muted-count)
    [:button {:data-testid "rf-xray-ribbon-mute-indicator"
              :on-click    #(dispatch [:rf.xray/open-mute-manager])
              :title       (str muted-count
                                " event-"
                                (if (= 1 muted-count) "id" "ids")
                                " muted from the spine. Click to manage.")
              :style       {:background    "transparent"
                            :border        (str "1px solid " (:border-subtle tokens))
                            :color         (:text-secondary tokens)
                            :cursor        "pointer"
                            :font-family   sans-stack
                            :font-size     (:caption type-scale)
                            :font-weight   600
                            :padding       "2px 8px"
                            :border-radius "10px"
                            :display       "inline-flex"
                            :align-items   "center"
                            :gap           "4px"}}
     [:span {:style {:font-size "12px"}} "🔇"]
     [:span {:data-testid "rf-xray-ribbon-mute-indicator-count"}
      (str muted-count)]]))

;; ---- install -----------------------------------------------------------

(defn install!
  "Idempotent install — registers the muted-event-ids subs / events /
  fxs against the registry. Called from
  `registry/register-xray-handlers!` fan-out."
  []
  ;; ---- fx ---------------------------------------------------------------
  (rf/reg-fx :rf.xray.spine-filters/persist
    (fn [_ctx muted]
      (save! muted)))

  ;; ---- subs -------------------------------------------------------------
  ;;
  ;; The mute set lives on `:muted-event-ids` (canonical set slot).
  ;; The count sub drives the L1 ribbon indicator; pulling the count
  ;; through its own sub means the indicator re-renders only when
  ;; the count changes — touching the underlying set on every mute
  ;; toggle would re-render every consumer that holds the set.
  (rf/reg-sub :rf.xray/muted-event-ids
    (fn [db _query]
      (or (get db :muted-event-ids) #{})))

  (rf/reg-sub :rf.xray/muted-event-ids-count
    :<- [:rf.xray/muted-event-ids]
    (fn [muted _query]
      (count muted)))

  (rf/reg-sub :rf.xray/mute-manager-open?
    (fn [db _query]
      (boolean (get db :mute-manager-open?))))

  (rf/reg-sub :rf.xray/row-context-menu
    (fn [db _query]
      (get db :row-context-menu)))

  ;; ---- events: mute / unmute / clear -----------------------------------
  ;;
  ;; Every mutation attaches the persist fx so the post-mutation set
  ;; lands in localStorage in one place (no fx-per-handler duplication;
  ;; mirrors the filters subsystem's pattern).

  (rf/reg-event :rf.xray/mute-event-id
    (fn [{:keys [db]} [_ event-id]]
      (let [next-muted (mute-event-id (get db :muted-event-ids) event-id)
            next-db    (assoc db :muted-event-ids next-muted)]
        {:db next-db
         :fx [[:rf.xray.spine-filters/persist next-muted]]})))

  (rf/reg-event :rf.xray/unmute-event-id
    (fn [{:keys [db]} [_ event-id]]
      (let [next-muted (unmute-event-id (get db :muted-event-ids) event-id)
            next-db    (assoc db :muted-event-ids next-muted)]
        {:db next-db
         :fx [[:rf.xray.spine-filters/persist next-muted]]})))

  (rf/reg-event :rf.xray/clear-muted-event-ids
    (fn [{:keys [db]} _event]
      (let [next-muted (clear-muted (get db :muted-event-ids))
            next-db    (assoc db :muted-event-ids next-muted)]
        {:db next-db
         :fx [[:rf.xray.spine-filters/persist next-muted]]})))

  ;; ---- events: manager modal open / close -----------------------------

  (rf/reg-event :rf.xray/open-mute-manager
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _event]
      {:db (assoc db :mute-manager-open? true)}))

  (rf/reg-event :rf.xray/close-mute-manager
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _event]
      {:db (assoc db :mute-manager-open? false)}))

  ;; ---- events: row context menu open / close --------------------------
  ;;
  ;; `:rf.xray/open-row-context-menu` is dispatched from the L2 row's
  ;; `on-context-menu` handler in `shell.cljs`. The payload carries
  ;; the row's event-id + the click coords so the menu renders at the
  ;; cursor. `:rf.xray/close-row-context-menu` is dispatched from
  ;; outside-click / Escape / menu-item click (after the item's own
  ;; action lands).

  (rf/reg-event :rf.xray/open-row-context-menu
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ {:keys [event-id x y]}]]
      {:db (if event-id
        (assoc db :row-context-menu
                  {:event-id event-id
                   :x        (or x 0)
                   :y        (or y 0)})
        db)}))

  (rf/reg-event :rf.xray/close-row-context-menu
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} _event]
      {:db (dissoc db :row-context-menu)}))

  ;; ---- events: hydrate from localStorage ------------------------------

  (rf/reg-event :rf.xray/hydrate-muted-event-ids
    {:rf.trace/no-emit? true}
    (fn [{:keys [db]} [_ muted]]
      {:db (assoc db :muted-event-ids (set (or muted #{})))}))

  ;; NO hydrate on install (rf2-swclw). The muted-event-ids set is a
  ;; TRANSIENT exploration filter — it resets to empty on every page
  ;; load so a fresh session never silently hides events muted in a
  ;; past session. The slot starts at its registry default `#{}` and
  ;; `mount.cljs`'s `::reset-transient-filters` first-mount hook clears
  ;; the stale localStorage slot so storage matches. `hydrate!` / `load`
  ;; remain as the data layer (exercised by the round-trip tests), but
  ;; init does not restore.
  nil)
