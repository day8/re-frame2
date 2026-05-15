(ns re-frame.adapter.reagent-slim-cljs-test
  "Structural tests for re-frame.adapter.reagent-slim (Stage 4-D, rf2-6hyy).

  The substrate-shape contract (per re-frame.substrate.adapter):

    The adapter map carries 9 keys; signatures match the bridge.
    Apps doing `(rf/init! reagent-slim/adapter)` see the same shape
    they get from `(rf/init! reagent/adapter)`.

  Test strategy: we don't drive React DOM here (no jsdom in node-
  test); we exercise the adapter map's keys and the shape of the
  fns on each slot. The full `(rf/init! ...)` dispatch / subscribe /
  render path is exercised in the browser-test target (Stage 4-E
  follow-up).

  Per rf2-0d35 the `:adapter/current-frame` and
  `:adapter/current-component` late-bind hooks are now installed as
  routing closures that delegate to the actively-installed adapter
  (via `substrate-adapter/current-adapter`) — so a test bundle that
  loads multiple adapter ns's no longer sees the last-loaded one
  silently win at the hook regardless of which adapter was
  `(rf/init!)`-installed. The `:reagent/set-hiccup-emitter!` hook is
  chained at ns-load time per the SSR shipping convention.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.adapter.reagent-slim :as reagent-slim]
            [re-frame.late-bind :as late-bind]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- a-mock-emitter
  "A toy hiccup -> HTML emitter so install-path tests assert wiring, not
  real rendering."
  [render-tree _opts]
  (str "<mock>" (pr-str render-tree) "</mock>"))

(defn- with-cleared-emitter
  "Run `f` with the adapter's hiccup-emitter forced to nil."
  [f]
  (reagent-slim/set-hiccup-emitter! nil)
  (try
    (f)
    (finally
      (reagent-slim/set-hiccup-emitter! nil))))

;; ---------------------------------------------------------------------------
;; Adapter map shape (per IMPL-SPEC §2.1 + Spec 006 §CLJS reference)
;; ---------------------------------------------------------------------------

(deftest adapter-has-9-canonical-keys
  (testing "the adapter map carries the 9 substrate-shape keys + :kind discriminator"
    (let [k (set (keys reagent-slim/adapter))]
      (is (= #{:kind
              :make-state-container
              :read-container
              :replace-container!
              :subscribe-container
              :make-derived-value
              :render
              :render-to-string
              :register-context-provider
              :dispose-adapter!}
             k)
          "every slot named in re-frame.substrate.adapter is present plus :kind")
      (is (= :reagent-slim (:kind reagent-slim/adapter))
          ":kind matches the canonical reagent-slim discriminator"))))

(deftest adapter-slot-fns-callable
  (testing "every adapter contract slot value is a fn (excludes the :kind discriminator)"
    (doseq [[k v] (dissoc reagent-slim/adapter :kind)]
      (is (fn? v) (str "adapter slot " k " is callable")))))

;; ---------------------------------------------------------------------------
;; State container (round-trip)
;; ---------------------------------------------------------------------------

(deftest state-container-roundtrip
  (testing "make-state-container / read / replace cycle"
    (let [make    (:make-state-container reagent-slim/adapter)
          read    (:read-container        reagent-slim/adapter)
          replace (:replace-container!    reagent-slim/adapter)
          c       (make {:n 0})]
      (is (= {:n 0} (read c)) "initial value flows through")
      (replace c {:n 42})
      (is (= {:n 42} (read c)) "replace updates the container"))))

(deftest state-container-subscribe
  (testing "subscribe-container fires on change; unsubscribe stops"
    (let [make      (:make-state-container reagent-slim/adapter)
          replace   (:replace-container!    reagent-slim/adapter)
          subscribe (:subscribe-container  reagent-slim/adapter)
          c         (make {:n 0})
          seen      (atom [])
          unsub     (subscribe c (fn [_prev nu] (swap! seen conj nu)))]
      (replace c {:n 1})
      (replace c {:n 2})
      (is (= [{:n 1} {:n 2}] @seen) "two transitions observed")
      (unsub)
      (replace c {:n 3})
      (is (= [{:n 1} {:n 2}] @seen) "no more transitions after unsubscribe"))))

