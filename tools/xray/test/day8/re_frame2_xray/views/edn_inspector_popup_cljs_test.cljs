(ns day8.re-frame2-xray.views.edn-inspector-popup-cljs-test
  "The edn-inspector popup overlay, pure-data: the z-index tiers, the
  open/close events over the stack and entries slots, `popup-chrome`'s
  hiccup and close affordances, and the opts it forwards to the embedded
  widget. The stack boundary's real React commit, and the ✕ button's real
  click, are `edn-inspector-popup-stack-boundary-dom-cljs-test`'s."
  (:require [cljs.test :refer-macros [are deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.views.edn-inspector-popup :as edn-inspector-popup]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---- helpers -------------------------------------------------------------

(defn- walk-hiccup
  "Depth-first collect every hiccup vector in `tree`."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (vector? node)
                (do (swap! out conj node)
                    (doseq [child (rest node)] (walk child)))
                (seq? node) (doseq [c node] (walk c))))]
      (walk tree))
    @out))

(defn- find-attr
  "Return the first node whose attribute-map key `k` equals `v`."
  [tree k v]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= v (get (second n) k)))))
       first))

(defn- chrome
  "`popup-chrome` for mount `m1`."
  ([opts] (chrome opts :fixed))
  ([opts positioning]
   (edn-inspector-popup/popup-chrome
     {:mount-id    "m1"
      :value       42
      :opts        opts
      :positioning positioning
      :stack-pos   0})))

(defn- node-attrs
  "The attribute map of `m1`'s popup node named `part` (title, dialog, backdrop …)."
  [tree part]
  (second (find-attr tree :data-testid (str "rf-xray-edn-inspector-popup-" part "-m1"))))

;; =========================================================================
;; z-index tiers
;; =========================================================================

(deftest z-index-for-layers-popups-above-the-base-tier
  ;; The base tier sits below the palette and settings modals; each stack
  ;; position paints one above the last.
  (are [expected pos] (= expected (edn-inspector-popup/z-index-for pos))
    2147483640 0
    2147483641 1))

;; =========================================================================
;; open / close events
;; =========================================================================

(deftest open-and-close-events-maintain-the-stack-and-entries
  (edn-inspector-popup/install!)
  (let [open!  (fn [id payload]
                 (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open id payload]))
        state  (fn []
                 [@(rf/subscribe [edn-inspector-popup/stack-slot])
                  @(rf/subscribe [edn-inspector-popup/entries-slot])])]
    (open! "m1" {:value 1 :opts {}})
    (open! "m2" {:value 2 :opts {}})
    (open! "m3" {:value 3 :opts {}})
    (open! "m1" {:value 99 :opts {:title "raised"}})
    (is (= [["m2" "m3" "m1"]
            {"m1" {:value 99 :opts {:title "raised"}}
             "m2" {:value 2 :opts {}}
             "m3" {:value 3 :opts {}}}]
           (state))
        "re-opening an id raises it to the top and replaces its payload")
    (rf/dispatch-sync [:rf.xray.edn-inspector-popup/close "m2"])
    (is (= [["m3" "m1"]
            {"m1" {:value 99 :opts {:title "raised"}}
             "m3" {:value 3 :opts {}}}]
           (state))
        ":close removes exactly that popup")
    (rf/dispatch-sync [:rf.xray.edn-inspector-popup/close-top])
    (is (= [["m3"] {"m3" {:value 3 :opts {}}}] (state))
        ":close-top removes only the topmost")))

;; =========================================================================
;; popup-chrome
;; =========================================================================

(deftest popup-chrome-title-labels-the-dialog
  ;; The header echoes the caller's `:title`, defaulting to "Inspect", and
  ;; the dialog names that header as its accessible label.
  (are [opts title] (= title (last (find-attr (chrome opts) :data-testid
                                              "rf-xray-edn-inspector-popup-title-m1")))
    {:title "Custom title"} "Custom title"
    {}                      "Inspect")
  (is (= "rf-xray-edn-inspector-popup-title-m1"
         (:aria-labelledby (node-attrs (chrome {}) "dialog")))))

