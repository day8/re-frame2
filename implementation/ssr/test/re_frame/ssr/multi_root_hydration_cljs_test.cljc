(ns re-frame.ssr.multi-root-hydration-cljs-test
  "Multi-root hydration (S5-B) — hydration PREFLIGHT and the IDEMPOTENT
  payload install (Spec 011 §Hydration preflight; ratified by
  [Spec 004C §6/§7]).

  A server-rendered page is N roots referencing M frames, and M is
  routinely smaller than N. Every root on the page boots, and every one
  of them reads the same page-wide `__rf_payload`. The contract under
  test is that the SECOND root to reference a payload finds it live and
  does NOT re-seed.

  ## The idempotence proof is CAUSAL, not structural

  `installing-the-same-payload-twice-is-indistinguishable-from-once`
  does not assert \"the ledger has one entry\" — a ledger can hold one
  entry while the frame was seeded twice. It asserts on the OBSERVABLE
  frame-state: it interleaves a real client mutation between the two
  installs and requires the mutation to SURVIVE the second one. That is
  the harm a re-seed actually causes, so that is what the test watches.

  ## The red-before is permanent and executable

  `re-seeding-a-live-payload-destroys-client-state` is the measurement
  that motivated this leaf, kept runnable forever. It reaches PAST the
  ledger — dispatching `:rf/hydrate` directly, exactly as a caller who
  skipped preflight would — and proves the client mutation IS destroyed
  on that path. Delete the guard from `hydrate!` and the idempotence
  test above reproduces this test's outcome; keep the guard and the two
  tests must disagree. So the guard cannot decay into a tautology: one
  of these tests is always exercising the unguarded behaviour.

  ## Both hosts

  This is a `.cljc` named `*-cljs-test`, so it runs under BOTH
  `clojure -M:test` from `implementation/ssr` (JVM) and the node runner
  (`npm run test:cljs`). The JVM emitter and the client substrates have
  diverged before, and multi-root hydration was measured on both hosts
  before this contract was designed: they double-applied IDENTICALLY.
  The `.cljc` keeps that agreement pinned rather than assumed.

  Handlers are registered INSIDE each test body, never at ns-load. In
  the shared node process a sibling namespace's `registrar/clear-all!`
  wipes ns-load-time registrations, which silently turns a dispatch into
  a no-op and would make an idempotence assertion pass for the wrong
  reason."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.boot :as rf.ssr.boot]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.manifest :as rf.ssr.manifest]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.router :as rf.router]))

(use-fixtures :once (fn [f] (rf/init! rf.ssr/adapter) (f)))

(use-fixtures :each (fn [f] (rf.ssr.install/reset-installed-payloads!) (f)))

;; ---------------------------------------------------------------------------
;; Host-neutral fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A `:client`-platform frame under an id no other test in this shared
  process has used. Frames are not torn down between tests here, so a
  reused id would carry a prior test's app-db into this one.

  `make-frame` opts are FLAT — `:platform` sits alongside `:id`. A nested
  `{:config {:platform :client}}` stores `:config {:config {…}}` and the
  frame is never platform-tagged at all; every test below would still pass,
  because the runtime falls back to the host-wide platform marker, which on
  CLJS is already `:client`. `the-fixture-frames-are-actually-platform-tagged`
  is what keeps that accident from coming back."
  []
  (let [fid (keyword "rf.multiroot" (str "f" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(defn- payload-for
  "The page-wide hydration payload, built by the SHIPPED assembler
  (`payload-policy/build-payload`) rather than a hand-written literal, so
  these tests track the real wire shape.

  No `:rf/frame-id`: the documented no-conflict shape (an anonymous
  per-request server frame), which lets the explicit client `:frame`
  target stand. The frame-id mismatch arm is covered by
  `ssr_hydration_test`."
  [db]
  (rf.ssr.payload-policy/build-payload nil db "server-hash-1" {}))

(defn- reg-bump!
  "Register the client-side mutation the idempotence proof interleaves.
  Registered per-test (see the ns docstring)."
  []
  (rf/reg-event ::bump (fn [{:keys [db]} _] {:db (update db :count inc)})))

(def ^:private manifest-v1
  "A minimal valid Root Manifest v1 — only `:rf.root/schema-version` is
  required, which is exactly the subset property S5-A pinned."
  {:rf.root/schema-version rf.ssr.manifest/schema-version
   :root-id                :page/shop
   :view-id                :app/shop-root
   :phase                  :server})

;; ---------------------------------------------------------------------------
;; The fixture's own tag, asserted so it cannot lapse
;; ---------------------------------------------------------------------------

(deftest the-fixture-frames-are-actually-platform-tagged
  (testing "`fresh-frame!` asks for `:platform :client` and the frame CARRIES
            it. Read through `frame-meta`, the canonical `:rf/frame-meta`
            shape, which flattens the frame's OWN config and does not fall
            back to the host-wide platform marker — so this discriminates a
            tagged frame from an untagged one, where the hydration tests
            below cannot: they would pass either way on CLJS."
    (is (= :client (:platform (rf/frame-meta (fresh-frame!)))))))

;; ---------------------------------------------------------------------------
;; THE RED-BEFORE, kept permanently executable
;; ---------------------------------------------------------------------------

(deftest re-seeding-a-live-payload-destroys-client-state
  (testing "dispatching :rf/hydrate a second time DIRECTLY — the unguarded
            path a caller who skipped preflight takes — discards everything
            that happened after the first install"
    (reg-bump!)
    (let [fid     (fresh-frame!)
          payload (payload-for {:count 7})]
      (rf.ssr.boot/hydrate! {:frame fid :payload payload})
      (is (= {:count 7} (rf/app-db-value fid)) "first install seeded")

      (rf/dispatch-sync [::bump] {:frame fid})
      (is (= {:count 8} (rf/app-db-value fid))
          "the client moved past the server slice")

      ;; PAST the ledger, straight at the handler.
      (rf.router/dispatch-sync! [:rf/hydrate payload] {:frame fid})

      (is (= {:count 7} (rf/app-db-value fid))
          (str "MEASURED (this is the defect, not an aspiration): an "
               "unguarded second install reverts the client mutation. "
               "`:replace-frame-state` is the locked merge policy, so the "
               "second install is not additive corruption — it is a "
               "silent RESET. If this ever goes green, the re-seed harm "
               "disappeared and the guard below is testing nothing.")))))

;; ---------------------------------------------------------------------------
;; The idempotence proof
;; ---------------------------------------------------------------------------

(deftest installing-the-same-payload-twice-is-indistinguishable-from-once
  (testing "root B, booting second against the same page payload, finds it
            live and does not re-seed (004C §6)"
    (reg-bump!)
    (let [fid     (fresh-frame!)
          payload (payload-for {:count 7})]
      ;; ROOT A boots.
      (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id :page/a})
      (rf/dispatch-sync [::bump] {:frame fid})
      (let [after-one (rf/app-db-value fid)]
        (is (= {:count 8} after-one))

        ;; ROOT B boots — same page, same frame, same payload script.
        (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id :page/b})

        (is (= after-one (rf/app-db-value fid))
            (str "the observable frame-state is IDENTICAL to installing "
                 "once — the second root did not re-seed. Compare "
                 "`re-seeding-a-live-payload-destroys-client-state`, which "
                 "takes the unguarded path and reverts to {:count 7}."))))))

(deftest a-no-op-install-still-reports-the-page-as-server-rendered
  (testing "hydrate! returns the payload on the idempotent path too — the
            page WAS server-rendered, whichever root got there first"
    (let [fid     (fresh-frame!)
          payload (payload-for {:count 7})]
      (is (= payload (rf.ssr.boot/hydrate! {:frame fid :payload payload})))
      (is (= payload (rf.ssr.boot/hydrate! {:frame fid :payload payload}))
          "a caller branching on the return value cannot tell root order"))))

(deftest the-installing-root-is-recorded-and-a-later-root-does-not-replace-it
  (testing "the ledger attributes the payload to the root that actually
            installed it, so a conflict can name the right party"
    (let [fid     (fresh-frame!)
          payload (payload-for {:count 7})]
      (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id :page/a})
      (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id :page/b})
      (is (= :page/a (:installed-by (rf.ssr.install/installed-payload fid)))
          "root B found the claim live; it did not take it over"))))

(deftest order-independence-either-root-may-boot-first
  (testing "the ledger records whichever root arrives first — install is
            order-INdependent, not first-listed-wins (004C §6)"
    (let [payload (payload-for {:count 7})]
      (doseq [[first-root second-root] [[:page/a :page/b] [:page/b :page/a]]]
        (let [fid (fresh-frame!)]
          (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id first-root})
          (rf.ssr.boot/hydrate! {:frame fid :payload payload :root-id second-root})
          (is (= first-root (:installed-by (rf.ssr.install/installed-payload fid)))
              (str "whichever root booted first owns the claim (order "
                   (pr-str [first-root second-root]) ")")))))))

