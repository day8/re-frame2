(ns re-frame.bench.hicasso.ssr.instance-key-payload-dom-cljs-test
  "THE INSTANCE-KEY PAYLOAD OBLIGATION, WITNESSED BOTH WAYS (rf2-2rtt6.99).

  `h/reg-state` puts a widget's own state at `[:ui ::concern ikey]`
  (`front/state.cljc`), which is APP-SPACE data — so whether it reaches
  the client is decided by the hydration payload policy and by nothing
  else. The 2026-08-04 ruling states the obligation that follows:

  > an allowlist MUST name `:ui` whenever server-side events write
  > render-affecting instance state; strip it only if the server never
  > writes it. Whole-app-db carries it automatically.

  This file is that sentence turned into two measurements taken on the
  same page, and the second one is the reason the file exists.

  ## Why prose was not enough, stated precisely

  The payload policy is fail-closed and stays fail-closed: an ABSENT
  `:payload` throws `:rf.error/ssr-missing-payload-policy` at the
  framework's own validator, and nothing here softens that (it is pinned
  by `ssr/entry-cljs-test`'s `the-payload-policy-is-fail-closed`). But
  `[:panels]` is a perfectly well-formed allowlist. There is no shape for
  the validator to refuse, no default to fall back on, and no way for the
  framework to know that `:ui` mattered on THIS page — so a policy that
  is fail-closed against absence is silent about omission, and the
  obligation is a rule the author keeps or does not.

  What catches the author who does not is React. The divergence between
  the server's open panel and the client's shut one is a hydration
  mismatch, and since rf2-2rtt6.97 the arm's hydrate door surfaces one as
  Spec 011's `:rf.ssr/hydration-mismatch`. **That is the enforcement**,
  and [[omitting-ui-from-the-allowlist-fires-the-structured-mismatch]]
  asserts it POSITIVELY — the row is red-by-design and the error is the
  row's subject, not an accident it survives.

  ## The two rows are each other's control

  A witness that only manufactured a fault could be reading a diagnostic
  that fires on every adoption; a witness that only took a clean
  adoption could be reading a capture that never fires at all. So:

  | Row | What it asserts | What it stops the other from lying about |
  |---|---|---|
  | [[naming-ui-on-the-allowlist-hydrates-with-zero-mismatch]] | zero emits, zero console complaints, the server's own nodes adopted | the diagnostic is not a constant |
  | [[omitting-ui-from-the-allowlist-fires-the-structured-mismatch]] | the diagnostic fires, tagged with the door's own site | the silence above is a reading |

  ## Where the structured error is read from, and why not `thrown?`

  From the live trace stream — `re-frame.trace.tooling/register-listener!`,
  filtered on `(= :rf.ssr/hydration-mismatch (:operation ev))`, which is
  `arm1/hydrate-recoverable-dom-cljs-test`'s channel and the only place
  this diagnostic can be read: it fires from a React root-error callback,
  outside any dispatch scope, so it is frameless and never reaches a
  per-frame ring.

  `(is (thrown? …))` would be wrong here twice over. React routes a
  recoverable hydration error to `reportError`, so nothing throws on any
  stack `cljs.test` is watching — a `thrown?` row would be RED over a
  working door. And the recoverable error is exactly the thing the door
  is supposed to have RECOVERED from, so there is no exception left to
  catch even in principle. The `window` `error` and `console.error`
  channels are watched as well (`hydration-support/open-console-capture!`)
  because a row asserting \"nothing complained\" that watched neither
  would be green over a live failure — and in the red row they carry the
  second half of rf2-2rtt6.97's finding: the door COMPOSES over React's
  default, so the uncaught report is still there beside the diagnostic
  and `rf2-mwx08` is not softened.

  ## And the capture window is the honest one

  Opened before `hydrate-root!` and closed after `adopted!` resolves.
  `hydrateRoot` is called plain, so it returns BEFORE the tree is
  adopted; a capture that closed at the call's return would be shut
  across the only part that can complain (measured by rf2-2rtt6.87: 1
  complaint across the adoption, 0 at the return).

  ## `:rf/render-hash` is not load-bearing anywhere in this file

  It cannot be, and as of rf2-2rtt6.91 it is not even present: an
  adoption-tier root carries no hash at either end (Spec 011
  §Hydration-mismatch detection), so the entry emits none. It never could
  have carried a claim anyway — the hash it used to ship was over an
  unresolved `[<minted head> {props}]` root, and `canonical-edn` renders
  every fn identically, so these rows took the same value as the dogfood
  screen, a different page entirely.
  [[the-instance-key-rows-cannot-lean-on-the-render-hash]] keeps that
  measurement on THESE rows so the exclusion stays a reading rather than a
  promise. Every determinism claim below is over the DOCUMENT BYTES.

  Runtime: `-dom-cljs-test`, so `:browser-test` runs it against a real
  React DOM. The payload-shape and determinism rows need no DOM and run
  under `:node-test` too; every DOM claim degrades to a stated skip
  there."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.hydration-support
             :refer [adopted! every-server-node? open-console-capture! server-dom!
                     server-node? stamp-server-nodes!]]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.ssr.entry :as entry]
            [re-frame.bench.hicasso.ssr.fixtures :as fixtures]
            [re-frame.bench.hicasso.ssr.instance-key :as instance-key]
            [re-frame.core :as rf]
            ;; rf2-2rtt6.91 — the entry emits no hash, so the exclusion row
            ;; takes the measurement it excludes directly.
            [re-frame.ssr.hash :as ssr-hash]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(def ^:private frame-id ::instance-key-payload)

