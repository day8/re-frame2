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
            [re-frame.ssr.constants :as constants]
            [re-frame.ssr.streaming.constants :as wire]
            [re-frame.ssr.streaming.client :as streaming-client]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

;; Per cljs.test: async tests require fixtures supplied as a MAP (a fn-form
;; fixture's teardown runs before the async body completes — "Async tests require
;; fixtures to be specified as maps"). One of the tests below is observer-driven
;; (`cljs.test/async`), so the suite uses `make-reset-runtime-fixture`'s
;; `:async? true` map-form for the snapshot/restore + frames-reset + adapter
;; dispose/install it hand-rolled. `:ambient-frame nil` preserves the
;; no-ambient-scope behaviour (each test creates its own client frame).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter :async? true :ambient-frame nil}))

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

(defn- failed-chunk-with-delta-html
  "A CONTRADICTORY wire shape (rf2-x76af2.40): a failed `<template>` (the
  server's inline-fallback marker) followed by a hydrate-delta `<script>` for
  the SAME id — which the shipped server NEVER emits for a failed
  continuation. Models a malformed / duplicated / reordered stream the client
  must fail CLOSED on: the failed fallback swaps, but the delta must be
  quarantined, not merged."
  [id fallback-html delta]
  (str (ssr/streaming-failed-template id fallback-html)
       (ssr/streaming-hydrate-delta-script id (pr-str delta))))

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
  (.querySelector host (str "[" wire/attr-suspense-mount "=\"" (pr-str id) "\"]")))

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
    ;; Register BEFORE creating the frame (rf2-h1vqa4): an explicit id in the
    ;; reserved `rf.frame` namespace is a DIRECT frame — auto-reprojection
    ;; deliberately never touches it (EP-0023 §Frame), so its default image
    ;; generation is sealed at construction and a post-construction reg-sub
    ;; would be invisible to `{:frame fid}` resolution.
    (rf/reg-sub :sct/card (fn [db [_ id]] (get-in db [:cards id])))
    (rf/make-frame {:id fid :doc "streaming-client-test frame" :platform :client})
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
          (is (nil? (.querySelector host (str "#" constants/payload-script-id)))
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
                     @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                  "subscription reads the revenue delta — progressive hydration BEFORE the final payload")
              (is (nil? @(rf/subscribe [:sct/card :signups] {:frame fid}))
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
              (is (= 42375 (:value @(rf/subscribe [:sct/card :revenue] {:frame fid})))
                  "revenue card survives the second chunk's :cards replacement")
              (is (= 318 (:value @(rf/subscribe [:sct/card :signups] {:frame fid})))
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

(deftest failed-boundary-suppresses-matching-hydration-delta
  (testing "rf2-x76af2.40 — a boundary the server flagged FAILED must NEVER
            merge a matching hydrate-delta into app-db, even under a
            contradictory / duplicated / reordered stream that pairs a
            `data-rf2-suspense-failed` template with a (valid-map) delta
            `<script>` for the same id. #5728 made `seen` an undifferentiated
            set, so `apply-ready-deltas!` would merge ANY seen id's delta —
            including a failed boundary's. The delta must be QUARANTINED (not
            merged), its script consumed, and exactly one `:quarantined-delta`
            diagnostic emitted; the failed fallback still swaps and emits its
            `:inline-fallback` signal exactly once. (Initial-sweep path — both
            nodes present at install, synchronous + deterministic.)"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid       (make-client-frame!)
            host      (make-root! [:card.flaky])
            captured  (atom [])
            k         (str (gensym "stream-failquarantine-cb"))
            failed-of #(filter (fn [ev] (= :rf.ssr/suspense-boundary-failed (:operation ev)))
                               @captured)]
        (trace-tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
        (try
          ;; A failed template AND a VALID-MAP delta for the SAME id — the
          ;; contradictory shape. The delta WOULD merge for a successful
          ;; boundary; it must NOT for a failed one.
          (append-chunk!
            host
            (failed-chunk-with-delta-html
              :card.flaky "<div class=\"card card-failed\">unavailable</div>"
              {:cards {:flaky {:value 99}}}))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "card-failed"))
                  "the failed chunk's fallback HTML swapped in")
              (is (= {} (frame/frame-app-db-value fid))
                  "the failed boundary's delta is NOT merged (fail-closed)")
              (is (nil? @(rf/subscribe [:sct/card :flaky] {:frame fid}))
                  "a subscription reads no speculative state for the failed boundary")
              (is (zero? (count (array-seq (.querySelectorAll host (str "[" wire/attr-suspense-hydrate "]")))))
                  "the contradictory delta script is consumed (DOM left script-free)")
              ;; exactly one :quarantined-delta diagnostic.
              (let [q (filter #(= :quarantined-delta (:recovery %)) (failed-of))]
                (is (= 1 (count q))
                    (str "exactly one :quarantined-delta diagnostic; saw recoveries: "
                         (pr-str (mapv :recovery (failed-of))))))
              ;; the inline-fallback failed signal still fires exactly once.
              (let [f (filter #(= :inline-fallback (:recovery %)) (failed-of))]
                (is (= 1 (count f))
                    "the failed fallback swap still emits its :inline-fallback signal exactly once"))
              (finally (stop!))))
          (finally
            (trace-tooling/unregister-listener! k)
            (remove-root! host)))))))

