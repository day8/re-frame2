(ns day8.re-frame2-xray.views.edn-inspector-popup-wireup-cljs-test
  "Wire-up tests for the edn-inspector popup affordance + shell mount.

  ## What's under test

  1. **Widget-level affordance** — when `[ei/edn-inspector value
     {:popup-affordance? true …}]` is mounted, the rendered hiccup
     carries a `:button` node with the canonical testid
     `rf-xray-edn-inspector-popup-affordance-ddp-<mount-id>`. The
     button's on-click dispatches
     `[:rf.xray.edn-inspector-popup/open …]` through the supplied
     dispatch-fn with the widget's value + a sanitised opts map
     (re-entry into the popup's own edn-inspector does NOT re-enable
     the affordance).
  2. **Opt-in default off** — without `:popup-affordance?` (or with
     `false`) the widget renders NO affordance button.
  3. **Shell mount** — `edn-inspector-popup-stack` is a Fresco
     boundary, and the stack it renders is the surrounding frame's.
     Its empty-stack gate and per-entry chrome are pinned by the popup
     ns's stack-view row and the browser-lane
     `edn-inspector-popup-stack-boundary-dom-cljs-test`, which also pins
     the frame its reads resolve through.
  4. **Registry install** — `registry.cljs` calls
     `edn-inspector-popup/install!` so the open/close events resolve
     through `rf/dispatch-sync` post-registration; `registry_cljs_test`'s
     registry snapshot pins the popup event and sub ids it registers.

  Driving the on-click through a captured dispatch-fn stub avoids the
  router's `next-tick` drain in node-test mode — the affordance's
  contract is the event vector it dispatches, not the router round-
  trip (the popup ns's own tests + the registry snapshot cover
  that)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-inspector-popup :as edn-inspector-popup]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` folds
  ;; `make-reset-runtime-fixture` + the `reset-all!` init into one owner:
  ;; plain-atom adapter + the default `:all` reset tier.
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
;; widget-level affordance — pure hiccup shape
;; =========================================================================

(deftest popup-affordance-on-renders-a-labelled-button-anchored-to-the-container
  (testing "`[ei/edn-inspector value {:popup-affordance? true}]`
            renders a top-right ↗ icon button (↗ reads as 'open in new
            pane'). Its testid includes the per-mount popup id
            (`ddp-<mount-id>`) so panel-level tests can target it, and the
            outer container carries `position: relative` so the absolute-
            positioned button anchors correctly"
    (let [h         (invoke-edn-inspector
                      {:cart [1 2 3]}
                      {:panel-id :rf.xray/app-db :popup-affordance? true})
          btn       (find-affordance h)
          mount-id  (:data-rf-mount-id (second h))
          by-testid (find-by-testid
                      h (str "rf-xray-edn-inspector-popup-affordance-ddp-" mount-id))]
      (is (some? btn)
          "affordance button is present in the rendered hiccup")
      (is (= "Open in popup" (:aria-label (second btn)))
          "button carries the canonical aria-label")
      (is (fn? (:on-click (second btn)))
          "button carries an on-click handler")
      (is (= "↗" (last btn))
          "glyph is ↗ (north-east arrow)")
      (is (some? mount-id) "container has a mount-id")
      (is (some? by-testid) "button found by the expected testid")
      (is (= (str "ddp-" mount-id)
             (:data-rf-popup-mount-id (second by-testid)))
          "button surfaces the popup-mount-id as a data attr too")
      (is (= "relative" (-> h second :style :position))
          "affordance-on → outer container is position: relative"))))

(deftest popup-affordance-off-renders-no-button-and-no-positioning
  (testing "without `:popup-affordance?` (or with `false`)
            the widget renders NO affordance button, and the outer
            container carries no positioning"
    (let [h-default (invoke-edn-inspector
                     {:cart [1 2 3]}
                     {:panel-id :rf.xray/app-db})
          h-false   (invoke-edn-inspector
                     {:cart [1 2 3]}
                     {:panel-id :rf.xray/app-db
                      :popup-affordance? false})]
      (is (nil? (find-affordance h-default))
          "no affordance when opt is absent")
      (is (nil? (find-affordance h-false))
          "no affordance when opt is explicitly false")
      (is (nil? (-> h-default second :style :position))
          "affordance-off → no positioning (no descendant uses absolute)"))))

;; =========================================================================
;; widget-level affordance — on-click dispatch contract via stub
;; =========================================================================

(defn- with-captured-dispatch-spy
  "Drive a click against a captured-dispatcher STUB passed in as the
  `popup-affordance-button`'s `dispatch-fn` arg, so the test can inspect
  the dispatched event vector WITHOUT spinning up the router. Returns
  the captured event vector.

  `popup-affordance-button` dispatches
  through the SUPPLIED frame-aware dispatcher (the one the surrounding
  `reg-view` body captured via `(:dispatch (rf/capture-frame))`), NOT a bare
  `rf/dispatch` with a `{:frame :rf/xray}` literal. The dispatcher
  closure binds the instance frame at render time, so this stub
  stands in for it; the affordance's contract is the single-arg event
  vector it hands the dispatcher."
  [make-btn]
  (let [captured (atom nil)
        spy      (fn [ev] (reset! captured ev))
        btn      (make-btn spy)
        on-click (:on-click (second btn))]
    (on-click nil)
    {:event @captured :btn btn}))

(deftest popup-affordance-button-onclick-dispatches-through-captured-dispatcher
  (testing "clicking the affordance dispatches
            `[:rf.xray.edn-inspector-popup/open popup-mount-id payload]`
            through the SUPPLIED frame-aware dispatcher (captured by the
            surrounding reg-view at render time), so the popup-open write
            lands on the instance frame — no `{:frame :rf/xray}` literal,
            so N shells stay isolated"
    (let [{:keys [event]}
          (with-captured-dispatch-spy
            (fn [spy]
              (ei/popup-affordance-button
                spy
                "ddp-abc"
                {:cart [1 2 3]}
                {:panel-id :rf.xray/app-db :default-expanded-depth 3
                 :popup-affordance? true})))
          [event-id mount-id payload] event]
      (is (= :rf.xray.edn-inspector-popup/open event-id)
          "canonical event id")
      (is (= "ddp-abc" mount-id)
          "popup-mount-id flows through as second positional arg")
      (is (= {:cart [1 2 3]} (:value payload))
          "value flows through as `:value`")
      (is (false? (:popup-affordance? (:opts payload)))
          "the popup's embedded edn-inspector does NOT re-enable the
           affordance (no recursion)")
      (is (= :rf.xray/app-db (:panel-id (:opts payload)))
          "other opts (`:panel-id`, `:default-expanded-depth`) survive")
      (is (= 3 (:default-expanded-depth (:opts payload))))
      ;; The dispatch goes through the captured dispatcher's
      ;; SINGLE-ARG form (the spy is `(fn [ev] …)`); the frame is baked
      ;; into the dispatcher closure, NOT passed as a `{:frame …}` opts
      ;; literal at the call site. The event vector is all the affordance
      ;; hands the dispatcher.
      (is (= 3 (count event))
          "dispatch is a single-arg event vector; the frame
           is captured in the dispatcher closure, not a `{:frame :rf/xray}`
           literal at the call site"))))

(deftest popup-affordance-button-onclick-preserves-nil-opts
  (testing "when the caller supplies no opts, the affordance
            still produces a sane payload (just `:popup-affordance? false`
            in the popup's downstream opts map)"
    (let [{:keys [event]}
          (with-captured-dispatch-spy
            (fn [spy] (ei/popup-affordance-button spy "ddp-x" {:k :v} nil)))
          payload (nth event 2)]
      (is (= {:k :v} (:value payload)))
      (is (false? (:popup-affordance? (:opts payload)))
          "even with nil opts the recursion guard fires"))))

;; =========================================================================
;; shell mount — `edn-inspector-popup-stack` view
;; =========================================================================

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; =========================================================================
;; frame context
;; =========================================================================
;;
;; A plain Reagent `defn` is a substrate-level Reagent component: it does
;; not carry the `:rf/frame` React context that `reg-view` wires up, so
;; its body's `rf/subscribe` calls cannot see the frame the component is
;; mounted under (the shell mounts the stack under `:rf/xray`). Such a
;; plain `defn` RAISES `:rf.error/no-frame-context` (Spec 006 §Plain-fn
;; footgun).
;;
;; `edn-inspector-popup-stack` is an `rf.fresco/defview` BOUNDARY: a
;; boundary reads its frame from the same `re-frame.adapter.context`
;; React context `reg-view` consults, so the reads resolve through the
;; surrounding `:rf/xray`. The observer is Fresco's collector rather than
;; the installed adapter's.
;;
;; So the row below pins that property one layer down, where it
;; bites: a `reg-view` head and a plain `defn` head grade
;; `:invalid` down the IDENTICAL codec arm, so once the shell root is a
;; boundary either one is a hard failure rather than a degradation. The
;; assertion is that the stack view, and the head it embeds the value
;; under, both grade `:boundary`.

(deftest popup-stack-view-is-a-fresco-boundary
  (testing "`edn-inspector-popup-stack-view` is an
            `rf.fresco/defview` boundary, so a Fresco shell root
            mounts it rather than refusing it. An `rf/reg-view` (or a
            plain `defn`) head grades `:invalid` down the same codec
            arm and reds this row — which is the point, since such a
            head is silent under Reagent."
    (setup-xray-frame!)
    (is (= :boundary
           (rf.fresco.impl.codec/head-kind
             edn-inspector-popup/edn-inspector-popup-stack-view))
        "the stack view is a Fresco boundary")
    (is (= :boundary
           (rf.fresco.impl.codec/head-kind
             (first (edn-inspector-popup/fresco-inspector
                      "m1" {:a 1} {}))))
        "the head the boundary embeds the inspected value under is a
         boundary too — a head-free subtree all the way down")
    (is (= :invalid
           (rf.fresco.impl.codec/head-kind
             (first (edn-inspector-popup/reagent-inspector
                      "m1" {:a 1} {}))))
        "and the Reagent head grades :invalid, so the row above is
         not vacuous")))