(def ^:private green-id
  "The row whose allowlist NAMES `:ui`." "instance-key-payload")

(def ^:private red-id
  "The row whose allowlist omits it — same page, same boot events."
  "instance-key-payload-omitted")

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (fixtures/register!)
                      (rt/reset-runtime!)
                      (rt/reset-body-runs!))}))

(defn- skip! [why]
  (is true (str "a payload-obligation hydration claim needs a real React DOM — " why)))

;; ---------------------------------------------------------------------------
;; The two sides
;; ---------------------------------------------------------------------------

(defn- server!
  "Render one corpus row through the real entry, with the runtime reset on
  both sides so the hydration measured afterwards starts from an empty
  table."
  [row-id]
  (rt/reset-runtime!)
  (rt/reset-body-runs!)
  (let [result (entry/render (fixtures/row row-id))]
    (rt/reset-runtime!)
    (rt/reset-body-runs!)
    result))

(defn- client-frame!
  "Boot a CLIENT frame out of the payload the server shipped, and NOTHING
  else.

  `[:rf/set-db (:rf/app-db payload)]` is the reserved framework seeding
  event carrying exactly the slice `re-frame.ssr.hydrate`'s own
  `:rf/hydrate` handler installs — `(or (:rf/app-db payload) db)`. It is
  spelled this way rather than by dispatching `:rf/hydrate` because
  loading the `re-frame.ssr` entry namespace would install its late-bind
  hooks and seven server-response fxs into every build this lane
  compiles in, including the `:advanced` bench build; the lane requires
  only leaf SSR namespaces for that reason, and the entry does the same
  (`ssr/entry.cljs` requires `payload-policy`, `hash`, `constants` and
  `html-helpers` and no more). What is under test is the CONTENT of the
  payload, so the seeding door is deliberately the least interesting
  part of the row."
  [payload]
  (lane/leave-act-environment!)
  (rf/make-frame {:id             frame-id
                  :initial-events [[:rf/set-db (:rf/app-db payload)]]})
  frame-id)

(defn- watch-mismatches!
  "Capture every `:rf.ssr/hydration-mismatch` the framework emits, on the
  live trace stream. Answers `{:seen atom :stop! fn}`; `stop!` is
  idempotent. The diagnostic is frameless — it fires from a React
  root-error callback, outside any dispatch scope — so a listener is the
  only place it can be read."
  []
  (let [seen (atom [])
        k    (keyword (str "rf2-2rtt6-99-" (gensym)))]
    (trace-tooling/register-listener!
      k (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                   (swap! seen conj ev))))
    {:seen seen :stop! (fn [] (trace-tooling/unregister-listener! k) @seen)}))

