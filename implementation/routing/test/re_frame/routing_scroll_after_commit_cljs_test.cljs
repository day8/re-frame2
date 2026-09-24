(ns re-frame.routing-scroll-after-commit-cljs-test
  "`:rf.nav/scroll` touches the page AFTER the view substrate
  commits the navigation, not inside the navigating event.

  The fx runs as the last `:fx` of the committing event, before any render.
  Scrolling there reads and moves the page being LEFT: a cross-route
  `:fragment` finds no element and lands at the top, and a Back `:restore` to
  a taller page is clamped to the shorter one. The handler schedules its
  DOM half through the installed adapter's `:adapter/after-render` hook.

  The node lane has no renderer, so it cannot show a commit. It shows the
  ORDERING the deferral rests on, against a controllable hook standing in for the
  adapter's: the hook queues its callback and answers nil (as UIx's does once
  it has scheduled), and `flush!` is the commit. The real-page witness is the
  browser lane's `re-frame.routing-conduct-dom-cljs-test`.

  ns ends in `-cljs-test`, so the `:node-test` build runs it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.scroll :as rf.routing.scroll]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.routing-browser-test-support :refer [with-window-stub-fixture]]))

(use-fixtures :each
  with-window-stub-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (rf.routing/reset-counters!)
                (rf.routing/reset-scroll-cache!))}))

;; ---- helpers -------------------------------------------------------------

(defn- count-dom-calls!
  "Record every `window.scrollTo` the handler makes, still moving the stub."
  []
  (let [calls     (atom [])
        scroll-to (.-scrollTo js/window)]
    (set! (.-scrollTo js/window)
          (fn [x y] (swap! calls conj [:scroll-to x y]) (scroll-to x y)))
    calls))

