(ns day8.re-frame2-causa.mount-cljs-test
  "Tests for Causa's DOM-side mount state machine (rf2-5x20s; source
  rf2-otcbz audit recommendation #6).

  `mount.cljs` carries six public fns — `mounted?`, `visible?`,
  `open!`, `close!`, `toggle!`, `teardown!` — whose combined contract
  is a small state machine over a single `defonce` singleton atom.
  Until this file the surface was untouched: the keybinding tests
  (rf2-jbhm5) cover the *attach/detach* listener-management lane and
  the shell tests (rf2-3lh6h) cover the *rendered hiccup* — neither
  exercises the *open → close → toggle → teardown* transitions nor
  the silent-no-op posture when no substrate adapter is installed.

  ## Three contract surfaces under test

  1. **State machine.** `(mounted?) ⇔ (some? @mount-state)`;
     `(visible?) ⇔ (:visible? @mount-state)`. After `open!`, both
     true; after `close!`, mounted? remains true but visible?
     flips false; after `teardown!`, both false again (the
     singleton is cleared and the DOM node removed).

  2. **Lazy-mount affordance.** The mount cost (DOM node + substrate
     render) is paid once on first `open!`. Subsequent `open!`s flip
     the container's CSS display only — no re-render, no new DOM
     node, no second `substrate-adapter/render` call. This is the
     spec/007-UX-IA.md §The default landing view <80ms toggle
     target: re-mounting would discard internal state and miss the
     budget.

  3. **Missing-adapter gate.** Per the source docstring `open!` is
     gated on `(substrate-adapter/current-adapter)`. When no
     adapter is installed — production-bundle accident, host that
     never called `rf/init!`, mid-`dispose-adapter!` race — the
     call returns nil silently. No DOM node, no state mutation, no
     throw. The user's Ctrl+Shift+C is a no-op until an adapter
     installs.

  ## Why these tests run on node-test (not browser-test)

  The mount logic is pure DOM-manipulation: `document.createElement`,
  `appendChild`, `removeChild`, `style.display`. Node-test has no
  `js/document` by default, so we install a minimal stub for the
  duration of each test. The substrate `render` fn that mount.cljs
  delegates to is stubbed via `with-redefs` so the test never depends
  on a real React tree (the shell tests live in shell_cljs_test.cljs
  on the hiccup-walk lane). The browser-level integration story —
  shadow-cljs preload + real Reagent render + real Ctrl+Shift+C —
  lives in the Playwright lane (rf2-s2bhn)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.mount :as mount]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- private-state accessors --------------------------------------------
;;
;; `mount/mount-state` is a `defonce` private atom — reaching it through
;; `#'mount/mount-state` lets the fixture reset the singleton between
;; tests so the state-machine transitions start from a known baseline.
;; The atom is reset, not replaced, so the defonce identity is preserved
;; (mirrors the keybinding-test pattern of poking `(detach!)` rather
;; than re-binding the sentinel itself).

