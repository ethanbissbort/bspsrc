# BSPSource — Repository Audit

**Date:** 2026-08-31 · **Commit audited:** `9e04aa3` · **Version:** 1.4.9-SNAPSHOT

> **Status update.** Most of the defects below have since been fixed. Everything in Tier 1 except
> finding 4 (`SourceAppDB` cannot answer "unknown"), most of Tier 2, and the bulk of the long tail
> are addressed; CI now runs on every push and pull request. The findings are left here as written
> so the reasoning and evidence stay on record. Still open, because each needs a decision rather
> than a patch: finding 4, the US-ASCII/Latin-1 output charset (16), cancellation (14), colliding
> output paths (15), the BSPInfo EDT refactor (17), the CLI `--visclusters` flag shape (9), and
> removal of the dead `app.unprotect` package.

---

## Verdict

The project is in **good structural health and poor output-verification health**. The build is clean, the
dependencies are current, the module boundaries are honest, and the security posture of the file-parsing
code is better than most projects that read untrusted binaries. Nothing here is rotten.

What is wrong is concentrated and consistent: **a set of small expression-level mistakes in the
decompiler's output path silently produce wrong VMF content, and nothing in the repository can catch
them.** No test asserts a single byte of emitted VMF, no test asserts a game-detection outcome, and CI
only runs on release tags. That combination is why a missing pair of parentheses has been emitting empty
`triangle_tags` for every displacement, and why the CS:GO detector cannot match `de_dust2`.

The fixes are mostly one-to-three lines each. The durable fix is one golden-file test and a CI trigger.

---

## What was verified, and how

Claims in this report are labelled by evidence strength:

- **[ran]** — proven by executing code (the shipped jar, the compiled classes, or an isolated reproduction).
- **[read]** — confirmed by reading the implementation and its callers.

Coverage: a full `mvn verify`; an end-to-end decompile of a committed BSP fixture; twelve parallel
review dimensions across all 277 main-source files, each dimension's findings put through an independent
skeptical re-check (97 raised, 88 survived); plus targeted manual verification of every finding promoted
to Tier 1 or Tier 2 below.

### Build and test health — clean

| Check | Result |
|---|---|
| `mvn -B verify` (JDK 25, `release 24`) | **BUILD SUCCESS**, all four modules |
| Test suite | **622 pass, 0 fail** (common 6, lib 528, decompiler 88) |
| Skipped | 18 — 17 tool-texture tests + 1 DStruct test, all silently assumption-aborted |
| End-to-end decompile of `l4d2_tooltextures.bsp` | Produces a valid 41 KB `.vmf` |
| Compiler warnings | ~40, incl. one `unchecked` in `BspInfoFrame.java` |

The default JDK on most distributions today is 21, which **cannot** build this project. That is correct
and documented, but worth knowing before filing a build issue.

---

## Tier 1 — silent output corruption and broken primary flows

These change what users get, without reporting an error.

### 1. `bspsrc <directory>` finds nothing, and still exits 0 **[ran]**

`bspsrc-app/.../cli/BspSourceCliCommand.java:292` walks with `Files.walk(path, recursive ? MAX_VALUE : 0)`.
`maxDepth 0` visits only the directory node itself, which is not a regular file, so **zero** maps match —
yet `--help` documents "One or more bsp files **or folders**".

```
$ bspsrc smoke/            # directory containing 3 .bsp files
05:50:51.204 ERROR No BSP file(s) specified
$ echo $?
0
```

Two defects in one command. The depth should be `1`. Separately,
`BspSourceCli.java:37` discards the value of `cmdLine.execute(args)` and never calls `System.exit`, so
**every** CLI failure — bad options, no input, all tasks failed — exits 0. Any script or CI job wrapping
bspsrc cannot detect failure.

### 2. Displacement `triangle_tags` is empty in every decompiled map **[ran]**

`bspsrc-decompiler/.../geom/FaceSource.java:739`:

```java
if (i % 2 * psize == 2 * psize - 1) {
```

Java gives `%` and `*` equal precedence, left-associative, so this is `(i % 2) * psize` — always `0` or
`psize`, while the right side is odd. The row is never flushed and the accumulated tag string is
discarded. Measured over the legal displacement power sizes:

```
psize=2   rows flushed as-written=0 ; with intended parens=2
psize=4   rows flushed as-written=0 ; with intended parens=4
psize=8   rows flushed as-written=0 ; with intended parens=8
psize=16  rows flushed as-written=0 ; with intended parens=16
```

