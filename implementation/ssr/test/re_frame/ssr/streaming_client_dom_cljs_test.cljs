(ns re-frame.ssr.streaming-client-dom-cljs-test
  "The client streaming-SSR runtime (`re-frame.ssr.streaming.client/install!`,
  Spec 011 §Streaming SSR — client-side hydration): a resolved chunk swaps its
  subtree in and merges its app-db delta BEFORE the final payload lands, a
  failed chunk swaps its fallback and merges nothing, and both hold under any
  batching or arrival order — including elements the HTML parser has only
  half-parsed.

  Chunks are built with the server facade fns the Ring writer uses, so the
  client reads the real wire bytes. `:node-test` loads this file too and
  every test exits early without `js/document`."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.constants :as rf.ssr.constants]
            [re-frame.ssr.streaming.constants :as rf.ssr.streaming.constants]
            [re-frame.ssr.streaming.client :as rf.ssr.streaming.client]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Map form, because some tests are async.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- chunk authoring, through the server facade ----------------------------

(defn- shell-with-fallbacks
  "The shell, its fallbacks inline `<template>`s from `streaming-fallback-template`."
  [ids]
  (str "<main class=\"dashboard\"><section class=\"cards\">"
       (->> ids
            (map (fn [id]
                   (rf.ssr/streaming-fallback-template
                     id (str "<div class=\"card skeleton\">loading " (name id) "</div>"))))
            (str/join ""))
       "</section></main>"))

(defn- resolved-chunk-html
  "A resolved chunk: the resolved `<template>` and its hydrate-delta `<script>`."
  [id resolved-html delta]
  (str (rf.ssr/streaming-resolved-template id resolved-html)
       (rf.ssr/streaming-hydrate-delta-script id (pr-str delta))))

(defn- failed-chunk-html
  "A failed chunk: the failed `<template>` and no delta."
  [id fallback-html]
  (rf.ssr/streaming-failed-template id fallback-html))

(defn- failed-chunk-with-delta-html
  "A stream the server never emits: a failed `<template>` plus a delta for
  the same id."
  [id fallback-html delta]
  (str (rf.ssr/streaming-failed-template id fallback-html)
       (rf.ssr/streaming-hydrate-delta-script id (pr-str delta))))

;; ---- DOM scaffolding -------------------------------------------------------

(defn- make-root!
  "An attached `#app` host holding the shell with `ids`' fallbacks."
  [ids]
  (let [host (.createElement js/document "div")]
    (set! (.-innerHTML host) (str "<div id=\"app\">" (shell-with-fallbacks ids) "</div>"))
    (.appendChild (.-body js/document) host)
    host))

(defn- append-chunk!
  "Append `chunk-html`'s nodes to `host`: a chunk arriving whole."
  [host chunk-html]
  (let [parser (.createElement js/document "template")]
    (set! (.-innerHTML parser) chunk-html)
    (.appendChild host (.-content parser))))

(defn- card-count [host cls]
  (count (array-seq (.querySelectorAll host (str "." cls)))))

(defn- mount-for [host id]
  (.querySelector host (str "[" rf.ssr.streaming.constants/attr-suspense-mount "=\"" (pr-str id) "\"]")))

(defn- showing-fallback?
  "True when boundary `id`'s live mount still holds its skeleton."
  [host id]
  (when-let [m (mount-for host id)]
    (some? (.querySelector m ".skeleton"))))

(defn- remove-root! [host]
  (when-let [p (.-parentNode host)]
    (.removeChild p host)))

;; ---- frame setup -----------------------------------------------------------

(defn- make-client-frame!
  "A `:client` frame with an empty app-db and a `:sct/card` sub."
  []
  (let [fid (keyword "rf.frame" (str (gensym "stream-client-")))]
    ;; Registered first: an `rf.frame`-namespaced id is a direct frame, whose
    ;; image is sealed at construction.
    (rf/reg-sub :sct/card (fn [db [_ id]] (get-in db [:cards id])))
    (rf/make-frame {:id fid :doc "streaming-client-test frame" :platform :client})
    fid))

;; ---- chunks present at install: the synchronous initial sweep ---------------
;;
;; Most tests put their chunks in place before `install!`, so its initial sweep
;; processes them deterministically; the observer runs the same sweep
;; (`async-observer-applies-late-chunk`).

