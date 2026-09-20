(ns day8.re-frame2-xray.views.edn-inspector-protocol-cljs-test
  "Unit tests for the IXrayEdnInspector custom-formatters protocol
  (rf2-oqa60 phase 7 · rf2-0qrcr).

  ## What's under test

  1. **Built-in types still render via the built-in dispatch.** A
     plain CLJS map / vector / scalar must NOT pick up the protocol
     path — `:data-rf-protocol` is absent.

  2. **Protocol-implementing types use the protocol methods.** A
     deftype that implements `IXrayEdnInspector` short-circuits the
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
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-inspector-protocol :as ddp
             :refer [IXrayEdnInspector]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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
  IXrayEdnInspector
  (-xray-render-header [_ _opts]
    [:span {:data-testid "custom-header"
            :data-custom-tag (str tag)} (str "#" tag)])
  (-xray-render-body [_ _opts]
    [:span {:data-testid "custom-body"} (str "body:" payload)]))

;; A type that only customises the header.
(deftype HeaderOnly [label]
  IXrayEdnInspector
  (-xray-render-header [_ _opts]
    [:span {:data-testid "header-only"} (str "h:" label)])
  (-xray-render-body [_ _opts] nil))

;; A type that opts-out by returning nil header.
(deftype OptsOut [inner]
  IXrayEdnInspector
  (-xray-render-header [_ _opts] nil)
  (-xray-render-body [_ _opts] [:span "ignored body"]))

;; A consumer impl that THROWS — the safe accessor must catch.
(deftype Broken []
  IXrayEdnInspector
  (-xray-render-header [_ _opts] (throw (ex-info "boom" {})))
  (-xray-render-body [_ _opts] (throw (ex-info "boom" {}))))

;; ---- 1. built-in dispatch untouched -------------------------------------

