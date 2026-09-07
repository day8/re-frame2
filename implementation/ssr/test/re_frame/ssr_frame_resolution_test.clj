(ns re-frame.ssr-frame-resolution-test
  "rf2-blpg — the explicitly targeted SSR queries resolve their
  REGISTRATIONS through the target frame's generation, not only its data.

  `render-head`, `active-head` and `project-error` all take an explicit
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
  exercise SSR would be green for the wrong reason."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.source-store :as rf.source-store]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-projector :as rf.ssr.error-projector]
            [re-frame.ssr.head :as rf.ssr.head]
            [re-frame.ssr.head-image-alpha :as alpha]
            [re-frame.ssr.head-image-beta :as beta]
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
    (is (= :rf.adapter/ssr (rf.substrate.adapter/current-adapter)))
    (is (identical? rf.ssr/adapter (rf.substrate.adapter/current-adapter-spec))
        "and it is this artefact's adapter map, not another :rf.adapter/ssr")))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- register-both!
  "ALPHA then BETA, so the registrar atom ends up holding BETA."
  []
  (alpha/register!)
  (beta/register!))

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
           (set (keys (rf.source-store/descriptors-for :head alpha/head-id))))))

  (testing "each frame's OWN generation resolves to its OWN image's body —
            this is the answer the head query has to match"
    (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})
          beta-frame  (frame-selecting! "re-frame.ssr.head-image-beta" {:marker "B"})]
      (is (= "ALPHA's body for the shared head id."
             (:doc (rf/handler-meta {:frame alpha-frame :kind :head :id alpha/head-id}))))
      (is (= "BETA's body for the shared head id."
             (:doc (rf/handler-meta {:frame beta-frame :kind :head :id alpha/head-id})))))))

;; ---------------------------------------------------------------------------
;; render-head
;; ---------------------------------------------------------------------------

(deftest render-head-runs-the-target-frames-own-registration
  (register-both!)
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})
        beta-frame  (frame-selecting! "re-frame.ssr.head-image-beta" {:marker "B"})]
    (testing "the frame whose image carries ALPHA's body gets ALPHA's body —
              not the registrar atom's last writer"
      (is (= {:title "alpha:A"} (rf.ssr.head/render-head alpha/head-id {:frame alpha-frame}))))

    (testing "and BETA's frame gets BETA's, so the fix is resolution rather
              than a different fixed answer"
      (is (= {:title "beta:B"} (rf.ssr.head/render-head alpha/head-id {:frame beta-frame}))))

    (testing "the keyword shorthand carries the same target"
      (is (= {:title "alpha:A"} (rf.ssr.head/render-head alpha/head-id alpha-frame))))))

(deftest render-head-refuses-a-head-the-target-frames-image-does-not-carry
  (register-both!)
  (rf/reg-head ::unselected (fn [_ _] {:title "unselected"}))
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})]
    (testing "a head registered outside the frame's image is not that frame's
              head — resolving it from the process store rendered another
              application's <title> into this one"
      (is (= :rf.error/no-such-head
             (caught-error-id #(rf.ssr.head/render-head ::unselected {:frame alpha-frame})))))))

;; ---------------------------------------------------------------------------
;; active-head — the route metadata NAMING the head must come from the same
;; image as the head itself
;; ---------------------------------------------------------------------------

(deftest active-head-resolves-route-metadata-and-head-in-the-target-generation
  (register-both!)
  (let [alpha-frame (frame-selecting! "re-frame.ssr.head-image-alpha" {:marker "A"})]
    (rf/dispatch-sync [:rf/set-db {:marker "A"}] {:frame alpha-frame})
    (testing "with no route in the runtime-db slice, active-head is the default
              head — the arm that must keep working"
      (is (= "" (:title (rf.ssr.head/active-head alpha-frame)))
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
                    :ssr      {:public-error-id alpha/projector-id}
                    :images   [(rf/image {:id        (keyword "rf.ssr-frame-resolution" (str "errimg" n))
                                          :select-ns {:include ["re-frame.ssr.head-image-alpha"]}})]})
    (testing "the frame's :ssr config named ALPHA's projector id and its image
              carries ALPHA's projector — so ALPHA's status and code leave the
              server, not the registrar atom's BETA"
      (is (= {:status 418 :code :alpha :message "alpha" :retryable? false}
             (rf.ssr.error-projector/project-error alpha-frame {:operation :rf.error/handler-exception}))))))
