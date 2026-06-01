(ns ssr.core-test
  "Headless tests for ssr.core — exercises the server flow (per-request
  frame → :rf/server-init → managed-HTTP via the canned stub → render to
  string → render-hash). Kept out of the example source so the example a
  learner reads is pure demonstrative code (the same test-free split
  realworld / nine_states / boot use).

  JVM-only: the server render path runs under Clojure. Driven by
  re-frame.examples-test."
  (:require [clojure.string]
            [re-frame.core :as rf]
            [re-frame.registrar :as registrar]
            [re-frame.ssr :as ssr]
            [ssr.core]))

(defn ssr-tests []
  ;; Boot the runtime (idempotent) — installs the SSR adapter and the
  ;; :rf/default frame. `re-frame.ssr` exports its own `adapter` var
  ;; (the JVM-side counterpart of reagent/uix/helix adapters); pass it
  ;; explicitly.
  (rf/init! ssr/adapter)
  ;; Stub `:rf.http/managed` so the test doesn't make real network
  ;; calls. The per-frame `:fx-overrides` redirect `:rf.http/managed`
  ;; to a per-test stub that delegates to the framework-shipped
  ;; `:rf.http/managed-canned-success` (Spec 014 §Testing) with a
  ;; canned `:value` payload — the same reply shape a live request
  ;; would produce.
  (rf/reg-fx :ssr.http/canned-articles
    {:platforms #{:server :client}}
    (fn [frame-ctx args-map]
      (let [stub (registrar/handler :fx :rf.http/managed-canned-success)]
        (stub frame-ctx
              (assoc args-map
                     :value [{:id "a" :title "Article A" :body "Body A"}
                             {:id "b" :title "Article B" :body "Body B"}])))))

  (let [fid          (keyword "rf.frame" (str (gensym "")))
        _            (ssr/set-request! fid {:uri "/articles"})
        f            (rf/reg-frame fid
                       {:doc          "ssr-example test frame"
                        :platform     :server
                        :on-create    [:rf/server-init]
                        :fx-overrides {:rf.http/managed :ssr.http/canned-articles}})
        final-db     (rf/app-db-value f)
        ;; The root view's body invokes the articles-page render fn,
        ;; which calls (rf/subscribe-once [:articles]). Both run
        ;; INSIDE render-to-string's tree walk; with-frame binds
        ;; *current-frame* across that walk so the sub reads from f
        ;; and not from :rf/default.
        hiccup      ((rf/view :app/root))
        html        (rf/with-frame f
                      (rf/render-to-string hiccup {:emit-hash? true}))
        render-hash (rf/render-tree-hash hiccup)]
    ;; State was loaded.
    (assert (= 2 (count (:articles final-db))))
    ;; HTML contains the article titles.
    (assert (clojure.string/includes? html "Article A"))
    (assert (clojure.string/includes? html "Article B"))
    ;; HTML round-trips via render-to-string without needing React/JSDOM.
    (assert (clojure.string/includes? html "<h1>"))
    ;; render-hash is a structural marker (lowercase-hex FNV-1a per
    ;; Spec 011); the client recomputes it and the runtime emits a
    ;; :rf.ssr/hydration-mismatch trace event on disagreement.
    (assert (re-matches #"[0-9a-f]{8}" render-hash))
    (assert (clojure.string/includes? html "data-rf-render-hash"))
    :ok))
