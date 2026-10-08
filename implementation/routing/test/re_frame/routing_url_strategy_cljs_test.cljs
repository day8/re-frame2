(ns re-frame.routing-url-strategy-cljs-test
  "CLJS tests for the browser halves of the URL-strategy seam, driven against
  a stubbed `window`. The pure `:encode` / `:decode` legs, the preflight and
  the frame-config resolution are in `routing_url_strategy_test.clj`; this
  suite pins what only a browser host runs:

  1. A `:url-bound?` hash frame's LIFECYCLE wires a `hashchange` listener that
     decodes each change to path-form and drives the owner — no imperative
     install call — and destroy removes it.
  2. `with-base-path`: `:encode` is the single outbound authority, so a
     `/demos` hash app's pushed and replaced address bar reads `/demos#/…`
     (base OUTSIDE the fragment) and is never double-hashed; the wrapped
     `:install-listener!` leaves a fragment-form decode unstripped; and a
     history app reached at its mount root with a query or fragment lands on
     the root route.
  3. CLJS requires the three browser legs at registration.
  4. `{:url …}` and `:rf.route/url-requested` resolve an app reference
     against the navigating frame's route, and hand an origin-bearing
     reference to the URL owner's `:decode`.

  Node has no `window`, so the history/location stub of
  `re-frame.routing-browser-test-support` is installed per test. Per Spec 012
  §URL strategies."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]
            [re-frame.routing.strategy :as rf.routing.strategy]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.routing-browser-test-support
             :refer [*history-state* current-url with-window-stub-fixture]]))

(use-fixtures :each
  with-window-stub-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn [] (rf.routing/reset-counters!) (rf.routing/reset-scroll-cache!))}))

(defn- register-routes! []
  (rf/reg-route :s/home      {} "/")
  (rf/reg-route :s/active    {} "/active")
  (rf/reg-route :s/completed {} "/completed")
  (rf/reg-route :rf.route/not-found {} "/_404"))

(defn- slice-of [frame-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
          [:rf.runtime/routing :current]))

;; ---- 1. the :url-bound? lifecycle wires the hash listener -----------------

(deftest hash-frame-install-listener-wires-hashchange-cljs
  (testing "registering a :url-bound? true hash-strategy frame wires a
            `hashchange` listener that decodes location.hash to path-form and
            drives the owner; destroy-frame! removes it"
    (rf/make-frame {:id :rf/default :url-bound?   true
                    :url-strategy rf.routing.strategy/hash-url-strategy})
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/completed"}])
    (is (= :s/completed (:route-id (slice-of :rf/default))) "on /completed before Back")
    (.back (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :s/active (:route-id (slice-of :rf/default)))
        "the hashchange listener decoded #/active and restored the slice")
    (rf/destroy-frame! :rf/default)
    (is (empty? (get-in @*history-state* [:listeners "hashchange"])))))

;; ---- 2. with-base-path ----------------------------------------------------

(deftest hash-base-links-and-address-bar-agree-irygd6-cljs
  (testing "a /demos-based HASH app's pushed and replaced entries read
            /demos#/… — the shape route-link renders, base OUTSIDE the
            fragment — because the RAW :push! / :replace! legs never
            re-encode the href :encode produced"
    (rf/make-frame {:id :rf/default :url-bound?   true
                    :url-strategy (rf.routing.strategy/with-base-path
                                    rf.routing.strategy/hash-url-strategy "/demos")})
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (is (= ["/" "/demos#/active"] (:entries @*history-state*)) "push")
    (rf/dispatch-sync [:rf.route/navigate {:to :s/completed :replace? true}])
    (is (= ["/" "/demos#/completed"] (:entries @*history-state*))
        "replace overwrites the entry in place")))

