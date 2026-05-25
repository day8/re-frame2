(ns day8.re-frame2-xray.views.data-display-popup-wireup-cljs-test
  "Wire-up tests for the data-display popup affordance + shell mount
  (rf2-l4625, follow-on from phase 6 / rf2-s0x6x).

  ## What's under test

  1. **Widget-level affordance** — when `[dd/data-display value
     {:popup-affordance? true …}]` is mounted, the rendered hiccup
     carries a `:button` node with the canonical testid
     `rf-xray-data-display-popup-affordance-ddp-<mount-id>`. The
     button's on-click dispatches
     `[:rf.xray.data-display-popup/open …]` through the supplied
     dispatch-fn with the widget's value + a sanitised opts map
     (re-entry into the popup's own data-display does NOT re-enable
     the affordance).
  2. **Opt-in default off** — without `:popup-affordance?` (or with
     `false`) the widget renders NO affordance button.
  3. **Shell mount** — `data-display-popup-stack` short-circuits to
     nil when the stack is empty and renders the chrome for every
     active mount-id when the stack is non-empty.
  4. **Registry install** — `registry.cljs` calls
     `data-display-popup/install!` so the open/close events resolve
     through `rf/dispatch-sync` post-registration.

  Driving the on-click through a captured dispatch-fn stub avoids the
  router's `next-tick` drain in node-test mode — the affordance's
  contract is the event vector it dispatches, not the router round-
  trip (that's already covered by the popup ns's own tests + the
  registry-wiring test below)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.data-display :as dd]
            [day8.re-frame2-xray.views.data-display-popup :as ddp]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (xray-test-support/reset-all!))}))

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

(defn- invoke-data-display
  "Form-2 unrolling — call the outer fn, then call the inner fn with
  the same args to get the rendered hiccup."
  [value opts]
  (let [outer (dd/data-display value opts)]
    (outer value opts)))

;; =========================================================================
;; widget-level affordance — pure hiccup shape
;; =========================================================================

(deftest popup-affordance-opt-in-renders-button
  (testing "rf2-l4625 — `[dd/data-display value {:popup-affordance? true}]`
            renders a top-right ↗ icon button (rf2-7sdja — glyph was
            ⊕; ↗ reads as 'open in new pane')"
    (let [h (invoke-data-display
              {:cart [1 2 3]}
              {:panel-id :rf.xray/app-db :popup-affordance? true})
          btn (find-affordance h)]
      (is (some? btn)
          "affordance button is present in the rendered hiccup")
      (is (= "Open in popup" (:aria-label (second btn)))
          "button carries the canonical aria-label")
      (is (fn? (:on-click (second btn)))
          "button carries an on-click handler")
      (is (= "↗" (last btn))
          "rf2-7sdja — glyph is ↗ (north-east arrow), not ⊕"))))

(deftest popup-affordance-default-off
  (testing "rf2-l4625 — without `:popup-affordance?` (or with `false`)
            the widget renders NO affordance button"
    (let [h-default (invoke-data-display
                     {:cart [1 2 3]}
                     {:panel-id :rf.xray/app-db})
          h-false   (invoke-data-display
                     {:cart [1 2 3]}
                     {:panel-id :rf.xray/app-db
                      :popup-affordance? false})]
      (is (nil? (find-affordance h-default))
          "no affordance when opt is absent")
      (is (nil? (find-affordance h-false))
          "no affordance when opt is explicitly false"))))

(deftest popup-affordance-button-carries-stable-testid
  (testing "rf2-l4625 — the button testid includes the per-mount popup
            id (`ddp-<mount-id>`) so panel-level tests can target it"
    (let [outer (dd/data-display {:a 1}
                                 {:panel-id :rf.xray/app-db
                                  :popup-affordance? true})
          inner-h (outer {:a 1} {:panel-id :rf.xray/app-db
                                 :popup-affordance? true})
          container (second inner-h)
          mount-id  (:data-rf-mount-id container)
          expected-testid
          (str "rf-xray-data-display-popup-affordance-ddp-" mount-id)
          btn (find-by-testid inner-h expected-testid)]
      (is (some? mount-id) "container has a mount-id")
      (is (some? btn) "button found by the expected testid")
      (is (= (str "ddp-" mount-id)
             (:data-rf-popup-mount-id (second btn)))
          "button surfaces the popup-mount-id as a data attr too"))))

(deftest popup-affordance-button-contributes-positioning-context
  (testing "rf2-l4625 — when the affordance is enabled the outer
            container carries `position: relative` so the absolute-
            positioned button anchors correctly"
    (let [h-on  (invoke-data-display
                  {:a 1} {:panel-id :rf.xray/app-db
                          :popup-affordance? true})
          h-off (invoke-data-display
                  {:a 1} {:panel-id :rf.xray/app-db})]
      (is (= "relative" (-> h-on second :style :position))
          "affordance-on → outer container is position: relative")
      (is (nil? (-> h-off second :style :position))
          "affordance-off → no positioning (no descendant uses absolute)"))))

;; =========================================================================
;; widget-level affordance — on-click dispatch contract via stub
;; =========================================================================

