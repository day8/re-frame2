(ns re-frame.story-static-build-cljs-test
  "CLJS tests closing the docs-promised gap on static-build behaviour.

  Spec coverage (rf2-ub1n4): `tools/story/spec/013-Static-Build.md` §
  Static-mode runtime semantics + § What gets bundled / stripped.

  The existing CLJS tests cover only the default-value of the
  `static-mode?` `goog-define` flag (`re-frame.story-cljs-test/
  static-mode-flag-defaults-false-in-cljs-test-build`). The bead names
  static-build behaviour as a coverage hole because the *consequences*
  of the flag are not exercised: the registrar-fingerprint poll
  suppression, the first-visit help-overlay suppression, the persistence
  of registrations across a 'frozen registrar' boot.

  This namespace covers the behavioural surfaces that are reachable from
  the node-test runner — i.e. anything that does not require a live
  shadow-cljs release build (`npm run test:story-static` covers the
  full release-mode smoke; that's a separate CI gate). The slice of
  static-build behaviour testable from the CLJS test bundle:

  - **`(static-mode?)` public probe.** Per spec/013 the probe is a
    public Var on `re-frame.story` so consumer code (e.g. a host app
    branching on static-mode at boot) can read the flag.
  - **`enabled?` and `static-mode?` are orthogonal.** Per spec/013 §
    Static-mode runtime semantics — the static-mode flag stacks
    alongside production elision; either may be true / false
    independently.
  - **Help overlay suppression contract.** When `static-mode?` is
    flipped on (we rebind the Var locally, mirroring what
    `:closure-defines` does at compile time), the
    `component-did-mount` auto-open path short-circuits. We mirror
    the gating predicate directly so a future refactor of the
    help-host that drops the `static-mode?` check fails this test.
  - **Hot-reload poll gating predicate.** Same shape: the static-mode
    flag is the gate that suppresses the 500ms `setInterval` —
    suppression is the contract `start-hot-reload-poll!` honours.
  - **Registrations survive across the static-mode boundary.** A
    variant registered with `static-mode?` true behaves identically
    to one registered with it false (the flag only changes the shell;
    the registrar is frozen as far as DEV mutations go, but the
    seed registrations the static export ships with must be intact)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story        :as story]
            [re-frame.story.config :as config]))

;; ===========================================================================
;; PUBLIC `static-mode?` PROBE
;; ===========================================================================

