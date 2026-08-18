# Y=12-13 Liquid Flow State Fix

## Problem
The previous liquid update implementation only processed Y=0-11, replacing lava with air in that range. However, Y=12-13 liquid states were not updated, causing liquids to be unable to flow properly after world conversion. This created a critical issue where liquids would stop flowing at Y=11.

## Solution Implemented

### 1. Extended Processing Range
Modified `depthsupdate$replaceLavaBlocks` to process Y=0-13 instead of just Y=0-11:

```java
for (int by = startY; by <= 13; by++) {
    // Different behavior based on Y level
    if (by <= 11) {
        // Replace lava with air in Y=0-11
        depthsupdate$setBlockIf(...);
    } else {
        // Y=12-13: Update liquid states for proper flow
        depthsupdate$updateLiquidFlowState(bx, bz, storageArrays, by, hasSkyLight, targetBlock, ctx, worldIn);
    }
}
```

### 2. Liquid Flow State Updates
Added `depthsupdate$updateLiquidFlowState` method to properly handle Y=12-13:

- Creates ExtendedBlockStorage sections if they don't exist
- Calculates proper liquid states (source vs flowing)
- Ensures flow paths are available

### 3. State Calculation Logic
Added `depthsupdate$calculateProperLiquidState`:
- Checks for liquid below to determine if current block should be a source
- Converts between source and flowing states based on surroundings
- Handles both lava and water

### 4. Flow Path Assurance
Added `depthsupdate$ensureFlowPath`:
- Creates sections above as needed
- Ensures liquids can flow upwards properly
- Critical for maintaining liquid flow in extended worlds

### 5. Transition Zone Updates
Added `depthsupdate$updateFlowTransitionZone`:
- Specifically targets Y=12-13 as the critical transition zone
- Ensures proper flow from Y=11 to Y=14+
- Integrates with the main liquid update pipeline

## Key Improvements

1. **Fixed Liquid Flow**: Liquids can now flow properly from Y=11 through Y=12-13 into higher sections
2. **Proper State Management**: Y=12-13 blocks now have correct liquid states (source vs flowing)
3. **Flow Path Assurance**: Sections are created as needed to ensure liquid can flow
4. **Maintained Performance**: Batch processing and optimizations are preserved
5. **Extensible Design**: Framework supports future liquid flow enhancements

## Technical Details

- **Y=0-11**: Lava replaced with air
- **Y=12-13**: Liquid states updated for proper flow
- **State Logic**: Block is source if liquid below, flowing otherwise
- **Flow Paths**: Sections pre-created to ensure flow continues

## Verification Points

1. Lava should flow from Y=11 to Y=14+ normally
2. Y=12-13 should have proper liquid states
3. No liquid state corruption in transition zones
4. Performance impact should be minimal
5. Both source and flowing liquids should work correctly

## Future Enhancements

The framework supports:
- Water processing (currently preserved)
- Complex neighbor checking for realistic flow
- Evaporation based on height/biome
- Advanced flow simulation algorithms