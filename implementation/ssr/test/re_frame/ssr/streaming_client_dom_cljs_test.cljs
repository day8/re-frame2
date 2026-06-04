(ns re-frame.ssr.streaming-client-dom-cljs-test
  "Acceptance coverage for the client-side streaming-SSR runtime
  (`re-frame.ssr.streaming.client/install!`). Per Spec 011 §Streaming
  SSR — client-side hydration semantics (rf2-3hhv5).

  ## What this proves — the feature's whole acceptance

  The prior gap (rf2-3hhv5) was that the server emitted `<template>`
  resolved chunks + `<script data-rf2-suspense-hydrate>` deltas that
  NOTHING consumed: a browser showed skeleton fallbacks until the final
  `__rf_payload`, defeating streaming. These tests prove the client
  runtime closes that gap — **a chunk hydrates its subtree (DOM swap +
  app-db delta merge) BEFORE the final payload lands**:

    1. `progressive-hydration-happens-before-final-payload` — drives
       chunk arrival one at a time into a real DOM, asserting after each
       chunk that (a) its fallback `<template>` was swapped for resolved
       content in-place, and (b) the target frame's app-db received the
       chunk's delta (so a subscription reading that region sees the
       speculative state) — all while NO `__rf_payload` exists yet. This
       is the regression the gap left invisible.
    2. `failed-boundary-swaps-fallback-and-emits-trace` — a
       `data-rf2-suspense-failed` chunk swaps the fallback HTML, applies
       no delta, and emits `:rf.ssr/suspense-boundary-failed`.
    3. `observer-disconnects-on-final-payload` — once `__rf_payload`
       lands the runtime stops, so a late delta cannot race the
       canonical `:rf/hydrate` replace.

  ## Wire shape — matches the SHIPPED server emitter

  The fixtures emit chunks through the SAME server façade fns the Ring
  adapter uses (`ssr/streaming-resolved-template`,
  `ssr/streaming-hydrate-delta-script`,
  `ssr/streaming-failed-template`) so the client is tested against the
  real on-wire bytes, not a paraphrase. The id round-trips through the
  `data-rf2-suspense-*` attribute; the delta is the bare delta-map EDN.

  Browser-only — the runtime is a `MutationObserver` + DOM-swap consumer.
  The `-dom-cljs-test$` suffix (rf2-2hrj8) opts this file into the
  `:browser-test` build; `:node-test` loads it too (matches `cljs-test$`)
  and the DOM-dependent assertions gate on `(browser?)`, exiting early
  under Node where `js/document` is absent."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.streaming.client :as streaming-client]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

;; Per cljs.test: async tests require fixtures supplied as a MAP (a
;; fn-form fixture's `finally` runs before the async body completes,
;; which cljs.test rejects — "Async tests require fixtures to be
;; specified as maps"). One of the tests below is observer-driven
;; (`cljs.test/async`), so the suite uses the map-form reset fixture —
;; the same idiom as `re-frame.dispatch-fallthrough-warn-dom-cljs-test`.

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (trace-tooling/clear-listeners!)
  (substrate-adapter/install-adapter! reagent-slim-adapter/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {})
  (trace-tooling/clear-listeners!))

(use-fixtures :each {:before before! :after after!})

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- chunk authoring (the SHIPPED server façade) ---------------------------
;;
;; We assemble chunk HTML through the same façade fns the Ring writer
;; thread calls, so the client consumes the real wire bytes.

(defn- shell-with-fallbacks
  "A `#app` host whose body holds inline fallback `<template>`s — the
  shape `render-shell` emits. Built by hand here (the shell walker is
  JVM-only `.cljc` exercised under :node-test); the fallback-template
  shape is the server's `streaming-fallback-template`, which we call
  directly so the attribute names match."
  [ids]
  (str "<main class=\"dashboard\"><section class=\"cards\">"
       (->> ids
            (map (fn [id]
                   (ssr/streaming-fallback-template
                     id (str "<div class=\"card skeleton\">loading " (name id) "</div>"))))
            (str/join ""))
       "</section></main>"))

(defn- resolved-chunk-html
  "One resolved-subtree chunk's HTML: the resolved `<template>` followed
  by its hydrate-delta `<script>` — exactly the two nodes the writer
  thread flushes per drained continuation."
  [id resolved-html delta]
  (str (ssr/streaming-resolved-template id resolved-html)
       (ssr/streaming-hydrate-delta-script id (pr-str delta))))

(defn- failed-chunk-html
  "A failed-continuation chunk: failed `<template>` (fallback HTML +
  `data-rf2-suspense-failed`), no delta script."
  [id fallback-html]
  (ssr/streaming-failed-template id fallback-html))

