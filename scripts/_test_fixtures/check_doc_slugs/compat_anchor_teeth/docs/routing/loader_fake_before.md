# Loaders

<!-- <a id="when-a-loader-fails"></a> -->

A backticked example anchor `<a id="when-a-loader-fails"></a>` also appears before
the passage, but inline code mints no fragment target either.

On loader failure, the transition moves to the error state.

<a id="when-a-loader-fails"></a>

## Declaring resources instead

The two anchors before the passage are non-rendered (one HTML-commented, one
inline code); the sole RENDERED `when-a-loader-fails` anchor sits AFTER the
loader-failure passage. Render-faithful placement must ignore both fakes and
fail (rf2-ehxs8). No markdown links here.