(deftest hash-base-install-listener!-preserves-colliding-prefix-exnw-cljs
  (testing "a based HASH app's wrapped `:install-listener!` hands `on-change`
            the fragment's path untouched — the base lives in the pathname, so
            /demos#/demos/item delivers /demos/item, not /item"
    (let [wrapped  (rf.routing.strategy/with-base-path
                     rf.routing.strategy/hash-url-strategy "/demos")
          received (atom [])
          teardown ((:install-listener! wrapped) (fn [p] (swap! received conj p)))]
      (.pushState js/globalThis.window.history nil "" "/demos#/demos/item")
      (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
      (.pushState js/globalThis.window.history nil "" "/demos#/demos")
      (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
      (is (= ["/demos/item" "/demos"] @received)
          "on-change received each app route with its leading segment intact")
      (teardown))))

(deftest history-base-mount-root-before-query-or-fragment-cljs-rf2-gwye-29
  (testing "a /app-deployed HISTORY app reached at its mount root with a query
            or fragment and no terminal slash lands on the root route — on the
            initial sync and through the listener — rather than the
            base-carrying /app?… that misses every route"
    (register-routes!)
    (.pushState js/globalThis.window.history nil "" "/app?tab=all")
    (rf/make-frame {:id :rf/default :url-bound?   true
                    :url-strategy (rf.routing.strategy/with-base-path
                                    rf.routing.strategy/history-url-strategy "/app")})
    (is (= [:s/home {"tab" "all"}] ((juxt :route-id :query) (slice-of :rf/default)))
        "initial sync at /app?tab=all")
    (.pushState js/globalThis.window.history nil "" "/app/active")
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= :s/active (:route-id (slice-of :rf/default))) "listener at /app/active")
    (.pushState js/globalThis.window.history nil "" "/app#section")
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= [:s/home "section"] ((juxt :route-id :fragment) (slice-of :rf/default)))
        "listener at /app#section")))

;; ---- 3. CLJS host-required legs ---------------------------------------------

(deftest make-frame-rejects-cljs-incomplete-strategy-at-registration-ktmto9-cljs
  (testing "on CLJS the browser legs are host-required, so a strategy carrying
            only :encode / :decode (enough on the JVM) is rejected at first
            registration"
    (let [ex (try (rf/make-frame {:id :ktmto9/cljs-legs :url-bound?   true
                                  :url-strategy {:encode identity
                                                 :decode (constantly "/")}})
                  nil
                  (catch :default e e))]
      (is (= {:rf.error/id :rf.error/invalid-url-strategy
              :frame       :ktmto9/cljs-legs
              :missing     [:push! :replace! :install-listener!]}
             (select-keys (ex-data ex) [:rf.error/id :frame :missing]))))))

;; ---- 4. `{:url …}` and :rf.route/url-requested speak app-relative URLs -----
;;
;; A reference with no origin (`?tab=2`, `#section`, `7`) is an APP reference:
;; it resolves against the navigating frame's current app URL. A reference with
;; an origin is a BROWSER ADDRESS: the URL owner's `:decode` reduces it. Under a
;; base path or a hash strategy an address-bar reading would double the base or
;; land on the home route. Each test runs BOTH doors.

(defn- register-user-routes! []
  (rf/reg-route :u/home {} "/")
  (rf/reg-route :u/user {} "/users/:id")
  (rf/reg-route :rf.route/not-found {} "/_404"))

(def ^:private both-doors
  "The two doors a caller hands a URL string to."
  [[:navigate      (fn [frame-id url]
                     (rf/dispatch-sync [:rf.route/navigate {:url url}] {:frame frame-id}))]
   [:url-requested (fn [frame-id url]
                     (rf/dispatch-sync [:rf.route/url-requested {:url url}] {:frame frame-id}))]])

(defn- own-url-at!
  "Put the browser on `href`, then declare `:rf/default` the URL owner (under
  `strategy`, when one is given) — its initial sync reads that address."
  [href strategy]
  (.pushState js/globalThis.window.history nil "" href)
  (rf/make-frame (cond-> {:id :rf/default :url-bound? true}
                   strategy (assoc :url-strategy strategy))))

(defn- to-user! [frame-id id]
  (rf/dispatch-sync [:rf.route/navigate {:to :u/user :params {:id id}}] {:frame frame-id}))

(defn- doc-origin [] (.-origin (.-location js/globalThis.window)))

(deftest a-query-reference-keeps-the-route-under-a-base-path-cljs
  (testing "under (with-base-path history \"/app\") on user 42, `?tab=2`
            commits user 42 with the query and pushes the base ONCE —
            not not-found at /app/app/users/42?tab=2"
    (register-user-routes!)
    (own-url-at! "/app/users/42" (rf.routing.strategy/with-base-path
                                   rf.routing.strategy/history-url-strategy "/app"))
    (is (= :u/user (:route-id (slice-of :rf/default)))
        "precondition: the initial sync stripped the base")
    (doseq [[door go!] both-doors]
      (to-user! :rf/default "42")
      (go! :rf/default "?tab=2")
      (let [s (slice-of :rf/default)]
        (is (= [:u/user {:id "42"} {"tab" "2"} "/app/users/42?tab=2"]
               [(:route-id s) (:params s) (:query s) (current-url *history-state*)])
            (str door))))))

