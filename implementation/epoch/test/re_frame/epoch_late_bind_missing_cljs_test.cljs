(ns re-frame.epoch-late-bind-missing-cljs-test
  "Per rf2-5lvk6 — CLJS-side missing-artefact pin for the documented
  `replace-frame-state!` raise contract.

  Per the rf2-5b6x missing-artefact error contract (see
  `re-frame.late-bind-missing-test` in the schemas artefact for the
  JVM-side sibling): each per-feature split surfaces a documented
  `:rf.error/<artefact>-artefact-missing` ex-info when a consumer
  calls a re-exported surface whose artefact is absent. For the
  epoch artefact, the load-bearing surface is `replace-frame-state!`
  (Tool-Pair §Pair-tool writes, rf2-zq55 / rf2-t3lftq — API-shrink #3
  consolidated the former `replace-app-db!` / `reset-app-db!` /
  `replace-runtime-db!` / `replace-frame-state!` four-mutator family into
  this ONE surface): unlike `restore-epoch!`, `register-epoch-listener!`,
  `epoch-history`, and `unregister-epoch-listener!` (which degrade
  silently — empty vector / `false` / no-op when the artefact is absent),
  `replace-frame-state!` MUST raise. The caller's invariant — 'undo works
  after this call' — cannot be honoured silently when the artefact that
  records the synthetic epoch is not on the classpath.

  Strategy mirrors `re-frame.late-bind-missing-test` (JVM, schemas):
  the epoch artefact IS on the classpath here (this ns requires
  `re-frame.epoch` to fire its load-time late-bind publications),
  so the absent-artefact state is simulated by flipping the
  `:epoch/replace-frame-state!` late-bind hook to nil for the duration
  of the assertion, then restoring it in `finally`.

  Mirrors the test shape used in
  `re-frame.adapter.uix-late-bind-publication-cljs-test` (per
  rf2-rrwwy) — both files pin a late-bind contract for an artefact,
  this one via the absent-hook path, that one via the present-hook
  enumeration.

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up via
  `:ns-regexp \"cljs-test$\"`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter}))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws. Identical mechanism
  to the JVM sibling test (`re-frame.late-bind-missing-test`'s
  same-named helper) — flipping the hook at runtime simulates the
  absent-artefact state without unloading the namespace (which
  CLJS cannot do)."
  [hook-key f]
  (let [original (late-bind/get-fn hook-key)]
    (try
      (late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (late-bind/set-fn! hook-key original)))))

(deftest replace-frame-state!-raises-when-epoch-artefact-missing-cljs
  (testing "rf/replace-frame-state! raises :rf.error/epoch-artefact-missing
            when the :epoch/replace-frame-state! late-bind hook is nil
            (the absent-artefact state). Mirrors the JVM contract
            pinned by re-frame.epoch-test/
            replace-frame-state!-raises-when-epoch-artefact-missing."
    (with-hook-as-nil :epoch/replace-frame-state!
      (fn []
        (let [thrown (try (rf/replace-frame-state! :any/frame {:rf.db/app {}})
                          nil
                          (catch :default e e))]
          (is (some? thrown)
              "replace-frame-state! throws when the epoch artefact is absent")
          ;; rf2-vvixub — message is the human :reason + trailing
          ;; [:rf.error/<id>] token; assert the token + canonical :rf.error/id,
          ;; not exact keyword-equality.
          (is (re-find #"\[:rf\.error/epoch-artefact-missing\]" (ex-message thrown))
              "the message carries the [:rf.error/epoch-artefact-missing] token")
          (is (= :rf.error/epoch-artefact-missing (:rf.error/id (ex-data thrown)))
              "ex-data carries the canonical :rf.error/id discriminator")
          (let [data (ex-data thrown)]
            (is (= 'rf/replace-frame-state! (:where data))
                "ex-data carries :where = 'rf/replace-frame-state!")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")
            (is (string? (:reason data))
                "ex-data carries :reason as a string")))))))

(deftest replace-frame-state!-works-when-hook-present-cljs
  (testing "Sanity companion: with the late-bind hook present (the
            normal state — the require at the top of this ns
            triggers re-frame.epoch's hook publication), the
            surface does NOT raise. Without this pair, the missing-
            hook assertion above could pass for the wrong reason
            (e.g. a typo'd hook key that's never wired)."
    (rf/reg-event :seed/cljs (fn [{:keys [db]} _] {:db {:n 0}}))
    (rf/dispatch-sync [:seed/cljs])
    ;; Replace the live db with a fresh value; surface returns true
    ;; (no exception).
    (is (true? (rf/replace-frame-state! :rf/default {:rf.db/app {:n 99}}))
        "replace-frame-state! succeeds with the hook present (sanity)")
    (is (= {:n 99} (rf/app-db-value :rf/default))
        "app-db now carries the injected value")))
