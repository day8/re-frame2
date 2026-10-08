(ns re-frame.http-frame-scoped-cancellation-test
  "Managed-request cancellation and supersession are FRAME-SCOPED.

  Reusable app code mounts one stable `:request-id` (or one machine spec, so one
  actor address) in several isolated frames. The registry therefore keys its
  cancellation indexes on `[issuing-frame id]`: a supersession, a
  `:rf.http/managed-abort`, an actor destroy or a cleanup in one frame must never
  reach a sibling frame's live request. A cleanup that deletes a sibling's slot
  is as bad as an abort — the sibling's request stays live but unabortable."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.late-bind :as rf.late-bind]
            ;; The destroy-cascade cases drive the real machines teardown
            ;; (machines is a test-only dep of this artefact).
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; The one raw id both frames use.
(def ^:private shared-id :articles/load)

;; ---- supersession and managed-abort ----------------------------------------

(deftest reissue-supersedes-only-the-issuing-frames-prior-attempt
  (testing "frame B issuing the same raw id leaves frame A's request live, and
            reissuing in frame A supersedes exactly A's prior attempt while B's
            handle is untouched"
    (let [traces (atom [])
          live   #(rf.http.registry/lookup-in-flight % shared-id)]
      (rf/make-frame {:id :frame/a})
      (rf/make-frame {:id :frame/b})
      (rf/reg-event :reply/ignored (fn [_ _] {}))
      (rf/reg-event :articles/fetch
        (fn [_ _]
          {:fx [[:rf.http/managed {:request    {:url "http://example.invalid/articles"}
                                   :decode     :json
                                   :request-id shared-id
                                   :reply-to   [:reply/ignored]}]]}))
      ;; Never-completing futures hold every request in flight.
      (with-redefs [rf.http.transport-jvm/jvm-fetch (fn [_] (CompletableFuture.))]
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/a})
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/b})
        (let [b-handle (live :frame/b)]
          (is (every? some? [(live :frame/a) b-handle])
              "frame B's issuance did not supersede frame A's live request")
          (try
            (rf.trace.tooling/register-listener! ::reissue #(swap! traces conj %))
            (rf/dispatch-sync [:articles/fetch] {:frame :frame/a})
            (is (= [:frame/a]
                   (->> @traces
                        (filter #(= :rf.http/stale-suppressed (:operation %)))
                        (map (comp :frame :tags))))
                "exactly one superseded attempt, and it is frame A's")
            (is (identical? b-handle (live :frame/b))
                "frame B still holds the same handle")
            (finally
              (rf.trace.tooling/unregister-listener! ::reissue))))))))

