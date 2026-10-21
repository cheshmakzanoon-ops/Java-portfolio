# Faultline corpus review

This review predates the corpus evaluation. It checked 120 matched source fixtures across 20 shapes in each SQL, shell, and path rule family, including source hashes and the 60-positive/60-negative balance.

The first revision contained guaranteed-null SQL receivers, shell tokens used as whole commands, and path transformations after confinement checks. Those sources and labels were preserved before corrected fixtures were evaluated.

The second revision's label SHA-256 is `d82a27da446265ee38a30f39265c2e71e93b4023a99849db621f0039483443da`. All 120 source hashes were verified. SQL fixtures receive supplied JDBC interfaces; shell fixtures combine a fixed echo command with a whitelisted token; path negatives validate the final transformed path at the sink. The review also covered wide values, concatenation, builders, missing dependencies, and matched direct flows.

Approval applies to the declared, context-specific taint model. Labels describe modeled flows, not proven exploits or field accuracy. Wrong-context sanitizer labels test model isolation even where lexical alphabets overlap. Path guards assume an owned, symlink-free root and do not prove general filesystem confinement. Missing dependencies remain conservative unknowns, and false positives remain in the results matrix. Analyzed targets must never execute; trusted fixture compilation uses `-proc:none`. Label review alone establishes no acceptance-gate pass.