(deftest progressive-hydration-happens-before-final-payload
  (testing "a resolved chunk present at install, with no final payload, is
            swapped in and its delta merged"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (let [fid  (make-client-frame!)
            host (make-root! [:card.revenue :card.signups])]
        (try
          (append-chunk!
            host
            (resolved-chunk-html :card.revenue
                                 "<div class=\"card resolved-revenue\">Revenue 42375</div>"
                                 {:cards {:revenue {:title "Revenue" :value 42375}}}))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "resolved-revenue")))
              (is (true? (showing-fallback? host :card.signups))
                  "the card with no chunk still shows its skeleton")
              (is (= 1 (card-count host "skeleton")) "exactly one card still a fallback")
              (is (= {:title "Revenue" :value 42375}
                     @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                  "a subscription reads the delta before any final payload")
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest into-merge-is-lossless-across-chunks
  (testing "each delta carries the full after-value of its changed top-level
            key, so the top-level `into` merge keeps both cards (Spec 011
            §Hydration interleaving)"
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
          (append-chunk!
            host
            (resolved-chunk-html :card.signups
                                 "<div class=\"card resolved-signups\">Signups</div>"
                                 {:cards {:revenue {:value 42375}
                                          :signups {:value 318}}}))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (= {:cards {:revenue {:value 42375} :signups {:value 318}}}
                     (rf.frame/frame-app-db-value fid)))
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest failed-boundary-suppresses-matching-hydration-delta
  (testing "a failed chunk swaps its fallback and emits `:inline-fallback`
            once; a delta paired with it (a stream the server never emits) is
            quarantined — consumed, never merged — with one `:quarantined-delta`"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid       (make-client-frame!)
            host      (make-root! [:card.flaky])
            captured  (atom [])
            k         (str (gensym "stream-failquarantine-cb"))
            failed-of #(filter (fn [ev] (= :rf.ssr/suspense-boundary-failed (:operation ev)))
                               @captured)]
        (rf.trace.tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
        (try
          (append-chunk!
            host
            (failed-chunk-with-delta-html
              :card.flaky "<div class=\"card card-failed\">unavailable</div>"
              {:cards {:flaky {:value 99}}}))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "card-failed")))
              (is (= {} (rf.frame/frame-app-db-value fid)) "the delta is NOT merged")
              (is (zero? (count (array-seq (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-hydrate "]")))))
                  "the delta script is consumed")
              (is (= 1 (count (filter #(= :quarantined-delta (:recovery %)) (failed-of))))
                  (str "saw recoveries: " (pr-str (mapv :recovery (failed-of)))))
              (is (= 1 (count (filter #(= :inline-fallback (:recovery %)) (failed-of)))))
              (finally (stop!))))
          (finally
            (rf.trace.tooling/unregister-listener! k)
            (remove-root! host)))))))

(deftest failed-boundary-suppresses-delta-arriving-first
  (testing "a delta that arrives in an EARLIER batch than its failed template
            is held, then quarantined once the boundary resolves failed —
            fail-closed regardless of arrival order"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid      (make-client-frame!)
              host     (make-root! [:card.flaky])
              captured (atom [])
              k        (str (gensym "stream-failfirst-cb"))]
          (rf.trace.tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
          (append-chunk!
            host
            (rf.ssr/streaming-hydrate-delta-script
              :card.flaky (pr-str {:cards {:flaky {:value 99}}})))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (append-chunk!
              host
              (failed-chunk-html :card.flaky "<div class=\"card card-failed\">unavailable</div>"))
            (js/setTimeout
              (fn []
                (is (= 1 (card-count host "card-failed")))
                (is (= {} (rf.frame/frame-app-db-value fid)) "the delta was never merged")
                (is (zero? (count (array-seq (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-hydrate "]")))))
                    "the delta script is consumed")
                ;; The listener is process-global, so match THIS boundary's id.
                (is (some #(and (= :rf.ssr/suspense-boundary-failed (:operation %))
                                (= :quarantined-delta (:recovery %))
                                (= :card.flaky (-> % :tags :id)))
                          @captured))
                (stop!)
                (remove-root! host)
                (done))
              0)))))))

