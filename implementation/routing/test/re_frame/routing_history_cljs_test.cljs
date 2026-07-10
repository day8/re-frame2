(ns re-frame.routing-history-cljs-test
  "CLJS tests for the browser-history surface of routing
  (rf2-wp0w4). Locks the popstate / hashchange / pushState /
  replaceState round-trip on the node-runtime test target.

  re-frame2's history-integration contract (Spec 012 §URL changes
  are events) is split across two functions:

  - `:rf.nav/push-url`     fx — calls `(.pushState js/window.history nil \"\" url)`.
  - `:rf.nav/replace-url`  fx — calls `(.replaceState js/window.history nil \"\" url)`.
  - `:rf.route/transitioned`      event — forward nav (push / link click).
  - `:rf.route/handle-url-change` event — popstate / initial / SSR.

  The runtime wires `window.addEventListener('popstate', ...)` itself,
  automatically, as part of the `:url-bound?` frame LIFECYCLE (rf2-g8pbwg):
  a `:url-bound? true` frame's creation (or re-registration, when it
  resolves as the URL owner) installs the listener; its destroy removes it.
  Some tests below still drive the browser→app leg by hand-dispatching
  `:rf.route/handle-url-change` (the shape a hand-rolled/legacy listener, or
  SSR, uses) to pin the event's own contract independent of the automatic
  wiring; the dedicated lifecycle tests near the end of this file pin the
  automatic install/remove contract itself. The tests below exercise both
  halves: the OUTBOUND fx (pushState / replaceState actually touch the
  history object) AND the INBOUND event (popstate-style dispatch updates
  the slice + fires :on-match + re-emits the nav-token-allocated trace).

  Mock approach — Node has no `window`/`document` globals, so this
  file installs a minimal jsdom-style stub on `js/globalThis` via a
  `:once` fixture (set up before `routing.cljc`'s fx run; torn down
  after). The stub records `pushState` / `replaceState` calls onto an
  in-memory entry stack and exposes `back` / `forward` / `go` so the
  popstate path can be driven without a real DOM. The fixture is
  scoped to this test ns; production code is untouched.

  Per Spec 012 §URL changes are events, §Navigation tokens, §Scroll
  restoration."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; rf2-qwm0a: listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.routing :as routing]
            ;; rf2-w3qgc: internal URL-classifier namespace — `external-url?`
            ;; / `request-url->app-url` are not facade-exported, so the
            ;; non-string fail-closed test calls them directly.
            [re-frame.routing.url :as routing-url]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            ;; rf2-y6e2zb: the browser history/location/document stub,
            ;; `*history-state*`, `current-url`, and `with-window-stub-fixture`
            ;; are the SUPERSET fixture shared with routing_url_strategy_cljs_test.
            [re-frame.routing-browser-test-support
             :refer [*history-state* current-url with-window-stub-fixture]]))

;; ---- window / history stub -----------------------------------------------
;;
;; The jsdom-style history/location/document stub, `*history-state*`,
;; `current-url`, and `with-window-stub-fixture` now live in the shared
;; re-frame.routing-browser-test-support ns (rf2-y6e2zb) — the SUPERSET fixture
;; this suite and routing_url_strategy_cljs_test both drive. This suite reads
;; `*history-state*` / `current-url` directly and composes
;; `with-window-stub-fixture` FIRST in `use-fixtures` below.

(use-fixtures :each
  with-window-stub-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     ;; rf2-1hncp2: the scroll-position cache is a module-level host atom
     ;; (not runtime-db), so the runtime reset does not touch it — drop it
     ;; explicitly so a captured position never leaks across tests.
     :init-fn (fn []
                (routing/reset-counters!)
                (routing/reset-scroll-cache!))}))

;; ---- trace-capture helper ------------------------------------------------

(defn- with-route-traces
  "Run thunk while collecting :rf.route.nav-token/allocated events.
   Returns [result-of-thunk vector-of-trace-payloads]."
  [thunk]
  (let [captured (atom [])
        cb-key   (keyword (gensym "route-trace-"))]
    (trace-tooling/register-listener!
      cb-key
      (fn [ev]
        (when (= :rf.route.nav-token/allocated (:operation ev))
          (swap! captured conj (:tags ev)))))
    (try
      (let [r (thunk)]
        [r @captured])
      (finally
        (trace-tooling/unregister-listener! cb-key)))))

;; ---- routes used across the suite ---------------------------------------

(defn- register-routes! []
  ;; EP-0002 (rf2-9o48ih): URL ownership is now an EXPLICIT declaration —
  ;; the runtime no longer infers `:rf/default` as the URL owner from
  ;; absence (`url-owner-frame-id` returns nil unless a frame declares
  ;; `:url-bound? true`). The fixture's `ensure-default-frame!` creates
  ;; `:rf/default` WITHOUT the slot, so opt it in explicitly here as this
  ;; suite's URL owner — otherwise `:rf.nav/push-url` / `:rf.nav/replace-url`
  ;; never fire and the history stack stays at one entry. Tests that drive a
  ;; non-default owner re-register `:rf/default {:url-bound? false}` AFTER
  ;; this call, so their override wins.
  (rf/reg-frame :rf/default {:url-bound? true})
  (rf/reg-route :hist/home     {} "/")
  (rf/reg-route :hist/cart     {} "/cart")
  (rf/reg-route :hist/checkout {} "/checkout")
  (rf/reg-route :hist/article  {:params [:map [:id :string]]} "/articles/:id"))

;; =========================================================================
;; 1. pushState round-trip
;; =========================================================================

