(ns ssr-streaming.core
  "Worked example for Spec 011 §Streaming SSR (rf2-ojakd / rf2-olb64 (a)).

  A dashboard with three slow cards: the page's shell + header render
  immediately on the server, then each card streams its content as its
  data fetch resolves. The browser shows a usable shell within ~50ms
  while the cards trickle in over ~300ms each.

  Demonstrates:
   - `:rf/suspense-boundary` hiccup marker — declarative
   - Per-card fallback hiccup — `[:div.card.skeleton …]`
   - Inline-fallback failure semantics — one card deliberately throws
     to show the failure path doesn't 500 the page
   - Hydration interleaved per subtree — each chunk's
     `<script data-rf2-suspense-hydrate>` carries the per-card app-db
     delta
   - Final `__rf_payload` arrives last with the canonical full state

  The .cljc shape mirrors `examples/reagent/ssr/core.cljc`: server
  branch in `:clj`, browser branch in `:cljs`. The :clj branch is
  what a Ring server would invoke; the :cljs branch is what the page
  bootstraps after the chunks arrive.

  Per [Spec 011 §Streaming SSR](../../../spec/011-SSR.md#streaming-ssr)."
  (:require [re-frame.core :as rf]
            [re-frame.schemas]
            [re-frame.ssr :as ssr]
            #?(:cljs [cljs.reader])
            #?(:cljs [reagent2.dom.client :as rdc])
            #?(:cljs [re-frame.adapter.reagent-slim :as reagent-slim-adapter])))

;; ============================================================================
;; SCHEMA
;; ============================================================================

(rf/reg-app-schema [:cards]
  [:map-of :keyword [:map [:title :string] [:value [:maybe :int]]]])

;; ============================================================================
;; EVENTS
;; ============================================================================

(rf/reg-event-fx :rf/server-init
  {:doc       "Per-request server-side init. In a real app the cards' data
               loads would dispatch :rf.http/managed fetches against three
               microservices; here we synchronously seed three card values
               of varying sizes so the demo's wire shape can be inspected
               in a single browser request."
   :platforms #{:server}}
  (fn [_ _]
    {:db {:cards
          {:revenue   {:title "Revenue (last 7 days)"     :value 42375}
           :signups   {:title "New signups (last 7 days)" :value 318}
           :latency   {:title "P50 latency (ms)"          :value 24}}}}))

;; ============================================================================
;; SUBSCRIPTIONS / VIEWS
;; ============================================================================

(rf/reg-sub :cards (fn [db _] (:cards db)))
(rf/reg-sub :card  (fn [db [_ id]] (get-in db [:cards id])))

(rf/reg-view ^{:rf/id :dashboard/card} card-view [card-id]
  (let [card @(subscribe [:card card-id])]
    [:div.card
     [:h3 (:title card)]
     [:p.value (str (:value card))]]))

(rf/reg-view ^{:rf/id :dashboard/card-skeleton} card-skeleton [card-id]
  [:div.card.skeleton
   [:h3 (str "Loading " (name card-id) " …")]
   [:p.value "—"]])

(rf/reg-view ^{:rf/id :dashboard/throwing-card} throwing-card []
  ;; Demonstrate Spec 011 §Failure semantics — inline fallback. This view
  ;; deliberately throws; the streaming runtime catches it inside
  ;; render-continuation, emits :rf.ssr/suspense-boundary-failed on the
  ;; trace bus, and ships the fallback HTML in the resolved-chunk
  ;; position (with data-rf2-suspense-failed marker). The PAGE still
  ;; loads — only the failing card stays in its fallback state.
  (throw (ex-info "flaky third-party metric service" {})))

(rf/reg-view ^{:rf/id :dashboard/root} root-view []
  [:main.dashboard
   [:header
    [:h1 "Dashboard"]
    [:p "Streamed SSR demo — shell renders first, cards stream in."]]
   [:section.cards
    [:rf/suspense-boundary
     {:id :card.revenue :fallback [:dashboard/card-skeleton :revenue]}
     [:dashboard/card :revenue]]
    [:rf/suspense-boundary
     {:id :card.signups :fallback [:dashboard/card-skeleton :signups]}
     [:dashboard/card :signups]]
    [:rf/suspense-boundary
     {:id :card.latency :fallback [:dashboard/card-skeleton :latency]}
     [:dashboard/card :latency]]
    ;; The failure-path demonstrator card. Same wire shape; the
    ;; chunk carries the fallback HTML with `data-rf2-suspense-failed="1"`
    ;; and no hydrate-delta script.
    [:rf/suspense-boundary
     {:id :card.flaky
      :fallback [:dashboard/card-skeleton :flaky]}
     [:dashboard/throwing-card]]]
   [:footer
    [:p "Each card above is a `:rf/suspense-boundary`."]]])

;; ============================================================================
;; SERVER ENTRY POINT (.clj branch — what a Ring server calls)
;; ============================================================================

#?(:clj
   (defn handle-request
     "What a host adapter (re-frame.ssr.ring/stream-handler) would call.
     This shape is one logical request → one chunked response; the
     adapter handles the actual Ring wiring and the writer thread. Here
     we synthesise the steps in-line so the example is JVM-runnable
     without a live server.

     Returns: a map carrying the rendered shell, the per-card chunks
     in order, and the final payload — the per-chunk byte sequence the
     streaming adapter would emit in order."
     [_request]
     (let [fid (keyword "rf.frame" (str (gensym "")))
           _   (rf/reg-frame fid {:doc "ssr-streaming-example frame"
                                  :platform :server
                                  :on-create [:rf/server-init]})
           hiccup (rf/with-frame fid ((rf/view :dashboard/root)))
           {:keys [shell-html continuations]}
           (rf/with-frame fid (ssr/streaming-render-shell hiccup))
           ;; Drain each continuation in order, collecting the resolved
           ;; subtree HTML + per-subtree hydration delta.
           resolved-chunks
           (mapv (fn [entry]
                   (let [{:keys [id html delta failed?]}
                         (rf/with-frame fid
                           (ssr/streaming-render-continuation fid entry))]
                     {:id    id
                      :template (if failed?
                                  (ssr/streaming-failed-template id html)
                                  (ssr/streaming-resolved-template id html))
                      :delta-script (when (and (not failed?) (some? delta))
                                      (ssr/streaming-hydrate-delta-script
                                        id (pr-str delta)))
                      :failed? failed?}))
                 continuations)
           render-hash (rf/with-frame fid (ssr/render-tree-hash hiccup))
           final-payload (rf/with-frame fid
                           (ssr/streaming-build-final-payload
                             fid render-hash {:version 1}))
           _ (rf/destroy-frame fid)]
       {:shell shell-html
        :resolved-chunks resolved-chunks
        :final-payload final-payload
        :render-hash render-hash})))

;; ============================================================================
;; CLIENT ENTRY POINT (.cljs branch — browser hydration)
;; ============================================================================
;;
;; Streaming hydration shape (per Spec 011 §Streaming SSR — Client-side
;; hydration semantics):
;;
;;  1. First chunk lands — the browser paints the shell with skeleton
;;     fallbacks. The `<script src="main.js">` reference at the end of
;;     `<body>` begins downloading.
;;  2. Resolved-card chunks stream in via Transfer-Encoding: chunked.
;;     Each one is `<template data-rf2-suspense-resolved=…>…</template>`
;;     plus `<script data-rf2-suspense-hydrate=…>…</script>`. Our shim
;;     (forthcoming, see below) swaps the fallback for the resolved
;;     content and merges the per-subtree delta into the client app-db.
;;  3. The final `<script id="__rf_payload">` arrives last. The shim
;;     dispatches `:rf/hydrate` with the canonical full payload, which
;;     runs :replace-app-db semantics — the per-card deltas were
;;     progressive-render speed props; the final payload is the
;;     correctness lock.

#?(:cljs
   (defn read-server-payload []
     (when-let [el (.getElementById js/document "__rf_payload")]
       (cljs.reader/read-string (.-textContent el)))))

#?(:cljs
   (defonce react-root
     (rdc/create-root (js/document.getElementById "app"))))

#?(:cljs
   (defn ^:export run []
     (rf/init! reagent-slim-adapter/adapter)
     (when-let [payload (read-server-payload)]
       (rf/dispatch-sync [:rf/hydrate payload]))
     (rdc/render react-root [(rf/view :dashboard/root)])))