;; ---------------------------------------------------------------------------
;; The conflict arm — 004C §7's S5 content-digest trigger
;; ---------------------------------------------------------------------------

(defn- caught-error-id
  [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) e
         (:rf.error/id (ex-data e)))))

(deftest a-different-payload-under-a-live-id-fails-loud
  (testing "two roots referencing one payload id with DIFFERENT content is
            :rf.error/frame-payload-conflict — never a silent first-wins"
    (let [fid (fresh-frame!)]
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})
                      :root-id :page/a})
      (is (= :rf.error/frame-payload-conflict
             (caught-error-id
              #(rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 99})
                               :root-id :page/b})))))))

(deftest a-conflicting-root-leaves-the-installed-payload-untouched
  (testing "the conflict throws BEFORE any install: the live payload, the
            frame it seeded, and the ledger record all survive intact"
    (reg-bump!)
    (let [fid (fresh-frame!)]
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})
                      :root-id :page/a})
      (rf/dispatch-sync [::bump] {:frame fid})
      (let [before-conflict (rf/app-db-value fid)
            record-before   (rf.ssr.install/installed-payload fid)]
        (caught-error-id
         #(rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 99})
                          :root-id :page/b}))
        (is (= before-conflict (rf/app-db-value fid))
            "the frame the first root seeded was not touched")
        (is (= record-before (rf.ssr.install/installed-payload fid))
            "the ledger still attributes the payload to root A — a
             conflicting root never overwrites, merges, or partially claims")))))

(deftest the-conflict-names-both-parties-and-carries-the-content-digest
  (testing "ex-data carries :payload-id, :installed and :arriving, each with
            its content :digest (004C §7 — the S5 arm's own slot)"
    (let [fid (fresh-frame!)]
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})
                      :root-id :page/a})
      (let [data (try (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 99})
                                      :root-id :page/b})
                      nil
                      (catch #?(:clj Exception :cljs :default) e (ex-data e)))]
        (is (= fid (:payload-id data)))
        (is (= :page/a (get-in data [:installed :installed-by])))
        (is (= :page/b (get-in data [:arriving :root-id])))
        (is (string? (get-in data [:installed :digest])))
        (is (string? (get-in data [:arriving :digest])))
        (is (not= (get-in data [:installed :digest])
                  (get-in data [:arriving :digest]))
            "the digests are what disagreed; both ride the diagnostic")
        (is (= :render-the-page-from-one-response (:recovery data)))))))

