(ns re-frame.handler-source-test
  "`:rf.handler/source` (Spec 009): the `reg-event` macro stamps the whole
  form as written, for Xray's Event panel.

  Auto-capture is dev-only on both hosts (the macro binds the source to
  `(if rf.interop/debug-enabled? <src> nil)` and `merge-form-source` is gated
  the same way), so that deftest is `^:requires-debug`. A `:rf.handler/source`
  the caller supplies is never touched, so `user-supplied-source-wins` holds in
  both postures."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- handler-source [id]
  (:rf.handler/source (rf/handler-meta {:source :store :kind :event :id id})))

(deftest ^:requires-debug reg-event-captures-form-source
  (rf/reg-event :rf2-xhfxcs/event-sample
                {:doc "metadata-shape middle slot"}
                (fn [{:keys [db]} _ev] {:db db}))
  (is (= (str "(rf/reg-event :rf2-xhfxcs/event-sample {:doc \"metadata-shape middle slot\"}"
              " (fn [{:keys [db]} _ev] {:db db}))")
         (handler-source :rf2-xhfxcs/event-sample))))

(deftest user-supplied-source-wins
  ;; A code-gen pass can stamp the original site's source; auto-capture
  ;; must not overwrite it.
  (rf/reg-event :rf2-xgfuy/explicit-source
                {:rf.handler/source "(rf/reg-event :elsewhere ...)"
                 :doc "hand-stamped source from a code-gen pass"}
                (fn [{:keys [db]} _] {:db db}))
  (is (= "(rf/reg-event :elsewhere ...)" (handler-source :rf2-xgfuy/explicit-source))))
