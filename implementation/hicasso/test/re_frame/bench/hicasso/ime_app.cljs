(ns re-frame.bench.hicasso.ime-app
  "THE IME COMPOSITION PAGE — the in-browser half of the real-CompositionEvent
  harness (rf2-o27h3). `ime_run.cjs` is the other half and the only caller.

  ## Why this page exists

  The `:ime-composition-commits-nothing` row of the controlled grid was
  probed with synthetic `Event`s named `compositionstart`/`compositionend`,
  which exercise neither React's composition plumbing nor the browser's own
  composition state, and the row was left deliberately unasserted
  (rf2-n3dxw, rf2-m6if4). A real IME exchange is browser machinery — a
  composition RANGE the IME owns, `input` events carrying
  `inputType \"insertCompositionText\"` and `isComposing true`, keydowns
  whose `isComposing` reflects live composition state — and nothing
  dispatched from page script reproduces it. CDP's `Input.imeSetComposition`
  / `Input.insertText` does: measured on this repo's Playwright Chromium,
  it produces TRUSTED `compositionstart`/`compositionupdate` events, trusted
  `input` events with the composition fields set, trusted keydowns with
  `isComposing` computed from the live composition, and a real composition
  range that a JS `value` write silently aborts. The driver drives those;
  this page mounts the implementations and records what their handlers see.

  ## The three input implementations, one per page load

  `?impl=` selects exactly one, so no row can inherit another's machinery
  (the rf2-n3dxw discipline — two rows were once green while measuring
  UIx's Reagent-port and reading it as React):

  | `?impl=` | what mounts | pin |
  |---|---|---|
  | `hicasso` | Arm 1's element path — `defview` cells, intent vectors, the composition-gated key-map, and `front.controlled/install!`'s converge wrapped around the change handler by the codec | `false` (recorded; the codec never consults it — `arm1_controlled_grid_dom_cljs_test` proves that) |
  | `react` | UIx adapter `defui` cells on plain React controlled inputs | `false` — plain React |
  | `uix-port` | the same `defui` cells on UIx's port of Reagent's controlled-input workaround | `true` — the port |

  Each page is three fields of the grid model's policy family — `plain`
  takes what is typed, `digits` refuses non-digits (the model does not
  move), `upper` normalises to upper case — plus the commit door the IME
  fence exists for: Enter copies a cell's live value into `:committed`,
  gated by `front.intent/composing?` (on the hicasso row via the key-map,
  which is composition-gated centrally; on the other two via the same
  predicate called from an ordinary `:on-key-down`, so one gate is driven
  through both event plumbings).

  ## Two probe layers, and why both

  - **`\"native\"`** — plain `addEventListener` on each field, recording
    what the browser actually delivered: `isComposing`, `inputType`,
    `data`, `keyCode`, `isTrusted`, and the field's value/selection at
    delivery. This layer is the ground truth the driver's carriage
    assertions read, and its `compositionstart` count is how a silent
    composition abort is detected (a JS value write mid-composition kills
    the composition WITHOUT a `compositionend`; the next IME update then
    mints a fresh `compositionstart` — measured).
  - **`\"handler\"`** — what React hands the application's own handlers,
    recording the SAME fields off the synthetic event AND off
    `.-nativeEvent`. The dead-`isComposing` defect (a gate reading
    React's synthetic keyboard event, whose copied interface does not
    include `isComposing`) is only visible at this layer, which is why it
    exists: the harness asserts what the handler saw, not what was sent.

  The page asserts nothing itself. It records, exposes the record and the
  model over `window`, and leaves every verdict to the driver — the same
  division every bench page in this directory keeps."
  (:require [clojure.string :as str]
            [re-frame.adapter.uix :as uixa]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :refer [sub]]
            [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.core :as rf]
            [uix.core :refer [$ defui]]
            [uix.compiler.input]
            ;; Required so `uix-port` can be SELECTED rather than merely
            ;; inherited: the port reaches Reagent's after-render queue
            ;; through the `reagent.impl.batching` global, which only
            ;; exists once that namespace is loaded — and only in a dev
            ;; (`:none`) bundle, which is why ime_run.cjs builds one.
            [reagent.impl.batching]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview hfn]]))

(def frame-id ::ime)