(deftest failed-boundary-suppresses-delta-arriving-first
  (testing "rf2-x76af2.40 — order independence + observer batching: the
            hydrate-delta `<script>` arrives in a SEPARATE, EARLIER batch than
            the failed `<template>`. The first sweep must HOLD the delta (the
            boundary has not swapped → no outcome recorded); when the failed
            template arrives and swaps, the boundary's outcome is `:failed`, so
            the still-present delta is quarantined — never merged — regardless
            of arrival order. Proves the fail-closed rule is not FIFO-only."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid      (make-client-frame!)
              host     (make-root! [:card.flaky])
              captured (atom [])
              k        (str (gensym "stream-failfirst-cb"))]
          (trace-tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
          ;; Batch 1: ONLY the delta <script> — its (failed) template has not arrived.
          (append-chunk!
            host
            (ssr/streaming-hydrate-delta-script
              :card.flaky (pr-str {:cards {:flaky {:value 99}}})))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            ;; First sweep: boundary not swapped → delta held, not applied.
            (is (= {} (frame/frame-app-db-value fid))
                "delta held (boundary not swapped yet) — nothing merged speculatively")
            (is (= 1 (count (array-seq (.querySelectorAll host (str "[" wire/attr-suspense-hydrate "]")))))
                "the delta <script> waits in the DOM (not consumed before its swap)")
            ;; Batch 2: the FAILED template arrives → swaps → outcome :failed.
            (append-chunk!
              host
              (failed-chunk-html :card.flaky "<div class=\"card card-failed\">unavailable</div>"))
            (js/setTimeout
              (fn []
                (is (= 1 (card-count host "card-failed"))
                    "failed fallback swapped in the later sweep")
                (is (= {} (frame/frame-app-db-value fid))
                    "the delta (which arrived FIRST) is quarantined once its boundary resolved FAILED — never merged")
                (is (zero? (count (array-seq (.querySelectorAll host (str "[" wire/attr-suspense-hydrate "]")))))
                    "the contradictory delta script is consumed")
                ;; Scope the match to THIS boundary's own id. `captured` is a
                ;; PROCESS-GLOBAL trace listener — it observes every namespace's
                ;; events, including any deferred emit a sibling suite leaked into
                ;; this test's `js/setTimeout` settle window — so an
                ;; `:operation`+`:recovery`-only match could go green on a foreign
                ;; `:quarantined-delta` while THIS `:card.flaky` boundary emitted
                ;; none (the rf2-veyfp false-green). The producer carries the
                ;; authored boundary under `[:tags :id]` (see string-boundary-id
                ;; test); scoping by it is a pure narrowing — it can only reject a
                ;; foreign boundary, never loosen the subject match.
                (is (some #(and (= :rf.ssr/suspense-boundary-failed (:operation %))
                                (= :quarantined-delta (:recovery %))
                                (= :card.flaky (-> % :tags :id)))
                          @captured)
                    ":quarantined-delta diagnostic emitted for the :card.flaky failed-boundary delta")
                (stop!)
                (remove-root! host)
                (done))
              0)))))))

