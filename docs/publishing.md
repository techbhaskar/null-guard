> Release 1.1.1 is PUBLISHED in deployment
> `97ba9be7-6a6f-4c3c-aba0-9bee05cbfd30`. See [release details](releases/1.1.1.md).

# Publishing to Maven Central

Version `1.0.0` is published and its plugin was installed from Central using an
empty Maven cache on 2026-10-16. Versions 1.0.0, 1.0.1, 1.1.0 and 1.1.1 are published. The current reactor version is `1.1.1`. The `central-release` Maven profile attaches
sources and Javadoc, signs artifacts, uploads the reactor bundle, and waits for
Central to confirm publication.

The 1.1.1 status response on 2026-10-22 warned that Arinits exceeded its monthly
File Count publishing limit. Further publishing is blocked until the reported
reset on November 1, 2026; this does not change the confirmed `PUBLISHED` status
of 1.1.1. Check current Portal usage before preparing another upload.

## One-time prerequisites

1. The project uses the MIT license, declared in LICENSE and the parent POM.
2. Verify the `com.arinits` namespace in the Central Portal using ownership of
   `arinits.com`. NullGuard publishes under its child group `com.arinits.nullguard`.
3. Generate a Central Portal publishing token. Set `CENTRAL_TOKEN_USERNAME` and
   `CENTRAL_TOKEN_PASSWORD` locally, or configure server `central` in your private
   Maven settings. Never commit tokens.
4. Install GnuPG, import or create your release signing key, and publish its public
   key as described in Sonatype's GPG instructions. Keep the private key local.
5. Use JDK 17 or 21, as in the project's verification workflow.

## Release

The reactor is currently `com.arinits.nullguard:…:1.1.1`. For a future release,
update all reactor parent references:

```sh
mvn org.codehaus.mojo:versions-maven-plugin:2.19.1:set -DnewVersion=1.1.1 -DgenerateBackupPoms=false
```

Update README commands and scripts containing the old version, run the normal
verification workflow, and commit the release source before publishing.

```sh
mvn --batch-mode clean install
mvn --batch-mode -Pcentral-release -s "$HOME/.m2/nullguard-central-settings.xml" clean deploy
```

The second command publishes automatically after validation. Do not skip tests,
signing, or release checks when publishing. If using private `~/.m2/settings.xml`
with server `central`, omit `-s`. Unlock the signing key with your local GPG agent.
The profile deliberately rejects snapshot versions and snapshot dependencies.

For an unsigned local packaging check only (no upload):

```sh
mvn --batch-mode -Pcentral-release -Denforcer.skip=true -Dgpg.skip=true clean verify
```

Confirm a `PUBLISHED` deployment in the Portal and verify that the release POM and
JARs resolve from `https://repo.maven.apache.org/maven2/com/arinits/nullguard/` before
announcing availability. Never mark the README as published based only on upload.

References:
- https://central.sonatype.org/publish/publish-portal-maven/
- https://central.sonatype.org/publish/requirements/
- https://central.sonatype.org/publish/requirements/gpg/

## Local GPG signing

The release profile uses Maven GPG Plugin 3.2.8 with `bestPractices=true`.
Private keys stay in `~/.gnupg`; credentials stay in private Maven settings.
Do not put a signing passphrase in the POM or pass it on the command line.

Select your release key using its full public fingerprint:

```sh
export GPG_TTY=$(tty)
gpg --list-secret-keys --keyid-format LONG
```

Before a batch-mode build, unlock the key in GPG's local prompt by signing a
throwaway file. Replace `YOUR_FINGERPRINT` with your key fingerprint:

```sh
printf 'NullGuard signing check\n' > /tmp/nullguard-signing-check.txt
gpg --armor --detach-sign --local-user YOUR_FINGERPRINT --output /tmp/nullguard-signing-check.txt.asc /tmp/nullguard-signing-check.txt
gpg --verify /tmp/nullguard-signing-check.txt.asc /tmp/nullguard-signing-check.txt
mvn --batch-mode -Pcentral-release -Dgpg.keyname=YOUR_FINGERPRINT verify
```

This verifies artifact signing without uploading a release. Batch mode uses the
GPG agent's cached passphrase; repeat the unlock step if its cache expires.

Configured release signing identity: `Arinits <admin@arinits.com>`.
Public fingerprint: `440E91AFE2B4E7291DFBB4BCF03390620AF752E8`.
The public key is available on `keyserver.ubuntu.com`; it expires on 2028-10-14.
Private settings `~/.m2/nullguard-central-settings.xml` select this key through
`gpg.keyname`. On this Mac, GPG uses pinentry-mac to request the passphrase.
Interactive and batch Maven signing of the parent POM have both been verified.

## First publication attempt (2026-10-15)

Deployment `aa5cb636-09d5-479e-ba85-aa1cdcfb2d48` was rejected by Central.
The namespace `com.arinits.nullguard` was not allowed while the owning
`com.arinits` namespace was pending verification. Central also reported missing
module names and public-key lookup failures. Explicit module names have been
added and the public signing key was re-uploaded to Ubuntu's keyserver.
At the time of this failed attempt, no release had been published. Retries required that `com.arinits` shows Verified
in the account that owns the publishing token.

## Combined reactor and Gradle publication

Stage Maven artifacts without upload using `-Pcentral-release -Dcentral.skipUpload=true`.
Stage Gradle publications using `publishAllPublicationsToCentralStagingRepository`
with `-PcentralRelease` and your public `signing.gnupg.keyName` property. Both builds
use the local GPG agent. Combine the versioned artifact files from both staging
directories, excluding Maven metadata, verify signatures, generate checksums and
upload a single bundle through the Central Publisher API with automatic publishing.
The 1.0.1 release followed this combined process; no artifact was uploaded twice.

The checked-in `scripts/publish_central.py` verifies all detached signatures against
the established release fingerprint, checks the 13-component inventory, and builds
the combined bundle with checksums. `bundle`, `upload`, and `status` are separate
commands. Upload records an intent before sending and refuses to reuse an existing
state file, so an ambiguous network failure cannot silently create duplicate deployments.
Credentials are read from private Maven settings and never printed. Run its safety
tests with `python -m unittest discover -s scripts -p test_publish_central.py`.

## GitHub distribution

The [GitHub release page](https://github.com/techbhaskar/null-guard/releases/tag/v1.1.0)
contains the signed CLI download. GitHub Packages is a separate Maven registry
mirror; see [setup and authentication](github-packages.md).
