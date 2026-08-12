(ns re-frame.freehand.route-link-matrix-dom-cljs-test
  "F6b matrix 6/8 — `v/route-link` NATIVE behaviour, in a real browser,
  across BOTH execution modes (EP-0036 §6, gate row \"browser
  correctness\").

  `v/route-link` is a framework-supplied VIEW, not an intrinsic — it
  renders a real `<a>` with a routing-owned href and intercepts ONLY the
  plain-navigation click, leaving a modifier click, a `:download` and a
  non-default `:target` to the browser. None of that is structural: it is
  whether a real anchor resolves a real URL, and whether a real
  `MouseEvent` is intercepted or left native.

  The mode dimension: route-link is an ordinary declared view, so a
  compiled PARENT that contains `[v/route-link …]` composes it exactly as
  an interpreted parent does. Each claim — a real resolved anchor, a plain
  click that navigates, and a modifier / download / target click left
  native — is asserted with the route-link mounted under an interpreted
  parent AND a compiled one, and the two build the same anchor.

  Navigation is suppressed by a document-level guard so a click the
  framework leaves native does not navigate the harness page; interception
  is read from the routing dispatch (`:rf.route/url-requested`), which
  fires exactly when route-link intercepts.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node it
  has no DOM and says so."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.routing :as routing]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :async?  true
     :init-fn routing/reset-counters!}))

(def ^:private host-frame :matrix.route-link/frame)

;; ---------------------------------------------------------------------------
;; Twins — a route-link under an interpreted parent and a compiled one,
;; one nav per scenario, props LITERAL so the compiled parent composes the
;; child boundary at build time.
;; ---------------------------------------------------------------------------

(v/defview nav-plain-interpreted
  [_]
  [:nav [v/route-link {:to :matrix.route/article :params {:slug "the-intro"} :class "title"} "Read"]])

(v/defview nav-plain-compiled
  {:compiled true}
  [_]
  [:nav [v/route-link {:to :matrix.route/article :params {:slug "the-intro"} :class "title"} "Read"]])

(v/defview nav-download-interpreted
  [_]
  [:nav [v/route-link {:to :matrix.route/article :params {:slug "the-intro"} :download "intro.pdf"} "Download"]])

(v/defview nav-download-compiled
  {:compiled true}
  [_]
  [:nav [v/route-link {:to :matrix.route/article :params {:slug "the-intro"} :download "intro.pdf"} "Download"]])

(v/defview nav-target-interpreted
  [_]
  [:nav [v/route-link {:to :matrix.route/article :params {:slug "the-intro"} :target "_blank"} "New tab"]])

(v/defview nav-target-compiled
  {:compiled true}
  [_]
  [:nav [v/route-link {:to :matrix.route/article :params {:slug "the-intro"} :target "_blank"} "New tab"]])

(def ^:private plain-modes
  [["interpreted" nav-plain-interpreted]
   ["compiled"    nav-plain-compiled]])