Every decompiled displacement loses its walkable/buildable surface tags. The correct idiom already exists
60 lines above at `:680` (`i % (psize + 1) == psize`). Fix: `i % (2 * psize) == 2 * psize - 1`.

### 3. Game detection cannot match its own games' map names **[ran]**

`SourceApp.checkName` (`SourceApp.java:100`) runs `filePattern.find()` on the lowercased map name. Four
definition patterns cannot match any real map of their own game:

| Game | Pattern | `de_dust2` / `mp_angel_city` / `syn_arcade` / `ch_hospital` |
|---|---|---|
| Counter-Strike: Global Offensive | `^[de\|cs]_` | **false** |
| Titanfall | `^mp_$` | **false** |
| Synergy | `^syn_$` | **false** |
| Contagion | `^(ch\|ce\|cx)_$` | **false** |

Two separate authoring errors. `[de|cs]` is a *character class* — the set `{d,e,|,c,s}` matching one
character — where alternation `(de|cs)` was meant. And `$` anchors a pattern meant as a prefix, so it only
matches the bare prefix string. The critique pass identified nine such dead patterns in total.

This is not cosmetic. The app ID selects the D-struct subclass in roughly twelve lump loaders, the
multiblend lump identity, the winding coordinate size, and ladder emission.

### 4. A map with no distinguishing evidence is confidently identified as the wrong game **[read]**

`SourceAppDB.calculateAppScore` contributes `0` for a *matching* version and `-Infinity` for a mismatch;
entity and filename evidence only add non-negative amounts. So an app whose version range merely covers
the file scores exactly `0.0` with zero corroborating evidence, and the `score >= 0` filter at
`SourceAppDB.java:97` admits it. Ties resolve to whichever app sits first in the hardcoded list.

There is no "unknown" outcome and no confidence qualifier — `BspSource.java:210` prints `Game: <X>` either
way. For BSP version 20 this resolves to **Black Mesa**, the one app that redefines lump semantics
(`BspFileReader.loadDispMultiBlend` then reads 4-byte `DOverlaySystemLevel` records as 80-byte
`DDispMultiBlend` records).

### 5. VMF write errors are never detected **[read]**

`BspSource.java:233` writes through `new PrintWriter(vmfFile, US_ASCII)`. `PrintWriter` swallows every
`IOException`, and `checkError()` is called nowhere in the repository. On a full disk or an I/O error the
decompile reports success and leaves a **truncated** VMF. Relatedly, the file is created before
decompilation and left behind when `BspDecompiler.start()` throws.

### 6. Cubemaps bind on any single axis **[read]**

`TextureSource.java:150`, directly under the comment *"search for cubemap with these coordinates"*:

```java
if (cx == origin[0] || cy == origin[1] || cz == origin[2]) {
```

The first cubemap sharing *one* coordinate wins, so `env_cubemap` side lists are assigned to the wrong
cubemap whenever any other cubemap shares an x, y, or z. Should be `&&`.

### 7. Overlay fade distances written squared **[read]**

`EntitySource.java:687-689` writes `DOverlayFade.fadeDistMinSq` / `fadeDistMaxSq` — squared values, per
the struct definition and vbsp — straight into `fademindist` / `fademaxdist`. A 512-unit fade decompiles
as 262144. **Note for the fix:** Source uses `-1` as a "no fade" sentinel, so a bare `Math.sqrt` would
emit `NaN`; the sentinel needs a guard.

### 8. Wrong original face selected for brush sides **[read]**

`BrushSideFaceMapper.java:158-171` carefully sums clipped overlap area per candidate original face, then
selects the winner with `.max(Map.Entry.comparingByKey())` — the largest **face index**, not the largest
area it just computed. Should be `comparingByValue()`. Corrupts smoothing groups and overlay side mappings.

---

## Tier 2 — defaults, distribution, robustness

### 9. CLI enables `func_viscluster` by default; the library and GUI disable it **[read]**

`BspSourceConfig.java:46` has `writeVisClusters = false`. The GUI reads that default. But
`BspSourceCliCommand.java:234` computes `config.writeVisClusters = !entityOpts.noVisClusterEnts`, and
`noVisClusterEnts` defaults to `false` — so the CLI turns **on** a feature the GUI labels as inaccurate.
This is the same inverted-negative-flag family as the already-fixed `no-cubemap` / `no-tooltexfix` bug
(commit `bd781d6`), which suggests the pattern deserves a systematic check rather than another point fix.

### 10. The `bspsrc-jar-only.zip` distribution logs an error on every launch **[ran]**