(deftest parseable-non-map-delta-fails-closed
  (testing "rf2-l3paoi — a resolved chunk whose hydrate-delta `<script>` body
            is PARSEABLE EDN but NOT a map (a vector / number / string —
            a server/client wire-shape regression) must fail CLOSED: the
            resolved HTML still swaps in (DOM progress), the delta is NOT
            merged (app-db stays unchanged), the script is consumed (DOM left
            script-free), AND a `:rf.ssr/suspense-boundary-failed`
            `:skipped-delta` trace fires. Before the fix `read-string`
            accepted the non-map, `merge-delta!`'s `(map? delta)` guard
            silently no-op'd the merge, and the script was removed with NO
            diagnostic — progressive hydration LOOKED successful in the DOM
            while subscriptions read stale state."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      ;; Each bad delta is parseable EDN but the wrong shape. Empty-string
      ;; / `nil` bodies are the legitimate no-delta shape (covered below),
      ;; so they are NOT in this malformed set.
      (doseq [bad-delta [[:not :a :map]
                         42
                         "a bare string"
                         :a-keyword]]
        (let [fid      (make-client-frame!)
              host     (make-root! [:card.revenue])
              captured (atom [])
              k        (str (gensym "stream-nonmap-cb"))]
          (trace-tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
          (try
            (append-chunk!
              host
              (resolved-chunk-html :card.revenue
                                   "<div class=\"card resolved-revenue\">Revenue</div>"
                                   bad-delta))
            (let [stop! (streaming-client/install! {:frame fid :root host})]
              (try
                ;; DOM still progresses — the resolved content swapped in.
                (is (= 1 (card-count host "resolved-revenue"))
                    (str (pr-str bad-delta)
                         ": resolved HTML still swaps in (DOM progress)"))
                (is (false? (boolean (showing-fallback? host :card.revenue)))
                    (str (pr-str bad-delta) ": mount shows resolved content, not a skeleton"))
                ;; app-db is UNCHANGED — the malformed delta was not merged.
                (is (= {} (frame/frame-app-db-value fid))
                    (str (pr-str bad-delta)
                         ": app-db must stay unchanged (malformed delta NOT merged)"))
                (is (nil? @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                    (str (pr-str bad-delta) ": subscription reads no speculative state"))
                ;; The script is consumed regardless of delta validity.
                (is (zero? (count (array-seq (.querySelectorAll host (str "[" wire/attr-suspense-hydrate "]")))))
                    (str (pr-str bad-delta) ": the delta script is dropped (DOM left script-free)"))
                ;; The fail-closed diagnostic fires.
                (let [skipped (filter #(and (= :rf.ssr/suspense-boundary-failed (:operation %))
                                            (= :skipped-delta (:recovery %)))
                                      @captured)]
                  (is (seq skipped)
                      (str (pr-str bad-delta)
                           ": must emit :rf.ssr/suspense-boundary-failed :skipped-delta; saw: "
                           (pr-str (mapv (juxt :operation :recovery) @captured)))))
                (finally (stop!))))
            (finally
              (trace-tooling/unregister-listener! k)
              (remove-root! host))))))))

(deftest empty-delta-body-is-not-malformed
  (testing "rf2-l3paoi — the fail-closed guard is PRECISE: an empty-map delta
            body (`{}`) is a legitimate no-op delta (no app-db change), and a
            VALID map delta still merges — neither emits the malformed
            diagnostic. This pins that the non-map fail-closed branch does not
            over-fire on the documented valid shapes."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid      (make-client-frame!)
            host     (make-root! [:card.empty :card.full])
            captured (atom [])
            k        (str (gensym "stream-empty-cb"))]
        (trace-tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
        (try
          ;; (a) empty-map delta — valid no-op, no app-db change, no trace.
          (append-chunk!
            host
            (resolved-chunk-html :card.empty
                                 "<div class=\"card resolved-empty\">empty</div>"
                                 {}))
          ;; (b) valid map delta — merges normally.
          (append-chunk!
            host
            (resolved-chunk-html :card.full
                                 "<div class=\"card resolved-full\">full</div>"
                                 {:cards {:full {:value 5}}}))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "resolved-empty")) "empty-delta chunk still swaps")
              (is (= 5 (:value @(rf/subscribe [:sct/card :full] {:frame fid}))) "valid delta merged")
              (is (not-any? #(= :rf.ssr/suspense-boundary-failed (:operation %)) @captured)
                  (str "valid + empty deltas must NOT emit the malformed diagnostic; saw: "
                       (pr-str (mapv :operation @captured))))
              (finally (stop!))))
          (finally
            (trace-tooling/unregister-listener! k)
            (remove-root! host)))))))

