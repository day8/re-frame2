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

(defn- settle
  "A generous macrotask boundary. A top-layer state change costs a
  microtask flush, a queued `toggle` task, the dispatch it causes, and the
  re-render that dispatch schedules — four hops, so a row that observes
  the END of that chain waits for it rather than for one task."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 60))))

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

(v/defview bare-nest
  "The CONTROL for the nesting rows: the minimal nested pair, with no
  library between it and the two intrinsics. If this nests and the pilot's
  dropdown does not, the difference is in the composition rather than in
  the platform or the substrate — which is the only way to attribute a
  nesting failure honestly."
  [{:keys [outer? inner?]}]
  [:div
   [:div {:id                 "bare-outer"
          :popover            :auto
          ::web/popover-open? outer?
          :on-toggle          [:probe/noted]}
    "outer"
    [:div {:id                 "bare-inner"
           :popover            :auto
           ::web/popover-open? inner?
           :on-toggle          [:probe/noted]}
     "inner"]]])

(defn- nest-panel-style []
  {:position "fixed" :inset "auto"
   :top "var(--acme-anchor-y)" :left "var(--acme-anchor-x)" :margin 0})

(v/defview nest-structured
  "The pilot's dropdown MARKUP, nested — anchor, panel, intermediate roots,
  the same inline styles — and NO behavior anywhere. The second rung of the
  attribution ladder."
  [{:keys [outer? inner?]}]
  [:div {:style {:display "inline-block" :position "relative"}}
   [:button {:type "button"} "outer"]
   [:div {:id                 "struct-outer"
          :popover            :auto
          :role               "listbox"
          ::web/popover-open? outer?
          :on-toggle          [:probe/noted]
          :style              (nest-panel-style)}
    [:div {:role "option"} "PDF"]
    [:div {:style {:display "inline-block" :position "relative"}}
     [:button {:type "button"} "inner"]
     [:div {:id                 "struct-inner"
            :popover            :auto
            :role               "listbox"
            ::web/popover-open? inner?
            :on-toggle          [:probe/noted]
            :style              (nest-panel-style)}
      [:div {:role "option"} "This page"]]]]])

(v/defview nest-behaved
  "The same markup with the pilot's BEHAVIOR on each root — the third rung.
  If this collapses and [[nest-structured]] does not, the behavior boundary
  is what breaks nesting; if both collapse, the markup is."
  [{:keys [outer? inner?]}]
  [v/behavior {:use ui/anchor-box :target :nest/outer :config {:open? outer? :gap 0}}
   [:div {:style {:display "inline-block" :position "relative"}}
    [:button {:type "button"} "outer"]
    [:div {:id                 "beh-outer"
           :popover            :auto
           :role               "listbox"
           ::web/popover-open? outer?
           :on-toggle          [:probe/noted]
           :style              (nest-panel-style)}
     [:div {:role "option"} "PDF"]
     [v/behavior {:use ui/anchor-box :target :nest/inner :config {:open? inner? :gap 0}}
      [:div {:style {:display "inline-block" :position "relative"}}
       [:button {:type "button"} "inner"]
       [:div {:id                 "beh-inner"
              :popover            :auto
              :role               "listbox"
              ::web/popover-open? inner?
              :on-toggle          [:probe/noted]
              :style              (nest-panel-style)}
        [:div {:role "option"} "This page"]]]]]]])

(v/defview nest-stateful
  "The fourth rung, and the decisive one. Identical to [[nest-behaved]] —
  same markup, same behaviors, same desired states from props — except
  that `:on-toggle` DISPATCHES A STATE WRITE and the view reads that state,
  so the platform's own opening report causes a re-render while the pair is
  open.

  That is not a contrivance. It is what reconciling a top-layer element
  costs when the report cannot be read: `ToggleEvent.newState` has no
  reserved projection, so a library that must distinguish `the browser
  opened it` from `the browser closed it` has to record the first report —
  and recording is a write."
  [{:keys [outer? inner?]}]
  (let [_ (v/sub [:probe/toggles])]
    [v/behavior {:use ui/anchor-box :target :nest/outer2 :config {:open? outer? :gap 0}}
     [:div {:style {:display "inline-block" :position "relative"}}
      [:button {:type "button"} "outer"]
      [:div {:id                 "stateful-outer"
             :popover            :auto
             :role               "listbox"
             ::web/popover-open? outer?
             :on-toggle          [:probe/toggled :outer]
             :style              (nest-panel-style)}
       [:div {:role "option"} "PDF"]
       [v/behavior {:use ui/anchor-box :target :nest/inner2 :config {:open? inner? :gap 0}}
        [:div {:style {:display "inline-block" :position "relative"}}
         [:button {:type "button"} "inner"]
         [:div {:id                 "stateful-inner"
                :popover            :auto
                :role               "listbox"
                ::web/popover-open? inner?
                :on-toggle          [:probe/toggled :inner]
                :style              (nest-panel-style)}
          [:div {:role "option"} "This page"]]]]]]]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- mount!
  "Mount into a fresh container. `at-origin?` pins the container to the
  viewport's top-left, which the geometry rows need: the runner's own
  output pushes an ordinary container far down a long page, where a
  measured coordinate is outside the viewport and `elementFromPoint`
  answers about nothing."
  ([] (mount! false))
  ([at-origin?]
   (let [container (js/document.createElement "div")]
     (when at-origin?
       (set! (.. container -style -position) "fixed")
       (set! (.. container -style -top) "0px")
       (set! (.. container -style -left) "0px"))
     (.appendChild js/document.body container)
     [container (rdc/createRoot container)])))

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
  (rf/reg-sub   :probe/toggles (fn [db _] (get db ::toggles [])))
  (rf/reg-event :probe/toggled
    (fn [{:keys [db]} [_ which]]
      {:db (update db ::toggles (fnil conj []) which)}))
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
        (let [[container root] (mount! true)
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

(deftest r-b2-the-panel-escapes-a-clipping-transformed-ancestor
  (testing "R-B2 (mounted). The toolbar's ancestor carries `overflow:
            hidden` AND a transform — the two things that defeat
            `position: fixed` emulation, because a transform makes an
            ancestor the containing block for every fixed descendant.

            Three measurements, and the middle one is what makes the other
            two mean something. A `position: fixed` probe inside the same
            ancestor resolves against the ANCESTOR, displaced by the
            transform — so the containing-block capture is real in this
            document, in this commit, and not merely asserted from the
            spec. The panel, at the same moment, sits exactly at the
            viewport coordinate the behavior measured, and paints outside
            the ancestor's clipping box. No z-index was involved in either
            direction."
    (if-not (browser?)
      (skip! "the browser job runs the containing-block assertions")
      (async done
        (setup!)
        (let [[container root] (mount! true)
              render (fn [] (act #(.render root (element [ui/toolbar {:id doc-id}]))))
              clip-id (str "toolbar-clip-" (name doc-id))]
          (-> (render)
              (.then (fn [_]
                       (send! [:acme.ui.dropdown/anchor-clicked k])
                       (render)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       ;; THE PROBE: an ordinary fixed element at the
                       ;; viewport origin, inside the transformed ancestor.
                       (let [host  (by-id clip-id)
                             probe (js/document.createElement "div")]
                         (set! (.-id probe) "clip-probe")
                         (set! (.. probe -style -position) "fixed")
                         (set! (.. probe -style -inset) "auto")
                         (set! (.. probe -style -top) "0px")
                         (set! (.. probe -style -left) "0px")
                         (set! (.. probe -style -width) "1px")
                         (set! (.. probe -style -height) "1px")
                         (.appendChild host probe))
                       (let [panel  (rect (panel-id))
                             anchor (rect (anchor-id))
                             clip   (rect clip-id)
                             probe  (rect "clip-probe")
                             hit    (js/document.elementFromPoint
                                      (+ (.-left panel) 2) (+ (.-top panel) 2))]
                         (is (true? (open? (panel-id))) "the panel is in the top layer")

                         (is (> (.-left probe) 0.5)
                             (str "non-vacuous: a fixed probe declared at viewport 0 is NOT "
                                  "at viewport 0 — the ancestor's transform captured it "
                                  "(probe left " (.-left probe) ")"))
                         (is (< (js/Math.abs (- (.-left probe) (.-left clip))) 1.5)
                             "it resolved against the TRANSFORMED ancestor's box instead")

                         (is (< (js/Math.abs (- (.-left panel) (.-left anchor))) 1.5)
                             "the panel, at the same moment, is at the measured VIEWPORT coordinate")
                         (is (> (.-top panel) (.-bottom clip))
                             (str "and paints below the clipping ancestor's box entirely "
                                  "(panel top " (.-top panel) ", clip bottom " (.-bottom clip) ")"))
                         (is (or (= hit (by-id (panel-id)))
                                 (and (some? hit) (.contains (by-id (panel-id)) hit)))
                             (str "and it is really there: the point paints the panel, not "
                                  "whatever the clip would have left behind (hit "
                                  (some-> hit .-id) ")")))
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
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (true? (open? (panel-id))) "open")
                       (is (true? (:acked? (record)))
                           "the platform's opening report reached the handshake")
                       ;; Let every render the acknowledgement scheduled
                       ;; commit BEFORE the browser dismisses: a render
                       ;; still in flight would re-assert the desired state
                       ;; after the dismissal and re-open the node, which is
                       ;; a race about test sequencing rather than about the
                       ;; handshake.
                       (act (fn [] nil))))
              (.then (fn [_]
                       ;; The browser closing it of its own accord — what
                       ;; Escape and a light dismiss both come down to.
                       (.hidePopover (by-id (panel-id)))
                       (settle)))
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

(def ^:private outer-k [ui/dropdown-kind [:menu doc-id :format]])
(def ^:private inner-k [ui/dropdown-kind [:menu doc-id :scope]])
(defn- outer-panel [] (str "acme-dropdown-outer-" (name doc-id) "-panel"))
(defn- inner-panel [] (str "acme-dropdown-inner-" (name doc-id) "-panel"))

(defn- open-record [] {:open? true :active 0})

(deftest r-b9-nested-dropdowns-collapse-because-a-counting-handshake-cannot-tell-open-from-closed
  (testing "R-B9 (mounted), and THE PILOT'S SHARPEST FINDING.

            The requirement is met by the platform and by the substrate:
            the attribution ladder above proves that the minimal pair, the
            pilot's exact markup, that markup plus behaviors, and even that
            markup with a state-writing toggle handler ALL nest correctly
            and stay nested across further commits.

            What cannot be built on top of it is a library control that
            reconciles its own dismissal. `ToggleEvent.newState` has no
            reserved projection, so `the browser opened it` and `the browser
            closed it` arrive as the same data; the only data-only
            reconciliation is to COUNT reports and treat the second as the
            dismissal. The ladder's last rung shows why that fails: opening
            a nested pair produces MORE than one report for the ancestor,
            so the counting control reads its own opening as a dismissal
            and closes.

            The row asserts the collapse rather than describing it. It is
            not a defect in the composition — the same declarations nest
            perfectly when their desired state is props-driven — and the
            control does not lie: it reconciles both records to closed, so
            the state and the document agree. The requirement is simply not
            reachable through the grammar as it stands."
    (if-not (browser?)
      (skip! "the browser job runs the nesting assertions")
      (async done
        (setup!)
        ;; Both open BEFORE the first render, so one commit carries both.
        (frame/replace-app-db! fid {ui/records-root {outer-k (open-record)
                                                     inner-k (open-record)}})
        (let [[container root] (mount!)
              render (fn [] (act #(.render root (element [ui/menu-bar {:id doc-id}]))))
              outer  (outer-panel)
              inner  (inner-panel)]
          (-> (render)
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (let [state (str "ops=" (top-layer/operation-count)
                                        " outer-open=" (open? outer)
                                        " inner-open=" (open? inner)
                                        " outer-connected=" (some-> (by-id outer) .-isConnected)
                                        " inner-connected=" (some-> (by-id inner) .-isConnected)
                                        " outer-popover=" (some-> (by-id outer) (.getAttribute "popover"))
                                        " inner-popover=" (some-> (by-id inner) (.getAttribute "popover"))
                                        " records=" (pr-str (get (frame/frame-app-db-value fid)
                                                                 ui/records-root)))]
                         (is (false? (open? outer))
                             (str "THE FINDING: the ancestor closed itself — " state))
                         (is (false? (open? inner))
                             (str "and the nested half went with the subtree that stopped "
                                  "rendering — " state)))
                       (is (.contains (by-id outer) (by-id inner))
                           "non-vacuous: the inner really is a DOM descendant of the outer")
                       (is (empty? (get (frame/frame-app-db-value fid) ui/records-root))
                           "and the control does not lie: both records reconciled to closed")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

(deftest the-minimal-nested-pair-stacks-when-both-open-in-one-commit
  (testing "The CONTROL for the two nesting rows: two bare popovers, one
            inside the other, both desired open at the first commit and
            nothing else in the picture. It exists so a nesting failure in
            the pilot's dropdown can be attributed — to the composition, to
            the substrate, or to the platform — instead of guessed at."
    (if-not (browser?)
      (skip! "the browser job runs the nesting assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [outer? inner?]
                       (act #(.render root (element [bare-nest {:outer? outer?
                                                                :inner? inner?}]))))]
          (-> (render false false)
              (.then (fn [_] (render true true)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (true? (open? "bare-outer")) "the bare outer popover is open")
                       (is (true? (open? "bare-inner")) "and the bare nested one with it")
                       (teardown! container root)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

(deftest the-attribution-ladder-for-nesting
  (testing "Two more rungs between the minimal pair and the pilot's
            dropdown, so a nesting failure can be ATTRIBUTED rather than
            guessed at: the same markup without a behavior, then the same
            markup with one on each root. Whichever rung breaks names the
            cause."
    (if-not (browser?)
      (skip! "the browser job runs the nesting assertions")
      (async done
        (setup!)
        (let [[c1 r1] (mount!)
              [c2 r2] (mount!)
              [c3 r3] (mount!)
              render (fn [root form outer? inner?]
                       (act #(.render root (element [form {:outer? outer? :inner? inner?}]))))]
          (-> (render r1 nest-structured false false)
              (.then (fn [_] (render r1 nest-structured true true)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (true? (open? "struct-outer"))
                           (str "RUNG 2 (markup, no behavior): outer open — ops="
                                (top-layer/operation-count)))
                       (is (true? (open? "struct-inner"))
                           "RUNG 2 (markup, no behavior): inner open")
                       (teardown! c1 r1)
                       (render r2 nest-behaved false false)))
              (.then (fn [_] (render r2 nest-behaved true true)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (true? (open? "beh-outer"))
                           (str "RUNG 3 (markup + behaviors): outer open — ops="
                                (top-layer/operation-count)))
                       (is (true? (open? "beh-inner"))
                           "RUNG 3 (markup + behaviors): inner open")
                       (teardown! c2 r2)
                       (render r3 nest-stateful false false)))
              (.then (fn [_] (render r3 nest-stateful true true)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       ;; THE DECISIVE RUNG. Reconciling the platform's
                       ;; report is a state write, and the write re-renders
                       ;; while the pair is open — so the pair survives a
                       ;; reconciling toggle handler, PROVIDED the desired
                       ;; state does not depend on what the handler wrote.
                       ;;
                       ;; The transcript is the finding: the OUTER popover
                       ;; is reported more than once for one opening. A
                       ;; library that must infer `the browser closed it`
                       ;; by COUNTING reports — which is the only way, since
                       ;; `newState` has no reserved projection — reads the
                       ;; second report as a dismissal and closes.
                       (is (true? (open? "stateful-outer"))
                           (str "RUNG 4 (a state write on toggle): the pair survives — ops="
                                (top-layer/operation-count)
                                " toggles=" (pr-str (get (frame/frame-app-db-value fid)
                                                         ::toggles))))
                       (is (true? (open? "stateful-inner"))
                           "RUNG 4: and so does the nested half")
                       (is (< 2 (count (get (frame/frame-app-db-value fid) ::toggles)))
                           (str "THE MECHANISM: two nested popovers opening produce MORE than "
                                "two toggle reports — "
                                (pr-str (get (frame/frame-app-db-value fid) ::toggles))))
                       (teardown! c3 r3)
                       (done)))
              (.catch (fn [e] (is false (str "browser run failed: " e)) (done)))))))))

(deftest a-nested-panel-opened-in-a-LATER-commit-collapses-the-pair
  (testing "R-B9, and a FINDING. The document-order flush repairs nesting
            WITHIN one commit. It cannot repair it ACROSS commits, and
            across commits is the shape a user produces: open the menu,
            then reach into it for the submenu.

            What happens is total. Showing the inner popover in a commit
            after its ancestor was already open does not nest — the browser
            light-dismisses the outer, and hiding the outer takes the
            just-shown inner down with it because the inner is inside a
            subtree that is no longer rendered. The pilot's own dismissal
            handshake then correctly reconciles both records to closed. So
            the control does not lie about its state; it simply cannot be
            opened this way.

            This row asserts the collapse rather than describing it,
            because a pilot's job is evidence. It is not a defect in the
            pilot's composition: the same declarations nest perfectly when
            both open in one commit (the row above)."
    (if-not (browser?)
      (skip! "the browser job runs the nesting assertions")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render (fn [] (act #(.render root (element [ui/menu-bar {:id doc-id}]))))
              outer  (outer-panel)
              inner  (inner-panel)]
          (-> (render)
              (.then (fn [_]
                       (send! [:acme.ui.dropdown/anchor-clicked outer-k])
                       (render)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (true? (open? outer)) "the outer panel opened, alone, first")
                       (is (.contains (by-id outer) (by-id inner))
                           "and the inner popover is inside it in the DOM, closed")
                       ;; The second commit — the user reaching into the menu.
                       (send! [:acme.ui.dropdown/anchor-clicked inner-k])
                       (render)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (false? (open? outer))
                           "THE FINDING: the ancestor was light-dismissed by its own descendant")
                       (is (false? (open? inner))
                           "and the descendant went down with the subtree that stopped rendering")
                       (is (nil? (get-in (frame/frame-app-db-value fid) [ui/records-root outer-k]))
                           "the handshake reconciled the outer's state to closed — the control
                            does not claim to be open")
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
