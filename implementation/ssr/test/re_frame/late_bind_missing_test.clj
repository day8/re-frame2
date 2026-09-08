(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the ssr artefact's `re-frame.core` REGISTRAR re-exports.

  rf2-kuky.87 deleted the six QUERY re-exports (`render-to-string`,
  `render-tree-hash`, `project-error`, `head-model->html` and the two head
  reads rf2-kuky.89 has since collapsed into one `head-model`) and their
  late-bind hooks: `re-frame.ssr` /
  `re-frame.ssr.head` are their only door, so an app that calls one has
  necessarily loaded the artefact and the guided-failure branch this test
  pins cannot be reached for them. `reg-error-projector` and `reg-head`
  remain on the façade and keep the contract.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the ssr artefact IS on the classpath here (the test ns
  requires `re-frame.ssr`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-uo7v (ssr split), and the
  prose at the call sites in `re-frame.core`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading ssr registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.ssr]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws."
  [hook-key f]
  (let [original (rf.late-bind/get-fn hook-key)]
    (try
      (rf.late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (rf.late-bind/set-fn! hook-key original)))))

(deftest reg-error-projector-raises-when-ssr-artefact-missing
  (testing "rf/reg-error-projector (macro) raises :rf.error/ssr-artefact-missing when the :ssr/reg-error-projector hook is nil"
    ;; The macro forwards to -reg-error-projector which performs the
    ;; late-bind lookup at runtime. Flipping the hook at runtime is
    ;; enough to surface the absent-artefact branch.
    (with-hook-as-nil :ssr/reg-error-projector
      (fn []
        (let [thrown (try (rf/reg-error-projector :probe/projector
                                                  (fn [_trace-event] {}))
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-error-projector throws when the ssr artefact is absent")
          ;; rf2-vvixub — message is the human :reason + trailing
          ;; [:rf.error/<id>] token; assert the token + canonical :rf.error/id,
          ;; not exact keyword-equality.
          (is (re-find #"\[:rf\.error/ssr-artefact-missing\]" (.getMessage thrown))
              "the message carries the [:rf.error/ssr-artefact-missing] token")
          (is (= :rf.error/ssr-artefact-missing (:rf.error/id (ex-data thrown)))
              "ex-data carries the canonical :rf.error/id discriminator")
          (let [data (ex-data thrown)]
            ;; Per rf2-j8icl the `:where` symbol is namespace-qualified
            ;; to the user-facing surface (`rf/reg-error-projector`) so
            ;; greping for the symbol finds call sites in the user's
            ;; codebase. The macro forwards to the plain-fn delegate
            ;; `-reg-error-projector`, which is where the late-bind
            ;; check (and ex-info) lives.
            (is (= 'rf/reg-error-projector (:where data))
                "ex-data carries :where = 'rf/reg-error-projector")
            (is (= :probe/projector (:id data))
                "ex-data carries :id from the call site")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))
