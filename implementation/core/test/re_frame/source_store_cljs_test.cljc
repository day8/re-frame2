(ns re-frame.source-store-cljs-test
  "EP-0023: the provenance-preserving registration source store.

    - same kind + id + DIFFERENT namespace -> both descriptors retained;
    - same kind + id + SAME namespace      -> replacement (hot reload);
    - `:rf.provenance/ns` recorded as a canonical string, one pooled instance
      per namespace;
    - `rf.registrar/register!` populates the store alongside the
      last-write-wins resolver map, and the removal paths keep the two in step.

  The store makes no assembly / selection / collision decision (image assembly
  selects from it), so these cases assert PRESERVATION, not resolution.

  `.cljc` so the suite runs on both the JVM gate and `npm run test:cljs`."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.registrar      :as rf.registrar]
            [re-frame.source-coords  :as rf.source-coords]
            [re-frame.image          :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.source-store   :as rf.source-store]))

;; The assembly cases read the live standard registry and generation cache, so
;; reset both alongside the store so a stale generation never leaks across cases.
(use-fixtures :each
  (fn [t]
    (rf.source-store/clear-all!)
    (reset! rf.registrar/kind->id->metadata {})
    (rf.image-assembly/clear-standards!)
    (rf.image-assembly/clear-generation-cache!)
    (t)
    (rf.source-store/clear-all!)
    (reset! rf.registrar/kind->id->metadata {})
    (rf.image-assembly/clear-standards!)
    (rf.image-assembly/clear-generation-cache!)))

(defn- handler-fns
  "`provenance-ns -> descriptor` slots, read down to each slot's `:handler-fn`."
  [slots]
  (into {} (map (fn [[k v]] [k (:handler-fn v)])) slots))

