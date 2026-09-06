(ns re-frame.hicasso.presence-ssr-seam-dom-cljs-test
  "THE PRESENCE/ROOT ROW OF THE SSR MATRIX, DRIVEN THROUGH THE REAL
  SERVER RENDERER — and the seam that measurement finds open.

  ## What this file is, said plainly

  It is a **RECORD OF A MISSING REFUSAL, not a proof of parity.**
  Presence has a server/client seam, and §1–§4 measure it on the path
  where it is open: a server render with no adoption window scoped over
  it. Every one of those rows drives `react-dom/server` for real and
  hydrates those actual bytes, and what they establish is the divergence,
  its cause, and the exact shape of the repair that closes it.

  **The repair has since landed as a product door**, and §5 is the
  measurement of it: `re-frame.hicasso.server/render` opens a window per
  request, and through it the tray's bytes say `present`. So
  the verdict this file returns is now a SPLIT one — closed through the
  package's server entry, open through a hand-rolled `renderToString` —
  and neither half is claimed for the other. Nothing here claims presence
  round-trips on a path §5 did not drive.

  ## The surface is dispositioned, and it does not do what its
  disposition says

  `docs/design/hicasso/product/dispositions.md` HS-33 — *optional motion
  and presence module* — carries the operative disposition
  **Client-only**, and the canonical two-policy matrix
  (`lanes/react-compatibility-notes.md`) states what bare Client-only
  owes: the surface is *\"absent from the server bytes, with nothing there
  to hydrate, mounting after adoption\"*, and the table's own third note
  adds that *\"a Client-only row still owes a witness … an unproved
  refusal is not a disposition.\"*

  Measured, the tray does neither. It is not absent from the server bytes
  — §1 finds its children right there in them, wearing the `:mounting`
  enter phase — and it is not Render either, because §3 finds those bytes
  fail to hydrate and the adoption is discarded. It occupies the third
  state the two-policy matrix exists to forbid: **server bytes that no
  client can adopt.** There is no refusal at source, no deterministic
  fallback, and no parity.

  That is the finding, and it is why this file records rather than
  upgrades. Which of the two policies presence should take is a product
  decision above this dispatch's fence; both are reachable from here and
  §1's control shows exactly what the Render arm costs.

  ## The census that says so

  `re-frame.hicasso.impl.roots/with-adoption` is the ONLY door onto the
  adoption window's React context. It is reached from exactly one place —
  `impl.mount/tree`, and only for a handle carrying an `:adoption` window
  — so the census is really a census of that window's MINTERS.

  **THERE ARE TWO, AND THE SECOND IS A SERVER DOOR.**
  `re-frame.hicasso.server/render` opens a
  window PER REQUEST, hands `impl.mount/tree` a
  `{:frame … :adoption <window>}` handle — the hydrating shape, so the
  same fork on both sides of the wire — and closes it in a `finally`.
  §5 drives that door and measures what it does to this seam, which is
  the honest answer to the paragraph below: the seam is CLOSED on the
  product path and stays open on the bare one.

  So on a server render **that no window is scoped over**
  `roots/adopting-here?` reads the context default `nil`,
  `roots/adopting?` calls that closed, and
  `impl.presence-react/presence-body` takes its ordinary `:mounting`
  branch. The client's hydrating root reads a real open window one line
  later and takes `:present`. Two different phases for the same tree,
  decided on opposite sides of a wire.

  That windowless render is still a path a consumer can spell — a
  hand-rolled `renderToString`, which is exactly what [[server-bytes!]]
  performs — so no row below is wrong and none is deleted here. What was
  stale was the RECORD: this page read as though the seam had no product
  answer, and one had landed.

  §1 measures that as a property rather than restating it, and its
  control shows the divergence is caused by the missing provider and by
  nothing else.

  ## Two source comments this file is the counterpart to

  `impl/roots.cljs` and `impl/presence_react.cljs` each carry a note
  about what a server render sees, and each names a measurement rather
  than a mechanism. A window IS opened around a request, so §1 and §3 are
  the counter-measurement for the path those notes name — the windowless
  render — and §5 is the measurement for the path that has one.

  ## Why the existing presence witness did not catch this

  `roots-frames-hydration-dom-cljs-test`'s H5 row obtains its \"server
  bytes\" from `roots-frames-support/settled-server-html!`, which mounts
  an ORDINARY CLIENT ROOT, waits for that root's own `:mounting →
  :present` enter flip to land, and copies `innerHTML`. Those bytes are
  therefore settled *by the client tier playing the transition* — the one
  thing a server cannot do and the one thing a hydrated root must not do.
  They agree with the hydrating client's first pass for a reason that has
  nothing to do with server rendering, so the row passes whether or not
  any server path works at all.

  §2 is that stated as a measurement: the same tree, through the two
  producers, gives two different strings. It is the row that would have
  gone red on the day the fake stood in for the real one, and it stays
  red-able for as long as they differ.

  **The harness helper is not at fault and is not being replaced.** Its
  own docstring already refuses to open a window by hand, on the grounds
  that \"giving the harness a private one would be inventing product API
  for a test\" — which is the correct call and is why the seam is a
  finding rather than a test bug. What was wrong was reading its output
  as evidence about a server.

  ## What closes this file

  Either arm of the two-policy matrix, taken deliberately.

  **RENDER.** A product server-render door that mints one window per
  request and scopes it over that request's tree — the fifth item of the
  adopted per-root repair shape. §1's control is that door in one line.

  **IT EXISTS** — `re-frame.hicasso.server/render`, driven by §5. What that
  does NOT do is close this file, and the distinction is the whole of the
  triage here: the rows below are about a render with no window, which remains
  spellable by hand, so they keep measuring what they always measured. What
  closing the file needs is a decision that the windowless spelling is out of
  scope — and HS-33's disposition is
  `docs/design/hicasso/product/dispositions.md`'s, not a witness's. The
  transitions below stay written down for whoever takes it:

    §1  the `\"mounting\"` expectation becomes `\"present\"`, and the
        hand-installed control is deleted because the product does it.
    §2  becomes an EQUALITY — the two producers agree — or is deleted
        along with `settled-server-html!` if the real path replaces it.
    §3  inverts wholesale: zero React complaints, zero
        `:rf.ssr/hydration-mismatch`, the server's own nodes kept, and
        the client's first phase `:present` against server bytes that
        already said `present`. It becomes the parity row hic-046 asks
        for, on the same construction.
    §4  is unchanged. It is the control, and a repair must not move it.

  **CLIENT-ONLY**, which is what HS-33 says today. The tray refuses at
  source on a server render and emits nothing (or its declared fallback),
  so §1 asserts the ABSENCE the bare arm requires and the refusal's own
  firing, §2 and §3 are deleted — there are no tray bytes left to
  disagree about or to hydrate — and a new row proves the tray mounts
  after adoption with its enter transition intact. §4 again unchanged.

  Do not re-pin any of the four by loosening an assertion. Each names its
  replacement above; a repair rewrites them.

  ## The Render arm was RUN, and it works — measured, not argued

  ONE sabotage covers all four rows, because all four read the same
  producer: give `server-bytes!` a window (the body of
  [[server-bytes-under-a-hand-held-window!]]) and the Render repair is
  simulated end to end. Run by hand under `:browser-test` for **PR #7872**,
  landed on main as commit `b5e03b138f` — named rather than left as \"the
  PR body\", so the record can be reached from the tree. §1, §2
  and §3 all red — nine assertions
  across the three — and **§4 stays green**, which is the control doing
  its job.

  What §3 reported under that sabotage is the part worth carrying
  forward, because it is evidence about the repair rather than about the
  test:

      expected: (pos? (count complaints))
        actual: (not (pos? 0))            ; React complained ZERO times
      expected: (false? (sup/server-node? (.querySelector ca \".probe\")))
        actual: (not (false? true))       ; the server's own node was KEPT

  So with a window scoped over the server render, the real
  `react-dom/server` bytes hydrate **byte-compatibly and silently, with
  the server's own DOM adopted**. The Render arm of HS-33 is reachable,
  it costs one provider per request, and nothing else in the tray needs
  to change. That is what §1's control is for, and it is now a
  measurement.

  The same sabotage also explains the merged witness in one line: once
  the bytes say `present`, §2's two producers agree and §3 goes quiet —
  which is exactly the state `settled-server-html!` was already
  delivering, without a server anywhere in the picture.

  ## Lane

  `-dom-cljs-test`, so `:browser-test` runs the whole file against a real
  React DOM. §1 is a `renderToString` row and needs no DOM, so it also
  runs under `:node-test`; §2–§4 hydrate and state their skip there
  rather than reporting a false green."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.impl.mount :as rf.hicasso.impl.mount]
            [re-frame.hicasso.impl.roots :as rf.hicasso.impl.roots]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.hicasso.motion :as rf.hicasso.motion]
            [re-frame.hicasso.roots-frames-support :as rf.hicasso.roots-frames-support]
            [re-frame.hicasso.server :as rf.hicasso.server]
            [re-frame.test-support :as rf.test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::seam)

(def ^:private label-q [::label])

;; Registered ABOVE `use-fixtures` — the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is evaluated, so a
;; registration written below it is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The app
;; ---------------------------------------------------------------------------

(defonce ^:private !phases
  ;; `tag -> [phase …]`, every phase the probe was rendered with, in
  ;; order. Reset at the top of each row that reads it.
  ;;
  ;; A sequence rather than a last-value, and for the reason the sibling
  ;; suite gives: a phase is gone from the DOM a macrotask after it is
  ;; applied, so a reading taken after a commit cannot tell a child that
  ;; was BORN present from one that entered and settled. The FIRST phase
  ;; is the entire question on both sides of this seam.
  (atom {}))

(rf.hicasso/defview phase-probe
  "A presence child that is a BOUNDARY, so the machine merges the phase's
  override map into its PROPS (`impl.presence/with-phase`, HD-030) rather
  than into a node's attributes. The tray below declares `{:phase …}`
  under each override key, so this body reads the phase back as an
  ordinary prop and defaults to `:present`, the phase with no override.

  It renders the phase as TEXT, which is what makes the seam visible in
  the server's bytes at all — an attribute-only divergence is outside
  React's hydration-mismatch contract by React's own rules
  (`impl.mount`'s reporter header, Spec 011 §Hydration-mismatch
  detection), so a native child wearing `::motion/mounting` attributes would
  diverge here in silence."
  [{:keys [tag phase] :or {phase :present}}]
  (swap! !phases update tag (fnil conj []) phase)
  [:span.probe (name phase)])

(rf.hicasso/defview tray-screen
  "A screen with a presence tray in it — the surface this row is about."
  [{:keys [tag]}]
  [:div.screen
   [:p.value (rf.hicasso/sub label-q)]
   [rf.hicasso.motion/presence {:timeout-ms 50}
    [phase-probe {:key                 "one"
                  :tag                 tag
                  ::rf.hicasso.motion/mounting    {:phase :mounting}
                  ::rf.hicasso.motion/unmounting  {:phase :unmounting}}]]])

(rf.hicasso/defview plain-screen
  "The same screen with the tray removed — §4's control. Identical in
  every other respect: same frame, same subscription read, same server
  door, same hydration."
  [_]
  [:div.screen
   [:p.value (rf.hicasso/sub label-q)]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh! []
  (rf.hicasso.roots-frames-support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed "alpha"]))
  (rf.hicasso.impl.collector/reset-runtime!)
  (rf.hicasso.test.runtime/reset-body-runs!)
  nil)

(defn- app-element
  "The app subtree EXACTLY as `impl.mount/tree` builds it for an ordinary
  root — `provider` over `root-element`, and nothing else.

  Written out rather than reached for through a mount door because that
  identity is the whole point of the comparison: the client's hydrating
  tree is this element with `adoption-window-closer` and
  `roots/with-adoption` wrapped around it, so the adoption window is the
  ONLY difference between what the server renders and what the client
  hydrates. Any divergence the rows below find is therefore attributable
  to the window and to nothing else in the tree."
  [hiccup]
  (rf.hicasso.impl.mount/provider frame-id (rf.hicasso.impl.codec/root-element frame-id hiccup)))

(defn- server-bytes!
  "The bytes React's ACTUAL server renderer produces for `hiccup` — the
  same runtime the sibling Node entry drives, reduced to
  one call, and the same door `host-ssr-dom-cljs-test`,
  `public-door-macros-cljs-test` and `revision-dom-cljs-test` all use.

  This is what `roots-frames-support/server-html!` is NOT: that helper
  renders on a client root and says so. Both are honest; only one of them
  is a server."
  [hiccup]
  (react-dom-server/renderToString (app-element hiccup)))

(defn- server-bytes-under-a-hand-held-window!
  "§1's CONTROL, and the executable shape of the repair.

  The same server render with an adoption window scoped over it BY HAND.
  It is not a product path and must never be mistaken for one — no server
  door mints a window (see this namespace's census) — but it is what such
  a door would do, and running it is the only way to show that §1's
  divergence is caused by the MISSING PROVIDER rather than by
  `renderToString`, by the tray, or by anything else in the tree.

  When the product grows that door this fn is deleted, not promoted."
  [hiccup]
  (let [window (rf.hicasso.impl.roots/open-adoption-window!)]
    (try
      (react-dom-server/renderToString
        (rf.hicasso.impl.roots/with-adoption window (app-element hiccup)))
      (finally (rf.hicasso.impl.roots/close-adoption-window! window)))))

(defn- probe-text
  "The phase the markup says, read out of a STRING rather than a DOM —
  §1 runs under `:node-test` too."
  [html]
  (second (re-find #"class=\"probe\"[^>]*>([a-z]+)<" html)))

(defn- text-in [container sel]
  (some-> (.querySelector container sel) .-textContent))

;; ---------------------------------------------------------------------------
;; Settlement — the ONE way out of an async row, taken on both outcomes
;; ---------------------------------------------------------------------------
;;
;; §3 and §4 each hydrate a real root and wait on `sup/adopted!`, and each
;; holds things the `:each` fixture will NOT take back: a MOUNTED React
;; root, with the container `sup/server-dom!` minted still in the document
;; — the fixture resets frames, disposes the adapter and empties the
;; runtime, and none of that unmounts a root — and `console.error`,
;; replaced by `sup/open-console-capture!`, together with the `window`
;; "error" listener it registered. §3 opens its capture with
;; `:swallow-uncaught? true`, so a listener that outlives its row goes on
;; calling `preventDefault` on every later row's uncaught errors, which is
;; precisely the fail-open the browser runner's pageerror rule exists to
;; prevent.
;;
;; The mismatch watcher's trace listener is the one thing the fixture does
;; sweep — `make-reset-runtime-fixture`'s `:before` calls
;; `trace-tooling/clear-listeners!` — and it is still stopped on both paths
;; here, because a row that leans on the fixture to stop its own watcher is
;; measuring the fixture.
;;
;; These rows used to end INSIDE the fulfilment handler:
;;
;;   (-> (sup/adopted! ha)
;;       (.then (fn [ok] (close!) (stop!)
;;                       (try …assertions…
;;                            (finally (mount/release! ha) (done))))))
;;
;; There was no rejection arm anywhere in this file, so on a rejection the
;; handler was skipped, the `try` was never entered and the `finally` never
;; fired. Nothing ran: no `close!`, no `stop!`, no `release!`, no `done`.
;; The row did not fail — it HUNG to `cljs.test`'s async timeout, reporting
;; the timeout rather than the rejection, and it handed the next row a live
;; root and a swallowing listener to take its census against.
;;
;; On THIS lane it is worse than a hang, which is worth knowing before
;; reading §6's sabotage as merely slow. An unsettled rejection is an
;; unhandled one, so it reaches the page as an uncaught error, and the
;; browser runner treats that as terminal (rf2-u0j8). Measured on the
;; sibling suite: the run stopped at that namespace with 85 announced, no
;; summary line at all, and every namespace scheduled after it silently
;; unrun — `shadow.test` runs the whole lane, and the closing summary,
;; inside one `cljs.test/run-block` with no try/catch. So the cost of a
;; rejection here was never one row.
;;
;; §2 is on it too, and it is NOT an adoption row: it waits on
;; `sup/settled-server-html!`, which can reject on its own account — its
;; `.then` reads `.-innerHTML` and calls `mount/release!`. It holds nothing
;; of its own, so it names no `:release!`.
;;
;; §1 and §5 are deliberately NOT on it: both are synchronous end to end —
;; no `async`, no promise — so there is no settlement question to answer.
;; The `finally` inside `server-bytes-under-a-hand-held-window!` is an
;; ordinary bracket for the same reason.
;;
;; `sup/settle-row!` is the one path all three async rows now end with, and §6
;; is what says it works — because its rejection arm is on no green path,
;; and a repair to a branch nothing takes is untested by construction.

;; ---------------------------------------------------------------------------
;; §1 — a server render with NO window scoped over it installs no adoption
;;      context, so presence enters
;; ---------------------------------------------------------------------------

;; The census, turned into a measurement. `renderToString` runs the tray's
;; body for real — hooks, context reads and all — so what it emits is what
;; `roots/adopting-here?` answered inside a genuine server render.
;;
;; SABOTAGE (run by hand; the namespace header records it, and names the PR
;; and the landed commit — PR #7872, commit `b5e03b138f`):
;; give `server-bytes!` a window — the body of
;; [[server-bytes-under-a-hand-held-window!]] — and this row reds three ways
;; at once: `"present"` in the bytes, `:present` off the machine, and the
;; control no longer differing from the measurement it controls.
(deftest a-windowless-server-render-installs-no-adoption-context-so-presence-enters
  (fresh!)
  (reset! !phases {})
  (let [real (server-bytes! [tray-screen {:tag :server}])]

    (testing "premise: the server render produced this tree's markup at all"
      (is (re-find #"class=\"screen\"" real) (str "got " real))
      (is (re-find #"alpha" real)
          (str "the frame's subscription resolved server-side — " real)))

    (testing "HS-33's Client-only refusal DOES NOT FIRE. The bare arm of
              that policy requires the surface to be absent from the server
              bytes with nothing there to hydrate; the tray's child is in
              them, so the disposition is unwitnessed and the runtime
              contradicts it. Nothing refuses at source and no fallback
              stands in its place — the tray simply renders"
      (is (some? (probe-text real))
          (str "the presence child reached the server bytes — " real))
      (is (re-find #"class=\"probe\"" real)))

    (testing "and the presence child is `:mounting` in the bytes a real
              `react-dom/server` render delivers. No product door installs
              the adoption context on this path, so `adopting-here?` reads
              the context default `nil`, `adopting?` calls that closed, and
              the tray emits its ENTER phase — the phase whose whole purpose
              is to be animated away, baked into the page as delivered"
      (is (= "mounting" (probe-text real))
          (str "the server's bytes; got " (pr-str (probe-text real))
               " from " real))
      (is (= :mounting (first (get @!phases :server)))
          (str "and the machine computed it that way on the server's first
                render; saw " (pr-str (get @!phases :server)))))

    (testing "CONTROL: the same server render, with a window scoped over it
              by hand, emits `present` instead. So the phase above is a
              property of the ABSENT PROVIDER and not of the server
              renderer, the tray or the tree — and this is the one-line
              shape of the door rf2-6tmu deferred here (its repair shape,
              item 5: give each render/request its own ref and provider)"
      (reset! !phases {})
      (let [repaired (server-bytes-under-a-hand-held-window! [tray-screen {:tag :repaired}])]
        (is (= "present" (probe-text repaired))
            (str "got " (pr-str (probe-text repaired)) " from " repaired))
        (is (= :present (first (get @!phases :repaired)))
            (str "saw " (pr-str (get @!phases :repaired))))
        (is (not= (probe-text real) (probe-text repaired))
            "the control must differ from the measurement, or it controls
             nothing")))))

;; ---------------------------------------------------------------------------
;; §2 — the harness's settled bytes are not the server's bytes
;; ---------------------------------------------------------------------------

;; THE DISCRIMINATION ROW. `settled-server-html!` and `renderToString` are
;; both called "the server's bytes" in prose, and for a tray they are
;; different strings. Everything the merged presence witness established
;; about server/client parity rests on that conflation, and this row is the
;; conflation made countable.
;;
;; It is deliberately a NOT-EQUAL. A row asserting the settled bytes hydrate
;; cleanly would pass forever without ever touching a server; a row asserting
;; the two agree is the row that must be written when the seam closes. Today
;; they disagree, and recording which way is what makes the disagreement
;; findable.
(deftest the-client-settled-bytes-and-the-real-server-bytes-differ
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM, and `settled-server-html!` mounts a client root") (done))
      (do
        (fresh!)
        (reset! !phases {})
        (-> (rf.hicasso.roots-frames-support/settled-server-html!
              frame-id [tray-screen {:tag :settled}]
              (fn [c] (= "present" (text-in c ".probe"))))
            (.then
              (fn [settled]
                (rf.hicasso.impl.collector/reset-runtime!)
                (reset! !phases {})
                (let [real (server-bytes! [tray-screen {:tag :server}])]

                  (testing "the harness's helper reports a SETTLED tray,
                            because a client root played the enter
                            transition before its markup was copied"
                    (is (= "present" (probe-text settled))
                        (str "got " (pr-str (probe-text settled)) " from " settled)))

                  (testing "React's own server renderer reports an ENTERING
                            tray for the very same tree under the very same
                            frame — so the two producers are not
                            interchangeable, and a hydration measured
                            against the first is not a measurement of the
                            second"
                    (is (= "mounting" (probe-text real))
                        (str "got " (pr-str (probe-text real)) " from " real))
                    (is (not= (probe-text settled) (probe-text real))
                        (str "settled=" (pr-str (probe-text settled))
                             " real=" (pr-str (probe-text real))
                             " — an equality here is the repair, and it
                             rewrites this row rather than relaxing it"))))))
            ;; No `:release!`: this row mounts nothing of its own.
            ;; `settled-server-html!` releases the client root it used to
            ;; produce the settled bytes, and `server-bytes!` is a
            ;; `renderToString` with no DOM behind it.
            (rf.hicasso.roots-frames-support/settle-row!
              {:row  "§2 — the client's settled bytes are not the server's"
               :done done}))))))

;; ---------------------------------------------------------------------------
;; §3 — hydrating the REAL server bytes diverges, and the adoption is lost
;; ---------------------------------------------------------------------------

;; The closure evidence the audit asked for, and it comes back negative:
;; drive `react-dom/server`, put those exact bytes on the page, hydrate them
;; through the product's own door, and read what happens.
;;
;; Three observables, and the file's header explains why it takes all three:
;;
;;   - the NODE EXPANDO. React repairs a failed hydration by discarding the
;;     server's DOM and client-rendering into the container, arriving at
;;     markup indistinguishable from a working adoption. A stamp that cannot
;;     survive serialisation is the only thing that tells the two apart.
;;   - REACT'S OWN COMPLAINT, off the page's error channel. A row that only
;;     read the DOM would pass over a divergence React silently repaired.
;;   - the FRAMEWORK's `:rf.ssr/hydration-mismatch`, off the live trace
;;     stream, which is also the root-attribution reading: the emit is gated
;;     on the window THIS root minted, so a diagnostic arriving here is
;;     attributed to this root by construction.
;;
;; And the final DOM reads `present` in every case, which is exactly why the
;; text assertion this seam most invites is worthless.
;;
;; SABOTAGE (run by hand; the namespace header quotes the failures): make
;; `server-bytes!` produce `present` bytes — which is what BOTH the repair
;; and `sup/settled-server-html!` do, and the reason one sabotage covers the
;; narrowing as well as the fix — and every assertion below inverts: React
;; complains zero times, the framework emits nothing, and the server's own
;; nodes are kept. That is the narrowing this row exists to catch, and it is
;; the only row in the file that catches it.
(deftest hydrating-the-real-server-bytes-diverges-and-discards-the-adoption
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (reset! !phases {})
        (let [html (server-bytes! [tray-screen {:tag :server}])]
          (is (= "mounting" (probe-text html))
              (str "premise: these are the real server's bytes — " html))
          (rf.hicasso.impl.collector/reset-runtime!)
          (reset! !phases {})
          (let [ca (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html))
                {:keys [seen stop!]} (rf.hicasso.roots-frames-support/watch-mismatches!)
                ;; MANUFACTURED here and asserted on here — the only shape of
                ;; call site at which swallowing an uncaught error is not the
                ;; fail-open the pageerror rule forbids. The divergence is the
                ;; subject of the row, not an accident inside it.
                {:keys [captured close!]} (rf.hicasso.roots-frames-support/open-console-capture! {:swallow-uncaught? true})
                ha (rf.hicasso.impl.mount/hydrate-root! ca frame-id [tray-screen {:tag :client}])]
            (is (true? (rf.hicasso.impl.roots/adopting? (:adoption ha)))
                "premise: the client root DID mint a window — the client half
                 of the seam is present and working; it is the server half
                 that is missing")
            (-> (rf.hicasso.roots-frames-support/adopted! ha)
                (.then
                  (fn [ok]
                    ;; Closed and stopped HERE, and named in `:release!` as
                    ;; well. Here, because the assertions below read
                    ;; `@captured` and `@seen` and the capture has to be shut
                    ;; across the render rather than across the rest of the
                    ;; row; there, because both are idempotent — `close!` on a
                    ;; `closed?` volatile, `stop!` by its own docstring — and
                    ;; the rejection path never reaches this line. Leaving it
                    ;; here alone is what let this row's `:swallow-uncaught?`
                    ;; listener outlive it.
                    (close!)
                    (stop!)
                    (is (true? ok) "the root's own closer ran")

                    (testing "the client's FIRST pass read its window and was
                              born `:present`, against server bytes that said
                              `mounting`. That is the seam, in one pair of
                              readings"
                      (is (= :present (first (get @!phases :client)))
                          (str "the client's first phase; saw "
                               (pr-str (get @!phases :client)))))

                    (let [complaints (filterv #(re-find #"Hydration failed" %) @captured)]
                      (testing "so React could not adopt: it complained on the
                                page's own error channel, which
                                `report-recoverable-default!` delegates to
                                unconditionally"
                        (is (pos? (count complaints))
                            (str "expected React to report the divergence; captured "
                                 (pr-str @captured))))

                      (testing "and every complaint React made reached the
                                framework's instrumentation stream as Spec
                                011's `:rf.ssr/hydration-mismatch`, gated on
                                THIS root's window — which is the root
                                attribution: no other root could have emitted
                                it, because no other root holds this window"
                        (is (= (count complaints) (count @seen))
                            (str "React said " (count complaints)
                                 ", the framework said " (count @seen)))
                        (doseq [ev @seen]
                          (let [tags (rf.hicasso.roots-frames-support/tags-of ev)]
                            (is (= :rf.ssr/hydration-mismatch (:operation ev)))
                            (is (= 're-frame.hicasso.impl.mount/hydrate-root! (:where tags)))
                            (is (= :warned-and-replaced (:recovery tags)))
                            (is (string? (:error tags)))))))

                    (testing "and the server's DOM was THROWN AWAY. The nodes
                              on the page are not the nodes the bytes
                              produced, so nothing was adopted — the whole
                              point of shipping server markup is lost on this
                              surface, and no value-level assertion anywhere
                              can see it"
                      (is (some? (.querySelector ca ".probe"))
                          "premise: something is there to check, so the
                           negative below is not vacuous")
                      (is (false? (rf.hicasso.roots-frames-support/server-node? (.querySelector ca ".probe")))
                          "the probe node was re-created")
                      (is (false? (rf.hicasso.roots-frames-support/every-server-node? ca ".screen, .probe"))))

                    (testing "while the FINAL DOM reads exactly what a healthy
                              adoption would have left — React's repair. This
                              is the assertion that stays green through the
                              whole failure, and the reason no row in this
                              file rests on it"
                      (is (= "present" (text-in ca ".probe")))
                      (is (= "alpha" (text-in ca ".value"))))))
                (rf.hicasso.roots-frames-support/settle-row!
                  {:row      "§3 — hydrating the real server bytes diverges"
                   :done     done
                   :release! (fn []
                               (close!)
                               (stop!)
                               (rf.hicasso.impl.mount/release! ha))}))))))))

;; ---------------------------------------------------------------------------
;; §4 — the control: the same real server path, with no tray in the tree
;; ---------------------------------------------------------------------------

;; Without this row §3 proves nothing. A red hydration is exactly what a
;; broken `server-bytes!`, a stale container, a mis-scoped frame or a
;; renderer/DOM version skew would also produce, and §3 could not tell those
;; apart from the seam it is about.
;;
;; Same frame, same subscription, same `renderToString`, same `server-dom!`,
;; same `hydrate-root!` — one difference, and it is the presence tray. This
;; hydrates silently and keeps every server node, so the divergence above is
;; the tray's and the plumbing beneath it is sound.
;;
;; A REPAIR MUST NOT MOVE THIS ROW. If closing the seam reds §4, the repair
;; has changed ordinary hydration, which is not what it was for.
(deftest the-same-real-server-path-hydrates-a-tray-free-tree-with-no-divergence
  (async done
    (if-not (rf.hicasso.impl.mount/browser?)
      (do (rf.hicasso.roots-frames-support/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html (server-bytes! [plain-screen {}])]
          (is (re-find #"alpha" html)
              (str "premise: the real server renderer produced this tree — " html))
          (rf.hicasso.impl.collector/reset-runtime!)
          (let [ca (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html))
                {:keys [seen stop!]}      (rf.hicasso.roots-frames-support/watch-mismatches!)
                {:keys [captured close!]} (rf.hicasso.roots-frames-support/open-console-capture!)
                ha (rf.hicasso.impl.mount/hydrate-root! ca frame-id [plain-screen {}])]
            (-> (rf.hicasso.roots-frames-support/adopted! ha)
                (.then
                  (fn [ok]
                    ;; Closed and stopped here and named in `:release!` too,
                    ;; for §3's reason: the assertions below read `@captured`
                    ;; and `@seen`, both primitives are idempotent, and the
                    ;; rejection path never reaches this line.
                    (close!)
                    (stop!)
                    (is (true? ok) "the root's own closer ran")

                    (testing "React adopted the real server render silently"
                      (is (= [] (filterv #(re-find #"Hydration failed" %) @captured))
                          (str "no complaint expected; captured " (pr-str @captured)))
                      (is (= 0 (count @seen))
                          (str "and no framework diagnostic; got "
                               (pr-str (mapv (comp :error rf.hicasso.roots-frames-support/tags-of) @seen)))))

                    (testing "and kept the server's own nodes, which is what
                              adoption MEANS and what §3 lost"
                      (is (rf.hicasso.roots-frames-support/every-server-node? ca ".screen, .value")))))
                (rf.hicasso.roots-frames-support/settle-row!
                  {:row      "§4 — the same real server path with no tray"
                   :done     done
                   :release! (fn []
                               (close!)
                               (stop!)
                               (rf.hicasso.impl.mount/release! ha))}))))))))

;; ---------------------------------------------------------------------------
;; §5 — the PRODUCT server door, which does scope a window, and what that
;;      does to the seam §1 records
;; ---------------------------------------------------------------------------

;; THE ROW THE PRODUCT DOOR ASKS FOR. §1 measures a `renderToString` with no
;; window over it; this measures `re-frame.hicasso.server/render`, which opens
;; one per request and hands `impl.mount/tree` the hydrating handle shape. The
;; two rows differ in exactly one thing, so what separates their bytes is the
;; window and nothing else — which is §1's control, now taken through a
;; product door instead of by hand.
;;
;; It answers the triage question at source: the presence seam is CLOSED
;; through this entry. `adopting-here?` reads a real open window on the
;; server, `impl.presence-react/presence-body` settles, and the tray's
;; children are `present` in the bytes a consumer ships — the phase that
;; hydrates against a client whose first pass is also `present`.
;;
;; WHAT IT DOES NOT DO is retire §1. A hand-rolled `renderToString` is still
;; a path a consumer can spell, and §1 is what that path costs. Which of the
;; two spellings HS-33 is dispositioned against is `dispositions.md`'s, and
;; a witness does not amend the row it witnesses.
;;
;; Node-safe for the same reason §1 is: `server/render` is a `renderToString`
;; call and reads no `document`.
(deftest the-product-server-door-scopes-a-window-so-presence-is-present-in-its-bytes
  (fresh!)
  (reset! !phases {})
  (let [{:keys [html]} (rf.hicasso.server/render {:hiccup   [tray-screen {:tag :product}]
                                       :snapshot {:label "alpha"}
                                       :payload  [:label]})]

    (testing "premise: the door rendered this tree, and the frame it minted
              per request resolved the subscription"
      (is (re-find #"class=\"screen\"" html) (str "got " html))
      (is (re-find #"alpha" html)
          (str "the per-request frame's subscription resolved — " html)))

    (testing "**the seam is closed on this path.** The tray's child is
              `present` in the bytes `re-frame.hicasso.server/render`
              delivers, where §1's windowless render emits `mounting`.
              `roots/adopting-here?` reads the window this door opened, so
              `presence-body` settles on the server and the page ships the
              phase a hydrating client's first pass also computes"
      (is (= "present" (probe-text html))
          (str "got " (pr-str (probe-text html)) " from " html))
      (is (= :present (first (get @!phases :product)))
          (str "and the machine computed it that way on the server's first
                render; saw " (pr-str (get @!phases :product)))))

    (testing "and the two producers really do differ, which is what makes
              this a measurement of the WINDOW rather than of the door's
              name. §1's bare `renderToString` over the same tree still
              emits the enter phase"
      (reset! !phases {})
      (let [bare (server-bytes! [tray-screen {:tag :bare}])]
        (is (= "mounting" (probe-text bare))
            (str "got " (pr-str (probe-text bare)) " from " bare))
        (is (not= (probe-text html) (probe-text bare))
            "the product door and the hand-rolled path must differ, or this
             row is measuring nothing")))))

;; ---------------------------------------------------------------------------
;; §6 — THE LEAK CONTROL: a rejected adoption still releases the root,
;;      the console and the watcher
;; ---------------------------------------------------------------------------
;;
;; §2 through §4 all FULFIL on a green run, so `sup/settle-row!`'s rejection
;; arm is on no green path — and a repair to a branch nothing takes is
;; untested by construction. This row takes it.
;;
;; The rejection is injected AFTER the adoption completes, which is the
;; harder case and not the weaker one: every resource the row owns is live
;; and committed at that moment, so there is strictly MORE to release than
;; there would be had `sup/adopted!` rejected before the root ever adopted.
;;
;; Under the shape this file carried before, nothing below the injection runs
;; at all. The rejection skips the fulfilment handler, so the `try` is never
;; entered and its `finally` never fires: no `close!`, no `stop!`, no
;; `release!`, no `done`. The row does not go red — it hangs to `cljs.test`'s
;; async timeout, reports the timeout instead of the rejection, and leaves the
;; root mounted, the container in the document and `console.error` still
;; replaced for whatever runs next.

(deftest a-rejected-adoption-still-releases-the-root-console-and-watcher
  (if-not (rf.hicasso.impl.mount/browser?)
    (rf.hicasso.roots-frames-support/skip! "what a rejection can leak here is a real root and a real console")
    (async done
      (fresh!)
      (let [html           (server-bytes! [plain-screen {}])
            ca             (rf.hicasso.roots-frames-support/stamp-server-nodes! (rf.hicasso.roots-frames-support/server-dom! html))
            watch          (rf.hicasso.roots-frames-support/watch-mismatches!)
            ;; NOT `:swallow-uncaught? true`: this row manufactures a rejected
            ;; PROMISE, which `sup/settle-row!` handles, and no uncaught window
            ;; error at all. Swallowing anywhere else is the fail-open the
            ;; browser runner's pageerror rule forbids.
            console-before (.-error js/console)
            capture        (rf.hicasso.roots-frames-support/open-console-capture!)
            stops          (atom 0)
            finishes       (atom 0)
            reports        (atom [])]
        (rf.hicasso.impl.collector/reset-runtime!)
        (let [ha (rf.hicasso.impl.mount/hydrate-root! ca frame-id [plain-screen {}])]
          (-> (rf.hicasso.roots-frames-support/adopted! ha)
              (.then
                (fn [ok]
                  (is (true? ok) "premise: the root really did adopt")
                  (is (not= rf.hicasso.roots-frames-support/released (rf.hicasso.roots-frames-support/census))
                      (str "premise: the runtime is holding this root's cells "
                           "and edges, so the census taken after the rejection "
                           "is a RELEASE and not an empty page; got "
                           (pr-str (rf.hicasso.roots-frames-support/census))))
                  (js/Promise.reject (js/Error. "adoption rejected on purpose"))))
              (rf.hicasso.roots-frames-support/settle-row!
                {:row      "the rejected-adoption control"
                 :done     (fn [] (swap! finishes inc))
                 :report!  (fn [e] (swap! reports conj e))
                 :release! (fn []
                             ((:close! capture))
                             (swap! stops inc)
                             ((:stop! watch))
                             (rf.hicasso.impl.mount/release! ha))})
              ;; The cell reapers are armed at unmount and run past a bare
              ;; macrotask, so the tables are read at the runtime's own horizon
              ;; rather than one tick after the release.
              (.then (fn [_] (rf.hicasso.roots-frames-support/quiesced!)))
              (.then
                (fn [_]
                  (testing "the rejection is REPORTED — which is the whole of
                            what a hang gives away — and the row ends ONCE"
                    (is (= 1 @finishes)
                        (str "done ran " @finishes " times"))
                    (is (= 1 (count @reports))
                        (str "exactly one report; got " (pr-str @reports)))
                    (is (re-find #"adoption rejected on purpose" (str (first @reports)))
                        (str "naming what the adoption threw; got "
                             (pr-str @reports))))

                  (testing "and the page the NEXT row inherits holds nothing of
                            this one. Two of these four are the row's alone to
                            give back — the `:each` fixture resets frames,
                            disposes the adapter and empties the runtime, but it
                            never unmounts a React root and never hands
                            `console.error` back. (The trace listener it does
                            sweep, in its `:before`; `stop!` is asserted here
                            all the same, because a row that leans on the
                            fixture to stop its own watcher is measuring the
                            fixture)"
                    (is (= rf.hicasso.roots-frames-support/released (rf.hicasso.roots-frames-support/census))
                        (str "no root: residue was " (pr-str (rf.hicasso.roots-frames-support/census))))
                    (is (empty? (rf.hicasso.roots-frames-support/cell-frames))
                        (str "no frame: the cell table still mentions "
                             (pr-str (rf.hicasso.roots-frames-support/cell-frames))))
                    (is (= 1 @stops)
                        (str "the mismatch watcher was stopped — `stop!` is what "
                             "unregisters the trace listener; it ran "
                             @stops " times"))
                    (is (identical? console-before (.-error js/console))
                        "`console.error` is the page's own again"))))
              (rf.hicasso.roots-frames-support/settle-row!
                {:row      "the rejected-adoption control's own settlement"
                 :done     done
                 :release! (fn []
                             ((:close! capture))
                             ((:stop! watch))
                             (rf.hicasso.impl.mount/release! ha))})))))))
