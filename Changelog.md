# Laser Excavator Changelog

## V.1.1

### New Features:

### Optimization:
- Refactored the "NextTagetSelection" step (only relevant for the server)
  - This affects specifically excavators with filters, as "duplicate" checks are removed (8-10% less server runtime)
  - Also affects the excavators without filters (3-5% less server runtime)

### Bug Fixes:

- Fixed an issue with the leaves and branches of DynamicTrees not being detected by the filters as blocks to be ignored.
