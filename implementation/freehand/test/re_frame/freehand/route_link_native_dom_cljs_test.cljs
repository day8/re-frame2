(ns re-frame.freehand.route-link-native-dom-cljs-test
  "FH-ROUTELINK-001 (browser half), -002, -003 and the browser half of
  -007 — the real-browser contract of `v/route-link`.

  This file exists because the failure it guards against is INVISIBLE to
  a structural test. A hand-rolled link renders, and its plain click
  works; the bug shows up the first time a user cmd-clicks to open a page
  in a background tab and gets a same-tab navigation instead, or
  middle-clicks a nav item and nothing happens, or clicks a download link
  and downloads nothing. Every one of those is a property of a REAL
  MouseEvent travelling through a REAL DOM to the browser's default
  action — so that is what is asserted here, one case per way a click can
  be non-plain.

  ## How a native click is observed without navigating the test page

  A click that the framework declines to intercept still carries the
  browser's default action — following the `href`, opening a tab,
  starting a download. So each case registers a PROBE listener on the
  anchor AFTER the framework's own handler. Registration order is
  listener order, so the probe runs immediately after the framework has
  decided, reads `defaultPrevented` — which IS the framework's verdict,
  since intercepting means calling `preventDefault` — and only then
  cancels the event itself, keeping the harness page where it is.

  The other half of each verdict is whether a routing dispatch happened,
  read off the trace stream. Intercepted means prevented AND dispatched;
  native means neither.

  ## What materialises the anchor

  The interpreted emitter — the thing that turns a rendered vector into
  DOM — lands with its own slice. Until it does, `mount-anchor!` below
  materialises the anchor this view rendered: it sets the attributes the
  view produced and registers the `:on-click` the view produced, and
  invents nothing. What is under test is the anchor and its handler; the
  three lines that put them in the document are the fixture, not the
  subject.

  Browser-only. The `-dom-cljs-test` suffix opts this file into the
  `:browser-test` build; the node builds load it too and every case gates
  on `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.routing :as routing]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn routing/reset-counters!}))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(def ^:private host-frame :fh.route-link/dom-host)

;; ---------------------------------------------------------------------------
;; Rendering, materialising, clicking
;; ---------------------------------------------------------------------------

(defn- render
  "Render one `[v/route-link props]` call through the declared view body,
  inside frame scope — the same two steps a mount performs, minus the
  host emitter."
  [props]
  (rf/with-frame host-frame
    ((:render (.-entry v/route-link))
     (:props (descriptor/normalize-call v/route-link [props])))))

(defn- with-anchor-host
  "Run `f` with a fresh container element attached to the document, and
  remove that container afterwards.

  Scoped to its own element deliberately: the browser runner renders its
  own report into the page, so a suite that reached for `document.body`
  would erase the thing reporting on it."
  [f]
  (let [host (.createElement js/document "div")]
    (.setAttribute host "data-fh-route-link-host" "")
    (.appendChild (.-body js/document) host)
    (try (f host)
         (finally (.remove host)))))

(defn- dom-event-name
  "The DOM event a Freehand `:on-*` key names — `:on-mouse-enter` is
  `\"mouseenter\"`. The one place this mapping lives in this fixture."
  [k]
  (-> (name k) (subs 3) (str/replace "-" "")))

(defn- mount-anchor!
  "Put the rendered `[:a attrs & children]` into `host` as a real anchor:
  the view's attribute values as real attributes, and every FUNCTION the
  view produced as a real listener on the event its key names. Returns
  the element.

  Function-valued rather than `:on-click`-only, because the view installs
  handlers at more than one position now (a `:prefetch :intent` link binds
  hover, focus and touch-start too) and a fixture that knew the roster
  would have to be edited to keep the subject honest."
  [host [_ attrs & children]]
  (let [a (.createElement js/document "a")]
    (doseq [[k value] attrs]
      (if (fn? value)
        (.addEventListener a (dom-event-name k) value)
        (.setAttribute a (name k) (str value))))
    (set! (.-textContent a) (apply str children))
    (.appendChild host a)
    a))

