# ADR 001: one declared service-area depot in release 1

The snapshot declares one depot location shared by every vehicle. Each vehicle's loading and final return are to that declared location; it need not be location 0. Vehicle residual location is separate and can be any observed stop. A moving vehicle may deliver its own onboard cargo and cannot acquire new depot deliveries remotely. Urgent deliveries remain at the declared shared depot.

The contract describes depot-loaded delivery jobs and a standby vehicle at the depot; independent per-vehicle depot variability is outside this release's explicit acceptance gates. We expose and document the common-depot invariant instead of claiming a general multi-depot planner. The nonzero-depot regression validates both vehicles' return arcs and rejects a new delivery on a vehicle currently at a different location.

If a later release adds multiple loading depots, jobs will need explicit cargo origin as well as vehicle return/loading depots. Adding only a vehicle depot field without modeling actual cargo locations would be misleading.
