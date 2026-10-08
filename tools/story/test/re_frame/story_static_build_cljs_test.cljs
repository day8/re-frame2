(ns re-frame.story-static-build-cljs-test
  "CLJS tests for the consequences of the `static-mode?` flag
  (`tools/story/spec/013-Static-Build.md` §Static-mode runtime semantics,
  §What gets bundled / stripped) that the node-test runner can reach: the
  help host does not auto-open, the shell schedules no hot-reload poll, and
  a passed project-root fails closed. Each rebinds the flag locally, standing
  in for `:closure-defines`; `npm run test:story-static` smokes the release
  build."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.config   :as rf.story.config]
            [re-frame.story.ui.help  :as rf.story.ui.help]
            [re-frame.story.ui.shell :as rf.story.ui.shell]))

;; Node-test has no DOM to mount the help host into, so the tests call the
;; host class's real `componentDidMount` against a stand-in component and
;; read its `open?` atom; with no localStorage, `seen?` reads false unless
;; rebound.

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
  (testing "in dev mode the help host auto-opens on a first visit — the
            onboarding spec/013 keeps for watch sessions — and the seen? flag,
            not static mode, suppresses it afterwards"
    (is (false? rf.story.config/static-mode?) "node-test build is dev-flavoured")
    (is (true? (help-host-auto-opens?)))
    (with-redefs [rf.story.ui.help/seen? (fn [] true)]
      (is (false? (help-host-auto-opens?))))))

;; spec/013 §No registrar-fingerprint poll: under static mode the registrar
;; is frozen, so the shell's 500ms poll would only thrash the React tree.

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

;; A published export must not bake the build machine's checkout root into its
;; open-in-editor URIs: under static mode `set-project-root!` ignores a passed
;; root unless the host opts in with `set-allow-static-project-root!`.

(def ^:private sentinel-root
  "A checkout root shaped like a real build-machine home path, which must
  never survive into the static-export project-root slot."
  "C:/Users/leak-sentinel/code/my-app")

(deftest static-mode-suppresses-project-root-by-default
  (testing "under static mode a passed project-root is dropped, and an explicit
            opt-in keeps it (a published site deep-linking to its author's editor)"
    (doseq [[opt-in? expected] [[false nil] [true sentinel-root]]]
      (rf.story.config/reset-all!)
      (with-redefs [rf.story.config/static-mode? true]
        (when opt-in? (rf.story.config/set-allow-static-project-root! true))
        (rf.story.config/set-project-root! sentinel-root)
        (is (= expected (rf.story.config/get-project-root)) (str "opt-in " opt-in?))))
    (rf.story.config/reset-all!)))

(deftest reset-all-clears-static-opt-in
  (testing "rf.story.config/reset-all! restores the static project-root opt-in to its
            fail-closed default so a test that flips it cannot leak into the
            next test"
    (rf.story.config/set-allow-static-project-root! true)
    (rf.story.config/reset-all!)
    (is (false? (rf.story.config/allow-static-project-root?*))
        "opt-in is back to fail-closed after reset-all!")))
