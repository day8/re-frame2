(ns day8.re-frame2-xray.views.data-display-protocol-cljs-test
  "Unit tests for the IXrayDataDisplay custom-formatters protocol
  (rf2-oqa60 phase 7 · rf2-0qrcr).

  ## What's under test

  1. **Built-in types still render via the built-in dispatch.** A
     plain CLJS map / vector / scalar must NOT pick up the protocol
     path — `:data-rf-protocol` is absent.

  2. **Protocol-implementing types use the protocol methods.** A
     deftype that implements `IXrayDataDisplay` short-circuits the
     built-in dispatch; the consumer's `-xray-render-header` output
     appears verbatim in the rendered hiccup.

  3. **Header-nil fall-through.** A consumer that returns nil from
     `-xray-render-header` falls through to the built-in renderer
     for that node.

  4. **Body-nil suppresses body.** A consumer with header but nil
     body renders header-only (no expanded body container).

  5. **Toggle wiring still composes.** The protocol node carries the
     same `data-testid` shape `[panel-id mount-id path]` as built-in
     nodes, so a panel's reset / toggle affordances still address
     it uniformly.

  Pure-data unit tests; no DOM mount."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.views.data-display :as dd]
            [day8.re-frame2-xray.views.data-display-protocol :as ddp
             :refer [IXrayDataDisplay]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers ------------------------------------------------------------

(defn- walk-hiccup
  "Depth-first collect every hiccup vector in `tree`."
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (vector? node)
                (do (swap! out conj node)
                    (doseq [child (rest node)] (walk child)))
                (seq? node) (doseq [c node] (walk c))))]
      (walk tree))
    @out))

(defn- find-attr
  [tree k v]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= v (get (second n) k)))))
       first))

(defn- collect-text
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (cond
                (string? node) (swap! out conj node)
                (vector? node) (doseq [c (rest node)] (walk c))
                (seq? node)    (doseq [c node] (walk c))))]
      (walk tree))
    (apply str @out)))

;; ---- consumer types -----------------------------------------------------

;; A type that satisfies the protocol with both header + body.
(deftype FullCustom [tag payload]
  IXrayDataDisplay
  (-xray-render-header [_ _opts]
    [:span {:data-testid "custom-header"
            :data-custom-tag (str tag)} (str "#" tag)])
  (-xray-render-body [_ _opts]
    [:span {:data-testid "custom-body"} (str "body:" payload)]))

;; A type that only customises the header.
(deftype HeaderOnly [label]
  IXrayDataDisplay
  (-xray-render-header [_ _opts]
    [:span {:data-testid "header-only"} (str "h:" label)])
  (-xray-render-body [_ _opts] nil))

;; A type that opts-out by returning nil header.
(deftype OptsOut [inner]
  IXrayDataDisplay
  (-xray-render-header [_ _opts] nil)
  (-xray-render-body [_ _opts] [:span "ignored body"]))

;; A consumer impl that THROWS — the safe accessor must catch.
(deftype Broken []
  IXrayDataDisplay
  (-xray-render-header [_ _opts] (throw (ex-info "boom" {})))
  (-xray-render-body [_ _opts] (throw (ex-info "boom" {}))))

;; ---- 1. built-in dispatch untouched -------------------------------------

