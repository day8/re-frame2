(ns day8.re-frame2-machines-viz.viewer
  "Read-only viewer page entry (rf2-8d7w1 · v1.0).

  A statically-hostable single page that decodes a `#machine=<base64url>`
  URL fragment and renders the encoded chart in a no-runtime,
  read-only `MachineChart`. The receiving end of the 'Copy machine as →
  Share URL' affordance — a chart pasted into a PR description, a design
  doc, a Slack thread, a whiteboard.

  ## What it does (per `API.md` §Read-only viewer)

  1. On load, read `location.hash`, strip `#machine=`, base64url-decode,
     transit-read, and validate the envelope
     (`share/decode-share-url-safe`).
  2. Validation failure → a banner: 'This share-URL is malformed or was
     produced by a newer Machines-Viz.' No chart renders.
  3. Validation success → mount `MachineChart` with `:machine-id` +
     `:definition` from the payload, `:current-state` from the
     snapshot's `:state` (the share carries the state NAME only — there
     is no runtime `:data`), and `:read-only? true`.
  4. A banner reads: 'This is a static machine chart, not a Xray
     session — interactions are disabled.'
  5. A 'show idle' toggle clears `:current-state` so the chart renders
     at rest (Lock #5).

  ## What it never does

  - Never transmits the fragment to a server (read client-side via
    `location.hash`).
  - Never loads trace events, app-db slices, or anything outside the
    validated payload schema.
  - Never receives runtime `:data` (the snapshot carries `:state` only).
  - Never receives `:source-coords` (not in the share schema).
  - Never enables `:on-*` callbacks (`:read-only? true` no-op's them).

  ## Build

  Compiled to a single bundle the static `public/viewer.html` loads.
  `(viewer/run)` is the `^:export` entry. The page is presentation-only:
  it initialises re-frame purely so the Reagent adapter is live (the
  chart is a Reagent component); it registers no frame, dispatches no
  events, and reads no `[:rf/machines]` slot.

  Per [`API.md`](../../spec/API.md) §Read-only viewer + §Share-URL
  encoding."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [day8.re-frame2-machines-viz.chart :as chart]
            [day8.re-frame2-machines-viz.share :as share]))

;; ---------------------------------------------------------------------------
;; Pure decode → view-model (testable without a DOM)

(defn decode-location
  "Decode a share-URL string into a viewer view-model. Pure — the DOM
  reader (`run`) hands `location.href` in.

  Returns one of:
  - `{:status :ok    :props {<MachineChart props>}}`
  - `{:status :empty}` — no `#machine=` fragment present (a bare visit)
  - `{:status :error :reason <kw> :message <str>}` — malformed payload"
  [url]
  (let [has-fragment? (and (string? url) (re-find #"#machine=" url))]
    (if-not has-fragment?
      {:status :empty}
      (let [{:keys [ok error]} (share/decode-share-url-safe url)]
        (if ok
          {:status :ok :props (share/chart-state->props ok)}
          {:status :error
           :reason  (:reason error)
           :message (:message error)})))))

;; ---------------------------------------------------------------------------
;; Styling tokens (inline — the page ships no external stylesheet)

(def ^:private styles
  {:page    {:min-height "100vh" :margin 0
             :background "#1a1d23" :color "#e6e8eb"
             :font-family "system-ui, sans-serif"
             :display "flex" :flex-direction "column"}
   :banner  {:padding "10px 16px" :font-size "13px"
             :background "#2a2e36" :border-bottom "1px solid #353a44"
             :display "flex" :align-items "center" :gap "12px"}
   :err     {:padding "24px" :max-width "640px" :margin "48px auto"
             :background "#3a2a2a" :border "1px solid #5a3a3a"
             :border-radius "8px" :line-height 1.5}
   :chart   {:flex 1 :min-height 0 :padding "12px"}
   :toggle  {:font-size "12px" :color "#a8aeb6" :cursor "pointer"
             :display "inline-flex" :align-items "center" :gap "6px"}})

;; ---------------------------------------------------------------------------
;; Views

(defn- error-view [{:keys [message]}]
  [:div {:style (:err styles) :data-testid "rf-mv-viewer-error"}
   [:strong "This share-URL is malformed or was produced by a newer Machines-Viz."]
   (when message
     [:p {:style {:color "#c8a0a0" :margin "8px 0 0 0" :font-size "12px"}}
      message])])

(defn- empty-view []
  [:div {:style (:err styles) :data-testid "rf-mv-viewer-empty"}
   [:strong "No machine to display."]
   [:p {:style {:color "#a8aeb6" :margin "8px 0 0 0" :font-size "13px"}}
    "This page renders a machine chart from a share-URL. Open a "
    [:code "#machine=…"] " link to see a chart."]])

(defn- chart-view
  "The success path. Mounts MachineChart read-only with a 'show idle'
  toggle that clears the active-state highlight (Lock #5)."
  [props]
  (let [show-idle? (r/atom false)]
    (fn [props]
      [:div {:style (:page styles)}
       [:div {:style (:banner styles) :data-testid "rf-mv-viewer-banner"}
        [:span "This is a static machine chart, not a Xray session — interactions are disabled."]
        [:label {:style (:toggle styles)}
         [:input {:type "checkbox"
                  :checked @show-idle?
                  :data-testid "rf-mv-viewer-show-idle"
                  :on-change #(swap! show-idle? not)}]
         "show idle"]]
       [:div {:style (:chart styles)}
        [chart/MachineChart
         (cond-> props
           @show-idle? (dissoc :current-state))]]])))

(defn viewer-view
  "Top-level viewer view for a decoded view-model. Pure given the
  view-model — used directly by tests + by `run`."
  [view-model]
  (case (:status view-model)
    :ok    [chart-view (:props view-model)]
    :error [:div {:style (:page styles)} [error-view view-model]]
    :empty [:div {:style (:page styles)} [empty-view]]
    [:div {:style (:page styles)} [empty-view]]))

;; ---------------------------------------------------------------------------
;; Mount

(defonce ^:private app-root (atom nil))

(defn- current-href []
  (try (.. js/window -location -href) (catch :default _ "")))

(defn ^:export run
  "Viewer entry. Initialises re-frame (so the Reagent component renders),
  decodes the current location, and mounts the viewer into `#app`."
  []
  (rf/init! reagent-adapter/adapter)
  (let [el (.getElementById js/document "app")]
    (when el
      (when (nil? @app-root)
        (reset! app-root (rdc/create-root el)))
      (rdc/render @app-root [viewer-view (decode-location (current-href))]))))