(deftest same-id-different-namespace-both-survive
  ;; The image-isolation path: keyed by [kind id provenance-namespace].
  (rf.source-store/record-descriptor! :event :boot/init
                                      {:ns 'examples.todo.boot :handler-fn :todo-fn})
  (rf.source-store/record-descriptor! :event :boot/init
                                      {:ns 'examples.counter.boot :handler-fn :counter-fn})
  (is (= {"examples.todo.boot" :todo-fn "examples.counter.boot" :counter-fn}
         (handler-fns (rf.source-store/descriptors-for :event :boot/init)))))

(deftest same-namespace-replacement-does-not-disturb-siblings
  (rf.source-store/record-descriptor! :event :boot/init {:ns 'a.boot :handler-fn :a1})
  (rf.source-store/record-descriptor! :event :boot/init {:ns 'b.boot :handler-fn :b1})
  (rf.source-store/record-descriptor! :event :boot/init {:ns 'a.boot :handler-fn :a2})
  (is (= {"a.boot" :a2 "b.boot" :b1}
         (handler-fns (rf.source-store/descriptors-for :event :boot/init)))))

(deftest provenance-ns-canonical-one-string-per-namespace
  ;; EP-0023: one string per namespace, not per descriptor, however spelled.
  (let [a (:rf.provenance/ns (rf.source-store/record-descriptor! :event :one {:ns 'shared.feature :handler-fn :a}))
        b (:rf.provenance/ns (rf.source-store/record-descriptor! :sub   :two {:ns 'shared.feature :handler-fn :b}))]
    (is (= "shared.feature" a))
    (is (every? #(identical? a %)
                [b
                 (rf.source-store/canonical-ns 'shared.feature)
                 (rf.source-store/canonical-ns "shared.feature")]))))

(deftest programmatic-no-provenance-recorded-once
  ;; A registration with no :ns is kept under the nil slot, never dropped, and a
  ;; later programmatic re-register of the same (kind, id) replaces it.
  (rf.source-store/record-descriptor! :event :prog/handler {:handler-fn :v1})
  (rf.source-store/record-descriptor! :event :prog/handler {:handler-fn :v2})
  (is (= {nil {:handler-fn :v2 :kind :event :id :prog/handler}}
         (rf.source-store/descriptors-for :event :prog/handler))))

(deftest unregister-bang-forgets-source-slots
  (rf.registrar/register! :event :x/y {:ns 'a.ns :handler-fn :a})
  (rf.registrar/register! :event :x/y {:ns 'b.ns :handler-fn :b})
  (is (= 2 (count (rf.source-store/descriptors-for :event :x/y))) "both recorded first")
  (rf.registrar/unregister! :event :x/y)
  (is (= {} (rf.source-store/descriptors-for :event :x/y))
      "every provenance slot for the id is forgotten"))

(deftest clear-all-resets-source-store
  (rf.registrar/register! :event :a/b {:ns 'n.s :handler-fn :f})
  (is (seq (rf.source-store/descriptors-for :event :a/b)) "recorded")
  (rf.registrar/clear-all!)
  (is (empty? (rf.source-store/kinds-present)) "no kinds remain in the store"))

(deftest forget-descriptor-removes-one-slot
  (rf.source-store/record-descriptor! :event :boot/init {:ns 'a.ns :handler-fn :a})
  (rf.source-store/record-descriptor! :event :boot/init {:ns 'b.ns :handler-fn :b})
  (rf.source-store/forget-descriptor! :event :boot/init 'a.ns)
  (is (= #{"b.ns"} (set (keys (rf.source-store/descriptors-for :event :boot/init))))))

(deftest all-descriptors-flattens-across-slots
  (rf.source-store/record-descriptor! :event :boot/init {:ns 'a.ns :handler-fn :a})
  (rf.source-store/record-descriptor! :event :boot/init {:ns 'b.ns :handler-fn :b})
  (rf.source-store/record-descriptor! :event :other     {:ns 'c.ns :handler-fn :c})
  (is (= [:a :b :c] (sort (map :handler-fn (rf.source-store/all-descriptors :event))))))

(deftest registrar-recorded-descriptor-assembles-via-all-descriptors
  ;; Image assembly reads (:kind d) / (:id d), so a register!-recorded
  ;; descriptor must carry them or a :select-ns image fails on a nil kind.
  (rf.registrar/register! :event :counter/inc
                          {:ns 'docs.counter.v2 :handler-fn (fn [_ _] {})})
  (let [img (rf.image/image {:id        :docs.counter/v2
                             :select-ns {:include ["docs.counter.v2"]}})]
    (is (some? (rf.image-assembly/resolve-descriptor
                 (rf.image-assembly/assemble [img]) :event :counter/inc)))))

(deftest provenance-from-pending-coords-when-ns-stripped
  ;; In production `merge-coords` strips :ns from the descriptor, but the
  ;; reg-* macro's `*pending-coords*` binding keeps it, so provenance is read
  ;; off the live binding or :include-ns assembly could not work
  ;; (EP-0023 §Namespace-Selected Images).
  (binding [rf.source-coords/*pending-coords* {:ns "docs.counter.prod"}]
    (is (= "docs.counter.prod"
           (:rf.provenance/ns (rf.source-store/record-descriptor! :event :counter/inc
                                                                  {:handler-fn :f}))))))

(deftest provenance-precedence-explicit-over-ns-over-pending
  ;; The `*pending-coords*` fallback is a production safety net, not an override.
  (binding [rf.source-coords/*pending-coords* {:ns "from.pending"}]
    (is (= "from.descriptor"
           (:rf.provenance/ns (rf.source-store/record-descriptor! :event :a/one
                                                                  {:ns 'from.descriptor :handler-fn :f}))))
    (is (= "tool.synthesised.ns"
           (:rf.provenance/ns (rf.source-store/record-descriptor! :event :a/two
                                                                  {:ns               'macro.captured.ns
                                                                   :rf.provenance/ns "tool.synthesised.ns"
                                                                   :handler-fn       :f}))))))

(deftest registrar-clear-kind-invalidates-resolved-generation-cache
  ;; The resolved-generation cache keys on the store generation, so clear-kind!
  ;; must bump it or a re-assemble HITS a stale generation that still resolves
  ;; the cleared id (EP-0023 §Image). No clear-all! between the assembles: it
  ;; would reset the cache and mask that stale hit.
  (rf.registrar/register! :event :counter/inc
                          {:ns 'docs.counter.v2 :handler-fn (fn [_ _] {})})
  (is (some? (rf.image-assembly/resolve-descriptor
               (rf.image-assembly/assemble []) :event :counter/inc)))
  (rf.registrar/clear-kind! :event)
  (is (nil? (rf.image-assembly/resolve-descriptor
              (rf.image-assembly/assemble []) :event :counter/inc))))
