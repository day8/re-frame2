(ns re-frame.live-frame-reload-cljs-test
  "EP-0023 §Hot Reload / §Default Image Semantics — image hot reload is
  re-construction: re-calling `make-frame` against the SAME `:id` swaps the
  generation on the frame's record (EP-0024: the one `rf.frame/frames`
  registry) while preserving frame memory, and a source-store change
  reprojects affected EXPLICIT-image frames, not only default-image ones —
  by hand through `reproject-live-frames!`, or automatically through the
  registration hook's coalesced flush. A no-id frame has no id to reload
  against: making another creates a fresh local-only frame. A bad `:images`
  meets the same guard as any `make-frame`
  (`live-frame-cljs-test/non-vector-images-rejected`).

  The reload/diff cases resolve against an explicit descriptor pool
  (`make-frame`'s 2-arity); the reprojection cases use the LIVE source store
  and snapshot/restore it around the case (never `rf.registrar/clear-all!`,
  which would wipe shared registrations). Every case that lets the
  registration hook arm a flush redefs `rf.interop/next-tick` for its whole
  body and teardown, so no deferred tick can drain the shared pending flag
  out from under the synchronous flush the case asserts on."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core         :as rf]
            [re-frame.error-emit   :as rf.error-emit]
            [re-frame.events       :as rf.events]
            [re-frame.frame        :as rf.frame]
            [re-frame.image        :as rf.image]
            [re-frame.image-assembly :as rf.image-assembly]
            [re-frame.interop      :as rf.interop]
            [re-frame.registrar    :as rf.registrar]
            [re-frame.source-store :as rf.source-store]
            [re-frame.live-frame   :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; The runtime fixture snapshot/restores the registrar and resets
;; `rf.frame/frames`, which clears every record AND its generation.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  (fn [t]
    (rf.image-assembly/clear-standards!)
    (t)
    (rf.image-assembly/clear-standards!)))

(defn- reg-desc
  "A synthetic registered descriptor authored in `provenance-ns`, shaped like a
  source-store entry."
  [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(defn- err-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

(defn- resolved-handler
  "The impl `frame-id`'s current generation resolves for `[kind id]`."
  [frame-id kind id]
  (:handler-fn (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation frame-id) kind id)))

;; One image over "counter.core"; the reload changes only the descriptor POOL,
;; as a same-namespace reg-* re-eval does. Across v1 -> v2 :counter/inc changes
;; impl, :counter/value is identical in provenance AND impl (retained), and
;; :counter/reset is added.
(def ^:private value-desc (reg-desc "counter.core" :sub :counter/value ::value))

(def ^:private pool-v1
  [(reg-desc "counter.core" :event :counter/inc ::inc-v1)
   value-desc])

(def ^:private pool-v2
  [(reg-desc "counter.core" :event :counter/inc   ::inc-v2)
   value-desc
   (reg-desc "counter.core" :event :counter/reset ::reset)])

(def ^:private img (rf.image/image {:id :counter/img :select-ns {:include ["counter.core"]}}))

;; ---- re-make-frame: swap the generation, keep the memory ------------------

(deftest reload-swaps-generation-preserving-frame-memory
  ;; the seed resolves the `:rf/set-db` standard through the sealed generation,
  ;; so this case re-seeds that one standard after the fixture's clear
  (rf.events/register-set-db-standard!)
  (rf.image-assembly/clear-generation-cache!)
  (let [old-gen  (rf.live-frame/frame-generation
                   (rf.live-frame/make-frame {:id :counter/main
                                              :images [img]
                                              :initial-events [[:rf/set-db {:count 7}]]
                                              :adapter ::reagent}
                                             pool-v1))
        reloaded (rf.live-frame/make-frame {:id :counter/main :images [img]} pool-v2)]
    ;; a new generation on the same id, and the app-db seeded at creation
    ;; survives — not a teardown/recreate
    (is (= [true :counter/main {:count 7}]
           [(not= old-gen (rf.live-frame/frame-generation reloaded))
            (rf.frame/frame-value->id reloaded)
            (rf/app-db-value :counter/main)]))))

(deftest reload-report-carries-the-shadow-report
  ;; EP-0026 §Shadow Report: after a reload, frame-shadows reads the NEW
  ;; generation's cross-image shadows — an ordinary read, not a reload verb
  (let [override (rf.image/image {:id :counter/override
                                  :registrations {:reg-event [[:counter/inc (fn [_ _] {})]]}})]
    (rf.live-frame/make-frame {:id :counter/main :images [img]} pool-v1)
    (is (empty? (rf.live-frame/frame-shadows :counter/main)))
    (rf.live-frame/make-frame {:id :counter/main :images [img override]} pool-v1)
    (is (= [{:registration [:event :counter/inc]
             :image        :counter/img
             :shadowed-by  :counter/override}]
           (rf.live-frame/frame-shadows :counter/main)))))

(deftest reload-is-frame-targeted-does-not-move-siblings
  ;; two frames from the SAME image inputs; reloading one leaves the other's
  ;; generation untouched
  (rf.live-frame/make-frame {:id :counter/left  :images [img]} pool-v1)
  (let [right-gen-before (rf.live-frame/frame-generation
                           (rf.live-frame/make-frame {:id :counter/right :images [img]} pool-v1))]
    (rf.live-frame/make-frame {:id :counter/left :images [img]} pool-v2)
    (is (= [true ::inc-v2]
           [(identical? right-gen-before (rf.live-frame/frame-generation :counter/right))
            (resolved-handler :counter/left :event :counter/inc)]))))

(deftest generation-diff-is-pure-and-correct
  ;; every [kind id] is added / changed / removed / retained by descriptor value
  (let [g1 (rf.image-assembly/assemble [img] pool-v1)
        g2 (rf.image-assembly/assemble [img] pool-v2)]
    (is (= {:added #{[:event :counter/reset]} :changed #{[:event :counter/inc]}
            :retained #{[:sub :counter/value]} :removed #{}}
           (select-keys (rf.live-frame/generation-diff g1 g2) [:added :changed :retained :removed])))
    (testing "two equal generations diff to all-retained"
      (is (= [true true true #{[:event :counter/inc] [:sub :counter/value]}]
             (let [d (rf.live-frame/generation-diff g1 g1)]
               [(empty? (:added d)) (empty? (:changed d)) (empty? (:removed d)) (:retained d)]))))))

;; ---- reproject-live-frames! over explicit-image frames --------------------

(deftest reproject-removed-leg-forgets-a-selected-descriptor
  ;; a selected descriptor forgotten from the source store: reproject narrows
  ;; the frame's generation and names the id under :removed
  (let [snapshot @rf.source-store/kind->id->ns->descriptor]
    (try
      (rf.source-store/record-descriptor!
        :event :rm/inc
        {:rf.provenance/ns "removal.feature" :kind :event :id :rm/inc :handler-fn ::rm-inc})
      (rf.source-store/record-descriptor!
        :sub :rm/value
        {:rf.provenance/ns "removal.feature" :kind :sub :id :rm/value :handler-fn ::rm-value})
      (rf.live-frame/make-frame {:id :rm/main
                                 :images [(rf.image/image {:id :rm/img :select-ns {:include ["removal.feature"]}})]})
      (rf.source-store/forget-descriptor! :sub :rm/value "removal.feature")
      (let [diff (get (rf.live-frame/reproject-live-frames!) :rm/main)]
        (is (= [true false false true nil ::rm-inc]
               [(contains? (:removed diff) [:sub :rm/value])
                (contains? (:changed diff) [:sub :rm/value])
                (contains? (:added diff)   [:sub :rm/value])
                (contains? (:retained diff) [:event :rm/inc])
                (rf.image-assembly/resolve-descriptor (rf.live-frame/frame-generation :rm/main) :sub :rm/value)
                (resolved-handler :rm/main :event :rm/inc)])
            "the frame narrowed: the forgotten id is gone, :rm/inc still resolves"))
      (finally
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

(deftest reproject-composed-frame-on-one-member-ns-change
  ;; a frame composed of two images over distinct namespaces reprojects when
  ;; ONE member's source changes: that id is :changed, the other :retained, and
  ;; both still resolve
  (let [snapshot @rf.source-store/kind->id->ns->descriptor]
    (try
      (rf.source-store/record-descriptor!
        :event :compose.a/go
        {:rf.provenance/ns "compose.member-a" :kind :event :id :compose.a/go :handler-fn ::a-original})
      (rf.source-store/record-descriptor!
        :event :compose.b/go
        {:rf.provenance/ns "compose.member-b" :kind :event :id :compose.b/go :handler-fn ::b-stable})
      (rf.live-frame/make-frame {:id :compose/main
                                 :images [(rf.image/image {:id :compose/a :select-ns {:include ["compose.member-a"]}})
                                          (rf.image/image {:id :compose/b :select-ns {:include ["compose.member-b"]}})]})
      (rf.source-store/record-descriptor!
        :event :compose.a/go
        {:rf.provenance/ns "compose.member-a" :kind :event :id :compose.a/go :handler-fn ::a-reloaded})
      (let [diff (get (rf.live-frame/reproject-live-frames!) :compose/main)]
        (is (= [true true false ::a-reloaded ::b-stable]
               [(contains? (:changed diff) [:event :compose.a/go])
                (contains? (:retained diff) [:event :compose.b/go])
                (contains? (:changed diff) [:event :compose.b/go])
                (resolved-handler :compose/main :event :compose.a/go)
                (resolved-handler :compose/main :event :compose.b/go)])))
      (finally
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

;; ---- auto-reprojection: reg-* reprojects without a manual call ------------
;;
;; `rf.registrar/add-registration-hook!` wires reg-* to mark-dirty + a
;; coalesced `next-tick` flush. These cases drive the change through
;; `rf.registrar/register!` (the path every reg-* funnels through, and the one
;; that fires the hook) and never call `reproject-live-frames!`; the flush is
;; forced synchronously with `flush-pending-reprojection!`, which finds
;; nothing pending unless the hook marked it.

(deftest reg-star-change-auto-reprojects-explicit-image-frame
  (let [snapshot @rf.source-store/kind->id->ns->descriptor]
    (try
      (with-redefs [rf.interop/next-tick (fn [_f] nil)]
        (rf.live-frame/flush-pending-reprojection!)
        (rf.registrar/register! :event :auto/inc
          {:rf.provenance/ns "auto.feature" :handler-fn ::auto-v1})
        (rf.live-frame/flush-pending-reprojection!)
        (rf.live-frame/make-frame {:id :auto/main
                                   :images [(rf.image/image {:id :auto/img :select-ns {:include ["auto.feature"]}})]})
        (rf.registrar/register! :event :auto/inc
          {:rf.provenance/ns "auto.feature" :handler-fn ::auto-v2})
        (let [moved (rf.live-frame/flush-pending-reprojection!)]
          (is (= [true ::auto-v2]
                 [(contains? (:changed (get moved :auto/main)) [:event :auto/inc])
                  (resolved-handler :auto/main :event :auto/inc)]))))
      (finally
        ;; `unregister!` marks dirty too, so clear the live frame BEFORE
        ;; draining, or the drain would reproject :auto/main against the
        ;; forgotten descriptor and zero-match in teardown
        (with-redefs [rf.interop/next-tick (fn [_f] nil)]
          (reset! rf.frame/frames {})
          (rf.registrar/unregister! :event :auto/inc)
          (rf.live-frame/flush-pending-reprojection!))
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

(deftest reg-star-burst-coalesces-to-one-flush
  ;; a burst of reg-* over a live frame's namespace schedules at most ONE
  ;; deferred flush (only the false->true edge of the pending flag schedules),
  ;; and the flag re-arms after a drain. The JVM schedules no tick at all —
  ;; it marks the flag and the read-time consult / explicit flush drains it.
  (let [snapshot  @rf.source-store/kind->id->ns->descriptor
        scheduled (atom 0)
        captured  (atom nil)]
    (try
      ;; the image's selector must match a loaded registration, so record one
      ;; first; make-frame fires the hook, so drain before the burst
      (rf.source-store/record-descriptor!
        :event :burst/seed
        {:rf.provenance/ns "burst.feature" :kind :event :id :burst/seed :handler-fn ::burst-seed})
      (rf.live-frame/make-frame {:id :burst/main
                                 :images [(rf.image/image {:id :burst/img :select-ns {:include ["burst.feature"]}})]})
      (rf.live-frame/flush-pending-reprojection!)
      ;; count schedules and capture the tick without running it, so the flag
      ;; stays set through the burst as a real deferred tick would leave it
      (with-redefs [rf.interop/next-tick (fn [f] (swap! scheduled inc) (reset! captured f) nil)]
        (doseq [n (range 5)]
          (rf.registrar/register! :event (keyword "burst" (str "e" n))
            {:rf.provenance/ns "burst.feature" :handler-fn (keyword "impl" (str n))}))
        (is (= #?(:cljs 1 :clj 0) @scheduled))
        (if-let [tick @captured] (tick) (rf.live-frame/flush-pending-reprojection!))
        (rf.registrar/register! :event :burst/e0
          {:rf.provenance/ns "burst.feature" :handler-fn ::e0-again})
        (is (= #?(:cljs 2 :clj 0) @scheduled) "a post-drain reg-* re-arms")
        #?(:clj (is (seq (rf.live-frame/flush-pending-reprojection!))
                    "JVM: the post-drain reg-* re-armed the flag")))
      (finally
        ;; forget the live frame before draining, so no pending reproject
        ;; re-assembles its image after the burst descriptors are unregistered
        (reset! rf.frame/frames {})
        (rf.live-frame/flush-pending-reprojection!)
        (doseq [n (range 5)]
          (rf.registrar/unregister! :event (keyword "burst" (str "e" n))))
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

(deftest reg-star-burst-with-no-live-frame-schedules-nothing
  ;; the hang guard: with no live frame to reproject the hook is a no-op, so a
  ;; registration burst marks nothing and schedules nothing — one no-op tick
  ;; per reg-* across the bundle's thousands would flood the host task queue
  ;; and hang cljs.test's async scheduling
  (let [snapshot  @rf.source-store/kind->id->ns->descriptor
        scheduled (atom 0)]
    (try
      (reset! rf.frame/frames {})
      (rf.live-frame/flush-pending-reprojection!)
      (with-redefs [rf.interop/next-tick (fn [_f] (swap! scheduled inc) nil)]
        (doseq [n (range 50)]
          (rf.registrar/register! :event (keyword "noframe" (str "e" n))
            {:rf.provenance/ns "noframe.feature" :handler-fn (keyword "impl" (str n))}))
        (is (= [0 true] [@scheduled (empty? (rf.live-frame/flush-pending-reprojection!))])))
      (finally
        (doseq [n (range 50)]
          (rf.registrar/unregister! :event (keyword "noframe" (str "e" n))))
        (rf.live-frame/flush-pending-reprojection!)
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

(deftest flush-swaps-generations-and-never-re-arms-itself
  ;; a flush swaps generations through `rf.frame/set-generation!`, a plain
  ;; swap! rather than a register!, so a flush that does real work fires no
  ;; hook and schedules no successor
  (let [snapshot  @rf.source-store/kind->id->ns->descriptor
        scheduled (atom 0)]
    (try
      (rf.source-store/record-descriptor!
        :event :reentry/inc
        {:rf.provenance/ns "reentry.feature" :kind :event :id :reentry/inc :handler-fn ::v1})
      (rf.live-frame/make-frame {:id :reentry/main
                                 :images [(rf.image/image {:id :reentry/img :select-ns {:include ["reentry.feature"]}})]})
      (rf.live-frame/flush-pending-reprojection!)
      (with-redefs [rf.interop/next-tick (fn [_f] (swap! scheduled inc) nil)]
        (rf.registrar/register! :event :reentry/inc
          {:rf.provenance/ns "reentry.feature" :handler-fn ::v2})
        (is (= #?(:cljs 1 :clj 0) @scheduled) "the live-frame reg-* armed one flush")
        (let [moved (rf.live-frame/flush-pending-reprojection!)]
          (is (= [true ::v2 #?(:cljs 1 :clj 0)]
                 [(contains? moved :reentry/main)
                  (resolved-handler :reentry/main :event :reentry/inc)
                  @scheduled])
              "the flush did real work and scheduled no successor")))
      (finally
        ;; forget the frame before draining: a pending reproject would
        ;; re-assemble its image after the descriptor below is forgotten
        (reset! rf.frame/frames {})
        (rf.live-frame/flush-pending-reprojection!)
        (rf.registrar/unregister! :event :reentry/inc)
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

;; ---- generation provenance: an explicit-pool frame reprojects against its
;; own pool, never the live store -------------------------------------------

(deftest failed-re-construction-preserves-generation-provenance-rf2-ktmto9
  ;; the pool row is recorded AFTER the engine commit, so a FAILED re-make
  ;; threading a different pool cannot clobber it — a clobbered row would make
  ;; the next reprojection zero-match (the frame's ns is only in its own pool)
  (let [pool       [(reg-desc "ktmto9-pool.provenance.ns" :event :ktmto9-pool/inc ::pool-v1)]
        other-pool [(reg-desc "ktmto9-other.provenance.ns" :event :ktmto9-other/inc ::other)]
        img        (rf.image/image {:id :ktmto9-pool/img :select-ns {:include ["ktmto9-pool.provenance.ns"]}})
        gen-before (rf.live-frame/frame-generation
                     (rf.live-frame/make-frame {:id :ktmto9-pool/main :images [img]} pool))]
    (is (= [:rf.error/on-create-retired true nil ::pool-v1]
           [(err-id #(rf.live-frame/make-frame {:id :ktmto9-pool/main :on-create [:boom]} other-pool))
            (identical? gen-before (rf.live-frame/frame-generation :ktmto9-pool/main))
            (rf.live-frame/reproject-live-frame! :ktmto9-pool/main)
            (resolved-handler :ktmto9-pool/main :event :ktmto9-pool/inc)]))))

#?(:clj
   (deftest failed-first-construction-records-no-provenance-rf2-ktmto9
     ;; a failed FIRST make-frame writes no record and no provenance row
     ;; (JVM-only read of the private provenance table)
     (let [pool [(reg-desc "ktmto9-first.provenance.ns" :event :ktmto9-first/inc ::pool-v1)]]
       (is (= [:rf.error/on-create-retired false false]
              [(err-id #(rf.live-frame/make-frame {:id :ktmto9-first/never :on-create [:boom]} pool))
               (contains? (set (rf.frame/frame-ids)) :ktmto9-first/never)
               (contains? (deref @#'rf.live-frame/frame-generation-pool) :ktmto9-first/never)])))))

(deftest reproject-live-frames-mixes-explicit-pool-and-live-store-frames-safely
  ;; one sweep over an explicit-pool frame and a live-store frame: each
  ;; reprojects against its OWN recorded provenance
  (let [snapshot @rf.source-store/kind->id->ns->descriptor]
    (try
      (with-redefs [rf.interop/next-tick (fn [_f] nil)]
        (rf.live-frame/flush-pending-reprojection!)
        (rf.source-store/record-descriptor!
          :event :rpf-live/inc
          {:rf.provenance/ns "rpf-live.provenance.ns" :kind :event :id :rpf-live/inc :handler-fn ::live-v1})
        (rf.live-frame/make-frame {:id :rpf-live/main
                                   :images [(rf.image/image {:id :rpf-live/img :select-ns {:include ["rpf-live.provenance.ns"]}})]})
        (let [pool-gen-before
              (rf.live-frame/frame-generation
                (rf.live-frame/make-frame
                  {:id :rpf-mix-pool/main
                   :images [(rf.image/image {:id :rpf-mix-pool/img :select-ns {:include ["rpf-mix-pool.provenance.ns"]}})]}
                  [(reg-desc "rpf-mix-pool.provenance.ns" :event :rpf-mix-pool/inc ::pool-v1)]))]
          (rf.source-store/record-descriptor!
            :event :rpf-live/inc
            {:rf.provenance/ns "rpf-live.provenance.ns" :kind :event :id :rpf-live/inc :handler-fn ::live-v2})
          (let [moved (rf.live-frame/reproject-live-frames!)]
            (is (= [true false true]
                   [(contains? moved :rpf-live/main)
                    (contains? moved :rpf-mix-pool/main)
                    (identical? pool-gen-before (rf.live-frame/frame-generation :rpf-mix-pool/main))])))))
      (finally
        (reset! rf.frame/frames {})
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

;; ---- deferred-flush resilience ---------------------------------------------
;;
;; `deferred-flush!` isolates each frame's reprojection, so one failure neither
;; stops the sweep reaching the rest nor goes unreported
;; (`:rf.error/reprojection-failed`). `rf.frame/image-loaded-frame-ids` is
;; fixed to put the bad frame FIRST: the real registry iterates a hash-set,
;; and an order with the good frame first would let an all-or-nothing sweep
;; pass too.

(deftest deferred-flush-does-not-abort-mid-sweep-on-one-frame-failure
  (let [snapshot  @rf.source-store/kind->id->ns->descriptor
        diagnosed (atom [])
        reported  (atom [])]
    (try
      (with-redefs [rf.interop/next-tick (fn [_f] nil)]
        (rf.live-frame/flush-pending-reprojection!))
      (rf.registrar/register! :event :rpf-good/inc
        {:rf.provenance/ns "rpf-good.provenance.ns" :handler-fn ::good-v1})
      (rf.registrar/register! :event :rpf-bad/inc
        {:rf.provenance/ns "rpf-bad.provenance.ns" :handler-fn ::bad-v1})
      (let [tick (atom nil)]
        ;; capture (never run) every scheduled tick, and fix the sweep order
        ;; for the whole case
        (with-redefs [rf.interop/next-tick (fn [f] (reset! tick f) nil)
                      rf.frame/image-loaded-frame-ids
                      (fn [] [:rpf-bad/main :rpf-good/main])]
          (rf.live-frame/make-frame {:id :rpf-good/main
                                     :images [(rf.image/image {:id :rpf-good/img :select-ns {:include ["rpf-good.provenance.ns"]}})]})
          (rf.live-frame/make-frame {:id :rpf-bad/main
                                     :images [(rf.image/image {:id :rpf-bad/img :select-ns {:include ["rpf-bad.provenance.ns"]}})]})
          (rf.registrar/register! :event :rpf-good/inc
            {:rf.provenance/ns "rpf-good.provenance.ns" :handler-fn ::good-v2})
          ;; forgetting the bad frame's whole namespace makes its reproject
          ;; zero-match
          (rf.registrar/unregister! :event :rpf-bad/inc)
          (rf/register-listener! :trace ::rpf-rec
            (fn [ev] (when (= :rf.error/reprojection-failed (:operation ev))
                       (swap! diagnosed conj ev))))
          (rf.error-emit/register-error-listener! ::rpf-rec
            (fn [record] (when (= :rf.error/reprojection-failed (:error record))
                           (swap! reported conj record))))
          (try
            ;; the JVM captures no tick, so it drives the deferred body directly
            (is (nil? (if-let [f @tick] (f) (#'rf.live-frame/deferred-flush!))))
            (finally
              (rf/unregister-listener! :trace ::rpf-rec)
              (rf.error-emit/unregister-error-listener! ::rpf-rec))))
        (is (= ::good-v2 (resolved-handler :rpf-good/main :event :rpf-good/inc))
            "the sweep reached the good frame despite the bad one failing first")
        (is (= [:rpf-bad/main] (map :frame @reported))
            "the always-on record names only the failed frame, in every build")
        (is (some? (:exception (first @reported)))
            "a cause other than a duplicate id rides as the raw exception")
        ;; the dev trace is silent under -Dre-frame.debug=false
        (when rf.interop/debug-enabled?
          (is (= [:rpf-bad/main] (map #(get-in % [:tags :frame]) @diagnosed)))))
      (finally
        (with-redefs [rf.interop/next-tick (fn [_f] nil)]
          (reset! rf.frame/frames {})
          (rf.live-frame/flush-pending-reprojection!))
        (rf.registrar/unregister! :event :rpf-good/inc)
        (reset! rf.source-store/kind->id->ns->descriptor snapshot)))))

(deftest read-time-flush-makes-late-registration-visible-same-tick
  ;; make-frame -> reg-event -> dispatch-sync in ONE tick, with the deferred
  ;; tick disabled: only the read-time flush in the resolution seam
  ;; (`call-with-frame-resolution`) can have reprojected
  (with-redefs [rf.interop/next-tick (fn [_f] nil)]
    (rf/make-frame {:id :rtf/main})
    (rf/reg-event :rtf/hit (fn [{:keys [db]} _] {:db (assoc db :hit? true)}))
    (rf/dispatch-sync [:rtf/hit] {:frame :rtf/main})
    (is (true? (:hit? (rf/app-db-value :rtf/main))))))