(defn- click!
  "Dispatch a REAL `MouseEvent` at `a` and report the framework's verdict.

  Returns `{:intercepted? … :dispatched?  …}`. `:intercepted?` is
  `defaultPrevented` read the instant the framework's handler returned;
  `:dispatched?` is whether a `:rf.route/url-requested` reached the
  router. The probe cancels the event after reading, so a click the
  framework leaves native does not navigate the harness page."
  [a event-init]
  (let [verdict    (atom nil)
        dispatched (atom false)
        probe      (fn [e]
                     (reset! verdict (.-defaultPrevented e))
                     (.preventDefault e))
        listener-k (keyword (gensym "fh-routelink-"))]
    (.addEventListener a "click" probe)
    (trace-tooling/register-listener!
      listener-k
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/url-requested (-> ev :tags :rf.event/v first)))
          (reset! dispatched true))))
    (try
      (.dispatchEvent a (js/MouseEvent. "click"
                                        (clj->js (merge {:bubbles   true
                                                         :cancelable true}
                                                        event-init))))
      {:intercepted? @verdict :dispatched? @dispatched}
      (finally
        (.removeEventListener a "click" probe)
        (trace-tooling/unregister-listener! listener-k)))))

(defn- setup!
  "Register the fixture's routes and the host frame. The frame is
  deliberately NOT `:url-bound? true`: a link click here must not reach
  for the address bar of the page running the tests."
  [routes]
  (doseq [[route-id path] routes]
    (routing/reg-route route-id {} path))
  (rf/make-frame {:id host-frame :initial-events [[:rf/set-db {}]]}))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-001 (browser half) — it really is an anchor
;; ---------------------------------------------------------------------------

(def routelink-001 (conf/fixture :FH-ROUTELINK-001))

(deftest fh-routelink-001-mounts-a-real-anchor-element-with-a-resolved-href
  (testing "Per FH-ROUTELINK-001, in a real browser: the mounted node is
            an HTMLAnchorElement — not a div wearing a click handler — and
            the browser resolves its href to a real absolute URL. That
            resolution is what copy-link, open-in-new-tab, the status bar
            and every crawler read."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-001))
        (with-anchor-host
          (fn [host]
            (doseq [{:keys [why props href]} (:cases routelink-001)]
              (let [a (mount-anchor! host (render props))]
                (is (instance? js/HTMLAnchorElement a)
                    (str why " — a real anchor element"))
                (is (= href (.getAttribute a "href"))
                    (str why " — the href attribute is the route URL"))
                (is (= (str (.-origin js/location) href) (.-href a))
                    (str why " — and the browser resolves it against the document"))))
            (let [{:keys [props attrs]} (:passthrough routelink-001)
                  a                     (mount-anchor! host (render props))]
              (doseq [[k expected] attrs]
                (is (= expected (.getAttribute a (name k)))
                    (str (name k) " reaches the DOM"))))))))))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-002 — native browser behaviour is not intercepted
;; ---------------------------------------------------------------------------

(def routelink-002 (conf/fixture :FH-ROUTELINK-002))

(deftest fh-routelink-002-native-clicks-keep-their-native-behaviour
  (testing "Per FH-ROUTELINK-002: a modifier click, an auxiliary-button
            click, a `:download` anchor and a non-`_self` `:target` are
            left to the browser — the default action stands and NOTHING
            is dispatched. The plain-click rows are the positive control:
            without them, a view that intercepted nothing at all would
            pass every other row in this table."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-002))
        (with-anchor-host
          (fn [host]
            (doseq [{:keys [why props event intercepted? dispatched?]} (:cases routelink-002)]
              (let [a       (mount-anchor! host (render props))
                    verdict (click! a event)]
                (is (= intercepted? (:intercepted? verdict))
                    (str why " — default action "
                         (if intercepted? "prevented" "left intact")))
                (is (= dispatched? (:dispatched? verdict))
                    (str why " — routing dispatch "
                         (if dispatched? "made" "not made")))))))))))