`bspsrc-app/pom.xml:147` filters `**/Log4j2Plugins.dat` from artifact `*:*` — intended to stop dependency
plugin descriptors leaking in, but the wildcard also strips **the module's own generated descriptor**.

```
$ java -cp bspsrc-app-1.4.9-SNAPSHOT-shaded.jar ...BspSourceLauncher --help
main ERROR Console contains an invalid element or attribute "IsDecompileTaskFilter"
```

Verified precisely: the module jar contains `Log4j2Plugins.dat` (260 bytes), the shaded jar does not, and
the jlink runtime image — shipped as the linux/windows zips — starts **clean**. So this affects the
jar-only distribution only. Beyond the error line, the `IsDecompileTaskFilter` never installs, so the
console filtering it provides is inert. Fix: scope the exclude to the dependency coordinates, or use
log4j's shade transformer to merge the `.dat` files.

### 11. CI never runs tests on ordinary commits or pull requests **[read]**

`.github/workflows/application-packaging.yml` triggers only on `workflow_dispatch` and `push: tags: v*`.
Everything merged to `master` is untested until someone cuts a release. Given that the Tier 1 defects are
exactly the kind a golden-file test would catch, this is the highest-leverage process fix in the report.

### 12. Pakfile extraction aborts on archives containing directory entries **[ran]**

`PakFile.extract` (`PakFile.java:140-144`) does `Files.createDirectories(path.getParent())` then
`Files.copy(stream, path)`, and `unpack` never checks `ZipArchiveEntry.isDirectory()`. A directory entry
`materials/` is therefore written as a zero-byte *file*, and the next entry inside it fails:

```
after dir entry -> isRegularFile=true size=0
SECOND ENTRY THREW: java.nio.file.FileAlreadyExistsException: pakout/materials
-> propagates out of unpack(), aborting remaining extraction
```

The exception escapes `unpack()`, so extraction stops for the rest of the archive. Triggers on any pakfile
that stores explicit directory entries (common in repacked and workshop maps).

### 13. Failed LZMA decompression still marks the lump uncompressed **[read]**

`AbstractLump.java:118-130` catches the `IOException`, logs it, then runs `setCompressed(false)`
*outside* the try. The buffer still holds raw LZMA bytes while the lump claims to be decompressed, and for
`Lump` this also zeroes the recorded uncompressed size. In practice the downstream
`catch (Exception) → defaultData()` usually converts this into an *empty* lump rather than parsed garbage —
which is its own problem (see Theme A).

### 14. Cancel does not stop a decompile **[read]**

`BspSource.java:93` — `ExecutorService.close()` waits for every in-flight task, and no decompiler code
checks `Thread.interrupted()`. Cancelling a large batch keeps working until it finishes.

### 15. Colliding output paths are written concurrently **[read]**

Two inputs that derive the same output VMF (`a/de_dust.bsp` and `b/de_dust.bsp` with `-o out/`; or the same
file added twice in the GUI, which does not de-duplicate) are submitted to the work-stealing pool as
separate tasks. Two threads then open `PrintWriter`s on one path and interleave into a corrupt VMF.

### 16. Character encoding mismatch **[read]**

The entity lump is decoded as Latin-1 but the VMF is written as US-ASCII (`BspSource.java:233`), so every
non-ASCII character in a targetname or comment becomes `?`.

### 17. Swing threading in BSPInfo **[read]**

`BspInfoFrame.java:137+` runs whole-file CRC (`Files.readAllBytes`), protection scanning and pakfile
enumeration synchronously on the Event Dispatch Thread, freezing the window on large maps. The newer
decompiler GUI uses `SwingWorker` correctly, so the pattern is known. Separately,
`DocumentAppender.java:41` mutates a Swing `Document` from decompile-pool threads with no
`invokeLater` — while its sibling `DialogAppender.java:39` does it correctly.

### 18. Visgroup colors are written red-blue-green **[read]**

`VmfMeta.java:221-224` emits `getRed(), getBlue(), getGreen()` into an `R G B` field.

---

## Structural themes

The individual findings cluster into four root causes worth fixing as designs rather than as patches.

**A. There is no "this lump is untrustworthy" state.** `BspFileReader.readAbstractLump` catches
`Exception` and substitutes `defaultData()`, so *absent*, *empty* and *corrupt* are the same value
downstream — `bspData.texnames.isEmpty()` cannot distinguish a texture-less map from a destroyed lump. One
out-of-range texdata index discards every texture name in the map; a blanked entity lump feeds app
detection zero evidence, which (per finding 4) returns a confidently wrong game, which changes struct
dispatch.

