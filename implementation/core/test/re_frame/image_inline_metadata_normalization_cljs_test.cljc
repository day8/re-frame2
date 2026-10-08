(ns re-frame.image-inline-metadata-normalization-cljs-test
  "Inline image metadata is doc-normalized the same way for every inline kind
  (event, sub, fx, cofx): `:doc` is pure documentation, kept in dev and stripped
  in production at the descriptor top level, where each kind's lowering spreads
  the authored metadata, and under the nested `:metadata`. Each row drives the
  live image and assembly-side lowering with a load-bearing `:tags` witness, so
  \"no `:doc` anywhere\" cannot be satisfied by a lowering that produced nothing.

  The kind namespaces are required so their `:image/lower-inline-<kind>`
  publishers are installed; image-assembly cannot require them (a cycle).

  The dev rows read `:doc` in a `(when rf.interop/debug-enabled? ...)` arm: under
  the production gate it is stripped before the descriptor is built. The
  production rows rebind the flag, so they hold in both postures."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.interop        :as rf.interop]
            [re-frame.events]
            [re-frame.subs]
            [re-frame.fx]
            [re-frame.cofx]))

(def ^:private supported-kinds
  [{:kind :event :section :reg-event :body (fn [_cofx _event] {})}
   {:kind :sub   :section :reg-sub   :body (fn [_db _query] :ok)}
   {:kind :fx    :section :reg-fx    :body (fn [_args] nil)}
   {:kind :cofx  :section :reg-cofx  :body (fn [] :v)}])

(defn- assemble
  "Lower ONE inline registration through the live `rf/image` and assembly-side
  lowering; return the runnable descriptor a frame resolves."
  [section id metadata body]
  (-> (rf.image/image {:id            :mt1cvi/inline-image
                       :registrations {section [[id metadata body]]}})
      :rf.image/inline
      first
      rf.image-assembly/lower-inline-descriptor))

(deftest dev-retains-authored-doc-across-every-kind
  (doseq [{:keys [kind section body]} supported-kinds]
    (let [d (assemble section (keyword "counter" (str (name kind) "-doc"))
                      {:doc "author note" :tags [:audit]} body)]
      (when rf.interop/debug-enabled?
        (is (= ["author note" "author note"] [(:doc d) (get-in d [:metadata :doc])])
            (str kind))))))

(deftest production-strips-doc-everywhere-across-every-kind
  (with-redefs [rf.interop/debug-enabled? false]
    (doseq [{:keys [kind section body]} supported-kinds]
      (let [d (assemble section (keyword "counter" (str (name kind) "-prod"))
                        {:doc "elided in prod" :tags [:audit]} body)]
        (is (= [kind false [:audit] {:tags [:audit]} true]
               [(:kind d) (contains? d :doc) (:tags d) (:metadata d) (fn? (:handler-fn d))])
            (str kind))))))

(deftest production-doc-only-metadata-drops-the-nested-map-across-every-kind
  ;; a doc-only map reduces to empty and is dropped, not kept as {}
  (with-redefs [rf.interop/debug-enabled? false]
    (doseq [{:keys [kind section body]} supported-kinds]
      (let [d (assemble section (keyword "counter" (str (name kind) "-doc-only"))
                        {:doc "only a note"} body)]
        (is (= [false nil true]
               [(contains? d :doc) (:metadata d) (fn? (:handler-fn d))])
            (str kind))))))
