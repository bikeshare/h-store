#!/bin/bash
BENCH=("winhstore" "winhstorenocleanup" "winhstorenostate" "winsstore")
OLD="wXsYY"
NEW=("w100s1" "w100s5" "w100s100" "w1000s1" "w1000s5" "w1000s10" "w1000s100" "w10000s1" "w10000s5" "w10000s10" "w10000s100")
NEWW=("100" "1000" "10000")
NEWS=("1" "5" "10" "100")
TFILE="/tmp/out.tmp.$$"
for d in "${BENCH[@]}"
do
cd $d
for w in "${NEWW[@]}"
do
for s in "${NEWS[@]}"
do
REP="w${w}s${s}"
rm -rf $REP
cp -r $OLD $REP
mv "$REP/voter${d}${OLD}-ddl.sql" "$REP/voter${d}${REP}-ddl.sql"

DPATH="$REP/*"
for f in $DPATH
do
  if [ -f $f -a -r $f ]; then
   sed "s/$OLD/$REP/g" "$f" > $TFILE && mv $TFILE "$f"
  else
   echo "Error: Cannot read $f"
  fi
done
DPATH="$REP/procedures/*"
for f in $DPATH
do
  if [ -f $f -a -r $f ]; then
   sed "s/$OLD/$REP/g" "$f" > $TFILE && mv $TFILE "$f"
  else
   echo "Error: Cannot read $f"
  fi
done
sed "s/SLIDE_SIZE = 10/SLIDE_SIZE = $s/g" "$REP/VoterWinHStoreConstants.java" > $TFILE && mv $TFILE "$REP/VoterWinHStoreConstants.java"
sed "s/ROWS 100 SLIDE 10/ROWS $w SLIDE $s/g" "$REP/voterwinsstore${REP}-ddl.sql" > $TFILE && mv $TFILE "$REP/voterwinsstore${REP}-ddl.sql"
done
done
cd ..
done
