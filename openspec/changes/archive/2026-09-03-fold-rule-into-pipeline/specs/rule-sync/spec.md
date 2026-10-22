## REMOVED Requirements

### Requirement: Rule registry sync

**Reason**: The capability is renamed. ADAS has folded its `rule` registry into its `pipeline` registry as
the `ENRICH` kind and deleted `/v1/rules` in the same release, so the runner syncs pipelines of kind
`enrich`, not rules. The feed's path, its query filters and its response shape (an envelope rather than a
bare array) all change, and the cache is keyed on the pipeline name rather than a UUID.
**Migration**: Superseded by "Pipeline registry sync" in the `pipeline-sync` capability, which carries
every scenario this requirement had plus the `kind=enrich` filter, the envelope and the nested trigger.

### Requirement: Recurring task reconciliation

**Reason**: The capability is renamed, and the requirement's substance moves with it: the recurring task
instance is keyed by the pipeline name rather than a rule id, and the non-Class-1 lane's task name becomes
`pipeline-scan`.
**Migration**: Superseded by "Recurring task reconciliation" in the `pipeline-sync` capability. Behavior is
unchanged apart from the identity and the task name.

### Requirement: Rule admission gate

**Reason**: The capability is renamed. The gate's conditions are unchanged, but they are stated over a
pipeline and its resolved input, and the requirement now records that name-charset validation is ADAS's
and is deliberately not duplicated here.
**Migration**: Superseded by "Pipeline admission gate" in the `pipeline-sync` capability.
