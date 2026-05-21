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

  The runtime does NOT wire `window.addEventListener('popstate', ...)`
  itself; apps are responsible for translating the browser's lifecycle
  events into `:rf.route/handle-url-change` dispatches. The tests below
  exercise both halves of the contract: the OUTBOUND fx (pushState /
  replaceState actually touch the history object) AND the INBOUND
  event (popstate-style dispatch updates the slice + fires :on-match
  + re-emits the nav-token-allocated trace).

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
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

;; ---- window / history stub -----------------------------------------------
;;
;; jsdom-style minimal history mock. Sufficient for routing.cljc's
;; `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` fx to
;; run without throwing. The mock keeps the entry stack in *state* so
;; tests can assert against it directly (no need to read js/window.history.length).

(defn- new-history-stub []
  (let [state (atom {:entries ["/"]   ;; stack of URLs, top = current
                     :index   0       ;; index into :entries for the current URL
                     :listeners {}})] ;; event-type → vec of listeners
    state))

(defn- current-url [state]
  (let [{:keys [entries index]} @state]
    (nth entries index)))

(defn- install-window-stub! []
  (let [state (new-history-stub)
        ;; Synthetic event factory the stub passes to listeners.
        mk-event (fn [type]
                   #js {:type type})
        ;; The stub history object — exposes the HTML5 History API
        ;; surface routing.cljc actually calls into.
        history #js {:pushState
                     (fn [_state _title url]
                       ;; Truncate the forward stack (any entries past
                       ;; the current index get dropped on a fresh push,
                       ;; matching real browser semantics) then append.
                       (swap! state
                              (fn [{:keys [entries index] :as s}]
                                (let [kept (subvec entries 0 (inc index))]
                                  (-> s
                                      (assoc :entries (conj kept url))
                                      (update :index inc))))))
                     :replaceState
                     (fn [_state _title url]
                       (swap! state assoc-in
                              [:entries (:index @state)] url))
                     :back
                     (fn []
                       (swap! state
                              (fn [{:keys [index] :as s}]
                                (if (pos? index)
                                  (assoc s :index (dec index))
                                  s))))
                     :forward
                     (fn []
                       (swap! state
                              (fn [{:keys [entries index] :as s}]
                                (if (< index (dec (count entries)))
                                  (assoc s :index (inc index))
                                  s))))
                     :go
                     (fn [delta]
                       (swap! state
                              (fn [{:keys [entries index] :as s}]
                                (let [next (+ index delta)]
                                  (if (and (>= next 0)
                                           (< next (count entries)))
                                    (assoc s :index next)
                                    s)))))}
        location #js {:origin   "https://app.example"
                      :href     "https://app.example/"
                      :pathname "/"
                      :search   ""
                      :hash     ""}
        window  #js {:history history
                     :location location
                     :scrollX 0
                     :scrollY 0
                     :pageXOffset 0
                     :pageYOffset 0
                     :scrollTo
                     ;; routing.cljc's `:rf.nav/scroll` fx calls
                     ;; `(.scrollTo js/window 0 0)` on forward nav and
                     ;; `(.scrollTo js/window x y)` on restore. Mirror
                     ;; browser state by updating the scroll position
                     ;; fields that :rf.nav/capture-scroll reads.
                     (fn [x y]
                       (set! (.-scrollX js/globalThis.window) x)
                       (set! (.-scrollY js/globalThis.window) y)
                       (set! (.-pageXOffset js/globalThis.window) x)
                       (set! (.-pageYOffset js/globalThis.window) y))
                     :addEventListener
                     (fn [type listener]
                       (swap! state update-in [:listeners type]
                              (fnil conj []) listener))
                     :removeEventListener
                     (fn [type listener]
                       (swap! state update-in [:listeners type]
                              (fnil (fn [xs] (vec (remove #(= % listener) xs)))
                                    [])))
                     :dispatchEvent
                     (fn [event]
                       (doseq [l (get-in @state [:listeners (.-type event)] [])]
                         (l event)))}
        ;; routing.cljc's scroll fx also calls
        ;; `(.getElementById js/document fragment)`. Provide a stub
        ;; document that returns nil so the fragment branch falls
        ;; through to `(.scrollTo js/window 0 0)`.
        document #js {:getElementById (fn [_id] nil)}]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    state))

(defn- uninstall-window-stub! []
  (js-delete js/globalThis "window")
  (js-delete js/globalThis "document"))

;; Per-test fixture: install + tear down the stub, snapshot/restore the
;; registrar (same shape as `route_link_cljs_test.cljs`). The window
;; stub MUST be installed BEFORE the fx fire — both fixtures run :each
;; so order is "compose-first then test-support-fixture-second" via
;; cljs.test's :each composition.

(def ^:dynamic *history-state* nil)

;; Real-browser detection. node-runtime starts with no `js/window`; the
;; stub's `install-window-stub!` builds one on `js/globalThis`. In a
;; real browser (shadow-cljs `:browser-test` headless Chromium runner)
;; `js/window` IS the actual `Window` object — its `History` API can't
;; be js-deleted + replaced, so the in-memory `*history-state*` atom
;; can't observe real-browser pushState calls. Defer browser-runtime
;; coverage to a future real-history-wrapping harness; for now scope
;; this ns to node-runtime via fixture-level skip.
(def ^:private real-browser?
  (and (exists? js/window)
       (some? (.-history js/window))
       (identical? js/window js/globalThis)))

(defn- with-window-stub-fixture
  [f]
  (if real-browser?
    ;; Real browser — fixture skips body; tests run as no-ops.
    nil
    (let [state (install-window-stub!)]
      (try
        (binding [*history-state* state]
          (f))
        (finally
          (uninstall-window-stub!))))))

(use-fixtures :each
  with-window-stub-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn routing/reset-counters!}))

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
  (rf/reg-route :hist/home     {:path "/"})
  (rf/reg-route :hist/cart     {:path "/cart"})
  (rf/reg-route :hist/checkout {:path "/checkout"})
  (rf/reg-route :hist/article  {:path   "/articles/:id"
                                :params [:map [:id :string]]}))

;; =========================================================================
;; 1. pushState round-trip
;; =========================================================================

(deftest pushstate-round-trip-cljs
  (testing "[:rf/url-requested {:url \"/cart\"}] → history.pushState pushes the URL onto the stack AND the :rf/route slice updates"
    (register-routes!)

    ;; Sanity: stub starts at "/" with one entry.
    (is (= ["/"] (:entries @*history-state*))
        "history stub starts with the single root entry")
    (is (= "/" (current-url *history-state*))
        "current URL is /")

    (let [[_ traces]
          (with-route-traces
            (fn []
              (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])))]
      ;; pushState side-effect: a new entry sits on top of the stack.
      (is (= ["/" "/cart"] (:entries @*history-state*))
          ":rf.nav/push-url appended /cart to the history stack")
      (is (= 1 (:index @*history-state*))
          "the history index advanced to the new top entry")
      (is (= "/cart" (current-url *history-state*))
          "history.current points at /cart")

      ;; Slice side-effect: :rf/route was rewritten.
      (let [route (:rf/route (rf/get-frame-db :rf/default))]
        (is (= :hist/cart (:id route))
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
  (testing "successive :rf/url-requested dispatches stack history entries in order"
    (register-routes!)

    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf/url-requested {:url "/checkout"}])
    (rf/dispatch-sync [:rf/url-requested {:url "/articles/intro"}])

    (is (= ["/" "/cart" "/checkout" "/articles/intro"]
           (:entries @*history-state*))
        "four entries on the stack in dispatch order")
    (is (= 3 (:index @*history-state*))
        "index points at the most recent entry")
    (is (= :hist/article
           (:id (:rf/route (rf/get-frame-db :rf/default))))
        "the slice tracks the most recently pushed URL")))

(deftest url-requested-external-url-does-not-push-cljs
  (testing "external absolute URLs are classified before pushState"
    (register-routes!)
    (rf/dispatch-sync [:rf.route/handle-url-change "/"])
    (rf/dispatch-sync [:rf/url-requested {:url "https://elsewhere.example/cart"}])
    (is (= ["/"] (:entries @*history-state*))
        "external URL did not append a history entry")
    (is (= :hist/home
           (:id (:rf/route (rf/get-frame-db :rf/default))))
        "external URL did not rewrite the app route to not-found")))

(deftest scroll-position-captured-before-forward-nav-cljs
  (testing "leaving a route captures the current browser scroll position under that route's URL"
    (register-routes!)
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (.scrollTo js/globalThis.window 12 345)
    (rf/dispatch-sync [:rf/url-requested {:url "/checkout"}])
    (is (= [12 345]
           (routing/lookup-scroll-position
             (rf/get-frame-db :rf/default)
             "/cart"))
        "scroll position for the route being left is saved before the scroll strategy runs")))

(deftest duplicate-url-bound-frame-does-not-push-cljs
  (testing "a second :url-bound? true frame is reported but not allowed to mutate browser history"
    (register-routes!)
    (rf/reg-frame :hist/duplicate-owner {:url-bound? true})
    (rf/dispatch-sync [:rf.route/navigate :hist/cart]
                      {:frame :hist/duplicate-owner})
    (is (= ["/"] (:entries @*history-state*))
        "duplicate URL-bound frame did not push to browser history")
    (is (= :hist/cart
           (:id (:rf/route (rf/get-frame-db :hist/duplicate-owner))))
        "the non-owner frame still updates its own route slice")))

;; =========================================================================
;; 2. popstate (back-button) round-trip
;; =========================================================================

(deftest popstate-back-button-cljs
  (testing "after two pushes, (.back history) + dispatch :rf.route/handle-url-change drops the slice back to the prior route"
    (register-routes!)

    ;; Push two routes.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf/url-requested {:url "/checkout"}])
    (is (= :hist/checkout
           (:id (:rf/route (rf/get-frame-db :rf/default))))
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
             (:id (:rf/route (rf/get-frame-db :rf/default))))
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
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
    (rf/dispatch-sync [:rf/url-requested {:url "/checkout"}])

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
             (:id (:rf/route (rf/get-frame-db :rf/default))))
          "the slice landed on /cart through the listener-driven popstate path"))))

