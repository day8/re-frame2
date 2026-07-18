(ns re-frame.ui.raw-foreign-boundary-jvm-test
  "S4-D (rf2-yjhrt) — the FOREIGN BOUNDARY corpus, JVM half (Spec 004
  §Interop and boundaries; the stage-profile row `§Interop — ui/raw` =
  S1 compile form + opaque marker, COMPLETES S4 with this corpus).

  The boundary has two sides and one law each:

  1. `ui/raw` / a foreign component head are HOST-BEARING. They cannot
     render on the JVM, so a rendered occurrence raises the TYPED
     `:rf.error/jvm-host-op` naming which form was hit — never a silent
     nil child, never a half-rendered tree. The raise is LAZY: an
     untaken branch never evaluates its argument, so a JVM structural
     test of a view that *can* reach a foreign child still renders every
     path that avoids it.

  2. A foreign value in PROP position is OPAQUE. It becomes the
     `{:rf.ui/opaque :foreign}` marker and the value itself is NEVER
     evaluated and NEVER enters the tree — so no host object can leak
     into a structural tree, a fingerprint, or a serialised payload.

  The trusted-markup boundary (`ui/html`) is the third foreign edge: the
  string crosses into the document unescaped and unfiltered. This file
  pins that `re-frame.ui` applies NO sanitisation — the contract the
  Spec 004 §Trusted markup guidance documents.

  The compile-form rejections (`:rf.ui.compile/bad-raw`,
  `.../bad-html`, `.../html-not-sole-child`) ride the analyzer suites;
  the client half — the foreign element actually reaching the DOM — is
  raw_foreign_boundary_dom_cljs_test."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.tree :as tree]))

(defn- boom! []
  (throw (ex-info "a foreign value must never be evaluated on the JVM" {})))

;; A resolvable var WITHOUT view metadata is a foreign React head.
(def ForeignWidget ::not-a-view)

(defview raw-child-view [{:keys [foreign?]}]
  [:div.host
   [:span.before "before"]
   (when foreign? (ui/raw (boom!)))
   [:span.after "after"]])

(defview foreign-head-view [{:keys [foreign?]}]
  [:div.host (when foreign? [ForeignWidget {:x 1}])])

(defview foreign-prop-sink [{:keys [title]}]
  [:div.sink title])

(defview boundary-props-view []
  [foreign-prop-sink {:title "plain data"
                      :el    (ui/raw (boom!))
                      :cb    (ui/raw-fn boom!)}])

(defview trusted-view [{:keys [markup]}]
  [:div.content (ui/html markup)])

(defn- err [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- element
  "The view's root ELEMENT (`tree/render` returns the view-boundary wrapper)."
  [view props]
  (first (:children (tree/render view props))))

;; ---------------------------------------------------------------------------
;; 1. Host-bearing forms raise the TYPED host-op error, lazily
;; ---------------------------------------------------------------------------

(deftest raw-child-raises-the-typed-host-op-error
  (let [d (err #(tree/render raw-child-view {:foreign? true}))]
    (is (= :rf.error/jvm-host-op (:rf.error/id d))
        "the boundary fails with the typed id, not a bare host exception")
    (is (= :ui/raw (:op d))
        "the ex-data names WHICH host-bearing form was hit")
    (is (= 're-frame.ui/render (:where d))
        "attributed to the render entry point")))

(deftest foreign-component-head-raises-the-typed-host-op-error
  (let [d (err #(tree/render foreign-head-view {:foreign? true}))]
    (is (= :rf.error/jvm-host-op (:rf.error/id d)))
    (is (= :foreign-component (:op d))
        "a foreign head is a DISTINCT boundary from ui/raw and says so")))

(deftest the-boundary-is-lazy-and-does-not-poison-the-surrounding-tree
  ;; The argument of an untaken `ui/raw` is never evaluated (`boom!` would
  ;; throw a DIFFERENT exception), and every compiled sibling still renders.
  (let [kids (:children (element raw-child-view {:foreign? false}))]
    (is (= 2 (count kids)) "the untaken foreign child contributes no node")
    (is (= ["before" "after"] (mapv #(first (:children %)) kids))
        "compiled siblings render normally around the absent boundary"))
  (is (some? (tree/render foreign-head-view {:foreign? false}))
      "an untaken foreign head is equally inert"))

;; ---------------------------------------------------------------------------
;; 2. A foreign value in PROP position is opaque — and never evaluated
;; ---------------------------------------------------------------------------

(deftest foreign-props-become-the-opaque-marker-carrying-no-host-value
  (let [props (:props (element boundary-props-view {}))]
    (testing "the boundary is recorded, discriminated by form"
      (is (= {:rf.ui/opaque :foreign} (:el props))
          "a (ui/raw …) prop is the opaque :foreign marker")
      (is (= {:rf.ui/opaque :ui/raw-fn} (:cb props))
          "a callback-ref prop is the opaque :ui/raw-fn marker"))
    (testing "the host value itself never enters the tree"
      ;; `boom!` did not throw, so the foreign expression was never
      ;; evaluated: no host object can reach a structural tree, a
      ;; fingerprint, or a serialised payload through a prop.
      (is (not (contains? (set (vals props)) nil))
          "no prop degrades to a silent nil")
      (is (= "plain data" (:title props))
          "ordinary data props are unaffected by a foreign sibling prop"))))

;; ---------------------------------------------------------------------------
;; 3. Trusted markup — the boundary re-frame.ui does NOT sanitise
;; ---------------------------------------------------------------------------

(deftest trusted-markup-crosses-the-boundary-verbatim
  (let [hostile (str "<img src=x onerror=\"steal()\">"
                     "<a href=\"javascript:alert(1)\">click</a>"
                     "<b>bold & raw</b>")
        node    (first (:children (element trusted-view {:markup hostile})))]
    (is (= {:html hostile} node)
        "the trusted-HTML leaf carries the string VERBATIM — no escaping, no tag filter, no attribute filter, no javascript:-scheme gate")
    (testing "the raw ampersand and angle brackets are NOT entity-escaped"
      (is (.contains ^String (:html node) "bold & raw"))
      (is (.contains ^String (:html node) "<img src=x")))
    (testing "normalization N carries it as an opaque leaf, still verbatim"
      (let [[sem] (semantic/normalize (tree/render trusted-view {:markup hostile}))]
        (is (= [{:html hostile}] (:children sem))
            "N compares raw markup verbatim — it does not parse or sanitise it")))))

(deftest trusted-markup-shape-check-is-the-only-runtime-guard
  ;; The JVM node builder's ONLY validation is `string?`. It is a shape
  ;; check, never a content check — nothing about the string is inspected.
  (let [d (err #(tree/render trusted-view {:markup 42}))]
    (is (= :rf.error/ui-tree-malformed (:rf.error/id d))
        "a non-string trusted-markup value fails loud on the JVM")
    (is (= 're-frame.ui/html (:where d))))
  (is (= {:html ""} (first (:children (element trusted-view {:markup ""}))))
      "an empty string is a legal (and inert) bypass — emptiness is not a content rule"))
