(ns ssr-multi-frame.core
  "Shared framework-behavior testbed — per-frame hydration isolation.

  Three frames coexist on the page (`:counter/a`, `:counter/b`, `:log`),
  each receiving its OWN `:rf/hydrate` dispatch from a per-frame
  payload slice. Per [spec/011-SSR.md §Frames are per-request] +
  [spec/002-Frames.md §What lives in a frame]: each frame owns its
  own app-db, router queue, and signal-graph cache; the hydration
  protocol must operate frame-by-frame.

  The static `index.html` bakes ONE payload script carrying a map of
  `{frame-id → per-frame-payload}`. The browser-side `run` walks the
  map and dispatches `[:rf/hydrate slice]` per frame with explicit
  `{:frame frame-id}` opt.

  Per-frame seeded values:

    :counter/a → {:n 10}                                    -> 'A: n=10'
    :counter/b → {:n 99}                                    -> 'B: n=99'
    :log       → {:entries [{:from :ssr :note 'hello'} ...]}-> log entries

  Post-hydrate, three sibling `[rf/frame-provider {:frame ...}]`
  subtrees render the three panels. Each subtree reads ONLY its own
  frame's app-db — `:counter/a`'s `:n` sub fires against `:counter/a`'s
  app-db, never `:counter/b`'s. A `data-testid='hydration-summary'`
  element reads each frame directly — its hydration metadata from
  runtime-db at `[:rf.runtime/ssr :hydration]` and its seeded value from
  app-db — to prove each frame's hydrate-event fired against its own
  frame-state (no cross-frame bleed).

  What a Playwright consumer observes:

    - Three panels with distinct seeded values, each tagged with the
      frame id. Cross-frame state is independent (Inc-A only bumps
      `:counter/a`; A's panel reads 11, B's panel still reads 99).
    - The hydration-summary block confirms three hydrated frames
      (each with its own runtime-db `[:rf.runtime/ssr :hydration]`
      record) holding three distinct seeded values — proving the
      round-trip is per-frame.

  This is NOT a tutorial — bodies are stark."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; SSR hydration metadata is durable runtime-db state, so the
            ;; per-frame :hydration readout is a runtime-db sub
            ;; (reg-runtime-sub lives on the re-frame.subs surface).
            [re-frame.subs :as rf.subs]
            [re-frame.views]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            ;; Loading the SSR artefact registers `:rf/hydrate`.
            [re-frame.ssr]
            [cljs.reader])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def frame-a   :counter/a)
(def frame-b   :counter/b)
(def frame-log :log)

;; ----------------------------------------------------------------------------
;; App-db initialisers (per-frame shape, registered once)
;; ----------------------------------------------------------------------------

(rf/reg-event ::counter-init
  (fn [_cofx _ev]
    {:db {:n 0}}))

(rf/reg-event ::log-init
  (fn [_cofx _ev]
    {:db {:entries []}}))

;; ----------------------------------------------------------------------------
;; Per-frame handlers — the framework dispatches each to the targeted frame
;; ----------------------------------------------------------------------------

(rf/reg-event ::inc
  (fn [{:keys [db]} _ev]
    {:db (update db :n (fnil inc 0))}))

(rf/reg-event ::log-append
  (fn [{:keys [db]} [_ entry]]
    {:db (update db :entries (fnil conj []) entry)}))

(rf/reg-sub :n          (fn [db _] (:n db)))
(rf/reg-sub :entries    (fn [db _] (:entries db)))
;; The SSR hydration metadata is durable runtime-db state — the `:rf/hydrate`
;; handler records it at [:rf.runtime/ssr :hydration] in each frame's
;; runtime-db partition (NOT app-db). So :hydration is a runtime-db sub; the
;; frame-explicit `{:frame f}` reads in `hydration-summary` resolve it against
;; each named frame's runtime-db.
(rf.subs/reg-runtime-sub :hydration
  (fn [rt _] (get-in rt [:rf.runtime/ssr :hydration])))

;; ----------------------------------------------------------------------------
;; Views
;; ----------------------------------------------------------------------------

