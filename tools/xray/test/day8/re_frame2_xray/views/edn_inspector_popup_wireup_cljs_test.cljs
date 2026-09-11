(ns day8.re-frame2-xray.views.edn-inspector-popup-wireup-cljs-test
  "Wire-up tests for the edn-inspector popup affordance + shell mount
  (rf2-l4625, follow-on from phase 6 / rf2-s0x6x).

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
  3. **Shell mount** — `edn-inspector-popup-stack` short-circuits to
     nil when the stack is empty and renders the chrome for every
     active mount-id when the stack is non-empty.
  4. **Registry install** — `registry.cljs` calls
     `edn-inspector-popup/install!` so the open/close events resolve
     through `rf/dispatch-sync` post-registration.

  Driving the on-click through a captured dispatch-fn stub avoids the
  router's `next-tick` drain in node-test mode — the affordance's
  contract is the event vector it dispatches, not the router round-
  trip (that's already covered by the popup ns's own tests + the
  registry-wiring test below)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-inspector-popup :as edn-inspector-popup]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the inline
  ;; `make-reset-runtime-fixture` + `reset-all!` init into one owner:
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

(deftest popup-affordance-opt-in-renders-button
  (testing "rf2-l4625 — `[ei/edn-inspector value {:popup-affordance? true}]`
            renders a top-right ↗ icon button (rf2-7sdja — glyph was
            ⊕; ↗ reads as 'open in new pane')"
    (let [h (invoke-edn-inspector
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
          "no affordance when opt is explicitly false"))))

(deftest popup-affordance-button-carries-stable-testid
  (testing "rf2-l4625 — the button testid includes the per-mount popup
            id (`ddp-<mount-id>`) so panel-level tests can target it"
    (let [outer (ei/edn-inspector {:a 1}
                                 {:panel-id :rf.xray/app-db
                                  :popup-affordance? true})
          inner-h (outer {:a 1} {:panel-id :rf.xray/app-db
                                 :popup-affordance? true})
          container (second inner-h)
          mount-id  (:data-rf-mount-id container)
          expected-testid
          (str "rf-xray-edn-inspector-popup-affordance-ddp-" mount-id)
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
    (let [h-on  (invoke-edn-inspector
                  {:a 1} {:panel-id :rf.xray/app-db
                          :popup-affordance? true})
          h-off (invoke-edn-inspector
                  {:a 1} {:panel-id :rf.xray/app-db})]
      (is (= "relative" (-> h-on second :style :position))
          "affordance-on → outer container is position: relative")
      (is (nil? (-> h-off second :style :position))
          "affordance-off → no positioning (no descendant uses absolute)"))))

;; =========================================================================
;; widget-level affordance — on-click dispatch contract via stub
;; =========================================================================

(defn- with-captured-dispatch-spy
  "Drive a click against a captured-dispatcher STUB passed in as the
  `popup-affordance-button`'s `dispatch-fn` arg, so the test can inspect
  the dispatched event vector WITHOUT spinning up the router. Returns
  the captured event vector.

  Per rf2-r0o63 — the post-fix `popup-affordance-button` dispatches
  through the SUPPLIED frame-aware dispatcher (the one the surrounding
  `reg-view` body captured via `(:dispatch (rf/capture-frame))`), NOT a bare
  `rf/dispatch` with a `{:frame :rf/xray}` literal. The dispatcher
  closure already bound the instance frame at render time, so this stub
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
  (testing "rf2-r0o63 — clicking the affordance dispatches
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
      ;; rf2-r0o63 — the dispatch goes through the captured dispatcher's
      ;; SINGLE-ARG form (the spy is `(fn [ev] …)`); the frame is baked
      ;; into the dispatcher closure, NOT passed as a `{:frame …}` opts
      ;; literal at the call site. The event vector is all the affordance
      ;; hands the dispatcher.
      (is (= 3 (count event))
          "rf2-r0o63 — dispatch is a single-arg event vector; the frame
           is captured in the dispatcher closure, not a `{:frame :rf/xray}`
           literal at the call site"))))

(deftest popup-affordance-button-onclick-preserves-nil-opts
  (testing "rf2-l4625 — when the caller supplies no opts, the affordance
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

;; rf2-k97c.3 — `edn-inspector-popup-stack` is now an
;; `rf.fresco/as-component` bridge and answers an interop vector, not a
;; tree to walk. `popup-stack-tree` below reproduces the boundary's gate
;; and reads EXACTLY — same gate, same order, same query vectors — so the
;; rows in this section assert on the hiccup they always did, and a
;; boundary that stopped reading one of these slots would diverge from
;; this helper rather than silently agreeing with it.
;;
;; Ambient `rf/subscribe` deliberately: these rows run under
;; `rf/with-frame :rf/xray` in the node lane with no React commit. What
;; the boundary's own read resolves to — the frame React context names —
;; is the browser lane's subject.

(defn- popup-stack-tree
  "What calling the stack view directly returned before the migration:
  nil while the stack is empty, the container otherwise."
  []
  (let [stack @(rf/subscribe [edn-inspector-popup/stack-slot])]
    (when (seq stack)
      (edn-inspector-popup/popup-stack-tree
        {:stack       stack
         :entries     @(rf/subscribe [edn-inspector-popup/entries-slot])
         :positioning @(rf/subscribe [:rf.xray/modal-positioning])}))))

(deftest popup-stack-view-empty-when-stack-empty
  (setup-xray-frame!)
  (rf/with-frame :rf/xray
    (let [tree (popup-stack-tree)]
      (is (nil? tree)
          "stack view returns nil when no popups are open (closed-state
           cost is one subscribe + a when-gate)"))))

(deftest popup-stack-view-renders-active-popup
  (testing "rf2-l4625 — when one popup is open the stack view renders
            its chrome (backdrop / dialog / body)"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.edn-inspector-popup/open
         "m1" {:value {:foo :bar}
               :opts  {:title "Inspect cart"}}])
      (let [tree (popup-stack-tree)]
        (is (some? tree) "stack view returns hiccup when stack non-empty")
        (is (some? (find-by-testid tree "rf-xray-edn-inspector-popup-stack"))
            "outer stack container present")
        (is (some? (find-by-testid tree
                                   "rf-xray-edn-inspector-popup-backdrop-m1"))
            "popup chrome rendered for m1")))))

(deftest popup-stack-view-renders-each-active-mount
  (testing "rf2-l4625 — every mount-id on the stack gets a chrome"
    (setup-xray-frame!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                         "m1" {:value 1 :opts {}}])
      (rf/dispatch-sync [:rf.xray.edn-inspector-popup/open
                         "m2" {:value 2 :opts {}}])
      (let [tree (popup-stack-tree)]
        (is (some? (find-by-testid tree
                                   "rf-xray-edn-inspector-popup-backdrop-m1")))
        (is (some? (find-by-testid tree
                                   "rf-xray-edn-inspector-popup-backdrop-m2")))))))

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
        [:rf.xray.edn-inspector-popup/open
         "smoke" {:value :hi :opts {:title "Smoke"}}])
      (let [stack @(rf/subscribe [edn-inspector-popup/stack-slot])]
        (is (= ["smoke"] stack)
            "open event resolved through the registry-installed handler")))))

;; =========================================================================
;; rf2-1yif8 — frame-context regression
;; =========================================================================
;;
;; Before rf2-1yif8 `edn-inspector-popup-stack` was a plain Reagent `defn`.
;; Plain fns are substrate-level Reagent components: they do not carry the
;; `:rf/frame` React-context that `reg-view` automatically wires up, so the
;; body's `rf/subscribe` calls could not see the frame the component was
;; mounted under (the shell mounts the stack under `:rf/xray`). On the
;; runtime of the day that meant a silent fall-through to `:rf/default`,
;; flagged by `:rf.warning/plain-fn-under-non-default-frame-once`. BOTH
;; HALVES OF THAT SENTENCE ARE HISTORY: EP-0002 removed the `:rf/default`
;; floor and retired the warning, so the same plain `defn` today RAISES
;; `:rf.error/no-frame-context` instead (Spec 006 §Plain-fn footgun).
;;
;; rf2-1yif8's fix registered the symbol via `rf/reg-view`. rf2-k97c.3
;; replaced that with an `rf.fresco/defview` BOUNDARY, and the frame
;; reasoning is unchanged: a boundary reads its frame from the same
;; `re-frame.adapter.context` React context `reg-view` consulted, so the
;; reads still resolve through the surrounding `:rf/xray`. What moved is
;; the observer — Fresco's collector rather than the installed adapter's.
;;
;; So the row below pins the same property one layer down, and pins it
;; where it now bites: a `reg-view` head and a plain `defn` head grade
;; `:invalid` down the IDENTICAL codec arm, so once the shell root is a
;; boundary either one is a hard failure rather than a degradation. The
;; assertion is that the stack view, and the head it embeds the value
;; under, both grade `:boundary`.

(deftest popup-stack-view-is-a-fresco-boundary
  (testing "rf2-k97c.3 — `edn-inspector-popup-stack-view` is an
            `rf.fresco/defview` boundary, so the shell root becoming a
            Fresco tree mounts it rather than refusing it. A revert to
            `rf/reg-view` (or to a plain `defn`) grades `:invalid` down
            the same codec arm and reds this row — which is the point,
            since that revert is silent under Reagent."
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
        "and the Reagent head it replaced grades :invalid, so the row
         above is not vacuous")))

(deftest popup-stack-view-subscribes-route-to-surrounding-frame
  (testing "rf2-1yif8 — when the stack view is rendered under `:rf/xray`,
            its subscribes read `:rf/xray`'s app-db, NOT `:rf/default`.
            We open a popup in `:rf/xray` and confirm the rendered chrome
            reflects `:rf/xray`'s stack; a popup written into
            `:rf/default` MUST NOT leak in.
            A plain-fn regression cannot read the surrounding frame at
            all: under EP-0002 it raises `:rf.error/no-frame-context`
            and this row's tree never renders — that is the bug this
            test pins, and on the pre-EP-0002 runtime it presented as
            `:rf/default`'s entry rendering instead."
    (setup-xray-frame!)
    ;; `:rf.xray/modal-positioning` is already registered by
    ;; `register-xray-handlers!` inside `setup-xray-frame!`.
    ;; Seed contradicting data in :rf/default + :rf/xray. If the
    ;; subscribes read :rf/default, the test would see the
    ;; "default-only" mount-id; the correct routing sees "xray-only".
    (rf/dispatch-sync
      [:rf.xray.edn-inspector-popup/open
       "default-only" {:value :default-payload :opts {}}])
    (rf/with-frame :rf/xray
      (rf/dispatch-sync
        [:rf.xray.edn-inspector-popup/open
         "xray-only" {:value :xray-payload :opts {}}]))
    ;; Drive the stack under :rf/xray. Since rf2-k97c.3 the view is a
    ;; Fresco boundary, whose body may only run inside a React render
    ;; window, so the node lane reads the slots itself and hands them to
    ;; the pure `popup-stack-tree` — the same slots in the same order the
    ;; boundary reads. Under `with-frame :rf/xray` those ambient reads
    ;; resolve through the dynamic-var tier; what the BOUNDARY's own read
    ;; resolves to is the React-context tier and the browser lane's
    ;; subject. Either way the property pinned here is the same one: the
    ;; stack rendered under :rf/xray shows :rf/xray's entries and not
    ;; :rf/default's.
    (rf/with-frame :rf/xray
      (let [tree (popup-stack-tree)]
        (is (some? tree)
            "stack view rendered some chrome under :rf/xray (proves :rf/xray's
             stack slot is non-empty from the view's perspective)")
        (is (some? (find-by-testid tree
                                   "rf-xray-edn-inspector-popup-backdrop-xray-only"))
            "the :rf/xray-frame's mount-id `xray-only` was picked up by the
             view's subscribe — proves the subscribe routed to :rf/xray,
             not :rf/default")
        (is (nil? (find-by-testid tree
                                  "rf-xray-edn-inspector-popup-backdrop-default-only"))
            "the :rf/default-frame's mount-id `default-only` was NOT
             visible to the view — proves the subscribe did not leak across
             frames"))
      ;; And the stack's count attribute reflects :rf/xray's stack
      ;; depth exclusively (one entry), not the combined two.
      (let [tree (popup-stack-tree)]
        (is (= 1 (-> tree second :data-rf-popup-count))
            "popup-count reflects :rf/xray's stack only (one entry), not
             the two-entry total across frames")))))