(deftest parseable-non-map-delta-fails-closed
  (testing "a delta that parses but is not a map is not merged; the resolved
            HTML still swaps, the script is consumed, and a `:skipped-delta`
            failure is traced, so the miss is visible"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid      (make-client-frame!)
            host     (make-root! [:card.revenue])
            captured (atom [])
            k        (str (gensym "stream-nonmap-cb"))]
        (rf.trace.tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
        (try
          (append-chunk!
            host
            (resolved-chunk-html :card.revenue
                                 "<div class=\"card resolved-revenue\">Revenue</div>"
                                 [:not :a :map]))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "resolved-revenue")))
              (is (= {} (rf.frame/frame-app-db-value fid)))
              (is (zero? (count (array-seq (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-hydrate "]")))))
                  "the delta script is consumed")
              (is (some #(and (= :rf.ssr/suspense-boundary-failed (:operation %))
                              (= :skipped-delta (:recovery %)))
                        @captured)
                  (str "saw: " (pr-str (mapv (juxt :operation :recovery) @captured))))
              (finally (stop!))))
          (finally
            (rf.trace.tooling/unregister-listener! k)
            (remove-root! host)))))))

(deftest empty-delta-body-is-not-malformed
  (testing "an empty-map delta is a valid no-op and a map delta merges;
            neither emits the failure trace"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid      (make-client-frame!)
            host     (make-root! [:card.empty :card.full])
            captured (atom [])
            k        (str (gensym "stream-empty-cb"))]
        (rf.trace.tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
        (try
          (append-chunk!
            host
            (resolved-chunk-html :card.empty
                                 "<div class=\"card resolved-empty\">empty</div>"
                                 {}))
          (append-chunk!
            host
            (resolved-chunk-html :card.full
                                 "<div class=\"card resolved-full\">full</div>"
                                 {:cards {:full {:value 5}}}))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "resolved-empty")) "empty-delta chunk still swaps")
              (is (= 5 (:value @(rf/subscribe [:sct/card :full] {:frame fid}))) "valid delta merged")
              (is (not-any? #(= :rf.ssr/suspense-boundary-failed (:operation %)) @captured)
                  (str "saw: " (pr-str (mapv :operation @captured))))
              (finally (stop!))))
          (finally
            (rf.trace.tooling/unregister-listener! k)
            (remove-root! host)))))))

(deftest css-special-payload-id-does-not-throw
  (testing "a `:payload-id` that is a valid HTML id but not a valid CSS id
            selector is matched by exact id, not through a selector"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [pid  "rf:payload"
            fid  (make-client-frame!)
            host (make-root! [:card.x])]
        (try
          (append-chunk!
            host
            (resolved-chunk-html :card.x
                                 "<div class=\"card resolved-x\">x</div>"
                                 {:cards {:x {:value 1}}}))
          (append-chunk!
            host
            (str "<script id=\"" pid "\" type=\"application/edn\">"
                 "{:rf/version 1 :rf/app-db {} :rf/render-hash \"00000000\"}"
                 "</script>"))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host :payload-id pid})]
            (try
              (is (= 1 (card-count host "resolved-x")))
              (is (= 1 (:value @(rf/subscribe [:sct/card :x] {:frame fid}))))
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest nested-resolved-chunks-recover-on-late-install
  (testing "an outer chunk whose resolved HTML holds an inner boundary's
            fallback, with the inner chunk earlier in document order: the
            sweep iterates to a fixpoint, so both boundaries resolve"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid  (make-client-frame!)
            host (make-root! [:card.outer])
            inner-fallback (rf.ssr/streaming-fallback-template
                             :card.inner "<div class=\"card skeleton inner-skel\">loading inner</div>")
            outer-resolved-html (str "<section class=\"card resolved-outer\">outer "
                                     inner-fallback "</section>")]
        (try
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
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (= 1 (card-count host "resolved-outer")))
              (is (= 1 (card-count host "resolved-inner")))
              (is (= 0 (card-count host "inner-skel")) "the inner skeleton was not stranded")
              (is (= {:cards {:outer {:value 7} :inner {:value 99}}}
                     (rf.frame/frame-app-db-value fid)))
              (finally (stop!))))
          (finally (remove-root! host)))))))

(defn- mounts-for
  "Every live mount for boundary `id` under `host`, in document order."
  [host id]
  (array-seq
    (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-mount "=\"" (pr-str id) "\"]"))))

(defn- inert-fallback-template-count [host]
  (count (array-seq (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-fallback "]")))))

