(ns re-frame.freehand.guide.compilation-cljs-test
  "Executable fixtures for `docs/core/freehand/compilation.md` and
  `docs/core/freehand/debugging.md`.

  Compilation is the chapter with the sharpest failure mode. `{:compiled
  true}` submits a body to a closed grammar, so a sample that drifts out of
  that grammar does not read wrong — it stops building, at the consumer's
  site rather than ours. Transcribing each compiled declaration here means
  the grammar's edge is proved against the page every run, in both
  execution modes and on both hosts.

  Two page-specific notes on transcription:

  - **Elided bodies get a real one.** The guide writes `…` where the body is
    beside the point (`(v/defview people-list {:compiled true} [props] …)`).
    A fixture supplies the smallest body inside the grammar; what the block
    is claiming is the DECLARATION shape, and that is what stays verbatim.
  - **Anti-examples are not transcribed as code.** Two blocks open with a
    commented-out illegal spelling and then show the recovery. Only the
    recovery is code here; the illegal half is already owned, executably, by
    `re-frame.freehand.analyze-reject-cljs-test`.

  Filed under rf2-qwsmv."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.test :as t]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; compilation.md — the promotion ladder
;; ---------------------------------------------------------------------------

;; block 1 — the promotion recipe. The guide elides the body; the
;; declaration shape is the claim.
(v/defview people-list-promotion-recipe
  {:compiled true}
  [props]
  [:ul [:li (:label props)]])

;; block 2 — selecting compiled mode on an ordinary row. Call sites stay
;; `[todo-row props]`, and the var still holds a descriptor.
(v/defview todo-row-compiled
  {:compiled true}
  [{:keys [id]}]
  (let [{:keys [title completed]} (v/sub [:todo/by-id id])]
    [:li {:class {:completed completed}}
     [:input {:type :checkbox
              :checked completed
              :on-change [:todo/toggle id]}]
     title]))

;; block 4 — the mini promotion transcript. The interpreted starting point,
;; then the two declarations the build's recoveries produce.
(defn person-row-markup [p] [:li (:name p)])

;; 1) interpreted, fails check — opaque helper + map markup
(v/defview people-list-interpreted [_]
  [:ul (map (fn [p] (person-row-markup p)) (v/sub [:people]))])

;; 2) after taking the build's named recoveries — keyed for + declared child
(v/defview person-row-compiled
  {:compiled true}
  [{:keys [person]}]
  [:li (:name person)])

(v/defview people-list-compiled
  {:compiled true}
  [_]
  [:ul
   (for [p (v/sub [:people])]
     [person-row-compiled {:key (:id p) :person p}])])

;; block 5 — reactive sites must be finite: the recovery is a keyed child
;; that subscribes for one row.
(v/defview item-row
  {:compiled true}
  [{:keys [id]}]
  [:li (:title (v/sub [:item id]))])

(v/defview item-list
  {:compiled true}
  [_]
  [:ul
   (for [id (v/sub [:item-ids])]
     [item-row {:key id :id id}])])

;; block 6 — an opaque helper returning markup, which interpreted mode is
;; perfectly happy with. The guide's own comment says so.
(defn field-help [{:keys [error hint]}]
  (if error [:p.error error] [:p.hint hint]))

(v/defview editor [_]
  [:section (field-help {:error (v/sub [:e]) :hint "…"})])  ; fine interpreted

;; block 7 — the recovery when the markup is already in hand as a value:
;; a visible interpreted child, counted in the manifest.
(def some-hiccup-value [:em "late-transformed markup"])

(def markup-child
  [v/markup {:value some-hiccup-value}])

(v/defview compiled-parent-with-markup-child
  {:compiled true}
  [_]
  [:section
   [v/markup {:value some-hiccup-value}]])

;; block 8 — parameterized content in a compiled body: `v/slot` at the site,
;; `v/render-fn` at the caller, both lexically visible.
(v/defview data-table-compiled
  {:compiled true}
  [{:keys [rows row]}]
  [:table
   [:tbody
    (for [item rows]
      [:tr {:key (:id item)}
       (v/slot row item)])]])

;; The block prints the caller as a bare fragment under a compiled-mode
;; heading, so it is hosted in a COMPILED declaration here — which is the
;; faithful reading and the only one that renders. A `v/render-fn` authored
;; in an INTERPRETED body answers with raw hiccup, and a compiled parent's
;; child collector refuses it (`:rf.error/ui-tree-malformed`); authored in a
;; compiled body it lowers to nodes and the slot fills.
(v/defview data-table-compiled-call
  {:compiled true}
  [{:keys [people]}]
  ;; caller — pure, visible body
  [data-table-compiled
   {:rows people
    :row  (v/render-fn [person]
            [:td (:name person)])}])

;; block 9 — a props schema, which is optional in the grammar and mandatory
;; by policy for a shipped library leaf. The guide elides the body.
(v/defview todo-row-with-props-schema
  {:compiled true
   :props [:map [:id :int]]}   ; vector-form Malli; closed by default for maps
  [{:keys [id]}]
  [:li (str id)])

