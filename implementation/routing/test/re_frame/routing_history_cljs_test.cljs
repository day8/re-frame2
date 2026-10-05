(ns re-frame.routing-history-cljs-test
  "CLJS tests for the browser-history surface of routing.
  Locks the popstate / hashchange / pushState /
  replaceState round-trip on the node-runtime test target.

  re-frame2's history-integration contract (Spec 012 §URL changes
  are events) is split across two functions:

  - `:rf.nav/push-url`     fx — calls `(.pushState js/window.history nil \"\" url)`.
  - `:rf.nav/replace-url`  fx — calls `(.replaceState js/window.history nil \"\" url)`.
  - `:rf.route/handle-url-change` event — the one URL-driven door: a link
                                  click (cause `:link`), popstate, initial, SSR.

  The runtime wires `window.addEventListener('popstate', ...)` itself,
  automatically, as part of the `:url-bound?` frame LIFECYCLE:
  a `:url-bound? true` frame's creation (or re-registration, when it
  resolves as the URL owner) installs the listener; its destroy removes it.
  Some tests below drive the browser→app leg by hand-dispatching
  `:rf.route/handle-url-change` (the shape a hand-rolled/legacy listener, or
  SSR, uses) to pin the event's own contract independent of the automatic
  wiring; the dedicated lifecycle tests near the end of this file pin the
  automatic install/remove contract itself. The tests below exercise both
  halves: the OUTBOUND fx (pushState / replaceState actually touch the
  history object) AND the INBOUND event (popstate-style dispatch updates
  the slice + fires :on-match + re-emits the nav-token-allocated trace).

  Mock approach — Node has no `window`/`document` globals, so this
  file installs a minimal jsdom-style stub on `js/globalThis` via the
  shared `with-window-stub-fixture` (set up before `routing.cljc`'s fx run;
  torn down after). The stub records `pushState` / `replaceState` calls onto an
  in-memory entry stack and exposes `back` / `forward` / `go` so the
  popstate path can be driven without a real DOM. The fixture is
  scoped to this test ns; production code is untouched.

  Per Spec 012 §URL changes are events, §Navigation tokens, §Scroll
  restoration."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; The first-registration/re-registration atomicity
            ;; tests read the frames registry record + the trace-policy
            ;; predicate directly to prove zero residue.
            [re-frame.frame :as rf.frame]
            [re-frame.trace :as rf.trace]
            ;; The listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.routing :as rf.routing]
            ;; Internal URL-classifier namespace — `external-url?`
            ;; / `request-url->app-url` are not facade-exported, so the
            ;; non-string fail-closed test calls them directly.
            [re-frame.routing.url :as rf.routing.url]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; The browser history/location/document stub,
            ;; `*history-state*`, `current-url`, and `with-window-stub-fixture`
            ;; are the SUPERSET fixture shared with routing_url_strategy_cljs_test.
            [re-frame.routing-browser-test-support
             :refer [*history-state* current-url with-window-stub-fixture]]))

;; ---- window / history stub -----------------------------------------------
;;
;; The jsdom-style history/location/document stub, `*history-state*`,
;; `current-url`, and `with-window-stub-fixture` live in the shared
;; re-frame.routing-browser-test-support ns — the SUPERSET fixture
;; this suite and routing_url_strategy_cljs_test both drive. This suite reads
;; `*history-state*` / `current-url` directly and composes
;; `with-window-stub-fixture` FIRST in `use-fixtures` below.

(use-fixtures :each
  with-window-stub-fixture
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     ;; The scroll-position cache is a module-level host atom
     ;; (not runtime-db), so the runtime reset does not touch it — drop it
     ;; explicitly so a captured position never leaks across tests.
     :init-fn (fn []
                (rf.routing/reset-counters!)
                (rf.routing/reset-scroll-cache!))}))

;; ---- trace-capture helper ------------------------------------------------

