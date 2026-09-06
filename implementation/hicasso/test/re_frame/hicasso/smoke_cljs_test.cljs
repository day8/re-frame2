(ns re-frame.hicasso.smoke-cljs-test
  "THE PACKAGE SMOKE — a `defview` reads a `sub`, through the public door.

  This is the one test the extraction bead owes, and it is deliberately
  the smallest thing that can fail for the right reason. It is not a
  re-proof of the runtime: the runtime's ~30 suites live in the frozen
  bench tree and go on running there, which is the point of freezing it.
  What is genuinely NEW here — and therefore the only thing that can be
  newly broken — is the PACKAGING:

  - `re-frame.hicasso` resolves, and hands a consumer the three macros
    through a plain `:require` (a CLJS namespace cannot re-export a macro,
    so this is the property most likely to be quietly wrong);
  - the renamed `impl.*` graph loads, with the bench tree nowhere on the
    classpath;
  - a boundary minted by the door's `defview` reads a subscription and
    re-reads it after a write.

  ## Why the server renderer

  This is the NODE lane, which has no DOM, and a package smoke should not
  need a browser to say whether it was assembled correctly. React's server
  renderer runs a boundary's body for real — the shell's two hooks, the
  read collection, the codec's element emission — so the round-trip below
  is the runtime's own, not a simulation of it. The DOM-driven proofs of
  the same machinery are the bench tree's `*_dom_cljs_test` suites.

  The three harness namespaces reached below the door (`impl.mount`'s
  provider, `impl.codec`'s root element, `impl.collector`'s reset) are the
  server-render harness, not authoring surface. Mounting is
  [[re-frame.hicasso/mount!]]'s job and needs a DOM; wiring a consumer app
  and an SSR entry to the package is the consumer app's."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.test-support :as rf.test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::smoke)

(rf/reg-sub :smoke/greeting (fn [db _] (:greeting db)))

(rf/reg-event :smoke/seed  (fn [_ [_ greeting]] {:db {:greeting greeting}}))
(rf/reg-event :smoke/shout (fn [{:keys [db]} _] {:db (update db :greeting str/upper-case)}))

;; Registered above `use-fixtures`, deliberately — the reset fixture captures
;; its source-store baseline when the `use-fixtures` form is evaluated. The
;; adapter is UIx's rather than plain-atom's for the reason the prototype's
;; own node-lane suites give: plain-atom has no reactivity layer, so a
;; subscription under it never notifies and a "the value changed" assertion
;; would pass vacuously by never firing.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The consumer's whole source file
;; ---------------------------------------------------------------------------

(rf.hicasso/defview greeting-line
  "A boundary declared exactly as a consumer declares one: the macro off
  the public door, the read off the public door, and nothing imported from
  below it."
  [{:keys [tag]}]
  [:p {:class tag} (rf.hicasso/sub [:smoke/greeting])])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  [greeting]
  ;; React's `act` queue is not the browser's scheduler, and a server render
  ;; is not inside one. Set outright rather than imported: the helper that
  ;; carries this line in the prototype lives in the bench tree, which the
  ;; freeze gate forbids this package from importing.
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:smoke/seed greeting]))
  frame-id)

(defn- html
  [hiccup]
  (react-dom-server/renderToString
    (rf.hicasso.impl.mount/provider frame-id (rf.hicasso.impl.codec/root-element frame-id hiccup))))

;; ---------------------------------------------------------------------------
;; The round-trip
;; ---------------------------------------------------------------------------

(deftest defview-and-sub-round-trip-through-the-public-door
  (testing "the door hands over a real minted boundary, not a plain fn —
            which is what `defview` expanding correctly through the
            self-required macro namespace looks like"
    (is (true? (rf.hicasso.impl.codec/boundary-head? greeting-line))
        "greeting-line should be a Hicasso boundary head"))

  (testing "a boundary's body reads its subscription and the value reaches
            the markup"
    (seeded! "hello")
    (is (re-find #"hello" (html [greeting-line {:tag "greet"}]))))

  (testing "and it is a live read rather than a render-time snapshot: a
            write through the ordinary event path changes what the same
            boundary renders"
    (seeded! "hello")
    (rf/with-frame frame-id (rf/dispatch-sync [:smoke/shout]))
    (let [markup (html [greeting-line {:tag "greet"}])]
      (is (re-find #"HELLO" markup))
      (is (nil? (re-find #">hello<" markup))))))

(deftest the-module-graph-loads-and-a-server-render-acquires-nothing
  ;; The runtime is six owned modules, and `test.runtime` is the one
  ;; nothing else requires — its readers reach
  ;; ACROSS the split, into the collector's tables, the frame memo and the
  ;; generation counters. Requiring it here is what puts it on the build at
  ;; all; asserting through it is what says those reaches resolve.
  ;;
  ;; The property asserted is the simplest true statement about the render
  ;; phase: `renderToString` runs bodies and never commits — React calls
  ;; `getServerSnapshot`, never `subscribe` — so the runtime acquires no
  ;; cell, takes no reference and records no edge. The real
  ;; commit-owns/abandonment witnesses are elsewhere; this one only has
  ;; to fail when the module graph is wrong.
  (testing "a render that never commits leaves the retention tables empty"
    (seeded! "hello")
    (html [greeting-line {:tag "greet"}])
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
           (dissoc (rf.hicasso.test.runtime/residue) :entries)))))
