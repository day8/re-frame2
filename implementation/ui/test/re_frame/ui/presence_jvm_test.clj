(ns re-frame.ui.presence-jvm-test
  "S4 presence (rf2-uckeg) on the JVM Tier-1 structural host (Spec 004
  §Presence + §The JVM structural subset; §004B reserved `:rf.ui/presence`):

    - a (ui/presence {:timeout-ms n} …) boundary renders a FRAGMENT carrying
      `:rf.ui/presence {:phase :present :timeout-ms n}` — the JVM has no
      lifecycle, so every keyed child renders `:present` (no retention timers);
    - `(ui/presence-phase)` reads `:present` on the JVM structural subset AND
      `:present` outside any boundary;
    - the `:rf.ui/presence` diagnostic marker is stripped by semantic
      normalization N (it never changes markup / a fingerprint).

  The client three-phase machine (enter/exit retention, `:timeout-ms` removal,
  exactly-once cleanup, `flush-presence!`) rides the DOM suite
  `presence_dom_cljs_test`; the pure reconcile rides `presence_reconcile_cljs_test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.emit-cljs :as emit-cljs]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.test :as uit]))

(use-fixtures :each (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- all-nodes [t]
  (tree-seq map? (fn [n] (filter map? (:children n))) t))

(defn- presence-node [t]
  (some #(when (contains? % :rf.ui/presence) %) (all-nodes t)))

(defn- all-strings [t]
  (mapcat (fn [n] (filter string? (:children n))) (all-nodes t)))

;; ---------------------------------------------------------------------------
;; Views
;; ---------------------------------------------------------------------------

(defview toast-card [{:keys [msg]}]
  ;; a presence-aware child: it reads its phase (always :present on the JVM).
  [:li {:data-phase (name (ui/presence-phase))} msg])

(defview toast-list [{:keys [toasts]}]
  (ui/presence {:timeout-ms 300}
    (for [t toasts]
      [toast-card {:key (:id t) :msg (:msg t)}])))

(defview reusable-outside-boundary []
  ;; presence-phase OUTSIDE any boundary must still resolve to :present, so the
  ;; child stays reusable anywhere.
  [:span {:data-phase (name (ui/presence-phase))} "reusable"])

;; ---------------------------------------------------------------------------
;; JVM :present — the fragment marker + every child present
;; ---------------------------------------------------------------------------

(deftest presence-renders-the-present-marker-fragment
  (let [t (uit/render toast-list {:props {:toasts [{:id 1 :msg "a"} {:id 2 :msg "b"}]}})
        p (presence-node t)]
    (is (some? p) "the JVM emits a `:rf.ui/presence` fragment node")
    (is (= {:phase :present :timeout-ms 300} (:rf.ui/presence p))
        "the marker carries :phase :present and the compile-time :timeout-ms")
    (is (not (contains? p :tag)) "the presence node is a FRAGMENT, not an element")
    (testing "every keyed child renders (no retention on the JVM)"
      (is (= #{"a" "b"} (set (all-strings t)))))))

(deftest presence-phase-is-present-on-the-jvm
  (let [t (uit/render toast-list {:props {:toasts [{:id 1 :msg "a"}]}})]
    (is (= "present" (:data-phase (uit/attrs (uit/find t :li))))
        "a presence-aware child reads :present on the JVM structural subset"))
  (testing "presence-phase OUTSIDE a boundary is also :present (reusable anywhere)"
    (let [t (uit/render reusable-outside-boundary)]
      (is (= "present" (:data-phase (uit/attrs (uit/find t :span))))))))

;; ---------------------------------------------------------------------------
;; §004B — the marker is a droppable diagnostic (stripped by N)
;; ---------------------------------------------------------------------------

(deftest presence-marker-is-stripped-by-semantic-normalization
  (let [t (uit/render toast-list {:props {:toasts [{:id 1 :msg "a"}]}})
        n (semantic/normalize t)]
    (is (not-any? #(and (map? %) (contains? % :rf.ui/presence))
                  (tree-seq coll? seq n))
        "the :rf.ui/presence diagnostic never reaches the semantic projection")
    (is (contains? (set (mapcat :children (filter map? (tree-seq coll? seq n)))) "a")
        "the keyed child content survives normalization")))

;; ---------------------------------------------------------------------------
;; Empty presence still renders the marker (discriminable fragment)
;; ---------------------------------------------------------------------------

(deftest empty-presence-still-carries-the-marker
  (let [t (uit/render toast-list {:props {:toasts []}})
        p (presence-node t)]
    (is (some? p) "an empty presence boundary still emits its marker fragment")
    (is (= {:phase :present :timeout-ms 300} (:rf.ui/presence p)))))

;; ---------------------------------------------------------------------------
;; Analyzer + CLJS emission — recognition and the browser lowering target
;; (the analyzer/emitter are .cljc, so both are inspectable on this JVM host;
;; locks the codegen without a React mount)
;; ---------------------------------------------------------------------------

(defn- resolver [sym]
  (case sym
    presence {:fqn 're-frame.ui/presence :meta {}}
    nil))

(defn- mk-env []
  (env/make-env {:host :clj :ns-sym 'app.probe
                 :self 'self-view :self-id :app.probe/self-view
                 :resolver resolver}))

(deftest analyzer-lowers-presence-to-the-presence-op
  (let [ast (ana/analyze (mk-env)
                         '(presence {:timeout-ms 300}
                            (for [t ts] [:li {:key (:id t)} (:msg t)])))]
    (is (= :presence (:op ast)) "a valid presence form lowers to the :presence op")
    (is (= 300 (:timeout-ms ast)) "the compile-time :timeout-ms literal is carried")
    (is (= [:for] (mapv :op (:children ast)))
        "the keyed (for …) child is analyzed under the boundary")))

(deftest cljs-emission-wires-the-presence-boundary
  (let [ast  (ana/analyze (mk-env)
                          '(presence {:timeout-ms 300}
                             (for [t ts] [:li {:key (:id t)} (:msg t)])))
        text (pr-str (emit-cljs/emit-standalone ast))]
    (is (str/includes? text "re-frame.ui.presence-runtime/presence-boundary")
        "the CLJS emitter lowers ui/presence to the runtime retention boundary")
    (is (str/includes? text "(re-frame.ui.presence-runtime/presence-boundary 300")
        "the compile-time :timeout-ms literal is threaded to the boundary")))