(deftest fh-routelink-002-native-attributes-reach-the-dom
  (testing "Per FH-ROUTELINK-002: declining to intercept is only half of
            it. `target` and `download` have to REACH the element, or the
            browser has nothing to act on and the anchor is dead rather
            than native."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-002))
        (with-anchor-host
          (fn [host]
            (doseq [{:keys [props attr value]} (:native-attrs routelink-002)]
              (let [a (mount-anchor! host (render props))]
                (is (= value (.getAttribute a attr))
                    (str attr " renders on the anchor"))))))))))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-003 — the caller's :on-click runs first and may veto
;; ---------------------------------------------------------------------------

(def routelink-003 (conf/fixture :FH-ROUTELINK-003))

(deftest fh-routelink-003-a-caller-on-click-runs-first-and-can-veto
  (testing "Per FH-ROUTELINK-003: an analytics ping, a confirm dialog or
            an unsaved-changes guard needs to run BEFORE the navigation
            decision and to be able to stop it. So `:on-click` is not
            merged, wrapped, or run afterwards — it runs first, sees an
            event nothing has prevented yet, and if it prevents the
            default the framework stands down."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-003))
        (with-anchor-host
          (fn [host]
            (doseq [{:keys [why props veto? caller-ran? dispatched?]} (:cases routelink-003)]
              (let [ran       (atom false)
                    prevented (atom nil)
                    on-click  (fn [e]
                                (reset! ran true)
                                (reset! prevented (.-defaultPrevented e))
                                (when veto? (.preventDefault e)))
                    a         (mount-anchor! host (render (assoc props :on-click on-click)))
                    verdict   (click! a {:button 0})]
                (is (= caller-ran? @ran) (str why " — the caller ran"))
                (is (= (:default-prevented-when-caller-runs (:ordering routelink-003))
                       @prevented)
                    (str why " — and ran BEFORE the framework's decision"))
                (is (= dispatched? (:dispatched? verdict))
                    (str why " — routing dispatch "
                         (if dispatched? "made" "not made")))))))))))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-007 — both accepted forms run once, and both can veto
;; ---------------------------------------------------------------------------

(def routelink-007 (conf/fixture :FH-ROUTELINK-007))

(defn- accepted-on-click
  "Build the fixture's named accepted `:on-click` form over one body.
  EDN names a form; only code can carry a function or a declared
  callback."
  [form body]
  (case form
    :bare-fn   (fn [e] (body e))
    :v-handler (v/handler [e] (body e))))

(deftest fh-routelink-007-both-accepted-forms-veto-in-a-real-browser
  (testing "Per FH-ROUTELINK-007, against real MouseEvents: the two
            accepted spellings of the pre-navigation seam behave
            identically. Each runs EXACTLY ONCE, before the framework has
            decided anything, and each can stop the navigation with
            `.preventDefault`. The `v/handler` arm is the one that would
            silently break: a roster callback is deliberately not `IFn`,
            so nothing could have called it unless the boundary unwrapped
            it first."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-007))
        (with-anchor-host
          (fn [host]
            (doseq [{form :form accepted-why :why} (:accepted routelink-007)
                    {:keys [why veto? caller-runs dispatched?]} (:veto-cases routelink-007)]
              (let [runs      (atom 0)
                    prevented (atom nil)
                    on-click  (accepted-on-click
                                form
                                (fn [e]
                                  (swap! runs inc)
                                  (reset! prevented (.-defaultPrevented e))
                                  (when veto? (.preventDefault e))))
                    a         (mount-anchor! host (render (assoc (:props routelink-007)
                                                                 :on-click on-click)))
                    verdict   (click! a {:button 0})
                    label     (str accepted-why " / " why)]
                (is (= caller-runs @runs) (str label " — the caller ran exactly once"))
                (is (false? @prevented)
                    (str label " — and ran BEFORE the framework's decision"))
                (is (= dispatched? (:dispatched? verdict))
                    (str label " — routing dispatch "
                         (if dispatched? "made" "not made")))))))))))

;; ---------------------------------------------------------------------------
;; FH-ROUTELINK-008 — hover, focus and touch warm the destination
;; ---------------------------------------------------------------------------

(def routelink-008 (conf/fixture :FH-ROUTELINK-008))