(deftest fallback-is-inert-template-before-js-then-painted-on-install
  (testing "before the client runs, the fallback is inert `<template>`
            content; `install!` consumes the template into a live, painted mount"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid  (make-client-frame!)
            host (make-root! [:card.revenue])]
        (try
          (is (nil? (.querySelector host ".skeleton"))
              "the skeleton is not painted DOM before JS")
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (zero? (inert-fallback-template-count host)))
              (is (true? (showing-fallback? host :card.revenue)))
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest duplicate-id-resolves-into-last-boundary
  (testing "two boundaries with one id: both fallbacks are materialised, and
            the single resolved chunk lands in the LAST one, the registration
            the server's last-write-wins dedupe kept (Spec 011 §Boundary
            nesting and recursion)"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (let [fid  (make-client-frame!)
            host (make-root! [:card.dupe :card.dupe])]
        (try
          (append-chunk!
            host
            (resolved-chunk-html :card.dupe
                                 "<div class=\"card resolved-dupe\">resolved 7</div>"
                                 {:cards {:dupe {:value 7}}}))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (try
              (is (zero? (inert-fallback-template-count host)))
              (is (= [[true false] [false true]]
                     (mapv (fn [m] [(some? (.querySelector m ".skeleton"))
                                    (some? (.querySelector m ".resolved-dupe"))])
                           (mounts-for host :card.dupe)))
                  "[skeleton? resolved?] per mount, in document order")
              (is (= 1 (card-count host "resolved-dupe")) "placed exactly once")
              (is (= 7 (:value @(rf/subscribe [:sct/card :dupe] {:frame fid}))))
              (finally (stop!))))
          (finally (remove-root! host)))))))

(deftest string-boundary-id-preserves-type-in-trace
  (testing "the failure trace carries the authored boundary id with its type:
            a string id stays a string (a plain EDN read would make it a
            symbol), a keyword id a keyword"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (doseq [id ["card-revenue" :card/revenue]]
        (let [fid      (make-client-frame!)
              host     (make-root! [id])
              captured (atom [])
              k        (str (gensym "stream-idtype-cb"))]
          (rf.trace.tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
          (try
            (append-chunk!
              host
              (failed-chunk-html id "<div class=\"card card-failed\">unavailable</div>"))
            (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
              (try
                ;; The boundary id rides the trace payload, under `:tags`.
                (is (= id (some #(when (= :rf.ssr/suspense-boundary-failed (:operation %))
                                   (get-in % [:tags :id]))
                                @captured)))
                (finally (stop!))))
            (finally
              (rf.trace.tooling/unregister-listener! k)
              (remove-root! host))))))))

(deftest async-observer-applies-late-chunk
  (testing "a chunk arriving after install is swapped and merged by the
            MutationObserver-driven sweep"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid   (make-client-frame!)
              host  (make-root! [:card.late])
              stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
          (append-chunk!
            host
            (resolved-chunk-html :card.late
                                 "<div class=\"card resolved-late\">late 7</div>"
                                 {:cards {:late {:value 7}}}))
          ;; A macrotask runs after the observer's microtask.
          (js/setTimeout
            (fn []
              (is (= 1 (card-count host "resolved-late")))
              (is (= 7 (:value @(rf/subscribe [:sct/card :late] {:frame fid}))))
              (stop!)
              (remove-root! host)
              (done))
            0))))))

(deftest split-batch-template-first-delta-later
  (testing "a delta arriving in a LATER batch than its already-swapped
            template still merges"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid  (make-client-frame!)
              host (make-root! [:card.revenue])]
          (append-chunk!
            host
            (rf.ssr/streaming-resolved-template
              :card.revenue "<div class=\"card resolved-revenue\">Revenue 42375</div>"))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (is (= 1 (card-count host "resolved-revenue"))
                "template swapped in the first sweep")
            (append-chunk!
              host
              (rf.ssr/streaming-hydrate-delta-script
                :card.revenue (pr-str {:cards {:revenue {:title "Revenue" :value 42375}}})))
            (js/setTimeout
              (fn []
                (is (= {:title "Revenue" :value 42375}
                       @(rf/subscribe [:sct/card :revenue] {:frame fid})))
                (is (zero? (count (array-seq
                                    (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-hydrate "]")))))
                    "the delta <script> was consumed (DOM left script-free)")
                (stop!)
                (remove-root! host)
                (done))
              0)))))))

