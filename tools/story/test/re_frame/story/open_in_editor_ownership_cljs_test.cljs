(ns re-frame.story.open-in-editor-ownership-cljs-test
  "Cross-tool ownership contract for open-in-editor (rf2-5ot1d).

  ## What this pins

  Story and Xray each own a DISTINCT open-in-editor effect —
  `:rf.story.fx/open-in-editor` and `:rf.xray.fx/open-in-editor`. The
  retired `:rf.editor/open` was a single shared fx-id both tools
  registered, and `re-frame.registrar/register!` is last-writer-wins
  (`swap! reg assoc-in`), so whichever tool's installer ran LAST
  silently commandeered the other tool's editor preference, project
  root and navigator seam. Co-loading is the FLAGSHIP topology (Story
  mounts Xray as its embed), so that was not an edge case.

  The regression this file forbids: a load-order-dependent policy
  takeover. Both tools are configured with DELIBERATELY DIFFERENT
  editors and project roots, then installed in BOTH orders. Each
  tool's public event must drive its OWN tool's navigator with its
  OWN tool's URI, in either order.

  ## Why this test ns lives under Story

  Both tool source paths sit on the `:node-test` build (see
  `implementation/shadow-cljs.edn`), and Story tests already require
  Xray directly (`xray_preset_cljs_test.cljs`) because Story-mounting-
  Xray is the shipped topology. Same direction here.

  ## Seams (NOT `:fx-overrides`)

  Stubbing the effect via a frame's `:fx-overrides` would replace the
  very registration whose ownership is under test — a green test that
  proves nothing. Instead this file drives the REAL registered
  effects and observes them at each tool's own navigator seam
  (`open-in-editor/set-navigator!`), forcing the synchronous
  `editor://` URI path via the shared core endpoint-launcher seam
  (`rf.source-coords.open-endpoint/set-launcher!`), whose stub invokes the caller's
  `fallback!` thunk directly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.canonical :as rf.story.canonical]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.ui.open-in-editor :as rf.story.ui.open-in-editor]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.open-in-editor :as xray-open]))

;; ---- deliberately divergent per-tool configuration ----------------------
;;
;; Different editor AND different project root on each side, so a URI
;; observed at either navigator identifies its originating tool
;; unambiguously — a takeover cannot masquerade as a pass.

(def ^:private story-editor :zed)
(def ^:private story-root   "C:/repo/story-root")
(def ^:private xray-editor  :idea)
(def ^:private xray-root    "C:/repo/xray-root")

(def ^:private coord {:file "src/app/events.cljs" :line 17 :column 3})

(def ^:private story-uri
  "zed://file/C:/repo/story-root/src/app/events.cljs:17:3")

(def ^:private xray-uri
  "idea://open?file=C:/repo/xray-root/src/app/events.cljs&line=17&column=3")

;; ---- capture seams -------------------------------------------------------

(defonce ^:private story-navigated (atom []))
(defonce ^:private xray-navigated  (atom []))

(defn- configure-both-tools!
  "Give each tool its own editor + project root, and clear any operator
  override so `get-editor` resolves to the host default on the Xray side."
  []
  (rf.story.config/set-editor! story-editor)
  (rf.story.config/set-project-root! story-root)
  (xray-config/set-editor! xray-editor)
  (xray-config/set-project-root! xray-root)
  (xray-config/update-setting! :general :editor-override nil))

(defn- capture-navigators!
  "Point each tool's navigator seam at its own capture atom, and force
  the endpoint launcher to fall straight through to the `editor://` URI
  path so the whole flow is synchronous under `dispatch-sync`."
  []
  (reset! story-navigated [])
  (reset! xray-navigated [])
  (rf.story.ui.open-in-editor/set-navigator! #(swap! story-navigated conj %))
  (xray-open/set-navigator!  #(swap! xray-navigated conj %))
  nil)

(defn- install-story! [] (rf.story.ui.open-in-editor/install!))
(defn- install-xray!  [] (xray-open/install!))

(defn- ensure-adapter!
  "Install the plain-atom test adapter unless one is already installed.

  Per rf2-oslyz: `rf/make-frame` needs a state-container factory, and this
  namespace supplied none — every test here failed with
  `:rf.error/no-adapter-installed` when the selector ran it ALONE, and only
  passed in the consolidated run because some earlier namespace happened to
  have called `init!` first. A suite whose green depends on a neighbour is
  not a suite. `init!` throws when an adapter is already installed, which is
  the idempotent case, so the throw is the no-op branch."
  []
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil)))

(defn- fresh-frames!
  "Story's public event lands on `:rf/default`; Xray's on `:rf/xray`."
  []
  (ensure-adapter!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id :rf/xray}))

(defn- dispatch-both!
  "Fire each tool's PUBLIC event (the surface programmers and agents
  actually use — the fx-ids are internal)."
  []
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:rf.story/open-in-editor coord]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/open-in-editor coord])))

