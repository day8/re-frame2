(ns re-frame.hicasso.route-link-cljs-test
  "ROUTE-LINK'S GRAMMAR, tested as data.

  Everything here is the node-provable half: that a route-link render is
  a PURE value (two renders of one link are `=`, and the click decision
  is readable off the tree), that the routing-owned href is the SHIPPING
  link law's (the real `re-frame.routing` artefact publishes the seam;
  no stub), that every malformed form is loud at the position it was
  written, and that the veto composition is mechanical — the prevent
  closure runs first and routing's own `activate-link!` stands down on
  `defaultPrevented`. The real browser click, the real route change and
  the real page re-render are `shapes/route_link_dom_cljs_test`'s."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.hicasso.impl.intent :as rf.hicasso.impl.intent]
            [re-frame.hicasso.impl.route-link :as rf.hicasso.impl.route-link]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.routing :as rf.routing]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil}))

(def ^:private frame-id ::grammar)

(defn- fresh! []
  (rf.routing/reg-route :conduit.profile/show {} "/profile/:username")
  (rf/make-frame {:id frame-id :initial-events [[:rf/set-db {}]]})
  frame-id)

(defn- rendered
  "Render one link under the ambient context an arm binds — the 3-arity
  [[intent/with-frame]] — and answer the hiccup value."
  [props & children]
  (fresh!)
  (rf.hicasso.impl.intent/with-frame frame-id (fn [_] nil)
    (fn [] (apply rf.hicasso.impl.route-link/route-link props children))))

(defn- ev
  "A stand-in click event: `:button`/`:metaKey` select the click class,
  `:prevented` records `preventDefault`, and `defaultPrevented` mirrors
  it the way a real event's flag would."
  [{:keys [button meta? prevented]}]
  (let [e #js {:button           (or button 0)
               :metaKey          (boolean meta?)
               :ctrlKey          false :shiftKey false :altKey false
               :defaultPrevented false}]
    (unchecked-set e "preventDefault"
                   (fn []
                     (unchecked-set e "defaultPrevented" true)
                     (when prevented (reset! prevented true))
                     nil))
    e))

;; ---------------------------------------------------------------------------
;; The anchor is data
;; ---------------------------------------------------------------------------

