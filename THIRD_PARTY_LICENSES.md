# Third-party notices

ToyForge is MIT-licensed (see `LICENSE`). It contains or bundles the following third-party work.

## csg.js (ported)

`app/src/main/java/com/kidtoy/geometry/Csg.kt` is a Kotlin port of the BSP-tree CSG algorithm in
[csg.js](https://github.com/evanw/csg.js) by Evan Wallace. The port adds time budgets and other changes.

```
Copyright (c) 2011 Evan Wallace (http://madebyevan.com/)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```

## Lilita One font (bundled)

`app/src/main/assets/fonts/LilitaOne-Regular.ttf`, Copyright (c) 2011 Juan Montoreano, with Reserved Font Name
"Lilita". Licensed under the SIL Open Font License 1.1; the full text is in `app/src/main/assets/fonts/OFL.txt`.

## Written from public specifications (no third-party code)

- `text/TrueTypeFont.kt`: a TrueType `glyf`/`cmap` reader, written from the OpenType specification.
- `text/Text3D.kt`: ear-clipping triangulation, a textbook algorithm implemented from scratch.
- `io/BambuProject.kt`, `io/Exporters.kt`: 3MF writers, written from the 3MF Core specification and the
  documented Bambu/Orca project layout. No OrcaSlicer/Bambu Studio (AGPL) code or profiles are included;
  the slicer profiles used by `tools/slicetest` are generated locally by `flatten.py` from your own install and
  are git-ignored.

## Libraries (Gradle dependencies, not vendored)

AndroidX / Jetpack Compose, Kotlin coroutines and kotlinx.serialization, AndroidX Security Crypto: Apache License 2.0.
JUnit 4 (tests only): Eclipse Public License 1.0.
