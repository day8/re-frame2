(ns re-frame.ssr-streaming-example-cljs-test
  "Client-side render contract for the streaming SSR example
   (`examples/capabilities/ssr/ssr_streaming/`).

   Closes the isomorphism gap rf2-o4rbh found: the example's `:clj` branch
   was covered end-to-end by `re-frame.examples-test/ssr-streaming-example-
   runs-end-to-end` (JVM), but NOTHING exercised the `:cljs` branch's render
   tree. The example composed its cards as keyword view-refs
   (`[:dashboard/card :revenue]`) inside `:rf/suspense-boundary` markers —
   both of which resolve on the JVM SSR emitter and NEITHER of which has any
   client-side meaning:

     - Per [Conventions §Render-tree shape vs runtime lookup] a bare
       `[:keyword args]` head in a render tree is an **HTML element**; the
       runtime does not intercept the keyword case to dispatch via the views
       registry. Reagent's `parse-tag` runs `(name tag)`, so
       `:dashboard/card` became the literal DOM tag `<card>`.
     - Per [Spec 011 §Streaming SSR] `:rf/suspense-boundary` is recognised
       ONLY by the streaming shell walker; its name passes the DOM tag
       grammar, so on the client it became a phantom `<suspense-boundary>`
       element with `{:id … :fallback …}` serialised as bogus attributes.

   Server and client therefore disagreed STRUCTURALLY on the flagship
   streaming example: the server streamed `<div class=\"card\">`s, the
   browser painted `<suspense-boundary><card>…`.

   The example tree itself stays test-free (rf2-8cevm), so this substrate-
   side suite carries the cover — the same split as the JVM half, which lives
   in `re-frame.examples-test`. Sibling shape:
   `re-frame.infinite-feed-example-cljs-test`.

   `render-to-static-markup` is stock Reagent's react-dom/server serializer —
   the same hiccup→React-element mapping the browser's `hydrate-root` runs,
   so the markup below is what the client actually paints.

   ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest testing use-fixtures is]]
            [clojure.string :as str]
            [reagent.dom.server :as rds]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            ;; The failed-boundary record the example's boundaries consult.
            [re-frame.ssr.suspense :as rf.ssr.suspense]
            ;; the example's production source — registers :dashboard/root,
            ;; :dashboard/card, :dashboard/card-skeleton and the two subs at
            ;; ns-load.
            [ssr-streaming.core :as example]))

;; ---- fixture ---------------------------------------------------------------

(def ^:private test-frame :ssr-streaming-example-cljs-test/frame)

(defn- init!
  "Per-test setup (adapter installed, registrar baseline reinstated). Stands
  up a `:client`-platform frame — the same platform the example's `run`
  makes — and registers the example's `:cards` contract against it, mirroring
  the `run` wiring."
  []
  (rf/make-frame {:id       test-frame
                  :doc      "ssr-streaming example client-render test frame"
                  :platform :client})
  (rf/reg-app-schema [:cards] {:frame test-frame} example/CardsSchema))

;; `make-reset-runtime-fixture` is load-bearing here, not boilerplate: the
;; CLJS node runner loads every test ns into ONE bundle, and a sibling suite's
;; `:each` fixture can restore the registrar to a snapshot that predates this
;; ns's load — which strands `ssr-streaming.core`'s ns-load views and subs and
;; leaves `(rf/view :dashboard/root)` nil. The fixture folds this ns's stable
;; ns-load baseline back over the live registrar before each test.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn init!}))

(defn- render-client-tree
  "Render the example's client render tree — byte-for-byte the `tree` its
  `run` hands to `hydrate-root` — to static markup."
  []
  ;; `with-frame` as well as the provider: `render-to-static-markup` runs
  ;; outside React's DOM renderer, where the provider's context bridge does
  ;; not reach the views' injected `subscribe`. The provider is here because
  ;; it is what the example's `run` actually mounts; `with-frame` is what
  ;; makes the subs resolve under a static render.
  (rf/with-frame test-frame
    (rds/render-to-static-markup
      [rf/frame-provider {:frame test-frame}
       [(rf/view :dashboard/root)]])))

