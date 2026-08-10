# Page

<a id="alias-one"></a>
<a id="dup-me"></a>
<a id="alias-two"></a>

## Dup Me

The corpus's real shape (rf2-1cpt): a STACK of alias anchors above the heading
they name, one of which duplicates the heading's own generated slug. Anchor
elements render empty, so the whole stack and the heading are one landing spot
— a co-location test that only tolerates blank lines between the occurrences
gets this wrong, and two spec pages are written exactly this way.

It still fails the unique-target check (rf2-zq5i6); co-location changes what the
diagnostic SAYS, not whether the gate fails. No markdown links here.