(deftest popup-chrome-respects-modal-positioning
  ;; `:absolute` confines the backdrop to the parent cell (Story testbed);
  ;; `:fixed` spans the viewport in production.
  (are [positioning expected]
       (= expected ((juxt (comp :position :style) :data-rf-xray-modal-positioning)
                    (node-attrs (chrome {} positioning) "backdrop")))
    :absolute ["absolute" "absolute"]
    :fixed    ["fixed" "fixed"]))

;; =========================================================================
;; close affordances
;; =========================================================================

(deftest close-fn-uses-caller-on-close-when-supplied
  (let [called (atom 0)]
    ((edn-inspector-popup/close-fn "m1" {:on-close #(swap! called inc)}))
    (is (= 1 @called))))

(deftest backdrop-on-click-closes-via-handler
  (let [captured (atom nil)
        on-click (:on-click (node-attrs (chrome {}) "backdrop"))]
    (with-redefs [rf/dispatch-impl (fn [event-v & _]
                                     (reset! captured event-v))]
      (on-click #js {:stopPropagation (fn [])})
      (is (= [:rf.xray.edn-inspector-popup/close "m1"] @captured)))))

(deftest handle-keydown-closes-the-top-popup-on-escape-only
  ;; Esc → :close-top, so the topmost popup closes and layered popups
  ;; beneath survive. Any other key dispatches nothing and bubbles to the
  ;; global keybindings.
  (doseq [[k expected] [["Escape" [:rf.xray.edn-inspector-popup/close-top]]
                        ["Enter"  nil]]]
    (let [captured (atom nil)]
      (with-redefs [rf/dispatch-impl (fn [event-v & _]
                                       (reset! captured event-v))]
        (edn-inspector-popup/handle-keydown
          #js {:key k
               :preventDefault  (fn [])
               :stopPropagation (fn [])})
        (is (= expected @captured)
            (str k " dispatches " (pr-str expected)))))))

;; =========================================================================
;; the opts the embedded widget receives
;; =========================================================================

(defn- forwarded-opts
  "The opts map `popup-chrome` hands the embedded widget, captured through
  its 3-arg `:inspector` head seam."
  [opts]
  (let [captured (atom ::never-called)]
    (edn-inspector-popup/popup-chrome
      {:mount-id    "m1"
       :value       {:foo :bar}
       :opts        opts
       :positioning :fixed
       :stack-pos   0
       :inspector   (fn [_mount-id _value widget-opts]
                      (reset! captured widget-opts)
                      [:span "stub"])})
    @captured))

(deftest popup-forwards-its-opts-to-the-widget
  ;; Every caller opt reaches the widget, so a value popped out because it
  ;; was cramped keeps the inline mount's affordances — except `:panel-id`,
  ;; REPLACED by one derived from the mount-id, and `:site-id`, DROPPED
  ;; because the widget keys expansion on `(or site-id mount-id)`: both keep
  ;; the popup's expansion state off the mount it was opened from. A popup
  ;; never offers to open itself in a popup, and adds no
  ;; `:default-expanded-depth`, so the widget applies its own ceiling.
  (are [opts expected] (= expected (forwarded-opts opts))
    nil
    {:panel-id :rf.xray.edn-inspector-popup/anon-m1 :popup-affordance? false}

    {:zoomable? true :card? true :header "Payload" :added? true
     :max-inline-width 120 :default-expanded-depth 3
     :panel-id :rf.xray/app-db :site-id "app-db-top" :popup-affordance? true}
    {:zoomable? true :card? true :header "Payload" :added? true
     :max-inline-width 120 :default-expanded-depth 3
     :panel-id :rf.xray.edn-inspector-popup/app-db-m1 :popup-affordance? false}))
