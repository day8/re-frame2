(ns re-frame.hicasso.host-ssr-dom-cljs-test
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

      (h/defhost chart Chart)                                ; :client-only
      (h/defhost chart Chart {:ssr :client-only})            ; the same, said
      (h/defhost chart Chart {:ssr {:fallback [:div.skel]}}) ; markup instead
      (h/defhost themed (.-Provider ctx) {:ssr :render})     ; run it, server-side

  `:client-only` renders nothing where the host sits until the client
  has adopted the markup. `{:fallback <hiccup>}` renders that markup
  there instead. `:render` is the author asserting that the component
  is safe to run on the server, and it is the ONLY policy under which a
  crossing's CHILDREN reach the server response — §3 below. There is no
  fourth value.

  WHAT MAY BE WRITTEN IN THAT FALLBACK is settled (rf2-nv07k, ruled
  2026-08-05): a fallback is inert markup, enforced — a `defview` or
  `defhost` head written there is refused at the declaration with
  `:rf.error/hicasso-host-fallback-boundary-head`. The contract is
  [[re-frame.hicasso.fallback-contents-cljs-test]]; nothing
  in this suite depends on it beyond the one refusal row below.

  ## The three places, and why ONE mechanism covers the first two policies

  A `:client-only` or `{:fallback …}` declaration mints one gate — a
  component whose single `useSyncExternalStore` answers `false` from its
  SERVER snapshot and `true` from its client one. React reads the server
  snapshot under `renderToString` AND again on hydration's first client
  pass, then re-renders with the client snapshot once adoption
  completes. So:

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

  ## And why `:render` needs NO mechanism at all (rf2-l0wfx)

  Under `:render` the declaration mints no gate: the head's type IS the
  foreign component. So all three places above render the SAME element
  type with the same props, the same context and the same children —
  one tree everywhere. There is no snapshot pair, so no mismatch by
  identity; no adoption event, so nothing to wait for; and no type swap,
  so NO REMOUNT.

  That last one is the law that priced every rejected candidate. React
  reconciles a position by element type, and under a gate the gate is
  the type — so any policy whose unadopted branch returns something
  other than the component pays a full subtree destroy-and-rebuild the
  moment adoption swaps it. Free for a skeleton; not free for the
  application. [[a-render-crossing-hydrates-once-and-never-remounts]] is
  where that is measured rather than argued.

  ## The mutation witnesses

  Make `:client-only` render something — `gate-unadopted` answering
  `true`, or the placeholder becoming the live element — and
  [[client-only-hydrates-with-nothing-there-and-mounts-after-adoption]]
  goes red on React's own mismatch report. Make `{:fallback …}` render
  nothing — `mint-host-gate!`'s `placeholder` forced to `nil` — and
  [[the-server-render-honours-the-policy]] goes red on the fallback
  markup missing from the server HTML. Route `:render` through
  `mint-host-gate!` like the other two — `mint-host!`'s
  `keyword-identical?` branch removed — and
  [[the-server-render-honours-the-policy]] goes red on the subtree
  missing from the server HTML, while
  [[a-render-crossing-hydrates-once-and-never-remounts]] goes red on the
  second child mount the adoption swap causes. No mutation is visible to
  more than one row, which is why there are four.

  A fifth guards the assertion from the other side (rf2-hic-028): make a
  server render SWALLOW what the component threw — a `try` around the
  crossing, a policy that quietly emitted the page with the region
  missing — and
  [[a-false-render-assertion-fails-loudly-at-the-crossing]] goes red,
  because `:render` is an author's claim and a claim whose falsification
  is silent is not worth making.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The declaration rows and the `renderToString` rows need no
  DOM and run under `:node-test` too — `renderToString` is React's
  server renderer, and the point of using it here is that it is the
  same runtime the sibling Node entry (rf2-2rtt6.86) drives."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::host-ssr)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :hicasso.ssr/title (fn [db _] (:title db)))

(rf/reg-event :hicasso.ssr/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- skip! [why] (is true (str "an SSR-policy DOM claim needs a real React DOM — " why)))

(defn- fresh! []
  (support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.ssr/seed]))
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

(h/defhost bare-chart
  "No `:ssr` written at all — the default is what an author gets."
  chart)

(h/defhost client-only-chart chart {:ssr :client-only})

(h/defhost fallback-chart chart
  {:ssr {:fallback [:div.chart-skeleton {:data-live "no"} "loading"]}})