(deftest split-batch-delta-first-template-later
  (testing "a delta arriving in an EARLIER batch than its template is held in
            the DOM, not applied before the swap, and merges once it lands"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid  (make-client-frame!)
              host (make-root! [:card.revenue])]
          (append-chunk!
            host
            (rf.ssr/streaming-hydrate-delta-script
              :card.revenue (pr-str {:cards {:revenue {:title "Revenue" :value 42375}}})))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            (is (nil? @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                "not applied before its template swaps")
            (is (= 1 (count (array-seq
                              (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-hydrate "]")))))
                "the delta <script> waits in the DOM")
            (append-chunk!
              host
              (rf.ssr/streaming-resolved-template
                :card.revenue "<div class=\"card resolved-revenue\">Revenue 42375</div>"))
            (js/setTimeout
              (fn []
                (is (= {:title "Revenue" :value 42375}
                       @(rf/subscribe [:sct/card :revenue] {:frame fid})))
                (is (zero? (count (array-seq
                                    (.querySelectorAll host (str "[" rf.ssr.streaming.constants/attr-suspense-hydrate "]")))))
                    "the delta <script> was consumed once applied")
                (stop!)
                (remove-root! host)
                (done))
              0)))))))

(deftest failed-boundary-trace-emits-exactly-once-when-mount-arrives-late
  (testing "a failed chunk with no live mount yet emits nothing, on any
            number of re-sweeps, and emits exactly once when a later sweep
            materialises its mount and the swap lands"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid          (make-client-frame!)
              id           :card.racey
              host         (.createElement js/document "div")
              captured     (atom [])
              k            (str (gensym "stream-exactly-once-cb"))
              ;; The listener is process-global, so count THIS boundary's id.
              failed-count #(count (filter (fn [ev]
                                             (and (= :rf.ssr/suspense-boundary-failed
                                                     (:operation ev))
                                                  (= id (-> ev :tags :id))))
                                           @captured))]
          (set! (.-innerHTML host) "<div id=\"app\"><main></main></div>")
          (.appendChild (.-body js/document) host)
          (rf.trace.tooling/register-listener! k (fn [ev] (swap! captured conj ev)))
          (append-chunk!
            host
            (failed-chunk-html id "<div class=\"card card-failed\">unavailable</div>"))
          (let [stop! (rf.ssr.streaming.client/install! {:frame fid :root host})]
            ;; An unrelated mutation forces a second sweep, mount still absent.
            (append-chunk! host "<div class=\"noise\"></div>")
            (js/setTimeout
              (fn []
                (is (zero? (failed-count))
                    "two sweeps with no mount emitted nothing")
                (append-chunk!
                  host
                  (rf.ssr/streaming-fallback-template
                    id "<div class=\"card skeleton\">loading</div>"))
                (js/setTimeout
                  (fn []
                    (is (= 1 (card-count host "card-failed")))
                    (is (= 1 (failed-count)) "emitted exactly once, on the successful swap")
                    (stop!)
                    (remove-root! host)
                    (done))
                  0))
              0)))))))

;; ---- a REAL incremental parse ----------------------------------------------
;;
;; The tests above append each chunk whole. The HTML parser instead INSERTS a
;; `<script>` or `<template>` at its start tag and fills it as bytes arrive,
;; so an observer batch can see an element half-parsed. These tests drive the
;; browser's parser one network read per `document.write`, into a same-origin
;; iframe whose document stays loading until `document.close`.

(defn- open-streaming-document!
  "A same-origin iframe whose document is open for writing and has received
  `shell-html` as its first read. Returns `[iframe doc]`."
  [shell-html]
  (let [iframe (.createElement js/document "iframe")]
    (.appendChild (.-body js/document) iframe)
    (let [doc (.-contentDocument iframe)]
      (.open doc)
      (.write doc shell-html)
      [iframe doc])))

(defn- streamed-shell [ids]
  (str "<!DOCTYPE html><html><head></head><body><div id=\"app\">"
       (shell-with-fallbacks ids)
       "</div>"))

(defn- after-observer!
  "Run `f` on a macrotask, so the MutationObserver microtask the last
  `document.write` queued has already run."
  [f]
  (js/setTimeout f 0))

(defn- await!
  "Call `k` with true once `(pred)` holds, or with false after `timeout-ms`,
  so a finalisation that never comes fails the test instead of hanging the
  lane."
  [pred timeout-ms k]
  (let [deadline (+ (js/Date.now) timeout-ms)]
    (letfn [(poll []
              (cond
                (pred)                     (k true)
                (> (js/Date.now) deadline) (k false)
                :else                      (js/setTimeout poll 10)))]
      (poll))))

