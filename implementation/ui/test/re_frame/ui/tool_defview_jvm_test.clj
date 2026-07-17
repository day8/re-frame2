(ns re-frame.ui.tool-defview-jvm-test
  "rf2-vxgfnd.95.6 — end-to-end proof that the five `re-frame.ui.tool` projections
  read a REAL compiler-emitted manifest, not just the hand-built contract the
  `-cljs-test` companion pins. A JVM `defview` registers its manifest into the
  process-global `:view` registry (`re-frame.ui.tree/register-view!`); the
  projections read it back. The macro is host-shared, so the manifest the JVM
  emitter registers here is the same shape the CLJS emitter registers."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.registrar :as registrar]
            [re-frame.ui :as ui :refer [defview sub]]
            [re-frame.ui.tool :as tool]))

;; Real compiled views. Each registers its manifest at namespace load.

(defview literal-view
  "A static view with no sites."
  []
  [:div "static"])

(defview doc-view
  "A view whose props schema carries per-prop docs/defaults."
  {:props [:map
           [:label {:doc "The visible label."} :string]
           [:size {:default :md :optional true} :keyword]]}
  [{:keys [label]}]
  [:div label])

(defview reactive-view
  "A view with a literal sub, a dynamic sub, and event handlers."
  [{:keys [id]}]
  (let [total (sub [:demo/total])
        item  (sub [:demo/item id])]
    [:button {:on-click [:demo/inc id]
              :on-mouse-over (fn [_] nil)}
     (str total item)]))

(defn- view-id-of [v] (:rf.ui/view-id (meta v)))
(defn- manifest-of [id] (:rf.ui/manifest (registrar/lookup :view id)))

(def ^:private ids
  {:literal  (view-id-of #'literal-view)
   :doc      (view-id-of #'doc-view)
   :reactive (view-id-of #'reactive-view)})

;; The REAL emitted manifests, captured at load; a per-test fixture re-registers
;; them so a sibling namespace's registrar-clearing fixture cannot strand them.
(def ^:private captured
  (into {} (map (fn [[k id]] [id (manifest-of id)])) ids))

(use-fixtures :each
  (fn [f]
    (doseq [[id m] captured]
      (registrar/register! :view id {:rf/id id :rf.ui/compiled? true
                                     :rf.ui/manifest m}))
    (f)))

(deftest view-manifest-reads-a-real-emitted-manifest
  (let [vm (tool/view-manifest (:literal ids))]
    (is (= tool/schema-version (:rf.ui.tool/version vm)))
    (is (= (:literal ids) (:view-id vm)))
    (is (= "A static view with no sites." (:doc vm)))
    (is (map? (:source vm)) "the real source coord rides through")
    (is (= 0 (:subs (:site-counts vm)))
        "a static view declares no reactive sites")))

(deftest real-props-schema-yields-per-prop-docs-and-defaults
  (let [vm     (tool/view-manifest (:doc ids))
        by-key (into {} (map (juxt :key identity)) (:props vm))]
    (is (= "The visible label." (:doc (:label by-key)))
        "the per-prop doc is derived from the REAL emitted props schema")
    (is (= :md (:default (:size by-key))) "…and the per-prop default")
    (is (true? (:optional? (:size by-key))))))

(deftest real-sub-lease-and-event-sites-project-with-honesty
  (let [rid (:reactive ids)]
    (testing "site counts reflect the real emission"
      (let [vm (tool/view-manifest rid)]
        (is (set? (:capabilities vm)) "capabilities is a (possibly empty) set")
        (is (<= 2 (:subs (:site-counts vm))) "both subs counted")
        (is (<= 2 (:events (:site-counts vm))) "both handlers counted")))

    (testing "view-dependencies distinguishes the literal sub from the dynamic one"
      (let [deps    (tool/view-dependencies rid)
            queries (into #{} (map :query) (:subscriptions deps))
            dyn     (filter :dynamic? (:subscriptions deps))]
        (is (contains? queries [:demo/total]) "the literal sub is projected verbatim")
        (is (some #(= :demo/item (:query-id %)) dyn)
            "the sub with a captured local is :dynamic with only its query-id")))

    (testing "view-event-sites is honest about the literal vector and the opaque fn"
      (let [sites   (:handlers (tool/view-event-sites rid))
            by-prop (group-by :prop sites)]
        (is (= :literal (:site-kind (first (by-prop :on-click))))
            "the literal event vector is inspectable")
        (is (= [:demo/inc] (take 1 (:handler (first (by-prop :on-click)))))
            "…and its authored event-id is shown")
        (let [over (first (by-prop :on-mouse-over))]
          (is (= :dynamic (:site-kind over)))
          (is (= :opaque (:handler over)) "the raw callback is opaque, never inspected")
          (is (false? (:serializable? over))))))))
