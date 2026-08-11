(ns re-frame.hicasso.presence-ssr-seam-dom-cljs-test
  "THE PRESENCE/ROOT ROW OF THE SSR MATRIX, DRIVEN THROUGH THE REAL
  SERVER RENDERER — and the seam that measurement finds open
  (rf2-hic-046, audit of PR #7756).

  ## What this file is, said plainly

  It is a **RECORD OF A MISSING REFUSAL, not a proof of parity.**
  Presence has a server/client seam and the seam is currently OPEN: the
  client half exists and the server half does not. Every row below drives
  `react-dom/server` for real and hydrates those actual bytes, and what
  they establish is the divergence, its cause, and the exact shape of the
  repair that closes it. Nothing here claims presence round-trips.

  ## The surface is dispositioned, and it does not do what its
  disposition says

  `docs/design/hicasso/product/dispositions.md` HS-33 — *optional motion
  and presence module* — carries the operative disposition **Client-only
  — refusal until rf2-hic-046**, and the canonical two-policy matrix
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
  adoption window's React context. Over the whole tree it has exactly one
  product-source caller:

      implementation/hicasso/src/re_frame/hicasso/impl/mount.cljs:107
        — inside `impl.mount/tree`, and only for a handle carrying an
          `:adoption` window, which only `hydrate-root!` mints.

  There is no second caller anywhere: no server entry, no request scope,
  no render helper. So on **every** server render `roots/adopting-here?`
  reads the context default `nil`, `roots/adopting?` calls that closed,
  and `impl.presence-react/presence-body` takes its ordinary
  `:mounting` branch. The client's hydrating root reads a real open
  window one line later and takes `:present`. Two different phases for
  the same tree, decided on opposite sides of a wire.

  §1 measures that as a property rather than restating it, and its
  control shows the divergence is caused by the missing provider and by
  nothing else.

  ## Two source comments currently overstate this, and they are OUTSIDE
  this suite's fence

  Both speak in the present tense about a server door that does not
  exist. They are runtime source and this dispatch may not edit them;
  they are recorded here so the next reader of either line finds the
  measurement rather than trusting the prose:

      impl/roots.cljs:54          \"Minted per `hydrate-root!` call and
                                   per server render/request.\"
      impl/presence_react.cljs:130 \"A SERVER render entry opens a window
                                   of its own around its own request, so
                                   the server's HTML and the client's
                                   first pass agree by construction\"

  §1 and §3 are the counter-measurement: they do not agree, and nothing
  opens a window around a request.

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
  request and scopes it over that request's tree — the fifth item of
  rf2-6tmu's adopted repair shape, explicitly deferred there to
  rf2-hic-046. §1's control is that door in one line, so the cost is
  already measured. When it lands:

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

  ## Lane

  `-dom-cljs-test`, so `:browser-test` runs the whole file against a real
  React DOM. §1 is a `renderToString` row and needs no DOM, so it also
  runs under `:node-test`; §2–§4 hydrate and state their skip there
  rather than reporting a false green."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.hicasso.motion :as motion]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.test-support :as test-support]
            ["react-dom/server" :as react-dom-server]))

(def ^:private frame-id ::seam)

(def ^:private label-q [::label])

;; Registered ABOVE `use-fixtures` — the reset fixture captures its
;; source-store baseline when the `use-fixtures` form is evaluated, so a
;; registration written below it is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

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

(h/defview phase-probe
  "A presence child that is a BOUNDARY, so the machine hands it its phase
  as the ordinary prop `:rf/phase` (`impl.presence/with-phase`) rather
  than as attribute overrides on a node.

  It renders the phase as TEXT, which is what makes the seam visible in
  the server's bytes at all — an attribute-only divergence is outside
  React's hydration-mismatch contract by React's own rules
  (`impl.mount`'s reporter header, Spec 011 §Hydration-mismatch
  detection), so a native child wearing `::h/mounting` attributes would
  diverge here in silence."
  [{:keys [tag] :as props}]
  (let [phase (:rf/phase props)]
    (swap! !phases update tag (fnil conj []) phase)
    [:span.probe (name phase)]))

(h/defview tray-screen
  "A screen with a presence tray in it — the surface this row is about."
  [{:keys [tag]}]
  [:div.screen
   [:p.value (h/sub label-q)]
   [motion/presence {:timeout-ms 50}
    [phase-probe {:key "one" :tag tag}]]])

(h/defview plain-screen
  "The same screen with the tray removed — §4's control. Identical in
  every other respect: same frame, same subscription read, same server
  door, same hydration."
  [_]
  [:div.screen
   [:p.value (h/sub label-q)]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- fresh! []
  (sup/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [::seed "alpha"]))
  (collector/reset-runtime!)
  (collector/reset-body-runs!)
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
  (mount/provider frame-id (codec/root-element frame-id hiccup)))

(defn- server-bytes!
  "The bytes React's ACTUAL server renderer produces for `hiccup` — the
  same runtime the sibling Node entry drives (rf2-2rtt6.86), reduced to
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
  (let [window (roots/open-adoption-window!)]
    (try
      (react-dom-server/renderToString
        (roots/with-adoption window (app-element hiccup)))
      (finally (roots/close-adoption-window! window)))))

