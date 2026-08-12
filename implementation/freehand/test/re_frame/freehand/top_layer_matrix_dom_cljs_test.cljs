(ns re-frame.freehand.top-layer-matrix-dom-cljs-test
  "F6b matrix 3/8 — DOM TOP LAYER reconciliation and layout timing, in a
  real browser, across BOTH execution modes (EP-0036 §6, gate row
  \"browser correctness\").

  A qualified `::web/popover-open?` / `::web/modal-open?` is a DESIRED
  STATE the substrate reconciles against the element's real top-layer
  membership — `showPopover` / `showModal` and their inverses — inside the
  commit, before the browser paints. A structural tree can only record
  what was ASKED FOR; whether the element actually joined the top layer is
  a browser question, and whether it did so at layout time rather than a
  frame later is a timing question. So this mounts and asks the real top
  layer.

  The matrix dimension is mode. The top-layer intrinsics are recognised at
  `node/element`, reached by both emitters, so each claim — a popover
  promoted to the real top layer at layout time, a modal opened as a real
  `:modal` dialog, and reconciliation that FOLLOWS a flipped desired state
  — is asserted interpreted AND compiled, and the two build the same
  element.

  Guarded on `showPopover`: a real top layer, not merely a DOM. Rides the
  browser lane through its `-dom-cljs-test` suffix; under node it has no
  top layer and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.web :as web]
            [re-frame.live-frame :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fid :matrix.top-layer/frame)
(def ^:private menu-id "matrix-menu")
(def ^:private modal-id "matrix-modal")

;; ---------------------------------------------------------------------------
;; Twins — a popover and a modal, each parametrised by its desired state so
;; a reconcile is a re-render with a flipped prop.
;; ---------------------------------------------------------------------------

(v/defview popover-interpreted
  [{:keys [open?]}]
  [:div {:id                    menu-id
         :popover               :auto
         ::web/popover-open?    open?
         :on-toggle             [:matrix.top-layer/toggled]}
   "Account"])

(v/defview popover-compiled
  {:compiled true}
  [{:keys [open?]}]
  [:div {:id                    menu-id
         :popover               :auto
         ::web/popover-open?    open?
         :on-toggle             [:matrix.top-layer/toggled]}
   "Account"])

(v/defview modal-interpreted
  [{:keys [open?]}]
  [:dialog {:id                menu-id
            ::web/modal-open?  open?
            :on-close          [:matrix.top-layer/closed]}
   [:p "Delete this?"]])

(v/defview modal-compiled
  {:compiled true}
  [{:keys [open?]}]
  [:dialog {:id                menu-id
            ::web/modal-open?  open?
            :on-close          [:matrix.top-layer/closed]}
   [:p "Delete this?"]])

(def ^:private popover-modes
  [["interpreted" popover-interpreted]
   ["compiled"    popover-compiled]])

(def ^:private modal-modes
  [["interpreted" modal-interpreted]
   ["compiled"    modal-compiled]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- top-layer? []
  (some? (.-showPopover (.-prototype js/HTMLElement))))

(defn- setup! []
  (live-frame/make-frame {:id fid})
  (rf/reg-event :matrix.top-layer/toggled (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :matrix.top-layer/closed  (fn [{:keys [db]} _] {:db db}))
  fid)

(defn- render! [root view open?]
  (ms/act #(.render root (shell/provide-frame fid (fr/element [view {:open? open?}])))))

(defn- el [container] (.querySelector container (str "#" menu-id)))
(defn- popover-open? [container] (.matches (el container) ":popover-open"))
(defn- modal-open? [container] (.matches (el container) ":modal"))

;; ===========================================================================
;; Row 1 — both lowerings build the same top-layer element
;; ===========================================================================

(deftest top-layer-matrix-both-modes-build-the-same-popover-element
  (testing "The popover element — its `popover` mode, its id, its text —
            is the SAME real DOM in each mode. The desired-state property is
            a reserved fact the tree carries, not an authored attribute, so
            it does not appear on the element; what appears is the ordinary
            markup, and it must match."
    (if-not (and (ms/browser?) (top-layer?))
      (ms/skip! "the browser job runs the top-layer assertions")
      (async done
        (setup!)
        (let [[ci ri] (ms/create-root!)
              [cc rc] (ms/create-root!)]
          (-> (render! ri popover-interpreted false)
              (.then (fn [_] (render! rc popover-compiled false)))
              (.then (fn [_]
                       (ms/outlines-agree? (el ci) (el cc) "popover element")
                       (is (= "auto" (.getAttribute (el ci) "popover"))
                           "non-vacuous: it really is an auto popover")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "a popover mount rejected: " e)) nil))
              ;; Both arms tore both roots down identically, so the teardown
              ;; rides the single trailing step: written once, run once per path.
              (.then (fn [_] (ms/destroy-root! ci ri) (ms/destroy-root! cc rc) (done)))))))))

;; ===========================================================================
;; Row 2 — a popover promotes to the real top layer at layout time
;; ===========================================================================

(deftest top-layer-matrix-a-popover-promotes-at-layout-time-in-both-modes
  (testing "A popover whose desired state is open joins the real top layer
            — `:popover-open` is true the instant the commit's act boundary
            resolves, before any extra task, which is the layout-time
            reconcile the intrinsic promises — while a closed desired state
            leaves it out. Both are asserted, so a view that always opened
            (or never did) fails. In each mode."
    (if-not (and (ms/browser?) (top-layer?))
      (ms/skip! "the browser job runs the promotion assertions")
      (async done
        (ms/each-mode
          popover-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view false)
                  (.then (fn [_]
                           (is (false? (popover-open? container))
                               (str label ": a closed desired state stays out of the top layer"))
                           (render! root view true)))
                  (.then (fn [_]
                           (is (true? (popover-open? container))
                               (str label ": an open desired state joined the top layer at layout time"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 3 — reconciliation follows a flipped desired state
;; ===========================================================================

(deftest top-layer-matrix-reconciliation-follows-the-desired-state-in-both-modes
  (testing "The element's top-layer membership FOLLOWS its declared desired
            state across re-renders: open, then closed, then open again —
            the substrate reconciles each flip rather than latching. In each
            mode."
    (if-not (and (ms/browser?) (top-layer?))
      (ms/skip! "the browser job runs the reconciliation assertions")
      (async done
        (ms/each-mode
          popover-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view true)
                  (.then (fn [_]
                           (is (true? (popover-open? container)) (str label ": opened"))
                           (render! root view false)))
                  (.then (fn [_]
                           (is (false? (popover-open? container))
                               (str label ": a closed desired state reconciled it OUT"))
                           (render! root view true)))
                  (.then (fn [_]
                           (is (true? (popover-open? container))
                               (str label ": and an open desired state reconciled it back IN"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 4 — a modal opens as a real :modal dialog
;; ===========================================================================

(deftest top-layer-matrix-a-modal-opens-and-closes-in-both-modes
  (testing "A `<dialog>` whose desired state is open is a real MODAL — it
            matches `:modal` and reports `open` — and a closed desired state
            reconciles it shut. A modal is a distinct top-layer intrinsic
            from a popover, so it earns its own row. In each mode."
    (if-not (and (ms/browser?) (top-layer?))
      (ms/skip! "the browser job runs the modal assertions")
      (async done
        (ms/each-mode
          modal-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view true)
                  (.then (fn [_]
                           (is (true? (.-open (el container))) (str label ": the dialog reports open"))
                           (is (true? (modal-open? container))
                               (str label ": and it is a real :modal dialog in the top layer"))
                           (render! root view false)))
                  (.then (fn [_]
                           (is (false? (.-open (el container)))
                               (str label ": a closed desired state reconciled the modal shut"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))
