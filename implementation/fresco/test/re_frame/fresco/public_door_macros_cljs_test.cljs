(ns re-frame.fresco.public-door-macros-cljs-test
  "THE PUBLIC DOOR'S MACRO SHAPES, pinned by contract rather than by
  expansion snapshot.

  What a consumer needs answered is *does the door still hand back what it
  promises* — a contract, not a byte-for-byte expansion, whose incidental
  symbol and def-layout detail would make safe refactoring noisy for no
  consumer confidence. What `event` and `defhost` mint is read through the
  kit's predicates in [[re-frame.fresco.test-kit-cljs-test]]; this file
  holds what only the EXPANSION decides:

  - Witness C: the fn `defview` emits binds NO name a body could reach, so
    the author's lexical scope is the one the body runs in;
  - Witnesses D and E: `defhost` refuses a form after its options map, and
    options that are not a map, at the door rather than dropping them.

  The server renderer runs witness C's body for real in the node lane. A
  body binds the ambient frame whether or not it reads anything, so it
  seats a frame of its own and scopes the render with
  `impl.mount/provider`. A `renderToString` never commits, so it leaves no
  runtime state behind it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::public-door)

(defn- badge-component
  "A foreign React component: not a boundary, not hiccup, nothing Fresco
  minted."
  [^js props]
  (react/createElement "b" #js {"className" "badge"} (.-label props)))

;; ---------------------------------------------------------------------------
;; Witness C — `h/defview`, and the name its emitted fn does NOT bind
;; ---------------------------------------------------------------------------
;;
;; The emitted fn is ANONYMOUS, and that is the contract this witness
;; holds. A NAMED `fn` binds its own name inside its body, so naming it
;; `<sym>-body` makes the ordinary extract-a-helper spelling below expand
;; to `(fn ticket-body [p] (ticket-body p))` and recurse until the stack
;; overflows. Under React the symptom is `Maximum call stack size
;; exceeded` with no id, no view name and nothing pointing at the macro.
;;
;; The helper is named after the view DELIBERATELY: that is the collision,
;; and a helper named any other way would leave this row green whatever the
;; expansion binds.

(defn- ticket-body
  "The helper a body extracts, spelled the way an author spells it — the
  view's own name with `-body` on the end."
  [{:keys [id]}]
  [:li.ticket (str "ticket " id)])

(rf.fresco/defview ticket [props] (ticket-body props))

(defn- rendered
  "One boundary, server-rendered under a frame — React running the body
  for real, which is the level this collision bites at as hard as it
  bites at the kit's."
  [hiccup]
  ;; `renderToString` is not inside React's act queue, and the flag is set
  ;; outright for the reason the package smoke gives: the helper that
  ;; carries it lives in the bench tree, which this package may not import.
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (react-dom-server/renderToString
    (rf.fresco.impl.mount/provider frame-id (rf.fresco.impl.codec/root-element frame-id hiccup))))

(deftest the-fn-defview-emits-binds-no-name-a-body-could-reach
  (testing "a body that calls a helper named after its view resolves the
            AUTHOR's helper: React runs the body once and the markup is the
            helper's, where a self-binding emitted fn would recurse forever"
    (is (re-find #"<li[^>]*class=\"ticket\"[^>]*>ticket 7</li>"
                 (rendered [ticket {:id 7}])))))

;; ---------------------------------------------------------------------------
;; Witnesses D and E — `h/defhost`'s two shapes, and what is outside them
;; ---------------------------------------------------------------------------
;;
;; The door's arity is part of its contract. `[component opts]` is a
;; fixed-width destructure over a variadic tail, so unguarded, a second
;; options map would mint as if absent — the head would read back
;; consistent (it simply has no slots) and the markup written at a
;; declared slot could never arrive. `mint-host!` refuses an option key
;; it does not know for exactly that reason, one layer down; this is the
;; same rule at the door.
;;
;; The refusals are driven THROUGH THE MACRO where the fault is in the
;; form, and through `mint-host!` directly where the fault is in a value —
;; which is also the non-macro caller's path.

(defn- error-data
  [f]
  (try (f) nil (catch :default e (ex-data e))))

(deftest defhost-refuses-a-form-after-its-options-map
  (testing "a second options map is not merged — the destructure would
            DISCARD it, so the declaration is refused at the door rather than
            minting a head whose declared slots silently do not exist"
    (let [data (error-data
                 #(rf.fresco/defhost two-options-host badge-component
                    {:server :render}
                    {:slots #{:title}}))]
      (is (= {:rf.error/id :rf.error/fresco-bad-host-declaration
              :extra       ['{:slots #{:title}}]
              :host        "re-frame.fresco.public-door-macros-cljs-test/two-options-host"}
             (select-keys data [:rf.error/id :extra :host]))))))

(deftest defhost-refuses-options-that-are-not-a-map
  (testing "unguarded, a non-map would go straight from `mint-host!`'s
            nil-component check to `(keys opts)`, and whatever `keys`
            raised would not be a declaration refusal. `h/reg-state` carries
            the same guard; this is it on the comparable surface"
    (let [data (error-data #(rf.fresco.impl.codec/mint-host! "doc/in-the-wrong-place"
                                              badge-component
                                              "a docstring in the wrong place"))]
      (is (= {:rf.error/id :rf.error/fresco-bad-host-declaration
              :options     "a docstring in the wrong place"}
             (select-keys data [:rf.error/id :options]))))))
