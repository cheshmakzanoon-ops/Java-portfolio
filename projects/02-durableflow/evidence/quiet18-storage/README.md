# Recovering the incomplete quiet18 study

Quiet18 failed after two complete workload runs. Their elapsed times were962.372049 and1611.414seconds; the third run was refused before reset because1,651,793,920free workspace bytes were below the unchanged2GiB admission reserve. These two rows do not establish a three-run reference, stability or a performance pass. The original measurement recorded its attempt, provider logs, receipts and command outcomes under `.work/A02/full-reference-20261018T095104-746011/`.

Only the two closed `target/performance/20261018T095149-10000-746947` and `target/performance/20261018T101046-10000-760867` directories were replaced with lossless archives. Each contained22files and two directories. All 3,912,701,887 original bytes were physically restored and independently verified before the originals were removed. [Independent recovery](independent-recovery.json), [preservation verification](preservation-verification.json) and [exact replacement receipt](replacement.json) retain that boundary. The initial archive mode-check failure remains under `/tmp/durableflow-quiet18-preservation-4wpma5tp/`; it changed no original.

Recorded archive location on the measurement machine: `.work/A02/archive/quiet18/20261018T105252896249Z/`.

| Original capture | Archive bytes | Archive SHA-256 | Complete manifest |
| --- | ---: | --- | --- |
| `20261018T095149-10000-746947` | 179775817 | `2890dc149177f59cfc6bd049b76b60db10c7e871b56f1e30baf03c5864a2240d` | [run 1](20261018T095149-10000-746947-manifest.json) |
| `20261018T101046-10000-760867` | 181009160 | `37a99f19534748febc7efbc56e9174fe67dd6f0a8827f251fc89efde75468b13` | [run 2](20261018T101046-10000-760867-manifest.json) |

The unchanged manifests record their initial temporary archive paths. Durable paths above and in the replacement receipt are the final storage locators. Archive binaries are ignored artifacts from the original measurement machine; a Git clone contains their manifests, not the recordings. Obtain and verify the archives separately before attempting restoration. Their availability is not established by these retained manifests. The original84ca full runner, scripts, preparation receipt/log and all eight exact runtime JARs are separately preserved under `/tmp/durableflow-gzip-original-20261018T104743547182Z/` with a complete hash manifest.

Verify the selected archive's exact SHA-256 first. Extract into a fresh empty directory; never overwrite an existing raw tree:

```bash
restore=$(mktemp -d /tmp/durableflow-quiet18-restore.XXXXXX)
tar --extract --gzip \
  --file .work/A02/archive/quiet18/20261018T105252896249Z/20261018T095149-10000-746947.tar.gz \
  --directory "$restore" --no-same-owner --same-permissions --keep-old-files
```

Check the complete file/directory set, each file's binary SHA-256/size and all modes against the manifest. Exact original modification times are also recorded. Only then place the restored capture root at its manifest's `source` path, provided that path is absent. Run2 uses its corresponding archive/manifest. Restoration never converts the failed attempt into acceptance: a new reference requires three fresh complete runs under one newly verified source identity.