;; ============================================================================
;; HEADLESS TEST (JVM-runnable; exercises the server stream)
;; ============================================================================

#?(:clj
   (defn streaming-tests []
     (rf/init! ssr/adapter)
     (let [result (handle-request {:uri "/dashboard"})]
       ;; Shell carries the static header content + four template fallbacks.
       (assert (clojure.string/includes? (:shell result) "<h1>Dashboard</h1>"))
       (assert (clojure.string/includes? (:shell result) "data-rf2-suspense-fallback=\"1\""))
       (assert (= 4 (count (:resolved-chunks result)))
               "four boundaries → four resolved chunks")
       ;; Three cards rendered successfully; one (flaky) ships the
       ;; fallback HTML with data-rf2-suspense-failed.
       (let [failed-chunks (filter :failed? (:resolved-chunks result))]
         (assert (= 1 (count failed-chunks))
                 "one boundary (flaky) ships the failed template")
         (assert (clojure.string/includes? (:template (first failed-chunks))
                                            "data-rf2-suspense-failed=\"1\"")))
       ;; Successful cards carry the rendered card body.
       (let [ok-chunks (remove :failed? (:resolved-chunks result))]
         (assert (= 3 (count ok-chunks)))
         (doseq [c ok-chunks]
           (assert (clojure.string/includes? (:template c)
                                              "data-rf2-suspense-resolved=\"1\""))))
       ;; Final payload carries the canonical :rf/* keys.
       (assert (= 1 (:rf/version (:final-payload result))))
       (assert (some? (:rf/render-hash (:final-payload result))))
       (assert (= 3 (count (:cards (:rf/app-db (:final-payload result)))))
               "three cards' state in the final payload (revenue, signups, latency); the flaky card has no app-db slice because it threw before its data fetched")
       :ok)))
