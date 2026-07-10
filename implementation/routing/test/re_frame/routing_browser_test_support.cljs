(ns re-frame.routing-browser-test-support
  "Shared node-runtime browser-history fixture for the routing CLJS test
  suites (rf2-y6e2zb).

  `routing_history_cljs_test` and `routing_url_strategy_cljs_test` each carried
  their own copy of this jsdom-style history/location/document stub; this
  namespace is the single owner of the SUPERSET fixture — the history suite's
  version, which additionally exposes `go` alongside `back` / `forward`.

  Node has no `window` / `document` globals, so `with-window-stub-fixture`
  installs a minimal stub on `js/globalThis` and tears it down in `finally`. The
  stub records `pushState` / `replaceState` onto an in-memory entry stack and
  exposes `back` / `forward` / `go` so the popstate path can be driven without a
  real DOM; it splits `location` into pathname / search / hash the way a real
  browser does so a hash strategy that writes `#/active` and reads
  `location.hash` round-trips. `*history-state*` holds the current in-memory
  history state (entry stack + index + listener registry) for direct assertion,
  and `current-url` reassembles the active URL string. It also mirrors
  `scrollX` / `scrollY` on `scrollTo` (for `:rf.nav/scroll`) and returns nil
  from `document.getElementById` (so the fragment branch falls through). The
  fixture is scoped to the test target; production code is untouched.

  Node-runtime only by design (rf2-6qclsc). This namespace deliberately ends in
  `-test-support`, NOT `-cljs-test`, so the shadow-cljs `:node-test` discovery
  regex (`cljs-test$`) never runs it as a test namespace (and the narrowed
  `:browser-test` `-dom-cljs-test$` regex ignores it too). A real browser's
  `Window` / `History` cannot be replaced, so the in-memory `*history-state*`
  atom could not observe real `pushState` calls there anyway; a real-browser
  harness would be a separate `*_dom_cljs_test.cljs` namespace.

  Per Spec 012 §URL changes are events, §Navigation tokens, §Scroll
  restoration, §URL strategies.")

;; ---- window / history + location stub ------------------------------------
;;
;; jsdom-style minimal history + location mock. Sufficient for routing.cljc's
;; `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` fx to run
;; without throwing. The mock keeps the entry stack in *state* so tests can
;; assert against it directly (no need to read js/window.history.length).

(defn- new-history-stub []
  (atom {:entries ["/"]     ;; stack of URLs, top = current
         :index   0         ;; index into :entries for the current URL
         :listeners {}}))   ;; event-type → vec of listeners

(defn current-url
  "Reassemble the active URL string from the history `state` atom."
  [state]
  (let [{:keys [entries index]} @state]
    (nth entries index)))

(defn- install-window-stub! []
  (let [state (new-history-stub)
        location #js {:origin   "https://app.example"
                      :href     "https://app.example/"
                      :pathname "/"
                      :search   ""
                      :hash     ""}
        ;; Keep `location` in sync with the current history entry, the way a
        ;; real browser updates `window.location` on pushState / back /
        ;; forward. The automatically-installed popstate handler (rf2-g8pbwg —
        ;; a `:url-bound? true` frame's lifecycle installs it) reads the new
        ;; URL off `window.location` (not the test's state atom), so the stub
        ;; MUST reflect the navigation here for the listener-driven
        ;; Back/Forward tests (rf2-6qgbs.4). Splits the entry into pathname /
        ;; search / hash so `current-url` reassembles the same string.
        sync-location!
        (fn []
          (let [{:keys [entries index]} @state
                url    (nth entries index)
                [path+ hash]   (let [i (.indexOf url "#")]
                                 (if (neg? i) [url ""]
                                     [(subs url 0 i) (subs url i)]))
                [path search]  (let [i (.indexOf path+ "?")]
                                 (if (neg? i) [path+ ""]
                                     [(subs path+ 0 i) (subs path+ i)]))]
            (set! (.-pathname location) path)
            (set! (.-search location) search)
            (set! (.-hash location) hash)
            (set! (.-href location) (str (.-origin location) url))))
        ;; The stub history object — exposes the HTML5 History API surface
        ;; routing.cljc actually calls into.
        history #js {:pushState
                     (fn [_state _title url]
                       ;; Truncate the forward stack (any entries past the
                       ;; current index get dropped on a fresh push, matching
                       ;; real browser semantics) then append.
                       (swap! state
                              (fn [{:keys [entries index] :as s}]
                                (let [kept (subvec entries 0 (inc index))]
                                  (-> s
                                      (assoc :entries (conj kept url))
                                      (update :index inc)))))
                       (sync-location!))
                     :replaceState
                     (fn [_state _title url]
                       (swap! state assoc-in
                              [:entries (:index @state)] url)
                       (sync-location!))
                     :back
                     (fn []
                       (swap! state
                              (fn [{:keys [index] :as s}]
                                (if (pos? index)
                                  (assoc s :index (dec index))
                                  s)))
                       (sync-location!))
                     :forward
                     (fn []
                       (swap! state
                              (fn [{:keys [entries index] :as s}]
                                (if (< index (dec (count entries)))
                                  (assoc s :index (inc index))
                                  s)))
                       (sync-location!))
                     :go
                     (fn [delta]
                       (swap! state
                              (fn [{:keys [entries index] :as s}]
                                (let [next (+ index delta)]
                                  (if (and (>= next 0)
                                           (< next (count entries)))
                                    (assoc s :index next)
                                    s))))
                       (sync-location!))}
        window  #js {:history history
                     :location location
                     :scrollX 0
                     :scrollY 0
                     :pageXOffset 0
                     :pageYOffset 0
                     :scrollTo
                     ;; routing.cljc's `:rf.nav/scroll` fx calls
                     ;; `(.scrollTo js/window 0 0)` on forward nav and
                     ;; `(.scrollTo js/window x y)` on restore. Mirror browser
                     ;; state by updating the scroll fields
                     ;; `:rf.nav/capture-scroll` reads.
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
        ;; `(.getElementById js/document fragment)`. Provide a stub document
        ;; that returns nil so the fragment branch falls through to
        ;; `(.scrollTo js/window 0 0)`.
        document #js {:getElementById (fn [_id] nil)}]
    (set! (.-window js/globalThis) window)
    (set! (.-document js/globalThis) document)
    state))

(defn- uninstall-window-stub! []
  (js-delete js/globalThis "window")
  (js-delete js/globalThis "document"))

(def ^:dynamic *history-state* nil)

(defn with-window-stub-fixture
  "Per-test `use-fixtures` thunk: install the window/history/document stub, bind
  `*history-state*` to it, run the test, and tear the stub down in `finally`
  even on failure. Compose it FIRST (before the runtime-reset fixture) so the
  stub exists before any routing fx fires."
  [f]
  (let [state (install-window-stub!)]
    (try
      (binding [*history-state* state]
        (f))
      (finally
        (uninstall-window-stub!)))))
