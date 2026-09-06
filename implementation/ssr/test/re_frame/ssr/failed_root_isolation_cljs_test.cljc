(ns re-frame.ssr.failed-root-isolation-cljs-test
  "Failed-root isolation (S5-C) — Spec 011 §Failed-root isolation.

  **A page is N roots, and one of them failing must not stop the others
  from hydrating and running.** That is the whole contract, and it is the
  ergonomics payoff of the stage: a page assembled from independently
  rendered regions stays up when one region is broken.

  ## The proof obligation, and why it is shaped this way

  A test where the surviving roots happen to succeed proves nothing —
  they would have succeeded anyway. Every isolation test here therefore:

  1. fails a root **deliberately**, through a named lever;
  2. asserts the siblings **hydrated** (they hold the server slice); AND
  3. asserts the siblings are **interactive** — a dispatch reaches them
     and moves their state. A root that hydrated but cannot receive a
     dispatch is not \"running\"; app-db equality alone would not notice.

  ## Every arm has its OWN lever

  Four independent ways a root dies, each failing at a different point in
  the boot, each with its own falsification:

  | Arm | Lever | Fails at |
  |---|---|---|
  | manifest | a manifest outside the schema family | preflight step 1 |
  | conflict | a payload id already held at a different digest | preflight step 2 |
  | verify | a throwing `:render-tree-fn` | after the seed commits |
  | mount | a throwing `:mount-fn` | after the seed commits |

  One blanket mutation could red some of these while leaving others
  green, which would certify a falsifiability it never established. So
  each arm is failed by its own lever and falsified by its own
  `boot-page-without-isolation!` counterpart.

  ## The red-before is permanent and executable

  `boot-page-without-isolation!` IS `boot-one-root!` with the try/catch
  deleted — the bare hydrate-then-mount loop a host writes when it does
  not use the boundary. Every isolation arm has a
  `without-the-boundary-*` twin that runs the same page through it and
  measures the damage: the failing root's throw escapes and the roots
  after it never boot. Delete the guard from `hydrate-page!` and the
  isolation arms reproduce their twins' outcome. So the guard cannot
  decay into a tautology.

  ## Both hosts

  A `.cljc` named `*-cljs-test`, so it runs under BOTH `clojure -M:test`
  from `implementation/ssr` (JVM) and the node runner
  (`npm run test:cljs`). The browser half — a real N-root document with
  adjacency-discovered manifests — is
  `re-frame.ssr.failed-root-isolation-dom-cljs-test`.

  Handlers are registered INSIDE each test body, never at ns-load: in the
  shared node process a sibling namespace's `registrar/clear-all!` wipes
  ns-load-time registrations, which would silently turn the interactivity
  probe into a no-op and let it pass for the wrong reason."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            ;; JVM-only: the sole executable read is the `#?(:clj …)`
            ;; `with-redefs [interop/debug-enabled? false]` arm below.
            #?(:clj [re-frame.interop :as rf.interop])
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.boot :as rf.ssr.boot]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

(use-fixtures :once (fn [f] (rf/init! rf.ssr/adapter) (f)))