(deftest css-special-payload-id-does-not-throw
  (testing "rf2-58zvy1 finding 3 — a documented :payload-id override that is
            a VALID HTML id but carries CSS-selector-significant chars
            (`.`, `:`) must not make install! throw or mis-detect the
            final payload. The prior `(.querySelector root (str \"#\" id))`
            built a raw CSS selector, so `#rf:payload` / `#rf.payload`
            either threw a SyntaxError or selected the wrong element.
            install! now matches by exact id (getElementById on a Document
            root / id-scan on an element root)."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (doseq [pid ["rf:payload" "rf.payload" "rf payload" "rf[0]"]]
        (let [fid  (make-client-frame!)
              host (make-root! [:card.x])]
          (try
            ;; A resolved chunk AND a final payload using the CSS-special id
            ;; are already present — the stream-complete case for a custom
            ;; shell. install! must sweep the chunk and DETECT completion
            ;; without throwing on the selector.
            (append-chunk!
              host
              (resolved-chunk-html :card.x
                                   "<div class=\"card resolved-x\">x</div>"
                                   {:cards {:x {:value 1}}}))
            ;; Append a final-payload script carrying the CSS-special id.
            (append-chunk!
              host
              (str "<script id=\"" pid "\" type=\"application/edn\">"
                   "{:rf/version 1 :rf/app-db {} :rf/render-hash \"00000000\"}"
                   "</script>"))
            (let [stop! (streaming-client/install! {:frame fid :root host :payload-id pid})]
              (try
                ;; No throw is the headline assertion; the chunk still
                ;; swapped (the sweep ran before completion-detection).
                (is (= 1 (card-count host "resolved-x"))
                    (str "chunk swapped despite CSS-special payload-id " (pr-str pid)))
                (is (= 1 (:value @(rf/subscribe [:sct/card :x] {:frame fid})))
                    "delta merged")
                (finally (stop!))))
            (finally (remove-root! host))))))))

(deftest nested-resolved-chunks-recover-on-late-install
  (testing "rf2-58zvy1 finding 4 — when an OUTER and a NESTED INNER resolved
            chunk are both already in the DOM at install (a late-loading
            client), and the outer resolved HTML contains the inner
            FALLBACK template, install! must leave BOTH outer and inner
            content live with the inner delta merged. The prior code marked
            the inner id seen BEFORE its mount existed (the inner mount only
            appears once the outer swap moves the inner fallback into live
            DOM), so the inner swap failed and was skipped permanently —
            the nested boundary stuck on fallback. The sweep now marks seen
            only on a successful swap, re-materialises fallbacks introduced
            by a swap, and iterates to a fixpoint."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid  (make-client-frame!)
            ;; Only the OUTER boundary has an inline shell fallback. The
            ;; INNER boundary's fallback lives INSIDE the outer resolved
            ;; chunk's HTML — it is inert template content until the outer
            ;; mount swaps, exactly the nested wire shape.
            host (make-root! [:card.outer])
            ;; The outer resolved content embeds the inner fallback template.
            inner-fallback (ssr/streaming-fallback-template
                             :card.inner "<div class=\"card skeleton inner-skel\">loading inner</div>")
            outer-resolved-html (str "<section class=\"card resolved-outer\">outer "
                                     inner-fallback "</section>")]
        (try
          ;; Both chunks already present at install, NO final payload.
          ;; Append inner FIRST so document order puts the inner resolved
          ;; template before the outer one — the worst case for a single
          ;; document-order pass (inner processed before its mount exists).
          ;; Per the server contract each delta ships the FULL after-value
          ;; for the changed top-level key (`:cards`), so the client's
          ;; top-level `(into existing delta)` merge is lossless regardless
          ;; of which delta the fixpoint applies last (Spec 011 §Hydration
          ;; interleaving — same property the lossless-across-chunks test
          ;; pins). Using distinct nested values for outer/inner here proves
          ;; BOTH nested boundaries hydrated, not just one.
          (append-chunk!
            host
            (resolved-chunk-html :card.inner
                                 "<div class=\"card resolved-inner\">inner 99</div>"
                                 {:cards {:outer {:value 7} :inner {:value 99}}}))
          (append-chunk!
            host
            (resolved-chunk-html :card.outer
                                 outer-resolved-html
                                 {:cards {:outer {:value 7} :inner {:value 99}}}))
          (is (nil? (.querySelector host (str "#" constants/payload-script-id)))
              "no final payload — recovery is purely the sweep's doing")
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "resolved-outer"))
                  "outer resolved content is live")
              (is (= 1 (card-count host "resolved-inner"))
                  "inner resolved content is live — the nested boundary recovered")
              (is (= 0 (card-count host "inner-skel"))
                  "the inner skeleton fallback was replaced, not stranded")
              (is (= 7 (:value @(rf/subscribe [:sct/card :outer] {:frame fid})))
                  "outer delta merged")
              (is (= 99 (:value @(rf/subscribe [:sct/card :inner] {:frame fid})))
                  "inner delta merged — progressive hydration of the nested boundary")
              (finally (stop!))))
          (finally (remove-root! host)))))))

