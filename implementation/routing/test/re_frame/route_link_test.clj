(ns re-frame.route-link-test
  "JVM tests for the `:route/link` registered view (rf2-uhv2). The view is
  CLJS-only for the click-interception semantics; this file covers the
  JVM-portable contract:

  - registry registration — `:route/link` is present in the `:view`
    registrar kind on both platforms (CLJS via `reg-view*`, JVM via the
    `routing.cljc` :clj branch).
  - href synthesis — the SSR-side render fn yields a hiccup tree whose
    `:href` matches `(route-url to params query)` for the supplied
    route, with the optional `:fragment` appended after `#`.
  - HTML-attr passthrough — keys other than `:to` / `:params` / `:query`
    / `:fragment` / `:on-click` land on the rendered `<a>` element.
  - missing route — invoking `route-link` with an unregistered `:to` id
    raises `:rf.error/no-such-route` (the same error `route-url` raises;
    the link view delegates to `route-url` for URL synthesis).
  - `:rf.route/url-requested` lands on `:rf.route/navigate` — dispatching the
    event the view fires when a plain left-click is intercepted updates
    the `:rf/route` slice end-to-end. This pins the click→event pipeline
    at the JVM layer; CLJS tests cover the click handler's modifier-key
    branching.
  - server-frame `:url-strategy` (rf2-skr1c) — a `:platform :server` frame
    declaring `with-base-path` / the hash strategy renders the ENCODED href
    through the registered `:route/link` view and the SSR emitter, while
    the navigation payload stays path-form. The cross-host half (the same
    hrefs on CLJS) is `route_link_ssr_parity_cljs_test.cljc`.

  Per Spec 012 §Linking from views — plain-anchor semantics and API.md
  `route-link` row."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.registrar :as rf.registrar]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.link :as rf.routing.link]
            [re-frame.routing-test-support :as rf.routing-test-support]))

;; rf2-6qclsc: use the shared `reset-runtime` fixture directly rather than a
;; drifting local copy. The route-link suite has no suite-specific reset
;; extras, so the shared fixture (which additionally reloads ssr /
;; test-support and drops the host-side scroll / nav-counter caches) applies
;; verbatim.
(use-fixtures :each rf.routing-test-support/reset-runtime)

;; ---- registry registration ----------------------------------------------

(deftest route-link-registered-at-route-link-id
  (testing ":route/link is present in the :view registrar kind"
    ;; Per API.md `route-link` row and Spec 012 §Linking from views: the
    ;; routing artefact registers a view at id `:route/link` on
    ;; ns-load, on both platforms — `.cljc` render trees that embed
    ;; `[rf/route-link ...]` resolve identically client- and server-side.
    (is (some? (rf/handler-meta :view :route/link))
        ":route/link is registered when re-frame.routing is loaded")
    (is (fn? (:handler-fn (rf.registrar/lookup :view :route/link)))
        "the registered slot carries a callable :handler-fn")))

;; ---- href synthesis -----------------------------------------------------

(deftest route-link-href-from-route-id
  (testing "the rendered <a> :href matches route-url for the given :to"
    (rf/reg-route :route/cart    {} "/cart")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")

    (let [render  rf.routing/route-link-render-ssr
          [tag attrs] (render {:to :route/cart})]
      (is (= :a tag) "renders an <a> element")
      (is (= "/cart" (:href attrs))
          ":href is the route-url for :route/cart"))

    (let [[_ attrs] (rf.routing/route-link-render-ssr
                     {:to :route/article :params {:id "intro"}})]
      (is (= "/articles/intro" (:href attrs))
          ":href substitutes :params into the route's :path pattern"))))

