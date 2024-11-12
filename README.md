### AutoUpdateLib
Figured that if I absolutely had to add this, I may as well make it reusable. 
Makes some strict assumptions:
- `fabric.mod.json`
  - This mod is specified as a dependency
  - A public GitHub URL is specified in the `sources` tag of the `contact` section
  - The `version` string for the update release is contained in the GH tag name
- Criteria for auto-updating is solely whether mod version (as fetched from `fabric.mod.json`) != tag name
- The first jar found on the latest release will be used

**TO-DO**
- Allow user-configured endpoint for checksum matching
- Ease assumptions by allowing user-configured fields via annotation
- Remove Jackson deps because apparently the latest MC envs have that already
