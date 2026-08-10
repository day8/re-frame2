(ns re-frame.hicasso.cold-probe-cljs-test
  "THE COLD PROBE'S CONTRACT — what a read guarantees on a first,
  uncached read (rf2-wjag).

  A boundary body's read is warm when a committed cell already holds the
  key: a pure deref, nothing global touched. Every OTHER read is cold —
  the first read of a mounting boundary, every read of a render React
  will abandon, and every read taken while a cell's reaction has been
  invalidated out from under it. That is the majority of the reads this
  runtime performs, and the package had no witness for any of it.

  The contract, from the observation port's cold-probe discipline
  (rf2-6c237), is four promises in one sentence:

  > reuse a live sub-cache reaction by deref alone, else compute pure
  > against ONE render-scoped frame-state snapshot through ONE
  > render-scoped memo — **no reaction build, no cache insert, no in-tick
  > effect**.

  ## Why the promises are worth a witness rather than a comment

  The negative half is the whole design. `subscribe-once` — the door this
  path replaced — paid a reaction build, a cache insert, an in-tick evict
  and a dispose cascade **per read**, and a render React abandons is a
  render whose every read did all four for nothing. So `render probes,
  commit owns` (invariant I5) is not a property of the commit path alone:
  it is equally a property of the read path, and a probe that quietly
  cached would satisfy every acquisition assertion in
  `kernel_commit_owns_cljs_test` while leaving a cache entry per
  abandoned read.

  The positive half is a coherence promise. Within one body run a cold
  key computes ONCE, against one frame-state snapshot, through a memo
  the run owns — and the run's end is where that memo dies. Both halves
  of that are load-bearing in opposite directions: a memo that did not
  dedup would recompute per read, and a memo that outlived its run would
  serve a later render a value from an earlier commit.

  ## The instrument

  A counted sub body. Sub bodies are pure by contract and this one is
  pure in everything but a counter, which is what makes `how many times
  did the sub actually compute` an observable at all. It is the only way
  to tell rung 1 from rung 2, and the only way to tell one memoised
  compute from three unmemoised ones — neither of which changes a single
  value on screen.

  Beside it, the frame's own `:sub-cache` and the arm's `!cells`, read
  directly. `retains nothing` is a claim about two tables, so both are
  read, and each row that asserts a table is empty is paired with the
  committed case that fills it.

  ## What this file does not claim

  The generation fence — that a commit landing mid-body makes the body
  run again against the newer basis — is `render-body`'s law, not the
  probe's, and it is asserted where the tear can be seen
  (`reincarnation_paint_dom_cljs_test`). The probe's whole share of it is
  that its memo box is reset by every body run, which is the second half
  of the one-compute-per-run row below.

  ## The resolution seam, and a row that was deleted and is now back

  `cold-read!` computes inside `live-frame/call-with-frame-resolution`,
  which BINDS the target's resolved image generation and, before that,
  runs the read-time coalesced reprojection flush. §5 witnesses both.

  It witnesses them on an IMAGE-LOADED frame, and that is the whole
  difference from an earlier attempt. A same-tick registration row was
  written against the default-image frame the sections above use, found
  to red nothing under mutation, and deleted as decorative. The finding
  was about the ROW'S CONDITIONS and not about the claim: `make-frame`
  with no `:images` resolves the default image over the entire live
  source store, so a read through that generation and a read around it
  answer identically and neither half of the wrapper can be caught doing
  anything. Deleting is right only when the claim is covered elsewhere in
  the same artefact, and this one was covered only in the prototype
  runtime — which is what this file exists to stop relying on. So §5
  keeps the claim and changes the condition: a frame running an EXPLICIT
  image, where a selection is a thing a read can get wrong."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.inventory :as inventory]
            [re-frame.interop :as interop]
            [re-frame.subs :as subs]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::cold-probe)

(def ^:private !runs
  "How many times the counted sub's body ran. A test instrument and not a
  contract — sub bodies are pure by contract, and this one is pure in
  everything but the count."
  (volatile! 0))

(rf/reg-sub :coldprobe/counted (fn [db _] (vswap! !runs inc) (:v db)))
(rf/reg-sub :coldprobe/plain   (fn [db _] (:v db)))

(rf/reg-event :coldprobe/seed (fn [_ [_ db]] {:db db}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn [] (collector/reset-runtime!) (vreset! !runs 0))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- seeded!
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:coldprobe/seed {:v 7}]))
  (vreset! !runs 0)
  frame-id)

(defn- k [query-v] [frame-id query-v])

