(ns re-frame.bench.hicasso.arm1.host-ssr-dom-cljs-test
  "DEFHOST'S `:ssr` POLICY, PROVEN IN ALL THREE PLACES IT HAS TO HOLD
  (rf2-2rtt6.85, HD-011).

  HD-011 listed \"SSR placeholder\" among `defhost`'s strong defaults;
  HD-020(d) left it inert in v0; the operator's 2026-08-04 ruling makes
  SSR required scope. The 2026-08-04 runtime audit then found the
  placeholder was not inert but ABSENT — `mint-host!` read `:callbacks`
  and silently ignored every other option — so activating it is two
  jobs: give the declaration a policy, and make an option it does not
  know a refusal rather than a shrug.

  ## The policy

      (defhost chart Chart)                                ; :client-only
      (defhost chart Chart {:ssr :client-only})            ; the same, said
      (defhost chart Chart {:ssr {:fallback [:div.skel]}}) ; markup instead

  `:client-only` renders nothing where the host sits until the client
  has adopted the markup. `{:fallback <hiccup>}` renders that markup
  there instead. There is no third value.

  ## The three places, and why ONE mechanism covers them

  A declaration mints one gate — a component whose single
  `useSyncExternalStore` answers `false` from its SERVER snapshot and
  `true` from its client one. React reads the server snapshot under
  `renderToString` AND again on hydration's first client pass, then
  re-renders with the client snapshot once adoption completes. So:

  1. **The server render** honours the policy without a server walk
     that knows anything about it — it honours it by rendering.
  2. **Hydration's first client pass** produces the same markup the
     server did, so there is nothing to reconcile. That claim is taken
     here the only way it can be taken honestly: React is asked, via
     `onRecoverableError` and a console/window capture, whether it
     found a mismatch. A row that merely asserts the final DOM would
     pass over a mismatch React silently repaired.
  3. **A fresh `createRoot` mount** never consults a server snapshot at
     all, so the foreign component renders on the very first pass and
     the placeholder never flashes. Asserted on the line after
     `root!` returns, which is inside its own `flushSync`.

  ## The mutation witnesses

  Make `:client-only` render something — `gate-unadopted` answering
  `true`, or the placeholder becoming the live element — and
  [[client-only-hydrates-with-nothing-there-and-mounts-after-adoption]]
  goes red on React's own mismatch report. Make `{:fallback …}` render
  nothing — `mint-host-gate!`'s `placeholder` forced to `nil` — and
  [[the-server-render-honours-the-policy]] goes red on the fallback
  markup missing from the server HTML. Neither mutation is visible to
  the other row, which is why there are two.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The declaration rows and the `renderToString` rows need no
  DOM and run under `:node-test` too — `renderToString` is React's
  server renderer, and the point of using it here is that it is the
  same runtime the sibling Node entry (rf2-2rtt6.86) drives."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview defhost]]))

(def ^:private frame-id ::host-ssr)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :ssr/title (fn [db _] (:title db)))

