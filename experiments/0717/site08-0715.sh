#!/bin/bash
ant clean-all build-all
ant hstore-prepare -Dproject="voterdemohstorewXsYY" -Dhosts="localhost:0:0"
ant hstore-prepare -Dproject="voterdemosstorewXsYY" -Dhosts="localhost:0:0"
python ./tools/autorunexp.py -p "voterdemohstorewXsYY" -o "experiments/0717/voterdemohstorewXsYY-1c-95-0717-site08.txt" --txnthreshold 0.95 -e "experiments/0717/site08-0717.txt" --winconfig "tuple ${REP} (site08)" --threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --numruns 1
python ./tools/autorunexp.py -p "voterdemosstorewXsYY" -o "experiments/0717/voterdemosstorewXsYY-1c-95-0717-site08.txt" --txnthreshold 0.95 -e "experiments/0717/site08-0717.txt" --winconfig "tuple ${REP} (site08)" --threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --numruns 1

BENCH=("winhstore" "winhstorenocleanup" "winhstorenostate" "winsstore")
NEWW=("100" "1000" "10000")
NEWS=("1" "5" "10" "100")
for w in "${NEWW[@]}"
do
for s in "${NEWS[@]}"
do
REP="w${w}s${s}"
ant hstore-prepare -Dproject="voterwinhstore${REP}" -Dhosts="localhost:0:0"
ant hstore-prepare -Dproject="voterwinsstore${REP}" -Dhosts="localhost:0:0"
python ./tools/autorunexp.py -p "voterwinhstore${REP}" -o "experiments/0717/voterwinhstore${REP}-1c-95-0717-site08.txt" --txnthreshold 0.95 -e "experiments/0717/site08-0717.txt" --winconfig "tuple ${REP} (site08)" --threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --numruns 1
python ./tools/autorunexp.py -p "voterwinsstore${REP}" -o "experiments/0717/voterwinsstore${REP}-1c-95-0717-site08.txt" --txnthreshold 0.95 -e "experiments/0717/site08-0717.txt" --winconfig "tuple ${REP} (site08)" --threads 1 --rmin 1000 --rstep 1000 --finalrstep 100 --warmup 10000 --numruns 1
done
done