(defn- mounts-for
  "Every live `<rf-suspense data-rf2-suspense-mount=\"<id>\">` wrapper for
  boundary `id` under `host`, in document order. Duplicate-id boundaries
  produce more than one — the placement-coherence assertions (rf2-8en9mu)
  read both."
  [host id]
  (array-seq
    (.querySelectorAll host (str "[" wire/attr-suspense-mount "=\"" (pr-str id) "\"]"))))

(defn- inert-fallback-template-count
  "Count of un-materialised fallback `<template>`s still in the DOM. After a
  sweep this MUST be zero — every fallback template is consumed into a live
  mount (rf2-8en9mu: a duplicate-id boundary must not leave a stray inert
  template)."
  [host]
  (count (array-seq (.querySelectorAll host (str "[" wire/attr-suspense-fallback "]")))))

(deftest fallback-is-inert-template-before-js-then-painted-on-install
  (testing "rf2-xzhf2a — the no-JS / first-byte contract. The shell's
            fallback markup is delivered ONLY inside an inert
            `<template data-rf2-suspense-fallback>` — its content is NOT
            painted DOM until the client runtime runs. We distinguish the two:
            before install, the skeleton lives inside a `<template>` (its
            `.content` is a detached DocumentFragment — `querySelector` on the
            live tree does NOT find it) and there is NO live `<rf-suspense>`
            mount; after install, the same markup is materialised into a live,
            paintable `<rf-suspense data-rf2-suspense-mount>` mount."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid  (make-client-frame!)
            host (make-root! [:card.revenue])]
        (try
          ;; BEFORE install (the no-JS / first-byte state): the fallback skeleton
          ;; is inert template content — NOT in the live painted DOM.
          (is (some? (.querySelector host (str "[" wire/attr-suspense-fallback "]")))
              "the inert fallback <template> is present in the shell")
          (is (nil? (.querySelector host ".skeleton"))
              "the skeleton is INERT template content — not painted live DOM before JS")
          (is (nil? (mount-for host :card.revenue))
              "no live <rf-suspense> mount exists before the client runs")
          ;; AFTER install: the runtime materialises the inert template into a
          ;; live, painted mount — the skeleton is now real DOM.
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              (is (zero? (inert-fallback-template-count host))
                  "the inert fallback <template> is consumed on install")
              (is (some? (mount-for host :card.revenue))
                  "a live <rf-suspense> mount now exists")
              (is (some? (.querySelector host ".skeleton"))
                  "the skeleton is now PAINTED live DOM (inside the live mount)")
              (is (true? (showing-fallback? host :card.revenue))
                  "the boundary shows its (now live, visible) fallback")
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest duplicate-id-resolves-into-last-boundary
  (testing "rf2-8en9mu — two suspense boundaries declared with the SAME id.
            The server's `dedupe-continuations` keeps the LAST registration
            (last-write-wins, Spec 011 §Boundary nesting and recursion), so
            exactly ONE resolved chunk for that id streams in. The shell still
            carries TWO fallback `<template>`s (one per boundary). The client
            must (a) materialise BOTH fallbacks into visible live mounts (no
            stray inert template stranded), and (b) swap the single resolved
            chunk into the LAST boundary's mount — the registration that won —
            leaving the EARLIER mount showing its fallback. Before the fix the
            resolved content landed in the FIRST mount (querySelectorAll
            document order) and the second fallback template was left inert."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid  (make-client-frame!)
            ;; Two boundaries, SAME id — make-root! emits a fallback template
            ;; per id, so [:card.dupe :card.dupe] yields two same-id templates.
            host (make-root! [:card.dupe :card.dupe])]
        (try
          ;; The server emitted only the LAST continuation's resolved chunk
          ;; (dedupe keeps last). It arrives before install (initial sweep).
          (append-chunk!
            host
            (resolved-chunk-html :card.dupe
                                 "<div class=\"card resolved-dupe\">resolved 7</div>"
                                 {:cards {:dupe {:value 7}}}))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            (try
              ;; (a) BOTH fallbacks materialised — no stray inert template.
              (is (zero? (inert-fallback-template-count host))
                  "no inert fallback <template> left behind for the duplicate id")
              (let [ms (mounts-for host :card.dupe)]
                (is (= 2 (count ms)) "two live mounts — one per boundary")
                ;; (b) the resolved content is in the LAST mount; the first
                ;; mount still shows its skeleton fallback.
                (let [first-mount (first ms)
                      last-mount  (last ms)]
                  (is (some? (.querySelector first-mount ".skeleton"))
                      "earlier boundary still shows its fallback skeleton")
                  (is (nil? (.querySelector first-mount ".resolved-dupe"))
                      "earlier boundary did NOT receive the resolved content")
                  (is (some? (.querySelector last-mount ".resolved-dupe"))
                      "the LAST boundary (the won registration) received the resolved content")
                  (is (nil? (.querySelector last-mount ".skeleton"))
                      "the last boundary no longer shows its fallback")))
              ;; exactly one resolved-dupe node total — not duplicated.
              (is (= 1 (card-count host "resolved-dupe"))
                  "the single resolved chunk is placed exactly once")
              ;; delta still merged (placement bug must not regress hydration).
              (is (= 7 (:value @(rf/subscribe [:sct/card :dupe] {:frame fid})))
                  "the resolved chunk's delta merged")
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest string-boundary-id-preserves-type-in-trace
  (testing "rf2-m96yhw — a STRING suspense id must keep its string type in
            client-side trace payloads, while a KEYWORD id parses back to a
            keyword. The malformed-delta + failed-boundary trace paths decode
            the id via `read-boundary-id`; before the fix a bare string id
            (`card-revenue`, emitted via `(str id)`) parsed to an EDN SYMBOL,
            so the trace `:id` carried the wrong type."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      ;; Drive a STRING id and a KEYWORD id through the failed-boundary path
      ;; (which decodes the attribute via read-boundary-id for its :id).
      (doseq [[id expected-pred shape]
              [["card-revenue" string? "string id"]
               [:card/revenue  keyword? "keyword id"]]]
        (let [fid      (make-client-frame!)
              host     (make-root! [id])
              captured (atom [])
              k        (str (gensym "stream-idtype-cb"))]
          (trace-tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
          (try
            ;; A failed-boundary chunk → process-resolved-template! emits
            ;; :rf.ssr/suspense-boundary-failed with :id (read-boundary-id …).
            ;; `make-root!` seeds a fallback <template> for `id`, so the
            ;; initial sweep materialises its live mount BEFORE the failed
            ;; chunk is processed and the fallback swap SUCCEEDS — the failed
            ;; trace is gated on swap success (rf2-8ymnem; the shipped FIFO
            ;; shape always materialises a mount first).
            (append-chunk!
              host
              (failed-chunk-html id "<div class=\"card card-failed\">unavailable</div>"))
            (let [stop! (streaming-client/install! {:frame fid :root host})]
              (try
                ;; The boundary id lands in the trace envelope's PAYLOAD —
                ;; `[:tags :id]` — not the top-level `:id` (a trace-sequence
                ;; counter). `emit-error!` stamps caller payload under `:tags`.
                (let [ev     (some #(when (= :rf.ssr/suspense-boundary-failed (:operation %)) %)
                                   @captured)
                      ev-id  (get-in ev [:tags :id])]
                  (is (some? ev) (str shape ": failed-boundary trace emitted"))
                  (is (= id ev-id)
                      (str shape ": trace :id round-trips to the authored id; got "
                           (pr-str ev-id)))
                  (is (expected-pred ev-id)
                      (str shape ": trace :id has the right type; got "
                           (pr-str (type ev-id)))))
                (finally (stop!))))
            (finally
              (trace-tooling/unregister-listener! k)
              (remove-root! host))))))))

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
              (is (= 7 (:value @(rf/subscribe [:sct/card :late] {:frame fid})))
                  "delta merged by the observer-driven sweep")
              (stop!)
              (remove-root! host)
              (done))
            0))))))