(deftest split-payload-is-not-finalised-until-the-parser-closes-it
  (testing "a payload `<script>` split across two reads finalises only once
            the parser has closed it, so `:on-ready` sees the whole payload"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid          (make-client-frame!)
              payload-id   rf.ssr.constants/payload-script-id
              payload-edn  (pr-str {:rf/version 1
                                    :rf/app-db  {:cards {:revenue {:title "Revenue" :value 42375}}}})
              cut          (quot (count payload-edn) 2)
              [iframe doc] (open-streaming-document! (streamed-shell [:card.revenue]))
              ready        (atom [])
              stop!        (rf.ssr.streaming.client/install!
                             {:frame    fid
                              :root     doc
                              :on-ready (fn [_report]
                                          (swap! ready conj
                                                 (.-textContent (.getElementById doc payload-id))))})]
          ;; Read 1: the payload's start tag and the first half of its EDN.
          (.write doc (str "<script id=\"" payload-id "\" type=\"application/edn\">"
                           (subs payload-edn 0 cut)))
          ;; Read before the observer runs: without it the test is vacuous.
          (is (some? (.getElementById doc payload-id))
              "precondition: the parser has already inserted the half-parsed payload element")
          (after-observer!
            (fn []
              (is (= [] @ready)
                  "not finalised while the parser is still writing the payload")
              ;; Read 2: the rest of the payload, then the end of the document.
              (.write doc (str (subs payload-edn cut) "</script></body></html>"))
              (.close doc)
              (await! #(seq @ready) 2000
                      (fn [_]
                        (is (= [payload-edn] @ready)
                            "finalised exactly once, with the WHOLE payload in the element")
                        (stop!)
                        (remove-root! iframe)
                        (done))))))))))

(deftest split-resolved-template-and-delta-wait-for-the-parser
  (testing "a half-parsed resolved template is not swapped in and a
            half-parsed delta is not read; each is processed once the parser
            has moved past it"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid          (make-client-frame!)
              template     (rf.ssr/streaming-resolved-template
                             :card.revenue
                             "<div class=\"card resolved-revenue\">Revenue 42375</div>")
              template-cut (str/index-of template "42375")
              delta-script (rf.ssr/streaming-hydrate-delta-script
                             :card.revenue
                             (pr-str {:cards {:revenue {:title "Revenue" :value 42375}}}))
              delta-cut    (str/index-of delta-script ":value")
              [iframe doc] (open-streaming-document! (streamed-shell [:card.revenue]))
              ready        (atom 0)
              stop!        (rf.ssr.streaming.client/install!
                             {:frame fid :root doc :on-ready (fn [_] (swap! ready inc))})
              revenue-text #(some-> (.querySelector doc ".resolved-revenue") .-textContent)
              deltas-in-dom
              #(count (array-seq
                        (.querySelectorAll
                          doc (str "[" rf.ssr.streaming.constants/attr-suspense-hydrate "]"))))]
          ;; Read 1: the resolved template, cut inside its content.
          (.write doc (subs template 0 template-cut))
          (is (some? (.querySelector
                       doc (str "[" rf.ssr.streaming.constants/attr-suspense-resolved "]")))
              "precondition: the parser has already inserted the half-parsed resolved template")
          (after-observer!
            (fn []
              (is (true? (showing-fallback? doc :card.revenue))
                  "a half-parsed resolved template is not swapped in")
              ;; Read 2: the rest of the template, then the delta cut inside
              ;; its EDN.
              (.write doc (str (subs template template-cut)
                               (subs delta-script 0 delta-cut)))
              (is (= 1 (deltas-in-dom))
                  "precondition: the parser has already inserted the half-parsed delta script")
              (after-observer!
                (fn []
                  (is (= "Revenue 42375" (revenue-text))
                      "once the parser has moved past it, the template swaps in WHOLE")
                  (is (= 1 (deltas-in-dom))
                      "the half-parsed delta script is left in the DOM, not consumed")
                  (is (nil? @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                      "and not merged")
                  ;; Read 3: the rest of the delta, the payload, the end of
                  ;; the document.
                  (.write doc (str (subs delta-script delta-cut)
                                   "<script id=\"" rf.ssr.constants/payload-script-id
                                   "\" type=\"application/edn\">{:rf/version 1}</script>"
                                   "</body></html>"))
                  (.close doc)
                  (await! #(pos? @ready) 2000
                          (fn [_]
                            (is (= {:title "Revenue" :value 42375}
                                   @(rf/subscribe [:sct/card :revenue] {:frame fid}))
                                "the delta, read once whole, merged")
                            (is (zero? (deltas-in-dom)) "and was consumed")
                            (stop!)
                            (remove-root! iframe)
                            (done))))))))))))

