#!/bin/sh
# Named Volume（/b に読み取り専用でマウント）内の通常単体テスト結果（JUnit XML）を集計する。
find /b -path '*test-results/testDebugUnitTest/*.xml' -print0 \
  | xargs -0 grep -ho '<testsuite [^>]*>' \
  | awk '{
      n++
      if (match($0, /tests="[0-9]+"/))    t += substr($0, RSTART+7, RLENGTH-8)
      if (match($0, /skipped="[0-9]+"/))  s += substr($0, RSTART+9, RLENGTH-10)
      if (match($0, /failures="[0-9]+"/)) f += substr($0, RSTART+10, RLENGTH-11)
      if (match($0, /errors="[0-9]+"/))   e += substr($0, RSTART+8, RLENGTH-9)
    } END { printf "suites=%d tests=%d skipped=%d failures=%d errors=%d\n", n, t, s, f, e }'
