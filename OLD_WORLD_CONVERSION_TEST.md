# Old World Conversion Verification Guide

## Overview
This document describes how to verify that the old world conversion feature is working correctly.

## Steps to Test

### 1. Create a Test World
- Create a new Minecraft world with vanilla settings (don't install Depths Update yet)
- Explore the world to generate some chunks
- Save and exit the game

### 2. Install Depths Update
- Install Depths Update mod on your Minecraft instance
- Configure the mod to enable old world conversion:
  ```
  convertOldWorlds = true
  ```
- Start Minecraft and load the world created in step 1

### 3. Verify Conversion
- Fly or teleport to coordinates below Y=0 (e.g., X=100, Y=-64, Z=100)
- Check that the area is filled with:
  - Stone/Deepslate from Y=minY to Y=fillMaxY
  - Proper deepslate transitions
- Check bedrock replacement:
  - Y=0 to Y=3 should be replaced with stone
  - Y=4 to Y=6 should be replaced with deepslate
- Check lava replacement:
  - Y=0 to Y=11 should have lava replaced with air (preserving caves)
  - Verify that cave structures are still intact
- Check existing structures above Y=0 to ensure they remain intact

### 4. Performance Check
- Load chunks below Y=0 and verify they generate smoothly
- Check for any lag or performance issues during chunk loading

## Expected Behavior

### When `convertOldWorlds = true`:
- Old worlds (Y=0-256) should be converted automatically on first load
- Areas below Y=0 should be filled with:
  - Bedrock at `minY` to `minY + random(5)`
  - Deepslate from `minY + 5` to `deepslateMaxY - transitionRange`
  - Transition zone with mixed stone/deepslate
  - Stone filling the rest
- Existing structures above Y=0 should remain unchanged

### When `convertOldWorlds = false`:
- No conversion should occur
- Old worlds should load normally with vanilla height

## Technical Details

The conversion is implemented in `MixinChunk.read()` method:
1. Detects old world chunks by checking for vanilla sections (Y=0-255)
2. When conversion is enabled, fills below Y=0 using the same logic as `MixinChunkProviderServer`
3. Maintains consistency with existing terrain generation
4. Regenerates heightmap and skylight after conversion

## Troubleshooting

If conversion doesn't work:
1. Check that `convertOldWorlds` is set to `true` in config
2. Ensure the world is configured for height extension in `extendedDimensions`
3. Look for any error messages in the console
4. Try creating a new world to test