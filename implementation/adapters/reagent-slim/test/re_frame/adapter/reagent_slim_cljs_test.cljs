(ns re-frame.adapter.reagent-slim-cljs-test
  "Structural tests for re-frame.adapter.reagent-slim.

  The substrate-shape contract (per re-frame.substrate.adapter):

    The adapter map carries the contract fns (6 required + 3 optional +
    1 lifecycle) plus the :kind discriminator; signatures match the
    bridge. Apps doing `(rf/init! reagent-slim/adapter)` see the same
    shape they get from `(rf/init! reagent/adapter)`.

  Test strategy: we don't drive React DOM here (no jsdom in node-
  test); we exercise the adapter map's keys and the shape of the
  fns on each slot. The full `(rf/init! ...)` dispatch / subscribe /
  render path is exercised in the browser-test target.

  The `:adapter/current-frame` and
  `:adapter/current-component` late-bind hooks are installed as
  routing closures that delegate to the actively-installed adapter
  (via `substrate-adapter/current-adapter`) — so a test bundle that
  loads multiple adapter ns's does not see the last-loaded one
  silently win at the hook regardless of which adapter was
  `(rf/init!)`-installed. The `:reagent/set-hiccup-emitter!` hook is
  chained at ns-load time per the SSR shipping convention.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
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

(deftest adapter-slot-fns-callable
  (testing "every adapter contract slot value is a fn (excludes the :kind discriminator)"
    (doseq [[k v] (dissoc rf.adapter.reagent-slim/adapter :kind)]
      (is (fn? v) (str "adapter slot " k " is callable")))))

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
      (is (some? hook-fn)
          "the chained hook is registered after the Reagent Slim adapter ns has loaded")
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

;; ---------------------------------------------------------------------------
;; register-context-provider returns the views ns's frame-provider
;; ---------------------------------------------------------------------------

(deftest register-context-provider-returns-component
  (testing "register-context-provider returns a component value"
    (let [reg (:register-context-provider rf.adapter.reagent-slim/adapter)
          provider (reg :rf/some-frame)]
      (is (some? provider)
          "register-context-provider returned a non-nil component"))))
