# Building and releasing

Version 1.0.0 is unreleased. Pushing `main` runs validation; it does not publish a release. A `v*` tag starts the release workflow, so create a release tag only when publication is intended.

## Repository configuration

Configure these GitHub Actions **secrets** on this repository, using the same enrolled SSL.com eSigner account as the sibling plugins:

| Secret | Value |
| --- | --- |
| `SSL_COM_USERNAME` | SSL.com account username |
| `SSL_COM_PASSWORD` | SSL.com account password |
| `SSL_COM_CREDENTIAL_ID` | Enrolled code-signing credential ID |
| `SSL_COM_TOTP_SECRET` | Credential's TOTP secret, not a temporary one-time code |

Set the Actions **variable** `SSL_COM_CERT_SHA256` to the expected signing certificate's SHA-256 fingerprint. Obtain it from the enrolled certificate; the helper accepts hexadecimal with optional colons. Update this pin when renewing the certificate.

These values are not supplied by the repository files or inherited from another repository. The four secrets must be provisioned before the first signed release. The workflow fails before signing when any secret or a valid fingerprint is missing. An active eSigner enrollment and sufficient signing quota are also required; one release signs two JARs. Keep credentials and raw vendor logs out of issues, artifacts and source control.

Only the release job requests `contents: write`. Validation and signing checks need no signing credentials. Catalog publication is not configured and requires no catalog token.

## Validation and build inputs

The reusable [CI workflow](../.github/workflows/ci.yml) builds and tests the package using Java 17 and pinned OIE 4.6.0 distribution inputs. It uploads the unsigned ZIP as the `debugger-package` workflow artifact. The package includes its browser bundle; Maven installs the configured Node runtime and runs the frontend build and unit tests.

The [signing checks](../.github/workflows/signing-checks.yml) exercise CodeSignTool without credentials and run offline signing regression tests with temporary local certificates and a local timestamp service. They run on pull requests, `main`, and as a release prerequisite. No paid signature is requested by these checks.

The signing implementation is shared with the Web Support and OIDC plugins: CodeSignTool 1.3.2 is downloaded with a pinned SHA-256 digest, runs on JDK 11, and uses individual signing calls to honor certificate policy. JDK 17 performs strict trust verification and checks the expected signer, timestamps, and signed coverage of every payload entry. Tests reject missing signatures, altered payloads, unexpected output, partial signing, and unsafe archives. The ZIP is replaced atomically only after both JARs pass.

The debugger-specific [signing configuration](../.github/signing/config.json) selects only the shared and server debugger JARs. Browser files and the extension descriptor are preserved byte for byte; they are covered by the complete ZIP checksum, not by the JAR signatures.

## Publishing

1. Keep the Maven `revision`, browser package/lockfile versions, and browser plugin manifest in sync. Run `python3 scripts/verify-package.py` and complete CI. The package checker also validates the generated descriptor inside the ZIP. Engine compatibility (`4.6.0`) and the web plugin API requirement (`4.7`) are separate version constraints.
2. Confirm that signing secrets and the certificate pin are configured and that the release commit is the reviewed commit intended for publication.
3. Create and push the corresponding `v<version>` tag. For the initial release that is `v1.0.0`; do not create it merely to test CI. Tags containing a hyphen produce GitHub prereleases.
4. The [release workflow](../.github/workflows/release.yml) waits for CI and signing checks, downloads the exact CI ZIP, validates it, signs both JARs, validates the resulting ZIP again, and uploads it with `SHA256SUMS` to a draft. Only after both assets upload successfully does the final step publish the release. It never rebuilds after signing.
5. Check the successful workflow and release assets. Verify a downloaded ZIP with `sha256sum -c SHA256SUMS` from the directory containing both files. Retain the workflow run URL and tag commit with release records.

The unsigned CI artifact is a validation build. The signed ZIP attached to the GitHub Release is the distribution artifact. Do not replace it with a locally rebuilt ZIP or run Maven packaging over a signed bundle.

## Failed releases and retries

The workflow rejects an existing release for its tag, including a draft, before requesting signatures. Release jobs for the same tag run serially. Publication then uses a create-only draft request and retains the returned numeric release ID for both asset uploads and the final publication request. It never adopts a release by tag, overwrites an asset, or deletes a release. A concurrent release or an API failure cannot make it update another run's release. If creation conflicts or its response cannot be validated, the workflow stops.

Immediately before draft creation, the publisher resolves the tag and requires its commit to equal the workflow's `GITHUB_SHA`. A moved or deleted tag stops publication. The create request also specifies that commit, so a tag deleted after the check cannot fall back to the default branch. The release notes retain the validated source commit SHA. Repository tag rules are still needed to prevent tag changes after the check; this guard does not lock Git references.

If a run fails before a release exists, correct the external failure (for example missing secrets or an unavailable signing service) and rerun the failed jobs for the same commit. Signing attempts may consume quota even when a later step fails. Changes to workflow or source files require a newly reviewed commit; rerunning an old run uses its original workflow.

If draft creation, an asset upload, or publication fails, the workflow may leave an unpublished draft containing some or all assets. Inspect that release and the failed run first; the validated draft ID is printed when creation succeeds. Preserve any useful logs and artifact hashes. An owner may remove the unpublished draft and rerun the same release after confirming that it was never published. The workflow does not automatically delete drafts, assets, tags, or releases. If creation or the final publication request lost its response, inspect the repository's releases before considering any retry.

If a release was already published, preserve its tag and assets. Publish a corrected version under a new tag instead of deleting or replacing the old release. Do not move an existing release tag to a different commit.