(deftest split-batch-template-first-delta-later
  (testing "rf2-x76af2.35 — a boundary's resolved `<template>` and its
            hydrate-delta `<script>` arrive in SEPARATE observer batches
            (the server flushes them as two `.flush`ed chunks), TEMPLATE
            FIRST. The template is swapped + REMOVED + marked `seen` in the
            first sweep; the delta arrives in a LATER sweep, by which point
            the template node is gone. The delta must STILL merge into
            app-db. Before the fix the delta was applied only as a side
            effect of processing the resolved template, so a delta landing
            after the template was swept was orphaned and its `into` merge
            was lost (silent progressive-hydration failure until the final
            payload self-healed it)."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid  (make-client-frame!)
              host (make-root! [:card.revenue])]
          ;; Batch 1: ONLY the resolved <template> is present at install —
          ;; its delta <script> has NOT streamed in yet.
          (append-chunk!
            host
            (ssr/streaming-resolved-template
              :card.revenue "<div class=\"card resolved-revenue\">Revenue 42375</div>"))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            ;; After the synchronous initial sweep: the template swapped in,
            ;; but app-db is still empty — the delta is in a later chunk.
            (is (= 1 (card-count host "resolved-revenue"))
                "template swapped in the first sweep")
            (is (false? (boolean (showing-fallback? host :card.revenue)))
                "revenue mount shows resolved content, not a skeleton")
            (is (nil? @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                "no delta yet — its <script> is in a later batch")
            ;; Batch 2: the delta <script> arrives AFTER the template was
            ;; swapped + removed. The observer fires a fresh sweep.
            (append-chunk!
              host
              (ssr/streaming-hydrate-delta-script
                :card.revenue (pr-str {:cards {:revenue {:title "Revenue" :value 42375}}})))
            ;; `setTimeout 0` (macrotask) runs after the observer microtask,
            ;; so the second-batch sweep has definitely run by the continuation.
            (js/setTimeout
              (fn []
                (is (= {:title "Revenue" :value 42375}
                       @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                    "the delta merged even though it arrived in a LATER batch than its template")
                (is (zero? (count (array-seq
                                    (.querySelectorAll host (str "[" wire/attr-suspense-hydrate "]")))))
                    "the delta <script> was consumed (DOM left script-free)")
                (stop!)
                (remove-root! host)
                (done))
              0)))))))

