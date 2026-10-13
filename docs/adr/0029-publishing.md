# Publishing under io.github.then00p.j2act until 1.0

j2act is published to Maven Central as `io.github.then00p.j2act`, its Gradle plugin is `id("io.github.then00p.j2act")` on the Gradle Plugin Portal and on Central, and the Vite integration is `@j2act/vite` on npm. Central only accepts a groupId under a namespace the publisher has verified, and a GitHub account verifies `io.github.<name>` by itself. `dev.j2act` would need the domain j2act.dev and a DNS record, which is not worth it before anyone runs j2act in production. A sub-group of the account's namespace keeps j2act apart from `io.github.then00p:exploded-hotswap-*`, and it makes the move at 1.0 a prefix swap: the artifactIds and Java packages (`j2act.*`, and `dev.j2act.gradle` and `dev.j2act.maven` in the plugins) stay. At 1.0, users change one groupId line and one plugin id, and relocation POMs at the old coordinates point Maven to the new ones.

**A tag releases everything.** Pushing `v0.1.0` runs `.github/workflows/release.yml`:
1. It checks the client runtime as CI does (ADR 0028) and sets the reactor's version from the tag.
2. `./mvnw -Prelease deploy -DautoPublish=true` uploads the Maven modules, and Central releases them once validated.
3. The Gradle plugin goes to Central and the Plugin Portal.
4. `@j2act/vite` goes to npm with provenance.
5. The run creates the GitHub release.

A version with a hyphen (`0.2.0-rc.1`) is a prerelease: npm's `next` tag and a GitHub prerelease. The repository itself stays on `0.1.0-SNAPSHOT`. `j2act-codegen` and the two demo apps are not published.

**Signing runs in-process.** Maven signs with maven-gpg-plugin's `bc` signer and the Gradle plugin with `signingInMemoryKey`, both reading the armored key and its passphrase from the environment. No `gpg` program or agent is involved, so a release cannot stop at a passphrase prompt, and CI and a local run sign the same way. Without `-Prelease` nothing is signed, and CI builds with `-Prelease -Dgpg.skip` so that the source and javadoc jars Central requires are built on every push.

The release needs these repository secrets: `CENTRAL_USERNAME` and `CENTRAL_PASSWORD` (a Central Portal user token), `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE` (the release key, published on keyserver.ubuntu.com and keys.openpgp.org), `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET`, and `NPM_TOKEN`. npm write tokens expire after 90 days at most. Once `@j2act/vite` exists on npm, trusted publishing from this workflow can replace the token.

A Plugin Portal plugin's first version waits for Gradle's manual review, usually a few days. Until it is approved, builds find the plugin on Central by adding `mavenCentral()` to `pluginManagement`.
