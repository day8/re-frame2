(ns re-frame.hicasso.identifier-prefix-ssr-dom-cljs-test
  "`identifierPrefix` ACROSS THE SEAM — the clause dispositions.md §2.4
  asks for and no witness could reach until rf2-hic-046 opened the door.

  §2.4's upgrade list requires, of every surface it promotes, *two
  simultaneous hydrating roots with a stable `identifierPrefix`*. That
  clause was unreachable from every per-surface witness in the package,
  and for a source reason rather than an oversight: `hydrate-root!`
  passed `onRecoverableError` and nothing else, so **the prefix was
  unspellable** — there was no argument for it on any door. rf2-hic-046's
  native-tier half measured the obstruction and stopped
  (`native-ssr-dom-cljs-test`, *What is deliberately NOT here*); the
  operator ruled a thin pass-through; this file is the witness that
  ruling exists for.

  ## The claim is AGREEMENT, and presence is not agreement

  Two roots each *carrying* a prefix proves nothing whatever. React
  numbers `useId` per root from the same start, so what a page actually
  needs is two facts at once:

    - **the same prefix on both sides of the seam produces the same
      ids** — the server's bytes and the client that adopts them agree,
      so nothing mismatches and nothing is replaced; and
    - **different prefixes produce different ids** — which is why a page
      with two roots gives them distinct prefixes, and is the reason the
      option exists at all.

  A witness that asserted only the first would stay green under a door
  that ignored the option and let React default both sides; one that
  asserted only the second would stay green under a door that broke
  hydration outright. Both directions are below, and so is the
  near-miss — a client prefix that is legal, spelled, and *different*
  from the server's — because that is the case a page ships by accident
  and it must not pass.

  ## Why the id is TEXT and not an attribute

  Every probe below renders its `useId` as element **text**.
  Attribute-only divergences are outside React's own hydration contract
  and stay outside it here (Spec 011 §Hydration-mismatch detection,
  `impl.mount/emit-hydration-mismatch!`), so an id parked in an `id=`
  attribute would diverge in total silence and the disagreement row
  would be measuring nothing. As text it is a text mismatch, which React
  reports and recovers from — and the recovery is itself the observable.

  ## Why the readings survive a re-render

  Adopted markup and re-created markup are indistinguishable in
  `innerHTML`; that is the trap `roots-frames-support` exists to escape,
  and its answers are used here — the server-node EXPANDO for *this node
  is the one the bytes produced*, and the live trace stream for *the
  framework complained, or did not*.

  There is a second trap peculiar to `useId`, and one row would fall in
  it. After an adoption the probe's text is the SERVER's text whether or
  not the client agrees, because React patches only what differs and a
  witness reading it has no way to tell agreement from an untouched byte.
  So every agreement row **dispatches into the root afterwards** and
  re-reads: the island takes a changing prop, so the re-render is real,
  the body runs again, and a client id that had diverged is patched into
  the DOM at that moment. A reading taken on the far side of a genuine
  client render is a reading of what the CLIENT thinks the id is.

  ## The harness

  The server bytes come from `react-dom/server`'s own `renderToString`,
  called by hand with React's own `identifierPrefix` option — which per
  rf2-ggnp's census is the only server path this package has, and is
  exactly the call a consumer makes. The client half is the product door
  `impl.mount/hydrate-root!` and never `hydrateRoot` directly: what is
  under test is the door's pass-through, so a row that reached past it
  would witness React rather than this arm.

  `roots-frames-support/server-html!` is deliberately NOT the harness
  here. It renders on an ordinary client root and reads `innerHTML`,
  which is the right instrument for markup rows and the wrong one for
  this file — a client root's `useId` is not a server render's, and a
  server claim needs a server renderer.

  Runtime: `-dom-cljs-test`, so `:browser-test` decides every hydration
  claim against a real React DOM. The `renderToString` rows need no DOM
  and run under `:node-test` too; the DOM rows say so and skip.

  Dispositions: this file is what upgrades `dispositions.md` HS-14 (the
  root and frame-provider element, *including `identifierPrefix`*) and
  what discharges HS-11's measured obstruction."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-a ::frame-a)
(def ^:private frame-b ::frame-b)

(def ^:private label-q [::label])

;; Registered ABOVE `use-fixtures` for the reason the sibling multi-root
;; suites give: the reset fixture captures its source-store baseline when
;; the `use-fixtures` form is EVALUATED, so a registration written below
;; it is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-event ::relabel (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; the hydration rows wait on a real clock, and `cljs.test`
     ;; hard-errors on a fn-form fixture in a suite with an async test.
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The probe — a `useId` that reaches the server bytes
;; ---------------------------------------------------------------------------
;;
;; `useId` is a React hook, so it is written where React hooks are
;; written: a native island. And it is declared `{:server :render}` under
;; a `{:server :render}` host, because that pair is the ONLY spelling
;; that puts a native body into a server response — the innermost
;; Client-only wins, and a Client-only island contributes nothing to the
;; bytes for the prefix to be read out of (rf2-hic-046's native half,
;; `native-ssr-dom-cljs-test`).
;;
;; The island is therefore the vehicle and never the subject. What is
;; under test is the ROOT's option; the island is simply the smallest
;; legal thing that makes React's answer to it visible.

(n/defcomponent id-probe
  "One `useId`, rendered as text, beside a prop that changes.

  The prop is not decoration: it is what makes the post-adoption
  re-render REAL. Without it a re-render of the page would re-render a
  host whose props are identical, React would have every right to bail
  out, and the row that re-reads the id after a dispatch would be
  re-reading a body that never ran."
  {:server :render}
  [^js props]
  (n/$ :span #js {"className" "island"}
       (n/$ :b #js {"className" "probe"} (react/useId))
       (n/$ :i #js {"className" "label"} (.-label props))))

(h/defhost id-host id-probe {:server :render})

(h/defview id-page
  "The page both sides render. One subscription read, so the root
  acquires a frame-keyed cell and its commit is observable at all; one
  island, so the page has an id in it."
  [_]
  [:div.page
   [:p.value (h/sub label-q)]
   [id-host {:label (h/sub label-q)}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh! []
  (sup/leave-act-environment!)
  (rf/make-frame {:id frame-a})
  (rf/make-frame {:id frame-b})
  (rf/with-frame frame-a (rf/dispatch-sync [::seed "alpha"]))
  (rf/with-frame frame-b (rf/dispatch-sync [::seed "beta"]))
  (collector/reset-runtime!)
  nil)

(defn- server-html!
  "The bytes an SSR route delivers for `hiccup` under `frame-kw`, with
  React's own `identifierPrefix` — `nil` for a route that names none.

  `react-dom/server`'s `renderToString`, called by hand: the only server
  path this package has (rf2-ggnp's census), and the call the consumer
  makes. The element is the frame provider over the root element, which
  is what `impl.mount` puts a root's tree inside."
  [frame-kw hiccup prefix]
  (react-dom-server/renderToString
    (mount/provider frame-kw (codec/root-element frame-kw hiccup))
    (when prefix #js {"identifierPrefix" prefix})))

(defn- text-in [container sel]
  (some-> (.querySelector container sel) .-textContent))

(defn- probe-id
  "The id this container's probe is displaying."
  [container]
  (text-in container ".probe"))

(defn- id-in-html
  "The id inside `html`, read straight off the bytes.

  A string match rather than a parse, because the server rows run under
  `:node-test` too and there is no `document` there to parse with. What
  it matches is [[id-probe]]'s OWN markup — this file authored
  `<b class=\"probe\">`, so recognising it is reading back what it
  wrote. What it must not assume is the shape of the ID, and it does
  not: the capture is `.*?` and every assertion below compares ids to
  each other rather than to a literal."
  [html]
  (second (re-find #"<b class=\"probe\">(.*?)</b>" html)))

(defn- relabel!
  "Dispatch into `frame-kw` and let the sync-lane notification commit, so
  the reading on the next line is taken after a real client render."
  [frame-kw label]
  (rf/with-frame frame-kw (rf/dispatch-sync [::relabel label]))
  (mount/settle!)
  nil)

;; ---------------------------------------------------------------------------
;; 0 — the SERVER side alone (no DOM; runs under :node-test)
;; ---------------------------------------------------------------------------

(deftest the-server-renderer-honours-the-prefix-and-is-deterministic
  (fresh!)
  (let [a1 (server-html! frame-a [id-page {}] "pfx-a-")
        a2 (server-html! frame-a [id-page {}] "pfx-a-")
        b1 (server-html! frame-a [id-page {}] "pfx-b-")
        none (server-html! frame-a [id-page {}] nil)]

    (testing "the same request rendered twice is the same bytes — §2.4's
              *deterministic server bytes from an immutable request
              snapshot*, for the root and provider element"
      (is (= a1 a2) "two renders of one snapshot produced identical bytes"))

    (testing "the prefix reaches the id React put in those bytes"
      (is (some? (id-in-html a1)) "premise: the island is IN the bytes, so
                                   there is an id to read — a Client-only
                                   island would leave nothing here")
      (is (re-find #"pfx-a-" (id-in-html a1))
          (str "the server id must carry the prefix it was rendered with; got "
               (pr-str (id-in-html a1)))))

    (testing "and a DIFFERENT prefix is a different id — the distinctness
              half, measured on the server where it is unambiguous"
      (is (not= (id-in-html a1) (id-in-html b1))
          (str "two prefixes, two ids; got " (pr-str (id-in-html a1))
               " and " (pr-str (id-in-html b1))))
      (is (re-find #"pfx-b-" (id-in-html b1))))

    (testing "naming no prefix is not an error and not a default of this
              arm's invention — it is React's own unprefixed id"
      (is (some? (id-in-html none)))
      (is (not (re-find #"pfx-a-" (id-in-html none)))
          "an unprefixed render carries no prefix"))))

;; ---------------------------------------------------------------------------
;; 1 — one root: the same prefix on both sides AGREES
;; ---------------------------------------------------------------------------

(deftest a-matching-prefix-hydrates-with-the-server-s-own-id
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no React DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html! frame-a [id-page {}] "pfx-a-")
              server-id (id-in-html html)
              container (sup/stamp-server-nodes! (sup/server-dom! html))
              {:keys [seen stop!]} (sup/watch-mismatches!)]
          (is (some? server-id) "premise: the bytes carry an id")
          (is (= server-id (probe-id container))
              "premise: the page on screen is the server's, before any adoption")
          (collector/reset-runtime!)
          (let [handle (mount/hydrate-root! container frame-a [id-page {}]
                                            {:identifier-prefix "pfx-a-"})]
            (-> (sup/adopted! handle)
                (.then
                  (fn [ok]
                    (try
                      (is (true? ok) "the root's own adoption window shut")

                      (testing "the adoption was real — these are the very
                                nodes the bytes produced, which no re-render
                                could reconstruct"
                        (is (sup/every-server-node? container ".page, .value, .probe")
                            "the server's nodes survived, probe included"))

                      (testing "and React had nothing to complain about"
                        (is (empty? @seen)
                            (str "a matching prefix must produce no hydration "
                                 "mismatch; got " (pr-str (mapv sup/tags-of @seen)))))

                      (testing "the id agrees ACROSS A REAL CLIENT RENDER, which
                                is the only reading that separates *the client
                                minted the same id* from *nobody touched the
                                server's text*"
                        (relabel! frame-a "alpha'")
                        (is (= "alpha'" (text-in container ".value"))
                            "premise: the dispatch painted, so the render happened")
                        (is (= "alpha'" (text-in container ".label"))
                            "premise: the ISLAND re-rendered too — its prop moved")
                        (is (= server-id (probe-id container))
                            (str "the client's own id must equal the server's; "
                                 "server " (pr-str server-id) ", client now "
                                 (pr-str (probe-id container)))))

                      (finally (stop!) (mount/release! handle) (done))))))))))))

;; ---------------------------------------------------------------------------
;; 2 — §2.4's clause, as written: TWO SIMULTANEOUS hydrating roots
;; ---------------------------------------------------------------------------

(deftest two-simultaneous-hydrating-roots-keep-stable-and-distinct-prefixes
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no React DOM") (done))
      (do
        (fresh!)
        (let [html-a    (server-html! frame-a [id-page {}] "pfx-a-")
              html-b    (server-html! frame-b [id-page {}] "pfx-b-")
              server-a  (id-in-html html-a)
              server-b  (id-in-html html-b)
              ca        (sup/stamp-server-nodes! (sup/server-dom! html-a))
              cb        (sup/stamp-server-nodes! (sup/server-dom! html-b))
              {:keys [seen stop!]} (sup/watch-mismatches!)]
          (is (not= server-a server-b)
              (str "premise: two prefixes gave the server two ids; got "
                   (pr-str server-a) " and " (pr-str server-b)))
          (collector/reset-runtime!)
          (let [ha (mount/hydrate-root! ca frame-a [id-page {}]
                                        {:identifier-prefix "pfx-a-"})
                hb (mount/hydrate-root! cb frame-b [id-page {}]
                                        {:identifier-prefix "pfx-b-"})]
            ;; The overlap is a CONSTRUCTION and not a timing guess: both
            ;; roots were handed to React before either had adopted, and
            ;; each root's OWN window being open on this line says so.
            (is (true? (roots/adopting? (:adoption ha)))
                "root A is in flight — `hydrate-root!` returns before adoption")
            (is (true? (roots/adopting? (:adoption hb)))
                "and so is root B, in its own window")
            (-> (sup/adopted! ha)
                (.then (fn [_] (sup/adopted! hb)))
                (.then
                  (fn [ok]
                    (try
                      (is (true? ok) "both roots adopted")

                      (testing "each root adopted its OWN server DOM"
                        (is (sup/every-server-node? ca ".page, .value, .probe")
                            "root A kept the server's nodes")
                        (is (sup/every-server-node? cb ".page, .value, .probe")
                            "root B kept the server's nodes"))

                      (testing "and neither adoption complained — two prefixes,
                                each stable across its own seam"
                        (is (empty? @seen)
                            (str "two matching prefixes must produce no mismatch; "
                                 "got " (pr-str (mapv sup/tags-of @seen)))))

                      (testing "the ids are DISTINCT, and each is its own root's.
                                Read after a dispatch into each root, so both
                                are the client's own answer rather than the
                                server's untouched bytes"
                        (relabel! frame-a "alpha'")
                        (relabel! frame-b "beta'")
                        (is (= "alpha'" (text-in ca ".label"))
                            "premise: root A's island re-rendered")
                        (is (= "beta'" (text-in cb ".label"))
                            "premise: root B's island re-rendered")
                        (is (= server-a (probe-id ca)) "root A held its id")
                        (is (= server-b (probe-id cb)) "root B held its id")
                        (is (not= (probe-id ca) (probe-id cb))
                            (str "two roots on one page must not mint the same id; "
                                 "got " (pr-str (probe-id ca)) " twice"))
                        (is (re-find #"pfx-a-" (probe-id ca)))
                        (is (re-find #"pfx-b-" (probe-id cb))))

                      (testing "§2.4's *exact cleanup on unmount*, and the fact
                                that teardown is root-scoped: A's unmount leaves
                                B's runtime standing, and the census is exact
                                only once BOTH are down. Read before any
                                `release!`, because `release!` empties the tables
                                by fiat and a census after it cannot go red"
                        (mount/unmount! ha)
                        (is (not= sup/released (sup/census))
                            "root B is still live, so the runtime is not empty")
                        (mount/unmount! hb)
                        (is (= sup/released (sup/census))
                            (str "both roots down; residue was "
                                 (pr-str (sup/census)))))

                      (finally
                        (stop!)
                        (mount/release! (assoc ha :root nil))
                        (mount/release! (assoc hb :root nil))
                        (done))))))))))))

;; ---------------------------------------------------------------------------
;; 3 — the NEAR-MISS: a legal prefix that is the wrong one
;; ---------------------------------------------------------------------------
;;
;; This row is what makes every green above mean something. The two roots
;; in row 2 each *have* a prefix, and a door that accepted the option and
;; dropped it on the floor would satisfy every assertion there that reads
;; only the server's bytes. Here the client is handed a prefix that is
;; well-formed, spelled exactly as the door asks, and DIFFERENT from the
;; one the bytes were baked with — the page a team ships when the two
;; halves of their build drift apart.

(deftest a-client-prefix-that-disagrees-with-the-bytes-is-caught-at-source
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no React DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html! frame-a [id-page {}] "pfx-a-")
              server-id (id-in-html html)
              container (sup/stamp-server-nodes! (sup/server-dom! html))
              {:keys [seen stop!]} (sup/watch-mismatches!)]
          (collector/reset-runtime!)
          (let [handle (mount/hydrate-root! container frame-a [id-page {}]
                                            {:identifier-prefix "pfx-b-"})]
            (-> (sup/adopted! handle)
                (.then
                  (fn [_]
                    (try
                      (testing "the divergence is REPORTED, and attributed to
                                this arm's own door rather than left as an
                                uncaught window error"
                        (is (seq @seen)
                            "a prefix that disagrees with the bytes must complain")
                        (let [tags (sup/tags-of (first @seen))]
                          (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                                 (:where tags))
                              (str "attributed to source; got " (pr-str (:where tags))))
                          (is (= :warned-and-replaced (:recovery tags))
                              "React had already patched the DOM by the time the
                               callback ran, so that is the recovery reported")))

                      (testing "and the recovery is visible: the id on the page
                                is the CLIENT's, not the bytes'"
                        (is (not= server-id (probe-id container))
                            (str "the disagreeing client id must have replaced "
                                 "the server's; both read " (pr-str server-id)))
                        (is (re-find #"pfx-b-" (probe-id container))
                            (str "and it is the client's prefix; got "
                                 (pr-str (probe-id container)))))

                      (finally (stop!) (mount/release! handle) (done))))))))))))
