(ns re-frame.ui.parity-corpus-jvm-test
  "JVM half of the S1f parity corpus: every corpus case renders through
  the JVM emitter; the raw trees must hold the canonical-form
  invariants (jvm-tree contract §Child normalization / §Canonical
  uniqueness) and normalization N's output must hold the semantic-space
  invariants; the load-bearing conversion-table rows are pinned by
  targeted assertions (the cross-emitter equivalence itself runs in the
  node suite — react-dom/server needs a JS host)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.compiler.emit-jvm :as emit-jvm]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.root :as root]
            [re-frame.ui.parity-fixtures :as fx]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.tree :as tree]))

(defn- render-case [{:keys [view props]}]
  (tree/render (get fx/views view) props))

(defn- semantic-case [c]
  (semantic/normalize (render-case c)))

;; ---------------------------------------------------------------------------
;; Tree walkers
;; ---------------------------------------------------------------------------

(defn- tree-nodes
  "All map nodes of a raw tree, depth-first."
  [n]
  (when (map? n)
    (cons n (mapcat tree-nodes (:children n)))))

(defn- sem-nodes [sem-vec]
  (mapcat (fn [n]
            (when (map? n)
              (cons n (sem-nodes (or (:children n) [])))))
          sem-vec))

(defn- find-sem
  "First semantic element matching pred, depth-first."
  [sem-vec pred]
  (some #(when (pred %) %) (sem-nodes sem-vec)))

(defn- by-class [cls]
  (fn [n] (when-let [c (get-in n [:attrs "class"])]
            (contains? (set (str/split c #" ")) cls))))

(defn- by-tag [tag] (fn [n] (= tag (:tag n))))

;; ---------------------------------------------------------------------------
;; Canonical form (raw trees)
;; ---------------------------------------------------------------------------

(deftest every-case-renders-a-versioned-canonical-tree
  (doseq [{:keys [id] :as c} fx/cases]
    (testing (str id)
      (let [root (render-case c)]
        (is (= 1 (:rf.ui/tree-version root))
            "root carries the schema version")
        (is (contains? root :view-id) "root is the view's boundary node")
        (doseq [n (tree-nodes root)]
          ;; discrimination: at most one primary field
          (is (<= (count (filter #(contains? n %) [:tag :view-id :html])) 1)
              (str "one discriminating field only: " (pr-str n)))
          ;; absent-when-empty + no nil attr entries + no :ns :html
          (when (contains? n :attrs)
            (is (seq (:attrs n)))
            (is (every? some? (vals (:attrs n)))))
          (when (contains? n :events) (is (seq (:events n))))
          (is (not= :html (:ns n)) ":ns is absent for HTML")
          ;; text canonicalization: coalesced, no empties
          (let [chs (:children n)]
            (is (not-any? #(and (string? %) (= "" %)) chs))
            (is (not-any? (fn [[a b]] (and (string? a) (string? b)))
                          (partition 2 1 (or chs []))))))))))

;; ---------------------------------------------------------------------------
;; Normalization N invariants
;; ---------------------------------------------------------------------------

(deftest normalization-produces-pure-semantic-space
  (doseq [{:keys [id] :as c} fx/cases]
    (testing (str id)
      (let [sem (semantic-case c)]
        (doseq [n (sem-nodes sem)]
          (is (or (contains? n :tag) (contains? n :html))
              "boundaries and fragments are spliced away")
          (is (not (contains? n :events)) ":events dropped by N")
          (is (not (contains? n :key)) ":key values dropped by N")
          (is (not-any? #(= "rf.ui" (namespace %)) (keys n))
              "reserved diagnostics never reach the semantic space")
          (doseq [[an av] (:attrs n)]
            (is (string? an) "final attribute names are strings")
            (is (or (string? av) (map? av))
                "serialised values are strings (style: a map)")
            (when (map? av)
              (is (= "style" an)))))))))

;; ---------------------------------------------------------------------------
;; Targeted conversion-table rows ([S1-CONFIRM] pins, JVM side)
;; ---------------------------------------------------------------------------

(deftest boolean-value-classes
  (let [on  (semantic-case {:view :booleans :props {:on? true :dl "file.txt"}})
        off (semantic-case {:view :booleans :props {:on? false :dl false}})]
    (testing "boolean attrs: true -> presence-\"\", false -> absent"
      (is (= "" (get-in (find-sem on (by-tag :button)) [:attrs "disabled"])))
      (is (nil? (get-in (find-sem off (by-tag :button)) [:attrs "disabled"])))
      (is (= "" (get-in (find-sem on (by-tag :details)) [:attrs "open"]))))
    (testing "hidden is boolean-only on this React pin (corrected row 4)"
      (is (= "" (get-in (find-sem on (by-class "bools"))
                        [:children 3 :attrs "hidden"]))
          "hidden \"until-found\" renders bare presence"))
    (testing "booleanish strings: never omitted (names verbatim-camel —
              react-dom 19.2.0 serialises unaliased prop names verbatim;
              the CLJS corpus is the live confirmation)"
      (let [sp (find-sem on (fn [n] (contains? (:attrs n) "contentEditable")))]
        (is (= "true" (get-in sp [:attrs "contentEditable"])))
        (is (= "false" (get-in sp [:attrs "spellCheck"]))))
      (let [sp (find-sem off (fn [n] (contains? (:attrs n) "contentEditable")))]
        (is (= "false" (get-in sp [:attrs "contentEditable"])))))
    (testing "overloaded booleans: value/presence/absence"
      (is (= "file.txt" (get-in (find-sem on (by-tag :a)) [:attrs "download"])))
      (let [a2 (last (filter (by-tag :a) (sem-nodes on)))]
        (is (nil? (get-in a2 [:attrs "download"])) "false -> omitted"))
      (let [a2 (last (filter (by-tag :a) (sem-nodes off)))]
        (is (= "" (get-in a2 [:attrs "download"])) "true -> bare presence")))
    (testing "muted DOES serialise (corrected row 7 — no property-only member)"
      (is (= "" (get-in (find-sem on (by-tag :video)) [:attrs "muted"])))
      (is (nil? (get-in (find-sem off (by-tag :video)) [:attrs "muted"]))))
    (testing "aria-* always stringifies; data-* stringifies booleans"
      (let [sp (find-sem off (fn [n] (contains? (:attrs n) "aria-checked")))]
        (is (= "true" (get-in sp [:attrs "aria-hidden"])))
        (is (= "mixed" (get-in sp [:attrs "aria-checked"])))
        (is (= "false" (get-in sp [:attrs "data-on"])))
        (is (= "7" (get-in sp [:attrs "data-idx"])))))))

(deftest form-control-special-forms
  (let [sem (semantic-case {:view :form-controls
                            :props {:txt "typed & <kept>" :on? true :pick "b"}})
        inputs (filter (by-tag :input) (sem-nodes sem))]
    (testing "input value/checked serialise as attributes"
      (is (= "typed & <kept>" (get-in (nth inputs 0) [:attrs "value"])))
      (is (= "" (get-in (nth inputs 1) [:attrs "checked"]))))
    (testing "default-value / default-checked -> value / checked (row 8)"
      (is (= "dv" (get-in (nth inputs 2) [:attrs "value"])))
      (is (= "" (get-in (nth inputs 3) [:attrs "checked"]))))
    (testing "textarea value -> the text child, not an attribute"
      (let [ta (find-sem sem (by-tag :textarea))]
        (is (nil? (get-in ta [:attrs "value"])))
        (is (= ["typed & <kept>"] (:children ta)))))
    (testing "select value -> selected on the matching option"
      (let [opts (filter (by-tag :option) (sem-nodes sem))]
        (is (= [nil "" nil] (mapv #(get-in % [:attrs "selected"]) opts)))
        (is (nil? (get-in (find-sem sem (by-tag :select)) [:attrs "value"])))))
    (testing "the select text-content fallback matches option labels"
      (let [sem2 (semantic-case {:view :form-controls
                                 :props {:txt "x" :on? false :pick "gamma"}})
            opts (filter (by-tag :option) (sem-nodes sem2))]
        (is (= [nil nil ""] (mapv #(get-in % [:attrs "selected"]) opts)))))
    (testing ":for -> for (row 14)"
      (is (= "field-1" (get-in (find-sem sem (by-tag :label)) [:attrs "for"]))))))

(deftest svg-and-mathml-namespace-rows
  (let [sem (semantic-case {:view :svg-shapes :props {:w 24}})
        svg (find-sem sem (by-tag :svg))]
    (testing "alias rows: viewBox camel, SVG hyphens, xlink:/xml: colons"
      (is (= "0 0 24 24" (get-in svg [:attrs "viewBox"])))
      (is (= "2" (get-in (find-sem sem (by-tag :path)) [:attrs "stroke-width"])))
      (is (= "0.5" (get-in (find-sem sem (by-tag :path)) [:attrs "fill-opacity"])))
      (is (= "#i" (get-in (find-sem sem (by-tag :use)) [:attrs "xlink:href"])))
      (is (= "en" (get-in (find-sem sem (by-tag :use)) [:attrs "xml:lang"])))
      (is (= "middle" (get-in (find-sem sem (by-tag :text)) [:attrs "text-anchor"]))))
    (testing "namespace context: svg inherits; foreignObject children revert"
      (is (= :svg (:ns svg)))
      (is (= :svg (:ns (find-sem sem (by-tag :clipPath)))))
      (is (= :svg (:ns (find-sem sem (by-tag :foreignObject)))))
      (let [fo-div (find-sem sem (by-class "fo"))]
        (is (nil? (:ns fo-div)) "HTML island inside foreignObject")
        (is (= "2" (get-in fo-div [:attrs "tabindex"])))))
    (let [msem (semantic-case {:view :math-shapes :props {}})]
      (testing "math enters mathml; annotation-xml text/html island reverts"
        (is (= :mathml (:ns (find-sem msem (by-tag :math)))))
        (is (= :mathml (:ns (find-sem msem (by-tag :mi)))))
        (is (= :mathml (:ns (find-sem msem (by-tag :annotation-xml)))))
        (is (nil? (:ns (find-sem msem (by-class "m-island")))))))))

(deftest custom-element-property-classification
  (let [sem (semantic-case {:view :custom-elements
                            :props {:accent "#00f" :flag true}})
        w   (find-sem sem (by-tag :parity-widget))]
    (testing "property-classified props never reach the semantic space"
      (is (nil? (get-in w [:attrs "accentColor"])))
      (is (nil? (get-in w [:attrs "accent-color"]))))
    (testing "undeclared names stay attributes; booleans follow DOM rules"
      (is (= "d" (get-in w [:attrs "data-x"])))
      (is (= "" (get-in w [:attrs "disabled"])))
      (is (= "wide & <deep>" (get-in w [:attrs "label"]))))
    (testing "undeclared elements default to all-attributes"
      (let [pe (find-sem sem (by-tag :plain-element))]
        (is (= "bar" (get-in pe [:attrs "foo"])))
        (is (= "3" (get-in pe [:attrs "count"])))))))

(deftest events-are-data-in-the-tree-and-absent-from-n
  (let [on   (tree-nodes (render-case {:view :handlers
                                       :props {:id 42 :on? true :ks ["k1"]}}))
        off  (tree-nodes (render-case {:view :handlers
                                       :props {:id 7 :on? false :ks []}}))]
    (testing "literal vectors + placeholders retained as data"
      (is (some #(= [:cart/add 42] (get-in % [:events :on-click])) on))
      (is (some #(= [:form/typed :email :rf.ui/value]
                    (get-in % [:events :on-input])) on)))
    (testing "options maps retained verbatim"
      (is (some #(= {:event [:cart/remove 42] :prevent-default true
                     :capture true}
                    (get-in % [:events :on-click])) on)))
    (testing "dynamic handler classification: vector kept, nil dropped"
      (is (some #(= [:mode/a] (get-in % [:events :on-click])) on))
      (is (some #(= [:mode/b] (get-in % [:events :on-click])) off))
      (is (some #(= [:mode/only] (get-in % [:events :on-click])) on))
      (is (some #(and (= :button (:tag %)) (not (contains? % :events))) off)
          "a nil-classified dynamic handler drops the entry"))))

(deftest defaults-and-splicing
  (testing ":or defaults — absent takes default, present-nil stays nil"
    (let [absent (semantic-case {:view :with-defaults :props {}})
          pnil   (semantic-case {:view :with-defaults :props {:a "x" :b nil}})]
      (is (= ["A"] (:children (find-sem absent (by-class "a")))))
      (is (= ["B"] (:children (find-sem absent (by-class "b")))))
      (is (= ["x"] (:children (find-sem pnil (by-class "a")))))
      (is (nil? (:children (find-sem pnil (by-class "b")))))))
  (testing "fragment- and nil-rooted views splice; text re-coalesces"
    (let [off (semantic-case {:view :fragments :props {:pairs [] :show? false}})]
      (is (nil? (find-sem off (by-class "maybe"))))
      (is (some? (find-sem off (by-class "after")))))))

(deftest trusted-html-is-an-opaque-leaf
  (let [sem (semantic-case {:view :trusted
                            :props {:markup "<b>bold & raw</b><i>i</i>"}})]
    (is (= [{:html "<b>bold & raw</b><i>i</i>"}]
           (:children (find-sem sem (by-class "content")))))))

;; ---------------------------------------------------------------------------
;; frame-root top region (S1c fold — rf2-vxgfnd.3/#5701 left the JVM
;; emitter's side to this sweep): frame-root is TRANSPARENT at S1 —
;; the JVM structural/semantic output of a plan-bearing root form must
;; equal the same form without the wrapper, while the plan itself is
;; extracted at compile time. The CLJS-emission half is pinned by
;; root_analysis_cljs_test.
;; ---------------------------------------------------------------------------

(defn- analyze-mount-form [form]
  (root/analyze-root
   (env/make-env {:host :clj :ns-sym 're-frame.ui.parity-corpus-jvm-test})
   'ui/mount form))

(defn- jvm-root-semantic [form]
  (let [{:keys [ast]} (analyze-mount-form form)
        tree ((eval `(fn [] ~(emit-jvm/emit-node ast))))]
    ;; the raw emitted root node bypasses `tree/render`, which is what
    ;; stamps `:rf.ui/tree-version` in the real path — stamp it here so
    ;; the semantic-version gate sees a well-formed v1 root (rf2-vxgfnd.20).
    (semantic/normalize (assoc tree :rf.ui/tree-version tree/tree-version))))

(deftest frame-root-is-transparent-in-the-jvm-tree
  (let [wrapped '[re-frame.ui/frame-root {:id :chat/frame}
                  [re-frame.ui.parity-fixtures/with-defaults {:a "x"}]]
        nested  '[:div.host
                  [re-frame.ui/frame-root {:id :chat/frame :history 10}
                   [re-frame.ui.parity-fixtures/with-defaults {:a "x"}]
                   [:p "after"]]]
        bare    '[re-frame.ui.parity-fixtures/with-defaults {:a "x"}]]
    (testing "wrapper leaves no trace in semantic space"
      (is (= (jvm-root-semantic bare) (jvm-root-semantic wrapped))))
    (testing "nested top-region wrapper: children + order preserved"
      (is (= (jvm-root-semantic
              '[:div.host
                [re-frame.ui.parity-fixtures/with-defaults {:a "x"}]
                [:p "after"]])
             (jvm-root-semantic nested))))
    (testing "the plan is still extracted (transparency is render-side only)"
      (is (= [:chat/frame] (mapv :frame-id (:plans (analyze-mount-form wrapped)))))
      (is (= {:history 10}
             (:config (first (:plans (analyze-mount-form nested)))))))))

;; ---------------------------------------------------------------------------
;; ui/spread-safe — JVM half of the dual-host spread-safe corrections
;; (rf2-m5h0f authored eval order; rf2-j4len nil-owned absence + host parity).
;; The CLJS half lives in react-render-cljs-test; both hosts must AGREE.
;; ---------------------------------------------------------------------------

(defonce ^:private eval-order (atom []))
(defn- omark! [v] (swap! eval-order conj [:owned v]) v)
(defn- cmark! [v] (swap! eval-order conj [:caller v]) v)
(defn- oboom! [] (swap! eval-order conj [:owned :boom]) (throw (ex-info "owned boom" {})))
(defn- cboom! [] (swap! eval-order conj [:caller :boom]) (throw (ex-info "caller boom" {})))

;; owned + caller both literal-at-site so their value expressions ride the
;; compiled spread-safe path.
(defview order-probe []
  [:input.op (ui/spread-safe {:title (omark! "t")} {:data-x (cmark! "c")})])
(defview order-owned-throws []
  [:input.op (ui/spread-safe {:title (omark! "t") :lang (oboom!)}
                             {:data-x (cmark! "c")})])
(defview order-caller-throws []
  [:input.op (ui/spread-safe {:title (omark! "t")} {:data-x (cboom!)})])
(defview order-denial [{:keys [attr]}]
  [:input.op (ui/spread-safe {:title (omark! "t") :value "owned"
                              :on-change [:set :rf.ui/value]}
                             attr)])

(deftest safe-spread-authored-owned-then-caller-eval-order
  ;; rf2-m5h0f — the JVM already forces the authored owned-then-caller order via
  ;; its opts-map literal; pin it (and single-eval) so it stays that way and
  ;; agrees with the CLJS host.
  (testing "success: owned before caller, once each"
    (reset! eval-order [])
    (is (map? (tree/render order-probe {})))
    (is (= [[:owned "t"] [:caller "c"]] @eval-order)
        "owned evaluates before caller, each exactly once"))
  (testing "owned throws: the caller is never reached"
    (reset! eval-order [])
    (try (tree/render order-owned-throws {}) (catch Exception _))
    (is (= [[:owned "t"] [:owned :boom]] @eval-order)
        "owned ran (and threw) before any caller expression"))
  (testing "caller throws: owned already ran"
    (reset! eval-order [])
    (try (tree/render order-caller-throws {}) (catch Exception _))
    (is (= [[:owned "t"] [:caller :boom]] @eval-order)
        "owned evaluated first, then the caller expression threw"))
  (testing "caller denial throws: owned already ran"
    (reset! eval-order [])
    (let [data (try (tree/render order-denial {:attr {:ref "r"}})
                    nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= :rf.error/ui-tree-malformed (:rf.error/id data)) "the deny fired")
      (is (= [[:owned "t"]] @eval-order)
          "owned evaluated before the every-build caller deny threw"))))