;; =========================================================================
;; 3. hashchange — fragment-only round-trip
;; =========================================================================
;;
;; Per Spec 012 §Fragments and routing.cljc's `:rf.route/transitioned`
;; handler — when only the URL fragment changes (the route-id,
;; :params, and :query are unchanged) the runtime updates
;; :rf/route :fragment and emits :rf.route/fragment-changed (rf2-cj9fn,
;; pre-rename: `:rf.route/fragment-changed`) instead of re-firing :on-match.
;; That's the framework's hashchange surface.

(deftest hashchange-fragment-only-cljs
  (testing "URL fragment change → :rf.route/fragment-changed trace fires; no new nav-token allocation"
    (register-routes!)
    ;; Forward nav lands on /articles/intro.
    (rf/dispatch-sync [:rf/url-requested {:url "/articles/intro"}])
    (let [pre-nav-token (-> (rf/get-frame-db :rf/default)
                            :rf/route :nav-token)]

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
        (is (zero? (count @allocations))
            "fragment-only nav does NOT allocate a new nav-token")

        (let [route (:rf/route (rf/get-frame-db :rf/default))]
          (is (= "section-2" (:fragment route))
              ":rf/route :fragment is updated to the new fragment")
          (is (= :hist/article (:id route))
              "route-id is unchanged across the fragment-only nav")
          (is (= pre-nav-token (:nav-token route))
              "nav-token survives the fragment-only update (no new allocation)"))))))