(defn- run-body-in!
  "Run one boundary body on `fid` under the real fence and answer what it
  read. `f` is handed a `read` fn, so a row states its read SEQUENCE
  rather than a shape — which is the surface the probe's per-run memo is
  about."
  [fid f]
  (let [!seen (volatile! [])]
    (collector/render-body
      fid
      (fn [_] (f (fn [query-v] (vswap! !seen conj (collector/sub query-v)))) [:p])
      {})
    @!seen))

(defn- run-body!
  "[[run-body-in!]] on the file's default frame."
  [f]
  (run-body-in! frame-id f))

(def ^:private this-ns
  "The namespace `reg-sub` attributes this file's registrations to, and
  therefore the one an image has to select to carry them."
  "re-frame.hicasso.cold-probe-cljs-test")

(defn- image-loaded!
  "A frame running an EXPLICIT image over `selected-ns`, seeded, and
  answered by id.

  The distinction from [[seeded!]] is the whole of §5 below. `make-frame`
  with no `:images` resolves the DEFAULT image over the live source
  store, which carries every registration there is — so a read through it
  answers whatever the registrar answers, and the resolution seam has
  nothing to do that could be seen. An explicit image SELECTS, and a
  selection is a thing a read can get wrong."
  [fid selected-ns]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (rf/make-frame {:id fid :images [(rf/image {:select-ns {:include [selected-ns]}})]})
  (frame/replace-app-db! fid {:v 7})
  (vreset! !runs 0)
  fid)

(defn- sub-cache-entry
  "The frame's own sub-cache slot for `query-v`, or nil. The probe
  promises not to create one; `subs/subscribe` is what does."
  [query-v]
  (get @(:sub-cache (frame/frame frame-id)) query-v))

(defn- errors-during
  "Run `thunk` with the always-on error listener attached; answer the
  records it emitted."
  [thunk]
  (let [!records (volatile! [])]
    (error-emit/register-error-listener! ::cold-probe (fn [r] (vswap! !records conj r)))
    (try (thunk)
         (finally (error-emit/unregister-error-listener! ::cold-probe)))
    @!records))

;; ---------------------------------------------------------------------------
;; 1. A cold read answers, and leaves the world as it found it
;; ---------------------------------------------------------------------------

(deftest a-cold-read-answers-correctly-and-retains-nothing
  (seeded!)
  (testing "the read answers the value the committed state holds"
    (is (= [7] (run-body! (fn [read] (read [:coldprobe/plain]))))))

  (testing "and nothing was built for it. No cell, so no reference, no
            reverse edge and no watch; no sub-cache slot, so no reaction
            and no ref-count. An abandoned render pays for its reads and
            keeps none of them"
    (is (nil? (get @collector/!cells (k [:coldprobe/plain]))))
    (is (nil? (sub-cache-entry [:coldprobe/plain])))
    (is (= [] (inventory/cell-readers (k [:coldprobe/plain]))))
    (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0}
           (dissoc (inventory/residue) :entries))))

  ;; THE CONTROL, and the reason the four nils above mean anything. Both
  ;; tables are perfectly capable of holding this key; what decides is
  ;; whether React committed the render.
  (let [entry   (do (collector/render-body
                      frame-id (fn [_] (collector/sub [:coldprobe/plain]) [:p]) {})
                    (collector/last-reads))
        release (collector/commit-boundary! entry (fn []))]
    (testing "committing the very same read fills both tables — so the
              emptiness above is the probe's promise, not an instrument
              that cannot see"
      (is (some? (get @collector/!cells (k [:coldprobe/plain]))))
      (is (some? (sub-cache-entry [:coldprobe/plain])))
      (is (= 1 (count (inventory/cell-readers (k [:coldprobe/plain]))))))
    (release)))

(deftest a-cold-read-answers-what-the-committed-path-answers
  (seeded!)
  ;; The equivalence the port staked commit-free Tier-1 reads on: the
  ;; probe's pure compute and the reactive build share the input grammar,
  ;; so a value must not depend on which rung answered it.
  (let [cold (first (run-body! (fn [read] (read [:coldprobe/plain]))))]
    (let [entry   (do (collector/render-body
                        frame-id (fn [_] (collector/sub [:coldprobe/plain]) [:p]) {})
                      (collector/last-reads))
          release (collector/commit-boundary! entry (fn []))
          warm    (first (run-body! (fn [read] (read [:coldprobe/plain]))))]
      (testing "the warm read really is warm — a committed cell now holds
                the key, so this second read is a pure deref and not a
                second cold one"
        (is (some? (get @collector/!cells (k [:coldprobe/plain])))))
      (is (= cold warm))
      (is (= 7 warm))
      (release))))

