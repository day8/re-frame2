(ns re-frame.bench.hicasso.arm1.keywarn-dom-cljs-test
  "THE MINTED KEY WARNING, END TO END IN A REAL REACT DOM (rf2-2rtt6.104).

  `front.codec-cljs-test` settles the predicate, the wording and the
  dedupe at the element, without React. This file asserts the two things
  only a real render can say:

  1. **The owner threading works from the arm's own shell.** The codec
     cannot name the enclosing view by itself — `as-element` carries no
     context — so `run-once` records it around the lowering and clears it
     in the `finally` it already had. A unit row can drive that seam by
     hand; only a mounted `defview` proves the arm actually does.
  2. **Both warnings arrive, and they are complementary rather than
     duplicate.** React's own key warning is a `console.error` naming a
     component stack; the Hicasso one is a `console.warn` naming the
     view, the child head and the fix. Neither suppresses the other, and
     no suppression machinery exists.

  The parent tag is the custom element `:keywarn-list` on purpose. React
  dedupes its key warning on the PARENT TAG NAME, module-globally and for
  the life of the page (`ownerHasKeyUseWarning` in react-dom-client's
  development build), so a row asserting React's channel under a `:ul`
  would go green or red depending on whether some earlier namespace in
  the bundle had already warned under a `:ul`. A tag only this file uses
  makes the assertion deterministic — and the coarse dedupe it works
  around is the whole reason the Hicasso warning names the view.

  A `window` `error` listener rides alongside the two channel spies
  because React routes a throw during a commit to `reportError`, where
  `cljs.test` reads 0 failures over a live exception — the same guard
  `host-ssr-dom-cljs-test`'s `watch-errors!` carries.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM; under `:node-test` every claim degrades to a stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :ambient-frame nil
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-keywarn)

(defn- skip! [why]
  (is true (str "a minted-key-warning claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-id 3)
  (dogfood/reseed! frame-id 3)
  frame-id)

;; ---------------------------------------------------------------------------
;; The boundaries — one unkeyed list, one keyed control
;; ---------------------------------------------------------------------------

(defview keywarn-row
  "An ordinary boundary child. Nothing about it is unusual: the defect is
  entirely in how its parent spells the list."
  [{:keys [label]}]
  [:li.keywarn-row (str label)])

(defview keywarn-list
  "The shape the guide promises a warning for — a bare seq of boundary
  children with no `:key` in sight."
  [_]
  [:keywarn-list
   (for [i (range 3)]
     [keywarn-row {:label i}])])

(defview keyed-list
  "The same list, written correctly. It is here so the row below is a
  reading of the WARNING rather than of the fixture: this one must be
  silent on the Hicasso channel in the same bundle, the same render pass
  and the same spy."
  [_]
  [:keyed-keywarn-list
   (for [i (range 3)]
     [keywarn-row {:key i :label i}])])

;; ---------------------------------------------------------------------------
;; Both channels, plus the one React hides
;; ---------------------------------------------------------------------------

(defn- watch-console!
  "Both channels and `reportError`, captured together. `console.warn` is
  Hicasso's; `console.error` is React's; the `window` listener is the one
  a green row would otherwise be hiding."
  []
  (let [warns    (atom [])
        errors   (atom [])
        thrown   (atom [])
        o-warn   (.-warn js/console)
        o-error  (.-error js/console)
        on-error (fn [e] (swap! thrown conj (str (.-message e))))]
    (set! (.-warn js/console) (fn [& args] (swap! warns conj (apply str args))))
    (set! (.-error js/console) (fn [& args] (swap! errors conj (apply str args))))
    (.addEventListener js/window "error" on-error)
    {:warns   warns
     :errors  errors
     :thrown  thrown
     :restore (fn []
                (set! (.-warn js/console) o-warn)
                (set! (.-error js/console) o-error)
                (.removeEventListener js/window "error" on-error))}))

(defn- one-matching [lines re]
  (filterv #(re-find re %) lines))

(deftest a-mounted-unkeyed-list-warns-on-both-channels-and-names-its-own-view
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [{:keys [warns errors thrown restore]} (watch-console!)
            handle (try (mount/root! (mount/fresh-container!) frame-id [keywarn-list {}])
                        (catch :default e (restore) (throw e)))]
        (try
          (mount/settle!)
          (let [hicasso (one-matching @warns #"rf\.warning/hicasso-missing-key")
                react   (one-matching @errors #"unique \"key\" prop")]
            (is (= [] @thrown)
                "no exception was routed to reportError — without this listener a
                 live throw inside a render reads as 0 failures")
            (is (= 1 (count hicasso)) "exactly one Hicasso line, once for the site")
            (is (re-find #"keywarn-list" (first hicasso))
                "**the owner threading, end to end**: the codec named the
                 enclosing view, which it can only do because `run-once` told it")
            (is (re-find #"keywarn-row" (first hicasso)) "and the child head")
            (is (re-find #"first at index 0" (first hicasso)))
            (is (= 1 (count react))
                "React's own warning fired too — neither is suppressed, and no
                 suppression machinery exists")
            (is (not (re-find #"rf\.warning" (first react)))
                "and they are visibly different lines on different channels"))
          (finally (mount/release! handle) (restore)))))))

(deftest the-keyed-control-is-silent-in-the-same-bundle-and-the-same-spy
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [{:keys [warns errors thrown restore]} (watch-console!)
            handle (try (mount/root! (mount/fresh-container!) frame-id [keyed-list {}])
                        (catch :default e (restore) (throw e)))]
        (try
          (mount/settle!)
          (is (= [] @thrown))
          (is (= [] (one-matching @warns #"rf\.warning/hicasso"))
              "a correctly keyed list says nothing at all")
          (is (= [] (one-matching @errors #"unique \"key\" prop"))
              "and React agrees, which is the cross-check on the fixture")
          (finally (mount/release! handle) (restore)))))))

(deftest the-owner-slot-is-cleared-by-the-finally-not-merely-set-by-the-body
  (testing "after a completed render, a lowering that is NOT inside any body
            observes a nil owner and drops the owner clause — the end-to-end
            witness for the clear half of the pair, which a set-only test
            would pass without"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount/root! (mount/fresh-container!) frame-id [keywarn-list {}])]
          (mount/settle!)
          (mount/release! handle))
        (let [orphan (fn [_js-props] nil)
              _      (unchecked-set orphan "displayName" "keywarn.dom/orphan")
              _      (codec/mark-boundary! orphan)
              seen   (atom [])
              o-warn (.-warn js/console)]
          (set! (.-warn js/console) (fn [& args] (swap! seen conj (apply str args))))
          (try (codec/as-element [:ul (list [orphan {}])])
               (finally (set! (.-warn js/console) o-warn)))
          (let [line (first (filterv #(re-find #"hicasso-missing-key" %) @seen))]
            (is (some? line) "the direct lowering still warns")
            (is (re-find #"^\[hicasso\] Unkeyed boundary children: " line)
                "with no owner clause — the slot was cleared when the render
                 unwound, so nothing stale could be named")
            (is (re-find #"keywarn\.dom/orphan" line))))))))
