# Deploying the connector library: Groovy scripts or a jar

**Date:** 2026-11-21
**Status:** Scripts are the default deployment. The jar remains a supported alternative.
**Evidence:** IDM 7.5.0 (`gcr.io/forgerock-io/idm:7.5.0`) in Docker, driven by `integration/` (plan WS8).

## Summary

The connector library (`connector/src/main/groovy`, about 40 classes) **can be deployed as Groovy scripts**, meaning sources that IDM compiles itself.

The jar was adopted in WS8 because of a compilation failure. That failure turned out to be a race in how the connector was configured, not a problem with source deployment. With the deployment order below, sources deploy as reliably as the jar:
- **Fresh starts:** clean on three fresh IDM starts.
- **Integration suite:** the full suite passes twice.
- **Scale:** a 300-user scale run passes.
- **Concurrency:** both concurrency stress tests are clean.

Source deployment does have four real costs, each with a mitigation:

1. **The first-initialisation race (both modes).**
   - The connector must not be initialised twice at once on its first use in a fresh IDM.
   - The rule that prevents it: generate the configuration with `createFullConfig`, *then* save the provisioner.
   - This applies to the jar as well; with the jar the symptom is just different.
2. **Compiler version.**
   - IDM compiles the sources with its own Groovy, 3.0.9, while the build uses 3.0.21.
   - `:connector:compileWithIdmGroovy` now compiles everything with 3.0.9 in CI.
3. **Compile time on each connector initialisation.**
   - It adds about 0.6–1.7 s per initialisation.
   - It is paid at IDM start and on configuration changes, never per operation.
4. **Layout and change control.**
   - The package directories must be kept as they are, and the files must be readable by IDM.
   - Sources on disk can be edited in place, so record the version deployed.

## What "deploying as scripts" means

The Scripted REST connector separates two kinds of Groovy:

| | Where | Configured by | Compiled |
|---|---|---|---|
| The seven **operation scripts** (`CreateScript.groovy`, …) | `connectors/entitlementsaccelerator/scripts` | `scriptRoots`, `*ScriptFileName` | Always by IDM, from source |
| The **library** (`au.com.azwork.idm.entitlementsaccelerator.*`) | `connectors/entitlementsaccelerator/lib` | `classpath` | **Scripts mode:** by IDM, from source (a directory on `classpath`). **Jar mode:** at build time (a jar on `classpath`) |

The question is only about the library. The operation scripts are always deployed as scripts. `./gradlew :connector:connectorScripts` builds the directory to copy into `<idm>/connectors/entitlementsaccelerator`:

```text
scripts/CreateScript.groovy … UpdateScript.groovy      (both modes)
lib/au/com/azwork/idm/entitlementsaccelerator/**/*.groovy          (scripts mode, the default)
lib/entitlementsaccelerator-connector-lib.jar                      (jar mode: -PlibraryAs=jar)
provisioner.openicf-entitlementsaccelerator.json
```

| Mode | `classpath` in the provisioner |
|---|---|
| Scripts | `["&{idm.instance.dir}/connectors/entitlementsaccelerator/lib"]` |
| Jar | `["&{idm.instance.dir}/connectors/entitlementsaccelerator/lib/entitlementsaccelerator-connector-lib.jar"]` |

A directory entry does not pick up jars inside it, so jar mode must name the file.

## The problems, one by one

### 1. The first-initialisation race (affects both modes)

**What happened.** WS8 deployed the provisioner over REST in the obvious order: `PUT config/provisioner.openicf/entitlementsaccelerator`, then `POST system?_action=createFullConfig` to generate the object types. The first `createFullConfig` on a fresh IDM failed with HTTP 500. The log showed:

```text
SEVERE: Failed to customize the connector
BUG! exception in phase 'semantic analysis' in source unit '…/scripts/CustomizerScript.groovy'
The lookup for au.com.azwork.idm.entitlementsaccelerator.ops.ClientCustomizer caused a failed compilation.
There should not have been any compilation from this call.
```