(deftest route-link-href-with-query-and-fragment
  (testing ":query and :fragment are appended to the href"
    ;; :q is optional so /search is reachable without a query (exercised by
    ;; the empty-:fragment sub-case below); when present it must be a string.
    (rf/reg-route :route/search {:query [:map [:q {:optional true} :string]]} "/search")

    (let [[_ attrs] (rf.routing/route-link-render-ssr
                     {:to    :route/search
                      :query {:q "clojure"}})]
      (is (= "/search?q=clojure" (:href attrs))
          ":query lands as ?key=value on the href"))

    (let [[_ attrs] (rf.routing/route-link-render-ssr
                     {:to       :route/search
                      :query    {:q "clojure"}
                      :fragment "results"})]
      (is (= "/search?q=clojure#results" (:href attrs))
          ":fragment is appended after #"))

    (let [[_ attrs] (rf.routing/route-link-render-ssr
                     {:to       :route/search
                      :fragment ""})]
      (is (= "/search" (:href attrs))
          "empty :fragment is treated as no fragment (no trailing #)"))))

;; ---- html-attr passthrough ---------------------------------------------

(deftest route-link-passes-html-attrs-through
  (testing "props other than :to / :params / :query / :fragment / :on-click pass through"
    (rf/reg-route :route/home {} "/")

    (let [[_ attrs children] (rf.routing/route-link-render-ssr
                              {:to    :route/home
                               :class "nav-link"
                               :id    "home-link"
                               :title "Home"
                               :aria-label "Go to home page"}
                              "Home")]
      (is (= "/" (:href attrs)) ":href is synthesised")
      (is (= "nav-link" (:class attrs)) ":class passes through")
      (is (= "home-link" (:id attrs)) ":id passes through")
      (is (= "Home" (:title attrs)) ":title passes through")
      (is (= "Go to home page" (:aria-label attrs)) ":aria-label passes through")
      (is (nil? (:to attrs)) ":to is consumed (not forwarded to <a>)")
      (is (nil? (:params attrs)) ":params is consumed")
      (is (nil? (:query attrs)) ":query is consumed")
      (is (nil? (:fragment attrs)) ":fragment is consumed")
      (is (= "Home" children) "children pass through"))))

;; ---- server-frame :url-strategy (rf2-skr1c) --------------------------------
;;
;; The SSR pipeline pins the per-request frame with `rf/with-frame` around
;; its render walk (ssr-ring `build-full-response*`), so the registered
;; `:route/link` view renders INSIDE a frame scope on the server exactly as it
;; does on the client. Both JVM link doors used to hard-code `identity` as the
;; encoder, so a `/demos`-based server frame rendered `/active` where the
;; hydrated client rendered `/demos/active` — a link that left the deployment
;; mount if followed before hydration, and a Spec 011 hydration mismatch.

(defn- server-frame!
  "Seat a `:platform :server` frame — the shape the SSR pipeline constructs
  per request — declaring `strategy`. `:url-bound?` is what a real URL-owning
  server frame declares; on the JVM it installs no listener (the install is
  CLJS-only), so the declaration costs nothing here."
  [id strategy]
  (rf/make-frame {:id id :platform :server :url-bound? true :url-strategy strategy}))

(deftest route-link-ssr-honours-the-server-frames-url-strategy
  (rf/reg-route :route/active {} "/active")
  (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")
  (server-frame! :ssr/history-base
                 (rf.routing/with-base-path rf.routing/history-url-strategy "/demos"))
  (server-frame! :ssr/hash rf.routing/hash-url-strategy)
  (server-frame! :ssr/hash-base
                 (rf.routing/with-base-path rf.routing/hash-url-strategy "/demos"))

  (testing "the SSR emitter renders the based href for a with-base-path server
            frame — the production path: `[rf/route-link …]` in a render tree,
            walked by `render-to-string` inside the frame's scope"
    (let [html (rf/with-frame :ssr/history-base
                 (rf/render-to-string [rf/route-link {:to :route/active} "Active"]))]
      (is (str/includes? html "href=\"/demos/active\"")
          (str "the emitted <a> carries the base-prefixed href, got: " html))
      (is (not (str/includes? html "href=\"/active\""))
          "the path-form href no longer reaches the server shell")))

  (testing "the registered :route/link view (what the emitter resolves) yields
            the encoded href for every strategy shape a server frame can declare"
    (let [view (rf/view :route/link)]
      (is (fn? view) ":route/link resolves to its registered render fn")
      (is (= "/demos/active"
             (:href (second (rf/with-frame :ssr/history-base (view {:to :route/active})))))
          "with-base-path history: /demos/active")
      (is (= "/demos/articles/intro"
             (:href (second (rf/with-frame :ssr/history-base
                              (view {:to :route/article :params {:id "intro"}})))))
          "with-base-path history: params ride inside the based href")
      (is (= "#/active"
             (:href (second (rf/with-frame :ssr/hash (view {:to :route/active})))))
          "hash: #/active on the server too")
      (is (= "/demos#/active"
             (:href (second (rf/with-frame :ssr/hash-base (view {:to :route/active})))))
          "with-base-path hash: base OUTSIDE the fragment on the server too")))

  (testing "the :routing/link-model seam agrees, and its navigation payload stays
            path-form — only the rendered href is encoded"
    (let [model (rf.routing.link/link-model {:to :route/active} :ssr/history-base)]
      (is (= "/demos/active" (:href model)))
      (is (= [:rf.route/url-requested {:url "/active" :to :route/active}]
             (:payload model))
          "the cascade is path-form throughout; the base never enters the payload"))
    (is (= "/demos#/active" (:href (rf.routing.link/link-model {:to :route/active} :ssr/hash-base)))))

  (testing "a bare call outside any frame scope still renders path-form (the
            direct-call ergonomics above do not regress)"
    (is (= "/active" (:href (second (rf.routing/route-link-render-ssr {:to :route/active})))))))

