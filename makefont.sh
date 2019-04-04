#!/bin/sh

exec java \
  -jar target/com.io7m.unbolted_frontiers-0.0.2-main.jar \
  --input-directory src/main/flac \
  --input-percussion-directory src/main/flac_perc \
  --output-file unbolted-0.0.2.sf2

