(ns re-frame.ui.route-link-dom-cljs-test
  "Browser fixture for the compiled `ui/route-link` defview (rf2-vxgfnd.95.5).

  Proves the browser-specific value of the compiled view: mounted in a real
  React root it renders a REAL DOM `<a href=…>` with the route's strategy-encoded
  href (href truth — copy-link / open-in-new-tab / keyboard activation all rely
  on a real href) and forwards arbitrary passthrough attributes onto the anchor.
  A native anchor (`:target=\"_blank\"`) renders its native attribute so the
  browser owns the click.

  The full click law (plain-left intercept + `:source :router` committed-frame
  dispatch; modifier / middle / native deferral; caller `:on-click`-first veto)
  is proven against synthetic events in `route_link_seam_cljs_test` — the same
  node-with-synthetic-events pattern routing itself uses for `rf/route-link`
  (`route_link_cljs_test`). Driving a REAL plain-left click here would run the
  routing cascade and navigate the Playwright page (destroying the execution
  context), so the DOM fixture deliberately stops at the render contract.

  The `:prefetch :intent` arm IS driven with real DOM events, and belongs here
  rather than beside the click: a prefetch does not navigate, so hovering the
  mounted anchor is safe in this page, and hover-to-dispatch is exactly the
  behaviour a synthetic-event test cannot vouch for — whether the handler
  reached the real element at all.

  routing rides the TEST classpath (deps.edn :test alias) so its late-bind hooks
  publish; production `re-frame.ui` never requires it."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.ui :as ui]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]
            ;; publishes :routing/link-model + :routing/activate-link!
            ;; + :routing/prefetch-payload + :routing/prefetch-on-intent!
            [re-frame.routing]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

(deftest ui-route-link-renders-a-real-anchor-with-encoded-href-and-passthrough
  (when (browser?)
    (rf/reg-route :route/home {} "/home")
    (rf/reg-route :route/user {} "/users/:id")
    (rf/reg-route :route/user {} "/users/:id")
    (let [f (rf/make-frame {:id :route-link/host :initial-events [[:rf/set-db {}]]})]
      (async done
        (-> (uit/with-root
              [root [ui/frame-provider {:frame f}
                     [:nav
                      [ui/route-link {:to :route/home
                                      :class "nav"
                                      :aria-label "Home"
                                      :data-testid "home"} "Home"]
                      [ui/route-link {:to :route/user :params {:id "42"}} "User 42"]]]]
              (let [home (.querySelector root "a[data-testid='home']")
                    user (.querySelector root "a:not([data-testid])")]
                (testing "the compiled defview mounts real DOM anchors"
                  (is (some? home) "the home route-link mounted a real <a>")
                  (is (some? user) "the user route-link mounted a real <a>"))
                (testing "href truth — strategy-encoded (history default → path-form)"
                  (is (= "/home" (.getAttribute home "href")))
                  (is (= "/users/42" (.getAttribute user "href")) "params synthesise into the href"))
                (testing "children + arbitrary passthrough attrs reach the DOM"
                  (is (= "Home" (.-textContent home)))
                  (is (= "nav" (.getAttribute home "class")))
                  (is (= "Home" (.getAttribute home "aria-label")))
                  (is (= "home" (.getAttribute home "data-testid"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e]
                     (rf/destroy-frame! f)
                     (is false (str "route-link DOM fixture rejected: " e))
                     (done))))))))

;; ---------------------------------------------------------------------------
;; `:prefetch :intent` — hover / focus / touch warm the destination
;; ---------------------------------------------------------------------------

(defn- prefetch-log
  "Register a trace listener recording, in order, every `:rf.route/prefetch`
  dispatch with the frame it targeted and its origin tag. Answers
  `[log unregister!]` — the log is appended to by the listener AND by the
  caller's own handler, so ORDER is readable and not merely occurrence."
  []
  (let [log (atom [])
        k   (keyword (gensym "ui-route-link-prefetch-"))]
    (trace-tooling/register-listener!
      k
      (fn [ev]
        (when (and (= :rf.event/dispatched (:operation ev))
                   (vector? (-> ev :tags :rf.event/v))
                   (= :rf.route/prefetch (-> ev :tags :rf.event/v first)))
          (swap! log conj {:dispatched (-> ev :tags :rf.event/v)
                           :frame      (-> ev :tags :frame)
                           :source     (:source ev)}))))
    [log #(trace-tooling/unregister-listener! k)]))

(deftest ui-route-link-prefetch-intent-warms-on-real-dom-intent
  (when (browser?)
    (rf/reg-route :route/article {} "/articles/:slug")
    (let [f              (rf/make-frame {:id :route-link/host3 :initial-events [[:rf/set-db {}]]})
          [log unregister!] (prefetch-log)
          caller-ran     (atom 0)]
      (async done
        (-> (uit/with-root
              [root [ui/frame-provider {:frame f}
                     [:nav
                      [ui/route-link {:to :route/article
                                      :params {:slug "the-intro"}
                                      :prefetch :intent
                                      :on-mouse-enter (fn [_]
                                                        (swap! caller-ran inc)
                                                        (swap! log conj :caller))
                                      :data-testid "warm"} "Read"]
                      [ui/route-link {:to :route/article
                                      :params {:slug "cold"}
                                      :data-testid "cold"} "Cold"]]]]
              (let [warm (.querySelector root "a[data-testid='warm']")
                    cold (.querySelector root "a[data-testid='cold']")]
                (testing ":prefetch is a control key and never reaches the anchor"
                  (is (nil? (.getAttribute warm "prefetch"))
                      "prefetch=\"intent\" is not HTML and is stripped before emission")
                  (is (= "/articles/the-intro" (.getAttribute warm "href"))
                      "and the opt does not change where the link points"))
                (testing "a passive render warms nothing — intent is a user act"
                  (is (= [] @log)))
                (testing "hover dispatches ONE prefetch for the link's own address, after the caller"
                  (.dispatchEvent warm (js/MouseEvent. "mouseover" #js {:bubbles true}))
                  (is (= 1 @caller-ran) "the caller's own handler still runs")
                  (is (= [:caller
                          {:dispatched [:rf.route/prefetch
                                        {:to :route/article :params {:slug "the-intro"}}]
                           :frame      :route-link/host3
                           :source     :router}]
                         @log)
                      "composed, not replaced: caller first, then the warm-up on the rendering frame"))
                (testing "a link that did not opt in warms nothing on the same gesture"
                  (reset! log [])
                  (.dispatchEvent cold (js/MouseEvent. "mouseover" #js {:bubbles true}))
                  (is (= [] @log)))))
            (.then (fn [] (unregister!) (rf/destroy-frame! f) (done))
                   (fn [e]
                     (unregister!)
                     (rf/destroy-frame! f)
                     (is false (str "prefetch DOM fixture rejected: " e))
                     (done))))))))

(deftest ui-route-link-native-anchor-renders-its-native-attribute
  (when (browser?)
    (rf/reg-route :route/home {} "/home")
    (let [f (rf/make-frame {:id :route-link/host2 :initial-events [[:rf/set-db {}]]})]
      (async done
        (-> (uit/with-root
              [root [ui/frame-provider {:frame f}
                     [ui/route-link {:to :route/home :target "_blank"} "Home"]]]
              (let [a (.querySelector root "a")]
                (testing "a native anchor renders its target so the browser owns the click"
                  (is (= "_blank" (.getAttribute a "target")))
                  (is (= "/home" (.getAttribute a "href"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "native-anchor fixture rejected: " e)) (done))))))))
