(ns re-frame.routing-url-strategy-cljs-test
  "CLJS tests for the URL-strategy seam (rf2-aerrz5) — the four
  egress/ingress consult points driven end-to-end against a stubbed
  `window`. The JVM suite (`routing_url_strategy_test.clj`) pins the pure
  encode/decode legs + frame-config resolution; this suite pins the
  side-effecting halves a browser owns:

  1. `:rf.nav/push-url` / `:rf.nav/replace-url` through a HASH-strategy frame
     push/replace the `#`-prefixed href (history entries carry `#/active`).
  2. `route-link` renders a `#`-prefixed `:href` for a hash frame.
  3. `install-url-listener!` wires a `hashchange` listener for a hash frame
     (a `popstate` listener for a history frame) and decodes each change to
     path-form before dispatching `:rf.route/handle-url-change`.
  4. The encode/decode ROUND-TRIP holds against the live (stubbed) window.

  Plus an ADVERSARIAL negative: a malformed `#`-URL fails closed to a
  route-miss, exactly as a malformed path-URL does.

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
    ;; :rf/url-requested resolves in-app, pushes, and synthesises the transition.
    (rf/dispatch-sync [:rf/url-requested {:url "/active"}])
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
    (rf/dispatch-sync [:rf/url-requested {:url "/active"}])
    (is (= ["/" "/active"] (:entries @*history-state*))
        "the default history strategy pushes the bare path — no `#`")))

(deftest hash-frame-replace-url-replaces-hash-href-cljs
  (testing "a HASH-strategy owner's :rf.nav/replace-url overwrites the current
            entry with the `#` href (no new entry)"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    (rf/dispatch-sync [:rf/url-requested {:url "/active"}])
    (let [before (count (:entries @*history-state*))]
      (rf/dispatch-sync [:rf.route/navigate :s/completed {} {:replace? true}])
      (is (= before (count (:entries @*history-state*)))
          "replace did not add a history entry")
      (is (= "#/completed" (current-url *history-state*))
          "the current entry was overwritten with the `#`-prefixed completed href"))))

;; ==========================================================================
;; 2. install-url-listener! wires hashchange for a hash frame + round-trips
;; ==========================================================================

(deftest hash-frame-install-listener-wires-hashchange-cljs
  (testing "install-url-listener! wires a `hashchange` listener for a hash
            frame, decodes location.hash to path-form, and dispatches
            handle-url-change to the owner — the browser→app leg of the seam"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    (rf/install-url-listener!)
    ;; Push two hash routes (forward nav via the owner).
    (rf/dispatch-sync [:rf/url-requested {:url "/active"}])
    (rf/dispatch-sync [:rf/url-requested {:url "/completed"}])
    (is (= :s/completed
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "slice on /completed before Back")
    ;; Browser Back moves the hash to #/active; a hashchange fires. The
    ;; installed listener decodes #/active → /active and drives the owner.
    (.back (.-history js/globalThis.window))
    (is (= "#/active" (current-url *history-state*))
        "back() moved the address bar to the #/active entry")
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :s/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the hashchange listener decoded #/active → /active and restored the slice")
    (rf/remove-url-listener!)
    ;; After teardown a further hashchange is a no-op.
    (.forward (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :s/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "after remove-url-listener! a hashchange no longer drives the slice")))

(deftest history-frame-install-listener-wires-popstate-cljs
  (testing "install-url-listener! wires a `popstate` listener for a history
            (default) frame — the seam preserves the existing history behaviour"
    (rf/reg-frame :rf/default {:url-bound? true})
    (register-routes!)
    (rf/install-url-listener!)
    (rf/dispatch-sync [:rf/url-requested {:url "/active"}])
    (rf/dispatch-sync [:rf/url-requested {:url "/completed"}])
    (.back (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= :s/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the popstate listener restored the slice to /active for the history frame")
    (rf/remove-url-listener!)))

(deftest install-history-listener-alias-still-works-cljs
  (testing "install-history-listener! is retained as an alias for
            install-url-listener! (back-compat) and drives a hash frame too"
    (rf/reg-frame :rf/default {:url-bound?   true
                               :url-strategy strategy/hash-url-strategy})
    (register-routes!)
    (rf/install-history-listener!)          ;; the alias
    (rf/dispatch-sync [:rf/url-requested {:url "/active"}])
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :s/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the alias wired the hash listener (owner strategy decides the kind)")
    (rf/remove-history-listener!)))

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