(defn- with-rf-dispatch-spy
  "Drive a click against a stubbed `rf/dispatch*` (the fn-form the
  `dispatch` macro expands to) so the test can inspect the dispatched
  event vector + opts WITHOUT spinning up the router. Returns
  `{:event ... :opts ...}` captured at click. Per rf2-7sdja — the
  post-fix `popup-affordance-button` calls `rf/dispatch` directly with
  `{:frame :rf/xray}` opts (not the lexically-captured dispatch-fn)."
  [on-click]
  (let [captured (atom nil)]
    (with-redefs [rf/dispatch* (fn
                                 ([ev]      (reset! captured {:event ev :opts nil}))
                                 ([ev opts] (reset! captured {:event ev :opts opts})))]
      (on-click nil))
    @captured))

(deftest popup-affordance-button-onclick-dispatches-against-xray-frame
  (testing "rf2-7sdja — clicking the affordance dispatches
            `[:rf.xray.data-display-popup/open popup-mount-id payload]`
            against `:rf/xray` EXPLICITLY (popup state is Xray-global,
            not per-frame — pinned via `{:frame :rf/xray}` opts)"
    (let [btn (dd/popup-affordance-button
                ;; `dispatch-fn` parameter is a no-op post rf2-7sdja —
                ;; pass nil to make the new contract obvious.
                nil
                "ddp-abc"
                {:cart [1 2 3]}
                {:panel-id :rf.xray/app-db :default-expanded-depth 3
                 :popup-affordance? true})
          on-click (:on-click (second btn))
          {:keys [event opts]} (with-rf-dispatch-spy on-click)
          [event-id mount-id payload] event]
      (is (= :rf.xray.data-display-popup/open event-id)
          "canonical event id")
      (is (= "ddp-abc" mount-id)
          "popup-mount-id flows through as second positional arg")
      (is (= {:cart [1 2 3]} (:value payload))
          "value flows through as `:value`")
      (is (false? (:popup-affordance? (:opts payload)))
          "the popup's embedded data-display does NOT re-enable the
           affordance (no recursion)")
      (is (= :rf.xray/app-db (:panel-id (:opts payload)))
          "other opts (`:panel-id`, `:default-expanded-depth`) survive")
      (is (= 3 (:default-expanded-depth (:opts payload))))
      (is (= :rf/xray (:frame opts))
          "rf2-7sdja — popup dispatch pins frame to `:rf/xray`
           explicitly so the popup-stack-view (which only subscribes
           against `:rf/xray`) sees the write regardless of which
           frame the widget is mounted under"))))

(deftest popup-affordance-button-onclick-preserves-nil-opts
  (testing "rf2-l4625 — when the caller supplies no opts, the affordance
            still produces a sane payload (just `:popup-affordance? false`
            in the popup's downstream opts map)"
    (let [btn (dd/popup-affordance-button nil "ddp-x" {:k :v} nil)
          on-click (:on-click (second btn))
          {:keys [event opts]} (with-rf-dispatch-spy on-click)
          payload (nth event 2)]
      (is (= {:k :v} (:value payload)))
      (is (false? (:popup-affordance? (:opts payload)))
          "even with nil opts the recursion guard fires")
      (is (= :rf/xray (:frame opts))
          "rf2-7sdja — `:rf/xray` frame still pinned even with nil opts"))))

;; =========================================================================
;; shell mount — `data-display-popup-stack` view
;; =========================================================================

(defn- setup-xray-frame! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

(deftest popup-stack-view-empty-when-stack-empty
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [tree (ddp/data-display-popup-stack)]
      (is (nil? tree)
          "stack view returns nil when no popups are open (closed-state
           cost is one subscribe + a when-gate)"))))

(deftest popup-stack-view-renders-active-popup
  (testing "rf2-l4625 — when one popup is open the stack view renders
            its chrome (backdrop / dialog / body)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.data-display-popup/open
         "m1" {:value {:foo :bar}
               :opts  {:title "Inspect cart"}}])
      (let [tree (ddp/data-display-popup-stack)]
        (is (some? tree) "stack view returns hiccup when stack non-empty")
        (is (some? (find-by-testid tree "rf-xray-data-display-popup-stack"))
            "outer stack container present")
        (is (some? (find-by-testid tree
                                   "rf-xray-data-display-popup-backdrop-m1"))
            "popup chrome rendered for m1")))))

(deftest popup-stack-view-renders-each-active-mount
  (testing "rf2-l4625 — every mount-id on the stack gets a chrome"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.data-display-popup/open
                         "m1" {:value 1 :opts {}}])
      (rf/dispatch-sync [:rf.xray.data-display-popup/open
                         "m2" {:value 2 :opts {}}])
      (let [tree (ddp/data-display-popup-stack)]
        (is (some? (find-by-testid tree
                                   "rf-xray-data-display-popup-backdrop-m1")))
        (is (some? (find-by-testid tree
                                   "rf-xray-data-display-popup-backdrop-m2")))))))

;; =========================================================================
;; registry wiring
;; =========================================================================

(deftest registry-wires-popup-handlers
  (testing "rf2-l4625 — `register-xray-handlers!` installs the popup
            events so `:open` lands a real entry without a separate
            install call from a test fixture"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.data-display-popup/open
         "smoke" {:value :hi :opts {:title "Smoke"}}])
      (let [stack @(rf/subscribe [ddp/stack-slot])]
        (is (= ["smoke"] stack)
            "open event resolved through the registry-installed handler")))))