(defn- with-prefetch-probe
  "Run `f` while recording, in order, every `:rf.route/prefetch` dispatch
  and the frame it targeted. Answers `[result recorded]`, where `recorded`
  is the interleaved log `f` appended to — so WHEN the dispatch happened
  relative to the caller's own handler is readable, not merely whether it
  did."
  [f]
  (let [log        (atom [])
        listener-k (keyword (gensym "fh-routelink-prefetch-"))]
    (trace-tooling/register-listener!
      listener-k
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/prefetch (-> ev :tags :rf.event/v first)))
          (swap! log conj {:dispatched (-> ev :tags :rf.event/v)
                           :frame      (-> ev :tags :frame)
                           :source     (:source ev)}))))
    (try [(f log) @log]
         (finally (trace-tooling/unregister-listener! listener-k)))))

(deftest fh-routelink-008-intent-warms-the-destination-in-a-real-browser
  (testing "Per FH-ROUTELINK-008, against real DOM events: hovering,
            focusing or touching an opted-in link warms the destination it
            points at. Each of the three positions dispatches exactly one
            `:rf.route/prefetch` carrying the link's OWN address, to the
            frame that RENDERED it — and the caller's own handler at that
            key still runs, FIRST, rather than being replaced by the one
            the framework installed."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-008))
        (with-anchor-host
          (fn [host]
            (doseq [k (:intent-keys routelink-008)]
              (let [[_ recorded]
                    (with-prefetch-probe
                      (fn [log]
                        (let [props (assoc (:props (:opt-in routelink-008))
                                           k (fn [_] (swap! log conj {:caller k})))
                              a     (mount-anchor! host (render props))]
                          (.dispatchEvent a (js/Event. (dom-event-name k) #js {:bubbles false})))))]
                (is (= [{:caller k}
                        {:dispatched (:event (:opt-in routelink-008))
                         :frame      host-frame
                         :source     :router}]
                       recorded)
                    (str k " — the caller ran first, then ONE prefetch for the link's own "
                         "address reached the rendering frame, stamped :source :router"))))))))))

(deftest fh-routelink-008-nothing-warms-without-the-opt-in
  (testing "Per FH-ROUTELINK-008: the half that matters. Rendering an
            opted-in link dispatches nothing — intent is a user act, not a
            render — and a link that never opted in dispatches nothing on
            hover either, while the author's own hover handler runs
            exactly as it always did."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-008))
        (with-anchor-host
          (fn [host]
            (let [[_ on-render]
                  (with-prefetch-probe
                    (fn [_] (mount-anchor! host (render (:props (:opt-in routelink-008))))))]
              (is (= [] on-render)
                  "a passive render warms nothing — there is no render mode"))
            (let [{:keys [why props]} (:passive routelink-008)
                  [ran recorded]
                  (with-prefetch-probe
                    (fn [_]
                      (let [ran (atom 0)
                            a   (mount-anchor! host (render (assoc props
                                                                   :on-mouse-enter
                                                                   (fn [_] (swap! ran inc)))))]
                        (.dispatchEvent a (js/Event. "mouseenter" #js {:bubbles false}))
                        @ran)))]
              (is (= 1 ran) (str why " — the author's handler runs, untouched"))
              (is (= [] recorded) (str why " — and nothing is warmed")))))))))

(deftest fh-routelink-007-a-non-vetoing-caller-is-unaffected
  (testing "Per FH-ROUTELINK-007: the control. A link with NO `:on-click`
            navigates exactly as it did before this contract existed —
            the narrowing applies to a prop, not to the view."
    (if-not (browser?)
      (is true "no DOM under the node builds — the :browser-test runner exercises this")
      (do
        (setup! (:routes routelink-007))
        (with-anchor-host
          (fn [host]
            (let [{:keys [why dispatched?]} (:absent routelink-007)
                  a       (mount-anchor! host (render (:props routelink-007)))
                  verdict (click! a {:button 0})]
              (is (= dispatched? (:dispatched? verdict)) why)
              (is (true? (:intercepted? verdict))
                  (str why " — and the framework still intercepts")))))))))