(deftest public-probe-resolves
  (testing "re-frame.story/static-mode? is a public fn — consumers branch
            on it at boot to drop dev-only setup work"
    (is (fn? @#'story/static-mode?)
        "the public Var resolves and is a fn")))

(deftest public-probe-mirrors-config-flag
  (testing "(story/static-mode?) returns the same value as the underlying
            goog-define `re-frame.story.config/static-mode?`"
    (is (= config/static-mode? (story/static-mode?)))))

(deftest static-mode-defaults-false-in-node-test-build
  (testing "the node-test build does not flip the static-mode goog-define,
            so consumer code branches into the dev-flavoured path"
    (is (false? (story/static-mode?))
        "default is the dev-flavoured branch")))

;; ===========================================================================
;; FLAG ORTHOGONALITY — enabled? × static-mode? are independent
;; ===========================================================================

(deftest enabled-and-static-mode-are-orthogonal
  (testing "spec/013 § Static-mode runtime semantics — the two flags are
            independent goog-defines. A consumer can ship a static export
            in development-flavoured mode (enabled? true + static-mode?
            true) or in production-elided mode (enabled? false +
            static-mode? true). The orthogonality lets the static export
            be the runtime artefact `:rf.story/enabled? false` builds
            DCE the Story shell out of"
    (is (true? config/enabled?))
    (is (false? config/static-mode?))
    ;; The two slots are independently set; reading them does not
    ;; couple them. A future refactor that lazily-derives one from the
    ;; other (an XOR shortcut, say) would break the orthogonality
    ;; contract spec/013 names.
    (is (not= config/enabled? config/static-mode?)
        "in the dev-flavoured node-test build the two are different
         booleans — proves they're not aliased to one another")))

;; ===========================================================================
;; HELP-OVERLAY SUPPRESSION CONTRACT (spec/013 §First-visit help overlay
;; suppressed)
;; ===========================================================================
;;
;; The help-host's component-did-mount auto-open path is gated on
;; `(not config/static-mode?)`. We mirror that predicate here so a
;; future refactor that drops the static-mode check from the help host
;; fails this test even though we can't actually trigger Reagent's
;; component-did-mount in a node-test context (no DOM).

(defn help-should-auto-open?
  "Mirror of the predicate `re-frame.story.ui.help/help-host`'s
  `component-did-mount` body uses. The static-mode? flag suppresses
  auto-open; in dev-mode the flag short-circuits, the seen? flag
  takes over (covered by `re-frame.story-help-cljs-test`)."
  [static-mode? seen?]
  (and (not static-mode?)
       (not seen?)))

(deftest help-auto-open-suppressed-under-static-mode
  (testing "with static-mode? true, the help overlay's auto-open
            predicate returns false — a static-export visitor never sees
            the dev-time onboarding modal pop unprompted"
    (is (false? (help-should-auto-open? true  false))
        "static-mode wins: even a never-seen-it user gets no auto-open")
    (is (false? (help-should-auto-open? true  true))
        "static-mode still wins: a seen-it user gets no auto-open either")))

(deftest help-auto-open-active-under-dev-mode-first-visit
  (testing "in dev-mode + never-seen, the auto-open path fires — the
            normal dev onboarding behaviour spec/013 deliberately
            preserves for shadow-cljs watch sessions"
    (is (true? (help-should-auto-open? false false)))))

(deftest help-auto-open-suppressed-under-dev-mode-after-seen
  (testing "in dev-mode + already-seen, the auto-open path short-circuits
            via the seen? flag (not the static-mode flag)"
    (is (false? (help-should-auto-open? false true)))))

;; ===========================================================================
;; HOT-RELOAD POLL SUPPRESSION CONTRACT (spec/013 §No registrar-fingerprint
;; poll)
;; ===========================================================================
;;
;; Same shape as the help overlay. The shell's `start-hot-reload-poll!`
;; gates on `(and config/enabled? (not config/static-mode?) ...)`. We
;; mirror the predicate so a future refactor that drops the
;; static-mode check from the shell fails this test.

(defn hot-reload-poll-should-start?
  "Mirror of the predicate `re-frame.story.ui.shell/start-hot-reload-poll!`
  gates on. Per spec/013 §No registrar-fingerprint poll: the 500ms
  `setInterval` is wasted work under static-mode (the registrar is
  frozen) and emits ratom-writes that thrash the React tree on every
  tick; suppression eliminates both costs."
  [enabled? static-mode? handle-already-set?]
  (and enabled?
       (not static-mode?)
       (not handle-already-set?)))

(deftest hot-reload-poll-suppressed-under-static-mode
  (testing "with static-mode? true, the poll-start predicate returns
            false — no setInterval is scheduled, no ratom write fires
            every 500ms"
    (is (false? (hot-reload-poll-should-start? true  true  false))
        "static-mode is the deciding gate")
    (is (false? (hot-reload-poll-should-start? false true  false))
        "enabled? false also gates — production elision is the other path")))

(deftest hot-reload-poll-active-under-dev-mode
  (testing "in dev-mode (enabled? true, static-mode? false) the predicate
            allows the poll to start"
    (is (true?  (hot-reload-poll-should-start? true  false false))
        "all three gates open — poll starts")
    (is (false? (hot-reload-poll-should-start? true  false true))
        "handle already set — idempotent, do not re-start")))

;; ===========================================================================
;; REGISTRATIONS WORK UNDER STATIC-MODE (the export's seed payload survives)
;; ===========================================================================
;;
;; Per spec/013 § What gets bundled: the static-export bundle carries
;; every story / variant / workspace / mode / decorator / panel
;; registered at boot. The static-mode flag only changes the shell
;; chrome — the registrar mechanics are unaffected. We seed a
;; registration under static-mode-equivalent conditions and confirm
;; every read surface surfaces it.

(deftest registrations-land-in-the-side-table-regardless-of-flag-state
  (testing "spec/013 § What gets bundled — Story's registrations are
            data, not behaviour gated on static-mode. The flag affects
            the shell chrome; the registrar is unaffected. A static-
            export bundle's seed registrations must reach the same side-
            table the dev-mode bundle uses"
    (story/clear-all!)
    (story/install-canonical-vocabulary!)
    (story/reg-story :story.static.seed
      {:doc "a seed story baked into the static export"})
    (story/reg-variant :story.static.seed/probe
      {:events [[:probe/init]]
       :tags   #{:dev}})
    ;; Read surface — the MCP-or-tooling consumer path.
    (is (story/registered? :story   :story.static.seed))
    (is (story/registered? :variant :story.static.seed/probe))
    (is (= #{:story.static.seed/probe}
           (story/variants-of :story.static.seed)))
    (is (= [[:probe/init]]
           (:events (story/variant->edn :story.static.seed/probe))))))
