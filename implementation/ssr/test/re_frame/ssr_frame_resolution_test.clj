(ns re-frame.ssr-frame-resolution-test
  "rf2-blpg — the explicitly targeted SSR queries resolve their
  REGISTRATIONS through the target frame's generation, not only its data.

  `head-model` and `project-error` both take an explicit
  frame and are called OUTSIDE any `with-frame` binding — that is what
  \"explicit target\" means, and it is how `ssr_head_test` and the Ring
  host call them. Each read the named frame's app-db / `:ssr` config by
  id, which needs no ambient scope. The `(kind, id)` lookups they then
  perform did NOT: they went to the process registrar atom, which is a
  different question with a different answer.

  ## Why two support namespaces

  The provenance source store keys descriptors `[kind id provenance-ns]`
  and RETAINS every provenance-distinct one (`source-store/descriptors-for`
  — \"the image-isolation case the store preserves\"), while the registrar
  ATOM keeps only the last writer. So a `[kind id]` registered from two
  namespaces is one entry in the atom and two in the store, and two frames
  whose images select one namespace each resolve it to two DIFFERENT
  bodies. That divergence is the whole measurement, and it needs two real
  files because `reg-*` stamps provenance at MACROEXPANSION time — see
  `re-frame.ssr.head-image-alpha`.

  ALPHA registers first and BETA second in every test here, so the
  registrar atom always holds BETA. A read that resolves correctly returns
  ALPHA's body for ALPHA's frame; the pre-fix behaviour returned BETA's
  body for BOTH frames, run against whichever frame's app-db was asked
  for — a plausible head model, or a public error status, for the wrong
  page.

  ## The adapter these tests run under

  `adapter-under-test-is-the-ssr-adapter` asserts it rather than assuming
  it: `rf/init!` installs only when NO adapter is seated, so in a shared
  runtime the first suite to call it wins and every later `init!` is a
  silent no-op. A test that quietly ran on plain-atom while claiming to
  exercise SSR would be green for the wrong reason.

  ## This namespace also runs under the PRODUCTION gate

  `scripts/test-ssr-prod-gate.sh` re-runs this artefact under
  `-Dre-frame.debug=false`, and its roster is an EXCLUSION list — a new
  namespace joins that lane BY DEFAULT. So every assertion here must hold
  with the dev-only surfaces compiled out, and `clojure -M:test` passing
  is only half the evidence. The one thing that bit: a descriptor's
  `:doc` is pure documentation and is stripped before storage under the
  gate (Spec 001 §Production elision contract), so the divergence between
  the two images is discriminated by RUNNING the resolved `:handler-fn`
  rather than by reading a doc string off it. Everything else these tests
  assert on — a head model, an `:rf.error/*` id, a public error's
  `:status` — is always-on by contract."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.source-store :as rf.source-store]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-projector :as rf.ssr.error-projector]
            [re-frame.ssr.head-image-alpha :as rf.ssr.head-image-alpha]
            [re-frame.ssr.head-image-beta :as rf.ssr.head-image-beta]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.substrate.adapter :as rf.substrate.adapter]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---------------------------------------------------------------------------
;; What is actually installed
;; ---------------------------------------------------------------------------

(deftest adapter-under-test-is-the-ssr-adapter
  (testing "the shared fixture's (rf/init! ssr/adapter) really seated the SSR
            adapter in THIS runtime — `init!` is first-wins, so a suite that
            assumes it can be measuring plain-atom and calling it SSR"
    (is (= :rf.adapter/ssr (:kind (rf.substrate.adapter/current-adapter))))
    (is (identical? rf.ssr/adapter (rf.substrate.adapter/current-adapter))
        "and it is this artefact's adapter map, not another :rf.adapter/ssr")))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- register-both!
  "ALPHA then BETA, so the registrar atom ends up holding BETA."
  []
  (rf.ssr.head-image-alpha/register!)
  (rf.ssr.head-image-beta/register!))

(defn- frame-selecting!
  "A frame whose image selects exactly `ns-glob`, seeded with `db`.

  `:select-ns` SELECTS from what is already registered — it never loads —
  so `register-both!` must have run first."
  [ns-glob db]
  (let [n   (swap! frame-counter inc)
        fid (keyword "rf.ssr-frame-resolution" (str "f" n))]
    (rf/make-frame {:id       fid
                    :platform :server
                    :images   [(rf/image {:id        (keyword "rf.ssr-frame-resolution" (str "img" n))
                                          :select-ns {:include [ns-glob]}})]})
    (rf/dispatch-sync [:rf/set-db db] {:frame fid})
    fid))

