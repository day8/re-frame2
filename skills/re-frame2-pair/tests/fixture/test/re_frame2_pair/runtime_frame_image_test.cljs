(ns re-frame2-pair.runtime-frame-image-test
  "rf2-fzbj.15 F1 — call-time validation resolves through the OPERATING
  FRAME's image, not the process source store.

  This exercises the SHIPPED preload (`re-frame2-pair.runtime`) against a
  REAL frame carrying a REAL inline image, with real `rf/image` /
  `rf/make-frame` / `rf/dispatch` / `rf/subscribe` underneath — not the
  pure core, and not a structural assertion that a validator is called.
  The defect was invisible to both of those: `validate-registered` was
  called, and it delegated to the correct pure helper; what was wrong was
  the SET it handed that helper.

  ## The defect

  A frame runs its own sealed image generation, so an `:reg-sub` /
  `:reg-event` defined INLINE IN AN IMAGE is registered for that frame
  and absent from the process store. Validation read
  `(rf/registrations {:source :store :kind k})`, so those ids came back
  `:reason :unknown-id :known-count 0` — the typed read and the default
  dispatch both refused, telling the operator 'nothing is registered'
  about an app whose frame subscribes and dispatches perfectly well, and
  pushing them to raw `eval-cljs` for a gesture the typed tools support.
  The skill teaches per-frame image operation explicitly, so this is an
  ordinary supported shape rather than an exotic one.

  Both directions are covered, because a set-membership repair can fail
  either way: an image-only id must now be ACCEPTED, and a store-only id
  excluded from the chosen frame must now be REFUSED (it would otherwise
  validate and then dispatch into a frame that cannot serve it)."
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame2-pair.runtime :as rt]))

;; A STORE registration, deliberately excluded from the image below — the
;; negative direction's subject.
(rf/reg-sub :review/store-only-sub (fn [db _] (:store-only db)))
(rf/reg-event :review/store-only-event (fn [{:keys [db]} _] {:db (assoc db :store-only true)}))

(def ^:private frame-id :review/frame)

(defn- ensure-frame! []
  (rf/init! plain-atom/adapter)
  (rf/make-frame
    {:id     frame-id
     :images [(rf/image
                {:id :review/image
                 :registrations
                 {:reg-sub   [[:review/inline-sub (fn [db _] (:n db))]]
                  :reg-event [[:review/inline-event
                               (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))})]]}})]}))

(deftest image-only-sub-reads-through-the-typed-tool
  (ensure-frame!)
  (let [res (rt/read-sub! [:review/inline-sub] frame-id)]
    (is (true? (:ok? res))
        "an inline-image sub is a valid id for the frame that carries it")
    (is (not= :unknown-id (:reason res))
        "and is NOT refused as unregistered")
    (is (= [:review/inline-sub] (:query-v res)) "the query-v echoes back")))

(deftest image-only-event-dispatches-through-the-typed-tool
  (ensure-frame!)
  (let [before (:value (rt/read-sub! [:review/inline-sub] frame-id))
        res    (rt/dispatch-consequence! [:review/inline-event] {:frame frame-id})
        after  (:value (rt/read-sub! [:review/inline-sub] frame-id))]
    (is (not= :unknown-id (:reason res))
        "an inline-image event is not refused as unregistered")
    (is (not (false? (:dispatched? res)))
        "and the dispatch is not suppressed")
    (is (= (inc (or before 0)) after)
        "the handler actually ran — the frame's app-db advanced")))

(deftest store-only-id-is-refused-against-a-frame-that-excludes-it
  ;; The opposite mismatch of the same lookup. Validating against the
  ;; store would ACCEPT these and then read/dispatch into a frame whose
  ;; image has no such handler.
  (ensure-frame!)
  (testing "sub"
    (let [res (rt/read-sub! [:review/store-only-sub] frame-id)]
      (is (false? (:ok? res)))
      (is (= :unknown-id (:reason res)))
      (is (false? (:subscribed? res)) "refused WITHOUT subscribing")))
  (testing "event"
    (let [res (rt/dispatch-consequence! [:review/store-only-event] {:frame frame-id})]
      (is (= :unknown-id (:reason res)))
      (is (false? (:dispatched? res)) "refused WITHOUT dispatching"))))

(deftest suggestions-come-from-the-chosen-frame
  ;; `:nearest` must be drawn from the frame's candidate set, so every
  ;; suggestion is an id the caller can actually use here.
  (ensure-frame!)
  (let [res (rt/read-sub! [:review/inline-subb] frame-id)]
    (is (= :unknown-id (:reason res)))
    (is (= frame-id (:frame res)) "the envelope names the frame it checked")
    (is (some #{:review/inline-sub} (:nearest res))
        "the frame's own id is offered as the nearest match")
    (is (not (some #{:review/store-only-sub} (:nearest res)))
        "a store id this frame does not carry is never suggested")))

(deftest two-frames-do-not-bleed
  ;; A second frame with a DIFFERENT selected set must not accept the
  ;; first frame's image-only id.
  (ensure-frame!)
  (rf/make-frame
    {:id     :review/other
     :images [(rf/image
                {:id :review/other-image
                 :registrations {:reg-sub [[:review/other-sub (fn [db _] (:other db))]]}})]})
  (is (true? (:ok? (rt/read-sub! [:review/other-sub] :review/other)))
      "the other frame's own id validates there")
  (is (= :unknown-id (:reason (rt/read-sub! [:review/inline-sub] :review/other)))
      "and the first frame's image-only id does not")
  (is (true? (:ok? (rt/read-sub! [:review/inline-sub] frame-id)))
      "while it still validates in the frame that carries it"))