;; ---------------------------------------------------------------------------
;; Derived value
;; ---------------------------------------------------------------------------

(deftest derived-value-tracks-source
  (testing "make-derived-value produces a Reaction that tracks its sources"
    (let [make-c    (:make-state-container reagent-slim/adapter)
          replace   (:replace-container!    reagent-slim/adapter)
          make-d    (:make-derived-value   reagent-slim/adapter)
          src       (make-c 1)
          derived   (make-d [src] (fn [v] (* v 100)))]
      (is (= 100 @derived) "initial derived value")
      (replace src 5)
      (is (= 500 @derived) "derived recomputes when source changes"))))

;; ---------------------------------------------------------------------------
;; render-to-string requires emitter installation
;; ---------------------------------------------------------------------------

(deftest render-to-string-throws-without-emitter
  (testing "render-to-string raises when no hiccup-emitter installed"
    (with-cleared-emitter
      (fn []
        (let [render-to-string (:render-to-string reagent-slim/adapter)]
          (is (thrown? :default (render-to-string [:div] {}))))))))

(deftest set-hiccup-emitter-installs-fn
  (testing "set-hiccup-emitter! lets render-to-string emit"
    (with-cleared-emitter
      (fn []
        (reagent-slim/set-hiccup-emitter! a-mock-emitter)
        (let [render-to-string (:render-to-string reagent-slim/adapter)
              tree             [:div "ok"]
              html             (render-to-string tree {})]
          (is (str/starts-with? html "<mock>")
              "the installed emitter is what render-to-string invokes")
          (is (str/includes? html (pr-str tree))
              "the installed emitter received the render-tree the caller passed in"))))))

(deftest set-hiccup-emitter-published-through-late-bind-chain
  (testing "rf2-swoks: the Reagent Slim adapter chains its set-hiccup-emitter!
            into `:reagent/set-hiccup-emitter!` at ns-load. Calling the
            hook installs the emitter into the Reagent Slim adapter's
            slot, so SSR's `re-frame.ssr.emit` ns-load can auto-wire
            render-to-string without a direct `set-hiccup-emitter!`
            call from user code."
    (let [hook-fn (late-bind/get-fn :reagent/set-hiccup-emitter!)]
      (is (some? hook-fn)
          "the chained hook is registered after the Reagent Slim adapter ns has loaded")
      (with-cleared-emitter
        (fn []
          (let [render-to-string (:render-to-string reagent-slim/adapter)]
            (is (thrown? :default (render-to-string [:div] {}))
                "precondition: emitter cleared"))
          (hook-fn a-mock-emitter)
          (let [render-to-string (:render-to-string reagent-slim/adapter)
                html             (render-to-string [:div "via-chain"] {})]
            (is (str/starts-with? html "<mock>")
                "the chained hook wired the Reagent Slim adapter's emitter slot"))
          (hook-fn nil))))))

;; ---------------------------------------------------------------------------
;; dispose-adapter! is a no-op
;; ---------------------------------------------------------------------------

(deftest dispose-adapter-runs-cleanly
  (testing "dispose-adapter! returns nil and doesn't throw"
    (let [dispose (:dispose-adapter! reagent-slim/adapter)]
      (is (nil? (dispose))))))

;; ---------------------------------------------------------------------------
;; render slot accepts a stub root + returns an unmount thunk
;; ---------------------------------------------------------------------------

(deftest render-slot-fake-root
  ;; The render slot wraps create-root; we can't easily mock create-root
  ;; from here. Settle for: the slot is callable. Real render-path tests
  ;; live in reagent2.dom.client-cljs-test (with stub roots) and
  ;; browser-test (with jsdom).
  (testing "render slot is callable"
    (is (fn? (:render reagent-slim/adapter)))))

;; ---------------------------------------------------------------------------
;; register-context-provider returns the views ns's frame-provider
;; ---------------------------------------------------------------------------

(deftest register-context-provider-returns-component
  (testing "register-context-provider returns a component value"
    (let [reg (:register-context-provider reagent-slim/adapter)
          provider (reg :rf/some-frame)]
      (is (some? provider)
          "register-context-provider returned a non-nil component"))))