(deftest plain-map-does-not-pick-up-protocol-path
  (let [h (dd/render-node {:value {:a 1 :b 2}
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (nil? (find-attr h :data-rf-protocol "1"))
        "plain map MUST NOT render via the protocol path")
    (is (some? (find-attr h :data-rf-kind "map"))
        "plain map renders via built-in :map dispatch")))

(deftest plain-vector-does-not-pick-up-protocol-path
  (let [h (dd/render-node {:value [1 2 3]
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (nil? (find-attr h :data-rf-protocol "1")))
    (is (some? (find-attr h :data-rf-kind "vector")))))

(deftest plain-scalar-does-not-pick-up-protocol-path
  (let [h (dd/render-node {:value 42
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (nil? (find-attr h :data-rf-protocol "1")))))

(deftest sentinel-does-not-pick-up-protocol-path
  ;; Sentinels are first-class types — they must stay on the built-in
  ;; dispatch, not be accidentally diverted by the protocol seam.
  (let [h (dd/render-node {:value :rf/redacted
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (nil? (find-attr h :data-rf-protocol "1")))
    (is (some? (find-attr h :data-rf-type "rf-redacted")))))

;; ---- 2. protocol-implementing types use the protocol --------------------

(deftest full-custom-renders-via-protocol-header-and-body
  (let [v (FullCustom. "Account" "data-here")
        h (dd/render-node {:value v
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (some? (find-attr h :data-rf-protocol "1"))
        "protocol path is taken")
    (is (some? (find-attr h :data-testid "custom-header"))
        "consumer's header hiccup is rendered")
    (is (re-find #"#Account" (collect-text h))
        "consumer's header text appears in output")
    ;; default-expanded? on protocol nodes is true; body renders.
    (is (some? (find-attr h :data-testid "custom-body"))
        "consumer's body hiccup is rendered when expanded")
    (is (re-find #"body:data-here" (collect-text h)))))

(deftest protocol-node-carries-stable-testid
  (let [v (FullCustom. "Account" "data")
        h (dd/render-node {:value v
                           :panel-id :test
                           :mount-id "m99"
                           :path [:k]
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (some? (find-attr h :data-testid
                          "rf-xray-data-display-test-m99-:k"))
        "protocol node carries the same `[panel-id mount-id path]` testid as built-in nodes")))

;; ---- 3. header-nil fall-through to built-ins ----------------------------

(deftest header-nil-falls-through-to-built-ins
  ;; OptsOut returns nil from header — but the underlying value isn't
  ;; itself a built-in container/scalar; it's a deftype. The
  ;; fall-through lands at the `:other` scalar case (pr-str). That's
  ;; the right outcome — the consumer explicitly opted out, the
  ;; widget shouldn't second-guess.
  (let [v (OptsOut. "inner")
        h (dd/render-node {:value v
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (nil? (find-attr h :data-rf-protocol "1"))
        "header-nil opts out of the protocol path")
    (is (some? (find-attr h :data-rf-type "other"))
        "falls through to the :other scalar fallback")))

;; ---- 4. body-nil renders header-only ------------------------------------

(deftest body-nil-renders-header-only
  (let [v (HeaderOnly. "tag")
        h (dd/render-node {:value v
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (some? (find-attr h :data-rf-protocol "1"))
        "protocol path is still taken (header is non-nil)")
    (is (some? (find-attr h :data-testid "header-only"))
        "consumer header rendered")
    ;; No body container — the testid suffix `-body` is absent.
    (is (nil? (find-attr h :data-testid
                         "rf-xray-data-display-test-m1--body"))
        "no body container rendered when body-fn returns nil")))

;; ---- 5. broken consumer impl: safe catch --------------------------------

(deftest broken-consumer-impl-falls-through-safely
  ;; A consumer impl that throws must not blank the whole inspector.
  ;; The safe accessor catches; the widget sees nil header and
  ;; falls through to built-ins (which renders the deftype via
  ;; the :other / pr-str fallback).
  (let [v (Broken.)
        h (dd/render-node {:value v
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (nil? (find-attr h :data-rf-protocol "1"))
        "broken impl falls through to built-ins")
    (is (some? (find-attr h :data-rf-type "other"))
        "lands on the :other pr-str fallback")))

;; ---- 6. expansion-map override still wins on protocol nodes -------------

(deftest expansion-map-collapses-protocol-body
  (let [v (FullCustom. "Account" "data")
        ;; Force-collapsed via the expansion-map override.
        k (dd/expansion-key :test "m1" [])
        h (dd/render-node {:value v
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {k {:expanded? false}}
                           :opts {}})]
    (is (some? (find-attr h :data-rf-protocol "1")))
    (is (some? (find-attr h :data-rf-expanded "0"))
        "expansion-map override flips :data-rf-expanded to \"0\"")
    (is (nil? (find-attr h :data-testid "custom-body"))
        "body NOT rendered when expansion-map collapses the node")))

;; ---- 7. predicate convenience -------------------------------------------

(deftest satisfies-predicate
  (is (true?  (ddp/satisfies-xray-data-display? (FullCustom. "x" "y"))))
  (is (false? (ddp/satisfies-xray-data-display? {:a 1})))
  (is (false? (ddp/satisfies-xray-data-display? 42)))
  (is (false? (ddp/satisfies-xray-data-display? nil))))