;; Every seam these tests swap is process-global, so each is saved and
;; restored around the test rather than reset to a guessed default —
;; leaking a capturing navigator or a synchronous launcher into another
;; namespace's tests would be a silent cross-suite corruption.
(use-fixtures :each
  (fn [run-test]
    (let [prev-story-nav (rf.story.ui.open-in-editor/set-navigator! #(swap! story-navigated conj %))
          prev-xray-nav  (xray-open/set-navigator! #(swap! xray-navigated conj %))
          ;; The shared core seam: `open-coord!` calls
          ;; `(@launcher url fallback!)`. Invoking `fallback!` immediately
          ;; models "no dev server present" — the documented standalone
          ;; contract, and the only path carrying the per-tool URI.
          prev-launcher  (rf.source-coords.open-endpoint/set-launcher!
                           (fn [_url fallback!] (fallback!)))]
      (try
        (run-test)
        (finally
          (xray-config/update-setting! :general :editor-override nil)
          (xray-config/set-project-root! nil)
          (rf.story.config/set-project-root! nil)
          (rf.source-coords.open-endpoint/set-launcher! prev-launcher)
          (rf.story.ui.open-in-editor/set-navigator! prev-story-nav)
          (xray-open/set-navigator! prev-xray-nav))))))

;; ---- the ownership contract ---------------------------------------------

(deftest each-tool-keeps-its-own-editor-regardless-of-install-order
  (testing "rf2-5ot1d — installing Story-then-Xray AND Xray-then-Story
            both leave each tool driving its OWN navigator with its OWN
            editor + project root. Under the retired shared
            `:rf.editor/open` the second installer overwrote the first,
            so exactly one of these two orders necessarily failed."
    (doseq [[label install-first! install-second!]
            [["story-then-xray" install-story! install-xray!]
             ["xray-then-story" install-xray!  install-story!]]]
      (configure-both-tools!)
      (install-first!)
      (install-second!)
      (fresh-frames!)
      (capture-navigators!)
      (dispatch-both!)

      (is (= [story-uri] @story-navigated)
          (str label " — Story's navigator receives Story's editor ("
               story-editor ") and Story's project root; it is NOT "
               "carrying Xray's " xray-editor " URI"))
      (is (= [xray-uri] @xray-navigated)
          (str label " — Xray's navigator receives Xray's editor ("
               xray-editor ") and Xray's project root; it is NOT "
               "carrying Story's " story-editor " URI")))))

(deftest the-two-effect-ids-are-distinct-and-separately-registered
  (testing "rf2-5ot1d — the tools register two DISTINCT fx-ids, so no
            `[kind id]` pair is claimed by two provenance namespaces.
            Cross-source same-id provenance is exactly what image
            assembly rejects as `:rf.error/image-duplicate-id`; distinct
            ids make the collision unreachable rather than merely
            unlikely."
    (configure-both-tools!)
    (install-story!)
    (install-xray!)
    (fresh-frames!)
    (capture-navigators!)

    (is (not= :rf.story.fx/open-in-editor :rf.xray.fx/open-in-editor)
        "the ids are distinct")
    ;; Drive each effect directly and confirm each is really registered
    ;; AND wired to its own tool — a missing registration would leave the
    ;; corresponding capture empty.
    (rf/with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor coord]))
    (is (= [story-uri] @story-navigated)
        ":rf.story.fx/open-in-editor is registered and Story-owned")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-in-editor coord]))
    (is (= [xray-uri] @xray-navigated)
        ":rf.xray.fx/open-in-editor is registered and Xray-owned")))

;; ---- Xray-specific policy stays Xray-owned ------------------------------

(deftest xray-operator-override-does-not-reach-story
  (testing "rf2-5ot1d — the operator's `[:general :editor-override]`
            is Xray policy. It must retarget Xray's launch and leave
            Story's entirely alone, in a co-loaded process."
    (configure-both-tools!)
    (install-story!)
    (install-xray!)
    (fresh-frames!)
    (capture-navigators!)
    (xray-config/update-setting! :general :editor-override :cursor)

    (dispatch-both!)

    (is (= ["cursor://file/C:/repo/xray-root/src/app/events.cljs:17:3"]
           @xray-navigated)
        "the operator override retargets Xray to :cursor")
    (is (= [story-uri] @story-navigated)
        "Story still launches its OWN :zed editor — the Xray operator
         override is not a global editor setting")))

(deftest xray-unconfigured-editor-hint-does-not-gate-story
  (testing "rf2-5ot1d — Xray's unconfigured-editor DX hint is Xray
            EVENT policy (rf2-4s08ov). With no editor confirmed on the
            Xray side it must suppress Xray's silent navigation while
            Story — which has no such gate — still opens normally."
    (rf.story.config/set-editor! story-editor)
    (rf.story.config/set-project-root! story-root)
    (xray-config/set-project-root! xray-root)
    ;; Reset Xray to the unconfirmed state: no host editor, no override.
    (xray-config/set-editor! nil)
    (xray-config/update-setting! :general :editor-override nil)
    (install-story!)
    (install-xray!)
    (fresh-frames!)
    (capture-navigators!)

    (is (false? (xray-config/editor-configured?))
        "precondition — Xray considers itself unconfigured")

    (dispatch-both!)

    (is (= [] @xray-navigated)
        "Xray fires the Settings hint instead of a silent navigation —
         no URI reaches Xray's navigator")
    (is (= [story-uri] @story-navigated)
        "Story is unaffected by Xray's hint gate and opens as usual")))

;; ---- Story's public event is actually installed in production -----------

(deftest story-public-event-exists-after-canonical-boot
  (testing "rf2-5ot1d item 4 — `[:rf.story/open-in-editor coord]` is a
            DOCUMENTED public dispatch, but its installer was absent
            from the canonical roster: the sole caller was Story's own
            test, so the documented path had no production handler.
            The installer now rides the canonical roster, so the event
            works after an explicit `rf.story.canonical/install!` with no
            hand-wiring."
    (configure-both-tools!)
    (rf.story.canonical/reset-installed-flag!)
    (rf.story.canonical/install!)
    (fresh-frames!)
    (capture-navigators!)

    (rf/with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor coord]))

    (is (= [story-uri] @story-navigated)
        "canonical boot alone wires the public event through to Story's
         navigator — no explicit `ui.open-in-editor/install!` call")))