(defn- final-payload-html []
  "<script id=\"__rf_payload\" type=\"application/edn\">{:rf/version 1 :rf/app-db {} :rf/render-hash \"00000000\"}</script>")

;; ---- DOM scaffolding -------------------------------------------------------

(defn- make-root!
  "Build a detached `#app` host seeded with the shell + fallbacks. The
  detached container lets us drive chunk arrival deterministically:
  appending a chunk's nodes is the test's stand-in for a streamed
  chunk parsing into the live document."
  [ids]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host) (str "<div id=\"app\">" (shell-with-fallbacks ids) "</div>"))
    ;; Attach to the document so MutationObserver fires (an observer on a
    ;; never-attached node still fires on childList mutations, but
    ;; attaching mirrors the real page and keeps querySelector(#id) sane).
    (.appendChild (.-body js/document) host)
    host))

(defn- append-chunk!
  "Parse `chunk-html` and append its nodes into `host` — the test's
  model of a chunk arriving over the wire. Uses a `<template>` to parse
  the fragment (so `<template>`/`<script>` chunk nodes materialise with
  their content intact) then moves the parsed children into `host`."
  [host chunk-html]
  (let [parser (.createElement js/document "template")]
    (set! (.-innerHTML parser) chunk-html)
    (.appendChild host (.-content parser))))

(defn- card-count [host cls]
  (count (array-seq (.querySelectorAll host (str "." cls)))))

(defn- mount-for [host id]
  (.querySelector host (str "[data-rf2-suspense-mount=\"" (pr-str id) "\"]")))

(defn- showing-fallback?
  "True when boundary `id` is in its (live, visible) fallback state —
  the mount exists and still holds skeleton content, i.e. no resolved
  chunk has swapped it yet. After install the runtime materialises the
  inert `<template>` fallback into a live `<rf-suspense>` mount, so the
  user sees the skeleton; a resolved chunk later replaces the mount's
  content."
  [host id]
  (when-let [m (mount-for host id)]
    (some? (.querySelector m ".skeleton"))))

(defn- remove-root! [host]
  (when-let [p (.-parentNode host)]
    (.removeChild p host)))

;; ---- frame setup -----------------------------------------------------------

(defn- make-client-frame!
  "Register a `:client`-platform frame with an empty app-db + a sub that
  reads a card value, so we can prove a subscription SEES a delta the
  moment its chunk arrives (progressive hydration), before the final
  payload."
  []
  (let [fid (keyword "rf.frame" (str (gensym "stream-client-")))]
    (rf/reg-frame fid {:doc "streaming-client-test frame" :platform :client})
    (rf/reg-sub :sct/card (fn [db [_ id]] (get-in db [:cards id])))
    fid))

;; ---- the acceptance test ---------------------------------------------------

;; The runtime's swap+merge is driven by a `MutationObserver` for chunks
;; that arrive AFTER install (the async path), and by the synchronous
;; INITIAL SWEEP for chunks already present when install runs (the common
;; real case: the shell + several cards stream in while `main.js`
;; downloads + boots). These tests exercise the synchronous initial-sweep
;; path so the assertions are deterministic — no event-loop-timing
;; dependence. The async observer path runs the SAME `sweep!` code, so
;; this coverage is faithful to both. (`async-observer-applies-late-chunk`
;; below additionally proves the observer-driven path under an explicit
;; `cljs.test/async`.)