;; ---- missing route ------------------------------------------------------

(deftest route-link-missing-route-raises
  (testing "an unregistered :to id raises :rf.error/no-such-route"
    ;; Per the existing route-url contract (see routing.cljc — the
    ;; route-url helper throws ex-info ":rf.error/no-such-route" when
    ;; the route-id has no registered :path). The route-link view
    ;; delegates href synthesis to route-url, so the same error
    ;; surfaces from the link layer.
    (let [thrown (try
                   (rf.routing/route-link-render-ssr {:to :route/nope})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "route-link raises when :to is unregistered")
      ;; rf2-vvixub — anchor on the canonical :rf.error/id discriminator
      ;; (the message is now a human sentence + trailing token).
      (is (= :rf.error/no-such-route (:rf.error/id (ex-data thrown)))
          "the missing-route error keyword matches route-url's contract")
      (is (= :route/nope (:route-id (ex-data thrown)))
          "ex-data carries the offending route-id"))))

;; ---- :rf.route/url-requested → :rf.route/navigate pipeline -------------------

(deftest route-link-click-event-completes-navigation
  (testing ":rf.route/url-requested with a route-link's payload navigates"
    ;; Per Spec 012 §Standard runtime events the click handler emits
    ;; `:rf.route/url-requested {:url ... :to ... :params ... :query ...}`.
    ;; The default `:rf.route/url-requested` handler classifies via match-url
    ;; and dispatches `:rf.route/transitioned`, which updates the :rf/route
    ;; slice. This test pins the round-trip without a DOM event — the
    ;; CLJS test covers the click branching that produces the dispatch.
    (rf/reg-route :route/home    {} "/")
    (rf/reg-route :route/article {:params [:map [:id :string]]} "/articles/:id")

    ;; Suppress the :client-only :rf.nav/push-url fx on the JVM (matches
    ;; the pattern in routing_test.clj).
    (rf.fx/reg-fx :rf.nav/push-url
               {:platforms #{:server :client}}
               (fn [_ _] nil))

    ;; Land on /home first so :rf/route has a current id.
    (rf/dispatch-sync [:rf.route/transitioned "/"])
    (is (= :route/home
           (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :route-id]))
        "initial nav lands at :route/home")

    ;; Fire the event a click on `[rf/route-link {:to :route/article :params {:id \"intro\"}}]`
    ;; would produce.
    (rf/dispatch-sync [:rf.route/url-requested
                       {:url    "/articles/intro"
                        :to     :route/article
                        :params {:id "intro"}}])
    (is (= :route/article
           (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :route-id]))
        ":rf.route/url-requested with a route-link payload completes the navigation")
    (is (= {:id "intro"}
           (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current :params]))
        ":params from the link land in the :rf/route slice")))
