(ns re-frame.ssr.failed-root-isolation-cljs-test
  "Failed-root isolation — Spec 011 §Failed-root isolation. A page is N
  roots, and one failing must not stop the others hydrating and running.

  A sibling that would have succeeded anyway proves nothing, so every
  isolation test fails one root DELIBERATELY, through its own lever, at
  every position on a three-root page, and asserts each sibling both
  hydrated and is interactive (a dispatch reaches it and moves its state):

  | Arm | Lever | Fails at |
  |---|---|---|
  | manifest | a manifest outside the schema family | preflight step 1 |
  | conflict | a payload id already held at a different digest | preflight step 2 |
  | verify | a throwing `:render-tree-fn` | after the seed commits |
  | mount | a throwing `:mount-fn` | after the seed commits |

  Runs on the JVM and the node runner; the browser half is
  `re-frame.ssr.failed-root-isolation-dom-cljs-test`. Handlers are
  registered inside each test, because in the shared node process a sibling
  namespace's `registrar/clear-all!` would wipe ns-load registrations and
  turn the interactivity probe into a no-op."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.boot :as rf.ssr.boot]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

;; Seat the adapter this ns names from a cold slot, and leave it cold: in the
;; shared node bundle a sibling suite may have seated a different adapter,
;; and `init!` refuses to replace one.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

;; The payload-install ledger is process-global and outlives frame resets.
(use-fixtures :each (fn [f] (rf.ssr.install/reset-installed-payloads!) (f)))

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A `:client` frame under an id no other test in this shared process has
  used; frames are not torn down between tests."
  []
  (let [fid (keyword "rf.isolation" (str "f" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(defn- payload-for [db]
  (rf.ssr.payload-policy/build-payload nil db "server-hash-1" {}))

(defn- reg-bump! []
  (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)})))

(defn- hydrated? [fid]
  (= {:count 7} (rf/app-db-value fid)))

(defn- interactive?
  "Does a dispatch reach this frame and move its state?"
  [fid]
  (let [before (:count (rf/app-db-value fid))]
    (rf/dispatch-sync [::bump] {:frame fid})
    (= (inc before) (:count (rf/app-db-value fid)))))

(defn- boom! [what]
  (fn [] (throw (ex-info (str "deliberate " what " failure") {::lever what}))))

(defn- root-specs
  "One root per frame, so a sibling's survival is about the ROOT boundary
  and not shared state; `lever` is merged into the root at `fail-idx`."
  [frames fail-idx lever]
  (vec (map-indexed
        (fn [i fid]
          (cond-> {:frame   fid
                   :root-id (keyword "page" (str "r" i))
                   :payload (payload-for {:count 7})}
            (= i fail-idx) (merge lever)))
        frames)))

(def ^:private manifest-lever {:manifest {:rf.root/schema-version 2}})
(def ^:private verify-lever   {:render-tree-fn (boom! "render-tree-fn")})
(def ^:private mount-lever    {:mount-fn (boom! "mount")})

(defn- poison-with-a-conflicting-payload!
  "Claim `fid` for a DIFFERENT payload, as a page composed from two server
  responses would."
  [fid]
  (rf.ssr.install/payload-install-decision!
   'test fid (rf.ssr.install/payload-content-digest (payload-for {:count 99}))
   :page/other-response))

(defn- assert-isolated!
  "The root at `fail-idx` failed; every other root booted, hydrated and is
  interactive."
  [outcomes frames fail-idx label]
  (is (= (assoc (vec (repeat (count frames) [:hydrated true true])) fail-idx :failed)
         (vec (map-indexed (fn [i outcome]
                             (if (= i fail-idx)
                               (:status outcome)
                               [(:status outcome)
                                (hydrated? (nth frames i))
                                (interactive? (nth frames i))]))
                           outcomes)))
      label))

(defn- run-arm!
  ([label lever] (run-arm! label lever (fn [_fid])))
  ([label lever before-boot!]
   (doseq [fail-idx (range 3)]
     (reg-bump!)
     (let [frames (vec (repeatedly 3 fresh-frame!))]
       (rf.ssr.install/reset-installed-payloads!)
       (before-boot! (nth frames fail-idx))
       (assert-isolated! (rf.ssr/hydrate-page! (root-specs frames fail-idx lever))
                         frames fail-idx (str label " @" fail-idx))))))

(deftest a-root-whose-manifest-is-not-from-the-schema-family-fails-alone
  (run-arm! "manifest" manifest-lever))

(deftest a-root-whose-render-tree-fn-throws-fails-alone
  (run-arm! "verify" verify-lever))

(deftest a-root-whose-mount-throws-fails-alone
  (run-arm! "mount" mount-lever))

(deftest a-root-meeting-a-payload-conflict-fails-alone
  (run-arm! "conflict" nil poison-with-a-conflicting-payload!))

;; ---------------------------------------------------------------------------
;; What a failed root leaves behind
;; ---------------------------------------------------------------------------

(deftest a-root-that-fails-in-preflight-leaves-no-claim
  (testing "a later root referencing that payload installs normally"
    (let [fid (fresh-frame!)]
      (rf.ssr/hydrate-page! [{:frame fid :root-id :page/a
                              :payload (payload-for {:count 7})
                              :manifest {:rf.root/schema-version 2}}])
      (is (= [:hydrated true]
             [(:status (first (rf.ssr/hydrate-page!
                               [{:frame fid :root-id :page/b :payload (payload-for {:count 7})}])))
              (hydrated? fid)])))))

(deftest a-root-whose-seed-does-not-land-releases-its-claim
  (testing "dispatching into a destroyed frame is a no-op, not a throw, so a
            root can claim a payload id and never seed it; the claim must go
            back or the next root reads :already-installed and skips its seed"
    (let [fid (fresh-frame!)]
      (rf/destroy-frame! fid)
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7}) :root-id :page/a})
      (rf/make-frame {:id fid :platform :client})
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7}) :root-id :page/b})
      (is (hydrated? fid)))))