(deftest pushstate-round-trip-cljs
  (testing "[:rf.route/url-requested {:url \"/cart\"}] → history.pushState pushes the URL onto the stack AND the :rf/route slice updates"
    (register-routes!)

    ;; Sanity: stub starts at "/" with one entry.
    (is (= ["/"] (:entries @*history-state*))
        "history stub starts with the single root entry")
    (is (= "/" (current-url *history-state*))
        "current URL is /")

    (let [[_ traces]
          (with-route-traces
            (fn []
              (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])))]
      ;; pushState side-effect: a new entry sits on top of the stack.
      (is (= ["/" "/cart"] (:entries @*history-state*))
          ":rf.nav/push-url appended /cart to the history stack")
      (is (= 1 (:index @*history-state*))
          "the history index advanced to the new top entry")
      (is (= "/cart" (current-url *history-state*))
          "history.current points at /cart")

      ;; Slice side-effect: :rf/route was rewritten.
      (let [route (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
        (is (= :hist/cart (:route-id route))
            "the :rf/route slice carries the new route id")
        (is (some? (:nav-token route))
            "a fresh :nav-token is allocated"))

      ;; Trace side-effect: nav-token allocation fired exactly once
      ;; for this dispatch. (Per Spec 012 §Navigation tokens.)
      (is (= 1 (count traces))
          ":rf.route.nav-token/allocated fired once for the pushState nav")
      (is (= :hist/cart (-> traces first :route-id))
          "the trace's :route-id matches the new route"))))

(deftest pushstate-multiple-entries-cljs
  (testing "successive :rf.route/url-requested dispatches stack history entries in order"
    (register-routes!)

    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/articles/intro"}])

    (is (= ["/" "/cart" "/checkout" "/articles/intro"]
           (:entries @*history-state*))
        "four entries on the stack in dispatch order")
    (is (= 3 (:index @*history-state*))
        "index points at the most recent entry")
    (is (= :hist/article
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the slice tracks the most recently pushed URL")))

(deftest url-requested-external-url-does-not-push-cljs
  (testing "external absolute URLs are classified before pushState"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/"])
    (rf/dispatch-sync [:rf.route/url-requested {:url "https://elsewhere.example/cart"}])
    (is (= ["/"] (:entries @*history-state*))
        "external URL did not append a history entry")
    (is (= :hist/home
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "external URL did not rewrite the app route to not-found")))

(deftest url-requested-non-string-url-fails-closed-cljs-rf2-w3qgc
  (testing "rf2-w3qgc: with a live window/location, a NON-STRING `:url`
            (nil / number / boolean / object) is classed EXTERNAL and never
            pushed — JS would otherwise stringify it through `js/URL`
            (`new URL(null, base)` → `/null`, numbers → `/123`) and class it
            same-origin, pushing a FABRICATED in-app URL. The browser path
            must fail closed identically to the JVM/no-window fallback."
    (register-routes!)
    ;; Land on /cart so we can prove the slice does NOT move on a bad URL.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (is (= :hist/cart
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "precondition: active route is :hist/cart")
    (let [entries-before (:entries @*history-state*)
          index-before   (:index @*history-state*)]
      (doseq [bad [nil 123 true false (js-obj "toString" (fn [] "/checkout")) #js {} []]]
        ;; external-url? classifies the raw value as external (fail closed).
        (is (true? (routing-url/external-url? bad))
            (str "non-string url " (pr-str bad) " classes EXTERNAL"))
        ;; request-url->app-url must NOT canonicalise a non-string (gate is
        ;; external? → returns the value unchanged, never touching js/URL).
        (is (= bad (routing-url/request-url->app-url bad))
            (str "request-url->app-url leaves non-string " (pr-str bad) " unchanged"))
        ;; End-to-end: the :rf.route/url-requested sink does not push or rewrite.
        (rf/dispatch-sync [:rf.route/url-requested {:url bad}])
        (is (= entries-before (:entries @*history-state*))
            (str "non-string url " (pr-str bad) " appended NO history entry"))
        (is (= index-before (:index @*history-state*))
            (str "non-string url " (pr-str bad) " did not move the history index"))
        (is (= :hist/cart
               (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
            (str "non-string url " (pr-str bad) " did not rewrite the route slice"))))
    ;; Sanity: a normal same-origin string STILL works through the same path.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
    (is (= "/checkout" (current-url *history-state*))
        "a normal same-origin string still pushes through after the guard")
    (is (= :hist/checkout
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the slice tracks the legitimate same-origin navigation")))

;; ---- rf2-aftbmz: external-url? BROWSER js/URL branch — the open-redirect gate
;;
;; `external-url?` (url.cljc:203-236) is fail-closed by TWO impls of one
;; contract: a JVM/no-window LEXICAL path (`safe-in-app-url?`, exhaustively
;; adversarial in routing_navigation_test.clj) and the CLJS LIVE-WINDOW path
;; (js/URL + protocol-allowlist + origin-compare, url.cljc:224-231). The
;; browser path is the classifier that actually runs where an open redirect
;; is the live risk. Its `or` has TWO clauses:
;;
;;   (or (not (#{"http:" "https:"} protocol))        ; A — protocol allowlist
;;       (not= (.-origin parsed) (.-origin loc)))     ; B — origin compare
;;
;; The window stub above (document origin https://app.example, node global
;; `js/URL`) makes THIS branch run in the node-test target. Pre-existing
;; coverage reached ONLY clause B (the single https://elsewhere.example
;; vector at :320, plus the non-string guard which short-circuits BEFORE
;; js/URL). Clause A — the non-http(s)-scheme gate — was reached by NO CLJS
;; test, so a regression there (dropped allowlist, `.-host` vs `.-origin`,
;; inverted compare) is a SILENT browser open-redirect the gates never catch.
;; These deftests drive the JVM lexical bypass matrix through the browser
;; classifier so BOTH clauses are exercised + asserted.

(deftest external-url-browser-protocol-allowlist-cljs-rf2-aftbmz
  (testing "rf2-aftbmz: the live-window js/URL branch fails closed on
            non-http(s) schemes AND off-origin authorities — driving BOTH the
            protocol-allowlist clause and the origin-compare clause"
    (register-routes!)
    (let [doc-origin (.-origin (.-location js/globalThis.window))]
      (is (= "https://app.example" doc-origin)
          "precondition: the window stub's document origin is https://app.example")

      (testing "protocol-allowlist clause is LOAD-BEARING — a SAME-ORIGIN
                blob: URL (matching origin, non-http(s) scheme) is EXTERNAL
                ONLY via clause A; a dropped allowlist would class it in-app
                (the exact silent open-redirect / scheme-smuggling regression)"
        (is (true? (routing-url/external-url? (str "blob:" doc-origin "/1234-uuid")))
            "blob:<same-origin> classes EXTERNAL — origins MATCH here, so the
             protocol-allowlist clause is the sole gate that catches it"))

      (testing "non-http(s) schemes reach the allowlist clause and fail closed"
        (doseq [u ["javascript:alert(1)"
                   "data:text/html,<script>alert(1)</script>"
                   "file:///etc/passwd"]]
          (is (true? (routing-url/external-url? u))
              (str "non-http(s) scheme " (pr-str u)
                   " classes EXTERNAL via the protocol allowlist (clause A)"))))

      (testing "off-origin http(s) authorities fail closed via origin-compare"
        (doseq [u ["//evil.example/x"                ;; protocol-relative → https://evil.example
                   "http://other-host.example/x"     ;; different host + scheme
                   "https://good@evil.example/x"]]    ;; userinfo-confusion → origin evil.example
          (is (true? (routing-url/external-url? u))
              (str "off-origin URL " (pr-str u)
                   " classes EXTERNAL via the origin-compare clause (clause B)"))))

      (testing "a SAME-ORIGIN ABSOLUTE http(s) URL is the one in-app case —
                proving the browser gate is not blanket-true"
        (is (false? (routing-url/external-url? (str doc-origin "/cart?q=1#frag")))
            "same-origin absolute URL passes BOTH clauses → in-app (false)")))))

(deftest request-url->app-url-canonicalizes-same-origin-absolute-cljs-rf2-aftbmz
  (testing "rf2-aftbmz: request-url->app-url canonicalizes a SAME-ORIGIN
            ABSOLUTE URL to its origin-relative pathname+search+hash via the
            live-window js/URL leg (url.cljc:249-250) — the canonicalization
            leg every prior nav test routed around by passing already-relative
            URLs, so it was reached by no test"
    (register-routes!)
    (let [doc-origin (.-origin (.-location js/globalThis.window))]
      (is (= "/cart?q=1#frag"
             (routing-url/request-url->app-url (str doc-origin "/cart?q=1#frag")))
          "a same-origin ABSOLUTE URL is reduced to pathname+search+hash")
      (is (= "/cart?q=1#frag"
             (routing-url/request-url->app-url "/cart?q=1#frag"))
          "an already-relative in-app URL canonicalizes to itself")
      (is (= "https://evil.example/x"
             (routing-url/request-url->app-url "https://evil.example/x"))
          "an EXTERNAL URL is passed through unchanged — the external? gate
           short-circuits the canonicalize (canonicalising it could fabricate
           an in-app-looking path)"))))

(deftest scroll-position-captured-before-forward-nav-cljs
  (testing "leaving a route captures the current browser scroll position under that route's URL"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (.scrollTo js/globalThis.window 12 345)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
    ;; rf2-1hncp2: scroll-position caches are a HOST-SIDE TRANSIENT cache
    ;; (not runtime-db) — read the frame's host cache, not the runtime-db.
    (is (= [12 345]
           (routing/lookup-scroll-position
             (routing/frame-scroll-cache :rf/default)
             "/cart"))
        "scroll position for the route being left is saved before the scroll strategy runs")
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/routing :scroll-positions]))
        "the position is NOT written to runtime-db — it stays off the egress wire")))

(deftest duplicate-url-bound-frame-does-not-push-cljs
  (testing "a second :url-bound? true frame is reported but not allowed to mutate browser history"
    ;; rf2-3l7xxz: `register-routes!` declares `:rf/default {:url-bound? true}`
    ;; as the established (first-claimed) URL owner. The duplicate here sorts
    ;; AFTER `:rf/default` (`:zz/duplicate-owner`); the companion test below
    ;; covers the harder case — a duplicate that sorts BEFORE the incumbent,
    ;; which the prior alphabetical resolver let STEAL the URL. A push from the
    ;; non-owner duplicate is suppressed.
    (register-routes!)
    (rf/reg-frame :zz/duplicate-owner {:url-bound? true})
    (rf/dispatch-sync [:rf.route/navigate :hist/cart]
                      {:frame :zz/duplicate-owner})
    (is (= ["/"] (:entries @*history-state*))
        "duplicate URL-bound frame did not push to browser history")
    (is (= :hist/cart
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :zz/duplicate-owner)) [:rf.runtime/routing :current])))
        "the non-owner frame still updates its own route slice")))

(deftest duplicate-sorting-before-incumbent-does-not-steal-url-cljs
  (testing "rf2-3l7xxz: a duplicate :url-bound? true frame whose id sorts
            BEFORE the incumbent (:aaa-early < :rf/default) does NOT steal the
            browser URL — the incumbent still drives pushState, the duplicate's
            push no-ops. This is the case the prior `(sort-by (str id))`
            resolver got wrong (it would have made :aaa-early the owner)."
    (register-routes!)               ;; :rf/default claims the URL first
    (rf/reg-frame :aaa-early {:url-bound? true})   ;; sorts before :rf/default
    ;; The earlier-sorting duplicate navigates — under the bug it owned the URL
    ;; and would push. It must NOT touch browser history now.
    (rf/dispatch-sync [:rf.route/navigate :hist/cart] {:frame :aaa-early})
    (is (= ["/"] (:entries @*history-state*))
        "the earlier-sorting duplicate did NOT steal the URL / push to history")
    (is (= :hist/cart
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :aaa-early)) [:rf.runtime/routing :current])))
        "the duplicate still updates its OWN route slice (binding reported, not rejected)")
    ;; The incumbent :rf/default still drives the browser URL.
    (rf/dispatch-sync [:rf.route/navigate :hist/checkout] {:frame :rf/default})
    (is (= ["/" "/checkout"] (:entries @*history-state*))
        "the incumbent :rf/default still owns + pushes the URL")
    (is (= :hist/checkout
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the incumbent's slice tracks the legitimate navigation")))

;; =========================================================================
;; 2. popstate (back-button) round-trip
;; =========================================================================

(deftest popstate-back-button-cljs
  (testing "after two pushes, (.back history) + dispatch :rf.route/handle-url-change drops the slice back to the prior route"
    (register-routes!)

    ;; Push two routes.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
    (is (= :hist/checkout
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "slice is on /checkout before the back-button")

    ;; Simulate back-button: browser would (a) move history.index back
    ;; and (b) fire a `popstate` event. The app is responsible for
    ;; reading the new URL from the browser and dispatching
    ;; :rf.route/handle-url-change with it. We exercise both halves.
    (.back (.-history js/globalThis.window))
    (is (= "/cart" (current-url *history-state*))
        "back() moved the history pointer to /cart (no NEW entry created)")
    (is (= 3 (count (:entries @*history-state*)))
        "back() does NOT mutate the entry stack — it only moves the index")
    (is (= 1 (:index @*history-state*))
        "history.index now references the /cart entry")

    ;; The popstate dispatch the app would issue.
    (let [[_ traces]
          (with-route-traces
            (fn []
              (rf/dispatch-sync
                [:rf.route/handle-url-change (current-url *history-state*)])))]
      (is (= :hist/cart
             (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
          "the slice fell back to :hist/cart after the popstate-style dispatch")
      (is (= 1 (count traces))
          "the popstate dispatch fires exactly one :rf.route.nav-token/allocated")
      (is (= :hist/cart (-> traces first :route-id))
          "the trace identifies the route we landed on"))))

(deftest popstate-via-window-listener-cljs
  (testing "registering a popstate listener via window.addEventListener fires when the stub dispatches"
    (register-routes!)

    ;; This is the wiring an app would actually do: register a
    ;; popstate listener that dispatches :rf.route/handle-url-change
    ;; with the URL the browser landed on.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])

    (let [fired? (atom false)
          listener (fn [_event]
                     (reset! fired? true)
                     (rf/dispatch-sync
                       [:rf.route/handle-url-change
                        (current-url *history-state*)]))]
      (.addEventListener js/globalThis.window "popstate" listener)

      ;; Simulate the browser sequence: back() then dispatch popstate.
      (.back (.-history js/globalThis.window))
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})

      (is @fired? "the popstate listener registered via addEventListener fired")
      (is (= :hist/cart
             (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
          "the slice landed on /cart through the listener-driven popstate path"))))

;; ---- rf2-6qgbs.4: popstate drives the URL-OWNER frame --------------------
;;
;; Regression for the step-deck Back/Forward bug. After rf2-6qgbs.3 a
;; non-default frame can own the URL (`:rf/default` opts out, the
;; non-default frame opts in). The PUSH side already routed through that
;; owner (`:rf.nav/push-url` gate). The POP side did not — a hand-rolled
;; popstate listener dispatched `:rf.route/handle-url-change` with no
;; `:frame`, hitting `:rf/default` (now frozen) instead of the owner, so
;; Back/Forward left the owner's route — and the rendered body — unchanged.
;;
;; rf2-g8pbwg: a `:url-bound? true` frame's REGISTRATION automatically
;; (re)installs the listener when it resolves as `url-owner-frame-id` — no
;; imperative install call. The installed listener still resolves the owner
;; at POP TIME, so the test asserts the owner frame's slice round-trips on
;; back AND forward while `:rf/default` stays put.

;; The installed popstate handler dispatches synchronously
;; (`dispatch-sync!`) — a real `popstate` fires on the browser macrotask
;; loop, never nested in a drain — so the route slice settles within the
;; same turn as `dispatchEvent` and the assertions can read it directly.

(deftest popstate-drives-url-owner-non-default-frame-cljs
  (testing "rf2-g8pbwg / rf2-6qgbs.4: the :url-bound? lifecycle automatically
            drives the non-default URL-owner frame on Back/Forward"
    (register-routes!)
    ;; Single-non-default-owner setup (the step-deck shape): default opts
    ;; OUT, a non-default frame opts IN, so `url-owner-frame-id` resolves
    ;; to the non-default owner — and `:sd/owner`'s reg-frame automatically
    ;; installs the listener for it (rf2-g8pbwg).
    (rf/reg-frame :rf/default {:url-bound? false})
    (rf/reg-frame :sd/owner   {:url-bound? true})
    (is (= :sd/owner (routing/url-owner-frame-id))
        "the non-default :url-bound? true frame owns the URL after default opts out")
    ;; :rf/default briefly resolved as the URL owner during register-routes!
    ;; above (before opting out on the very next line), so its OWN
    ;; registration already triggered ONE automatic initial-URL sync
    ;; (rf2-g8pbwg) — a side effect of having briefly BEEN the declared
    ;; owner, not something popstate does. Capture that value so the
    ;; assertions below prove POPSTATE itself never touches the non-owner,
    ;; independent of whatever this shared-registrar test bundle's `match-url
    ;; "/"` happened to resolve to at that transient moment.
    (let [default-route-before (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current]))]

      ;; Forward nav on the owner frame pushes the URL (owner gates push).
      (rf/dispatch-sync [:rf.route/navigate :hist/cart]     {:frame :sd/owner})
      (rf/dispatch-sync [:rf.route/navigate :hist/checkout] {:frame :sd/owner})
      (is (= ["/" "/cart" "/checkout"] (:entries @*history-state*))
          "owner-frame forward nav pushed both URLs onto the history stack")
      (is (= :hist/checkout (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :sd/owner)) [:rf.runtime/routing :current])))
          "owner slice is on /checkout before Back")

      ;; --- Back: browser moves the pointer + fires popstate. ---
      (.back (.-history js/globalThis.window))
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (is (= :hist/cart (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :sd/owner)) [:rf.runtime/routing :current])))
          "Back restored the OWNER frame's slice to /cart via the installed listener")
      (is (= default-route-before
             (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
          ":rf/default (the non-owner) was NOT mutated by the popstate")

      ;; --- Forward: pointer moves up, popstate fires again. ---
      (.forward (.-history js/globalThis.window))
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (is (= :hist/checkout (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :sd/owner)) [:rf.runtime/routing :current])))
          "Forward restored the OWNER frame's slice back to /checkout")
      (is (= default-route-before
             (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
          ":rf/default still untouched after Forward"))))

(deftest popstate-targets-incumbent-after-earlier-sorting-duplicate-cljs
  (testing "rf2-3l7xxz: after a duplicate :url-bound? true frame that sorts
            BEFORE the incumbent registers, popstate (Back/Forward) STILL
            targets the incumbent owner — not the earlier-sorting duplicate.
            The popstate listener resolves url-owner-frame-id at pop time, so a
            stolen-ownership resolution would have driven the WRONG frame."
    (register-routes!)               ;; :rf/default claims the URL first + auto-installs
    (rf/reg-frame :aaa-early {:url-bound? true})   ;; sorts before :rf/default — a losing duplicate, never installs (rf2-g8pbwg)
    (is (= :rf/default (routing/url-owner-frame-id))
        "incumbent :rf/default is still the owner despite the earlier-sorting duplicate")
    ;; Incumbent forward-navigates (it owns push), building a history stack.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
    (is (= :hist/checkout
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "incumbent slice on /checkout before Back")
    ;; Back-button → popstate. The listener must target the incumbent.
    (.back (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= :hist/cart
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "Back restored the INCUMBENT :rf/default slice — popstate targeted the right frame")
    (is (nil? (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :aaa-early)) [:rf.runtime/routing :current])))
        "the earlier-sorting duplicate :aaa-early was NOT driven by popstate")))

(deftest popstate-drives-default-owner-when-default-bound-cljs
  (testing "rf2-g8pbwg / rf2-6qgbs.4: the automatically-installed listener
            drives :rf/default when it is the owner (no regression)"
    (register-routes!)
    ;; Default-owned app: url-owner-frame-id resolves to :rf/default, so
    ;; register-routes!'s reg-frame auto-installed the listener for it.
    (is (= :rf/default (routing/url-owner-frame-id))
        ":rf/default owns the URL by default")

    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
    (is (= :hist/checkout (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "default slice on /checkout before Back")

    (.back (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= :hist/cart (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "Back restored :rf/default's slice to /cart — default-owned routing unregressed")))

;; =========================================================================
;; rf2-g8pbwg: the :url-bound? frame LIFECYCLE installs/removes the listener
;; =========================================================================
;;
;; The full fold this bead makes: `install-url-listener!` /
;; `install-history-listener!` / `remove-url-listener!` /
;; `remove-history-listener!` are DELETED from the public facade (no
;; compatibility shim — pre-alpha). A `:url-bound? true` frame's CREATE
;; installs the strategy listener; its DESTROY removes it. A losing
;; duplicate `:url-bound? true` registration never installs at all.

(deftest url-bound-frame-lifecycle-installs-on-create-and-removes-on-destroy-cljs
  (testing "rf2-g8pbwg: a :url-bound? true frame automatically installs its
            popstate listener on create and removes it on destroy-frame! —
            zero imperative install/remove calls anywhere"
    (register-routes!)   ;; :rf/default {:url-bound? true} — auto-installs on create
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
        "the listener installed automatically when the owner frame was created")

    ;; Back/Forward already works with zero imperative wiring.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (.back (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= :hist/home
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the automatically-installed listener drove Back with no install call")

    ;; Destroy tears the browser listener down.
    (rf/destroy-frame! :rf/default)
    (is (empty? (get-in @*history-state* [:listeners "popstate"]))
        "destroy-frame! removed the browser popstate listener")))

(deftest duplicate-url-bound-frame-does-not-reinstall-listener-cljs
  (testing "rf2-g8pbwg: a losing duplicate :url-bound? true registration does
            NOT reinstall the popstate listener — the incumbent's listener
            instance is untouched (Codex correction: a losing duplicate must
            never install its own / a replacement strategy listener)"
    (register-routes!)                          ;; :rf/default claims + auto-installs
    (let [installed-before (first (get-in @*history-state* [:listeners "popstate"]))]
      (is (some? installed-before)
          "the incumbent's popstate listener installed automatically on create")
      (rf/reg-frame :zz/dup-owner {:url-bound? true})   ;; losing duplicate
      (is (identical? installed-before
                       (first (get-in @*history-state* [:listeners "popstate"])))
          "the duplicate's registration did not tear down + reinstall the incumbent's listener"))))

;; ---- rf2-9vgyp7: first-load / deep-link route hydration at frame create ----
;;
;; The URL-owning frame's LIFECYCLE (rf2-g8pbwg) drives the initial URL sync: a
;; `:url-bound? true` frame's (re-)registration installs its strategy listener
;; and immediately syncs the CURRENT browser URL into the route slice — and it
;; does so SYNCHRONOUSLY during frame creation. `frame-provider` runs that
;; creation in RENDER PHASE, BEFORE any child renders
;; (`re-frame.views.owned-frame/ensure-frame-fc`), so a `root-view` reading
;; `:rf/route` / `:rf.route/id` on its FIRST render sees the matched route, never
;; a nil slice — even for a deep link or a hard refresh. (This is the correct
;; ordering the retired imperative `install-url-listener!`-at-boot pattern could
;; get wrong: install-before-frame-exists skipped the sync. The fold makes the
;; frame lifecycle own the ordering.) This pins the guarantee: register the URL
;; owner while the browser already sits at a deep link and assert the slice is
;; hydrated the instant the frame exists — no dispatch, no render, no popstate.

(deftest first-load-deep-link-hydrates-slice-at-create-cljs-rf2-9vgyp7
  (testing "a :url-bound? true frame created while the browser sits at a deep
            link hydrates its route slice SYNCHRONOUSLY at create time, so the
            first read sees the matched route (not nil)"
    ;; Routes exist first (so the create-time sync can match), but the URL
    ;; owner is not yet bound.
    (rf/reg-route :hist/home    {} "/")
    (rf/reg-route :hist/article {:params [:map [:id :string]]} "/articles/:id")
    ;; Move the browser to a deep link BEFORE the URL owner is bound — the exact
    ;; first-load / hard-refresh condition (window.location is now /articles/42).
    (.pushState (.-history js/globalThis.window) nil "" "/articles/42")
    ;; Bind the URL owner (the fixture pre-created :rf/default WITHOUT the slot;
    ;; opting it in is a re-registration whose lifecycle hook runs the initial
    ;; sync synchronously during reg-frame).
    (rf/reg-frame :rf/default {:url-bound? true})
    (let [slice (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/routing :current])]
      (is (some? slice)
          "the route slice is populated the instant the url-bound frame exists")
      (is (= :hist/article (:route-id slice))
          "the deep-link URL matched its route at create time (not nil / not-found)")
      (is (= {:id "42"} (:params slice))
          "the deep-link path param hydrated into the slice — a first render sees it"))))

;; ---- rf2-ede1h.3: blocked popstate restores the browser URL -------------
;;
;; Per Spec 012 §Navigation blocking §Default flow step 4c — "the URL
;; does not change" on a block. A FORWARD nav never moved the URL, so
;; declining to push is enough. A POPSTATE block (Back/Forward dispatches
;; :rf.route/handle-url-change) is different: the browser has ALREADY
;; moved the address bar to the rejected URL. Without a restore the
;; address bar and the :rf/route slice diverge — the slice stays on the
;; rejecting route, the URL shows the destination. The runtime emits a
;; :rf.nav/replace-url (history replace, no new entry) that restores the
;; address bar to the current slice's URL so the two agree again.

(deftest blocked-popstate-restores-url-cljs
  (testing "rf2-ede1h.3: a :can-leave guard blocking a Back/Forward popstate
            restores the browser URL to the slice's route; the slice stays put"
    ;; EP-0002 (rf2-9o48ih): URL ownership is explicit — opt `:rf/default` in
    ;; as the URL owner so the restore `:rf.nav/replace-url` fx fires.
    (rf/reg-frame :rf/default {:url-bound? true})
    (rf/reg-route :hist/cart   {} "/cart")
    (rf/reg-route :hist/editor {:params    [:map [:id :string]]
                                :can-leave :hist/can-leave?} "/editor/articles/:id")
    (rf/reg-event :hist/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :hist/can-leave?
                (fn [db _]
                  ;; closed contract: explicit boolean. OK to leave = NOT dirty.
                  (not (boolean (get-in db [:editor :dirty?])))))

    ;; A → B: land on /cart, then push the guarded editor route.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/editor/articles/X"}])
    (is (= ["/" "/cart" "/editor/articles/X"] (:entries @*history-state*))
        "two forward pushes stacked the history entries")
    (is (= :hist/editor
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the slice is on the editor route before Back")

    ;; Mark the editor route dirty so its :can-leave guard returns false.
    (rf/dispatch-sync [:hist/dirty true])

    ;; Browser Back: the address bar moves to /cart (no NEW entry; only
    ;; the index moves), then the app dispatches the popstate-style
    ;; handle-url-change for the URL the browser landed on.
    (.back (.-history js/globalThis.window))
    (is (= "/cart" (current-url *history-state*))
        "back() moved the address bar to /cart")
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url *history-state*)])

    ;; The guard blocked: pending-nav is set, slice unchanged.
    (let [pending (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :pending-navigation])]
      (is (some? pending)
          ":rf/pending-navigation is populated on the blocked popstate")
      (is (= "/cart" (:requested-url pending))
          "the rejected (Back) URL is captured for resume"))
    (is (= :hist/editor
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the :rf/route slice STAYS on the editor route (the block did not commit /cart)")

    ;; THE FIX: the browser address bar was restored to the slice's URL
    ;; via replaceState — URL and slice agree again. The entry count is
    ;; unchanged (a replace, not a push).
    (is (= "/editor/articles/X" (current-url *history-state*))
        "the address bar was restored to the editor route's URL (replaceState)")
    (is (= 3 (count (:entries @*history-state*)))
        "the restore was a replace, not a push — no new history entry")

    ;; CANCEL leaves nothing else changed: slot clears, slice + URL stay.
    (let [pn-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/routing :pending-navigation :id])]
      (rf/dispatch-sync [:rf.route/cancel pn-id]))
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :pending-navigation]))
        "cancel clears the pending slot")
    (is (= :hist/editor
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "cancel leaves the slice on the editor route")
    (is (= "/editor/articles/X" (current-url *history-state*))
        "cancel leaves the restored URL in place")))

(deftest forward-nav-block-does-not-restore-url-cljs
  (testing "rf2-ede1h.3: a FORWARD-nav block emits NO :rf.nav/replace-url —
            the URL never moved, so there is nothing to restore"
    ;; EP-0002 (rf2-9o48ih): URL ownership is explicit — opt `:rf/default` in
    ;; as the URL owner (the assertion that NO replace-url fires is only
    ;; meaningful when the frame COULD own the URL).
    (rf/reg-frame :rf/default {:url-bound? true})
    (rf/reg-route :hist/cart   {} "/cart")
    (rf/reg-route :hist/editor {:params    [:map [:id :string]]
                                :can-leave :hist/can-leave?} "/editor/articles/:id")
    (rf/reg-event :hist/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :hist/can-leave?
                (fn [db _] (not (boolean (get-in db [:editor :dirty?])))))

    (rf/dispatch-sync [:rf.route/url-requested {:url "/editor/articles/X"}])
    (rf/dispatch-sync [:hist/dirty true])

    (let [entries-before (:entries @*history-state*)]
      ;; Forward nav attempt (link click / programmatic) — the browser URL
      ;; has NOT moved; the block declines to push.
      (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
      (is (some? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                         [:rf.runtime/routing :pending-navigation]))
          "the forward nav was blocked (pending slot set)")
      (is (= entries-before (:entries @*history-state*))
          "no push AND no replace — the history stack is byte-identical")
      (is (= "/editor/articles/X" (current-url *history-state*))
          "the address bar still shows the editor route (it never moved)"))))

;; ---- rf2-8zvajk: CONTINUE after a blocked popstate re-moves the URL ------
;;
;; The block above restored the address bar to the rejecting route's URL via
;; replaceState. On `:rf.route/continue` the resume re-dispatches the
;; original `[:rf.route/handle-url-change requested-url {:bypass-guards?
;; #{:leave}}]`, which rewrites the slice but emits NO history mutation (it
;; assumes the browser already moved). After the restore that assumption is
;; false — so without a fix the slice commits to /cart while the address bar
;; stays on /editor/articles/X, leaving the visible route and the browser
;; URL / history entry divergent (refresh, copy-URL, and subsequent
;; Back/Forward all operate on the stale URL). The fix: a blocked popstate
;; that restored the URL records `:url-restored?`, and continue replaces the
;; address bar with `:requested-url` (replaceState — preserving the popstate
;; entry's place, history length unchanged).

(deftest blocked-popstate-continue-restores-url-cljs
  (testing "rf2-8zvajk: :rf.route/continue after a blocked popstate moves the
            address bar to the requested URL — slice and URL agree, no new
            history entry"
    ;; EP-0002 (rf2-9o48ih): URL ownership is explicit — opt `:rf/default` in
    ;; as the URL owner so the continue `:rf.nav/replace-url` fx fires.
    (rf/reg-frame :rf/default {:url-bound? true})
    (rf/reg-route :hist/cart   {} "/cart")
    (rf/reg-route :hist/editor {:params    [:map [:id :string]]
                                :can-leave :hist/can-leave?} "/editor/articles/:id")
    (rf/reg-event :hist/dirty (fn [{:keys [db]} [_ v]] {:db (assoc-in db [:editor :dirty?] v)}))
    (rf/reg-sub :hist/can-leave?
                (fn [db _] (not (boolean (get-in db [:editor :dirty?])))))

    ;; A → B: land on /cart, then push the guarded editor route.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf.route/url-requested {:url "/editor/articles/X"}])
    (rf/dispatch-sync [:hist/dirty true])

    ;; Browser Back to /cart, then dispatch the popstate-style change. The
    ;; guard blocks; the runtime restores the address bar to the editor URL.
    (.back (.-history js/globalThis.window))
    (rf/dispatch-sync [:rf.route/handle-url-change (current-url *history-state*)])
    (is (= "/editor/articles/X" (current-url *history-state*))
        "the block restored the address bar to the editor route")
    (let [pending (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :pending-navigation])]
      (is (some? pending) "pending-nav populated on the blocked popstate")
      (is (true? (:url-restored? pending))
          "the pending-nav records that a URL restore was performed")

      ;; The history stack is at 3 entries before continue resolves.
      (let [entries-before (count (:entries @*history-state*))
            pn-id          (:id pending)]
        ;; CONTINUE: resume the rejected Back navigation.
        (rf/dispatch-sync [:rf.route/continue pn-id])

        (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                          [:rf.runtime/routing :pending-navigation]))
            "continue clears the pending-nav slot")
        (is (= :hist/cart
               (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                            [:rf.runtime/routing :current])))
            "continue committed the slice to the requested /cart route")
        (is (= "/cart" (current-url *history-state*))
            "continue moved the address bar to /cart — slice and URL agree")
        (is (= entries-before (count (:entries @*history-state*)))
            "the URL move was a replace, not a push — history length unchanged")))))

;; =========================================================================
;; 3. hashchange — fragment-only round-trip
;; =========================================================================
;;
;; Per Spec 012 §Fragments and routing.cljc's `:rf.route/transitioned`
;; handler — when only the URL fragment changes (the route-id,
;; :params, and :query are unchanged) the runtime updates
;; [:rf.runtime/routing :current :fragment] and emits :rf.route/fragment-changed (rf2-cj9fn,
;; pre-rename: `:rf.route/url-changed`) instead of re-firing :on-match.
;; That's the framework's hashchange surface.

(deftest hashchange-fragment-only-cljs
  (testing "URL fragment change → :rf.route/fragment-changed trace fires; no new nav-token allocation"
    (register-routes!)
    ;; Forward nav lands on /articles/intro.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/articles/intro"}])
    ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db state.
    (let [pre-nav-token (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
                            :rf.runtime/routing :current :nav-token)]

      ;; Capture both :rf.route/fragment-changed AND
      ;; :rf.route.nav-token/allocated emissions during the fragment-only
      ;; dispatch — assert the former fires and the latter does NOT.
      (let [fragment-changed (atom [])
            allocations      (atom [])
            cb-key           (keyword (gensym "hashchange-"))]
        (trace-tooling/register-listener!
          cb-key
          (fn [ev]
            (case (:operation ev)
              :rf.route/fragment-changed
              (swap! fragment-changed conj (:tags ev))
              :rf.route.nav-token/allocated
              (swap! allocations conj (:tags ev))
              nil)))
        (try
          (rf/dispatch-sync [:rf.route/transitioned "/articles/intro#section-2"])
          (finally
            (trace-tooling/unregister-listener! cb-key)))

        (is (= 1 (count @fragment-changed))
            "fragment-only nav emits :rf.route/fragment-changed exactly once")
        (is (= "section-2"
               (:next-fragment (first @fragment-changed)))
            "trace carries :next-fragment")
        ;; rf2-n0851k: the fragment-only trace carries the frame stamp
        ;; under :tags :frame so epoch/Xray capture and the frame
        ;; trace-disable gate cover fragment-only changes (Spec 012
        ;; §Multi-frame routing / Spec 009).
        (is (= :rf/default (:frame (first @fragment-changed)))
            "rf2-n0851k: fragment-only trace is frame-attributed")
        (is (zero? (count @allocations))
            "fragment-only nav does NOT allocate a new nav-token")

        (let [route (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])]
          (is (= "section-2" (:fragment route))
              "[:rf.runtime/routing :current :fragment] is updated to the new fragment")
          (is (= :hist/article (:route-id route))
              "route-id is unchanged across the fragment-only nav")
          (is (= pre-nav-token (:nav-token route))
              "nav-token survives the fragment-only update (no new allocation)"))))))

(deftest hashchange-via-window-listener-cljs
  (testing "a hashchange listener registered via window.addEventListener fires on dispatchEvent"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])

    (let [fired? (atom 0)
          listener (fn [_event] (swap! fired? inc))]
      (.addEventListener js/globalThis.window "hashchange" listener)
      (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
      (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
      (is (= 2 @fired?)
          "the hashchange listener fired twice via dispatchEvent")
      (.removeEventListener js/globalThis.window "hashchange" listener)
      (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
      (is (= 2 @fired?)
          "removeEventListener stopped further deliveries"))))

;; ---- malformed-% fail-closed (CLJS decode path) --------------------------
;;
;; Per Spec 012 §Routing failure semantics §Malformed percent-encoding
;; (rf2-wbvme + rf2-4ic0f). The JVM suite pins the fail-closed contract
;; against `URLDecoder/decode` (routing_test.clj:781-808). The CLJS
;; runtime decodes via `js/decodeURIComponent`, which throws on a
;; DIFFERENT set of malformed inputs than the JVM decoder — so the
;; security-critical fail-closed path (hostile / broken URLs → route-miss,
;; never a runtime crash) needs a smoke on the runtime that actually
;; ships to browsers. `safe-url-decode` must swallow `js/decodeURIComponent`'s
;; throw and `match-url` must return nil, exactly as on the JVM.
(deftest match-url-malformed-percent-fails-closed-cljs
  (testing "rf2-4ic0f: malformed %-encoding fails closed on the CLJS
            decodeURIComponent path — match-url returns nil, never throws"
    (register-routes!)
    (rf/reg-route :hist/search {} "/search")
    ;; Path segment — bare `%`, incomplete pair, non-hex pair.
    (is (nil? (routing/match-url "/articles/%"))
        "bare `%` in path → route-miss (no decodeURIComponent throw escapes)")
    (is (nil? (routing/match-url "/articles/x%a"))
        "incomplete %-pair in path → route-miss")
    (is (nil? (routing/match-url "/articles/x%XX"))
        "non-hex %-pair in path → route-miss")
    ;; Query value + key — whole URL fails closed (no partial slice).
    (is (nil? (routing/match-url "/search?x=%"))
        "malformed query VALUE → whole URL is a route-miss")
    (is (nil? (routing/match-url "/search?%=v"))
        "malformed query KEY → whole URL is a route-miss")
    ;; Fragment.
    (is (nil? (routing/match-url "/search#%"))
        "malformed `#fragment` → route-miss")
    ;; No registered route: even a bare `%` URL must not throw.
    (is (nil? (routing/match-url "/%"))
        "bare `%` with no matching route → route-miss, not an exception"))
  (testing "well-formed %-encoding still decodes on the CLJS path"
    (register-routes!)
    (let [m (routing/match-url "/articles/hello%20world")]
      (is (some? m) "well-formed %-encoded path segment matches")
      (is (= "hello world" (get-in m [:params :id]))
          "decodeURIComponent decodes the well-formed segment into the slice"))))

;; rf2-oyw04: :int query coercion must be STRICT and IDENTICAL to the JVM.
;; The predecessor used `js/parseInt v 10`, which is lenient: `parseInt
;; "12abc" 10` -> 12, so `?page=12abc` produced the NUMBER 12 client-side
;; while the JVM `Long/parseLong` threw and passed the STRING "12abc"
;; through — a Spec 011 hydration-mismatch hazard violating Spec 012's
;; "same handler both sides" + the Spec 000 Goal 2 cross-host bar. The fix
;; coerces only when the whole string is an integer literal (`^-?\d+$`),
;; else string passthrough — so this CLJS pin asserts the EXACT outputs the
;; JVM `query-coercion-vocabulary` test (routing_test.clj T2) now expects.
;; The corpus fixture routing-query-string-coercion.edn runs the same
;; `?page=12abc` call through both harnesses for the formal cross-host bar.
(deftest int-query-coercion-strict-cljs
  (testing "rf2-oyw04: :int coerces only whole integer literals on CLJS;
            lenient `js/parseInt` partial-numeric coercion is closed so the
            client agrees with the JVM"
    (register-routes!)
    (rf/reg-route :hist/list {:query [:map [:page :int]]} "/list")
    (is (= 12 (get-in (routing/match-url "/list?page=12") [:query :page]))
        "clean integer literal coerces to a number")
    (is (= -7 (get-in (routing/match-url "/list?page=-7") [:query :page]))
        "signed integer literal coerces")
    (is (= "12abc" (get-in (routing/match-url "/list?page=12abc") [:query :page]))
        "partial-numeric input stays a STRING (was 12 under js/parseInt) —
         the cross-host asymmetry rf2-oyw04 closes")
    (is (= "0x10" (get-in (routing/match-url "/list?page=0x10") [:query :page]))
        "radix-prefixed input stays a string, matching the JVM")
    (is (= " 12" (get-in (routing/match-url "/list?page=%2012") [:query :page]))
        "leading-whitespace input stays a string, matching the JVM")
    (is (= "abc" (get-in (routing/match-url "/list?page=abc") [:query :page]))
        "fully non-numeric input stays a string (already symmetric)")))

;; rf2-cylse.1: :int coercion must be HOST-SYMMETRIC and TOTAL on OVERSIZED
;; integer literals. The predecessor `js/parseInt` produced a LOSSY DOUBLE
;; for a literal above 2^53 (e.g. 9007199254740993 -> ...92) while the JVM
;; `Long/parseLong` stayed EXACT — the same URL yielded a DIFFERENT :query
;; slice server vs client (a Spec 011 hydration mismatch), and a >2^63
;; literal threw NumberFormatException on the JVM (route-miss) while CLJS
;; committed a lossy float (page render) — a divergent OUTCOME. The fix
;; bounds the literal at the cross-host safe-integer ceiling (2^53-1) and
;; passes through AS A STRING above it on BOTH hosts. This CLJS pin asserts
;; the EXACT outputs the JVM `int-coercion-oversized-host-parity-jvm` test
;; expects.
(deftest int-coercion-oversized-host-parity-cljs
  (testing "rf2-cylse.1: oversized :int literals pass through as STRINGS on
            CLJS (was a lossy double under js/parseInt), matching the JVM"
    (register-routes!)
    (rf/reg-route :hist/items {:query [:map [:page :int]]} "/items")
    (testing "within the safe-integer range still coerces"
      (is (= 42 (get-in (routing/match-url "/items?page=42") [:query :page])))
      (is (= 9007199254740991
             (get-in (routing/match-url "/items?page=9007199254740991") [:query :page]))
          "2^53-1 (MAX_SAFE_INTEGER) coerces — inclusive ceiling, exact on both hosts"))
    (testing "above the ceiling passes through as a string (both hosts agree)"
      (is (= "9007199254740992"
             (get-in (routing/match-url "/items?page=9007199254740992") [:query :page]))
          "2^53 exceeds MAX_SAFE_INTEGER → string (js/parseInt would round)")
      (is (= "9007199254740993"
             (get-in (routing/match-url "/items?page=9007199254740993") [:query :page]))
          "the canonical lossy-double case → string on CLJS too (was ...92)")
      (is (= "-9007199254740993"
             (get-in (routing/match-url "/items?page=-9007199254740993") [:query :page]))
          "negative oversized literal also passes through"))
    (testing "a literal beyond 2^63 does NOT coerce (parse-long is total, returns nil)"
      (is (= "99999999999999999999999"
             (get-in (routing/match-url "/items?page=99999999999999999999999") [:query :page]))
          "string passthrough, matching the JVM (no throw / no lossy float)"))))

;; rf2-cylse.5: PATH params coerce against the :params schema on CLJS too —
;; the canonical Spec 012 :uuid route must round-trip a real UUID URL to
;; {:id #uuid ...} on the browser, identically to the JVM (SSR) side.
(deftest path-param-coercion-cljs
  (testing "rf2-cylse.5: :int / :uuid PATH params coerce against the
            :params schema before validation on CLJS"
    (register-routes!)
    (rf/reg-route :hist/page    {:params [:map [:n :int]]} "/page/:n")
    (rf/reg-route :hist/article {:params [:map [:id :uuid]]} "/articles/:id")
    (is (= 42 (get-in (routing/match-url "/page/42") [:params :n]))
        ":int path param coerced to a number")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          m        (routing/match-url (str "/articles/" uuid-str))]
      (is (= (parse-uuid uuid-str) (get-in m [:params :id]))
          ":uuid path param coerced to a #uuid object")
      (is (uuid? (get-in m [:params :id])) "the slice carries a UUID object, not a string"))))

;; rf2-fwz29i: OPTIONED Malli scalar schemas (`[:int {:min 1}]`,
;; `[:uuid {}]`, `[:boolean {}]`, optioned enums, and
;; `[:maybe inner]`) must coerce the URL string identically to the bare
;; form on CLJS, exactly as on the JVM. The pre-fix coercion table held the
;; raw vector type-form, so the still-string value failed the optioned
;; schema and every valid deep link 404'd. This is the CLJS half of the
;; JVM `rf2-fwz29i-*` pins in routing_test.clj.
(deftest optioned-scalar-coercion-cljs-rf2-fwz29i
  (testing "optioned :query scalars coerce equivalently to bare forms on CLJS"
    (register-routes!)
    (rf/reg-route :hist/items
                  {:query [:map
                           [:page [:int {:min 1}]]
                           [:id [:uuid {}]]
                           [:archived [:boolean {}]]]} "/items")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          m (routing/match-url
              (str "/items?page=2&id=" uuid-str "&archived=true"))]
      (is (= 2 (get-in m [:query :page]))
          "[:int {:min 1}] coerces \"2\" to 2 (was string → 404)")
      (is (= (parse-uuid uuid-str) (get-in m [:query :id]))
          "[:uuid {...}] coerces to a UUID object")
      (is (true? (get-in m [:query :archived])) "[:boolean {...}] coerces")
      (is (false? (:validation-failed? m))
          "coerced typed values conform to their optioned schemas — no 404")))

  (testing "optioned :params (path) scalars coerce equivalently on CLJS"
    (rf/reg-route :hist/opt-page    {:params [:map [:n [:int {:min 1}]]]} "/op/:n")
    (rf/reg-route :hist/opt-article {:params [:map [:id [:uuid {}]]]} "/oa/:id")
    (is (= 2 (get-in (routing/match-url "/op/2") [:params :n]))
        "[:int {:min 1}] path param coerces to 2")
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          m        (routing/match-url (str "/oa/" uuid-str))]
      (is (= (parse-uuid uuid-str) (get-in m [:params :id]))
          "[:uuid {}] path param coerces to a UUID object")
      (is (false? (:validation-failed? m)))))

  (testing "optioned `[:enum {...} :a :b]` keeps the keyword allowlist gate"
    (rf/reg-route :hist/sorted
                  {:query [:map [:sort [:enum {:default :asc} :asc :desc]]]} "/sorted")
    (is (= :asc (get-in (routing/match-url "/sorted?sort=asc") [:query :sort]))
        "declared enum value interns even with an opts map")
    (is (= "nope" (get-in (routing/match-url "/sorted?sort=nope") [:query :sort]))
        "value outside the allowlist stays a string"))

  (testing "[:maybe inner] coerces the present value against the inner type"
    (rf/reg-route :hist/maybe
                  {:query [:map [:page [:maybe [:int {:min 1}]]]]} "/maybe")
    (let [m (routing/match-url "/maybe?page=7")]
      (is (= 7 (get-in m [:query :page]))
          "[:maybe [:int {:min 1}]] coerces through wrapper + option")
      (is (false? (:validation-failed? m))))))

;; rf2-zmcq6 (CODE half): {:fragment ""} normalizes to nil at the navigate
;; boundary on CLJS so the pushed URL and slice fragment agree with
;; URL-driven nav.
(deftest navigate-empty-string-fragment-normalized-cljs
  (testing "rf2-zmcq6: navigate {:fragment \"\"} writes :fragment nil and
            pushes a fragment-less URL on CLJS"
    (register-routes!)
    (rf/reg-route :hist/docs {} "/docs/:page")
    (rf/dispatch-sync [:rf.route/navigate :hist/docs {:page "guide"} {:fragment ""}])
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/routing :current :fragment]))
        "empty-string fragment normalized to nil in the slice")
    (is (= "/docs/guide" (current-url *history-state*))
        "the pushed URL has no trailing # for an empty-string fragment")))

;; =========================================================================
;; 4. replaceState — no new history entry
;; =========================================================================

(deftest replacestate-no-new-entry-cljs
  (testing ":rf.route/navigate with :replace? true → replaceState mutates the top entry; stack length unchanged"
    (register-routes!)

    ;; Land on /cart via a normal push so the stack is at length 2.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (is (= ["/" "/cart"] (:entries @*history-state*))
        "stack is at length 2 before the replace")
    (let [pre-index (:index @*history-state*)]

      ;; Programmatic navigation with :replace? true → :rf.nav/replace-url.
      (rf/dispatch-sync [:rf.route/navigate :hist/checkout nil {:replace? true}])

      (is (= ["/" "/checkout"] (:entries @*history-state*))
          "replaceState rewrote the top entry from /cart to /checkout")
      (is (= pre-index (:index @*history-state*))
          "the history index did NOT advance (no new entry was created)")
      (is (= 2 (count (:entries @*history-state*)))
          "stack length is unchanged across a replaceState call")

      ;; The hallmark of replaceState: popstate skips the replaced URL.
      ;; back() from index 1 should land on the original / entry, NOT
      ;; the /cart URL that was replaced.
      (.back (.-history js/globalThis.window))
      (is (= "/" (current-url *history-state*))
          "back() after replaceState lands on the entry BEFORE the replaced one"))))

;; =========================================================================
;; 5. Cross-state cleanup — A → B → pop → C → pop → pop
;; =========================================================================
;;
;; Real-browser semantics: pushing a new entry after a `pop` truncates
;; the forward history. The stub mirrors this. The slice cascade must
;; track the active URL across every step.

(deftest cross-state-cleanup-cljs
  (testing "push A → push B → pop → push C → pop → pop yields the correct route cascade"
    (register-routes!)
    (let [route-id (fn []
                     (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
          pop-and-dispatch!
          (fn []
            (.back (.-history js/globalThis.window))
            (rf/dispatch-sync
              [:rf.route/handle-url-change (current-url *history-state*)]))]

      ;; push A (/cart)
      (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
      (is (= :hist/cart (route-id)) "after push A → :hist/cart")
      (is (= ["/" "/cart"] (:entries @*history-state*)))

      ;; push B (/checkout)
      (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
      (is (= :hist/checkout (route-id)) "after push B → :hist/checkout")
      (is (= ["/" "/cart" "/checkout"] (:entries @*history-state*)))

      ;; pop → back to /cart
      (pop-and-dispatch!)
      (is (= :hist/cart (route-id)) "after pop → :hist/cart")
      (is (= 1 (:index @*history-state*))
          "the forward entry survives the pop (only index moved)")

      ;; push C (/articles/intro) → forward entry truncated, new entry appended.
      (rf/dispatch-sync [:rf.route/url-requested {:url "/articles/intro"}])
      (is (= :hist/article (route-id)) "after push C → :hist/article")
      (is (= ["/" "/cart" "/articles/intro"] (:entries @*history-state*))
          "pushing after a pop truncates the forward stack (browser semantics)")

      ;; pop → back to /cart
      (pop-and-dispatch!)
      (is (= :hist/cart (route-id)) "after second pop → :hist/cart")

      ;; pop → back to /
      (pop-and-dispatch!)
      (is (= :hist/home (route-id)) "after third pop → :hist/home")
      (is (= 0 (:index @*history-state*))
          "history.index is at the root entry"))))

;; =========================================================================
;; 6. History-mutation defence-in-depth: a throwing pushState / replaceState
;;    fails closed to a structured trace (rf2-u8qe7y finding 2)
;; =========================================================================
;;
;; The `url/external-url?` gate at the nav-event sinks (rf2-cylse.4)
;; already fails cross-origin URLs closed before they reach the
;; `:rf.nav/push-url` / `:rf.nav/replace-url` fxs, but the fx still wraps
;; the actual browser history mutation in a shared try/catch
;; (`run-history-mutation!`) as a second line of defence. If the browser
;; throws (residual unsafe URL, invalid-URL restriction, jsdom/stub
;; mismatch) the fx must NOT escape the exception — it downgrades to a
;; `:rf.fx/<fx-id>-failed` trace and the drain survives. Both sibling
;; history fxs share the wrapper, so both behave identically under the
;; same failure class.

(defn- with-throwing-history-method!
  "Temporarily swap the stub history `method` (\"pushState\" /
  \"replaceState\") for one that throws `message`, run `thunk`, then
  restore. Returns the thunk's result."
  [method message thunk]
  (let [history  (.-history js/globalThis.window)
        original (aget history method)]
    (aset history method
          (fn [& _]
            (throw (js/Error. message))))
    (try
      (thunk)
      (finally
        (aset history method original)))))

(defn- with-fx-failure-traces
  "Run `thunk` while collecting `:rf.fx/push-url-failed` /
  `:rf.fx/replace-url-failed` trace payloads. Returns
  `[result vector-of-tags]`."
  [thunk]
  (let [captured (atom [])
        cb-key   (keyword (gensym "fx-failure-"))]
    (trace-tooling/register-listener!
      cb-key
      (fn [ev]
        (when (#{:rf.fx/push-url-failed :rf.fx/replace-url-failed}
                (:operation ev))
          (swap! captured conj (assoc (:tags ev) :operation (:operation ev))))))
    (try
      [(thunk) @captured]
      (finally
        (trace-tooling/unregister-listener! cb-key)))))

(deftest replace-url-throwing-replacestate-fails-closed-cljs
  (testing ":rf.nav/replace-url downgrades a throwing replaceState to a :rf.fx/replace-url-failed trace; no exception escapes the drain"
    (register-routes!)
    ;; Land on /cart via a normal push so a :replace? navigation routes
    ;; through :rf.nav/replace-url.
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (let [[_ failures]
          (with-fx-failure-traces
            (fn []
              (with-throwing-history-method!
                "replaceState" "boom-replace"
                (fn []
                  ;; The drain must NOT throw — fail-closed via the
                  ;; shared try/catch. `is` with no thrown exception is the
                  ;; assertion; a leaked throw would fail the deftest.
                  (rf/dispatch-sync
                    [:rf.route/navigate :hist/checkout nil {:replace? true}])
                  (is true
                      ":rf.route/navigate dispatch returned without an escaping exception")))))]
      (is (= 1 (count failures))
          "a single :rf.fx/replace-url-failed trace fired for the throwing replaceState")
      (let [tags (first failures)]
        (is (= :rf.fx/replace-url-failed (:operation tags))
            "the failure trace operation is :rf.fx/replace-url-failed")
        (is (= :rf.nav/replace-url (:rf.fx/id tags))
            "the trace carries :rf.fx/id :rf.nav/replace-url")
        (is (= "/checkout" (:url tags))
            "the trace carries the attempted :url")
        (is (= "boom-replace" (:error tags))
            "the trace carries the browser error message")))
    ;; The route slice still committed — only the browser-URL sync failed.
    (is (= :hist/checkout
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the route slice committed even though replaceState threw")))

(deftest push-url-throwing-pushstate-fails-closed-cljs
  (testing ":rf.nav/push-url downgrades a throwing pushState to a :rf.fx/push-url-failed trace; no exception escapes the drain (push/replace parity)"
    (register-routes!)
    (let [[_ failures]
          (with-fx-failure-traces
            (fn []
              (with-throwing-history-method!
                "pushState" "boom-push"
                (fn []
                  (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
                  (is true
                      ":rf.route/url-requested dispatch returned without an escaping exception")))))]
      (is (= 1 (count failures))
          "a single :rf.fx/push-url-failed trace fired for the throwing pushState")
      (let [tags (first failures)]
        (is (= :rf.fx/push-url-failed (:operation tags))
            "the failure trace operation is :rf.fx/push-url-failed")
        (is (= :rf.nav/push-url (:rf.fx/id tags))
            "the trace carries :rf.fx/id :rf.nav/push-url")
        (is (= "/cart" (:url tags))
            "the trace carries the attempted :url")
        (is (= "boom-push" (:error tags))
            "the trace carries the browser error message")))))
