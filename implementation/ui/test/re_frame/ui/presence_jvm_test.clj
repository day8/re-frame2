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
            [re-frame.ui.compiler.emit-jvm :as emit-jvm]
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

(defview literal-toasts [{:keys [banner?]}]
  ;; rf2-vxgfnd.96.1 — the DOCUMENTED literal route: keyed literal host and
  ;; fragment children written inline, alongside the keyed (for …) that has
  ;; always shipped. This view exists to compile: before the fix the analyzer
  ;; read an :element's key at the WRONG AST path and this defview was a build
  ;; failure.
  (ui/presence {:timeout-ms 300}
    [:li {:key "static"} "static"]
    [:<> {:key "frag"} [:li "frag"]]
    (when banner? [:li {:key "banner"} "banner"])))

(defview reusable-outside-boundary []
  ;; presence-phase OUTSIDE any boundary must still resolve to :present, so the
  ;; child stays reusable anywhere.
  [:span {:data-phase (name (ui/presence-phase))} "reusable"])

;; ---------------------------------------------------------------------------
;; JVM :present — the fragment marker + every child present
;; ---------------------------------------------------------------------------

(deftest presence-renders-the-present-marker-fragment
  (let [t (uit/render [toast-list {:toasts [{:id 1 :msg "a"} {:id 2 :msg "b"}]}])
        p (presence-node t)]
    (is (some? p) "the JVM emits a `:rf.ui/presence` fragment node")
    (is (= {:phase :present :timeout-ms 300} (:rf.ui/presence p))
        "the marker carries :phase :present and the compile-time :timeout-ms")
    (is (not (contains? p :tag)) "the presence node is a FRAGMENT, not an element")
    (testing "every keyed child renders (no retention on the JVM)"
      (is (= #{"a" "b"} (set (all-strings t)))))))

(deftest presence-phase-is-present-on-the-jvm
  (let [t (uit/render [toast-list {:toasts [{:id 1 :msg "a"}]}])]
    (is (= "present" (:data-phase (uit/attrs (some #(when (= :li (:tag %)) %) (tree-seq map? :children t)))))
        "a presence-aware child reads :present on the JVM structural subset"))
  (testing "presence-phase OUTSIDE a boundary is also :present (reusable anywhere)"
    (let [t (uit/render [reusable-outside-boundary])]
      (is (= "present" (:data-phase (uit/attrs (some #(when (= :span (:tag %)) %) (tree-seq map? :children t)))))))))

;; ---------------------------------------------------------------------------
;; §004B — the marker is a droppable diagnostic (stripped by N)
;; ---------------------------------------------------------------------------

(deftest presence-marker-is-stripped-by-semantic-normalization
  (let [t (uit/render [toast-list {:toasts [{:id 1 :msg "a"}]}])
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
  (let [t (uit/render [toast-list {:toasts []}])
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

;; ---------------------------------------------------------------------------
;; The keyed-child grammar (rf2-vxgfnd.96.1)
;;
;; Presence admits FOUR keyed shapes: a keyed `(for …)`, a keyed literal host
;; element, a keyed fragment, and a keyed view/foreign row — plus branches whose
;; every rendered arm is one of those. The key's AST location differs by node,
;; and the grammar is only as correct as the path this predicate reads.
;; ---------------------------------------------------------------------------

(defn- presence-error [form]
  (try (ana/analyze (mk-env) form) nil
       (catch clojure.lang.ExceptionInfo ex
         (:rf.ui.compile/error (ex-data ex)))))

(deftest analyzed-key-locations-differ-by-node
  ;; The mutation pin: restore `[:key :present?]` for :element (or
  ;; `[:props :key :present?]` for :fragment) and the acceptances below go red.
  (testing "a literal host element carries its key inside its analyzed PROPS"
    (let [el (ana/analyze (mk-env) '[:div {:key "x"} "hi"])]
      (is (true? (get-in el [:props :key :present?])))
      (is (nil? (get-in el [:key :present?]))
          "an :element has NO node-level :key — reading one finds nothing and
           wrongly rejects every keyed literal child")))
  (testing "a fragment carries its key at the NODE"
    (let [fr (ana/analyze (mk-env) '[:<> {:key "x"} [:p "a"]])]
      (is (true? (get-in fr [:key :present?])))
      (is (nil? (get-in fr [:props :key :present?]))
          "a :fragment has no analyzed props map"))))

;; ---------------------------------------------------------------------------
;; Key PRESENCE means the key, not the props map (rf2-xoz1s)
;;
;; A fragment's props map may hold exactly one entry — `:key` — so `has a props
;; map` reads like `has a key` right up until the map is EMPTY. `[:<> {} …]`
;; used to report `:present? true` with `:expr nil`: a key claimed and not
;; supplied. Every consumer believed it, and the presence boundary — whose whole
;; job is retaining a child BY its identity — admitted a child with none.
;; ---------------------------------------------------------------------------

(deftest fragment-key-presence-tracks-the-key-not-the-props-map
  (testing "an EMPTY props map is not a key"
    (let [fr (ana/analyze (mk-env) '[:<> {} [:p "a"]])]
      (is (false? (get-in fr [:key :present?]))
          "[:<> {} …] carries a props map and no identity — saying otherwise
           promises downstream a key that was never written")
      (is (nil? (get-in fr [:key :expr]))
          "and there is no key expression to promise")))
  (testing "a supplied key is still present, and still carries its expression"
    (let [fr (ana/analyze (mk-env) '[:<> {:key "x"} [:p "a"]])]
      (is (true? (get-in fr [:key :present?])))
      (is (= "x" (get-in fr [:key :expr])))))
  (testing "an explicitly nil key IS written, so it stays present"
    ;; `:element` answers `contains?` the same way — the two node kinds agree
    ;; rather than the fragment inventing a nil-specific rule of its own.
    (is (true? (get-in (ana/analyze (mk-env) '[:<> {:key nil} [:p "a"]])
                       [:key :present?])))
    (is (true? (get-in (ana/analyze (mk-env) '[:div {:key nil} "hi"])
                       [:props :key :present?]))))
  (testing "no props map at all remains keyless"
    (is (false? (get-in (ana/analyze (mk-env) '[:<> [:p "a"]]) [:key :present?])))))

(deftest fragment-key-presence-reaches-its-consumers
  (testing "the JVM structural tree no longer gains a :key nil entry"
    ;; emit-jvm splices `:present?` straight into `(tree/fragment key? expr …)`,
    ;; and `tree/fragment` assocs `:key` on a truthy flag — so the old claim put
    ;; a `:key nil` on the structural node for a fragment with no key.
    (let [node (emit-jvm/emit-node (ana/analyze (mk-env) '[:<> {} [:p "a"]]))]
      (is (= 'false (nth node 1)) "the fragment is emitted as UNkeyed")
      (is (nil? (nth node 2)) "with no key expression")))
  (testing "a for row spelling an empty props map fails as UNKEYED"
    ;; analyze-for reads the same `:present?`. It used to pass the missing-key
    ;; check and trip on `:literal?` instead, reporting a CONSTANT key for a row
    ;; that had no key at all — the right rejection for the wrong reason.
    (is (= :rf.ui.compile/unkeyed-list-item
           (presence-error '(for [t ts] [:<> {} [:p "a"]]))))
    (is (= :rf.ui.compile/unkeyed-list-item
           (presence-error '(for [t ts] [:<> [:p "a"]]))))
    (is (nil? (presence-error '(for [t ts] [:<> {:key (:id t)} [:p "a"]])))
        "a genuinely keyed row is untouched")))

(deftest keyed-literal-children-are-accepted
  (testing "a keyed literal host child"
    (let [ast (ana/analyze (mk-env) '(presence {:timeout-ms 300} [:li {:key "x"} "hi"]))]
      (is (= :presence (:op ast)))
      (is (= [:element] (mapv :op (:children ast))))))
  (testing "a keyed fragment child"
    (is (= [:fragment]
           (mapv :op (:children (ana/analyze (mk-env)
                                             '(presence {:timeout-ms 300}
                                                [:<> {:key "x"} [:p "a"]])))))))
  (testing "ADVERSARIAL: keyed literals mixed with a (for …) sibling and a branch"
    (let [ast (ana/analyze (mk-env)
                           '(presence {:timeout-ms 300}
                              [:li {:key "static"} "static"]
                              (for [t ts] [:li {:key (:id t)} (:msg t)])
                              [:<> {:key "frag"} [:li "frag"]]
                              (when b [:li {:key "banner"} "banner"])))]
      (is (= [:element :for :fragment :if] (mapv :op (:children ast)))
          "every blessed keyed shape composes under one boundary"))))

(deftest unkeyed-children-remain-a-build-failure
  (testing "an unkeyed literal host child"
    (is (= :rf.ui.compile/presence-unkeyed-child
           (presence-error '(presence {:timeout-ms 300} [:li "hi"])))))
  (testing "an unkeyed fragment child"
    (is (= :rf.ui.compile/presence-unkeyed-child
           (presence-error '(presence {:timeout-ms 300} [:<> [:li "hi"]])))))
  (testing "a fragment whose props map holds NO key (rf2-xoz1s)"
    ;; the wrong-answer path this bead closes: accepted as keyed, leaving the
    ;; boundary with nothing to retain the child by.
    (is (= :rf.ui.compile/presence-unkeyed-child
           (presence-error '(presence {:timeout-ms 300} [:<> {} [:li "hi"]])))))
  (testing "ADVERSARIAL: one unkeyed sibling among keyed ones still fails"
    (is (= :rf.ui.compile/presence-unkeyed-child
           (presence-error '(presence {:timeout-ms 300}
                              [:li {:key "a"} "a"]
                              [:li "b"])))))
  (testing "ADVERSARIAL: an unkeyed branch ARM still fails"
    (is (= :rf.ui.compile/presence-unkeyed-child
           (presence-error '(presence {:timeout-ms 300}
                              (if b [:li {:key "a"} "a"] [:li "b"])))))))

(deftest keyed-literal-children-render-on-the-jvm
  ;; End to end: the documented literal route compiles AND renders.
  (let [t (uit/render [literal-toasts {:banner? true}])]
    (is (some? (presence-node t)) "the boundary is there")
    (is (= #{"static" "frag" "banner"} (set (all-strings t)))
        "every keyed literal child renders under the boundary")))

(deftest cljs-emission-wires-the-presence-boundary
  (let [ast  (ana/analyze (mk-env)
                          '(presence {:timeout-ms 300}
                             (for [t ts] [:li {:key (:id t)} (:msg t)])))
        text (pr-str (emit-cljs/emit-standalone ast))]
    (is (str/includes? text "re-frame.ui.presence-runtime/presence-boundary")
        "the CLJS emitter lowers ui/presence to the runtime retention boundary")
    (is (str/includes? text "(re-frame.ui.presence-runtime/presence-boundary 300")
        "the compile-time :timeout-ms literal is threaded to the boundary")))