(defn- with-route-traces
  "Run thunk while collecting :rf.route.nav-token/allocated events.
   Returns [result-of-thunk vector-of-trace-payloads]."
  [thunk]
  (let [captured (atom [])
        cb-key   (keyword (gensym "route-trace-"))]
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (= :rf.route.nav-token/allocated (:operation ev))
          (swap! captured conj (:tags ev)))))
    (try
      (let [r (thunk)]
        [r @captured])
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

;; ---- routes used across the suite ---------------------------------------

(defn- register-routes! []
  ;; EP-0002: URL ownership is an EXPLICIT declaration —
  ;; the runtime does not infer `:rf/default` as the URL owner from
  ;; absence (`url-owner-frame-id` returns nil unless a frame declares
  ;; `:url-bound? true`). The fixture's `ensure-default-frame!` creates
  ;; `:rf/default` WITHOUT the slot, so opt it in explicitly here as this
  ;; suite's URL owner — otherwise `:rf.nav/push-url` / `:rf.nav/replace-url`
  ;; never fire and the history stack stays at one entry. Tests that drive a
  ;; non-default owner re-register `:rf/default {:url-bound? false}` AFTER
  ;; this call, so their override wins.
  (rf/make-frame {:id :rf/default :url-bound? true})
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

    (let [[_ traces]
          (with-route-traces
            (fn []
              (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])))]
      ;; pushState side-effect: a new entry sits on top of the stack.
      (is (= ["/" "/cart"] (:entries @*history-state*))
          ":rf.nav/push-url appended /cart to the history stack")
      (is (= 1 (:index @*history-state*))
          "the history index advanced to the new top entry")

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

(deftest url-requested-non-string-url-fails-closed-cljs-rf2-w3qgc
  (testing "with a live window/location, a NON-STRING `:url`
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
        (is (true? (rf.routing.url/external-url? bad))
            (str "non-string url " (pr-str bad) " classes EXTERNAL"))
        ;; request-url->app-url must NOT canonicalise a non-string (gate is
        ;; external? → returns the value unchanged, never touching js/URL).
        (is (= bad (rf.routing.url/request-url->app-url bad "/" identity))
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

;; ---- external-url? BROWSER js/URL branch — the open-redirect gate
;;
;; `external-url?` (url.cljc) is fail-closed by TWO impls of one
;; contract: a JVM/no-window LEXICAL path (`safe-in-app-url?`, exhaustively
;; adversarial in routing_navigation_test.clj) and the CLJS LIVE-WINDOW path
;; (js/URL + protocol-allowlist + origin-compare). The
;; browser path is the classifier that actually runs where an open redirect
;; is the live risk. Its `or` has TWO clauses:
;;
;;   (or (not (#{"http:" "https:"} protocol))        ; A — protocol allowlist
;;       (not= (.-origin parsed) (.-origin loc)))     ; B — origin compare
;;
;; The shared window stub (document origin https://app.example, node global
;; `js/URL`) makes THIS branch run in the node-test target, and the
;; non-string guard short-circuits BEFORE js/URL. Without a test of its own,
;; a regression in clause A — the
;; non-http(s)-scheme gate (dropped allowlist, `.-host` vs `.-origin`,
;; inverted compare) — would be a SILENT browser open-redirect.
;; These deftests drive the JVM lexical bypass matrix through the browser
;; classifier so BOTH clauses are exercised + asserted.

(deftest external-url-browser-protocol-allowlist-cljs-rf2-aftbmz
  (testing "the live-window js/URL branch fails closed on
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
        (is (true? (rf.routing.url/external-url? (str "blob:" doc-origin "/1234-uuid")))
            "blob:<same-origin> classes EXTERNAL — origins MATCH here, so the
             protocol-allowlist clause is the sole gate that catches it"))

      (testing "non-http(s) schemes reach the allowlist clause and fail closed"
        (doseq [u ["javascript:alert(1)"
                   "data:text/html,<script>alert(1)</script>"
                   "file:///etc/passwd"]]
          (is (true? (rf.routing.url/external-url? u))
              (str "non-http(s) scheme " (pr-str u)
                   " classes EXTERNAL via the protocol allowlist (clause A)"))))

      (testing "off-origin http(s) authorities fail closed via origin-compare"
        (doseq [u ["//evil.example/x"                ;; protocol-relative → https://evil.example
                   "http://other-host.example/x"     ;; different host + scheme
                   "https://good@evil.example/x"]]    ;; userinfo-confusion → origin evil.example
          (is (true? (rf.routing.url/external-url? u))
              (str "off-origin URL " (pr-str u)
                   " classes EXTERNAL via the origin-compare clause (clause B)"))))

      (testing "a SAME-ORIGIN ABSOLUTE http(s) URL is the one in-app case —
                proving the browser gate is not blanket-true"
        (is (false? (rf.routing.url/external-url? (str doc-origin "/cart?q=1#frag")))
            "same-origin absolute URL passes BOTH clauses → in-app (false)")))))

(deftest request-url->app-url-canonicalizes-same-origin-absolute-cljs-rf2-aftbmz
  (testing "request-url->app-url canonicalizes a SAME-ORIGIN
            ABSOLUTE URL to its origin-relative pathname+search+hash via the
            live-window js/URL leg — the canonicalization leg a nav test
            passing already-relative URLs never reaches"
    (register-routes!)
    (let [doc-origin (.-origin (.-location js/globalThis.window))]
      (is (= "/cart?q=1#frag"
             (rf.routing.url/request-url->app-url (str doc-origin "/cart?q=1#frag") "/" identity))
          "a same-origin ABSOLUTE URL is reduced to pathname+search+hash")
      (is (= "/cart?q=1#frag"
             (rf.routing.url/request-url->app-url "/cart?q=1#frag" "/" identity))
          "an already-relative in-app URL canonicalizes to itself")
      (is (= "https://evil.example/x"
             (rf.routing.url/request-url->app-url "https://evil.example/x" "/" identity))
          "an EXTERNAL URL is passed through unchanged — the external? gate
           short-circuits the canonicalize (canonicalising it could fabricate
           an in-app-looking path)"))))

;; `:rf.route/navigate {:url …}` normalises an
;; ACCEPTED raw reference to the location the browser will actually reach
;; BEFORE matching — the same `request-url->app-url` the link door runs — so the
;; committed route, its params and the pushed history entry describe ONE
;; location. Handing the raw string to `match-url` verbatim would make a
;; same-origin absolute URL, a protocol-relative same-origin URL and a rooted
;; dot-segment path all miss `/articles/:id` and commit not-found while the
;; address bar shows the valid article URL.
(deftest navigate-raw-url-normalizes-before-matching-cljs-rf2-fzbj-12
  (register-routes!)
  (let [doc-origin (.-origin (.-location js/globalThis.window))
        authority  (subs doc-origin (.indexOf doc-origin "//"))
        current    (fn [] (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                  [:rf.runtime/routing :current]))]
    (testing "every accepted spelling of one location resolves to the same
              route, params and history entry"
      (doseq [[case-name raw] [["same-origin absolute"          (str doc-origin "/articles/ok")]
                               ["same-origin protocol-relative" (str authority "/articles/ok")]
                               ["rooted dot-segment"            "/x/../articles/ok"]
                               ["ordinary rooted (control)"     "/articles/ok"]]]
        ;; Land elsewhere first so the rule-3 identical-nav no-op never masks
        ;; the navigation under test.
        (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}])
        (rf/dispatch-sync [:rf.route/navigate {:url raw}])
        (is (= :hist/article (:route-id (current)))
            (str case-name " " (pr-str raw) ": commits the article route"))
        (is (= {:id "ok"} (:params (current)))
            (str case-name " " (pr-str raw) ": params come from the normalised location"))
        (is (= "/articles/ok" (current-url *history-state*))
            (str case-name " " (pr-str raw) ": the history entry is the normalised location"))))
    (testing "query-only and fragment-only references resolve against the
              current document"
      (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}])
      (rf/dispatch-sync [:rf.route/navigate {:url "?q=1"}])
      (is (= :hist/cart (:route-id (current)))
          "query-only: stays on the current document's route")
      (is (= {"q" "1"} (:query (current)))
          "query-only: the query rides the current route")
      (is (= "/cart?q=1" (current-url *history-state*))
          "query-only: the history entry is the current path plus the query")
      (rf/dispatch-sync [:rf.route/navigate {:url "#frag"}])
      (is (= :hist/cart (:route-id (current)))
          "fragment-only: stays on the current document's route")
      (is (= "frag" (:fragment (current)))
          "fragment-only: the fragment lands in the slice")
      (is (= "/cart?q=1#frag" (current-url *history-state*))
          "fragment-only: the history entry keeps the current path and query"))
    (testing "an unmatched normalised reference names the browser's location in
              both the not-found params and the history entry"
      (rf/dispatch-sync [:rf.route/navigate {:url "/x/../no/such/path"}])
      (is (= :rf.route/not-found (:route-id (current))))
      (is (= {:url "/no/such/path"} (:params (current)))
          "the not-found params carry the location the browser reaches")
      (is (= "/no/such/path" (current-url *history-state*))
          "the pushed entry is that same location"))))

(deftest scroll-position-captured-before-forward-nav-cljs
  (testing "leaving a route captures the current browser scroll position under that route's URL"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])
    (.scrollTo js/globalThis.window 12 345)
    (rf/dispatch-sync [:rf.route/url-requested {:url "/checkout"}])
    ;; Scroll-position caches are a HOST-SIDE TRANSIENT cache
    ;; (not runtime-db) — read the frame's host cache, not the runtime-db.
    (is (= [12 345]
           (rf.routing/lookup-scroll-position
             (rf.routing/frame-scroll-cache :rf/default)
             "/cart"))
        "scroll position for the route being left is saved before the scroll strategy runs")
    (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                      [:rf.runtime/routing :scroll-positions]))
        "the position is NOT written to runtime-db — it stays off the egress wire")))

(deftest duplicate-sorting-before-incumbent-does-not-steal-url-cljs
  (testing "a duplicate :url-bound? true frame whose id sorts
            BEFORE the incumbent (:aaa-early < :rf/default) does NOT steal the
            browser URL — the incumbent still drives pushState, the duplicate's
            push no-ops. A `(sort-by (str id))` resolver would get this case
            wrong, making :aaa-early the owner."
    (register-routes!)               ;; :rf/default claims the URL first
    (rf/make-frame {:id :aaa-early :url-bound? true})   ;; sorts before :rf/default
    ;; The earlier-sorting duplicate navigates — an alphabetical resolver would
    ;; make it the owner and push. It must NOT touch browser history.
    (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}] {:frame :aaa-early})
    (is (= ["/"] (:entries @*history-state*))
        "the earlier-sorting duplicate did NOT steal the URL / push to history")
    (is (= :hist/cart
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :aaa-early)) [:rf.runtime/routing :current])))
        "the duplicate still updates its OWN route slice (binding reported, not rejected)")
    ;; The incumbent :rf/default still drives the browser URL.
    (rf/dispatch-sync [:rf.route/navigate {:to :hist/checkout}] {:frame :rf/default})
    (is (= ["/" "/checkout"] (:entries @*history-state*))
        "the incumbent :rf/default still owns + pushes the URL")
    (is (= :hist/checkout
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current])))
        "the incumbent's slice tracks the legitimate navigation")))

