# Laser Excavator Changelog

## V.1.1

### New Features:
- Solar Upgrade with 3 tiers, generates power if unobstructed and during the day
- An ingame settings menu allowing the player to change rendering settings
- A serverside config setting to allow a limit of active excavators per player (by default disabled)
- Added a whitelist to the filter settings

### Other Improvements:
- Updated parts of the UI to use custom (better fitting) buttons
- Reworked the filter UI to look cleaner

### Optimization:
- Refactored the "NextTagetSelection" step (only relevant for the server)
  - Biggest improvement for 100% Cooldown Reduction (so by default only tier 5 upgrade): around 60% lower server runtime
  - Other filter tiers: 8-10% lower server runtime
  - Excavators without filters: Maybe 3-5% lower server runtime (though hard to quantify)
- Refactored the delivery manager (especially the inventory manager) and internal fuel generator, both are now much more efficient (up to 75% less server time needed for these components)
- Also refactored the Loot Cache and Delivery Manager to reduce RAM usage of both
### Bug Fixes:

- Fixed an issue with the leaves and branches of DynamicTrees not being detected by the filters as blocks to be ignored.
- Fixed a potential memory leak on the client revolving the ingestion budget