(ns re-frame.hicasso.host-adoption-trace-dom-cljs-test
  "**`:rf.ssr/host-adopted` — the client-only crossing, made observable.**

  `defhost`'s default policy is `:server :client-only`: the host region
  renders nothing on the server and nothing on hydration's first client
  pass, a declared `:fallback` stands in its place, and React's own
  post-hydration pass swaps it for the foreign component. That is a real,
  user-visible transition on every hydrated Hicasso page carrying a
  client-only crossing — and until this suite existed, nothing in the
  instrumentation stream said it had happened. The debugging question
  *is this region showing its fallback or its live subtree?* had no
  answer.

  It has one now, and these rows pin the properties that make it worth
  having rather than noise.

  ## The claims

  1. **The server announces nothing.** Server bytes are produced under
     the server snapshot, where the gate renders its placeholder and
     stays there.
  2. **A real crossing announces exactly once** — not once per render,
     and not once per SITE.
  3. **A first render that is already adopted announces nothing.** A
     fresh `createRoot` mount renders the foreign component on its very
     first pass, with no placeholder to replace, because such a root
     consults no server snapshot at all. Reporting a transition there
     would be reporting one that did not occur, which is the failure
     mode that turns a trace into noise.
  4. **End to end**, a genuinely hydrated page produces (2).

  ## Why each row mints its own declaration

  The crossing state is closed over by the gate, and `mint-host!` mints
  ONE gate per declaration — which is the whole point of the per-
  declaration grain, and also means a declaration announces at most once
  for the lifetime of the module. A `defhost` at the top of this file
  would therefore be spent by whichever row ran first, and every row
  after it would read a green that meant only *already announced*. So
  each row calls [[re-frame.hicasso.impl.codec/mint-host!]] — the same
  door `defhost` expands to — and gets a declaration nobody else has
  touched.

  ## Why rows 2 and 3 stub `adopted?`, and what that costs

  Adoption is React's own business: `adopted?` answers `false` from its
  SERVER snapshot and `true` from its client one, and only a real client
  renderer over a real document ever moves between them. Node has no
  document, so a Node-only suite could asserts nothing but absences —
  and an absence-only suite is exactly what let the first draft of this
  mechanism ship broken. (It did: the crossing cell's transitions were
  guarded with `identical?` on keyword literals, which is `false` in a
  dev build, so the trace never fired at all while every no-trace row
  stayed green. [[re-frame.hicasso.impl.codec/mint-adoption-crossing]]
  carries the post-mortem.)

  So rows 2 and 3 drive the REAL gate, the REAL crossing cell and the
  REAL emit through `renderToString`, stubbing only `adopted?` — the one
  input Node cannot supply. Row 4 then takes the whole thing unstubbed
  through an actual `hydrateRoot` in the browser, so the stub is a Node
  convenience rather than the only evidence.

  ## Lane

  Rows 1–3 need no DOM and run under `:node-test` as well as in the
  browser. Row 4 mounts React against a real document, so this file
  takes the `-dom-cljs-test` suffix and that row skips in Node, in the
  shape the sibling `host-ssr-dom-cljs-test` established."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso.checkpoint-support :as rf.hicasso.checkpoint-support]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::host-adoption-trace)

(rf/reg-event :hicasso.adoption/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "an adoption-trace DOM claim needs a real React DOM — " why)))

(defn- fresh! []
  (rf.hicasso.checkpoint-support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:hicasso.adoption/seed]))
  frame-id)

;; ---------------------------------------------------------------------------
;; The foreign component, and a fresh declaration per row
;; ---------------------------------------------------------------------------

(defn- chart
  "The stand-in for the library that reaches for `window` — the reason an
  author declares `:client-only` in the first place."
  [^js props]
  (react/createElement "div"
    #js {:className "chart" :data-live "yes"}
    (.-label props)))

(defn- mint!
  "A declaration nobody else has touched — the same door `defhost`
  expands to. `n` keeps the `displayName` legible in a failure."
  [n]
  (rf.hicasso.impl.codec/mint-host! n chart {:fallback [:div.chart-skeleton "loading"]}))

;; ---------------------------------------------------------------------------
;; Watching the instrumentation channel
;; ---------------------------------------------------------------------------

(defn- watch-adoptions!
  "Collect every `:rf.ssr/host-adopted` event, and nothing else. Returns
  the sink and its own unregister — a listener that outlived its row
  would report another row's crossing as this one's."
  []
  (let [seen (atom [])
        lk   (keyword (gensym "rf.oaksj-adopt-"))]
    (rf.trace/register-listener!
      lk (fn [ev] (when (= :rf.ssr/host-adopted (:operation ev))
                    (swap! seen conj ev))))
    {:seen seen :stop (fn [] (rf.trace/unregister-listener! lk))}))

(defn- render-html [hiccup]
  (react-dom-server/renderToString
    (rf.hicasso.impl.mount/provider frame-id (rf.hicasso.impl.codec/root-element frame-id hiccup))))

(defn- render-as
  "One render of `hiccup` with React's adoption answer FORCED — the whole
  stub, and the reason it is legitimate: `adopted?` is React's signal,
  not Hicasso's state. Everything downstream of it here — the gate
  closure, the crossing cell, the emit — is the shipping code."
  [adopted? hiccup]
  (with-redefs [rf.hicasso.impl.codec/adopted? (fn [] adopted?)]
    (render-html hiccup)))

(defn- query-node [root selector] (.querySelector root selector))

;; ---------------------------------------------------------------------------
;; 1 — the server announces nothing
;; ---------------------------------------------------------------------------