(deftest split-batch-delta-first-template-later
  (testing "rf2-x76af2.35 — the same two chunks in SEPARATE observer batches,
            DELTA FIRST. The delta `<script>` is present before its resolved
            `<template>`. The first sweep must NOT apply it (the boundary has
            not swapped — its id is not yet `seen`), and the delta must WAIT
            in the DOM. When the template arrives in a later sweep and swaps,
            the still-present delta must merge. Proves the sweep-level delta
            scan is `seen`-gated (no speculative-before-swap application) yet
            still recovers the deferred delta."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid  (make-client-frame!)
              host (make-root! [:card.revenue])]
          ;; Batch 1: ONLY the delta <script> is present at install — its
          ;; resolved <template> has NOT streamed in yet.
          (append-chunk!
            host
            (ssr/streaming-hydrate-delta-script
              :card.revenue (pr-str {:cards {:revenue {:title "Revenue" :value 42375}}})))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            ;; After the synchronous initial sweep: no resolved template yet,
            ;; so the boundary is not `seen` and the delta is HELD (not applied).
            (is (nil? @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                "delta held until its template swaps — not applied speculatively before the swap")
            (is (true? (showing-fallback? host :card.revenue))
                "the boundary still shows its fallback — no resolved template yet")
            (is (= 1 (count (array-seq
                              (.querySelectorAll host (str "[" wire/attr-suspense-hydrate "]")))))
                "the delta <script> waits in the DOM (not consumed before its swap)")
            ;; Batch 2: the resolved <template> arrives. The observer fires;
            ;; the swap marks the id `seen`, then the still-present delta merges.
            (append-chunk!
              host
              (ssr/streaming-resolved-template
                :card.revenue "<div class=\"card resolved-revenue\">Revenue 42375</div>"))
            (js/setTimeout
              (fn []
                (is (= 1 (card-count host "resolved-revenue"))
                    "template swapped in the later sweep")
                (is (= {:title "Revenue" :value 42375}
                       @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                    "the delta (which arrived FIRST) merged once its template swapped")
                (is (zero? (count (array-seq
                                    (.querySelectorAll host (str "[" wire/attr-suspense-hydrate "]")))))
                    "the delta <script> was consumed once applied")
                (stop!)
                (remove-root! host)
                (done))
              0)))))))

