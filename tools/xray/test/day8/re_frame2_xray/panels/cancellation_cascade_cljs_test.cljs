(ns day8.re-frame2-xray.panels.cancellation-cascade-cljs-test
  "CLJS-side wiring and view tests for Xray's Cancellation-cascade
  visualiser: the side-panel and popover composites, the row jump's tab,
  modal positioning, the dialog's Esc handler, and the row keys React
  receives. The view's hiccup is walked by `data-testid` rather than
  mounted; `cancellation_cascade_fresco_boundary_dom_cljs_test` mounts the
  boundary for real."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            ;; The key assertions read the element the codec BUILDS, so the
            ;; codec itself is the instrument.
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cc]))

;; ---- fixtures -----------------------------------------------------------

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture))

;; ---- hiccup walkers -----------------------------------------------------

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (apply (first node) (rest node))
    node))

(defn- hiccup-seq [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-fn-component tree))
       (map expand-fn-component)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

;; ---- the two views, as trees ----------------------------------------------
;;
;; `cc/SidePanel` and `cc/Popover` are `as-component` bridges, not trees to
;; walk. These helpers reproduce each boundary's gate and reads — same
;; order, same query vectors — over `cc/render-cascade` / `cc/popover-tree`,
;; through the ambient `rf/subscribe` of `rf/with-frame :rf/xray`.

(defn- side-panel-tree []
  (let [cascade @(rf/subscribe [:rf.xray/cancellation-cascade-for-focused-machine])]
    (when-not (= :no-trigger (:empty-kind cascade))
      (cc/render-cascade
        cascade nil @(rf/subscribe [:rf.xray/cancellation-cascade-expanded?])))))

(defn- popover-tree []
  (when @(rf/subscribe [:rf.xray/cancellation-cascade-popover-open?])
    (cc/popover-tree
      {:cascade     @(rf/subscribe [:rf.xray/cancellation-cascade-for-focused-event])
       :positioning @(rf/subscribe [:rf.xray/modal-positioning])
       :expanded?   @(rf/subscribe [:rf.xray/cancellation-cascade-expanded?])})))

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- seed-trace! [events]
  (rf/dispatch-sync [:rf.xray/sync-trace-buffer (vec events)]))

(def ^:private cancel-cascade-buffer
  "One decision + one cancellation-anchor + two HTTP aborts."
  [{:id 1 :operation :rf.event/dispatched :op-type :rf.event
    :time 1000
    :tags {:rf.event/v [:auth/logout] :rf.trace/dispatch-id 7 :frame :rf/default}}
   {:id 2 :operation :rf.machine/destroyed :op-type :rf.machine
    :time 1010
    :tags {:machine-id :user-session :reason :explicit
           :rf.trace/dispatch-id 7 :frame :rf/default}}
   {:id 3 :operation :rf.http/aborted-on-actor-destroy :op-type :rf.http
    :severity :info :time 1020
    :tags {:request-id :r1 :url "/api/profile" :actor-id :user-session
           :rf.trace/dispatch-id 7 :frame :rf/default}}
   {:id 4 :operation :rf.http/aborted-on-actor-destroy :op-type :rf.http
    :severity :info :time 1021
    :tags {:request-id :r2 :url "/api/log" :actor-id :user-session
           :rf.trace/dispatch-id 7 :frame :rf/default}}])

;; ---- renders --------------------------------------------------------------

(deftest popover-renders-empty-state-when-open-with-no-cascade
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
    (is (some? (find-by-testid (popover-tree)
                               "rf-xray-cancellation-cascade-empty-no-trigger")))))

(deftest side-panel-renders-when-machine-cascade-present
  ;; The side panel's composite folds the cascade for the SELECTED machine.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (seed-trace! cancel-cascade-buffer)
    (rf/dispatch-sync [:rf.xray/select-machine-id :user-session])
    (is (= 2 (count (find-all-by-testid-prefix
                      (side-panel-tree) "rf-xray-cancellation-cascade-abort-row-"))))))

;; ---- the row jump -------------------------------------------------------

