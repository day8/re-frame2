(ns re-frame.ui.root-analysis-cljs-test
  "S1c root identity + mount-surface analysis conformance (rf2-vxgfnd.3;
  the root-identity-and-mount contract §§1-7 compile half): root-id
  authoring/derivation/slug, the identifier-prefix default, the static
  top-region walk (mounted view + frame plans), Root Descriptor v1
  assembly, config fingerprints, the frame-root grammar accept/reject
  table, root opts validation, and the inline (def-free) emission both
  emitters give a root template. Pure — resolution is injected (the S1b
  pattern), so the suite runs identically under `clojure -M:test` and
  `npm run test:ui`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.emit-cljs :as emit-cljs]
            [re-frame.ui.compiler.emit-jvm :as emit-jvm]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.root :as root]
            [re-frame.ui.fingerprint :as fingerprint]))

(def resolver
  "Injected Q5 resolution stub."
  (fn [sym]
    (case sym
      frame-root  {:fqn 're-frame.ui/frame-root :meta {}}
      sub         {:fqn 're-frame.ui/sub :meta {}}
      app-view    {:fqn 'app.views/app-view
                   :meta {:rf.ui/view true :rf.ui/view-id :app.views/app-view}}
      panel-view  {:fqn 'app.views/panel-view
                   :meta {:rf.ui/view true :rf.ui/view-id :app.views/panel-view}}
      ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
      nil)))

(defn mk-env []
  (env/make-env {:host :clj :ns-sym 'app.test :resolver resolver}))

(defn analyze-root* [form]
  (root/analyze-root (mk-env) 'ui/mount form))

(defn compile-error-id [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.ui.compile/error (ex-data e)))))

(defn thrown-error [f]
  (try (f) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         {:id (:rf.error/id (ex-data e))
          :msg (ex-message e)
          :data (ex-data e)})))

(deftest reactive-sites-are-rejected-at-a-root-expression
  (is (= :rf.ui.compile/sub-in-loop
         (compile-error-id
          #(analyze-root* '[:div {:title (sub [:root/query])}])))
      "a mount/root has no ViewCell and therefore cannot own a sub site"))

;; ---------------------------------------------------------------------------
;; Root-id: authored shapes + derivation + slug + prefix (contract §1, §3)
;; ---------------------------------------------------------------------------

(deftest authored-root-id-wins
  (let [{:keys [views]} (analyze-root* '[app-view {}])]
    (is (= {:root-id :page/shop :provenance :authored}
           (root/resolve-root-identity 'ui/mount {:root-id :page/shop} views))
        "an authored :root-id is the root-id, verbatim")
    (is (= {:root-id [:shop/panel :left] :provenance :authored}
           (root/resolve-root-identity
            'ui/mount {:root-id [:shop/panel :left]} views)))))

(deftest authored-root-id-shapes-rejected
  (doseq [bad [:unqualified "str" 42 [:unqualified :left] [:page/shop]
               [:page/shop {:m 1}] [] {:a 1}]]
    (is (= :rf.ui.compile/bad-root-id
           (compile-error-id
            #(root/validate-authored-root-id! 'ui/mount bad)))
        (str "rejected shape: " (pr-str bad)))))

(deftest derivation-default
  (testing "no :root-id -> the mounted view's registered id"
    (let [{:keys [views]} (analyze-root* '[app-view {}])]
      (is (= {:root-id :app.views/app-view :provenance :derived}
             (root/resolve-root-identity 'ui/mount {} views)))))
  (testing ":disambiguator -> [view-id d]"
    (let [{:keys [views]} (analyze-root* '[panel-view {}])]
      (is (= {:root-id [:app.views/panel-view :left] :provenance :derived}
             (root/resolve-root-identity
              'ui/mount {:disambiguator :left} views)))))
  (testing "non-scalar disambiguator rejected"
    (let [{:keys [views]} (analyze-root* '[panel-view {}])]
      (is (= :rf.ui.compile/bad-disambiguator
             (compile-error-id
              #(root/resolve-root-identity
                'ui/mount {:disambiguator {:m 1}} views)))))))

(deftest derivation-impossible-is-the-pinned-compile-error
  (testing "zero internal views (bare DOM root)"
    (let [{:keys [views]} (analyze-root* '[:div "static"])]
      (let [ex (try (root/resolve-root-identity 'ui/mount {} views) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e e))]
        (is (= :rf.ui.compile/no-single-mounted-view
               (:rf.ui.compile/error (ex-data ex))))
        (is (re-find #"no single mounted view — author :root-id"
                     (ex-message ex))
            "the contract-pinned didactic message"))))
  (testing "two views (fragment of two views)"
    (let [{:keys [views]} (analyze-root* '[:<> [app-view {}] [panel-view {}]])]
      (is (= :rf.ui.compile/no-single-mounted-view
             (compile-error-id
              #(root/resolve-root-identity 'ui/mount {} views))))))
  (testing "foreign-component root derives nothing"
    (let [{:keys [views]} (analyze-root* '[ForeignComp {}])]
      (is (= :rf.ui.compile/no-single-mounted-view
             (compile-error-id
              #(root/resolve-root-identity 'ui/mount {} views)))))))

(deftest root-id-slug-cases
  (is (= "page_Sshop" (root/root-id-slug :page/shop))
      "keyword -> enc(ns) _S enc(name)")
  (is (= "_V_Kshop_Sapp_Kleft" (root/root-id-slug [:shop/app :left]))
      "vector -> _V + type-tagged escaped elements")
  (is (= "_V_Kshop_Sapp_I2" (root/root-id-slug [:shop/app 2]))
      "integer element tagged _I")
  (is (= "app" (root/root-id-slug :app)) "namespace absent -> enc(name)")
  (is (= "a_2e_b_Sc_2a_d" (root/root-id-slug :a.b/c*d))
      "outside [A-Za-z0-9-] is reversibly escaped _<hex>_, never normalised")
  (is (= "_V_Kshop_Sapp_Ta_20_b" (root/root-id-slug [:shop/app "a b"]))
      "string element tagged _T; space escaped _20_"))

;; --- INJECTIVITY (rf2-vxgfnd.17): distinct valid root-ids MUST NOT share
;;     a slug, hence never a default identifier-prefix / synthesized
;;     locator. The old lossy transform aliased each pair below.
(deftest root-id-slug-injective-on-known-collisions
  (doseq [[a b old] [[:a/b-c          :a-b/c            "a-b-c"]      ; ns/name join vs `-` in name
                     [:a/b.c          :a/b-c            "a-b-c"]      ; `.`-normalisation vs literal `-`
                     [:a/b            :a-b              "a-b"]        ; qualified vs unqualified
                     [[:x/y "a--b"]   [:x/y "a" "b"]    "x-y--a--b"]  ; string `--` vs element join
                     [[:x/y "a-b"]    [:x/y "a" "b"]    "x-y--a-b"]   ; one component vs two
                     [[:x/y :foo]     [:x/y "foo"]      "x-y--foo"]   ; keyword vs string disambiguator
                     [[:x/y 5]        [:x/y "5"]        "x-y--5"]]]   ; integer vs string disambiguator
    (is (not= (root/root-id-slug a) (root/root-id-slug b))
        (str "distinct root-ids must not share a slug: " (pr-str a) " vs "
             (pr-str b) " (both -> " (pr-str old) " under the old transform); "
             "now " (pr-str (root/root-id-slug a)) " vs "
             (pr-str (root/root-id-slug b))))))

(deftest root-id-slug-injective-over-enumerated-grammar
  ;; Enumerate valid root-ids over an alphabet that hits every boundary the
  ;; encoding must protect — `-`, `--`, `.`, `_`, whitespace, the empty
  ;; string, the marker letters themselves (S/V/K/T/I), and all three
  ;; disambiguator types — then assert the slug map is INJECTIVE and every
  ;; slug (and default identifier-prefix) stays within the DOM-safe alphabet.
  (let [strs  ["" "a" "-" "a-b" "a--b" "a.b" "a_b" "a b" "S" "V" "5"]
        nms   ["a" "b" "a-b" "a.b" "a_b" "S"]           ; keyword names (non-empty)
        nss   ["a" "a-b" "a.b" "x"]                     ; namespaces
        kws   (distinct (concat (map keyword nms)                 ; unqualified
                                (for [s nss n nms] (keyword s n)))) ; qualified
        vecs  (distinct (concat
                         (for [k (take 4 kws) s strs]       [k s])
                         (for [k (take 4 kws) n nms]        [k (keyword n)])
                         (for [k (take 4 kws) i [0 5 -5]]   [k i])
                         (for [k (take 2 kws) a strs b strs] [k a b])))
        roots (vec (distinct (concat kws vecs)))
        by-slug (group-by root/root-id-slug roots)
        collisions (into {} (filter (fn [[_ v]] (< 1 (count v))) by-slug))
        dom-safe? #(re-matches #"[A-Za-z0-9_-]+" %)]
    (is (< 300 (count roots)) "the enumeration is a meaningful sample")
    (is (empty? collisions)
        (str "root-id-slug is non-injective — these distinct root-ids share "
             "a slug: " (pr-str collisions)))
    (is (every? #(dom-safe? (root/root-id-slug %)) roots)
        "every slug stays within the DOM-safe identifierPrefix alphabet")
    (is (every? #(dom-safe? (root/default-identifier-prefix %)) roots)
        "every default identifier-prefix stays DOM-safe")))

;; ---------------------------------------------------------------------------
;; Top-region walk: frame plans (contract §6)
;; ---------------------------------------------------------------------------

(deftest frame-plans-extract-through-top-region-wrappers
  (let [{:keys [plans views]}
        (analyze-root*
         '[:div.shell
           [frame-root {:id :shop :initial-events [[:shop/boot]]}
            [:<>
             [frame-root {:id :session}
              [app-view {}]]]]])]
    (is (= [:shop :session] (mapv :frame-id plans))
        "plans collect through nested top-region wrappers, document order")
    (is (every? #(re-find #"^cf1-[0-9a-f]{16}$" (:config-fingerprint %)) plans)
        "config fingerprints are cf1- digests")
    (is (= 1 (count views)))
    (is (= :app.views/app-view (:view-id (first views)))
        "the mounted view is found through the wrappers")))

(deftest config-fingerprint-semantics
  (let [fp #(fingerprint/config-fingerprint %1 %2)]
    (is (= (fp :shop {:initial-events [[:a]]})
           (fp :shop {:initial-events [[:a]]}))
        "equal id + config source -> equal fingerprint")
    (is (not= (fp :shop {:initial-events [[:a]]})
              (fp :shop {:initial-events [[:b]]}))
        "config source differences fingerprint apart")
    (is (not= (fp :shop nil) (fp :session nil))
        "the frame-id participates")
    (is (= (fp :shop nil) (fp :shop {}))
        "no config normalises to the empty map")))

(deftest duplicate-plans-dedupe-and-conflict
  (testing "identical [frame-id fingerprint] pairs dedupe (idempotent no-op)"
    (let [{:keys [plans]}
          (analyze-root*
           '[:<>
             [frame-root {:id :shop} [:div "a"]]
             [frame-root {:id :shop} [app-view {}]]])]
      (is (= 1 (count plans)))))
  (testing "one root form, one frame-id, differing fingerprints = fail loud"
    (let [{:keys [id msg]}
          (thrown-error
           #(analyze-root*
             '[:<>
               [frame-root {:id :shop :initial-events [[:a]]} [:div "a"]]
               [frame-root {:id :shop :initial-events [[:b]]} [app-view {}]]]))]
      (is (= :rf.error/frame-payload-conflict id))
      (is (re-find #"\[:rf\.error/frame-payload-conflict\]" msg)
          "canonical builder: message carries the greppability token"))))

;; ---------------------------------------------------------------------------
;; frame-root grammar rejections
;; ---------------------------------------------------------------------------

(deftest frame-root-outside-the-top-region-rejected
  (testing "under a control form"
    (is (= :rf.ui.compile/frame-root-misplaced
           (compile-error-id
            #(analyze-root* '[:div (when true [frame-root {:id :shop}])])))))
  (testing "below an internal-view boundary"
    (is (= :rf.ui.compile/frame-root-misplaced
           (compile-error-id
            #(analyze-root* '[app-view {} [frame-root {:id :shop}]])))))
  (testing "in a defview template (no :top-region? in the env)"
    (is (= :rf.ui.compile/frame-root-misplaced
           (compile-error-id
            #(ana/analyze (mk-env) '[frame-root {:id :shop} [:div "x"]]))))))

(deftest frame-root-shape-rejections
  (testing ":id must be a compile-time literal keyword"
    (is (= :rf.ui.compile/bad-frame-root
           (compile-error-id
            #(analyze-root* '[frame-root {:id some-id} [app-view {}]]))))
    (is (= :rf.ui.compile/bad-frame-root
           (compile-error-id
            #(analyze-root* '[frame-root {} [app-view {}]])))))
  (testing "a literal props map is required"
    (is (= :rf.ui.compile/bad-frame-root
           (compile-error-id
            #(analyze-root* '[frame-root opts [app-view {}]])))))
  (testing ":frame is frame-provider's key — roots ensure by :id"
    (is (= :rf.ui.compile/bad-frame-root
           (compile-error-id
            #(analyze-root* '[frame-root {:id :x :frame :y} [app-view {}]]))))))

(deftest frame-root-op-in-the-closed-set
  (is (contains? ana/node-ops :frame-root)))

;; ---------------------------------------------------------------------------
;; Root form literality
;; ---------------------------------------------------------------------------

(deftest runtime-root-forms-rejected
  (doseq [bad ['some-vector '(build-tree) "str" 42]]
    (is (= :rf.ui.compile/runtime-root-form
           (compile-error-id #(analyze-root* bad)))
        (str "rejected root form: " (pr-str bad)))))

;; ---------------------------------------------------------------------------
;; Props extraction + Root Descriptor v1 (contract §2, §5)
;; ---------------------------------------------------------------------------

(defn descriptor* [form opts]
  (let [{:keys [ast views plans]} (analyze-root* form)
        {:keys [root-id provenance]}
        (root/resolve-root-identity 'ui/mount opts views)]
    (root/root-descriptor {:root-id root-id :provenance provenance
                           :views views :plans plans :ast ast})))

(deftest descriptor-literal-props
  (let [d (descriptor* '[app-view {:promo :spring :sizes [1 2] :opts {:a 1}}]
                       {:root-id :page/shop})]
    (is (= 1 (:rf.root/schema-version d)))
    (is (= :page/shop (:root-id d)))
    (is (= :authored (:root-id-provenance d)))
    (is (= :app.views/app-view (:view-id d)))
    (is (= :literal (:props-shape d))
        "literal EDN collections count as literal props (contract §5)")
    (is (= {:promo :spring :sizes [1 2] :opts {:a 1}} (:static-props d))
        ":static-props records the literal map verbatim")
    (is (re-find #"^tf1-[0-9a-f]{16}$" (:template-fingerprint d)))
    (is (not (contains? d :build-digest))
        "root-descriptor is per-root static facts only; it carries no
         whole-build aggregate")))

(deftest descriptor-dynamic-props
  (let [d (descriptor* '[app-view {:promo (current-promo)}] {})]
    (is (= :dynamic (:props-shape d)))
    (is (not (contains? d :static-props))
        "no static props recorded for dynamic shapes (no guessing)")
    (is (= :derived (:root-id-provenance d)))))

(deftest descriptor-no-single-view-under-authored-id
  (let [d (descriptor* '[:div "bare"] {:root-id :page/static})]
    (is (= :page/static (:root-id d)))
    (is (not (contains? d :view-id))
        "an authored-id root may mount zero internal views")
    (is (not (contains? d :props-shape)))))

(deftest descriptor-frame-plans-are-the-static-subset
  (let [d (descriptor* '[frame-root {:id :shop :initial-events [[:boot]]}
                         [app-view {}]]
                       {})]
    (is (= 1 (count (:frame-plans d))))
    (is (= #{:frame-id :config-fingerprint}
           (set (keys (first (:frame-plans d)))))
        "descriptor plans carry identity only — config forms never ride it")))

;; ---------------------------------------------------------------------------
;; Root opts validation (contract §3)
;; ---------------------------------------------------------------------------

(deftest root-opts-validation
  (testing "closed key set"
    (is (= :rf.ui.compile/bad-root-opts
           (compile-error-id
            #(root/parse-root-opts! 'ui/mount {:root-idd :page/shop}
                                    root/mount-opt-keys)))))
  (testing "opts must be a literal map"
    (is (= :rf.ui.compile/bad-root-opts
           (compile-error-id
            #(root/parse-root-opts! 'ui/mount 'opts root/mount-opt-keys)))))
  (testing ":root-id + :disambiguator together is a contradiction"
    (is (= :rf.ui.compile/bad-root-opts
           (compile-error-id
            #(root/parse-root-opts! 'ui/mount
                                    {:root-id :page/shop :disambiguator :left}
                                    root/mount-opt-keys)))))
  (testing ":identifier-prefix must be a literal string"
    (is (= :rf.ui.compile/bad-root-opts
           (compile-error-id
            #(root/parse-root-opts! 'ui/mount {:identifier-prefix :kw}
                                    root/mount-opt-keys)))))
  (testing "host callbacks pass through as opaque expressions"
    (is (= {:on-uncaught-error '(fn [e] (report! e))}
           (root/parse-root-opts! 'ui/mount
                                  {:on-uncaught-error '(fn [e] (report! e))}
                                  root/mount-opt-keys)))))

;; ---------------------------------------------------------------------------
;; Emission: inline (def-free) CLJS + transparent trees on both hosts
;; ---------------------------------------------------------------------------

(defn- forms-of [form]
  (tree-seq coll? seq form))

(deftest emit-inline-hoists-into-let-not-defs
  (let [{:keys [ast]} (analyze-root*
                       '[:div.shell
                         [:p "static one"]
                         [app-view {:on-save [:app/save]}]])
        emitted (emit-cljs/emit-inline ast 'rf-ui-root)]
    (is (not-any? #(and (seq? %) (= 'def (first %))) (forms-of emitted))
        "no module-level defs — mount sites may sit inside function bodies")
    ;; syntax-quote resolves `let` per reading host
    (is (contains? #{'clojure.core/let 'cljs.core/let} (first emitted))
        "hoisted constants become let bindings around the body")))

(deftest emitters-scope-frame-root-to-its-frame
  ;; rf2-vxgfnd.25: a top-region frame-root SCOPES its ensured frame to its
  ;; children so an ambient (sub …) resolves it — no longer a bare transparent
  ;; Fragment. The wrappers add no DOM (a React context Provider on CLJS, a
  ;; dynamic binding on the JVM); the tree stays transparent, but the frame is
  ;; now in ambient scope.
  (let [{:keys [ast]} (analyze-root*
                       '[frame-root {:id :shop} [app-view {}]])]
    (testing "CLJS: children scoped via frames/scope-element with the frame id"
      (let [emitted (emit-cljs/emit-inline ast 'rf-ui-root)
            forms   (forms-of emitted)]
        (is (some #{'re-frame.ui.frames/scope-element} forms))
        (is (some #{:shop} forms)
            "the frame-root's literal :id is the scope target")
        (is (not-any? #{'re-frame.ui.runtime/Fragment} forms)
            "no bare transparent Fragment — the frame-root emits a scope")))
    (testing "JVM: children bound under frames/jvm-root-scope with the frame id"
      (let [emitted (emit-jvm/emit-node ast)]
        (is (= 're-frame.ui.frames/jvm-root-scope (first emitted)))
        (is (= :shop (second emitted))
            "the frame-root's literal :id is bound as the ambient frame")))))
