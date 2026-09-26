# Laser Excavator Changelog

## V.1.1

### New Features:

### Optimization:
- Refactored the "NextTagetSelection" step (only relevant for the server)
  - Biggest improvement for 100% Cooldown Reduction (so by default only tier 5 upgrade): around 60% lower server runtime
  - Other filter tiers: 8-10% lower server runtime
  - Excavators without filters: 3-5% lower server runtime

### Bug Fixes:

- Fixed an issue with the leaves and branches of DynamicTrees not being detected by the filters as blocks to be ignored.
