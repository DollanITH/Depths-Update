# Variable Scope Fixes for Y=12-13 Liquid Flow Update

## Fixed Issues

### 1. `worldIn` Variable Scope Issues

**Problem**: `worldIn` variable was not available in all method contexts where it was needed.

**Fixes Applied**:
- Updated `depthsupdate$updateLiquidsInColumn` to accept `World worldIn` parameter
- Updated `depthsupdate$updateLiquidFlowStates` to accept `World worldIn` parameter  
- Updated `depthsupdate$updateFlowTransitionZone` to accept `World worldIn` parameter
- Updated all call sites to pass `worldIn` parameter

**Method Signatures Updated**:
```java
// Before
private void depthsupdate$updateLiquidsInColumn(int bx, int bz, ExtendedBlockStorage[] storageArrays, boolean hasSkyLight, int minY, HeightContext ctx)

// After  
private void depthsupdate$updateLiquidsInColumn(int bx, int bz, ExtendedBlockStorage[] storageArrays, boolean hasSkyLight, int minY, HeightContext ctx, World worldIn)
```

### 2. `ctx` Variable Scope Issues

**Problem**: `HeightContext ctx` was not available in method contexts where it was needed.

**Fixes Applied**:
- Updated `depthsupdate$hasLiquidBelow` to accept `HeightContext ctx` parameter
- Updated all call sites to pass `ctx` parameter
- Ensured all methods that need access to `ctx.toStorageIndex()` have it as parameter

### 3. `storageArrays` Variable Scope Issues

**Problem**: `ExtendedBlockStorage[] storageArrays` was not available in method contexts where it was needed.

**Fixes Applied**:
- Updated `depthsupdate$ensureFlowPath` to accept `ExtendedBlockStorage[] storageArrays` parameter
- Updated `depthsupdate$hasLiquidBelow` to accept `ExtendedBlockStorage[] storageArrays` parameter
- Updated all call sites to pass `storageArrays` parameter

### 4. Parameter Chain Propagation

The parameter flow now works correctly:
```
depthsupdate$fillBelowY0() → depthsupdate$processColumn() → depthsupdate$updateLiquidsInColumn() → depthsupdate$updateLiquidFlowStates() → depthsupdate$updateFlowTransitionZone() → depthsupdate$updateLiquidFlowState()
```

## Updated Method Calls

### Call Sites Fixed:
1. Line 212: `depthsupdate$updateLiquidsInColumn(bx, bz, storageArrays, hasSkyLight, minY, ctx, worldIn)`
2. Line 291: `depthsupdate$updateLiquidFlowStates(bx, bz, storageArrays, minY, ctx, worldIn)`
3. Line 440: `depthsupdate$hasLiquidBelow(bx, bz, y, section, targetBlock, ctx, storageArrays)`

### Method Signatures Fixed:
1. `updateLiquidsInColumn` - Added `World worldIn` parameter
2. `updateLiquidFlowStates` - Added `World worldIn` parameter  
3. `updateFlowTransitionZone` - Added `World worldIn` parameter
4. `hasLiquidBelow` - Added `HeightContext ctx` and `ExtendedBlockStorage[] storageArrays` parameters
5. `ensureFlowPath` - Added `ExtendedBlockStorage[] storageArrays` parameter

## Verification

All variable scope issues should now be resolved. The Y=12-13 liquid flow update system has access to all required variables:
- `worldIn` - For accessing world properties like sky light
- `ctx` - For height coordinate transformations
- `storageArrays` - For accessing chunk storage sections

The liquid flow update system should now work correctly for Y=12-13 transition zone processing.