(ns re-frame.http-frame-scoped-cancellation-test
  "rf2-o8ek — managed-request cancellation and supersession are FRAME-SCOPED.

  Frames are isolated contexts (Spec 002; `docs/core/frames.md`), and Spec 014
  §Frame awareness promises multi-frame apps \"work without extra ceremony\".
  But the in-flight registry keyed both of its cancellation indexes on the RAW
  caller-supplied id — `request-id -> handle` and `actor-id -> handles` — while
  the frame rode along only as a stamp on the VALUE. Reusable app code
  naturally reuses an ordinary stable id (`:request-id :articles/load`), so two
  isolated frames running the same code cross-cancelled at the lifetime
  boundary:

   - frame B's issuance SUPERSEDED frame A's live request and suppressed its
     reply;
   - `[:rf.http/managed-abort :articles/load]` dispatched in B aborted A's
     request even when B owned none;
   - frame B's FIRST issuance number was allocated from frame A's counter,
     perturbing B's `:work/id` for a supersession that never happened to it;
   - destroying an actor in one frame walked the same actor-id slot holding a
     sibling frame's handles.

  The repair makes the ISSUING FRAME part of the internal key (`[frame-id id]`)
  while the caller's raw `:request-id` stays the public correlation value
  echoed in replies and traces. The public `:rf.http/managed` args map and the
  `:rf.http/managed-abort` effect shape are unchanged.

  Determinism: the end-to-end cases hold every request open with a latched
  localhost `HttpServer`, so \"still live\" and \"aborted\" are decided by the
  registry rather than by a race with the network. Every positive signal is
  polled; the one non-event (no reply delivered into the wrong frame) uses the
  bounded-window idiom the sibling frame-destroy test established."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.late-bind :as rf.late-bind]
            ;; rf2-wjfm — the destroy-cascade cases below drive the REAL
            ;; machines teardown. machines is a test-only dep of this artefact
            ;; (see deps.edn) precisely so cancellation-cascade tests can.
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent CountDownLatch TimeUnit]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; The one raw id BOTH frames use — the whole point of the bead is that an app
;; may write this once in reusable code and mount it in N isolated frames.
(def ^:private shared-id :articles/load)

;; ---- harness ---------------------------------------------------------------

(defn- start-blocking-server!
  "A localhost server that holds every request open until `latch` counts down,
  then answers 200 with a JSON body. Holding the request open is what makes
  \"still in flight\" a decided fact rather than a timing guess."
  [^CountDownLatch latch body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex]
                          (.await latch 30 TimeUnit/SECONDS)
                          (let [bs (.getBytes (str body) "UTF-8")]
                            (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
                            (try
                              (.sendResponseHeaders ex 200 (long (count bs)))
                              (with-open [os (.getResponseBody ex)]
                                (.write os bs))
                              (catch Throwable _ nil)))
                          nil))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [^HttpServer server]}]
  (.stop server 0))

(defn- await-condition!
  ([pred] (await-condition! pred 5000))
  ([pred timeout-ms]
   (rf.test-support/poll-until pred {:timeout-ms  timeout-ms
                                  :interval-ms 10
                                  :label       "http-frame-scoped-cancellation condition"})
   true))

(defn- register-two-frame-app!
  "Register ONE set of handlers and mount it in two isolated frames — the exact
  shape the bead names (documented per-request SSR frames, Story/test variants,
  side-by-side mounts). `replies` collects every reply envelope; each carries
  `:rf.frame/id`, so one shared recorder never loses which frame it landed in."
  [port replies]
  (rf/make-frame {:id :frame/a :doc "isolated frame A"})
  (rf/make-frame {:id :frame/b :doc "isolated frame B"})
  (rf/reg-event :reply/recorder
    (fn [_ [_ payload]] (swap! replies conj payload) {}))
  (rf/reg-event :articles/fetch
    (fn [_ _]
      {:fx [[:rf.http/managed
             {:request    {:url (str "http://127.0.0.1:" port "/articles") :method :get}
              :decode     :json
              ;; ONE ordinary stable id, written once, reused in both frames.
              :request-id shared-id
              :on-success [:reply/recorder]
              :on-failure [:reply/recorder]}]]}))
  (rf/reg-event :articles/cancel
    (fn [_ _] {:fx [[:rf.http/managed-abort shared-id]]})))

(defn- live-in? [frame-id]
  (some? (rf.http.registry/lookup-in-flight frame-id shared-id)))

