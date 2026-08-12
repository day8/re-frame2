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

  The `:prefetch :intent` arms ARE driven with real DOM events, and belong here
  rather than beside the click: a prefetch does not navigate, so hovering,
  focusing and touching the mounted anchor are all safe in this page, and
  intent-to-dispatch is exactly the behaviour a synthetic-event test cannot
  vouch for — whether the handler reached the real element at all, under the
  real event name. EVERY position of the closed intent class
  (`re-frame.routing.link/prefetch-intent-keys`) gets a real-DOM arm here, not
  just the pointer one: `ui/route-link` is the one link surface still writing
  the three positions out LITERALLY instead of mapping over that class
  (rf2-drpa3.57), so a per-position gesture is what would catch the copy
  drifting from the law.

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
            [re-frame.routing]
            ;; the closed intent class itself — read so the roster of arms below
            ;; can be checked against the law rather than restated from memory
            [re-frame.routing.link :as routing-link]))

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

(def ^:private arms-driven
  "The intent positions THIS fixture drives with real DOM events: pointer in
  `…-warms-on-real-dom-intent`, keyboard-focus and touch in
  `…-warms-on-real-focus-and-touch`. Checked against the published class
  below — a position added to the law with no gesture here would otherwise
  arrive unwitnessed on this surface, which is the exact failure the literal
  copy in `ui/route-link` invites."
  #{:on-mouse-enter :on-focus :on-touch-start})

(defn- touch-start-event
  "A real `touchstart` DOM event to dispatch at the anchor. Chromium exposes
  the `TouchEvent` constructor only on touch-capable builds, so fall back to
  the base `Event` under the SAME name: what is under test is React's
  delegated `touchstart` listener finding the compiled view's handler, and
  that listener keys off the event name, not the interface."
  []
  (if (exists? js/TouchEvent)
    (js/TouchEvent. "touchstart" #js {:bubbles true})
    (js/Event. "touchstart" #js {:bubbles true})))

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

(deftest ui-route-link-prefetch-intent-warms-on-real-focus-and-touch
  ;; The hover arm above proves the pointer position. These are the other two
  ;; members of the closed class — the keyboard user who Tabs onto the link and
  ;; the touch user whose finger lands on it — driven the same way: a REAL
  ;; gesture at the REAL element, not a call through the attrs map. `ui.cljc`
  ;; writes all three positions out literally, so each needs its own witness;
  ;; a copy that dropped one would sail through a fixture that only hovers.
  (when (browser?)
    (rf/reg-route :route/article {} "/articles/:slug")
    (let [f              (rf/make-frame {:id :route-link/host4 :initial-events [[:rf/set-db {}]]})
          [log unregister!] (prefetch-log)
          callers        (atom {})
          caller         (fn [position]
                           (fn [_]
                             (swap! callers update position (fnil inc 0))
                             (swap! log conj [:caller position])))
          gestures       [{:position :on-focus
                           :gesture  "a real .focus() — the Tab-to-a-link gesture"
                           :fire!    (fn [a]
                                       (.focus a)
                                       (is (identical? a (.-activeElement js/document))
                                           (str ":on-focus — .focus() moved the document's active "
                                                "element, so the gesture itself landed (without "
                                                "this, a silent no-op would read as a clean pass "
                                                "on the cold-link control)")))}
                          {:position :on-touch-start
                           :gesture  "a real touchstart at the anchor"
                           :fire!    (fn [a] (.dispatchEvent a (touch-start-event)))}]
          warmed         {:dispatched [:rf.route/prefetch
                                       {:to :route/article :params {:slug "the-intro"}}]
                          :frame      :route-link/host4
                          :source     :router}]
      (async done
        (-> (uit/with-root
              [root [ui/frame-provider {:frame f}
                     [:nav
                      [ui/route-link {:to :route/article
                                      :params {:slug "the-intro"}
                                      :prefetch :intent
                                      :on-focus (caller :on-focus)
                                      :on-touch-start (caller :on-touch-start)
                                      :data-testid "warm"} "Read"]
                      [ui/route-link {:to :route/article
                                      :params {:slug "cold"}
                                      :data-testid "cold"} "Cold"]]]]
              (let [warm (.querySelector root "a[data-testid='warm']")
                    cold (.querySelector root "a[data-testid='cold']")]
                (testing "a passive render warms nothing — intent is a user act"
                  (is (= [] @log)))
                (doseq [{:keys [position gesture fire!]} gestures]
                  (testing (str position " — " gesture
                                " dispatches ONE prefetch for the link's own address, after the caller")
                    (reset! log [])
                    (fire! warm)
                    (is (= 1 (get @callers position))
                        (str position " — the caller's own handler at this position still runs"))
                    (is (= [[:caller position] warmed] @log)
                        (str position " — composed, not replaced: caller first, then ONE warm-up "
                             "for this link's own address on the rendering frame, stamped "
                             ":source :router. A literal position missing from ui/route-link "
                             "shows up here as an empty log")))
                  (testing (str position " — a link that did not opt in warms nothing on the same gesture")
                    (reset! log [])
                    (fire! cold)
                    (is (= [] @log))))))
            (.then (fn [] (unregister!) (rf/destroy-frame! f) (done))
                   (fn [e]
                     (unregister!)
                     (rf/destroy-frame! f)
                     (is false (str "focus/touch prefetch DOM fixture rejected: " e))
                     (done))))))))

(deftest ui-route-link-prefetch-real-dom-arms-cover-the-whole-intent-class
  (testing "the three arms above are the WHOLE published class, not a sample of
            it — `re-frame.routing.link/prefetch-intent-keys` is routing's law
            and `ui/route-link` still copies it out literally, so a position
            added to the law lands on this surface only if someone edits the
            view. This assertion is the alarm: it reds the moment the class
            grows, naming the gesture this fixture owes it."
    (is (= arms-driven (set routing-link/prefetch-intent-keys)))))

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
