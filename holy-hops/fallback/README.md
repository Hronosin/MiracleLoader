# Fallbacks

When `miracle bake` reports a hole for a target version, put the one method that must
differ there into `fallback/<version>/src/`, as a partial class with the same name.
See MiracleLoader's README, "Fallback functions".