(deftest a-server-render-announces-no-adoption
  (fresh!)
  (let [host (mint! "server-silent-chart")
        {:keys [seen stop]} (watch-adoptions!)]
    (try
      (let [html (render-html [:div [host {:label "revenue"}]])]
        (testing "the server rendered the placeholder rather than the component
                  — the row's own restatement of the policy, so a gate that
                  stopped gating is red HERE and not only in the trace claim"
          (is (re-find #"chart-skeleton" html) html)
          (is (not (re-find #"class=\"chart\"" html)) html))
        (testing "and nothing crossed, so nothing was announced. The trace
                  reports an ADOPTION, and a server render is the one place
                  adoption provably cannot have happened"
          (is (empty? @seen) (pr-str @seen))))
      (finally
        (stop)
        (rf.hicasso.impl.collector/reset-runtime!)))))

;; ---------------------------------------------------------------------------
;; 2 — a crossing announces ONCE, and carries the facts a tool reads
;; ---------------------------------------------------------------------------

(deftest a-crossing-announces-exactly-once
  (fresh!)
  (let [host (mint! "crossing-chart")
        page [:div
              ;; TWO sites of ONE declaration. The grain claim is not
              ;; decoration: the retired predecessor was root-scoped
              ;; because the substrate that emitted it was, and hicasso's
              ;; is per-declaration. A per-SITE implementation passes
              ;; every other assertion in this file and fails this one.
              [host {:label "revenue"}]
              [host {:label "costs"}]]
        {:keys [seen stop]} (watch-adoptions!)]
    (try
      (let [before (render-as false page)]
        (testing "the unadopted pass shows the placeholder and says nothing"
          (is (re-find #"chart-skeleton" before) before)
          (is (empty? @seen) (pr-str @seen))))
      (let [after (render-as true page)]
        (testing "the adopted pass swaps in the foreign component"
          (is (re-find #"class=\"chart\"" after) after)
          (is (not (re-find #"chart-skeleton" after)) after))
        (testing "and announces the crossing EXACTLY ONCE — one declaration,
                  one gate, one event, however many sites used it"
          (is (= 1 (count @seen)) (pr-str @seen))))
      (testing "the event carries what a tool needs to place it: `:info`,
                because nothing is wrong — this is `:client-only` reporting
                that it completed — plus the declaration that crossed and
                the door that observed it"
        (when (= 1 (count @seen))
          (let [ev (first @seen)]
            (is (= :info (:op-type ev)))
            (is (= :rf.ssr/host-adopted (:operation ev)))
            (is (= "crossing-chart" (get-in ev [:tags :host])))
            (is (= 're-frame.hicasso.impl.codec/mint-host-gate!
                   (get-in ev [:tags :where]))))))
      (testing "and every later adopted render is silent — React re-renders a
                gate freely, and a Strict-Mode double render is two passes of
                the same one; an announce per pass would be a stream rather
                than an event"
        (render-as true page)
        (render-as true page)
        (is (= 1 (count @seen)) (pr-str @seen)))
      (finally
        (stop)
        (rf.hicasso.impl.collector/reset-runtime!)))))

;; ---------------------------------------------------------------------------
;; 3 — nothing crossed, so nothing is announced
;; ---------------------------------------------------------------------------

(deftest an-already-adopted-first-render-announces-nothing
  (fresh!)
  (let [host (mint! "fresh-mount-chart")
        page [:div [host {:label "revenue"}]]
        {:keys [seen stop]} (watch-adoptions!)]
    (try
      (let [html (render-as true page)]
        (testing "this is the fresh-mount shape: `adopted?` answers true on the
                  very first pass of a root that consults no server snapshot,
                  so the component renders and the placeholder never flashes"
          (is (re-find #"class=\"chart\"" html) html)
          (is (not (re-find #"chart-skeleton" html)) html)))
      (testing "and precisely because the placeholder never rendered, there was
                no transition to report. This is the row that keeps the trace
                meaningful: a mechanism that announced on every mount would be
                announcing the absence of the thing it names"
        (is (empty? @seen) (pr-str @seen)))
      (finally
        (stop)
        (rf.hicasso.impl.collector/reset-runtime!)))))

;; ---------------------------------------------------------------------------
;; 4 — the same claim, unstubbed, through a real hydration
;; ---------------------------------------------------------------------------

(deftest a-hydrated-crossing-announces-once
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [host      (mint! "hydrated-chart")
              page      [:div [host {:label "revenue"}] [host {:label "costs"}]]
              html      (render-html page)
              container (rf.hicasso.impl.mount/fresh-container!)
              {:keys [seen stop]} (watch-adoptions!)]
          (set! (.-innerHTML container) html)
          (let [root (react-dom-client/hydrateRoot
                       container
                       (rf.hicasso.impl.mount/provider frame-id (rf.hicasso.impl.codec/root-element frame-id page)))]
            (js/setTimeout
              (fn []
                (try
                  (testing "the markup hydrated FROM carries the placeholder at
                            both sites, and the live component at neither"
                    (is (re-find #"chart-skeleton" html) html)
                    (is (not (re-find #"class=\"chart\"" html)) html))
                  (testing "React's own post-hydration pass swapped both"
                    (is (some? (query-node container ".chart")))
                    (is (nil? (query-node container ".chart-skeleton"))))
                  (testing "and the real crossing announced once, with no stub
                            anywhere in the path"
                    (is (= 1 (count @seen)) (pr-str @seen)))
                  (finally
                    (stop)
                    (.unmount root)
                    (when-some [p (.-parentNode container)] (.removeChild p container))
                    (rf.hicasso.impl.collector/reset-runtime!)
                    (done))))
              150)))))))