(def ^:private native-scenarios
  ;; [label view event-init why]
  [["modifier/interpreted" nav-plain-interpreted    #js {:metaKey true} "a modifier click"]
   ["modifier/compiled"    nav-plain-compiled       #js {:metaKey true} "a modifier click"]
   ["download/interpreted" nav-download-interpreted #js {}             "a :download anchor"]
   ["download/compiled"    nav-download-compiled    #js {}             "a :download anchor"]
   ["target/interpreted"   nav-target-interpreted   #js {}             "a non-default :target"]
   ["target/compiled"      nav-target-compiled      #js {}             "a non-default :target"]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- setup! []
  (routing/reg-route :matrix.route/article {} "/articles/:slug")
  (rf/make-frame {:id host-frame :initial-events [[:rf/set-db {}]]})
  host-frame)

(defn- render! [root view]
  (ms/act #(.render root (shell/provide-frame host-frame (fr/element [view {}])))))

(defn- anchor [container] (.querySelector container "a"))

(defn- with-nav-guard
  "Suppress real navigation for `thunk` — a click the framework leaves
  native must not navigate the harness page — then run it."
  [thunk]
  (let [guard (fn [e] (.preventDefault e))]
    (.addEventListener js/document "click" guard)
    (try (thunk) (finally (.removeEventListener js/document "click" guard)))))

(defn- dispatched?
  "Fire a real `MouseEvent` at `a` and answer whether route-link
  INTERCEPTED it — a routing `:rf.route/url-requested` dispatch. Interception
  and `preventDefault` are one decision in route-link, so the dispatch is a
  faithful proxy for 'the framework took the click'."
  [a event-init]
  (let [hit (atom false)
        k   (keyword (gensym "matrix-route-"))]
    (trace-tooling/register-listener!
      k
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          (reset! hit true))))
    (try
      (with-nav-guard
        #(.dispatchEvent a (js/MouseEvent. "click"
                                           (js/Object.assign #js {:bubbles true :cancelable true}
                                                             event-init))))
      @hit
      (finally (trace-tooling/unregister-listener! k)))))

;; ===========================================================================
;; Row 1 — a real anchor with a resolved href, both modes
;; ===========================================================================

(deftest route-link-matrix-mounts-a-real-anchor-with-a-resolved-href-in-both-modes
  (testing "The mounted node is an `HTMLAnchorElement` — not a div wearing a
            click handler — whose href is the route's synthesised URL and
            whom the browser resolves to a real absolute URL. In each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the anchor assertions")
      (async done
        (ms/each-mode
          plain-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view)
                  (.then (fn [_]
                           (let [a (anchor container)]
                             (is (instance? js/HTMLAnchorElement a)
                                 (str label ": a real anchor element"))
                             (is (= "/articles/the-intro" (.getAttribute a "href"))
                                 (str label ": the href is synthesised from the route"))
                             (is (= (str (.-origin js/location) "/articles/the-intro") (.-href a))
                                 (str label ": and the browser resolves it against the document")))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 2 — a plain click navigates, both modes
;; ===========================================================================

(deftest route-link-matrix-a-plain-click-navigates-in-both-modes
  (testing "A plain left click on a same-origin route-link is INTERCEPTED —
            the framework takes it and dispatches a routing request rather
            than letting the browser reload. This is the positive control
            for the native rows below. In each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the plain-click assertions")
      (async done
        (ms/each-mode
          plain-modes
          (fn [[label view]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view)
                  (.then (fn [_]
                           (is (true? (dispatched? (anchor container) #js {}))
                               (str label ": a plain click dispatched a routing request"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 3 — native clicks keep their native behaviour, both modes
;; ===========================================================================

(deftest route-link-matrix-native-clicks-keep-native-behaviour-in-both-modes
  (testing "A modifier click, a `:download` anchor and a non-default
            `:target` are LEFT to the browser — route-link intercepts none
            of them, so no routing request is dispatched and the native
            action stands. Each scenario is asserted in each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the native-behaviour assertions")
      (async done
        (ms/each-mode
          native-scenarios
          (fn [[label view event-init why]]
            (setup!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root view)
                  (.then (fn [_]
                           (is (false? (dispatched? (anchor container) event-init))
                               (str label ": " why " is left native — nothing dispatched"))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 4 — both modes build the same anchor
;; ===========================================================================

(deftest route-link-matrix-both-modes-build-the-same-anchor
  (testing "The route-link anchor — its href, its class, its text — is the
            SAME real DOM whether composed by an interpreted parent or a
            compiled one. route-link is a shared declared view; promotion of
            its caller must not change the anchor it renders."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the parity assertion")
      (async done
        (setup!)
        (let [[ci ri] (ms/create-root!)
              [cc rc] (ms/create-root!)]
          (-> (render! ri nav-plain-interpreted)
              (.then (fn [_] (render! rc nav-plain-compiled)))
              (.then (fn [_]
                       (ms/outlines-agree? (anchor ci) (anchor cc) "route-link anchor")
                       (is (= "/articles/the-intro" (.getAttribute (anchor ci) "href"))
                           "non-vacuous: the shared anchor really carries the route href")))
              ;; Reports and RELEASES; it never finishes (rf2-o0n1). `done` runs
              ;; the whole remainder of the run synchronously, so a `.catch`
              ;; downstream of it would claim a later namespace's throw as this
              ;; row's and fire `done` a second time.
              (.catch (fn [e] (is false (str "a route-link mount rejected: " e)) nil))
              ;; Both arms tore both roots down identically, so the teardown
              ;; rides the single trailing step: written once, run once per path.
              (.then (fn [_] (ms/destroy-root! ci ri) (ms/destroy-root! cc rc) (done)))))))))
