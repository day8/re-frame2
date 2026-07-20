(ns re-frame.ui.tool-cljs-test
  "rf2-vxgfnd.95.6 — the five frozen `re-frame.ui.tool` dev-only projections over
  the compiled-view evidence model.

  The rows:

    - VERSIONED PUBLIC SHAPE — every projection stamps `:rf.ui.tool/version`
      (`schema-version`) so a consumer degrades on an evolved shape;
    - `view-manifest` — the manifest projection (what CAN happen): per-prop
      docs/defaults/schema, render-slot + interop sites, capability bits, source,
      site counts; nil for an unregistered view;
    - `view-event-sites` — literal (`:vector`) vs normalized (`:options`) vs
      opaque `:dynamic` handler honesty; a raw callback is `:opaque`, never
      claimed inspectable;
    - `view-dependencies` — literal-vs-`:dynamic` sub query-shape honesty;
    - `mounted-views` — committed CONNECTED-INSTANCE records: occurrence identity
      distinguishes two instances of one view; connection state; the honest
      hide-vs-unmount lifecycle labels (INFERENCE, never fabricated runtime truth);
    - `explain-render` — the render causes + explicit loss accounting;
    - SERIALIZABLE, not handles — no ViewCell / React object egresses.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` / `test:ui` (node) AND
  `clojure -M:test` (JVM), so the projections are graft-checked on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            #?(:cljs [cljs.reader])
            [re-frame.registrar             :as registrar]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.reactive           :as reactive]
            [re-frame.ui.tool               :as tool]
            [re-frame.ui.tool.evidence      :as evidence]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

;; ---------------------------------------------------------------------------
;; A hand-built manifest mirroring the compiler's emitted shape (compiler.cljc
;; `manifest` + analyze.cljc `add-site!`). The projection tier is a pure function
;; of this contract; the compiler's emission of it is proven by the compiler
;; suites, and the JVM `defview` companion (tool_defview_jvm_test) proves a REAL
;; emitted manifest projects the same.
;; ---------------------------------------------------------------------------

(defn- register-view! [view-id manifest]
  (registrar/register! :view view-id {:rf/id view-id
                                       :rf.ui/compiled? true
                                       :rf.ui/manifest manifest}))

(def ^:private demo-manifest
  {:view-id      :demo/widget
   :display-name "demo/widget"
   :doc          "A demo widget."
   :source       {:file "demo.cljc" :line 10 :column 1}
   :prop-slots   [{:key :label :slot "label" :index 0}
                  {:key :count :slot "count" :index 1}
                  {:key :children :slot "children" :index 2}]
   :props-schema [:map
                  [:label {:doc "The button label."} :string]
                  [:count {:default 0 :optional true} :int]]
   :open-props?  false
   :children?    true
   :as?          false
   :template-fingerprint "tf1-abc"
   :hook-signature       "hs1-def"
   :capabilities #{:cell :local}
   :sites
   {:subs   [{:sid "sid1-a" :query [:demo/total] :path [] :expr-path [0]}
             {:sid "sid1-b" :query '[:demo/item id] :path [1] :expr-path [1]}]
    :events [{:sid "sid1-e" :site-index 0 :view-id :demo/widget
              :source-coord {:file "demo.cljc" :line 11} :path [:button 0]
              :prop :on-click :handler '[:demo/inc n]
              :classification :vector :serializable? true}
             {:sid "sid1-f" :site-index 1 :view-id :demo/widget
              :source-coord {:file "demo.cljc" :line 12} :path [:input 0]
              :prop :on-change
              :handler {:event '[:demo/set v] :prevent-default true}
              :classification :options :serializable? true}
             {:sid "sid1-g" :site-index 2 :view-id :demo/widget
              :source-coord {:file "demo.cljc" :line 13} :path [:input 1]
              :prop :on-input :handler :opaque
              :classification :ui-event :serializable? false :sync? true}
             {:sid "sid1-h" :site-index 3 :view-id :demo/widget
              :source-coord {:file "demo.cljc" :line 14} :path [:a 0]
              :prop :on-mouse-over :handler :opaque
              :classification :fn :serializable? false}
             {:sid "sid1-i" :site-index 4 :view-id :demo/widget
              :source-coord {:file "demo.cljc" :line 15} :path [:a 1]
              :prop :on-key-down :handler :opaque
              :classification :dynamic :serializable? false}]
    :slots  [{:sid "sid1-s" :source-coord {:file "demo.cljc" :line 16} :inline? true}]
    :react  [{:sid "sid1-r" :kind :ref :order 0 :path [] :expr-path [0]}
             {:sid "sid1-t" :kind :effect :order 1 :path [] :expr-path [1]}]
    :effects      []
    :dispatch-fns []
    :frame-ops    []
    :locals       [{:sid "sid1-l"}]
    :htmls        []
    ;; S4-C compile-tier a11y findings. A SUPPRESSED finding is still a site —
    ;; it carries its reason so a tool can show what an author silenced and why.
    :diagnostics  [{:sid "sid1-d1" :id :rf.ui.compile/a11y-click-non-interactive
                    :tag :div :path [:div 0] :suppressed? false}
                   {:sid "sid1-d2" :id :rf.ui.compile/a11y-missing-accessible-name
                    :tag :button :path [:button 2] :suppressed? true
                    :reason "named by the adjacent legend"}]}})

;; ---------------------------------------------------------------------------
;; helpers for connected-instance evidence (driven through the reactive seam,
;; no DOM — the same technique the S2 tool-evidence suite uses)
;; ---------------------------------------------------------------------------

(defn- connect! [cell]
  (let [[_ capture] (reactive/with-capture cell (fn [] nil))]
    (reactive/commit! cell capture))
  cell)

(defn- enrol! [cell epoch]
  (reactive/mark-dirty! cell epoch)
  (reactive/flush-pending!)
  cell)

(defn- view-of [view-id]
  (filter #(= view-id (:view-id %)) (:views (tool/mounted-views))))

(defn- no-cell-anywhere?
  "Deep-walk `x`: true when NO ViewCell is reachable (the handle-egress proof)."
  [x]
  (cond
    (reactive/cell? x) false
    (map? x)  (every? no-cell-anywhere? (concat (keys x) (vals x)))
    (coll? x) (every? no-cell-anywhere? x)
    :else     true))

;; ===========================================================================
;; view-manifest — the versioned public shape (what CAN happen)
;; ===========================================================================

(deftest view-manifest-projects-the-versioned-public-shape
  (register-view! :demo/widget demo-manifest)
  (let [vm (tool/view-manifest :demo/widget)]
    (is (= tool/schema-version (:rf.ui.tool/version vm)) "stamped with the schema version")
    (is (= :demo/widget (:view-id vm)))
    (is (= "A demo widget." (:doc vm)))
    (is (= {:file "demo.cljc" :line 10 :column 1} (:source vm)))
    (is (= #{:cell :local} (:capabilities vm)))
    (is (false? (:open-props? vm)))
    (is (true? (:children? vm)))

    (testing "per-prop docs/defaults are the single source of truth (readiness P1-5)"
      (let [by-key (into {} (map (juxt :key identity)) (:props vm))]
        (is (= 3 (count (:props vm))))
        (is (= {:key :label :slot "label" :index 0
                :doc "The button label." :optional? false :schema :string}
               (:label by-key))
            "label carries its doc + schema + not-optional")
        (is (= {:key :count :slot "count" :index 1
                :default 0 :optional? true :schema :int}
               (:count by-key))
            "count carries its default + optional flag + schema")
        (is (= {:key :children :slot "children" :index 2} (:children by-key))
            "a slot with no literal schema entry carries key/slot/index alone — no fabrication")))

    (testing "render-slot + interop sites + site counts"
      (is (= [{:sid "sid1-s" :source-coord {:file "demo.cljc" :line 16} :inline? true}]
             (:render-slots vm)))
      (is (= [{:sid "sid1-r" :kind :ref :order 0}
              {:sid "sid1-t" :kind :effect :order 1}]
             (:interop-sites vm))
          "the frozen re-frame.ui.react interop sites are surfaced")
      (is (= {:subs 2 :events 5 :effects 0 :dispatch-fns 0 :frame-ops 0
              :render-slots 1 :interop 2 :locals 1 :htmls 0 :diagnostics 2}
             (:site-counts vm))))

    (testing "compile-tier a11y diagnostics ride the manifest projection"
      (is (= [{:sid "sid1-d1" :id :rf.ui.compile/a11y-click-non-interactive
               :tag :div :suppressed? false}
              {:sid "sid1-d2" :id :rf.ui.compile/a11y-missing-accessible-name
               :tag :button :suppressed? true
               :reason "named by the adjacent legend"}]
             (:diagnostics vm))
          (str "the compiler-minted site id, the finding id, and a suppressed "
               "finding's REASON all reach the consumer; the raw template path "
               "stays private")))

    (testing "the raw props schema rides alongside the derived per-prop view"
      (is (= (:props-schema demo-manifest) (:props-schema vm))))))

(deftest view-manifest-tolerates-a-missing-view
  (is (nil? (tool/view-manifest :nope/absent)) "an unregistered view projects nil"))

;; ===========================================================================
;; view-event-sites — literal / normalized / dynamic handler honesty (AC3)
;; ===========================================================================

(deftest view-event-sites-distinguish-literal-normalized-and-dynamic
  (register-view! :demo/widget demo-manifest)
  (let [res    (tool/view-event-sites :demo/widget)
        by-sid (into {} (map (juxt :sid identity)) (:handlers res))]
    (is (= tool/schema-version (:rf.ui.tool/version res)))
    (is (= 5 (count (:handlers res))))

    (testing "a LITERAL event vector is site-kind :literal and shows its vector"
      (let [h (by-sid "sid1-e")]
        (is (= :literal (:site-kind h)))
        (is (= :vector (:classification h)))
        (is (true? (:serializable? h)))
        (is (= '[:demo/inc n] (:handler h)) "the authored event vector is inspectable")
        (is (= :on-click (:prop h)))))

    (testing "a NORMALIZED options-map handler is site-kind :normalized"
      (let [h (by-sid "sid1-f")]
        (is (= :normalized (:site-kind h)))
        (is (= :options (:classification h)))
        (is (true? (:serializable? h)))
        (is (= {:event '[:demo/set v] :prevent-default true} (:handler h)))))

    (testing "a ui/event is opaque :dynamic — never claims callback internals"
      (let [h (by-sid "sid1-g")]
        (is (= :dynamic (:site-kind h)))
        (is (= :ui-event (:classification h)))
        (is (false? (:serializable? h)))
        (is (= :opaque (:handler h)) "the raw callback body is NOT projected")
        (is (true? (:sync? h)) "the controlled-input synchronous door is surfaced")))

    (testing "a bare fn and a dynamic expression are both opaque :dynamic"
      (doseq [sid ["sid1-h" "sid1-i"]]
        (let [h (by-sid sid)]
          (is (= :dynamic (:site-kind h)))
          (is (= :opaque (:handler h)))
          (is (false? (:serializable? h)))
          (is (false? (:sync? h)) "no sync door on a non-controlled site"))))))

(deftest view-event-sites-tolerates-a-missing-view
  (is (nil? (tool/view-event-sites :nope/absent))))

;; ===========================================================================
;; view-dependencies — literal vs dynamic query honesty
;; ===========================================================================

(deftest view-dependencies-are-honest-about-dynamic-queries
  (register-view! :demo/widget demo-manifest)
  (let [res    (tool/view-dependencies :demo/widget)
        by-sid (into {} (map (juxt :sid identity)) (:subscriptions res))]
    (is (= tool/schema-version (:rf.ui.tool/version res)))

    (testing "a fully-literal sub query is projected verbatim"
      (let [s (by-sid "sid1-a")]
        (is (false? (:dynamic? s)))
        (is (= [:demo/total] (:query s)))))

    (testing "a sub query carrying a captured local is :dynamic, with only its id"
      (let [s (by-sid "sid1-b")]
        (is (true? (:dynamic? s)))
        (is (= :demo/item (:query-id s)) "the literal query-id is honest")
        (is (not (contains? s :query)) "the dynamic runtime argument is NOT fabricated")))))

(deftest view-dependencies-tolerates-a-missing-view
  (is (nil? (tool/view-dependencies :nope/absent))))

;; ===========================================================================
;; mounted-views — committed connected-instance records + occurrence identity
;; ===========================================================================

(deftest mounted-views-are-committed-connected-instance-records
  (is (true? (evidence/install! ::xray)))
  (let [a (enrol! (connect! (reactive/make-cell :demo/row)) 1)
        b (enrol! (connect! (reactive/make-cell :demo/row)) 2)]
    (let [res  (tool/mounted-views)
          rows (view-of :demo/row)]
      (is (= tool/schema-version (:rf.ui.tool/version res)))
      (is (= 2 (count rows)) "two committed incarnations of ONE view")
      (is (= [:connected :connected] (mapv :connection rows)))
      (is (= 2 (count (into #{} (map :occurrence) rows)))
          "occurrence identity distinguishes two instances of one view (render-key alone insufficient)")
      (is (every? #(contains? % :evidence) rows) "each carries its bounded render evidence")
      (is (every? #(= {:intervals [] :dropped 0 :exact? true} (:lifecycle %)) rows)
          "a never-disconnected instance has an empty (exact) lifecycle log"))))

(deftest mounted-views-label-hide-vs-unmount-as-inference-not-runtime-truth
  ;; The core honesty row (rf2-un54gv / 03 §4). React gives no hide-vs-unmount
  ;; signal at cleanup, so a disconnect is `:disconnected {:reason :unknown}`; a
  ;; settled reconnect PROVES an Activity hide (a retroactive INFERENCE label); an
  ;; explicit teardown PROVES an unmount. The tier copies these through and never
  ;; fabricates the distinction.
  (is (true? (evidence/install! ::xray)))
  (let [cell (enrol! (connect! (reactive/make-cell :demo/hidden)) 1)]

    (testing "a bare disconnect stays honestly :unknown — no fabricated :unmounted"
      (reactive/disconnect! cell)
      (let [row (first (view-of :demo/hidden))]
        (is (= :disconnected (:connection row)) "runtime state is the observed fact")
        (is (= [{:state :disconnected :reason :unknown}]
               (:intervals (:lifecycle row)))
            "the reason stays :unknown until the runtime proves otherwise")))

    (testing "a settled reconnect retro-labels the PRIOR interval an Activity hide"
      (reactive/settle-disconnect! cell)
      (connect! cell)
      (let [row (first (view-of :demo/hidden))]
        (is (= :connected (:connection row)) "the live runtime state is :connected again")
        (is (= [{:state :disconnected :reason :activity-hidden :proof :reconnect}]
               (:intervals (:lifecycle row)))
            "the hide is a QUALIFIED retroactive inference, tagged with its proof")))

    (testing "explicit teardown proves an unmount and prunes the dead incarnation"
      (reactive/teardown! cell)
      (is (empty? (view-of :demo/hidden)) "a proven-dead incarnation leaves mounted-views"))))

(deftest mounted-views-are-empty-without-an-installed-tool
  (is (= {:rf.ui.tool/version tool/schema-version :views []} (tool/mounted-views))
      "no evidence tool installed → an empty but versioned envelope"))

;; ===========================================================================
;; explain-render — causes + explicit loss accounting
;; ===========================================================================

(deftest explain-render-projects-causes-and-loss-accounting
  (is (true? (evidence/install! ::xray)))
  (let [cell (connect! (reactive/make-cell :demo/why))
        sink (evidence/installed-sink)]
    ;; drive real evidence: 8 shown + 2 dropped distinct targets in one window
    (doseq [i (range 10)]
      (sink cell {:first-epoch 1 :latest-epoch 1 :count 1 :causes #{:value}
                  :targets [[:sub :rf/default [:demo/q i]]]
                  :dropped #{} :dropped-exact? true}))
    (let [res (tool/explain-render :demo/why)
          occ (first (:occurrences res))]
      (is (= tool/schema-version (:rf.ui.tool/version res)))
      (is (= :demo/why (:view-id res)))
      (is (= 1 (count (:occurrences res))))
      (is (= #{:value} (:causes occ)) "the projected render cause")
      (is (= 10 (:render-count occ)) "every occurrence counted")
      (is (= 8 (count (:targets (:observations occ)))) "shown sample capped at 8")
      (is (true? (:identity-exact? (:observations occ)))
          "the shown targets kept exact identity (plain EDN, no opaque marker)")
      (is (= 2 (:dropped (:loss occ)))
          "two DISTINCT omissions past the shown cap — the completeness axis")
      (is (true? (:exact? (:loss occ))) "the loss account itself is exact")
      (is (= :connected (:connection occ)))))

  (testing "explain-render with no arg spans every incarnation"
    (is (= tool/schema-version (:rf.ui.tool/version (tool/explain-render))))
    (is (<= 1 (count (:occurrences (tool/explain-render)))))))

;; ===========================================================================
;; Serializable, versioned, read-only — no handles egress
;; ===========================================================================

(deftest projections-are-serializable-not-handles
  (register-view! :demo/widget demo-manifest)
  (is (true? (evidence/install! ::xray)))
  (let [cell (enrol! (connect! (reactive/make-cell :demo/row)) 1)
        outputs [(tool/view-manifest :demo/widget)
                 (tool/view-event-sites :demo/widget)
                 (tool/view-dependencies :demo/widget)
                 (tool/mounted-views)
                 (tool/explain-render :demo/row)]]
    (doseq [o outputs]
      (is (no-cell-anywhere? o) "no ViewCell reaches a projection result")
      (is (string? (pr-str o)) "the projection is printable")
      (is (= o (#?(:clj read-string :cljs cljs.reader/read-string) (pr-str o)))
          "…and round-trips through EDN unchanged — pure serializable data"))
    ;; read-only: a second read after no mutation is identical (deterministic)
    (is (= (tool/mounted-views) (tool/mounted-views)) "deterministic + read-only")
    ;; keep `cell` reachable so it is not collected mid-assertion
    (is (reactive/cell? cell))))