;; ---------------------------------------------------------------------------
;; 2. One memo, and it belongs to the run
;; ---------------------------------------------------------------------------

(deftest a-cold-key-computes-once-per-body-run-however-often-it-is-read
  (seeded!)
  (let [seen (run-body! (fn [read]
                          (read [:coldprobe/counted])
                          (read [:coldprobe/counted])
                          (read [:coldprobe/counted])))]

    (testing "three reads, three answers, and they agree"
      (is (= [7 7 7] seen)))

    (testing "and ONE compute — the run's own memo is what the second and
              third reads hit, so a body that reads a key in a loop pays
              for it once"
      (is (= 1 @!runs))))

  (testing "the edges the three reads recorded are one key, because the
            scratch's sub-keys are values: a repeated read is a repeated
            entry in the sequence and one member of the set"
    (is (= #{(k [:coldprobe/counted])}
           (collector/reads-of (collector/last-reads)))))

  ;; THE OTHER HALF, and it is the half a global memo would break. The box
  ;; is reset by every body run, so a LATER render computes again rather
  ;; than serving a value from a snapshot taken at some earlier commit.
  (let [before @!runs]
    (run-body! (fn [read] (read [:coldprobe/counted])))
    (testing "a second body run computes again: the memo is render-scoped,
              so it can never serve a stale snapshot to a later render"
      (is (= (inc before) @!runs)))))

;; ---------------------------------------------------------------------------
;; 3. The unregistered query — the recovery is memoised as a HIT
;; ---------------------------------------------------------------------------