;; ---------------------------------------------------------------------------
;; The digest itself
;; ---------------------------------------------------------------------------

(deftest equal-payloads-digest-equal-and-differing-payloads-do-not
  (testing "the digest is a function of payload CONTENT, not identity —
            two roots reading the same script must agree without sharing
            an object"
    (is (= (rf.ssr.install/payload-content-digest (payload-for {:count 7}))
           (rf.ssr.install/payload-content-digest (payload-for {:count 7})))
        "separately constructed but equal payloads agree")
    (is (not= (rf.ssr.install/payload-content-digest (payload-for {:count 7}))
              (rf.ssr.install/payload-content-digest (payload-for {:count 8})))
        "a differing app-db slice is a differing digest"))

  (testing "key order does not change the digest — the canonical-EDN walk
            sorts, so an unordered map literal cannot fake a conflict"
    (is (= (rf.ssr.install/payload-content-digest {:rf/app-db {:a 1 :b 2} :rf/version 1})
           (rf.ssr.install/payload-content-digest {:rf/version 1 :rf/app-db {:b 2 :a 1}})))))

;; ---------------------------------------------------------------------------
;; Preflight step 1 — the manifest
;; ---------------------------------------------------------------------------

(deftest preflight-validates-an-explicit-manifest-and-takes-root-id-from-it
  (testing "a hydrating root's identity comes FROM its manifest (004C §3),
            not from a caller-supplied guess"
    (let [fid (fresh-frame!)
          {:keys [root-id decision manifest]}
          (rf.ssr.install/preflight! 'test {:payload    (payload-for {:count 7})
                                     :payload-id fid
                                     :manifest   manifest-v1})]
      (is (= :page/shop root-id) "read from the manifest's content")
      (is (= :install decision))
      (is (= manifest-v1 manifest)))))