(defn- probe-text
  "The phase the markup says, read out of a STRING rather than a DOM —
  §1 runs under `:node-test` too."
  [html]
  (second (re-find #"class=\"probe\"[^>]*>([a-z]+)<" html)))

(defn- text-in [container sel]
  (some-> (.querySelector container sel) .-textContent))

;; ---------------------------------------------------------------------------
;; §1 — the real server render installs no adoption window
;; ---------------------------------------------------------------------------

;; The census, turned into a measurement. `renderToString` runs the tray's
;; body for real — hooks, context reads and all — so what it emits is what
;; `roots/adopting-here?` answered inside a genuine server render.
;;
;; SABOTAGE (run by hand, PR body records it): make `with-adoption`'s
;; provider unconditional, or make `presence-body` settle without consulting
;; the window, and the control below stops discriminating — both halves
;; report the same phase and the pair goes red.
(deftest the-real-server-render-installs-no-adoption-window-so-presence-enters
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
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM, and `settled-server-html!` mounts a client root") (done))
      (do
        (fresh!)
        (reset! !phases {})
        (-> (sup/settled-server-html!
              frame-id [tray-screen {:tag :settled}]
              (fn [c] (= "present" (text-in c ".probe"))))
            (.then
              (fn [settled]
                (try
                  (collector/reset-runtime!)
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
                               rewrites this row rather than relaxing it"))))
                  (finally (done))))))))))

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
;; SABOTAGE (run by hand, PR body records it): swap `server-bytes!` for
;; `sup/settled-server-html!` — the substitution the merged witness made —
;; and every assertion below inverts: no complaint, no diagnostic, the
;; server's nodes kept. That is the narrowing this row exists to catch, and
;; it is the only one that catches it.
(deftest hydrating-the-real-server-bytes-diverges-and-discards-the-adoption
  (async done
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (reset! !phases {})
        (let [html (server-bytes! [tray-screen {:tag :server}])]
          (is (= "mounting" (probe-text html))
              (str "premise: these are the real server's bytes — " html))
          (collector/reset-runtime!)
          (reset! !phases {})
          (let [ca (sup/stamp-server-nodes! (sup/server-dom! html))
                {:keys [seen stop!]} (sup/watch-mismatches!)
                ;; MANUFACTURED here and asserted on here — the only shape
                ;; of call site at which swallowing an uncaught error is not
                ;; the fail-open rf2-mwx08 forbids. The divergence is the
                ;; subject of the row, not an accident inside it.
                {:keys [captured close!]} (sup/open-console-capture! {:swallow-uncaught? true})
                ha (mount/hydrate-root! ca frame-id [tray-screen {:tag :client}])]
            (is (true? (roots/adopting? (:adoption ha)))
                "premise: the client root DID mint a window — the client half
                 of the seam is present and working; it is the server half
                 that is missing")
            (-> (sup/adopted! ha)
                (.then
                  (fn [ok]
                    (close!)
                    (stop!)
                    (try
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
                            (let [tags (sup/tags-of ev)]
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
                        (is (false? (sup/server-node? (.querySelector ca ".probe")))
                            "the probe node was re-created")
                        (is (false? (sup/every-server-node? ca ".screen, .probe"))))

                      (testing "while the FINAL DOM reads exactly what a healthy
                                adoption would have left — React's repair. This
                                is the assertion that stays green through the
                                whole failure, and the reason no row in this
                                file rests on it"
                        (is (= "present" (text-in ca ".probe")))
                        (is (= "alpha" (text-in ca ".value"))))

                      (finally (mount/release! ha) (done))))))))))))

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
    (if-not (mount/browser?)
      (do (sup/skip! ":node-test has no DOM") (done))
      (do
        (fresh!)
        (let [html (server-bytes! [plain-screen {}])]
          (is (re-find #"alpha" html)
              (str "premise: the real server renderer produced this tree — " html))
          (collector/reset-runtime!)
          (let [ca (sup/stamp-server-nodes! (sup/server-dom! html))
                {:keys [seen stop!]}      (sup/watch-mismatches!)
                {:keys [captured close!]} (sup/open-console-capture!)
                ha (mount/hydrate-root! ca frame-id [plain-screen {}])]
            (-> (sup/adopted! ha)
                (.then
                  (fn [ok]
                    (close!)
                    (stop!)
                    (try
                      (is (true? ok) "the root's own closer ran")

                      (testing "React adopted the real server render silently"
                        (is (= [] (filterv #(re-find #"Hydration failed" %) @captured))
                            (str "no complaint expected; captured " (pr-str @captured)))
                        (is (= 0 (count @seen))
                            (str "and no framework diagnostic; got "
                                 (pr-str (mapv (comp :error sup/tags-of) @seen)))))

                      (testing "and kept the server's own nodes, which is what
                                adoption MEANS and what §3 lost"
                        (is (sup/every-server-node? ca ".screen, .value")))

                      (finally (mount/release! ha) (done))))))))))))
