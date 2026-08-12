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

  One row here is not a claim about the dropdown but about the vocabulary
  it is built from: an element carrying BOTH a behavior and a top-layer
  desired state hands the node to both, because ref composition chains the
  two. The pilot's own composition never asks for such an element — it
  measures the anchor and promotes the panel — so that row is what says
  the split is a modelling choice rather than an avoidance.

  Two rows are about PROMOTION rather than about the platform. The
  overlay markup's compiled twin is mounted beside the interpreted one:
  it reaches the real top layer, dispatches a live click from its
  compiled event site, builds the same document attribute for attribute
  — and is judged by the dismissal advisory exactly as its interpreted
  twin is. The structural rows in `pilot-overlay-parity-cljs-test` prove
  the two declarations DENOTE the same node; only a page can say the
  promoted one reaches a browser, because promotion's whole cost is paid
  at a commit.

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
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.pilot-overlay :as ui]
            [re-frame.freehand.pilot-overlay-compiled :as compiled]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.freehand.top-layer :as top-layer]
            [re-frame.freehand.web :as web]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

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
  "THE COMBINATION. One element carrying BOTH a registered behavior and a
  top-layer desired state.

  A behavior attaches through `cloneElement` with its own ref; the top
  layer installs its idempotent host call on a ref of its own. React keeps
  one `ref` prop per element, so before ref composition whichever was
  applied last silently won and the other never ran. Composition chains
  them, and this shape is how the pilot reads that back."
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
  "The fourth rung. Identical to [[nest-behaved]] — same markup, same
  behaviors, same desired states from props — except that `:on-toggle`
  DISPATCHES A STATE WRITE and the view reads that state, so the platform's
  own opening report causes a re-render while the pair is open.

  It is the control for what a reconciling library actually does. A toggle
  handler that writes on every report re-renders the pair mid-open, and the
  pair still survives PROVIDED the desired state does not follow the write
  into `closed`. The pilot's dropdown is exactly such a handler: it reads
  `::v/new-state` and closes only on a genuine `closed`, so an opening
  report leaves the desired state untouched and the ancestor stays up."
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

(v/defview interpreted-panel
  "The BROWSER CONTROL for promotion parity: `pilot-overlay-compiled/panel`
  with `{:compiled true}` removed and nothing else changed. Declared here
  rather than borrowed, because a view id is derived from where a
  declaration lives and this file has to mount both twins itself."
  {:props [:map
           [:open? :boolean]
           [:control :any]]}
  [{:keys [open? control]}]
  [:div {:data-component "acme/dropdown"
         :data-part      "root"
         :style          {:display "inline-block" :position "relative"}}
   [:button {:data-part     "anchor"
             :type          "button"
             :aria-expanded (if open? "true" "false")
             :on-click      [:acme.ui.dropdown/anchor-clicked control]}
    "Export as…"]
   [:div {:data-part          "panel"
          :role               "listbox"
          :popover            :auto
          ::web/popover-open? open?
          :on-toggle          [:acme.ui.dropdown/toggle-reported control ::v/new-state]
          :style              {:position "fixed"
                               :inset    "auto"
                               :top      "var(--acme-anchor-y)"
                               :left     "var(--acme-anchor-x)"
                               :margin   0}}
    "PDF"]])

(v/defview unreconciled-promoted
  "The POSITIVE CONTROL for the dismissal advisory: a promoted popover
  asking to be open with nothing listening for the browser's own closing
  report. It is the mistake the pilot's dropdown does not make, declared
  in the same mode, so \"the pilot is not accused\" is a reading of the
  advisory rather than of a channel that was never live."
  {:compiled true}
  [_]
  [:div {:id                 "unreconciled-promoted"
         :popover            :auto
         ::web/popover-open? true}
   "menu"])

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
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba). `cljs.test`
              ;; hands `done` a continuation that runs the WHOLE REMAINDER of the
              ;; run synchronously, so a `.catch` downstream of it claims whatever
              ;; a later namespace throws as this row's failure, prints it against
              ;; this row's label, and fires `done` a SECOND time — re-forcing
              ;; `run-block`'s unrealized delay and re-running that namespace.
              ;;
              ;; `teardown!` stays on the SUCCESS arm throughout this file rather
              ;; than riding the trailing step: the failure arm has never torn a
              ;; root down, and a `teardown!` in the trailing step would run
              ;; against a root whose own commit had just thrown — where an
              ;; `.unmount` that threw in turn would strand `done` and hang the
              ;; lane. Inside the `.then` it is covered by the handler below.
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

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
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

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
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

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
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; R-B3 — the browser dismisses, and the pilot reconciles from newState
;; ===========================================================================