;; --- the `:render` fixtures: a provider, and something that reads it -------
;;
;; A context PROVIDER is the shape that filed rf2-l0wfx, and it is the
;; shape that makes the policy legible: it contributes no markup of its
;; own, so under a gate its unadopted arm returns something that is not
;; the component and the ENTIRE subtree leaves the server response with
;; it. It is also the case where the author's assertion is trivially
;; true — a Provider is React's own component and React's server
;; renderer supports context fully.

(def ^:private theme-context (react/createContext "unset"))

(def ^:private !child-mounts
  "How many times the counted child's mount effect ran. One is an
  adoption; two is the destroy-and-rebuild that the type swap under a
  gate would cause. `useEffect` never runs on the server, so a server
  render leaves this alone and the count is purely about the client."
  (atom 0))

(defn- theme-reader
  "A consumer BELOW the provider. The whole discriminator between
  `:ssr :render` and the rejected `:ssr :children`: with the provider
  above it this reads `dark`, and with only the children rendered it
  reads the context DEFAULT."
  [_props]
  (react/createElement "span" #js {:className "theme-reader"}
                       (react/useContext theme-context)))

(defn- counted-reader [props]
  (react/useEffect (fn [] (swap! !child-mounts inc) js/undefined) #js [])
  (theme-reader props))

(h/defhost themed
  "The guide's own provider example, declared the way the ruling says to
  declare it."
  (.-Provider theme-context)
  {:ssr :render})

(h/defhost themed-client-only
  "The SAME provider under the default policy — the control that keeps
  the row below a fact about the policy rather than about the page."
  (.-Provider theme-context))

(h/defhost reader-host theme-reader {:ssr :render})

;; --- the FALSE assertion, and what it costs (rf2-hic-028) -----------------
;;
;; `:render` is an assertion the author makes, and the matrix's closing
;; requirement is what happens when it is FALSE: *a host that renders
;; server-side unsafely fails loudly at the crossing*. The real spelling
;; is a `ReferenceError` reading `window` under a server runtime — which
;; cannot be staged here, because both lanes this suite runs in HAVE a
;; `window` (`:node-test` supplies one, `:browser-test` is one). So the
;; component throws that error itself, with that message, which is the
;; same shape reaching React by the same route; what is under test is
;; the CONDUIT, and nothing about it varies with who constructed the
;; error.

(defn- browser-only
  "The component whose author declared `:render` and was wrong."
  [_props]
  (throw (js/ReferenceError. "window is not defined")))

(h/defhost unsafe-render-host browser-only {:ssr :render})

(h/defhost unsafe-client-only-host browser-only)

(h/defhost counted-reader-host counted-reader {:ssr :render})

;; ---------------------------------------------------------------------------
;; The pages
;; ---------------------------------------------------------------------------

(h/defview client-only-page
  "A native sibling beside the host, so \"the host is absent\" is
  distinguishable from \"nothing rendered at all\"."
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.ssr/title])]
   [client-only-chart {:label (collector/sub [:hicasso.ssr/title])}]])

(h/defview fallback-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.ssr/title])]
   [fallback-chart {:label (collector/sub [:hicasso.ssr/title])}]])

(h/defview slotted-page
  "Children and props still cross the door — the gate forwards its own
  props object, so this is the regression guard for the crossing that
  the gate now sits in front of."
  [_]
  [:div.page
   [fallback-chart {:label (collector/sub [:hicasso.ssr/title])}
    [:span.kid "slotted"]]])

(h/defview provider-page
  "THE PAGE THAT FILED rf2-l0wfx, under the policy that answers it. A
  native sibling above the crossing so \"the subtree is absent\" stays
  distinguishable from \"nothing rendered\", markup inside it so the
  subtree is visible, and a consumer inside it so the context VALUE is
  visible too."
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.ssr/title])]
   [themed {:value "dark"}
    [:p.body "THE ENTIRE APPLICATION"]
    [reader-host {}]]])

(h/defview provider-page-client-only
  "The same page under the default policy — the defect, kept live."
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.ssr/title])]
   [themed-client-only {:value "dark"}
    [:p.body "THE ENTIRE APPLICATION"]
    [reader-host {}]]])

(h/defview unsafe-render-page
  "A page whose one crossing declares `:render` over a component that is
  not server-safe."
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.ssr/title])]
   [unsafe-render-host {}]])

(h/defview unsafe-client-only-page
  "The same component under the DEFAULT policy — the control that makes
  the row below a fact about the assertion rather than about the
  component."
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.ssr/title])]
   [unsafe-client-only-host {}]])

(h/defview unprovided-page
  "The consumer with NO provider above it — what a policy that rendered
  the children alone would have emitted, so the `dark` below is a
  measured contrast and not an assumed one."
  [_]
  [:div.page [reader-host {}]])

