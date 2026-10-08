(ns re-frame.route-link-ssr-parity-cljs-test
  "Cross-host `:href` parity for a strategy-bearing route-link.
  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build runs
  it on the CLJS host alongside the JVM `clojure -M:test` runner — ONE table
  of expected hrefs, asserted through each host's PRODUCTION link door.

  The contract under test is Spec 012 §URL strategies: `:encode` is pure and
  host-agnostic, and the `route-link` href render is one of its four consult
  points on BOTH hosts. Spec 011 §The render tree is the contract requires
  the server's render tree and the client's first render to be structurally
  identical — an attribute VALUE that differs is a hydration mismatch — so a
  frame declaring `(with-base-path history-url-strategy \"/demos\")` must
  render `/demos/active` on the server exactly as the hydrated client does.
  A JVM link door that hard-coded `identity` as the encoder would turn the
  JVM half of this suite red while the CLJS half stays green.

  Two doors, four strategy shapes, one table:

    - `rf/route-link`'s render fn — `route-link-render` on CLJS,
      `route-link-render-ssr` on the JVM — rendered inside `rf/with-frame`
      on a frame declaring the strategy.
    - The `:routing/link-model` seam (`link-model`), the door a view
      artefact's own route-link consumes, handed the same frame id.

  The frames declare `:url-strategy` WITHOUT `:url-bound? true`: the href
  consult reads the rendering frame's declared strategy, while `:url-bound?`
  governs only the browser listener and history legs, which SSR never runs
  (and which would try to install a `popstate` listener under node's absent
  `window`). The `:platform :server`, `:url-bound?` frame rendered through
  the SSR emitter is `route_link_test.clj`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.routing :as rf.routing]
   [re-frame.routing.link :as rf.routing.link]
   [re-frame.routing.strategy :as rf.routing.strategy]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn rf.routing/reset-counters!}))

(defn- register-routes! []
  (rf/reg-route :parity/home    {} "/")
  (rf/reg-route :parity/active  {} "/active")
  (rf/reg-route :parity/article {:params [:map [:slug :string]]
                                 :query  [:map [:tab {:optional true} :string]]}
                "/articles/:slug"))

(def ^:private parity-cases
  "One row per supported strategy shape: the frame id the row seats its
  strategy on, the strategy, and the href BOTH hosts must render for
  `/active` and for the punctuation-bearing `/articles/draft~1?tab=it's(new)!`.
  The expected strings are literal on purpose, never derived from `:encode`,
  so a door that stopped consulting the strategy cannot also rewrite the
  expectation.

  The `:punct` column carries params, a query, and host-symmetric encoding's
  teeth at the LINK door: `java.net.URLEncoder` escapes `! ' ( ) ~` where
  `encodeURIComponent` leaves them literal, so a `url-encode` JVM arm that
  did not correct it would render `/articles/draft%7E1` on the server and
  `/articles/draft~1` on the hydrated client — a Spec 011 hydration mismatch."
  [{:frame    :parity/history
    :strategy rf.routing.strategy/history-url-strategy
    :active   "/active"
    :punct    "/articles/draft~1?tab=it's(new)!"}
   {:frame    :parity/history-base
    :strategy (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/demos")
    :active   "/demos/active"
    :punct    "/demos/articles/draft~1?tab=it's(new)!"}
   {:frame    :parity/hash
    :strategy rf.routing.strategy/hash-url-strategy
    :active   "#/active"
    :punct    "#/articles/draft~1?tab=it's(new)!"}
   {:frame    :parity/hash-base
    :strategy (rf.routing.strategy/with-base-path rf.routing.strategy/hash-url-strategy "/demos")
    :active   "/demos#/active"
    :punct    "/demos#/articles/draft~1?tab=it's(new)!"}])

(def ^:private active-props  {:to :parity/active})
(def ^:private article-props {:to :parity/article :params {:slug "x"} :query {:tab "comments"}})
(def ^:private punct-props   {:to :parity/article :params {:slug "draft~1"} :query {:tab "it's(new)!"}})

(defn- seat-frame!
  "Construct the row's frame with its strategy declared."
  [{:keys [frame strategy]}]
  (rf/make-frame {:id frame :url-strategy strategy}))

(defn- rendered-anchor
  "The `[tag href]` THIS host's `rf/route-link` render fn emits for `props`
  inside `frame-id`'s scope — the production render path on each host, not a
  re-derivation of the strategy."
  [frame-id props]
  (rf/with-frame frame-id
    (let [[tag attrs] #?(:cljs (rf.routing.link/route-link-render props)
                         :clj  (rf.routing.link/route-link-render-ssr props))]
      [tag (:href attrs)])))

(deftest route-link-href-agrees-across-hosts-for-every-strategy-shape
  (register-routes!)
  (doseq [{:keys [frame active punct] :as row} parity-cases]
    (seat-frame! row)
    (testing (str "rf/route-link render inside frame " frame)
      (is (= [:a active] (rendered-anchor frame active-props)))
      (is (= [:a punct] (rendered-anchor frame punct-props))
          "params, query and literal punctuation ride inside the encoded form"))))

(deftest link-model-href-agrees-across-hosts-for-every-strategy-shape
  (register-routes!)
  (doseq [{:keys [frame active punct] :as row} parity-cases]
    (seat-frame! row)
    (testing (str ":routing/link-model seam for frame " frame)
      (is (= {:href          active
              :payload       [:rf.route/url-requested {:url "/active"}]
              :native?       false
              :prefetch      nil
              :prefetch-keys rf.routing.link/prefetch-intent-keys}
             (rf.routing.link/link-model active-props frame))
          "only the href is encoded — the navigation payload stays PATH-FORM")
      (is (= punct (:href (rf.routing.link/link-model punct-props frame)))))))

(deftest link-model-prefetch-agrees-across-hosts
  (testing "the warm-up is PURE and identical on both hosts: the SSR shell
           installs no handlers, but it must emit and refuse exactly what the
           hydrated client does"
    (register-routes!)
    (is (= [:rf.route/prefetch {:to :parity/article :params {:slug "x"}
                                :query {:tab "comments"}}]
           (:prefetch (rf.routing.link/link-model (assoc article-props :prefetch :intent) nil)))
        "PATH-FORM address data — no strategy encoding, so it cannot differ between hosts")
    (doseq [bad [:render nil]]
      (is (thrown? #?(:cljs js/Error :clj clojure.lang.ExceptionInfo)
                   (rf.routing.link/link-model (assoc article-props :prefetch bad) nil))
          (str ":prefetch " (pr-str bad) " is refused on this host — a present nil included")))))

(deftest no-frame-and-default-frame-keep-the-path-form-href
  (testing "a frame that declares no strategy renders path-form on both hosts"
    (register-routes!)
    (rf/make-frame {:id :parity/undeclared})
    (is (= [:a "/active"] (rendered-anchor :parity/undeclared active-props)))
    (is (= "/active" (:href (rf.routing.link/link-model active-props :parity/undeclared)))))
  (testing "link-model with no frame at all resolves the history default on both hosts"
    (is (= "/active" (:href (rf.routing.link/link-model active-props nil))))))