(deftest preflight-rejects-a-value-outside-the-manifest-schema-family
  (testing "a manifest whose schema-version is not 1 is not from this
            family — :rf.error/root-manifest-invalid, before any install"
    (let [fid (fresh-frame!)]
      (is (= :rf.error/root-manifest-invalid
             (caught-error-id
              #(rf.ssr.install/preflight! 'test {:payload    (payload-for {:count 7})
                                          :payload-id fid
                                          :manifest   {:rf.root/schema-version 2}}))))
      (is (nil? (rf.ssr.install/installed-payload fid))
          "the payload was NOT claimed — preflight failed first"))))

(deftest an-explicit-root-id-wins-over-the-manifests
  (testing "a caller that knows its root-id may say so; the manifest is the
            default source, not an override"
    (let [fid (fresh-frame!)]
      (is (= :page/explicit
             (:root-id (rf.ssr.install/preflight! 'test
                                           {:payload    (payload-for {:count 7})
                                            :payload-id fid
                                            :manifest   manifest-v1
                                            :root-id    :page/explicit})))))))

(deftest the-manifest-less-boot-path-is-unchanged
  (testing "hydrate! with neither :container nor :manifest still hydrates —
            preflight adds a manifest STEP, it does not make manifests
            mandatory for a host that supplies its own payload"
    (let [fid (fresh-frame!)]
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})})
      (is (= {:count 7} (rf/app-db-value fid))))))

;; ---------------------------------------------------------------------------
;; Release
;; ---------------------------------------------------------------------------

(deftest releasing-a-claim-lets-a-fresh-lifetime-install-again
  (testing "a destroyed frame's claim goes with it — a frame re-created
            under the same id must not meet a phantom conflict raised by a
            lifetime that no longer exists"
    (let [fid (fresh-frame!)]
      (rf.ssr.boot/hydrate! {:frame fid :payload (payload-for {:count 7})
                      :root-id :page/a})
      (rf.ssr.install/release-payload! fid)
      (is (nil? (rf.ssr.install/installed-payload fid)))
      ;; A DIFFERENT payload would have conflicted a moment ago.
      (is (= :install
             (:decision (rf.ssr.install/preflight! 'test
                                            {:payload    (payload-for {:count 99})
                                             :payload-id fid
                                             :root-id    :page/c})))))))