(defn- stale-rows [traces]
  (filter #(= :rf.http/stale-suppressed (:operation %)) traces))

;; ---- AC 1 — two frames, one raw id, both stay live -------------------------

(deftest same-request-id-in-two-frames-does-not-cross-supersede
  (testing "rf2-o8ek — two isolated frames issuing overlapping requests under
            the SAME raw :request-id both remain live; neither is superseded
            merely because the sibling reused the id, no stale-suppressed row
            fires, and each eventually completes into ITS OWN frame echoing the
            caller's original raw :request-id (never an internal compound key)"
    (let [latch   (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch "{\"ok\":true}")
          replies (atom [])
          traces  (atom [])]
      (try
        (rf.trace/register-listener! ::cross (fn [ev] (swap! traces conj ev)))
        (register-two-frame-app! port replies)
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/a})
        (await-condition! #(live-in? :frame/a))
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/b})
        (await-condition! #(live-in? :frame/b))
        ;; THE regression: pre-fix, frame B's issuance superseded frame A's
        ;; handle out of the one raw-id slot, so this read was nil.
        (is (live-in? :frame/a)
            "frame A's request is STILL live after frame B issued the same raw id")
        (is (live-in? :frame/b)
            "frame B's request is live")
        (is (contains? (rf.http.managed/in-flight-snapshot :frame/a) shared-id)
            "the frame-precise snapshot shows the id under frame A")
        (is (contains? (rf.http.managed/in-flight-snapshot :frame/b) shared-id)
            "and independently under frame B")
        (is (empty? (stale-rows @traces))
            "NO stale-suppressed row fired — nothing was superseded")
        (is (empty? @replies)
            "neither frame's reply has been suppressed or delivered early")
        ;; Release both requests: each completes into its own frame.
        (.countDown latch)
        (await-condition! #(= 2 (count @replies)))
        (is (= #{:frame/a :frame/b} (set (map :rf.frame/id @replies)))
            "each reply landed in the frame that issued it")
        (is (every? #(= :ok (:status %)) @replies)
            "both requests completed successfully — neither was cancelled")
        (is (= #{shared-id} (set (map #(get-in % [:correlation :request-id]) @replies)))
            "each reply echoes the CALLER'S raw :request-id, not a compound key")
        (finally
          (rf.trace/unregister-listener! ::cross)
          (.countDown latch)
          (stop-server! srv)
          (rf.http.managed/clear-all-in-flight!))))))

;; ---- AC 2 — reissue supersedes exactly the issuing frame's prior attempt ---

(deftest reissue-supersedes-only-the-issuing-frames-prior-attempt
  (testing "rf2-o8ek — reissuing the id inside frame A still supersedes exactly
            A's prior attempt and emits ONE stale-suppressed row for A carrying
            distinct carried/current work-ids, while frame B's handle — holding
            the identical raw id — is untouched and completes normally"
    (let [latch   (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch "{\"ok\":true}")
          replies (atom [])
          traces  (atom [])]
      (try
        (register-two-frame-app! port replies)
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/a})
        (await-condition! #(live-in? :frame/a))
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/b})
        (await-condition! #(live-in? :frame/b))
        (let [b-handle (rf.http.registry/lookup-in-flight :frame/b shared-id)]
          ;; Listen only from here, so the rows we count belong to the reissue.
          (rf.trace/register-listener! ::reissue (fn [ev] (swap! traces conj ev)))
          (rf/dispatch-sync [:articles/fetch] {:frame :frame/a})
          (await-condition! #(seq (stale-rows @traces)))
          (let [rows (stale-rows @traces)
                row  (first rows)
                tags (:tags row)]
            (is (= 1 (count rows))
                "exactly ONE stale-suppressed row — frame B's sibling was not superseded")
            (is (= :frame/a (:frame tags))
                "the suppressed attempt belongs to frame A, the reissuing frame")
            (is (= :stale (:rf.reply/status tags)))
            (is (= :suppressed (:rf.reply/work-status tags)))
            (is (not= (:rf.reply/carried tags) (:rf.reply/current tags))
                "carried (superseded) and current (superseding) work-ids stay =-distinct"))
          (is (identical? b-handle (rf.http.registry/lookup-in-flight :frame/b shared-id))
              "frame B still holds the SAME handle — byte-for-byte untouched")
          (is (live-in? :frame/a)
              "frame A's fresh attempt now owns A's slot"))
        (.countDown latch)
        ;; Two live requests remain (A's successor and B's original); the
        ;; superseded attempt delivers nothing.
        (await-condition! #(= 2 (count @replies)))
        (Thread/sleep 150)
        (is (= 2 (count @replies))
            "the superseded attempt delivered NO app reply (supersession suppresses)")
        (is (= #{:frame/a :frame/b} (set (map :rf.frame/id @replies)))
            "one reply per frame")
        (finally
          (rf.trace/unregister-listener! ::reissue)
          (.countDown latch)
          (stop-server! srv)
          (rf.http.managed/clear-all-in-flight!))))))

;; ---- AC 3 — :rf.http/managed-abort is frame-scoped -------------------------

(deftest managed-abort-aborts-only-the-dispatching-frames-request
  (testing "rf2-o8ek — [:rf.http/managed-abort id] dispatched in frame A aborts
            ONLY A's request; the identical id in frame B stays live and
            completes into B. Pre-fix the abort resolved the raw id globally, so
            a frame owning no request could cancel its sibling's"
    (let [latch   (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch "{\"ok\":true}")
          replies (atom [])]
      (try
        (register-two-frame-app! port replies)
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/a})
        (await-condition! #(live-in? :frame/a))
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/b})
        (await-condition! #(live-in? :frame/b))
        (rf/dispatch-sync [:articles/cancel] {:frame :frame/a})
        (await-condition! #(not (live-in? :frame/a)))
        (is (not (live-in? :frame/a))
            "frame A's request was aborted by its own frame's managed-abort")
        (is (live-in? :frame/b)
            "frame B's identically-named request is UNTOUCHED and still live")
        (await-condition! #(= 1 (count @replies)))
        (let [cancelled (first @replies)]
          (is (= :cancelled (:status cancelled)) "A received the cancellation reply")
          (is (= :frame/a (:rf.frame/id cancelled)) "…in frame A"))
        (.countDown latch)
        (await-condition! #(= 2 (count @replies)))
        (let [completed (first (filter #(= :ok (:status %)) @replies))]
          (is (some? completed) "frame B's request completed normally")
          (is (= :frame/b (:rf.frame/id completed)) "…into frame B"))
        (finally
          (.countDown latch)
          (stop-server! srv)
          (rf.http.managed/clear-all-in-flight!))))))

(deftest managed-abort-frame-scoping-is-symmetric
  (testing "rf2-o8ek — the symmetric case: aborting in frame B leaves frame A's
            identically-named request live"
    (let [latch   (CountDownLatch. 1)
          {:keys [port] :as srv} (start-blocking-server! latch "{\"ok\":true}")
          replies (atom [])]
      (try
        (register-two-frame-app! port replies)
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/a})
        (await-condition! #(live-in? :frame/a))
        (rf/dispatch-sync [:articles/fetch] {:frame :frame/b})
        (await-condition! #(live-in? :frame/b))
        (rf/dispatch-sync [:articles/cancel] {:frame :frame/b})
        (await-condition! #(not (live-in? :frame/b)))
        (is (live-in? :frame/a) "frame A remains live")
        (is (not (live-in? :frame/b)) "frame B aborted its own request")
        (finally
          (.countDown latch)
          (stop-server! srv)
          (rf.http.managed/clear-all-in-flight!))))))

;; ---- AC 7 — the bead's two reproduction probes, at their production seams --

(deftest reproduction-probes-no-longer-reach-across-frames
  (testing "rf2-o8ek — the two probes the bead recorded (a frame-B supersede
            selector, and managed-abort-handler carrying frame B's context) no
            longer resolve or abort frame A's registered handle, while the
            SAME-frame selector still does. This is the registry/handler-level
            statement of the end-to-end cases above"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])]
      (rf.http.registry/record-in-flight!
        :shared nil {:frame    :frame/a
                     :abort-fn #(swap! seen conj [:frame/a %])})
      ;; probe 1 — supersede! driven by frame B
      (is (nil? (rf.http.registry/supersede! :frame/b :shared))
          "a frame-B supersede finds NOTHING under frame A's handle")
      (is (empty? @seen) "…and fired no abort")
      (is (some? (rf.http.registry/lookup-in-flight :frame/a :shared))
          "frame A's handle survives a sibling frame's supersede")
      ;; probe 2 — the real managed-abort fx body carrying frame B's ctx
      (rf.http.handlers/managed-abort-handler {:frame :frame/b :event [:cancel]} :shared)
      (is (empty? @seen) "a frame-B managed-abort does NOT abort frame A's request")
      (is (some? (rf.http.registry/lookup-in-flight :frame/a :shared))
          "frame A's handle is still registered")
      ;; the same selectors, correctly scoped, still work
      (rf.http.handlers/managed-abort-handler {:frame :frame/a :event [:cancel]} :shared)
      (is (= [[:frame/a :user]] @seen)
          "frame A's OWN managed-abort aborts frame A's request with :reason :user"))
    (rf.http.managed/clear-all-in-flight!)))

;; ---- AC 4 — actor-destroy cancellation -------------------------------------

(deftest actor-destroy-is-frame-scoped-when-the-frame-is-known
  (testing "rf2-o8ek — the same generated/fixed actor-id in two frames keeps
            INDEPENDENT registry slots, and the frame-bearing arity of
            abort-on-actor-destroy aborts only the named frame's HTTP. This is
            structural (the frame is part of the key) rather than dependent on
            an actor-naming convention"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])
          mk   (fn [frame-id]
                 (rf.http.registry/record-in-flight!
                   nil :worker/proc#1
                   {:frame    frame-id
                    :url      "http://x/y"
                    :abort-fn #(swap! seen conj [frame-id %])}))]
      (mk :frame/a)
      (mk :frame/b)
      (is (= 1 (count (get (rf.http.managed/actor-in-flight-snapshot :frame/a) :worker/proc#1)))
          "frame A's actor slot holds exactly its own handle")
      (is (= 1 (count (get (rf.http.managed/actor-in-flight-snapshot :frame/b) :worker/proc#1)))
          "frame B's same-named actor keeps an INDEPENDENT slot")
      (rf.http.registry/abort-on-actor-destroy :frame/a :worker/proc#1)
      (is (= [[:frame/a :actor-destroyed]] @seen)
          "destroying frame A's actor aborted ONLY A-owned HTTP")
      (is (= 1 (count (get (rf.http.managed/actor-in-flight-snapshot :frame/b) :worker/proc#1)))
          "frame B's actor request is still live"))
    (rf.http.managed/clear-all-in-flight!)))

(deftest actor-destroy-any-frame-arity-preserves-the-hook-contract
  (testing "rf2-o8ek — the 1-arg arity is the ANY-FRAME sweep: it matches the
            raw actor-id in EVERY frame, keeping the pre-rf2-o8ek behaviour
            byte-for-byte. rf2-wjfm threaded the frame at both destroy-cascade
            callers, so NO in-repo destroy path takes this arity any more; it
            remains the documented seam for a caller that genuinely holds an
            address and no frame. Pinned here because the behaviour is a
            contract, not an accident — the cases below prove the cascade does
            not use it"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])
          mk   (fn [frame-id]
                 (rf.http.registry/record-in-flight!
                   nil :worker/proc#1
                   {:frame frame-id :abort-fn #(swap! seen conj [frame-id %])}))]
      (mk :frame/a)
      (mk :frame/b)
      (rf.http.registry/abort-on-actor-destroy :worker/proc#1)
      (is (= #{[:frame/a :actor-destroyed] [:frame/b :actor-destroyed]} (set @seen))
          "the any-frame arity sweeps every frame's slot for that actor-id")
      (is (empty? (rf.http.managed/actor-in-flight-snapshot))
          "and clears both slots"))
    (rf.http.managed/clear-all-in-flight!)))

;; ---- AC 5 — frame-lifecycle sweeps still reap every handle they own --------

(deftest frame-lifecycle-sweeps-reap-siblings-that-reused-one-id
  (testing "rf2-o8ek — frame destroy and epoch restore still abort EVERY handle
            owned by the selected frame, including two sibling frames that
            reused a request id. The sweeps filter on the handle's :frame stamp,
            so frame-scoped keying neither hides a handle from them nor lets one
            sweep reach a sibling"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])
          ;; Production abort-fns clear their OWN slot through the 2-arg,
          ;; identity-conditional `clear-in-flight!`, reading the handle back
          ;; out of a cell published just after registration (the transport's
          ;; `@handle-cell` / `@handle-holder` idiom). Model that exactly: the
          ;; 1-arg clear is the ANY-FRAME seam and would take the sibling
          ;; frame's slot with it, which is the very reach-through under test.
          mk   (fn [frame-id request-id]
                 (let [cell   (atom nil)
                       handle (rf.http.registry/seed-in-flight-for-test!
                                request-id nil
                                {:abort-fn   (fn [reason]
                                               (swap! seen conj [frame-id request-id reason])
                                               (rf.http.registry/clear-in-flight! request-id @cell))
                                 :request-id request-id
                                 :url        "http://x/y"
                                 :frame      frame-id})]
                   (reset! cell handle)
                   handle))]
      ;; Both frames use the SAME raw id, plus one extra in A.
      (mk :frame/a shared-id)
      (mk :frame/a :articles/detail)
      (mk :frame/b shared-id)
      (rf.http.registry/abort-in-flight-on-frame-destroyed! :frame/a)
      (is (= #{[:frame/a shared-id :frame-destroyed]
               [:frame/a :articles/detail :frame-destroyed]}
             (set @seen))
          "BOTH of frame A's handles fired — including the one whose id frame B shares")
      (is (some? (rf.http.registry/lookup-in-flight :frame/b shared-id))
          "frame B's identically-named request survives A's destroy")
      (reset! seen [])
      (rf.http.registry/abort-in-flight-for-frame! :frame/b)
      (is (= [[:frame/b shared-id :epoch-restored]] @seen)
          "the epoch-restore sweep then reaps frame B's own handle")
      (is (nil? (rf.http.registry/lookup-in-flight :frame/b shared-id))))
    (rf.http.managed/clear-all-in-flight!)))

;; ---- the any-frame seam resources reaches through is unchanged -------------

(deftest resources-abort-by-frame-qualified-token-still-works
  (testing "rf2-o8ek — resources aborts a managed request through the
            :http/abort-in-flight! late-bind hook using its already-frame-
            qualified token ([:rf.req frame-id work-id], Spec 016). That seam
            carries no frame argument, so it stays ANY-FRAME — and because the
            token embeds the frame, at most one frame can ever match"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen  (atom [])
          token [:rf.req :frame/a :work-1]]
      (rf.http.registry/record-in-flight!
        token nil {:frame :frame/a :abort-fn #(swap! seen conj %)})
      (is (true? (rf.http.registry/abort-in-flight! token :resource-superseded))
          "the any-frame seam still resolves a frame-qualified token")
      (is (= [:resource-superseded] @seen)))
    (rf.http.managed/clear-all-in-flight!)))

;; ---- rf2-o8ek AUDIT — the CLEANUP half of the same isolation ---------------
;;
;; The merged repair frame-scoped the registry KEYS, which isolated the abort
;; and supersede paths. The post-merge audit found two CLEANUP paths that still
;; escaped that scope, and both looked migrated:
;;
;;  1. a seeded-handle demo that gained a `:frame` stamp but kept the ANY-FRAME
;;     one-arg `clear-in-flight!` in its abort closure;
;;  2. `clear-in-flight!`'s nil-handle fallback, taken inside the publication
;;     window between `record-in-flight!` and the `reset!` of the cell the
;;     abort-fn reads. Both live-fetch hosts acknowledge that window; on the JVM
;;     another thread can fire the just-published abort-fn while it is open.
;;
;; Neither is an abort path, which is why frame-scoped keys did not cover them.
;; The standing law they now witness: A CLEANUP PATH THAT POSSESSES AN ISSUING
;; FRAME MUST BE FRAME-EXACT. Cleaning a sibling's slot is not a lesser fault
;; than aborting it — it leaves a LIVE request unregistered, so nothing can
;; abort it afterwards and its UI can sit loading forever.

(defn- seed-two-frames-under-one-id!
  "Register a handle in frame A and frame B under the SAME raw `shared-id`,
  each recording its own aborts. Returns the aborts atom. Neither closure
  cleans up — these tests are about which SLOTS a cleanup call reaches, so the
  cleanup under test is always made explicitly by the test body."
  []
  (let [seen (atom [])]
    (doseq [frame-id [:frame/a :frame/b]]
      (rf.http.registry/record-in-flight!
        shared-id nil
        {:frame    frame-id
         :url      "http://x/articles"
         :abort-fn (fn [reason] (swap! seen conj [frame-id reason]))}))
    seen))

(deftest seeded-demo-abort-closure-clears-only-its-own-frames-slot
  (testing "rf2-o8ek audit (1) — a seeded-handle demo's abort closure holds the
            frame it carried in, but not the handle (the closure is built as
            part of the map `record-in-flight!` is still consuming). It must
            clean through `clear-in-flight-in-frame!`. The one-arg form it
            reads as a shorthand for is an ANY-FRAME sweep: with the demo
            mounted in two frames under one stable id, cancelling A deletes
            BOTH slots and B's live request becomes unregistered/unabortable"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])
          ;; The example's exact shape: `frame` and `request-id` are lexical,
          ;; the handle is not.
          mk   (fn [frame-id]
                 (rf.http.registry/record-in-flight!
                   shared-id nil
                   {:frame    frame-id
                    :url      "api/long"
                    :abort-fn (fn [reason]
                                (rf.http.registry/clear-in-flight-in-frame! frame-id shared-id)
                                (swap! seen conj [frame-id reason]))}))]
      (mk :frame/a)
      (mk :frame/b)
      ;; Cancel in frame A through the production frame-scoped abort seam —
      ;; what `[:rf.http/managed-abort id]` dispatched in A resolves to.
      (is (true? (rf.http.registry/abort-in-flight-in-frame! :frame/a shared-id :user))
          "frame A's own handle is found and fired")
      (is (= [[:frame/a :user]] @seen)
          "only frame A's closure ran")
      (is (nil? (rf.http.registry/lookup-in-flight :frame/a shared-id))
          "frame A's slot is cleaned up by its own closure")
      (is (some? (rf.http.registry/lookup-in-flight :frame/b shared-id))
          "frame B's identically-named LIVE request is still registered — the audit's failure was here, and it is silent: no abort fires in B, its slot simply vanishes and nothing can cancel it afterwards")
      (is (true? (rf.http.registry/abort-in-flight-in-frame! :frame/b shared-id :user))
          "and B remains abortable, which is the consequence that was lost")
      (is (= [[:frame/a :user] [:frame/b :user]] @seen)))
    (rf.http.managed/clear-all-in-flight!)))

(deftest pre-publication-clear-cannot-reach-a-sibling-frame
  (testing "rf2-o8ek audit (2) — the transport's cleanup runs with a nil handle
            for as long as the publication window is open. The two-arg form has
            no frame to be exact about and falls back to the ANY-FRAME sweep, so
            every transport site passes the ctx frame through the three-arity.
            A nil handle from frame A must clear frame A's own slot and leave an
            already-live sibling's alone. The original reasoning — that the
            window precedes any successor — covers same-frame succession only"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (seed-two-frames-under-one-id!)]
      ;; Frame A's abort-fn fires while its cell is still nil.
      (rf.http.registry/clear-in-flight! :frame/a shared-id nil)
      (is (nil? (rf.http.registry/lookup-in-flight :frame/a shared-id))
          "frame A's own slot IS cleared — the window still must not leak a slot")
      (is (some? (rf.http.registry/lookup-in-flight :frame/b shared-id))
          "frame B's already-live request under the same raw id survives")
      (is (= [] @seen)
          "and no abort-fn fired in either frame — a clear is not an abort"))
    (rf.http.managed/clear-all-in-flight!))

  (testing "the same call with a PUBLISHED handle is unchanged: identity-conditional, so a same-id successor within the frame is not evicted"
    (rf.http.managed/clear-all-in-flight!)
    (let [old (rf.http.registry/record-in-flight!
                shared-id nil {:frame :frame/a :abort-fn (fn [_] nil) :url "old"})
          new (rf.http.registry/record-in-flight!
                shared-id nil {:frame :frame/a :abort-fn (fn [_] nil) :url "new"})]
      (rf.http.registry/clear-in-flight! :frame/a shared-id old)
      (is (identical? new (rf.http.registry/lookup-in-flight :frame/a shared-id))
          "the old attempt's clear no-ops against the successor that took the slot"))
    (rf.http.managed/clear-all-in-flight!)))

(deftest frame-exact-clear-walks-the-actor-index-too
  (testing "rf2-o8ek audit — `clear-in-flight-in-frame!` is a full cleanup, not
            a request-index-only one: it recovers the handle it just removed and
            drops it from that frame's actor slot by identity, leaving a
            same-named actor in a sibling frame untouched. A half-cleanup here
            would strand an actor-index entry, which is the leak class
            rf2-lz7se / rf2-meq28 closed on the handle-bearing paths"
    (rf.http.managed/clear-all-in-flight!)
    (let [actor-id :worker/proc]
      (doseq [frame-id [:frame/a :frame/b]]
        (rf.http.registry/record-in-flight!
          shared-id actor-id
          {:frame frame-id :abort-fn (fn [_] nil) :url "http://x/y"}))
      (is (= 1 (count (get (rf.http.registry/actor-in-flight-snapshot :frame/a) actor-id))))
      (rf.http.registry/clear-in-flight-in-frame! :frame/a shared-id)
      (is (nil? (get (rf.http.registry/actor-in-flight-snapshot :frame/a) actor-id))
          "frame A's actor slot is emptied, not stranded")
      (is (= 1 (count (get (rf.http.registry/actor-in-flight-snapshot :frame/b) actor-id)))
          "the same-named actor in frame B keeps its handle"))
    (rf.http.managed/clear-all-in-flight!))

  (testing "a nil request-id is a documented no-op — an anonymous request is indexed only in actor-in-flight, reachable only by handle identity"
    (rf.http.managed/clear-all-in-flight!)
    (let [handle (rf.http.registry/record-in-flight!
                   nil :worker/anon {:frame :frame/a :abort-fn (fn [_] nil) :url "u"})]
      (rf.http.registry/clear-in-flight-in-frame! :frame/a nil)
      (is (= 1 (count (get (rf.http.registry/actor-in-flight-snapshot :frame/a) :worker/anon)))
          "the anonymous handle is untouched — the two-arg handle form owns it")
      (rf.http.registry/clear-in-flight! nil handle)
      (is (nil? (get (rf.http.registry/actor-in-flight-snapshot :frame/a) :worker/anon))))
    (rf.http.managed/clear-all-in-flight!)))

;; ---- rf2-wjfm — the DESTROY CASCADE threads the destroying frame ----------
;;
;; rf2-o8ek keyed the actor index on `[frame-id actor-id]` and gave
;; `abort-on-actor-destroy` a frame-bearing 2-arity, but left the hook's CALLERS
;; passing an address alone, so every destroy still took the ANY-FRAME arity.
;;
;; A spawned actor's address is frame-LOCAL. One machine spec mounted in two
;; isolated frames — reusable app code, documented per-request SSR frames, Story
;; variants, side-by-side mounts — spawns actors under the SAME address in both.
;; Destroying frame A's actor therefore swept frame B's live requests: the exact
;; isolation failure this campaign exists to close, reached through the DESTROY
;; path instead of the abort path. Every pre-existing actor-destroy test uses one
;; frame, which is why a green suite never saw it.
;;
;; These cases drive the REAL destroy entry points against the REAL registry:
;; the machines cascade (imperative `[:rf.machine/destroy …]`, and `destroy-frame!`
;; through `teardown-on-frame-destroy!`), and core's machines-ABSENT
;; `destroy-frame!` fallback — the two call sites the bead named, plus the
;; final-state/singleton paths that funnel through the same helper.

(def ^:private actor-address
  "The address the shared spec resolves to in EVERY frame — the spawn counter is
  per-frame, so identical code yields an identical address. Asserted, not assumed."
  :worker/proc#1)

(defn- register-two-frame-actor-app!
  "Register ONE machine spec plus its spawn/destroy events, and mount it in two
  isolated frames. The whole point is that the app author writes this once."
  []
  (rf/make-frame {:id :frame/a :doc "isolated frame A"})
  (rf/make-frame {:id :frame/b :doc "isolated frame B"})
  (rf/reg-machine :worker/proc {:initial :running :data {} :states {:running {}}})
  (rf/reg-event :worker/spawn
    (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id :worker/proc
                                        :id-prefix  :worker/proc}]]}))
  (rf/reg-event :worker/kill
    (fn [_ [_ actor-id]] {:fx [[:rf.machine/destroy actor-id]]}))
  (doseq [frame-id [:frame/a :frame/b]]
    (rf/dispatch-sync [:worker/spawn] {:frame frame-id})))

(defn- actor-snapshot [frame-id actor-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame-id))
          [:rf.runtime/machines :snapshots actor-id]))

(defn- seed-actor-handle!
  "Register one actor-owned in-flight handle in `frame-id`, recording its aborts
  into `seen`. Anonymous (nil request-id), so ONLY the actor index selects it —
  the shape an actor-issued request takes when the app supplies no `:request-id`,
  and the shape that isolates this test to the actor-destroy path."
  [seen frame-id actor-id]
  (rf.http.registry/record-in-flight!
    nil actor-id
    {:frame    frame-id
     :url      "http://x/actor-work"
     :abort-fn (fn [reason] (swap! seen conj [frame-id reason]))}))

(defn- actor-live? [frame-id actor-id]
  (= 1 (count (get (rf.http.registry/actor-in-flight-snapshot frame-id) actor-id))))

(defn- assert-same-address-in-both-frames! []
  (is (some? (actor-snapshot :frame/a actor-address))
      "frame A spawned the actor")
  (is (some? (actor-snapshot :frame/b actor-address))
      "frame B spawned an actor at the SAME address from the SAME spec — the
       frame-local-address collision the bead names. If this fails the rest of
       the case proves nothing, so it is asserted rather than assumed"))

(deftest imperative-actor-destroy-aborts-only-the-destroying-frames-http
  (testing "rf2-wjfm — `[:rf.machine/destroy <addr>]` dispatched in frame A
            aborts only A's actor-owned HTTP. The identically-addressed actor in
            frame B keeps its in-flight request. Before the cascade threaded the
            frame, this aborted BOTH — the hook took its ANY-FRAME arity"
    (rf.http.managed/clear-all-in-flight!)
    (register-two-frame-actor-app!)
    (assert-same-address-in-both-frames!)
    (let [seen (atom [])]
      (seed-actor-handle! seen :frame/a actor-address)
      (seed-actor-handle! seen :frame/b actor-address)
      (is (and (actor-live? :frame/a actor-address)
               (actor-live? :frame/b actor-address))
          "precondition: both frames hold an independent slot under one address")
      (rf/dispatch-sync [:worker/kill actor-address] {:frame :frame/a})
      (is (= [[:frame/a :actor-destroyed]] @seen)
          "ONLY frame A's actor-owned request aborted, with the actor-destroy reason")
      (is (actor-live? :frame/b actor-address)
          "frame B's identically-addressed actor keeps its in-flight request"))
    (rf.http.managed/clear-all-in-flight!)))

(deftest frame-destroy-cascade-aborts-only-the-destroyed-frames-actor-http
  (testing "rf2-wjfm — `destroy-frame!` with the machines artefact loaded walks
            the frame's actors through the same helper. Destroying frame A must
            leave frame B's same-named actor's in-flight request alone"
    (rf.http.managed/clear-all-in-flight!)
    (register-two-frame-actor-app!)
    (assert-same-address-in-both-frames!)
    (let [seen (atom [])]
      (seed-actor-handle! seen :frame/a actor-address)
      (seed-actor-handle! seen :frame/b actor-address)
      (rf/destroy-frame! :frame/a)
      ;; The frame-destroy sweep (`:http/on-frame-destroyed!`) also fires for the
      ;; destroyed frame, so assert on WHICH FRAME was reached rather than on a
      ;; single reason — the isolation claim is what this case owns.
      (is (= #{:frame/a} (set (map first @seen)))
          "only frame A's handles were aborted — nothing reached frame B")
      (is (actor-live? :frame/b actor-address)
          "frame B's identically-addressed actor keeps its in-flight request")
      (is (empty? (get (rf.http.registry/actor-in-flight-snapshot :frame/a) actor-address))
          "and frame A's own slot is reaped, so the narrowing did not under-abort"))
    (rf.http.managed/clear-all-in-flight!)))

(deftest machines-absent-frame-destroy-fallback-is-frame-exact
  (testing "rf2-wjfm — core's `destroy-frame!` fallback, taken when the machines
            artefact is absent, fires the same hook per snapshot key. It holds
            the frame under destruction, so it must pass it: this is the second
            of the two call sites the bead named, and an app with no machines
            artefact runs only this one"
    (rf.http.managed/clear-all-in-flight!)
    (register-two-frame-actor-app!)
    (assert-same-address-in-both-frames!)
    (let [seen (atom [])
          orig (rf.late-bind/get-fn :machines/teardown-on-frame-destroy!)]
      (seed-actor-handle! seen :frame/a actor-address)
      (seed-actor-handle! seen :frame/b actor-address)
      (try
        ;; Unbind the machines cascade so `destroy-frame!` takes core's
        ;; machines-ABSENT fallback — the classpath an app without the optional
        ;; artefact actually has.
        (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! nil)
        (rf/destroy-frame! :frame/a)
        (finally
          (rf.late-bind/set-fn! :machines/teardown-on-frame-destroy! orig)))
      (is (= #{:frame/a} (set (map first @seen)))
          "only frame A's handles were aborted by the fallback")
      (is (actor-live? :frame/b actor-address)
          "frame B's identically-addressed actor keeps its in-flight request"))
    (rf.http.managed/clear-all-in-flight!)))

;; ---- the two-index publication window (rf2-o8ek audit) ---------------------
;;
;; The frame-scoped keys above settle WHO a cancellation may reach. These cases
;; settle WHEN a handle is reachable at all, which those keys did not touch.
;;
;; `record-in-flight!` publishes an actor-originated request to the request
;; index and the actor index in TWO separate `swap!`s over two atoms, so
;; between them the handle is already resolvable by `:request-id` while owning
;; no actor-index slot. A cleanup arriving there — on the JVM, another thread
;; firing the just-published `:abort-fn`; the same pre-publication window the
;; transport's `@handle-cell` / `@handle-holder` forward references exist for —
;; drops the request slot and then tries to drop an actor slot THAT DOES NOT
;; EXIST YET. Publication then resumed and conj'd the already-aborted handle
;; into the actor index: a GHOST that outlived its own abort and stayed visible
;; in `actor-in-flight-snapshot` until a later actor or frame teardown.
;;
;; Determinism without threads: an atom's watches run on the swapping thread
;; BEFORE `swap!` returns, so a watch that fires the abort the instant the
;; request slot appears executes strictly between the two publications. The
;; interleaving is decided rather than raced — and the landed cases above
;; cannot see this gap at all, because every one of them calls cleanup only
;; after `record-in-flight!` has already returned.

(def ^:private publication-actor
  ;; The actor address the window cases issue from. Both raw ids are ordinary
  ;; and stable, exactly as reusable app code writes them.
  :worker/publication)

(defn- actor-slot-count
  "How many handles `frame-id` currently has indexed under `publication-actor`
  — read through the same snapshot helper app-facing tooling reads."
  [frame-id]
  (count (get (rf.http.registry/actor-in-flight-snapshot frame-id)
              publication-actor)))

(defn- record-actor-handle-with-midpoint!
  "Publish an actor-originated handle in `frame-id` under `shared-id` and
  `publication-actor`, running `at-midpoint!` at the exact instant the request
  slot appears — after `record-in-flight!`'s first swap and before its second.
  Aborts land in `seen`.

  The `:abort-fn` performs the cleanup both production sites perform, with the
  nil handle they necessarily hold inside the window: `@handle-cell` is filled
  only once `record-in-flight!` RETURNS, so an abort landing here cannot name
  its own handle and reaches the frame-exact 3-arity."
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
         :url      "http://127.0.0.1/publication"
         :abort-fn (fn [reason]
                     (swap! seen conj [frame-id reason])
                     (rf.http.registry/clear-in-flight! frame-id shared-id nil))})
      (finally
        (remove-watch rf.http.registry/in-flight ::publication-midpoint)))))

(deftest abort-inside-the-publication-window-leaves-no-ghost-in-the-actor-index
  (testing "rf2-o8ek audit — an abort reaching the handle between its two
            publications aborts it, and BOTH indexes are empty afterwards.
            Before the publication reconcile the request slot went while the
            actor slot arrived AFTER the abort had already passed, so the app
            had been told this request was cancelled while
            `actor-in-flight-snapshot` still reported one in flight for the
            actor: measured `{:abort-fired? true, :request-slot nil,
            :actor-slot-count 1}`"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])]
      (record-actor-handle-with-midpoint!
        :frame/a seen
        #(rf.http.registry/abort-in-flight-in-frame! :frame/a shared-id :user))
      (is (= [[:frame/a :user]] @seen)
          "precondition: the abort really did fire INSIDE the window. If this is
           empty the interleaving never happened and the rest proves nothing")
      (is (nil? (get (rf.http.registry/in-flight-snapshot :frame/a) shared-id))
          "the request index is empty — it already was before the fix")
      (is (zero? (actor-slot-count :frame/a))
          "and so is the actor index: nothing may still report an aborted
           request as in flight"))
    (rf.http.managed/clear-all-in-flight!)))

(deftest supersede-inside-the-publication-window-leaves-no-ghost-either
  (testing "rf2-o8ek audit — the same window reached through the OTHER
            request-index door. `supersede!` clears by identity and THEN fires
            the abort-fn, so its own actor-index removal finds no slot at the
            midpoint just as the abort cascade's does. Publication owning the
            reconcile is what makes this hold for both callers rather than for
            whichever one a test happened to drive"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])]
      (record-actor-handle-with-midpoint!
        :frame/a seen
        #(rf.http.registry/supersede! :frame/a shared-id))
      (is (= [[:frame/a :request-id-superseded]] @seen)
          "precondition: the supersession really did land inside the window")
      (is (zero? (actor-slot-count :frame/a))
          "the superseded handle left no actor-index slot behind"))
    (rf.http.managed/clear-all-in-flight!)))

(deftest the-publication-window-reconcile-is-frame-exact
  (testing "rf2-o8ek — the retraction matches on handle IDENTITY, so a sibling
            frame already live under the SAME raw request-id AND the same actor
            address keeps both of its slots and stays abortable. A reconcile
            that swept by raw id would reintroduce, inside publication, exactly
            the cross-frame reach the compound keys removed"
    (rf.http.managed/clear-all-in-flight!)
    (let [seen (atom [])]
      ;; Frame B goes live FIRST, under both of the same ordinary raw ids.
      (rf.http.registry/record-in-flight!
        shared-id publication-actor
        {:frame    :frame/b
         :url      "http://127.0.0.1/sibling"
         :abort-fn (fn [reason] (swap! seen conj [:frame/b reason]))})
      (record-actor-handle-with-midpoint!
        :frame/a seen
        #(rf.http.registry/abort-in-flight-in-frame! :frame/a shared-id :user))
      (is (= [[:frame/a :user]] @seen)
          "only frame A's handle was aborted")
      (is (some? (get (rf.http.registry/in-flight-snapshot :frame/b) shared-id))
          "frame B's request slot survives A's publication-window abort")
      (is (= 1 (actor-slot-count :frame/b))
          "and so does frame B's actor slot, so B's request is still abortable")
      (is (zero? (actor-slot-count :frame/a))
          "while frame A leaves nothing behind"))
    (rf.http.managed/clear-all-in-flight!)))

(deftest an-undisturbed-publication-is-never-retracted
  (testing "rf2-o8ek audit — the reconcile is CONDITIONAL on the handle having
            lost its request slot. With no interleaving at all an
            actor-originated request must end up registered in both indexes.
            Without this control a reconcile that retracted unconditionally
            would pass every case above while silently unregistering every
            actor-owned request in the library"
    (rf.http.managed/clear-all-in-flight!)
    (rf.http.registry/record-in-flight!
      shared-id publication-actor
      {:frame    :frame/a
       :url      "http://127.0.0.1/quiet"
       :abort-fn (fn [_reason] nil)})
    (is (some? (get (rf.http.registry/in-flight-snapshot :frame/a) shared-id))
        "the request index holds it")
    (is (= 1 (actor-slot-count :frame/a))
        "and so does the actor index — publication completed untouched")
    (rf.http.managed/clear-all-in-flight!)))