(deftest hashchange-via-window-listener-cljs
  (testing "a hashchange listener registered via window.addEventListener fires on dispatchEvent"
    (register-routes!)
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])

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
    (rf/reg-route :hist/search {:path "/search"})
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
    (rf/reg-route :hist/list {:path  "/list"
                              :query [:map [:page :int]]})
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

;; =========================================================================
;; 4. replaceState — no new history entry
;; =========================================================================

(deftest replacestate-no-new-entry-cljs
  (testing ":rf.route/navigate with :replace? true → replaceState mutates the top entry; stack length unchanged"
    (register-routes!)

    ;; Land on /cart via a normal push so the stack is at length 2.
    (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
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
                     (:id (:rf/route (rf/get-frame-db :rf/default))))
          pop-and-dispatch!
          (fn []
            (.back (.-history js/globalThis.window))
            (rf/dispatch-sync
              [:rf.route/handle-url-change (current-url *history-state*)]))]

      ;; push A (/cart)
      (rf/dispatch-sync [:rf/url-requested {:url "/cart"}])
      (is (= :hist/cart (route-id)) "after push A → :hist/cart")
      (is (= ["/" "/cart"] (:entries @*history-state*)))

      ;; push B (/checkout)
      (rf/dispatch-sync [:rf/url-requested {:url "/checkout"}])
      (is (= :hist/checkout (route-id)) "after push B → :hist/checkout")
      (is (= ["/" "/cart" "/checkout"] (:entries @*history-state*)))

      ;; pop → back to /cart
      (pop-and-dispatch!)
      (is (= :hist/cart (route-id)) "after pop → :hist/cart")
      (is (= 1 (:index @*history-state*))
          "the forward entry survives the pop (only index moved)")

      ;; push C (/articles/intro) → forward entry truncated, new entry appended.
      (rf/dispatch-sync [:rf/url-requested {:url "/articles/intro"}])
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
