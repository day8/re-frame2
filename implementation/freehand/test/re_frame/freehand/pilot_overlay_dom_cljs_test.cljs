(ns re-frame.freehand.pilot-overlay-dom-cljs-test
  "CASE B, the MOUNTED half — the claims the structural rows cannot make.

  `pilot-overlay-cljs-test` proves what the dropdown MEANS: its state, its
  intents, its placement inputs, its dismissal policy. None of that can
  answer the questions CASE B actually exists for, because none of them is
  in the tree:

  - does the panel land at the anchor's box BEFORE the first paint?
  - does it escape a clipping, TRANSFORMED ancestor — the arrangement that
    defeats `position: fixed` emulation?
  - does a modal take focus, inert the page, and give focus back?
  - does a nested pair stack, and does dismissing the inner leave the
    outer up?
  - and after a full open/close/unmount cycle, is the retained
    listener/observer/timer count actually ZERO?

  Every claim below is read back off a live `document` after a real
  `react-dom/client` commit, and the counts are exact integers measured
  against a CONTROL mount of the same markup with no overlay at all — so
  React's own bookkeeping cannot be mistaken for the library's.

  One row here is not a proof of the pilot but a REPRODUCTION: an element
  carrying both a behavior and a top-layer desired state loses one of the
  two refs. The pilot's own composition never asks for such an element,
  and this row is the evidence that the avoidance was necessary rather
  than decorative.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where it has no top layer to
  drive and says so rather than passing quietly."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.pilot-overlay :as ui]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.top-layer :as top-layer]
            [re-frame.freehand.web :as web]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fid :dom/pilot-overlay)
(def ^:private doc-id :doc-1)
(def ^:private address [:toolbar doc-id :format])
(def ^:private k [ui/dropdown-kind address])

(defn- browser?
  "A real top layer, not merely a DOM."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))
       (some? (.-showPopover (.-prototype js/HTMLElement)))))

(defn- skip! [why]
  (is true (str "a real browser top layer is required — " why)))