(deftest two-renders-of-one-link-are-equal-data
  (let [a (rendered {:to :conduit.profile/show :params {:username "jane"} :class "author"} "jane")
        b (rendered {:to :conduit.profile/show :params {:username "jane"} :class "author"} "jane")]
    (is (= a b)
        "the whole anchor — href, click decision, veto — is a value `=` can
         see, which is the axis the prevent head was ruled on (HD-026) and
         the axis a closure-carrying anchor loses")
    (is (= :a (first a)))
    (is (= "/profile/jane" (:href (second a)))
        "the href is the routing artefact's own synthesis — no hand-built URL")
    (is (= "author" (:class (second a))) "passthrough attrs reach the anchor")
    (is (= ["jane"] (subvec a 2)) "children land inside the anchor")))

(deftest the-click-decision-is-readable-off-the-tree
  (let [[_ attrs] (rendered {:to :conduit.profile/show :params {:username "jane"}} "jane")
        on-click  (:on-click attrs)]
    (is (rf.hicasso.impl.intent/navigate-head? on-click) "the click position carries the navigate head")
    (let [{:keys [frame payload native? veto]} (second on-click)]
      (is (= frame-id frame) "the frame was captured at render, as data")
      (is (= [:rf.route/url-requested {:url "/profile/jane"
                                       :to :conduit.profile/show
                                       :params {:username "jane"}}]
             payload)
          "the payload is routing's own url-requested synthesis, in band")
      (is (false? native?))
      (is (nil? veto)))))

(deftest no-control-key-reaches-the-anchor
  (let [[_ attrs] (rendered {:to       :conduit.profile/show
                             :params   {:username "jane"}
                             :query    {:tab "posts"}
                             :fragment "bio"
                             :class    "author"}
                            "jane")]
    (is (= #{:href :on-click :class} (set (keys attrs)))
        "the address keys are route-link's OWN: they are consumed by the link
         model and never emitted. What reaches the anchor is the routing-owned
         href, the click decision, and the author's passthrough attrs — nothing
         else")))

(deftest a-native-anchor-is-classified-at-render
  (let [[_ attrs] (rendered {:to :conduit.profile/show :params {:username "jane"}
                             :target "_blank"} "jane")]
    (is (true? (:native? (second (:on-click attrs))))
        "target=_blank is routing's native-anchor verdict, carried as data")
    (is (= "_blank" (:target attrs)) "and the attribute still reaches the anchor")))

(deftest a-prevent-veto-rides-the-vector-as-data
  (let [[_ attrs] (rendered {:to :conduit.profile/show :params {:username "jane"}
                             :on-click [rf.hicasso.impl.intent/prevent-head [:conduit/track "jane"]]}
                            "jane")]
    (is (= [rf.hicasso.impl.intent/prevent-head [:conduit/track "jane"]]
           (:veto (second (:on-click attrs))))
        "the declarative veto is visible to `=` on the rendered tree")))

;; ---------------------------------------------------------------------------
;; Loud at the site that wrote it
;; ---------------------------------------------------------------------------

(deftest a-bare-intent-vector-at-on-click-is-refused-at-render
  (is (thrown-with-msg?
        js/Error #"hicasso-route-link-bad-on-click"
        (rendered {:to :conduit.profile/show :params {:username "jane"}
                   :on-click [:conduit/track "jane"]}
                  "jane"))
      "the click already produces the one routing intent; the bare vector is
       the taught mistake and fails at render with the teaching diagnostic"))

(deftest a-key-map-at-on-click-is-refused-at-render
  (is (thrown-with-msg?
        js/Error #"hicasso-route-link-bad-on-click"
        (rendered {:to :conduit.profile/show :params {:username "jane"}
                   :on-click {"Enter" [:conduit/track]}}
                  "jane"))))

(deftest a-link-outside-a-boundary-is-loud
  (fresh!)
  (is (thrown-with-msg?
        js/Error #"hicasso-route-link-outside-boundary"
        (rf.hicasso.impl.route-link/route-link {:to :conduit.profile/show :params {:username "jane"}} "jane"))))

(deftest a-prefetch-key-never-reaches-the-anchor
  (testing "`:prefetch` is routing's link-model key, not an HTML attribute,
           and Hicasso wires none of the prefetch handlers in v0 — so the key
           is one the link owns and it is kept off the anchor rather than
           emitted as a stray attribute"
    (let [[_ attrs] (rendered {:to :conduit.profile/show :params {:username "jane"}
                               :prefetch :intent}
                              "jane")]
      (is (not (contains? attrs :prefetch)))
      (is (string? (:href attrs)) "and the link still renders"))))

(defn- lower-navigate
  "Lower `[intent/navigate-head m]` at an event position and answer the closure."
  [m]
  (rf.hicasso.impl.intent/with-frame frame-id (fn [_] nil)
    (fn [] (rf.hicasso.impl.intent/lower-prop :on-click [rf.hicasso.impl.intent/navigate-head m]))))

(deftest the-navigate-map-lowers-to-a-closure
  (testing "HD-027's four keys — :frame, :payload, :native? and :veto — lower
           to the click closure; the map is `route-link`'s to mint, so the
           lowering reads it rather than re-validating it"
    (is (fn? (lower-navigate {:frame    frame-id
                              :payload  [:rf.route/url-requested {:to :conduit.profile/show}]
                              :native?  false
                              :veto     nil})))))

(deftest prevent-does-not-wrap-a-navigate
  (is (thrown-with-msg?
        js/Error #"hicasso-malformed-prevent"
        (rf.hicasso.impl.intent/with-frame frame-id (fn [_] nil)
          (fn [] (rf.hicasso.impl.intent/lower-prop
                   :on-click
                   [rf.hicasso.impl.intent/prevent-head
                    [rf.hicasso.impl.intent/navigate-head {:frame frame-id :payload [:x]
                                           :native? false :veto nil}]]))))
      "decorators do not nest, in either order"))

;; ---------------------------------------------------------------------------
;; The composition, mechanically
;; ---------------------------------------------------------------------------

(defn- lowered-click
  "Lower a rendered link's `:on-click` under a recording dispatch and
  answer `[closure !dispatched]` — the closure the browser would call."
  [props]
  (let [!seen  (atom [])
        [_ attrs] (rendered props "jane")
        h      (rf.hicasso.impl.intent/with-frame frame-id
                 (fn [ev] (swap! !seen conj ev) nil)
                 (fn [] (rf.hicasso.impl.intent/lower-prop :on-click (:on-click attrs))))]
    [h !seen]))

(deftest a-prevent-veto-cancels-the-navigation-and-dispatches-instead
  (let [[h !seen] (lowered-click {:to :conduit.profile/show :params {:username "jane"}
                                  :on-click [rf.hicasso.impl.intent/prevent-head [:conduit/track "jane"]]})
        !prevented (atom false)]
    (h (ev {:prevented !prevented}))
    (is (true? @!prevented)
        "the veto's prevent closure ran first and cancelled the default")
    (is (= [[:conduit/track "jane"]] @!seen)
        "…and dispatched the app intent — routing's activate-link! saw
         defaultPrevented and stood down, so one click yielded ONE semantic
         event: the intent that replaced the navigation")))

(deftest a-modifier-click-is-left-native
  (let [[h !seen] (lowered-click {:to :conduit.profile/show :params {:username "jane"}})
        !prevented (atom false)]
    (h (ev {:meta? true :prevented !prevented}))
    (is (false? @!prevented)
        "routing's own plain-left-click law deferred to the browser")
    (is (= [] @!seen))))

(deftest a-plain-click-is-intercepted
  (let [[h _] (lowered-click {:to :conduit.profile/show :params {:username "jane"}})
        !prevented (atom false)]
    (h (ev {:prevented !prevented}))
    (is (true? @!prevented)
        "a plain left click is routing's to take: preventDefault fired and the
         url-requested dispatch went to the captured frame (the route change
         itself is the DOM witness's claim)")))

;; ---------------------------------------------------------------------------
;; The imperative veto — the roster's other admitted half
;; ---------------------------------------------------------------------------

(defn- with-activate-link
  "Run `thunk` with `f` published at `:routing/activate-link!` — nil
  included, which is the detached-artefact arrangement — restoring the
  previous registration in `finally` so the suite's other rows keep
  running against routing's real seam."
  [f thunk]
  (let [previous (rf.late-bind/get-fn :routing/activate-link!)]
    (rf.late-bind/set-fn! :routing/activate-link! f)
    (try (thunk)
         (finally (rf.late-bind/set-fn! :routing/activate-link! previous)))))

(deftest a-function-veto-rides-the-vector-and-reaches-the-seam-by-identity
  (testing "the roster's IMPERATIVE half (HD-024: whoever holds the event
           owns it) had no exercise anywhere — every veto row used nil or
           the prevent head, so `on-click-roster!`'s fn arm and
           `lower-veto`'s could rot with every declarative row green. A
           plain function at :on-click renders, and rides the navigate
           vector's :veto slot by identity"
    (let [veto      (fn a-veto [_e] nil)
          [_ attrs] (rendered {:to :conduit.profile/show :params {:username "jane"}
                               :on-click veto}
                              "jane")]
      (is (identical? veto (:veto (second (:on-click attrs))))
          "the author's own function, untouched — where the declarative
           head is lowered into a closure, the imperative veto IS the
           closure already")))
  (testing "and the lowered click hands that same function to routing's
           `activate-link!` as the veto argument, the rest of the navigate
           map beside it. The composition itself — veto first, stand down
           on defaultPrevented — is routing's own tested law; hicasso's
           half, pinned here, is that the imperative veto arrives at the
           seam intact"
    (let [veto  (fn a-veto [_e] nil)
          !seam (atom nil)
          [h _] (lowered-click {:to :conduit.profile/show :params {:username "jane"}
                                :on-click veto})]
      (with-activate-link
        (fn [_e veto-fn frame payload native?]
          (reset! !seam {:veto-fn veto-fn :frame frame
                         :payload payload :native? native?})
          nil)
        #(h (ev {})))
      (let [{:keys [veto-fn frame payload native?]} @!seam]
        (is (identical? veto veto-fn) "the fn crossed the seam by identity")
        (is (= frame-id frame))
        (is (= [:rf.route/url-requested {:url    "/profile/jane"
                                         :to     :conduit.profile/show
                                         :params {:username "jane"}}]
               payload))
        (is (false? native?))))))

;; ---------------------------------------------------------------------------
;; The two ways routing can be gone, and neither is a throw
;; ---------------------------------------------------------------------------

(deftest a-detached-click-with-no-routing-hook-degrades-to-native-and-still-runs-the-veto
  (testing "`navigate-handler`'s own promise: when `:routing/activate-link!`
           is unbound at CLICK time — the routing artefact hot-reloaded
           away between render and click — the closure stands aside, so
           the browser follows the anchor's real href. No throw, no
           interception, no dispatch"
    (let [[h !seen]  (lowered-click {:to :conduit.profile/show :params {:username "jane"}})
          !prevented (atom false)]
      (with-activate-link nil #(h (ev {:prevented !prevented})))
      (is (false? @!prevented)
          "nothing intercepted the click — native navigation is the degrade")
      (is (= [] @!seen) "and nothing dispatched")))
  (testing "…and the veto still runs. The imperative fn is invoked with
           the event"
    (let [!ran  (atom false)
          [h _] (lowered-click {:to :conduit.profile/show :params {:username "jane"}
                                :on-click (fn [_e] (reset! !ran true) nil)})]
      (with-activate-link nil #(h (ev {})))
      (is (true? @!ran))))
  (testing "…and the declarative prevent veto still cancels and dispatches
           its replacement intent — the app's reaction survives the
           routing artefact's absence, which is what keeps the degrade a
           navigation policy rather than a lost click"
    (let [[h !seen]  (lowered-click {:to :conduit.profile/show :params {:username "jane"}
                                     :on-click [rf.hicasso.impl.intent/prevent-head [:conduit/track "jane"]]})
          !prevented (atom false)]
      (with-activate-link nil #(h (ev {:prevented !prevented})))
      (is (true? @!prevented))
      (is (= [[:conduit/track "jane"]] @!seen)))))

(deftest a-missing-routing-artefact-fails-at-the-link-site-naming-the-artefact-and-the-to
  (testing "route-link's other absence: no `:routing/link-model` at RENDER
           means the artefact is off the classpath, and the refusal is
           the link site's own — the id a tool branches on, the link's
           :to, and the two coordinates the author needs to repair it.
           Asserted as ex-data rather than a message regex, per the
           refusal-shape convention"
    (let [previous (rf.late-bind/get-fn :routing/link-model)]
      (try
        (rf.late-bind/set-fn! :routing/link-model nil)
        (let [data (try
                     (rendered {:to :conduit.profile/show :params {:username "jane"}} "jane")
                     ::returned-without-refusing
                     (catch :default e (ex-data e)))]
          (is (= :rf.error/routing-artefact-missing (:rf.error/id data)))
          (is (= :conduit.profile/show (:to data))
              "the refusal names the link's own :to")
          (is (re-find #"day8/re-frame2-routing" (str (:reason data)))
              "…and the dependency to add")
          (is (re-find #"re-frame\.routing" (str (:reason data)))
              "…and the namespace to require at boot"))
        (finally (rf.late-bind/set-fn! :routing/link-model previous))))))