(defn- reset-mount-state! []
  (reset! @#'mount/mount-state nil)
  (reset! @#'mount/popout-state nil))

;; ---- js/document stub ---------------------------------------------------
;;
;; Node-test has no `js/document`; the mount fns call
;; `(.createElement js/document "div")`, `(.appendChild ...)`,
;; `(.removeChild ...)` and read `(.-parentNode node)`. We install a
;; hand-rolled stub for the duration of each test that exposes:
;;
;;   - `js/document` with `createElement` + `body`
;;   - body.appendChild / body.removeChild
;;   - each created node has its own `style` map, `parentNode` slot,
;;     `id` slot, plus an `appendChild` / `removeChild` so the shell's
;;     render tree can attach children if it wants to.
;;
;; The stub mirrors the surface mount.cljs actually touches —
;; `createElement`, `appendChild`, `removeChild`, `style.display`,
;; `parentNode`. We do not try to be a full JSDOM; we just cover the
;; calls `create-mount-node!` and `teardown!` make.

(defn- mk-stub-node []
  (let [attrs (atom {})
        node (js-obj
              "style"     (js-obj "display" "")
              "id"        ""
              "tagName"   "DIV"
              "children"  (array))]
    (set! (.-parentNode node) nil)
    (set! (.-setAttribute node)
          (fn [k v]
            (swap! attrs assoc k v)
            nil))
    (set! (.-getAttribute node)
          (fn [k]
            (get @attrs k)))
    (set! (.-removeAttribute node)
          (fn [k]
            (swap! attrs dissoc k)
            nil))
    (set! (.-appendChild node)
          (fn [child]
            (.push (.-children node) child)
            (set! (.-parentNode child) node)
            child))
    (set! (.-removeChild node)
          (fn [child]
            (let [idx (.indexOf (.-children node) child)]
              (when (>= idx 0)
                (.splice (.-children node) idx 1))
              (set! (.-parentNode child) nil)
              child)))
    node))

(defn- mk-stub-document []
  (let [created (atom [])
        body    (mk-stub-node)]
    (js-obj
     "body"
     body
     "createElement"
     (fn [_tag]
       (let [n (mk-stub-node)]
         (swap! created conj n)
         n))
     "querySelector"
     (fn [selector]
       (when (= selector "[data-rf-causa-host]")
         body))
     ;; Test introspection — not part of the DOM API, but lets the
     ;; assertions count nodes created during a run.
     "_created"
     created)))

(defn- can-stub-js-document?
  "True iff the running host lets us write `js/document` via `set!`.

  In node-test there is no real `document` global; `(set! js/document
  ...)` installs a fresh slot on `goog.global` and subsequent reads
  see the new value. In a real browser `window.document` is a non-
  configurable read-only WebIDL accessor — the JS engine silently
  drops the assignment (or throws in strict mode), so subsequent
  reads still return the genuine `HTMLDocument`. Test code that
  installs a stub via `set! js/document` and asserts against that
  stub silently fails in that case.

  Per rf2-higwg: detect the host by writing-and-checking; if the
  write didn't take effect we're inside a real browser
  (`:browser-test` build under Playwright) and the mount tests'
  stub-driven contracts can't be exercised. The predicate gates
  `with-stub-document*` so the deftest bodies no-op on that host.
  The contracts are still proven on the node-test build where
  stubbing works."
  []
  (let [marker (js-obj "rf2-higwg-marker" true)
        prior  (when (exists? js/document) js/document)]
    (set! js/document marker)
    (let [installed? (identical? js/document marker)]
      (if prior
        (set! js/document prior)
        (when installed?
          (js-delete js/goog.global "document")))
      installed?)))

(defn- with-stub-document* [f]
  ;; Per rf2-higwg: in `:browser-test` the host's `window.document` is
  ;; non-configurable and `set!` silently no-ops — the stub never
  ;; takes effect and `mount/open!`'s `(.appendChild (.-body
  ;; js/document) node)` lands on the real document. Skip the body
  ;; cleanly in that host; the contracts run on node-test where the
  ;; stub does install.
  (when (can-stub-js-document?)
    (let [doc       (mk-stub-document)
          had-doc?  (exists? js/document)
          prior     (when had-doc? js/document)]
      (set! js/document doc)
      (try
        (f doc)
        (finally
          (if had-doc?
            (set! js/document prior)
            (js-delete js/goog.global "document")))))))

(defn- with-stub-document [f]
  (with-stub-document* f))

;; ---- substrate-render stub ----------------------------------------------
;;
;; The fixture installs `plain-atom/adapter` — its `:render` slot
;; throws (per substrate/plain_atom.cljc line 39: render is not
;; supported on the JVM/headless adapter). Real `open!` calls
;; `(substrate-adapter/render [shell/shell-view] node nil)`; we
;; intercept that delegation with `with-redefs` so the test never
;; spins a React tree. The stub records its arguments + returns a
;; sentinel unmount fn so `teardown!` has something to invoke and
;; the assertion can verify it was called exactly once.

(defn- mk-render-stub
  "Build a stub for `substrate-adapter/render`. Returns
  `{:render-fn ..., :calls (atom []), :unmount-calls (atom 0)}` so
  tests can assert call counts. The render-fn signature matches the
  contract: 3 args, returns an unmount fn."
  []
  (let [calls         (atom [])
        unmount-calls (atom 0)
        unmount-fn    (fn unmount-stub []
                        (swap! unmount-calls inc)
                        nil)]
    {:render-fn     (fn render-stub [tree node opts]
                      (swap! calls conj {:tree tree :node node :opts opts})
                      unmount-fn)
     :calls         calls
     :unmount-calls unmount-calls
     :unmount-fn    unmount-fn}))

;; ---- fixtures -----------------------------------------------------------
;;
;; `reset-runtime-fixture` installs `plain-atom/adapter` and snapshots
;; the registrar — same pattern preload_cljs_test.cljs uses. The
;; per-test cleanup also resets the mount-state defonce so a failing
;; transition test doesn't poison neighbours via stale singleton state.

(defn- causa-init! []
  (causa-test-support/reset-all!)
  ;; Per rf2-in6l2 the registry installs the trace-buffer mirror
  ;; events (note-trace-event / clear-trace-buffer / sync-trace-buffer).
  ;; `ensure-causa-frame!` (called inside `open!`) dispatches
  ;; `:rf.causa/sync-trace-buffer` to seed the slot — that needs the
  ;; event handlers installed.
  (registry/register-causa-handlers!)
  (config/set-auto-open! true)
  (trace-bus/clear-buffer!)
  (reset-mount-state!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; -------------------------------------------------------------------------
;; (1) Predicates on a clean baseline
;; -------------------------------------------------------------------------

(deftest mounted?-and-visible?-are-false-on-clean-state
  (testing "before any open!/teardown! cycle both predicates report false
            — the fixture's reset-mount-state! puts the singleton back
            at nil"
    (is (false? (mount/mounted?))
        "mounted? false when @mount-state is nil")
    (is (false? (mount/visible?))
        "visible? false when @mount-state is nil")))

;; -------------------------------------------------------------------------
;; (2) Open — first call (mount + show)
;; -------------------------------------------------------------------------

(deftest first-open!-creates-dom-node-and-renders
  (testing "first open! creates a fresh <div id=\"rf-causa-root\"> under
            document.body, delegates to substrate-adapter/render with
            the shell-view tree and the new node, and marks visible"
    (with-stub-document
      (fn [doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (let [result (mount/open!)]
              (is (some? result) "open! returns the mount-state map")
              (is (map? result))
              (is (= 1 (count @calls))
                  "substrate-adapter/render invoked exactly once")
              (let [{:keys [tree node opts]} (first @calls)]
                (is (vector? tree) "render received a hiccup vector")
                (is (some? node) "render received the mount node")
                (is (nil? opts)
                    "render called with nil opts (mount.cljs passes nil)")
                (is (= "rf-causa-root" (.-id node))
                    "the created div has id rf-causa-root")
                (is (= 1 (.-length (.-children (.-body doc))))
                    "the div was appended to document.body")
                (is (identical? node (aget (.-children (.-body doc)) 0))
                    "the appended child is the created node"))
              (is (true? (mount/mounted?))
                  "mounted? flips true after first open!")
              (is (true? (mount/visible?))
                  "visible? flips true after first open!"))))))))

(deftest first-open!-marks-inline-root-visible
  (testing "first open! creates the inline container under the
            app-provided Causa host and explicitly writes
            style.display=block so close/open remains a CSS-only
            visibility transition"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (let [node (:node @@#'mount/mount-state)]
              (is (= "block" (.-display (.-style node)))
                  "inline root is explicitly visible on first mount")
              (is (= "inline" (.getAttribute node "data-rf-causa-mode"))
                  "default open! uses true-inline mode"))))))))

(deftest open!-without-layout-host-reports-actionable-diagnostic
  (testing "with an adapter installed but no `[data-rf-causa-host]`,
            open! does not mount and reports an inspectable diagnostic"
    (with-stub-document
      (fn [doc]
        (set! (.-querySelector doc) (fn [_selector] nil))
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (let [prior-console (when (exists? js/console) js/console)]
              (set! js/console (js-obj "error" (fn [& _args] nil)))
              (try
                (let [result (mount/open!)
                      diagnostic (:diagnostic (mount/status))]
                  (is (= :missing-layout-host (:reason result)))
                  (is (= :missing-layout-host (:reason diagnostic)))
                  (is (= "[data-rf-causa-host]" (:selector diagnostic)))
                  (is (re-find #"data-rf-causa-host" (:snippet diagnostic)))
                  (is (nil? @@#'mount/mount-state))
                  (is (zero? (count @calls))))
                (finally
                  (set! js/console prior-console))))))))))

;; -------------------------------------------------------------------------
;; (3) Open — second call (already mounted; no re-render)
;; -------------------------------------------------------------------------

(deftest second-open!-does-not-re-render
  (testing "calling open! when already mounted is a CSS-only show —
            substrate-adapter/render is NOT invoked again, the
            existing DOM node is reused, display flips back to block.
            This is the <80ms toggle target the spec calls out:
            re-rendering would discard internal shell state"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (let [first-state (mount/open!)
                  first-node  (:node first-state)]
              (is (= 1 (count @calls)) "first open! triggered one render")
              ;; Flip to invisible so we can confirm the second open!
              ;; flips it back to block.
              (mount/close!)
              (is (= "none" (.-display (.-style first-node))))
              (let [second-state (mount/open!)]
                (is (= 1 (count @calls))
                    "second open! did NOT trigger another render")
                (is (identical? first-node (:node second-state))
                    "second open! reuses the same DOM node")
                (is (= "block" (.-display (.-style first-node)))
                    "second open! flips display back to block")
                (is (true? (:visible? second-state))
                    "second open! marks visible? true again")))))))))

;; -------------------------------------------------------------------------
;; (4) Close — hide without unmounting
;; -------------------------------------------------------------------------

(deftest close!-hides-but-retains-mount-state
  (testing "close! flips display=none and visible?=false, but the
            singleton + DOM node + unmount fn stay in place so a
            subsequent open! is a CSS-only show"
    (with-stub-document
      (fn [doc]
        (let [{:keys [render-fn calls unmount-calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (let [pre-close @@#'mount/mount-state]
              (is (some? pre-close))
              (mount/close!)
              (let [post-close @@#'mount/mount-state]
                (is (some? post-close)
                    "mount-state retained (not nil'd) on close")
                (is (false? (:visible? post-close))
                    "visible? flipped to false in the singleton")
                (is (= "none" (.-display (.-style (:node post-close))))
                    "container.style.display = none")
                (is (identical? (:node pre-close) (:node post-close))
                    "same DOM node retained")
                (is (= 0 @unmount-calls)
                    "close! must NOT invoke the substrate unmount fn")
                (is (= 1 (count @calls))
                    "close! must NOT trigger a second render")
                (is (= 1 (.-length (.-children (.-body doc))))
                    "the node stays attached to document.body")))))))))

(deftest close!-on-clean-state-is-safe
  (testing "calling close! before any open! is a no-op — does not
            throw, does not allocate state, returns nil"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (is (nil? (mount/close!))
                "close! returns nil on clean state")
            (is (nil? @@#'mount/mount-state)
                "mount-state stays nil — close! did not allocate")
            (is (zero? (count @calls))
                "no render invocation triggered by close!")
            (is (false? (mount/mounted?)))
            (is (false? (mount/visible?)))))))))

(deftest close!-after-close!-is-idempotent
  (testing "close! when already hidden is a no-op — visible? stays
            false, the DOM node is not re-styled (already display=none),
            no second unmount fires"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn unmount-calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (mount/close!)
            (is (false? (mount/visible?)))
            (is (nil? (mount/close!))
                "second close! returns nil")
            (is (false? (mount/visible?))
                "visible? remains false after two closes")
            (is (= 0 @unmount-calls)
                "still no unmount fn invocation")))))))

;; -------------------------------------------------------------------------
;; (5) Toggle — open + close + open round-trip
;; -------------------------------------------------------------------------

(deftest toggle!-mounts-on-first-call
  (testing "toggle! on clean state behaves like open! — creates the
            DOM node, renders, marks visible"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/toggle!)
            (is (= 1 (count @calls))
                "first toggle! triggers a substrate render")
            (is (true? (mount/visible?)) "shell is visible after toggle!")
            (is (true? (mount/mounted?)) "shell is mounted after toggle!")))))))

(deftest toggle!-hides-when-currently-visible
  (testing "toggle! while visible is equivalent to close! — flips
            visible? false, does NOT unmount"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn unmount-calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (is (true? (mount/visible?)))
            (mount/toggle!)
            (is (false? (mount/visible?))
                "toggle! while visible flips to hidden")
            (is (true? (mount/mounted?))
                "mount-state retained")
            (is (= 0 @unmount-calls)
                "toggle!-as-close must not invoke unmount")))))))

(deftest toggle!-shows-when-currently-hidden
  (testing "toggle! while hidden is equivalent to a second open! —
            flips visible? true, reuses the existing DOM node (no
            re-render)"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (mount/close!)
            (is (false? (mount/visible?)))
            (mount/toggle!)
            (is (true? (mount/visible?))
                "toggle!-as-open flips visible? back true")
            (is (= 1 (count @calls))
                "the re-show did NOT trigger a second render")))))))

(deftest toggle!-round-trips-cleanly-three-times
  (testing "open → close → open → close → open via repeated toggle!
            calls — render fires exactly once (lazy-mount affordance);
            visible? toggles exactly with each call"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/toggle!)  ;; #1 open
            (is (true? (mount/visible?)))
            (mount/toggle!)  ;; #2 close
            (is (false? (mount/visible?)))
            (mount/toggle!)  ;; #3 open
            (is (true? (mount/visible?)))
            (mount/toggle!)  ;; #4 close
            (is (false? (mount/visible?)))
            (mount/toggle!)  ;; #5 open
            (is (true? (mount/visible?)))
            (is (= 1 (count @calls))
                "all five toggles share the single initial render —
                 the substrate render must not fire on the four
                 toggle-after-mount transitions")))))))

;; -------------------------------------------------------------------------
;; (6) Teardown — full destroy
;; -------------------------------------------------------------------------

(deftest teardown!-invokes-unmount-and-removes-node
  (testing "teardown! invokes the substrate unmount fn returned at
            render time, removes the DOM node from document.body, and
            clears the singleton so a subsequent open! is back to
            first-mount semantics"
    (with-stub-document
      (fn [doc]
        (let [{:keys [render-fn unmount-calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (is (= 1 (.-length (.-children (.-body doc)))))
            (let [pre-node (:node @@#'mount/mount-state)]
              (mount/teardown!)
              (is (= 1 @unmount-calls)
                  "unmount fn invoked exactly once")
              (is (nil? (.-parentNode pre-node))
                  "the DOM node is detached from its parent")
              (is (= 0 (.-length (.-children (.-body doc))))
                  "document.body no longer carries the node")
              (is (nil? @@#'mount/mount-state)
                  "mount-state cleared back to nil")
              (is (false? (mount/mounted?)))
              (is (false? (mount/visible?))))))))))

(deftest teardown!-after-open!-then-mount!-cycle
  (testing "open → teardown → open: the second open! is a fresh
            first-mount (new DOM node, fresh render call) — not a
            CSS-only show. This is the distinction between close!
            (which retains state) and teardown! (which destroys it)"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (let [first-node (:node @@#'mount/mount-state)]
              (mount/teardown!)
              (is (nil? @@#'mount/mount-state))
              (mount/open!)
              (let [second-node (:node @@#'mount/mount-state)]
                (is (= 2 (count @calls))
                    "teardown then open triggers a SECOND render —
                     teardown destroys state so the next open is a
                     fresh first-mount")
                (is (not (identical? first-node second-node))
                    "the second open allocates a new DOM node")))))))))

(deftest teardown!-on-clean-state-is-safe
  (testing "teardown! before any open! is a no-op — does not throw,
            does not allocate, returns nil. Symmetric with close!'s
            clean-state behaviour"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn unmount-calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (is (nil? (mount/teardown!)))
            (is (nil? @@#'mount/mount-state))
            (is (= 0 @unmount-calls)
                "no unmount fn to invoke — the no-op posture must not
                 invent calls")))))))

(deftest teardown!-swallows-unmount-errors
  (testing "if the substrate's unmount fn throws (mid-dispose race,
            unmounted-already React error) teardown! still removes
            the DOM node and clears the singleton. The source uses
            (try (unmount) (catch :default _ nil)) for exactly this"
    (with-stub-document
      (fn [doc]
        ;; Build a stub render that returns a throwing unmount.
        (let [calls (atom [])]
          (with-redefs [substrate-adapter/render
                        (fn [tree node opts]
                          (swap! calls conj [tree node opts])
                          (fn throwing-unmount []
                            (throw (ex-info "unmount blew up"
                                            {:reason :test}))))]
            (mount/open!)
            (is (= 1 (count @calls)))
            ;; teardown! must not propagate the unmount exception.
            (is (nil? (mount/teardown!))
                "teardown returns nil even when unmount throws")
            (is (= 0 (.-length (.-children (.-body doc))))
                "DOM node still removed despite the unmount throw")
            (is (nil? @@#'mount/mount-state)
                "singleton still cleared despite the unmount throw")))))))

;; -------------------------------------------------------------------------
;; (7) Missing-adapter gate — graceful no-op when no substrate installed
;; -------------------------------------------------------------------------

(deftest open!-without-adapter-is-silent-no-op
  (testing "when no substrate adapter is installed (e.g. preload loaded
            into a production bundle, or a host that never called
            rf/init!), open! returns nil, does NOT create a DOM node,
            does NOT mutate mount-state, does NOT call substrate
            render. This is the silent-failure mode the source docs
            describe — the user's Ctrl+Shift+C is a no-op until an
            adapter installs"
    (with-stub-document
      (fn [doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            ;; Tear down the fixture's plain-atom install so
            ;; current-adapter returns nil.
            (substrate-adapter/dispose-adapter!)
            (is (nil? (substrate-adapter/current-adapter))
                "sanity — no adapter is currently installed")
            (is (nil? (mount/open!))
                "open! returns nil when no adapter is installed")
            (is (nil? @@#'mount/mount-state)
                "mount-state untouched — no singleton allocated")
            (is (zero? (count @calls))
                "substrate render was NOT called")
            (is (zero? (.-length (.-children (.-body doc))))
                "no <div> was appended to document.body")
            (is (false? (mount/mounted?)))
            (is (false? (mount/visible?)))))))))

(deftest auto-open-inline!-honours-disabled-launch-config
  (testing "Story/tool pages can suppress only the preload's default
            auto-open before adapter readiness; explicit open! keeps
            the normal missing-host diagnostic path"
    (with-stub-document
      (fn [doc]
        (set! (.-querySelector doc) (fn [_selector] nil))
        (config/set-auto-open! false)
        (let [{:keys [render-fn calls]} (mk-render-stub)
              console-calls (atom [])]
          (with-redefs [substrate-adapter/render render-fn]
            (let [prior-console (when (exists? js/console) js/console)]
              (set! js/console (js-obj "error" (fn [& args]
                                                  (swap! console-calls conj args)
                                                  nil)))
              (try
                (mount/auto-open-inline!)
                (is (nil? @@#'mount/mount-state)
                    "auto-open disabled does not mount")
                (is (zero? (count @calls))
                    "auto-open disabled does not render")
                (is (zero? (count @console-calls))
                    "auto-open disabled is not a console error")
                (is (= :auto-open-disabled
                       (get-in (mount/status) [:diagnostic :reason])))
                (mount/open!)
                (is (= :missing-layout-host
                       (get-in (mount/status) [:diagnostic :reason]))
                    "explicit open still diagnoses a missing host")
                (is (= 1 (count @console-calls))
                    "explicit open emits the actionable diagnostic")
                (finally
                  (set! js/console prior-console)
                  (config/set-auto-open! true))))))))))

(deftest toggle!-without-adapter-is-silent-no-op
  (testing "toggle! routes through open! when nothing is mounted; the
            missing-adapter gate inside open! short-circuits the
            allocation. The user pressing Ctrl+Shift+C with no adapter
            installed must not throw"
    (with-stub-document
      (fn [doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (substrate-adapter/dispose-adapter!)
            (is (nil? (mount/toggle!))
                "toggle! returns nil rather than throwing")
            (is (nil? @@#'mount/mount-state))
            (is (zero? (count @calls)))
            (is (zero? (.-length (.-children (.-body doc)))))))))))

(deftest open!-recovers-after-adapter-installs-late
  (testing "the missing-adapter posture is transient: once an adapter
            is installed, the next open! proceeds normally. This
            matters because the preload loads before the host's
            rf/init! call in some hot-reload orderings — the listener
            attaches, the first Ctrl+Shift+C is a no-op, but the
            second (after init! runs) succeeds"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn calls]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (substrate-adapter/dispose-adapter!)
            (is (nil? (mount/open!)) "first open! is a no-op")
            ;; Re-install — same plain-atom adapter the fixture had.
            (substrate-adapter/install-adapter! plain-atom/adapter)
            (let [result (mount/open!)]
              (is (some? result) "second open! succeeds after install")
              (is (= 1 (count @calls))
                  "exactly one render — first open! was a true no-op")
              (is (true? (mount/visible?))))))))))

;; -------------------------------------------------------------------------
;; (8) State-machine cross-checks
;; -------------------------------------------------------------------------

(deftest visible?-tracks-the-singleton-not-the-style
  (testing "visible? reads the :visible? slot of the singleton, not
            the DOM node's style.display. This matters because tests
            (and production diagnostics) can rely on visible? without
            paying the .-style lookup cost"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (is (true? (mount/visible?)))
            ;; Forcibly mutate the singleton's :visible? slot — visible?
            ;; must reflect the change immediately.
            (swap! @#'mount/mount-state assoc :visible? false)
            (is (false? (mount/visible?))
                "visible? returns the singleton's :visible? slot")))))))

(deftest mounted?-tracks-the-singleton-presence
  (testing "mounted? is (some? @mount-state) — the predicate is true
            whether the shell is currently visible or hidden, and
            flips back to false only after teardown!"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (is (false? (mount/mounted?)) "clean baseline")
            (mount/open!)
            (is (true? (mount/mounted?)) "mounted? true after open!")
            (mount/close!)
            (is (true? (mount/mounted?))
                "mounted? STAYS true after close! — only teardown! clears it")
            (mount/open!)
            (is (true? (mount/mounted?)))
            (mount/teardown!)
            (is (false? (mount/mounted?))
                "mounted? flips back to false after teardown!")))))))

;; -------------------------------------------------------------------------
;; (8) Lazy `:rf/causa` frame registration (rf2-in6l2)
;; -------------------------------------------------------------------------
;;
;; Per rf2-in6l2 the `:rf/causa` frame is lazy-registered by `open!` —
;; the preload runs before `rf/init!` has installed a substrate adapter
;; so `reg-frame` cannot run there (per rf2-e9s81). The first Ctrl+
;; Shift+C keypress fires `open!` AFTER `rf/init!`, so that's the
;; canonical registration point. Subsequent toggles surgical-update
;; (reg-frame's re-register semantics) — the frame's app-db and sub-
;; cache are preserved across keypresses.

(deftest first-open!-registers-causa-frame
  (testing "first open! registers the :rf/causa frame so reg-view-wrapped
            panels' React-context resolution lands in a real frame
            (not chain-resolves to :rf/default)"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (is (nil? (frame/frame :rf/causa))
                ":rf/causa is absent on the clean baseline")
            (mount/open!)
            (is (some? (frame/frame :rf/causa))
                ":rf/causa registered after open!")))))))

(deftest first-open!-seeds-trace-buffer-mirror
  (testing "first open! seeds Causa's app-db `:trace-buffer` slot with
            the trace-bus atom's current contents — closes the pre-
            mount-trace-events window: events that arrived before the
            user opened Causa (atom accumulated, no frame to dispatch
            into yet) lift into the reactive slot on first mount"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            ;; Push two events into the trace-bus atom BEFORE the
            ;; first open!. The frame doesn't exist yet, so the
            ;; `mirror-into-causa!` guard skips the dispatch — atom
            ;; still accumulates.
            (trace-bus/collect-trace!
              {:id 1 :op-type :event :operation :rf.test/pre-mount :tags {}})
            (trace-bus/collect-trace!
              {:id 2 :op-type :event :operation :rf.test/pre-mount :tags {}})
            (mount/open!)
            ;; After open! the slot reflects the pre-mount atom contents.
            (rf/with-frame :rf/causa
              (let [buf @(rf/subscribe [:rf.causa/trace-buffer])]
                (is (= 2 (count buf))
                    "pre-mount atom contents seeded into the reactive slot")
                (is (= [1 2] (mapv :id buf))
                    "seed preserves oldest-first ordering")))))))))

(deftest open!-is-idempotent-on-causa-frame-registration
  (testing "subsequent open!s after the frame is registered surgical-
            update the frame's config (reg-frame contract per Spec 002
            §Re-registration) without re-allocating the app-db.
            Production toggle path: every Ctrl+Shift+C calls open!;
            the close path doesn't unmount, so re-registration is
            the on-show no-op the bead's design relies on"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (let [first-frame (frame/frame :rf/causa)
                  first-db    (frame/get-frame-db :rf/causa)]
              (mount/close!)
              (mount/open!)
              (let [second-frame (frame/frame :rf/causa)
                    second-db   (frame/get-frame-db :rf/causa)]
                (is (some? first-frame))
                (is (some? second-frame))
                (is (identical? first-db second-db)
                    "app-db container preserved across re-register")))))))))

;; -------------------------------------------------------------------------
;; (9) Teardown covers both mount singletons (rf2-yudol, rf2-sbfb7)
;; -------------------------------------------------------------------------
;;
;; Per the rf2-yudol fix (sourced from audit rf2-a6tvr Q1-1+Q1-2),
;; `teardown!` must clear both mount singletons — `mount-state` and
;; `popout-state` — not just the in-app shell. Before the fix
;; `teardown!` touched only the first; popout leaked across test runs
;; and caused subsequent `(popout!)` calls to short-circuit on stale
;; state. (The third pre-existing singleton — `inline-mounts` — went
;; away under rf2-sbfb7 with the `mount-inline-panel!` debug API.)
;; These tests pin the broadened contract documented in
;; tools/causa/spec/011-Launch-Modes.md §Mount lifecycle.

(defn- mk-stub-popout-window
  "Build a fake popout window with enough surface for the popout!
  code path: `addEventListener`, `close`, `closed`, plus a `document`
  that supports `createElement` + a `body` whose `appendChild` keeps
  a reference. Listener registrations are recorded so the test can
  fire the unload handler directly."
  []
  (let [listeners (atom {})
        closed?   (atom false)
        body      (mk-stub-node)
        doc       (js-obj "body"          body
                          "title"         ""
                          "createElement" (fn [_tag] (mk-stub-node)))
        win       (js-obj "document" doc)]
    (set! (.-addEventListener win)
          (fn [event-name handler]
            (swap! listeners update event-name (fnil conj []) handler)
            nil))
    (set! (.-close win)
          (fn []
            (reset! closed? true)
            nil))
    ;; `closed` is a read-only property in real browsers; on a stub we
    ;; expose it via a JS getter so `(.-closed win)` reflects the atom.
    (js/Object.defineProperty
      win "closed"
      (js-obj "get" (fn [] @closed?)
              "configurable" true))
    {:window    win
     :listeners listeners
     :closed?   closed?}))

(defn- seed-popout-state!
  "Install a synthetic popout-state into the singleton — the equivalent
  of what `popout!` would install if it had a real browser window
  available. Returns the seeded state map so the test can assert
  against its slots."
  [{:keys [window unmount-fn]}]
  (let [node    (mk-stub-node)
        unmount (or unmount-fn (fn [] nil))
        state   {:ok? true
                 :window  window
                 :node    node
                 :unmount unmount
                 :mode    :popout}]
    (reset! @#'mount/popout-state state)
    state))

(deftest teardown!-clears-popout-state-and-closes-window
  (testing "rf2-yudol Q1-1: teardown! must invoke the popout's substrate
            unmount, close the popout window, and reset popout-state to
            nil. Before the fix popout-state leaked — a subsequent
            (popout!) returned the stale state whose :window was orphaned."
    (with-stub-document
      (fn [_doc]
        (let [{:keys [window closed?]} (mk-stub-popout-window)
              unmount-calls (atom 0)]
          (seed-popout-state! {:window     window
                               :unmount-fn (fn []
                                             (swap! unmount-calls inc)
                                             nil)})
          (is (some? @@#'mount/popout-state)
              "sanity — popout-state populated")
          (mount/teardown!)
          (is (nil? @@#'mount/popout-state)
              "popout-state cleared by teardown!")
          (is (= 1 @unmount-calls)
              "popout's substrate unmount fn invoked exactly once")
          (is (true? @closed?)
              "popout window's .close() invoked"))))))

(deftest teardown!-tolerates-already-closed-popout-window
  (testing "rf2-yudol: if the popout window is already closed (user
            closed it before teardown! runs), teardown! must not throw
            and must still clear the singleton and invoke the substrate
            unmount."
    (with-stub-document
      (fn [_doc]
        (let [{:keys [window closed?]} (mk-stub-popout-window)
              unmount-calls (atom 0)]
          (reset! closed? true)       ; pretend the user already closed it
          (seed-popout-state! {:window     window
                               :unmount-fn (fn []
                                             (swap! unmount-calls inc)
                                             nil)})
          (is (nil? (mount/teardown!))
              "teardown! returns nil even when the window is already closed")
          (is (nil? @@#'mount/popout-state))
          (is (= 1 @unmount-calls)
              "substrate unmount still invoked"))))))

(deftest teardown!-swallows-popout-unmount-errors
  (testing "rf2-yudol: a throwing popout unmount must not strand the
            singleton or the window-close attempt"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [window closed?]} (mk-stub-popout-window)]
          (seed-popout-state!
            {:window     window
             :unmount-fn (fn [] (throw (ex-info "popout unmount blew up"
                                                {:reason :test})))})
          (is (nil? (mount/teardown!))
              "teardown returns nil even when popout unmount throws")
          (is (nil? @@#'mount/popout-state)
              "popout-state cleared despite the throw")
          (is (true? @closed?)
              "popout window still closed despite the throw"))))))

(deftest teardown!-clears-both-singletons-in-one-call
  (testing "rf2-yudol + rf2-sbfb7: a single teardown! call clears
            mount-state + popout-state together. The fixture between
            tests pokes these atoms back to baseline; teardown! itself
            must achieve the same baseline so a test that omits the
            reset (or a tear-down + re-open sequence inside a single
            test) starts from a clean slate."
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn unmount-calls]} (mk-render-stub)
              {:keys [window closed?]}          (mk-stub-popout-window)]
          (with-redefs [substrate-adapter/render render-fn]
            (mount/open!)
            (seed-popout-state! {:window     window
                                 :unmount-fn (fn []
                                               (swap! unmount-calls inc)
                                               nil)})
            (is (some? @@#'mount/mount-state))
            (is (some? @@#'mount/popout-state))
            (mount/teardown!)
            (is (nil? @@#'mount/mount-state)
                "mount-state cleared")
            (is (nil? @@#'mount/popout-state)
                "popout-state cleared")
            (is (= 2 @unmount-calls)
                "two unmount fns invoked (in-app shell + popout)")
            (is (true? @closed?)
                "popout window closed by teardown!")))))))

(deftest teardown!-isolation-across-multi-run
  (testing "rf2-yudol regression guard: two consecutive open!/seed-popout
            → teardown! cycles must not leak state. Before the fix the
            second cycle would observe stale popout-state left over from
            the first run."
    (with-stub-document
      (fn [_doc]
        (let [{:keys [render-fn]} (mk-render-stub)
              cycle! (fn []
                       (let [{:keys [window]} (mk-stub-popout-window)]
                         (with-redefs [substrate-adapter/render render-fn]
                           (mount/open!)
                           (seed-popout-state! {:window window})
                           (mount/teardown!))))]
            (cycle!)
            (is (nil? @@#'mount/mount-state))
            (is (nil? @@#'mount/popout-state))
            (cycle!)
            (is (nil? @@#'mount/mount-state)
                "second cycle's teardown still clears mount-state")
            (is (nil? @@#'mount/popout-state)
                "second cycle's teardown still clears popout-state"))))))

;; -------------------------------------------------------------------------
;; (10) Popout external-close → opener-side cleanup (rf2-yudol)
;; -------------------------------------------------------------------------
;;
;; When the user closes the popout window externally, the opener-side
;; popout-state singleton MUST be cleared via the pagehide/unload
;; listener `popout!` registered at mount time. Without this, a
;; subsequent (popout!) short-circuits on the stale singleton whose
;; :window has .closed = true.

(defn- register-popout-cleanup!
  "Test-side accessor for the private fn `register-popout-unload-cleanup!`
  that `popout!` calls. Per the rf2-yudol fix, this is the listener-
  registration step we want to exercise directly without standing up a
  real browser window."
  [win]
  ((deref #'mount/register-popout-unload-cleanup!) win))

(deftest popout-registers-unload-listeners-on-popout-window
  (testing "rf2-yudol: register-popout-unload-cleanup! must register
            pagehide + unload listeners on the popout window so the
            opener-side singleton clears when the popout closes
            externally"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [window listeners]} (mk-stub-popout-window)]
          (seed-popout-state! {:window window})
          (register-popout-cleanup! window)
          (is (= 1 (count (get @listeners "pagehide")))
              "pagehide listener registered on the popout window")
          (is (= 1 (count (get @listeners "unload")))
              "unload listener registered on the popout window"))))))

(deftest popout-pagehide-clears-popout-state-and-invokes-unmount
  (testing "rf2-yudol: firing the popout window's pagehide event must
            clear popout-state and invoke the substrate unmount fn.
            Simulates the user closing the popout via the browser's
            window-close affordance"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [window listeners]} (mk-stub-popout-window)
              unmount-calls (atom 0)]
          (seed-popout-state! {:window     window
                               :unmount-fn (fn []
                                             (swap! unmount-calls inc)
                                             nil)})
          (register-popout-cleanup! window)
          (is (some? @@#'mount/popout-state)
              "sanity — popout-state populated")
          ;; Fire the pagehide handler — simulating the user closing
          ;; the popout via the browser window-close affordance.
          (let [pagehide-handler (first (get @listeners "pagehide"))]
            (pagehide-handler (js-obj "type" "pagehide")))
          (is (nil? @@#'mount/popout-state)
              "popout-state cleared by the pagehide handler")
          (is (= 1 @unmount-calls)
              "substrate unmount fn invoked by the pagehide handler"))))))

(deftest popout-unload-handler-also-clears-popout-state
  (testing "rf2-yudol: the unload event is the older companion to
            pagehide — both must clear popout-state for cross-browser
            coverage"
    (with-stub-document
      (fn [_doc]
        (let [{:keys [window listeners]} (mk-stub-popout-window)
              unmount-calls (atom 0)]
          (seed-popout-state! {:window     window
                               :unmount-fn (fn []
                                             (swap! unmount-calls inc)
                                             nil)})
          (register-popout-cleanup! window)
          (let [unload-handler (first (get @listeners "unload"))]
            (unload-handler (js-obj "type" "unload")))
          (is (nil? @@#'mount/popout-state)
              "popout-state cleared by the unload handler")
          (is (= 1 @unmount-calls)
              "substrate unmount fn invoked by the unload handler"))))))

(deftest popout-stale-unload-handler-does-not-nuke-fresh-state
  (testing "rf2-yudol: a stale unload handler that fires AFTER a fresh
            popout has replaced the singleton must not nuke the new
            state. The handler identifies its window via identical?
            on the :window slot and ignores events that don't match."
    (with-stub-document
      (fn [_doc]
        (let [{window-a :window listeners-a :listeners} (mk-stub-popout-window)
              {window-b :window}                        (mk-stub-popout-window)]
          ;; First popout — window-a. Register the cleanup handler.
          (seed-popout-state! {:window window-a})
          (register-popout-cleanup! window-a)
          (is (identical? window-a (:window @@#'mount/popout-state)))
          ;; Manually clear and seed a fresh popout for window-b. The
          ;; stale window-a unload handler is still registered against
          ;; window-a.
          (reset! @#'mount/popout-state nil)
          (seed-popout-state! {:window window-b})
          (is (identical? window-b (:window @@#'mount/popout-state))
              "fresh popout-state references window-b")
          ;; Fire the stale window-a unload handler. It must NOT
          ;; nuke window-b's state.
          (let [stale-handler (first (get @listeners-a "unload"))]
            (stale-handler (js-obj "type" "unload")))
          (is (identical? window-b (:window @@#'mount/popout-state))
              "stale handler did not nuke the fresh popout state"))))))