(reg-view counter-panel [label test-suffix]
  (let [n     @(subscribe [:n])
        hyd   @(subscribe [:hydration])]
    [:div {:data-testid (str "panel-" test-suffix)
           :style {:border  "1px solid #aaa"
                   :padding "0.5em"
                   :margin  "0.25em"
                   :min-width "10em"}}
     [:h3 (str label ": ")
      [:span {:data-testid (str "label-" test-suffix)} label]]
     [:p "n=" [:span {:data-testid (str "n-" test-suffix)} n]]
     [:p "hydrated="
      [:span {:data-testid (str "hyd-" test-suffix)}
       (str (boolean hyd))]]
     [:button {:data-testid (str "inc-" test-suffix)
               :on-click    #(dispatch [::inc])}
      "Inc"]]))

(reg-view log-panel []
  (let [entries @(subscribe [:entries])
        hyd     @(subscribe [:hydration])]
    [:div {:data-testid "panel-log"
           :style {:border "1px solid #aaa"
                   :padding "0.5em"
                   :margin "0.25em"
                   :min-width "16em"}}
     [:h3 "log"]
     [:p "entries-count="
      [:span {:data-testid "entries-count"} (count entries)]]
     [:p "hydrated="
      [:span {:data-testid "hyd-log"} (str (boolean hyd))]]
     [:ul {:data-testid "log-entries"
           :style {:max-height "8em" :overflow :auto}}
      (for [[idx e] (map-indexed vector entries)]
        ^{:key idx}
        [:li (pr-str e)])]]))

(reg-view hydration-summary []
  ;; Cross-frame readout — proves each frame's runtime-db hydration
  ;; metadata ([:rf.runtime/ssr :hydration]) was written, and each frame's
  ;; app-db holds its OWN payload slice. Reactive `subscribe` with the
  ;; frame-explicit opts form (`[query-v {:frame f}]`) so each reaction
  ;; resolves against the named frame (this view renders OUTSIDE any
  ;; `frame-provider`), and the summary re-renders when a frame changes.
  ;; (Render-time reads are reactive `subscribe`, never `subscribe-once`.)
  (let [hyd-a  @(rf/subscribe [:hydration] {:frame frame-a})
        hyd-b  @(rf/subscribe [:hydration] {:frame frame-b})
        hyd-l  @(rf/subscribe [:hydration] {:frame frame-log})
        seed-a @(rf/subscribe [:n] {:frame frame-a})
        seed-b @(rf/subscribe [:n] {:frame frame-b})
        seed-l (count @(rf/subscribe [:entries] {:frame frame-log}))]
    [:div {:data-testid "hydration-summary"
           :style {:border  "1px solid #aaa"
                   :padding "0.5em"
                   :margin  "0.5em 0"
                   :font-family "monospace"}}
     [:p "frame :counter/a hydrated=" (str (boolean hyd-a)) " n="
      [:span {:data-testid "summary-a-seed"} (str seed-a)]]
     [:p "frame :counter/b hydrated=" (str (boolean hyd-b)) " n="
      [:span {:data-testid "summary-b-seed"} (str seed-b)]]
     [:p "frame :log hydrated=" (str (boolean hyd-l)) " entries="
      [:span {:data-testid "summary-log-seed"} (str seed-l)]]
     [:p "all-distinct="
      [:span {:data-testid "summary-all-distinct"}
       (str (and (every? boolean [hyd-a hyd-b hyd-l])
                 (= 3 (count (set [seed-a seed-b seed-l])))))]]]))

(reg-view root []
  [:div {:data-testid "ssr-multi-frame"
         :style {:font-family "sans-serif" :padding "1em"}}
   [:h1 "ssr-multi-frame testbed"]
   [hydration-summary]
   [:div {:style {:display :flex :flex-wrap :wrap :align-items :flex-start}}
    [rf/frame-provider {:frame frame-a}
     [counter-panel "A" "A"]]
    [rf/frame-provider {:frame frame-b}
     [counter-panel "B" "B"]]
    [rf/frame-provider {:frame frame-log}
     [log-panel]]]])

;; ----------------------------------------------------------------------------
;; Mount + hydrate per-frame
;; ----------------------------------------------------------------------------

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn read-server-payload []
  (when-let [el (.getElementById js/document "__rf_payload")]
    (cljs.reader/read-string (.-textContent el))))

(defn ^:export run []
  (rf/init! rf.adapter.reagent/adapter)

  ;; Register the three frames with their initialisers. Each has its
  ;; own :initial-events, but :rf/hydrate (dispatched below) will REPLACE
  ;; the resulting app-db with the per-frame payload slice (locked
  ;; :replace-app-db policy).
  (rf/make-frame {:id frame-a :initial-events [[::counter-init]]})
  (rf/make-frame {:id frame-b :initial-events [[::counter-init]]})
  (rf/make-frame {:id frame-log :initial-events [[::log-init]]})
  ;; `root` is a `reg-view` (EP-0002), so its wrapper resolves
  ;; a frame at render time for its injected bindings — it must render under
  ;; a provider. Use a neutral SHELL frame (`:rf/default`) as the page-root
  ;; scope; the three per-frame providers nested inside `root` override it
  ;; for their subtrees, and `hydration-summary`'s frame-explicit
  ;; `{:frame f}` reads target the three app frames directly, so the shell
  ;; carries no app state of its own.
  (rf/make-frame {:id :rf/default})

  (let [payload (read-server-payload)
        ;; payload shape:
        ;;   {:rf/version    1
        ;;    :rf/per-frame {<frame-id> <per-frame-payload>, ...}}
        per-frame (:rf/per-frame payload)]
    (when (map? per-frame)
      ;; HOT PATH — per-frame :rf/hydrate dispatch. Each call
      ;; targets a distinct frame; the framework writes against that
      ;; frame's app-db ONLY (no cross-frame bleed). The :frame opt
      ;; on the dispatch envelope is the routing key (per
      ;; spec/002 §Routing the dispatch envelope).
      (doseq [[fid slice] per-frame]
        (rf/dispatch-sync [:rf/hydrate slice] {:frame fid})))
    (rdc/render react-root [rf/frame-provider {:frame :rf/default} [root]])))