(h/defview counted-provider-page
  "[[provider-page]] with a mount-counting consumer, for the hydration
  row. Same shape, one extra effect."
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.ssr/title])]
   [themed {:value "dark"}
    [:p.body "THE ENTIRE APPLICATION"]
    [counted-reader-host {}]]])

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

(deftest the-third-policy-is-render-and-it-is-an-assertion
  (testing "rf2-l0wfx. `:render` is accepted as a bare keyword, alongside
            the two — the author asserting that this component is safe to
            run on the server"
    (is (= :render (codec/host-ssr themed)))
    (is (= :render (codec/host-ssr reader-host))))
  (testing "and it reads back as data like every other policy, so a server
            walk that wants to state what it is honouring still can"
    (is (= :client-only (codec/host-ssr themed-client-only)))
    (is (some? (codec/mint-host! "ssr/render-plus-callbacks" chart
                                 {:callbacks {:on-pick :event} :ssr :render}))
        "and it composes with :callbacks, which is the other option")))

(deftest the-declaration-refuses-a-policy-it-cannot-honour
  (testing "a fourth value is refused at the declaration, naming the three"
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/server" chart {:ssr :server}))))
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/true" chart {:ssr true})))))
  (testing "and the three spellings an author reaches for INSTEAD of
            `:render` stay refused, every one of them (rf2-l0wfx). They
            assert a structural property nobody can check — that the
            component is transparent — and they deliver the subtree under
            the WRONG context value, which turns a silent absence into a
            silent wrongness"
    (doseq [v [:children :transparent :passthrough]]
      (is (= :rf.error/hicasso-host-bad-ssr-policy
             (error-id #(codec/mint-host! (str "ssr/" (name v)) chart {:ssr v})))
          (str (pr-str v) " must stay refused"))))
  (testing "a fallback MAP spelling of it is not a spelling of it either —
            `:render` is a bare keyword, and a policy that admitted both
            would be two ways to say one thing"
    (is (= :rf.error/hicasso-host-bad-ssr-policy
           (error-id #(codec/mint-host! "ssr/render-map" chart
                                        {:ssr {:render true}})))))
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
           (error-id #(codec/mint-host! "ssr/bad-fb" chart {:ssr {:fallback []}})))))
  (testing "as does a `defview` head written into a fallback — a fallback
            is inert markup and that is enforced (rf2-nv07k). The full
            contract, both directions, is
            [[re-frame.hicasso.fallback-contents-cljs-test]]; this row is here because
            the two halves were ruled together and the refusal is one of
            this declaration's own"
    (is (= :rf.error/hicasso-host-fallback-boundary-head
           (error-id #(codec/mint-host! "ssr/live-fb" chart
                                        {:ssr {:fallback [client-only-page {}]}}))))))

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
;; 3 — `:ssr :render`: the component runs, and so do its CHILDREN
;;
;; The defect rf2-l0wfx filed and the ruling that answers it, in one
;; place. Every row here is a `renderToString`, so they run under
;; `:node-test` as well.
;; ---------------------------------------------------------------------------

(deftest a-client-only-provider-deletes-its-subtree-from-the-server-render
  (fresh!)
  (testing "THE DEFECT, kept live (rf2-l0wfx). A provider contributes no
            markup of its own, so under a gate its unadopted arm returns
            something that is not the component and the whole subtree
            leaves the server response with it — silently, because the
            server snapshot and hydration's first client pass agree by
            construction. This row is what `:ssr :render` exists to make
            unnecessary, and it stays green because the DEFAULT is
            unchanged: the door still does not guess"
    (let [html (server-html [provider-page-client-only {}])]
      (is (re-find #"quarterly" html)
          (str "the page DID render — the sibling above the crossing is
                there, so an absent subtree is an absent subtree: " html))
      (is (not (re-find #"THE ENTIRE APPLICATION" html))
          (str "and the provider took its children with it: " html))
      (is (not (re-find #"theme-reader" html))
          (str "including the consumer below it: " html)))))

(deftest a-render-crossing-puts-the-component-and-its-children-on-the-server
  (fresh!)
  (testing "the component itself renders server-side — the assertion the
            declaration makes, honoured"
    (let [html (server-html [provider-page {}])]
      (is (re-find #"quarterly" html) (str "sanity — the page rendered: " html))
      (is (re-find #"THE ENTIRE APPLICATION" html)
          (str "THE SUBTREE IS IN THE SERVER RESPONSE. This is the whole of
                rf2-l0wfx: " html))
      (is (re-find #"class=\"body\"" html)
          (str "as markup, not as stray text: " html))))
  (testing "AND THE CONTEXT VALUE IS THE DECLARED ONE, which is what
            separates this policy from `:ssr :children`. A consumer under
            the provider reads `dark`; the same consumer with no provider
            above it reads the context DEFAULT, and a policy that rendered
            only the children would have emitted exactly that"
    (let [provided   (server-html [provider-page {}])
          unprovided (server-html [unprovided-page {}])]
      (is (re-find #"<span class=\"theme-reader\">dark</span>" provided)
          (str "the consumer read the DECLARED value: " provided))
      (is (re-find #"<span class=\"theme-reader\">unset</span>" unprovided)
          (str "the contrast, measured rather than assumed — with no
                provider above it the same consumer reads the default: "
               unprovided))
      (is (not= provided unprovided)
          "so the two are genuinely distinguishable in the bytes")))
  (testing "and a `:render` host with no children of its own is not a
            special case — the policy is about the TYPE, not about arity"
    (let [html (server-html [unprovided-page {}])]
      (is (re-find #"class=\"theme-reader\"" html)
          (str "a childless `:render` crossing rendered its component: "
               html)))))

(deftest a-false-render-assertion-fails-loudly-at-the-crossing
  (fresh!)
  (testing "**THE CLOSING ROW OF THE CANONICAL MATRIX** (rf2-hic-028): a
            host that renders server-side unsafely fails LOUDLY, at the
            crossing. `:render` is the author asserting that a component is
            safe on the server, and an assertion nothing checks is only
            worth having if being wrong about it is unmissable — so the
            throw propagates out of `renderToString` rather than being
            swallowed into a hole in the response. A policy that quietly
            emitted a page with the crossing missing would hand the author
            a half-rendered document and no reason for it"
    (let [thrown (try (server-html [unsafe-render-page {}]) ::did-not-throw
                      (catch :default e e))]
      (is (not= ::did-not-throw thrown)
          "the server render failed rather than completing without it")
      (is (re-find #"window is not defined" (ex-message thrown))
          (str "carrying the runtime's own message, unwrapped — the author
                needs the ReferenceError, not a translation of it: "
               (ex-message thrown)))))
  (testing "and the SAME component under the default policy renders a clean
            page, so the failure above belongs to the assertion and not to
            the component. This is the recovery the guide names, measured:
            drop back to Client-only"
    (let [html (server-html [unsafe-client-only-page {}])]
      (is (re-find #"quarterly" html)
          (str "the page rendered whole — which is the proof that the
                component's body never ran, because a body that ran would
                have thrown the same ReferenceError: " html)))))

;; ---------------------------------------------------------------------------
;; 4 — a fresh mount: no placeholder, ever
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
;; 5 — hydration: the first client pass matches, and adoption swaps
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
              (collector/reset-runtime!))))
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

(deftest a-render-crossing-hydrates-once-and-never-remounts
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (reset! !child-mounts 0)
        (hydration-row
          [counted-provider-page {}]
          (fn [container seen html]
            (is (re-find #"THE ENTIRE APPLICATION" html)
                (str "the markup hydrated FROM carries the provider's whole
                      subtree — the row's own restatement of the policy, so
                      a `:render` that stopped rendering is red HERE and not
                      only in the server-render row: " html))
            (is (re-find #"<span class=\"theme-reader\">dark</span>" html)
                (str "under the DECLARED context value: " html))
            (is (empty? seen)
                (str "REACT FOUND NOTHING TO RECONCILE. Server render,
                      hydration's first pass and a fresh mount are one tree
                      under this policy — the same element type, the same
                      props, the same children — so there is zero mismatch
                      by identity rather than by a snapshot pair agreeing: "
                     (pr-str seen)))
            (is (some? (q container ".theme-reader"))
                "the consumer is mounted after hydration")
            (is (= "dark" (.-textContent (q container ".theme-reader")))
                "still reading the declared value on the client")
            (is (some? (q container ".body"))
                "and the subtree around it is the SERVER'S markup, adopted")
            (is (= 1 @!child-mounts)
                (str "**ONE MOUNT.** This is the closing measurement
                      (rf2-l0wfx): the child's effect ran exactly once, so
                      the adopted subtree was never destroyed and rebuilt.
                      Any policy whose unadopted branch returns something
                      other than the component swaps the position's element
                      TYPE at adoption, and React reconciles a position by
                      type — so it would read 2 here. Read "
                     @!child-mounts))
            (done)))))))
