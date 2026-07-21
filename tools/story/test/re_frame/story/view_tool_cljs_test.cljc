(ns re-frame.story.view-tool-cljs-test
  "rf2-vxgfnd.95.8 — Story's consumer of the compiled-view tool tier
  (`re-frame.ui.tool`, rf2-vxgfnd.95.6). Proves `re-frame.story.view-tool`:

    - projects a compiled view's IDENTITY + manifest + dependency/event SITES
      and its committed CONNECTED-INSTANCE records + render CAUSES, keyed on the
      view-id, stamping the tier `:rf.ui.tool/version` through;
    - labels the variant's observation overrides `:owned? false` — honest
      NON-OWNERSHIP (Story observes; core owns the static `:story-override`
      handle);
    - tolerates ABSENCE explicitly: an uncompiled view → `:compiled-view? false`
      with nil manifest but a still-live tier; no evidence tool → empty
      `:mounted`/`:render` rather than a fabricated record;
    - summarises a JVM structural tree's node VOCABULARY as a pure read.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM), so the consumer is graft-checked on both hosts.
  Real `ui.test/render` trees + the JVM `:sub-overrides` observation door land
  in the JVM-only companion (`view-tool-tree-jvm-test`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.registrar             :as registrar]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            [re-frame.ui.reactive           :as reactive]
            [re-frame.ui.tool               :as tool]
            [re-frame.ui.tool.evidence      :as evidence]
            [re-frame.story.view-tool       :as view-tool]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

;; A hand-built manifest mirroring the compiler's emitted shape — the SAME
;; contract the tool tier's own `-cljs-test` pins; the JVM `ui.test/render`
;; companion proves a REAL emitted manifest projects the same.
(defn- register-view! [view-id manifest]
  (registrar/register! :view view-id {:rf/id view-id
                                       :rf.ui/compiled? true
                                       :rf.ui/manifest manifest}))

(def ^:private demo-manifest
  {:view-id      :story.demo/widget
   :display-name "story.demo/widget"
   :doc          "A demo widget."
   :source       {:file "demo.cljc" :line 10 :column 1}
   :prop-slots   [{:key :label :slot "label" :index 0}]
   :props-schema [:map [:label {:doc "The button label."} :string]]
   :open-props?  false
   :children?    false
   :as?          false
   :capabilities #{:cell}
   :sites
   {:subs   [{:sid "s-a" :query [:demo/total] :path [] :expr-path [0]}]
    :events [{:sid "e-a" :site-index 0 :view-id :story.demo/widget
              :source-coord {:file "demo.cljc" :line 11} :path [:button 0]
              :prop :on-click :handler '[:demo/inc]
              :classification :vector :serializable? true}]
    :slots  []
    :react  []
    :effects [] :dispatch-fns [] :frame-ops [] :locals [] :htmls []}})

(defn- connect! [cell]
  (let [[_ capture] (reactive/with-capture cell (fn [] nil))]
    (reactive/commit! cell capture))
  cell)

(defn- enrol! [cell epoch]
  (reactive/mark-dirty! cell epoch)
  (reactive/flush-pending!)
  cell)

;; ===========================================================================
;; view-evidence — compiled view: identity + manifest + sites
;; ===========================================================================

(deftest view-evidence-projects-a-compiled-view
  (register-view! :story.demo/widget demo-manifest)
  (let [ev (view-tool/view-evidence :story.demo/widget)]
    (is (= :story.demo/widget (:view-id ev)))
    (is (true? (:tier-available? ev)) "the tier is live in a dev build")
    (is (true? (:compiled-view? ev)) "a registered compiled view is recognised")
    (is (= view-tool/consumed-schema-version (:rf.ui.tool/version ev))
        "the tier schema version is echoed through")
    (is (false? (:version-mismatch? ev)) "the consumed version matches")
    (testing "the manifest + declared SITES ride through the framework projections"
      (is (= "A demo widget." (:doc (:manifest ev))))
      (is (= [:demo/total] (:query (first (:subscriptions (:dependencies ev))))))
      (is (= :literal (:site-kind (first (:handlers (:event-sites ev))))))
      (is (= '[:demo/inc] (:handler (first (:handlers (:event-sites ev)))))))
    (testing "no live incarnation yet — empty, not fabricated"
      (is (= [] (:mounted ev)))
      (is (= [] (:render ev))))))

;; ===========================================================================
;; view-evidence — absence tolerated explicitly
;; ===========================================================================

(deftest view-evidence-tolerates-an-uncompiled-view
  (let [ev (view-tool/view-evidence :story.plain/reagent-component)]
    (is (false? (:compiled-view? ev))
        "a non-compiled variant view has no manifest — reported, not fabricated")
    (is (nil? (:manifest ev)))
    (is (nil? (:dependencies ev)))
    (is (nil? (:event-sites ev)))
    (is (true? (:tier-available? ev))
        "the tier itself is still live — absence is the VIEW, not the tier")
    (is (= [] (:mounted ev)) "no connected instances for an unknown view")
    (is (= [] (:render ev)))))

;; ===========================================================================
;; view-evidence — the version boundary is REAL (rf2-vxgfnd.98.1.1)
;; ===========================================================================

(deftest view-evidence-degrades-on-a-producer-version-it-was-not-taught
  ;; The version boundary must be REAL, not nominal. `consumed-schema-version` is
  ;; a consumer-owned LITERAL, so a producer that bumps its OWN schema-version
  ;; (its projections then stamp the new version) is a DETECTABLE mismatch — and
  ;; on mismatch Story SUPPRESSES every shape-dependent parse rather than shaping
  ;; the evolved manifest/sites/mounted/render as if understood. RED before the
  ;; fix, which set `:version-mismatch? true` yet STILL returned the parsed
  ;; manifest / :compiled-view? / mounted / render — a mis-parse presented as
  ;; exact.
  (register-view! :story.demo/widget demo-manifest)
  (let [producer-ahead (inc view-tool/consumed-schema-version)]
    ;; Bumping the producer var alone makes the REAL projections stamp the new
    ;; version — the running app's tier is simply ahead of this consumer.
    (with-redefs [tool/schema-version producer-ahead]
      (let [ev (view-tool/view-evidence :story.demo/widget {[:demo/total] 99})]
        (is (true? (:version-mismatch? ev))
            "a producer version the consumer was not taught is a detected mismatch")
        (is (= producer-ahead (:rf.ui.tool/version ev))
            "the producer's version is reported honestly")
        (is (true? (:tier-available? ev))
            "the tier is LIVE — the mismatch is the degrade, not absence")
        (testing "every shape-dependent parse is suppressed — never mis-parsed as understood"
          (is (false? (:compiled-view? ev)))
          (is (nil? (:manifest ev)))
          (is (nil? (:dependencies ev)))
          (is (nil? (:event-sites ev)))
          (is (= [] (:mounted ev)))
          (is (= [] (:render ev))))
        (testing "the non-shape-dependent observation frame (Story author data) still rides"
          (is (false? (:owned? (:observation ev))))
          (is (= 99 (:value (first (:overrides (:observation ev)))))))))))

;; ===========================================================================
;; view-evidence — connected-instance records + render causes
;; ===========================================================================

(deftest view-evidence-carries-committed-connected-instances-and-causes
  (register-view! :story.demo/row demo-manifest)
  (is (true? (evidence/install! ::story)))
  (let [a    (enrol! (connect! (reactive/make-cell :story.demo/row)) 1)
        b    (enrol! (connect! (reactive/make-cell :story.demo/row)) 2)
        sink (evidence/installed-sink)]
    (doseq [cell [a b]]
      (sink cell {:first-epoch 1 :latest-epoch 2 :count 1 :causes #{:subscription}
                  :targets [[:sub :rf/default [:demo/total]]]
                  :dropped #{} :dropped-exact? true}))
    (let [ev (view-tool/view-evidence :story.demo/row)]
      (is (= 2 (count (:mounted ev))) "two committed incarnations of ONE view")
      (is (every? #(= :connected (:connection %)) (:mounted ev)))
      (is (= 2 (count (into #{} (map :occurrence) (:mounted ev))))
          "occurrence identity distinguishes the two instances")
      (testing "render causes + loss accounting ride through explain-render"
        (let [occ (first (:render ev))]
          (is (some? occ))
          (is (= #{:subscription} (:causes occ)))
          (is (contains? (:loss occ) :dropped) "the honest loss account is present")
          (is (contains? (:observations occ) :identity-exact?))))
      ;; keep the cells reachable so they are not collected mid-assertion
      (is (every? reactive/cell? [a b])))))

;; ===========================================================================
;; observation overrides — honest `:owned? false` non-ownership
;; ===========================================================================

(deftest observation-overrides-are-labelled-non-owning
  (let [rows (view-tool/observation-overrides
              {[:cart/locked?] true
               [:item 7]       {:name "widget"}})]
    (is (= 2 (count rows)))
    (is (every? #(false? (:owned? %)) rows)
        "Story observes through the override door; it never owns the handle")
    (is (every? #(= :story-override (:kind %)) rows))
    (testing "each row carries its exact query vector + pinned value"
      (let [by-q (into {} (map (juxt :query identity)) rows)]
        (is (= true (:value (by-q [:cart/locked?]))))
        (is (= {:name "widget"} (:value (by-q [:item 7]))))))
    (testing "rows are deterministic (sorted by query)"
      (is (= rows (view-tool/observation-overrides
                   {[:item 7] {:name "widget"} [:cart/locked?] true})))))
  (is (= [] (view-tool/observation-overrides nil)) "nil overrides project []")
  (is (= [] (view-tool/observation-overrides {})) "empty overrides project []"))

(deftest view-evidence-surfaces-overrides-as-non-owning
  (register-view! :story.demo/widget demo-manifest)
  (let [ev (view-tool/view-evidence :story.demo/widget {[:demo/total] 99})]
    (is (false? (:owned? (:observation ev))) "the observation frame is non-owning")
    (is (= 1 (count (:overrides (:observation ev)))))
    (is (= 99 (:value (first (:overrides (:observation ev))))))))

;; ===========================================================================
;; tree-vocabulary — pure read over a structural tree (host-neutral shape)
;; ===========================================================================

(deftest tree-vocabulary-summarises-a-structural-tree
  ;; A hand-built node-schema-v1 tree (the SHAPE ui.test/render returns); the
  ;; JVM companion feeds a REAL rendered tree through the same fn.
  (let [tree {:view-id :story.demo/card :props {} :rf.ui/tree-version 1
              :children [{:tag :div
                          :children ["hi"
                                     {:view-id :story.demo/inner :props {}
                                      :children [{:tag :button :attrs {} :events {}}]}]}]}
        voc  (view-tool/tree-vocabulary tree)]
    (is (= 1 (:rf.ui/tree-version voc)))
    (is (= 4 (:node-count voc)) "text content is not a queryable node")
    (is (= 2 (:view-boundary (:kinds voc))) "the two view boundaries are counted")
    (is (= 2 (:element (:kinds voc))) "the :div and :button elements are counted")
    (is (= #{:story.demo/card :story.demo/inner} (:view-ids voc))))
  (testing "nil tree → empty summary"
    (let [voc (view-tool/tree-vocabulary nil)]
      (is (= 0 (:node-count voc)))
      (is (= #{} (:view-ids voc))))))