(def fields
  "One field per policy the exchange is driven against."
  ["plain" "digits" "upper"])

(def seed-values
  "`digits` seeds non-empty so a refusal has something to stand beside."
  {"plain" "" "digits" "12" "upper" ""})

;; ---------------------------------------------------------------------------
;; The model — the controlled grid's policy family, by field name
;; ---------------------------------------------------------------------------

(defn- apply-policy
  "What the model does with a typed value. `old` is what it holds now,
  which is the whole of a rejection: the model returns itself."
  [field old v]
  (case field
    "digits" (if (re-matches #"[0-9]*" v) v old)
    "upper"  (str/upper-case v)
    v))

(rf/reg-sub :ime/cell (fn [db [_ f]] (get-in db [:cells f] "")))

(rf/reg-event :ime/seed
  (fn [_ _] {:db {:cells seed-values :committed {}}}))

(rf/reg-event :ime/edit
  (fn [{:keys [db]} [_ f v]]
    {:db (assoc-in db [:cells f]
                   (apply-policy f (get-in db [:cells f] "") v))}))

;; The commit door the IME fence exists for: a composing Enter must not
;; reach this (authoring.md's pinned case).
(rf/reg-event :ime/commit
  (fn [{:keys [db]} [_ f]]
    {:db (assoc-in db [:committed f] (get-in db [:cells f] ""))}))

;; ---------------------------------------------------------------------------
;; The probe log
;; ---------------------------------------------------------------------------

(defonce ^:private !log (atom []))

(defn- log! [layer m]
  (swap! !log conj (assoc m :layer layer))
  nil)

(defn- probe-handler-event!
  "Record what React handed a handler: the synthetic reading AND the
  native one, side by side, because the difference between them IS the
  dead-`isComposing` finding."
  [field kind ^js e]
  (let [^js native (.-nativeEvent e)]
    (log! "handler"
          {:field                field
           :type                 kind
           :key                  (.-key e)
           :syntheticIsComposing (.-isComposing e)
           :syntheticKeyCode     (.-keyCode e)
           :syntheticInputType   (.-inputType e)
           :syntheticData        (.-data e)
           :nativeIsComposing    (when native (.-isComposing native))
           :nativeKeyCode        (when native (.-keyCode native))
           :nativeInputType      (when native (.-inputType native))
           :nativeData           (when native (.-data native))
           :value                (some-> ^js (.-target e) .-value)})))

(defn- attach-native-probes!
  "The ground-truth layer: what the browser delivered to the node,
  independent of React, with the field's value and selection at delivery."
  [^js node field]
  (doseq [t ["compositionstart" "compositionupdate" "compositionend"
             "beforeinput" "input" "keydown"]]
    (.addEventListener node t
                       (fn [^js e]
                         (log! "native"
                               {:field       field
                                :type        t
                                :isComposing (.-isComposing e)
                                :inputType   (.-inputType e)
                                :data        (.-data e)
                                :key         (.-key e)
                                :keyCode     (.-keyCode e)
                                :isTrusted   (.-isTrusted e)
                                :value       (.-value node)
                                :selStart    (.-selectionStart node)
                                :selEnd      (.-selectionEnd node)}))))
  nil)

;; ---------------------------------------------------------------------------
;; The hicasso page — Arm 1's element path, converge and key-map included
;; ---------------------------------------------------------------------------

(defview ime-cell
  "One controlled cell on the arm's own authoring surface: a `:value` read
  through the ambient collector, an `:on-input` intent carrying the value
  marker — which is what makes the codec install the converge around it
  (rf2-fki5d) — and a key-map whose `\"Enter\"` branch is what the
  composition gate has to stop. The composition probes are `hfn`
  callbacks; each returns nil deliberately, because an event callback's
  returned VECTOR is dispatched."
  [{:keys [field]}]
  [:input {:id          field
           :type        "text"
           :value       (sub [:ime/cell field])
           :on-input    [:ime/edit field :re-frame.hicasso/value]
           :on-key-down {"Enter" [:ime/commit field]}
           :on-composition-start  (hfn [e] (probe-handler-event! field "compositionstart" e) nil)
           :on-composition-update (hfn [e] (probe-handler-event! field "compositionupdate" e) nil)
           :on-composition-end    (hfn [e] (probe-handler-event! field "compositionend" e) nil)}])

(defview ime-grid
  []
  (into [:div.grid]
        (map (fn [f] [ime-cell {:key f :field f}]))
        fields))

;; ---------------------------------------------------------------------------
;; The UIx page — plain React or the Reagent-port, the pin decides
;; ---------------------------------------------------------------------------

(defui ime-cell-uix
  "The same three surfaces on the UIx adapter: a controlled `:value`, an
  `:on-change` edit (the port's `input-render-setup` requires `onChange`
  to engage at all), and an Enter commit gated by the SAME
  `front.intent/composing?` the hicasso key-map is gated by — so one gate
  is witnessed through both event plumbings, reading the native event."
  [{:keys [field]}]
  (let [v (uixa/use-subscribe [:ime/cell field])
        {:keys [dispatch-sync]} (uixa/use-frame)]
    ($ :input
       {:id        field
        :type      "text"
        :value     v
        :on-change (fn [^js e]
                     (dispatch-sync [:ime/edit field (.. e -target -value)])
                     (probe-handler-event! field "change" e))
        :on-key-down (fn [^js e]
                       (probe-handler-event! field "keydown" e)
                       (when (and (= "Enter" (.-key e))
                                  (not (intent/composing? e)))
                         (dispatch-sync [:ime/commit field])))
        :on-composition-start  (fn [^js e] (probe-handler-event! field "compositionstart" e))
        :on-composition-update (fn [^js e] (probe-handler-event! field "compositionupdate" e))
        :on-composition-end    (fn [^js e] (probe-handler-event! field "compositionend" e))})))

(defui ime-grid-uix
  []
  ($ :div.grid
     (for [f fields]
       ($ ime-cell-uix {:key f :field f}))))

;; ---------------------------------------------------------------------------
;; Mounting, pinning, and the window API the driver reads
;; ---------------------------------------------------------------------------

(defn- container! []
  (let [c (js/document.createElement "div")]
    (.appendChild js/document.body c)
    c))

(defn- pin!
  "Pin the UIx input implementation for this page's lifetime. `hicasso`
  records `false` too — the codec never consults the selector, which the
  grid suite proves; recording the pin keeps this page unable to measure
  an implementation it cannot name."
  [impl]
  (set! uix.compiler.input/*use-reagent-input-enabled?*
        (= "uix-port" impl)))

(defn- mount-impl! [impl]
  (case impl
    "hicasso" (mount/root! (container!) frame-id [ime-grid {}])
    (let [c    (container!)
          root (react-dom-client/createRoot c)]
      (react-dom/flushSync
       (fn [] (.render root ($ uixa/frame-provider {:frame frame-id}
                              ($ ime-grid-uix {})))))
      {:root root :container c})))

(defn- settle! []
  (react-dom/flushSync (fn [] nil))
  nil)

(defn- state-js
  "Everything the driver asserts on, as one JSON string: the model's two
  maps, the pin, and both probe layers in arrival order."
  [impl]
  (let [db (rf/app-db-value frame-id)]
    (js/JSON.stringify
     (clj->js {:impl      impl
               :pin       (str uix.compiler.input/*use-reagent-input-enabled?*)
               :cells     (:cells db)
               :committed (:committed db)
               :log       @!log}))))

(defn ^:export -main
  []
  (try
    (let [impl (or (some-> (js/URLSearchParams. (.. js/window -location -search))
                           (.get "impl"))
                   "react")]
      (rf/init! uixa/adapter)
      (pin! impl)
      (rf/make-frame {:id frame-id :initial-events [[:ime/seed]]})
      (mount-impl! impl)
      (settle!)
      (doseq [f fields]
        (attach-native-probes! (js/document.getElementById f) f))
      (unchecked-set js/window "IME_STATE" (fn [] (state-js impl)))
      (unchecked-set js/window "IME_CLEAR_LOG" (fn [] (reset! !log []) nil))
      (unchecked-set js/window "IME_RESET"
                     (fn []
                       (rf/dispatch-sync [:ime/seed] {:frame frame-id})
                       (settle!)
                       (reset! !log [])
                       nil))
      (unchecked-set js/window "IME_READY" true))
    (catch :default e
      (unchecked-set js/window "IME_ERROR"
                     (str (ex-message e) " " (pr-str (ex-data e)))))))