(deftest focus-trace-entry-lands-on-a-live-tab
  ;; `:epoch` is the live Dynamic tab the `:rf.xray/select-dispatch-id` pin
  ;; drives; an unregistered id would land the shell's unknown-tab stub.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (seed-trace! cancel-cascade-buffer)
    (rf/dispatch-sync [:rf.xray/focus-trace-entry
                       {:dispatch-id 7 :frame :rf/default :trace-id 1}])
    (is (= :epoch @(rf/subscribe [:rf.xray/selected-tab])))))

;; ---- modal positioning --------------------------------------------------

(deftest popover-backdrop-defaults-to-fixed-positioning
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
    (let [[_ attrs] (find-by-testid (popover-tree)
                                    "rf-xray-cancellation-cascade-popover-backdrop")]
      (is (= ["fixed" 2147483644 "fixed"]
             [(:position (:style attrs))
              (:z-index (:style attrs))
              (:data-rf-xray-modal-positioning attrs)])))))

(deftest popover-backdrop-honours-absolute-positioning
  ;; Story testbeds confine the backdrop to the shell cell.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
    (rf/dispatch-sync [:rf.xray/set-modal-positioning :absolute])
    (let [[_ attrs] (find-by-testid (popover-tree)
                                    "rf-xray-cancellation-cascade-popover-backdrop")]
      (is (= ["absolute" "absolute"]
             [(:position (:style attrs))
              (:data-rf-xray-modal-positioning attrs)]))
      (is (< (:z-index (:style attrs)) 1000)))))

;; ---- dialog Esc keydown -------------------------------------------------

(defn- fake-keydown-event
  "Stand-in for a React keydown SyntheticEvent."
  [key]
  #js {:key             key
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(deftest popover-dialog-esc-keydown-dispatches-close
  ;; The dialog's `:on-key-down` must be the BUILT handler: React would call
  ;; the bare 1-arity builder with the event and discard the fn it returns,
  ;; and the focus trap keeps a dialog-focused Esc from the backdrop.
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/cancellation-cascade-open nil])
    (let [on-key   (-> (find-by-testid (popover-tree)
                                       "rf-xray-cancellation-cascade-popover-dialog")
                       second
                       :on-key-down)
          captured (atom nil)]
      (with-redefs [rf/dispatch-impl (fn [event-v & _] (reset! captured event-v))]
        (on-key (fake-keydown-event "Enter"))
        (is (nil? @captured) "any other key dispatches nothing")
        (on-key (fake-keydown-event "Escape"))
        (is (= [:rf.xray/cancellation-cascade-close] @captured))))))

;; ---- row keys -----------------------------------------------------------
;;
;; Fresco's codec reads `:key` off the ATTRIBUTE MAP and Clojure metadata
;; nowhere, so a meta-only key reaches React as no key at all. These rows
;; read the key off the element the codec builds, walking the raw tree so
;; each container's child seq stays intact at index 2.

(defn- meta-preserving-children [node]
  (cond
    (and (vector? node) (fn? (first node)))
    [(apply (first node) (rest node))]

    (vector? node)
    (if (map? (second node))
      (drop 2 node)
      (rest node))

    (seq? node)
    node

    :else nil))

(defn- raw-find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (tree-seq (some-fn vector? seq?) meta-preserving-children tree)))

(defn- emitted-key
  "The key REACT sees for one row, through the codec's own hiccup→element
  door."
  [row]
  (.-key (rf.fresco.impl.codec/as-element row)))

(defn- emitted-row-keys
  "Emitted React keys for every row vector inside a `[:div attrs <seq>]`
  container."
  [container]
  (->> (nth container 2)
       (filter vector?)
       (mapv emitted-key)))

(defn- cascade-row-containers
  "Render the popover over `buffer` and hand back the two row containers."
  [buffer]
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (seed-trace! buffer)
    (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                       {:kind :dispatch-id :id 7}])
    (let [tree (popover-tree)]
      {:teardowns (raw-find-by-testid tree "rf-xray-cancellation-cascade-teardowns")
       :aborts    (raw-find-by-testid tree "rf-xray-cancellation-cascade-aborts")})))

(deftest cascade-body-rows-emit-react-keys
  ;; Trace-id-derived keys, so a row keeps its identity when an earlier row
  ;; leaves the list.
  (let [{:keys [teardowns aborts]} (cascade-row-containers cancel-cascade-buffer)]
    (is (= ["teardown-2"] (emitted-row-keys teardowns)))
    (is (= ["abort-3" "abort-4"] (emitted-row-keys aborts)))))
