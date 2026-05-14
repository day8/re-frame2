(ns re-frame.routing-history-cljs-test
  "CLJS tests for the browser-history surface of routing
  (rf2-wp0w4). Locks the popstate / hashchange / pushState /
  replaceState round-trip on the node-runtime test target.

  re-frame2's history-integration contract (Spec 012 §URL changes
  are events) is split across two functions:

  - `:rf.nav/push-url`     fx — calls `(.pushState js/window.history nil \"\" url)`.
  - `:rf.nav/replace-url`  fx — calls `(.replaceState js/window.history nil \"\" url)`.
  - `:rf/url-changed`      event — forward nav (push / link click).
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
        window  #js {:history history
                     :scrollTo
                     ;; routing.cljc's `:rf.nav/scroll` fx calls
                     ;; `(.scrollTo js/window 0 0)` on forward nav and
                     ;; `(.scrollTo js/window x y)` on restore. The
                     ;; stub no-ops; an atom captures the args for the
                     ;; one test that asserts scroll behaviour.
                     (fn [_x _y])
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
;; coverage to a future harness (track via rf2-wp0w4 retro). For now
;; scope this ns to node-runtime via fixture-level skip.
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
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn routing/reset-counters!}))

;; ---- trace-capture helper ------------------------------------------------

(defn- with-route-traces
  "Run thunk while collecting :rf.route.nav-token/allocated events.
   Returns [result-of-thunk vector-of-trace-payloads]."
  [thunk]
  (let [captured (atom [])
        cb-key   (keyword (gensym "route-trace-"))]
    (rf/register-trace-cb!
      cb-key
      (fn [ev]
        (when (= :rf.route.nav-token/allocated (:operation ev))
          (swap! captured conj (:tags ev)))))
    (try
      (let [r (thunk)]
        [r @captured])
      (finally
        (rf/remove-trace-cb! cb-key)))))

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
;; Per Spec 012 §Fragments and routing.cljc's `:rf/url-changed`
;; handler — when only the URL fragment changes (the route-id,
;; :params, and :query are unchanged) the runtime updates
;; :rf/route :fragment and emits :rf.route/url-changed instead of
;; re-firing :on-match. That's the framework's hashchange surface.

(deftest hashchange-fragment-only-cljs
  (testing "URL fragment change → :rf.route/url-changed trace fires; no new nav-token allocation"
    (register-routes!)
    ;; Forward nav lands on /articles/intro.
    (rf/dispatch-sync [:rf/url-requested {:url "/articles/intro"}])
    (let [pre-nav-token (-> (rf/get-frame-db :rf/default)
                            :rf/route :nav-token)]

      ;; Capture both :rf.route/url-changed AND
      ;; :rf.route.nav-token/allocated emissions during the fragment-only
      ;; dispatch — assert the former fires and the latter does NOT.
      (let [fragment-changed (atom [])
            allocations      (atom [])
            cb-key           (keyword (gensym "hashchange-"))]
        (rf/register-trace-cb!
          cb-key
          (fn [ev]
            (case (:operation ev)
              :rf.route/url-changed
              (swap! fragment-changed conj (:tags ev))
              :rf.route.nav-token/allocated
              (swap! allocations conj (:tags ev))
              nil)))
        (try
          (rf/dispatch-sync [:rf/url-changed "/articles/intro#section-2"])
          (finally
            (rf/remove-trace-cb! cb-key)))

        (is (= 1 (count @fragment-changed))
            "fragment-only nav emits :rf.route/url-changed exactly once")
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