;; ---------------------------------------------------------------------------
;; debugging.md — the two inspection reads
;; ---------------------------------------------------------------------------
;;
;; Blocks 3 and 4 (`v/active-connections` / `v/command-log`) are browser-only
;; by the page's own statement — "absent on the JVM exactly like the mount
;; verbs". They are covered from
;; `re-frame.freehand.guide.host-cljs-test`, alongside `v/mount`, so the
;; whole browser-only half of the door is proved present in one place.

;; ---------------------------------------------------------------------------
;; The samples, executed
;; ---------------------------------------------------------------------------

(defn- seed!
  [db]
  (rf/reg-sub :todo/by-id (fn [d [_ id]] (get-in d [:todos id])))
  (rf/reg-sub :people (fn [d _] (:people d)))
  (rf/reg-sub :item (fn [d [_ id]] (get-in d [:items id])))
  (rf/reg-sub :item-ids (fn [d _] (:item-ids d)))
  (rf/reg-sub :e (fn [d _] (:error d)))
  (rf/dispatch-sync [:rf/set-db db]))

(deftest promotion-is-a-declaration-edit-and-nothing-else
  (testing "compilation.md's call-site-invariance law: the promoted row
            renders the same tree the interpreted spelling would, and the
            var still holds a descriptor rather than a function."
    (seed! {:todos {1 {:title "Ship it" :completed true}}})
    (is (v/view? todo-row-compiled) "the public var remains a descriptor")
    (is (= :compiled (:lowering (v/describe todo-row-compiled))))
    (let [tree  (t/with-render (t/render [todo-row-compiled {:id 1}]))
          input (t/find tree #(= :input (:tag %)))]
      (is (= "Ship it" (t/text (t/find tree #(= :li (:tag %))))))
      (is (true? (:checked (t/attrs input))))
      (is (= [:todo/toggle 1] (:on-change (t/attrs input)))
          "event intent is the same data in either mode"))))

(deftest the-transcript-recoveries-both-build-and-render
  (testing "compilation.md's mini transcript — the recovered spelling is a
            keyed `for` over a visible `sub` plus a declared child, and it
            renders one child per person."
    (seed! {:people [{:id 1 :name "Ada"} {:id 2 :name "Bob"}]})
    (let [tree (t/with-render (t/render [people-list-compiled {}]))]
      (is (= ["Ada" "Bob"] (mapv t/text (t/find-all tree #(= :li (:tag %))))))))
  (testing "and the interpreted starting point still renders, because
            interpreted mode runs full Clojure — the helper is only a
            problem once the declaration claims the grammar."
    (seed! {:people [{:id 1 :name "Ada"}]})
    (is (= ["Ada"] (mapv t/text (t/find-all
                                  (t/with-render (t/render [people-list-interpreted {}]))
                                  #(= :li (:tag %))))))))

(deftest a-finite-site-in-the-child-is-the-recovery-for-a-loop-read
  (testing "compilation.md's finiteness rule — the parent reads the id set,
            the child reads one row, and both are compiled."
    (seed! {:item-ids [:a :b] :items {:a {:title "alpha"} :b {:title "beta"}}})
    (is (= ["alpha" "beta"]
           (mapv t/text (t/find-all (t/with-render (t/render [item-list {}]))
                                    #(= :li (:tag %))))))))

(deftest a-slot-in-a-compiled-body-renders-the-callers-visible-row
  (testing "compilation.md's parameterized-content block — `v/render-fn` at
            the caller, `v/slot` at the site, both inside the grammar."
    (let [tree (t/render [data-table-compiled-call {:people [{:id 1 :name "Ada"}]}])]
      (is (= ["Ada"] (mapv t/text (t/find-all tree #(= :td (:tag %)))))))))

(deftest describe-projects-exactly-the-keys-the-page-prints
  (testing "debugging.md block 1 — `v/describe` is a plain map, and
            `:props-schema` is ABSENT rather than `:any` when none was
            declared. Absence is the claim; a nil would blur two facts."
    (let [d (v/describe todo-row-compiled)]
      (is (true? (:re-frame.freehand/view d)))
      (is (= ::todo-row-compiled (:view-id d)))
      (is (= :compiled (:lowering d)))
      (is (= :optional (:children-policy d)))
      (is (map? (:source d)))
      (is (not (contains? d :props-schema))
          "no schema declared — the key is absent, not nil and not :any"))
    (is (contains? (v/describe todo-row-with-props-schema) :props-schema)
        "and present the moment one is declared")))

(deftest manifest-is-nil-for-interpreted-and-names-its-crossings-when-compiled
  (testing "debugging.md block 2 — `nil` is the honest answer for an
            interpreted declaration, and a compiled one says where the
            compiled tier STOPS."
    (is (nil? (v/manifest editor))
        "interpreted — nil, which is an answer rather than an omission")
    (let [m (v/manifest compiled-parent-with-markup-child)]
      (is (= :re-frame.freehand/v1 (:grammar m))
          "the artifact-wide grammar version rides on the manifest")
      (is (= ::compiled-parent-with-markup-child (:view-id m)))
      (is (= [{:view-id :re-frame.freehand/markup :lowering :interpreted}]
             (mapv #(select-keys % [:view-id :lowering]) (:crossings m)))
          "one crossing, into the interpreted child the page names"))))
