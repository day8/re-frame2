(ns re-frame.route-link-ssr-parity-cljs-test
  "Cross-host `:href` parity for a strategy-bearing route-link (rf2-skr1c).
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

  Before this suite both JVM link doors hard-coded `identity` as the encoder
  (`route-link-render-ssr` and the `:clj` arm of `link-model`), so the same
  frame config rendered `/active` on the server and `/demos/active` on the
  client. Nothing caught it: `route_link_test.clj` covered only the default
  path-form strategy, and `routing_url_strategy_test.clj` proved the pure
  encoders without composing them with an SSR render.

  Two doors, four strategy shapes, one table:

    - `rf/route-link`'s render fn — `route-link-render` on CLJS,
      `route-link-render-ssr` on the JVM — rendered inside `rf/with-frame`
      on a frame declaring the strategy.
    - The `:routing/link-model` seam (`link-model`), the door a view
      artefact's own route-link consumes, handed the same frame id.

  The frames here declare `:url-strategy` WITHOUT `:url-bound? true`: the
  href consult reads the RENDERING frame's declared strategy, while
  `:url-bound?` governs only the browser listener and the history legs —
  the side effects SSR never runs, and exactly what a pure href-parity
  suite must not depend on (a `:url-bound?` frame would try to install a
  `popstate` listener under node's absent `window`). The server-frame
  integration case — a `:platform :server`, `:url-bound?` frame rendered
  through the SSR emitter — lives in `route_link_test.clj`.

  Reverting either JVM door to `identity` turns the JVM half of this suite
  red while the CLJS half stays green — which is the mismatch it exists to
  name."
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
  `/active`, for `/articles/x?tab=comments`, and for the
  punctuation-bearing `/articles/draft~1?tab=it's(new)!`. The expected
  strings are the contract — literal on purpose, never derived from
  `:encode`, so a door that stopped consulting the strategy cannot also
  rewrite the expectation.

  The `:punct` column is rf2-j3tud's teeth at the LINK door. `url-encode`'s
  JVM arm escaped `! ' ( ) ~` where `encodeURIComponent` leaves them
  literal, so a `~`-bearing slug rendered `:href` `/articles/draft%7E1` on
  the server and `/articles/draft~1` on the hydrated client — a Spec 011
  hydration mismatch at the attribute the first client render compares.
  The `x` / `comments` rows above could not see it: neither string carries
  a character the two encoders disagree about."
  [{:frame    :parity/history
    :strategy rf.routing.strategy/history-url-strategy
    :active   "/active"
    :article  "/articles/x?tab=comments"
    :punct    "/articles/draft~1?tab=it's(new)!"}
   {:frame    :parity/history-base
    :strategy (rf.routing.strategy/with-base-path rf.routing.strategy/history-url-strategy "/demos")
    :active   "/demos/active"
    :article  "/demos/articles/x?tab=comments"
    :punct    "/demos/articles/draft~1?tab=it's(new)!"}
   {:frame    :parity/hash
    :strategy rf.routing.strategy/hash-url-strategy
    :active   "#/active"
    :article  "#/articles/x?tab=comments"
    :punct    "#/articles/draft~1?tab=it's(new)!"}
   {:frame    :parity/hash-base
    :strategy (rf.routing.strategy/with-base-path rf.routing.strategy/hash-url-strategy "/demos")
    :active   "/demos#/active"
    :article  "/demos#/articles/x?tab=comments"
    :punct    "/demos#/articles/draft~1?tab=it's(new)!"}])

(def ^:private active-props  {:to :parity/active})
(def ^:private article-props {:to :parity/article :params {:slug "x"} :query {:tab "comments"}})
(def ^:private punct-props   {:to :parity/article :params {:slug "draft~1"} :query {:tab "it's(new)!"}})

(defn- seat-frame!
  "Construct the row's frame with its strategy declared. The registration-time
  preflight validates the strategy on both hosts, so a seated strategy is
  always the one the consult points read."
  [{:keys [frame strategy]}]
  (rf/make-frame {:id frame :url-strategy strategy}))

(defn- rendered-href
  "The `:href` THIS host's `rf/route-link` render fn emits for `props` inside
  `frame-id`'s scope — the production render path on each host, not a
  re-derivation of the strategy."
  [frame-id props]
  (rf/with-frame frame-id
    (let [[tag attrs] #?(:cljs (rf.routing.link/route-link-render props)
                         :clj  (rf.routing.link/route-link-render-ssr props))]
      (is (= :a tag) "the render fn emits an <a> on this host")
      (:href attrs))))

(deftest route-link-href-agrees-across-hosts-for-every-strategy-shape
  (register-routes!)
  (doseq [{:keys [frame active article punct] :as row} parity-cases]
    (seat-frame! row)
    (testing (str "rf/route-link render inside frame " frame)
      (is (= active (rendered-href frame active-props))
          (str frame ": the rendered :href for /active is the strategy-encoded form on this host"))
      (is (= article (rendered-href frame article-props))
          (str frame ": params + query ride inside the encoded form on this host"))
      (is (= punct (rendered-href frame punct-props))
          (str frame ": punctuation in the slug and query value stays LITERAL on"
               " this host — the JVM SSR render and the first CLJS render emit"
               " the same :href (rf2-j3tud)")))))

(deftest link-model-href-agrees-across-hosts-for-every-strategy-shape
  (register-routes!)
  (doseq [{:keys [frame active article punct] :as row} parity-cases]
    (seat-frame! row)
    (testing (str ":routing/link-model seam for frame " frame)
      (let [model (rf.routing.link/link-model active-props frame)]
        (is (= active (:href model))
            (str frame ": link-model :href is the strategy-encoded form on this host"))
        (is (= [:rf.route/url-requested {:url "/active" :to :parity/active}]
               (:payload model))
            (str frame ": the navigation payload stays PATH-FORM — only the href is encoded"))
        (is (false? (:native? model)) "a plain link is not a native anchor"))
      (is (= article (:href (rf.routing.link/link-model article-props frame)))
          (str frame ": params + query ride inside link-model's encoded href"))
      (is (= punct (:href (rf.routing.link/link-model punct-props frame)))
          (str frame ": punctuation stays literal through link-model's href too"
               " — the second door reads the same canonical bytes (rf2-j3tud)")))))

(deftest no-frame-and-default-frame-keep-the-path-form-href
  (testing "a frame that declares no strategy renders path-form on both hosts"
    (register-routes!)
    (rf/make-frame {:id :parity/undeclared})
    (is (= "/active" (rendered-href :parity/undeclared active-props)))
    (is (= "/active" (:href (rf.routing.link/link-model active-props :parity/undeclared)))))
  (testing "link-model with no frame at all resolves the history default on both hosts"
    (is (= "/active" (:href (rf.routing.link/link-model active-props nil)))))
  #?(:clj
     (testing "the bare SSR helper, called outside any frame scope, stays path-form
               (the direct-call ergonomics route_link_test.clj pins do not regress)"
       (let [[_ attrs] (rf.routing.link/route-link-render-ssr active-props)]
         (is (= "/active" (:href attrs)))))))