(deftest a-root-that-fails-after-its-seed-commits-keeps-the-payload-installed
  (testing "the claim covers the install, not the whole boot: a sibling root
            sharing the frame must not re-seed over what ran since"
    (reg-bump!)
    (let [fid (fresh-frame!)]
      (rf.ssr/hydrate-page! [{:frame fid :root-id :page/a
                              :payload (payload-for {:count 7})
                              :mount-fn (boom! "mount")}])
      (rf/dispatch-sync [::bump] {:frame fid})
      (rf.ssr/hydrate-page! [{:frame fid :root-id :page/b :payload (payload-for {:count 7})}])
      (is (= {:count 8} (rf/app-db-value fid))))))

;; ---------------------------------------------------------------------------
;; A payload the handler REFUSES never claims
;; ---------------------------------------------------------------------------

(def ^:private refused-payloads
  [["a non-map :rf/app-db"     {:rf/app-db []}]
   ["a non-map :rf/runtime-db" {:rf/app-db {:count 7} :rf/runtime-db []}]])

(deftest a-refused-payload-leaves-no-claim-and-verifies-nothing
  (testing "hydrate! answers as a client-only first load, verifies nothing,
            and a corrected payload for the same frame then installs — a
            stale claim would throw :rf.error/frame-payload-conflict"
    (doseq [[label refused] refused-payloads]
      (let [fid      (fresh-frame!)
            verified (atom 0)
            returned (rf.ssr.boot/hydrate!
                      {:frame fid :payload refused :root-id :page/bad
                       :render-tree-fn (fn [] (swap! verified inc) [:div])})]
        (is (= [nil 0] [returned @verified]) label)
        (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7}) :root-id :page/good})
        (is (hydrated? fid) label)))))

(deftest a-refused-root-cannot-block-a-corrected-sibling-sharing-its-frame
  (testing "a refused first root, a corrected second root on the SAME frame,
            and an unrelated third root all boot"
    (reg-bump!)
    (let [a        (fresh-frame!)
          b        (fresh-frame!)
          mounted  (atom [])
          mount!   (fn [tag] #(swap! mounted conj tag))
          outcomes (rf.ssr/hydrate-page!
                    [{:frame a :root-id :page/bad :payload {:rf/app-db []}
                      :mount-fn (mount! :bad)}
                     {:frame a :root-id :page/good :payload (payload-for {:count 7})
                      :mount-fn (mount! :good)}
                     {:frame b :root-id :page/peer :payload (payload-for {:count 7})
                      :mount-fn (mount! :peer)}])]
      (is (= [[:hydrated :hydrated :hydrated] nil [:bad :good :peer]]
             [(mapv :status outcomes) (:payload (first outcomes)) @mounted])
          "the refused root reports no payload, like a client-only root")
      (is (= [true true true] [(hydrated? a) (interactive? a) (hydrated? b)])))))

;; ---------------------------------------------------------------------------
;; A contained failure is never silent
;; ---------------------------------------------------------------------------

(defn- capture-error-records!
  "Run `f` with an always-on error listener attached -> the records it saw."
  [f]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::capture #(swap! seen conj %))
    (try (f) (finally (rf.error-emit/unregister-error-listener! ::capture)))
    @seen))

