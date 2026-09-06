(ns re-frame.ssr-emitter-reinit-lifecycle-jvm-test
  "rf2-vxgfnd.204 — the JVM SSR emitter lifecycle expectation, the host twin of
  the CLJS lifecycle suite the retired compiled tier carried.

  On the JVM a compiled-view adapter IS the plain-atom adapter (`(assoc
  plain-atom/adapter :kind :rf.adapter/ui)`), and its `:render-to-string` reads
  plain-atom's `hiccup-emitter` atom. That atom is armed once by `re-frame.ssr`
  ns-load and, crucially, plain-atom's `dispose-adapter!` is a NO-OP — it does
  NOT clear the emitter. So the JVM lifecycle is repeatable by RETENTION, where
  the React-shaped CLJS spine is repeatable by disposal-clears + install-replay.
  This test pins that host-divergent expectation explicitly: the emitter stays
  bound across destroy → re-init on the JVM.

  The rf2-vxgfnd.204 install-time replay (`install-adapter!` →
  `rearm-hiccup-emitter!`) is a harmless no-op here: plain-atom publishes no
  `:adapter/arm-hiccup-emitter-if-unarmed!` routed arm (rf2-h9szm — the replay is
  routed to the installed adapter's own arm hook, not the broadcast
  `:reagent/set-hiccup-emitter!` chain), so the durable `:ssr/current-hiccup-
  emitter` slot has no per-adapter arm to run — retention alone keeps the JVM
  path armed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (fn [f]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (try
      (f)
      (finally
        (rf/destroy-adapter!)
        (rf.substrate.adapter/reset-lifecycle-state-for-tests!)))))

(defn- render! []
  (rf.substrate.adapter/render-to-string [:div "ok"] {}))

(deftest ssr-load-published-the-durable-emitter-on-the-jvm-too
  (is (fn? (rf.late-bind/get-fn :ssr/current-hiccup-emitter))
      "re-frame.ssr ns-load published :ssr/current-hiccup-emitter on the JVM"))

(deftest jvm-plain-atom-emitter-is-retained-across-destroy-and-reinit
  (is (nil? (rf/init! rf.substrate.plain-atom/adapter)))
  (testing "first install renders through the SSR emitter"
    (is (re-find #"<div>ok</div>" (render!))))

  (is (nil? (rf/destroy-adapter!)))
  (is (true? (rf.substrate.adapter/adapter-disposed?)))

  (is (nil? (rf/init! rf.substrate.plain-atom/adapter)) "a fresh init is legal after disposal")
  (testing "the JVM emitter survived the no-op dispose — re-init still renders"
    (is (re-find #"<div>ok</div>" (render!))
        "plain-atom retains its hiccup-emitter across the dispose/re-init cycle")))

(deftest jvm-lifecycle-is-repeatable-and-does-not-duplicate-publication
  (let [emitter-before (rf.late-bind/get-fn :ssr/current-hiccup-emitter)]
    (dotimes [_ 5]
      (is (nil? (rf/init! rf.substrate.plain-atom/adapter)))
      (is (re-find #"<div>ok</div>" (render!)))
      (is (nil? (rf/destroy-adapter!))))
    (is (identical? emitter-before (rf.late-bind/get-fn :ssr/current-hiccup-emitter))
        "the durable emitter slot holds the SAME single publication after every cycle")))