A second call worked. The error mentions compiling a library class from source, so the conclusion at the time was that sources are unreliable, and the library moved into a jar.

**What it actually is.** The `PUT` starts initialising the connector. A `createFullConfig` straight after it initialises a *second* instance while the first is still initialising. On the connector's first use in a fresh IDM JVM, the two initialisations race inside the connector bundle's class loader:
- **With sources:** they race over compiling the library, which produces Groovy's "BUG!" error above.
- **With the jar:** the same race shows as `java.lang.LinkageError: … attempted duplicate class definition for groovy.grape.GrabAnnotationTransformation`, in `ScriptedConfiguration.setCustomConfiguration`.

The jar did not fix the problem. The deployment order did, and it was changed in the same WS8 commit.

**Evidence** (each "fresh start" is a new IDM container):

| Order | Library | Result |
|---|---|---|
| `PUT`, then `createFullConfig` | jar | First call failed with `LinkageError`; second call worked |
| `PUT`, then `createFullConfig` | sources | 2 of 3 fresh starts failed (the "BUG!" compile error, plus `LinkageError`s in the log) |
| `createFullConfig`, then `PUT` of its result | jar | 2 of 2 fresh starts clean |
| `createFullConfig`, then `PUT` of its result | sources | **3 of 3 fresh starts clean** |

**When it can happen in production.** Only when two initialisations of the connector overlap on its first use in a freshly started IDM. Both of the following were tested and are safe in either mode:
- **Restarting IDM with the provisioner already in `conf/`** (the normal case after deployment). IDM initialises the connector once, during startup. 16 concurrent requests, sent the moment the connector registered, all succeeded on 3 boots with sources and 3 with the jar.
- **Re-initialising a connector that has already initialised once.** Five cycles of saving the provisioner while 15 Test calls and a `createFullConfig` ran at once: no compile errors and no `LinkageError` in either mode. There was one transient 404 in jar mode while the connector re-registered.

**Rule:**
- Generate the full provisioner with `createFullConfig` *before* saving it, then save the result once.
- Or place the full provisioner in `conf/` before IDM starts.
- Never save it and call `createFullConfig` straight away on an IDM whose connector has not yet initialised.

### 2. IDM compiles the library with its own Groovy (3.0.9)

IDM 7.5.0 ships Groovy 3.0.9. The build compiles with 3.0.21. In scripts mode, the code that runs is whatever 3.0.9 makes of the sources. A construct that 3.0.21 accepts and 3.0.9 does not, or one that 3.0.9 compiles differently, would show up only inside IDM.

**Mitigation:**
- `./gradlew :connector:compileWithIdmGroovy` compiles the library and all seven scripts with Groovy 3.0.9. It is part of `check` and a CI step.
- Today all 146 classes compile.
- Groovy 3.0.9 cannot read class files newer than Java 17 (*"Unsupported class file major version 65"* on JDK 21), so the task compiles on a JDK 17 toolchain, as IDM runs on JDK 17.0.10. CI runs on JDK 17 already. Locally, Gradle needs a JDK 17 installed, which it finds by itself.

The jar has the mirror-image risk: classes compiled by 3.0.21 running on IDM's 3.0.9 runtime. That works within the 3.0 line in practice, but no check covers it. In this respect scripts mode is arguably the safer of the two.

### 3. Compile time on every connector initialisation

In scripts mode, IDM compiles the library each time it initialises a connector configuration: at IDM start, after a provisioner change, and on each `createFullConfig`. It is not paid per operation.

Measured `createFullConfig` time on the same machine:

| | First initialisation in a fresh IDM | Later initialisations |
|---|---|---|
| Sources | 2.6 s | about 0.9 s |
| Jar | 0.9 s | about 0.26 s |

