(ns re-frame.hicasso.native-ssr-dom-cljs-test
  "THE NATIVE TIER'S SERVER POLICY, MEASURED AS BYTES (rf2-hic-046).

  `n/defcomponent`'s `:server` declaration used to be VALIDATED and read
  by nothing (rf2-u9lk, rf2-ggnp). Merged-PR audit #7839 named the
  consequence exactly: the landed fence suite server-rendered a
  declaration-less island — nominally Client-only, the conservative
  default — to `<output>42</output>`, while every normative source says a
  Client-only surface is *absent from the server bytes*. A recorded
  policy that nothing consults is not a policy; it is a comment that
  refuses typos.

  So this file's subject is the EFFECT, and never the marker. Every row
  here reads bytes out of React's own server renderer or DOM out of a
  real hydration; `native-fence-cljs-test` keeps the declaration rows,
  which are a different claim.

  ## The two policies, and the one mechanism

  | Declaration | Server bytes | Fresh client mount |
  |---|---|---|
  | omitted, or `{:server :client-only}` | nothing at all | the island, on the FIRST pass |
  | `{:server :render}` | the island | the island, on the first pass |

  One mechanism serves both and it is `defhost`'s
  (`impl.codec/adopted?`): a `useSyncExternalStore` whose SERVER snapshot
  is `false` and whose client snapshot is `true`. React reads the server
  snapshot under `renderToString` and again on hydration's first client
  pass, so the server bytes and the first client pass agree BY
  CONSTRUCTION and there is nothing to reconcile; a `createRoot` mount
  consults no server snapshot at all, so nothing ever flashes. Under
  `:render` there is no gate — the island IS the element type, one tree
  everywhere, no snapshot pair and NO REMOUNT.

  ## Which surfaces this file dispositions

  HS-24 (`n/$` intrinsic), HS-25 (component-headed `n/$`), HS-26
  (`n/props`), HS-27 (`n/defcomponent`), HS-28 (`n/use-sub`), HS-29
  (`n/use-frame`) and HS-30 (memo/lazy/ref and both embedding
  directions) — the rows
  `docs/design/hicasso/product/dispositions.md` §2.1 owes to this bead.
  HS-19's composition rule is measured beside them, because it is what
  decides whether an island's own declaration is ever consulted: `[:>]`
  is the door with the declaration erased and its crossing is HARD
  Client-only, so a `{:server :render}` island reached that way is still
  absent. Two declarations — the host's and the island's — are what it
  takes to put a native body in a server response, and the innermost
  Client-only wins.

  ## What is deliberately NOT here

  - **`identifierPrefix` across two hydrating roots.** `h/hydrate!` takes
    no root options for it (`impl.mount/hydrate-root!` passes
    `onRecoverableError` and nothing else), so it is not reachable from a
    native island; it belongs to HS-11/HS-14 and those rows stay
    Client-only.
  - **Two overlapping roots' mismatch ATTRIBUTION.** Witnessed already,
    per root and against React's own count, in
    `re-frame.hicasso.roots-frames-hydration-dom-cljs-test`. What is
    native-specific is that two roots under OPPOSITE policies do not
    interfere, and that is the row below.
  - **A Hicasso server entry.** There is none, and none is needed: the
    consumer calls `react-dom/server` and the policy is honoured by
    rendering. The harness here is `renderToString` called by hand, which
    is the only server path in the package (rf2-ggnp's census, re-run).

  ## The mutation witnesses, one per assertion class

  Make `component` ignore the policy — return `f` for both arms, which is
  the pre-rf2-hic-046 behaviour audit #7839 found — and
  [[a-client-only-island-is-absent-from-the-server-bytes]] goes red on
  the island's markup being present and on its body having run. Make the
  gate render its component unconditionally — `adopted?` replaced by
  `true` — and the same row goes red while
  [[a-render-island-is-in-the-server-bytes-and-hydrates-them]] stays
  green, because a `:render` island has no gate to break.

  Then the other direction, which is the one a green cannot see: widen
  the gate to `:render` as well — every island gated — and
  [[a-render-island-is-in-the-server-bytes-and-hydrates-them]] goes red
  on the subtree missing from the bytes, and
  [[a-render-island-hydrates-once-and-never-remounts]] on the second
  mount the adoption type-swap causes.
  [[a-client-only-island-hydrates-with-nothing-there-and-mounts-after-adoption]]
  is the row that separates *gated correctly* from *never rendered at
  all*: it reads React's own mismatch channels, so a gate whose two
  snapshots disagreed is red there and nowhere else.

  Runtime: `-dom-cljs-test`, so `:browser-test` decides the hydration
  claims against a real React DOM. The `renderToString` rows need no DOM
  and run under `:node-test` too; the DOM rows say so and skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::native-ssr)
(def ^:private other-frame-id ::native-ssr-other)

(rf/reg-sub :hicasso.native-ssr/title (fn [db _] (:title db)))

;; A SECOND key over the same db value, read only by the island. The page
;; around it reads `:title` through an ordinary boundary, so a shared key
;; would put two readers on one cell and the acquisition rows below could
;; not say WHICH half acquired. Same value, so every markup row is
;; unchanged by the split.
(rf/reg-sub :hicasso.native-ssr/island-title (fn [db _] (:title db)))

(rf/reg-event :hicasso.native-ssr/seed (fn [_ _] {:db {:title "quarterly"}}))
(rf/reg-event :hicasso.native-ssr/retitle
              (fn [{:keys [db]} [_ t]] {:db (assoc db :title t)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "a native-tier hydration claim needs a real React DOM — " why)))

(defn- fresh!
  ([] (fresh! frame-id))
  ([kw]
   (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
   (rf/make-frame {:id kw})
   (rf/with-frame kw (rf/dispatch-sync [:hicasso.native-ssr/seed]))
   kw))

;; ---------------------------------------------------------------------------
;; The islands
;; ---------------------------------------------------------------------------

(def ^:private !runs
  "How many times an island's body ran. The server rows' strongest
  assertion: a Client-only island is not merely absent from the bytes,
  its body was never INVOKED — which is the property an author relies on
  when the reason they wrote nothing is that the island reaches for
  `window`."
  (atom 0))

(def ^:private !mounts
  "How many times the counted island's mount effect ran. ONE is an
  adoption; TWO is the destroy-and-rebuild an element-type swap causes.
  `useEffect` never runs on the server, so this is purely about the
  client."
  (atom 0))

(n/defcomponent plain-island
  "NO declaration map — Client-only by default, which is the case audit
  #7839 measured rendering into a server response."
  [^js props]
  (swap! !runs inc)
  (n/$ :div #js {"className" "island" "data-live" "yes"}
       (n/$ :span #js {"className" "island-label"} (.-label props))
       (.-children props)))

(n/defcomponent declared-client-island
  "The same policy, said. An author who writes the default explicitly
  must get the default."
  {:server :client-only}
  [^js props]
  (swap! !runs inc)
  (n/$ :div #js {"className" "island" "data-live" "yes"}
       (n/$ :span #js {"className" "island-label"} (.-label props))))

(n/defcomponent render-island
  "`{:server :render}` — the author asserting this island is safe on the
  server, which for a pure display island it trivially is."
  {:server :render}
  [^js props]
  (swap! !runs inc)
  (n/$ :em #js {"className" "server-island"} (.-label props)))

(n/defcomponent counted-render-island
  "[[render-island]] with a mount effect, for the remount row."
  {:server :render}
  [^js props]
  (react/useEffect (fn [] (swap! !mounts inc) js/undefined) #js [])
  (n/$ :em #js {"className" "server-island"} (.-label props)))

(n/defcomponent reading-island
  "HS-28. `n/use-sub` inside a `:render` island: the read has to answer
  during a server render, from the same `useSyncExternalStore` a boundary
  reading this key would take, or the policy is unusable for the islands
  that have a reason to be islands."
  {:server :render}
  [^js _props]
  (n/$ :b #js {"className" "island-read"}
       (n/use-sub [:hicasso.native-ssr/island-title])))

(n/defcomponent framed-island
  "HS-29. `n/use-frame` under the same policy — the ops bundle for the
  frame this island is mounted in, resolved through React's own
  `useContext` and therefore answerable by the server renderer."
  {:server :render}
  [^js _props]
  (n/$ :i #js {"className" "island-frame"} (str (:frame (n/use-frame)))))

(n/defcomponent unsafe-island
  "The author who declared `:render` and was wrong. The real spelling is
  a `ReferenceError` reading `window` under a server runtime, which
  cannot be staged in either of this suite's lanes because both HAVE a
  `window` — so the body throws that error itself, by the same route."
  {:server :render}
  [^js _props]
  (throw (js/ReferenceError. "window is not defined")))

(n/defcomponent unsafe-client-island
  "The SAME body under the default policy — the control that makes the
  row above a fact about the assertion rather than about the island."
  [^js _props]
  (throw (js/ReferenceError. "window is not defined")))

;; --- HS-30: the ABI helpers carry the policy across ------------------------

(def ^:private memoised-client (n/memo plain-island))
(def ^:private memoised-render (n/memo render-island))

;; ---------------------------------------------------------------------------
;; The crossings — and WHY THEY ARE `defhost` AND NOT `[:>]`
;; ---------------------------------------------------------------------------
;;
;; A native island is a foreign React component to the interpreted tier,
;; so it enters through the seams that already exist. The two are not
;; interchangeable HERE, and the difference is the subject of a row
;; below:
;;
;;   - `[:>]` is `defhost` with the declaration erased, so it carries no
;;     `:server` policy and the crossing is HARD Client-only through one
;;     shared `raw-gate` (`impl.codec`, the `[:>]` section). An island
;;     reached that way contributes nothing to a server response whatever
;;     it declares — the OUTER policy decides first, and there is none to
;;     decide with.
;;   - `h/defhost` carries one. So a `{:server :render}` host over a
;;     `{:server :render}` island is the only spelling under which an
;;     island's own body reaches the server bytes from a hiccup page, and
;;     the two policies compose the conservative way: the innermost
;;     Client-only wins.
;;
;; That composition is dispositions.md §2.1 note 2 in code — *HS-19 is
;; Client-only until classified by an enclosing view or host policy* —
;; and it is why every `:render` page below is declared twice.

(h/defhost plain-host plain-island {:server :render})
(h/defhost declared-client-host declared-client-island {:server :render})
(h/defhost render-host render-island {:server :render})
(h/defhost counted-render-host counted-render-island {:server :render})
(h/defhost reading-host reading-island {:server :render})
(h/defhost framed-host framed-island {:server :render})
(h/defhost memoised-client-host memoised-client {:server :render})
(h/defhost memoised-render-host memoised-render {:server :render})
(h/defhost unsafe-host unsafe-island {:server :render})
(h/defhost unsafe-client-host unsafe-client-island {:server :render})

;; ---------------------------------------------------------------------------
;; The pages — a hiccup sibling beside every island, so "the island is
;; absent" stays distinguishable from "nothing rendered at all"
;; ---------------------------------------------------------------------------

(h/defview plain-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [plain-host {:label (collector/sub [:hicasso.native-ssr/title])}]])

(h/defview declared-client-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [declared-client-host {:label (collector/sub [:hicasso.native-ssr/title])}]])

(h/defview render-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [render-host {:label (collector/sub [:hicasso.native-ssr/title])}]])

(h/defview escaped-render-page
  "The SAME `:render` island, reached through the raw escape instead. The
  crossing has no declaration, so it is hard Client-only and the island
  never gets a say."
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [:> render-island {:label (collector/sub [:hicasso.native-ssr/title])}]])

(h/defview counted-render-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [counted-render-host {:label (collector/sub [:hicasso.native-ssr/title])}]])

(h/defview reading-page
  "The page the hydration rows use, so one adoption covers the reading
  island (HS-28), the frame island (HS-29) and both intrinsic forms
  (HS-24, HS-26) rather than leaving three of them witnessed on the
  server side only."
  [_]
  (let [dynamic {:class "intrinsic-dynamic" :data-kind "dyn"}]
    [:div.page
     [:h1.title (collector/sub [:hicasso.native-ssr/title])]
     (n/$ :p #js {"className" "intrinsic-literal"} "literal")
     (n/$ :p (n/props dynamic) "dynamic")
     [reading-host {}]
     [framed-host {}]]))

(h/defview framed-page
  [_]
  [:div.page [framed-host {}]])

(h/defview memoised-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [memoised-client-host {:label "memo-client"}]
   [memoised-render-host {:label "memo-render"}]])

(h/defview unsafe-render-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [unsafe-host {}]])

(h/defview unsafe-client-page
  [_]
  [:div.page
   [:h1.title (collector/sub [:hicasso.native-ssr/title])]
   [unsafe-client-host {}]])

(h/defview slotted-page
  "Props and children still cross the gate — the regression guard for the
  crossing the gate now sits in front of."
  [_]
  [:div.page
   [plain-host {:label (collector/sub [:hicasso.native-ssr/title])}
    [:span.kid "slotted"]]])

(h/defview intrinsic-page
  "HS-24 and HS-26 in one body: an intrinsic `n/$` head with a literal
  props map, and the same head with a DYNAMIC map through the `n/props`
  marker. Neither is a component, so neither has a declaration and
  neither can be gated — past DCE `n/$` is `createElement`, and the
  policy question does not arise."
  [_]
  (let [dynamic {:class "intrinsic-dynamic" :data-kind "dyn"}]
    [:div.page
     (n/$ :p #js {"className" "intrinsic-literal"} "literal")
     (n/$ :p (n/props dynamic) "dynamic")]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- server-html
  "The page as a REAL server render. `react-dom/server`'s own
  `renderToString`, called by hand — which per rf2-ggnp's census is the
  only server path this package has, and is exactly the call a consumer
  makes."
  ([hiccup] (server-html frame-id hiccup))
  ([kw hiccup]
   (react-dom-server/renderToString
     (mount/provider kw (codec/root-element kw hiccup)))))

(defn- render-native!
  "Server-render a native element with NO Hicasso around it — the tier on
  its own, which is where the component-headed `n/$` form (HS-25) has to
  be measured. `native-fence-cljs-test`'s harness of the same name, kept
  in step deliberately: that file's rows are the fence's and these are
  the policy's, and a divergence between the two harnesses would make
  them incomparable."
  [element]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (react-dom-server/renderToStaticMarkup element))

(defn- q [root sel] (.querySelector root sel))

(defn- watch-errors!
  "Everything React has to say about a hydration, from all three channels
  it uses. `console.error` carries the mismatch diff, `onRecoverableError`
  the recovery, and a `window` error listener is here because React routes
  a throw during a commit to `reportError`, where `cljs.test` cannot see
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

(defn- hydration-row
  "Bake the page on the server, hydrate THOSE BYTES, and answer what
  React reported — plus the HTML it hydrated from, because a row that
  reads only the settled DOM cannot tell a policy that worked from a
  policy that was never applied on either side.

  The server nodes are stamped with an EXPANDO before hydration
  (`sup/stamp-server-nodes!`), which does not round-trip through
  `innerHTML`, so a node still answering to it afterwards is the very
  node the server markup produced rather than a replacement that looks
  alike. Adopted and re-created are indistinguishable in the markup and
  opposites here."
  [hiccup after]
  (fresh!)
  (reset! !runs 0)
  (let [html      (server-html hiccup)
        container (sup/server-dom! html)
        _         (sup/stamp-server-nodes! container)
        {:keys [seen restore]} (watch-errors!)
        root      (react-dom-client/hydrateRoot
                    container
                    (mount/provider frame-id (codec/root-element frame-id hiccup))
                    #js {:onRecoverableError
                         (fn [err _info]
                           (swap! seen conj (str "onRecoverableError: "
                                                 (ex-message err))))})]
    (js/setTimeout
      (fn []
        (try
          (after container @seen html)
          (finally
            (restore)
            (.unmount root)
            (when-some [p (.-parentNode container)] (.removeChild p container))
            (collector/reset-runtime!))))
      150)))

;; ---------------------------------------------------------------------------
;; 0 — the tier on its own (no DOM; runs under :node-test)
;; ---------------------------------------------------------------------------

(deftest the-policy-decides-a-bare-component-headed-form
  (testing "HS-25, measured where it lives: a component-headed `n/$` with
            no Hicasso and no host anywhere near it. An undeclared island
            contributes NOTHING and its body does not run. Narrowing
            caught: the shipped behaviour audit #7839 found — this exact
            call rendering `<output>42</output>` for a nominally
            Client-only island"
    (reset! !runs 0)
    (is (= "" (render-native! (n/$ plain-island #js {"label" "L"})))
        "the Client-only island produced no bytes at all")
    (is (zero? @!runs) "and its body never ran"))

  (testing "while a `{:server :render}` island produces its own markup
            through the same call — so the row above is about the POLICY
            and not about the harness"
    (reset! !runs 0)
    (is (= "<em class=\"server-island\">L</em>"
           (render-native! (n/$ render-island #js {"label" "L"})))
        "the declared island rendered")
    (is (pos? @!runs) "and its body ran"))

  (testing "a Client-only island's CHILDREN go with it, exactly as a
            Client-only `defhost` crossing's do — the gate is the element
            type, so nothing below it is reached. That is the cost of the
            conservative default, stated where it can be seen"
    (is (= "" (render-native! (n/$ plain-island #js {"label" "L"}
                                   (n/$ :span nil "kid"))))
        "the child is not in the bytes either")))

;; ---------------------------------------------------------------------------
;; 1 — the server render honours the policy (no DOM; runs under :node-test)
;; ---------------------------------------------------------------------------

(deftest a-client-only-island-is-absent-from-the-server-bytes
  (fresh!)
  (testing "**THE ROW AUDIT #7839 ASKED FOR.** An island with no
            declaration map is Client-only, and Client-only means absent
            from the response — not 'recorded as absent'. Narrowing
            caught: `component` answering the author's fn for both arms,
            which is what shipped and what rendered a nominally
            Client-only island into every server page"
    (reset! !runs 0)
    (let [html (server-html [plain-page {}])]
      (is (re-find #"quarterly" html)
          (str "the page rendered — the hiccup sibling is there, so an "
               "absent island is an absent island and not an empty page: "
               html))
      (is (not (re-find #"class=\"island\"" html))
          (str "and the island region rendered NOTHING: " html))
      (is (zero? @!runs)
          "the island's body never ran on the server, which is the whole
           of what an author who declares nothing is relying on")))

  (testing "and an EXPLICIT `{:server :client-only}` is the same policy,
            said — the default and the declaration cannot differ"
    (reset! !runs 0)
    (let [html (server-html [declared-client-page {}])]
      (is (not (re-find #"class=\"island\"" html))
          (str "the declared Client-only island is absent too: " html))
      (is (zero? @!runs) "and its body never ran either")))

  (testing "there is NO fallback door here, and the absence is the whole
            contract (`n/declared-server`'s roster): a Client-only region
            is genuinely empty, and an author who wants markup in it
            writes it in the enclosing hiccup, where it is ordinary
            Hicasso"
    (let [html (server-html [plain-page {}])]
      (is (not (re-find #"island-label" html))
          (str "no placeholder, no skeleton, nothing: " html)))))

(deftest a-render-island-is-in-the-server-bytes-and-hydrates-them
  (fresh!)
  (testing "`{:server :render}` is the author's assertion, honoured: the
            island IS the element type, so it renders into the response.
            Narrowing caught: routing `:render` through the gate like the
            other arm, which would empty the region"
    (reset! !runs 0)
    (let [html (server-html [render-page {}])]
      (is (re-find #"class=\"server-island\"" html)
          (str "the island is in the server bytes: " html))
      (is (re-find #"quarterly" html)
          (str "carrying the prop it was handed, as markup rather than as
                stray text: " html))
      (is (pos? @!runs) "and its body DID run on the server")))

  (testing "the two policies are distinguishable in the bytes themselves,
            which is what makes either row a measurement rather than a
            reading of the same page twice"
    (is (not= (server-html [plain-page {}]) (server-html [render-page {}])))))

(deftest the-two-policies-compose-and-the-conservative-one-wins
  (fresh!)
  (testing "HS-19 and HS-25 together. Reached through the RAW ESCAPE the
            same `:render` island is absent, because `[:>]` is `defhost`
            with the declaration erased and its one shared `raw-gate` is
            hard Client-only — the outer crossing decides first and has
            no policy to decide with. Narrowing caught: a native tier that
            reached around the escape's gate to honour its own
            declaration, which would make `[:>]`'s stated policy a lie"
    (let [html (server-html [escaped-render-page {}])]
      (is (re-find #"quarterly" html)
          (str "the page rendered: " html))
      (is (not (re-find #"class=\"server-island\"" html))
          (str "and the `:render` island did not, because the escape it
                crossed carries no policy: " html))))

  (testing "and the other order: a `{:server :render}` HOST over a
            Client-only island is absent too — the host's policy admits
            the crossing and the island's own gate withholds the body. Two
            declarations, and it takes both to reach the server bytes"
    (reset! !runs 0)
    (let [html (server-html [plain-page {}])]
      (is (not (re-find #"class=\"island\"" html))
          (str "the innermost Client-only won: " html))
      (is (zero? @!runs) "and the island's body never ran"))))

(deftest the-native-hooks-answer-during-a-server-render
  (fresh!)
  (testing "HS-28. `n/use-sub` reads through `useSyncExternalStore` with
            the entry's own snapshot as the SERVER snapshot, and
            `collector/resolve-frame!` takes the frame from
            `react/useContext` — React's own dispatcher, not a
            `_currentValue` slot read — so both answer under
            `react-dom/server`. Narrowing caught: resolving the frame by
            reading the context object's client slot, which is
            renderer-specific and refuses server-side (rf2-5rqn)"
    (let [html (server-html [reading-page {}])]
      (is (re-find #"<b class=\"island-read\">quarterly</b>" html)
          (str "the island read the store during the server render: " html))))

  (testing "and the value tracks the request's own snapshot rather than a
            constant — the bytes differ when the frame's db does, which is
            what 'deterministic bytes from an immutable request snapshot'
            reduces to for a read"
    (rf/with-frame frame-id
      (rf/dispatch-sync [:hicasso.native-ssr/retitle "annual"]))
    (is (re-find #"<b class=\"island-read\">annual</b>" (server-html [reading-page {}]))
        "the second render carries the second value"))

  (testing "HS-29. `n/use-frame` resolves the same context and answers the
            frame this island is mounted in"
    (is (re-find (re-pattern (str "<i class=\"island-frame\">" frame-id "</i>"))
                 (server-html [framed-page {}]))
        "the ops bundle named this island's own frame, fully qualified")))

(deftest a-server-render-acquires-no-subscription-to-release
  (fresh!)
  (testing "§2.4's extra obligation for a row that READS: no duplicate
            acquisition. A server render never commits, so React never
            calls `subscribe` — the read takes the snapshot and nothing
            registers. Narrowing caught: an island that subscribed during
            render, which on a server has no unmount to release it and
            would leak one registration per request"
    (collector/reset-runtime!)
    (is (empty? (sup/cell-keys)) "the runtime starts empty")
    (server-html [reading-page {}])
    (is (zero? (sup/readers-of [frame-id [:hicasso.native-ssr/island-title]]))
        (str "the server render left no reader behind; cells: "
             (pr-str (sup/cell-keys))))
    (server-html [reading-page {}])
    (is (zero? (sup/readers-of [frame-id [:hicasso.native-ssr/island-title]]))
        "and a second request did not accumulate one either")))

(deftest the-abi-helpers-carry-the-policy-rather-than-losing-it
  (fresh!)
  (testing "HS-30. `n/memo` copies the tier marker onto the memo record,
            and the marker is stamped on whichever element type
            `component` answered — so a memoised Client-only island is
            still gated and a memoised `:render` island still renders.
            Narrowing caught: stamping the marker on the author's fn and
            memoising the gate, which would report a policy the type does
            not have"
    (let [html (server-html [memoised-page {}])]
      (is (re-find #"memo-render" html)
          (str "the memoised `:render` island is in the bytes: " html))
      (is (not (re-find #"memo-client" html))
          (str "and the memoised Client-only island is not: " html))))

  (testing "and both memo records still report their island's policy, so a
            tool reading the marker and the renderer reading the type
            cannot disagree"
    (is (= "render" (.-server (n/marker memoised-render))))
    (is (= "client-only" (.-server (n/marker memoised-client)))))

  (testing "`n/lazy` is Client-only and it is not the inner island's to
            override — the server never sent the chunk, so no declaration
            can make bytes exist"
    (is (= "client-only" (.-server (n/marker (n/lazy #(js/Promise.resolve render-island)))))))

  (testing "a gated island still carries the tier marker and its own
            display name, so every seam that recognises a native head
            keeps recognising one"
    (is (some? (n/marker plain-island)))
    (is (= "re-frame.hicasso.native-ssr-dom-cljs-test/plain-island"
           (.-displayName plain-island)))
    (is (= "client-only" (.-server (n/marker plain-island))))))

(deftest the-intrinsic-forms-render-on-the-server-unconditionally
  (fresh!)
  (testing "HS-24 and HS-26. An intrinsic `n/$` head is `createElement`
            and a props marker emits no component, so neither carries a
            policy and neither can be withheld. Both are in the bytes,
            with the canonical slot names the shared rule lowers"
    (let [html (server-html [intrinsic-page {}])]
      (is (re-find #"<p class=\"intrinsic-literal\">literal</p>" html)
          (str "the literal-props form: " html))
      (is (re-find #"<p class=\"intrinsic-dynamic\" data-kind=\"dyn\">dynamic</p>" html)
          (str "and the `n/props` form, lowered by the same rule at
                runtime that the macro applies at expansion: " html)))))

(deftest a-false-render-assertion-fails-loudly-at-the-island
  (fresh!)
  (testing "`:render` is an assertion nothing can check, and an assertion
            whose falsification is silent is not worth making. So a throw
            propagates out of `renderToString` rather than leaving a hole
            in the response"
    (let [thrown (try (server-html [unsafe-render-page {}]) ::did-not-throw
                      (catch :default e e))]
      (is (not= ::did-not-throw thrown)
          "the server render failed rather than completing without it")
      (is (re-find #"window is not defined" (ex-message thrown))
          (str "carrying the runtime's own message, unwrapped: "
               (ex-message thrown)))))

  (testing "and the SAME body under the default policy renders a clean
            page — which is both the recovery the guide names and the
            proof that the body never ran, because a body that ran would
            have thrown the same error"
    (let [html (server-html [unsafe-client-page {}])]
      (is (re-find #"quarterly" html)
          (str "the page rendered whole: " html)))))

;; ---------------------------------------------------------------------------
;; 2 — a fresh mount: no flash, ever (DOM)
;; ---------------------------------------------------------------------------

(deftest a-fresh-mount-renders-the-island-on-its-first-pass
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (testing "a `createRoot` mount consults no server snapshot, so the
                Client-only policy costs no placeholder pass at all —
                asserted on the line after `root!` returns, which is
                inside its own `flushSync`. Narrowing caught: a gate whose
                client snapshot started `false`, which would render the
                island one pass late and flash on every mount"
        (let [a (mount/root! (mount/fresh-container!) frame-id [plain-page {}])]
          (try
            (is (some? (q (:container a) ".island"))
                "the Client-only island mounted immediately")
            (is (= "yes" (.getAttribute (q (:container a) ".island") "data-live"))
                "and it is the real one")
            (finally (mount/release! a))))
        (let [b (mount/root! (mount/fresh-container!) frame-id [render-page {}])]
          (try
            (is (some? (q (:container b) ".server-island"))
                "and so did the `:render` island, which has no gate at all")
            (finally (mount/release! b))))))))

(deftest the-gate-forwards-props-and-children-untouched
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (testing "the gate hands its own props straight through, so the ABI
                a Client-only island sees is the ABI a `:render` island
                sees: one raw JavaScript props object, children at
                `.-children`. Narrowing caught: a gate that rebuilt props,
                which would drop `ref` and children"
        (let [h (mount/root! (mount/fresh-container!) frame-id [slotted-page {}])]
          (try
            (is (= "quarterly" (.-textContent (q (:container h) ".island-label")))
                "a declared prop reached the island")
            (is (some? (q (:container h) ".island .kid"))
                "and so did the children, in the island's own slot")
            (finally (mount/release! h))))))))

;; ---------------------------------------------------------------------------
;; 3 — hydration (DOM)
;; ---------------------------------------------------------------------------

(deftest a-client-only-island-hydrates-with-nothing-there-and-mounts-after-adoption
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (hydration-row
        [plain-page {}]
        (fn [container seen html]
          (is (not (re-find #"class=\"island\"" html))
              (str "the markup hydrated FROM has no island in it — the
                    row's own restatement of the policy, so a gate that
                    stopped gating is red HERE and not only in the
                    server-render row: " html))
          (is (empty? seen)
              (str "**REACT FOUND NOTHING TO RECONCILE.** The client's
                    first pass rendered what the server did — nothing — so
                    the two agreed by construction rather than by luck: "
                   (pr-str seen)))
          (is (some? (q container ".island"))
              "and after adoption the island is mounted")
          (is (some? (q container ".title"))
              "with the server's own markup still around it")
          (is (sup/every-server-node? container ".title")
              "and it is the SERVER'S node, still carrying the expando —
               adoption, not a re-render of the page")
          (is (pos? @!runs)
              "the island's body ran on the client, and only on the client")
          (done))))))

(deftest a-render-island-hydrates-once-and-never-remounts
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (reset! !mounts 0)
        (hydration-row
          [counted-render-page {}]
          (fn [container seen html]
            (is (re-find #"class=\"server-island\"" html)
                (str "the markup hydrated FROM carries the island — so a
                      `:render` that stopped rendering is red HERE too: "
                     html))
            (is (empty? seen)
                (str "**BYTE-COMPATIBLE, AND SILENT.** Server render,
                      hydration's first pass and a fresh mount are one
                      tree under this policy — same element type, same
                      props, same children — so there is zero mismatch by
                      identity: " (pr-str seen)))
            (is (some? (q container ".server-island"))
                "the island is mounted after hydration")
            (is (sup/every-server-node? container ".server-island")
                "and it is the SERVER'S OWN NODE, adopted rather than
                 replaced — the expando does not survive `innerHTML`, so
                 a re-created node fails this and looks identical")
            (is (= 1 @!mounts)
                (str "**ONE MOUNT.** The closing measurement: the island's
                      effect ran exactly once, so the adopted subtree was
                      never destroyed and rebuilt. Any policy whose
                      unadopted branch returns something other than the
                      island swaps the position's element TYPE at
                      adoption, and React reconciles a position by type —
                      so it would read 2 here. Read " @!mounts))
            (done)))))))

(deftest two-roots-under-opposite-policies-do-not-interfere
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (fresh! other-frame-id)
        (reset! !runs 0)
        (let [html-a (server-html frame-id [plain-page {}])
              html-b (server-html other-frame-id [render-page {}])
              ca     (sup/stamp-server-nodes! (sup/server-dom! html-a))
              cb     (sup/stamp-server-nodes! (sup/server-dom! html-b))
              {:keys [seen restore]} (watch-errors!)
              ra     (react-dom-client/hydrateRoot
                       ca (mount/provider frame-id
                                          (codec/root-element frame-id [plain-page {}])))
              rb     (react-dom-client/hydrateRoot
                       cb (mount/provider other-frame-id
                                          (codec/root-element other-frame-id
                                                              [render-page {}])))]
          (js/setTimeout
            (fn []
              (try
                (testing "two roots hydrate at once under OPPOSITE
                          policies and neither leaks its answer into the
                          other. The gate is a per-fiber hook, not a
                          page-wide flag, so root A's Client-only island
                          arrives after ITS adoption and root B's
                          `:render` island was in ITS bytes all along.
                          Narrowing caught: a module-level adoption
                          boolean, which is the defect rf2-6tmu found one
                          tier up"
                  (is (not (re-find #"class=\"island\"" html-a))
                      (str "root A's bytes withheld its island: " html-a))
                  (is (re-find #"class=\"server-island\"" html-b)
                      (str "root B's bytes carried its island: " html-b))
                  (is (empty? @seen)
                      (str "and neither hydration had anything to
                            reconcile: " (pr-str @seen)))
                  (is (some? (q ca ".island"))
                      "root A's island is mounted after adoption")
                  (is (some? (q cb ".server-island"))
                      "root B's island is still there")
                  (is (sup/every-server-node? cb ".server-island")
                      "as the server's own node"))
                (finally
                  (restore)
                  (.unmount ra)
                  (.unmount rb)
                  (doseq [c [ca cb]]
                    (when-some [p (.-parentNode c)] (.removeChild p c)))
                  (collector/reset-runtime!)
                  (done))))
            200))))))

(deftest a-deliberate-mismatch-inside-an-island-is-attributed-to-source
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html [reading-page {}])
              container (sup/server-dom! html)
              {:keys [seen stop!]}      (sup/watch-mismatches!)
              ;; MANUFACTURED here and asserted on here — the only shape of
              ;; call site at which swallowing an uncaught error is not the
              ;; fail-open rf2-mwx08 forbids.
              {:keys [captured close!]} (sup/open-console-capture!
                                          {:swallow-uncaught? true})]
          ;; The request the client renders is not the request the server
          ;; rendered. A `:render` island reads the store, so this diverges
          ;; INSIDE the island rather than in the hiccup around it.
          (rf/with-frame frame-id
            (rf/dispatch-sync [:hicasso.native-ssr/retitle "annual"]))
          (let [handle (mount/hydrate-root! container frame-id [reading-page {}])]
            (js/setTimeout
              (fn []
                (close!)
                (stop!)
                (try
                  (testing "§2.4's third clause, for a native island: a
                            deliberate divergence is DETECTED and ATTRIBUTED.
                            The island renders on the server under `:render`,
                            so its bytes are the ones that can diverge — and
                            they do, through the product door
                            (`impl.mount/hydrate-root!`), not a hand-built
                            `hydrateRoot`. Narrowing caught: a policy under
                            which the island never reached the server at all,
                            which would produce NO mismatch and read as a
                            clean hydration"
                    (is (re-find #"quarterly" html)
                        (str "the server bytes carried the first request's
                              value: " html))
                    (is (seq (filterv #(re-find #"Hydration failed" %) @captured))
                        (str "React itself complained: " (pr-str @captured)))
                    (is (= 1 (count @seen))
                        (str "and the framework's own Spec 011 diagnostic fired
                              exactly once for this one root; got "
                             (pr-str (mapv (comp :error sup/tags-of) @seen))))
                    (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                           (:where (sup/tags-of (first @seen))))
                        "tier-discriminated by the door that owns the adoption")
                    (is (= :warned-and-replaced
                           (:recovery (sup/tags-of (first @seen))))
                        "with the recovery React had already performed")
                    (is (= "annual" (.-textContent (q container ".island-read")))
                        "and the repaired DOM carries the CLIENT's value, which
                         is what 'warned and replaced' means"))
                  (finally
                    (mount/release! handle)
                    (collector/reset-runtime!)
                    (done))))
              300)))))))

(deftest a-hydrated-island-releases-exactly-what-it-acquired
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (hydration-row
        [reading-page {}]
        (fn [container _seen _html]
          (is (some? (q container ".island-read"))
              "the reading island is mounted")
          (is (= 1 (sup/readers-of [frame-id [:hicasso.native-ssr/island-title]]))
              (str "exactly ONE reader on the ISLAND'S OWN key after
                    adoption — the server render registered none and the
                    client registered one, so the two halves did not both
                    acquire; cells: " (pr-str (sup/cell-keys))))
          (done))))))

(deftest a-native-read-is-released-on-unmount
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (collector/reset-runtime!)
      (testing "§2.4's last clause: exact cleanup. A mounted island holds
                one reader on its own key and the teardown releases it —
                `useSyncExternalStore`'s unsubscribe is the release, so a
                gate that swallowed the island's unmount, or a policy that
                left the body mounted behind a stale snapshot, would leave
                the count at one. Narrowing caught: releasing on the FRAME
                rather than on the subscription, which passes while one
                root is up and leaks under two"
        (let [h (mount/root! (mount/fresh-container!) frame-id [reading-page {}])]
          (is (= 1 (sup/readers-of [frame-id [:hicasso.native-ssr/island-title]]))
              (str "one reader while mounted; cells: "
                   (pr-str (sup/cell-keys))))
          (mount/release! h)
          (is (zero? (sup/readers-of [frame-id [:hicasso.native-ssr/island-title]]))
              (str "and none after teardown; cells: "
                   (pr-str (sup/cell-keys)))))))))
