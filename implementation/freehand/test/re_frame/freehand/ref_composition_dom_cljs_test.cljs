(ns re-frame.freehand.ref-composition-dom-cljs-test
  "FH-BEHAVIOR-007 — the COMBINATION, in a real browser.

  One element, two mechanisms: a registered behavior decorating it, and a
  top-layer desired state declared on it. Both need the live node, React
  holds ONE ref per element, and before composition whichever mechanism
  wrote its ref last silently disabled the other.

  This has to mount, and it has to mount in a browser with a real top
  layer. A structural test cannot see a clobbered ref at all — the tree
  records the behavior boundary and the `:rf.ui/top-layer` fact whether or
  not either mechanism ever reached a node — so the only honest evidence
  is read back off a live `document` after a real `react-dom/client`
  commit: the behavior's own DOM write and the platform's `:popover-open`
  state, on the SAME node.

  Three claims, and each one fails loudly if a ref is clobbered in either
  direction:

    BOTH RECEIVED THE NODE   the behavior's mark is on the element the
                             browser promoted to its top layer.
    NEITHER IS CHURNED       the composed ref is fresh at every commit by
                             design, so React detaches and re-attaches the
                             whole chain — and the behavior must still hold
                             ONE connection, never a teardown and reconnect
                             per commit.
    BOTH RELEASED            after teardown the connection table, the live
                             target index and the commit batch are all
                             empty, measured against a CONTROL mount of the
                             same markup carrying NEITHER mechanism, so the
                             host's own bookkeeping cannot masquerade as
                             the substrate's.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where there is no top layer
  to promote anything into and it says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.top-layer :as top-layer]
            [re-frame.freehand.web :as web]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fx (conf/fixture :FH-BEHAVIOR-007))
(def ^:private combo (:combination fx))

(def ^:private frame-id :dom/ref-composition)

;; ---------------------------------------------------------------------------
;; The behavior — a `:layout` one, because measure-then-place beside a
;; top-layer intrinsic is the case an author actually writes.
;; ---------------------------------------------------------------------------

(def ^:private transcript (atom []))

(defn- record! [op] (swap! transcript conj op) nil)

(defn- ops [] @transcript)

(v/defbehavior measure
  "Records its lifecycle and marks the node it was handed. The mark is the
  evidence: an attribute that can only be on that element if this behavior
  received THAT node."
  {:timing     :layout
   :connect    (fn [{:keys [node]}]
                 (record! :connect)
                 (when node
                   (.setAttribute node (:attribute combo) (:value combo)))
                 nil)
   :update     (fn [_] (record! :update) nil)
   :disconnect (fn [_] (record! :disconnect) nil)})

;; ---------------------------------------------------------------------------
;; Views. Module-level — a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview composed
  "BOTH mechanisms on one element. The `:config` is constant, so any
  `:update` in the transcript is churn rather than movement; the label
  varies so React genuinely re-renders."
  [{:keys [menu-id open? label]}]
  [:div
   [:button#opener "Open"]
   [v/behavior {:use measure :target :composed/menu :config {:role "menu"}}
    [:div {:id                 menu-id
           :popover            :auto
           ::web/popover-open? open?
           :on-toggle          identity}
     (str "Account " label)]]])

