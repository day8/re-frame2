(ns day8.re-frame2-xray.views.edn-inspector-protocol-cljs-test
  "The IXrayEdnInspector custom-formatters seam in `render-node`: built-in
  values stay on the built-in dispatch, a protocol node keeps the built-in
  testid shape, a nil or throwing header falls through, a nil body renders
  header-only, an expansion override collapses the body, the seam yields to
  diff mode for a changed leaf, and a consumer body recursing `render-node`
  reaches the mount's captured dispatcher."
  (:require [cljs.test :refer-macros [are deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.views.edn-inspector :as ei]
            [day8.re-frame2-xray.views.edn-inspector-protocol
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

(defn- render
  ([v] (render v {}))
  ([v node-opts]
   (ei/render-node (merge {:value         v
                           :panel-id      :test
                           :mount-id      "m1"
                           :path          []
                           :depth         0
                           :expansion-map {}
                           :opts          {}}
                          node-opts))))

;; ---- consumer types -----------------------------------------------------

(deftype FullCustom [tag payload]
  IXrayEdnInspector
  (-xray-render-header [_ _opts]
    [:span {:data-testid "custom-header"
            :data-custom-tag (str tag)} (str "#" tag)])
  (-xray-render-body [_ _opts]
    [:span {:data-testid "custom-body"} (str "body:" payload)]))

(deftype HeaderOnly [label]
  IXrayEdnInspector
  (-xray-render-header [_ _opts]
    [:span {:data-testid "header-only"} (str "h:" label)])
  (-xray-render-body [_ _opts] nil))

(deftype OptsOut [inner]
  IXrayEdnInspector
  (-xray-render-header [_ _opts] nil)
  (-xray-render-body [_ _opts] [:span "ignored body"]))

(deftype Broken []
  IXrayEdnInspector
  (-xray-render-header [_ _opts] (throw (ex-info "boom" {})))
  (-xray-render-body [_ _opts] (throw (ex-info "boom" {}))))

;; ---- dispatch -----------------------------------------------------------

(deftest built-in-values-do-not-pick-up-protocol-path
  (let [h (render {:a 1 :b 2})]
    (is (nil? (find-attr h :data-rf-protocol "1")))
    (is (some? (find-attr h :data-rf-kind "map")))))

(deftest protocol-node-carries-stable-testid
  ;; The same `[panel-id mount-id path]` testid as a built-in node, so a
  ;; panel's toggle and reset affordances address it uniformly.
  (is (some? (find-attr (render (FullCustom. "Account" "data")
                                {:mount-id "m99" :path [:k]})
                        :data-testid "rf-xray-edn-inspector-test-m99-:k"))))

(deftest nil-or-throwing-header-falls-through-to-built-ins
  ;; A consumer that declines (nil header) or breaks (the safe accessor
  ;; catches) lands on the built-in `:other` pr-str fallback rather than
  ;; blanking the inspector.
  (are [v] (some? (find-attr (render v) :data-rf-type "other"))
    (OptsOut. "inner")
    (Broken.)))

(deftest body-nil-renders-header-only
  (let [h (render (HeaderOnly. "tag"))]
    (is (some? (find-attr h :data-testid "header-only")))
    (is (nil? (find-attr h :data-testid "rf-xray-edn-inspector-test-m1--body")))))

(deftest expansion-map-collapses-protocol-body
  (let [h (render (FullCustom. "Account" "data")
                  {:expansion-map {(ei/expansion-key :test "m1" []) {:expanded? false}}})]
    (is (some? (find-attr h :data-rf-expanded "0")))
    (is (nil? (find-attr h :data-testid "custom-body")))))

;; ---- the seam yields to diff mode ----------------------------------------
;;
;; The default formatters extend uuid and `js/Date`, so in a typical app-db
;; every `:session-id` and `:updated-at` takes the protocol path. A CHANGED
;; one must still wear the diff chrome, keeping the consumer's rendering as
;; the leaf's `:scalar-fn`; an UNCHANGED one keeps the plain protocol node.

(defn- diff-leaf
  [before after]
  (render after {:before before :diff? true :path [:k]}))

(deftest modified-uuid-leaf-carries-diff-chrome
  (let [h (diff-leaf (uuid "00000000-0000-0000-0000-00000000aaaa")
                     (uuid "00000000-0000-0000-0000-00000000bbbb"))]
    (is (some? (find-attr h :data-rf-diff-op "modified")))
    (is (some? (find-attr h :data-rf-default-fmt "uuid"))
        "the default uuid formatter still renders the after-value inside the diff row")
    (is (re-find #"bbbb.*← was .*aaaa" (collect-text h))
        "the after uuid, then the `← was` chip naming the before uuid")))

(deftest unchanged-uuid-leaf-keeps-the-plain-protocol-node
  (is (some? (find-attr (diff-leaf (uuid "00000000-0000-0000-0000-00000000aaaa")
                                   (uuid "00000000-0000-0000-0000-00000000aaaa"))
                        :data-rf-protocol "1"))))

(deftest added-protocol-leaf-carries-the-added-chrome
  ;; `:added` is resolved by the structural sentinel and rendered through
  ;; its own `render-leaf-with-diff` call site, which must thread the
  ;; consumer's formatter too.
  (let [h (diff-leaf ei/missing-sentinel
                     (uuid "00000000-0000-0000-0000-00000000bbbb"))]
    (is (some? (find-attr h :data-rf-diff-op "added")))
    (is (some? (find-attr h :data-rf-default-fmt "uuid")))))

;; ---- the instance dispatcher survives protocol recursion -----------------
;;
;; A consumer body recursing `render-node` with the opts it was handed (021
;; §10.0.6's worked example) must carry the mount's captured `:dispatch-fn`,
;; or a nested collection's toggle falls back to the global `rf/dispatch`
;; and writes off the frame the widget reads its expansion state on. On the
;; default frame the two reach the same app-db, so this runs on non-default
;; frames.

(deftype LedgerMoney [amount currency ledger]
  IXrayEdnInspector
  (-xray-render-header [_ _opts]
    [:span {:data-testid "money-header"} (str amount " " currency)])
  (-xray-render-body [_ opts]
    (ei/render-node (-> opts
                        (assoc :value ledger)
                        (update :path conj :ledger)))))

(def ^:private ledger-fixture
  ;; Too wide to inline-fit, so the nested container renders its own toggle.
  [{:entry-id 1 :memo "opening balance" :cents 1000}
   {:entry-id 2 :memo "flat white" :cents -450}])

(deftest protocol-recursion-toggle-updates-only-the-mounted-instance
  (ei/install!)
  (rf/make-frame {:id ::instance-a})
  (rf/make-frame {:id ::instance-b})
  (let [tree   (render (LedgerMoney. 42 "AUD" ledger-fixture)
                       {:dispatch-fn (:dispatch-sync (rf/capture-frame ::instance-a))})
        toggle (:on-click
                 (second (find-attr tree :data-testid
                                    "rf-xray-edn-inspector-test-m1-:ledger-toggle")))
        k      (ei/expansion-key :test "m1" [:ledger])]
    (toggle nil)
    (is (= [true false false]
           (for [frame [::instance-a ::instance-b :rf/default]]
             (some? (get-in (rf/app-db-value frame) [ei/expansion-slot k]))))
        "the nested toggle updated the mounted instance, not a second instance or the host frame")))