(deftest r-b3-a-browser-dismissal-reconciles-from-newstate-and-does-not-spring-back
  (testing "R-B3 (mounted). The substrate writes NO application state when
            the browser dismisses, so an unreconciled control re-opens on
            the next render. The pilot reconciles by reading `newState`: the
            opening toggle is inert (the record already exists), the
            dismissal toggle carries `closed` and retires it. This row
            drives a real `hidePopover()` — what Escape and a light dismiss
            both come down to — and then RE-RENDERS, which is the step that
            would expose a spring-back."
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
                       (is (= {:open? true :active 0} (record))
                           "the platform's opening report was inert — the record
                            is unchanged, an `open` newState reconciles nothing")
                       ;; Let any render still in flight commit BEFORE the
                       ;; browser dismisses: a render mid-flight would
                       ;; re-assert the desired state after the dismissal and
                       ;; re-open the node, which is a race about test
                       ;; sequencing rather than about the reconciliation.
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
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; R-B9 — nesting: stacked, and dismissed innermost-first
;; ===========================================================================

(def ^:private outer-k [ui/dropdown-kind [:menu doc-id :format]])
(def ^:private inner-k [ui/dropdown-kind [:menu doc-id :scope]])
(defn- outer-panel [] (str "acme-dropdown-outer-" (name doc-id) "-panel"))
(defn- inner-panel [] (str "acme-dropdown-inner-" (name doc-id) "-panel"))

(defn- open-record [] {:open? true :active 0})

(deftest r-b9-nested-dropdowns-stay-open-because-newstate-tells-open-from-closed
  (testing "R-B9 (mounted), and THE PILOT'S SHARPEST FINDING, now RESOLVED.

            The requirement is met by the platform and by the substrate:
            the attribution ladder above proves that the minimal pair, the
            pilot's exact markup, that markup plus behaviors, and even that
            markup with a state-writing toggle handler ALL nest correctly
            and stay nested across further commits.

            What could not be built on top of it was a library control that
            reconciled its OWN dismissal. Opening a nested pair produces
            MORE than one report for the ancestor (the ladder's last rung),
            so a control that COUNTED reports read its own second opening as
            a dismissal and closed. Reading `ToggleEvent.newState` — now the
            reserved `::v/new-state` projection — tells `the browser opened
            it` from `the browser closed it` directly: an opening report is
            inert, and only a genuine `closed` retires the record.

            The row asserts the pair STAYS OPEN rather than collapsing —
            the flip that deleting the counting handshake bought. The
            document and the state agree, and now they agree on `open`."
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
                         (is (true? (open? outer))
                             (str "THE FLIP: the ancestor stayed open — an opening report "
                                  "is inert under `::v/new-state` — " state))
                         (is (true? (open? inner))
                             (str "and the nested half with it — " state)))
                       (is (.contains (by-id outer) (by-id inner))
                           "non-vacuous: the inner really is a DOM descendant of the outer")
                       (is (= {outer-k (open-record) inner-k (open-record)}
                              (get (frame/frame-app-db-value fid) ui/records-root))
                           "and both records are still open — the control did not close
                            itself on its own opening reports")
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

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
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

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
                       ;; state does not follow the write into `closed`.
                       ;;
                       ;; The transcript is the mechanism the ancestor's
                       ;; reconciliation has to cope with: the OUTER popover
                       ;; is reported more than once for one opening. A
                       ;; library that COUNTED reports would read the second
                       ;; as a dismissal and close; reading `::v/new-state`
                       ;; keeps an opening report inert instead, which is
                       ;; what the pilot's dropdown now does (r-b9 above).
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
                       (teardown! c3 r3)))
              ;; Reports and releases; it never finishes (rf2-fyba). The three
              ;; rungs' teardowns stay where they are: each retires its OWN mount
              ;; at the moment that rung ends, and only the last is on this step.
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

(deftest a-nested-panel-opened-in-a-LATER-commit-also-stays-nested
  (testing "R-B9, and a FINDING now RESOLVED. The one-commit row proves a
            nested pair opened TOGETHER stays open. This row proves the
            harder shape — the one a user actually produces: open the menu,
            THEN reach into it for the submenu, in a later commit.

            Opening the inner popover in a commit after its ancestor was
            already open fires more toggle reports on the outer, and a
            control that COUNTED reports read one of them as a dismissal and
            collapsed the whole pair — the ancestor light-dismissing itself
            on its own descendant's opening. Reading `::v/new-state` keeps
            every opening report inert, so the ancestor stays up and the
            submenu opens inside it. This row asserts the pair STAYS nested
            across commits.

            It is not defect-free by luck: the same declarations collapsed
            here under the counting handshake, so the flip is exactly what
            reading the reported state — rather than counting — bought."
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
                       (is (false? (open? inner)) "the inner is not open yet")
                       ;; The second commit — the user reaching into the menu.
                       (send! [:acme.ui.dropdown/anchor-clicked inner-k])
                       (render)))
              (.then (fn [_] (settle)))
              (.then (fn [_]
                       (is (true? (open? outer))
                           "THE FLIP: the ancestor stayed open across the second commit —
                            an opening report is inert under `::v/new-state`")
                       (is (true? (open? inner))
                           "and the submenu opened inside it")
                       (is (= {:open? true :active 0}
                              (get-in (frame/frame-app-db-value fid) [ui/records-root outer-k]))
                           "the outer record is still open — no opening report was read
                            as a dismissal")
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

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
                             "with the top-layer commit batch drained"))))
              ;; Reports and releases; it never finishes (rf2-fyba). `restore!`
              ;; is ASYMMETRIC and stays put: the success arm un-patches the
              ;; globals in the same breath as the snapshot it took them for,
              ;; above, so this is the failure arm's own safety net for a
              ;; rejection that arrived while they were still patched. Leaving
              ;; `document.addEventListener` wrapped would corrupt the whole
              ;; remainder of the run, so it may not ride a step that a throw
              ;; could skip.
              (.catch (fn [e] (restore!) (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; THE COMBINATION — one element, both mechanisms, and both refs run
;; ===========================================================================

(deftest a-behavior-and-a-top-layer-state-on-one-element-both-receive-the-node
  (testing "A behavior attaches through `cloneElement` with its own ref and
            the top layer installs its host call on a ref of its own, and
            React keeps ONE `ref` per element — so an element carrying both
            was once a silent clobber, with no warning and no diagnostic
            for whichever half never ran. Ref composition chains them
            instead, and this row is the pilot's own reading of that: ONE
            element carries the behavior's measured box AND the browser's
            `:popover-open` state at the same time.

            The pilot's dropdown still splits the two across the root and
            the panel, because the measurement is of the anchor's box and
            the promotion is of the panel and those are simply different
            things. What changed is that the split is now a modelling
            choice rather than a hazard the author had to KNOW to avoid.

            FH-BEHAVIOR-007 pins the composition mechanism itself; this row
            pins that the PILOT's own vocabulary composes on one element."
    (if-not (browser?)
      (skip! "the browser job runs the shared-element combination")
      (async done
        (setup!)
        (let [[container root] (mount!)
              render   (fn [open?] (act #(.render root (element [collided {:open? open?}]))))
              measured (fn []
                         (some-> (by-id "collided")
                                 (.-style)
                                 (.getPropertyValue "--acme-anchor-w")
                                 (.trim)))
              closed   (atom nil)]
          (-> (render false)
              (.then (fn [_] (next-task)))
              (.then (fn [_]
                       (reset! closed {:connected (behaviors/connection-count)
                                       :promoted  (open? "collided")})
                       (render true)))
              (.then (fn [_] (next-task)))
              (.then (fn [_]
                       (let [connected (behaviors/connection-count)
                             promoted  (open? "collided")]
                         (is (= 1 connected)
                             (str "the BEHAVIOR's ref ran — exactly one connection, not "
                                  connected))
                         (is (true? promoted)
                             (str "and the TOP LAYER's ref ran too: the element declares a "
                                  "desired state of OPEN and IS open. Neither ref was "
                                  "clobbered."))
                         (is (seq (measured))
                             (str "on the SAME node — #collided carries the behavior's "
                                  "measured box, so both refs were handed that element"))
                         (is (= 1 (:connected @closed))
                             "non-vacuous: the behavior was already connected while closed")
                         (is (false? (:promoted @closed))
                             (str "non-vacuous: the element was NOT open before it was "
                                  "asked to be — the OPEN reading is a response, not a "
                                  "constant")))
                       (teardown! container root)))
              ;; Reports and releases; it never finishes (rf2-fyba).
              (.catch (fn [e] (is false (str "browser run failed: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; PROMOTION PARITY, MOUNTED — the compiled twin reaches the top layer
;; ===========================================================================

(defn- dom-shape
  "A mounted subtree as comparable data: every element, its attribute
  names and values, its text, and its children in document order.

  Deliberately NOT `outerHTML`. The two emitters write the same
  attributes to an element in a different ORDER, which nothing in a
  browser can observe but which makes string equality a false negative.
  Comparing the attribute SET is the parity question that means
  something, and it is stricter than `outerHTML` everywhere else: a
  missing attribute, a changed value, an extra node or a reordered child
  all still fail."
  [node]
  (if (= 3 (.-nodeType node))
    (.-nodeValue node)
    {:tag      (.-tagName node)
     :attrs    (into (sorted-map)
                     (map (fn [a] [(.-name a) (.-value a)]))
                     (js/Array.from (.-attributes node)))
     :children (mapv dom-shape (js/Array.from (.-childNodes node)))}))

(defn- overlay-arm!
  "Drive ONE twin through closed → open → a real click on its anchor, and
  hand back what the document said at each step.

  The two arms run one at a time, on their own mount, rather than side by
  side in one commit. That is the platform's rule rather than a
  convenience: sibling `:auto` popovers are mutually exclusive, so asking
  both twins to be open at the same moment would light-dismiss the first
  and the comparison would be reading the exclusivity contract instead of
  the promotion one."
  [form]
  (let [[container root] (mount!)
        render    (fn [open?]
                    (act #(.render root (element [form {:open? open? :control k}]))))
        panel     (fn [] (.querySelector container "[data-part='panel']"))
        promoted? (fn [] (some-> (panel) (.matches ":popover-open")))
        root-node (fn [] (.-firstElementChild container))
        seen      (atom {})]
    (frame/replace-app-db! fid {})
    (-> (render false)
        (.then (fn [_] (settle)))
        (.then (fn [_]
                 (swap! seen assoc :closed {:shape    (dom-shape (root-node))
                                            :promoted (promoted?)})
                 (render true)))
        (.then (fn [_] (settle)))
        (.then (fn [_]
                 (swap! seen assoc :open {:shape    (dom-shape (root-node))
                                          :promoted (promoted?)})
                 (act #(.click (.querySelector container "[data-part='anchor']")))))
        (.then (fn [_]
                 (swap! seen assoc :clicked (record))
                 (teardown! container root)
                 @seen)))))

(deftest promotion-puts-the-panel-in-the-top-layer-on-a-real-page
  (testing "Promotion parity, read off the document instead of off a tree.

            The structural rows in `pilot-overlay-parity-cljs-test` prove
            the two declarations DENOTE the same node. They cannot prove
            that the promoted one reaches a page, because a structural
            render has no host to call: `:popover-open` is a platform
            state, and the reserved desired-state property only becomes
            one when an emitter turns it into a host call at a commit.

            So each twin is mounted, driven closed → open, and clicked.
            Three things follow that no structural row can state: the
            compiled emitter really installs the top-layer host call, so
            a promoted panel is genuinely IN the top layer; the compiled
            event site really dispatches a live DOM event into app-db;
            and the document the compiled emitter builds is the document
            the interpreted one builds, attribute for attribute and
            character for character, in both states.

            What is still NOT provable here is the measure-then-place
            half. A compiled body may not attach a behavior, so the
            placement the pilot's real dropdown gets from `anchor-box`
            has no promoted form to compare — that refusal is pinned,
            with its diagnostic, in `pilot-overlay-parity-cljs-test`."
    (if-not (browser?)
      (skip! "the browser job runs the promotion-parity assertions")
      (async done
        (setup!)
        (let [seen (atom {})]
          (-> (overlay-arm! interpreted-panel)
              (.then (fn [a]
                       (swap! seen assoc :interpreted a)
                       (overlay-arm! compiled/panel)))
              (.then (fn [b]
                       (swap! seen assoc :promoted b)
                       (let [i (:interpreted @seen)
                             c (:promoted @seen)]
                         (is (false? (get-in i [:closed :promoted]))
                             "non-vacuous: the interpreted panel is NOT in the top layer
                              before it is asked to be")
                         (is (false? (get-in c [:closed :promoted]))
                             "non-vacuous: neither is the compiled one")
                         (is (true? (get-in i [:open :promoted]))
                             "asked to be open, the interpreted panel is in the top layer")
                         (is (true? (get-in c [:open :promoted]))
                             "AND SO IS THE COMPILED ONE — the promoted declaration's
                              reserved desired state became a host call at the commit")
                         (is (= (get-in i [:closed :shape]) (get-in c [:closed :shape]))
                             "closed, the two emitters build the same document: every
                              element, every attribute, every character of text, in order")
                         (is (= (get-in i [:open :shape]) (get-in c [:open :shape]))
                             "and open, they still do")
                         (is (= {:open? true :active 0} (:clicked c))
                             (str "the COMPILED anchor's event site dispatched a live "
                                  "browser click into app-db — " (pr-str (:clicked c))))
                         (is (= (:clicked i) (:clicked c))
                             "exactly as the interpreted twin's site does"))))
              ;; Reports and releases; it never finishes (rf2-fyba). Each arm
              ;; retires its own mount inside `overlay-arm!`, so there is no
              ;; teardown here to place either way.
              (.catch (fn [e]
                        (is false (str "the promotion pass threw " e))
                        nil))
              (.then (fn [_] (done)))))))))

(def ^:private fx-005 (conf/fixture :FH-TOPLAYER-005))

(defn- advisories!
  "The dismissal advisories the trace diagnostic bus carried while `f`'s
  promise settled, in order. The advisory is published from the COMMITTED
  ref, so the capture has to span the commit rather than the render call."
  [f]
  (let [records (atom [])
        key*    (keyword (gensym "fh-overlay-advisory-"))]
    (trace-tooling/register-listener!
      key* (fn [ev] (when (= (:unreconciled-id fx-005) (:operation ev))
                      (swap! records conj ev))))
    (-> (f)
        (.then (fn [_]
                 (trace-tooling/unregister-listener! key*)
                 @records))
        (.catch (fn [e]
                  (trace-tooling/unregister-listener! key*)
                  (js/Promise.reject e))))))

(deftest promotion-does-not-change-what-the-dismissal-advisory-says
  (testing "The other half of the promoted install: an advisory is a
            judgement about the ELEMENT, and a compiled element has no
            runtime attribute map to judge — its props are written
            imperatively onto a JS object. So the facts the judgement
            needs travel with the desired state, and this row is what says
            they arrived.

            The pilot's dropdown reconciles its own dismissal (that is
            what `:on-toggle` and its `::v/new-state` reconciliation are
            for), so neither mode may accuse it. A promoted popover that really
            declares no reconciliation IS accused, in the same mode, at
            the same commit — which is what makes the pilot's silence a
            reading rather than a dead channel."
    (if-not (browser?)
      (skip! "the browser job runs the advisory assertions")
      (async done
        (setup!)
        (let [seen (atom {})
              once (fn [form]
                     (let [[container root] (mount!)]
                       (-> (advisories!
                             (fn [] (act #(.render root (element form)))))
                           (.then (fn [records]
                                    (teardown! container root)
                                    records)))))]
          (-> (once [compiled/panel {:open? true :control k}])
              (.then (fn [r] (swap! seen assoc :compiled r)
                       (once [interpreted-panel {:open? true :control k}])))
              (.then (fn [r] (swap! seen assoc :interpreted r)
                       (once [unreconciled-promoted {}])))
              (.then (fn [r]
                       (swap! seen assoc :control r)
                       (let [{c :compiled i :interpreted ctl :control} @seen]
                         (is (= 1 (count ctl))
                             (str "non-vacuous: the channel is live — a promoted popover "
                                  "with no dismissal handler IS accused, from a COMPILED "
                                  "declaration (" (pr-str (mapv :operation ctl)) ")"))
                         (when-some [ev (first ctl)]
                           (is (= :popover (:mechanism (:tags ev)))
                               "naming the mechanism")
                           (is (= [:on-toggle :on-before-toggle] (:handlers (:tags ev)))
                               "and the positions that would reconcile it"))
                         (is (= [] i)
                             "the pilot's panel reconciles its own dismissal, so the
                              interpreted twin is not accused")
                         (is (= [] c)
                             "AND NEITHER IS THE COMPILED ONE — the `:on-toggle` it
                              declares reached the judgement that promotion could
                              have hidden"))))
              ;; Reports and releases; it never finishes (rf2-fyba). Each `once`
              ;; retires its own mount, so there is no teardown here to place.
              (.catch (fn [e]
                        (is false (str "the advisory pass threw " e))
                        nil))
              (.then (fn [_] (done)))))))))
