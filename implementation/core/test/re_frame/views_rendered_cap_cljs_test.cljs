(ns re-frame.views-rendered-cap-cljs-test
  "The per-run `:rf.view/rendered` cap (Spec 009 §`:rf.view/rendered`): at
  most 100 per frame per run, then one `:rf.view/rendered-cap-reached`. The
  count is core's own, so the cap holds with the epoch artefact's hook
  withdrawn and with it present, and renders after a run settles count
  against that run until the next run starts in the frame."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- render!
  "Fire the `:rf.view/rendered` emit site once for `frame-id`, as the
  frame-aware view wrapper does after each render."
  [frame-id]
  (#'re-frame.views/emit-view-rendered-trace!
   ::view [::view 0] frame-id false nil nil nil))

(defn- render-ops
  "Run `f`, returning how many of each `:rf.view/rendered*` operation it
  emitted for `frame-id`."
  [frame-id f]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::cap
                           (fn [ev]
                             (when (and (contains? #{:rf.view/rendered :rf.view/rendered-cap-reached}
                                                   (:operation ev))
                                        (= frame-id (get-in ev [:tags :frame])))
                               (swap! seen conj (:operation ev)))))
    (try (f) (finally (rf/unregister-listener! :trace ::cap)))
    (frequencies @seen)))

(defn- with-hook
  "Run `f` with the late-bind `hook-key` set to `hook`, restoring the
  original afterwards."
  [hook-key hook f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key hook)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(defn- check-cap-after-settle!
  "Run one event in a fresh frame, then render after it settles: 101
  renders emit 100 `:rf.view/rendered` and one cap marker, a further
  render emits nothing, and the next run starts a fresh budget."
  [frame-id]
  (rf/reg-event ::tick (fn [_ _] {}))
  (rf/make-frame {:id frame-id :doc "render-cap test frame"})
  (rf/dispatch-sync [::tick] {:frame frame-id})
  (is (= {:rf.view/rendered 100 :rf.view/rendered-cap-reached 1}
         (render-ops frame-id #(dotimes [_ 101] (render! frame-id)))))
  (is (= {} (render-ops frame-id #(render! frame-id)))
      "past the cap, the run's renders emit nothing")
  (rf/dispatch-sync [::tick] {:frame frame-id})
  (is (= {:rf.view/rendered 1} (render-ops frame-id #(render! frame-id)))
      "the next run starts a fresh budget"))

(deftest cap-holds-without-epoch
  (testing "with the epoch artefact's :epoch/run-cause hook withdrawn"
    (with-hook :epoch/run-cause nil
      #(check-cap-after-settle! ::without-epoch))))

(deftest cap-holds-after-settle-with-epoch
  (testing "with the epoch artefact loaded, for renders after the run settles"
    (is (some? (rf.late-bind/get-fn :epoch/run-cause)))
    (check-cap-after-settle! ::with-epoch)))