**B. The recovery design and the parsing primitives disagree.** `BspFile` is full of careful
warn-and-continue blocks written in terms of `BspException` / `IOException`. But the primitives it actually
calls fail with *unchecked* exceptions — `ByteBuffer.slice`, `ByteBuffer.limit`, `new ArrayList<>(n)`,
`List.set`. So the recovery is decorative: an `int` overflow in the lump-length check
(`BspFile.java:331`, `ofs + len` overflows past the clamp), an off-by-one accepting lump index 64
(`LumpFile.java:81`), and an unvalidated file-supplied count used as an `ArrayList` capacity
(`DataReaderUtil.java:31`) all bypass it. This is one fix — validate before calling, or wrap the
primitives — not six.

**C. The published library surface is largely unexercised.** `bspsrc-lib` and `bspsrc-decompiler` are
consumed via JitPack, but only the single path the GUI and CLI walk is ever run. Outside it:
`BrushSource.writeModel(int)` calls itself instead of its 3-arg overload (instant `StackOverflowError` for
any caller), `Collectors.mode()`'s combiner discards one partial map and double-counts the other, and
`EntityIO` reads `timesToFire` from the delay field. "No in-repo caller" is being used as a severity
discount when for a published library it is the finding.

**D. `bspsrc-common` — the module documented as reusable zero-dependency utilities — has the highest defect
density and the lowest coverage.** Eight classes, one tested. Confirmed defects in three:
`CountingInputStream` (its bulk `read(byte[], int, int)` calls the *single-byte* delegate, never fills the
array, and returns the byte's **value** as the count — so `transferTo`/`readAllBytes`/`Files.copy` silently
corrupt; and `getBytesRead()` sums byte values rather than counting), `Collectors.mode()`, and
`AlphanumComparator` returning 0 for unequal strings, which violates the `Comparator` contract.

---

## Test coverage

8 test files / ~2.4k LOC against 277 main files / ~26.4k LOC.

**Genuinely well tested:** the vector classes (property-based, via jqwik), `AlphanumComparator`, and
D-struct round-tripping (219 reflective cases).

**No coverage at all:** `BspFile` / `BspFileReader` parsing, `PakFile`, the `Winding` geometry, the app
detection database, and **the entire `bspsrc-app` module** (57 files, ~5000 lines, including all CLI
wiring — which is where findings 1 and 9 live).

Two gaps are worse than they look:

- The 219 `DynamicDStructTests` assert **byte counts against an all-zero stream**. Swapping two same-width
  fields in a struct keeps all 622 tests green while silently corrupting every affected entity.
- 17 of 88 tool-texture tests abort via `Assumptions`, so the sky/trigger/climb matcher rules are never
  actually asserted in CI. They register as passing.

`test/maps/` holds 18 hand-authored Hammer VMFs (7.1 MB), including six purpose-built anti-decompile
cases. Nothing references them — no test, no POM, no workflow, no documentation. This is pre-built
regression material sitting unused, and it is the natural raw material for the golden-file test this
project most needs.

---

## Security posture — good

Audited against the realistic threat: a hostile `.bsp` opened by a user.

| Area | Result |
|---|---|
| Archive path traversal (zip-slip) | **Properly guarded** — `PakFile.java:89-103` normalizes, checks `startsWith(dest)`, and refuses to overwrite |
| XML / XXE | **Not present** — no XML parsing of untrusted input |
| Java deserialization | **Not present** — no `ObjectInputStream` |
| Process execution | **Not present** in main sources (one test helper invokes vbsp) |
| Network I/O | **Not present** — no update check, no telemetry |
| `Desktop.browse` | Safe — only a Steam URL built from an `int` app ID, guarded against `<= 0` |
| Log4j | 2.25.4, current; plain `PatternLayout` console appenders, no lookups |
| Secrets in tree | None found across `*.java`, `*.xml`, `*.yml`, `*.py`, `*.properties` |
| Temp files | Not used |

Two low-severity notes. `BspFileUtils.java:82-83` builds an extraction path from
`destDir.resolve(lump.getName())`, where a game lump's name is four characters taken from the file's
fourCC — unlike `PakFile`, this path has no `normalize()`/`startsWith` guard. Worldspawn lump extraction
uses enum names and is safe. And decompression is unbounded (no size ceiling on LZMA/zip output), which is
a disk-fill nuisance for a local desktop tool rather than a meaningful risk.