(deftest a-contained-root-failure-reaches-the-always-on-error-axis
  (testing "every contained root failure emits one always-on record naming
            the root, and `:phase` says whether its seed had committed"
    (let [frames  (vec (repeatedly 3 fresh-frame!))
          specs   (-> (root-specs frames 0 manifest-lever)
                      (assoc-in [2 :mount-fn] (boom! "mount")))
          records (capture-error-records! #(rf.ssr/hydrate-page! specs))]
      (is (= [{:root-id :page/r0 :frame (nth frames 0) :phase :hydrate
               :recovery :warned-and-continued :exception? true}
              {:root-id :page/r2 :frame (nth frames 2) :phase :mount
               :recovery :warned-and-continued :exception? true}]
             (->> records
                  (filter #(= :rf.error/root-boot-failed (:error %)))
                  (mapv #(-> (select-keys % [:root-id :frame :phase :recovery])
                             (assoc :exception? (some? (:exception %)))))))))))

(deftest hydrating-a-frame-that-is-not-live-returns-nil-and-reports-it
  (testing "nothing was applied, so hydrate! returns nil, the seed's own
            :rf.error/frame-destroyed record names the frame, and the claim is
            released"
    (doseq [[label fid] [["destroyed"  (let [fid (fresh-frame!)]
                                         (rf/destroy-frame! fid)
                                         fid)]
                         ["never made" (keyword "rf.isolation"
                                                (str "never" (swap! frame-counter inc)))]]]
      (let [returned (atom ::not-called)
            records  (capture-error-records!
                      #(reset! returned
                               (rf.ssr.boot/hydrate! {:frame   fid
                                                      :payload (payload-for {:count 7})
                                                      :root-id :page/a})))]
        (is (= [nil true nil]
               [@returned
                (boolean (some #(and (= :rf.error/frame-destroyed (:error %)) (= fid (:frame %)))
                               records))
                (rf.ssr.install/installed-payload fid)])
            label)))))

;; ---------------------------------------------------------------------------
;; The boundary is isolation, not recovery
;; ---------------------------------------------------------------------------

(deftest a-failed-root-stays-failed
  (testing "no retry, no supervision, no fallback render"
    (let [fid      (fresh-frame!)
          attempts (atom 0)
          outcome  (first (rf.ssr/hydrate-page!
                           [{:frame fid :root-id :page/a
                             :payload (payload-for {:count 7})
                             :mount-fn (fn [] (swap! attempts inc)
                                         (throw (ex-info "no" {})))}]))]
      (is (= [1 :failed true] [@attempts (:status outcome) (some? (:error outcome))])
          "attempted once; the throwable is handed back"))))

(deftest outcomes-come-back-in-input-order
  (testing "so a caller correlates a root that died before its id could be
            read from a manifest"
    (let [frames (vec (repeatedly 3 fresh-frame!))]
      (is (= [[:page/r0 :hydrated] [:page/r1 :failed] [:page/r2 :hydrated]]
             (mapv (juxt :root-id :status)
                   (rf.ssr/hydrate-page! (root-specs frames 1 manifest-lever))))))))

(deftest an-all-healthy-page-boots-every-root
  (reg-bump!)
  (let [frames   (vec (repeatedly 3 fresh-frame!))
        mounted  (atom [])
        specs    (mapv (fn [spec] (assoc spec :mount-fn #(swap! mounted conj (:root-id spec))))
                       (root-specs frames nil nil))
        outcomes (rf.ssr/hydrate-page! specs)]
    (is (= [[:hydrated :hydrated :hydrated] [:page/r0 :page/r1 :page/r2]]
           [(mapv :status outcomes) @mounted]))
    (is (= (repeat 3 [true true]) (mapv (juxt hydrated? interactive?) frames)))))
