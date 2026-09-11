#!/usr/bin/env bash
cd "$(dirname "$0")"
( cd ../pack && CW_NOFX=1 python3 build.py >/dev/null ) && CW_FX=false SCRIPT=script_hit.txt ./shots.sh > shots_nofx.log 2>&1
mkdir -p shots_nofx && rm -f shots_nofx/*.png && cp shots/k*.png shots_nofx/
( cd ../pack && CW_NOPOSE=1 python3 build.py >/dev/null ) && CW_FX=false SCRIPT=script_hit.txt ./shots.sh > shots_nopose.log 2>&1
mkdir -p shots_nopose && rm -f shots_nopose/*.png && cp shots/k*.png shots_nopose/
( cd ../pack && python3 build.py >/dev/null )
echo "nofx: $(ls shots_nofx | wc -l)  nopose: $(ls shots_nopose | wc -l)"