(deftest split-fallback-is-painted-whole-through-failure-and-finalisation
  (testing "a half-parsed fallback is painted from its prefix but not
            consumed, then repainted whole once the parser closes it — a
            last-child fallback included — and stays whole through a failed
            boundary, an unresolved one, and finalisation"
    (if-not (browser?)
      (is true ":node-test: no DOM")
      (async
        done
        (let [fid            (make-client-frame!)
              fallback-html  #(str "<div class=\"card skeleton\">" (name %) " skeleton complete</div>")
              whole          #(str (name %) " skeleton complete")
              flaky          (rf.ssr/streaming-fallback-template :card.flaky (fallback-html :card.flaky))
              slow           (rf.ssr/streaming-fallback-template :card.slow (fallback-html :card.slow))
              flaky-cut      (str/index-of flaky "complete")
              slow-cut       (str/index-of slow "complete")
              ;; Read 1: the shell up to the middle of :card.flaky's fallback.
              [iframe doc]   (open-streaming-document!
                               (str "<!DOCTYPE html><html><head></head><body><div id=\"app\">"
                                    "<main class=\"dashboard\"><section class=\"cards\">"
                                    (subs flaky 0 flaky-cut)))
              ready          (atom [])
              stop!          (rf.ssr.streaming.client/install!
                               {:frame fid :root doc :on-ready #(swap! ready conj %)})
              mount-text     #(some-> (mount-for doc %) .-textContent)
              fallbacks-in-dom
              #(count (array-seq
                        (.querySelectorAll
                          doc (str "[" rf.ssr.streaming.constants/attr-suspense-fallback "]"))))]
          (is (some? (mount-for doc :card.flaky))
              "the half-parsed fallback is painted at once: the skeleton is the first paint")
          (is (= 1 (fallbacks-in-dom))
              "but its template is not consumed while the parser is still writing it")
          (after-observer!
            (fn []
              ;; Read 2: the rest of :card.flaky's fallback, then
              ;; :card.slow's cut inside its content.
              (.write doc (str (subs flaky flaky-cut) (subs slow 0 slow-cut)))
              (after-observer!
                (fn []
                  (is (= (whole :card.flaky) (mount-text :card.flaky))
                      "once the parser has closed it, the split fallback is repainted WHOLE")
                  (is (= 1 (fallbacks-in-dom))
                      "and consumed, while :card.slow's, still being written, is not")
                  ;; Read 3: the rest of :card.slow's fallback, the LAST child
                  ;; of its section, the end of the shell, then :card.flaky's
                  ;; failed chunk — the first node after `#app`.
                  (.write doc (str (subs slow slow-cut) "</section></main></div>"
                                   (failed-chunk-html :card.flaky (fallback-html :card.flaky))))
                  (after-observer!
                    (fn []
                      (is (= (whole :card.slow) (mount-text :card.slow))
                          "a last-child fallback is repainted WHOLE once a node follows its section")
                      (is (zero? (fallbacks-in-dom))
                          "and consumed then, before the document has finished parsing")
                      ;; Read 4: the payload and the end of the document.
                      ;; :card.slow never resolves.
                      (.write doc (str "<script id=\"" rf.ssr.constants/payload-script-id
                                       "\" type=\"application/edn\">{:rf/version 1}</script>"
                                       "</body></html>"))
                      (.close doc)
                      (await! #(seq @ready) 2000
                              (fn [_]
                                (is (= [{:resolved #{} :failed #{:card.flaky}}] @ready)
                                    "one failed boundary, one never resolved")
                                (is (= [(whole :card.flaky) (whole :card.slow)]
                                       (mapv #(.-textContent %)
                                             (array-seq (.querySelectorAll doc ".cards .skeleton"))))
                                    "both fallbacks are painted WHOLE in the finalised DOM")
                                (is (nil? (.querySelector
                                            doc (str "[" rf.ssr.streaming.constants/attr-suspense-mount "]")))
                                    "and every mount was unwrapped")
                                (stop!)
                                (remove-root! iframe)
                                (done))))))))))))))