(deftest a-fragment-reference-is-a-fragment-change-under-hash-cljs
  (testing "under hash-url-strategy on user 42, `#section` is a fragment-only
            change on user 42 — not the home route with fragment \"section\""
    (register-user-routes!)
    (own-url-at! "/#/users/42" rf.routing.strategy/hash-url-strategy)
    (is (= :u/user (:route-id (slice-of :rf/default))) "precondition: on user 42")
    (doseq [[door go!] both-doors]
      (to-user! :rf/default "42")
      (go! :rf/default "#section")
      (let [s (slice-of :rf/default)]
        (is (= [:u/user {:id "42"} "section" "#/users/42#section"]
               [(:route-id s) (:params s) (:fragment s) (current-url *history-state*)])
            (str door))))))

(deftest a-custom-strategy-decodes-with-its-own-decode-cljs
  (testing "an origin-bearing reference goes through WHATEVER `:decode` the
            URL owner declares, and its own `:encode` writes it back"
    (let [bang {:encode (fn [path] (str "#!" path))
                :decode (fn [href]
                          (let [i (.indexOf href "#!")
                                p (when-not (neg? i) (subs href (+ i 2)))]
                            (if (seq p) p "/")))}]
      (register-user-routes!)
      (own-url-at! "/#!/users/42" (merge rf.routing.strategy/hash-url-strategy bang))
      (is (= :u/user (:route-id (slice-of :rf/default)))
          "precondition: the initial sync decoded the #! address")
      (doseq [[door go!] both-doors]
        (to-user! :rf/default "42")
        (go! :rf/default (str (doc-origin) "/#!/users/7"))
        (is (= [{:id "7"} "#!/users/7"]
               [(:params (slice-of :rf/default)) (current-url *history-state*)])
            (str door))))))

(deftest an-app-reference-resolves-against-the-navigating-frame-cljs
  (testing "a frame that is not the URL owner resolves `?tab=2` against ITS
            route, never the host page's address bar"
    (register-user-routes!)
    (own-url-at! "/users/42" nil)
    (rf/make-frame {:id :u/story})
    (doseq [[door go!] both-doors]
      (to-user! :u/story "5")
      (go! :u/story "?tab=2")
      (is (= [{:id "5"} {"tab" "2"}] ((juxt :params :query) (slice-of :u/story)))
          (str door ": the story frame's own user, with the query"))
      (is (= [:u/user {:id "42"}] ((juxt :route-id :params) (slice-of :rf/default)))
          (str door ": the URL owner is untouched")))))

(deftest app-reference-bases-cljs
  (register-user-routes!)
  (testing "on a NOT-FOUND slice the base is the requested app URL the slice
            preserves, which the not-found pattern is not"
    (own-url-at! "/missing/page?old=1" nil)
    (is (= :rf.route/not-found (:route-id (slice-of :rf/default))) "precondition")
    (doseq [[door go!] both-doors]
      (rf/dispatch-sync [:rf.route/navigate {:url "/missing/page?old=1"}] {:frame :rf/default})
      (go! :rf/default "?tab=2")
      (is (= [:rf.route/not-found "/missing/page?tab=2"]
             ((juxt :route-id (comp :url :params)) (slice-of :rf/default)))
          (str door ": the query replaced the missed URL's query, on the missed path"))))
  (testing "a frame with NO location resolves against `/`"
    (doseq [[door go!] both-doors]
      (let [frame-id (keyword "u" (str "fresh-" (name door)))]
        (rf/make-frame {:id frame-id})
        (go! frame-id "?tab=2")
        (is (= [:u/home {"tab" "2"}] ((juxt :route-id :query) (slice-of frame-id)))
            (str door)))))
  (testing "a bare relative segment resolves against the frame's CANONICAL
            location, so `7` from user 42 is user 7"
    (doseq [[door go!] both-doors]
      (to-user! :rf/default "42")
      (go! :rf/default "7")
      (is (= {:id "7"} (:params (slice-of :rf/default))) (str door ": user 7")))))
