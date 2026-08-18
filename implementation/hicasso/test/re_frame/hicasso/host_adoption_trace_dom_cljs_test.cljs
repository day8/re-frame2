(ns re-frame.hicasso.host-adoption-trace-dom-cljs-test
  "**`:rf.ssr/host-adopted` — the client-only crossing, made observable
  (rf2-oaksj).**

  `defhost`'s default policy is `:server :client-only`: the host region
  renders nothing on the server and nothing on hydration's first client
  pass, a declared `:fallback` stands in its place, and React's own
  post-hydration pass swaps it for the foreign component. That is a real,
  user-visible transition on every hydrated Hicasso page carrying a
  client-only crossing — and until this suite existed, nothing in the
  instrumentation stream said it had happened. The debugging question
  *is this region showing its fallback or its live subtree?* had no
  answer.

  It has one now, and these rows pin the three properties that make it
  worth having rather than noise.

  ## The three claims

  1. **It fires on a real crossing, exactly once.** A hydrated
     `:client-only` host emits ONE `:info` trace when React swaps the
     placeholder out — not one per re-render, and not one per SITE.
  2. **It does not fire when nothing crossed.** A fresh `createRoot`
     mount renders the foreign component on its very first pass, with no
     placeholder to replace; `adopted?` answers `true` immediately there
     because such a root consults no server snapshot at all. Reporting a
     transition there would be reporting one that did not occur, which is
     the failure mode that turns a trace into noise.
  3. **The server emits nothing.** Server bytes are produced under the
     server snapshot, where the gate renders its placeholder and stays
     there.

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

  ## Lane

  Row 3 needs no DOM and runs under `:node-test` as well as in the
  browser. Rows 1 and 2 mount React against a real document, so they
  take the `-dom-cljs-test` suffix and skip in Node, in the shape the
  sibling `host-ssr-dom-cljs-test` established."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.checkpoint-support :as support]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            ["react" :as react]
            ["react-dom/client" :as react-dom-client]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::host-adoption-trace)

(rf/reg-event :hicasso.adoption/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

(defn- skip! [why]
  (is true (str "an adoption-trace DOM claim needs a real React DOM — " why)))

(defn- fresh! []
  (support/leave-act-environment!)
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
  [n opts]
  (codec/mint-host! n chart opts))

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
    (trace/register-listener!
      lk (fn [ev] (when (= :rf.ssr/host-adopted (:operation ev))
                    (swap! seen conj ev))))
    {:seen seen :stop (fn [] (trace/unregister-listener! lk))}))

(defn- server-html [hiccup]
  (react-dom-server/renderToString
    (mount/provider frame-id (codec/root-element frame-id hiccup))))

(defn- q [root sel] (.querySelector root sel))

;; ---------------------------------------------------------------------------
;; 1 — the server emits nothing (this row runs under :node-test too)
;; ---------------------------------------------------------------------------

(deftest a-server-render-announces-no-adoption
  (fresh!)
  (let [host   (mint! "server-silent-chart" {:fallback [:div.chart-skeleton "loading"]})
        {:keys [seen stop]} (watch-adoptions!)]
    (try
      (let [html (server-html [:div [host {:label "revenue"}]])]
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
        (collector/reset-runtime!)))))

;; ---------------------------------------------------------------------------
;; 2 — a fresh mount crossed nothing, and says nothing
;; ---------------------------------------------------------------------------

(deftest a-fresh-mount-announces-no-adoption
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [host      (mint! "fresh-mount-chart" {:fallback [:div.chart-skeleton "loading"]})
              container (mount/fresh-container!)
              {:keys [seen stop]} (watch-adoptions!)
              hiccup    [:div [host {:label "revenue"}]]
              root      (react-dom-client/createRoot container)]
          (.render root (mount/provider frame-id (codec/root-element frame-id hiccup)))
          (js/setTimeout
            (fn []
              (try
                (testing "the component is mounted and the placeholder never
                          flashed — `adopted?` answers true on the very first
                          pass of a root that consults no server snapshot"
                  (is (some? (q container ".chart")))
                  (is (nil? (q container ".chart-skeleton"))))
                (testing "and precisely because the placeholder never rendered,
                          there was no transition to report. This is the row
                          that keeps the trace meaningful: a mechanism that
                          announced on every mount would be announcing the
                          absence of the thing it names"
                  (is (empty? @seen) (pr-str @seen)))
                (finally
                  (stop)
                  (.unmount root)
                  (when-some [p (.-parentNode container)] (.removeChild p container))
                  (collector/reset-runtime!)
                  (done))))
            150))))))

;; ---------------------------------------------------------------------------
;; 3 — a hydrated crossing announces ONCE, whatever the site count
;; ---------------------------------------------------------------------------

(deftest a-hydrated-crossing-announces-once-per-declaration
  (async done
    (if-not (mount/browser?)
      (do (skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [host      (mint! "hydrated-chart" {:fallback [:div.chart-skeleton "loading"]})
              ;; TWO sites of ONE declaration. The grain claim is not
              ;; decoration: the retired predecessor was root-scoped
              ;; because the substrate that emitted it was, and hicasso's
              ;; is per-declaration. A per-SITE implementation passes
              ;; every other assertion here and fails this one.
              hiccup    [:div
                         [host {:label "revenue"}]
                         [host {:label "costs"}]]
              html      (server-html hiccup)
              container (mount/fresh-container!)
              {:keys [seen stop]} (watch-adoptions!)]
          (set! (.-innerHTML container) html)
          (let [root (react-dom-client/hydrateRoot
                       container
                       (mount/provider frame-id (codec/root-element frame-id hiccup)))]
            (js/setTimeout
              (fn []
                (try
                  (testing "the markup hydrated FROM carries the placeholder at
                            both sites, and the live component at neither"
                    (is (re-find #"chart-skeleton" html) html)
                    (is (not (re-find #"class=\"chart\"" html)) html))
                  (testing "React's post-hydration pass swapped both"
                    (is (some? (q container ".chart")))
                    (is (nil? (q container ".chart-skeleton"))))
                  (testing "and the crossing was announced EXACTLY ONCE — one
                            declaration, one gate, one event, however many
                            sites used it and however many times React
                            re-rendered them"
                    (is (= 1 (count @seen)) (pr-str @seen)))
                  (when (= 1 (count @seen))
                    (let [ev (first @seen)]
                      (testing "as an `:info`, because nothing is wrong: this is
                                `:client-only` reporting that it completed"
                        (is (= :info (:op-type ev)))
                        (is (= :rf.ssr/host-adopted (:operation ev))))
                      (testing "naming the declaration that crossed and the door
                                that observed it, so a page with several hosts
                                is legible rather than merely noisy"
                        (is (= "hydrated-chart" (get-in ev [:tags :host])))
                        (is (= 're-frame.hicasso.impl.codec/mint-host-gate!
                               (get-in ev [:tags :where]))))))
                  (finally
                    (stop)
                    (.unmount root)
                    (when-some [p (.-parentNode container)] (.removeChild p container))
                    (collector/reset-runtime!)
                    (done))))
              150)))))))