;; `installed-payloads` is a process-global `defonce` ledger keyed by payload
;; id, outside app-db and untouched by `clear-all!` or a `frame/frames` reset.
;; A test that installs a payload and does not reset it leaks into every
;; sibling test in the shared runner process.
(use-fixtures :each (fn [f] (rf.ssr.install/reset-installed-payloads!) (f)))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A `:client`-platform frame under an id no other test in this shared
  process has used. Frames are not torn down between tests, so a reused
  id would carry a prior test's app-db into this one.

  `make-frame` opts are FLAT — `:platform` sits alongside `:id`. A nested
  `{:config {:platform :client}}` stores `:config {:config {…}}` and the
  frame is never platform-tagged at all; every test below would still pass,
  because the runtime resolves the platform as
  `(or (-> rec :config :platform) (interop/active-platform))` and the
  host-wide marker is already `:client` on CLJS.
  `the-fixture-frames-are-actually-platform-tagged` is what keeps that
  accident from coming back."
  []
  (let [fid (keyword "rf.isolation" (str "f" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(defn- payload-for
  "The page-wide hydration payload, built by the SHIPPED assembler so
  these tests track the real wire shape."
  [db]
  (rf.ssr.payload-policy/build-payload nil db "server-hash-1" {}))

(defn- reg-bump!
  "Register the mutation the interactivity probe dispatches. Per-test —
  see the ns docstring."
  []
  (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)})))

(defn- hydrated?
  "Did this frame receive the server slice?"
  [fid]
  (= {:count 7} (rf/app-db-value fid)))

(defn- interactive?
  "Does a dispatch REACH this frame and move its state? A root that
  hydrated but cannot receive a dispatch is not running, and an app-db
  equality check alone would call it healthy."
  [fid]
  (let [before (:count (rf/app-db-value fid))]
    (rf/dispatch-sync [::bump] {:frame fid})
    (= (inc before) (:count (rf/app-db-value fid)))))

(defn- boom! [what]
  (fn [] (throw (ex-info (str "deliberate " what " failure") {::lever what}))))

;; ---------------------------------------------------------------------------
;; The page, and the four levers
;; ---------------------------------------------------------------------------

(defn- root-specs
  "N root specs over N frames — one root per frame, so a sibling's
  survival is about the ROOT boundary and not about sharing state with
  the failing one. `lever` is merged into the root at `fail-idx`."
  [frames fail-idx lever]
  (vec (map-indexed
        (fn [i fid]
          (cond-> {:frame   fid
                   :root-id (keyword "page" (str "r" i))
                   :payload (payload-for {:count 7})}
            (= i fail-idx) (merge lever)))
        frames)))

(def ^:private manifest-lever
  "Preflight step 1: a value whose `:rf.root/schema-version` is not 1 is
  not from the manifest schema family at all."
  {:manifest {:rf.root/schema-version 2}})

(def ^:private verify-lever
  "After the seed commits: the host's own client-tree computation throws."
  {:render-tree-fn (boom! "render-tree-fn")})

(def ^:private mount-lever
  "After the seed commits: the host's own mount throws."
  {:mount-fn (boom! "mount")})

(defn- poison-with-a-conflicting-payload!
  "Preflight step 2: claim `fid` for a DIFFERENT payload, so the root that
  arrives next meets `:rf.error/frame-payload-conflict`. This is a page
  composed from fragments rendered by two different server responses."
  [fid]
  (rf.ssr.install/payload-install-decision!
   'test fid (rf.ssr.install/payload-content-digest (payload-for {:count 99}))
   :page/other-response))

(defn- boot-page-without-isolation!
  "THE RED-BEFORE, kept permanently executable: `boot-one-root!` with the
  try/catch deleted. This is the bare loop a host writes without the
  boundary — hydrate each root, mount it, move on. The first failure
  escapes and every root after it is never reached."
  [specs]
  (mapv (fn [{:keys [mount-fn] :as spec}]
          (let [payload (rf.ssr.boot/hydrate! (dissoc spec :mount-fn))]
            (when mount-fn (mount-fn))
            payload))
        specs))

;; ---------------------------------------------------------------------------
;; The isolation arms — one lever each, every position
;; ---------------------------------------------------------------------------

(defn- assert-isolated!
  "The contract, asserted over one page: the root at `fail-idx` failed,
  and EVERY other root hydrated and is interactive."
  [outcomes frames fail-idx label]
  (is (= :failed (:status (nth outcomes fail-idx)))
      (str label ": the root the lever targeted failed"))
  (doseq [i (range (count frames))
          :when (not= i fail-idx)]
    (is (= :hydrated (:status (nth outcomes i)))
        (str label ": sibling root " i " booted despite root "
             fail-idx " failing"))
    (is (hydrated? (nth frames i))
        (str label ": sibling root " i " holds the server slice"))
    (is (interactive? (nth frames i))
        (str label ": sibling root " i " is RUNNING — a dispatch reaches "
             "it and moves its state"))))

(defn- run-arm!
  "Run one lever at EVERY position on a 3-root page. Failing only root 0
  would prove nothing about a failure in the middle or at the end, so the
  passing case is never positional."
  [label lever]
  (doseq [fail-idx (range 3)]
    (reg-bump!)
    (let [frames (vec (repeatedly 3 fresh-frame!))
          specs  (root-specs frames fail-idx lever)]
      (rf.ssr.install/reset-installed-payloads!)
      (assert-isolated! (rf.ssr/hydrate-page! specs) frames fail-idx
                        (str label " @" fail-idx)))))

;; ---------------------------------------------------------------------------
;; The fixture's own tag, asserted so it cannot lapse
;; ---------------------------------------------------------------------------

(deftest the-fixture-frames-are-actually-platform-tagged
  (testing "`fresh-frame!` asks for `:platform :client` and the frame CARRIES
            it. Read through `frame-meta`, the canonical `:rf/frame-meta`
            shape, which flattens the frame's OWN config and does not fall
            back to the host-wide platform marker — so this discriminates a
            tagged frame from an untagged one, where the isolation tests
            below cannot: they would pass either way on CLJS."
    (is (= :client (:platform (rf/frame-meta (fresh-frame!)))))))

(deftest a-root-whose-manifest-is-not-from-the-schema-family-fails-alone
  (testing "preflight step 1 kills one root; the page hydrates and runs"
    (run-arm! "manifest" manifest-lever)))

(deftest a-root-whose-render-tree-fn-throws-fails-alone
  (testing "a post-seed host failure kills one root; the page hydrates and runs"
    (run-arm! "verify" verify-lever)))

(deftest a-root-whose-mount-throws-fails-alone
  (testing "a root that hydrated but cannot MOUNT is just as dead to the
            page, and just as isolated"
    (run-arm! "mount" mount-lever)))

(deftest a-root-meeting-a-payload-conflict-fails-alone
  (testing "preflight step 2 kills one root; the page hydrates and runs"
    (doseq [fail-idx (range 3)]
      (reg-bump!)
      (let [frames (vec (repeatedly 3 fresh-frame!))
            specs  (root-specs frames fail-idx nil)]
        (rf.ssr.install/reset-installed-payloads!)
        (poison-with-a-conflicting-payload! (nth frames fail-idx))
        (assert-isolated! (rf.ssr/hydrate-page! specs) frames fail-idx
                          (str "conflict @" fail-idx))))))

;; ---------------------------------------------------------------------------
;; The red-before — the same four levers, without the boundary
;; ---------------------------------------------------------------------------

(defn- measure-unisolated!
  "Run the page through the unguarded loop and report what survived."
  [lever]
  (let [frames (vec (repeatedly 3 fresh-frame!))
        specs  (root-specs frames 0 lever)]
    (rf.ssr.install/reset-installed-payloads!)
    {:escaped? (try (boot-page-without-isolation! specs) false
                    (catch #?(:clj Throwable :cljs :default) _ true))
     :frames   frames}))

(deftest without-the-boundary-one-failed-root-takes-the-page-down
  (testing "MEASURED (this is the defect, not an aspiration): in a bare
            hydrate-then-mount loop the first root's throw escapes and
            every root after it is never booted. Each lever is measured
            separately — a single blanket failure could red one arm while
            leaving the others untouched."
    (doseq [[label lever] [["manifest" manifest-lever]
                           ["verify"   verify-lever]
                           ["mount"    mount-lever]]]
      (let [{:keys [escaped? frames]} (measure-unisolated! lever)]
        (is escaped?
            (str label ": the failing root's throw escaped the loop"))
        (doseq [i [1 2]]
          (is (not (hydrated? (nth frames i)))
              (str label ": root " i " was never reached — this is exactly "
                   "what hydrate-page! prevents. If this ever goes green "
                   "the failure stopped propagating and the isolation arm "
                   "above is testing nothing.")))))))

;; ---------------------------------------------------------------------------
;; What a failed root leaves behind
;; ---------------------------------------------------------------------------

(deftest a-root-that-fails-in-preflight-leaves-no-claim
  (testing "the throw happens before any claim, so the ledger is untouched
            and the next root referencing that payload installs normally"
    (let [fid (fresh-frame!)]
      (rf.ssr/hydrate-page! [{:frame fid :root-id :page/a
                           :payload (payload-for {:count 7})
                           :manifest {:rf.root/schema-version 2}}])
      (is (nil? (rf.ssr.install/installed-payload fid))
          "no claim was made")
      (is (= :hydrated (:status (first (rf.ssr/hydrate-page!
                                        [{:frame fid :root-id :page/b
                                          :payload (payload-for {:count 7})}]))))
          "a later root got a true :install, not a poisoned :already-installed")
      (is (hydrated? fid) "and it actually seeded the frame"))))

(deftest a-root-whose-seed-does-not-land-releases-its-claim
  (testing "THE POISONING CASE. Dispatching into a destroyed frame is a
            NO-OP, not a throw — so a root can claim a payload id and
            never seed it, with nothing failing loudly. The claim must go
            back, or the next root reads :already-installed (a legitimate
            verdict) and skips its own install, leaving a frame nobody
            ever hydrated."
    (let [fid (fresh-frame!)]
      (rf/destroy-frame! fid)
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})
                      :root-id :page/a})
      (is (nil? (rf.ssr.install/installed-payload fid))
          (str "MEASURED: the seed did not land (the frame was not live), "
               "so the claim was released. Without the release this reads "
               "the claim record, and the assertion below fails."))
      ;; The frame comes back — a legitimate SPA teardown/rebuild. Flat
      ;; opts, exactly as `fresh-frame!` builds it: the rebuilt frame must
      ;; carry the same `:client` tag the original did.
      (rf/make-frame {:id fid :platform :client})
      (is (= :client (:platform (rf/frame-meta fid)))
          (str "the REBUILT frame carries the tag too. This is the only "
               "`make-frame` in this file that does NOT go through "
               "`fresh-frame!`, so `the-fixture-frames-are-actually-platform"
               "-tagged` never reaches it: nesting THESE opts back to "
               "`{:config {:platform :client}}` leaves that test green and "
               "reds only this line."))
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})
                      :root-id :page/b})
      (is (hydrated? fid)
          "the next root actually seeded the re-created frame"))))

