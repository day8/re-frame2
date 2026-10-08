(ns re-frame.adapter.reagent-slim-cljs-test
  "Structural tests for re-frame.adapter.reagent-slim: the adapter map's
  substrate contract keys, and the SSR emitter hook chained at ns-load."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.late-bind :as rf.late-bind]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- mock-hiccup-emitter
  "A toy hiccup -> HTML emitter so install-path tests assert wiring, not
  real rendering."
  [render-tree _opts]
  (str "<mock>" (pr-str render-tree) "</mock>"))

(defn- with-cleared-hiccup-emitter
  "Run `f` with the adapter's hiccup-emitter forced to nil."
  [f]
  (rf.adapter.reagent-slim/set-hiccup-emitter! nil)
  (try
    (f)
    (finally
      (rf.adapter.reagent-slim/set-hiccup-emitter! nil))))

;; ---------------------------------------------------------------------------
;; Adapter map shape (per IMPL-SPEC §2.1 + Spec 006 §CLJS reference)
;; ---------------------------------------------------------------------------

(deftest adapter-has-canonical-keys
  (testing "the adapter map carries the substrate-shape keys + :kind discriminator
            (incl. the optional :flush-render! slot)"
    (let [k (set (keys rf.adapter.reagent-slim/adapter))]
      (is (= #{:kind
              :make-state-container
              :read-container
              :replace-container!
              :subscribe-container
              :make-derived-value
              :render
              :render-to-string
              :register-context-provider
              ;; Optional synchronous render-flush contract fn.
              :flush-render!
              :dispose-adapter!}
             k)
          "every slot named in re-frame.substrate.adapter is present plus :kind")
      (is (= :rf.adapter/reagent-slim (:kind rf.adapter.reagent-slim/adapter))
          ":kind matches the canonical reagent-slim discriminator"))))


;; ---------------------------------------------------------------------------
;; render-to-string requires emitter installation
;; ---------------------------------------------------------------------------

(deftest set-hiccup-emitter-published-through-late-bind-chain
  (testing "The Reagent Slim adapter chains its set-hiccup-emitter!
            into `:reagent/set-hiccup-emitter!` at ns-load. Calling the
            hook installs the emitter into the Reagent Slim adapter's
            slot, so SSR's `re-frame.ssr.emit` ns-load can auto-wire
            render-to-string without a direct `set-hiccup-emitter!`
            call from user code."
    (let [hook-fn (rf.late-bind/get-fn :reagent/set-hiccup-emitter!)]
      (with-cleared-hiccup-emitter
        (fn []
          (let [render-to-string (:render-to-string rf.adapter.reagent-slim/adapter)]
            (is (thrown? :default (render-to-string [:div] {}))
                "precondition: emitter cleared"))
          (hook-fn mock-hiccup-emitter)
          (let [render-to-string (:render-to-string rf.adapter.reagent-slim/adapter)
                html             (render-to-string [:div "via-chain"] {})]
            (is (str/starts-with? html "<mock>")
                "the chained hook wired the Reagent Slim adapter's emitter slot"))
          (hook-fn nil))))))
