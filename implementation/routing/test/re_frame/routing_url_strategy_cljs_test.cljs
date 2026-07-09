(ns re-frame.routing-url-strategy-cljs-test
  "CLJS tests for the URL-strategy seam (rf2-aerrz5) — the four
  egress/ingress consult points driven end-to-end against a stubbed
  `window`. The JVM suite (`routing_url_strategy_test.clj`) pins the pure
  encode/decode legs + frame-config resolution; this suite pins the
  side-effecting halves a browser owns:

  1. `:rf.nav/push-url` / `:rf.nav/replace-url` through a HASH-strategy frame
     push/replace the `#`-prefixed href (history entries carry `#/active`).
  2. `route-link` renders a `#`-prefixed `:href` for a hash frame.
  3. The `:url-bound?` frame LIFECYCLE (rf2-g8pbwg) automatically wires a
     `hashchange` listener for a hash frame (a `popstate` listener for a
     history frame) and decodes each change to path-form before dispatching
     `:rf.route/handle-url-change` — no imperative install call.
  4. The encode/decode ROUND-TRIP holds against the live (stubbed) window.

  Plus an ADVERSARIAL negative: a malformed `#`-URL fails closed to a
  route-miss, exactly as a malformed path-URL does.

  5. The `with-base-path` combinator (rf2-33uv27 / rf2-irygd6): `:encode` is
     the single outbound authority that re-adds the deployment base (the nav
     fxs encode ONCE then drive the RAW `:push!` / `:replace!` legs), and
     `:install-listener!` STRIPS the base off each browser-driven change before
     `on-change` — a `/realworld`-deployed app's address bar carries the
     mount-point URL while the router stays app-relative.

  6. rf2-irygd6 end-to-end: over BOTH shipped strategies, with and without a
     base, `:encode` / `:rf.nav/push-url` / `:rf.nav/replace-url` agree — a
     `/demos` hash app's route-link href AND its pushed/replaced address bar
     all read `/demos#/active` (base OUTSIDE the fragment), never
     `#/demos/active`, and no produced URL is double-encoded.

  Node has no `window`, so a jsdom-style history/location stub is installed
  per-test (mirroring `routing_history_cljs_test.cljs`). Per Spec 012 §URL
  strategies."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as routing]
            [re-frame.routing.strategy :as strategy]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; ---- window / history + location stub ------------------------------------
;; A minimal jsdom-style mock. `location` splits pathname / search / hash the
;; way a real browser does, and pushState/replaceState re-sync it — so a
;; hash strategy that writes `#/active` and reads `location.hash` round-trips.

(defn- new-history-stub []
  (atom {:entries ["/"] :index 0 :listeners {}}))

(defn- current-url [state]
  (let [{:keys [entries index]} @state] (nth entries index)))

(defn- install-window-stub! []
  (let [state    (new-history-stub)
        location #js {:origin "https://app.example" :href "https://app.example/"
                      :pathname "/" :search "" :hash ""}
        sync-location!
        (fn []
          (let [{:keys [entries index]} @state
                url           (nth entries index)
                [path+ hash]  (let [i (.indexOf url "#")]
                                (if (neg? i) [url ""] [(subs url 0 i) (subs url i)]))
                [path search] (let [i (.indexOf path+ "?")]
                                (if (neg? i) [path+ ""] [(subs path+ 0 i) (subs path+ i)]))]
            (set! (.-pathname location) path)
            (set! (.-search location) search)
            (set! (.-hash location) hash)
            (set! (.-href location) (str (.-origin location) url))))
        history #js {:pushState
                     (fn [_s _t url]
                       (swap! state (fn [{:keys [entries index] :as s}]
                                      (let [kept (subvec entries 0 (inc index))]
                                        (-> s (assoc :entries (conj kept url))
                                            (update :index inc)))))
                       (sync-location!))
                     :replaceState
                     (fn [_s _t url]
                       (swap! state assoc-in [:entries (:index @state)] url)
                       (sync-location!))
                     :back
                     (fn []
                       (swap! state (fn [{:keys [index] :as s}]
                                      (if (pos? index) (assoc s :index (dec index)) s)))
                       (sync-location!))
                     :forward
                     (fn []
                       (swap! state (fn [{:keys [entries index] :as s}]
                                      (if (< index (dec (count entries)))
                                        (assoc s :index (inc index)) s)))
                       (sync-location!))}
        window #js {:history history :location location
                    :scrollX 0 :scrollY 0 :pageXOffset 0 :pageYOffset 0
                    :scrollTo (fn [x y]
                                (set! (.-scrollX js/globalThis.window) x)
                                (set! (.-scrollY js/globalThis.window) y)
                                (set! (.-pageXOffset js/globalThis.window) x)
                                (set! (.-pageYOffset js/globalThis.window) y))
                    :addEventListener
                    (fn [type listener]
                      (swap! state update-in [:listeners type] (fnil conj []) listener))
                    :removeEventListener
                    (fn [type listener]
                      (swap! state update-in [:listeners type]
                             (fnil (fn [xs] (vec (remove #(= % listener) xs))) [])))
                    :dispatchEvent
                    (fn [event]
                      (doseq [l (get-in @state [:listeners (.-type event)] [])]
                        (l event)))}
        document #js {:getElementById (fn [_id] nil)}]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    state))

(defn- uninstall-window-stub! []
  (js-delete js/globalThis "window")
  (js-delete js/globalThis "document"))

(def ^:dynamic *history-state* nil)

(defn- with-window-stub-fixture [f]
  (let [state (install-window-stub!)]
    (try (binding [*history-state* state] (f))
         (finally (uninstall-window-stub!)))))

(use-fixtures :each
  with-window-stub-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn [] (routing/reset-counters!) (routing/reset-scroll-cache!))}))

;; ---- routes --------------------------------------------------------------

(defn- register-routes! []
  (rf/reg-route :s/home      {} "/")
  (rf/reg-route :s/active    {} "/active")
  (rf/reg-route :s/completed {} "/completed")
  (rf/reg-route :rf.route/not-found {} "/_404"))

(defn- double-hash?
  "True when `s` carries two or more `#` — the double-encode failure shape
  (`#/demos#/active`) rf2-irygd6 forbids on every produced URL."
  [s]
  (<= 2 (count (filter #(= % \#) s))))

(defn- route-slice-id [frame-id]
  (:route-id (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
                     [:rf.runtime/routing :current])))

;; ==========================================================================
;; 1. push-url / replace-url through a HASH-strategy frame push the `#` href
;; ==========================================================================

(deftest hash-frame-push-url-pushes-hash-href-cljs
  (testing "a HASH-strategy URL owner pushes the `#`-prefixed href — the
            router builds path-form /active, the strategy encodes it to
            #/active at the push-url fx"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    ;; :rf.route/url-requested resolves in-app, pushes, and synthesises the transition.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (is (= ["/" "#/active"] (:entries @*history-state*))
        "the pushed history entry carries the `#`-prefixed href")
    (is (= :s/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the route slice tracks the path-form route (cascade stays path-form)")))

(deftest history-frame-push-url-pushes-path-href-cljs
  (testing "a HISTORY-strategy (default) URL owner pushes the path-form href —
            no `#` — proving the default is unchanged by the seam"
    (rf/reg-frame :rf/default {:url-bound? true})   ;; no :url-strategy → history
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (is (= ["/" "/active"] (:entries @*history-state*))
        "the default history strategy pushes the bare path — no `#`")))

(deftest hash-frame-replace-url-replaces-hash-href-cljs
  (testing "a HASH-strategy owner's :rf.nav/replace-url overwrites the current
            entry with the `#` href (no new entry)"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (let [before (count (:entries @*history-state*))]
      (rf/dispatch-sync [:rf.route/navigate :s/completed {} {:replace? true}])
      (is (= before (count (:entries @*history-state*)))
          "replace did not add a history entry")
      (is (= "#/completed" (current-url *history-state*))
          "the current entry was overwritten with the `#`-prefixed completed href"))))

;; ==========================================================================
;; 2. the :url-bound? lifecycle auto-wires the listener + round-trips
;; ==========================================================================

(deftest hash-frame-install-listener-wires-hashchange-cljs
  (testing "rf2-g8pbwg: registering a :url-bound? true hash-strategy frame
            automatically wires a `hashchange` listener, decodes
            location.hash to path-form, and dispatches handle-url-change to
            the owner — the browser→app leg of the seam, zero install call"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    ;; Push two hash routes (forward nav via the owner).
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/completed"}])
    (is (= :s/completed
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "slice on /completed before Back")
    ;; Browser Back moves the hash to #/active; a hashchange fires. The
    ;; automatically-installed listener decodes #/active → /active and
    ;; drives the owner.
    (.back (.-history js/globalThis.window))
    (is (= "#/active" (current-url *history-state*))
        "back() moved the address bar to the #/active entry")
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :s/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the hashchange listener decoded #/active → /active and restored the slice")
    ;; destroy-frame! removes the listener (rf2-g8pbwg — install/remove is the
    ;; :url-bound? frame lifecycle, not an imperative pair of calls).
    (rf/destroy-frame! :rf/default)
    (is (empty? (get-in @*history-state* [:listeners "hashchange"]))
        "destroy-frame! tore down the browser hashchange listener")))

(deftest history-frame-install-listener-wires-popstate-cljs
  (testing "rf2-g8pbwg: registering a :url-bound? true history (default)
            frame automatically wires a `popstate` listener — the seam
            preserves the existing history behaviour, zero install call"
    (rf/reg-frame :rf/default {:url-bound? true})
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/completed"}])
    (.back (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= :s/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the popstate listener restored the slice to /active for the history frame")))

;; ==========================================================================
;; 3. encode/decode round-trip against the live (stubbed) window
;; ==========================================================================

(deftest hash-decode-round-trips-live-location-cljs
  (testing "hash-encode → set location.hash → hash-decode recovers the path
            against the live stubbed window (both round-trip legs, one browser)"
    (rf/reg-frame :rf/default {:url-bound? true})
    (register-routes!)
    (doseq [p ["/" "/active" "/completed"]]
      (.pushState js/globalThis.window.history nil "" (strategy/hash-encode p))
      (is (= p (strategy/hash-decode))
          (str "round-trip through window.location.hash recovers " (pr-str p))))))

;; ==========================================================================
;; 4. ADVERSARIAL — malformed `#`-URL fails closed to a route-miss
;; ==========================================================================

(deftest malformed-hash-url-fails-closed-cljs
  (testing "a malformed %-encoded hash URL, decoded to path-form and matched,
            fails closed to a route-miss — never a crash — exactly as a
            malformed path-URL does (adversarial: a hostile / broken deep link)"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    ;; hash-decode of #/%  →  /%  ; match-url must return nil (no throw).
    (is (nil? (routing/match-url "/%"))
        "a bare `%` (the decoded malformed hash tail) route-misses, not throws")
    ;; End-to-end: navigate the owner to the malformed decoded path — it lands
    ;; on :rf.route/not-found with the malformed reason, never crashing.
    (rf/dispatch-sync [:rf.route/handle-url-change "/%"])
    (is (= :rf.route/not-found
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "a malformed decoded hash URL routes to :rf.route/not-found (fail-closed)")))

;; A mismatched-form negative: a hash owner given a raw already-`#` URL still
;; encodes idempotently (no double-hash), so a caller that hand-passes a hash
;; href does not corrupt the pushed entry.
(deftest hash-encode-idempotent-on-raw-hash-href-cljs
  (testing "hash-encode does not double-hash an already-`#`-prefixed input"
    (is (= "#/active" (strategy/hash-encode "#/active")))
    (is (= "#/active" (strategy/hash-encode (strategy/hash-encode "/active"))))))

;; ==========================================================================
;; 5. with-base-path (rf2-g8pbwg / rf2-33uv27 / rf2-irygd6) — the CLJS-only
;;    side-effecting legs driven against the live stubbed window.
;;
;; The JVM suite (`routing_url_strategy_test.clj`) pins the host-agnostic
;; :encode/:decode wrapping + the blank-base no-op; these legs are CLJS-only
;; and had ZERO executed coverage (rf2-33uv27).
;;
;; rf2-irygd6 CONTRACT CHANGE: `:encode` is the SINGLE outbound encoding
;; authority — the nav fxs encode the path-form url ONCE (base re-added there)
;; and hand the final href to the RAW `:push!` / `:replace!` legs, which drive
;; window.history WITHOUT re-encoding. So `with-base-path` no longer wraps
;; `:push!` / `:replace!` (they pass through from the inner strategy); the base
;; rides the encoded href instead. These two tests therefore model the nav-fx
;; contract directly: encode-once, then drive the raw leg with the final href.
;; The ingress `:install-listener!` STRIPS the base (unchanged).
;; ==========================================================================

(deftest with-base-path-encode-then-push!-drives-base-prefixed-entry-cljs
  (testing "rf2-irygd6: :encode re-adds the base ONCE, then the RAW :push! leg
            drives window.history with that final href unchanged — every pushed
            entry carries the real /realworld mount-point href (base outside the
            wrapped form), mirroring how the nav fx drives it"
    (let [wrapped (strategy/with-base-path strategy/history-url-strategy "/realworld")
          drive!  (fn [p] ((:push! wrapped) ((:encode wrapped) p)))]
      (drive! "/active")
      (drive! "/completed")
      (is (= ["/" "/realworld/active" "/realworld/completed"]
             (:entries @*history-state*))
          "each entry is the base-prefixed href (encode-once → raw push)")
      (is (= "/realworld/completed" (current-url *history-state*))
          "the address bar sits on the base-prefixed completed href"))))

(deftest with-base-path-encode-then-replace!-overwrites-cljs
  (testing "rf2-irygd6: the RAW :replace! leg overwrites the current history
            entry (no new entry) with the encode-once base-prefixed href —
            mirroring the shipped strategy's replace semantics under the base"
    (let [wrapped (strategy/with-base-path strategy/history-url-strategy "/realworld")]
      ((:push! wrapped) ((:encode wrapped) "/active"))
      (let [before (count (:entries @*history-state*))]
        ((:replace! wrapped) ((:encode wrapped) "/completed"))
        (is (= before (count (:entries @*history-state*)))
            ":replace! did not add a history entry")
        (is (= "/realworld/completed" (current-url *history-state*))
            "the current entry was overwritten with the base-prefixed href")
        (is (= "/realworld/completed" (last (:entries @*history-state*)))
            "no stray /active remains as the tail — it was replaced in place")))))

(deftest with-base-path-install-listener!-strips-base-before-on-change-cljs
  (testing "rf2-33uv27: the wrapped :install-listener! STRIPS the base off each
            browser-driven change before calling on-change — a /realworld app's
            Back/Forward navs reach the router app-relative (/active), never the
            base-prefixed /realworld/active that would route-miss on every
            back-button"
    (let [wrapped  (strategy/with-base-path strategy/history-url-strategy "/realworld")
          received (atom [])
          teardown ((:install-listener! wrapped) (fn [p] (swap! received conj p)))]
      ;; Drive the browser to base-prefixed URLs and fire popstate: the inner
      ;; history-decode reads /realworld/active off the stubbed location; the
      ;; wrapper strips /realworld so on-change sees the app-relative /active.
      (.pushState js/globalThis.window.history nil "" "/realworld/active")
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (.pushState js/globalThis.window.history nil "" "/realworld/completed")
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (is (= ["/active" "/completed"] @received)
          "on-change received the base-STRIPPED app-relative path on each change")
      ;; the returned teardown thunk removes the browser listener.
      (teardown)
      (reset! received [])
      (.pushState js/globalThis.window.history nil "" "/realworld/active")
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (is (= [] @received)
          "the teardown thunk removed the popstate listener — no further deliveries"))))

(deftest with-base-path-install-listener!-mount-root-delivers-app-root-cljs
  (testing "rf2-33uv27: at the bare mount root (location == the base itself,
            /realworld) the wrapped listener delivers `/` — the app root — not
            an empty string, exactly as strip-base-path's mount-root case
            specifies"
    (let [wrapped  (strategy/with-base-path strategy/history-url-strategy "/realworld")
          received (atom nil)
          teardown ((:install-listener! wrapped) (fn [p] (reset! received p)))]
      (.pushState js/globalThis.window.history nil "" "/realworld")
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (is (= "/" @received)
          "the mount root decodes+strips to the app root `/`")
      (teardown))))

;; ==========================================================================
;; 6. rf2-irygd6 — single outbound-encoding authority: :encode / :rf.nav/push-url
;;    / :rf.nav/replace-url AGREE over both strategies, with and without a base,
;;    and no produced URL is double-encoded. The canonical hash+base shape is
;;    base OUTSIDE the fragment (/demos#/active) — the route-link href AND the
;;    address bar read it identically; inbound decode always returns the
;;    app-relative path-form. This is the divergence the bead fixes: before, the
;;    :encode href read /demos#/active while :push! drove #/demos/active.
;; ==========================================================================

(deftest hash-base-links-and-address-bar-agree-irygd6-cljs
  (testing "rf2-irygd6: a /demos-based HASH app — the route-link href, the
            :rf.nav/push-url entry, and the :rf.nav/replace-url entry ALL read
            /demos#/…-shaped (base OUTSIDE the fragment), inbound decode returns
            the app-relative path-form, and no URL is double-hashed"
    (rf/reg-frame :rf/default
                  {:url-bound?   true
                   :url-strategy (strategy/with-base-path
                                   strategy/hash-url-strategy "/demos")})
    (register-routes!)
    ;; (a) route-link href — the :encode egress leg.
    (let [[_ attrs] (rf/with-frame :rf/default
                      (routing/route-link-render {:to :s/active}))]
      (is (= "/demos#/active" (:href attrs))
          "route-link href puts the base OUTSIDE the fragment (:encode authority)")
      (is (not (double-hash? (:href attrs))) "route-link href is not double-hashed"))
    ;; (b) :rf.nav/push-url — the address bar agrees with the href.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (is (= ["/" "/demos#/active"] (:entries @*history-state*))
        "the pushed history entry matches the route-link href — base outside the fragment")
    (is (= :s/active (route-slice-id :rf/default))
        "the route slice tracks the path-form route (cascade stays path-form)")
    (is (not (double-hash? (current-url *history-state*)))
        "the pushed address-bar URL is not double-hashed (#/demos#/active)")
    ;; (c) :rf.nav/replace-url — overwrites in place with the same shape.
    (let [before (count (:entries @*history-state*))]
      (rf/dispatch-sync [:rf.route/navigate :s/completed {} {:replace? true}])
      (is (= before (count (:entries @*history-state*)))
          "replace did not add a history entry")
      (is (= "/demos#/completed" (current-url *history-state*))
          "the replaced entry is base-outside-fragment shaped, agreeing with :encode")
      (is (not (double-hash? (current-url *history-state*)))
          "the replaced URL is not double-hashed"))
    ;; (d) inbound decode returns the app-relative path-form.
    (let [strat (strategy/url-strategy-for-frame-id :rf/default)]
      (is (= "/completed" ((:decode strat)))
          "decode of the live /demos#/completed address bar is app-relative /completed"))))

(deftest history-base-links-and-address-bar-agree-irygd6-cljs
  (testing "rf2-irygd6 mirror: a /demos-based HISTORY app — route-link href +
            push + replace all read /demos/…-shaped, decode is app-relative
            (behaviour unchanged by the seam, pinned for parity with the hash case)"
    (rf/reg-frame :rf/default
                  {:url-bound?   true
                   :url-strategy (strategy/with-base-path
                                   strategy/history-url-strategy "/demos")})
    (register-routes!)
    (let [[_ attrs] (rf/with-frame :rf/default
                      (routing/route-link-render {:to :s/active}))]
      (is (= "/demos/active" (:href attrs))
          "route-link href re-adds the base to the path-form href"))
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (is (= ["/" "/demos/active"] (:entries @*history-state*))
        "the pushed entry carries the base-prefixed path")
    (is (= :s/active (route-slice-id :rf/default))
        "the route slice tracks the path-form route")
    (let [before (count (:entries @*history-state*))]
      (rf/dispatch-sync [:rf.route/navigate :s/completed {} {:replace? true}])
      (is (= before (count (:entries @*history-state*)))
          "replace did not add a history entry")
      (is (= "/demos/completed" (current-url *history-state*))
          "the replaced entry is base-prefixed, agreeing with :encode"))
    (let [strat (strategy/url-strategy-for-frame-id :rf/default)]
      (is (= "/completed" ((:decode strat)))
          "decode strips the base — app-relative /completed"))))

(deftest hash-no-base-push-is-single-hash-irygd6-cljs
  (testing "rf2-irygd6: the HASH strategy WITHOUT a base still pushes a single
            #/active (no double-hash) — the raw :push! leg drives exactly the
            :encode-produced href. (History-without-base is pinned by
            `history-frame-push-url-pushes-path-href-cljs`.)"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/active"}])
    (is (= ["/" "#/active"] (:entries @*history-state*))
        "hash pushes a single-# href")
    (is (not (double-hash? (current-url *history-state*)))
        "the hash URL is not double-hashed")))