(deftest plain-map-does-not-pick-up-protocol-path
  (let [h (ei/render-node {:value {:a 1 :b 2}
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
  (let [h (ei/render-node {:value [1 2 3]
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (nil? (find-attr h :data-rf-protocol "1")))
    (is (some? (find-attr h :data-rf-kind "vector")))))

(deftest plain-scalar-does-not-pick-up-protocol-path
  (let [h (ei/render-node {:value 42
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
  (let [h (ei/render-node {:value :rf/redacted
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
        h (ei/render-node {:value v
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
        h (ei/render-node {:value v
                           :panel-id :test
                           :mount-id "m99"
                           :path [:k]
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (some? (find-attr h :data-testid
                          "rf-xray-edn-inspector-test-m99-:k"))
        "protocol node carries the same `[panel-id mount-id path]` testid as built-in nodes")))

;; ---- 3. header-nil fall-through to built-ins ----------------------------

(deftest header-nil-falls-through-to-built-ins
  ;; OptsOut returns nil from header — but the underlying value isn't
  ;; itself a built-in container/scalar; it's a deftype. The
  ;; fall-through lands at the `:other` scalar case (pr-str). That's
  ;; the right outcome — the consumer explicitly opted out, the
  ;; widget shouldn't second-guess.
  (let [v (OptsOut. "inner")
        h (ei/render-node {:value v
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
        h (ei/render-node {:value v
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
                         "rf-xray-edn-inspector-test-m1--body"))
        "no body container rendered when body-fn returns nil")))

;; ---- 5. broken consumer impl: safe catch --------------------------------

(deftest broken-consumer-impl-falls-through-safely
  ;; A consumer impl that throws must not blank the whole inspector.
  ;; The safe accessor catches; the widget sees nil header and
  ;; falls through to built-ins (which renders the deftype via
  ;; the :other / pr-str fallback).
  (let [v (Broken.)
        h (ei/render-node {:value v
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
        k (ei/expansion-key :test "m1" [])
        h (ei/render-node {:value v
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
  (is (true?  (ddp/satisfies-xray-edn-inspector? (FullCustom. "x" "y"))))
  (is (false? (ddp/satisfies-xray-edn-inspector? {:a 1})))
  (is (false? (ddp/satisfies-xray-edn-inspector? 42)))
  (is (false? (ddp/satisfies-xray-edn-inspector? nil))))

;; ---- 8. rf2-y8doi.24 — the seam YIELDS to diff mode ----------------------
;;
;; The protocol seam used to sit ahead of the diff `cond` as a bare
;; `or`, so ANY value carrying a formatter short-circuited the diff
;; render outright. That is not an exotic case: `views.edn-inspector`
;; requires `views.edn-inspector-default-formatters`, which extends
;; the protocol over `cljs.core/UUID` and `js/Date` — so in a typical
;; app-db EVERY `:session-id` and EVERY `:updated-at` took the
;; protocol path, and a CHANGED one rendered its pretty custom header
;; with no `~` glyph, no wash, no stripe and no `← was` chip. It was
;; invisible in the one mode whose entire job is showing what changed.
;;
;; The contract now: a leaf that is part of a change wears the diff
;; chrome AND keeps the consumer's rendering (threaded in as
;; `render-leaf-with-diff`'s `:scalar-fn`). An UNCHANGED leaf keeps
;; the plain protocol node — cheaper, and nothing to signal.

(defn- diff-leaf
  "Render one scalar leaf in diff mode with an explicit before/after
  pair, no projection — `leaf-diff-op`'s `(= before value)` fallback
  decides, which is what a caller without a pre-computed projection
  gets."
  [before after]
  (ei/render-node {:value after
                   :before before
                   :diff? true
                   :panel-id :test
                   :mount-id "m1"
                   :path [:k]
                   :depth 0
                   :expansion-map {}
                   :opts {}}))

(deftest modified-uuid-leaf-carries-diff-chrome
  (let [h (diff-leaf (uuid "00000000-0000-0000-0000-00000000aaaa")
                     (uuid "00000000-0000-0000-0000-00000000bbbb"))
        text (collect-text h)]
    (is (nil? (find-attr h :data-rf-protocol "1"))
        "a CHANGED uuid leaf must NOT short-circuit to the plain protocol node")
    (is (some? (find-attr h :data-rf-diff-op "modified"))
        "it renders through the diff leaf path, op :modified")
    (is (re-find #"~" text)
        "the `~` modified glyph is painted in the gutter")
    (is (re-find #"← was" text)
        "the `← was <prior>` chip names the prior value")
    (is (re-find #"aaaa" text)
        "and the prior value in that chip is the BEFORE uuid")
    ;; The point of the `:scalar-fn` seam: the consumer's formatter is
    ;; kept, not traded away for the diff chrome.
    (is (some? (find-attr h :data-rf-default-fmt "uuid"))
        "the default uuid formatter still renders the after-value inside the diff row")
    (is (re-find #"bbbb" text)
        "and it renders the AFTER uuid")))

(deftest modified-inst-leaf-carries-diff-chrome
  (let [h (diff-leaf (js/Date. "2020-01-01T00:00:00.000Z")
                     (js/Date. "2020-06-01T00:00:00.000Z"))
        text (collect-text h)]
    (is (nil? (find-attr h :data-rf-protocol "1"))
        "a CHANGED inst leaf must NOT short-circuit to the plain protocol node")
    (is (some? (find-attr h :data-rf-diff-op "modified"))
        "it renders through the diff leaf path, op :modified")
    (is (re-find #"~" text)
        "the `~` modified glyph is painted in the gutter")
    (is (re-find #"← was" text)
        "the `← was <prior>` chip names the prior value")
    (is (some? (find-attr h :data-rf-default-fmt "inst"))
        "the default inst formatter still renders the after-value inside the diff row")))

(deftest unchanged-uuid-leaf-keeps-the-plain-protocol-node
  ;; The other half of the contract, and the control for the two above:
  ;; the seam is NARROWED, not removed.
  (let [u (uuid "00000000-0000-0000-0000-00000000aaaa")
        h (diff-leaf u u)]
    (is (some? (find-attr h :data-rf-protocol "1"))
        "an UNCHANGED uuid leaf still takes the protocol path in diff mode")
    (is (nil? (find-attr h :data-rf-diff-op "modified"))
        "and wears no modified chrome")))

(deftest uuid-leaf-outside-diff-mode-keeps-the-plain-protocol-node
  ;; The narrowing is scoped to diff mode alone — the ordinary render
  ;; path is untouched.
  (let [h (ei/render-node {:value (uuid "00000000-0000-0000-0000-00000000aaaa")
                           :panel-id :test
                           :mount-id "m1"
                           :path []
                           :depth 0
                           :expansion-map {}
                           :opts {}})]
    (is (some? (find-attr h :data-rf-protocol "1"))
        "no `:diff?` — the protocol seam wins exactly as before")))

(deftest added-and-removed-protocol-leaves-carry-their-chrome
  ;; `:added` / `:removed` are resolved by the STRUCTURAL sentinel
  ;; rather than by a before/after comparison, so they reach
  ;; `leaf-diff-op` down a different branch than `:modified`.
  (let [added   (diff-leaf ei/missing-sentinel
                           (uuid "00000000-0000-0000-0000-00000000bbbb"))
        removed (ei/render-node {:value ei/missing-sentinel
                                 :before (uuid "00000000-0000-0000-0000-00000000aaaa")
                                 :diff? true
                                 :panel-id :test
                                 :mount-id "m1"
                                 :path [:k]
                                 :depth 0
                                 :expansion-map {}
                                 :opts {}})]
    (is (some? (find-attr added :data-rf-diff-op "added"))
        "an ADDED uuid leaf wears the added chrome")
    (is (some? (find-attr added :data-rf-default-fmt "uuid"))
        "and keeps the consumer's formatter")
    (is (some? (find-attr removed :data-rf-diff-op "removed"))
        "a REMOVED uuid leaf wears the removed chrome")
    (is (not (re-find #"edn-inspector/missing" (collect-text removed)))
        "and never leaks the internal `::missing` sentinel into the output")))

;; ---- 9. rf2-et4l0 — the instance dispatcher survives protocol recursion --
;;
;; `render-node` rebuilt the protocol context WITHOUT `:dispatch-fn`, and
;; `render-protocol-node` hands that context to the consumer wholesale
;; (as `opts`, and again under `:node-opts`). A consumer body following
;; the documented worked example (021 §10.0.6) recurses `render-node`
;; with it, so a nested collection's toggle reached `render-container`'s
;; `(or dispatch-fn rf/dispatch)` fallback with nothing to fall back FROM
;; and captured the GLOBAL dispatcher. Nothing errored — `or` did exactly
;; what it says — but the toggle event was then written through
;; `rf/dispatch` while the widget reads its expansion state on the frame
;; it is mounted under, so the click could not update the mounted
;; inspector and landed on the host/default frame instead. Built-in
;; recursive children were unaffected: the two sibling child-row context
;; maps in `render-container` both pass `:dispatch-fn` through.
;;
;; The two tests below are the pair that separates the two worlds: one
;; pins the MECHANISM (the captured dispatcher is what the nested toggle
;; calls), one pins the ITEM'S CONTRACT (a non-default inspector frame's
;; toggle updates THAT instance and leaves the host and a second instance
;; alone). A regression built on the DEFAULT frame cannot tell them
;; apart — there the global dispatcher and the instance's captured one
;; reach the same app-db, so the defect is invisible — which is why both
;; tests below use a non-default frame.

;; A consumer shaped like 021 §10.0.6's worked `Money` example: a chip
;; header, and a body that recurses the built-in renderer over a nested
;; collection using the opts the widget handed it.
;;
;; The spec's example returns the Reagent component vector
;; `[render-node opts']`; this calls `render-node` directly so the hiccup
;; is materialised for a pure-data assertion. Both hand `render-node` the
;; same map, which is the threading property under test.
(deftype LedgerMoney [amount currency ledger]
  IXrayEdnInspector
  (-xray-render-header [_ _opts]
    [:span {:data-testid "money-header"} (str amount " " currency)])
  (-xray-render-body [_ opts]
    (ei/render-node (-> opts
                        (assoc :value ledger)
                        (update :path conj :ledger)))))

(def ^:private ledger-fixture
  ;; Wide enough that the container cannot inline-fit (`:max-inline-width`
  ;; defaults to 60), so it renders its own toggle glyph — the affordance
  ;; the defect broke.
  [{:entry-id 1 :memo "opening balance" :cents 1000}
   {:entry-id 2 :memo "flat white" :cents -450}])

(defn- nested-ledger-toggle
  "The `:on-click` of the toggle glyph on the NESTED `[:ledger]`
  container rendered inside the consumer's protocol body."
  [tree]
  (:on-click
    (second (find-attr tree :data-testid
                       "rf-xray-edn-inspector-test-m1-:ledger-toggle"))))

(defn- render-money-with
  "Render a `LedgerMoney` through the protocol seam with `dispatch-fn` as
  the mount's captured dispatcher — the same key `render-inspector`
  threads into its top-level `render-node` call."
  [dispatch-fn]
  (ei/render-node {:value         (LedgerMoney. 42 "AUD" ledger-fixture)
                   :panel-id      :test
                   :mount-id      "m1"
                   :path          []
                   :depth         0
                   :expansion-map {}
                   :dispatch-fn   dispatch-fn
                   :opts          {}}))

(deftest protocol-recursion-toggle-calls-the-captured-dispatcher
  ;; The mechanism, with a spy standing in for the mount's dispatcher.
  (ei/install!)
  (let [seen   (atom [])
        tree   (render-money-with (fn [ev] (swap! seen conj ev)))
        toggle (nested-ledger-toggle tree)]
    (is (some? (find-attr tree :data-rf-protocol "1"))
        "control: the value took the protocol path")
    (is (fn? toggle)
        "control: the nested ledger container rendered its own toggle glyph")
    (toggle nil)
    (is (= 1 (count @seen))
        "the nested toggle dispatched exactly once through the CAPTURED dispatcher")
    (is (= [:rf.xray.edn-inspector/toggle-node :test "m1" [:ledger]]
           (subvec (first @seen) 0 4))
        "and it addressed the nested node's own expansion key")))

(deftest protocol-recursion-toggle-updates-only-the-mounted-instance
  ;; The item's contract, on REAL non-default frames.
  (ei/install!)
  (rf/make-frame {:id ::instance-a})
  (rf/make-frame {:id ::instance-b})
  (let [tree   (render-money-with (:dispatch-sync (rf/capture-frame ::instance-a)))
        toggle (nested-ledger-toggle tree)
        k      (ei/expansion-key :test "m1" [:ledger])]
    (is (fn? toggle)
        "control: the nested ledger container rendered its own toggle glyph")
    (toggle nil)
    (is (some? (get-in (rf/app-db-value ::instance-a) [ei/expansion-slot k]))
        "the toggle updated the instance the widget is mounted under")
    (is (nil? (get-in (rf/app-db-value ::instance-b) [ei/expansion-slot k]))
        "a second inspector instance is untouched")
    (is (nil? (get-in (rf/app-db-value :rf/default) [ei/expansion-slot k]))
        "and the host/default frame is untouched")))
