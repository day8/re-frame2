(ns re-frame.ui.g13.measure
  "The G-13 dispatch-to-commit MEASUREMENT SEAM.

  One helper owns the measured interval — opening witness, start timestamp,
  flush of the supplied work thunk, end timestamp, closing witness — so
  containment is a property of THIS function rather than of the caller's
  layout. `timing-cycle!` supplies the collaborators and the work; it does not
  get to decide where the clock starts.

  The collaborators are passed in rather than closed over so the call ORDER
  can be asserted directly, with a fake clock and a fake flush, by
  `re-frame.ui.g13.measure-cljs-test`. That test is the whole of the lexical
  half of the proof; nothing reads source text.

  This namespace deliberately has NO requires. It is pure plumbing over four
  supplied functions, which is what makes it testable on the node runner
  without React, a frame, or a browser.")

(defn measure-dispatch-to-commit!
  "Measure one dispatch-to-commit interval. Returns a Promise of
  `{:elapsed-ms _ :pre-hot _ :post-hot _}`.

  Takes `{:keys [read-hot now flush! work!]}`:

    :read-hot  0-arity — the app-db `:hot` witness read
    :now       0-arity — the clock
    :flush!    1-arity — runs the supplied thunk and resolves on commit
    :work!     0-arity — the work under measurement (the timed dispatch)

  WHAT THIS PROVES, and how:

  1. The work runs INSIDE the flush, and the flush sits between the two clock
     reads. True by construction: `work!` is used in exactly one place — as
     the argument to `flush!` — and that call is the only thing between
     `started` and the end reading. A caller cannot place the work anywhere
     else and still have it measured, because the caller never sees the clock.

  2. Neither witness read is on the clock. `pre-hot` is read before `started`;
     `post-hot` is read after `elapsed-ms` is bound. So the reported span is
     the drain, not the drain plus two map lookups.

     Properties 1 and 2 are ORDER properties of this function's body. They are
     asserted directly in `measure-cljs-test` by passing a recording clock and
     a recording flush and comparing the resulting call log against the exact
     expected sequence — which also rejects an extra clock reading, since the
     log is compared whole.

  3. The measured interval actually CONTAINED its own dispatch and commit.
     This is the runtime half, checked by the gate as
     `post-hot - pre-hot = queued-writes` (`assertTimedIntervalDidWork` in
     `lib/g13-timing-evidence.cjs`), and it is why `read-hot` lives HERE
     rather than in the caller.

     Because the opening witness is read inside the seam — after anything the
     caller did before calling it — work hoisted OUT of `work!` is visible in
     the delta. A caller that dispatches before calling this function has
     already advanced app-db by the time `pre-hot` is read (`rf/dispatch-sync`
     lands the write epochs synchronously), so `pre-hot`
     and `post-hot` agree and the delta collapses to 0. Same for work deferred
     until after the returned Promise resolves: `post-hot` was read first.

     That is the one containment mutant a witness read in the CALLER cannot
     see — with the read outside the seam, hoisting the dispatch above the
     start timestamp leaves the delta at exactly `queued-writes` while the
     measured span no longer covers the write epochs. Owning the read closes
     it, which is what let the previous source-text proof retire (rf2-muhsq).
     Both directions are exercised in `measure-cljs-test`.

  What this does NOT prove: that the elapsed number is any particular size.
  G-13 has no wall-clock threshold; timing is evidence only."
  [{:keys [read-hot now flush! work!]}]
  (let [pre-hot (read-hot)
        started (now)]
    (-> (flush! work!)
        (.then (fn [_]
                 ;; End timestamp FIRST, so the closing witness read below
                 ;; cannot land inside the measured span.
                 (let [elapsed-ms (- (now) started)]
                   {:elapsed-ms elapsed-ms
                    :pre-hot    pre-hot
                    :post-hot   (read-hot)}))))))
