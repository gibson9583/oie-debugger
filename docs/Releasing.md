# Releasing OIE Debugger

1. Update the Maven revision, webadmin manifest, npm package and lockfile, and root `oie.json` to the same version. Add release notes under `docs/releases/<version>.md` and update installation documentation.
2. Run the source/package checks and tests described in [Testing](Testing.md). Commit release changes to `main` and push it.
3. Tag that commit as `v<version>` and push the tag. The Release workflow validates the tagged source, signs the tested package, and publishes the ZIP and `SHA256SUMS`. Existing releases and assets are never replaced.
4. After publication, the Catalog PR workflow uses the shared community catalog workflow to download the final signed ZIP, compute and validate its digest, and open a catalog PR. Review and merge that PR, wait for catalog index publication, and confirm the store offers the new version.

## Catalog automation setup

Create a fine-grained token restricted to `gibson9583/oie-community-catalog` with Contents and Pull requests read/write. Save it as the debugger repository's Actions secret `CATALOG_TOKEN`. Signing uses the existing SSL.com secrets independently.

Without `CATALOG_TOKEN`, the release still publishes and catalog submission is explicitly skipped with a workflow warning. Publish the catalog entry manually using the signed ZIP's actual SHA-256, or configure the token and run **Catalog PR** manually with the published tag. Do not rerun the entire Release workflow to submit an already-published release.

The reusable catalog workflow is pinned to a reviewed catalog commit. It reads identity, version, engine compatibility, UI support, artifact naming, and store documentation from the tagged `oie.json`. The store documentation is the tagged `docs/store.md`; the package's main documentation continues to link to the usage wiki.

Rerunning catalog submission refreshes an open version PR. Once the version manifest has merged, the shared workflow refuses to overwrite it. For a signing or release-upload failure, inspect the workflow and any draft release before retrying; do not replace a published artifact.