(defn- with-queued-after-render
  "Run `(f flush!)` with `:adapter/after-render` replaced by a hook that queues
  its callback and RETURNS NIL. `flush!` runs what was queued — the commit."
  [f]
  (let [original (rf.late-bind/get-fn :adapter/after-render)
        queued   (atom [])
        flush!   (fn [] (let [q @queued] (reset! queued []) (run! #(%) q)))]
    (try
      (rf.late-bind/set-fn! :adapter/after-render (fn [g] (swap! queued conj g) nil))
      (f flush!)
      (finally (rf.late-bind/set-fn! :adapter/after-render original)))))

(defn- register-routes! []
  (rf/reg-route :scroll/home {} "/")
  (rf/reg-route :scroll/list {} "/list")
  (rf/reg-route :scroll/detail {} "/detail"))

;; ---- the deferral ----------------------------------------------------------

(deftest the-page-moves-once-and-only-after-the-commit
  (testing "with an after-render hook, the fx makes NO DOM call;
            the page moves exactly once, when the hook fires. The hook answers
            nil after scheduling, so the decision cannot rest on its return
            value — a nil-means-absent fallback would scroll twice"
    (doseq [[args expected] [[{:strategy :top}                        [[:scroll-to 0 0]]]
                             [{:strategy :restore :saved-pos [0 420]} [[:scroll-to 0 420]]]]]
      (with-queued-after-render
        (fn [flush!]
          (let [calls (count-dom-calls!)]
            (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} args)
            (is (= [] @calls) (str (:strategy args) ": no DOM call inside the event"))
            (flush!)
            (is (= expected @calls) (str (:strategy args) ": one, after the commit"))))))))

(deftest a-fragment-on-the-arriving-page-is-found
  (testing "`#install` exists only on the page being navigated
            TO. Looked up inside the event it is missing and the page falls back
            to the top; looked up after the commit it is found"
    (with-queued-after-render
      (fn [flush!]
        (let [committed? (atom false)
              into-view  (atom 0)
              el         #js {:scrollIntoView (fn [] (swap! into-view inc))}
              calls      (count-dom-calls!)]
          (set! (.-getElementById js/document)
                (fn [id] (when (and @committed? (= "install" id)) el)))
          (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                               {:strategy :top :fragment "install"})
          (reset! committed? true)
          (flush!)
          (is (= 1 @into-view) "the arriving page's #install was scrolled into view")
          (is (= [] @calls) "and the page never fell back to the top"))))))

(deftest with-no-hook-the-page-moves-at-once
  (testing "a host publishing no after-render hook scrolls
            immediately — no timer is invented for it"
    (let [original (rf.late-bind/get-fn :adapter/after-render)]
      (try
        (rf.late-bind/set-fn! :adapter/after-render nil)
        (let [calls (count-dom-calls!)]
          (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                               {:strategy :restore :saved-pos [0 420]})
          (is (= [[:scroll-to 0 420]] @calls)))
        (finally (rf.late-bind/set-fn! :adapter/after-render original))))))

(deftest with-no-adapter-installed-the-page-moves-at-once
  (testing "loading an adapter publishes its ROUTED hook, which
            with no adapter installed falls back to a no-op that never calls
            the callback. That is a hookless host, so the scroll runs at once
            rather than being dropped"
    (is (some? (rf.late-bind/get-fn :adapter/after-render))
        "precondition: the routed hook is published")
    (rf.substrate.adapter/dispose-adapter!)
    (is (nil? (rf.substrate.adapter/current-adapter)) "precondition: none installed")
    (let [calls (count-dom-calls!)]
      (rf.routing.scroll/scroll-fx-handler {:frame :rf/default}
                                           {:strategy :restore :saved-pos [0 420]})
      (is (= [[:scroll-to 0 420]] @calls)))))

(deftest the-preserve-strategy-schedules-nothing
  (testing "`:preserve` queues no callback and moves nothing.
            (The unsupported-strategy rejection stays inside the event; the
            `routing-nav-fx-schemas-cljs-test` rejection rows pin that.)"
    (with-queued-after-render
      (fn [flush!]
        (let [calls (count-dom-calls!)]
          (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :preserve})
          (flush!)
          (is (= [] @calls)))))))

(deftest a-deferred-scroll-on-a-pageless-host-does-nothing
  (testing "deferred, the DOM half runs outside the fx's error
            isolation — a throw there escapes into the adapter's render queue —
            so a client host with no `window` must not throw from it"
    (with-queued-after-render
      (fn [flush!]
        (rf.routing.scroll/scroll-fx-handler {:frame :rf/default} {:strategy :top})
        (let [w (.-window js/globalThis)]
          (js-delete js/globalThis "window")
          (try
            (is (nil? (flush!)) "the queued scroll ran and did nothing")
            (finally (set! (.-window js/globalThis) w))))))))

;; ---- traversal scroll belongs to the runtime ---------------------------------

(deftest installing-the-url-listener-claims-traversal-scroll
  (testing "under the browser's default \"auto\" its own traversal
            restore would race `:rf.nav/scroll` on Back and win. The URL
            listener's install sets `history.scrollRestoration` to \"manual\""
    (set! (.-scrollRestoration (.-history js/window)) "auto")
    (rf/make-frame {:id :rf/default :url-bound? true})
    (is (= "manual" (.-scrollRestoration (.-history js/window))))))

;; ---- a superseded navigation ------------------------------------------------

(deftest a-superseded-navigations-scroll-is-dropped
  (register-routes!)
  (with-queued-after-render
    (fn [flush!]
      (let [calls (count-dom-calls!)]
        (testing "control: a navigation's deferred scroll runs at the commit"
          (rf/dispatch-sync [:rf.route/navigate {:to :scroll/list}])
          (is (= [] @calls))
          (flush!)
          (is (= [[:scroll-to 0 0]] @calls)))
        (reset! calls [])
        (testing "a later navigation that commits before the
                  render and asks for no scroll of its own is not moved by the
                  earlier navigation's scroll"
          (rf/dispatch-sync [:rf.route/navigate {:to :scroll/detail}])
          (rf/dispatch-sync [:rf.route/navigate {:to :scroll/home :scroll false}])
          (flush!)
          (is (= [] @calls)))))))

(deftest a-scroll-never-crosses-into-a-new-incarnation-of-its-frame
  (testing "a frame destroyed and rebuilt under the same id
            restarts its nav-tokens, so the token alone would match. The
            earlier incarnation's scroll is dropped; the new one's runs"
    (register-routes!)
    (with-queued-after-render
      (fn [flush!]
        (let [calls (count-dom-calls!)]
          (rf/dispatch-sync [:rf.route/navigate {:to :scroll/list}])
          (rf/destroy-frame! :rf/default)
          (rf/make-frame {:id :rf/default})
          (rf/dispatch-sync [:rf.route/navigate {:to :scroll/list}])
          (flush!)
          (is (= [[:scroll-to 0 0]] @calls) "one scroll, the live frame's"))))))