Operations cost the same once initialised: a 300-user reconciliation took the same time per user in both modes, and IDM's memory was the same. (The absolute figure from that run, 68 ms, was inflated by the test stand-in, H20; 50,000 users later reconciled at 3.4 ms per user, runbook §4.)

### 4. Layout, permissions and change control

- **Keep the package directories.** The sources must sit under `lib/au/com/azwork/idm/entitlementsaccelerator/…`, mirroring their packages. A flattened copy does not compile.
- **Keep the library out of `scriptRoots`, and the scripts out of `lib/`.** The scripts compile to the default package. The jar build excludes them for the same reason. Putting the library under `scriptRoots` was not tested.
- **Permissions.** The files must be readable by IDM's user. In the image that is uid 11111.
- **Change control.**
  - Sources on a server can be edited in place. That is handy for an emergency fix, but it lets servers drift from the release.
  - Deploy from the release build only.
  - Record the deployed version, for example with the `VERSION` file planned in WS7.01, which applies to both modes.
  - A jar is one artefact with a checksum, which is easier to verify.

## Problems found in WS8 that are not about packaging

These apply in both modes and are fixed regardless. Details are in `docs/service-contract.md` §4.1.
- **H10:** the provisioner's `objectTypes` must come from `createFullConfig`; IDM does not fill them in.
- **H12:** the two library transforms need `"source": ""`.
- **H13:** a managed delete raises `SOURCE_MISSING`.
- **H14:** IDM applies configuration asynchronously.

## Comparison

| | Scripts (default) | Jar |
|---|---|---|
| Works in IDM 7.5 | Yes, with the deployment order above | Yes, with the same order |
| First-initialisation race | Prevented by the order (symptom: Groovy "BUG!" compile error) | Prevented by the order (symptom: `LinkageError`) |
| Compiler | IDM's Groovy 3.0.9, checked in CI | Build's Groovy 3.0.21, run on 3.0.9 |
| Initialisation time | +0.6 to 1.7 s per initialisation | Lower |
| Readable on the server | Yes | No |
| Edit in place | Possible (a drift risk) | No |
| Artefact integrity | Many files; record the version | One file with a checksum |
| Tested in `integration/` | Default (`-PidmLibrary=sources`) | `-PidmLibrary=jar` |

## Recommendation

Deploy as scripts, as you prefer. Nothing in the evidence forces the jar.

The conditions for scripts mode:
1. Build with `./gradlew :connector:connectorScripts` and copy its output to `<idm>/connectors/entitlementsaccelerator` with the directory structure intact.
2. Generate the provisioner with `createFullConfig`, then save its result once, or drop it into `conf/` before IDM starts.
3. Keep `:connector:compileWithIdmGroovy` in CI, and re-check the Groovy version whenever IDM is upgraded. It is `idmGroovyVersion` in `gradle.properties`.
4. Treat the deployed sources as a release artefact: no in-place edits, and record the version.

Switch to the jar (`-PlibraryAs=jar`, and point `classpath` at the jar file) if any of these becomes true:
- initialisation time starts to matter;
- the operations team wants a single checksummed artefact;
- a future IDM version cannot compile the sources.

## Still unknown

- **Clusters:** behaviour on a clustered IDM, where each node compiles independently. Not tested, but no shared state is involved.
- **Frequent provisioner changes:** whether repeated configuration changes leak class-loader memory, since every initialisation compiles into a new class loader. This was not measured over long periods. It matters more for scripts mode, which compiles each time.
- **Recompile on change:** whether IDM picks up an edited source file without a provisioner change (the connector's recompile-on-change setting). Not tested, and not recommended for production.

## How to reproduce

```bash
./gradlew :integration:test -Pidm                       # library as scripts (default)
./gradlew :integration:test -Pidm -PidmLibrary=jar      # library as a jar
./gradlew :connector:compileWithIdmGroovy               # compile with IDM's Groovy 3.0.9
```

The nightly workflow (`.github/workflows/integration.yml`) runs the integration suite in both modes.
