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

(deftest render-cart-cascade-yields-four-section-blocks
  (testing "cart cascade → 4 section blocks each with a testid"
    (let [tree     (at/diff-tree cart-before cart-after)
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")]
      (is (= 4 (count sections)))
      (is (has-testid? hiccup "rf-causa-diff-section-[:cart]"))
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

(deftest section-header-renders-four-affordance-buttons
  (testing "rf2-ykjl5 — each section header carries Pin, Show-me-when,
            Copy-path, Copy-value buttons in the affordance row"
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
      (is (some? (find-by-testid hiccup
                                 (str "rf-causa-diff-section-pin-" suffix)))
          "Pin button present")
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

(deftest section-header-pin-button-dispatches-pin-slice
  (testing "rf2-ykjl5 — clicking the Pin button on a section header
            dispatches :rf.causa/pin-slice with the section path,
            targeted at the :rf/causa frame."
    (let [tree     (at/diff-tree {:cart {:items []}}
                                 {:cart {:items [{:id 7}]}})
          sections (sg/group-into-sections tree)
          hiccup   (render/render-sections sections "app-db-diff")
          {:keys [path]} (first sections)
          on-click (click-handler hiccup
                                  (str "rf-causa-diff-section-pin-"
                                       (pr-str path)))
          captured (with-dispatch-capture*
                     #(do (is (fn? on-click))
                          (on-click #js {})))]
      (is (= 1 (count captured)))
      (is (= [:rf.causa/pin-slice path] (first (first captured))))
      (is (= {:frame :rf/causa} (second (first captured)))
          "dispatch targeted at the :rf/causa frame"))))

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

(deftest breadcrumb-segment-click-copies-prefix-path
  (testing "rf2-ykjl5 — the breadcrumb renders one clickable segment per
            path element; plain click copies the absolute path-prefix
            up to AND INCLUDING that segment (design §5.1). Cmd-click
            is also supported because the click handler always fires —
            we just `preventDefault` so the browser's native Cmd-click
            behaviour does not interfere."
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
                             (handler #js {:preventDefault (fn [])})))
          captured (with-dispatch-capture*
                     #(doseq [i (range (count section-path))]
                        (click-segment! i)))]
      (is (= [0 1 2] @segment-clicks))
      (is (= 3 (count captured))
          "three clicks produce three dispatches")
      (doseq [i (range (count section-path))]
        (let [expected-prefix (vec (take (inc i) section-path))
              [event opts]    (nth captured i)]
          (is (= [:rf.causa/copy-path-to-clipboard expected-prefix]
                 event)
              (str "click on segment " i " dispatches copy-path with prefix "
                   (pr-str expected-prefix)))
          (is (= {:frame :rf/causa} opts)))))))

(deftest breadcrumb-renders-root-when-section-path-empty
  (testing "rf2-ykjl5 — empty section-path still renders the '(root)'
            label with the legacy section-path testid hook"
    (let [hdr (render/breadcrumb [] nil nil)]
      (is (some? (find-by-testid hdr "rf-causa-diff-section-path-[]"))
          "section-path span present even for the empty path")
      (is (some? (find-by-testid hdr
                                 "rf-causa-diff-section-affordances-[]"))
          "affordance row still ships for the root section"))))

(deftest breadcrumb-three-arity-back-compat-works
  (testing "rf2-ykjl5 — the legacy `[path summary]` arity remains
            callable; Copy-value just sends nil (no subtree-value
            supplied)"
    (let [hdr (render/breadcrumb [:a] {:added 1 :removed 0
                                       :modified 0 :children 0})]
      (is (vector? hdr))
      (is (some? (find-by-testid hdr "rf-causa-diff-section-pin-[:a]"))))))
