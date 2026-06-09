(ns re-frame.schemas-atom-privacy-test
  "Guard test for the rf2-l5r974 ruling (option (a) end-state, PR2
  rf2-btdpqn): the four raw schema atoms are NOT publicly re-exported from
  the `re-frame.schemas` facade.

  WHY THIS GUARD EXISTS. Exposing `schemas-by-frame` / `validator-fn` /
  `explainer-fn` / `printer-fn` as public Vars on `re-frame.schemas` was an
  encapsulation leak worse than aesthetic (rf2-l5r974):

    1. `schemas-by-frame` is the authoritative store, so its rep cannot
       evolve without breaking ad-hoc consumers — already realised:
       rf2-naihn1 added per-frame `frame-reg-locks` companion state that
       `clear-schemas-by-frame!` clears but raw `(reset! schemas-by-frame
       {})` sites silently skip, leaving stale locks.
    2. The public `printer-fn` atom bypassed the never-nil invariant
       `run-printer` relies on with NO read guard — `(reset! printer-fn
       nil)` NPEs the digest path; the setters exist to coerce nil →
       default.

  The supported public surface is the encapsulated snapshot / restore /
  clear API: tests and fixtures go through it instead of the raw atoms.
  This guard FAILS if a re-export of any of the four atoms creeps back onto
  the facade.

  The schemas artefact's OWN white-box tests (e.g. `re-frame.schemas-test`,
  `re-frame.schemas-storage-test`) are EXEMPT — they legitimately reach the
  authoritative atoms through their HOME namespaces (`storage/schemas-by-
  frame`, `validator/validator-fn`) to assert the encapsulated setters/
  snapshots mutate the underlying state. This guard is about the PUBLIC
  facade re-export, not internal home-namespace access."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.schemas]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.validator :as validator]))

(def ^:private raw-atom-syms
  "The four atoms that MUST NOT be public Vars on `re-frame.schemas`."
  '[schemas-by-frame validator-fn explainer-fn printer-fn])

(deftest raw-atoms-are-not-public-on-the-facade
  (testing "rf2-l5r974 / rf2-btdpqn — none of the four raw schema atoms is
            a public Var of the `re-frame.schemas` facade (the
            encapsulation-leak surface is gone)"
    (let [publics (ns-publics 're-frame.schemas)]
      (doseq [sym raw-atom-syms]
        (is (not (contains? publics sym))
            (str "re-frame.schemas/" sym " must NOT be a public re-export — "
                 "use the encapsulated snapshot/restore/clear API "
                 "(snapshot-schemas-by-frame / clear-schemas-by-frame! / "
                 "snapshot-schema-fns / set-schema-fns! …) instead."))))))

(deftest authoritative-atoms-still-live-in-their-home-namespaces
  (testing "the guard is non-vacuous: the four atoms DO still exist as the
            authoritative state in their home namespaces — they were
            privatised off the facade, not deleted"
    (is (instance? clojure.lang.Atom storage/schemas-by-frame)
        "storage/schemas-by-frame is the authoritative per-frame registry")
    (is (instance? clojure.lang.Atom validator/validator-fn)
        "validator/validator-fn is the authoritative validator atom")
    (is (instance? clojure.lang.Atom validator/explainer-fn)
        "validator/explainer-fn is the authoritative explainer atom")
    (is (instance? clojure.lang.Atom validator/printer-fn)
        "validator/printer-fn is the authoritative printer atom")))

(deftest encapsulated-replacements-are-public-on-the-facade
  (testing "the supported encapsulated replacements ARE public on
            `re-frame.schemas` — the migration target the swept test sites
            now use"
    (let [publics (ns-publics 're-frame.schemas)]
      (doseq [sym '[;; registry pair + clear (also drops frame-reg-locks)
                    snapshot-schemas-by-frame
                    restore-schemas-by-frame!
                    clear-schemas-by-frame!
                    ;; bundle pair (rf2-l4ljvr) + setters
                    snapshot-schema-fns
                    restore-schema-fns!
                    set-schema-fns!
                    set-schema-validator!
                    reset-schema-validator!]]
        (is (contains? publics sym)
            (str "re-frame.schemas/" sym " must remain public — it is the "
                 "encapsulated replacement for the privatised raw atoms."))))))