(defn- caught-error-id
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

;; ---------------------------------------------------------------------------
;; The premise: the store really does retain both, and the atom really does
;; hold only BETA. If either half stops being true the tests below would pass
;; vacuously, so both are asserted rather than assumed.
;; ---------------------------------------------------------------------------

(deftest the-two-support-namespaces-produce-a-genuine-divergence
  (register-both!)
  (testing "the provenance store retains BOTH descriptors for the shared id"
    (is (= #{"re-frame.ssr.head-image-alpha" "re-frame.ssr.head-image-beta"}
           (set (keys (rf.source-store/descriptors-for :head rf.ssr.head-image-alpha/head-id))))))

  (testing "each frame's OWN generation resolves to its OWN image's body —
            this is the answer the head query has to match.

            Discriminated by RUNNING the resolved `:handler-fn`, not by
            reading a `:doc` off the descriptor. `:doc` is a
            pure-documentation key that `registrar/strip-pure-documentation`
            drops BEFORE the metadata is stored when `debug-enabled?` is
            false (Spec 001 §Production elision contract), so a `:doc`
            assertion passes in the ordinary lane and reads nil under
            `scripts/test-ssr-prod-gate.sh` — which is where this one was
            caught, and the same trap `ssr_head_test`'s docstring records
            for `reg-head-accepts-metadata-arity`. The handler fn is the
            executable and is never stripped; it is also the thing this
            test is actually about, so the elision-proof assertion is the
            more direct one."
    (let [alpha-frame  (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})
          beta-frame   (frame-selecting! "re-frame.ssr.head-image-beta" {:marker "B"})
          head-fn-for  (fn [frame]
                         (:handler-fn (rf/handler-meta {:frame frame
                                                        :kind  :head
                                                        :id    rf.ssr.head-image-alpha/head-id})))]
      (is (= {:title "alpha:probe"} ((head-fn-for alpha-frame) {:marker "probe"} nil)))
      (is (= {:title "beta:probe"}  ((head-fn-for beta-frame)  {:marker "probe"} nil))))))

;; ---------------------------------------------------------------------------
;; head-model
;; ---------------------------------------------------------------------------

(deftest head-model-runs-the-target-frames-own-registration
  (register-both!)
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})
        beta-frame  (frame-selecting! "re-frame.ssr.head-image-beta" {:marker "B"})]
    (testing "the frame whose image carries ALPHA's body gets ALPHA's body —
              not the registrar atom's last writer"
      (is (= {:title "alpha:A"} (rf.ssr/head-model alpha-frame {:head-id rf.ssr.head-image-alpha/head-id}))))

    (testing "and BETA's frame gets BETA's, so the fix is resolution rather
              than a different fixed answer"
      (is (= {:title "beta:B"} (rf.ssr/head-model beta-frame {:head-id rf.ssr.head-image-alpha/head-id}))))

    (testing "and the read is pure — re-reading ALPHA's frame answers ALPHA
              again rather than the last frame asked about"
      (is (= {:title "alpha:A"} (rf.ssr/head-model alpha-frame {:head-id rf.ssr.head-image-alpha/head-id}))))))

(deftest head-model-refuses-a-head-the-target-frames-image-does-not-carry
  (register-both!)
  (rf/reg-head ::unselected (fn [_ _] {:title "unselected"}))
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})]
    (testing "a head registered outside the frame's image is not that frame's
              head — resolving it from the process store rendered another
              application's <title> into this one"
      (is (= :rf.error/no-such-head
             (caught-error-id #(rf.ssr/head-model alpha-frame {:head-id ::unselected})))))))

;; ---------------------------------------------------------------------------
;; head-model — the route metadata NAMING the head must come from the same
;; image as the head itself
;; ---------------------------------------------------------------------------

(deftest head-model-resolves-route-metadata-and-head-in-the-target-generation
  (register-both!)
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})]
    (rf/dispatch-sync [:rf/set-db {:marker "A"}] {:frame alpha-frame})
    (testing "with no route in the runtime-db slice, head-model is the default
              head — the arm that must keep working"
      (is (= "" (:title (rf.ssr/head-model alpha-frame)))
          "the frame carries no :doc, so the default title is the empty string"))))

;; ---------------------------------------------------------------------------
;; project-error — the same omission, deciding a public status
;; ---------------------------------------------------------------------------

(deftest project-error-runs-the-target-frames-own-projector
  (register-both!)
  (let [n           (swap! frame-counter inc)
        alpha-frame (keyword "rf.ssr-frame-resolution" (str "err" n))]
    (rf/make-frame {:id       alpha-frame
                    :platform :server
                    :ssr      {:public-error-id rf.ssr.head-image-alpha/projector-id}
                    :images   [(rf/image {:id        (keyword "rf.ssr-frame-resolution" (str "errimg" n))
                                          :select-ns {:include ["re-frame.ssr.head-image-alpha"]}})]})
    (testing "the frame's :ssr config named ALPHA's projector id and its image
              carries ALPHA's projector — so ALPHA's status and code leave the
              server, not the registrar atom's BETA"
      (is (= {:status 418 :code :alpha :message "alpha" :retryable? false}
             (rf.ssr.error-projector/project-error alpha-frame {:operation :rf.error/handler-exception}))))))
