(ns re-frame.hicasso.identifier-prefix-ssr-dom-cljs-test
  "`identifierPrefix` ACROSS THE SEAM — the option, and the SECOND
  obstruction it uncovered (rf2-hic-046).

  `dispositions.md` §2.4 requires, of every surface it promotes, *two
  simultaneous hydrating roots with a stable `identifierPrefix`*. That
  clause was unreachable from every per-surface witness in the package,
  for a source reason rather than an oversight: `hydrate-root!` passed
  `onRecoverableError` and nothing else, so **the prefix was
  unspellable**. rf2-hic-046's native-tier half measured that and stopped
  (`native-ssr-dom-cljs-test`); the operator ruled a thin pass-through;
  the pass-through landed, and this file is what measures it.

  **It works, and the clause still does not close.** Both halves are
  facts and the rows below are laid out to keep them apart:

    - the option reaches React on both doors — the server's bytes and
      the client's root each carry the prefix they were given, and the
      distinctness the option exists for is real (§1, §2); but
    - a root the product door hydrates carries an element the server
      render has no counterpart for, and `useId` is derived from tree
      POSITION as well as from the prefix. So the two sides disagree on
      the id's tail while agreeing perfectly on its head (§2), and a page
      whose bytes were baked by the only server path this package has
      hydrates into a text mismatch.

  §3 pins that cause to a single structural difference and §4 and §5 run
  the whole clause on the far side of it, so what is missing is named
  exactly rather than left as *hydration is broken*.

  ## The cause, stated once

  A hydrating root's tree is `Fragment[closer, adoption-provider[frame
  provider[page]]]` (`impl.mount/tree`) — the closer is a SIBLING of the
  app subtree, which is rf2-6tmu's deliberate arrangement and right for
  every reason that bead gives. React derives a `useId` from the
  prefix AND from a *tree id* accumulated at each fork — an array of more
  than one child — so that sibling pair is a fork the server render never
  sees, and every id below it shifts. `renderToString` over the tree the
  arm actually hydrates produces ids that agree (§3); over the tree a
  consumer can spell today, it does not (§2).

  Two repairs are visible from here and this file rules on neither, which
  is the operator's. A **matching server-render entry** — the ruling's own
  words — would emit the root's own shape. Making the closer a WRAPPER
  rather than a sibling would remove the fork instead, and then the
  hand-rolled `renderToString` a consumer already writes would agree with
  no new door at all; it also moves a component into the app's parent
  chain, which is a change to rf2-6tmu's design rather than to this one.

  ## The claim is AGREEMENT, and presence is not agreement

  React numbers `useId` per root from the same start, so a page needs two
  facts at once: **the same prefix on both sides produces the same ids**,
  and **different prefixes produce different ones**. A witness asserting
  only the first would stay green under a door that ignored the option
  and let React default both sides; one asserting only the second would
  stay green under a door that broke hydration outright. Both directions
  are below, and so is the near-miss (§5) — a client prefix that is
  legal, spelled, and *different* from the server's — because that is the
  page a team ships when the two halves of a build drift apart.

  ## Why the id is TEXT and not an attribute

  Every probe renders its `useId` as element **text**. Attribute-only
  divergences are outside React's own hydration contract and stay outside
  it here (Spec 011 §Hydration-mismatch detection,
  `impl.mount/emit-hydration-mismatch!`), so an id parked in an `id=`
  attribute would diverge in silence and the disagreement rows would be
  measuring nothing.

  ## Why the readings survive a re-render

  Adopted markup and re-created markup are indistinguishable in
  `innerHTML` — the trap `roots-frames-support` exists to escape, and its
  answers are used here: the server-node EXPANDO for *this node is the
  one the bytes produced*, and the live trace stream for *the framework
  complained, or did not*.

  There is a second trap peculiar to `useId`. After an adoption the
  probe's text is the SERVER's text whether or not the client agrees,
  because React patches only what differs. So every agreement row
  **dispatches into the root afterwards** and re-reads: the island takes a
  changing prop, so the re-render is real, the body runs again, and a
  client id that had diverged is on the page by then. A reading taken on
  the far side of a genuine client render is a reading of what the CLIENT
  thinks the id is.

  ## The harness

  The server bytes come from `react-dom/server`'s own `renderToString`,
  called by hand with React's own `identifierPrefix` option — per
  rf2-ggnp's census the only server path this package has, and exactly
  the call a consumer makes. The client half is the product door
  `impl.mount/hydrate-root!` and never `hydrateRoot` directly: what is
  under test is the door's pass-through, so a row reaching past it would
  witness React rather than this arm.

  `roots-frames-support/server-html!` is deliberately NOT the harness
  here: it renders on an ordinary client root and reads `innerHTML`,
  which is the right instrument for markup rows and the wrong one for a
  `useId` claim — a client root's ids are not a server render's.

  Runtime: `-dom-cljs-test`, so `:browser-test` decides every hydration
  claim against a real React DOM. The `renderToString` rows need no DOM
  and run under `:node-test` too; the DOM rows say so and skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
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
;; The island is the vehicle and never the subject. What is under test is
;; the ROOT's option; the island is the smallest legal thing that makes
;; React's answer to it visible.

(n/defcomponent id-probe
  "One `useId`, rendered as text, beside a prop that changes.

  The prop is not decoration: it is what makes the post-adoption
  re-render REAL. Without it a re-render of the page would re-render a
  host whose props are identical, React would have every right to bail
  out, and the rows that re-read the id after a dispatch would be
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

  **The tree a consumer can spell today**: the frame provider over the
  root element, handed to `react-dom/server`'s `renderToString`. Per
  rf2-ggnp's census that hand-rolled call is the only server path this
  package has, so these are the bytes an SSR route actually ships."
  [frame-kw hiccup prefix]
  (react-dom-server/renderToString
    (mount/provider frame-kw (codec/root-element frame-kw hiccup))
    (when prefix #js {"identifierPrefix" prefix})))

(defn- root-shaped-server-html!
  "The same bytes, from the tree the product door actually HYDRATES —
  `Fragment[closer, adoption-provider[frame provider[page]]]`,
  `impl.mount/tree`'s hydrating shape reproduced element for element.

  **This is not a product path and this file does not propose it as
  one.** It is a measurement: the ONLY difference between it and
  [[server-html!]] is that structure, so a row that agrees here and
  disagrees there has isolated the cause to the structure and to nothing
  else. The markup the two produce is identical — the closer renders no
  DOM — which is exactly why the difference is invisible to every row in
  the package that reads markup.

  The window is a real one, minted for the render and never closed: on
  the server nothing runs a passive effect, so it stays open and is read
  by the presence provider alone. The client's root mints its own.

  `sup/server-html!` refuses to open a window for its own renders and
  says why — inventing product API for a test. The distinction held to
  here is that this is not offered to any other suite and answers no
  question except *which structural difference moved the id*."
  [frame-kw hiccup prefix]
  (let [window (roots/open-adoption-window!)
        app    (mount/provider frame-kw (codec/root-element frame-kw hiccup))]
    (react-dom-server/renderToString
      (react/createElement (.-Fragment react) nil
                           (react/createElement mount/adoption-window-closer
                                                #js {:rfWindow window})
                           (roots/with-adoption window app))
      (when prefix #js {"identifierPrefix" prefix}))))

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
;; 1 — the SERVER side alone (no DOM; runs under :node-test)
;; ---------------------------------------------------------------------------

(deftest the-server-renderer-honours-the-prefix-and-is-deterministic
  (fresh!)
  (let [a1   (server-html! frame-a [id-page {}] "pfx-a-")
        a2   (server-html! frame-a [id-page {}] "pfx-a-")
        b1   (server-html! frame-a [id-page {}] "pfx-b-")
        none (server-html! frame-a [id-page {}] nil)]

    (testing "the same request rendered twice is the same bytes — §2.4's
              *deterministic server bytes from an immutable request
              snapshot*, for the root and provider element"
      (is (= a1 a2) "two renders of one snapshot produced identical bytes"))

    (testing "the prefix reaches the id React put in those bytes"
      (is (some? (id-in-html a1))
          "premise: the island is IN the bytes, so there is an id to read —
           a Client-only island would leave nothing here")
      (is (str/includes? (id-in-html a1) "pfx-a-")
          (str "the server id must carry the prefix it was rendered with; got "
               (pr-str (id-in-html a1)))))

    (testing "and a DIFFERENT prefix is a different id — the distinctness
              half, measured on the server where it is unambiguous"
      (is (not= (id-in-html a1) (id-in-html b1))
          (str "two prefixes, two ids; got " (pr-str (id-in-html a1))
               " and " (pr-str (id-in-html b1))))
      (is (str/includes? (id-in-html b1) "pfx-b-")))

    (testing "naming no prefix is not an error and not a default of this
              arm's invention — it is React's own unprefixed id"
      (is (some? (id-in-html none)))
      (is (not (str/includes? (id-in-html none) "pfx-a-"))
          "an unprefixed render carries no prefix"))))

(deftest the-hydrating-root-s-own-shape-moves-the-id-and-not-the-prefix
  (testing "one prefix, two tree SHAPES, two ids — the whole cause, on the
            server side alone and with no DOM in sight. The prefix is
            identical in both because the option is doing its job; the
            tail differs because `useId` is derived from tree position and
            the hydrating root carries a fork the plain render has not"
    (fresh!)
    (let [plain  (id-in-html (server-html! frame-a [id-page {}] "pfx-a-"))
          shaped (id-in-html (root-shaped-server-html! frame-a [id-page {}] "pfx-a-"))]
      (is (str/includes? plain "pfx-a-") "the plain shape carries the prefix")
      (is (str/includes? shaped "pfx-a-") "and so does the root's own shape")
      (is (not= plain shaped)
          (str "the two shapes must not agree — if they did, §2's mismatch "
               "would have some other cause; got " (pr-str plain) " both times")))))

;; ---------------------------------------------------------------------------
;; 2 — THE OBSTRUCTION, measured: the bytes a consumer can bake today do
;;     not hydrate, and the prefix is not why
;; ---------------------------------------------------------------------------

(deftest the-bytes-a-consumer-can-bake-today-mismatch-on-position-not-on-prefix
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no React DOM") (done))
      (do
        (fresh!)
        (let [html      (server-html! frame-a [id-page {}] "pfx-a-")
              server-id (id-in-html html)
              container (sup/stamp-server-nodes! (sup/server-dom! html))
              {:keys [seen stop!]} (sup/watch-mismatches!)
              ;; MANUFACTURED here and asserted on here — the only shape of
              ;; call site at which swallowing an uncaught error is not the
              ;; fail-open rf2-mwx08 forbids. React routes a hydration
              ;; failure to `reportError`, and the browser runner fails a
              ;; run on any uncaught pageerror.
              {:keys [captured close!]} (sup/open-console-capture!
                                          {:swallow-uncaught? true})]
          (is (some? server-id) "premise: the bytes carry an id")
          (collector/reset-runtime!)
          (let [handle (mount/hydrate-root! container frame-a [id-page {}]
                                            {:identifier-prefix "pfx-a-"})]
            (-> (sup/adopted! handle)
                (.then
                  (fn [ok]
                    (close!)
                    (is (true? ok) "the root's own adoption window shut")
                    (try
                      (testing "React complains, and the complaint is attributed
                                to this arm's own door rather than left as an
                                uncaught window error"
                        (is (seq (filterv #(re-find #"Hydration failed" %) @captured))
                            (str "React itself must have reported the divergence "
                                 "on the page's own channel; got "
                                 (pr-str (mapv #(subs % 0 (min 60 (count %)))
                                               @captured))))
                        (is (seq @seen)
                            "the divergence must reach Spec 011's channel")
                        (let [tags (sup/tags-of (first @seen))]
                          (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                                 (:where tags))
                              (str "attributed to source; got " (pr-str (:where tags))))
                          (is (= :warned-and-replaced (:recovery tags))
                              "React had already patched the DOM by the time the
                               callback ran, so that is the recovery reported")))

                      (testing "and the PREFIX is not what diverged. Read after a
                                real client render, so it is the client's own
                                answer and not an untouched server byte"
                        (relabel! frame-a "alpha'")
                        (is (= "alpha'" (text-in container ".value"))
                            "premise: the dispatch painted, so a render happened")
                        (is (= "alpha'" (text-in container ".label"))
                            "premise: the ISLAND re-rendered too — its prop moved")
                        (is (str/includes? server-id "pfx-a-")
                            "the server's id carried the prefix")
                        (is (str/includes? (probe-id container) "pfx-a-")
                            (str "and so does the client's — the pass-through "
                                 "reached both doors; got "
                                 (pr-str (probe-id container))))
                        (is (not= server-id (probe-id container))
                            (str "they differ all the same, which is the "
                                 "obstruction this row records; server "
                                 (pr-str server-id) ", client "
                                 (pr-str (probe-id container)))))

                      (finally (stop!) (mount/release! handle) (done))))))))))))

;; ---------------------------------------------------------------------------
;; 3 — THE DIAGNOSIS: bytes of the root's own shape hydrate silently
;; ---------------------------------------------------------------------------

(deftest bytes-of-the-hydrating-root-s-own-shape-adopt-with-the-server-s-id
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no React DOM") (done))
      (do
        (fresh!)
        (let [html      (root-shaped-server-html! frame-a [id-page {}] "pfx-a-")
              server-id (id-in-html html)
              container (sup/stamp-server-nodes! (sup/server-dom! html))
              {:keys [seen stop!]} (sup/watch-mismatches!)]
          (collector/reset-runtime!)
          (let [handle (mount/hydrate-root! container frame-a [id-page {}]
                                            {:identifier-prefix "pfx-a-"})]
            (-> (sup/adopted! handle)
                (.then
                  (fn [ok]
                    (try
                      (is (true? ok) "the root's own adoption window shut")

                      (testing "the adoption was real — these are the very nodes
                                the bytes produced, which no re-render could
                                reconstruct"
                        (is (sup/every-server-node? container ".page, .value, .probe")
                            "the server's nodes survived, probe included"))

                      (testing "and React had nothing to complain about, which is
                                what isolates §2's mismatch to the tree shape:
                                one structural difference is all that changed"
                        (is (empty? @seen)
                            (str "matching shape and matching prefix must produce "
                                 "no mismatch; got "
                                 (pr-str (mapv #(:error (sup/tags-of %)) @seen)))))

                      (testing "the id agrees ACROSS A REAL CLIENT RENDER, which
                                is the only reading that separates *the client
                                minted the same id* from *nobody touched the
                                server's text*"
                        (relabel! frame-a "alpha'")
                        (is (= "alpha'" (text-in container ".label"))
                            "premise: the island re-rendered")
                        (is (= server-id (probe-id container))
                            (str "the client's own id must equal the server's; "
                                 "server " (pr-str server-id) ", client "
                                 (pr-str (probe-id container)))))

                      (finally (stop!) (mount/release! handle) (done))))))))))))

;; ---------------------------------------------------------------------------
;; 4 — §2.4's clause, as written: TWO SIMULTANEOUS hydrating roots
;; ---------------------------------------------------------------------------
;;
;; Everything §2.4 asks of the root and provider element, on the far side
;; of §3's structural difference: two roots adopting at once, each with
;; its own stable prefix, neither complaining, ids distinct, and an exact
;; census once both are down. What it does NOT witness is a product door
;; that emits these bytes — §2 and §3 together are the record of that gap.

(deftest two-simultaneous-hydrating-roots-keep-stable-and-distinct-prefixes
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no React DOM") (done))
      (do
        (fresh!)
        (let [html-a   (root-shaped-server-html! frame-a [id-page {}] "pfx-a-")
              html-b   (root-shaped-server-html! frame-b [id-page {}] "pfx-b-")
              server-a (id-in-html html-a)
              server-b (id-in-html html-b)
              ca       (sup/stamp-server-nodes! (sup/server-dom! html-a))
              cb       (sup/stamp-server-nodes! (sup/server-dom! html-b))
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
                                 "got "
                                 (pr-str (mapv #(:error (sup/tags-of %)) @seen)))))

                      (testing "the ids are DISTINCT and each is its own root's.
                                Read after a dispatch into each root, so both are
                                the client's own answer rather than the server's
                                untouched bytes"
                        (relabel! frame-a "alpha'")
                        (relabel! frame-b "beta'")
                        (is (= "alpha'" (text-in ca ".label"))
                            "premise: root A's island re-rendered")
                        (is (= "beta'" (text-in cb ".label"))
                            "premise: root B's island re-rendered")
                        (is (= server-a (probe-id ca)) "root A held its id")
                        (is (= server-b (probe-id cb)) "root B held its id")
                        (is (not= (probe-id ca) (probe-id cb))
                            (str "two roots on one page must not mint the same "
                                 "id; both read " (pr-str (probe-id ca))))
                        (is (str/includes? (probe-id ca) "pfx-a-"))
                        (is (str/includes? (probe-id cb) "pfx-b-")))

                      (testing "§2.4's *exact cleanup on unmount*, and the fact
                                that teardown is root-scoped: A's unmount leaves
                                B's runtime standing, and the census is exact only
                                once both are down. Read before any `release!`,
                                because `release!` empties the tables by fiat and
                                a census after it cannot go red"
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
;; 5 — the NEAR-MISS: a legal prefix that is the wrong one
;; ---------------------------------------------------------------------------
;;
;; This row is what makes §4's green mean something. The two roots there
;; each *have* a prefix, and a door that accepted the option and dropped
;; it on the floor would still satisfy every assertion that reads only the
;; server's bytes. Here the client is handed a prefix that is well-formed,
;; spelled exactly as the door asks, and DIFFERENT from the one the bytes
;; were baked with — and everything else, the tree shape included, is held
;; identical to §3's silent adoption. The prefix is the only variable.

(deftest a-client-prefix-that-disagrees-with-the-bytes-is-caught-at-source
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no React DOM") (done))
      (do
        (fresh!)
        (let [html      (root-shaped-server-html! frame-a [id-page {}] "pfx-a-")
              server-id (id-in-html html)
              container (sup/stamp-server-nodes! (sup/server-dom! html))
              {:keys [seen stop!]} (sup/watch-mismatches!)
              ;; Manufactured and asserted on here — see §2's note.
              {:keys [captured close!]} (sup/open-console-capture!
                                          {:swallow-uncaught? true})]
          (collector/reset-runtime!)
          (let [handle (mount/hydrate-root! container frame-a [id-page {}]
                                            {:identifier-prefix "pfx-b-"})]
            (-> (sup/adopted! handle)
                (.then
                  (fn [_]
                    (close!)
                    (try
                      (testing "the divergence is REPORTED, and attributed to this
                                arm's own door"
                        (is (seq (filterv #(re-find #"Hydration failed" %) @captured))
                            (str "React itself must have reported it; got "
                                 (pr-str (mapv #(subs % 0 (min 60 (count %)))
                                               @captured))))
                        (is (seq @seen)
                            "a prefix that disagrees with the bytes must complain")
                        (let [tags (sup/tags-of (first @seen))]
                          (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                                 (:where tags))
                              (str "attributed to source; got "
                                   (pr-str (:where tags))))
                          (is (= :warned-and-replaced (:recovery tags)))))

                      (testing "and the recovery is visible: the id on the page is
                                the CLIENT's prefix, not the bytes'"
                        (is (not= server-id (probe-id container))
                            (str "the disagreeing client id must have replaced the "
                                 "server's; both read " (pr-str server-id)))
                        (is (str/includes? (probe-id container) "pfx-b-")
                            (str "and it is the client's prefix; got "
                                 (pr-str (probe-id container)))))

                      (finally (stop!) (mount/release! handle) (done))))))))))))
