(ns day8.re-frame2-xray.views.edn-inspector-popup-wireup-cljs-test
  "The widget's opt-in \"open in popup\" affordance: the button `:popup-
  affordance? true` renders, its absence by default, and the open event
  its click hands the mount's captured dispatcher. The stack it opens into
  is `edn-inspector-popup-stack-boundary-dom-cljs-test`'s."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture))

;; ---- helpers ------------------------------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-affordance
  "Walk the hiccup tree and return the first popup-affordance button,
  or nil."
  [tree]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= "popup" (:data-rf-affordance (second node))))
            node))
        (hiccup-seq tree)))

(defn- invoke-edn-inspector
  "Form-2 unrolling — call the outer fn, then call the inner fn with
  the same args to get the rendered hiccup."
  [value opts]
  (let [outer (ei/edn-inspector value opts)]
    (outer value opts)))

;; =========================================================================
;; the affordance button
;; =========================================================================

(deftest popup-affordance-on-renders-a-labelled-button-anchored-to-the-container
  ;; The testid carries the per-mount popup id (`ddp-<mount-id>`) so a
  ;; panel's tests can target it, and the container is `position: relative`
  ;; so the absolutely-positioned ↗ button anchors to its top-right.
  (let [h        (invoke-edn-inspector
                   {:cart [1 2 3]}
                   {:panel-id :rf.xray/app-db :popup-affordance? true})
        mount-id (:data-rf-mount-id (second h))
        btn      (find-by-testid
                   h (str "rf-xray-edn-inspector-popup-affordance-ddp-" mount-id))]
    (is (= ["popup" "Open in popup" "↗" "relative"]
           [(:data-rf-affordance (second btn))
            (:aria-label (second btn))
            (last btn)
            (-> h second :style :position)]))))

(deftest popup-affordance-is-off-by-default
  (is (nil? (find-affordance (invoke-edn-inspector {:cart [1 2 3]}
                                                   {:panel-id :rf.xray/app-db})))))

(deftest popup-affordance-button-onclick-dispatches-through-captured-dispatcher
  ;; The click hands the mount's frame-bound dispatcher a bare event vector —
  ;; the frame lives in that closure, so N shells stay isolated — and the
  ;; popup's own embedded inspector does not re-offer the affordance.
  (let [captured (atom nil)
        btn      (ei/popup-affordance-button
                   #(reset! captured %)
                   "ddp-abc"
                   {:cart [1 2 3]}
                   {:panel-id :rf.xray/app-db :default-expanded-depth 3
                    :popup-affordance? true})]
    ((:on-click (second btn)) nil)
    (is (= [:rf.xray.edn-inspector-popup/open "ddp-abc"
            {:value {:cart [1 2 3]}
             :opts  {:panel-id :rf.xray/app-db :default-expanded-depth 3
                     :popup-affordance? false}}]
           @captured))))
