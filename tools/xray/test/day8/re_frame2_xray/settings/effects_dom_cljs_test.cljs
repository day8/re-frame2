(ns day8.re-frame2-xray.settings.effects-dom-cljs-test
  "Browser-lane half of the Settings side-effect tests, promoted out of
  `day8.re-frame2-xray.settings.effects-cljs-test` under rf2-r51p.

  WHY A SEPARATE NAMESPACE. Every row here asserts a real DOM mutation
  — a CSS custom property on the shell root or `<html>`, a theme class
  on a `classList`, a `data-rf-force-colors` attribute. The sibling
  keeps the host-free half: the settings-atom reads, the `with-redefs`
  substrate wiring and the missing-shell-root no-op claims.

  THESE ROWS EXECUTED IN NEITHER LANE. Each was wrapped in
  `(when-let [el (shell-root)] ...)`, and `shell-root` /`html-root` are
  themselves `(when (exists? js/document) ...)`. The sibling's
  `ensure-stub-shell-root!` looks like it supplies the missing host but
  does not: it too opens with `(and (exists? js/document)
  (.-createElement js/document))`, so on node it creates nothing and
  every `when-let` binds nil. Node has no jsdom, happy-dom or
  dom-storage in any dependency list. Meanwhile `:browser-test`'s
  `:ns-regexp` is `.*-dom-cljs-test$`, which the sibling's name never
  matched — so it never loaded the file at all. Measured before the
  move: the sibling ran 30 test vars containing 40 assertions against
  68 `is` forms in the source. 28 of them had never executed.

  THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES. `:node-test`'s
  `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, so the node
  build loads this namespace too and the overlap is deliberate (see the
  comment above `:browser-test` in `implementation/shadow-cljs.edn`).
  Moving a row here ADDS the browser lane; it does not take the row off
  the node one. [[browser?]] is what keeps the node run inert, and the
  skip branch ASSERTS a marker row so the node lane never holds a
  deftest with zero assertions — the hollow shape rf2-r51p exists to
  remove.

  EACH ROW ASSERTS ITS HOST PRECONDITION BEFORE READING IT. `(when-let
  [el (shell-root)] (is ...))` is the shape that made these rows dead,
  and it fails OPEN: a nil root silently skips the assertion instead of
  reporting one. The rows below bind the root, assert it is present,
  and reach through `some->` — so a missing root is a named failure
  rather than a vanished test. `some->` also matters because the
  browser lane runs every namespace inside one `cljs.test/run-block`
  with no try/catch, where a nil-deref would take down the rows after
  it as well.

  These assertions had never executed in ANY lane before this namespace
  existed. A failure here is evidence about the Settings effects
  arriving for the first time, not a regression introduced by the move."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.effects :as effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- host predicate (house shape — see
;; `day8.re-frame2-xray.palette.empty-row-frame-context-dom-cljs-test`)

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- stub shell root ----------------------------------------------------

(defn- ensure-stub-shell-root! []
  (when (browser?)
    (when-not (.getElementById js/document "rf-xray-root")
      (let [el (.createElement js/document "div")]
        (set! (.-id el) "rf-xray-root")
        (when (.-body js/document)
          (.appendChild (.-body js/document) el))))))

(defn- remove-stub-shell-root! []
  (when (browser?)
    (when-let [el (.getElementById js/document "rf-xray-root")]
      (when (.-parentNode el)
        (.removeChild (.-parentNode el) el)))))

(defn- shell-root []
  (when (browser?)
    (.getElementById js/document "rf-xray-root")))

(defn- html-root []
  (when (browser?)
    (.-documentElement js/document)))

(defn- css-var [el prop]
  (some-> el .-style (.getPropertyValue prop)))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; Mirrors the sibling's fixture. The teardown matters more here than
  ;; on node: the browser lane runs every namespace on ONE page, so a
  ;; stamped `<html>` or a leftover stub root would leak into whatever
  ;; namespace runs next.
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :post-reset (fn []
                   (effects/detach-auto-open-watcher!)
                   (remove-stub-shell-root!))}))

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- text-size ----------------------------------------------------------

(deftest apply-text-size-writes-css-var
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (ensure-stub-shell-root!)
      (effects/apply-text-size! 17)
      (let [el   (shell-root)
            html (html-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "17px" (css-var el "--rf-xray-text-size"))
            "shell root CSS var carries the value")
        (is (= "17px" (css-var html "--rf-xray-text-size"))
            "<html> CSS var also carries the value")))))

;; ---- theme --------------------------------------------------------------

(deftest apply-theme-toggles-class
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (ensure-stub-shell-root!)
      (effects/apply-theme! :light)
      (let [el (shell-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (true? (some-> el .-classList (.contains "rf-xray-theme-light")))
            "light class applied")
        (is (false? (some-> el .-classList (.contains "rf-xray-theme-dark")))
            "dark class removed"))
      ;; Switch to dark
      (effects/apply-theme! :dark)
      (let [el (shell-root)]
        (is (true? (some-> el .-classList (.contains "rf-xray-theme-dark"))))
        (is (false? (some-> el .-classList (.contains "rf-xray-theme-light"))))))))

;; ---- update-setting! drives the side effect ----------------------------

(deftest update-event-applies-text-size-effect
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (setup!)
      (ensure-stub-shell-root!)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update :general :text-size 15]))
      (let [el (shell-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "15px" (css-var el "--rf-xray-text-size"))
            "dispatching update writes the CSS var")))))

(deftest update-event-applies-theme-effect
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (setup!)
      (ensure-stub-shell-root!)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update :theme nil :light]))
      (let [el (shell-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (true? (some-> el .-classList (.contains "rf-xray-theme-light"))))))))

;; ---- apply-all! ---------------------------------------------------------

(deftest apply-all-restores-text-size-and-theme
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (ensure-stub-shell-root!)
      (config/update-setting! :general :text-size 12)
      (config/update-setting! :theme nil :light)
      ;; Re-apply via the boot path.
      (effects/apply-all!)
      (let [el (shell-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "12px" (css-var el "--rf-xray-text-size")))
        (is (true? (some-> el .-classList (.contains "rf-xray-theme-light"))))))))

;; ---- panel width (rf2-x8h9y) -------------------------------------------

(deftest apply-all-restores-panel-width
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "rf2-x8h9y — boot path restores the persisted panel width
              so the user's saved drag survives reload BEFORE first
              paint. The CSS var lands on `<html>` (the cascade reaches
              the layout host's flex-basis even pre-mount)."
      (config/update-setting! :general :panel-width-px 700)
      (effects/apply-all!)
      (let [html (html-root)]
        (is (some? html) "precondition: <html> is reachable")
        (is (= "700px" (css-var html "--rf-xray-inline-width"))
            "<html> --rf-xray-inline-width carries the persisted value")))))

;; ---- use-system-colors? (rf2-846h2) ------------------------------------

(deftest apply-use-system-colors-stamps-and-clears-attribute
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "rf2-846h2 — apply-use-system-colors! stamps
              `data-rf-force-colors=active` on the shell root +
              `<html>` when truthy, removes it when falsey."
      (ensure-stub-shell-root!)
      (effects/apply-use-system-colors! true)
      (let [el   (shell-root)
            html (html-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "active" (some-> el (.getAttribute effects/force-colors-attribute)))
            "shell root carries the active attribute when toggle on")
        (is (= "active" (some-> html (.getAttribute effects/force-colors-attribute)))
            "<html> carries the active attribute when toggle on"))
      (effects/apply-use-system-colors! false)
      (let [el   (shell-root)
            html (html-root)]
        (is (nil? (some-> el (.getAttribute effects/force-colors-attribute)))
            "shell root attribute cleared when toggle off")
        (is (nil? (some-> html (.getAttribute effects/force-colors-attribute)))
            "<html> attribute cleared when toggle off")))))

(deftest apply-use-system-colors-stamps-html-without-a-shell-root
  ;; The `<html>`-still-written half of the sibling's
  ;; `apply-use-system-colors-handles-missing-shell-root`. The no-op
  ;; return claim in that test is host-free and STAYED on node; only
  ;; this DOM half moved, so neither lane loses an assertion.
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "rf2-846h2 — with no shell root, `<html>` still gets the
              attribute write so the cascade reaches descendants before
              the shell mounts."
      (remove-stub-shell-root!)
      (is (nil? (shell-root)) "precondition: the shell root really is absent")
      (effects/apply-use-system-colors! true)
      (let [html (html-root)]
        (is (= "active" (some-> html (.getAttribute effects/force-colors-attribute)))
            "<html> attribute still landed even without the shell root"))
      ;; Clean up so unrelated namespaces don't see the stamped <html>.
      (effects/apply-use-system-colors! false))))

(deftest update-event-applies-use-system-colors-effect
  ;; The settings-atom half of the sibling's test of the same name is
  ;; host-free and STAYED on node; only the attribute claims moved.
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "rf2-846h2 — dispatching `:rf.xray/settings-update :general
              :use-system-colors? true` stamps the chrome attribute via
              the matching effect."
      (setup!)
      (ensure-stub-shell-root!)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update
                           :general :use-system-colors? true]))
      (let [el (shell-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "active" (some-> el (.getAttribute effects/force-colors-attribute)))
            "dispatching update stamps the attribute"))
      ;; Flip off and verify clear.
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update
                           :general :use-system-colors? false]))
      (let [el (shell-root)]
        (is (nil? (some-> el (.getAttribute effects/force-colors-attribute)))
            "dispatching update with false clears the attribute")))))

(deftest apply-all-restores-use-system-colors
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "rf2-846h2 — the boot path re-applies the persisted toggle
              so the user's saved opt-in survives reload BEFORE first
              paint."
      (ensure-stub-shell-root!)
      (config/update-setting! :general :use-system-colors? true)
      (effects/apply-all!)
      (let [el   (shell-root)
            html (html-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "active" (some-> el (.getAttribute effects/force-colors-attribute)))
            "shell root attribute restored from persistence")
        (is (= "active" (some-> html (.getAttribute effects/force-colors-attribute)))
            "<html> attribute restored from persistence"))
      ;; Clean up.
      (config/update-setting! :general :use-system-colors? false)
      (effects/apply-all!))))

;; ---- density font-size --------------------------------------------------

(deftest apply-density-font-size-writes-css-var
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (do
      (ensure-stub-shell-root!)
      (effects/apply-density-font-size! :compact)
      (let [el   (shell-root)
            html (html-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "12px" (css-var el "--rf-xray-font-size"))
            "shell root --rf-xray-font-size carries the compact value")
        (is (= "12px" (css-var html "--rf-xray-font-size"))
            "<html> --rf-xray-font-size carries the compact value"))
      (effects/apply-density-font-size! :cosy)
      (is (= "13px" (css-var (shell-root) "--rf-xray-font-size"))
          "shell root rewrites to 13px on cosy flip")
      (effects/apply-density-font-size! :comfy)
      (is (= "14px" (css-var (shell-root) "--rf-xray-font-size"))
          "shell root rewrites to 14px on comfy"))))

(deftest update-event-applies-density-font-size-effect
  ;; The settings-atom half of the sibling's test of the same name is
  ;; host-free and STAYED on node; only the CSS-var claims moved.
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "Dispatching `[:rf.xray/settings-update :general :density
              :compact]` flips `--rf-xray-font-size` to 12px so the
              whole `type-scale` rescales on the next paint."
      (setup!)
      (ensure-stub-shell-root!)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update
                           :general :density :compact]))
      (let [el (shell-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "12px" (css-var el "--rf-xray-font-size"))
            "dispatch writes 12px on compact"))
      ;; Flip to cosy — verify the inline write rewrites (not just adds).
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/settings-update
                           :general :density :cosy]))
      (is (= "13px" (css-var (shell-root) "--rf-xray-font-size"))
          "dispatch writes 13px on cosy"))))

(deftest apply-all-restores-density-font-size
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (testing "rf2-i40us — boot path restores the persisted density so
              the user's saved knob rescales the type scale BEFORE first
              paint. The CSS var lands on the shell root + `<html>`."
      (ensure-stub-shell-root!)
      (config/update-setting! :general :density :compact)
      (effects/apply-all!)
      (let [el   (shell-root)
            html (html-root)]
        (is (some? el) "precondition: stub shell root is present")
        (is (= "12px" (css-var el "--rf-xray-font-size"))
            "shell root --rf-xray-font-size carries the persisted compact value")
        (is (= "12px" (css-var html "--rf-xray-font-size"))
            "<html> --rf-xray-font-size carries the persisted compact value")))))
