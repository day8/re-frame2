(ns re-frame.story-static-build-cljs-test
  "CLJS tests for static-build behaviour.

  Spec coverage: `tools/story/spec/013-Static-Build.md` §
  Static-mode runtime semantics + § What gets bundled / stripped.

  `help-auto-open-active-under-dev-mode-first-visit` reads the default
  value of the `static-mode?` `goog-define` flag in this build, and the
  JVM `re-frame.story-test/static-mode-defaults-false-on-jvm` pins the
  public `(rf.story/static-mode?)` probe. This namespace covers the
  flag's *consequences*.

  This namespace covers the behavioural surfaces that are reachable from
  the node-test runner — i.e. anything that does not require a live
  shadow-cljs release build (`npm run test:story-static` covers the
  full release-mode smoke; that's a separate CI gate). The slice of
  static-build behaviour testable from the CLJS test bundle:

  - **Help overlay suppression contract.** When `static-mode?` is
    flipped on (we rebind the Var locally, standing in for what
    `:closure-defines` does at compile time), the help host's real
    `component-did-mount` leaves the overlay closed, so a help host
    that drops the `static-mode?` check fails this test.
  - **Hot-reload poll suppression.** Same shape: the shell's real
    `start-hot-reload-poll!` schedules no 500ms `setInterval` under
    static mode, so a shell that drops the check fails this test.
  - **Project-root fails closed.** Under `static-mode?` a passed
    project-root is dropped unless the host opts in, so a published
    export never carries the build machine's checkout path."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.config   :as rf.story.config]
            [re-frame.story.ui.help  :as rf.story.ui.help]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

;; ===========================================================================
;; HELP-OVERLAY SUPPRESSION CONTRACT (spec/013 §First-visit help overlay
;; suppressed)
;; ===========================================================================
;;
;; The help host's component-did-mount auto-opens the overlay on a first
;; visit unless `rf.story.config/static-mode?` is set. Node-test has no DOM
;; to mount the host into, so `help-host-auto-opens?` calls the host
;; class's real `componentDidMount` against a stand-in component (Reagent's
;; wrapper hands the component to the `:component-did-mount` fn, which the
;; host ignores) and reads the host's `open?` atom. Node-test has no
;; localStorage either, so `seen?` reads false unless a test rebinds it.

(def ^:private help-open? @#'rf.story.ui.help/open?)

(defn- help-host-auto-opens?
  "Run the help host's real `component-did-mount` from a closed overlay and
  report whether it opened the overlay, leaving the overlay closed."
  []
  (reset! help-open? false)
  (try
    (.call (.. (rf.story.ui.help/help-host) -prototype -componentDidMount) #js {})
    @help-open?
    (finally (reset! help-open? false))))

(deftest help-auto-open-suppressed-under-static-mode
  (testing "with static-mode? true, the help host does not auto-open the
            overlay — a static-export visitor never sees the dev-time
            onboarding modal pop unprompted"
    (with-redefs [rf.story.config/static-mode? true]
      (is (false? (help-host-auto-opens?))
          "static-mode wins: even a never-seen-it user gets no auto-open")
      (with-redefs [rf.story.ui.help/seen? (fn [] true)]
        (is (false? (help-host-auto-opens?))
            "static-mode still wins: a seen-it user gets no auto-open either")))))

(deftest help-auto-open-active-under-dev-mode-first-visit
  (testing "in dev-mode + never-seen, the help host auto-opens the
            overlay — the normal dev onboarding behaviour spec/013
            deliberately preserves for shadow-cljs watch sessions"
    (is (false? rf.story.config/static-mode?) "node-test build is dev-flavoured")
    (is (true? (help-host-auto-opens?)))))

(deftest help-auto-open-suppressed-under-dev-mode-after-seen
  (testing "in dev-mode + already-seen, the help host does not auto-open:
            the seen? flag (not the static-mode flag) short-circuits it"
    (with-redefs [rf.story.ui.help/seen? (fn [] true)]
      (is (false? (help-host-auto-opens?))))))

;; ===========================================================================
;; HOT-RELOAD POLL SUPPRESSION CONTRACT (spec/013 §No registrar-fingerprint
;; poll)
;; ===========================================================================
;;
;; Same shape as the help overlay, against the shell's real
;; `start-hot-reload-poll!`. Per spec/013 §No registrar-fingerprint poll:
;; the 500ms `setInterval` is wasted work under static-mode (the registrar
;; is frozen) and emits ratom-writes that thrash the React tree on every
;; tick; suppression eliminates both costs. Every test stops the poll it
;; starts, so no interval outlives the test.

(def ^:private start-hot-reload-poll! @#'rf.story.ui.shell/start-hot-reload-poll!)
(def ^:private stop-hot-reload-poll!  @#'rf.story.ui.shell/stop-hot-reload-poll!)
(def ^:private hot-reload-poll-handle @#'rf.story.ui.shell/hot-reload-poll-handle)

(defn- hot-reload-poll-starts?
  "Run the shell's real `start-hot-reload-poll!` from a stopped poll and
  report whether it scheduled an interval, leaving the poll stopped."
  []
  (stop-hot-reload-poll!)
  (try
    (start-hot-reload-poll!)
    (some? @hot-reload-poll-handle)
    (finally (stop-hot-reload-poll!))))

(deftest hot-reload-poll-suppressed-under-static-mode
  (testing "with static-mode? true, the shell schedules no setInterval —
            no ratom write fires every 500ms"
    (with-redefs [rf.story.config/static-mode? true]
      (is (false? (hot-reload-poll-starts?))
          "static-mode is the deciding gate"))
    (with-redefs [rf.story.config/enabled? false]
      (is (false? (hot-reload-poll-starts?))
          "enabled? false also gates — production elision is the other path"))))

(deftest hot-reload-poll-active-under-dev-mode
  (testing "in dev-mode (enabled? true, static-mode? false) the shell
            starts the poll, and a second start keeps the running one"
    (is (true? (hot-reload-poll-starts?))
        "all gates open — poll starts")
    (stop-hot-reload-poll!)
    (try
      (start-hot-reload-poll!)
      (let [handle @hot-reload-poll-handle]
        (start-hot-reload-poll!)
        (is (identical? handle @hot-reload-poll-handle)
            "handle already set — idempotent, do not re-start"))
      (finally (stop-hot-reload-poll!)))))

;; ===========================================================================
;; STATIC-EXPORT SELF-CONTAINMENT — open-in-editor project-root fails closed
;; ===========================================================================
;;
;; A published `story:build` export must not bake the build machine's
;; checkout root (a `C:/Users/<name>/...`-style absolute path) into its
;; open-in-editor URIs. The project-root is a DEV-time affordance; under
;; `static-mode?` `rf.story.config/set-project-root!` fails closed — a passed root is
;; ignored (the slot stays nil) unless a host explicitly opts in via
;; `rf.story.config/set-allow-static-project-root!`. The dev path (static-mode? false)
;; is unaffected. This guard is the whole of the defence: no build in the
;; repository derives a checkout path from its environment for a host to pass
;; in, so there is no ambient value for it to have to catch.

(def ^:private sentinel-root
  "A sentinel absolute checkout root that must NEVER survive into the
  static-export project-root slot. Shaped like a real build-machine home
  path so the assertion is meaningful."
  "C:/Users/leak-sentinel/code/my-app")

(deftest static-mode-suppresses-project-root-by-default
  (testing "under static-mode?, a passed project-root is ignored — the
            open-in-editor slot stays nil so no build-machine checkout path
            leaks into a published bundle's editor URIs"
    (rf.story.config/reset-all!)
    (with-redefs [rf.story.config/static-mode? true]
      (rf.story.config/set-project-root! sentinel-root)
      (is (nil? (rf.story.config/get-project-root))
          "static mode fails closed — the sentinel root is not retained"))
    (rf.story.config/reset-all!)))

(deftest static-mode-opt-in-restores-project-root
  (testing "a host that explicitly opts in via set-allow-static-project-root!
            keeps the root in static mode — the escape hatch for a published
            site that deep-links back into the author's editor"
    (rf.story.config/reset-all!)
    (with-redefs [rf.story.config/static-mode? true]
      (rf.story.config/set-allow-static-project-root! true)
      (rf.story.config/set-project-root! sentinel-root)
      (is (= sentinel-root (rf.story.config/get-project-root))
          "with the explicit opt-in the root is retained even in static mode"))
    (rf.story.config/reset-all!)))

(deftest reset-all-clears-static-opt-in
  (testing "rf.story.config/reset-all! restores the static project-root opt-in to its
            fail-closed default so a test that flips it cannot leak into the
            next test"
    (rf.story.config/set-allow-static-project-root! true)
    (rf.story.config/reset-all!)
    (is (false? (rf.story.config/allow-static-project-root?*))
        "opt-in is back to fail-closed after reset-all!")))