(v/defview control
  "The CONTROL: the same markup with NEITHER mechanism — no behavior and
  no desired state. Measured first, so a zero after teardown is the
  substrate's release rather than a counter that was never written."
  [{:keys [menu-id label]}]
  [:div
   [:button#opener "Open"]
   [:div {:id menu-id :popover :auto} (str "Account " label)]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- browser?
  "A real top layer, not merely a DOM — `showPopover` is the capability
  half of this file's evidence rests on."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))
       (some? (.-showPopover (.-prototype js/HTMLElement)))))

(defn- skip! [why]
  (is true (str "a real browser top layer is required — " why)))

(defn- act
  "A React 19 `act` boundary as a promise, so assertions run after the
  commit, its flushed effects, AND the microtask checkpoint the top-layer
  batch drains on — never racing any of them."
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (.unmount root)
  (.remove container)
  nil)

(defn- element [form]
  (shell/provide-frame frame-id (fr/element form)))

(defn- by-id [id] (js/document.getElementById id))

(defn- popover-open? [id] (some-> (by-id id) (.matches ":popover-open")))

(defn- marked [id] (some-> (by-id id) (.getAttribute (:attribute combo))))

(defn- setup! []
  (behaviors/reset-connections!)
  (reset! transcript [])
  (live-frame/make-frame {:id frame-id})
  nil)

;; ===========================================================================
;; FH-BEHAVIOR-007 — the combination
;; ===========================================================================

(deftest fh-behavior-007-a-behavior-and-a-top-layer-intrinsic-share-one-node
  (testing "Per FH-BEHAVIOR-007: an element may carry a behavior and a
            top-layer desired state at once — the two are orthogonal in
            intent, and a popover an author cannot attach a behavior to
            would read as a substrate bug rather than as a rule. Both refs
            must therefore reach the SAME node: the behavior's mark is
            asserted on the very element the browser promoted to its top
            layer, so a clobber in EITHER direction fails here."
    (if-not (browser?)
      (skip! "the browser job owns the combination")
      (async done
        (setup!)
        (let [{:keys [menu-id target renders]} combo
              [control-container control-root] (mount!)
              [container root]                 (mount!)
              render  (fn [open?* label]
                        (act #(.render root (element [composed {:menu-id menu-id
                                                                :open?   open?*
                                                                :label   label}]))))
              counted (atom 0)]
          (-> (act #(.render control-root
                             (element [control {:menu-id menu-id :label "c"}])))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle pending]} (:control fx)]
                         (is (= connections (behaviors/connection-count))
                             "the CONTROL mount connects nothing")
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= lifecycle (ops)))
                         (is (= pending (top-layer/pending-count))))
                       (is (some? (by-id menu-id))
                           "and it really mounted the same element")
                       (is (false? (popover-open? menu-id))
                           "which no desired state opened")
                       (is (nil? (marked menu-id))
                           "and no behavior marked")
                       (act #(teardown! control-container control-root))))

              ;; The combination, closed. The behavior connects at this
              ;; commit; the desired state matches the node's live state,
              ;; so the top layer has nothing to do yet.
              (.then (fn [_]
                       (reset! counted (top-layer/operation-count))
                       (render false "a")))
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle]} (:mounted fx)]
                         (is (= connections (behaviors/connection-count)))
                         (is (= (set targets) (behaviors/target-ids)))
                         (is (= target (first (behaviors/target-ids))))
                         (is (= lifecycle (ops))))
                       (is (= (:value combo) (marked menu-id))
                           "the BEHAVIOR received the node — its own DOM write is on it")
                       (is (false? (popover-open? menu-id))
                           "and the desired state still says closed")
                       (is (= 0 (- (top-layer/operation-count) @counted))
                           "so the top layer performed no operation at all")
                       (reset! counted (top-layer/operation-count))
                       (render true "b")))

              ;; The same node, opened. This is the assertion the hazard
              ;; is about: with a clobbering ref the popover never opens.
              (.then (fn [_]
                       (is (true? (popover-open? menu-id))
                           "the TOP LAYER received that same node — showPopover() ran on it")
                       (is (= (:value combo) (marked menu-id))
                           "and the behavior's mark is on the very element now promoted")
                       (is (= 1 (- (top-layer/operation-count) @counted))
                           "exactly one show")
                       (is (= 1 (behaviors/connection-count))
                           "with the behavior still holding its one connection")
                       (reset! counted (top-layer/operation-count))
                       ;; N further commits. The composed ref is fresh at
                       ;; each one by design, so React detaches and
                       ;; re-attaches the whole chain every time.
                       (reduce (fn [p n] (.then p (fn [_] (render true (str "r" n)))))
                               (js/Promise.resolve nil)
                               (range renders))))
              (.then (fn [_]
                       (is (= (:lifecycle (:mounted fx)) (ops))
                           (str renders " re-commits neither re-connected the behavior "
                                "nor moved its config"))
                       (is (= 1 (behaviors/connection-count))
                           "one connection, not one per commit")
                       (is (= 0 (- (top-layer/operation-count) @counted))
                           "and an unchanged desired state cost no host operation")
                       (is (true? (popover-open? menu-id))
                           "with the popover still open throughout")
                       (act #(teardown! container root))))

              ;; Release is TOTAL — both participants, measured against the
              ;; control's numbers.
              (.then (fn [_]
                       (let [{:keys [connections targets lifecycle pending]}
                             (:after-teardown fx)]
                         (is (= connections (behaviors/connection-count))
                             "after teardown the connection table is EMPTY")
                         (is (= (set targets) (behaviors/target-ids))
                             "the live target index holds nothing")
                         (is (= lifecycle (ops))
                             "and :disconnect ran exactly once")
                         (is (= pending (top-layer/pending-count))
                             "with the commit batch drained"))
                       (is (nil? (by-id menu-id))
                           "and the node left the document with both of them")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time. Nothing to hoist: both
              ;; teardowns are `act`ed mid-chain, as the steps under test.
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))