(defn- tags-of
  "The diagnostic's tags, merged with its top level, so a read is robust
  to whichever slots the envelope hoists."
  [ev]
  (merge (:tags ev) ev))

(defn- open-panel-in [container]
  (.querySelector container ".panel-body"))

(defn- panel-count [container]
  (.-length (.querySelectorAll container ".panel")))

(defn- adopt!
  "Put `html` on the page, stamp every node, hydrate it on `frame-id`, and
  answer a promise of what happened across the WHOLE adoption.

  `capture-opts` reaches `open-console-capture!`; the only caller that
  sets `:swallow-uncaught?` is the row that manufactures the fault and
  asserts on it."
  [html capture-opts]
  (let [container (stamp-server-nodes! (server-dom! html))
        {:keys [seen stop!]} (watch-mismatches!)
        {:keys [captured close!]} (open-console-capture! capture-opts)
        before    (some? (open-panel-in container))
        handle    (mount/hydrate-root! container frame-id [instance-key/screen {}])]
    (-> (adopted!)
        (.then (fn [ok]
                 (close!)
                 (stop!)
                 {:handle    handle
                  :container container
                  :adopted?  ok
                  :open-before? before
                  :mismatches @seen
                  :warnings   @captured})))))

(defn- report! [label m]
  (js/console.log (str ";; HICASSO SSR PAYLOAD-OBLIGATION " label " " (pr-str m)))
  m)

;; ===========================================================================
;; The payload itself — the omission is a fact about the WIRE
;; ===========================================================================

