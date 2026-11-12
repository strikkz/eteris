#!/bin/bash
# Downloads Car App Library sources and extracts public API signatures of the classes we need.
set -e
V=1.9.0-alpha03
mkdir -p /tmp/cal && cd /tmp/cal
curl -sSL -o src.jar "https://dl.google.com/android/maven2/androidx/car/app/app/$V/app-$V-sources.jar"
unzip -q -o src.jar -d src
OUT=$GITHUB_WORKSPACE/tools/carapp_api.txt
: > $OUT
for c in SectionedItemTemplate GridSection RowSection Section GridItem Row Item ChipSection Chip SpotlightSection CondensedSection CondensedItem Header Action ActionStrip CarIcon TabTemplate TabContents Tab MediaPlaybackTemplate MediaPlaybackManager CarContext Screen Session CarAppService ItemList GridTemplate ListTemplate OnClickListener CarText Badge ChipStyle; do
  f=$(find src -name "$c.java" | head -1)
  [ -z "$f" ] && { echo "### $c: NOT FOUND" >> $OUT; continue; }
  echo "### $c ($f)" >> $OUT
  grep -nE "^\s*(public|protected)\s|@RequiresCarApi|@ExperimentalCarApi|@CarProtocol|@KeepFields" "$f" | grep -vE "^\s*[0-9]+:\s*\*" | head -120 >> $OUT
done
ls src/androidx/car/app/model/ src/androidx/car/app/media/ 2>/dev/null >> $OUT
