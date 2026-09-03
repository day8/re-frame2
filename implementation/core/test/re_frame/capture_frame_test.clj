(ns re-frame.capture-frame-test
  "Design-pinning tests for the frame-affordance redesign (rf2-kkut0.1):
  `capture-frame` (the keystone OPERATION BUNDLE, and per API-shrink #1
  rf2-csbbwu the ONE public HOLD primitive), `re-frame.frame/bind-fn` (the
  INTERNAL relocated `frame-bound-fn*` dynamic-rebinding primitive),
  `current-frame-id`, `app-db-value`, and the absence of the removed public
  names (`bound-fn`, `dispatcher`, `subscriber`, `get-frame-db`,
  `current-frame`, `frame-bound-fn`, `frame-bound-fn*`, `frame-value->id`).
  Per Spec 002 §capture-frame and `re-frame.core.cljc`.

  `capture-frame` exists to support async callbacks where the dynamic-var
  frame binding has already unwound: it captures the frame at CREATION
  time and its `:dispatch` / `:dispatch-sync` / `:subscribe` ops always
  target THAT frame — not whatever the caller's current frame is when an
  op later fires.

  These JVM tests use `with-frame :A` to set the dynamic var, capture the
  handle, then EXIT the with-frame scope before invoking its ops —
  proving the captured frame survives the unwind.

  ## Posture split (rf2-d2841)

  NO GUARD WAS NEEDED HERE, and the reason is the question rf2-d2841's fourth
  pass learned to ask on the error axis: IS THE CATEGORY PROMOTED?
  `:rf.error/frame-destroyed` is one of the categories `error-emit` fans onto
  the always-on corpus-wide listener registry (`error-emit` ns docstring,
  §promoted set covers frame-destroyed dispatch / subscribe), so every incarnation
  -fence assertion in this file survives the production gate verbatim once it
  reads the `:errors` STREAM instead of the `:trace` stream. That is the whole
  change: `register-listener! :errors`, and `(:error rec)` where the trace
  spelled `(:operation ev)`.

  It matters more here than the mechanical size of the diff suggests. These are
  incarnation-fence tests — the class of defect where a capture pinned to a
  destroyed incarnation leaks into a same-id successor and mutates it — and
  every one of them was previously unexecuted under the posture that ships.

  TWO VACUOUS PASSES CAME OFF (rf2-d2841 class 1 — a negative over an empty
  trace ring), and both were in deftests already GREEN under the gate:
  `live-target-not-reported-destroyed-when-only-owner-dies-async` / `-sync`
  each certify `(zero? (count (filter #(= :rf.error/frame-destroyed …))))` —
  the LIVE target was not wrongly reported destroyed — over a stream that
  carried nothing at all. A false-negative check on a fence is exactly the
  assertion you least want passing for free; re-aimed at the always-on stream
  it now discriminates, with its sibling deftests supplying the control that
  the same stream DOES carry the category when the fence genuinely fires."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.router :as rf.router]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; EP-0002 (rf2-jue6sp): `init!` no longer synthesises `:rf/default`.
  ;; Register it explicitly as an ordinary frame so the tests that
  ;; observe `:rf/default`'s app-db (and the explicit-id capture cases)
  ;; have a real frame; the no-arg capture forms still REQUIRE a carried
  ;; scope at capture time (covered by the *-requires-scope tests below).
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- shape: a handle is an operation bundle ------------------------------

(deftest capture-frame-returns-operation-bundle
  (testing "(capture-frame frame-id) returns {:frame :dispatch :dispatch-sync :subscribe}"
    (rf/make-frame {:id :fh/shape :doc "shape probe"})
    (let [h (rf/capture-frame :fh/shape)]
      (is (= :fh/shape (:frame h))
          ":frame is the captured frame id")
      (is (fn? (:dispatch h))      ":dispatch is a fn")
      (is (fn? (:dispatch-sync h)) ":dispatch-sync is a fn")
      (is (fn? (:subscribe h))     ":subscribe is a fn"))))

;; ---- captures at creation, not op-call time ------------------------------

(deftest capture-frame-captures-frame-at-creation
  (testing "(capture-frame) captures the active frame at CREATION; the bundle's
            :dispatch routes to THAT frame after the with-frame scope unwinds"
    (rf/make-frame {:id :fh/A :doc "frame A — the capture target"})
    (rf/reg-event :fh/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; Capture inside :fh/A; fire OUTSIDE.
    (let [{:keys [dispatch]} (rf/with-frame :fh/A (rf/capture-frame))]
      ;; The dynamic-var binding has unwound. The captured op must still
      ;; route to :fh/A.
      (dispatch [:fh/inc])
      (dispatch [:fh/inc])
      (rf.test-support/poll-until #(= 2 (:n (rf/app-db-value :fh/A)))
                               {:label "captured handle drains to :fh/A"})
      (is (= 2 (:n (rf/app-db-value :fh/A)))
          "the captured handle routed events to :fh/A after the scope unwound")
      (is (nil? (:n (rf/app-db-value :rf/default)))
          ":rf/default's app-db was NOT touched — capture is frame-faithful"))))

(deftest capture-frame-subscribe-captures-frame
  (testing "the bundle's :subscribe op resolves against the captured frame
            after the with-frame scope unwinds"
    (rf/make-frame {:id :fh/B :doc "frame B — the subscribe target"})
    (rf/reg-event :fh/seed (fn [{:keys [db]} [_ v]] {:db {:value v}}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/dispatch-sync [:fh/seed :B-value] {:frame :fh/B})
    ;; Seed :rf/default explicitly (EP-0002: no ambient :rf/default floor)
    ;; so the assertion can prove the captured subscribe reads :fh/B, not
    ;; :rf/default's app-db.
    (rf/dispatch-sync [:fh/seed :default-value] {:frame :rf/default})
    (let [{:keys [subscribe]} (rf/with-frame :fh/B (rf/capture-frame))
          reaction            (subscribe [:fh/value])]
      (is (= :B-value @reaction)
          "captured :subscribe resolves against :fh/B's app-db, not :rf/default"))))

;; ---- per-call :frame CANNOT override the captured frame ------------------

(deftest capture-frame-locked-frame-cannot-be-overridden
  (testing "a per-call :frame in dispatch opts MUST NOT override the captured
            frame — the handle is LOCKED to one frame"
    (rf/make-frame {:id :fh/locked :doc "the locked target"})
    (rf/make-frame {:id :fh/other :doc "the would-be override"})
    (rf/reg-event :fh/touch (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (let [{:keys [dispatch]} (rf/capture-frame :fh/locked)]
      ;; Attempt to redirect to :fh/other via a per-call :frame opt.
      (dispatch [:fh/touch] {:frame :fh/other})
      (rf.test-support/poll-until #(:touched? (rf/app-db-value :fh/locked))
                               {:label "locked handle drains to :fh/locked"})
      (is (true? (:touched? (rf/app-db-value :fh/locked)))
          "the event landed in the CAPTURED frame :fh/locked")
      (is (nil? (:touched? (rf/app-db-value :fh/other)))
          "the per-call :frame :fh/other was IGNORED — the handle is locked"))))

;; ---- incarnation fence: a stale capture never retargets a same-id successor
;;
;; rf2-9pyles pinned the EXACT incarnation live at capture so a captured op
;; cannot leak into a same-id successor reseated after the captured incarnation
;; was destroyed. These pin the two consistency gaps closed on top of it:
;;   - rf2-tdjv7p: the `:subscribe` op is fenced on the SAME pin as dispatch —
;;     a superseded subscribe recover-but-emits (returns nil) rather than
;;     silently resolving a reaction into the successor's sub-cache.
;;   - rf2-vclh63: a capture over a frame VALUE pins the value's carried EXACT
;;     incarnation token, so a value-capture fences identically to an id-capture
;;     (before the fix the value path silently lost its pin).

(deftest stale-capture-subscribe-does-not-retarget-same-id-successor
  (testing "rf2-tdjv7p — a capture pinned to incarnation A, after A is destroyed
            and a same-id successor B reseats, MUST NOT subscribe into B (reading
            B's app-db, caching a reaction in B's sub-cache). It recover-but-
            emits :rf.error/frame-destroyed and returns nil — the async-safe
            sibling of the throwing synchronous (frame)-bundle subscribe fence."
    (rf/reg-event :fh/seed (fn [{:keys [db]} [_ v]] {:db {:value v}}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    ;; Incarnation A of :fh/sub-stale, seeded with :A-value.
    (rf/make-frame {:id :fh/sub-stale :doc "incarnation A"})
    (rf/dispatch-sync [:fh/seed :A-value] {:frame :fh/sub-stale})
    (let [{:keys [subscribe]} (rf/capture-frame :fh/sub-stale)] ; pins incarnation A
      ;; Sanity: while A is live the captured subscribe reads A.
      (is (= :A-value @(subscribe [:fh/value]))
          "a LIVE capture's subscribe reads incarnation A")
      ;; Destroy A; reseat a same-id successor B with DIFFERENT data.
      (rf/destroy-frame! :fh/sub-stale)
      (rf/make-frame {:id :fh/sub-stale :doc "incarnation B (successor)"})
      (rf/dispatch-sync [:fh/seed :B-value] {:frame :fh/sub-stale})
      (let [errs (atom [])]
        (rf/register-listener! :errors ::sub-stale (fn [rec] (swap! errs conj rec)))
        ;; Before the fix this returned a reaction reading B's :B-value; the
        ;; fence makes it recover-but-emit and return nil.
        (let [result (subscribe [:fh/value])]
          (rf/unregister-listener! :errors ::sub-stale)
          (is (nil? result)
              "the superseded capture's subscribe returns nil — it did NOT
               resolve a reaction into successor B")
          (is (some #(= :rf.error/frame-destroyed (:error %)) @errs)
              "the superseded subscribe recover-but-emits :rf.error/frame-destroyed"))))))

(deftest capture-frame-over-value-pins-exact-incarnation
  (testing "rf2-vclh63 — (capture-frame <frame-value>) pins the value's EXACT
            incarnation via its carried :rf.frame/incarnation-token, so a
            dispatch through the capture after the frame is destroyed and a
            same-id successor reseats recover-but-emits instead of mutating the
            successor. A LIVE value-capture still dispatches into its frame — the
            pin is exact, not a spurious supersession."
    (rf/reg-event :fh/mark (fn [{:keys [db]} [_ v]] {:db (assoc db :mark v)}))
    ;; Incarnation A — capture the construction VALUE (carries its exact token).
    (let [frame-a (rf/make-frame {:id :fh/vpin :doc "incarnation A"})]
      (is (some? (rf.frame/frame-value-incarnation-token frame-a))
          "precondition: a fresh make-frame VALUE carries its incarnation token (rf2-moftbs)")
      (let [{:keys [dispatch-sync]} (rf/capture-frame frame-a)]
        ;; Live path: the value-capture dispatches into incarnation A — the new
        ;; pin must NOT spuriously supersede a live capture.
        (dispatch-sync [:fh/mark :A-mark])
        (is (= :A-mark (:mark (rf/app-db-value :fh/vpin)))
            "a LIVE value-capture dispatches into its frame — the pin is exact, not spurious")
        ;; Destroy A (incarnation-exact via the value) and reseat a same-id B.
        (rf/destroy-frame! frame-a)
        (rf/make-frame {:id :fh/vpin :doc "incarnation B (successor)"})
        (let [errs (atom [])]
          (rf/register-listener! :errors ::vpin (fn [rec] (swap! errs conj rec)))
          ;; Before the fix the unpinned value-capture retargeted B and set
          ;; :mark; the carried-token pin makes it recover-but-emit.
          (dispatch-sync [:fh/mark :leaked])
          (rf/unregister-listener! :errors ::vpin)
          (is (nil? (:mark (rf/app-db-value :fh/vpin)))
              "the stale value-capture did NOT mutate the same-id successor B")
          (is (some #(= :rf.error/frame-destroyed (:error %)) @errs)
              "the superseded value-capture dispatch recover-but-emits :rf.error/frame-destroyed"))))))

;; ---- rf2-dlld6: carry the captured incarnation THROUGH target consumption ---
;;
;; rf2-9pyles/tdjv7p/vclh63 (above) pinned the exact incarnation and had
;; `capture-target-superseded?` VALIDATE it — but that check and the ordinary
;; address-directed dispatch/subscribe it then delegated to were SEPARATE
;; operations. On the concurrent JVM host frame A can be destroyed AND a same-id
;; successor B installed in the window between the check returning "A live" and
;; the ordinary implementation resolving the bare id — so a check-passing stale
;; capture leaked into B (`{:successor-db {:owner :B :marked-by :stale-capture}}`
;; in the reproduction). The fix carries the captured incarnation through into
;; the rf.router/sub resolve so validation and target consumption are ONE
;; exact-incarnation operation.
;;
;; These fixtures interpose ONE-SHOT on `rf.frame/frame-incarnation-live?` — the
;; predicate `capture-target-superseded?` consults — and, at the moment the
;; pre-check validates incarnation A as live, destroy A and reseat a same-id
;; successor B BEFORE returning A's (true) liveness. That is exactly the JVM
;; interleaving: the stale capture validated A, another actor swapped A for B,
;; then the op resumed and resolved the bare id → B. Deterministic + single-
;; threaded (the swap runs inside the interposed call), so no latch is needed.

(defn- run-supersede-during-precheck
  "Run `op` with a ONE-SHOT interposition on `rf.frame/frame-incarnation-live?`:
  when the capture's pre-check validates incarnation `a-token` of `frame-id` as
  live, destroy A and reseat a fresh same-id successor B BEFORE handing back A's
  (true) liveness — reproducing the destroy-A/create-B window between
  `capture-frame`'s liveness pre-check and the ordinary bare-id target resolve.
  The interposition fires exactly once (the pre-check); every later call —
  including the destroy/create machinery's own — delegates to the real fn."
  [frame-id a-token op]
  (let [real  rf.frame/frame-incarnation-live?
        fired (atom false)]
    (with-redefs [rf.frame/frame-incarnation-live?
                  (fn [id token]
                    (let [live? (real id token)]
                      (when (and (not @fired)
                                 (= id frame-id)
                                 (identical? token a-token)
                                 live?)
                        (reset! fired true)     ;; set BEFORE the swap so the
                        ;; destroy/create's own liveness reads take the real path
                        (rf/destroy-frame! frame-id)
                        (rf/make-frame {:id frame-id :doc "incarnation B (successor)"}))
                      live?))]
      (op))))

(deftest stale-capture-dispatch-sync-does-not-retarget-same-id-successor
  (testing "rf2-dlld6 (dispatch-sync arm) — a capture whose pre-check validated
            incarnation A, superseded by same-id B before the bare-id resolve,
            MUST NOT dispatch-sync into B. It recover-but-emits
            :rf.error/frame-destroyed and leaves B byte-for-byte unchanged."
    (rf/reg-event :fh/mark (fn [{:keys [db]} _] {:db (assoc db :marked-by :stale-capture)}))
    (rf/make-frame {:id :fh/race :doc "incarnation A"})
    (let [a-token (rf.frame/frame-incarnation-token :fh/race)
          {:keys [dispatch-sync]} (rf/capture-frame :fh/race) ;; pins A
          errs    (atom [])]
      (rf/register-listener! :errors ::race (fn [rec] (swap! errs conj rec)))
      (run-supersede-during-precheck
        :fh/race a-token #(dispatch-sync [:fh/mark]))
      (rf/unregister-listener! :errors ::race)
      (is (nil? (:marked-by (rf/app-db-value :fh/race)))
          "the stale capture did NOT mutate same-id successor B")
      (is (some #(= :rf.error/frame-destroyed (:error %)) @errs)
          "the superseded dispatch-sync recover-but-emits :rf.error/frame-destroyed"))))

(deftest stale-capture-async-dispatch-does-not-enqueue-into-same-id-successor
  (testing "rf2-dlld6 (async dispatch arm) — a superseded capture's async
            dispatch enqueues NOTHING into same-id successor B; it recover-but-
            emits and B stays byte-for-byte unchanged."
    (rf/reg-event :fh/mark (fn [{:keys [db]} _] {:db (assoc db :marked-by :stale-capture)}))
    (rf/make-frame {:id :fh/race :doc "incarnation A"})
    (let [a-token (rf.frame/frame-incarnation-token :fh/race)
          {:keys [dispatch]} (rf/capture-frame :fh/race) ;; pins A
          errs    (atom [])]
      (rf/register-listener! :errors ::race (fn [rec] (swap! errs conj rec)))
      (run-supersede-during-precheck
        :fh/race a-token #(dispatch [:fh/mark]))
      ;; The fence short-circuits BEFORE enqueue/schedule, so nothing can drain
      ;; into B; poll to confirm the emit lands rather than asserting a single
      ;; instant.
      (rf.test-support/poll-until (fn [] (some (fn [ev] (= :rf.error/frame-destroyed (:error ev))) @errs))
                               {:label "async fence emits frame-destroyed"})
      (rf/unregister-listener! :errors ::race)
      (is (nil? (:marked-by (rf/app-db-value :fh/race)))
          "the stale async dispatch enqueued nothing into successor B")
      (is (some #(= :rf.error/frame-destroyed (:error %)) @errs)
          "the superseded async dispatch recover-but-emits :rf.error/frame-destroyed"))))

(deftest stale-capture-subscribe-race-does-not-read-or-cache-in-same-id-successor
  (testing "rf2-dlld6 (subscribe arm) — a capture whose pre-check validated A,
            superseded by same-id B before the sub-cache resolve, neither reads
            B's app-db nor installs a reaction/cache entry in B; it returns nil
            and recover-but-emits."
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/make-frame {:id :fh/race :doc "incarnation A"})
    (let [a-token (rf.frame/frame-incarnation-token :fh/race)
          {:keys [subscribe]} (rf/capture-frame :fh/race) ;; pins A
          errs    (atom [])
          result  (do (rf/register-listener! :errors ::race (fn [rec] (swap! errs conj rec)))
                      (run-supersede-during-precheck
                        :fh/race a-token #(subscribe [:fh/value])))]
      (rf/unregister-listener! :errors ::race)
      (is (nil? result)
          "the superseded subscribe returns nil — it did NOT resolve a reaction into B")
      (is (empty? @(:sub-cache (rf.frame/frame :fh/race)))
          "no reaction/cache entry was installed in same-id successor B's sub-cache")
      (is (some #(= :rf.error/frame-destroyed (:error %)) @errs)
          "the superseded subscribe recover-but-emits :rf.error/frame-destroyed"))))

;; ---- rf2-7w1im: the POST-comparison boundary (not only the pre-check) -------
;;
;; rf2-dlld6 (above) made the OUTER subscribe-in-frame comparison validate the
;; captured incarnation — but that comparison and the DURABLE build it delegates
;; a miss / hit-eviction rebuild to were still separate operations. On the
;; concurrent JVM host frame A can be destroyed AND a same-id successor B
;; installed in the window between the comparison returning "A" and
;; `compute-and-cache!` re-resolving the bare id — so a comparison-passing
;; capture leaked its read / cache-write / recursive-input resolution into B.
;; rf2-7w1im carries the pinned incarnation THROUGH the build (compute-and-cache!
;; → build-and-cache!*), which re-fences it and reads the container / writes the
;; sub-cache / subscribes inputs off the validated record, so the whole captured
;; subscribe is ONE exact-incarnation operation.
;;
;; These fixtures interpose ONE-SHOT on `rf.subs/compute-and-cache!` — entered only
;; AFTER the outer comparison validated A and delegated the miss/rebuild — and,
;; at that boundary, destroy A and reseat a same-id successor B (seeded with
;; :B-value) BEFORE the real build runs. That is exactly the JVM interleaving the
;; earlier `frame-incarnation-live?` pre-check interposition (rf2-dlld6) CANNOT
;; reach: it fires at the pre-check, before the outer comparison. Deterministic +
;; single-threaded (the swap runs inside the interposed call), so no latch.

(defn- run-supersede-at-build
  "Interpose ONE-SHOT on `rf.subs/compute-and-cache!`: the first time it is entered
  for `trigger-qv` (the post-comparison durable-build boundary — the outer
  `subscribe-in-frame` has already validated incarnation A and delegated the miss
  / hit-eviction rebuild here), destroy A and reseat a same-id successor B seeded
  with :B-value, THEN delegate to the real build. Reproduces A destroyed + B
  installed AFTER the outer comparison but before the durable read/cache-write.
  Fires exactly once; every later call (incl. the destroy/create machinery's own,
  and the entry sub's build when `trigger-qv` is an INPUT query) takes the real
  path."
  [frame-id trigger-qv op]
  (let [real  @#'rf.subs/compute-and-cache!
        fired (atom false)]
    (with-redefs [rf.subs/compute-and-cache!
                  (fn [& args]
                    (when (and (= trigger-qv (second args))
                               (compare-and-set! fired false true))
                      (rf/destroy-frame! frame-id)
                      (rf/make-frame {:id frame-id :doc "incarnation B (successor)"})
                      (rf/dispatch-sync [:fh/seed :B-value] {:frame frame-id}))
                    (apply real args))]
      (op))))

(deftest stale-capture-subscribe-post-comparison-miss-does-not-retarget-successor
  (testing "rf2-7w1im (miss arm) — a captured subscribe whose OUTER incarnation
            comparison validated A, then superseded by same-id B AFTER that
            comparison but BEFORE the durable build (compute-and-cache!), must not
            read B, install a reaction in B, or return B's value. It returns nil,
            emits exactly one :rf.error/frame-destroyed, and leaves B's db +
            sub-cache untouched. MUTATION TOOTH: if the build falls back to a bare
            frame id (drops the token / re-resolves), it reads and caches B and
            this fails."
    (rf/reg-event :fh/seed (fn [{:keys [db]} [_ v]] {:db {:value v}}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/make-frame {:id :fh/race :doc "incarnation A"})
    (rf/dispatch-sync [:fh/seed :A-value] {:frame :fh/race})
    (let [{:keys [subscribe]} (rf/capture-frame :fh/race)   ;; pins A
          errs   (atom [])
          _      (rf/register-listener! :errors ::pcb-miss (fn [rec] (swap! errs conj rec)))
          result (run-supersede-at-build :fh/race [:fh/value]
                   #(subscribe [:fh/value]))]
      (rf/unregister-listener! :errors ::pcb-miss)
      (is (nil? result)
          "the post-comparison-superseded subscribe returns nil — it did NOT resolve into B")
      (is (= :B-value (:value (rf/app-db-value :fh/race)))
          "successor B's app-db is intact (untouched by the stale build)")
      (is (empty? @(:sub-cache (rf.frame/frame :fh/race)))
          "no reaction/cache entry was installed in successor B's sub-cache")
      (is (= 1 (count (filter #(= :rf.error/frame-destroyed (:error %)) @errs)))
          "exactly one :rf.error/frame-destroyed emit"))))

(deftest live-capture-subscribe-miss-still-reads-its-own-incarnation
  (testing "rf2-7w1im (genuine-subscribe control) — with NO interposition a
            captured subscribe that misses builds against its OWN incarnation,
            caches in its OWN sub-cache, and reads its value; the fence rejects
            ONLY a superseded read, never a genuine same-incarnation one."
    (rf/reg-event :fh/seed (fn [{:keys [db]} [_ v]] {:db {:value v}}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/make-frame {:id :fh/live :doc "incarnation A"})
    (rf/dispatch-sync [:fh/seed :A-value] {:frame :fh/live})
    (let [{:keys [subscribe]} (rf/capture-frame :fh/live)
          reaction (subscribe [:fh/value])]
      (is (some? reaction) "a live captured subscribe on a miss builds a reaction")
      (is (= :A-value @reaction) "and reads its own incarnation's value")
      (is (contains? @(:sub-cache (rf.frame/frame :fh/live)) [:fh/value])
          "the reaction is cached in its own frame's sub-cache"))))

(deftest stale-capture-subscribe-recursive-input-not-resolved-in-successor
  (testing "rf2-7w1im (recursive-input arm) — a captured layer-2 subscribe whose
            ENTRY build validated A, then superseded by same-id B before the
            layer-1 INPUT build, must not recursively resolve the input in B. The
            input build's fence rejects; neither the entry nor its input installs
            a reaction in successor B's sub-cache. MUTATION TOOTH: if the input
            subscribe drops the token it resolves in B and B's sub-cache gains
            [:fh/value]."
    (rf/reg-event :fh/seed (fn [{:keys [db]} [_ v]] {:db {:value v}}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/reg-sub :fh/derived :<- [:fh/value] (fn [v _] [:derived v]))
    (rf/make-frame {:id :fh/race :doc "incarnation A"})
    (rf/dispatch-sync [:fh/seed :A-value] {:frame :fh/race})
    (let [{:keys [subscribe]} (rf/capture-frame :fh/race)   ;; pins A
          errs   (atom [])
          _      (rf/register-listener! :errors ::pcb-rec (fn [rec] (swap! errs conj rec)))
          ;; Fire at the INPUT build ([:fh/value]) — AFTER the entry [:fh/derived]
          ;; build validated A and began resolving its inputs.
          _      (run-supersede-at-build :fh/race [:fh/value]
                   #(subscribe [:fh/derived]))]
      (rf/unregister-listener! :errors ::pcb-rec)
      (is (= :B-value (:value (rf/app-db-value :fh/race)))
          "successor B's app-db is intact")
      (is (empty? @(:sub-cache (rf.frame/frame :fh/race)))
          "neither :fh/derived nor its input :fh/value is installed in successor B's sub-cache")
      (is (some #(= :rf.error/frame-destroyed (:error %)) @errs)
          "the recursive input's build fence emits :rf.error/frame-destroyed"))))

;; ---- rf2-a2x2w: exactly-once when A is lost AFTER the token match ----------
;;
;; rf2-dlld6 (above) fenced the A→B mismatch AT the token comparison. A LATER
;; window remained: A can be lost AFTER dispatch! / dispatch-sync! pass the
;; expected-token comparison (A still current then, so the mismatch clause does
;; NOT fire) but BEFORE the async enqueue linearizes under the router monitor /
;; the sync drain-lock is acquired. The `target-live?` / lock guards correctly
;; keep successor B untouched — but pre-fix they SILENTLY returned (no emit,
;; violating the recover-but-EMIT contract). rf2-a2x2w recover-but-emits EXACTLY
;; ONCE from this window too, realm-exact via the threaded `:op`.
;;
;; These fixtures interpose ONE-SHOT on the router's private enqueue / sync-drain
;; seam — entered ONLY after the cond's token comparison passed — and, at that
;; boundary, destroy A + reseat a same-id successor B. The real seam's own lock-
;; guarded incarnation re-check then fences the enqueue / seed-push out, so the
;; post-token-match window is exercised deterministically (single-threaded — the
;; swap runs inside the interposed call, no latch). The MUTATION TOOTH is the
;; emit COUNT: pre-fix these returned silently (zero emits); the fix makes it one.

(defn- run-supersede-at-enqueue
  "Interpose ONE-SHOT on `rf.router/ensure-drain-scheduled!` (dispatch!'s async
  enqueue seam, reached only after the cond passed the token comparison): the
  first entry destroys A and reseats a same-id successor B, THEN delegates to the
  real seam — whose router-monitor liveness re-check now fences the enqueue out.
  Reproduces A lost after the token match but before the async enqueue."
  [frame-id op]
  (let [real  @#'rf.router/ensure-drain-scheduled!
        fired (atom false)]
    (with-redefs [rf.router/ensure-drain-scheduled!
                  (fn [fid frame-record router envelope continue?]
                    (when (compare-and-set! fired false true)
                      (rf/destroy-frame! frame-id)
                      (rf/make-frame {:id frame-id :doc "incarnation B (successor)"}))
                    (real fid frame-record router envelope continue?))]
      (op))))

(defn- run-supersede-at-drain-block
  "Interpose ONE-SHOT on `rf.router/drain-block!` (dispatch-sync!'s sync-drain seam,
  reached only after the cond passed the token comparison AND both `target-live?`
  guards held): the first entry destroys A and reseats same-id B, THEN delegates
  to the real seam — whose post-CAS incarnation re-check now resets the lock
  WITHOUT running the seed-push. Reproduces A lost after the token match but
  before the drain-lock acquire."
  [frame-id op]
  (let [real  @#'rf.router/drain-block!
        fired (atom false)]
    (with-redefs [rf.router/drain-block!
                  (fn [fid frame-record under-lock-fn]
                    (when (compare-and-set! fired false true)
                      (rf/destroy-frame! frame-id)
                      (rf/make-frame {:id frame-id :doc "incarnation B (successor)"}))
                    (real fid frame-record under-lock-fn))]
      (op))))

(deftest stale-capture-async-dispatch-post-token-match-emits-exactly-once
  (testing "rf2-a2x2w (async, gap 2) — a captured async dispatch that PASSED the
            exact-incarnation token comparison, then lost A before the enqueue
            linearized under the router monitor, recover-but-emits EXACTLY ONE
            :rf.error/frame-destroyed and enqueues NOTHING into successor B.
            MUTATION TOOTH: pre-fix this post-token-match window returned SILENTLY
            (zero emits)."
    (rf/reg-event :fh/mark (fn [{:keys [db]} _] {:db (assoc db :marked-by :stale-capture)}))
    (rf/make-frame {:id :fh/race :doc "incarnation A"})
    (let [{:keys [dispatch]} (rf/capture-frame :fh/race)   ;; pins A
          errs (atom [])]
      (rf/register-listener! :errors ::a2x2w-async (fn [rec] (swap! errs conj rec)))
      (run-supersede-at-enqueue :fh/race #(dispatch [:fh/mark]))
      (rf/unregister-listener! :errors ::a2x2w-async)
      (is (nil? (:marked-by (rf/app-db-value :fh/race)))
          "the post-token-match loss enqueued nothing into successor B")
      (is (= 1 (count (filter #(= :rf.error/frame-destroyed (:error %)) @errs)))
          "EXACTLY ONE :rf.error/frame-destroyed for the post-token-match async loss"))))

(deftest stale-capture-dispatch-sync-post-token-match-emits-exactly-once
  (testing "rf2-a2x2w (dispatch-sync, gap 2) — a captured dispatch-sync that
            PASSED the exact-incarnation token comparison, then lost A before the
            drain-lock acquire, recover-but-emits EXACTLY ONE
            :rf.error/frame-destroyed and processes NOTHING into successor B.
            MUTATION TOOTH: pre-fix this window returned SILENTLY."
    (rf/reg-event :fh/mark (fn [{:keys [db]} _] {:db (assoc db :marked-by :stale-capture)}))
    (rf/make-frame {:id :fh/race :doc "incarnation A"})
    (let [{:keys [dispatch-sync]} (rf/capture-frame :fh/race)   ;; pins A
          errs (atom [])]
      (rf/register-listener! :errors ::a2x2w-sync (fn [rec] (swap! errs conj rec)))
      (run-supersede-at-drain-block :fh/race #(dispatch-sync [:fh/mark]))
      (rf/unregister-listener! :errors ::a2x2w-sync)
      (is (nil? (:marked-by (rf/app-db-value :fh/race)))
          "the post-token-match loss processed nothing into successor B")
      (is (= 1 (count (filter #(= :rf.error/frame-destroyed (:error %)) @errs)))
          "EXACTLY ONE :rf.error/frame-destroyed for the post-token-match sync loss"))))

;; ---- rf2-iqfbg: a live target is NOT reported destroyed when only the ------
;;      ORIGINATING event OWNER dies -------------------------------------------
;;
;; rf2-a2x2w (above) made a falsey `enqueued?` / `drained?` recover-but-emit one
;; `:rf.error/frame-destroyed`, to cover a captured target A lost after its token
;; match. But that falsey result is NOT target-specific: the enqueue/drain path
;; guards on `target-live?`, which FUSES `owner-live?` with target-incarnation
;; liveness. So the enqueue also fences out (falsey) when only the ORIGINATING
;; event OWNER died — a benign framework-owned-tail cutoff (a callback keeps
;; running after it destroyed its own frame and issues a captured dispatch) —
;; while the captured target is fully LIVE. Pre-fix the a2x2w gate emitted a
;; FALSE `:rf.error/frame-destroyed` naming the live target frame, mis-steering
;; off-box diagnostics at the wrong frame / lifecycle failure.
;;
;; These fixtures bind a genuine exact owner token for `:audit/owner`, capture a
;; DIFFERENT live target `:audit/target`, then interpose ONE-SHOT on the router's
;; `emit-dispatched-trace!` seam (the first thing the captured op's `:else`
;; branch does, after the token comparison passed) to destroy ONLY the owner —
;; the exact production outcome when continuation dies during that callback-
;; bearing seam. The MUTATION TOOTH is the emit COUNT: pre-fix these emitted one
;; false frame-destroyed for the live target; the fix keeps owner-continuation
;; cutoff SILENT (zero emits) while the target stays untouched.

(defn- run-owner-death-at-dispatch
  "Interpose ONE-SHOT on `rf.router/emit-dispatched-trace!` (reached in the captured
  op's `:else` branch only after the token comparison passed): the first entry
  destroys ONLY the originating event `owner-id` — the captured TARGET is left
  fully live — THEN delegates to the real seam. `emit-dispatched-trace!` returns
  `(target-live?)`, which now reads `owner-live?` false and short-circuits the
  enqueue/drain to falsey WITHOUT the target ever being touched. Reproduces the
  originating owner's continuation dying during the callback-bearing dispatch
  seam while the captured target stays live."
  [owner-id op]
  (let [real  @#'rf.router/emit-dispatched-trace!
        fired (atom false)]
    (with-redefs [rf.router/emit-dispatched-trace!
                  (fn
                    ([envelope sync?]
                     (real envelope sync?))
                    ([envelope sync? continue?]
                     (when (compare-and-set! fired false true)
                       (rf/destroy-frame! owner-id))   ;; kill ONLY the owner
                     (real envelope sync? continue?)))]
      (op))))

(deftest live-target-not-reported-destroyed-when-only-owner-dies-async
  (testing "rf2-iqfbg (async) — a captured async dispatch whose ORIGINATING event
            OWNER dies during the dispatch seam, while the captured TARGET stays
            LIVE, DROPS the operation (target untouched) and emits NO
            :rf.error/frame-destroyed. The falsey `enqueued?` is an owner-
            continuation cutoff, NOT target destruction. ADVERSARIAL MUTATION
            TOOTH: pre-fix (the a2x2w gate `(and capture-op (not enqueued?))`)
            emitted a FALSE frame-destroyed naming the still-live target frame."
    (rf/reg-event :audit/touch (fn [{:keys [db]} _] {:db (assoc db :marked-by :owner-death)}))
    (rf/make-frame {:id :audit/owner  :doc "originating event owner"})
    (rf/make-frame {:id :audit/target :doc "captured target A (stays live)"})
    (let [owner-token  (rf.frame/frame-incarnation-token :audit/owner)
          target-token (rf.frame/frame-incarnation-token :audit/target)
          {:keys [dispatch]} (rf/capture-frame :audit/target)   ;; pins target A
          errs (atom [])]
      (rf/register-listener! :errors ::iqfbg-async (fn [rec] (swap! errs conj rec)))
      (rf.frame/call-with-event-owner-token :audit/owner owner-token
        (fn [] (run-owner-death-at-dispatch :audit/owner #(dispatch [:audit/touch]))))
      (rf/unregister-listener! :errors ::iqfbg-async)
      (is (nil? (rf.frame/frame :audit/owner))
          "precondition: the interpose actually destroyed the originating owner")
      (is (rf.frame/frame-incarnation-live? :audit/target target-token)
          "the captured TARGET incarnation is STILL LIVE — only the owner died")
      (is (nil? (:marked-by (rf/app-db-value :audit/target)))
          "owner-continuation cutoff drops the op — the live target is untouched")
      (is (zero? (count (filter #(= :rf.error/frame-destroyed (:error %)) @errs)))
          "NO frame-destroyed: a live target is never reported destroyed for an owner cutoff"))))

(deftest live-target-not-reported-destroyed-when-only-owner-dies-sync
  (testing "rf2-iqfbg (dispatch-sync) — a captured dispatch-sync whose ORIGINATING
            event OWNER dies during the dispatch seam, while the captured TARGET
            stays LIVE, processes NOTHING (target untouched) and emits NO
            :rf.error/frame-destroyed. The falsey `drained?` is an owner-
            continuation cutoff, NOT target destruction. ADVERSARIAL MUTATION
            TOOTH: pre-fix (the a2x2w gate `(and capture-op (not drained?))`)
            emitted a FALSE frame-destroyed naming the still-live target frame."
    (rf/reg-event :audit/touch (fn [{:keys [db]} _] {:db (assoc db :marked-by :owner-death)}))
    (rf/make-frame {:id :audit/owner  :doc "originating event owner"})
    (rf/make-frame {:id :audit/target :doc "captured target A (stays live)"})
    (let [owner-token  (rf.frame/frame-incarnation-token :audit/owner)
          target-token (rf.frame/frame-incarnation-token :audit/target)
          {:keys [dispatch-sync]} (rf/capture-frame :audit/target)   ;; pins target A
          errs (atom [])]
      (rf/register-listener! :errors ::iqfbg-sync (fn [rec] (swap! errs conj rec)))
      (rf.frame/call-with-event-owner-token :audit/owner owner-token
        (fn [] (run-owner-death-at-dispatch :audit/owner #(dispatch-sync [:audit/touch]))))
      (rf/unregister-listener! :errors ::iqfbg-sync)
      (is (nil? (rf.frame/frame :audit/owner))
          "precondition: the interpose actually destroyed the originating owner")
      (is (rf.frame/frame-incarnation-live? :audit/target target-token)
          "the captured TARGET incarnation is STILL LIVE — only the owner died")
      (is (nil? (:marked-by (rf/app-db-value :audit/target)))
          "owner-continuation cutoff drops the op — the live target is untouched")
      (is (zero? (count (filter #(= :rf.error/frame-destroyed (:error %)) @errs)))
          "NO frame-destroyed: a live target is never reported destroyed for an owner cutoff"))))

;; ---- contract: (capture-frame) outside any scope RAISES (EP-0002) ---------
;;
;; rf2-jue6sp: the no-arg capture form captures ONLY when a real scope
;; exists at capture time. Capturing outside any with-frame / provider
;; raises :rf.error/no-frame-context — it does NOT capture :rf/default.

(deftest capture-frame-outside-with-frame-raises-no-frame-context
  (testing "(capture-frame) with no active scope raises :rf.error/no-frame-context
            instead of capturing :rf/default (rf2-jue6sp)"
    (is (nil? rf.frame/*current-frame*) "no with-frame scope established")
    (let [e (try (rf/capture-frame) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "no-arg capture-frame outside any scope must throw")
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data e)))
          ":rf.error/id is the carried-invariant absence error")
      (is (= :capture-frame (:operation (ex-data e)))
          ":operation attributes the failure to capture-frame"))))

(deftest capture-frame-explicit-frame-works-outside-scope
  (testing "(capture-frame frame-id) needs no scope — the explicit-id capture
            shape for async callbacks / tools / tests (rf2-jue6sp)"
    (rf/reg-event :fh/explicit-touch (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (is (nil? rf.frame/*current-frame*) "no scope established")
    (let [h (rf/capture-frame :rf/default)]
      (is (= :rf/default (:frame h))
          "the explicit frame-id is captured verbatim")
      ((:dispatch h) [:fh/explicit-touch])
      (rf.test-support/poll-until #(:touched? (rf/app-db-value :rf/default))
                               {:label "explicit handle drains to :rf/default"})
      (is (true? (:touched? (rf/app-db-value :rf/default)))
          "the explicit handle routes to the named frame"))))

;; ---- re-frame.frame/bind-fn — the INTERNAL relocated frame-bound-fn* -----
;;
;; API-shrink #1 (rf2-csbbwu) DELETES the public `frame-bound-fn` macro /
;; `frame-bound-fn*` fn from the facade — `capture-frame` (above) is the
;; ONE public HOLD primitive. Its genuinely-different dynamic-rebinding
;; semantics (re-establish `*current-frame*` around an ARBITRARY already-
;; held fn, not a pre-bound op bundle) survive internally as
;; `re-frame.frame/bind-fn` — the Codex caveat this suite pins.

(deftest bind-fn-rebinds-frame-after-scope-unwinds
  (testing "(rf.frame/bind-fn frame-id f) wraps f so *current-frame* is
            re-established on each call, even after the surrounding
            with-frame scope has unwound"
    (rf/make-frame {:id :fbf/A :doc "bind-fn capture target"})
    (rf/reg-event :fbf/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [cb (rf/with-frame :fbf/A
               (rf.frame/bind-fn :fbf/A (fn [] (rf/dispatch [:fbf/inc]))))]
      (is (nil? rf.frame/*current-frame*) "the with-frame scope has unwound")
      (cb)
      (rf.test-support/poll-until #(= 1 (:n (rf/app-db-value :fbf/A)))
                               {:label "bind-fn drains to :fbf/A"})
      (is (= 1 (:n (rf/app-db-value :fbf/A)))
          "bind-fn re-established :fbf/A inside the body"))))

(deftest bind-fn-binds-explicit-frame-with-no-surrounding-scope
  (testing "(rf.frame/bind-fn frame-id f) binds an explicit frame — no
            surrounding with-frame needed"
    (rf/make-frame {:id :fbf/C :doc "bind-fn explicit target"})
    (rf/reg-event :fbf/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [cb (rf.frame/bind-fn :fbf/C (fn [] (rf/dispatch [:fbf/inc])))]
      (is (nil? rf.frame/*current-frame*) "no with-frame scope was ever entered")
      (cb)
      (rf.test-support/poll-until #(= 1 (:n (rf/app-db-value :fbf/C)))
                               {:label "bind-fn drains to :fbf/C"})
      (is (= 1 (:n (rf/app-db-value :fbf/C)))
          "the explicit frame-id was re-established inside the body"))))

(deftest bind-fn-rebinds-around-an-arbitrary-fn-body
  (testing "bind-fn re-establishes the dynamic binding around an ARBITRARY
            already-held fn (e.g. one that itself calls current-frame-id) —
            the genuinely-different semantics from capture-frame's pre-bound
            op bundle that the Codex caveat required to survive internally"
    (rf/make-frame {:id :fbf/D :doc "bind-fn current-frame-id probe"})
    (let [captured (rf/with-frame :fbf/D
                     (rf.frame/bind-fn :fbf/D rf/current-frame-id))]
      (is (nil? rf.frame/*current-frame*) "scope has unwound")
      (is (= :fbf/D (captured))
          "the wrapped arbitrary fn (current-frame-id) reads :fbf/D"))))

;; ---- renamed reads -------------------------------------------------------

(deftest current-frame-id-requires-scope
  (testing "(current-frame-id) reads the established scope's id inside a scope,
            and raises :rf.error/no-frame-context outside any scope — no
            :rf/default floor (rf2-jue6sp / EP-0002)"
    (rf/make-frame {:id :cfi/probe :doc "probe"})
    ;; Inside a scope: the bound id.
    (is (= :cfi/probe (rf/with-frame :cfi/probe (rf/current-frame-id)))
        "inside with-frame: the bound id")
    (is (keyword? (rf/with-frame :cfi/probe (rf/current-frame-id)))
        "always a keyword inside a scope")
    ;; Outside any scope: the carried-invariant absence error.
    (is (nil? rf.frame/*current-frame*) "no with-frame scope established")
    (let [e (try (rf/current-frame-id) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "current-frame-id outside any scope must throw")
      (is (= :rf.error/no-frame-context (:rf.error/id (ex-data e)))
          "raises the carried-invariant absence error instead of :rf/default")
      (is (= :current-frame-id (:operation (ex-data e)))
          ":operation attributes the failure to current-frame-id"))))

(deftest app-db-value-returns-a-value
  (testing "(app-db-value frame-id) returns the app-db VALUE (a plain map), not a container"
    (rf/make-frame {:id :fdb/probe :doc "probe"})
    (rf/reg-event :fdb/seed (fn [{:keys [db]} _] {:db {:k :v}}))
    (rf/dispatch-sync [:fdb/seed] {:frame :fdb/probe})
    (let [db (rf/app-db-value :fdb/probe)]
      (is (map? db) "app-db-value returns a plain map value")
      (is (= :v (:k db)) "the value reflects app-db state")
      (is (not (instance? clojure.lang.IDeref db))
          "it is a VALUE — not a deref-able container"))
    (is (nil? (rf/app-db-value :fdb/never-registered))
        "nil for an unregistered frame")))

;; ---- removed public names are absent -------------------------------------

(deftest removed-public-names-are-absent
  (testing "the deleted public names are NOT interned in re-frame.core"
    ;; `ns-interns` (NOT `ns-resolve`) — `ns-resolve` would follow the
    ;; clojure.core referral for `bound-fn` (we dropped the
    ;; `:refer-clojure :exclude [bound-fn]`, so clojure.core/bound-fn is
    ;; visible again). The contract is that re-frame.core no longer
    ;; INTERNS its own Var under these names.
    (let [interned (ns-interns 're-frame.core)]
      (doseq [sym '[bound-fn dispatcher subscriber get-frame-db current-frame
                    frame-bound-fn frame-bound-fn* frame-value->id]]
        (is (nil? (get interned sym))
            (str "re-frame.core/" sym " must be removed (DELETE, not deprecate)"))))))
