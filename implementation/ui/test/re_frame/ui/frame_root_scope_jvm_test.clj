(ns re-frame.ui.frame-root-scope-jvm-test
  "rf2-vxgfnd.25 — `frame-root` now EMITS its frame scope on the JVM
  (structural / SSR) host too: the compiled `:frame-root` node binds the
  dynamic-tier ambient frame to its literal `:id` around the subtree's
  construction (`frames/jvm-root-scope`), the mirror of the CLJS
  `scope-element` and of `jvm-provider-scope`. So an ambient `(sub …)` in a
  descendant view resolves the frame-root's frame during a Tier-1 render.

    - the `jvm-root-scope` helper binds `*current-frame*` (the emit mechanism,
      exercised directly, independent of the test render host);
    - end to end: an ambient `(sub …)` reads its frame's app-db through
      `ui.test/render` when that frame is the ambient scope (established here
      with `rf/with-new-frame` — the same ambient a `frame-root` binds)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.frames :as frames]
            [re-frame.ui.test :as uit]))

;; :ambient-frame nil — opt OUT of the fixture's default `:rf/default`
;; *current-frame* binding, so the jvm-root-scope unit arm sees a clean
;; nil ambient outside its own binding.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil})
  (fn [t] (frames/reset-installed-plans!) (t) (frames/reset-installed-plans!)))

(defview rooted-greeting
  "Reads a sub ambiently — resolves the enclosing frame-root's frame."
  []
  [:h1.greet (ui/sub [:greet/text])])

;; ---------------------------------------------------------------------------
;; the emit mechanism: jvm-root-scope binds the dynamic-tier ambient frame
;; ---------------------------------------------------------------------------

(deftest jvm-root-scope-binds-the-ambient-frame
  ;; PURE scope (no validation): outside the scope *current-frame* is nil;
  ;; inside, it is the frame-root's literal id — exactly what an ambient
  ;; sub-read resolves. This is the JVM half the emitted :frame-root node calls.
  (is (nil? frame/*current-frame*) "no ambient frame before the scope")
  (is (= :app/rooted
         (frames/jvm-root-scope :app/rooted (fn [] frame/*current-frame*)))
      "jvm-root-scope binds *current-frame* to the frame-root's id for its subtree")
  (is (nil? frame/*current-frame*) "the binding is scoped — unwinds after the thunk"))

;; ---------------------------------------------------------------------------
;; end to end: an ambient (sub …) under a frame-root reads its frame's app-db
;; ---------------------------------------------------------------------------

(deftest sub-under-frame-root-resolves-its-frame-jvm
  (rf/reg-sub :greet/text (fn [db _] (:greet db)))
  ;; Frame scope is the programmer's ordinary bracket: rf/with-new-frame binds
  ;; the fresh frame as the ambient *current-frame* for the render (the same
  ;; ambient a frame-root would establish) and destroys it on exit, so an
  ;; ambient (sub …) in the rendered view resolves that frame's app-db. (The
  ;; frame-root emit mechanism itself is pinned by jvm-root-scope-binds-the-
  ;; ambient-frame above.)
  (let [before (set (keys @frame/frames))]
    (rf/with-new-frame [_f (rf/make-frame
                            {:id :app/rooted
                             :initial-events [[:rf/set-db {:greet "scoped"}]]})]
      (let [tree (uit/render [rooted-greeting {}])]
        (is (= "scoped"
               (uit/text (some #(when (= :h1 (:tag %)) %)
                               (tree-seq map? :children tree))))
            "the ambient (sub …) resolved the frame's app-db on the JVM")))
    (is (= before (set (keys @frame/frames)))
        "the test-owned frame is torn down — no residue")))