(deftest the-two-rows-differ-by-exactly-the-ui-key
  (testing "**the variable, isolated.** The two corpus rows render the
           same hiccup from the same boot events, so the SERVER BYTES are
           identical; the allowlist is the only thing that moves, and it
           moves exactly one key. Without this row the two hydration rows
           below could be measuring any difference at all between two
           pages"
    (let [green (server! green-id)
          red   (server! red-id)
          gdb   (:rf/app-db (:payload green))
          rdb   (:rf/app-db (:payload red))]
      (is (= (:html green) (:html red))
          "the server rendered the same markup for both rows — the payload
           policy is a decision about what CROSSES, never about what is
           drawn")
      (is (re-find #"class=\"panel-body\"" (:html green))
          (str "and that markup carries the OPEN panel the boot event
                wrote, so there is something for the payload to be
                obliged about: " (:html green)))

      (testing "the green row ships the instance state at the documented tier"
        (is (= true (get-in gdb instance-key/state-path))
            (str "[:ui ::open? \"billing\"] crossed the wire as written. Saw: "
                 (pr-str gdb))))

      (testing "the red row ships a WELL-FORMED allowlist that strands it"
        (is (not (contains? rdb :ui))
            (str "no :ui partition at all — not an empty one, not a
                  defaulted one; absent: " (pr-str rdb)))
        (is (nil? (get-in rdb instance-key/state-path))))

      (testing "and everything else is identical, so `:ui` is the whole
               difference"
        (is (= (:panels gdb) (:panels rdb) instance-key/panels))
        (is (= #{:panels :ui} (set (keys gdb))))
        (is (= #{:panels} (set (keys rdb)))))

      (testing "read off the EDN the client bootstrap actually parses,
               because a claim about a payload map is a claim about the
               wire only if the bytes agree with it"
        (is (re-find #":ui\b" (:payload-edn green)))
        (is (nil? (re-find #":ui\b" (:payload-edn red)))))

      (report! "payload"
               {:green-keys (vec (sort-by str (keys gdb)))
                :red-keys   (vec (sort-by str (keys rdb)))
                :green-edn-bytes (count (:payload-edn green))
                :red-edn-bytes   (count (:payload-edn red))
                :html-identical? (= (:html green) (:html red))}))))

;; ===========================================================================
;; Determinism — the instance keys are authored data
;; ===========================================================================

(deftest both-rows-render-byte-identical-documents-from-authored-keys
  (testing "**the determinism row.** Every instance key on this page is an
           `:id` out of the authored roster, so a double render must be
           byte-identical — the two renders take two DIFFERENT per-request
           gensym frames, which is also the standing proof that the
           per-request id never reaches the wire. Stated over the document
           bytes; see [[the-instance-key-rows-cannot-lean-on-the-render-hash]]
           for why it could not be stated over the hash"
    (doseq [row-id [green-id red-id]]
      (let [{:keys [identical? differs-at] a :first b :second}
            (entry/render-twice (fixtures/row row-id))]
        (is identical?
            (str "row " row-id " rendered two DIFFERENT documents"
                 (when differs-at
                   (str " — first difference at character " differs-at))))
        (is (not= (:frame-id a) (:frame-id b))
            (str "row " row-id " took two different per-request frames"))
        (doseq [{ikey :id ptitle :title} instance-key/panels]
          (is (re-find (re-pattern (str "data-ikey=\"" ikey "\"")) (:html a))
              (str "the authored instance key " (pr-str ikey) " is in the
                    markup as written — not an ordinal, not a counter, not
                    a gensym"))
          (is (re-find (re-pattern ptitle) (:html a))
              "and so is the roster entry it was taken from"))))))

(deftest the-instance-key-rows-cannot-lean-on-the-render-hash
  (testing "**the exclusion, measured.** rf2-2rtt6.91 removed
           `:rf/render-hash` from this tier's wire — an adoption-tier root
           carries none — and the first assertion holds the entry to that.
           The second keeps the reason live: the hash this root WOULD have
           taken is over an unresolved hiccup whose head is a function, and
           `canonical-edn` renders every function identically, so these
           rows and the dogfood screen — a different page entirely — take
           one shared constant. Neither an absent value nor a constant one
           can carry a claim, so no claim in this file rests on it"
    (let [payload (:payload (entry/render (fixtures/row green-id)))
          hash-of #(ssr-hash/render-tree-hash (:hiccup (fixtures/row %)))
          dogfood (hash-of "dogfood-snapshot")
          green   (hash-of green-id)
          red     (hash-of red-id)]
      (is (not (contains? payload :rf/render-hash))
          "the wire carries no hash for an adoption-tier root")
      (is (= dogfood green red)
          (str "and the one it would have carried is a constant: "
               (pr-str [dogfood green red])))
      (report! "render-hash" {:would-have-been (str green) :on-the-wire "absent"}))))

;; ===========================================================================
;; ROW 1 — GREEN. The obligation met.
;; ===========================================================================

(deftest naming-ui-on-the-allowlist-hydrates-with-zero-mismatch
  (testing "**the obligation met.** `:ui` is on the allowlist, so the
           panel the server's boot event opened crosses the wire, the
           client's first render draws the same open panel, and React
           finds nothing to reconcile — asserted on the framework's own
           diagnostic AND on both channels React 19 complains through"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (let [{:keys [html payload]} (server! green-id)]
          (client-frame! payload)
          (is (= true (get-in (rf/app-db-value frame-id) instance-key/state-path))
              "the client frame booted holding the instance state the
               server wrote — which is the whole of what the allowlist
               bought")
          (-> (adopt! html nil)
              (.then
                (fn [{:keys [handle container adopted? open-before? mismatches warnings]}]
                  (try
                    (is (true? adopted?) "the adoption completed")
                    (is (true? open-before?)
                        "the panel was open in the bytes the client was
                         handed, BEFORE any JavaScript adopted them")

                    (is (= [] mismatches)
                        (str "**zero `:rf.ssr/hydration-mismatch`.** The
                              framework's own instrument stayed silent
                              across the whole adoption. Saw: "
                             (pr-str mismatches)))
                    (is (= [] warnings)
                        (str "and React reported nothing on either channel
                              — `window` `error` or `console.error`: "
                             (pr-str warnings)))

                    (is (some? (open-panel-in container))
                        "the panel is STILL open after adoption — open
                         before, open after, which is what \"hydrated\"
                         means for a page whose appearance is instance
                         state")
                    (is (server-node? (open-panel-in container))
                        "and it is the SERVER'S own node. The stamp is an
                         expando and cannot survive re-serialisation, so
                         React reused the markup rather than building a
                         replacement that happens to look alike")
                    (is (every-server-node? container "*")
                        "as is every element on the page — no partial
                         adoption, which is what a mismatch on one subtree
                         would look like")
                    (is (= 2 (panel-count container))
                        "both panels are there, so the row above is a
                         claim about a page and not about an empty
                         container")

                    (report! "row-1-green"
                             {:mismatches (count mismatches)
                              :warnings   (count warnings)
                              :panels     (panel-count container)
                              :open-after? (some? (open-panel-in container))})
                    (finally (mount/release! handle) (done)))))
              (.catch (fn [e] (is false (str "row 1 threw: " e)) (done)))))))))

;; ===========================================================================
;; ROW 2 — RED BY DESIGN. The obligation broken, and caught.
;; ===========================================================================

(deftest omitting-ui-from-the-allowlist-fires-the-structured-mismatch
  (testing "**the teaching witness, and it EXPECTS the error.** The same
           page with `:ui` omitted: the client boots without the instance
           state, its first render reads `reg-state`'s default and draws a
           SHUT panel where the server's bytes say open, and the
           structured `:rf.ssr/hydration-mismatch` fires. Asserted
           positively — a row that merely documented the obligation, or
           that passed because nothing happened, would be worth nothing.

           **This row MANUFACTURES the fault and is the assertion about
           it**, which is the one case `open-console-capture!`'s
           `:swallow-uncaught?` exists for. `rf2-mwx08` is not softened:
           the error is not unasserted here, it is the subject, and the
           row goes on to assert that the door reported it as well as
           emitting it"
    (async done
      (if-not (mount/browser?)
        (do (skip! ":node-test has no DOM") (done))
        (let [{:keys [html payload]} (server! red-id)]
          (client-frame! payload)
          (is (nil? (get-in (rf/app-db-value frame-id) instance-key/state-path))
              "the client frame booted WITHOUT the instance state — the
               entry is absent, so `reg-state`'s sub will read its
               `:default`, which is `false`")
          ;; MANUFACTURED fault, asserted on — see the testing string.
          (-> (adopt! html {:swallow-uncaught? true})
              (.then
                (fn [{:keys [handle container adopted? open-before? mismatches warnings]}]
                  (try
                    (is (true? adopted?) "the adoption completed")
                    (is (true? open-before?)
                        "the server's bytes DID carry the open panel — the
                         divergence is the payload's, not the render's")

                    (is (pos? (count mismatches))
                        (str "**the structured hydration-mismatch FIRED.**
                              The taught obligation is enforced by the
                              mismatch machinery, and this is that
                              machinery answering. Saw "
                             (count mismatches) ": " (pr-str mismatches)))
                    (doseq [mm mismatches]
                      (let [tags (tags-of mm)]
                        (is (= :rf.ssr/hydration-mismatch (:operation mm)))
                        (is (= 're-frame.bench.hicasso.arm1.mount/hydrate-root!
                               (:where tags))
                            "tier-discriminated by :where — the arm's hydrate
                             door, so this is React's own reconciliation
                             report and not a hash comparison")
                        (is (= :warned-and-replaced (:recovery tags))
                            "React had already patched the DOM when the
                             callback ran")
                        (is (string? (:error tags))
                            "and the recoverable error's own message rides
                             along")))

                    (is (seq warnings)
                        (str "and the complaint ALSO reached a console
                              channel — the door composes over React's
                              default rather than replacing it
                              (rf2-2rtt6.97), so composing a diagnostic in
                              did not make the mismatch quieter than React
                              left it: " (pr-str warnings)))

                    (is (nil? (open-panel-in container))
                        "**and the client's model won, which is the cost.**
                         The panel the server rendered open is SHUT on the
                         adopted page: the server's work was thrown away
                         and the user sees the default. That is what
                         omitting `:ui` buys, stated as a DOM reading")
                    (is (= 2 (panel-count container))
                        "the rest of the page adopted normally — a stranded
                         partition is a wrong screen, not a blank one")

                    (report! "row-2-red-by-design"
                             {:mismatches (count mismatches)
                              :warnings   (count warnings)
                              :where      (str (:where (tags-of (first mismatches))))
                              :panels     (panel-count container)
                              :open-after? (some? (open-panel-in container))})
                    (finally (mount/release! handle) (done)))))
              (.catch (fn [e] (is false (str "row 2 threw: " e)) (done)))))))))