(deftest failed-boundary-trace-emits-exactly-once-when-mount-arrives-late
  (testing "rf2-8ymnem — the failed-boundary trace is gated on a SUCCESSFUL
            fallback swap. A resolved-failed <template> whose live mount does
            not exist yet (the nested-boundary race / out-of-order-stream
            shape) emits NOTHING — it stays un-`seen` and retryable — and
            emits EXACTLY ONCE when a later sweep materialises the mount and
            the swap lands. Before the fix the failed arm fired on every sweep
            regardless of swap success, so a no-mount failed chunk re-emitted
            :rf.ssr/suspense-boundary-failed on each MutationObserver
            re-sweep (multi-emit). The shipped FIFO emitter never hits this
            (a fallback template always materialises a mount first), so the
            bug is latent — this drives the non-FIFO shape directly."
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid          (make-client-frame!)
              id           :card.racey
              host         (.createElement js/document "div")
              captured     (atom [])
              k            (str (gensym "stream-exactly-once-cb"))
              ;; Scope the count to THIS boundary's own id. `captured` is a
              ;; PROCESS-GLOBAL trace listener — it observes every namespace's
              ;; events, including any deferred emit a sibling suite leaked into
              ;; this test's `js/setTimeout` settle windows — so an
              ;; `:operation`-only count measures the SUITE, not this boundary.
              ;; The `(= 1 (failed-count))` below is the SOLE detector that the
              ;; trace fired on the successful swap, so an unscoped count could
              ;; go green on a foreign padding while THIS boundary emitted none
              ;; (the rf2-veyfp false-green). The failed trace carries the
              ;; authored id under `[:tags :id]` (see string-boundary-id test).
              failed-count #(count (filter (fn [ev]
                                             (and (= :rf.ssr/suspense-boundary-failed
                                                     (:operation ev))
                                                  (= id (-> ev :tags :id))))
                                           @captured))]
          ;; NO fallback <template> for `id` yet — the failed chunk arrives
          ;; before any live mount exists.
          (set! (.-innerHTML host) "<div id=\"app\"><main></main></div>")
          (.appendChild (.-body js/document) host)
          (trace-tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
          (append-chunk!
            host
            (failed-chunk-html id "<div class=\"card card-failed\">unavailable</div>"))
          (let [stop! (streaming-client/install! {:frame fid :root host})]
            ;; Sweep 1 (synchronous initial sweep): no mount → swapped? false
            ;; → the failed arm is gated → NO trace, and the failed <template>
            ;; stays retryable.
            (is (zero? (failed-count))
                "no failed trace while the failed chunk has no live mount to swap into")
            ;; Force a SECOND sweep with the mount STILL absent (an unrelated
            ;; DOM mutation drives the observer). The OLD code re-emitted here.
            (append-chunk! host "<div class=\"noise\"></div>")
            (js/setTimeout
              (fn []
                (is (zero? (failed-count))
                    "a re-sweep with the mount still absent still emits nothing (no multi-emit)")
                ;; Now the fallback <template> for `id` streams in. The next
                ;; sweep materialises its mount, then re-processes the still-
                ;; retryable failed chunk → swap succeeds → EXACTLY ONE trace.
                (append-chunk!
                  host
                  (ssr/streaming-fallback-template
                    id "<div class=\"card skeleton\">loading</div>"))
                (js/setTimeout
                  (fn []
                    (is (= 1 (card-count host "card-failed"))
                        "the failed fallback HTML swapped into the now-materialised mount")
                    (is (= 1 (failed-count))
                        "the failed-boundary trace fired EXACTLY once — on the successful swap")
                    (stop!)
                    (remove-root! host)
                    (done))
                  0))
              0)))))))
