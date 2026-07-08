(ns re-frame.adapter.helix-frame-provider-ensure-dom-cljs-test
  "Helix DOM/browser regression coverage for the NATIVE `frame-provider`
  `defnc`'s ENSURE (`{:id …}`) arm through Helix's `$` → `extract-cljs-props`
  marshalling seam (rf2-ipqu8p).

  WHY THIS EXISTS. `re-frame.adapter.helix/frame-provider` is a native Helix
  `defnc` with TWO arms dispatched on the prop map (see the component
  docstring): the SCOPE-ONLY `{:frame …}` arm and the ENSURE `{:id …}` arm.
  The SCOPE-ONLY arm is already pinned end-to-end through `$` by
  `frame-provider-trailing-children-propagate-frame`
  (helix_use_subscribe_dom_cljs_test) — the moved-up-seam regression
  (rf2-z7hfp / rf2-7kii2). The ENSURE arm, by contrast, mounted NOTHING
  through the native component anywhere in the Helix test tree: ENSURE
  BEHAVIOUR is covered at the SHARED / core level (`ensure-frame-fc` built
  with raw `createElement`; the reagent scenario-6 HOT-RELOAD GATE), but
  NONE of those touch Helix's `$` → `extract-cljs-props` marshalling of the
  ENSURE config. So whether `extract-cljs-props` faithfully beans the ENSURE
  config — the `:id` KEYWORD, the nested `:initial-events` / `:images`
  vectors, the `:url-bound?` boolean — before the `defnc` else arm hands
  props to `owned-frame/ensure-frame-react-element` was UNTESTED. This is
  exactly the prop-mangling class the SCOPE-ONLY regression exists to pin,
  but only for the `:frame` arm.

  These tests mount the public ENSURE call shape
  (`($ frame-provider {:id … :initial-events [[…]] …} ($ child))`) through
  the real `$` under `act`, and assert the frame is CREATED live with the
  config-seeded durable state AND a descendant `use-subscribe` reads it —
  the structural proof that the ENSURE config survived `$` marshalling
  intact. Twin of the UIx `glue-args` seam
  (uix_frame_provider_ensure_dom_cljs_test, rf2-0bhnwm).

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` (ns-regexp
  `-dom-cljs-test$`) discovers it for the real DOM assertions; `:node-test`'s
  `cljs-test$` regex also matches, where each test self-gates on `(browser?)`
  and no-ops cleanly."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react" :as React]
            ["react-dom/client" :as react-dom-client]
            [helix.core :refer-macros [$ defnc]]
            [helix.dom  :as d]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.helix :as helix-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter helix-adapter/adapter}))

;; ---- side-channel atoms + descendant probe --------------------------------
;; The probe is a top-level `defnc` (helix `defnc` defines a Var; it cannot
;; sit in a `let`) reading the ENSURED frame's value via the 1-arg
;; `use-subscribe` (which resolves through the surrounding provider's React
;; context — the ENSURE provider provides the created frame's id there).

(def ^:private ensure-observed (atom []))

(defnc ProbeEnsure []
  (let [v (helix-adapter/use-subscribe [:rf.helix-ensure/k])]
    (swap! ensure-observed conj v)
    (d/div (str "k=" v))))

;; Inline image carrying the ENSURE `:images`-arm registrations. A real
;; `rf/image` value (a CLJS record/map inside a vector) — precisely the
;; nested structure the `$` → `extract-cljs-props` seam must round-trip
;; without mangling. The inline `:reg-event` seeds durable app-db; the frame
;; created from this image runs it via `:initial-events` at construction.
(def ^:private ensure-image
  (rf/image
    {:id :rf.helix-ensure/image
     :registrations
     {:reg-event [[:rf.helix-ensure/seed
                   (fn [{:keys [db]} [_ v]] {:db (assoc db :img v)})]]}}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- get-act []
  (or (when (exists? (.-act React)) (.-act React))
      (try
        (let [test-utils (js/require "react-dom/test-utils")]
          (.-act test-utils))
        (catch :default _ nil))))

;; ---- ENSURE `:id` + `:initial-events` + `:url-bound?` through `$` ----------

(deftest ensure-id-arm-marshals-config-through-dollar
  (testing "Helix — ($ frame-provider {:id .. :initial-events [[..]] :url-bound? ..}) ENSURE arm marshals config through $/extract-cljs-props (rf2-ipqu8p)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          ;; Clear the fixture's ambient `:rf/default` dynamic scope so the
          ;; 1-arg `use-subscribe` in ProbeEnsure resolves through the ENSURE
          ;; provider's React-context tier (the created frame), not a
          ;; shadowing dynamic frame. Mirrors the SCOPE-arm regression's note.
          (binding [frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (reset! ensure-observed [])
            ;; Register the layer-1 db-reader the descendant reads. Default
            ;; image (no `:images`) → the ENSURE frame's generation projects
            ;; this + the framework `:rf/set-db` standard.
            (rf/reg-sub :rf.helix-ensure/k (fn [db _] (:k db)))
            (let [frame-kw   :rf.helix-ensure/frame
                  mount-node (.createElement js/document "div")
                  root       (react-dom-client/createRoot mount-node)]
              (try
                (act-fn
                  (fn []
                    ;; The public ENSURE call shape through the real `$`:
                    ;; `:id` KEYWORD, a nested `:initial-events` vector-of-
                    ;; vectors carrying the NAMESPACED `:rf/set-db` keyword +
                    ;; a nested seed map, and a `:url-bound?` boolean — all
                    ;; must survive `extract-cljs-props` before the `defnc`
                    ;; else arm hands them to `ensure-frame-react-element`.
                    (.render root
                      ($ helix-adapter/frame-provider
                         {:id             frame-kw
                          :initial-events [[:rf/set-db {:k :ensured}]]
                          :url-bound?     false}
                         ($ ProbeEnsure)))))
                ;; :id survived $ as a KEYWORD — a stringified id would have
                ;; thrown :rf.error/ensure-frame-provider-missing-id, and the
                ;; frame would not be registered under the keyword.
                (is (some? (frame/frame frame-kw))
                    "ENSURE created a live frame under the keyword :id (proves :id survived $ as a keyword)")
                ;; The nested [[:rf/set-db {:k :ensured}]] vector + namespaced
                ;; keyword + nested map survived extract-cljs-props: the seed
                ;; ran at construction into the durable app-db.
                (is (= {:k :ensured} (rf/app-db-value frame-kw))
                    ":initial-events seeded durable app-db (proves the nested vector-of-vectors + namespaced keyword + nested map survived $/extract-cljs-props)")
                ;; End-to-end: a descendant riding the native `$` trailing-
                ;; children channel read the ENSURED frame's value through the
                ;; provided React context.
                (is (some #{:ensured} @ensure-observed)
                    "descendant use-subscribe read the ENSURED frame's seeded value through the provided React context")
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))

;; ---- ENSURE `:images` through `$` -----------------------------------------

(deftest ensure-images-arm-marshals-image-vector-through-dollar
  (testing "Helix — ($ frame-provider {:id .. :images [img] :initial-events [[..]]}) ENSURE :images vector marshals through $/extract-cljs-props (rf2-ipqu8p)"
    (if-not (browser?)
      (is true ":node-test: no DOM — :browser-test runner exercises the assertion")
      (let [act-fn (get-act)]
        (if (nil? act-fn)
          (is true "act() not reachable from this runner; skipping")
          (binding [frame/*current-frame* nil]
            (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
            (let [frame-kw   :rf.helix-ensure/image-frame
                  mount-node (.createElement js/document "div")
                  root       (react-dom-client/createRoot mount-node)]
              (try
                (act-fn
                  (fn []
                    ;; `:images [ensure-image]` — a vector holding a real
                    ;; `rf/image` value — plus `:initial-events` referencing
                    ;; the image's inline event. If the image value were
                    ;; mangled to a bare JS object by `$`, make-frame's
                    ;; validate-images! would throw here; if the inline event
                    ;; id were lost, the seed would not apply below.
                    (.render root
                      ($ helix-adapter/frame-provider
                         {:id             frame-kw
                          :images         [ensure-image]
                          :initial-events [[:rf.helix-ensure/seed :img-ensured]]}
                         (d/div "ensure-images-child")))))
                (is (some? (frame/frame frame-kw))
                    "ENSURE created a live frame under the keyword :id")
                ;; The image's inline :reg-event resolved in the ensured
                ;; frame's generation and the :initial-events dispatch seeded
                ;; durable state through it — the decisive proof the :images
                ;; vector + its image value survived $ marshalling.
                (is (= :img-ensured (:img (rf/app-db-value frame-kw)))
                    ":images [inline-image] survived $ marshalling — the image's inline :reg-event resolved and :initial-events seeded through it")
                (finally
                  (try (.unmount root) (catch :default _ nil)))))))))))
