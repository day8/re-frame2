(ns re-frame.story.ui.share
  "Share-URL hydration + the dropped-overrides hint banner.

  Story's per-variant share URL is the same URL the browser's address
  bar carries (Cmd-L / Cmd-A / Cmd-C copies it); there is no separate
  Share button (rf2-ymnfx — Issue B). What survives here is the read-
  side of the share URL contract: parse `?variant=...&overrides=...`
  on shell mount and surface a non-blocking hint when any encoded
  override no longer applies to the current variant body.

  ## What lives here

  - `hydrate-from-url!` — fold the share-URL `?overrides=` slot into
    the shell state on mount, including any drift the URL carries
    against the live registry (variant args renamed / removed). The
    rest of the share-URL surface (workspace, mode-tab, viewport,
    background, tag-filter, substrate) is hydrated by
    `re-frame.story.ui.url-state` — that ns owns chrome-state slots;
    this one owns the per-variant cell-overrides slot and the
    accompanying drift hint.

  - `share-import-hint` / `dismiss-share-import-hint!` — non-blocking
    banner the canvas splices over a variant when a hydrated URL
    dropped one or more `:cell-overrides` entries (rf2-9jthx).

  ## Bundle isolation

  Part of the Story UI shell. Under `:advanced` builds with
  `:rf.story/enabled?` false the shell is DCE'd and this ns rides
  out alongside it."
  (:require [re-frame.story.registrar :as registrar]
            [re-frame.story.share :as share]
            [re-frame.story.ui.state :as state]
            [re-frame.story.theme.typography :as typography :refer [mono-stack]]
            [re-frame.story.theme.colors :as colors]))

;; ---- helpers -------------------------------------------------------------

(defn- current-url-params
  []
  (when (exists? js/window)
    (let [search (some-> js/window .-location .-search)]
      (when (and (string? search) (seq search))
        (try
          (js/URLSearchParams. search)
          (catch :default _ nil))))))

(defn hydrate-from-url!
  "Hydrate the share-URL-owned shell state from `window.location.search`.

  Toolbar modes hydrate in `re-frame.story.ui.toolbar`; this function
  owns the rest of the share URL contract: focused variant, per-variant
  cell overrides, and substrate. Invalid/stale variant ids are ignored
  so old URLs degrade to the shell empty state instead of crashing.

  Surfaces the count of dropped overrides (rf2-9jthx) under
  `[:rf.story/share-import-hint variant-id]` so the shell can render
  a non-blocking hint when a recorded URL has drifted (variant args
  refactored, removed, renamed). Returns nothing — side-effect only.
  Pure helper exposed via `share/parse-overrides-param*` so tests can
  assert per-axis without touching the ratom."
  []
  (when-let [params (current-url-params)]
    (let [variant-id (share/parse-keyword-token (.get params "variant"))
          parsed     (share/parse-overrides-param* (.get params "overrides"))
          overrides  (:overrides parsed)
          dropped    (:dropped parsed)
          substrate  (share/parse-substrate-param (.get params "substrate"))
          valid?     (and variant-id (registrar/registered? :variant variant-id))]
      (state/swap-state!
        (fn [s]
          (let [s' (if valid?
                     (cond-> (-> s
                                 (state/select-variant variant-id)
                                 (state/select-workspace nil))
                       (seq overrides)
                       (assoc-in [:cell-overrides variant-id] overrides)
                       (and valid? (seq dropped))
                       (assoc-in [:rf.story/share-import-hint variant-id]
                                 {:dropped-count (count dropped)
                                  :dropped       (vec dropped)}))
                     s)]
            (cond-> s'
              substrate (assoc :substrate substrate))))))))

(defn dismiss-share-import-hint!
  "Clear the share-import hint for `variant-id` (rf2-9jthx). Called
  from the hint's close affordance."
  [variant-id]
  (state/swap-state!
    (fn [s] (update s :rf.story/share-import-hint dissoc variant-id))))

(defn share-import-hint
  "Render the share-import hint banner for `variant-id` when a hydrated
  share URL dropped one or more overrides (rf2-9jthx). Non-blocking —
  shows N + the dropped tokens, with a dismiss affordance.

  Returns nil when nothing dropped, so callers can splice
  unconditionally."
  [variant-id]
  (let [hint (get-in @state/shell-state-atom
                     [:rf.story/share-import-hint variant-id])
        n    (:dropped-count hint 0)]
    (when (pos? n)
      [:div {:role      "status"
             :data-test "story-share-import-hint"
             :data-dropped-count n
             :style     {:padding "6px 10px"
                         :margin "4px 0"
                         :background "#3a2a1a"
                         :color "#e0a060"
                         :border "1px solid #a06030"
                         :border-radius "3px"
                         :font-family mono-stack
                         :font-size (:caption typography/type-scale)
                         :display "flex"
                         :align-items "center"
                         :justify-content "space-between"}}
       [:span
        (str n " override" (when (not= 1 n) "s")
             " from this URL no longer apply — variant args refactored?")]
       [:button {:style     {:padding "2px 8px"
                             :background (:bg-3 colors/tokens)
                             :color (:text-primary colors/tokens)
                             :border "1px solid #555"
                             :border-radius "3px"
                             :cursor "pointer"
                             :font-size (:micro typography/type-scale)
                             :margin-left "10px"}
                 :data-test "story-share-import-hint-dismiss"
                 :on-click  (fn [_] (dismiss-share-import-hint! variant-id))}
        "dismiss"]])))
