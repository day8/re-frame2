(ns re-frame.hicasso.route-link-cljs-test
  "ROUTE-LINK'S GRAMMAR, tested as data (rf2-2rtt6.54).

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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.impl.route-link :as link]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

(def ^:private frame-id ::grammar)

(defn- fresh! []
  (routing/reg-route :conduit.profile/show {} "/profile/:username")
  (rf/make-frame {:id frame-id :initial-events [[:rf/set-db {}]]})
  frame-id)

(defn- rendered
  "Render one link under the ambient context an arm binds — the 3-arity
  [[intent/with-frame]] — and answer the hiccup value."
  [props & children]
  (fresh!)
  (intent/with-frame frame-id (fn [_] nil)
    (fn [] (apply link/route-link props children))))

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
    (is (intent/navigate-head? on-click) "the click position carries the navigate head")
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
                             :on-click [intent/prevent-head [:conduit/track "jane"]]}
                            "jane")]
    (is (= [intent/prevent-head [:conduit/track "jane"]]
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
        (link/route-link {:to :conduit.profile/show :params {:username "jane"}} "jane"))))

(deftest a-prefetch-is-refused-at-render
  (testing "v0 declines routing's prefetch trio, and a DECLINED key fails
           rather than crossing — :intent included, which is the one routing's
           own link model validates and accepts, and which would otherwise
           reach the anchor as a stray attribute over a link that prefetches
           nothing"
    (doseq [v [:intent true false nil]]
      (is (thrown-with-msg?
            js/Error #"hicasso-route-link-prefetch-declined"
            (rendered {:to :conduit.profile/show :params {:username "jane"}
                       :prefetch v}
                      "jane"))
          (str ":prefetch " (pr-str v) " — presence is what is declined, not
               truthiness: a falsey value is just as much a site that believes
               it opted in")))))

(deftest a-malformed-navigate-vector-is-loud-at-lowering
  (testing "the navigate head is in-band data anyone can write, so the
           lowering is total over it — each violation names the position"
    (doseq [bad [[intent/navigate-head]
                 [intent/navigate-head {:frame frame-id}]
                 [intent/navigate-head "not-a-map"]
                 [intent/navigate-head {:frame frame-id :payload [] :native? false :veto nil}]
                 [intent/navigate-head {:frame frame-id :payload [:x] :native? "yes" :veto nil}]]]
      (is (thrown-with-msg?
            js/Error #"hicasso-malformed-navigate"
            (intent/with-frame frame-id (fn [_] nil)
              (fn [] (intent/lower-prop :on-click bad))))
          (pr-str bad)))))

(defn- lower-navigate
  "Lower `[::h/navigate m]` at an event position and answer the closure."
  [m]
  (intent/with-frame frame-id (fn [_] nil)
    (fn [] (intent/lower-prop :on-click [intent/navigate-head m]))))

(deftest the-navigate-map-is-a-closed-key-set
  (testing "HD-027 promises EXACTLY :frame, :payload, :native? and :veto, so
           the check is the exact key SET rather than four presence tests — a
           grammar that validates only the keys it knows is fail-open by
           construction, and admits both the map missing the veto slot (an
           uncancelable navigation where a cancelable one was promised) and
           the map carrying a fifth key the lowering then silently drops"
    (doseq [bad [{:frame frame-id :payload [:x] :native? false}
                 {:payload [:x] :native? false :veto nil}
                 {:frame frame-id :native? false :veto nil}
                 {:frame frame-id :payload [:x] :veto nil}
                 {:frame frame-id :payload [:x] :native? false :veto nil :replace? true}]]
      (is (thrown-with-msg? js/Error #"hicasso-malformed-navigate" (lower-navigate bad))
          (pr-str bad))))
  (testing "and the exact four keys still lower — :veto nil is PRESENT, which
           is why the slot is asked of the key set and never of its value"
    (is (fn? (lower-navigate {:frame    frame-id
                              :payload  [:rf.route/url-requested {:to :conduit.profile/show}]
                              :native?  false
                              :veto     nil})))))

(deftest a-bare-vector-at-the-veto-slot-is-loud-at-lowering
  (is (thrown-with-msg?
        js/Error #"hicasso-malformed-navigate"
        (intent/with-frame frame-id (fn [_] nil)
          (fn [] (intent/lower-prop
                   :on-click
                   [intent/navigate-head {:frame frame-id
                                          :payload [:rf.route/url-requested {:to :x}]
                                          :native? false
                                          :veto [:conduit/track]}]))))))

(deftest prevent-does-not-wrap-a-navigate
  (is (thrown-with-msg?
        js/Error #"hicasso-malformed-prevent"
        (intent/with-frame frame-id (fn [_] nil)
          (fn [] (intent/lower-prop
                   :on-click
                   [intent/prevent-head
                    [intent/navigate-head {:frame frame-id :payload [:x]
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
        h      (intent/with-frame frame-id
                 (fn [ev] (swap! !seen conj ev) nil)
                 (fn [] (intent/lower-prop :on-click (:on-click attrs))))]
    [h !seen]))

(deftest a-prevent-veto-cancels-the-navigation-and-dispatches-instead
  (let [[h !seen] (lowered-click {:to :conduit.profile/show :params {:username "jane"}
                                  :on-click [intent/prevent-head [:conduit/track "jane"]]})
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
