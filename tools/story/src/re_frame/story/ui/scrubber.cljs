(ns re-frame.story.ui.scrubber
  "Time-travel scrubber. Per Stage 4 (rf2-ekai) IMPL-SPEC §9.1.

  Reads the variant frame's `epoch-history` (implementation/epoch/) and
  exposes a slider that scrubs forward/back through epochs. Releasing
  the slider triggers `restore-epoch` against the frame.

  Pinned snapshots: clicking 'capture' emits a labelled marker into the
  shell state's `:pinned-snapshots` slot — the markers are rendered as
  chips alongside the scrubber and clicking a chip restores to its
  epoch.

  ## Listener wiring

  The scrubber registers an epoch listener via
  `re-frame.epoch/register-epoch-cb!` so the slider knows to redraw when
  new epochs commit. The listener is keyed by variant id and torn down
  when the shell unmounts.

  ## Elision

  Epoch history itself sits inside `interop/debug-enabled?` (spec/009);
  the scrubber's mount call is additionally gated by `enabled?`. In a
  production CLJS build both flags are false, and the scrubber's `panel`
  fn is unreachable from any call site (shell entry is gated)."
  (:require [reagent.core :as r]
            [re-frame.epoch :as epoch]
            [re-frame.story.config :as config]
            [re-frame.story.ui.state :as state]))

;; ---- per-variant 'live history' ratom ------------------------------------

(defonce histories
  ;; {variant-id → r/atom holding the latest epoch-history vector}
  (atom {}))

(defn- ensure-history-atom!
  [variant-id]
  (or (get @histories variant-id)
      (let [a (r/atom (epoch/epoch-history variant-id))]
        (swap! histories assoc variant-id a)
        a)))

(defn- refresh-history!
  "Re-read the framework's epoch history into the local ratom."
  [variant-id]
  (let [a (ensure-history-atom! variant-id)]
    (reset! a (epoch/epoch-history variant-id))))

(defn drop-history!
  "Remove the per-variant atom. Called from shell unmount."
  [variant-id]
  (swap! histories dissoc variant-id)
  nil)

;; ---- the epoch callback --------------------------------------------------

(defn- cb-id [variant-id]
  (keyword "re-frame.story.ui.scrubber"
           (str "epoch-cb-" (when variant-id (str variant-id)))))

(defn register-listener!
  "Install an epoch-cb that refreshes the local ratom on every settled
  epoch for the variant. Idempotent."
  [variant-id]
  (when config/enabled?
    (epoch/register-epoch-cb! (cb-id variant-id)
      (fn [record]
        (when (= variant-id (:frame record))
          (refresh-history! variant-id))))))

(defn remove-listener!
  "Tear down the epoch listener for `variant-id`. Idempotent."
  [variant-id]
  (when config/enabled?
    (epoch/remove-epoch-cb! (cb-id variant-id))
    nil))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:panel       {:padding "8px"
                 :font-family "monospace"
                 :font-size "11px"
                 :border-top "1px solid #444"
                 :background "#252526"
                 :color "#ddd"}
   :title       {:font-weight "bold"
                 :margin-bottom "6px"
                 :color "#9cdcfe"}
   :slider-row  {:display "flex"
                 :align-items "center"
                 :gap "8px"}
   :slider      {:flex "1"}
   :label       {:min-width "80px"
                 :color "#888"}
   :chip-row    {:display "flex"
                 :flex-wrap "wrap"
                 :gap "4px"
                 :margin-top "6px"}
   :chip        {:padding "2px 6px"
                 :background "#37373d"
                 :color "#9cdcfe"
                 :border-radius "10px"
                 :cursor "pointer"
                 :font-size "10px"}
   :button      {:padding "2px 8px"
                 :background "#0e639c"
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"}
   :empty       {:color "#666" :font-style "italic"}})

;; ---- public component ----------------------------------------------------

(defn- snapshot-label
  "Build a default label for a pinned snapshot — uses the epoch's
  trigger event when available."
  [history idx]
  (let [record (get history idx)
        ev     (:trigger-event record)]
    (str "epoch-" idx (when ev (str " " (pr-str (first ev)))))))

(defn panel
  "The scrubber component. Renders a slider over the variant frame's
  epoch history, a 'capture' button that pins the current epoch, and a
  chip row of pinned snapshots."
  [variant-id]
  (let [history-atom (ensure-history-atom! variant-id)
        ;; Local UI state for the slider position — separate from the
        ;; framework's epoch history so the user can scrub without
        ;; committing until they release.
        slider-pos   (r/atom nil)]
    (fn [variant-id]
      (let [history (or @history-atom [])
            n       (count history)
            shell   @state/shell-state-atom
            pinned  (get-in shell [:pinned-snapshots variant-id] [])
            pos     (or @slider-pos (dec n) 0)]
        [:div {:style (:panel styles)}
         [:div {:style (:title styles)}
          "Time travel " (when variant-id (str (pr-str variant-id)))
          " — " n " epochs"]
         (if (zero? n)
           [:div {:style (:empty styles)}
            "no epochs recorded yet — dispatch an event to capture an epoch"]
           [:div
            [:div {:style (:slider-row styles)}
             [:span {:style (:label styles)} (str "epoch " pos "/" (dec n))]
             [:input {:type      "range"
                      :min       0
                      :max       (max 0 (dec n))
                      :value     pos
                      :style     (:slider styles)
                      :on-change (fn [e]
                                   (reset! slider-pos
                                           (js/parseInt
                                             (.. e -target -value))))
                      :on-mouse-up
                                 (fn [e]
                                   (let [idx (js/parseInt
                                               (.. e -target -value))]
                                     (when-let [record (get history idx)]
                                       (epoch/restore-epoch
                                         variant-id
                                         (:epoch-id record)))))}]
             [:button {:style    (:button styles)
                       :on-click (fn [_]
                                   (let [record (get history pos)
                                         label  (snapshot-label history pos)]
                                     (when record
                                       (state/swap-state!
                                         state/pin-snapshot
                                         variant-id label
                                         (:epoch-id record)))))}
              "capture"]]
            (when (seq pinned)
              [:div {:style (:chip-row styles)}
               (for [{:keys [label epoch-id]} pinned]
                 ^{:key (str label "-" epoch-id)}
                 [:span {:style    (:chip styles)
                         :on-click (fn [_]
                                     (epoch/restore-epoch variant-id epoch-id))}
                  label])])])]))))