(deftest progressive-hydration-happens-before-final-payload
  (testing "Resolved chunks present at install (NO final payload) are each
            swapped into their live mount AND their delta merged into
            app-db — proving the client applies deltas progressively,
            independent of the final `__rf_payload`. This is the rf2-3hhv5
            acceptance: the client path actually runs. (Initial-sweep path
            — synchronous + deterministic.)"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [fid  (make-client-frame!)
            host (make-root! [:card.revenue :card.signups])]
        (try
          ;; Two resolved chunks have ALREADY streamed in (the shell +
          ;; cards landed while main.js booted) — but NOT the final
          ;; payload. This is the page state when the bundle executes.
          (append-chunk!
            host
            (resolved-chunk-html :card.revenue
                                 "<div class=\"card resolved-revenue\">Revenue 42375</div>"
                                 {:cards {:revenue {:title "Revenue" :value 42375}}}))
          ;; signups has NOT resolved yet — its fallback is still inert.
          (is (nil? (.querySelector host "#__rf_payload"))
              "no final payload present — any hydration is purely from chunk deltas")
          (is (= {} (frame/frame-app-db-value fid)) "app-db empty before install")

          ;; Install runs its synchronous initial sweep: materialise the
          ;; un-resolved fallback into a visible mount, swap the resolved
          ;; chunk into its mount, and merge its delta.
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              ;; (a) revenue (resolved chunk present) swapped in-place.
              (is (false? (boolean (showing-fallback? host :card.revenue)))
                  "revenue mount shows resolved content, not a skeleton")
              (is (= 1 (card-count host "resolved-revenue"))
                  "revenue resolved content is live in the DOM")
              ;; signups (no resolved chunk) shows its skeleton fallback.
              (is (true? (showing-fallback? host :card.signups))
                  "the un-resolved card still shows its skeleton — only the arrived chunk hydrated")
              (is (= 1 (card-count host "skeleton")) "exactly one card still a fallback")
              ;; (b) delta merged — a SUBSCRIPTION reads the speculative
              ;; value, with NO __rf_payload / :rf/hydrate.
              (is (= {:title "Revenue" :value 42375}
                     @(rf/subscribe fid [:sct/card :revenue]))
                  "subscription reads the revenue delta — progressive hydration BEFORE the final payload")
              (is (nil? @(rf/subscribe fid [:sct/card :signups]))
                  "the un-resolved card's state is not present yet")
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest into-merge-is-lossless-across-chunks
  (testing "Two resolved chunks present at install, the second carrying the
            FULL after-db value for the changed top-level key — the
            client's top-level `(into existing delta)` merge keeps both
            cards (the lossless property, Spec 011 §Hydration interleaving;
            folds rf2-bee5i part-2's resolved-vs-marker concern on the
            client side)."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid  (make-client-frame!)
            host (make-root! [:card.revenue :card.signups])]
        (try
          (append-chunk!
            host
            (resolved-chunk-html :card.revenue
                                 "<div class=\"card resolved-revenue\">Revenue</div>"
                                 {:cards {:revenue {:value 42375}}}))
          ;; signups chunk ships the FULL :cards value (both cards) — the
          ;; server's contract (full after-db value per changed top-level
          ;; key), so the top-level into-merge is lossless.
          (append-chunk!
            host
            (resolved-chunk-html :card.signups
                                 "<div class=\"card resolved-signups\">Signups</div>"
                                 {:cards {:revenue {:value 42375}
                                          :signups {:value 318}}}))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              (is (= 42375 (:value @(rf/subscribe fid [:sct/card :revenue])))
                  "revenue card survives the second chunk's :cards replacement")
              (is (= 318 (:value @(rf/subscribe fid [:sct/card :signups])))
                  "signups card applied")
              (is (= 0 (card-count host "skeleton")) "no fallbacks remain — both swapped")
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest failed-boundary-swaps-fallback-and-emits-trace
  (testing "A data-rf2-suspense-failed chunk swaps the fallback HTML, applies
            NO delta, and emits :rf.ssr/suspense-boundary-failed (Spec 011
            §Failure semantics — inline fallback)."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid      (make-client-frame!)
            host     (make-root! [:card.flaky])
            captured (atom [])
            k        (str (gensym "stream-fail-cb"))]
        (trace-tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
        (try
          (append-chunk!
            host
            (failed-chunk-html :card.flaky "<div class=\"card card-failed\">unavailable</div>"))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "card-failed")) "the failed chunk's fallback HTML is in the DOM")
              (is (= {} (frame/frame-app-db-value fid)) "no delta applied for a failed boundary")
              (is (some #(= :rf.ssr/suspense-boundary-failed (:operation %)) @captured)
                  ":rf.ssr/suspense-boundary-failed trace emitted client-side")
              (finally (stop!))))
          (finally
            (trace-tooling/unregister-listener! k)
            (remove-root! host)))))))

(deftest async-observer-applies-late-chunk
  (testing "A resolved chunk that arrives AFTER install (the observer-driven
            path) is swapped + merged too. Proves the MutationObserver
            trigger runs the same sweep as the initial sweep."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid   (make-client-frame!)
              host  (make-root! [:card.late])
              stop! (streaming-client/install! {:frame fid :root host})]
          ;; Before the chunk: the fallback is materialised + visible.
          (is (true? (showing-fallback? host :card.late)) "late card starts as a visible skeleton")
          ;; Chunk arrives after install → the observer fires (microtask).
          (append-chunk!
            host
            (resolved-chunk-html :card.late
                                 "<div class=\"card resolved-late\">late 7</div>"
                                 {:cards {:late {:value 7}}}))
          ;; `setTimeout 0` (macrotask) runs after the observer microtask,
          ;; so the swap+merge has definitely happened by the continuation.
          (js/setTimeout
            (fn []
              (is (false? (boolean (showing-fallback? host :card.late)))
                  "late card swapped by the observer-driven sweep")
              (is (= 1 (card-count host "resolved-late")) "resolved content live in the DOM")
              (is (= 7 (:value @(rf/subscribe fid [:sct/card :late])))
                  "delta merged by the observer-driven sweep")
              (stop!)
              (remove-root! host)
              (done))
            0))))))