(defn- act [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- next-task
  "A macrotask boundary — the platform queues a popover's `toggle` event as
  a task rather than firing it synchronously."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 0))))

;; ---------------------------------------------------------------------------
;; Views. Module-level: a declared view cannot close over a test's locals.
;; ---------------------------------------------------------------------------

(v/defview plain-toolbar
  "The CONTROL MOUNT: the same shape with no overlay at all, so React's own
  listener bookkeeping can be subtracted from the measurement."
  [_]
  [:div {:id "plain-toolbar"}
   [:button {:type "button"} "Export as…"]
   [:div {:id "plain-panel"} "PDF"]])

(v/defview collided
  "THE REPRODUCTION (rf2-drpa3.118). One element carrying BOTH a registered
  behavior and a top-layer desired state.

  A behavior attaches through `cloneElement` with its own ref; the top
  layer installs its idempotent host call on a ref of its own. React keeps
  one `ref` prop per element, so whichever is applied last silently wins
  and the other never runs. Nothing warns."
  [{:keys [open?]}]
  [:div
   [v/behavior {:use    ui/anchor-box
                :target :collision/probe
                :config {:open? open?}}
    [:div {:id                 "collided"
           :popover            :auto
           ::web/popover-open? open?
           :on-toggle          [:probe/noted]}
     "menu"]]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- mount! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    [container (rdc/createRoot container)]))

(defn- teardown! [container root]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
  (.unmount root)
  (.remove container)
  nil)

(defn- setup! []
  (behaviors/reset-connections!)
  (top-layer/reset-operation-count!)
  (live-frame/make-frame {:id fid})
  (ui/register!)
  (ui/register-app!)
  (rf/reg-event :probe/noted (fn [db _] db))
  (frame/replace-app-db! fid {})
  nil)

(defn- element [form] (shell/provide-frame fid (fr/element form)))

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- by-id [id] (js/document.getElementById id))
(defn- open? [id] (some-> (by-id id) (.matches ":popover-open")))
(defn- modal? [id] (some-> (by-id id) (.matches ":modal")))
(defn- rect [id] (.getBoundingClientRect (by-id id)))
(defn- record [] (get-in (frame/frame-app-db-value fid) [ui/records-root k]))

(defn- anchor-id [] (str "acme-dropdown-format-" (name doc-id) "-anchor"))
(defn- panel-id  [] (str "acme-dropdown-format-" (name doc-id) "-panel"))

;; ===========================================================================
;; R-B1 / R-B2 — measured before paint, and outside the clipping ancestor
;; ===========================================================================

(deftest r-b1-the-panel-is-already-at-the-anchors-box-at-the-first-frame
  (testing "R-B1 (mounted). The measurement runs at `:layout` — before the
            browser paints — so the panel is at the anchor's box in the
            FIRST frame after the commit that opened it. There is no
            -10000px park and no opacity-0 pass, because there is no wrong
            position to hide.

            The callback is scheduled BEFORE the opening commit, so what it
            sees is what the first paint would have shown."
    (if-not (browser?)
      (skip! "the browser job runs the placement assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [] (act #(.render root (element [ui/toolbar {:id doc-id}]))))
              seen   (atom nil)]
          (-> (render)
              (.then (fn [_]
                       (is (false? (open? (panel-id))) "closed before anything is asked")
                       (let [painted (js/Promise.
                                       (fn [resolve]
                                         (js/requestAnimationFrame
                                           (fn []
                                             (reset! seen
                                                     {:open? (open? (panel-id))
                                                      :panel (rect (panel-id))
                                                      :anchor (rect (anchor-id))})
                                             (resolve nil)))))]
                         (send! [:acme.ui.dropdown/anchor-clicked k])
                         (.then (render) (fn [_] painted)))))
              (.then (fn [_]
                       (let [{:keys [open? panel anchor]} @seen]
                         (is (true? open?) "the panel was already open at the first frame")
                         (is (< (js/Math.abs (- (.-left panel) (.-left anchor))) 1.5)
                             "and already at the anchor's left edge — no wrong-position paint")
                         (is (< (js/Math.abs (- (.-top panel) (+ (.-bottom anchor) 4))) 1.5)
                             "below it by exactly the declared gap"))
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

(deftest r-b2-the-panel-escapes-a-clipping-transformed-ancestor-and-a-fixed-sibling-does-not
  (testing "R-B2 (mounted). The toolbar's ancestor carries `overflow:
            hidden` AND a transform — the two things that defeat
            `position: fixed` emulation, because a transform makes the
            ancestor the containing block for a fixed descendant.

            The panel lands at the measured VIEWPORT coordinate. A control
            element in the same ancestor, `position: fixed` at the SAME
            coordinates, lands displaced by exactly the transform — which
            is the wart, measured, in the same document, in the same
            commit. The panel does not have it because the browser gives a
            top-layer element the viewport as its containing block, and no
            z-index was involved in either direction."
    (if-not (browser?)
      (skip! "the browser job runs the containing-block assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [] (act #(.render root (element [ui/toolbar {:id doc-id}]))))]
          (-> (render)
              (.then (fn [_]
                       (send! [:acme.ui.dropdown/anchor-clicked k])
                       (render)))
              (.then (fn [_]
                       ;; A control at the same coordinates, inside the same
                       ;; transformed ancestor, but not in the top layer.
                       (let [host (by-id (str "toolbar-clip-" (name doc-id)))
                             ctrl (js/document.createElement "div")]
                         (set! (.-id ctrl) "clip-control")
                         (set! (.. ctrl -style -position) "fixed")
                         (set! (.. ctrl -style -inset) "auto")
                         (set! (.. ctrl -style -top) "var(--acme-anchor-y)")
                         (set! (.. ctrl -style -left) "var(--acme-anchor-x)")
                         (.appendChild host ctrl))
                       (let [panel  (rect (panel-id))
                             anchor (rect (anchor-id))
                             ctrl   (rect "clip-control")]
                         (is (true? (open? (panel-id))) "the panel is in the top layer")
                         (is (< (js/Math.abs (- (.-left panel) (.-left anchor))) 1.5)
                             "and sits exactly at the measured viewport coordinate")
                         (is (> (js/Math.abs (- (.-left ctrl) (.-left panel))) 5)
                             (str "non-vacuous: an ordinary fixed sibling at the SAME "
                                  "declared coordinates is displaced by the ancestor "
                                  "transform (panel " (.-left panel) " vs control "
                                  (.-left ctrl) ")"))
                         (is (> (.-bottom panel) (.-bottom (rect (str "toolbar-clip-" (name doc-id)))))
                             "and the panel extends past the clipping ancestor's box"))
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

;; ===========================================================================
;; R-B4 — the focus contract, both overlay classes
;; ===========================================================================

(deftest r-b4-the-modal-takes-focus-inerts-the-page-and-gives-focus-back
  (testing "R-B4 (mounted). re-com's parity bar on this requirement is
            ZERO — no focus trap, no inert, no Escape, no focus return
            anywhere in the library. The pilot's modal gets all of it from
            `showModal()`, and the row reads each one back off a live
            document rather than citing the platform."
    (if-not (browser?)
      (skip! "the browser job runs the focus assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [] (act #(.render root (element [ui/toolbar {:id doc-id}]))))
              dialog (str "acme-confirm-" (name doc-id))
              opener (str "toolbar-delete-" (name doc-id))]
          (-> (render)
              (.then (fn [_]
                       (is (false? (modal? dialog)) "closed to begin with")
                       (.focus (by-id opener))
                       (is (= (by-id opener) js/document.activeElement)
                           "the page owns focus")
                       (send! [:toolbar/delete-requested doc-id])
                       (render)))
              (.then (fn [_]
                       (is (true? (modal? dialog)) "showModal() ran at the commit")
                       (is (.contains (by-id dialog) js/document.activeElement)
                           "focus ENTERED the dialog")
                       (.focus (by-id opener))
                       (is (.contains (by-id dialog) js/document.activeElement)
                           "the background is INERT — it cannot take focus back")
                       (send! [:toolbar/delete-cancelled doc-id])
                       (render)))
              (.then (fn [_]
                       (is (false? (modal? dialog)) "close() ran at the commit")
                       (is (= (by-id opener) js/document.activeElement)
                           "and focus RETURNED to the control that opened it")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

(deftest r-b4-the-anchored-panel-leaves-focus-on-the-anchor
  (testing "R-B4 (mounted), the anchored half. An `:auto` popover takes no
            focus, so the anchor keeps it — which is what makes the
            keyboard grammar work at all, because the keys arrive at the
            anchor while the list is up."
    (if-not (browser?)
      (skip! "the browser job runs the focus assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [] (act #(.render root (element [ui/toolbar {:id doc-id}]))))]
          (-> (render)
              (.then (fn [_]
                       (.focus (by-id (anchor-id)))
                       (send! [:acme.ui.dropdown/anchor-clicked k])
                       (render)))
              (.then (fn [_]
                       (is (true? (open? (panel-id))) "the panel is up")
                       (is (= (by-id (anchor-id)) js/document.activeElement)
                           "and the anchor still has focus")
                       (is (= "true" (.getAttribute (by-id (anchor-id)) "aria-expanded"))
                           "with the expanded state announced")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

;; ===========================================================================
;; R-B3 — the browser dismisses, and the handshake reconciles
;; ===========================================================================

(deftest r-b3-a-browser-dismissal-reaches-the-handshake-and-does-not-spring-back
  (testing "R-B3 (mounted). The substrate writes NO application state when
            the browser dismisses, so an unreconciled control re-opens on
            the next render. The pilot reconciles by counting reports: the
            opening toggle acknowledges, the dismissal toggle closes. This
            row drives a real `hidePopover()` — what Escape and a light
            dismiss both come down to — and then RE-RENDERS, which is the
            step that would expose a spring-back."
    (if-not (browser?)
      (skip! "the browser job runs the dismissal assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [] (act #(.render root (element [ui/toolbar {:id doc-id}]))))]
          (-> (render)
              (.then (fn [_]
                       (send! [:acme.ui.dropdown/anchor-clicked k])
                       (render)))
              (.then (fn [_] (next-task)))
              (.then (fn [_]
                       (is (true? (open? (panel-id))) "open")
                       (is (true? (:acked? (record)))
                           "the platform's opening report reached the handshake")
                       ;; The browser closing it of its own accord.
                       (.hidePopover (by-id (panel-id)))
                       (next-task)))
              (.then (fn [_]
                       (is (nil? (record))
                           "the dismissal report closed the control's own state")
                       (render)))
              (.then (fn [_]
                       (is (false? (open? (panel-id)))
                           "and the next render does NOT re-open it")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

;; ===========================================================================
;; R-B9 — nesting: stacked, and dismissed innermost-first
;; ===========================================================================

(deftest r-b9-a-nested-panel-stacks-and-dismissing-it-leaves-the-outer-up
  (testing "R-B9 (mounted). The inner control is ordinary children of the
            outer panel, so its popover is a DOM descendant of the outer
            one — which is what makes the browser stack them rather than
            treat the inner as a sibling that closes the outer. Dismissing
            the inner leaves the outer up; that is the LIFO property, and
            it is the platform's, not the library's."
    (if-not (browser?)
      (skip! "the browser job runs the nesting assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render  (fn [] (act #(.render root (element [ui/menu-bar {:id doc-id}]))))
              outer-k [ui/dropdown-kind [:menu doc-id :format]]
              inner-k [ui/dropdown-kind [:menu doc-id :scope]]
              outer   (str "acme-dropdown-outer-" (name doc-id) "-panel")
              inner   (str "acme-dropdown-inner-" (name doc-id) "-panel")]
          (-> (render)
              (.then (fn [_]
                       (send! [:acme.ui.dropdown/anchor-clicked outer-k])
                       (send! [:acme.ui.dropdown/anchor-clicked inner-k])
                       (render)))
              (.then (fn [_] (next-task)))
              (.then (fn [_]
                       (is (true? (open? outer)) "the outer panel is open")
                       (is (true? (open? inner))
                           "and the nested one is open TOO — document order beat React's bottom-up refs")
                       (is (.contains (by-id outer) (by-id inner))
                           "non-vacuous: the inner really is a DOM descendant of the outer")
                       ;; The browser dismissing the INNER.
                       (.hidePopover (by-id inner))
                       (next-task)))
              (.then (fn [_]
                       (is (false? (open? inner)) "the inner closed")
                       (is (true? (open? outer)) "and the outer stayed up")
                       (is (nil? (get-in (frame/frame-app-db-value fid) [ui/records-root inner-k]))
                           "the inner's state closed with it")
                       (is (some? (get-in (frame/frame-app-db-value fid) [ui/records-root outer-k]))
                           "and the outer's did not")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

;; ===========================================================================
;; R-B6 / R-B12 — the retained count, as exact integers
;; ===========================================================================

(deftest r-b6-and-r-b12-a-full-cycle-retains-exactly-nothing
  (testing "R-B6 and R-B12 (mounted). The baseline's dropdown installs a
            document click listener it never removes on unmount, and
            re-arms a `requestAnimationFrame` every frame while open. This
            row measures both, plus observers, intervals and behavior
            connections, across mount → open → close → unmount — with the
            listener and frame deltas taken against a CONTROL mount of the
            same shape with no overlay, so React's own bookkeeping cannot
            masquerade as the library's."
    (if-not (browser?)
      (skip! "the browser job owns the retention counts")
      (async done
        (setup!)
        (let [original #js {"docAdd"    (.-addEventListener js/document)
                            "docRemove" (.-removeEventListener js/document)
                            "winAdd"    (.-addEventListener js/window)
                            "winRemove" (.-removeEventListener js/window)
                            "ro"        (.-ResizeObserver js/window)
                            "io"        (.-IntersectionObserver js/window)
                            "mo"        (.-MutationObserver js/window)
                            "interval"  (.-setInterval js/window)
                            "raf"       (.-requestAnimationFrame js/window)}
              counts   #js {}
              bump!    (fn [key* n] (gobj/set counts key* (+ n (gobj/get counts key* 0))))
              zero!    (fn [] (doseq [key* ["doc" "win" "obs" "iv" "raf"]]
                                (gobj/set counts key* 0)))
              orig     (fn [key*] (gobj/get original key*))
              observed (fn [key* ctor]
                         (fn [& args]
                           (bump! key* 1)
                           (js/Reflect.construct ctor (to-array args))))
              wrap!    (fn []
                         (zero!)
                         (set! (.-addEventListener js/document)
                               (fn [t l o] (bump! "doc" 1) (.call (orig "docAdd") js/document t l o)))
                         (set! (.-removeEventListener js/document)
                               (fn [t l o] (bump! "doc" -1) (.call (orig "docRemove") js/document t l o)))
                         (set! (.-addEventListener js/window)
                               (fn [t l o] (bump! "win" 1) (.call (orig "winAdd") js/window t l o)))
                         (set! (.-removeEventListener js/window)
                               (fn [t l o] (bump! "win" -1) (.call (orig "winRemove") js/window t l o)))
                         (set! (.-ResizeObserver js/window) (observed "obs" (orig "ro")))
                         (set! (.-IntersectionObserver js/window) (observed "obs" (orig "io")))
                         (set! (.-MutationObserver js/window) (observed "obs" (orig "mo")))
                         (set! (.-setInterval js/window)
                               (fn [f d] (bump! "iv" 1) (.call (orig "interval") js/window f d)))
                         (set! (.-requestAnimationFrame js/window)
                               (fn [f] (bump! "raf" 1) (.call (orig "raf") js/window f))))
              restore! (fn []
                         (set! (.-addEventListener js/document) (orig "docAdd"))
                         (set! (.-removeEventListener js/document) (orig "docRemove"))
                         (set! (.-addEventListener js/window) (orig "winAdd"))
                         (set! (.-removeEventListener js/window) (orig "winRemove"))
                         (set! (.-ResizeObserver js/window) (orig "ro"))
                         (set! (.-IntersectionObserver js/window) (orig "io"))
                         (set! (.-MutationObserver js/window) (orig "mo"))
                         (set! (.-setInterval js/window) (orig "interval"))
                         (set! (.-requestAnimationFrame js/window) (orig "raf")))
              snapshot (fn [] {:doc (gobj/get counts "doc") :win (gobj/get counts "win")
                               :obs (gobj/get counts "obs") :iv  (gobj/get counts "iv")
                               :raf (gobj/get counts "raf")})
              cycle!   (fn [form open! close!]
                         (let [[container root] (mount!)
                               render (fn [] (act #(.render root (element form))))]
                           (-> (render)
                               (.then (fn [_] (open!) (render)))
                               (.then (fn [_] (next-task)))
                               (.then (fn [_] (close!) (render)))
                               (.then (fn [_] (teardown! container root) nil)))))
              control  (atom nil)]
          (wrap!)
          (-> (cycle! [plain-toolbar {}] (fn [] nil) (fn [] nil))
              (.then (fn [_]
                       (reset! control (snapshot))
                       (zero!)
                       (frame/replace-app-db! fid {})
                       (cycle! [ui/toolbar {:id doc-id}]
                               #(send! [:acme.ui.dropdown/anchor-clicked k])
                               #(send! [:acme.ui.dropdown/dismissed k]))))
              (.then (fn [_]
                       (let [measured (snapshot)]
                         (restore!)
                         (is (= (:doc @control) (:doc measured))
                             (str "no net document listener beyond the control's "
                                  "(control " (:doc @control) ", measured " (:doc measured) ")"))
                         (is (= (:win @control) (:win measured))
                             "no net window listener beyond the control's")
                         (is (= 0 (:obs measured))
                             "zero resize / intersection / mutation observers")
                         (is (= 0 (:iv measured)) "zero intervals")
                         (is (<= (:raf measured) (:raf @control))
                             (str "no animation-frame arm beyond the control's — there is "
                                  "no tracking loop (control " (:raf @control)
                                  ", measured " (:raf measured) ")"))
                         (is (zero? (behaviors/connection-count))
                             "and every behavior connection released")
                         (is (zero? (top-layer/pending-count))
                             "with the top-layer commit batch drained"))
                       (done)))
              (.catch (fn [e] (restore!) (is false (str "browser run failed: " e)) (done)))))))))

;; ===========================================================================
;; THE REPRODUCTION — rf2-drpa3.118, met by a pilot that had to avoid it
;; ===========================================================================

(deftest a-behavior-and-a-top-layer-state-on-one-element-lose-a-ref
  (testing "REPRODUCTION of the known hazard. A behavior attaches through
            `cloneElement` with its own ref, and the top layer installs its
            host call on a ref of its own. React keeps ONE `ref` per
            element, so on an element carrying both, one silently
            overwrites the other — no warning, no diagnostic, and the
            half that lost simply never runs.

            The pilot's dropdown never asks for such an element: the
            measurement is of the anchor's box and the promotion is of the
            panel, and they were never the same node. That split reads as
            natural rather than contorted — the root is what a stylesheet
            already addresses and what the anchor's box already is — but it
            is a split the author has to KNOW to make, and nothing tells
            them. This row is the evidence that knowing mattered."
    (if-not (browser?)
      (skip! "the browser job runs the ref-collision reproduction")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [open?] (act #(.render root (element [collided {:open? open?}]))))]
          (-> (render false)
              (.then (fn [_] (render true)))
              (.then (fn [_] (next-task)))
              (.then (fn [_]
                       (let [connected (behaviors/connection-count)
                             promoted  (open? "collided")]
                         (is (or (zero? connected) (false? promoted))
                             (str "one of the two refs was lost — behavior connections "
                                  connected ", popover open " promoted))
                         (is (= 1 connected)
                             "the BEHAVIOR's ref is the one that survives (cloneElement wins)")
                         (is (false? promoted)
                             (str "and the top layer's never ran: the element declares a "
                                  "desired state of OPEN and is not open. Nothing warned.")))
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))