(deftest an-unknown-query-recovers-to-nil-and-is-refused-once-per-run
  (seeded!)
  (let [records (errors-during
                  (fn []
                    (is (= [nil nil nil]
                           (run-body! (fn [read]
                                        (read [:coldprobe/never-registered])
                                        (read [:coldprobe/never-registered])
                                        (read [:coldprobe/never-registered])))))))
        misses  (filter (comp #{:rf.error/no-such-sub} :error) records)]

    (testing "the probe keeps the reactive path's contract for an id
              nobody registered: it emits, and it recovers to nil rather
              than throwing through a render"
      (is (= 1 (count misses))))

    (testing "and it emits ONCE for the run, not once per read. A memoised
              nil has to be a HIT — a lookup that treated `no value` and
              `the value nil` alike would re-emit and recompute on every
              read of every unregistered key a body touches"
      (is (= 1 (count misses)))))

  ;; The mirror: the emission is per RUN, so the next run emits again.
  (let [records (errors-during
                  (fn [] (run-body! (fn [read] (read [:coldprobe/never-registered])))))]
    (testing "a second run emits its own"
      (is (= 1 (count (filter (comp #{:rf.error/no-such-sub} :error) records)))))))

;; ---------------------------------------------------------------------------
;; 4. Rung 1 — a live sub-cache reaction is reused by deref alone
;; ---------------------------------------------------------------------------

(deftest a-live-sub-cache-reaction-is-reused-by-deref-alone
  (seeded!)
  ;; Rung 1 is for a key no COMMITTED CELL holds but which some other
  ;; holder keeps warm — a cell on another boundary mid-anything, a tool,
  ;; a test. The holder here is a test, and it holds the reaction exactly
  ;; as `wire-cell!` does: subscribe, ACTIVATE, settle. Activation is not
  ;; ceremony — a subscription under the ratom family learns its sources
  ;; only through a capturing deref, and an unactivated container
  ;; recomputes on every deref, which would make the count below say
  ;; nothing.
  (let [reaction (subs/subscribe [:coldprobe/counted] {:frame frame-id})]
    (interop/activate-derived-value! reaction)
    @reaction
    (vreset! !runs 0)

    (testing "the premise: the reaction is in the frame's sub-cache and no
              cell holds the key, so this read is cold by the runtime's
              own definition and rung 1 is the rung it must take"
      (is (some? (sub-cache-entry [:coldprobe/counted])))
      (is (nil? (get @collector/!cells (k [:coldprobe/counted])))))

    (testing "the read answers the reaction's own value"
      (is (= [7] (run-body! (fn [read] (read [:coldprobe/counted]))))))

    (testing "and computes NOTHING. The warm reaction was deref'd, not
              recomputed and not rebuilt — which is the whole of what
              rung 1 buys over the pure compute beneath it"
      (is (= 0 @!runs)))

    (subs/unsubscribe frame-id [:coldprobe/counted]))

  ;; THE CONTROL. Same key, same body, same value — with the warm reaction
  ;; released, so rung 1 has nothing to find and the read falls to the
  ;; pure compute. A zero that cannot become a one is not a measurement.
  (testing "the sub-cache slot went with the last holder"
    (is (nil? (sub-cache-entry [:coldprobe/counted]))))

  (vreset! !runs 0)
  (testing "and the same read now computes exactly once"
    (is (= [7] (run-body! (fn [read] (read [:coldprobe/counted])))))
    (is (= 1 @!runs))))

;; ---------------------------------------------------------------------------
;; 5. The resolution seam — an image-loaded frame's cold read resolves
;;    through THAT frame's image, and sees this tick's registrations
;; ---------------------------------------------------------------------------
;;
;; `cold-read!` computes inside `live-frame/call-with-frame-resolution`, and
;; that wrapper does two separable things: it BINDS the target's resolved image
;; generation for every registrar lookup inside, and it runs the read-time
;; coalesced reprojection flush first. Each has its own row here, because the
;; narrowing that reds one is green on the other.
;;
;; THE ROW THIS SECTION REPLACES, and why it is worth saying. A same-tick
;; registration row was written for the default-image frame above, found to red
;; NOTHING under mutation, and deleted as decorative. Half right. It was
;; decorative AS WRITTEN, because `make-frame` with no `:images` resolves the
;; DEFAULT image over the whole live source store — so a read through that
;; generation and a read around it answer the same, and neither half of the
;; wrapper can be seen doing anything. But the CLAIM was real, and deleting the
;; row left it witnessed only in the prototype runtime, which is precisely what
;; this bead exists to stop depending on. The fix is the CONDITION, not the
;; row: an image that SELECTS.

(deftest a-cold-read-resolves-through-the-frames-own-image-and-not-the-registrar
  ;; Registered live, so its provenance namespace is this file's — which is
  ;; the coordinate the two images below include and exclude.
  (rf/reg-sub :coldprobe/mine (fn [db _] (:v db)))

  (let [narrow  (image-loaded! ::narrow-image "re-frame.hicasso.todo-support")
        records (errors-during
                  (fn []
                    (is (= [nil] (run-body-in! narrow (fn [read] (read [:coldprobe/mine]))))
                        "a query this frame's image does not carry recovers to nil")))]

    (testing "and it recovers the way the reactive path does — by emitting,
              not by answering a plausible value. `:coldprobe/mine` is
              registered and the global registrar holds it; this frame's
              image selects another namespace, and the read resolved through
              the FRAME"
      (is (= 1 (count (filter (comp #{:rf.error/no-such-sub} :error) records)))))

    ;; THE CONTROL, and what makes the nil above a measurement rather than a
    ;; probe that cannot resolve anything. Same query, same body, same tick —
    ;; an image that DOES select this namespace.
    (let [wide (image-loaded! ::wide-image this-ns)]
      (testing "the same read on a frame whose image carries the namespace
                answers the value"
        (is (= [7] (run-body-in! wide (fn [read] (read [:coldprobe/mine])))))))))

(deftest a-same-tick-registration-is-visible-to-the-very-next-cold-read
  (rf/reg-sub :coldprobe/early (fn [db _] (:v db)))

  (let [fid (image-loaded! ::same-tick this-ns)]

    (testing "the premise: this frame's image carries the namespace, so a
              query registered BEFORE it was built reads through it"
      (is (= [7] (run-body-in! fid (fn [read] (read [:coldprobe/early]))))))

    ;; Registered AFTER the frame sealed its generation, read in the SAME
    ;; tick — the register-then-read-sync sequence every test, REPL session
    ;; and piece of setup code performs.
    (rf/reg-sub :coldprobe/late (fn [db _] (:v db)))

    (testing "and the very next cold read resolves it. The seam's read-time
              coalesced flush is what makes that true: the generation sealed
              at `make-frame` time does not carry this id, and a read that
              skipped the flush would compute against that stale projection
              and recover to nil"
      (is (= [7] (run-body-in! fid (fn [read] (read [:coldprobe/late]))))))

    (testing "the flush REPROJECTED rather than replaced — the frame is
              still the one built before the registration, still answering
              from its own seeded state, and still carrying what it carried"
      (is (= [7] (run-body-in! fid (fn [read] (read [:coldprobe/early]))))))))