(deftest a-root-that-fails-after-its-seed-commits-keeps-the-payload-installed
  (testing "the claim covers the INSTALL, not the whole root boot.
            Releasing here would invite a sibling to re-seed and silently
            reset everything that ran in between — the precise harm the
            ledger exists to prevent."
    (reg-bump!)
    (let [fid (fresh-frame!)]
      (rf.ssr/hydrate-page! [{:frame fid :root-id :page/a
                           :payload (payload-for {:count 7})
                           :mount-fn (boom! "mount")}])
      (is (= :page/a (:installed-by (rf.ssr.install/installed-payload fid)))
          "the payload IS installed — the seed committed before the mount died")
      (is (hydrated? fid) "and the frame carries the server slice")
      ;; A sibling root sharing that frame keeps running against it.
      (rf/dispatch-sync [::bump] {:frame fid})
      (rf.ssr/hydrate-page! [{:frame fid :root-id :page/b
                           :payload (payload-for {:count 7})}])
      (is (= {:count 8} (rf/app-db-value fid))
          "the sibling found the payload live and did NOT re-seed — the
           dead root's install still protects it"))))

;; ---------------------------------------------------------------------------
;; A contained failure is never silent
;; ---------------------------------------------------------------------------

(defn- capture-error-records!
  "Run `f` with an always-on error listener attached -> the records it
  saw. This is the production-survivable axis (surface #4), not the
  dev-only trace bus."
  [f]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::capture #(swap! seen conj %))
    (try (f) (finally (rf.error-emit/unregister-error-listener! ::capture)))
    @seen))

(defn- root-boot-failures [records]
  (filterv #(= :rf.error/root-boot-failed (:error %)) records))

(deftest a-contained-root-failure-reaches-the-always-on-error-axis
  (testing "isolation must not mean silence: a page quietly running with
            N-1 roots is the failure mode this contract exists to
            prevent, so every contained failure emits an always-on
            record naming the root"
    (let [frames  (vec (repeatedly 3 fresh-frame!))
          records (capture-error-records!
                   #(rf.ssr/hydrate-page! (root-specs frames 1 mount-lever)))
          failed  (root-boot-failures records)]
      (is (= 1 (count failed)) "exactly one root failed, one record")
      (let [r (first failed)]
        (is (= :page/r1 (:root-id r)) "the record names the dead root")
        (is (= (nth frames 1) (:frame r)))
        (is (= :mount (:phase r))
            "and WHICH half died — the seed had already committed")
        (is (some? (:exception r)) "the cause rides along")
        (is (= :warned-and-continued (:recovery r)))))))

(deftest the-failure-phase-distinguishes-a-seeded-root-from-an-unseeded-one
  (testing "an operator reading 'root X failed' needs to know whether its
            frame was seeded before it died"
    (let [frames (vec (repeatedly 3 fresh-frame!))]
      (is (= :hydrate
             (:phase (first (root-boot-failures
                             (capture-error-records!
                              #(rf.ssr/hydrate-page!
                                (root-specs frames 0 manifest-lever)))))))
          "a preflight death never reached the seed"))))

(deftest every-failed-root-on-a-page-is-reported
  (testing "two dead roots are two records — the page reports each one it
            contained, not just the first"
    (let [frames  (vec (repeatedly 3 fresh-frame!))
          specs   (-> (root-specs frames 0 manifest-lever)
                      (assoc-in [2 :mount-fn] (boom! "mount")))
          records (capture-error-records! #(rf.ssr/hydrate-page! specs))]
      (is (= #{:page/r0 :page/r2}
             (set (map :root-id (root-boot-failures records)))))
      (is (hydrated? (nth frames 1)) "and the survivor still hydrated"))))

;; ---------------------------------------------------------------------------
;; Isolation is a PRODUCTION property
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest isolation-and-its-report-survive-with-debugging-disabled
     (testing "a guarantee only the dev build can see does not exist for
               users. With `debug-enabled?` false — the dev trace bus
               silent, as under CLJS `:advanced` + goog.DEBUG=false — one
               root still fails alone AND its always-on record still
               arrives. Nothing in the boundary sits behind the debug
               gate. (The CLJS half of this proof is structural: the
               category is `always-on` in the Spec 009 catalogue, which
               `error-catalogue-channel-conformance-test` pins against
               the `always-on-axis-conformance-cljs-test` literal.)"
       (with-redefs [rf.interop/debug-enabled? false]
         (reg-bump!)
         (let [frames  (vec (repeatedly 3 fresh-frame!))
               specs   (root-specs frames 1 mount-lever)
               records (capture-error-records! #(rf.ssr/hydrate-page! specs))]
           (is (= 1 (count (root-boot-failures records)))
               "the always-on record survives a production build")
           (doseq [i [0 2]]
             (is (hydrated? (nth frames i))
                 (str "sibling " i " hydrated with debugging off"))
             (is (interactive? (nth frames i))
                 (str "sibling " i " is running with debugging off"))))))))

;; ---------------------------------------------------------------------------
;; The boundary is isolation, not recovery
;; ---------------------------------------------------------------------------

(deftest a-failed-root-stays-failed
  (testing "there is no retry, no supervision, and no fallback render —
            the single guarantee is that a failed root fails ALONE"
    (let [fid      (fresh-frame!)
          attempts (atom 0)
          outcome  (first (rf.ssr/hydrate-page!
                           [{:frame fid :root-id :page/a
                             :payload (payload-for {:count 7})
                             :mount-fn (fn [] (swap! attempts inc)
                                         (throw (ex-info "no" {})))}]))]
      (is (= 1 @attempts) "the mount was attempted exactly once")
      (is (= :failed (:status outcome)))
      (is (some? (:error outcome)) "the throwable is handed back verbatim"))))

(deftest outcomes-come-back-in-input-order
  (testing "so a caller correlates a failure positionally even when the
            root died before its id could be read from a manifest"
    (let [frames   (vec (repeatedly 3 fresh-frame!))
          outcomes (rf.ssr/hydrate-page! (root-specs frames 1 manifest-lever))]
      (is (= [:page/r0 :page/r1 :page/r2] (mapv :root-id outcomes)))
      (is (= [:hydrated :failed :hydrated] (mapv :status outcomes))))))

(deftest an-all-healthy-page-boots-every-root
  (testing "the boundary is not a behaviour change for the happy path"
    (reg-bump!)
    (let [frames   (vec (repeatedly 3 fresh-frame!))
          mounted  (atom [])
          specs    (mapv (fn [spec]
                           (assoc spec :mount-fn
                                  #(swap! mounted conj (:root-id spec))))
                         (root-specs frames nil nil))
          outcomes (rf.ssr/hydrate-page! specs)]
      (is (= [:hydrated :hydrated :hydrated] (mapv :status outcomes)))
      (is (= [:page/r0 :page/r1 :page/r2] @mounted) "every root mounted")
      (doseq [fid frames]
        (is (hydrated? fid))
        (is (interactive? fid))))))