(rf/reg-event :ssr/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(defn- skip! [why] (is true (str "an SSR-policy DOM claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:ssr/seed]))
  frame-id)

;; ---------------------------------------------------------------------------
;; The foreign component, and the three declarations
;; ---------------------------------------------------------------------------

(def ^:private !renders
  "How many times the foreign component's body ran. The server rows'
  strongest assertion: a `:client-only` host is not merely absent from
  the HTML, its component was never INVOKED — which is the property an
  author relies on when the reason they declared `:client-only` is
  that the thing reaches for `window`."
  (atom 0))

(defn- chart
  "A stand-in for the library nobody wants running on a server: its own
  state, its own effect, its own DOM."
  [^js props]
  (swap! !renders inc)
  (let [ready-hook (react/useState "cold")
        ready      (aget ready-hook 0)
        set-ready  (aget ready-hook 1)]
    (react/useEffect (fn [] (set-ready "warm") js/undefined) #js [])
    (react/createElement "div"
      #js {:className "chart" :data-live "yes" :data-ready ready}
      (react/createElement "span" #js {:className "chart-label"} (.-label props))
      (.-children props))))

(defhost bare-chart
  "No `:ssr` written at all — the default is what an author gets."
  chart)

(defhost client-only-chart chart {:ssr :client-only})

(defhost fallback-chart chart
  {:ssr {:fallback [:div.chart-skeleton {:data-live "no"} "loading"]}})

;; ---------------------------------------------------------------------------
;; The pages
;; ---------------------------------------------------------------------------

(defview client-only-page
  "A native sibling beside the host, so \"the host is absent\" is
  distinguishable from \"nothing rendered at all\"."
  [_]
  [:div.page
   [:h1.title (rt/sub [:ssr/title])]
   [client-only-chart {:label (rt/sub [:ssr/title])}]])

(defview fallback-page
  [_]
  [:div.page
   [:h1.title (rt/sub [:ssr/title])]
   [fallback-chart {:label (rt/sub [:ssr/title])}]])

(defview slotted-page
  "Children and props still cross the door — the gate forwards its own
  props object, so this is the regression guard for the crossing that
  the gate now sits in front of."
  [_]
  [:div.page
   [fallback-chart {:label (rt/sub [:ssr/title])}
    [:span.kid "slotted"]]])

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- error-id [f]
  (try (f) ::did-not-throw (catch :default e (:rf.error/id (ex-data e)))))

(defn- server-html
  "The page as a server render — the SAME runtime, under React's server
  renderer, which is the sibling Node entry's whole architecture
  (rf2-2rtt6.86) reduced to one call."
  [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(defn- q [root sel] (.querySelector root sel))

(defn- watch-errors!
  "Everything React has to say about a hydration, from all three
  channels it uses. `console.error` is the one that carries the
  mismatch diff; `onRecoverableError` is the one that carries the
  recovery; a `window` error listener is here because React routes a
  throw during a commit to `reportError`, where `cljs.test` cannot see
  it and a row would read 0 failures over a live exception."
  []
  (let [seen     (atom [])
        original (.-error js/console)
        on-error (fn [e] (swap! seen conj (str "window: " (.-message e))))]
    (set! (.-error js/console)
          (fn [& args] (swap! seen conj (str "console.error: " (pr-str (vec args))))))
    (.addEventListener js/window "error" on-error)
    {:seen    seen
     :restore (fn []
                (set! (.-error js/console) original)
                (.removeEventListener js/window "error" on-error))}))

(defn- hydrate!
  "`hydrateRoot` over server HTML, plainly — no `flushSync`, because the
  claim under test is about the schedule the browser actually runs."
  [container element seen]
  (react-dom-client/hydrateRoot
    container element
    #js {:onRecoverableError
         (fn [err _info]
           (swap! seen conj (str "onRecoverableError: " (ex-message err))))}))

;; ---------------------------------------------------------------------------
;; 1 — the declaration (these rows run under :node-test too)
;; ---------------------------------------------------------------------------

(deftest the-default-policy-is-client-only
  (testing "an author who writes no :ssr gets the conservative answer, and
            an author who writes it explicitly gets the same one — a
            foreign component is exactly the node whose render may reach
            for `window`, and the door does not guess"
    (is (= :client-only (codec/host-ssr bare-chart)))
    (is (= :client-only (codec/host-ssr client-only-chart))))
  (testing "and a declared fallback reads back as the data it was written as"
    (is (= {:fallback [:div.chart-skeleton {:data-live "no"} "loading"]}
           (codec/host-ssr fallback-chart)))))

(deftest the-declaration-refuses-a-policy-it-cannot-honour
  (testing "a third value is refused at the declaration, naming the two"
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/server" chart {:ssr :server}))))
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/true" chart {:ssr true})))))
  (testing "an explicit nil is a value, not an absence — `:client-only` is
            the default of an ABSENT key, and inferring it from nil is how
            a typo becomes a policy"
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/nil" chart {:ssr nil})))))
  (testing "a fallback map is exactly one key with a value"
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/empty" chart {:ssr {}}))))
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/nil-fb" chart {:ssr {:fallback nil}}))))
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/extra" chart
                                        {:ssr {:fallback [:div] :timeout 3}})))))
  (testing "and a fallback that is not hiccup fails HERE, at the
            declaration, rather than one render into a server response —
            it is walked once at mint, where the author's stack is"
    (is (= :rf.error/hicasso-empty-vector
           (error-id #(codec/mint-host! "ssr/bad-fb" chart {:ssr {:fallback []}}))))))

(deftest the-declaration-refuses-an-option-it-does-not-know
  (testing "`mint-host!` read :callbacks and IGNORED the rest, so a
            misspelled policy was a setting that never applied — the same
            defect class as an intent crossing as inert data, and it gets
            the same refusal"
    (is (= :rf.error/hicasso-host-unknown-option
           (error-id #(codec/mint-host! "ssr/typo" chart {:sssr :client-only}))))
    (is (= :rf.error/hicasso-host-unknown-option
           (error-id #(codec/mint-host! "ssr/legacy" chart
                                        {:callbacks {} :hydrate? true})))))
  (testing "while the two it does know are accepted together"
    (is (some? (codec/mint-host! "ssr/both" chart
                                 {:callbacks {:on-pick :event}
                                  :ssr       {:fallback [:div.s]}})))))

;; ---------------------------------------------------------------------------
;; 2 — the server render (no DOM needed; runs under :node-test too)
;; ---------------------------------------------------------------------------

(deftest the-server-render-honours-the-policy
  (fresh!)
  (testing ":client-only — the host region is ABSENT from the server HTML,
            and the component was never even invoked"
    (reset! !renders 0)
    (let [html (server-html [client-only-page {}])]
      (is (re-find #"quarterly" html)
          (str "the page rendered — the sibling native node is there: " html))
      (is (not (re-find #"class=\"chart\"" html))
          (str "and the host region rendered NOTHING: " html))
      (is (zero? @!renders)
          "the foreign component's body never ran on the server, which is
           the property an author declaring :client-only is relying on")))
  (testing "{:fallback …} — the declared markup is what the server writes,
            and still not the component"
    (reset! !renders 0)
    (let [html (server-html [fallback-page {}])]
      (is (re-find #"chart-skeleton" html)
          (str "the fallback hiccup is in the server HTML: " html))
      (is (not (re-find #"data-live=\"yes\"" html))
          (str "and the foreign component's own markup is not: " html))
      (is (zero? @!renders)
          "nor did its body run"))))

;; ---------------------------------------------------------------------------
;; 3 — a fresh mount: no placeholder, ever
;; ---------------------------------------------------------------------------

(deftest a-fresh-mount-renders-the-component-on-its-first-pass
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (testing "a `createRoot` mount never consults a server snapshot, so
                neither policy costs a placeholder pass — asserted on the
                line after `root!` returns, which is inside its flushSync"
        (let [a (mount/root! (mount/fresh-container!) frame-id [client-only-page {}])]
          (try
            (is (some? (q (:container a) ".chart"))
                ":client-only mounted its component immediately")
            (is (= "yes" (.getAttribute (q (:container a) ".chart") "data-live"))
                "and it is the real one")
            (finally (mount/release! a))))
        (let [b (mount/root! (mount/fresh-container!) frame-id [fallback-page {}])]
          (try
            (is (some? (q (:container b) ".chart"))
                "{:fallback …} mounted its component immediately too")
            (is (nil? (q (:container b) ".chart-skeleton"))
                "and the placeholder never flashed — a fallback is for the
                 server's markup, not for a client that has none")
            (finally (mount/release! b))))))))

(deftest the-gate-forwards-props-and-children-untouched
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (testing "the gate hands its own props straight through, so the
                crossing is the object `host-element` built — the door's
                contract is unchanged by the policy sitting in front of it"
        (let [h (mount/root! (mount/fresh-container!) frame-id [slotted-page {}])]
          (try
            (is (= "quarterly" (.-textContent (q (:container h) ".chart-label")))
                "a declared prop reached the foreign component")
            (is (some? (q (:container h) ".chart .kid"))
                "and so did the children, in the component's own slot")
            (finally (mount/release! h))))))))

;; ---------------------------------------------------------------------------
;; 4 — hydration: the first client pass matches, and adoption swaps
;; ---------------------------------------------------------------------------

(defn- hydration-row
  "Bake the page on the server, hydrate that HTML, and answer what React
  reported — plus the HTML it hydrated FROM, because a row that only
  reads the settled DOM cannot tell a policy that worked from a policy
  that was never applied on either side. Shared by the two policies
  because the difference between them is the markup, not the procedure."
  [hiccup after]
  (fresh!)
  (reset! !renders 0)
  (let [html      (server-html hiccup)
        container (mount/fresh-container!)
        {:keys [seen restore]} (watch-errors!)]
    (set! (.-innerHTML container) html)
    (let [root (hydrate! container
                         (mount/provider frame-id (codec/root-element frame-id hiccup))
                         seen)]
      (js/setTimeout
        (fn []
          (try
            (after container @seen html)
            (finally
              (restore)
              (.unmount root)
              (when-some [p (.-parentNode container)] (.removeChild p container))
              (rt/reset-runtime!))))
        150))))

(deftest client-only-hydrates-with-nothing-there-and-mounts-after-adoption
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (hydration-row
        [client-only-page {}]
        (fn [container seen html]
          (is (not (re-find #"class=\"chart\"" html))
              (str "the markup hydrated FROM has no host region in it — the "
                   "row's own restatement of the policy, so that a gate "
                   "which stopped gating is red HERE and not only in the "
                   "server-render row: " html))
          (is (empty? seen)
              (str "REACT FOUND NOTHING TO RECONCILE. The client's first "
                   "pass rendered what the server did — nothing — so there "
                   "was no mismatch to repair: " (pr-str seen)))
          (is (some? (q container ".chart"))
              "and after adoption the foreign component is mounted")
          (is (= "yes" (.getAttribute (q container ".chart") "data-live"))
              "the real one, not a placeholder")
          (is (some? (q container ".title"))
              "with the server's own markup still in place around it —
               adoption, not a re-render of the page")
          (is (pos? @!renders)
              "the component ran on the client, and only on the client")
          (done))))))

(deftest a-fallback-hydrates-as-the-placeholder-and-is-swapped-after-adoption
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (hydration-row
        [fallback-page {}]
        (fn [container seen html]
          (is (re-find #"chart-skeleton" html)
              (str "the markup hydrated FROM carries the declared fallback: "
                   html))
          (is (empty? seen)
              (str "the fallback the server wrote is what the client's "
                   "first pass rendered, so again there was nothing to "
                   "reconcile: " (pr-str seen)))
          (is (nil? (q container ".chart-skeleton"))
              "and the placeholder is GONE after adoption")
          (is (some? (q container ".chart"))
              "replaced by the foreign component")
          (done))))))