(defn- seed-cards!
  "Stand in for the streamed deltas + the final `__rf_payload` hydrate: the
  three cards whose boundaries RESOLVED land in app-db; `:flaky` (whose
  continuation threw server-side, shipping no delta) stays absent.

  Also stands in for the streaming client's finalization step, which
  records the failed boundary ids from the wire. That record is what lets
  `:card.flaky`'s boundary re-render its DECLARED fallback — the example's
  `card-view` no longer carries a nil branch duplicating the skeleton."
  []
  (rf/reg-event ::seed
    (fn [{:keys [db]} _]
      {:db (assoc db :cards
                  {:revenue {:title "Revenue (last 7 days)"     :value 42375}
                   :signups {:title "New signups (last 7 days)" :value 318}
                   :latency {:title "P50 latency (ms)"          :value 24}})}))
  (rf/dispatch-sync [::seed] {:frame test-frame})
  (rf.ssr.suspense/reset-failed-boundaries!)
  (rf.ssr.suspense/record-failed-boundaries! #{:card.flaky}))

;; ---- (1) no server-only marker leaks into the client DOM -------------------

(deftest client-render-emits-no-server-only-markers
  (testing "rf2-o4rbh: the client render tree contains neither a phantom
            <suspense-boundary> element (Spec 011 — streaming-shell-walker-only
            marker) nor a keyword view-ref rendered as a literal DOM tag
            (Conventions — keyword heads are HTML elements, never views)"
    (seed-cards!)
    (let [html (render-client-tree)]
      (is (not (str/includes? html "<suspense-boundary"))
          (str ":rf/suspense-boundary is a server-only marker; it must not "
               "reach a client render tree. Got: " html))
      (is (not (str/includes? html "<card"))
          (str "A keyword view-ref head renders as a literal DOM tag on the "
               "client. Got: " html))
      (is (not (str/includes? html "<card-skeleton"))
          (str "A keyword view-ref head renders as a literal DOM tag on the "
               "client. Got: " html)))))

;; ---- (2) the client paints the same structure the server streamed ----------

(deftest client-render-matches-the-streamed-dom
  (testing "rf2-o4rbh: with the resolved cards hydrated, the client renders
            the same .card structure the streaming server painted — this is
            what `hydrate-root` has to adopt without a mismatch"
    (seed-cards!)
    (let [html (render-client-tree)]
      ;; `<main …>` also carries the adapter's dev-only source-coord /
      ;; view-id attributes, so match the class rather than the whole tag.
      (is (str/includes? html "class=\"dashboard\"") html)
      (is (str/includes? html "42375") html)
      (is (str/includes? html "Revenue (last 7 days)") html)
      (is (str/includes? html "New signups (last 7 days)") html)
      (is (str/includes? html "P50 latency (ms)") html)
      (is (= 4 (count (re-seq #"class=\"card[\" ]" html)))
          (str "expected four rendered cards (three resolved + the flaky "
               "one's skeleton). Got: " html)))))

;; ---- (3) a boundary that never resolved stays on its skeleton --------------

(deftest failed-boundary-renders-its-declared-fallback
  (testing "rf2-ycz3k: the `:flaky` card's continuation threw server-side, so
            the final payload named it in the failed set. Its BOUNDARY
            re-renders the `:fallback` it declared — the same skeleton the
            failed chunk's fallback left in the DOM — without `card-view`
            carrying a nil branch that duplicates it"
    (seed-cards!)
    (let [html (render-client-tree)]
      (is (str/includes? html "class=\"card skeleton\"") html)
      (is (str/includes? html "Loading flaky") html)))

  (testing "the fallback is a consequence of the RECORDED OUTCOME, not of
            the card's data being absent. Clear the record and the same
            boundary renders its body — which, for this example's flaky
            card, is the view that throws on purpose. That throw is the
            proof: nothing about app-db decides this, the recorded failure
            does"
    (seed-cards!)
    (rf.ssr.suspense/reset-failed-boundaries!)
    (is (thrown? :default (render-client-tree))
        (str "with no recorded failure the boundary must render its body "
             "(`throwing-card`), not silently keep showing the fallback"))))