;; =========================================================================
;; 2. popstate (back-button) round-trip
;; =========================================================================

;; ---- popstate drives the URL-OWNER frame ----------------------------------
;;
;; A non-default frame can own the URL (`:rf/default` opts out, the
;; non-default frame opts in). The PUSH side routes through that owner
;; (`:rf.nav/push-url` gate), and the POP side must too: a popstate listener
;; dispatching `:rf.route/handle-url-change` with no `:frame` would hit
;; `:rf/default` instead of the owner, so Back/Forward would leave the owner's
;; route — and the rendered body — unchanged.
;;
;; A `:url-bound? true` frame's REGISTRATION automatically
;; (re)installs the listener when it resolves as `url-owner-frame-id` — no
;; imperative install call. The installed listener resolves the owner
;; at POP TIME, so the test asserts the owner frame's slice round-trips on
;; back AND forward while `:rf/default` stays put.

;; The installed popstate handler dispatches synchronously
;; (`dispatch-sync!`) — a real `popstate` fires on the browser macrotask
;; loop, never nested in a drain — so the route slice settles within the
;; same turn as `dispatchEvent` and the assertions can read it directly.

(deftest popstate-drives-url-owner-non-default-frame-cljs
  (testing "the :url-bound? lifecycle automatically
            drives the non-default URL-owner frame on Back/Forward"
    (register-routes!)
    ;; Single-non-default-owner setup (the step-deck shape): default opts
    ;; OUT, a non-default frame opts IN, so `url-owner-frame-id` resolves
    ;; to the non-default owner — and `:sd/owner`'s make-frame automatically
    ;; installs the listener for it.
    (rf/make-frame {:id :rf/default :url-bound? false})
    (rf/make-frame {:id :sd/owner :url-bound? true})
    (is (= :sd/owner (rf.routing/url-owner-frame-id))
        "the non-default :url-bound? true frame owns the URL after default opts out")
    ;; :rf/default briefly resolved as the URL owner during register-routes!
    ;; above (before opting out on the very next line), so its OWN
    ;; registration already triggered ONE automatic initial-URL sync
    ;; — a side effect of having briefly BEEN the declared
    ;; owner, not something popstate does. Capture that value so the
    ;; assertions below prove POPSTATE itself never touches the non-owner,
    ;; independent of whatever this shared-registrar test bundle's `match-url
    ;; "/"` happened to resolve to at that transient moment.
    (let [default-route-before (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/routing :current]))]

      ;; Forward nav on the owner frame pushes the URL (owner gates push).
      (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}]     {:frame :sd/owner})
      (rf/dispatch-sync [:rf.route/navigate {:to :hist/checkout}] {:frame :sd/owner})
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
  (testing "after a duplicate :url-bound? true frame that sorts
            BEFORE the incumbent registers, popstate (Back/Forward) STILL
            targets the incumbent owner — not the earlier-sorting duplicate.
            The popstate listener resolves url-owner-frame-id at pop time, so a
            stolen-ownership resolution would drive the WRONG frame."
    (register-routes!)               ;; :rf/default claims the URL first + auto-installs
    (rf/make-frame {:id :aaa-early :url-bound? true})   ;; sorts before :rf/default — a losing duplicate, never installs
    (is (= :rf/default (rf.routing/url-owner-frame-id))
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

;; =========================================================================
;; the :url-bound? frame LIFECYCLE installs/removes the listener
;; =========================================================================
;;
;; There is no `install-url-listener!` / `install-history-listener!` /
;; `remove-url-listener!` / `remove-history-listener!` on the public facade.
;; A `:url-bound? true` frame's CREATE
;; installs the strategy listener; its DESTROY removes it. A losing
;; duplicate `:url-bound? true` registration never installs at all.

(deftest url-bound-frame-lifecycle-installs-on-create-and-removes-on-destroy-cljs
  (testing "a :url-bound? true frame automatically installs its
            popstate listener on create and removes it on destroy-frame! —
            zero imperative install/remove calls anywhere"
    (register-routes!)   ;; :rf/default {:url-bound? true} — auto-installs on create
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
        "the listener installed automatically when the owner frame was created")

    ;; Back/Forward works with zero imperative wiring.
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
  (testing "a losing duplicate :url-bound? true registration does
            NOT reinstall the popstate listener — the incumbent's listener
            instance is untouched (a losing duplicate never installs its own
            / a replacement strategy listener)"
    (register-routes!)                          ;; :rf/default claims + auto-installs
    (let [installed-before (first (get-in @*history-state* [:listeners "popstate"]))]
      (is (some? installed-before)
          "the incumbent's popstate listener installed automatically on create")
      (rf/make-frame {:id :zz/dup-owner :url-bound? true})   ;; losing duplicate
      (is (identical? installed-before
                       (first (get-in @*history-state* [:listeners "popstate"])))
          "the duplicate's registration did not tear down + reinstall the incumbent's listener"))))

;; =========================================================================
;; URL-listener RECONCILIATION on ownership TRANSFER
;; =========================================================================
;;
;; When URL ownership transfers between frames — the incumbent owner is
;; DESTROYED, or RELINQUISHES its binding by re-registering `:url-bound? false`
;; — while another live `:url-bound? true` claimant remains, the browser
;; URL-change listener must REBIND to the new owner's strategy. A destroy path
;; that removed the incumbent's listener and installed nothing would leave a
;; live successor with ZERO browser listeners, and a re-registration opt-out
;; that left the incumbent's listener in place would keep a stale `popstate`
;; across a history→hash handoff and never install `hashchange`. The single
;; strategy-aware `reconcile-url-listener!` op, invoked after post-registration
;; claim changes AND after destroyed-owner claim removal, establishes exactly
;; one matching listener (or none).

(deftest url-ownership-transfer-on-destroy-rebinds-listener-cljs-rf2-3fc89f-11
  (testing "destroying the URL owner rebinds the browser listener
            to the live successor claimant — exactly one popstate listener,
            url-owner resolves to B, and Back drives B's route slice (never
            ZERO listeners after the destroy)"
    (rf/reg-route :hist/home     {} "/")
    (rf/reg-route :hist/cart     {} "/cart")
    (rf/reg-route :hist/checkout {} "/checkout")
    ;; A first-claims the URL (history strategy) → owns + installs popstate. B is
    ;; a later live :url-bound? true claimant (a losing duplicate the registry
    ;; retains and orders AFTER A).
    (rf/make-frame {:id :owner/a :url-bound? true})
    (rf/make-frame {:id :owner/b :url-bound? true})
    (is (= :owner/a (rf.routing/url-owner-frame-id))
        "A is the first-claimed URL owner")
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
        "A's popstate listener installed automatically on create")

    ;; Destroy A: ownership transfers to the live claimant B; the listener rebinds.
    (rf/destroy-frame! :owner/a)
    (is (= :owner/b (rf.routing/url-owner-frame-id))
        "ownership resolved to the surviving claimant B")
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
        "exactly one popstate listener remains, rebound to B (not ZERO)")

    ;; B owns the URL now: forward nav pushes, Back drives B's slice through the
    ;; rebound listener.
    (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}]     {:frame :owner/b})
    (rf/dispatch-sync [:rf.route/navigate {:to :hist/checkout}] {:frame :owner/b})
    (is (= ["/" "/cart" "/checkout"] (:entries @*history-state*))
        "B, the new owner, drives outbound pushState")
    (.back (.-history js/globalThis.window))
    (.dispatchEvent js/globalThis.window #js {:type "popstate"})
    (is (= :hist/cart
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :owner/b)) [:rf.runtime/routing :current])))
        "Back drove the NEW owner B's slice to /cart via the rebound popstate listener")))

(deftest url-ownership-transfer-on-destroy-cross-strategy-rebinds-cljs-rf2-3fc89f-11
  (testing "when the successor uses a DIFFERENT strategy,
            destroying the owner tears the old popstate down and installs the
            successor's hashchange — a hashchange updates B from the
            hash-decoded path"
    (rf/reg-route :hist/home   {} "/")
    (rf/reg-route :hist/active {} "/active")
    ;; A = history owner (popstate); B = hash claimant (hashchange).
    (rf/make-frame {:id :owner/a :url-bound? true})
    (rf/make-frame {:id :owner/b :url-bound? true :url-strategy rf.routing/hash-url-strategy})
    (is (= :owner/a (rf.routing/url-owner-frame-id)))
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
        "A installed a popstate listener")
    (is (empty? (get-in @*history-state* [:listeners "hashchange"]))
        "no hashchange listener yet — A is a history owner")

    (rf/destroy-frame! :owner/a)
    (is (= :owner/b (rf.routing/url-owner-frame-id))
        "ownership resolved to the hash claimant B")
    (is (empty? (get-in @*history-state* [:listeners "popstate"]))
        "the history owner's popstate listener was torn down on transfer")
    (is (= 1 (count (get-in @*history-state* [:listeners "hashchange"])))
        "the successor's hash strategy installed exactly one hashchange listener")

    ;; Move the browser hash to #/active; the rebound hashchange listener decodes
    ;; #/active → /active and updates B.
    (.pushState js/globalThis.window.history nil "" "#/active")
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :hist/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :owner/b)) [:rf.runtime/routing :current])))
        "the rebound hashchange listener decoded #/active → /active and drove B")))

(deftest url-ownership-transfer-on-reregistration-cross-strategy-cljs-rf2-3fc89f-11
  (testing "when the incumbent HISTORY owner opts OUT via
            re-registration (:url-bound? false) while a live HASH claimant B
            remains, the listener rebinds to B's hashchange strategy WITHOUT B
            re-registering — a history→hash handoff must not keep the stale
            popstate"
    (rf/reg-route :hist/home   {} "/")
    (rf/reg-route :hist/active {} "/active")
    (rf/make-frame {:id :owner/a :url-bound? true})   ;; history owner, popstate
    (rf/make-frame {:id :owner/b :url-bound? true :url-strategy rf.routing/hash-url-strategy})
    (is (= :owner/a (rf.routing/url-owner-frame-id)))
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"]))))
    (is (empty? (get-in @*history-state* [:listeners "hashchange"])))

    ;; A opts out; B (hash) becomes owner without re-registering.
    (rf/make-frame {:id :owner/a :url-bound? false})
    (is (= :owner/b (rf.routing/url-owner-frame-id))
        "ownership fell through to the still-bound hash claimant B")
    (is (empty? (get-in @*history-state* [:listeners "popstate"]))
        "A's stale popstate listener was torn down (history→hash handoff)")
    (is (= 1 (count (get-in @*history-state* [:listeners "hashchange"])))
        "B's hash strategy installed exactly one hashchange listener")

    (.pushState js/globalThis.window.history nil "" "#/active")
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :hist/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :owner/b)) [:rf.runtime/routing :current])))
        "the rebound hashchange listener drove B from the hash-decoded path")))

(deftest same-owner-strategy-change-rewires-once-cljs-rf2-3fc89f-11
  (testing "re-registering the SAME owner with a
            changed :url-strategy rewires exactly once — the history popstate is
            torn down, exactly one hashchange installed, no double listener"
    (rf/reg-route :hist/home   {} "/")
    (rf/reg-route :hist/active {} "/active")
    (rf/make-frame {:id :owner/a :url-bound? true})   ;; history → popstate
    (is (= 1 (count (get-in @*history-state* [:listeners "popstate"]))))
    ;; Same owner re-registers with the hash strategy (hot-reload strategy swap).
    (rf/make-frame {:id :owner/a :url-bound? true :url-strategy rf.routing/hash-url-strategy})
    (is (= :owner/a (rf.routing/url-owner-frame-id))
        "A is still the owner across the strategy change")
    (is (empty? (get-in @*history-state* [:listeners "popstate"]))
        "the old history popstate listener was torn down")
    (is (= 1 (count (get-in @*history-state* [:listeners "hashchange"])))
        "exactly one hashchange listener installed — rewired once, not stacked")
    (.pushState js/globalThis.window.history nil "" "#/active")
    (.dispatchEvent js/globalThis.window #js {:type "hashchange"})
    (is (= :hist/active
           (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :owner/a)) [:rf.runtime/routing :current])))
        "the rewired hashchange listener drives the same owner A")))

(deftest destroying-non-owner-leaves-incumbent-listener-untouched-cljs-rf2-3fc89f-11
  (testing "reconciliation on a NON-owner frame
            destroy leaves the incumbent owner's listener instance untouched —
            owner unchanged → no tear-down + reinstall, no stacking"
    (rf/reg-route :hist/home {} "/")
    (rf/make-frame {:id :owner/a :url-bound? true})       ;; owner, popstate
    (rf/make-frame {:id :owner/b :url-bound? true})       ;; losing duplicate (claimed after A)
    (let [incumbent (first (get-in @*history-state* [:listeners "popstate"]))]
      (is (some? incumbent) "A's popstate listener is installed")
      ;; Destroy the NON-owner duplicate B — A keeps the URL.
      (rf/destroy-frame! :owner/b)
      (is (= :owner/a (rf.routing/url-owner-frame-id))
          "A is still the owner after the non-owner B is destroyed")
      (is (identical? incumbent (first (get-in @*history-state* [:listeners "popstate"])))
          "the incumbent's popstate listener instance was NOT torn down + reinstalled")
      (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
          "still exactly one popstate listener — no stacking"))))

(deftest invalid-strategy-reregistration-rejects-and-preserves-listener-cljs-rf2-j538f7-11
  (testing "re-registering the active URL owner with
            a MALFORMED custom :url-strategy (missing the CLJS browser legs)
            fails LOUD with :rf.error/invalid-url-strategy at the
            registration-time PREFLIGHT — BEFORE any write — so EVERY previously
            committed value survives: the frames-registry record (config +
            generation, the same object), the URL claim, and
            the incumbent working listener instance; and no
            :rf.frame/re-registered trace fires. (The preflight makes the
            whole re-registration failure-atomic, not only the listener's
            survival.)"
    (rf/reg-route :hist/home {} "/")
    (rf/reg-route :hist/cart {} "/cart")
    (rf/make-frame {:id :owner/a :url-bound? true})       ;; history owner → popstate
    (let [incumbent       (first (get-in @*history-state* [:listeners "popstate"]))
          ;; SNAPSHOT the committed state before the malformed attempt.
          record-before   (get @rf.frame/frames :owner/a)
          meta-before     (rf/frame-meta :owner/a)
          captured        (atom [])
          cb-key          (keyword (gensym "ktmto9-rereg-trace-"))]
      (is (some? incumbent) "A's popstate listener is installed")
      (is (= 1 (count (get-in @*history-state* [:listeners "popstate"]))))
      (is (some? record-before) "A's frames-registry record exists")
      (rf.trace.tooling/register-listener! cb-key (fn [ev] (swap! captured conj ev)))
      (try
        ;; Re-register A with an invalid strategy: shape-valid on the JVM
        ;; (encode+decode) but MISSING the required CLJS browser legs
        ;; (:push! / :replace! / :install-listener!). The registration-time
        ;; preflight validates and throws before ANY candidate-derived write.
        (let [ex (try
                   (rf/make-frame {:id :owner/a :url-bound?   true
                                   :url-strategy {:encode identity
                                                  :decode (constantly "/")}})
                   nil
                   (catch :default e e))]
          (is (some? ex) "the malformed re-registration throws")
          (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex)))
              "the canonical structured error id is stamped")
          (is (= :owner/a (:frame (ex-data ex)))
              "the ex-data names the offending frame"))
        ;; EVERY previously committed value is unchanged.
        (is (identical? record-before (get @rf.frame/frames :owner/a))
            "the frames-registry record (config + generation + containers) is
             the SAME object — the frames swap never ran")
        (is (= meta-before (rf/frame-meta :owner/a))
            "the frame-meta introspection surface is unchanged — no config commit")
        (is (= :owner/a (rf.routing/url-owner-frame-id))
            "A still owns the URL — the claim order is unchanged")
        (is (empty? (filter #(= :rf.frame/re-registered (:operation %)) @captured))
            "no :rf.frame/re-registered trace fired for the failed attempt")
        ;; The incumbent listener SURVIVED — same instance, still exactly one.
        (is (identical? incumbent (first (get-in @*history-state* [:listeners "popstate"])))
            "the working popstate listener instance was NOT torn down")
        (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
            "still exactly one popstate listener — no orphaning, no stacking")
        ;; And it still drives A: forward-nav then Back through the surviving
        ;; listener updates A's slice.
        (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}] {:frame :owner/a})
        (.back (.-history js/globalThis.window))
        (.dispatchEvent js/globalThis.window #js {:type "popstate"})
        (is (= :hist/home
               (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :owner/a))
                                  [:rf.runtime/routing :current])))
            "Back drove A's slice back to / via the surviving popstate listener")
        (finally
          (rf.trace.tooling/unregister-listener! cb-key))))))

;; ---- FIRST-registration preflight — zero residue on failure ---------------
;;
;; Validating a URL owner's malformed custom :url-strategy only in a
;; POST-create hook would fire after the frame container is built, the
;; trace-policy flags written, and the :initial-events setup RUN. The
;; registration-time preflight (through :routing/preflight-frame-config!)
;; validates the FINAL expanded config BEFORE any candidate-derived write, so
;; a failed first registration leaves NO frame record, NO
;; URL claim or listener, NO trace-policy residue, NO trace event, and NO
;; :initial-events effect — via BOTH `rf/make-frame` arities. The two
;; deftests below differ by that ARITY, which is what their names say.

(defn- assert-zero-residue-first-registration!
  "Shared assertion body for the two constructor spellings: run
  `attempt!` (which must attempt to construct `frame-id` with a malformed
  :url-strategy + a probe :initial-events step) and assert the failure left
  zero residue. `probe` is the external counter the setup step increments."
  [frame-id attempt! probe]
  (let [captured (atom [])
        cb-key   (keyword (gensym "ktmto9-first-trace-"))]
    (rf.trace.tooling/register-listener! cb-key (fn [ev] (swap! captured conj ev)))
    (try
      (let [ex (try (attempt!) nil (catch :default e e))]
        (is (some? ex) "the malformed first registration throws")
        (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data ex)))
            "the canonical structured error id is stamped")
        (is (= frame-id (:frame (ex-data ex)))
            "the ex-data names the offending frame"))
      (is (zero? @probe)
          "the :initial-events setup step never ran — no probe increment")
      (is (not (contains? (set (rf/frame-ids)) frame-id))
          "no frame record was created")
      (is (nil? (rf/frame-meta frame-id))
          "no frame config was seated (frames have no registrar rows)")
      (is (nil? (rf.routing/url-owner-frame-id))
          "no URL claim was recorded")
      (is (empty? (get-in @*history-state* [:listeners "popstate"]))
          "no popstate listener was installed")
      (is (empty? (get-in @*history-state* [:listeners "hashchange"]))
          "no hashchange listener was installed")
      (is (not (rf.trace/frame-trace-disabled? frame-id))
          "no trace-policy residue — the :rf.trace/frame-no-emit? flag was
           never written for the failed frame")
      (is (empty? (filter #(= frame-id (get-in % [:tags :frame])) @captured))
          "no trace event (incl. :rf.frame/created) mentions the failed frame")
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

(deftest first-registration-preflight-zero-residue-make-frame-1-arity-cljs-rf2-ktmto9
  (testing "a URL owner's FIRST rf/make-frame — the 1-arity
            config-only spelling, which resolves the DEFAULT descriptor pool —
            with a malformed custom :url-strategy fails at the
            registration-time preflight with ZERO residue: no container, no
            :initial-events run, no registrar row, no URL claim/listener, no
            trace-policy write, no trace event"
    (rf/reg-route :hist/home {} "/")
    (let [probe (atom 0)]
      (rf/reg-event :ktmto9/probe!
                    (fn [{:keys [db]} _] (swap! probe inc) {:db db}))
      (assert-zero-residue-first-registration!
        :ktmto9/bad-first
        #(rf/make-frame {:id :ktmto9/bad-first :url-bound?              true
                         :url-strategy            {:decode (constantly "/")}
                         :rf.trace/frame-no-emit? true
                         :initial-events          [[:ktmto9/probe!]]})
        probe))))

(deftest first-registration-preflight-zero-residue-make-frame-2-arity-cljs-rf2-ktmto9
  (testing "the same zero-residue invariant through the OTHER
            rf/make-frame arity — the 2-arity spelling that takes an explicit
            descriptor pool — with a malformed custom :url-strategy"
    (rf/reg-route :hist/home {} "/")
    (let [probe (atom 0)]
      (rf/reg-event :ktmto9/probe2!
                    (fn [{:keys [db]} _] (swap! probe inc) {:db db}))
      (assert-zero-residue-first-registration!
        :ktmto9/bad-first-mf
        ;; 2-arity with an explicit EMPTY descriptor pool: the shared node
        ;; test bundle's LIVE source store carries cross-namespace same-id
        ;; registrations, so the default-image projection would fail loud
        ;; (:rf.error/image-duplicate-id) BEFORE the engine is reached. The
        ;; empty pool resolves a valid empty default generation (standards
        ;; only) and the construction proceeds to the engine's preflight —
        ;; which is the surface under test.
        #(rf/make-frame {:id                      :ktmto9/bad-first-mf
                         :url-bound?              true
                         :url-strategy            {:decode (constantly "/")}
                         :rf.trace/frame-no-emit? true
                         :initial-events          [[:ktmto9/probe2!]]}
                        [])
        probe))))

(deftest throwing-installer-preserves-incumbent-listener-cljs-rf2-ktmto9
  (testing "the install-new-before-teardown handoff: a
            SHAPE-VALID custom strategy whose :install-listener! THROWS at
            install time PASSES the static preflight (shape/callability is the
            enforceable static contract — legs are never executed during
            preflight, which would itself cause browser effects) and fails
            later, in the post-registration reconcile; the incumbent listener
            instance + record survive rather than being orphaned"
    (rf/reg-route :hist/home {} "/")
    (rf/reg-route :hist/cart {} "/cart")
    (rf/make-frame {:id :owner/a :url-bound? true})       ;; history owner → popstate
    (let [incumbent (first (get-in @*history-state* [:listeners "popstate"]))
          ;; Every leg callable (so the preflight passes) and behaviourally the
          ;; history strategy — EXCEPT the installer, which throws.
          throwing  (merge rf.routing/history-url-strategy
                           {:install-listener! (fn [_on-change]
                                                 (throw (ex-info "installer boom" {})))})]
      (is (some? incumbent) "A's popstate listener is installed")
      ;; The re-registration COMMITS (the strategy is shape-valid) and the
      ;; post-registration reconcile's install throws — loudly, not swallowed.
      (let [ex (try (rf/make-frame {:id :owner/a :url-bound?   true
                                    :url-strategy throwing})
                    nil
                    (catch :default e e))]
        (is (some? ex) "the throwing installer propagates loudly"))
      ;; The handoff installs the replacement BEFORE the incumbent
      ;; is torn down, so a throwing installer leaves the incumbent in place.
      (is (identical? incumbent (first (get-in @*history-state* [:listeners "popstate"])))
          "the incumbent popstate listener instance survived the failed handoff")
      (is (= 1 (count (get-in @*history-state* [:listeners "popstate"])))
          "exactly one popstate listener — no orphaning, no stacking")
      ;; And it still drives A.
      (rf/dispatch-sync [:rf.route/navigate {:to :hist/cart}] {:frame :owner/a})
      (.back (.-history js/globalThis.window))
      (.dispatchEvent js/globalThis.window #js {:type "popstate"})
      (is (= :hist/home
             (:route-id (get-in (:rf.db/runtime (rf/frame-state-value :owner/a))
                                [:rf.runtime/routing :current])))
          "Back drove A's slice via the surviving popstate listener"))))

;; ---- blocked popstate restores the browser URL -------------------------
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
  (testing "a :can-leave guard blocking a Back/Forward popstate
            restores the browser URL to the slice's route; the slice stays put"
    ;; EP-0002: URL ownership is explicit — opt `:rf/default` in
    ;; as the URL owner so the restore `:rf.nav/replace-url` fx fires.
    (rf/make-frame {:id :rf/default :url-bound? true})
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

    ;; THE RESTORE: the browser address bar was restored to the slice's URL
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

;; ---- CONTINUE after a blocked popstate re-moves the URL -----------------
;;
;; The block above restored the address bar to the rejecting route's URL via
;; replaceState. On `:rf.route/continue` the resume replays the STORED
;; destination + policy through `:rf.route/navigate` with a one-shot
;; `:bypass-leave? true` (EP-0037 R4). A plain replay would PUSH, which would
;; add a second history entry on top of the popstate entry the browser is
;; already sitting on. So a blocked URL-driven transition that restored the
;; address bar records `:url-restored?`, and continue forces `:replace? true`
;; — the address bar moves to `:requested-url` by replaceState, preserving
;; that entry's place with the history length unchanged. Without it the slice
;; would commit to /cart while the address bar stayed on /editor/articles/X,
;; leaving the visible route and the browser URL divergent.

(deftest blocked-popstate-continue-restores-url-cljs
  (testing ":rf.route/continue after a blocked popstate moves the
            address bar to the requested URL — slice and URL agree, no new
            history entry"
    ;; EP-0002: URL ownership is explicit — opt `:rf/default` in
    ;; as the URL owner so the continue `:rf.nav/replace-url` fx fires.
    (rf/make-frame {:id :rf/default :url-bound? true})
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


;; :int query coercion is STRICT and IDENTICAL to the JVM. A lenient
;; `js/parseInt v 10` would turn `?page=12abc` into the NUMBER 12
;; client-side (`parseInt "12abc" 10` -> 12) while the JVM passes the STRING
;; "12abc" through — a Spec 011 hydration-mismatch hazard violating Spec
;; 012's "same handler both sides" + the Spec 000 Goal 2 cross-host bar.
;; Coercion applies only when the whole string is an integer literal
;; (`^-?\d+$`), else string passthrough — so this CLJS pin asserts the EXACT
;; outputs the JVM `query-coercion-vocabulary` test (routing_registry_test.clj)
;; expects.
;; The corpus fixture routing-query-string-coercion.edn runs the same
;; `?page=12abc` call through both harnesses for the formal cross-host bar.
(deftest int-query-coercion-strict-cljs
  (testing ":int coerces only whole integer literals on CLJS, with no
            lenient `js/parseInt` partial-numeric coercion, so the client
            agrees with the JVM"
    (register-routes!)
    (rf/reg-route :hist/list {:query [:map [:page :int]]} "/list")
    (is (= 12 (get-in (rf.routing/match-url "/list?page=12") [:query :page]))
        "clean integer literal coerces to a number")
    (is (= -7 (get-in (rf.routing/match-url "/list?page=-7") [:query :page]))
        "signed integer literal coerces")
    (is (= "12abc" (get-in (rf.routing/match-url "/list?page=12abc") [:query :page]))
        "partial-numeric input stays a STRING (js/parseInt would give 12) —
         no cross-host asymmetry")
    (is (= "0x10" (get-in (rf.routing/match-url "/list?page=0x10") [:query :page]))
        "radix-prefixed input stays a string, matching the JVM")
    (is (= " 12" (get-in (rf.routing/match-url "/list?page=%2012") [:query :page]))
        "leading-whitespace input stays a string, matching the JVM")
    (is (= "abc" (get-in (rf.routing/match-url "/list?page=abc") [:query :page]))
        "fully non-numeric input stays a string (symmetric on both hosts)")))

;; :int coercion is HOST-SYMMETRIC and TOTAL on OVERSIZED integer literals.
;; `js/parseInt` would produce a LOSSY DOUBLE for a literal above 2^53 (e.g.
;; 9007199254740993 -> ...92) where an exact JVM parse would not — the same
;; URL would yield a DIFFERENT :query slice server vs client (a Spec 011
;; hydration mismatch), and a >2^63 literal would route-miss on the JVM
;; while CLJS commits a lossy float (page render) — a divergent OUTCOME.
;; Coercion is bounded at the cross-host safe-integer ceiling (2^53-1) and
;; passes through AS A STRING above it on BOTH hosts. This CLJS pin asserts
;; the EXACT outputs the JVM `int-coercion-oversized-host-parity-jvm` test
;; expects.
(deftest int-coercion-oversized-host-parity-cljs
  (testing "oversized :int literals pass through as STRINGS on
            CLJS (js/parseInt would give a lossy double), matching the JVM"
    (register-routes!)
    (rf/reg-route :hist/items {:query [:map [:page :int]]} "/items")
    (testing "within the safe-integer range still coerces"
      (is (= 42 (get-in (rf.routing/match-url "/items?page=42") [:query :page])))
      (is (= 9007199254740991
             (get-in (rf.routing/match-url "/items?page=9007199254740991") [:query :page]))
          "2^53-1 (MAX_SAFE_INTEGER) coerces — inclusive ceiling, exact on both hosts"))
    (testing "above the ceiling passes through as a string (both hosts agree)"
      (is (= "9007199254740992"
             (get-in (rf.routing/match-url "/items?page=9007199254740992") [:query :page]))
          "2^53 exceeds MAX_SAFE_INTEGER → string (js/parseInt would round)")
      (is (= "9007199254740993"
             (get-in (rf.routing/match-url "/items?page=9007199254740993") [:query :page]))
          "the canonical lossy-double case → string on CLJS too (js/parseInt would give ...92)")
      (is (= "-9007199254740993"
             (get-in (rf.routing/match-url "/items?page=-9007199254740993") [:query :page]))
          "negative oversized literal also passes through"))
    (testing "a literal beyond 2^63 does NOT coerce (parse-long is total, returns nil)"
      (is (= "99999999999999999999999"
             (get-in (rf.routing/match-url "/items?page=99999999999999999999999") [:query :page]))
          "string passthrough, matching the JVM (no throw / no lossy float)"))))

;; PATH params coerce against the :params schema on CLJS too —
;; the canonical Spec 012 :uuid route must round-trip a real UUID URL to
;; {:id #uuid ...} on the browser, identically to the JVM (SSR) side.

;; OPTIONED Malli scalar schemas (`[:int {:min 1}]`,
;; `[:uuid {}]`, `[:boolean {}]`, optioned enums, and
;; `[:maybe inner]`) coerce the URL string identically to the bare
;; form on CLJS, exactly as on the JVM. A coercion table keyed on the raw
;; vector type-form would leave the value a string, failing the optioned
;; schema, so every valid deep link would 404. This is the CLJS half of the
;; JVM `rf2-fwz29i-*` pins in routing_registry_test.clj.

;; {:fragment ""} normalizes to nil at the navigate
;; boundary on CLJS so the pushed URL and slice fragment agree with
;; URL-driven nav.
;; =========================================================================
;; 6. History-mutation defence-in-depth: a throwing pushState / replaceState
;;    fails closed to a structured trace
;; =========================================================================
;;
;; The `url/external-url?` gate at the nav-event sinks
;; fails cross-origin URLs closed before they reach the
;; `:rf.nav/push-url` / `:rf.nav/replace-url` fxs, and the fx also wraps
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
    (rf.trace.tooling/register-listener!
      cb-key
      (fn [ev]
        (when (#{:rf.fx/push-url-failed :rf.fx/replace-url-failed}
                (:operation ev))
          (swap! captured conj (assoc (:tags ev) :operation (:operation ev))))))
    (try
      [(thunk) @captured]
      (finally
        (rf.trace.tooling/unregister-listener! cb-key)))))

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
                  ;; shared try/catch; a leaked throw errors the deftest.
                  (rf/dispatch-sync
                    [:rf.route/navigate {:to :hist/checkout :replace? true}])))))]
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
                  (rf/dispatch-sync [:rf.route/url-requested {:url "/cart"}])))))]
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
