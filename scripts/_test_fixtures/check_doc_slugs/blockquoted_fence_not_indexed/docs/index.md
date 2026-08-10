# Index — A Blockquoted Fence Mints No Fragment Target (rf2-1cpt)

python-markdown renders the block below as one `<code>` element, so neither the
`###` line nor the `<a id>` inside it becomes a fragment target on the built
site.  A scanner that reads the block as prose indexes both and then happily
validates links that resolve nowhere — the gate agreeing with itself instead of
with the renderer.

Both of these links are therefore broken and must be reported:
[the fenced heading](#not-a-real-heading) and
[the fenced anchor](#not-a-real-anchor).

> Captured from a run, quoted so it reads as evidence rather than instruction:
>
> ```bash
> ### Not a real heading
> <a id="not-a-real-anchor"></a>
> ```

## A real heading

And the link to [that one](#a-real-heading) must still resolve — the positive
control, so the count fails upward if the fence is still read as prose and
downward if blanking runs past the blockquote and eats the page.

> #### A blockquoted heading outside the fence

The blockquoted heading above is a real `<h4 id="a-blockquoted-heading-outside-the-fence">`
(rf2-869k9m), and [the link to it](#a-blockquoted-heading-outside-the-fence)
must keep resolving.
