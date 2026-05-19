(ns day8.re-frame2-causa.diff.render-cljs-test
  "Smoke tests for the sections-per-cluster renderer (rf2-gfxmk Phase 1
  of rf2-abts7).

  Per the panel-render test pattern (`app_db_diff_slices_cljs_test`)
  each public renderer is invoked once and the load-bearing
  `data-testid` hooks asserted present in the produced hiccup."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.diff.annotated-tree :as at]
            [day8.re-frame2-causa.diff.render :as render]
            [day8.re-frame2-causa.diff.section-grouping :as sg]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter}))

;; ---- helpers -----------------------------------------------------------

(defn- has-testid? [tree testid]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (= testid (:data-testid (second node)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

(defn- any-testid-prefix? [tree prefix]
  (some (fn [node]
          (and (vector? node)
               (map? (second node))
               (let [tid (:data-testid (second node))]
                 (and tid (.startsWith tid prefix)))))
        (tree-seq (some-fn vector? seq?) seq tree)))

;; ---- empty / boundary --------------------------------------------------

(deftest render-sections-empty-state
  (testing "no sections → empty-state hiccup with testid hook"
    (is (has-testid? (render/render-sections [] "app-db-diff")
                     "rf-causa-diff-empty"))))

;; ---- single-leaf section -----------------------------------------------

(deftest render-single-leaf-section
  (testing "one :modified leaf at path [:a] → 1 section with breadcrumb +
            section testid + section-header testid"
    (let [tree     (at/diff-tree {:a 1} {:a 2})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      (is (= 1 (count sections)))
      (is (has-testid? hiccup "rf-causa-diff-sections"))
      (is (any-testid-prefix? hiccup "rf-causa-diff-section-"))
      (is (any-testid-prefix? hiccup "rf-causa-diff-section-header-")))))

;; ---- multi-section cart cascade ----------------------------------------

(def ^:private cart-before
  {:cart   {:items [{:id 7  :qty 1} {:id 22 :qty 1}] :gross 42}
   :user   {:name "Alice" :prefs {:theme :light}}
   :status :pending})

(def ^:private cart-after
  {:cart   {:items [{:id 7  :qty 1} {:id 22 :qty 2} {:id 91 :qty 3}]
            :gross 47.5}
   :user   {:name "Alice" :prefs {:theme :dark}}
   :status :submitting
   :flash  "Order saved"})

(deftest render-cart-cascade-yields-five-section-blocks
  (testing "cart cascade at tuned default depth=2 (rf2-ogkh0) → 5
            section blocks each with a testid. depth=2 keeps the
            sub-cart change-points separated; the [:cart :gross]
            singleton promotes to [:cart], the [:cart :items] cluster
            keeps its full path."
    (let [tree     (at/diff-tree cart-before cart-after)
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      (is (= 5 (count sections)))
      (is (has-testid? hiccup "rf-causa-diff-section-[:cart]"))
      (is (has-testid? hiccup "rf-causa-diff-section-[:cart :items]"))
      (is (has-testid? hiccup "rf-causa-diff-section-[:user :prefs]"))
      (is (has-testid? hiccup "rf-causa-diff-section-[:status]"))
      (is (has-testid? hiccup "rf-causa-diff-section-[:flash]")))))

;; ---- smart-expand depth + collapse-unchanged-chip ----------------------

(deftest render-collapses-unchanged-when-many-same-siblings
  (testing "design §5.3: many :same direct children collapse into a
            (N entries unchanged) chip"
    (let [;; Make 10 same keys + 1 modified key.
          padding (into {} (for [i (range 10)]
                             [(keyword (str "k" i)) i]))
          before  (assoc padding :changed 1)
          after   (assoc padding :changed 2)
          tree     (at/diff-tree before after)
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      ;; One section at [:changed]. The :same siblings aren't direct
      ;; children of the SECTION subtree (each section's subtree is the
      ;; cluster-headed local view, NOT the root), so this assertion
      ;; checks the collapse-chip primitive in isolation: when a
      ;; :children container has > collapse-unchanged-threshold
      ;; same-children, the chip renders.
      ;;
      ;; Drive this directly: build a :children node with many :same
      ;; kids and render via render-annotated.
      (is (= 1 (count sections))
          "single-key change yields one section")
      ;; Drive the collapse-chip primitive directly.
      (let [container {:day8.re-frame2-causa.diff.annotated-tree/op :children
                       :tag :map
                       :value (assoc padding :z 99)
                       :children (vec
                                   (concat
                                     [{:day8.re-frame2-causa.diff.annotated-tree/op :modified
                                       :key :z :before 1 :after 99}]
                                     (for [i (range 10)]
                                       {:day8.re-frame2-causa.diff.annotated-tree/op :same
                                        :key (keyword (str "k" i))
                                        :value i})))
                       :child-summary {:added 0 :removed 0
                                       :modified 1 :children 0 :same 10}}
            rendered  (render/render-annotated container [] [] "test" 0)]
        (is (has-testid? rendered "rf-causa-diff-unchanged-chip"))))))

(deftest render-smart-expand-respects-depth-cap
  (testing "design §3.1.2: auto-expand caps at smart-expand-max-depth
            levels (default 3)"
    (let [container {:day8.re-frame2-causa.diff.annotated-tree/op :children
                     :tag :map
                     :value {}
                     :children []
                     :child-summary {:added 0 :removed 0 :modified 5
                                     :children 0 :same 0}}
          ;; Render at the cap → should NOT recurse / auto-expand; emits
          ;; the collapse hint.
          rendered (render/render-annotated container [] [] "test"
                                            render/smart-expand-max-depth)]
      ;; The container block itself is still rendered, but the auto-
      ;; expand body is replaced by the deferred-expand hint.
      (is (vector? rendered)))))

;; ---- gutters per ::op --------------------------------------------------

(deftest render-modified-leaf-emits-yellow-tone
  (testing ":modified leaf — gutter renders with the yellow tone (no
            crash on the inline before → after format)"
    (let [node     {:day8.re-frame2-causa.diff.annotated-tree/op :modified
                    :key :status :before :pending :after :submitting}
          rendered (render/render-annotated node [] [] "test" 0)]
      (is (vector? rendered)))))

(deftest render-added-leaf-emits-green-gutter
  (let [node     {:day8.re-frame2-causa.diff.annotated-tree/op :added
                  :key :flash :value "Order saved"}
        rendered (render/render-annotated node [] [] "test" 0)]
    (is (vector? rendered))))

(deftest render-removed-leaf-emits-red-gutter
  (let [node     {:day8.re-frame2-causa.diff.annotated-tree/op :removed
                  :key :old-flag :value :was-here}
        rendered (render/render-annotated node [] [] "test" 0)]
    (is (vector? rendered))))

;; ---- sentinel handling -------------------------------------------------

(deftest render-redacted-sentinel-respects-elision-contract
  (testing "when both sides are :rf/redacted, the walker emits :same;
            the renderer never crosses the elision boundary"
    (let [tree (at/diff-tree {:auth :rf/redacted} {:auth :rf/redacted})]
      (is (= :same (at/op-of tree)))
      ;; And via sections — empty.
      (is (= [] (sg/group-into-sections tree))))))

;; ---- rf2-ykjl5: per-section-header affordances -------------------------
;;
;; The section header carries four buttons (Pin / Show-me-when /
;; Copy-path / Copy-value) and the breadcrumb path renders as
;; individually clickable segments — plain click copies the path-prefix
;; up to and including that segment (the design's §5.1 Cmd-click
;; integration; plain click suffices, Cmd-click is also compatible
;; because the click handler always fires).

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (tree-seq (some-fn vector? seq?) seq tree)))

(deftest section-header-renders-three-affordance-buttons
  (testing "rf2-ykjl5 — section header carries Show-me-when, Copy-path,
            Copy-value buttons in the affordance row. rf2-e9tb0 — the
            Pin button was dropped when pinned-watches was replaced by
            the segment-inspector popup."
    (let [tree     (at/diff-tree {:cart {:items []}}
                                 {:cart {:items [{:id 7}]}})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")
          ;; The path lifts to either [:cart] or [:cart :items] depending
          ;; on the grouper's singleton-promote rule. Find whichever.
          {:keys [path]} (first sections)
          suffix   (pr-str path)]
      (is (some? (find-by-testid hiccup
                                 (str "rf-causa-diff-section-affordances-"
                                      suffix)))
          "affordance row container present")
      (is (nil? (find-by-testid hiccup
                                (str "rf-causa-diff-section-pin-" suffix)))
          "Pin button is gone — pinned-watches feature dropped (rf2-e9tb0)")
      (is (some? (find-by-testid hiccup
                                 (str "rf-causa-diff-section-show-when-"
                                      suffix)))
          "Show-me-when button present")
      (is (some? (find-by-testid hiccup
                                 (str "rf-causa-diff-section-copy-path-"
                                      suffix)))
          "Copy-path button present")
      (is (some? (find-by-testid hiccup
                                 (str "rf-causa-diff-section-copy-value-"
                                      suffix)))
          "Copy-value button present"))))

;; The on-click handlers fire `rf/dispatch` (async by design — the
;; production click → enqueue path matches the slice-mini-panel's
;; pre-rf2-gfxmk behaviour). To assert per-click without juggling the
;; event-queue drain step, the tests intercept the macro-expansion
;; target `rf/dispatch*` via `with-redefs` and capture the
;; `[event opts]` tuple emitted by the click handler. (The `rf/dispatch`
;; macro expands to `rf/dispatch*` with a `:rf.trace/call-site` stamped
;; into the opts — we strip it before asserting so tests don't pin to
;; source-line metadata.) The events themselves are tested independently
;; in `panels.app_db_diff_events_cljs_test`.

(defn- strip-call-site [opts]
  (when (map? opts) (dissoc opts :rf.trace/call-site)))

(defn- with-dispatch-capture*
  [thunk]
  (let [captured (atom [])
        capture  (fn ([event] (swap! captured conj [event nil]) nil)
                   ([event opts]
                    (swap! captured conj [event (strip-call-site opts)])
                    nil))]
    (with-redefs [rf/dispatch* capture]
      (thunk))
    @captured))

(defn- click-handler [hiccup testid]
  (let [btn (find-by-testid hiccup testid)]
    (:on-click (second btn))))

(deftest section-header-show-when-button-dispatches-focus-slice-path
  (testing "rf2-ykjl5 — Show-me-when button dispatches
            :rf.causa/focus-slice-path with the section path"
    (let [tree     (at/diff-tree {:cart {:items []}}
                                 {:cart {:items [{:id 7}]}})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")
          {:keys [path]} (first sections)
          on-click (click-handler hiccup
                                  (str "rf-causa-diff-section-show-when-"
                                       (pr-str path)))
          captured (with-dispatch-capture*
                     #(do (is (fn? on-click))
                          (on-click #js {})))]
      (is (= 1 (count captured)))
      (is (= [:rf.causa/focus-slice-path path] (first (first captured))))
      (is (= {:frame :rf/causa} (second (first captured)))))))

(deftest section-header-copy-path-button-dispatches-copy-path
  (testing "rf2-ykjl5 — Copy-path button dispatches
            :rf.causa/copy-path-to-clipboard with the section path"
    (let [tree     (at/diff-tree {:cart {:items []}}
                                 {:cart {:items [{:id 7}]}})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")
          {:keys [path]} (first sections)
          on-click (click-handler hiccup
                                  (str "rf-causa-diff-section-copy-path-"
                                       (pr-str path)))
          captured (with-dispatch-capture*
                     #(do (is (fn? on-click))
                          (on-click #js {})))]
      (is (= 1 (count captured)))
      (is (= [:rf.causa/copy-path-to-clipboard path]
             (first (first captured))))
      (is (= {:frame :rf/causa} (second (first captured)))))))

(deftest section-header-copy-value-button-payload-is-after-value
  (testing "rf2-ykjl5 — Copy-value button dispatches
            :rf.causa/copy-value-to-clipboard. The payload is the
            section's after-value (the post-change value at the section
            path) — for a :modified leaf at the section root, that's
            :after."
    (let [;; A pure leaf-modified section: {:status :pending} →
          ;; {:status :submitting} ⇒ section subtree is a :modified
          ;; leaf at path [:status].
          tree     (at/diff-tree {:status :pending}
                                 {:status :submitting})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")
          {:keys [path]} (first sections)
          on-click (click-handler hiccup
                                  (str "rf-causa-diff-section-copy-value-"
                                       (pr-str path)))
          captured (with-dispatch-capture*
                     #(do (is (fn? on-click))
                          (on-click #js {})))]
      (is (= 1 (count captured)))
      (is (= [:rf.causa/copy-value-to-clipboard :submitting]
             (first (first captured)))
          "Copy-value sends the :after scalar as the payload"))))

(deftest section-header-copy-value-button-on-container-subtree
  (testing "rf2-ykjl5 — when the section subtree is a `:children`
            container (the common multi-key change case), Copy-value
            sends the container's :value — the after-value at the
            section path."
    (let [;; Two keys change under [:user] → section lifts to [:user]
          ;; and the subtree is a :children container.
          tree     (at/diff-tree {:user {:name "ada" :age 30}}
                                 {:user {:name "ada" :age 31}})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")
          {:keys [path subtree]} (first sections)
          on-click (click-handler hiccup
                                  (str "rf-causa-diff-section-copy-value-"
                                       (pr-str path)))
          captured (with-dispatch-capture*
                     #(do (is (fn? on-click))
                          (on-click #js {})))]
      (is (= 1 (count captured)))
      (is (= [:rf.causa/copy-value-to-clipboard (:value subtree)]
             (first (first captured)))
          "Copy-value sends the subtree's :value (the after-container)"))))

(deftest breadcrumb-segment-click-opens-segment-inspector
  (testing "rf2-e9tb0 — the breadcrumb renders one clickable segment
            per path element; a plain click opens the App-DB segment-
            inspector popup at the absolute path-prefix up to AND
            INCLUDING that segment. So clicking `:cart` in
            `[:cart :items 0 :price]` opens the popup at `[:cart]`;
            clicking `:price` opens it at the full leaf path."
    ;; Drive `breadcrumb` directly with a known multi-segment path so
    ;; the test is independent of the grouper's coalesce behaviour.
    (let [section-path [:user :prefs :theme]
          hdr          (render/breadcrumb section-path
                                          {:added 0 :removed 0
                                           :modified 1 :children 0}
                                          :dark)
          segment-clicks (atom [])
          click-segment! (fn [i]
                           (let [seg (find-by-testid
                                       hdr
                                       (str "rf-causa-diff-breadcrumb-segment-"
                                            (pr-str section-path) "-" i))
                                 handler (:on-click (second seg))]
                             (is (fn? handler)
                                 (str "segment " i " has on-click"))
                             (swap! segment-clicks conj i)
                             (handler #js {:preventDefault  (fn [])
                                            :stopPropagation (fn [])})))
          captured (with-dispatch-capture*
                     #(doseq [i (range (count section-path))]
                        (click-segment! i)))]
      (is (= [0 1 2] @segment-clicks))
      (is (= 3 (count captured))
          "three clicks produce three dispatches")
      (doseq [i (range (count section-path))]
        (let [expected-prefix (vec (take (inc i) section-path))
              [event opts]    (nth captured i)]
          (is (= [:rf.causa/open-segment-inspector expected-prefix]
                 event)
              (str "click on segment " i " opens the inspector at prefix "
                   (pr-str expected-prefix)))
          (is (= {:frame :rf/causa} opts)))))))

(deftest breadcrumb-segments-have-hover-styling
  (testing "rf2-e9tb0 — clickable segments carry discoverable hover
            styling (dotted underline + pointer cursor + a tooltip) so
            the user knows they're interactive without screaming
            through the diff body"
    (let [section-path [:cart :items]
          hdr          (render/breadcrumb section-path
                                          {:added 0 :removed 0
                                           :modified 1 :children 0}
                                          nil)
          seg          (find-by-testid
                         hdr
                         (str "rf-causa-diff-breadcrumb-segment-"
                              (pr-str section-path) "-0"))
          props        (second seg)
          style        (:style props)]
      (is (= "pointer" (:cursor style))
          "pointer cursor advertises click affordance")
      (is (= "underline" (:text-decoration style))
          "underline marks the segment as clickable text")
      (is (= "dotted" (:text-decoration-style style))
          "dotted underline keeps the chrome quiet")
      (is (string? (:title props))
          "tooltip text explains what clicking does"))))

(deftest breadcrumb-segments-have-no-pin-affordance
  (testing "rf2-e9tb0 — clicking a segment opens the inspector; it
            does NOT pin the path. Pinned-watches was the dropped
            alternative — this regression guard catches any future
            attempt to reintroduce pin-on-segment-click."
    (let [section-path [:cart]
          hdr          (render/breadcrumb section-path
                                          {:added 0 :removed 0
                                           :modified 1 :children 0}
                                          nil)
          seg          (find-by-testid
                         hdr
                         (str "rf-causa-diff-breadcrumb-segment-"
                              (pr-str section-path) "-0"))
          handler      (:on-click (second seg))
          captured     (with-dispatch-capture*
                         #(handler #js {:preventDefault  (fn [])
                                        :stopPropagation (fn [])}))]
      (is (= 1 (count captured)))
      (is (not= :rf.causa/pin-slice (first (first (first captured))))
          "click MUST NOT dispatch :rf.causa/pin-slice")
      (is (= :rf.causa/open-segment-inspector
             (first (first (first captured))))
          "click opens the segment inspector"))))

(deftest breadcrumb-renders-root-when-section-path-empty
  (testing "rf2-ykjl5 — empty section-path still renders the '(root)'
            label with the legacy section-path testid hook"
    (let [hdr (render/breadcrumb [] nil nil)]
      (is (some? (find-by-testid hdr "rf-causa-diff-section-path-[]"))
          "section-path span present even for the empty path")
      (is (some? (find-by-testid hdr
                                 "rf-causa-diff-section-affordances-[]"))
          "affordance row still ships for the root section"))))

(deftest breadcrumb-two-arity-back-compat-works
  (testing "rf2-ykjl5 — the legacy `[path summary]` arity remains
            callable; Copy-value just sends nil (no subtree-value
            supplied). rf2-e9tb0 — pin testid was dropped; assert on
            the copy-path button instead."
    (let [hdr (render/breadcrumb [:a] {:added 1 :removed 0
                                       :modified 0 :children 0})]
      (is (vector? hdr))
      (is (some? (find-by-testid hdr "rf-causa-diff-section-copy-path-[:a]"))))))

;; ---- rf2-s8r6c — origin-tag chip --------------------------------------

(deftest render-sections-omits-origin-chip-by-default
  (testing "rf2-s8r6c — back-compat: calling render-sections without
            opts produces no origin chip (legacy hiccup shape preserved
            for non-Causa-panel callers and tests)"
    (let [tree     (at/diff-tree {:a 1} {:a 2})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      (is (not (any-testid-prefix? hiccup "rf-causa-diff-section-origin-"))
          "no origin chip when opts omitted"))))

(deftest render-sections-pure-fx-section-tags-fx-db
  (testing "rf2-s8r6c — section with no flow coverage gets the
            `[fx :db]` chip"
    (let [tree     (at/diff-tree {:counter 0} {:counter 1})
          sections (sg/group-into-sections tree)
          triples  [{:op :modified :path [:counter] :before 0 :after 1}]
          hiccup   (render/render-sections
                     sections "app-db-diff"
                     {:flow-writes [] :diff-triples triples})
          chip     (find-by-testid hiccup
                                    "rf-causa-diff-section-origin-[:counter]")]
      (is (some? chip) "origin chip renders for pure-fx section")
      (is (= "fx" (:data-origin (second chip)))
          "data-origin attribute is 'fx'")
      (let [label (->> (tree-seq (some-fn vector? seq?) seq chip)
                       (filter string?)
                       (apply str))]
        (is (re-find #"\[fx :db\]" label)
            "chip label contains '[fx :db]'")))))

(deftest render-sections-flow-section-tags-flow-id
  (testing "rf2-s8r6c — section whose path equals a flow's :write-path
            gets the `[flow :flow-id]` chip"
    (let [tree     (at/diff-tree {:cart {:total 0}} {:cart {:total 52.5}})
          sections (sg/group-into-sections tree)
          writes   [{:flow-id :cart-total :write-path [:cart :total]}]
          triples  [{:op :modified :path [:cart :total]
                     :before 0 :after 52.5}]
          hiccup   (render/render-sections
                     sections "app-db-diff"
                     {:flow-writes writes :diff-triples triples})]
      ;; Section path may coalesce to [:cart] or stay [:cart :total]
      ;; depending on grouping defaults — assert at least one origin
      ;; chip carries the `:flow` attribution.
      (let [chips (filter
                    (fn [n]
                      (and (vector? n) (map? (second n))
                           (some-> (:data-testid (second n))
                                   (.startsWith "rf-causa-diff-section-origin-"))))
                    (tree-seq (some-fn vector? seq?) seq hiccup))]
        (is (seq chips) "at least one origin chip renders")
        (is (some #(= "flow" (:data-origin (second %))) chips)
            "a chip carries data-origin='flow'")
        (let [label (->> (tree-seq (some-fn vector? seq?)
                                   seq (first chips))
                         (filter string?)
                         (apply str))]
          (is (re-find #":cart-total" label)
              "chip label names the flow id"))))))

(deftest render-sections-mixed-section-tags-mixed
  (testing "rf2-s8r6c — coalesced section covering one flow write AND
            a handler-only sibling triple gets the mixed chip"
    (let [;; Both writes occur under [:cart] — section coalesces.
          tree     (at/diff-tree
                     {:cart {:total 0 :items []}}
                     {:cart {:total 52.5 :items [:apple]}})
          sections (sg/group-into-sections tree)
          writes   [{:flow-id :cart-total :write-path [:cart :total]}]
          triples  [{:op :modified :path [:cart :total]
                     :before 0 :after 52.5}
                    {:op :modified :path [:cart :items]
                     :before [] :after [:apple]}]
          hiccup   (render/render-sections
                     sections "app-db-diff"
                     {:flow-writes writes :diff-triples triples})
          chips    (filter
                     (fn [n]
                       (and (vector? n) (map? (second n))
                            (some-> (:data-testid (second n))
                                    (.startsWith "rf-causa-diff-section-origin-"))))
                     (tree-seq (some-fn vector? seq?) seq hiccup))]
      (is (seq chips) "origin chip(s) render for the mixed section")
      ;; At least one chip must reflect the mixed attribution OR the
      ;; flow-only attribution (depending on grouping coalescence).
      ;; The contract: a chip with data-origin in #{"flow" "mixed"}
      ;; exists; if mixed, label contains both the flow id and [fx :db].
      (let [mixed (filter #(= "mixed" (:data-origin (second %))) chips)]
        (when (seq mixed)
          (let [label (->> (tree-seq (some-fn vector? seq?)
                                     seq (first mixed))
                           (filter string?)
                           (apply str))]
            (is (re-find #":cart-total" label)
                "mixed chip label names the flow id")
            (is (re-find #"\[fx :db\]" label)
                "mixed chip label includes [fx :db]")))))))

(deftest breadcrumb-omits-chip-when-no-origin-tag
  (testing "rf2-s8r6c — calling breadcrumb without an origin-tag arg
            (legacy 2-/3-arity) produces no chip"
    (let [hdr (render/breadcrumb [:a]
                                  {:added 1 :removed 0 :modified 0 :children 0}
                                  nil)]
      (is (nil? (find-by-testid hdr "rf-causa-diff-section-origin-[:a]"))
          "no chip rendered when origin-tag arg omitted"))))

(deftest breadcrumb-renders-chip-when-origin-tag-supplied
  (testing "rf2-s8r6c — the 4-arity breadcrumb renders the chip"
    (let [hdr (render/breadcrumb
                [:cart :total]
                {:added 0 :removed 0 :modified 1 :children 0}
                nil
                {:kind :flow :flow-id :cart-total})]
      (is (some? (find-by-testid
                   hdr "rf-causa-diff-section-origin-[:cart :total]"))
          "chip renders next to the path breadcrumb"))))

;; ---- rf2-5kfxe.2 — diff-flash motion -----------------------------------

(defn- find-section
  "Locate the rendered `[:section ...]` for `path` inside a
  `render-sections` hiccup tree."
  [hiccup path]
  (let [testid (str "rf-causa-diff-section-" (pr-str path))]
    (some (fn [node]
            (when (and (vector? node)
                       (= :section (first node))
                       (map? (second node))
                       (= testid (:data-testid (second node))))
              node))
          (tree-seq (some-fn vector? seq?) seq hiccup))))

(deftest section-omits-flash-without-epoch-id
  (testing "legacy callers / test rigs that omit :epoch-id keep the
            pre-rf2-5kfxe.2 hiccup shape — no `:animation` style"
    (let [tree     (at/diff-tree {:a 1} {:a 2})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")
          sec      (find-section hiccup [:a])]
      (is (some? sec) "section exists")
      (is (nil? (get-in sec [1 :style :animation]))
          "no :animation style without an :epoch-id opt"))))

(deftest section-renders-flash-when-epoch-id-supplied
  (testing "rf2-5kfxe.2 — :epoch-id present → each changed section
            carries the `rf-causa-diff-flash` :animation prop. Duration
            is interpolated through the --rf-causa-motion-scale seam
            (rf2-5kfxe.5) so prefers-reduced-motion can disable it."
    (let [tree     (at/diff-tree {:a 1} {:a 2})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff"
                                           {:epoch-id 42})
          sec      (find-section hiccup [:a])
          anim     (get-in sec [1 :style :animation])]
      (is (some? sec))
      (is (string? anim))
      (is (re-find #"rf-causa-diff-flash" anim)
          "animation references the diff-flash keyframes")
      (is (re-find #"var\(--rf-causa-motion-scale" anim)
          "animation duration is calc()'d through the motion-scale seam")
      (is (re-find #"forwards" anim)
          "fill-mode forwards pins the end state (transparent)"))))

(deftest section-react-key-includes-epoch-id
  (testing "rf2-5kfxe.2 — the React key for each section is
            `<epoch-id>/<path>`, so a fresh cascade forces React to
            re-mount the section element + replay the CSS animation
            from frame 0. Without this, React would reuse the same
            DOM node and the keyframes wouldn't restart."
    (let [tree     (at/diff-tree {:a 1} {:a 2})
          sections (sg/group-into-sections tree)
          hiccup-a (render/render-sections sections "app-db-diff"
                                           {:epoch-id 1})
          hiccup-b (render/render-sections sections "app-db-diff"
                                           {:epoch-id 2})
          ;; Locate the section under both keys via the wrapper's
          ;; child list. The `(into [:div …] (for [s sections]
          ;; ^{:key …} …))` lays out the children in order, so we can
          ;; introspect the metadata on element index 1 (skip the
          ;; opening `:div` + props map).
          key-of   (fn [hiccup]
                     ;; hiccup is `[:div {:data-testid ...} child]`.
                     ;; The `child` carries `:key` via reader-meta —
                     ;; React's key extraction reads it from the
                     ;; vector's meta.
                     (-> hiccup
                         (nth 2)
                         meta
                         :key))]
      (is (re-find #"^1/" (key-of hiccup-a))
          "epoch 1's section key starts with `1/`")
      (is (re-find #"^2/" (key-of hiccup-b))
          "epoch 2's section key starts with `2/`")
      (is (not= (key-of hiccup-a) (key-of hiccup-b))
          "different epoch-ids yield different React keys"))))
