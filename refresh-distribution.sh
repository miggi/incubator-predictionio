#!/usr/bin/env bash

rm -r PredictionIO-*
rm -r ~/PredictionIO

CONF_FILE="/Users/miggi/work/predicty/conf/pio-env.miggi.sh"
PIO_DIR="~/distr/PredictionIO"
TARNAME="PredictionIO-0.12.0.tar.gz"
TARDIR="PredictionIO-0.12.0"

./make-distribution.sh
tar -zxvf ${TARNAME}
mkdir ${PIO_DIR}

cp -r ${TARDIR}/ ${PIO_DIR}
cp ${CONF_FILE}  ${PIO_DIR}/conf/pio-env.sh

echo -e "Starting PIO eventserver"
pio eventserver &