**Supply chain:** dependencies resolve over HTTPS and are current. `com.github.rihi:ioutils` comes from
JitPack pinned to commit `8e2a0c1a` — an immutable ref, which is the right call, though JitPack builds
are not independently verifiable and there is no checksum pinning. GitHub Actions are pinned by **major
tag** (`@v5`, `@v6`, `@v7`) rather than commit SHA, so a compromised tag would flow straight in; the
workflow also declares no `permissions:` block, defaulting to whatever the repository grants. The trigger
set is safe — tags and manual dispatch only, so no untrusted pull-request code executes.

---

## Repository hygiene

- **Version drift:** `BspSource.java:61` hardcodes `VERSION = "1.4.9-DEV"` while the POM says
  `1.4.9-SNAPSHOT`. It is stamped into every VMF header and printed by `--version`. Two sources of truth,
  hand-synced at each release.
- **Undeclared dependency:** `bspsrc-app` uses `bspsrc-lib` in 10 files and `requires` it in
  `module-info.java`, but declares no Maven dependency on it — it works only transitively through
  `bspsrc-decompiler`, which does not use `requires transitive`.
- **Dead code:** the `app.unprotect` package (`BspUnprotect` + a 280-line `IceKey` cipher, ~450 LOC) has
  zero references and no launcher wiring, yet ships in the jar. `ConvexVolume` lives in decompiler main
  sources but is used only by tests. Twelve debug `main()` methods in GUI panel classes ship in the jar.
- **Unreferenced assets:** `icons/` (~2.5 MB, including apparently superseded `v2` variants) is referenced
  by nothing; the icons actually used are duplicated into module resources. `test/maps/test_brushes.vmf`
  alone is 6.7 MB — the single largest file in the repository.
- **Vestigial config:** `.gitattributes` declares `*.form text` though no `.form` files remain;
  `.gitignore` still carries a full NetBeans block; `.mvn/` contains only `.gitkeep`, with no Maven
  wrapper, so contributors must supply Maven and JDK 24+ themselves.
- **Launcher quoting:** `.github/scripts/create_launcher.py` emits `$*` unquoted, so
  `./bspsrc.sh "my map.bsp"` splits on the space. Should be `"$@"`. (Windows `%*` is fine.)
- **Test artifact not ignored:** running the suite drops an untracked `bspsrc-common/.jqwik-database`.
- **User-facing text:** `--brushmode` help prints `null` four times instead of the mode names **[ran]**, so
  the valid values are undiscoverable; `--appid` help tells users to run `-appids`, which the parser
  rejects; a GUI checkbox is labelled `info_cubemap`, an entity that does not exist; "seperated" in
  `--list` help.
- **Cosmetic but visible:** `BrushSideFaceMapper.java:113` divides 0 by 0 when a map has no displacement
  faces, printing `0 (NaN%) disp original faces left` — observed in a normal decompile.
- **Missing community files:** no `CONTRIBUTING`, `SECURITY.md`, `CHANGELOG`, issue templates, or
  `CODEOWNERS`. `LICENSE.md` (Unlicense) matches the POM.

---

## Recommended order of work

1. **Add a CI trigger on push and pull request.** Everything below is a regression risk without it.
2. **Add one golden-file test.** Decompile a committed fixture, diff the VMF against a checked-in
   expectation. `test/maps/` already contains the material. This is the single change that converts this
   class of bug from invisible to impossible.
3. **Fix the Tier 1 output bugs** — items 1-8. All are one to three lines. Start with the parentheses at
   `FaceSource.java:739`, the walk depth at `BspSourceCliCommand.java:292`, the exit code at
   `BspSourceCli.java:37`, and the four detection regexes.
4. **Give lump loading a "corrupt" state** distinct from "empty" (Theme A), and make app detection able to
   answer "unknown" (finding 4).
5. **Fix the shaded-jar log4j filter** so the jar-only download stops erroring on launch.
6. **Validate before parsing** rather than catching unchecked exceptions after the fact (Theme B).
7. Work the long tail: `bspsrc-common`'s three broken utilities, the Swing threading in BSPInfo, the dead
   `app.unprotect` package, and the help-text errors.

---

## Appendix: raw review output

The twelve-dimension review pass raised 97 findings and confirmed 88 after independent re-checking
(1 high, 34 medium, 53 low). This report promotes the ones with user-visible consequences and groups the
remainder into the four structural themes. The long tail — roughly 50 low-severity items covering
`CountingInputStream`, `BspFile` bounds handling, `EntitySource` indexing, dead code, and help-text
errors — is real but individually minor, and is best worked after the CI and golden-file changes above
make regressions visible.