(deftest cross-frame-probes-cannot-reach-a-sibling-frames-handle
  (testing "the managed-abort fx body resolves the raw id in the dispatching
            frame only"
    (let [seen (atom [])]
      (rf.http.registry/record-in-flight!
        :shared nil {:frame :frame/a :abort-fn #(swap! seen conj %)})
      (rf.http.handlers/managed-abort-handler {:frame :frame/b :event [:cancel]} :shared)
      (is (empty? @seen) "a frame-B managed-abort does not abort frame A's request")
      (rf.http.handlers/managed-abort-handler {:frame :frame/a :event [:cancel]} :shared)
      (is (= [:user] @seen) "frame A's own managed-abort aborts it with :reason :user"))))

;; ---- the frame-less seams ---------------------------------------------------

(deftest actor-destroy-any-frame-arity-preserves-the-hook-contract
  (testing "the documented 1-arg hook arity sweeps the actor address in every
            frame and clears each slot"
    (let [seen (atom [])]
      (doseq [frame-id [:frame/a :frame/b]]
        (rf.http.registry/record-in-flight!
          nil :worker/proc#1
          {:frame frame-id :abort-fn #(swap! seen conj [frame-id %])}))
      (rf.http.registry/abort-on-actor-destroy :worker/proc#1)
      (is (= #{[:frame/a :actor-destroyed] [:frame/b :actor-destroyed]} (set @seen)))
      (is (empty? (rf.http.managed/actor-in-flight-snapshot))))))

(deftest resources-abort-by-frame-qualified-token-still-works
  (testing "the any-frame abort-by-id seam resources reaches through
            `:http/abort-in-flight!` resolves its frame-qualified token, which
            is registered under the issuing frame"
    (let [seen  (atom [])
          token [:rf.req :frame/a :work-1]]
      (rf.http.registry/record-in-flight!
        token nil {:frame :frame/a :abort-fn #(swap! seen conj %)})
      (is (true? (rf.http.registry/abort-in-flight! token :resource-superseded)))
      (is (= [:resource-superseded] @seen)))))

;; ---- frame-lifecycle sweeps and cleanup ------------------------------------

(deftest frame-lifecycle-sweeps-reap-siblings-that-reused-one-id
  (testing "frame destroy and epoch restore abort every handle the selected
            frame owns, including one whose raw id a sibling frame shares, and
            nothing of the sibling's"
    (let [seen (atom [])
          mk   (fn [frame-id request-id]
                 (rf.http.registry/record-in-flight!
                   request-id nil
                   {:frame frame-id :abort-fn #(swap! seen conj [frame-id request-id %])}))]
      (mk :frame/a shared-id)
      (mk :frame/a :articles/detail)
      (mk :frame/b shared-id)
      (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/a)
      (is (= #{[:frame/a shared-id :frame-destroyed]
               [:frame/a :articles/detail :frame-destroyed]}
             (set @seen)))
      (reset! seen [])
      (rf.http.registry/abort-in-flight-for-frame! :frame/b)
      (is (= [[:frame/b shared-id :epoch-restored]] @seen)))))

(deftest frame-exact-clear-walks-the-actor-index-too
  (testing "`clear-in-flight-in-frame!` also drops the handle from that frame's
            actor slot, and leaves a same-named actor in a sibling frame alone"
    (let [actor-id :worker/proc
          slot     #(get (rf.http.registry/actor-in-flight-snapshot %) actor-id)]
      (doseq [frame-id [:frame/a :frame/b]]
        (rf.http.registry/record-in-flight!
          shared-id actor-id {:frame frame-id :abort-fn (fn [_] nil)}))
      (is (= 1 (count (slot :frame/a))))
      (rf.http.registry/clear-in-flight-in-frame! :frame/a shared-id)
      (is (nil? (slot :frame/a)) "frame A's actor slot is emptied, not stranded")
      (is (= 1 (count (slot :frame/b))) "the same-named actor in frame B keeps its handle"))))

;; ---- the destroy cascades thread the destroying frame ----------------------
;;
;; One machine spec mounted in two frames spawns actors under the SAME address in
;; both (the spawn counter is per frame), so every destroy entry point must pass
;; its frame to `:http/abort-on-actor-destroy`.

(def ^:private actor-address :worker/proc#1)

(defn- register-two-frame-actor-app! []
  (rf/make-frame {:id :frame/a})
  (rf/make-frame {:id :frame/b})
  (rf/reg-machine :worker/proc {:initial :running :data {} :states {:running {}}})
  (rf/reg-event :worker/spawn
    (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :worker/proc
                                        :id-prefix  :worker/proc}]]}))
  (rf/reg-event :worker/kill
    (fn [_ [_ actor-id]] {:fx [[:rf.machine/destroy actor-id]]}))
  (doseq [frame-id [:frame/a :frame/b]]
    (rf/dispatch-sync [:worker/spawn] {:frame frame-id})))

(defn- seed-actor-handle!
  "An anonymous actor-owned handle, so only the actor index selects it."
  [seen frame-id]
  (rf.http.registry/record-in-flight!
    nil actor-address
    {:frame frame-id :abort-fn (fn [reason] (swap! seen conj [frame-id reason]))}))

(defn- actor-live? [frame-id]
  (= 1 (count (get (rf.http.registry/actor-in-flight-snapshot frame-id) actor-address))))

(deftest imperative-actor-destroy-aborts-only-the-destroying-frames-http
  (testing "`[:rf.machine/destroy <addr>]` in frame A aborts only A's actor-owned
            HTTP; the identically-addressed actor in frame B keeps its request"
    (register-two-frame-actor-app!)
    (let [seen (atom [])]
      (seed-actor-handle! seen :frame/a)
      (seed-actor-handle! seen :frame/b)
      (rf/dispatch-sync [:worker/kill actor-address] {:frame :frame/a})
      (is (= [[:frame/a :actor-destroyed]] @seen))
      (is (actor-live? :frame/b)))))

(deftest machines-absent-frame-destroy-fallback-is-frame-exact
  (testing "core's `destroy-frame!` fallback, taken when the machines artefact
            is absent, passes the destroyed frame to the hook"
    (register-two-frame-actor-app!)
    (let [seen (atom [])
          orig (rf.late-bind/get-fn :machines/teardown-on-frame-destroy!)]
      (seed-actor-handle! seen :frame/a)
      (seed-actor-handle! seen :frame/b)
      (try
        (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! nil)
        (rf/destroy-frame! :frame/a)
        (finally
          (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! orig)))
      (is (= #{:frame/a} (set (map first @seen))))
      (is (actor-live? :frame/b)))))

;; ---- the two-index publication window --------------------------------------
;;
;; `record-in-flight!` publishes an actor-owned, named handle to the request
;; index and then the actor index, in two swaps. A cleanup or destroy landing
;; between them must neither leave a ghost actor slot nor reach a sibling frame.
;; An atom's watches run on the swapping thread before `swap!` returns, so a
;; watch on the request index runs its callback strictly between the two
;; publications — the interleaving is decided, not raced.

(def ^:private publication-actor :worker/publication)

(defn- actor-slot-count [frame-id]
  (count (get (rf.http.registry/actor-in-flight-snapshot frame-id) publication-actor)))

(defn- seed-named-actor-handle!
  "A named actor-owned handle, visible in both indexes once published."
  [seen frame-id]
  (rf.http.registry/record-in-flight!
    shared-id publication-actor
    {:frame frame-id :abort-fn (fn [reason] (swap! seen conj [frame-id reason]))}))

(defn- record-actor-handle-with-midpoint!
  "Publish a named actor-owned handle in `frame-id`, running `at-midpoint!`
  between the two publications. Its abort-fn makes the cleanup the transport
  makes inside the window, where the handle cell is still nil."
  [frame-id seen at-midpoint!]
  (let [slot [frame-id shared-id]]
    (add-watch rf.http.registry/in-flight ::publication-midpoint
               (fn [_ _ before after]
                 (when (and (nil? (get before slot)) (some? (get after slot)))
                   ;; Once only: the abort's own cleanup swaps this atom again.
                   (remove-watch rf.http.registry/in-flight ::publication-midpoint)
                   (at-midpoint!))))
    (try
      (rf.http.registry/record-in-flight!
        shared-id publication-actor
        {:frame    frame-id
         :abort-fn (fn [reason]
                     (swap! seen conj [frame-id reason])
                     (rf.http.registry/clear-in-flight! frame-id shared-id nil))})
      (finally
        (remove-watch rf.http.registry/in-flight ::publication-midpoint)))))

(deftest the-publication-window-reconcile-is-frame-exact
  (testing "an abort inside frame A's publication window leaves A no ghost actor
            slot, while frame B — live under the same request-id and actor
            address — keeps both slots"
    (let [seen (atom [])]
      (seed-named-actor-handle! seen :frame/b)
      (record-actor-handle-with-midpoint!
        :frame/a seen
        #(rf.http.registry/abort-in-flight-in-frame! :frame/a shared-id :user))
      (is (= [[:frame/a :user]] @seen))
      (is (some? (get (rf.http.registry/in-flight-snapshot :frame/b) shared-id)))
      (is (= 1 (actor-slot-count :frame/b)))
      (is (zero? (actor-slot-count :frame/a))))))

(deftest actor-destroy-inside-the-publication-window-is-frame-exact
  (testing "an actor destroy inside frame A's publication window reaches A's
            handle through the request index, and only A's"
    (let [seen (atom [])]
      (seed-named-actor-handle! seen :frame/b)
      (record-actor-handle-with-midpoint!
        :frame/a seen
        #(rf.http.registry/abort-on-actor-destroy :frame/a publication-actor))
      (is (= [[:frame/a :actor-destroyed]] @seen)))))
