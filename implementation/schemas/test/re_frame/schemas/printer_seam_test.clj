(ns re-frame.schemas.printer-seam-test
  "Pluggable schema-print companion fn.

  Per Spec 010 §Schema digest line 491 — schema digests are computed
  from the schema values as serialised by the registered validator's
  `schema-print` companion fn. This file locks the pluggable surface
  reached through the `:print` key of the one installer (there is no
  per-fn setter, so `:print` is the whole printer door):

    - The default printer is the Malli-EDN canonicaliser the pinned
      digest literals were taken over.
    - `(set-schema-fns! {:print fn})` swaps the printer atom; the
      digest pipeline picks up the new bytes on the next call, and the
      swap rides alongside `:validate` / `:explain`.
    - `(set-schema-fns! {:print nil})` falls back to the default (the
      digest is never undefined for a present schema set).
    - `(set-schema-fns! default-schema-fns)` restores the default
      printer alongside the validator/explainer defaults.

  These contracts are what a non-Malli port (a Zod-port, a
  clojure.spec port) needs to be able to plug into the digest
  pipeline without re-implementing it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.frame :as rf.frame]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.validator :as rf.schemas.validator]))

(defn- reset [test-fn]
  ;; The validator/explainer/printer atoms and the per-frame schema registry
  ;; are process-global. Restore the default fns and empty the registry
  ;; around each test, so neither a sibling test nor a namespace that ran
  ;; earlier can poison this one: the pinned digest literal holds only while
  ;; the test's own registration is the frame's whole schema set.
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (rf.schemas/clear-schemas-by-frame!)
  ;; EP-0002: the digest-seam tests register schemas via
  ;; reg-app-schema, which is context-required frame-local. Pin
  ;; :rf/default as the established scope so those ambient registrations
  ;; carry a frame stamp (no :rf/default floor). No `ensure-default-frame!`
  ;; here — these tests install no adapter; `reg-app-schema` only needs the
  ;; carried STAMP (it writes the plain `schemas-by-frame` atom), and the
  ;; schema-derived elision-populate step no-ops when the frame has no live
  ;; runtime-db container.
  (try (binding [rf.frame/*current-frame* :rf/default]
         (test-fn))
       (finally (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
                (rf.schemas/clear-schemas-by-frame!))))

(use-fixtures :each reset)

(deftest schema-print-swap-flips-the-digest-bytes
  (testing "A printer swap changes the digest for a non-empty schema
            set — the per-schema bytes are fed through the digest
            pipeline so a different printer (returning different
            bytes) MUST produce a different digest. This is the
            cross-port distinction Spec 010 §Locked rules line 491
            describes: 'two ports using *different* schema languages
            produce different digests by construction'."
    (rf.schemas/reg-app-schema [:n] :int)
    (let [default-digest (rf.schemas/app-schemas-digest {:frame :rf/default})]
      (rf.schemas/set-schema-fns! {:print (fn [_schema] "::DIFFERENT::")})
      (let [swapped-digest (rf.schemas/app-schemas-digest {:frame :rf/default})]
        (is (not= default-digest swapped-digest)
            "digest changes once the printer registers different bytes")))
    ;; Restoring the default brings the digest back — the printer
    ;; surface is purely a contract over the serialisation step.
    (rf.schemas/set-schema-fns! {:print nil})
    (is (= "sha256:e7939756d704eaab"
           (rf.schemas/app-schemas-digest {:frame :rf/default}))
        "{:print nil} restores the default — the digest matches
         the `single-prim` fixture literal byte-for-byte (the path key is
         CEDN-1 `canonical-bytes`, not pr-str)")))

(deftest installing-default-schema-fns-restores-the-default-printer
  (testing "`(set-schema-fns! default-schema-fns)` restores the framework
            defaults for all three atoms — validator, explainer, AND
            printer. Test-support call sites that restore the defaults
            reset the printer
            too."
    (rf.schemas/set-schema-fns! {:print (fn [_] "::POISONED::")})
    (is (= "::POISONED::" (rf.schemas.validator/run-printer :int)))
    (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
    (is (= ":int" (rf.schemas.validator/run-printer :int))
        "reset restores the default EDN canonicaliser")))

;; ---- set-schema-fns! return contract --------------------------------------
;;
;; The bundle setter returns the INSTALLED BUNDLE as a map
;; `{:validate … :explain … :print …}` reflecting the live state of all
;; three fns after the call — not just the validator. These tests pin the
;; return value WITHOUT dereferencing the raw `schemas/*` atoms: the return
;; is the public observation seam for what is now installed.

(deftest set-schema-fns!-nil-print-returns-non-nil-coerced-printer
  (testing "`{:print nil}` coerces to the default
            EDN canonicaliser, and the RETURNED `:print` reflects that
            coercion: never nil. A caller observing the return sees the
            actual printer that will be hashed, not the literal nil it passed."
    ;; Poison first so a no-op would be observable.
    (rf.schemas/set-schema-fns! {:print (fn [_] "::POISONED::")})
    (is (= "::POISONED::" (rf.schemas.validator/run-printer :int)))
    (let [ret (rf.schemas/set-schema-fns! {:print nil})]
      (is (some? (:print ret))
          "the returned :print is the coerced default, never the nil passed")
      ;; The returned printer IS the live default — verified via run-printer
      ;; rather than a raw atom deref.
      (is (= ":int" (rf.schemas.validator/run-printer :int))
          "{:print nil} falls back to default-edn-print on the hot path")
      (is (= ":int" ((:print ret) :int))
          "calling the returned :print fn directly yields the default bytes"))))

(deftest a-single-key-install-still-returns-the-whole-bundle
  (testing "the bundle setter is the only setter,
            and it returns the bundle whatever subset was installed. A caller
            that wants back just the fn it installed selects that key from the
            return; the keys it did NOT touch come back too, so no
            single-purpose setter is needed."
    (let [v-fn (fn [_ _] true)
          e-fn (fn [_ _] {:e true})
          p-fn (fn [_] "::P::")]
      (is (= v-fn (:validate (rf.schemas/set-schema-fns! {:validate v-fn})))
          "a :validate-only install returns a bundle carrying that validator")
      (is (= e-fn (:explain (rf.schemas/set-schema-fns! {:explain e-fn})))
          "an :explain-only install returns a bundle carrying that explainer")
      (let [ret (rf.schemas/set-schema-fns! {:print p-fn})]
        (is (= p-fn (:print ret))
            "a :print-only install returns a bundle carrying that printer")
        (is (= v-fn (:validate ret))
            "and carries the untouched :validate key from the earlier install")
        (is (= e-fn (:explain ret))
            "and the untouched :explain key")))))
